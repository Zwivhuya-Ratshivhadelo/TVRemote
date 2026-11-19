# One click: builds the TV home screen and the phone remote, and installs the update on the TV.
# No questions, unless the TV needs pairing again.
param([string]$TvIp = '')
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
. "$here\tv-common.ps1"
$OUR_PKG = 'com.livingroom.home'

Say "1/4: shared key for your phone remote"
$keyFile = Join-Path $here 'link-key.txt'
if (-not (Test-Path $keyFile)) {
    $b = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
    Set-Content -Path $keyFile -Value (($b | ForEach-Object { $_.ToString('x2') }) -join '') -Encoding ascii
}
$key = (Get-Content $keyFile -Raw).Trim()
$xml = "<?xml version=`"1.0`" encoding=`"utf-8`"?>`r`n<resources>`r`n    <string name=`"link_key`" translatable=`"false`">$key</string>`r`n</resources>`r`n"
foreach ($p in @("$here\app\src\main\res\values\link.xml", "$here\PhoneRemote\app\src\main\res\values\link.xml")) {
    [System.IO.File]::WriteAllText($p, $xml, [System.Text.Encoding]::ASCII)
}
Good "ready (kept in link-key.txt, so the phone and TV always match)"

Say "2/4: building the TV home screen"
& powershell -NoProfile -ExecutionPolicy Bypass -File "$here\build.ps1"
if ($LASTEXITCODE -ne 0) { Fail "The TV build failed. Copy the red text above and send it to Claude." }

Say "3/4: building the phone remote"
& powershell -NoProfile -ExecutionPolicy Bypass -File "$here\PhoneRemote\build.ps1"
if ($LASTEXITCODE -ne 0) { Fail "The phone build failed. Copy the red text above and send it to Claude." }
Copy-Item "$here\PhoneRemote\PhoneRemote.apk" (Join-Path $here 'PhoneRemote.apk') -Force
Good "PhoneRemote.apk is in this folder"

Say "4/4: installing on the TV"
$ipFile = Join-Path $here 'tv-ip.txt'
if (-not $TvIp -and (Test-Path $ipFile)) { $TvIp = (Get-Content $ipFile -Raw).Trim() }
Connect-Tv $TvIp
Set-Content -Path $ipFile -Value ($script:serial -replace ':\d+$', '') -Encoding ascii
$r = ((& $script:adb -s $script:serial install -r (Join-Path $here 'LivingRoomHome.apk') 2>&1) | ForEach-Object { "$_" }) -join ' '
if ($r -notmatch 'Success') { Fail "Install failed: $r" }
# Lets the home screen see what is playing, for the remote's title, time and picture.
Shell cmd notification allow_listener "$OUR_PKG/$OUR_PKG.MediaWatch" | Out-Null
# Keeps the HOME helper (which also runs the remote link) switched on.
$SVC = "$OUR_PKG/$OUR_PKG.HomeKeyService"
$cur = (Shell settings get secure enabled_accessibility_services).Trim()
if ($cur -notmatch [regex]::Escape($SVC)) {
    $new = if (-not $cur -or $cur -eq 'null') { $SVC } else { "${cur}:$SVC" }
    Shell settings put secure enabled_accessibility_services $new | Out-Null
}
Shell settings put secure accessibility_enabled 1 | Out-Null
Shell am start -n "$OUR_PKG/.HomeActivity" | Out-Null
Disconnect-Tv
Good "TV updated"

Write-Host ""
Write-Host "DONE." -ForegroundColor Green
Write-Host "  Phone: send PhoneRemote.apk (in this folder) to your phone, tap it and press Install."
Write-Host "  Then turn Wireless debugging off on the TV."
