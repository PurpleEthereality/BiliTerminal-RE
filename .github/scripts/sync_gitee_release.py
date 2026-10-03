#!/usr/bin/env python3
"""把一次发版的产物同步成 Gitee 发行版（Release + 附件），并输出发行版直链。

只同步**发行版**，不推送任何代码：创建 Gitee release（tag 指向 target_commitish），
把 ASSET_DIR 里的 APK / 校验文件作为附件上传，再写一份 release-links.txt 记录
Gitee 与 GitHub 两侧的直链，一并作为附件传上去。

用法（全部经环境变量传入，避免把 token 写进命令行）：
  GITEE_TOKEN       必填，Gitee 私人令牌（仓库级）
  GITEE_OWNER/REPO  Gitee 仓库坐标，默认 zisekongling / bili-terminal-re
  GITHUB_OWNER/REPO GitHub 仓库坐标，用于生成兜底直链
  TAG               发行 tag（如 26.10.03）
  RELEASE_NAME      Release 名称，留空用 TAG
  BODY_FILE         发布说明文件（Markdown），留空则用空说明
  ASSET_DIR         待上传文件目录，默认 app/build/outputs/apk/release
  KEEP              仅保留最近 N 个 Gitee 发行版，默认 3
  TARGET_COMMITISH  新 tag 的落点分支，默认 master
  LINKS_OUT         release-links.txt 输出路径，默认 ASSET_DIR/release-links.txt
"""

import json
import os
import sys
import time
import uuid
import urllib.error
import urllib.parse
import urllib.request

API = "https://gitee.com/api/v5"

# 普通 JSON 请求的超时（秒）
HTTP_TIMEOUT = int(os.environ.get("HTTP_TIMEOUT", "120"))
# 附件上传的超时（秒）。Gitee 的 attach_files 慢得出奇：实测从 GitHub runner 直传约 13KB/s，
# 10MB 的包要 759s。原来写死 180s 直接超时（发版 run 37117070245 就是这么挂的），这里放到 30 分钟。
UPLOAD_TIMEOUT = int(os.environ.get("UPLOAD_TIMEOUT", "1800"))
# 单个附件上传的重试次数
UPLOAD_RETRIES = int(os.environ.get("UPLOAD_RETRIES", "3"))
# 是否把 universal 包也传上 Gitee。默认不传：它 23.7MB，按上面的速度要半个多小时，
# 而 ABI 匹配不上的设备现在会正确回落 GitHub（见 util/UpdateRelease.kt 的 pickApkUrl）。
INCLUDE_UNIVERSAL = os.environ.get("GITEE_INCLUDE_UNIVERSAL", "false").strip().lower() == "true"


def log(msg):
    print(msg, flush=True)


def call(method, path, token, *, params=None, json_body=None, raw=None, content_type=None, timeout=None):
    """发一个 Gitee API 请求，返回 (status, 解码后的 body 或原始文本)。"""
    query = {"access_token": token}
    if params:
        query.update(params)
    url = f"{API}{path}?{urllib.parse.urlencode(query)}"
    headers = {"Accept": "application/json", "User-Agent": "biliterminal-release-sync"}
    data = None
    if json_body is not None:
        data = json.dumps(json_body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    elif raw is not None:
        data = raw
        headers["Content-Type"] = content_type
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout or HTTP_TIMEOUT) as resp:
            body = resp.read().decode("utf-8", "replace")
            return resp.status, body
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        # 超时 / 连接中断等：返回一个不可能被当成成功的状态码，交由调用方重试
        return -1, f"{e.__class__.__name__}: {e}"


def as_json(text):
    try:
        return json.loads(text)
    except Exception:
        return None


def build_multipart(field, filename, content):
    boundary = "----dsh" + uuid.uuid4().hex
    head = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'
        f"Content-Type: application/octet-stream\r\n\r\n"
    ).encode("utf-8")
    tail = f"\r\n--{boundary}--\r\n".encode("utf-8")
    return boundary, head + content + tail


def main():
    token = os.environ.get("GITEE_TOKEN", "").strip()
    if not token:
        log("::error::缺少 GITEE_TOKEN（仓库 Secrets 里配置）")
        return 1

    owner = os.environ.get("GITEE_OWNER", "zisekongling")
    repo = os.environ.get("GITEE_REPO", "bili-terminal-re")
    gh_owner = os.environ.get("GITHUB_OWNER", "zisekongling")
    gh_repo = os.environ.get("GITHUB_REPO", "BiliTerminal-RE")
    tag = os.environ.get("TAG", "").strip()
    if not tag:
        log("::error::缺少 TAG（没有 tag 就不该建发行版）")
        return 1
    name = os.environ.get("RELEASE_NAME", "").strip() or tag
    body_file = os.environ.get("BODY_FILE", "").strip()
    body = ""
    if body_file and os.path.isfile(body_file):
        with open(body_file, encoding="utf-8") as f:
            body = f.read()
    # Gitee 会以 HTTP 400「发行版的描述不能为空」拒绝空描述，这里兜个底
    if not body.strip():
        body = f"## {name}\n"
    asset_dir = os.environ.get("ASSET_DIR", "app/build/outputs/apk/release")
    keep = int(os.environ.get("KEEP", "3"))
    target = os.environ.get("TARGET_COMMITISH", "master").strip() or "master"
    links_out = os.environ.get("LINKS_OUT", os.path.join(asset_dir, "release-links.txt"))

    base = f"/repos/{owner}/{repo}"
    release_url = f"https://gitee.com/{owner}/{repo}/releases/download/{tag}"

    # ---- 1) 建发行版（已存在则复用，保证重跑幂等）----
    status, text = call("POST", f"{base}/releases", token, json_body={
        "tag_name": tag,
        "name": name,
        "body": body,
        "prerelease": False,
        "target_commitish": target,
    })
    release = as_json(text)
    if status == 201 and isinstance(release, dict):
        release_id = release.get("id")
        log(f"已创建 Gitee 发行版 {tag}（id={release_id}）")
    else:
        # 可能已存在；退回按 tag 找
        st2, tx2 = call("GET", f"{base}/releases", token, params={"per_page": 100})
        found = None
        for r in (as_json(tx2) or []):
            if r.get("tag_name") == tag:
                found = r
                break
        if not found:
            log(f"::error::创建 Gitee 发行版失败：HTTP {status} {text[:400]}")
            return 1
        release_id = found.get("id")
        log(f"Gitee 发行版 {tag} 已存在，复用 id={release_id}")

    # ---- 2) 上传附件 ----
    def existing_assets():
        st, tx = call("GET", f"{base}/releases/{release_id}", token)
        r = as_json(tx) or {}
        return {a.get("name") for a in (r.get("assets") or [])}

    uploaded = []
    have = existing_assets()
    files = []
    for entry in sorted(os.listdir(asset_dir)) if os.path.isdir(asset_dir) else []:
        path = os.path.join(asset_dir, entry)
        # release-links.txt 要等直链算出来再传，这里先跳过
        if entry == os.path.basename(links_out):
            continue
        # 只传真正的发行产物：APK 与校验文件。
        # 该目录里还有 AGP 生成的 output-metadata.json，别把它当附件传上去。
        if not (entry.endswith(".apk") or entry == "md5sums.txt"):
            continue
        # universal 默认不传（太大、太慢）；它缺席时客户端会按 ABI 找分包，找不到再回落 GitHub
        if entry == "app-universal-release.apk" and not INCLUDE_UNIVERSAL:
            log(f"按默认策略跳过 {entry}（它只在 ABI 都匹配不上时才用，客户端会回落 GitHub 取它）")
            continue
        if os.path.isfile(path):
            files.append((entry, path))

    if not files:
        log(f"::error::{asset_dir} 里没有可上传的文件")
        return 1

    for entry, path in files:
        if entry in have:
            log(f"跳过已存在的附件：{entry}")
            uploaded.append(entry)
            continue
        size = os.path.getsize(path)
        ok = False
        for attempt in range(1, UPLOAD_RETRIES + 1):
            with open(path, "rb") as f:
                content = f.read()
            boundary, payload = build_multipart("file", entry, content)
            started = time.time()
            log(f"上传 {entry}（{size} 字节）第 {attempt}/{UPLOAD_RETRIES} 次，超时上限 {UPLOAD_TIMEOUT}s …")
            status, text = call(
                "POST", f"{base}/releases/{release_id}/attach_files", token,
                raw=payload, content_type=f"multipart/form-data; boundary={boundary}",
                timeout=UPLOAD_TIMEOUT,
            )
            elapsed = time.time() - started
            if status == 201:
                log(f"已上传 {entry}（{size} 字节，耗时 {elapsed:.0f}s）")
                uploaded.append(entry)
                ok = True
                break
            # 附件已存在（重跑时可能已被前一次传上去）也算成功
            if status in (400, 409) and "已存在" in text:
                log(f"附件 {entry} 已存在于 Gitee，视为成功（HTTP {status}）")
                uploaded.append(entry)
                ok = True
                break
            log(f"::warning::上传 {entry} 失败（第 {attempt} 次，耗时 {elapsed:.0f}s）：HTTP {status} {text[:200]}")
            if attempt < UPLOAD_RETRIES:
                time.sleep(10 * attempt)
        if not ok:
            log(f"::error::{entry} 连续 {UPLOAD_RETRIES} 次上传失败")
            return 1

    # ---- 3) 生成发行版直链清单 ----
    lines = [
        f"# {name} 发行版直链",
        "",
        f"tag：{tag}",
        "",
        "## Gitee（首发，体积不受 GitHub 限制）",
        "",
    ]
    for entry in uploaded:
        lines.append(f"- {entry}")
        lines.append(f"  {release_url}/{entry}")
    lines += ["", "## GitHub（兜底）", ""]
    for entry in uploaded:
        lines.append(f"- {entry}")
        lines.append(f"  https://github.com/{gh_owner}/{gh_repo}/releases/download/{tag}/{entry}")
    lines.append("")
    links_text = "\n".join(lines)

    with open(links_out, "w", encoding="utf-8") as f:
        f.write(links_text)
    log("--- release-links.txt ---")
    log(links_text)

    # 把直链清单也作为 Gitee 附件传一份
    with open(links_out, "rb") as f:
        content = f.read()
    boundary, payload = build_multipart("file", os.path.basename(links_out), content)
    status, text = call(
        "POST", f"{base}/releases/{release_id}/attach_files", token,
        raw=payload, content_type=f"multipart/form-data; boundary={boundary}",
    )
    if status != 201:
        # 不算致命：GitHub 侧还会带一份
        log(f"::warning::上传 {os.path.basename(links_out)} 到 Gitee 失败：HTTP {status}")

    # ---- 4) 只保留最近 KEEP 个发行版 ----
    st, tx = call("GET", f"{base}/releases", token, params={"per_page": 100})
    releases = as_json(tx) or []
    releases = [r for r in releases if isinstance(r, dict) and r.get("id") is not None]
    releases.sort(key=lambda r: r.get("created_at") or "", reverse=True)
    log(f"Gitee 现有发行版 {len(releases)} 个：{[r.get('tag_name') for r in releases]}")
    for old in releases[keep:]:
        st, tx = call("DELETE", f"{base}/releases/{old['id']}", token, params={"access_token": token})
        if st in (204, 200):
            log(f"已删除旧发行版 {old.get('tag_name')}（id={old['id']}）")
        else:
            log(f"::warning::删除旧发行版 {old.get('tag_name')} 失败：HTTP {st} {tx[:200]}")

    log("Gitee 发行版同步完成")
    return 0


if __name__ == "__main__":
    sys.exit(main())
