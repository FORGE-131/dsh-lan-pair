/**
 * dsh-lan-pair — client half.
 *
 * Adds a "移动设备" section to the DSH settings page with one button. Pressing
 * it asks the host to open a pairing window: the machine starts advertising over
 * mDNS and a browser window shows a 4-digit code for two minutes.
 *
 * Hand-written bundle: the page provides React and the slot registry, so this
 * package needs no npm dependencies at all.
 */

window.__ModuleLoader__.load({
    id: "dsh-lan-pair",
    factory: (require) => {
        const module = { exports: {} };
        const exports = module.exports;
        Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });

        const React = require("react");
        const { createElement: h, useCallback, useEffect, useRef, useState } = React;

        const API = "/lanpair";

        /** Call one host endpoint and normalise the reply. */
        async function call(path, init) {
            const response = await fetch(`${API}${path}`, { cache: "no-store", ...(init ?? {}) });
            const text = await response.text();
            try {
                return { status: response.status, body: JSON.parse(text) };
            } catch {
                return { status: response.status, body: { ok: false, reason: text.slice(0, 300) } };
            }
        }

        const styles = {
            root: { display: "flex", flexDirection: "column", gap: "14px", maxWidth: "640px" },
            title: { fontSize: "16px", fontWeight: 600, margin: 0 },
            body: { fontSize: "13px", lineHeight: 1.6, opacity: 0.75, margin: 0 },
            row: { display: "flex", alignItems: "center", gap: "12px", flexWrap: "wrap" },
            button: {
                padding: "8px 18px",
                borderRadius: "8px",
                border: "1px solid rgba(127,127,127,0.35)",
                background: "rgba(127,127,127,0.12)",
                color: "inherit",
                fontSize: "14px",
                cursor: "pointer",
            },
            buttonPrimary: {
                padding: "8px 18px",
                borderRadius: "8px",
                border: "1px solid rgba(79,140,255,0.6)",
                background: "rgba(79,140,255,0.18)",
                color: "inherit",
                fontSize: "14px",
                fontWeight: 600,
                cursor: "pointer",
            },
            code: {
                fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
                fontSize: "40px",
                letterSpacing: "10px",
                fontWeight: 600,
                padding: "12px 18px",
                borderRadius: "10px",
                background: "rgba(79,140,255,0.12)",
                border: "1px solid rgba(79,140,255,0.35)",
            },
            error: { color: "#ff6b6b", fontSize: "13px", margin: 0 },
            hint: { fontSize: "12px", opacity: 0.6, margin: 0 },
        };

        function MobileSection() {
            const [status, setStatus] = useState(null);
            const [busy, setBusy] = useState(false);
            const [error, setError] = useState("");
            const mounted = useRef(true);

            const refresh = useCallback(async () => {
                try {
                    const reply = await call("/status");
                    if (mounted.current && reply.body && typeof reply.body.open === "boolean") {
                        setStatus(reply.body);
                        setError("");
                    }
                } catch (reason) {
                    if (mounted.current) setError(`连不上本机插件：${reason.message}`);
                }
            }, []);

            useEffect(() => {
                mounted.current = true;
                refresh();
                return () => {
                    mounted.current = false;
                };
            }, [refresh]);

            // Poll only while a window is open, so the countdown stays honest.
            useEffect(() => {
                if (!status || !status.open) return undefined;
                const timer = setInterval(refresh, 1000);
                return () => clearInterval(timer);
            }, [status && status.open, refresh]);

            const openWindow = useCallback(async () => {
                setBusy(true);
                setError("");
                try {
                    const reply = await call("/open", { method: "POST" });
                    if (reply.body && reply.body.ok) {
                        await refresh();
                    } else {
                        setError((reply.body && reply.body.reason) || "开启配对失败");
                    }
                } catch (reason) {
                    setError(`开启配对失败：${reason.message}`);
                }
                setBusy(false);
            }, [refresh]);

            const closeWindow = useCallback(async () => {
                setBusy(true);
                try {
                    await call("/close", { method: "POST" });
                    await refresh();
                } catch {
                    /* the status poll will correct the display */
                }
                setBusy(false);
            }, [refresh]);

            const open = Boolean(status && status.open);
            const seconds = status ? Math.ceil(status.remainingMs / 1000) : 0;

            return h(
                "div",
                { style: styles.root },
                h("h3", { style: styles.title }, "移动设备"),
                h(
                    "p",
                    { style: styles.body },
                    "手机或平板上的 DSH LAN 应用需要先配对一次。点下面的按钮后，这台电脑才会在局域网里出现，",
                    "同时会弹出一个窗口显示 4 位配对码，两分钟内有效、只能使用一次。",
                ),
                h(
                    "div",
                    { style: styles.row },
                    h(
                        "button",
                        {
                            type: "button",
                            style: open ? styles.button : styles.buttonPrimary,
                            disabled: busy,
                            onClick: open ? closeWindow : openWindow,
                        },
                        open ? "关闭配对" : busy ? "正在开启…" : "开启配对",
                    ),
                    open
                        ? h("span", { style: styles.hint }, `剩余 ${seconds} 秒`)
                        : h("span", { style: styles.hint }, "未开启时，手机扫描不到这台电脑"),
                ),
                open && status && status.code ? h("div", { style: styles.code }, status.code) : null,
                open
                    ? h(
                          "p",
                          { style: styles.hint },
                          "在平板应用里输入上面的数字即可完成配对；配对成功后这里会自动关闭。",
                      )
                    : null,
                error ? h("p", { style: styles.error }, error) : null,
            );
        }

        const inject = ["slots"];

        function apply(ctx) {
            const label = "dsh-lan-pair: settings section";
            ctx.effect(
                () =>
                    ctx.slots.inject("settings.section", () =>
                        ctx.slots.register(
                            { name: "settings.section", id: "lan-pair", order: 7, label: () => "移动设备" },
                            MobileSection,
                        ),
                    ),
                label,
            );
        }

        exports.apply = apply;
        exports.inject = inject;
        exports.MobileSection = MobileSection;
        return module.exports;
    },
});
