/**
 * Passive mDNS listener: joins 5353 and prints any packet whose source is not
 * this machine, so we can tell whether a phone's queries actually arrive.
 *
 * Usage: node tools/mdns-listen.mjs [seconds]
 */

import dgram from "node:dgram";
import os from "node:os";

const MDNS_ADDRESS = "224.0.0.251";
const MDNS_PORT = 5353;
const WAIT = Number(process.argv[2] ?? "20");

const local = new Set();
for (const list of Object.values(os.networkInterfaces())) {
    for (const iface of list ?? []) {
        if (iface.family === "IPv4") local.add(iface.address);
    }
}

const socket = dgram.createSocket({ type: "udp4", reuseAddr: true });
const seen = new Map();

socket.on("message", (message, rinfo) => {
    const key = `${rinfo.address}:${rinfo.port}`;
    seen.set(key, (seen.get(key) ?? 0) + 1);
    const isQuery = (message.readUInt16BE(2) & 0x8000) === 0;
    const tag = local.has(rinfo.address) ? "本机" : "外部";
    console.log(`  ${tag} ${key} ${isQuery ? "查询" : "应答"} ${message.length}B`);
});

socket.on("error", (error) => {
    console.error(`listen error: ${error.message}`);
    process.exit(1);
});

socket.bind(MDNS_PORT, () => {
    try {
        socket.addMembership(MDNS_ADDRESS);
    } catch (error) {
        console.error(`membership failed: ${error.message}`);
    }
    console.log(`listening ${WAIT}s on :${MDNS_PORT} (local: ${[...local].join(",")})`);
});

setTimeout(() => {
    console.log(`\n总计 ${visited()} 个来源`);
    socket.close();
    process.exit(0);

    function visited() {
        return seen.size;
    }
}, WAIT * 1000);
