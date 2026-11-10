#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ps_script="$script_dir/setup.ps1"

if command -v pwsh >/dev/null 2>&1; then
    powershell_bin="pwsh"
elif command -v powershell.exe >/dev/null 2>&1; then
    powershell_bin="powershell.exe"
elif command -v powershell >/dev/null 2>&1; then
    powershell_bin="powershell"
else
    echo "未找到 PowerShell，请先安装 PowerShell 7 或启用 Windows PowerShell" >&2
    exit 14
fi

exec "$powershell_bin" -NoProfile -ExecutionPolicy Bypass -File "$ps_script" "$@"