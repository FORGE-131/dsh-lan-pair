/**
 * Minimal mDNS responder for the dsh-lan-pair service.
 *
 * Advertises `_dshpair._tcp.local` so a phone can find this machine instead of
 * being told an IP by hand. Written by hand with UDP only: no npm dependency,
 * because the point of this plugin is that it installs with nothing else.
 *
 * Scope is deliberately small: we answer queries for our own records and
 * announce once at start. We do not implement caching, probing, or goodbye
 * packets - a LAN with one desktop and one phone does not need them.
 */

import dgram from "node:dgram";
import os from "node:os";

const MDNS_ADDRESS = "224.0.0.251";
const MDNS_PORT = 5353;

const TYPE_A = 1;
const TYPE_TXT = 16;
const TYPE_SRV = 33;
const TYPE_PTR = 12;
const TYPE_ANY = 255;
const CLASS_IN = 1;
const CACHE_FLUSH = 0x8000;
const TTL = 120;

/** Encode one DNS name as length-prefixed labels. */
function encodeName(name) {
    const parts = name.split(".").filter((label) => label.length > 0);
    const chunks = [];
    for (const label of parts) {
        const bytes = Buffer.from(label, "utf8");
        chunks.push(Buffer.from([bytes.length]), bytes);
    }
    chunks.push(Buffer.from([0]));
    return Buffer.concat(chunks);
}

/** Read one DNS name at `offset`; returns the name and the next offset. */
function readName(buffer, offset) {
    const labels = [];
    let cursor = offset;
    let jumped = false;
    let next = offset;
    for (let guard = 0; guard < 64; guard++) {
        if (cursor >= buffer.length) break;
        const length = buffer[cursor];
        if (length === 0) {
            if (!jumped) next = cursor + 1;
            break;
        }
        if ((length & 0xc0) === 0xc0) {
            const pointer = ((length & 0x3f) << 8) | buffer[cursor + 1];
            if (!jumped) next = cursor + 2;
            jumped = true;
            cursor = pointer;
            continue;
        }
        labels.push(buffer.subarray(cursor + 1, cursor + 1 + length).toString("utf8"));
        if (!jumped) next = cursor + 1 + length;
        cursor += 1 + length;
    }
    return { name: labels.join("."), next };
}
/** Build one resource record. */
function buildRecord(name, type, rdata, unique) {
    const header = Buffer.alloc(10);
    header.writeUInt16BE(type, 0);
    header.writeUInt16BE(unique ? CLASS_IN | CACHE_FLUSH : CLASS_IN, 2);
    header.writeUInt32BE(TTL, 4);
    header.writeUInt16BE(rdata.length, 8);
    return Buffer.concat([encodeName(name), header, rdata]);
}

/** Build TXT rdata from string key/value pairs. */
function buildTxt(entries) {
    const parts = [];
    for (const [key, value] of Object.entries(entries)) {
        if (value === undefined || value === null) continue;
        const text = `${key}=${value}`;
        const bytes = Buffer.from(text, "utf8").subarray(0, 255);
        parts.push(Buffer.from([bytes.length]), bytes);
    }
    if (parts.length === 0) parts.push(Buffer.from([0]));
    return Buffer.concat(parts);
}

/** Pick a LAN IPv4 address, preferring a private range. */
function pickAddress() {
    const interfaces = os.networkInterfaces();
    const candidates = [];
    for (const list of Object.values(interfaces)) {
        for (const iface of list ?? []) {
            if (iface.family !== "IPv4" || iface.internal) continue;
            candidates.push(iface.address);
        }
    }
    const isPrivate = (ip) => /^10\./.test(ip) || /^192\.168\./.test(ip) || /^172\.(1[6-9]|2\d|3[01])\./.test(ip);
    return candidates.find(isPrivate) ?? candidates[0];
}

/**
 * Start advertising the service.
 * @param options - port, instance name, extra TXT entries, log function.
 * @returns a handle with `close()`.
 */
export function advertise(options) {
    const { port, log } = options;
    const host = os.hostname().replace(/[^A-Za-z0-9-]/g, "-") || "dsh-host";
    const instance = `${host} (${os.platform()})`;
    const serviceName = "_dshpair._tcp.local";
    const instanceName = `${instance}.${serviceName}`;
    const hostName = `${host}.local`;
    const address = pickAddress();

    if (address === undefined) {
        log("mDNS skipped: no LAN IPv4 address");
        return { close() {} };
    }

    const txt = buildTxt({
            version: options.version,
            port,
            path: "/__pair",
            host,
            platform: os.platform(),
            ...(options.remote ? { remote: options.remote } : {}),
        });

    const srvRdata = (() => {
        const fixed = Buffer.alloc(6);
        fixed.writeUInt16BE(0, 0); // priority
        fixed.writeUInt16BE(0, 2); // weight
        fixed.writeUInt16BE(port, 4);
        return Buffer.concat([fixed, encodeName(hostName)]);
    })();

    /** The full answer set for one response (PTR plus unique records). */
    const answers = () => [
        buildRecord(serviceName, TYPE_PTR, encodeName(instanceName), false),
        buildRecord(instanceName, TYPE_SRV, srvRdata, true),
        buildRecord(instanceName, TYPE_TXT, txt, true),
    ];

    const additional = () => [buildRecord(hostName, TYPE_A, Buffer.from(address.split(".").map(Number)), true)];

    const socket = dgram.createSocket({ type: "udp4", reuseAddr: true });

    socket.on("error", (error) => log(`mDNS socket error: ${error.message}`));

    socket.on("message", (message, rinfo) => {
        if (process.env.DSH_LAN_PAIR_DEBUG) {
            log(`mDNS packet in: ${message.length} bytes from ${rinfo.address}:${rinfo.port}`);
        }
        // Parse the question section just enough to decide whether it is for us.
        if (message.length < 12) return;
        const flags = message.readUInt16BE(2);
        if ((flags & 0x8000) !== 0) return; // a response, not a query
        const questionCount = message.readUInt16BE(4);
        let offset = 12;
        const wanted = [];
        for (let i = 0; i < questionCount; i++) {
            const { name, next } = readName(message, offset);
            if (next + 4 > message.length) return;
            const type = message.readUInt16BE(next);
            offset = next + 4;
            wanted.push({ name: name.toLowerCase(), type });
        }
        const lowerService = serviceName.toLowerCase();
        const lowerInstance = instanceName.toLowerCase();
        const lowerHost = hostName.toLowerCase();
        const interested = wanted.some(({ name, type }) => {
            const matchesName = name === lowerService || name === lowerInstance || name === lowerHost;
            if (!matchesName) return false;
            return type === TYPE_ANY || [TYPE_PTR, TYPE_SRV, TYPE_TXT, TYPE_A].includes(type);
        });
        if (!interested) return;
        if (process.env.DSH_LAN_PAIR_DEBUG) {
            log(`mDNS query from ${rinfo.address}:${rinfo.port} for ${wanted.map((w) => w.name).join(",")}`);
        }

        const records = [...answers(), ...additional()];
        const header = Buffer.alloc(12);
        header.writeUInt16BE(0, 0); // id 0 for mDNS
        header.writeUInt16BE(0x8400, 2); // response + authoritative
        header.writeUInt16BE(0, 4); // no questions echoed
        header.writeUInt16BE(answers().length, 6);
        header.writeUInt16BE(0, 8);
        header.writeUInt16BE(additional().length, 10);
        const response = Buffer.concat([header, ...records]);

        // RFC 6762 section 6.7: a query from a port other than 5353 gets a
        // unicast answer on that port, which also sidesteps flaky multicast
        // delivery when several sockets share 5353 on one machine.
        const unicast = rinfo.port !== MDNS_PORT;
        const targetPort = unicast ? rinfo.port : MDNS_PORT;
        const destination = unicast ? rinfo.address : MDNS_ADDRESS;
        socket.send(response, 0, response.length, targetPort, destination, (error) => {
            if (error) log(`mDNS send error: ${error.message}`);
        });
    });

    socket.bind(MDNS_PORT, () => {
        try {
            socket.addMembership(MDNS_ADDRESS);
            socket.setMulticastTTL(255);
            socket.setMulticastLoopback(true);
        } catch (error) {
            log(`mDNS membership failed: ${error.message}`);
        }
        log(`mDNS advertising ${instanceName} at ${address}:${port}`);

        // one unsolicited announcement so clients need not wait for a timer
        const records = [...answers(), ...additional()];
        const header = Buffer.alloc(12);
        header.writeUInt16BE(0x8400, 2);
        header.writeUInt16BE(answers().length, 6);
        header.writeUInt16BE(additional().length, 10);
        const packet = Buffer.concat([header, ...records]);
        socket.send(packet, 0, packet.length, MDNS_PORT, MDNS_ADDRESS);
    });

    return {
        close() {
            try {
                socket.close();
            } catch {
                /* already closed */
            }
        },
    };
}
