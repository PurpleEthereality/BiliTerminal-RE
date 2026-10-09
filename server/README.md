# RE:BiliTerminal 自建后端

给「RE:哔哩终端」提供公告、匿名遥测、反馈、崩溃报告四类接口，外加一个自用的管理控制台
（遥测看板 / 反馈 / 崩溃 / 公告发布）。FastAPI + SQLite，不引入 ORM、不引入前端构建链，
跟着仓库一起做版本管理。

两个域名，两套用途，nginx 上分得很开：

| 域名 | 用途 | 只反代 |
|---|---|---|
| `rebiliterminal.zsapp.asia` | 客户端接口（App 调） | `/terminal/`、`/admin` |
| `console.zsapp.asia` | 管理控制台 WebUI | `/`、`/console/*`、`/admin/api/*`（`/terminal/` 返回 404） |

## 接口一览

### 客户端接口（基址 `https://rebiliterminal.zsapp.asia`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/terminal/health` | 健康检查 |
| GET | `/terminal/announcement/get_list[?from=<id>]` | 本服务器自有公告源 |
| POST | `/terminal/telemetry/ping` | 匿名安装数 / 日活 |
| POST | `/terminal/feedback/submit` | 匿名反馈 |
| POST | `/terminal/upload/stack` | 崩溃报告 |

成功一律 `code = 0`，失败 `code != 0` 且带 `msg`，HTTP 状态码保持 2xx
（客户端只在 `code == 0` 时判定成功，网络异常才走 IOException 分支）。
这些接口**不读也不依赖任何 Cookie**，`install_id` 是客户端生成的随机 UUID。

### 控制台接口（基址 `https://console.zsapp.asia`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 未登录 → 登录页；已登录 → WebUI |
| POST | `/console/login` | 表单提交口令，下发会话 Cookie |
| GET | `/console/logout` | 清除会话 |
| GET | `/admin/api/stats` | 看板指标 + 近 60 天活跃/新增 + 版本 / 品牌分布 |
| GET | `/admin/api/feedback[?limit=]` | 反馈列表 |
| GET | `/admin/api/crash[?limit=]` | 崩溃列表 |
| GET | `/admin/api/announcements` | 公告列表（返回**内部 id**，要回传给 update/delete） |
| POST | `/admin/api/announcement` | 发布公告 `{title, content, pinned}` |
| POST | `/admin/api/announcement/update` | `{id, pinned?, active?}` |
| POST | `/admin/api/announcement/delete` | `{id}` |
| GET | `/admin?token=<token>` | 旧版纯 HTML 看板（保留兜底，不需要 Cookie） |

控制台接口可以用三种凭据之一：URL / 表单里的 `token`、`X-Admin-Token` 头、或登录后的会话 Cookie。

### 请求体

`POST /terminal/telemetry/ping`

```json
{
  "install_id": "8b6c…（首次启动生成的随机 UUID）",
  "version_code": 2610090, "version_name": "26.10.09", "is_beta": false,
  "sdk": 34, "brand": "Xiaomi", "device": "tokyo", "abi": "arm64-v8a"
}
```

同一 `install_id` + 同一天只计一次日活（`daily_active` 主键去重）。
唯一安装数 = `installs` 表行数。

`POST /terminal/feedback/submit`

```json
{
  "install_id": "…", "category": "bug|suggestion|other|content|performance",
  "content": "正文", "contact": "可选", "mid": 0,
  "version_code": 2610090, "version_name": "26.10.09",
  "sdk": 34, "brand": "Xiaomi", "device": "tokyo"
}
```

`mid` 默认 0（不附带）。客户端只有用户主动勾选「附带我的 B 站账号」时才会填真实 UID。

`POST /terminal/upload/stack`

```json
{
  "install_id": "…", "mid": 0,
  "exception": "java.lang.NullPointerException", "message": "…", "thread": "main",
  "stack": "完整堆栈", "log": "崩溃前的日志尾巴", "extra": {},
  "version_code": 2610090, "version_name": "26.10.09",
  "sdk": 34, "release": "13", "brand": "Xiaomi", "device": "tokyo",
  "product": "…", "model": "…", "abi": "arm64-v8a",
  "ram_total": 0, "ram_avail": 0, "uptime_sec": 0
}
```

比上游 `api.biliterminal.cn/terminal/upload/stack` 多收了排障需要的上下文
（异常类名、消息、线程、崩溃前日志、ABI、机型号、内存、运行时长）。
返回 `{"code":0,"id":<报告ID>}`，报告 ID 就是给用户看的报错号。

### 公告 id 约定

对外暴露的公告 id = 数据库自增 id + `1000000000`。
上游 `api.biliterminal.cn` 的公告 id 是正常小整数，加偏移后两个公告源合并展示时不会撞号，
客户端也能用两个独立的「已读到的最大 id」分别做启动弹窗差量拉取。

## 控制台 WebUI

`console.html` 是同目录下的单文件前端，由 `GET /` 原样吐出（带 mtime 缓存，**改前端只需覆盖文件，
不用重启进程**）。设计上的几条硬约束：

* **零外部依赖**：不引 CDN、不引图表库。国内访问 jsdelivr / unpkg 不稳，后台不该因为外网挂了就打不开。
  折线图是手写内联 SVG，悬停数值用原生 `<title>`。
* **登录态**：`POST /console/login` 校验口令后下发 `rbt_console` Cookie，属性是
  `HttpOnly; Secure; SameSite=strict; Max-Age=604800`。Cookie 值是
  `<过期时间戳>.<hmac_sha256(ADMIN_TOKEN, 过期时间戳)>` —— **无服务端状态**，
  进程重启会话不失效，改口令则所有会话立刻失效。
* **CSRF**：两道互相独立的防线 —— `SameSite=strict` 让跨站请求带不上 Cookie；
  状态变更接口额外要求 `Content-Type: application/json`（跨站表单只能发
  urlencoded / plain，因此必须先过 CORS 预检）。
* **会话失效**：任何 `/admin/api/*` 返回 403 时前端直接 `location.reload()` 回登录页。
* 旧口令路径（`/admin?token=…`、`X-Admin-Token`）**保留可用**，作为 WebUI 出问题时的兜底入口。

前端里所有服务端文本都过 `esc()`，公告正文允许换行但绝不 `innerHTML` 原始串。

## 部署

服务器：`HK`（154.222.27.216，Ubuntu 24.04，nginx 1.30.4 + 宝塔面板）。

```bash
# 1. 代码
mkdir -p /opt/rebiliterminal-api
# 把 rebiliterminal_api.py、console.html、backup.sh 放进去

# 2. 环境文件（后台口令自己换）
cat > /opt/rebiliterminal-api/env <<'EOF'
REBILITERMINAL_DB=/opt/rebiliterminal-api/data.db
REBILITERMINAL_ADMIN_TOKEN=<一个随机口令>
EOF

# 3. 服务
cp rebiliterminal-api.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now rebiliterminal-api
curl -s http://127.0.0.1:8788/terminal/health

# 4. nginx（客户端域名 + 控制台域名）
cp nginx/rebiliterminal.zsapp.asia.conf /www/server/panel/vhost/nginx/
cp nginx/console.zsapp.asia.conf      /www/server/panel/vhost/nginx/
nginx -t && nginx -s reload

# 5. 备份 + 探活 cron
install -m 755 backup.sh /opt/rebiliterminal-api/backup.sh
/opt/rebiliterminal-api/backup.sh          # 先手跑一次确认能出备份
( crontab -l 2>/dev/null; \
  echo '7 4 * * * /opt/rebiliterminal-api/backup.sh >/dev/null 2>&1' ; \
  echo '*/10 * * * * curl -fsS -m 10 http://127.0.0.1:8788/terminal/health >/dev/null 2>&1 || echo "$(date -Is) health check FAILED" >> /opt/rebiliterminal-api/health.log' \
) | crontab -
```

证书放在宝塔标准路径 `/www/server/panel/vhost/cert/<域名>/`，由 acme.sh 申请并自动续签
（webroot 模式，**绕开宝塔站点表** —— 宝塔的 acme 工具会去查 `data/default.db` 的 `sites` 表，
要求先建站点记录；webroot 模式更可脚本化、可复现）。

```bash
mkdir -p /www/wwwroot/console.zsapp.asia/.well-known/acme-challenge
# 注意：证书还没签出来时，vhost 不能引用证书文件，
# 先用一个只 listen 80 的引导配置（要带 location ^~ /.well-known/ { root …; }，
# 否则 acme 的 http-01 验证会 404），签完再换成完整配置
acme.sh --issue -d console.zsapp.asia --webroot /www/wwwroot/console.zsapp.asia \
        --keylength ec-256 --server letsencrypt
acme.sh --install-cert -d console.zsapp.asia --ecc \
        --key-file       /www/server/panel/vhost/cert/console.zsapp.asia/privkey.pem \
        --fullchain-file /www/server/panel/vhost/cert/console.zsapp.asia/fullchain.pem \
        --reloadcmd      "nginx -s reload"
```

`zsapp.asia` 是**泛解析**（`*.zsapp.asia` → 154.222.27.216），所以新域名不需要另外加 DNS 记录，
HTTP-01 直接能签。注意泛域名证书才需要 DNS-01，本方案是逐域名签，用不到。

### 并发

业务函数是 `async def`（要 `await request.body()`），但 sqlite3 是阻塞库，所以每一段数据库访问
都通过 `run_in_threadpool` 丢进线程池 —— **别"顺手优化"回同步调用**：一旦撞上
`busy_timeout`（15s），整个事件循环会卡死，连 `/terminal/health` 都不响应。

`connect()` 里设了 `PRAGMA synchronous=NORMAL`：WAL 下不再每次 commit 都 fsync
（这是写吞吐的真正天花板），代价是掉电可能丢最后一个已提交事务，但数据库不会损坏。

## 自测

`selftest.py` 用临时 sqlite + 临时口令跑一遍登录、会话伪造、CSRF、公告增删改、
遥测去重、反馈/崩溃入库等 39 项断言，**不会碰生产数据**：

```bash
cd /opt/rebiliterminal-api && python3 selftest.py
```

它依赖 starlette 的测试客户端（`starlette.testclient`，starlette 1.x 需要 `pip install httpx2`）。
生产服务器上没有装这个包，属正常 —— 服务器上请直接用 `curl` 打公网地址验证。

## 数据

SQLite 单文件 `/opt/rebiliterminal-api/data.db`，五张表：
`installs`（唯一安装 + 最近版本/设备）、`daily_active`（install_id × 日期去重）、
`feedback`、`crash`、`announcements`。

备份 = `backup.sh`，每天 04:07 由 cron 跑（`/opt/rebiliterminal-api/backups/data-<YYYYMMDD>.db`，保留 14 天）。
用的是 `sqlite3 .backup` 而不是 `cp`：**WAL 模式下直接拷 `data.db` 会丢掉还在 `-wal` 里的最近写入**。
脚本会顺手校验备份里的表数量，坏备份直接非零退出。

探活是 `*/10` 的 cron：curl 本机 `/terminal/health`，失败就往
`/opt/rebiliterminal-api/health.log` 追加一行。**注意这只是留痕，不是告警** ——
想让它在服务挂掉时主动叫你，还需要接一个通知渠道（当前没有）。
服务本身由 systemd `Restart` 兜底。
