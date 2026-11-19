# Builds the phone remote app (PhoneRemote.apk) on this laptop.
# Safe to run more than once. Needs internet. Nothing here touches the TV.
#
# What it does:
#   1. Finds Java 17 or newer (installs Microsoft OpenJDK 17 if you say yes)
#   2. Finds the Android SDK (or downloads Google's command-line tools into your user folder)
#   3. Installs the few Android parts needed: platform-tools (adb), Android 35, build-tools 34
#   4. Builds the app and copies it here as PhoneRemote.apk

$ErrorActionPreference = 'Continue'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

function Say($text) { Write-Host $text -ForegroundColor Cyan }
function Fail($text) { Write-Host ""; Write-Host "STOPPED: $text" -ForegroundColor Red; exit 1 }
function Ask($question) {
    $a = Read-Host "$question [y/N]"
    return ($a -match '^(y|yes)$')
}

# ---------------------------------------------------------------- 1. Java 17+
function JavaMajor($jdk) {
    $rel = Join-Path $jdk 'release'
    if (-not (Test-Path $rel)) { return 0 }
    $line = Get-Content $rel | Where-Object { $_ -like 'JAVA_VERSION=*' } | Select-Object -First 1
    if (-not $line) { return 0 }
    $v = $line.Split('=')[1].Trim('"')
    if ($v.StartsWith('1.')) { return [int]$v.Split('.')[1] }
    return [int]($v.Split('.')[0])
}

function Find-Java {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += "$env:ProgramFiles\Android\Android Studio\jbr"
    $candidates += "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
    $candidates += Get-ChildItem "$env:ProgramFiles\Microsoft" -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
    $candidates += Get-ChildItem "$env:ProgramFiles\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
    $candidates += Get-ChildItem "$env:ProgramFiles\Java" -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
    $onPath = Get-Command java -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += (Split-Path (Split-Path $onPath.Source -Parent) -Parent) }
    foreach ($c in $candidates) {
        if ($c -and (Test-Path (Join-Path $c 'bin\java.exe')) -and ((JavaMajor $c) -ge 17) -and ((JavaMajor $c) -le 23)) { return $c }
    }
    return $null
}

Say "Step 1 of 4: looking for Java 17 to 23..."
$java = Find-Java
if (-not $java) {
    Write-Host "Java 17 (or 18 to 23) was not found. Android Studio's own Java is too new for this build."
    if (-not (Ask "Install Microsoft OpenJDK 17 now (free, from Microsoft, via winget)?")) { Fail "Java is needed to build the app." }
    winget install --id Microsoft.OpenJDK.17 -e --accept-source-agreements --accept-package-agreements
    $java = Find-Java
    if (-not $java) { Fail "Java still not found. If winget is blocked on this laptop, install 'Microsoft Build of OpenJDK 17' by hand and run this again." }
}
$env:JAVA_HOME = $java
$env:Path = "$java\bin;$env:Path"
Write-Host "  Using Java at $java"

# ---------------------------------------------------------------- 2. Android SDK
Say "Step 2 of 4: looking for the Android SDK..."
$sdk = $null
foreach ($c in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk")) {
    if ($c -and (Test-Path $c)) { $sdk = $c; break }
}
if (-not $sdk) { $sdk = "$env:LOCALAPPDATA\Android\Sdk"; New-Item -ItemType Directory -Force -Path $sdk | Out-Null }
Write-Host "  SDK folder: $sdk"

$sdkmanager = $null
foreach ($c in @("$sdk\cmdline-tools\latest\bin\sdkmanager.bat")) { if (Test-Path $c) { $sdkmanager = $c } }
if (-not $sdkmanager) {
    $found = Get-ChildItem "$sdk\cmdline-tools" -Recurse -Filter 'sdkmanager.bat' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) { $sdkmanager = $found.FullName }
}

$needed = @(
    @{ path = "$sdk\platform-tools\adb.exe";        pkg = 'platform-tools' },
    @{ path = "$sdk\platforms\android-35\android.jar"; pkg = 'platforms;android-35' },
    @{ path = "$sdk\build-tools\34.0.0";             pkg = 'build-tools;34.0.0' }
)
$missing = @($needed | Where-Object { -not (Test-Path $_.path) } | ForEach-Object { $_.pkg })

if ($missing.Count -gt 0 -and -not $sdkmanager) {
    Write-Host "  Downloading Google's Android command-line tools (about 150 MB)..."
    $zip = Join-Path $env:TEMP 'android-cmdline-tools.zip'
    $tmp = Join-Path $env:TEMP 'android-cmdline-tools'
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' -OutFile $zip -UseBasicParsing
    if (-not (Test-Path $zip)) { Fail "Could not download the Android tools. Check the internet connection." }
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    New-Item -ItemType Directory -Force -Path "$sdk\cmdline-tools" | Out-Null
    if (Test-Path "$sdk\cmdline-tools\latest") { Remove-Item "$sdk\cmdline-tools\latest" -Recurse -Force }
    Move-Item "$tmp\cmdline-tools" "$sdk\cmdline-tools\latest"
    $sdkmanager = "$sdk\cmdline-tools\latest\bin\sdkmanager.bat"
}

if ($missing.Count -gt 0) {
    Say "Step 3 of 4: installing Android parts: $($missing -join ', ')"
    Write-Host "  Accepting Google's Android SDK licences..."
    $yes = ("y`n" * 40)
    $yes | & $sdkmanager "--sdk_root=$sdk" --licenses | Out-Null
    & $sdkmanager "--sdk_root=$sdk" $missing
    foreach ($n in $needed) { if (-not (Test-Path $n.path)) { Fail "Android part '$($n.pkg)' did not install." } }
} else {
    Say "Step 3 of 4: Android parts already installed."
}
$env:ANDROID_HOME = $sdk

# local.properties tells the build where the SDK is (written without a BOM, as Gradle needs)
$escaped = $sdk.Replace('\', '\\').Replace(':', '\:')
[IO.File]::WriteAllText((Join-Path $here 'local.properties'), "sdk.dir=$escaped`n", [Text.Encoding]::ASCII)

# ---------------------------------------------------------------- 4. Build
Say "Step 4 of 4: building the app (the first time takes a few minutes)..."
& "$here\gradlew.bat" assembleDebug --no-daemon
if ($LASTEXITCODE -ne 0) { Fail "The build failed. Copy the red text above and send it to Claude." }

$apk = Join-Path $here 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) { Fail "The build finished but the app file was not found." }
Copy-Item $apk (Join-Path $here 'PhoneRemote.apk') -Force

Write-Host ""
Write-Host "DONE: PhoneRemote.apk is ready in $here" -ForegroundColor Green
Write-Host "Next: copy PhoneRemote.apk to your phone and open it there to install."
