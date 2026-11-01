/**
 * dsh-lan-pair
 *
 * Why this exists:
 *   `dsh web` binds loopback only and refuses 0.0.0.0 by design, so a phone on
 *   the LAN cannot reach the GUI. This plugin opens a second listener on the
 *   LAN and reverse-proxies to the loopback server, injecting the session
 *   cookie that it obtains in-process via `connection.authenticatedUrl()`.
 *   The phone therefore never receives the DSH launch token.
 *
 * Two ways to pair:
 *
 *   1. Pairing window (normal use)
 *      The desktop user presses a button in the DSH settings page, or opens
 *      /lanpair/open. That mints a 4-digit code for 120s and only then starts
 *      advertising over mDNS, so the machine is invisible to phones until the
 *      user asks for it. The phone posts the code to /__claim and receives the
 *      long-lived secret.
 *
 *   2. Long secret (fallback)
 *      GET /__pair?k=<secret> still works with the secret from
 *      $DSH_HOME/lan-pair.json.
 *
 * Presence is opt-in: no mDNS record exists unless a window is open.
 */

import http from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import crypto from "node:crypto";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { advertise } from "./mdns.js";
import { createPairing, renderCodePage } from "./pairing.js";

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

/** How long a pairing code stays valid. */
const PAIRING_TTL_MS = Number(process.env.DSH_LAN_PAIR_TTL_MS || "120000");

/**
 * Resolve the long-lived pairing secret.
 * Order: DSH_LAN_PAIR_SECRET, then $DSH_HOME/lan-pair.json, then a new one.
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

/** Open a URL in the desktop browser without blocking the host. */
function openInBrowser(url) {
    try {
        const command = process.platform === "win32" ? "cmd" : process.platform === "darwin" ? "open" : "xdg-open";
        const args = process.platform === "win32" ? ["/c", "start", "", url] : [url];
        const child = spawn(command, args, { detached: true, stdio: "ignore" });
        child.on("error", () => {});
        child.unref();
        return true;
    } catch {
        return false;
    }
}

export function apply(ctx) {
    const upstreamPort = ctx.webServer.port;
    const upstreamHost = "127.0.0.1";
    const authority = `${upstreamHost}:${upstreamPort}`;
    const codePageUrl = `http://127.0.0.1:${upstreamPort}/lanpair/code`;

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

    function json(res, statusCode, payload) {
        const body = JSON.stringify(payload);
        res.writeHead(statusCode, {
            "content-type": "application/json; charset=utf-8",
            "cache-control": "no-store",
            "content-length": Buffer.byteLength(body),
        });
        res.end(body);
    }

    // ---- pairing window, and mDNS that exists only while it is open ----
    let discovery = null;

    const pairing = createPairing({
        ttlMs: PAIRING_TTL_MS,
        onChange: (state) => {
            if (state.open) {
                if (discovery === null) {
                    try {
                        discovery = advertise({ port: LAN_PORT, version: VERSION, log: (message) => log(ctx, message) });
                    } catch (error) {
                        log(ctx, `mDNS unavailable: ${error.message}`);
                    }
                }
                log(ctx, `pairing window open, code ${state.code}, ${Math.round(state.remainingMs / 1000)}s`);
            } else {
                if (discovery !== null) {
                    discovery.close();
                    discovery = null;
                }
                log(ctx, `pairing window closed (${state.reason}): mDNS off`);
            }
        },
    });

    // ---- LAN listener (the phone talks to this one) ----
    const handleRequest = async (req, res) => {
        const url = new URL(req.url ?? "/", "http://lan.invalid");

        // Pairing by 4-digit code: the phone posts the code, gets the long secret.
        if (url.pathname === "/__claim") {
            if (!pairing.status().open) {
                json(res, 403, { ok: false, reason: "no pairing window is open on the desktop" });
                return;
            }
            const ok = pairing.claim(url.searchParams.get("code"));
            if (!ok) {
                json(res, 401, { ok: false, reason: "wrong or expired code" });
                return;
            }
            json(res, 200, { ok: true, secret: SECRET, port: LAN_PORT });
            return;
        }

        // Fallback: pair with the long-lived secret.
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
            reject(res, "dsh-lan-pair: not paired. pair with a code in the DSH settings page, or open /__pair?k=<secret>\n");
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
            // DSH's fence rejects an /api request whose Origin host differs from
            // the Host header (client-connection isTrustedApiRequest). The phone
            // sends Origin: http://<lan-ip>:8099 while we forward Host as
            // loopback, so rewrite the browser markers to match upstream.
            if (headers.origin !== undefined) headers.origin = `http://${authority}`;
            if (headers.referer !== undefined) headers.referer = `http://${authority}/`;
            return http.request({ host: upstreamHost, port: upstreamPort, path: req.url, method: req.method, headers }, (up) => {
                if (process.env.DSH_LAN_PAIR_DEBUG) {
                    log(ctx, `proxy ${req.method} ${req.url} -> ${up.statusCode}`);
                }
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
        // Same Origin rewrite as the HTTP path: WebSocket handshakes carry Origin too.
        if (headers.origin !== undefined) headers.origin = `http://${authority}`;
        if (headers.referer !== undefined) headers.referer = `http://${authority}/`;
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

    server.listen(LAN_PORT, "0.0.0.0", () => {
        log(ctx, `listening on 0.0.0.0:${LAN_PORT} -> ${authority}`);
        log(ctx, "pairing is off until you open a window (DSH settings -> 移动设备)");
    });

    // ---- loopback-only routes on the DSH web server, used by the GUI ----
    const lanpairRoute = {
        kind: "prefix",
        path: "/lanpair",
        handler: async (req, res) => {
            req.on("error", () => {});
            res.on("error", () => {});
            try {
                const url = new URL(req.url ?? "/lanpair", "http://local.invalid");
                if (url.pathname === "/lanpair/status") {
                    json(res, 200, pairing.status());
                    return;
                }
                if (url.pathname === "/lanpair/code") {
                    const body = renderCodePage(pairing.status());
                    res.writeHead(200, {
                        "content-type": "text/html; charset=utf-8",
                        "cache-control": "no-store",
                        "content-length": Buffer.byteLength(body),
                    });
                    res.end(body);
                    return;
                }
                if (url.pathname === "/lanpair/open") {
                    const state = pairing.open();
                    const opened = openInBrowser(codePageUrl);
                    log(ctx, `pairing requested from the GUI; browser window ${opened ? "opened" : "could not be opened"}`);
                    json(res, 200, { ok: true, open: true, expiresInMs: state.remainingMs, codePage: codePageUrl, browserOpened: opened });
                    return;
                }
                if (url.pathname === "/lanpair/close") {
                    pairing.close("closed by user");
                    json(res, 200, { ok: true, open: false });
                    return;
                }
                json(res, 404, { ok: false, reason: "unknown lanpair endpoint" });
            } catch (error) {
                json(res, 500, { ok: false, reason: String(error?.message ?? error) });
            }
        },
    };

    ctx.effect(() => ctx.webServer.register(lanpairRoute), "dsh-lan-pair: /lanpair routes");

    ctx.effect(() => () => {
        pairing.close("plugin disposed");
        if (discovery !== null) {
            discovery.close();
            discovery = null;
        }
        server.close();
        log(ctx, "listener closed");
    }, "dsh-lan-pair: listener");
}
