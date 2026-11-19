# Puts the TV back exactly as it was before setup-tv.ps1:
# switches back on every app that setup switched off, restores the screensaver settings,
# makes Google's home screen the home screen again, and (if you say yes) removes the new home screen app.

param([string]$TvIp = '', [string]$Backup = '')

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here
. (Join-Path $here 'tv-common.ps1')

if (-not $Backup) {
    $latest = Get-ChildItem $here -Directory -Filter 'backup-*' | Sort-Object Name | Select-Object -Last 1
    if ($latest) { $Backup = $latest.FullName }
}
if (-not $Backup -or -not (Test-Path (Join-Path $Backup 'changes.txt'))) {
    Fail "No backup folder found next to this script. Nothing to restore."
}
$lines = @(Get-Content (Join-Path $Backup 'changes.txt') | Where-Object { $_.Trim() })
Write-Host "Using backup: $Backup ($($lines.Count) recorded changes)"

Say "Step 1: connecting to the TV"
Connect-Tv $TvIp

if (-not (Ask "Put everything back as it was before the setup?")) { Write-Host "Nothing changed."; Disconnect-Tv; exit 0 }

# Apps that setup switched off, minus any it already switched back on itself.
$reEnabledBySetup = @($lines | Where-Object { $_ -like "enable`t*" } | ForEach-Object { $_.Split("`t")[1] })
$homeBefore = ''
$installedOurs = $false

Say "Step 2: switching apps back on"
[array]::Reverse($lines)
foreach ($line in $lines) {
    $f = $line.Split("`t")
    switch ($f[0]) {
        'disable' {
            $pkg = $f[1]
            if ($reEnabledBySetup -contains $pkg) { continue }
            $r = Shell pm enable $pkg
            if ($r -match 'enabled') { Good "$pkg is on again" } else { Warn "$pkg : $r" }
        }
        'setting' {
            $key = $f[1]
            $old = if ($f.Count -gt 2) { $f[2] } else { '' }
            if (-not $old -or $old -eq 'null') { Shell settings delete secure $key | Out-Null }
            else { Shell settings put secure $key $old | Out-Null }
            Good "setting $key restored"
        }
        'home'      { $homeBefore = if ($f.Count -gt 1) { $f[1].Trim() } else { '' } }
        'installed' { $installedOurs = $true }
    }
}

Say "Step 3: making Google's home screen the home screen again"
if ($homeBefore -and $homeBefore -notlike 'com.livingroom.home*') {
    $r = Shell cmd package set-home-activity $homeBefore
    if ($r -match 'Success') { Good "home screen set back to $homeBefore" } else { Warn "Press Home on the TV and pick the Google TV home screen if it asks." }
} else {
    Warn "The old home screen was not recorded. Press Home on the TV and pick the Google TV home screen if it asks."
}

if ($installedOurs -and (Ask "Also remove the new home screen app from the TV?")) {
    $r = Shell pm uninstall com.livingroom.home
    if ($r -match 'Success') { Good "removed" } else { Warn $r }
}

Shell input keyevent KEYCODE_HOME | Out-Null
Disconnect-Tv
Write-Host ""
Write-Host "DONE. The TV is back the way it was." -ForegroundColor Green
Write-Host "Remember: turn USB debugging (and Wireless debugging) off again in Developer options."
Read-Host "Press Enter to close"
