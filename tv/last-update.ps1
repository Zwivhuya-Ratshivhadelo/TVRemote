# The last time the laptop is needed: puts the TV app that updates itself on the TV.
# It downloads the TV app GitHub built (locked with your TV key), unlocks it and installs it.
param([string]$TvIp = '')
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
. "$here\tv-common.ps1"
$OUR_PKG = 'com.livingroom.home'

Say "1/3: downloading the TV app from GitHub"
$keyFile = Join-Path $here 'link-key.txt'
if (-not (Test-Path $keyFile)) { Fail "link-key.txt is missing from this folder." }
$secret = (Get-Content $keyFile -Raw).Trim()
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$locked = (New-Object Net.WebClient).DownloadData("https://raw.githubusercontent.com/Zwivhuya-Ratshivhadelo/TVRemote/dist/tv.cbc")
$sha = [Security.Cryptography.SHA256]::Create()
$encKey = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes("lrh-file-enc:$secret"))
$macKey = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes("lrh-file-mac:$secret"))
$body = New-Object byte[] ($locked.Length - 32); [Array]::Copy($locked, 0, $body, 0, $body.Length)
$tag = New-Object byte[] 32; [Array]::Copy($locked, $body.Length, $tag, 0, 32)
$mac = (New-Object Security.Cryptography.HMACSHA256 (, $macKey)).ComputeHash($body)
if ([Convert]::ToBase64String($mac) -ne [Convert]::ToBase64String($tag)) { Fail "The download didn't match your TV key." }
$aes = [Security.Cryptography.Aes]::Create(); $aes.Key = $encKey; $aes.Mode = 'CBC'; $aes.Padding = 'PKCS7'
$iv = New-Object byte[] 16; [Array]::Copy($body, 0, $iv, 0, 16); $aes.IV = $iv
$apk = $aes.CreateDecryptor().TransformFinalBlock($body, 16, $body.Length - 16)
$apkFile = Join-Path $here 'LivingRoomHome-github.apk'
[IO.File]::WriteAllBytes($apkFile, $apk)
Good "downloaded"

Say "2/3: installing on the TV"
$ipFile = Join-Path $here 'tv-ip.txt'
if (-not $TvIp -and (Test-Path $ipFile)) { $TvIp = (Get-Content $ipFile -Raw).Trim() }
Connect-Tv $TvIp
Set-Content -Path $ipFile -Value ($script:serial -replace ':\d+$', '') -Encoding ascii
$r = ((& $script:adb -s $script:serial install -r $apkFile 2>&1) | ForEach-Object { "$_" }) -join ' '
if ($r -match 'UPDATE_INCOMPATIBLE|signatures do not match') {
    # The old one was signed by this laptop; the new one by GitHub. Swap them once.
    & $script:adb -s $script:serial uninstall $OUR_PKG 2>&1 | Out-Null
    $r = ((& $script:adb -s $script:serial install $apkFile 2>&1) | ForEach-Object { "$_" }) -join ' '
}
if ($r -notmatch 'Success') { Fail "Install failed: $r" }
Good "installed"

Say "3/3: switching it on"
Shell cmd notification allow_listener "$OUR_PKG/$OUR_PKG.MediaWatch" | Out-Null
$SVC = "$OUR_PKG/$OUR_PKG.HomeKeyService"
$cur = (Shell settings get secure enabled_accessibility_services).Trim()
if ($cur -notmatch [regex]::Escape($SVC)) {
    $new = if (-not $cur -or $cur -eq 'null') { $SVC } else { "${cur}:$SVC" }
    Shell settings put secure enabled_accessibility_services $new | Out-Null
}
Shell settings put secure accessibility_enabled 1 | Out-Null
# Lets it install its own updates, and show the home screen after one.
Shell appops set $OUR_PKG REQUEST_INSTALL_PACKAGES allow | Out-Null
Shell appops set $OUR_PKG SYSTEM_ALERT_WINDOW allow | Out-Null
Shell appops set $OUR_PKG SCHEDULE_EXACT_ALARM allow | Out-Null
foreach ($perm in @('android.permission.ACCESS_COARSE_LOCATION', 'android.permission.ACCESS_FINE_LOCATION',
                    'android.permission.READ_TV_LISTINGS', 'com.android.providers.tv.permission.READ_EPG_DATA')) {
    Shell pm grant $OUR_PKG $perm | Out-Null
}
Shell am start -n "$OUR_PKG/.HomeActivity" | Out-Null
Disconnect-Tv
Good "done"

Write-Host ""
Write-Host "DONE. The TV now updates itself. You won't need the laptop, the IP address or Developer options again." -ForegroundColor Green
