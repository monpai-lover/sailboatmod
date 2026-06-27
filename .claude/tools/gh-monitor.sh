#!/usr/bin/env bash
# GitHub/代理连通性监测器。每 INTERVAL 秒探一次 https://github.com（经本地 HTTP 代理),
# 只在【状态翻转】(通<->断) 时打印一行带时间戳的事件,避免刷屏。可选 --push:通的时候自动推待推 commit。
set -u
PROXY="${GH_MONITOR_PROXY:-http://127.0.0.1:7897}"
INTERVAL="${GH_MONITOR_INTERVAL:-15}"
BRANCH="${GH_MONITOR_BRANCH:-feature/road-planner-rebuild}"
AUTO_PUSH=0
[ "${1:-}" = "--push" ] && AUTO_PUSH=1

probe() { # echo HTTP code; 000 on failure (curl 失败时 %{http_code} 已是 000,不再额外 echo,避免拼成 000000)
  local c
  c=$(curl -sS --max-time 10 --proxy "$PROXY" -o /dev/null -w "%{http_code}" "https://github.com" 2>/dev/null)
  echo "${c:-000}"
}
ts() { date "+%Y-%m-%d %H:%M:%S"; }
# UP 仅当 HTTP 2xx/3xx/401/403(都代表 TLS 到 github 握手成功);000 及其它一律 down。
up_code() { case "$1" in 2[0-9][0-9]|3[0-9][0-9]|401|403) return 0;; *) return 1;; esac; }

echo "[$(ts)] gh-monitor start: proxy=$PROXY interval=${INTERVAL}s branch=$BRANCH auto_push=$AUTO_PUSH"
prev="init"; downstart=""
while true; do
  code=$(probe)
  if up_code "$code"; then state="up"; else state="down"; fi
  if [ "$state" != "$prev" ]; then
    if [ "$state" = "up" ]; then
      extra=""
      [ -n "$downstart" ] && extra=" (down ~$(( $(date +%s) - downstart ))s)"
      echo "[$(ts)] ✅ UP   http=$code$extra"
      if [ "$AUTO_PUSH" = "1" ]; then
        out=$(git push origin "$BRANCH" 2>&1)
        if echo "$out" | grep -q "$BRANCH ->\|up-to-date"; then
          echo "[$(ts)]    ⬆️  push OK"
        else
          echo "[$(ts)]    push failed: $(echo "$out" | tail -1)"
        fi
      fi
    else
      downstart=$(date +%s)
      echo "[$(ts)] ❌ DOWN http=$code"
    fi
    prev="$state"
  fi
  sleep "$INTERVAL"
done
