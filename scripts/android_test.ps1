<#
.SYNOPSIS
    Automated smoke and UI test for LockKeeper on Android emulator.
.DESCRIPTION
    Launches com.lockkeeper.app on the target emulator, verifies foreground
    focus, captures UI hierarchy and screenshot to artifacts/, checks logcat
    for crashes/exceptions, and exits with 0 on success (or 1 on failure).
#>
[CmdletBinding()]
param(
    [string]$DeviceId = "emulator-5554",
    [string]$PackageName = "com.lockkeeper.app",
    [string]$MainActivity = "com.lockkeeper.app/.MainActivity",
    [string]$ArtifactsDir = "C:\AppLocker\artifacts"
)

$ErrorActionPreference = "Continue"

$AdbDir = "C:\Android\Sdk\platform-tools"
if ($env:PATH -notlike "*$AdbDir*") { $env:PATH = "$AdbDir;$env:PATH" }

if (-not (Test-Path $ArtifactsDir)) {
    New-Item -ItemType Directory -Path $ArtifactsDir -Force | Out-Null
}

Write-Host "=== [1/5] Verifying Target Device ===" -ForegroundColor Cyan
$devices = & adb devices -l
$deviceFound = $devices | Select-String -Pattern "$DeviceId\s+device"
if (-not $deviceFound) {
    Write-Error "Target device $DeviceId is not available in adb devices."
    exit 1
}
Write-Host "Target device $DeviceId is online." -ForegroundColor Green

Write-Host "=== [2/5] Preparing Logcat & Launching $PackageName ===" -ForegroundColor Cyan
& adb -s $DeviceId logcat -c
$launchOut = & adb -s $DeviceId shell am start -n $MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER 2>&1
Write-Host ($launchOut -join "`n")

Write-Host "=== [3/5] Waiting for Window Focus ===" -ForegroundColor Cyan
$focused = $false
for ($i = 1; $i -le 15; $i++) {
    Start-Sleep -Seconds 1
    $focusCheck = & adb -s $DeviceId shell dumpsys window | Select-String -Pattern "mFocusedApp|mCurrentFocus"
    if ($focusCheck -match $PackageName) {
        $focused = $true
        Write-Host "Application focused in window manager: $focusCheck" -ForegroundColor Green
        break
    }
    Write-Host "Waiting for window focus ($i/15s)..."
}

if (-not $focused) {
    Write-Error "Timeout waiting for $PackageName to gain focus in WindowManager."
    exit 1
}

Write-Host "=== [4/5] Capturing UI Hierarchy and Screenshot ===" -ForegroundColor Cyan
# Allow Flutter UI frames to stabilize
Start-Sleep -Seconds 2

$xmlDest = Join-Path $ArtifactsDir "smoke_test_dump.xml"
$pngDest = Join-Path $ArtifactsDir "smoke_test_screenshot.png"

& adb -s $DeviceId shell uiautomator dump /sdcard/smoke_test_dump.xml 2>$null
& adb -s $DeviceId pull /sdcard/smoke_test_dump.xml $xmlDest 2>$null

& adb -s $DeviceId shell screencap -p /sdcard/smoke_test_screenshot.png
& adb -s $DeviceId pull /sdcard/smoke_test_screenshot.png $pngDest 2>$null

if (Test-Path $xmlDest) {
    Write-Host "UI hierarchy saved to: $xmlDest" -ForegroundColor Green
} else {
    Write-Warning "Could not retrieve UI hierarchy XML."
}

if (Test-Path $pngDest) {
    Write-Host "Screenshot saved to: $pngDest" -ForegroundColor Green
} else {
    Write-Warning "Could not retrieve test screenshot."
}

Write-Host "=== [5/5] Checking for Runtime Exceptions / Crashes ===" -ForegroundColor Cyan
$crashLogs = & adb -s $DeviceId logcat -d -s AndroidRuntime:E *:F 2>&1 | Select-String -Pattern $PackageName
if ($crashLogs) {
    Write-Error "Fatal runtime exception or crash detected in logcat for ${PackageName}:`n$crashLogs"
    exit 1
}
Write-Host "Zero fatal exceptions or crashes detected for $PackageName." -ForegroundColor Green

Write-Host "`n[SUCCESS] Smoke test completed successfully for $PackageName on $DeviceId." -ForegroundColor Green
exit 0
