## 是什么

把电脑上的 DeepSeek Harness 网页界面开放给同一局域网里的手机/平板，两边配对即可用。

不需要 root，不需要 USB 调试，不需要手拼 URL。

## 内容

**插件 `dsh-lan-pair`（bundle 形态）**
- 在 DSH 进程内起局域网监听（默认 `0.0.0.0:8099`），反向代理到本机回环
- 用 `connection.authenticatedUrl()` 在进程内完成 token 交换并自行持有 cookie，
  **手机永远拿不到 DSH 的启动 token**
- 配对密钥随机生成并持久化在 `$DSH_HOME/lan-pair.json`
- mDNS 广播 `_dshpair._tcp`（端口、设备名、版本、平台），纯手写 UDP，零依赖
- HTTP 与 WebSocket 全量透传；客户端异常断开不会影响宿主进程

**Android App `dsh-lan-pair-0.1.0.apk`**
- 自动发现局域网内的 DSH 实例（NsdManager）
- 输入一次配对密钥后持久保存，之后打开即自动连接
- 系统 WebView 全屏渲染，不打包浏览器内核，APK 仅 16 KB
- 明确错误状态：密钥不对 / 连不上 / 扫描失败，都有提示而不是白屏
- minSdk 26（Android 8.0+），无原生库，任意架构可装

## 已验证

- DSH `0.1.7-rc.2` + HyperOS（Android 15/16）实机
- 发现 → 配对 → 连接 → 全屏显示 GUI
- 强杀 App 后电脑端 DSH 存活；重开自动重连

## 安装

见 [README](https://github.com/FORGE-131/dsh-lan-pair#readme)。
APK 是自签名证书（`CN=DSH LAN Pair`），侧载需要允许"未知来源"。

## 注意

配对密钥用于**防误连**，不是强加密：局域网内是明文 HTTP。
它解决的是"同一个 Wi-Fi 下的邻居随手就能指挥你的电脑"这类意外暴露。
