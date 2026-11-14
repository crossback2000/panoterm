#!/bin/sh
# PanoTerm notification helper for Claude Code hooks.
# Sends an OSC 777 desktop notification to the terminal (works inside tmux via passthrough).
#
#   ~/.claude/settings.json:
#   {
#     "hooks": {
#       "Notification": [{ "hooks": [{ "type": "command", "command": "~/.claude/claude-notify.sh 'Claude Code' '입력을 기다립니다'" }] }],
#       "Stop":         [{ "hooks": [{ "type": "command", "command": "~/.claude/claude-notify.sh 'Claude Code' '작업이 끝났습니다'" }] }]
#     }
#   }
#
# tmux needs:  set -g allow-passthrough on
title=${1:-Claude Code}
body=${2:-알림}
seq=$(printf '\033]777;notify;%s;%s\033\\' "$title" "$body")
if [ -n "$TMUX" ]; then
  # tmux passthrough: ESC P tmux; <seq with ESC doubled> ESC \
  seq=$(printf '\033Ptmux;%s\033\\' "$(printf '%s' "$seq" | sed 's/\x1b/\x1b\x1b/g')")
fi
# Write to the controlling terminal (hooks have no stdout to the screen).
printf '%s' "$seq" > /dev/tty 2>/dev/null || true
