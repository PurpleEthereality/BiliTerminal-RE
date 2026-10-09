#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RE:哔哩终端 自建后端（公告 / 匿名遥测 / 反馈 / 崩溃报告）。

全部接口由 nginx 反代到 127.0.0.1:8788：

客户端接口（由 nginx 的 rebiliterminal.zsapp.asia 反代到 127.0.0.1:8788）：

  GET  /terminal/health                          健康检查
  GET  /terminal/announcement/get_list?from=<id> 公告（本服务器自有公告源）
  POST /terminal/telemetry/ping                  匿名安装数 / 日活
  POST /terminal/feedback/submit                 匿名反馈
  POST /terminal/upload/stack                    崩溃报告

管理后台（由 nginx 的 console.zsapp.asia 反代到同一个 127.0.0.1:8788）：

  GET  /                                  WebUI（未登录时是登录页）
  POST /console/login                     提交口令 -> 下发 HttpOnly 会话 Cookie
  GET  /console/logout                    清除会话
  GET  /admin/api/stats|feedback|crash    看板 JSON
  GET  /admin/api/announcements           公告列表（内部 id，不带偏移）
  POST /admin/api/announcement            发布公告 {title, content, pinned}
  POST /admin/api/announcement/update     置顶 / 停用 {id, pinned?, active?}
  POST /admin/api/announcement/delete     删除公告 {id}
  GET  /admin?token=<token>               旧版纯 HTML 看板（保留可用，作为兜底）

约定（与客户端 api/TerminalApi.java 一一对应）：
  * 成功一律 code = 0；失败 code != 0 且带 msg（HTTP 状态码同为 2xx，避免客户端把业务错误当网络错误）
  * 客户端接口全部 JSON、不带 Cookie；后台接口靠 Cookie 会话
  * install_id 是客户端首次启动生成的随机 UUID，与 B 站账号无关
  * 公告 id 对外统一加 ANN_ID_OFFSET 偏移，避免与上游 api.biliterminal.cn 的公告 id 撞车；
    客户端据此把两个公告源合并展示，互不干扰

并发说明（别把下面的写法"优化"回去）：
  * 业务函数一律是 async def（要 await request.body()），但 sqlite3 是阻塞库，
    所以每一段数据库访问都通过 run_in_threadpool 丢进线程池，绝不直接在事件循环里跑。
    否则一旦撞上 busy_timeout（15s），整个进程连 /terminal/health 都不响应。
  * connect() 里 synchronous=NORMAL：WAL 下断电最多丢最后一个事务、不会坏库，
    但省掉每次 commit 的 fsync —— 这是本服务写吞吐的真正天花板。

环境变量：
  REBILITERMINAL_DB            sqlite 文件路径，默认 /opt/rebiliterminal-api/data.db
  REBILITERMINAL_ADMIN_TOKEN   后台口令，留空则不开放后台（WebUI 与旧 /admin 同时失效）
"""

import hashlib
import hmac
import html
import json
import os
import sqlite3
import threading
import time
from datetime import datetime, timedelta, timezone
from urllib.parse import parse_qs

from fastapi import FastAPI, Request, Response
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from starlette.concurrency import run_in_threadpool

DB_PATH = os.environ.get("REBILITERMINAL_DB", "/opt/rebiliterminal-api/data.db")
ADMIN_TOKEN = os.environ.get("REBILITERMINAL_ADMIN_TOKEN", "")
CONSOLE_HTML_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "console.html")
SESSION_COOKIE = "rbt_console"
SESSION_TTL = 7 * 24 * 3600  # 会话有效期，7 天
TZ = timezone(timedelta(hours=8))
ANN_ID_OFFSET = 1_000_000_000
MAX_BODY = 2 * 1024 * 1024  # 2MB，崩溃报告可能带整段日志

app = FastAPI(title="RE:BiliTerminal backend", docs_url=None, redoc_url=None, openapi_url=None)

SCHEMA = """
CREATE TABLE IF NOT EXISTS installs (
    install_id   TEXT PRIMARY KEY,
    first_seen   INTEGER NOT NULL,
    last_seen    INTEGER NOT NULL,
    launch_count INTEGER NOT NULL DEFAULT 1,
    version_code INTEGER DEFAULT 0,
    version_name TEXT    DEFAULT '',
    is_beta      INTEGER DEFAULT 0,
    sdk          INTEGER DEFAULT 0,
    brand        TEXT    DEFAULT '',
    device       TEXT    DEFAULT '',
    abi          TEXT    DEFAULT ''
);
CREATE TABLE IF NOT EXISTS daily_active (
    install_id TEXT NOT NULL,
    day        TEXT NOT NULL,
    ctime      INTEGER NOT NULL,
    PRIMARY KEY (install_id, day)
);
CREATE INDEX IF NOT EXISTS idx_daily_day ON daily_active(day);
CREATE TABLE IF NOT EXISTS feedback (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    install_id   TEXT,
    category     TEXT,
    content      TEXT,
    contact      TEXT,
    mid          INTEGER DEFAULT 0,
    version_code INTEGER DEFAULT 0,
    version_name TEXT    DEFAULT '',
    sdk          INTEGER DEFAULT 0,
    brand        TEXT    DEFAULT '',
    device       TEXT    DEFAULT '',
    ip           TEXT    DEFAULT '',
    ctime        INTEGER NOT NULL,
    handled      INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS crash (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    install_id   TEXT,
    mid          INTEGER DEFAULT 0,
    exception    TEXT    DEFAULT '',
    message      TEXT    DEFAULT '',
    thread       TEXT    DEFAULT '',
    stack        TEXT,
    log          TEXT    DEFAULT '',
    extra        TEXT    DEFAULT '',
    version_code INTEGER DEFAULT 0,
    version_name TEXT    DEFAULT '',
    sdk          INTEGER DEFAULT 0,
    release      TEXT    DEFAULT '',
    brand        TEXT    DEFAULT '',
    device       TEXT    DEFAULT '',
    product      TEXT    DEFAULT '',
    model        TEXT    DEFAULT '',
    abi          TEXT    DEFAULT '',
    ram_total    INTEGER DEFAULT 0,
    ram_avail    INTEGER DEFAULT 0,
    uptime_sec   INTEGER DEFAULT 0,
    ip           TEXT    DEFAULT '',
    ctime        INTEGER NOT NULL,
    handled      INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS announcements (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    title   TEXT NOT NULL,
    content TEXT NOT NULL,
    ctime   INTEGER NOT NULL,
    active  INTEGER NOT NULL DEFAULT 1,
    pinned  INTEGER NOT NULL DEFAULT 0
);
"""


# --------------------------------------------------------------------------- #
# 基础设施
# --------------------------------------------------------------------------- #

def connect() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, timeout=15)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout=15000")
    # WAL 下的 NORMAL：不再每次 commit 都 fsync（FULL 是默认值）。
    # 代价是断电/掉电可能丢最后一个已提交事务，但数据库不会损坏 —— 对遥测数据完全可接受。
    # 这是本服务写吞吐的主要瓶颈，别删。
    conn.execute("PRAGMA synchronous=NORMAL")
    return conn


def init_db() -> None:
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    conn = connect()
    try:
        conn.execute("PRAGMA journal_mode=WAL")
        conn.executescript(SCHEMA)
        conn.commit()
    finally:
        conn.close()


def day_str(ts: int | None = None) -> str:
    return datetime.fromtimestamp(ts or time.time(), TZ).strftime("%Y-%m-%d")


def client_ip(request: Request) -> str:
    fwd = request.headers.get("x-forwarded-for", "")
    if fwd:
        return fwd.split(",")[0].strip()
    return request.client.host if request.client else ""


def as_int(value, default: int = 0) -> int:
    try:
        if isinstance(value, bool):
            return int(value)
        if isinstance(value, (int, float)):
            return int(value)
        return int(float(str(value).strip()))
    except Exception:
        return default


def as_str(value, limit: int) -> str:
    if value is None:
        return ""
    text = value if isinstance(value, str) else str(value)
    return text[:limit]


async def read_json(request: Request) -> dict:
    raw = await request.body()
    if len(raw) > MAX_BODY:
        return {}
    try:
        data = json.loads(raw.decode("utf-8", "replace"))
    except Exception:
        return {}
    return data if isinstance(data, dict) else {}


_rl_lock = threading.Lock()
_rl_buckets: dict = {}


def rate_limit(ip: str, bucket: str, limit: int, window: float) -> bool:
    """极简滑动窗口限流，够用即可；进程重启即清零。"""
    now = time.time()
    key = (ip, bucket)
    with _rl_lock:
        queue = _rl_buckets.setdefault(key, [])
        while queue and now - queue[0] > window:
            queue.pop(0)
        if len(queue) >= limit:
            return False
        queue.append(now)
        if len(_rl_buckets) > 20000:  # 兜底防止内存无上限增长
            for k in [k for k, v in _rl_buckets.items() if not v or now - v[-1] > 3600]:
                _rl_buckets.pop(k, None)
        return True


def ok(**payload) -> JSONResponse:
    body = {"code": 0, "msg": ""}
    body.update(payload)
    return JSONResponse(body)


def fail(code: int, msg: str) -> JSONResponse:
    return JSONResponse({"code": code, "msg": msg})


init_db()


# --------------------------------------------------------------------------- #
# 数据库工作单元
#
# 下面全是**同步**函数，一个函数 = 一次完整的「开连接 / 做事 / 提交 / 关连接」。
# api 层用 run_in_threadpool 调用它们，把阻塞的 sqlite3 挪出事件循环。
# 不要在 async def 里直接 connect()。
# --------------------------------------------------------------------------- #

def _db_ping(install_id: str, now: int, day: str, fields: tuple) -> None:
    conn = connect()
    try:
        row = conn.execute("SELECT install_id FROM installs WHERE install_id=?", (install_id,)).fetchone()
        if row is None:
            conn.execute(
                "INSERT INTO installs (install_id, first_seen, last_seen, launch_count,"
                " version_code, version_name, is_beta, sdk, brand, device, abi)"
                " VALUES (?,?,?,1,?,?,?,?,?,?,?)",
                (install_id, now, now) + fields,
            )
        else:
            conn.execute(
                "UPDATE installs SET last_seen=?, launch_count=launch_count+1, version_code=?,"
                " version_name=?, is_beta=?, sdk=?, brand=?, device=?, abi=? WHERE install_id=?",
                (now,) + fields + (install_id,),
            )
        conn.execute(
            "INSERT OR IGNORE INTO daily_active (install_id, day, ctime) VALUES (?,?,?)",
            (install_id, day, now),
        )
        conn.commit()
    finally:
        conn.close()


def _db_feedback(row: tuple) -> int:
    conn = connect()
    try:
        cur = conn.execute(
            "INSERT INTO feedback (install_id, category, content, contact, mid, version_code,"
            " version_name, sdk, brand, device, ip, ctime) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            row,
        )
        conn.commit()
        return cur.lastrowid
    finally:
        conn.close()


def _db_crash(row: tuple) -> int:
    conn = connect()
    try:
        cur = conn.execute(
            "INSERT INTO crash (install_id, mid, exception, message, thread, stack, log, extra,"
            " version_code, version_name, sdk, release, brand, device, product, model, abi,"
            " ram_total, ram_avail, uptime_sec, ip, ctime)"
            " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            row,
        )
        conn.commit()
        return cur.lastrowid
    finally:
        conn.close()


def _db_announcement_list(raw_from) -> list:
    """from 为空 -> 倒序（列表页）；from 有值 -> id 大于它的、正序（启动差量弹窗）。"""
    conn = connect()
    try:
        if raw_from is None or str(raw_from).strip() == "":
            rows = conn.execute(
                "SELECT id, title, content, ctime FROM announcements WHERE active=1"
                " ORDER BY pinned DESC, id DESC LIMIT 100"
            ).fetchall()
        else:
            last = as_int(raw_from, -1)
            rows = conn.execute(
                "SELECT id, title, content, ctime FROM announcements"
                " WHERE active=1 AND id + ? > ? ORDER BY id ASC LIMIT 100",
                (ANN_ID_OFFSET, last),
            ).fetchall()
    finally:
        conn.close()
    return [dict(r) for r in rows]


def _db_announcement_add(title: str, content: str, pinned: bool) -> int:
    conn = connect()
    try:
        cur = conn.execute(
            "INSERT INTO announcements (title, content, ctime, active, pinned) VALUES (?,?,?,1,?)",
            (title, content, int(time.time()), 1 if pinned else 0),
        )
        conn.commit()
        return cur.lastrowid
    finally:
        conn.close()


def _db_announcement_update(ann_id: int, pinned, active) -> bool:
    """pinned / active 传 None 表示这一项不改。返回是否命中了公告。"""
    sets, args = [], []
    if pinned is not None:
        sets.append("pinned=?")
        args.append(1 if pinned else 0)
    if active is not None:
        sets.append("active=?")
        args.append(1 if active else 0)
    if not sets:
        return False
    args.append(ann_id)
    conn = connect()
    try:
        cur = conn.execute(f"UPDATE announcements SET {', '.join(sets)} WHERE id=?", args)
        conn.commit()
        return cur.rowcount > 0
    finally:
        conn.close()


def _db_announcement_delete(ann_id: int) -> bool:
    conn = connect()
    try:
        cur = conn.execute("DELETE FROM announcements WHERE id=?", (ann_id,))
        conn.commit()
        return cur.rowcount > 0
    finally:
        conn.close()


# --------------------------------------------------------------------------- #
# 业务接口
# --------------------------------------------------------------------------- #

@app.on_event("startup")
def _startup() -> None:
    init_db()


@app.get("/terminal/health")
def health() -> JSONResponse:
    return ok(service="rebiliterminal", time=int(time.time()))


@app.post("/terminal/telemetry/ping")
async def telemetry_ping(request: Request) -> JSONResponse:
    """匿名安装 / 日活上报。同一 install_id 同一天只计一次日活。"""
    ip = client_ip(request)
    if not rate_limit(ip, "ping", 480, 3600):
        return fail(429, "too many requests")

    data = await read_json(request)
    install_id = as_str(data.get("install_id"), 64).strip()
    if len(install_id) < 8:
        return fail(400, "install_id required")

    now = int(time.time())
    day = day_str(now)
    fields = (
        as_int(data.get("version_code")),
        as_str(data.get("version_name"), 32),
        1 if data.get("is_beta") else 0,
        as_int(data.get("sdk")),
        as_str(data.get("brand"), 64),
        as_str(data.get("device"), 64),
        as_str(data.get("abi"), 32),
    )

    await run_in_threadpool(_db_ping, install_id, now, day, fields)
    return ok()


@app.post("/terminal/feedback/submit")
async def feedback_submit(request: Request) -> JSONResponse:
    ip = client_ip(request)
    if not rate_limit(ip, "feedback", 10, 3600):
        return fail(429, "提交太频繁了，请稍后再试")

    data = await read_json(request)
    content = as_str(data.get("content"), 4000).strip()
    if len(content) < 2:
        return fail(400, "内容太短了")
    category = as_str(data.get("category"), 32).strip() or "other"
    if category not in ("bug", "suggestion", "other", "content", "performance"):
        category = "other"

    now = int(time.time())
    new_id = await run_in_threadpool(
        _db_feedback,
        (
            as_str(data.get("install_id"), 64),
            category,
            content,
            as_str(data.get("contact"), 256),
            as_int(data.get("mid")),
            as_int(data.get("version_code")),
            as_str(data.get("version_name"), 32),
            as_int(data.get("sdk")),
            as_str(data.get("brand"), 64),
            as_str(data.get("device"), 64),
            ip,
            now,
        ),
    )
    return ok(id=new_id)


@app.post("/terminal/upload/stack")
async def upload_stack(request: Request) -> JSONResponse:
    """崩溃报告。相比上游只收 stack + 3 个设备字段，这里把排障需要的上下文一次收全。"""
    ip = client_ip(request)
    if not rate_limit(ip, "crash", 30, 3600):
        return fail(429, "too many requests")

    data = await read_json(request)
    stack = as_str(data.get("stack"), 300000)
    if not stack:
        return fail(400, "stack required")

    extra = data.get("extra")
    if isinstance(extra, (dict, list)):
        try:
            extra = json.dumps(extra, ensure_ascii=False)[:8000]
        except Exception:
            extra = ""
    else:
        extra = as_str(extra, 8000)

    now = int(time.time())
    new_id = await run_in_threadpool(
        _db_crash,
        (
            as_str(data.get("install_id"), 64),
            as_int(data.get("mid")),
            as_str(data.get("exception"), 256),
            as_str(data.get("message"), 2000),
            as_str(data.get("thread"), 128),
            stack,
            as_str(data.get("log"), 200000),
            extra,
            as_int(data.get("version_code")),
            as_str(data.get("version_name"), 32),
            as_int(data.get("sdk")),
            as_str(data.get("release"), 32),
            as_str(data.get("brand"), 64),
            as_str(data.get("device"), 64),
            as_str(data.get("product"), 64),
            as_str(data.get("model"), 64),
            as_str(data.get("abi"), 32),
            as_int(data.get("ram_total")),
            as_int(data.get("ram_avail")),
            as_int(data.get("uptime_sec")),
            ip,
            now,
        ),
    )
    return ok(id=new_id)


@app.get("/terminal/announcement/get_list")
async def announcement_list(request: Request, from_: str | None = None) -> JSONResponse:
    """本服务器自有公告源。

    from 为空  -> 公告列表页用，按 id 倒序（新的在前）
    from 有值  -> 启动时差量拉取，只返回 id 大于 from 的，且按 id 正序
                  （正序保证最新的那条最后弹出，最终留在最上面）
    """
    params = request.query_params
    raw_from = from_ if from_ is not None else params.get("from")
    rows = await run_in_threadpool(_db_announcement_list, raw_from)

    return ok(data=[
        {
            "id": ANN_ID_OFFSET + row["id"],
            "ctime": row["ctime"],
            "title": row["title"],
            "content": row["content"],
        }
        for row in rows
    ])


# --------------------------------------------------------------------------- #
# 后台看板
# --------------------------------------------------------------------------- #

def hmac_compare(a: str, b: str) -> bool:
    return hmac.compare_digest(a.encode(), b.encode())


# --- 会话：无状态签名 Cookie -------------------------------------------------- #
# 值形如 "<过期时间戳>.<hmac_sha256(ADMIN_TOKEN, 过期时间戳)>"。
# 不存服务端状态：进程重启会话不失效，改口令则全部会话立刻失效。

def _session_value(exp: int) -> str:
    mac = hmac.new(ADMIN_TOKEN.encode(), str(exp).encode(), hashlib.sha256).hexdigest()
    return f"{exp}.{mac}"


def _session_valid(request: Request) -> bool:
    exp_s, _, mac = request.cookies.get(SESSION_COOKIE, "").partition(".")
    if not ADMIN_TOKEN or not exp_s or not mac:
        return False
    exp = as_int(exp_s, 0)
    if exp <= int(time.time()):
        return False
    return hmac_compare(mac, _session_value(exp).split(".", 1)[1])


def _check_token(request: Request, token: str | None) -> bool:
    """旧口令（URL/header/query）与新的会话 Cookie 都认，任一通过即可。"""
    if not ADMIN_TOKEN:
        return False
    supplied = token or request.headers.get("x-admin-token") or request.query_params.get("token") or ""
    if supplied and hmac_compare(supplied, ADMIN_TOKEN):
        return True
    return _session_valid(request)


# --- WebUI -------------------------------------------------------------------- #

CONSOLE_LOGIN_HTML = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>登录 · RE:哔哩终端控制台</title>
<style>
  :root{color-scheme:light dark}
  *{box-sizing:border-box}
  body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
       font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;
       background:#f5f6f8;color:#1f2329}
  .card{width:min(360px,92vw);background:#fff;border:1px solid #e6e8eb;border-radius:14px;
        padding:30px 28px;box-shadow:0 8px 30px rgba(20,25,40,.06)}
  h1{margin:0 0 4px;font-size:19px}
  p.sub{margin:0 0 20px;font-size:13px;color:#8a9099}
  label{display:block;font-size:12px;color:#5c636b;margin-bottom:6px}
  input{width:100%;padding:11px 12px;font-size:14px;border:1px solid #d8dce0;border-radius:9px;
        outline:none;background:#fbfbfc}
  input:focus{border-color:#00a1d6;background:#fff}
  button{width:100%;margin-top:16px;padding:11px;font-size:14px;font-weight:600;color:#fff;border:0;
         border-radius:9px;background:#00a1d6;cursor:pointer}
  button:hover{background:#0091c2}
  .err{margin:0 0 14px;padding:9px 12px;font-size:13px;color:#c0392b;background:#fdf0ee;
       border:1px solid #f7d9d4;border-radius:9px}
  .hint{margin:18px 0 0;font-size:12px;line-height:1.7;color:#8a9099}
  @media (prefers-color-scheme:dark){
    body{background:#16181d;color:#e6e8eb}
    .card{background:#1e2128;border-color:#2c3038;box-shadow:none}
    p.sub,.hint,label{color:#8b929c}
    input{background:#14161a;border-color:#333842;color:#e6e8eb}
    .err{background:#3a1f1c;border-color:#5a2f29;color:#f0a99e}
  }
</style></head><body>
<form class="card" method="post" action="/console/login">
  <h1>RE:哔哩终端控制台</h1>
  <p class="sub">遥测 / 反馈 / 崩溃 / 公告</p>
  <!--ERR-->
  <label for="token">访问口令</label>
  <input id="token" name="token" type="password" autocomplete="current-password"
         placeholder="REBILITERMINAL_ADMIN_TOKEN" autofocus>
  <button type="submit">登录</button>
  <p class="hint">登录成功后写一个 7 天有效的 HttpOnly 会话 Cookie，
    地址栏里不会再出现口令。旧版 <code>/admin?token=…</code> 依然可用。</p>
</form></body></html>
"""

_console_cache = {"mtime": 0.0, "html": ""}


def console_html() -> str:
    """读同目录的 console.html，带 mtime 缓存 —— 改前端只需覆盖文件，不用重启进程。"""
    try:
        mtime = os.path.getmtime(CONSOLE_HTML_PATH)
    except OSError:
        return ("<meta charset='utf-8'><body style='font-family:sans-serif;padding:40px'>"
                "console.html 缺失：它必须和 rebiliterminal_api.py 放在同一目录</body>")
    if mtime != _console_cache["mtime"]:
        with open(CONSOLE_HTML_PATH, "r", encoding="utf-8") as fp:
            _console_cache["html"] = fp.read()
        _console_cache["mtime"] = mtime
    return _console_cache["html"]


@app.get("/", response_class=HTMLResponse)
def console_index(request: Request) -> HTMLResponse:
    if not ADMIN_TOKEN:
        return HTMLResponse(
            "<meta charset='utf-8'><body style='font-family:sans-serif;padding:40px'>"
            "后台未启用：REBILITERMINAL_ADMIN_TOKEN 为空</body>",
            status_code=503,
        )
    if not _session_valid(request):
        return HTMLResponse(CONSOLE_LOGIN_HTML)
    return HTMLResponse(console_html())


@app.post("/console/login")
async def console_login(request: Request) -> Response:
    if not rate_limit(client_ip(request), "login", 20, 3600):
        return HTMLResponse(CONSOLE_LOGIN_HTML.replace("<!--ERR-->", "<p class='err'>尝试太频繁，请稍后再试</p>"), status_code=429)
    raw = await request.body()
    form = {k: v[0] for k, v in parse_qs(raw.decode("utf-8", "replace")).items()}
    if not ADMIN_TOKEN or not hmac_compare(form.get("token", ""), ADMIN_TOKEN):
        return HTMLResponse(
            CONSOLE_LOGIN_HTML.replace("<!--ERR-->", "<p class='err'>口令不正确</p>"), status_code=401
        )
    resp = RedirectResponse("/", status_code=303)
    resp.set_cookie(
        SESSION_COOKIE,
        _session_value(int(time.time()) + SESSION_TTL),
        max_age=SESSION_TTL,
        path="/",
        httponly=True,   # JS 读不到
        secure=True,     # 只走 https
        samesite="strict",  # 跨站请求不带这个 Cookie —— 这就是本控制台的 CSRF 防线
    )
    return resp


@app.get("/console/logout")
def console_logout() -> RedirectResponse:
    resp = RedirectResponse("/", status_code=303)
    resp.delete_cookie(SESSION_COOKIE, path="/")
    return resp


def _stat_rows(conn, sql, args=()) -> list:
    return [tuple(r) for r in conn.execute(sql, args).fetchall()]


@app.get("/admin", response_class=HTMLResponse)
def admin(request: Request, token: str | None = None, msg: str | None = None) -> HTMLResponse:
    if not _check_token(request, token):
        return HTMLResponse(
            "<html><head><meta charset='utf-8'><title>RE:BiliTerminal 后台</title></head>"
            "<body style='font-family:sans-serif;padding:40px'>"
            "<h3>需要访问口令</h3>"
            "<form method='get' action='/admin'>"
            "<input name='token' placeholder='admin token' style='width:280px;padding:6px'>"
            "<button style='padding:6px 14px'>进入</button></form></body></html>",
            status_code=200,
        )

    conn = connect()
    try:
        today = day_str()
        total_installs = conn.execute("SELECT COUNT(*) FROM installs").fetchone()[0]
        today_active = conn.execute("SELECT COUNT(*) FROM daily_active WHERE day=?", (today,)).fetchone()[0]
        today_new = conn.execute(
            "SELECT COUNT(*) FROM installs WHERE first_seen>=?", (int(time.time()) - 86400,)
        ).fetchone()[0]
        week_active = conn.execute(
            "SELECT COUNT(DISTINCT install_id) FROM daily_active WHERE day>=?",
            ((datetime.now(TZ) - timedelta(days=7)).strftime("%Y-%m-%d"),),
        ).fetchone()[0]
        feedback_count = conn.execute("SELECT COUNT(*) FROM feedback").fetchone()[0]
        crash_count = conn.execute("SELECT COUNT(*) FROM crash").fetchone()[0]

        daily = _stat_rows(
            conn,
            "SELECT d.day, COUNT(*) AS active,"
            " (SELECT COUNT(*) FROM installs i WHERE date(i.first_seen,'unixepoch','+8 hours')=d.day) AS new_installs"
            " FROM daily_active d GROUP BY d.day ORDER BY d.day DESC LIMIT 30",
        )
        versions = _stat_rows(
            conn, "SELECT version_name, COUNT(*) FROM installs GROUP BY version_name ORDER BY 2 DESC LIMIT 12"
        )
        brands = _stat_rows(
            conn, "SELECT brand, COUNT(*) FROM installs GROUP BY brand ORDER BY 2 DESC LIMIT 12"
        )
        feedbacks = _stat_rows(
            conn,
            "SELECT id, category, content, contact, version_name, brand, device, ctime, handled"
            " FROM feedback ORDER BY id DESC LIMIT 50",
        )
        crashes = _stat_rows(
            conn,
            "SELECT id, exception, message, version_name, brand, device, model, sdk, ram_total,"
            " ram_avail, uptime_sec, substr(stack,1,1200), ctime FROM crash ORDER BY id DESC LIMIT 30",
        )
        announcements = _stat_rows(
            conn, "SELECT id, title, active, pinned, ctime FROM announcements ORDER BY id DESC LIMIT 50"
        )
    finally:
        conn.close()

    def table(rows, headers):
        if not rows:
            return "<p style='color:#888'>暂无数据</p>"
        head = "".join(f"<th style='text-align:left;padding:4px 10px;border-bottom:1px solid #ddd'>{html.escape(h)}</th>" for h in headers)
        body = []
        for row in rows:
            cells = "".join(
                f"<td style='padding:4px 10px;border-bottom:1px solid #f0f0f0;vertical-align:top'>{html.escape(str(c))}</td>"
                for c in row
            )
            body.append(f"<tr>{cells}</tr>")
        return f"<table style='border-collapse:collapse;font-size:13px'><tr>{head}</tr>{''.join(body)}</table>"

    def ts(value):
        return datetime.fromtimestamp(int(value), TZ).strftime("%Y-%m-%d %H:%M")

    feedback_rows = [(r[0], r[1], r[2], r[3], f"{r[4]} / {r[5]} {r[6]}", ts(r[7]), "已处理" if r[8] else "未处理") for r in feedbacks]
    crash_rows = [
        (r[0], r[1], r[2], f"{r[3]} / {r[4]} {r[5]}", f"sdk{r[6]} ram {r[7]//1048576}MB/{r[8]//1048576}MB up{r[9]}s", ts(r[11]), r[10])
        for r in crashes
    ]
    ann_rows = [(r[0], r[1], "启用" if r[2] else "停用", "置顶" if r[3] else "", ts(r[4])) for r in announcements]

    body = f"""<html><head><meta charset='utf-8'><title>RE:BiliTerminal 后台</title></head>
<body style='font-family:-apple-system,Segoe UI,sans-serif;padding:24px;background:#fafafa;color:#222'>
<h2>RE:BiliTerminal 统计看板</h2>
<p style='color:#666'>数据截至 {datetime.now(TZ).strftime('%Y-%m-%d %H:%M:%S')} (UTC+8){' · ' + html.escape(msg) if msg else ''}</p>
<div style='display:flex;gap:18px;flex-wrap:wrap;margin:18px 0'>
  <div style='background:#fff;border:1px solid #e5e5e5;border-radius:8px;padding:14px 22px'><div style='font-size:12px;color:#888'>总安装数</div><div style='font-size:26px;font-weight:600'>{total_installs}</div></div>
  <div style='background:#fff;border:1px solid #e5e5e5;border-radius:8px;padding:14px 22px'><div style='font-size:12px;color:#888'>今日活跃</div><div style='font-size:26px;font-weight:600'>{today_active}</div></div>
  <div style='background:#fff;border:1px solid #e5e5e5;border-radius:8px;padding:14px 22px'><div style='font-size:12px;color:#888'>近 7 日活跃</div><div style='font-size:26px;font-weight:600'>{week_active}</div></div>
  <div style='background:#fff;border:1px solid #e5e5e5;border-radius:8px;padding:14px 22px'><div style='font-size:12px;color:#888'>24h 新增安装</div><div style='font-size:26px;font-weight:600'>{today_new}</div></div>
  <div style='background:#fff;border:1px solid #e5e5e5;border-radius:8px;padding:14px 22px'><div style='font-size:12px;color:#888'>反馈</div><div style='font-size:26px;font-weight:600'>{feedback_count}</div></div>
  <div style='background:#fff;border:1px solid #e5e5e5;border-radius:8px;padding:14px 22px'><div style='font-size:12px;color:#888'>崩溃报告</div><div style='font-size:26px;font-weight:600'>{crash_count}</div></div>
</div>

<h3>每日活跃 / 新增</h3>
{table(daily, ['日期', '活跃设备', '当日新增安装'])}

<h3>版本分布</h3>
{table(versions, ['版本', '安装数'])}

<h3>品牌分布</h3>
{table(brands, ['品牌', '安装数'])}

<h3>发布公告</h3>
<form method='post' action='/admin/announcement' style='background:#fff;border:1px solid #e5e5e5;border-radius:8px;padding:14px;max-width:720px'>
  <input type='hidden' name='token' value='{html.escape(token or "")}'>
  <div style='margin-bottom:8px'><input name='title' placeholder='标题' style='width:98%;padding:6px'></div>
  <div style='margin-bottom:8px'><textarea name='content' placeholder='正文（支持 &lt;extra_insert&gt; 标记）' rows='6' style='width:98%;padding:6px'></textarea></div>
  <label><input type='checkbox' name='pinned' value='1'> 置顶</label>
  <div style='margin-top:10px'><button style='padding:6px 16px'>发布</button></div>
</form>
<p style='color:#888;font-size:12px'>客户端会把本服务器的公告与上游 api.biliterminal.cn 的公告合并展示；本服务器公告 id 统一带 10 亿偏移，不会与上游冲突。</p>

<h3>公告列表</h3>
{table(ann_rows, ['ID', '标题', '状态', '置顶', '时间'])}

<h3>最近 50 条反馈</h3>
{table(feedback_rows, ['ID', '分类', '内容', '联系方式', '版本 / 设备', '时间', '状态'])}

<h3>最近 30 条崩溃报告</h3>
{table(crash_rows, ['ID', '异常', '信息', '版本 / 设备', '运行环境', '时间', '堆栈摘要'])}
</body></html>"""
    return HTMLResponse(body)


@app.post("/admin/announcement")
async def admin_add_announcement(request: Request) -> HTMLResponse:
    raw = await request.body()
    form = {k: v[0] for k, v in parse_qs(raw.decode("utf-8", "replace")).items()}
    token = form.get("token", "") or request.query_params.get("token", "")
    if not _check_token(request, token):
        return HTMLResponse("<meta charset='utf-8'>口令错误", status_code=403)

    title = as_str(form.get("title"), 200).strip()
    content = as_str(form.get("content"), 20000).strip()
    if not title or not content:
        return HTMLResponse("<meta charset='utf-8'>标题和正文都不能为空 <a href='/admin?token=" + html.escape(token) + "'>返回</a>", status_code=200)

    conn = connect()
    try:
        conn.execute(
            "INSERT INTO announcements (title, content, ctime, active, pinned) VALUES (?,?,?,1,?)",
            (title, content, int(time.time()), 1 if form.get("pinned") else 0),
        )
        conn.commit()
    finally:
        conn.close()
    return HTMLResponse(
        "<meta charset='utf-8'>已发布，2 秒后返回…<meta http-equiv='refresh' content='2;url=/admin?token="
        + html.escape(token) + "'>"
    )


@app.get("/admin/api/stats")
def admin_stats(request: Request, token: str | None = None) -> JSONResponse:
    if not _check_token(request, token):
        return JSONResponse({"code": 403, "msg": "forbidden"}, status_code=403)
    now = int(time.time())
    week_from = (datetime.now(TZ) - timedelta(days=7)).strftime("%Y-%m-%d")
    conn = connect()
    try:
        data = {
            "server_time": now,
            "total_installs": conn.execute("SELECT COUNT(*) FROM installs").fetchone()[0],
            "today_active": conn.execute("SELECT COUNT(*) FROM daily_active WHERE day=?", (day_str(),)).fetchone()[0],
            "week_active": conn.execute(
                "SELECT COUNT(DISTINCT install_id) FROM daily_active WHERE day>=?", (week_from,)
            ).fetchone()[0],
            "today_new": conn.execute(
                "SELECT COUNT(*) FROM installs WHERE first_seen>=?", (now - 86400,)
            ).fetchone()[0],
            "daily": [
                {"day": r[0], "active": r[1], "new_installs": r[2]}
                for r in conn.execute(
                    "SELECT d.day, COUNT(*) AS active,"
                    " (SELECT COUNT(*) FROM installs i"
                    "  WHERE date(i.first_seen,'unixepoch','+8 hours')=d.day) AS new_installs"
                    " FROM daily_active d GROUP BY d.day ORDER BY d.day DESC LIMIT 60"
                )
            ],
            "versions": [
                {"version": r[0], "count": r[1]}
                for r in conn.execute(
                    "SELECT version_name, COUNT(*) FROM installs GROUP BY version_name ORDER BY 2 DESC LIMIT 12"
                )
            ],
            "brands": [
                {"brand": r[0], "count": r[1]}
                for r in conn.execute("SELECT brand, COUNT(*) FROM installs GROUP BY brand ORDER BY 2 DESC LIMIT 12")
            ],
            "feedback": conn.execute("SELECT COUNT(*) FROM feedback").fetchone()[0],
            "feedback_pending": conn.execute("SELECT COUNT(*) FROM feedback WHERE handled=0").fetchone()[0],
            "crash": conn.execute("SELECT COUNT(*) FROM crash").fetchone()[0],
            "crash_pending": conn.execute("SELECT COUNT(*) FROM crash WHERE handled=0").fetchone()[0],
            "announcements": conn.execute("SELECT COUNT(*) FROM announcements WHERE active=1").fetchone()[0],
        }
    finally:
        conn.close()
    return ok(data=data)


@app.get("/admin/api/feedback")
def admin_feedback(request: Request, token: str | None = None, limit: int = 200) -> JSONResponse:
    if not _check_token(request, token):
        return JSONResponse({"code": 403, "msg": "forbidden"}, status_code=403)
    conn = connect()
    try:
        rows = conn.execute(
            "SELECT id, category, content, contact, mid, version_name, brand, device, ctime"
            " FROM feedback ORDER BY id DESC LIMIT ?",
            (max(1, min(limit, 1000)),),
        ).fetchall()
    finally:
        conn.close()
    return ok(data=[dict(r) for r in rows])


@app.get("/admin/api/crash")
def admin_crash(request: Request, token: str | None = None, limit: int = 100) -> JSONResponse:
    if not _check_token(request, token):
        return JSONResponse({"code": 403, "msg": "forbidden"}, status_code=403)
    conn = connect()
    try:
        rows = conn.execute(
            "SELECT id, exception, message, thread, stack, version_name, brand, device, model, sdk,"
            " ram_total, ram_avail, uptime_sec, ctime FROM crash ORDER BY id DESC LIMIT ?",
            (max(1, min(limit, 500)),),
        ).fetchall()
    finally:
        conn.close()
    return ok(data=[dict(r) for r in rows])


# --- 公告管理（WebUI 用） ------------------------------------------------------ #
#
# 返回的是**内部 id**（没加 ANN_ID_OFFSET），因为它要回传给 update/delete。
# 客户端看到的 id 一律是 ANN_ID_OFFSET + 内部 id。

def _wants_json(request: Request) -> bool:
    """状态变更接口只收 application/json。

    会话 Cookie 是 SameSite=Strict，跨站表单本来就带不上它；再要求 JSON，
    等于让跨站发起方必须先过 CORS 预检 —— 两道互相独立的 CSRF 防线。
    """
    return request.headers.get("content-type", "").split(";")[0].strip().lower() == "application/json"


@app.get("/admin/api/announcements")
def admin_announcements(request: Request, token: str | None = None) -> JSONResponse:
    if not _check_token(request, token):
        return JSONResponse({"code": 403, "msg": "forbidden"}, status_code=403)
    conn = connect()
    try:
        rows = conn.execute(
            "SELECT id, title, content, active, pinned, ctime FROM announcements"
            " ORDER BY pinned DESC, id DESC LIMIT 200"
        ).fetchall()
    finally:
        conn.close()
    return ok(data=[dict(r) for r in rows])


@app.post("/admin/api/announcement")
async def admin_announcement_add(request: Request) -> JSONResponse:
    if not _check_token(request, None):
        return JSONResponse({"code": 403, "msg": "forbidden"}, status_code=403)
    if not _wants_json(request):
        return JSONResponse({"code": 415, "msg": "content-type must be application/json"}, status_code=415)
    data = await read_json(request)
    title = as_str(data.get("title"), 200).strip()
    content = as_str(data.get("content"), 20000).strip()
    if not title or not content:
        return JSONResponse({"code": 400, "msg": "标题和正文都不能为空"}, status_code=400)
    new_id = await run_in_threadpool(_db_announcement_add, title, content, bool(data.get("pinned")))
    return ok(id=new_id, public_id=ANN_ID_OFFSET + new_id)


@app.post("/admin/api/announcement/update")
async def admin_announcement_update(request: Request) -> JSONResponse:
    if not _check_token(request, None):
        return JSONResponse({"code": 403, "msg": "forbidden"}, status_code=403)
    if not _wants_json(request):
        return JSONResponse({"code": 415, "msg": "content-type must be application/json"}, status_code=415)
    data = await read_json(request)
    ann_id = as_int(data.get("id"), 0)
    if ann_id <= 0:
        return JSONResponse({"code": 400, "msg": "id required"}, status_code=400)
    pinned = data.get("pinned")
    active = data.get("active")
    changed = await run_in_threadpool(
        _db_announcement_update,
        ann_id,
        None if pinned is None else bool(pinned),
        None if active is None else bool(active),
    )
    if not changed:
        return JSONResponse({"code": 404, "msg": "公告不存在，或没有要改的字段"}, status_code=404)
    return ok()


@app.post("/admin/api/announcement/delete")
async def admin_announcement_delete(request: Request) -> JSONResponse:
    if not _check_token(request, None):
        return JSONResponse({"code": 403, "msg": "forbidden"}, status_code=403)
    if not _wants_json(request):
        return JSONResponse({"code": 415, "msg": "content-type must be application/json"}, status_code=415)
    data = await read_json(request)
    ann_id = as_int(data.get("id"), 0)
    if ann_id <= 0:
        return JSONResponse({"code": 400, "msg": "id required"}, status_code=400)
    if not await run_in_threadpool(_db_announcement_delete, ann_id):
        return JSONResponse({"code": 404, "msg": "公告不存在"}, status_code=404)
    return ok()
