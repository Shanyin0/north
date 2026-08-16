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
OLD_T=""; OLD_W=""; OLD_P=""; OLD_S="先生"; OLD_M=""; OLD_U=""; OLD_UK=""
if [ -f "$ENV" ]; then
  # shellcheck disable=SC1090
  . "$ENV" 2>/dev/null || true
  OLD_T="${GARDEN_TOKEN:-}"; OLD_W="${WORKER:-}"; OLD_P="${PASS:-}"
  OLD_S="${SIR:-先生}"; OLD_M="${MODEL:-}"
  OLD_U="${UPSTREAM:-}"; OLD_UK="${UPSTREAM_KEY:-}"
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
ask "用哪个模型（跟手机上填的那个一样）" "${OLD_M:-gpt-4o-mini}" M

if [ -z "$T" ] || [ -z "$W" ] || [ -z "$P" ]; then
  echo "有一项是空的，没法往下走。重跑一次这个脚本。"
  exit 1
fi

# 这台机出不出得去？国内的机器连 *.workers.dev 常常是不通的。
# 通不了就当场问她模型站，直连，不绕 Worker 那一道
U="$OLD_U"; UK="$OLD_UK"
printf '\n试试这台机能不能连上 Worker… '
if curl -sS -m 20 -o /dev/null "$W/" 2>/dev/null; then
  echo "通"
  U=""; UK=""          # 通了就不用直连那条路，把旧的清掉免得抢
else
  echo "不通"
  echo "  这台机出不去 $W。八成是国内的机器连不上 workers.dev。"
  echo "  那就绕开它，直接填模型站 —— key 会存在这台机上（只有 root 看得见）。"
  ask "模型站地址（例如 https://api.deepseek.com）" "$U" U
  ask "那个站的 key" "$UK" UK
fi

umask 077
cat > "$ENV" <<EOF
GARDEN_TOKEN=$T
WORKER=$W
PASS=$P
SIR=$S
MODEL=$M
UPSTREAM=$U
UPSTREAM_KEY=$UK
EOF
chmod 600 "$ENV"
echo "   钥匙写好了，只有 root 看得见"

# 日志给谁都能看。钥匙在 /etc/mengxia.env 里，日志里没有钥匙，
# 她用普通账号 tail 一下不该还要 sudo
touch "$LOG"; chmod 644 "$LOG"

say "③ 加定时：每两小时一趟"
LINE="0 */2 * * * set -a; . $ENV; set +a; /usr/bin/python3 $DIR/go.py >> $LOG 2>&1"
( crontab -l 2>/dev/null | grep -v "$DIR/go.py" ; echo "$LINE" ) | crontab -
echo "   加好了。想看：sudo crontab -l"

# 一句 mengxia 顶一长串。她在手机上敲，能少打一个字是一个
cat > /usr/local/bin/mengxia <<EOF
#!/bin/sh
# mengxia        让他进去一趟（后台跑，关掉页面也不会断）
# mengxia log    看他干了什么
# mengxia 一句话  让他专门去做这件事
case "\${1:-}" in
  log|日志) exec tail -n 60 $LOG ;;
  ''|*)
    sudo sh -c 'set -a; . $ENV; set +a; setsid nohup python3 $DIR/go.py "\$@" >> $LOG 2>&1 &' _ "\$@"
    echo "他进去了。看他干什么： mengxia log"
    ;;
esac
EOF
chmod 755 /usr/local/bin/mengxia
echo "   顺手装了个 mengxia 命令"

say "④ 现在先跑一趟看看"
echo "-------------------------------------------"
# 用 setsid 甩开这个终端 —— 她在手机网页上开的黑框，一关就把前台进程带走了
set -a; . "$ENV"; set +a
setsid nohup python3 "$DIR/go.py" >> "$LOG" 2>&1 &
# 顶多等两分钟；她想关页面随时能关，那边照跑
for i in $(seq 1 60); do
  sleep 2
  if grep -q '他说：\|（这趟没说什么）\|连不上花园\|问不到模型' "$LOG" 2>/dev/null; then break; fi
done
tail -n 40 "$LOG"
echo "-------------------------------------------"

say "都好了。以后他每两小时自己进去一趟。"
echo "想看他干了什么： mengxia log"
echo "想让他现在就进去： mengxia"
echo "想让他专门做一件事： mengxia 发一个自我介绍帖"
