/**
 * mDNS probe for the dsh-lan-pair service.
 *
 * Sends a real PTR query for `_dshpair._tcp.local` and decodes whatever comes
 * back, so the responder's packets are checked by an independent reader.
 *
 * Usage: node tools/mdns-probe.mjs [seconds]
 */

import dgram from "node:dgram";

const MDNS_ADDRESS = "224.0.0.251";
const MDNS_PORT = 5353;
const SERVICE = "_dshpair._tcp.local";
const WAIT_SECONDS = Number(process.argv[2] ?? "6");
// Optional target: pass 127.0.0.1 to exercise the responder on this machine,
// because Windows does not loop multicast back to a second socket on 5353.
const TARGET = process.argv[3] ?? MDNS_ADDRESS;

const TYPES = { 1: "A", 12: "PTR", 16: "TXT", 28: "AAAA", 33: "SRV" };

function encodeName(name) {
    const chunks = [];
    for (const label of name.split(".").filter(Boolean)) {
        const bytes = Buffer.from(label, "utf8");
        chunks.push(Buffer.from([bytes.length]), bytes);
    }
    chunks.push(Buffer.from([0]));
    return Buffer.concat(chunks);
}

function readName(buffer, offset) {
    const labels = [];
    let cursor = offset;
    for (let guard = 0; guard < 64; guard++) {
        if (cursor >= buffer.length) break;
        const length = buffer[cursor];
        if (length === 0) {
            cursor += 1;
            break;
        }
        if ((length & 0xc0) === 0xc0) {
            cursor = ((length & 0x3f) << 8) | buffer[cursor + 1];
            continue;
        }
        labels.push(buffer.subarray(cursor + 1, cursor + 1 + length).toString("utf8"));
        cursor += 1 + length;
    }
    return { name: labels.join("."), next: cursor };
}

const socket = dgram.createSocket({ type: "udp4", reuseAddr: true });
const seen = new Set();

socket.on("message", (message) => {
    const flags = message.readUInt16BE(2);
    const qd = message.readUInt16BE(4);
    const an = message.readUInt16BE(6);
    const ns = message.readUInt16BE(8);
    const ar = message.readUInt16BE(10); // authority count sits at 8, additional at 10
    if ((flags & 0x8000) === 0) return; // ignore queries
    if (!globalThis.__countsLogged) {
        console.log(`  [packet] qd=${qd} an=${an} ns=${ns} ar=${ar} bytes=${message.length}`);
        globalThis.__countsLogged = true;
    }
    let offset = 12;
    for (let i = 0; i < qd; i++) {
        const { next } = readName(message, offset);
        offset = next + 4;
    }
    const total = an + ar;
    for (let i = 0; i < total; i++) {
        const { name, next } = readName(message, offset);
        if (next + 10 > message.length) return;
        const type = message.readUInt16BE(next);
        const rdlength = message.readUInt16BE(next + 8);
        const rdataStart = next + 10;
        let detail = "";
        if (type === 12) {
            detail = readName(message, rdataStart).name;
        } else if (type === 33) {
            const port = message.readUInt16BE(rdataStart + 4);
            const target = readName(message, rdataStart + 6).name;
            detail = `port=${port} target=${target}`;
        } else if (type === 16) {
            const parts = [];
            let cursor = rdataStart;
            while (cursor < rdataStart + rdlength) {
                const length = message[cursor];
                parts.push(message.subarray(cursor + 1, cursor + 1 + length).toString("utf8"));
                cursor += 1 + length;
            }
            detail = parts.join(" ");
        } else if (type === 1) {
            detail = `${message[rdataStart]}.${message[rdataStart + 1]}.${message[rdataStart + 2]}.${message[rdataStart + 3]}`;
        }
        const line = `${TYPES[type] ?? type} ${name} -> ${detail}`;
        if (!seen.has(line)) {
            seen.add(line);
            console.log(`  ${line}`);
        }
        offset = rdataStart + rdlength;
    }
});

socket.on("error", (error) => {
    console.error(`probe socket error: ${error.message}`);
    process.exit(1);
});

socket.bind(0, () => {
    // An ephemeral source port, so RFC 6762 6.7 makes the responder answer us
    // directly and we do not depend on multicast loopback delivery.
    const header = Buffer.alloc(12);
    header.writeUInt16BE(0, 0);
    header.writeUInt16BE(0, 2);
    header.writeUInt16BE(1, 4); // one question
    const tail = Buffer.alloc(4);
    tail.writeUInt16BE(12, 0); // PTR
    tail.writeUInt16BE(1, 2); // IN
    const query = Buffer.concat([header, encodeName(SERVICE), tail]);
    socket.send(query, 0, query.length, MDNS_PORT, TARGET);
    console.log(`query sent to ${TARGET}:${MDNS_PORT} for ${SERVICE}, listening ${WAIT_SECONDS}s ...`);
});

setTimeout(() => {
    console.log(seen.size === 0 ? "no records received" : `\n${seen.size} distinct records`);
    socket.close();
    process.exit(seen.size === 0 ? 1 : 0);
}, WAIT_SECONDS * 1000);
