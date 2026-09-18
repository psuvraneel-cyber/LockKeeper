<#
.SYNOPSIS
    Install and grant permissions for LockKeeper debug APK on emulator-5554.
.DESCRIPTION
    Installs the debug APK onto the emulator, verifies package presence,
    and grants required Android permissions deterministically.
    Fails loudly with non-zero exit code on failure.
#>
[CmdletBinding()]
param(
    [string]$DeviceId = "emulator-5554",
    [string]$PackageName = "com.lockkeeper.app",
    [string]$ApkPath = "C:\AppLocker\build\app\outputs\flutter-apk\app-debug.apk"
)

$ErrorActionPreference = "Stop"

$AdbDir = "C:\Android\Sdk\platform-tools"
if ($env:PATH -notlike "*$AdbDir*") { $env:PATH = "$AdbDir;$env:PATH" }

Write-Host "=== [1/4] Verifying Target APK & Device ===" -ForegroundColor Cyan
if (-not (Test-Path $ApkPath)) {
    Write-Error "APK not found at $ApkPath. Run scripts\android_build.ps1 first."
    exit 1
}

$devices = & adb devices -l
if (-not ($devices -match "$DeviceId\s+device")) {
    Write-Error "Target device $DeviceId is not available in adb devices."
    exit 1
}

Write-Host "=== [2/4] Installing APK onto $DeviceId ===" -ForegroundColor Cyan
$installOut = & adb -s $DeviceId install -r $ApkPath 2>&1
Write-Host ($installOut -join "`n")

if ($installOut -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE" -or $installOut -match "signatures do not match") {
    Write-Warning "Signature mismatch detected. Performing clean reinstall..."
    & adb -s $DeviceId shell dpm remove-active-admin "$PackageName/$PackageName.receiver.LockKeeperDeviceAdminReceiver" 2>$null
    & adb -s $DeviceId uninstall $PackageName
    $installOut = & adb -s $DeviceId install -r $ApkPath 2>&1
    Write-Host ($installOut -join "`n")
}


if ($LASTEXITCODE -ne 0 -or (-not ($installOut -match "Success"))) {
    Write-Error "Installation failed on $DeviceId"
    exit 1
}

Write-Host "=== [3/4] Verifying Package Installation ===" -ForegroundColor Cyan
$pkgCheck = & adb -s $DeviceId shell pm list packages | Select-String -Pattern $PackageName
if (-not $pkgCheck) {
    Write-Error "Package $PackageName not found in pm list packages after install."
    exit 1
}
Write-Host "Verified installed package: $pkgCheck" -ForegroundColor Green

Write-Host "=== [4/4] Configuring Necessary Permissions ===" -ForegroundColor Cyan
# Overlay permission
& adb -s $DeviceId shell appops set $PackageName SYSTEM_ALERT_WINDOW allow
# Usage stats
& adb -s $DeviceId shell appops set $PackageName GET_USAGE_STATS allow
# Notifications
& adb -s $DeviceId shell pm grant $PackageName android.permission.POST_NOTIFICATIONS 2>$null
# Battery optimization whitelist
& adb -s $DeviceId shell dumpsys deviceidle whitelist "+$PackageName" 2>$null
# Accessibility service
& adb -s $DeviceId shell settings put secure enabled_accessibility_services "$PackageName/$PackageName.service.LockKeeperAccessibilityService"
& adb -s $DeviceId shell settings put secure accessibility_enabled 1
# Device Administrator
& adb -s $DeviceId shell dpm set-active-admin --user current "$PackageName/$PackageName.receiver.LockKeeperDeviceAdminReceiver" 2>$null

Write-Host "`n[SUCCESS] Package $PackageName installed and configured on $DeviceId." -ForegroundColor Green
exit 0
