#!/usr/bin/env python3
"""从 strings.xml 抽出当版「本次更新」日志，供发版工作流当 Release 说明正文。

**为什么需要它**：发版工作流有两条触发路径 —— 手工触发（`workflow_dispatch`）与推 tag。
推 tag 时 `inputs.*` 恒为空，拿不到人填的 `release_body`，于是 Release 说明只剩
`### APK 校验值（MD5）` 与 `<!-- update: … -->` 机器元数据，用户点进去看不到这版改了什么。
（26.10.05 就是这么漏掉的。）本脚本让推 tag 也能自动带上完整更新日志。

**语义**：`update_log_current` 是当版日志的单一真相（关于页读的就是它，锚点也由
`:app:verifyVersionConsistency` 校验），所以直接从它抽，不做二次维护。

环境变量 / 参数：
  argv[1]   strings.xml 路径，默认 app/src/main/res/values/strings.xml

输出：
  更新日志条目，每行一条，已去掉 `<item>` 标签并反转义 XML 实体（含首行【YY.MM.DD 本次更新】）。
  空条目（历史里用来分段的空 `<item></item>`）会被跳过。
  抽不到任何条目时**打印 ::error:: 并以非 0 退出** —— 宁可让发版失败，
  也不要发出一个没有更新说明的 Release。
"""

import html
import re
import sys

ARRAY_NAME = "update_log_current"
DEFAULT_STRINGS = "app/src/main/res/values/strings.xml"


def main(argv):
    path = argv[1] if len(argv) > 1 else DEFAULT_STRINGS

    # 明确按 UTF-8 输出：正文要被 shell 捕获后写进 $GITHUB_OUTPUT，那里必须是 UTF-8。
    # 不显式指定时 Python 在 stdout 被重定向的场合会退回系统 locale 编码
    # （中文 Windows 上是 GBK），CI 之外手工跑就会变成乱码。
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", newline="\n")

    try:
        with open(path, encoding="utf-8") as f:
            text = f.read()
    except OSError as e:
        print(f"::error::读取 {path} 失败：{e}")
        return 1

    # 只认 update_log_current 这一块，别把 update_history_log / update_log_items 也卷进来。
    block = re.search(
        r'<string-array\s+name="%s"\s*>(.*?)</string-array>' % re.escape(ARRAY_NAME),
        text,
        re.S,
    )
    if not block:
        print(f'::error::{path} 里找不到 <string-array name="{ARRAY_NAME}">')
        return 1

    lines = []
    for raw in re.findall(r"<item>(.*?)</item>", block.group(1), re.S):
        item = html.unescape(raw).strip()
        if item:
            lines.append(item)

    if not lines:
        print(
            f"::error::{path} 的 {ARRAY_NAME} 是空的，Release 说明会缺少更新日志，拒绝发版"
        )
        return 1

    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
