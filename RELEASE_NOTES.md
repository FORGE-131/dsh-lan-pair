## 0.4.0：配对一次，之后出门也能连

之前只能在**同一个局域网**里用。这一版把「出门还能连」做成了原生能力。

### 1. 远程访问由插件托管

```powershell
# 插件启动时自动拉起隧道，并从输出里抓公网地址
$env:DSH_LAN_PAIR_TUNNEL = "cloudflared tunnel --url http://127.0.0.1:8099 --no-autoupdate"
# 或者填固定地址（命名隧道 / 反向代理）
$env:DSH_LAN_PAIR_PUBLIC_URL = "https://dsh.example.com"
```

新增接口 `GET /lanpair/info`，返回局域网地址、公网地址、配对链接和隧道状态。

### 2. 多地址自动切换

插件的 mDNS TXT 记录多了 `remote=<公网地址>`。App 在局域网发现电脑时，
会把**局域网 + 远程**两个地址一起存下来；连接时依次探测（3 秒超时），
第一个通的就是当前网络下的最优路径：

- 在家：局域网直连，不绕公网
- 出门：局域网超时后自动走隧道

### 3. 深链接

```text
dshlanpair://connect?base=<地址>&remote=<备用>&k=<密钥>
dshlanpair://pair?u=<完整配对链接>
```

可以做成二维码或桌面快捷方式，点一下直接连，不用手输地址。

### 4. 其它

- 地址栏支持完整 `https://` URL（省略端口时按 80/443 补全）
- 记住连接方案（scheme），重启后原样恢复
- 已在 Android 15 上验证（含移动数据下的纯外网连接）

### 已知限制

- Cloudflare **快速隧道**域名每次重启都变，且同一出口 IP 并发会被限流
- 长期使用建议配命名隧道，或用 `DSH_LAN_PAIR_PUBLIC_URL` 指向固定域名

---
## 0.2.1：修掉一个让界面"半死"的 bug

上一版虽然页面能打开，但**所有 `/api` 调用都被 DSH 的准入栅栏拒绝**，表现就是：

- 侧栏「暂无会话」，电脑上的会话都看不到
- 工作区一直卡在「正在加载工作区…」
- 点「新会话」直接报错

### 根因

DSH 的连接层会校验：

```js
const origin = header(request.headers, "origin");
if (origin === undefined) return true;
return new URL(origin).host === hostUrl.host;   // 必须与 Host 一致
```

代理只把 `Host` 改写成了 `127.0.0.1:<端口>`，却把浏览器发来的
`Origin: http://192.168.1.14:8099` 原样转了过去 → 两者不一致 → **403**。
静态页面（GET，不带 Origin）能正常加载，所以界面看起来"是好的"。

### 修复

转发时把 `Origin` 和 `Referer` 一并改写为上游地址，HTTP 与 WebSocket 两条路径都改。

修复后实测：

```
proxy POST /api/session/list   -> 200
proxy POST /api/session/create -> 200
proxy POST /api/agentPresets/list -> 200
proxy POST /api/credentials/describe -> 200
```

### 顺带

- 增加 `DSH_LAN_PAIR_DEBUG=1` 时的请求级日志（`proxy <method> <path> -> <status>`），
  这类问题以后可以直接看日志定位，不用猜
- App 侧没有功能改动，版本号跟着对齐到 0.2.1

## 0.2.0：不用再复制密钥了

- 配对码：4 位数字、两分钟有效、只能使用一次、错 5 次作废
- 默认不广播：只有点「开启配对」后这台电脑才会出现在手机列表里
- DSH 设置页新增「移动设备」，一个按钮搞定，桌面同时弹出配对码窗口
- App 输入 4 位码即可，之后自动用长期凭据连接
