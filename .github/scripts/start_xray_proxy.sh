#!/usr/bin/env bash
# 起一个本地 socks5（xray-core + VLESS），用于给 Gitee 上传提速 / 绕开超时。
#
# 只在配置了 VLESS_PROXY_LINK 时才真起代理；没配就什么都不做（直连）。
# 无论哪种情况都把结果写到 $GITHUB_OUTPUT 的 proxy_url：
#   - 代理可用 → socks5h://127.0.0.1:<port>
#   - 没配 / 起不来 / 连不通 → 空字符串（调用方据此退回直连，不要让整步失败）
#
# 需要环境变量：VLESS_LINK（可为空）、SOCKS_PORT（默认 10808）、GITHUB_OUTPUT（可选）
set -u

PORT="${SOCKS_PORT:-10808}"
WORK="${RUNNER_TEMP:-/tmp}/xray-proxy"

emit() {
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "proxy_url=$1" >> "$GITHUB_OUTPUT"
  fi
}

if [ -z "${VLESS_LINK:-}" ]; then
  echo "未配置 VLESS_PROXY_LINK，Gitee 上传直连"
  emit ""
  exit 0
fi

mkdir -p "$WORK" && cd "$WORK" || { emit ""; exit 0; }

echo "下载 xray-core …"
if ! curl -sSL --retry 3 --max-time 180 -o xray.zip \
    https://github.com/XTLS/Xray-core/releases/latest/download/Xray-linux-64.zip; then
  echo "::warning::xray-core 下载失败，Gitee 上传退回直连"
  emit ""
  exit 0
fi
unzip -oq xray.zip || { echo "::warning::xray 解压失败，退回直连"; emit ""; exit 0; }
chmod +x xray

# 生成配置（uuid / pbk 等敏感值不会打进日志）
if ! VLESS_LINK="$VLESS_LINK" OUT="$WORK/config.json" SOCKS_PORT="$PORT" \
      python3 "$GITHUB_WORKSPACE/.github/scripts/make_xray_config.py"; then
  echo "::warning::VLESS 链接解析失败，Gitee 上传退回直连"
  emit ""
  exit 0
fi

nohup ./xray run -c "$WORK/config.json" > "$WORK/xray.log" 2>&1 &
echo "xray 已启动（pid=$!），探活中 …"
sleep 4

if curl -sS --max-time 30 --proxy "socks5h://127.0.0.1:$PORT" -o /dev/null https://gitee.com/; then
  echo "代理可用：Gitee 上传将走 socks5h://127.0.0.1:$PORT"
  emit "socks5h://127.0.0.1:$PORT"
else
  echo "::warning::代理起不来或连不通，Gitee 上传退回直连"
  echo "--- xray.log 尾部 ---"
  tail -20 "$WORK/xray.log" 2>/dev/null || true
  emit ""
fi
