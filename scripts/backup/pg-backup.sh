#!/usr/bin/env bash
# PostgreSQL 全量备份（自定义格式 + zlib 压缩）+ 按天轮转。
# 用法：
#   PGPASSWORD=... PGHOST=localhost bash scripts/backup/pg-backup.sh
# 依赖：pg_dump（与后端 PG 大版本一致的客户端）、find/date/du。
# 失败语义：任何一步失败都以非 0 退出，且不留下半截文件（临时文件被清掉）。
set -euo pipefail

# PGPASSWORD 必须显式提供：缺失时给出可读错误而不是让 pg_dump 卡在交互提示上
: "${PGPASSWORD:?PGPASSWORD is required (PostgreSQL password for pg_dump)}"

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-aerofleet}"
PGDATABASE="${PGDATABASE:-aerofleet_prod}"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
RETENTION_DAYS="${RETENTION_DAYS:-7}"

case "$RETENTION_DAYS" in
  '' | *[!0-9]*)
    echo "ERROR: RETENTION_DAYS must be a non-negative integer, got: $RETENTION_DAYS" >&2
    exit 1
    ;;
esac

mkdir -p "$BACKUP_DIR"

timestamp="$(date +%Y%m%d-%H%M%S)"
final_file="$BACKUP_DIR/pg-${timestamp}.dump"
tmp_file="$BACKUP_DIR/.pg-${timestamp}.dump.part"

# 中断（Ctrl-C / SIGTERM）也清掉临时文件，避免残留被误当备份
cleanup_tmp() {
  [ -f "$tmp_file" ] && rm -f "$tmp_file"
}
trap cleanup_tmp EXIT INT TERM

echo "==> pg_dump $PGUSER@$PGHOST:$PGPORT/$PGDATABASE -> $final_file"
if ! pg_dump -Fc -Z6 -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -f "$tmp_file"; then
  echo "ERROR: pg_dump failed, nothing kept" >&2
  exit 1
fi

# 先写临时文件再原子改名：不会出现"看起来是备份、实际写了一半"的文件
mv "$tmp_file" "$final_file"

deleted=0
while IFS= read -r old; do
  [ -n "$old" ] || continue
  rm -f "$old"
  deleted=$((deleted + 1))
done < <(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'pg-*.dump' -mtime "+${RETENTION_DAYS}" -print 2>/dev/null || true)

kept="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'pg-*.dump' | wc -l | tr -d ' ')"
total_size="$(du -sh "$BACKUP_DIR" 2>/dev/null | awk '{print $1}')"

echo "==> done: $final_file ($(du -h "$final_file" | awk '{print $1}'))"
echo "==> retention: removed ${deleted} file(s) older than ${RETENTION_DAYS} day(s)"
echo "==> backups kept: ${kept}, dir size: ${total_size:-unknown}"