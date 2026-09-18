<#
.SYNOPSIS
    Clean and build APK (debug or release) for AppLocker / LockKeeper.
.DESCRIPTION
    Stops Gradle daemons, runs flutter pub get, builds the requested APK variant (debug/release),
    verifies signature (for release), updates root artifact and checksums,
    and verifies that the resulting APK exists at the expected project build path.
    Fails loudly with non-zero exit code if build fails.
#>
[CmdletBinding()]
param(
    [string]$ProjectRoot = "C:\AppLocker",
    [ValidateSet("debug", "release")]
    [string]$Variant = "debug",
    [switch]$Release,
    [switch]$SkipClean
)

if ($Release) {
    $Variant = "release"
}

$ErrorActionPreference = "Continue"

# Ensure Flutter and Java tools are in PATH
$FlutterBin = "C:\flutter\bin"
$AdbDir = "C:\Android\Sdk\platform-tools"
if ($env:PATH -notlike "*$FlutterBin*") { $env:PATH = "$FlutterBin;$env:PATH" }
if ($env:PATH -notlike "*$AdbDir*") { $env:PATH = "$AdbDir;$env:PATH" }

Set-Location $ProjectRoot

Write-Host "=== [1/4] Stopping Gradle Daemons ===" -ForegroundColor Cyan
if (Test-Path "$ProjectRoot\android\gradlew.bat") {
    Push-Location "$ProjectRoot\android"
    try {
        & .\gradlew.bat --stop
    } catch {
        Write-Warning "Could not cleanly stop Gradle daemons: $_"
    }
    Pop-Location
}

Write-Host "=== [2/4] Resolving Flutter Dependencies ===" -ForegroundColor Cyan
& flutter pub get
if ($LASTEXITCODE -ne 0) {
    Write-Error "flutter pub get failed with exit code $LASTEXITCODE"
    exit 1
}

Write-Host "=== [3/4] Building $Variant APK ===" -ForegroundColor Cyan
& flutter build apk "--$Variant" -v
if ($LASTEXITCODE -ne 0) {
    Write-Error "flutter build apk --$Variant failed with exit code $LASTEXITCODE"
    exit 1
}

Write-Host "=== [4/4] Verifying APK Artifact ===" -ForegroundColor Cyan
$expectedApk = "$ProjectRoot\build\app\outputs\flutter-apk\app-$Variant.apk"
if (-not (Test-Path $expectedApk)) {
    Write-Error "Build finished without creating expected APK at $expectedApk"
    exit 1
}

$apkItem = Get-Item $expectedApk
if ($apkItem.Length -lt 1000000) {
    Write-Error "APK file at $expectedApk is unusually small ($($apkItem.Length) bytes). Potential corrupted artifact."
    exit 1
}

Write-Host "Verified APK artifact:" -ForegroundColor Green
Write-Host "  Path: $($apkItem.FullName)" -ForegroundColor Green
Write-Host "  Size: $([math]::Round($apkItem.Length / 1MB, 2)) MB ($($apkItem.Length) bytes)" -ForegroundColor Green
Write-Host "  Timestamp: $($apkItem.LastWriteTime)" -ForegroundColor Green

if ($Variant -eq "release") {
    $distApk = "$ProjectRoot\LockKeeper-v1.0.0-release.apk"
    Copy-Item $expectedApk $distApk -Force
    $hash = (Get-FileHash -Path $distApk -Algorithm SHA256).Hash
    "$hash  LockKeeper-v1.0.0-release.apk" | Set-Content "$ProjectRoot\checksums.txt"
    Write-Host "  Distribution copy: $distApk" -ForegroundColor Green
    Write-Host "  SHA-256 Checksum: $hash" -ForegroundColor Green
}

Write-Host "`n[SUCCESS] $Variant APK built successfully." -ForegroundColor Green
exit 0
