#!/usr/bin/env python3
"""生成给**老版本客户端**用的一次性 config.json（迁移用）。

老客户端（26.10.02 及更早）的更新检查读的是单独部署在 123pan 上的 config.json，
不认识"读发行版"的新机制。这里按老格式产出一份：downloadUrl 指向 Gitee 直链、
forceUpdate 置 true，把老客户端强制带到新版本，从此它们也走新链路。
之后这份文件即可永久弃用（新版本不再需要它）。

老客户端的解析器（历史 `UpdateManager.parseConfig`）兼容字符串与原生类型，
所以这里直接写规范的 JSON 类型（number / boolean）即可。

环境变量：
  VERSION_CODE      版本号数字，来自 app/build.gradle
  VERSION_NAME      版本名，来自 app/build.gradle
  DESCRIPTION_FILE  发布说明文件（Markdown），可空
  DOWNLOAD_URL      APK 直链（Gitee 优先，兜底 GitHub）
  FORCE_UPDATE      "true"/"false"
  OUT               输出路径，默认 config.json
"""

import json
import os
import sys


def main():
    code = os.environ.get("VERSION_CODE", "").strip()
    name = os.environ.get("VERSION_NAME", "").strip()
    url = os.environ.get("DOWNLOAD_URL", "").strip()
    force = os.environ.get("FORCE_UPDATE", "false").strip().lower() == "true"
    desc_file = os.environ.get("DESCRIPTION_FILE", "").strip()
    out = os.environ.get("OUT", "config.json").strip() or "config.json"

    if not code.isdigit() or not name or not url:
        print(f"::error::生成 config.json 缺少必要入参：versionCode=「{code}」versionName=「{name}」downloadUrl=「{url}」")
        return 1

    description = ""
    if desc_file and os.path.isfile(desc_file):
        with open(desc_file, encoding="utf-8") as f:
            description = f.read().strip()

    config = {
        "versionCode": int(code),
        "versionName": name,
        "description": description,
        "downloadUrl": url,
        "forceUpdate": force,
    }
    with open(out, "w", encoding="utf-8") as f:
        json.dump(config, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print(f"已生成 {out}（给老客户端的一次性迁移文件）：")
    print(open(out, encoding="utf-8").read())
    return 0


if __name__ == "__main__":
    sys.exit(main())
