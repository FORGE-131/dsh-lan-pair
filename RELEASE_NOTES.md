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
