/**
 * dsh-lan-pair  (P1 spike)
 *
 * Why this exists:
 *   `dsh web` binds loopback only and refuses 0.0.0.0 by design, so a phone on
 *   the LAN cannot reach the GUI. This plugin opens a second listener on the
 *   LAN and reverse-proxies to the loopback server, injecting the session
 *   cookie that it obtains in-process via `connection.authenticatedUrl()`.
 *
 *   The phone therefore never receives the DSH launch token: the plugin holds
 *   the cookie itself.
 *
 * Auth (deliberately minimal):
 *   GET /__pair?k=<secret>  -> sets a `dshpair` cookie and redirects to /
 *   every other request needs that cookie. Secret comes from the
 *   DSH_LAN_PAIR_SECRET environment variable, or a fixed default.
 *
 * Not done yet (later phases): mDNS advertisement, device list, TLS,
 * firewall automation, pairing window/expiry.
 */

import http from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";
import { advertise } from "./mdns.js";

/** This package's version, read once so the mDNS TXT record can advertise it. */
const VERSION = (() => {
    try {
        const here = path.dirname(fileURLToPath(import.meta.url));
        return JSON.parse(fs.readFileSync(path.join(here, "..", "package.json"), "utf8")).version ?? "0.0.0";
    } catch {
        return "0.0.0";
    }
})();

/** Cordis plugin name used by loader diagnostics. */
export const name = "dsh-lan-pair";

/** Services required before `apply` runs. */
export const inject = ["connection", "webServer"];

/**
 * Resolve the pairing secret.
 *
 * Order: DSH_LAN_PAIR_SECRET, then a secret persisted in $DSH_HOME/lan-pair.json,
 * then a freshly generated one written to that file. The shared literal is never
 * used, so two machines never share a secret by accident.
 * @returns the secret string.
 */
function loadSecret() {
    if (process.env.DSH_LAN_PAIR_SECRET) return process.env.DSH_LAN_PAIR_SECRET;
    const home = process.env.DSH_HOME || path.join(os.homedir(), ".dsh");
    const file = path.join(home, "lan-pair.json");
    try {
        const saved = JSON.parse(fs.readFileSync(file, "utf8"));
        if (typeof saved.secret === "string" && saved.secret.length >= 16) return saved.secret;
    } catch {
        /* first run, or unreadable: fall through and create one */
    }
    const created = crypto.randomBytes(12).toString("base64url");
    try {
        fs.mkdirSync(home, { recursive: true });
        fs.writeFileSync(file, `${JSON.stringify({ secret: created, createdAt: new Date().toISOString() }, null, 2)}\n`);
    } catch {
        /* a read-only home still works for this session, just not persistently */
    }
    return created;
}

const SECRET = loadSecret();
const LAN_PORT = Number(process.env.DSH_LAN_PAIR_PORT || "8099");
const COOKIE_NAME = "dshpair";

/** Report to stdout (so launchers that capture output show it) and to the host log. */
function log(ctx, message) {
    console.log(`[dsh-lan-pair] ${message}`);
    try {
        if (ctx && typeof ctx.logger === "function") ctx.logger("dsh-lan-pair").info(message);
    } catch {
        /* stdout already carries it */
    }
}

/**
 * Perform the token exchange against the loopback server and return the
 * `name=value` cookie the GUI would have received.
 * @param tokenUrl - URL carrying the process launch token.
 * @returns the first Set-Cookie pair, without attributes.
 */
function acquireCookie(tokenUrl) {
    return new Promise((resolve, reject) => {
        const req = http.request(tokenUrl, { method: "GET" }, (res) => {
            const setCookie = res.headers["set-cookie"];
            res.resume();
            if (Array.isArray(setCookie) && setCookie.length > 0) {
                resolve(setCookie[0].split(";")[0]);
                return;
            }
            reject(new Error(`no session cookie (status ${res.statusCode})`));
        });
        req.on("error", reject);
        req.setTimeout(8000, () => req.destroy(new Error("token exchange timed out")));
        req.end();
    });
}

/** Parse one Cookie request header into a plain map. */
function parseCookies(headerValue) {
    const out = {};
    if (typeof headerValue !== "string") return out;
    for (const segment of headerValue.split(";")) {
        const at = segment.indexOf("=");
        if (at <= 0) continue;
        out[segment.slice(0, at).trim()] = segment.slice(at + 1).trim();
    }
    return out;
}

export function apply(ctx) {
    const upstreamPort = ctx.webServer.port;
    const upstreamHost = "127.0.0.1";
    const authority = `${upstreamHost}:${upstreamPort}`;

    /** Cached upstream cookie; refreshed whenever upstream answers 401. */
    let cookie = null;

    async function ensureCookie(force) {
        if (cookie !== null && !force) return cookie;
        const tokenUrl = ctx.connection.authenticatedUrl(`http://${authority}/`);
        cookie = await acquireCookie(tokenUrl);
        return cookie;
    }

    const authorized = (req) => parseCookies(req.headers.cookie)[COOKIE_NAME] === SECRET;

    function reject(res, message) {
        res.writeHead(401, { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" });
        res.end(message);
    }

    const handleRequest = async (req, res) => {
        const url = new URL(req.url ?? "/", "http://lan.invalid");

        // pairing handshake: /__pair?k=<secret>
        if (url.pathname === "/__pair") {
            if (url.searchParams.get("k") !== SECRET) {
                reject(res, "dsh-lan-pair: bad pairing secret\n");
                return;
            }
            res.writeHead(303, {
                "set-cookie": `${COOKIE_NAME}=${SECRET}; Path=/; HttpOnly; SameSite=Lax; Max-Age=31536000`,
                location: "./",
                "cache-control": "no-store",
            });
            res.end();
            return;
        }

        if (!authorized(req)) {
            reject(res, "dsh-lan-pair: not paired. open /__pair?k=<secret>\n");
            return;
        }

        let upstreamCookie;
        try {
            upstreamCookie = await ensureCookie(false);
        } catch (error) {
            res.writeHead(502, { "content-type": "text/plain; charset=utf-8" });
            res.end(`dsh-lan-pair: cannot reach the local DSH web server (${error.message})\n`);
            return;
        }

        const forward = () => {
            const headers = { ...req.headers, host: authority, cookie: upstreamCookie };
            delete headers["accept-encoding"];
            return http.request({ host: upstreamHost, port: upstreamPort, path: req.url, method: req.method, headers }, (up) => {
                if (up.statusCode === 401) {
                    // cookie expired or the process restarted: re-exchange once
                    cookie = null;
                    ensureCookie(true)
                        .then(() => {
                            up.resume();
                            forward();
                        })
                        .catch(() => {
                            res.writeHead(502);
                            res.end("dsh-lan-pair: cookie refresh failed\n");
                        });
                    return;
                }
                res.writeHead(up.statusCode ?? 502, up.headers);
                up.pipe(res);
            });
        };

        const proxied = forward();
        proxied.on("error", (error) => {
            if (!res.headersSent) {
                res.writeHead(502, { "content-type": "text/plain; charset=utf-8" });
                res.end(`dsh-lan-pair: upstream error (${error.message})\n`);
            }
        });
        req.pipe(proxied);
    };

    // A client that hangs up mid-response must never take the host down: an
    // unhandled ECONNRESET here kills the whole DSH process, which is far worse
    // than a dropped phone connection.
    const server = http.createServer((req, res) => {
        req.on("error", () => {});
        res.on("error", () => {});
        handleRequest(req, res).catch(() => {
            if (!res.headersSent) {
                try {
                    res.writeHead(500, { "content-type": "text/plain; charset=utf-8" });
                    res.end("dsh-lan-pair: internal error\n");
                } catch {
                    /* the client is already gone */
                }
            }
        });
    });

    server.on("clientError", (error, socket) => {
        try {
            socket.destroy();
        } catch {
            /* already closed */
        }
    });

    // WebSocket / upgrade passthrough: the GUI's live stream needs this.
    const handleUpgrade = async (req, socket, head) => {
        if (!authorized(req)) {
            socket.destroy();
            return;
        }
        let upstreamCookie;
        try {
            upstreamCookie = await ensureCookie(false);
        } catch {
            socket.destroy();
            return;
        }
        const headers = { ...req.headers, host: authority, cookie: upstreamCookie };
        const proxied = http.request({
            host: upstreamHost,
            port: upstreamPort,
            path: req.url,
            method: req.method ?? "GET",
            headers,
        });
        proxied.on("upgrade", (upRes, upSocket, upHead) => {
            const lines = [`HTTP/1.1 101 Switching Protocols`];
            for (let i = 0; i < upRes.rawHeaders.length; i += 2) {
                lines.push(`${upRes.rawHeaders[i]}: ${upRes.rawHeaders[i + 1]}`);
            }
            socket.write(lines.join("\r\n") + "\r\n\r\n");
            if (upHead && upHead.length > 0) upSocket.write(upHead);
            if (head && head.length > 0) upSocket.write(head);
            upSocket.on("error", () => socket.destroy());
            socket.on("error", () => upSocket.destroy());
            upSocket.pipe(socket);
            socket.pipe(upSocket);
        });
        proxied.on("error", () => socket.destroy());
        proxied.end();
    };

    server.on("upgrade", (req, socket, head) => {
        socket.on("error", () => {});
        handleUpgrade(req, socket, head).catch(() => {
            try {
                socket.destroy();
            } catch {
                /* gone */
            }
        });
    });

    server.on("error", (error) => log(ctx, `listener error: ${error.message}`));

    let discovery = { close() {} };

    server.listen(LAN_PORT, "0.0.0.0", () => {
        log(ctx, `listening on 0.0.0.0:${LAN_PORT} -> ${authority}`);
        log(ctx, `pair URL: http://<this-pc-lan-ip>:${LAN_PORT}/__pair?k=${SECRET}`);
        try {
            discovery = advertise({ port: LAN_PORT, version: VERSION, log: (message) => log(ctx, message) });
        } catch (error) {
            log(ctx, `mDNS unavailable: ${error.message}`);
        }
    });

    ctx.effect(() => () => {
        discovery.close();
        server.close();
        log(ctx, "listener closed");
    }, "dsh-lan-pair: listener");
}
