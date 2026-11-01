package io.github.dshlanpair;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
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
import java.net.URL;
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

        String host = prefs.getString("host", null);
        int port = prefs.getInt("port", 0);
        String secret = prefs.getString("secret", null);
        if (host != null && port > 0 && secret != null) {
            hostPortField.setText(host + ":" + port);
            secretField.setText(secret);
            connectTo(host, port, secret);
        } else {
            startScan();
        }
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
                hostPortField.setText(device.host + ":" + device.port);
                setStatus("已选择 " + device.name + "，填入配对密钥后点连接");
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
            setStatus("先选一个设备，或手动填电脑地址（例如 192.168.1.14:8099）");
            return;
        }
        if (entered.isEmpty()) {
            setStatus("请输入电脑上显示的 4 位配对码");
            return;
        }
        String host = raw;
        int port = 8099;
        int colon = raw.lastIndexOf(':');
        if (colon > 0) {
            host = raw.substring(0, colon);
            try {
                port = Integer.parseInt(raw.substring(colon + 1));
            } catch (NumberFormatException error) {
                setStatus("端口不是数字：" + raw);
                return;
            }
        }
        if (entered.matches("\\d{4}")) {
            claimWithCode(host, port, entered);
        } else {
            // fallback: someone pasted the long-term secret from lan-pair.json
            connectTo(host, port, entered);
        }
    }

    /**
     * Spend a 4-digit pairing code. The desktop answers with the long-lived
     * secret, which we store so the code is never needed again.
     */
    private void claimWithCode(final String host, final int port, final String code) {
        setStatus("正在用配对码连接 " + host + ":" + port + " …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String secret = null;
                String error = null;
                HttpURLConnection connection = null;
                try {
                    URL url = new URL("http://" + host + ":" + port + "/__claim?code=" + code);
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
                            connectTo(host, port, foundSecret);
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
        discovery.stop();
        connecting = true;
        prefs.edit()
                .putString("host", host)
                .putInt("port", port)
                .putString("secret", secret)
                .apply();
        String url = "http://" + host + ":" + port + "/__pair?k=" + secret;
        panel.setVisibility(View.GONE);
        setStatus("正在连接 " + host + ":" + port + " …");
        web.loadUrl(url);
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
        }
    }
}
