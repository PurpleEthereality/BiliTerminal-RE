#!/bin/bash
# RE:哔哩终端 自建后端每日备份。
#
#   sqlite3 .backup 是在线备份：会自己处理 WAL，不锁库，服务不用停。
#   直接 cp data.db 是不够的 —— WAL 模式下最近的写入还在 -wal 文件里，拷出来会丢数据。
#
# 安装：
#   install -m 755 backup.sh /opt/rebiliterminal-api/backup.sh
#   (crontab -l; echo '7 4 * * * /opt/rebiliterminal-api/backup.sh >/dev/null 2>&1') | crontab -
set -euo pipefail

DIR=/opt/rebiliterminal-api
OUT="$DIR/backups"
KEEP_DAYS=14

mkdir -p "$OUT"
STAMP=$(date +%Y%m%d)
sqlite3 "$DIR/data.db" ".backup $OUT/data-$STAMP.db"

# 顺手校验备份可读（能读出表数量说明不是半截文件）
TABLES=$(sqlite3 "$OUT/data-$STAMP.db" "SELECT COUNT(*) FROM sqlite_master WHERE type='table';")
if [ "$TABLES" -lt 5 ]; then
    echo "backup looks broken: only $TABLES tables" >&2
    exit 1
fi

find "$OUT" -name 'data-*.db' -mtime +"$KEEP_DAYS" -delete
echo "ok $STAMP tables=$TABLES size=$(du -h "$OUT/data-$STAMP.db" | cut -f1)"
