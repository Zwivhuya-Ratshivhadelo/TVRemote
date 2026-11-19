# Shared helpers for setup-tv.ps1 and restore-tv.ps1. Not meant to be run on its own.

$ErrorActionPreference = 'Continue'

function Say($text)  { Write-Host ""; Write-Host $text -ForegroundColor Cyan }
function Good($text) { Write-Host "  OK: $text" -ForegroundColor Green }
function Warn($text) { Write-Host "  NOTE: $text" -ForegroundColor Yellow }
function Fail($text) {
    Write-Host ""
    Write-Host "STOPPED: $text" -ForegroundColor Red
    Read-Host "Press Enter to close"
    exit 1
}
function Ask($question) {
    $a = Read-Host "  $question [y/N]"
    return ($a -match '^(y|yes)$')
}

function Find-Adb {
    foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk")) {
        if ($sdk -and (Test-Path "$sdk\platform-tools\adb.exe")) { return "$sdk\platform-tools\adb.exe" }
    }
    $c = Get-Command adb -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    return $null
}

$script:adb = Find-Adb
$script:serial = $null
if (-not $script:adb) { Fail "adb was not found. Run 2-setup-tv.cmd (it installs it)." }

# Runs a command on the TV and returns its output (errors included) as one string.
# A plain function on purpose: it passes flags like -d and --user straight to the TV.
function Shell {
    $cmdArgs = @($args)
    ((& $script:adb -s $script:serial shell @cmdArgs 2>&1) | ForEach-Object { "$_" }) -join "`n"
}

function Connect-Tv([string]$ip) {
    Write-Host "  On the TV: Settings > Network & Internet > (your Wi-Fi) shows the TV's IP address, e.g. 192.168.1.23"
    if (-not $ip) { $ip = Read-Host "  Type the TV's IP address" }
    $ip = $ip.Trim()
    if (-not $ip) { Fail "No IP address given." }

    & $script:adb start-server 2>$null | Out-Null
    $out = ((& $script:adb connect "${ip}:5555" 2>&1) | ForEach-Object { "$_" }) -join ' '
    if ($out -match 'connected to|authenticate') {
        $script:serial = "${ip}:5555"
    } else {
        Warn "Could not connect on the usual port. Newer TVs use 'Wireless debugging' with a pairing code."
        Write-Host "  On the TV: Settings > System > Developer options > Wireless debugging > Pair device with pairing code"
        $pairAddr = Read-Host "  Type the IP address and port shown for pairing (like 192.168.1.23:37123)"
        $code = Read-Host "  Type the 6-digit pairing code"
        $p = ((& $script:adb pair $pairAddr.Trim() $code.Trim() 2>&1) | ForEach-Object { "$_" }) -join ' '
        if ($p -notmatch 'Successfully paired') { Fail "Pairing failed: $p" }
        $connAddr = (Read-Host "  Now type the IP address and port shown at the top of the Wireless debugging screen (like 192.168.1.23:41234)").Trim()
        $out = ((& $script:adb connect $connAddr 2>&1) | ForEach-Object { "$_" }) -join ' '
        if ($out -notmatch 'connected to|authenticate') { Fail "Could not connect: $out" }
        $script:serial = $connAddr
    }

    Write-Host "  If the TV asks 'Allow USB debugging?', tick 'Always allow from this computer' and choose Allow."
    $state = ''
    for ($i = 0; $i -lt 45; $i++) {
        $state = ((& $script:adb -s $script:serial get-state 2>$null) -join '').Trim()
        if ($state -eq 'device') { break }
        Start-Sleep -Seconds 2
    }
    if ($state -ne 'device') { Fail "The TV did not allow the connection. Accept the prompt on the TV and run this again." }
    $model = (Shell getprop ro.product.model).Trim()
    $android = (Shell getprop ro.build.version.release).Trim()
    Good "Connected to $model (Android $android)"
}

function Disconnect-Tv {
    if ($script:serial) { & $script:adb disconnect $script:serial 2>$null | Out-Null }
}
