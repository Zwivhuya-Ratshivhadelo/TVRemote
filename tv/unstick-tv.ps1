# Emergency: undoes the HOME-button change, records what the TV is doing, and gets the remote working again.
param([string]$TvIp = '')
. "$PSScriptRoot\tv-common.ps1"
Say "Getting the remote working again"
Connect-Tv $TvIp
$lines = @("== focus ==")
$lines += Shell dumpsys window windows | Select-String -Pattern 'mCurrentFocus|mFocusedApp' | ForEach-Object { "$_" }
$lines += "== log =="
$lines += ((& $script:adb -s $script:serial logcat -d -v time 2>&1) | ForEach-Object { "$_" } | Select-Object -Last 400)
$lines | Set-Content -Path (Join-Path $PSScriptRoot 'stuck-log.txt') -Encoding ascii

# 1. Undo the last change (Google's recovery screen back on).
Write-Host "  $((Shell pm enable --user 0 com.google.android.tungsten.setupwraith/.RecoveryActivity).Trim())"
Shell am force-stop com.livingroom.home | Out-Null
Shell input keyevent KEYCODE_HOME | Out-Null
Start-Sleep -Seconds 3
if (Ask "Try the remote now. Does it work?") { Good "remote works"; Disconnect-Tv; exit 0 }

# 2. Still stuck: switch Google's old home screen back on so the TV is usable.
Write-Host "  $((Shell pm enable com.google.android.apps.tv.launcherx).Trim())"
Shell input keyevent KEYCODE_HOME | Out-Null
Start-Sleep -Seconds 3
if (Ask "Try the remote now. Does it work?") { Good "remote works (Google's home screen is back on for now)" }
else { Warn "Turn the TV off at the wall for 10 seconds and back on." }
Disconnect-Tv
