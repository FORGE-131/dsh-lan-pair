package io.github.dshlanpair;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A shell around the DeepSeek Harness web GUI.
 *
 * It discovers `_dshpair._tcp` on the LAN, walks the pairing URL once so the
 * server sets its session cookie, and then keeps a WebView on the GUI. The
 * pairing URL is re-walked on every launch: it is idempotent and costs one
 * redirect, which removes the whole class of stale-cookie failures.
 */
public class MainActivity extends Activity implements Discovery.Listener {

    private static final String PREFS = "dsh-lan-pair";

    private final Handler main = new Handler(Looper.getMainLooper());

    private WebView web;
    private LinearLayout panel;
    private TextView status;
    private ListView deviceList;
    private EditText hostPortField;
    private EditText secretField;
    private ArrayAdapter<String> listAdapter;
    private final List<Discovery.Device> devices = new ArrayList<>();

    /** Remote endpoints advertised by a discovered desktop (mDNS TXT). */
    private final List<String> pendingRemotes = new ArrayList<>();

    private Discovery discovery;
    private SharedPreferences prefs;
    private boolean connecting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new Client());

        panel = buildPanel();

        FrameLayout root = new FrameLayout(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        discovery = new Discovery(this);

        if (getIntent() != null && getIntent().getData() != null) {
            handleDeepLink();
            return;
        }

        String secret = prefs.getString("secret", null);
        String endpoints = prefs.getString("endpoints", null);
        if (secret != null && endpoints != null && !endpoints.isEmpty()) {
            List<String> bases = new ArrayList<>();
            for (String part : endpoints.split("\\|")) {
                if (!part.trim().isEmpty()) {
                    bases.add(part.trim());
                }
            }
            if (!bases.isEmpty()) {
                hostPortField.setText(bases.get(0));
                secretField.setText(secret);
                connectWithEndpoints(bases, secret);
            } else {
                startScan();
            }
        } else {
            String scheme = prefs.getString("scheme", "http");
            String host = prefs.getString("host", null);
            int port = prefs.getInt("port", 0);
            if (host != null && port > 0 && secret != null) {
                hostPortField.setText(buildBase(scheme, host, port));
                secretField.setText(secret);
                connectTo(scheme, host, port, secret);
            } else {
                startScan();
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDeepLink();
    }

    /**
     * dshlanpair://connect?base=<base>&k=<secret>
     * dshlanpair://pair?u=<url-encoded .../__pair?k=... link>
     */
    private void handleDeepLink() {
        Intent intent = getIntent();
        Uri data = intent == null ? null : intent.getData();
        if (data == null) {
            return;
        }
        String secret = data.getQueryParameter("k");
        String base = data.getQueryParameter("base");
        String link = data.getQueryParameter("u");
        if (link != null && !link.isEmpty()) {
            String fromLink = secretFromLink(link);
            if (fromLink != null) {
                secret = fromLink;
            }
            int cut = link.indexOf("/__pair");
            base = cut > 0 ? link.substring(0, cut) : link;
        }
        if (base == null || base.trim().isEmpty()) {
            setStatus("链接里缺少 base 地址");
            return;
        }
        if (secret == null || secret.trim().isEmpty()) {
            setStatus("链接里缺少配对密钥");
            return;
        }
        listAdapter.clear();
        hostPortField.setText(base.trim());
        secretField.setText(secret.trim());
        List<String> bases = new ArrayList<>();
        bases.add(base.trim());
        // optional second hop: dshlanpair://connect?base=<lan>&remote=<public>&k=...
        String remote = data.getQueryParameter("remote");
        if (remote != null && !remote.trim().isEmpty()) {
            bases.add(remote.trim());
        }
        String extra = data.getQueryParameter("fallback");
        if (extra != null && !extra.trim().isEmpty()) {
            bases.add(extra.trim());
        }
        connectWithEndpoints(bases, secret.trim());
    }

    /** Build the connect/pair screen entirely in code: no layout resources to compile. */
    private LinearLayout buildPanel() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(0xF01A1A1E);
        int pad = dp(20);
        outer.setPadding(pad, dp(28), pad, pad);

        TextView title = new TextView(this);
        title.setText("连接 DSH");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        outer.addView(title);

        status = new TextView(this);
        status.setTextColor(0xFFB8B8C0);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setPadding(0, dp(6), 0, dp(10));
        outer.addView(status);

        Button scan = new Button(this);
        scan.setText("扫描局域网");
        scan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });
        outer.addView(scan);

        deviceList = new ListView(this);
        listAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        deviceList.setAdapter(listAdapter);
        deviceList.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        deviceList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= devices.size()) {
                    return;
                }
                Discovery.Device device = devices.get(position);
                if (!device.resolved) {
                    setStatus("这个设备还没解析出地址，稍等一下再点");
                    return;
                }
                pendingRemotes.clear();
                if (device.remote != null && !device.remote.isEmpty()) {
                    pendingRemotes.add(device.remote);
                }
                hostPortField.setText(device.host + ":" + device.port);
                setStatus(device.remote != null && !device.remote.isEmpty()
                        ? "已选择 " + device.name + "（含远程地址），填入配对密钥后点连接"
                        : "已选择 " + device.name + "，填入配对密钥后点连接");
            }
        });
        outer.addView(deviceList);

        hostPortField = new EditText(this);
        hostPortField.setHint("电脑地址，例如 192.168.1.14:8099");
        hostPortField.setInputType(InputType.TYPE_CLASS_TEXT);
        hostPortField.setTextColor(Color.WHITE);
        hostPortField.setHintTextColor(0xFF7A7A80);
        outer.addView(hostPortField);

        secretField = new EditText(this);
        secretField.setHint("4 位配对码（在电脑 DSH 的「移动设备」里开启配对）");
        secretField.setInputType(InputType.TYPE_CLASS_NUMBER);
        secretField.setTextColor(Color.WHITE);
        secretField.setHintTextColor(0xFF7A7A80);
        outer.addView(secretField);

        Button connect = new Button(this);
        connect.setText("连接");
        connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connectFromFields();
            }
        });
        outer.addView(connect);

        ScrollView scroller = new ScrollView(this);
        scroller.addView(outer);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return wrapper;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void setStatus(String text) {
        status.setText(text);
    }

    private void startScan() {
        panel.setVisibility(View.VISIBLE);
        setStatus("正在扫描局域网里的 DSH…");
        listAdapter.clear();
        devices.clear();
        discovery.start(this);
    }

    private void connectFromFields() {
        String raw = hostPortField.getText().toString().trim();
        String entered = secretField.getText().toString().trim();
        if (raw.isEmpty()) {
            setStatus("先选一个设备，或手动填地址（192.168.1.14:8099 或 https://xxx.trycloudflare.com）");
            return;
        }

        // A pasted pair link (.../__pair?k=SECRET) also carries the secret.
        String linkSecret = secretFromLink(raw);
        if (linkSecret != null) {
            entered = linkSecret;
        }

        String scheme = "http";
        String host;
        int port;
        String base = raw;
        int sep = base.indexOf("://");
        if (sep > 0) {
            try {
                URI uri = URI.create(base);
                String parsed = uri.getScheme();
                scheme = parsed == null ? "http" : parsed.toLowerCase(Locale.ROOT);
                host = uri.getHost();
                port = uri.getPort();
                if (port <= 0) {
                    port = "https".equals(scheme) ? 443 : 80;
                }
            } catch (RuntimeException error) {
                setStatus("地址格式不对：" + raw);
                return;
            }
            if (host == null || host.isEmpty()) {
                setStatus("地址里没有主机名：" + raw);
                return;
            }
        } else {
            String hp = base;
            int slash = hp.indexOf('/');
            if (slash > 0) {
                hp = hp.substring(0, slash);
            }
            host = hp;
            port = 8099;
            int colon = hp.lastIndexOf(':');
            if (colon > 0) {
                host = hp.substring(0, colon);
                try {
                    port = Integer.parseInt(hp.substring(colon + 1));
                } catch (NumberFormatException error) {
                    setStatus("端口不是数字：" + raw);
                    return;
                }
            }
        }

        if (entered.isEmpty()) {
            setStatus("请填电脑上显示的 4 位配对码，或直接粘贴完整配对链接");
            return;
        }
        if (entered.matches("\\d{4}")) {
            claimWithCode(scheme, host, port, entered);
        } else {
            connectTo(scheme, host, port, entered);
        }
    }

    /** Extracts k=SECRET from a pasted .../__pair?k=... link, or null. */
    private static String secretFromLink(String raw) {
        int at = raw.indexOf("__pair");
        if (at < 0) {
            return null;
        }
        int k = raw.indexOf("k=", at);
        if (k < 0) {
            return null;
        }
        String rest = raw.substring(k + 2);
        int amp = rest.indexOf('&');
        if (amp >= 0) {
            rest = rest.substring(0, amp);
        }
        return rest.isEmpty() ? null : rest;
    }

    /** scheme + host + (non-default) port, without a trailing slash. */
    private static String buildBase(String scheme, String host, int port) {
        boolean isDefault = ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
        if (port <= 0 || isDefault) {
            return scheme + "://" + host;
        }
        return scheme + "://" + host + ":" + port;
    }

    /**
     * Spend a 4-digit pairing code. The desktop answers with the long-lived
     * secret, which we store so the code is never needed again.
     */
    private void claimWithCode(final String host, final int port, final String code) {
        claimWithCode("http", host, port, code);
    }

    private void claimWithCode(final String scheme, final String host, final int port, final String code) {
        setStatus("正在用配对码连接 " + buildBase(scheme, host, port) + " …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String secret = null;
                String error = null;
                HttpURLConnection connection = null;
                try {
                    URL url = new URL(buildBase(scheme, host, port) + "/__claim?code=" + code);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setConnectTimeout(6000);
                    connection.setReadTimeout(6000);
                    connection.setRequestMethod("GET");
                    int statusCode = connection.getResponseCode();
                    InputStream stream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
                    String body = stream == null ? "" : readAll(stream);
                    if (statusCode == 200) {
                        Matcher matcher = Pattern.compile("\"secret\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
                        if (matcher.find()) {
                            secret = matcher.group(1);
                        } else {
                            error = "电脑返回的内容看不懂：" + body;
                        }
                    } else if (statusCode == 403) {
                        error = "电脑上没有开启配对。请在电脑 DSH 的「移动设备」里点『开启配对』，再重试";
                    } else if (statusCode == 401) {
                        error = "配对码不对，或者已经过期了。请重新点『开启配对』生成新码";
                    } else {
                        error = "配对失败（HTTP " + statusCode + "）：" + body;
                    }
                } catch (Exception failure) {
                    error = "连不上电脑：" + failure.getMessage();
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
                final String foundSecret = secret;
                final String foundError = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (foundSecret != null) {
                            connectTo(scheme, host, port, foundSecret);
                        } else {
                            setStatus(foundError);
                        }
                    }
                });
            }
        }).start();
    }

    /** Read a small HTTP body fully; used only for the pairing reply. */
    private static String readAll(InputStream stream) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = stream.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString("UTF-8");
    }

    /** Walk the pairing URL so the server issues its cookie, then land on the GUI. */
    private void connectTo(String host, int port, String secret) {
        connectTo("http", host, port, secret);
    }

    private void connectTo(String scheme, String host, int port, String secret) {
        List<String> bases = new ArrayList<>();
        bases.add(buildBase(scheme, host, port));
        bases.addAll(pendingRemotes);
        connectWithEndpoints(bases, secret);
    }

    /**
     * Tries every candidate base URL in order and keeps the first that answers,
     * so a desktop paired on the LAN keeps working after the phone leaves it.
     */
    private void connectWithEndpoints(final List<String> candidates, final String secret) {
        final List<String> bases = new ArrayList<>();
        for (String candidate : candidates) {
            String trimmed = candidate == null ? "" : candidate.trim();
            if (!trimmed.isEmpty() && !bases.contains(trimmed)) {
                bases.add(trimmed);
            }
        }
        if (bases.isEmpty() || secret == null || secret.isEmpty()) {
            setStatus("地址或密钥为空，请重新配对");
            return;
        }
        discovery.stop();
        connecting = true;
        panel.setVisibility(View.GONE);
        setStatus("正在连接 " + bases.get(0) + " …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String good = null;
                for (String base : bases) {
                    if (probePair(base, secret)) {
                        good = base;
                        break;
                    }
                }
                final String found = good;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (found == null) {
                            panel.setVisibility(View.VISIBLE);
                            setStatus("这 " + bases.size() + " 个地址都连不上，检查网络或重新配对");
                            return;
                        }
                        StringBuilder joined = new StringBuilder();
                        for (String base : bases) {
                            if (joined.length() > 0) {
                                joined.append('|');
                            }
                            joined.append(base);
                        }
                        prefs.edit()
                                .putString("secret", secret)
                                .putString("endpoints", joined.toString())
                                .putString("lastGood", found)
                                .apply();
                        web.loadUrl(found + "/__pair?k=" + secret);
                    }
                });
            }
        }).start();
    }

    /** Reachability probe: /__pair answers 303 when the secret is right. */
    private static boolean probePair(String base, String secret) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(base + "/__pair?k=" + URLEncoder.encode(secret, "UTF-8"));
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setRequestMethod("GET");
            int code = connection.getResponseCode();
            return code == 200 || code == 302 || code == 303;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    @Override
    public void onDevicesChanged(List<Discovery.Device> found) {
        main.post(new Runnable() {
            @Override
            public void run() {
                devices.clear();
                devices.addAll(found);
                listAdapter.clear();
                for (Discovery.Device device : found) {
                    listAdapter.add(device.label());
                }
                if (found.isEmpty()) {
                    setStatus("还没找到电脑。先在电脑 DSH 的「移动设备」里点『开启配对』，"
                            + "这台电脑才会出现在列表里（两分钟内有效）");
                } else {
                    boolean anyResolved = false;
                    for (Discovery.Device device : found) {
                        if (device.resolved) {
                            anyResolved = true;
                            hostPortField.setText(device.host + ":" + device.port);
                            break;
                        }
                    }
                    setStatus(anyResolved
                            ? "找到 " + found.size() + " 个设备，输入电脑上显示的 4 位码"
                            : "找到 " + found.size() + " 个设备，正在解析地址…");
                }
            }
        });
    }

    @Override
    public void onDiscoveryFailed(String reason) {
        main.post(new Runnable() {
            @Override
            public void run() {
                setStatus("扫描失败：" + reason + "\n可以手动填电脑地址继续");
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
            web.goBack();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && panel.getVisibility() != View.VISIBLE) {
            panel.setVisibility(View.VISIBLE);
            setStatus("已断开显示。可以重新扫描或直接点连接");
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        discovery.stop();
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }

    /** Surface failures instead of leaving a blank screen. */
    private final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return false;
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse response) {
            if (request.isForMainFrame() && response.getStatusCode() == 401) {
                panel.setVisibility(View.VISIBLE);
                setStatus("电脑拒绝了连接：配对密钥不对。\n密钥在电脑的 .dsh\\lan-pair.json 里");
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) {
                return;
            }
            panel.setVisibility(View.VISIBLE);
            setStatus("连不上：" + error.getDescription()
                    + "\n检查电脑是否开着 DSH、平板和电脑是否同一 Wi-Fi、防火墙是否放行了该端口");
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            connecting = false;
            if (url != null && !url.contains("__pair")) {
                panel.setVisibility(View.GONE);
            }
            view.evaluateJavascript(TAP_FEEDBACK_CSS, null);
            view.evaluateJavascript(FOCUS_GUARD_JS, null);
        }
    }

    /**
     * Keep the soft keyboard closed unless the user actually tapped the field.
     *
     * DSH focuses the composer whenever a session becomes active (switching a
     * session, creating one), which on a tablet throws the keyboard up over the
     * whole conversation. We do not want to forbid focusing - tapping the input
     * must still work - so we only drop focus that did not come from a touch on
     * that same field.
     */
    private static final String FOCUS_GUARD_JS =
            "(function(){"
            + "if(window.__dshLanPairFocusGuard)return;"
            + "window.__dshLanPairFocusGuard=true;"
            + "var touched=null,touchedAt=0;"
            + "document.addEventListener('touchstart',function(e){touched=e.target;touchedAt=Date.now();},true);"
            + "document.addEventListener('focusin',function(e){"
            + "var el=e.target;"
            + "if(!el||!(el.tagName==='INPUT'||el.tagName==='TEXTAREA'||el.isContentEditable))return;"
            + "var fromTouch=touched&&(el===touched||el.contains(touched)||touched.contains(el));"
            + "if(!fromTouch||Date.now()-touchedAt>700){el.blur();}"
            + "},true);"
            + "})()";

    /**
     * Replace WebView's default tap highlight with something quiet.
     *
     * Android paints a solid highlight block over whatever was tapped, which on
     * this device is a harsh green flash across the whole control. We switch it
     * off and substitute a subtle dim + short transition, so pressing a button
     * still feels responsive without the colour block.
     */
    private static final String TAP_FEEDBACK_CSS =
            "(function(){"
            + "if(document.getElementById('dsh-lan-pair-feedback'))return;"
            + "var s=document.createElement('style');"
            + "s.id='dsh-lan-pair-feedback';"
            + "s.textContent='*{-webkit-tap-highlight-color:transparent !important;}"
            + "button,a,[role=\"button\"],input[type=\"button\"]{"
            + "-webkit-transition:opacity 90ms ease-out;transition:opacity 90ms ease-out;}"
            + "button:active,a:active,[role=\"button\"]:active,input[type=\"button\"]:active{"
            + "opacity:0.72;}';"
            + "document.head.appendChild(s);"
            + "})()";
}
