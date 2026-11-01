/**
 * Pairing window for dsh-lan-pair.
 *
 * A short, human-typable code that is only valid while the user has explicitly
 * opened a window, in the spirit of Bluetooth pairing:
 *
 *   - 4 digits, generated from crypto randomness (no Math.random)
 *   - valid for a bounded time (default 120s) and only until first successful claim
 *   - at most 5 wrong attempts per window, then the window closes
 *
 * The long-lived secret stays exactly as it was; this window is only the way a
 * new device proves it is in the room. A claimed device receives the long-term
 * secret and never sees a code again.
 */

import crypto from "node:crypto";

const MAX_ATTEMPTS = 5;

/**
 * @typedef {object} PairingStatus
 * @property {boolean} open - whether a window is currently accepting claims
 * @property {number} remainingMs - milliseconds left before it closes
 * @property {string|undefined} code - the current code (callers must only expose it locally)
 * @property {number} attempts - wrong attempts so far in this window
 */

export function createPairing(options = {}) {
    const ttlMs = Number(options.ttlMs ?? 120000);
    const onChange = options.onChange ?? (() => {});

    /** @type {{code: string, expiresAt: number, attempts: number, timer: NodeJS.Timeout}|null} */
    let current = null;

    function status() {
        if (current === null) {
            return { open: false, remainingMs: 0, code: undefined, attempts: 0 };
        }
        return {
            open: true,
            remainingMs: Math.max(0, current.expiresAt - Date.now()),
            code: current.code,
            attempts: current.attempts,
        };
    }

    function close(reason) {
        if (current === null) return;
        clearTimeout(current.timer);
        current = null;
        onChange({ ...status(), reason });
    }

    /** Open a fresh window, replacing any previous one. */
    function open() {
        if (current !== null) {
            clearTimeout(current.timer);
        }
        // Uniform 0000-9999, from crypto so the sequence is unpredictable.
        const code = String(crypto.randomInt(0, 10000)).padStart(4, "0");
        const expiresAt = Date.now() + ttlMs;
        const timer = setTimeout(() => close("expired"), ttlMs);
        if (typeof timer.unref === "function") timer.unref();
        current = { code, expiresAt, attempts: 0, timer };
        onChange({ ...status(), reason: "opened" });
        return status();
    }

    /**
     * Try to spend a code. A correct code closes the window (single use).
     * @param candidate - digits supplied by the remote device.
     * @returns true only for the first correct claim of this window.
     */
    function claim(candidate) {
        if (current === null) return false;
        if (Date.now() >= current.expiresAt) {
            close("expired");
            return false;
        }
        const supplied = String(candidate ?? "");
        const expected = current.code;
        // Constant-time compare over equal-length buffers.
        const a = Buffer.from(supplied, "utf8");
        const b = Buffer.from(expected, "utf8");
        const matches = a.length === b.length && crypto.timingSafeEqual(a, b);
        if (!matches) {
            current.attempts += 1;
            if (current.attempts >= MAX_ATTEMPTS) close("too many attempts");
            return false;
        }
        close("claimed");
        return true;
    }

    return { open, close, claim, status, maxAttempts: MAX_ATTEMPTS };
}

/**
 * Render the page the desktop browser opens to show the pairing code.
 * Plain HTML with a small inline script: it polls /lanpair/status so the code
 * disappears the moment the window closes.
 * @param status - status to render initially.
 * @returns an HTML document.
 */
export function renderCodePage(status) {
    const initial = JSON.stringify({
        open: status.open,
        code: status.code ?? "",
        remainingMs: status.remainingMs,
    });
    return `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>DSH 配对码</title>
<style>
  :root { color-scheme: dark }
  body { margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center;
         background:#111114; color:#f2f2f4; font-family:system-ui,-apple-system,"Segoe UI",sans-serif }
  .card { text-align:center; padding:48px 56px }
  h1 { font-size:18px; font-weight:500; color:#a8a8b4; margin:0 0 8px }
  .code { font-size:96px; font-weight:600; letter-spacing:18px; font-variant-numeric:tabular-nums;
          margin:12px 0; color:#fff }
  .meta { font-size:15px; color:#8b8b96 }
  .bar { height:4px; background:#26262c; border-radius:2px; overflow:hidden; margin-top:22px }
  .bar > i { display:block; height:100%; background:#4f8cff; width:100%; transform-origin:left }
  .closed .code { color:#6b6b76; text-decoration:line-through }
</style></head>
<body><div class="card" id="card">
  <h1>在平板的 DSH 应用里输入这个配对码</h1>
  <div class="code" id="code">••••</div>
  <div class="meta" id="meta">正在开启…</div>
  <div class="bar"><i id="bar"></i></div>
</div>
<script>
  const initial = ${initial};
  const card = document.getElementById('card');
  const codeEl = document.getElementById('code');
  const metaEl = document.getElementById('meta');
  const barEl = document.getElementById('bar');
  let total = Math.max(1, initial.remainingMs || 120000);

  function paint(s) {
    if (!s.open) {
      card.classList.add('closed');
      codeEl.textContent = '----';
      metaEl.textContent = '配对窗口已关闭';
      barEl.style.width = '0%';
      return;
    }
    card.classList.remove('closed');
    codeEl.textContent = s.code;
    metaEl.textContent = Math.ceil(s.remainingMs / 1000) + ' 秒后自动关闭';
    barEl.style.width = Math.max(0, Math.min(100, (s.remainingMs / total) * 100)) + '%';
  }

  paint(initial);
  const timer = setInterval(async () => {
    try {
      const response = await fetch('/lanpair/status', { cache: 'no-store' });
      const s = await response.json();
      if (s.open) total = Math.max(total, s.remainingMs);
      paint(s);
      if (!s.open) clearInterval(timer);
    } catch (error) {
      metaEl.textContent = '与 DSH 的连接断开';
      clearInterval(timer);
    }
  }, 500);
</script>
</body></html>`;
}
