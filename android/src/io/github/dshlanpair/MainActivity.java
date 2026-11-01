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

import java.util.ArrayList;
import java.util.List;

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
        secretField.setHint("配对密钥（电脑上 lan-pair.json 里的 secret）");
        secretField.setInputType(InputType.TYPE_CLASS_TEXT);
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
        String secret = secretField.getText().toString().trim();
        if (raw.isEmpty()) {
            setStatus("先选一个设备，或手动填电脑地址（例如 192.168.1.14:8099）");
            return;
        }
        if (secret.isEmpty()) {
            setStatus("配对密钥不能为空");
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
        connectTo(host, port, secret);
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
                    setStatus("还没找到设备。确认电脑上 DSH 已启动、并在同一 Wi-Fi 下");
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
                            ? "找到 " + found.size() + " 个设备，填密钥后点连接"
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
