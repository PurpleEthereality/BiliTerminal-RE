# -*- coding: utf-8 -*-
"""本地自测：不改服务器、用临时 sqlite 跑一遍 WebUI + 会话 + 公告 + 遥测。

用法（在 server/ 目录下）：
    python selftest.py
"""
import os
import sys
import tempfile

TMP = tempfile.mkdtemp(prefix="rbt-selftest-")
os.environ["REBILITERMINAL_DB"] = os.path.join(TMP, "data.db")
os.environ["REBILITERMINAL_ADMIN_TOKEN"] = "selftest-token-1234567890"
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import rebiliterminal_api as api  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

client = TestClient(api.app, base_url="https://console.test")
TOKEN = "selftest-token-1234567890"
FAILED = []


def check(name, cond, extra=""):
    print(("  OK   " if cond else "  FAIL ") + name + ("" if cond else "  <- " + str(extra)))
    if not cond:
        FAILED.append(name)


print("== 1. 未登录 ==")
r = client.get("/")
check("GET / 200 且是登录页", r.status_code == 200 and "访问口令" in r.text, r.status_code)
r = client.get("/admin/api/stats")
check("GET /admin/api/stats 无凭据 403", r.status_code == 403, r.status_code)

print("== 2. 登录 ==")
r = client.post("/console/login", data={"token": "wrong"}, follow_redirects=False)
check("口令错误 401", r.status_code == 401, r.status_code)
r = client.post("/console/login", data={"token": TOKEN}, follow_redirects=False)
check("口令正确 303", r.status_code == 303, r.status_code)
cookie = r.headers.get("set-cookie", "")
check("Set-Cookie 有 HttpOnly", "HttpOnly" in cookie, cookie)
check("Set-Cookie 有 Secure", "Secure" in cookie, cookie)
check("Set-Cookie 有 SameSite=strict", "SameSite=strict" in cookie.lower() or "samesite=strict" in cookie.lower(), cookie)
check("Set-Cookie 值为签名形式", "rbt_console=" in cookie, cookie)

print("== 3. 会话可用 ==")
r = client.get("/")
check("带会话 GET / 返回 WebUI", r.status_code == 200 and "RE:哔哩终端控制台" in r.text, r.status_code)
r = client.get("/admin/api/stats")
check("带会话 stats code=0", r.status_code == 200 and r.json()["code"] == 0, r.text[:200])

print("== 4. 会话伪造 ==")
bad = client.get("/admin/api/stats", cookies={"rbt_console": "9999999999.deadbeef"})
check("伪造签名被拒", bad.status_code == 403, bad.status_code)
expired = client.get("/admin/api/stats", cookies={"rbt_console": api._session_value(int(api.time.time()) - 10)})
check("过期会话被拒", expired.status_code == 403, expired.status_code)

print("== 5. 旧 /admin?token= 仍然可用 ==")
r = client.get("/admin", params={"token": TOKEN})
check("旧看板 200 且有内容", r.status_code == 200 and "统计看板" in r.text, r.status_code)
r = client.get("/admin/api/stats", params={"token": TOKEN})
check("旧 JSON 接口 code=0", r.json()["code"] == 0, r.text[:200])

print("== 6. CSRF 防线 ==")
r = client.post("/admin/api/announcement", data={"title": "x", "content": "y"})
check("表单 Content-Type 被拒 415", r.status_code == 415, r.status_code)

print("== 7. 公告增删改 + 客户端可见性 ==")
r = client.post("/admin/api/announcement", json={"title": "本地自测公告", "content": "正文\n第二行", "pinned": True})
check("发布 code=0", r.json()["code"] == 0, r.text[:200])
ann_id = r.json()["id"]
check("public_id = 内部 id + 10 亿", r.json()["public_id"] == 1000000000 + ann_id, r.json())
r = client.get("/terminal/announcement/get_list")
data = r.json()["data"]
check("客户端能拉到该公告", len(data) == 1 and data[0]["title"] == "本地自测公告", data)
check("客户端看到的 id 带偏移", data[0]["id"] == 1000000000 + ann_id, data)
r = client.get("/terminal/announcement/get_list", params={"from": 1000000000})
check("from=10亿 差量也能拿到", len(r.json()["data"]) == 1, r.json())
r = client.post("/admin/api/announcement/update", json={"id": ann_id, "pinned": False})
check("取消置顶 code=0", r.json()["code"] == 0, r.text[:200])
r = client.post("/admin/api/announcement/update", json={"id": ann_id, "active": False})
check("停用 code=0", r.json()["code"] == 0, r.text[:200])
r = client.get("/terminal/announcement/get_list")
check("停用后客户端拉不到", r.json()["data"] == [], r.json())
r = client.post("/admin/api/announcement/delete", json={"id": ann_id})
check("删除 code=0", r.json()["code"] == 0, r.text[:200])
r = client.post("/admin/api/announcement/delete", json={"id": ann_id})
check("重复删除 404", r.status_code == 404, r.status_code)
r = client.get("/admin/api/announcements")
check("公告列表接口 code=0", r.json()["code"] == 0, r.text[:200])

print("== 8. 遥测去重 ==")
payload = {"install_id": "selftest-install-0001", "version_code": 2610090, "version_name": "26.10.09",
           "sdk": 34, "brand": "test", "device": "test", "abi": "arm64-v8a"}
for _ in range(3):
    r = client.post("/terminal/telemetry/ping", json=payload)
    check("ping code=0", r.json()["code"] == 0, r.text[:200])
r = client.get("/admin/api/stats")
d = r.json()["data"]
check("installs 只 1 行", d["total_installs"] == 1, d)
check("日活只 1 条", d["today_active"] == 1, d)
check("近7日活跃 1", d["week_active"] == 1, d)
check("24h 新增 1", d["today_new"] == 1, d)
check("版本分布有数据", d["versions"] and d["versions"][0]["version"] == "26.10.09", d["versions"])

print("== 9. 反馈 / 崩溃 ==")
r = client.post("/terminal/feedback/submit", json={"install_id": "selftest-install-0001", "category": "bug",
                                                   "content": "本地自测反馈", "version_name": "26.10.09"})
check("反馈 code=0 且有 id", r.json()["code"] == 0 and r.json().get("id"), r.text[:200])
r = client.post("/terminal/upload/stack", json={"install_id": "selftest-install-0001",
                                                "exception": "java.lang.NullPointerException",
                                                "message": "本地自测崩溃", "stack": "at com.example.A.b(A.kt:1)"})
check("崩溃 code=0 且有 id", r.json()["code"] == 0 and r.json().get("id"), r.text[:200])
r = client.get("/admin/api/feedback")
check("反馈列表能读到", r.json()["data"][0]["content"] == "本地自测反馈", r.json())
r = client.get("/admin/api/crash")
check("崩溃列表能读到", r.json()["data"][0]["exception"].endswith("NullPointerException"), r.json())

print("== 10. 退出 ==")
r = client.get("/console/logout", follow_redirects=False)
check("logout 303 并清 Cookie", r.status_code == 303 and "rbt_console=" in r.headers.get("set-cookie", ""), r.status_code)

print("== 11. 客户端接口不依赖 Cookie ==")
fresh = TestClient(api.app)
for path in ("/terminal/health",):
    r = fresh.get(path)
    check("无 Cookie " + path + " 可用", r.json()["code"] == 0, r.text[:200])

print()
if FAILED:
    print("失败 %d 项：%s" % (len(FAILED), ", ".join(FAILED)))
    sys.exit(1)
print("全部通过")
