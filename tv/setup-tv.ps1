# Sets up the new home screen on the Skyworth Google TV, over Wi-Fi.
#
# Rules this script follows:
#   - It backs up the TV's app list and settings BEFORE changing anything.
#   - It asks you before every change.
#   - It only switches apps OFF (disable). It never deletes the TV's own apps.
#   - Every change is written to a log, and restore-tv.cmd puts everything back.
#   - Google's home screen is only switched off after you confirm the new one works,
#     and it is switched straight back on if the new one fails.

param([string]$TvIp = '')

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here
. (Join-Path $here 'tv-common.ps1')

$OUR_PKG   = 'com.livingroom.home'
$OUR_HOME  = 'com.livingroom.home/.HomeActivity'
$OUR_SAVER = 'com.livingroom.home/.ClockDream'

$apk = Join-Path $here 'LivingRoomHome.apk'
if (-not (Test-Path $apk)) { Fail "LivingRoomHome.apk was not found. The build step did not finish." }

# ---------------------------------------------------------------- 1. connect
Say "Step 1: connecting to the TV"
Connect-Tv $TvIp

# ---------------------------------------------------------------- 2. backup
Say "Step 2: backing up the TV's app list and settings (nothing is changed yet)"
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
$backup = Join-Path $here "backup-$stamp"
New-Item -ItemType Directory -Force -Path $backup | Out-Null
$log = Join-Path $backup 'changes.txt'
New-Item -ItemType File -Force -Path $log | Out-Null
function Log($line) { Add-Content -Path $log -Value $line -Encoding ASCII }

(Shell pm list packages -u) | Set-Content (Join-Path $backup 'packages-all.txt') -Encoding ASCII
(Shell pm list packages -d) | Set-Content (Join-Path $backup 'packages-disabled-before.txt') -Encoding ASCII
(Shell pm list packages -s) | Set-Content (Join-Path $backup 'packages-system.txt') -Encoding ASCII

$homeBefore = ((Shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN) -split "`n" |
    Where-Object { $_ -match '/' } | Select-Object -Last 1)
if ($homeBefore) { $homeBefore = $homeBefore.Trim() }
$settingsBefore = @{}
foreach ($k in @('screensaver_components', 'screensaver_enabled', 'screensaver_default_component')) {
    $settingsBefore[$k] = (Shell settings get secure $k).Trim()
}
$settingsBefore.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" } |
    Set-Content (Join-Path $backup 'settings-before.txt') -Encoding ASCII
"home=$homeBefore" | Add-Content (Join-Path $backup 'settings-before.txt') -Encoding ASCII

$installed = @((Shell pm list packages) -split "`n" | ForEach-Object { $_.Trim() -replace '^package:', '' } | Where-Object { $_ })
$alreadyOff = @((Shell pm list packages -d) -split "`n" | ForEach-Object { $_.Trim() -replace '^package:', '' } | Where-Object { $_ })
Good "Backup saved in $backup ($($installed.Count) apps listed)"

function Disable($pkg, $why) {
    if ($installed -notcontains $pkg) { return $false }
    if ($alreadyOff -contains $pkg) { return $false }
    Write-Host ""
    Write-Host "  $pkg" -ForegroundColor White
    Write-Host "    $why"
    if (-not (Ask "Switch this off?")) { Write-Host "    Left on."; return $false }
    $r = Shell pm disable-user --user 0 $pkg
    if ($r -match 'disabled') {
        Log "disable`t$pkg"
        Good "switched off"
        return $true
    }
    Warn "the TV did not allow this one: $r"
    return $false
}

# ---------------------------------------------------------------- 3. install
Say "Step 3: installing the new home screen"
$r = ((& $script:adb -s $script:serial install -r $apk 2>&1) | ForEach-Object { "$_" }) -join ' '
if ($r -notmatch 'Success') { Fail "Install failed: $r" }
Log "installed`t$OUR_PKG"
Good "installed"

# Lets the home screen read the 'continue watching' list (only one of these exists per TV).
$granted = $false
foreach ($perm in @('android.permission.READ_TV_LISTINGS', 'com.android.providers.tv.permission.READ_EPG_DATA')) {
    $g = (Shell pm grant $OUR_PKG $perm).Trim()
    if (-not $g) { $granted = $true }
}
if ($granted) { Good "allowed to read Netflix's picks" } else { Warn "this TV did not allow reading Netflix's picks; that panel will stay hidden" }

# Lets the home screen see what is playing, for the phone remote's title, time and picture.
Shell cmd notification allow_listener "$OUR_PKG/$OUR_PKG.MediaWatch" | Out-Null

# Lets the home screen show the Wi-Fi name (Android only gives the name to apps with location permission).
Shell pm grant $OUR_PKG android.permission.ACCESS_COARSE_LOCATION | Out-Null
Shell pm grant $OUR_PKG android.permission.ACCESS_FINE_LOCATION | Out-Null

# Skeem Saam at 19:30: allowed to show over other apps and to wake at the exact time.
Shell appops set $OUR_PKG SYSTEM_ALERT_WINDOW allow | Out-Null
Shell appops set $OUR_PKG SCHEDULE_EXACT_ALARM allow | Out-Null
Good "Skeem Saam reminder allowed at 19:30"

# ---------------------------------------------------------------- 4. set as home and test
Say "Step 4: making it the home screen (Google's home screen stays installed for now)"
# This TV runs your profile as its own Android user (not user 0), so set it for that user too.
function Set-OurHome {
    $u = (Shell am get-current-user).Trim()
    if ($u -notmatch '^\d+$') { $u = '0' }
    $res = ''
    foreach ($usr in @($u, '0') | Select-Object -Unique) { $res += (Shell cmd package set-home-activity --user $usr $OUR_HOME) }
    return $res
}
$r = Set-OurHome
Log "home`t$homeBefore"
if ($r -notmatch 'Success') { Warn "The TV may ask which home screen to use the first time you press Home. Pick 'Living Room Home' and 'Always'." }
Shell am start -n $OUR_HOME | Out-Null
Start-Sleep -Seconds 3
Write-Host ""
Write-Host "  The NEW home screen should now be on the TV. (Pressing Home may still show Google's"
Write-Host "  old one - that is normal until the next step switches it off.)"
Write-Host "  Check with the remote on the new screen:"
Write-Host "    1. Move left and right through the apps"
Write-Host "    2. Press the mic button on the remote and say something, then press Back"
Write-Host "  You can always reopen it from the TV's Apps list: 'Living Room Home'."
if (-not (Ask "Is the new home screen showing and working?")) {
    Warn "Stopping here. Google's home screen was not switched off. Run restore-tv.cmd to put the old one back."
    Read-Host "Press Enter to close"; exit 0
}

# Apps like YouTube need these Android services; an earlier setup switched them off, so switch them back on.
foreach ($p in @('com.android.adservices.api', 'com.android.ondevicepersonalization.services', 'com.google.android.partnersetup')) {
    Shell pm enable --user 0 $p | Out-Null
}

# ---------------------------------------------------------------- 5. HOME button opens the new screen
Say "Step 5: making the remote's HOME button open the new home screen"
# Google TV switches its own home screen back on at start-up, so it is NOT switched off.
# Instead the new home screen catches the HOME button (an accessibility helper inside the app).
foreach ($c in @('com.google.android.apps.tv.launcherx', 'com.google.android.tungsten.setupwraith/.RecoveryActivity')) {
    Shell pm enable --user 0 $c | Out-Null
}
$SVC = 'com.livingroom.home/com.livingroom.home.HomeKeyService'
$curSvc = (Shell settings get secure enabled_accessibility_services).Trim()
$curOn = (Shell settings get secure accessibility_enabled).Trim()
if ($curSvc -notmatch [regex]::Escape($SVC)) {
    Log "setting`tenabled_accessibility_services`t$curSvc"
    Log "setting`taccessibility_enabled`t$curOn"
    $newSvc = if (-not $curSvc -or $curSvc -eq 'null') { $SVC } else { "${curSvc}:$SVC" }
    Shell settings put secure enabled_accessibility_services $newSvc | Out-Null
    Shell settings put secure accessibility_enabled 1 | Out-Null
}
Start-Sleep -Seconds 2
if (Ask "Press HOME on the remote now (the real button). Does the new home screen come up?") {
    Good "HOME opens the new home screen"
} else {
    Warn "If the TV asked to allow 'Living Room Home button', allow it, then try HOME again."
}

# ---------------------------------------------------------------- 6. screensaver
Say "Step 6: screensaver"
Write-Host "  The new screensaver is a black screen with a dim clock that moves. No photos, no ads."
if (Ask "Use it as the TV's screensaver?") {
    foreach ($k in @('screensaver_components', 'screensaver_default_component', 'screensaver_enabled')) {
        Log "setting`t$k`t$($settingsBefore[$k])"
    }
    Shell settings put secure screensaver_components $OUR_SAVER | Out-Null
    Shell settings put secure screensaver_default_component $OUR_SAVER | Out-Null
    Shell settings put secure screensaver_enabled 1 | Out-Null
    Good "screensaver set"
}
Disable 'com.google.android.apps.tv.dreamx' "Google's 'Ambient mode' screensaver. It can show Google Photos and ads." | Out-Null
Disable 'com.google.android.backdrop' "Older Google screensaver that shows photos." | Out-Null

# ---------------------------------------------------------------- 7. photos
Say "Step 7: photos (so your Google Photos and Drive pictures cannot be opened on the TV)"
$photoKnown = @{
    'com.google.android.apps.photos' = 'Google Photos.'
    'com.google.android.apps.docs'   = 'Google Drive.'
    'com.google.android.apps.photos.tv' = 'Google Photos for TV.'
}
foreach ($pkg in $photoKnown.Keys) { Disable $pkg $photoKnown[$pkg] | Out-Null }

# Never offered: parts the TV needs to work.
$protected = 'gms|gsf|vending|tv\.settings|tvsettings|systemui|remote|inputmethod|\.ime|keyboard|tvinput|tv\.input|hdmi|livetv|tvcenter|tvservice|tuner|\.cec|katniss|mediashell|setupwraith|launcherx|livingroom|providers|bluetooth|wifi|network|permissioncontroller|packageinstaller|webview|ext\.services|framework|\.shell$|android\.tv$|audio|update|\.ota'

$photoPattern = 'photo|gallery|album|filemanager|filebrowser|file\.manager|documentsui|mediacenter|media\.center|mediaplayer|media\.player|localmedia|usbmedia|usb\.media'
$photoCandidates = $installed | Where-Object { $_ -match $photoPattern -and $_ -notmatch $protected -and -not $photoKnown.ContainsKey($_) }
foreach ($pkg in $photoCandidates) {
    $why = 'Looks like a photo, file or media viewer.'
    if ($pkg -match 'mediaplayer|media\.player|mediacenter|media\.center|usb') { $why = 'Looks like the player for USB sticks (photos and videos). Not needed to protect your Drive photos.' }
    if ($pkg -match 'documentsui') { $why = "Android's file picker. Some apps use it to pick files; switching it off is usually fine on a TV." }
    Disable $pkg $why | Out-Null
}

# ---------------------------------------------------------------- 8. ads
Say "Step 8: ads and tracking"
$adKnown = [ordered]@{
    'com.google.android.tvrecommendations'                 = 'Feeds sponsored and recommended rows to the home screen.'
    'com.google.android.leanbacklauncher.recommendations'  = 'Recommended rows for the older home screen.'
    'com.google.android.feedback'                          = 'Sends feedback and usage reports to Google.'
    'android.autoinstalls.config.google.gtvpai'            = 'Installs promoted apps in the background.'
}
foreach ($pkg in $adKnown.Keys) { Disable $pkg $adKnown[$pkg] | Out-Null }

$adPattern = '(^|\.)(ads?|advert\w*|adservice\w*|promos?|promotion\w*|recommend\w*|analytics?|tracking|tracker|telemetry|samba|alphonso|acr|smartlink|autoinstall\w*|pushservice|push)(\.|$)'
$adCandidates = $installed | Where-Object { $_ -match $adPattern -and $_ -notmatch $protected -and $_ -notmatch 'adservices|ondevicepersonalization|partnersetup' -and -not $adKnown.Contains($_) }
foreach ($pkg in $adCandidates) {
    Disable $pkg 'Its name suggests ads, promotions, recommendations or tracking (often from the TV maker).' | Out-Null
}

Write-Host ""
$adsDone = Join-Path $PSScriptRoot 'ads-id-done.txt'
if (Test-Path $adsDone) {
    Good "advertising ID was already switched off on an earlier run"
} elseif (Ask "Open the TV's Privacy settings to switch off personalised ads? (only needed once)") {
    Shell am start -a android.settings.PRIVACY_SETTINGS | Out-Null
    Write-Host "  On the TV: open 'Ads', choose 'Delete advertising ID' (or 'Reset'),"
    Write-Host "  and turn on 'Opt out of Ads Personalisation' if it is there. Then press HOME."
    Read-Host "  Press Enter when done"
    Set-Content -Path $adsDone -Value (Get-Date) -Encoding ascii
}

# ---------------------------------------------------------------- 9. finish
Say "Step 9: apps for the home screen"
Write-Host "  Chrome is not offered on Google TV. TV Bro is a free browser made for TVs (for Goojara and Skeem Saam)."
$have = @((Shell pm list packages) -split "`n" | ForEach-Object { $_.Trim() })
$apps = @(
    @('DStv', 'com.dstvmobile.android'),
    @('Prime Video', 'com.amazon.amazonvideo.livingroom'),
    @('Spotify', 'com.spotify.tv.android'),
    @('SABC+', 'tv.sabcplus.vod'),
    @('TV Bro', 'com.phlox.tvwebbrowser')
)
foreach ($a in $apps) {
    if ($have -contains "package:$($a[1])") { Good "$($a[0]) is already installed"; continue }
    if (Ask "Open the Play Store on the TV at $($a[0]) so you can press Install?") {
        Shell am start -a android.intent.action.VIEW -d "market://details?id=$($a[1])" | Out-Null
        Write-Host "  On the TV: press Install. If the Play Store says it is not available for this TV, just press BACK."
        Read-Host "  Press Enter here when done"
    }
}

Say "Step 10: finishing"
Shell am start -n $OUR_HOME | Out-Null
Write-Host "  Still to do by hand on the TV (see CHECKLIST):"
Write-Host "    - Chrome: do not sign in; if signed in, Settings > Sync off and sign out"
Write-Host "    - Settings > Privacy > Google Assistant (or Account): turn off Personal results"
Write-Host "    - Settings > System > Developer options: turn USB debugging (and Wireless debugging) OFF"
Disconnect-Tv
Write-Host ""
Write-Host "DONE. Everything that was changed is listed in $log" -ForegroundColor Green
Write-Host "To undo everything, run restore-tv.cmd"
Read-Host "Press Enter to close"
