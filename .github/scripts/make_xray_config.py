#!/usr/bin/env python3
"""把 VLESS 分享链接转成 xray-core 的客户端配置（本机 socks5 入站 → VLESS 出站）。

用途：GitHub Actions 里 Gitee 上传很慢甚至超时，起一个本地 socks5，
把 `https_proxy` 指向它，让**只有 Gitee 上传**走代理。

环境变量：
  VLESS_LINK  vless:// 分享链接（来自仓库 Secrets，勿写进代码库）
  OUT         输出路径，默认 xray-config.json
  SOCKS_PORT  本地 socks5 端口，默认 10808

只实现实际用得到的组合，遇到不支持的传输方式会明确报错而不是悄悄生成错配置。
"""

import json
import os
import sys
import urllib.parse


def parse_vless(link):
    if not link.startswith("vless://"):
        raise ValueError("不是 vless:// 链接")
    parsed = urllib.parse.urlparse(link)
    uuid = parsed.username
    if not uuid:
        raise ValueError("链接里没有 uuid")
    host = parsed.hostname
    port = parsed.port
    if not host or not port:
        raise ValueError("链接里没有 host/port")
    q = {k: v[0] for k, v in urllib.parse.parse_qs(parsed.query).items()}
    return uuid, host, port, q


def build_config(uuid, host, port, q, socks_port):
    security = q.get("security", "none")
    network = q.get("type", "tcp")
    flow = q.get("flow", "")

    stream = {"network": network}
    if network == "tcp":
        stream["tcpSettings"] = {"header": {"type": q.get("headerType", "none")}}
    elif network == "ws":
        stream["wsSettings"] = {
            "path": q.get("path", "/"),
            "headers": {"Host": q.get("host", q.get("sni", host))},
        }
    elif network == "grpc":
        stream["grpcSettings"] = {"serviceName": q.get("serviceName", "")}
    else:
        raise ValueError(f"暂不支持的传输方式 type={network}（只支持 tcp / ws / grpc）")

    if security == "reality":
        pbk = q.get("pbk")
        if not pbk:
            raise ValueError("REALITY 链接缺少 pbk（publicKey）")
        stream["security"] = "reality"
        stream["realitySettings"] = {
            "serverName": q.get("sni", ""),
            "fingerprint": q.get("fp", "chrome"),
            "publicKey": pbk,
            # 链接没有 sid 时用空串：REALITY 允许服务端配置空 shortId
            "shortId": q.get("sid", ""),
            "spiderX": q.get("spx", ""),
        }
    elif security == "tls":
        stream["security"] = "tls"
        stream["tlsSettings"] = {
            "serverName": q.get("sni", host),
            "fingerprint": q.get("fp", "chrome"),
            "allowInsecure": q.get("allowInsecure", "0") == "1",
        }
    elif security == "none":
        stream["security"] = "none"
    else:
        raise ValueError(f"暂不支持的安全类型 security={security}（只支持 reality / tls / none）")

    user = {"id": uuid, "encryption": q.get("encryption", "none")}
    if flow:
        user["flow"] = flow

    return {
        "log": {"loglevel": "warning"},
        "inbounds": [
            {
                "tag": "socks-in",
                "listen": "127.0.0.1",
                "port": socks_port,
                "protocol": "socks",
                "settings": {"udp": True},
            }
        ],
        "outbounds": [
            {
                "tag": "proxy",
                "protocol": "vless",
                "settings": {"vnext": [{"address": host, "port": port, "users": [user]}]},
                "streamSettings": stream,
            },
            {"tag": "direct", "protocol": "freedom"},
        ],
    }


def main():
    link = os.environ.get("VLESS_LINK", "").strip()
    if not link:
        print("::error::缺少 VLESS_LINK")
        return 1
    out = os.environ.get("OUT", "xray-config.json").strip() or "xray-config.json"
    socks_port = int(os.environ.get("SOCKS_PORT", "10808"))
    try:
        uuid, host, port, q = parse_vless(link)
        config = build_config(uuid, host, port, q, socks_port)
    except Exception as e:
        print(f"::error::解析 VLESS 链接失败：{e}")
        return 1

    with open(out, "w", encoding="utf-8") as f:
        json.dump(config, f, ensure_ascii=False, indent=2)
        f.write("\n")
    # 只打点非敏感信息，别把 uuid / pbk 打进日志
    print(f"已生成 {out}：host={host} port={port} network={q.get('type', 'tcp')} "
          f"security={q.get('security', 'none')} flow={q.get('flow', '')} socks=127.0.0.1:{socks_port}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
