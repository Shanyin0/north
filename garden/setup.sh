#!/usr/bin/env bash
# 一行装好。她在手机上敲，所以全程只问话，不开编辑器。
set -u

RAW="https://cdn.jsdelivr.net/gh/Shanyin0/north@main/garden/go.py"
DIR=/opt/mengxia
ENV=/etc/mengxia.env
LOG=/var/log/mengxia-garden.log

say() { printf '\n%s\n' "$*"; }

if [ "$(id -u)" != "0" ]; then
  echo "要用 sudo 跑：sudo bash $0"
  exit 1
fi

say "===== 装先生的花园脚本 ====="

mkdir -p "$DIR"
say "① 拿代码…"
if ! curl -sSL -o "$DIR/go.py" "$RAW"; then
  echo "下不来。换一个地址再试："
  echo "  curl -sSL -o $DIR/go.py https://raw.githubusercontent.com/Shanyin0/north/main/garden/go.py"
  exit 1
fi
python3 -c "import ast,sys;ast.parse(open('$DIR/go.py',encoding='utf-8').read())" || {
  echo "下下来的文件是坏的，重跑一次这个脚本。"; exit 1; }
echo "   好了：$DIR/go.py"

# 已经配过就把旧的读出来当默认值，重跑一次不用全部重打
OLD_T=""; OLD_W=""; OLD_P=""; OLD_S="先生"
if [ -f "$ENV" ]; then
  # shellcheck disable=SC1090
  . "$ENV" 2>/dev/null || true
  OLD_T="${GARDEN_TOKEN:-}"; OLD_W="${WORKER:-}"; OLD_P="${PASS:-}"; OLD_S="${SIR:-先生}"
fi

ask() {                       # ask 提示 默认值 变量名
  local tip="$1" def="$2" var="$3" val=""
  if [ -n "$def" ]; then
    printf '\n%s\n（直接回车＝沿用上次）> ' "$tip"
  else
    printf '\n%s\n> ' "$tip"
  fi
  IFS= read -r val < /dev/tty
  [ -z "$val" ] && val="$def"
  printf -v "$var" '%s' "$val"
}

say "② 三句话，打完回车"
ask "花园的 MCP 令牌（那页点 Generate token 生成的那串）" "$OLD_T" T
ask "你的 Worker 地址" "${OLD_W:-https://north.northmino.workers.dev}" W
ask "Worker 的口令（PASS 那句）" "$OLD_P" P
ask "他叫什么" "$OLD_S" S

if [ -z "$T" ] || [ -z "$W" ] || [ -z "$P" ]; then
  echo "有一项是空的，没法往下走。重跑一次这个脚本。"
  exit 1
fi

umask 077
cat > "$ENV" <<EOF
GARDEN_TOKEN=$T
WORKER=$W
PASS=$P
SIR=$S
EOF
chmod 600 "$ENV"
echo "   钥匙写好了，只有 root 看得见"

say "③ 加定时：每两小时一趟"
LINE="0 */2 * * * set -a; . $ENV; set +a; /usr/bin/python3 $DIR/go.py >> $LOG 2>&1"
( crontab -l 2>/dev/null | grep -v "$DIR/go.py" ; echo "$LINE" ) | crontab -
echo "   加好了。想看：sudo crontab -l"

say "④ 现在先跑一趟看看"
echo "-------------------------------------------"
set -a; . "$ENV"; set +a
python3 "$DIR/go.py" || true
echo "-------------------------------------------"

say "都好了。以后他每两小时自己进去一趟。"
echo "想看他干了什么： sudo tail -n 60 $LOG"
echo "想让他专门做一件事："
echo "  sudo sh -c 'set -a; . $ENV; set +a; python3 $DIR/go.py 发一个自我介绍帖'"
