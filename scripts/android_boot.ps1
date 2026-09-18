<#
.SYNOPSIS
    Boot and verify visible Android emulator (Antigravity_Test / emulator-5554).
.DESCRIPTION
    Ensures ADB is available, checks if emulator-5554 is booted, and launches it visibly if needed.
    Fails loudly with non-zero exit code if emulator fails to boot.
#>
[CmdletBinding()]
param(
    [string]$AvdName = "Antigravity_Test",
    [string]$DeviceId = "emulator-5554",
    [int]$BootTimeoutSeconds = 120
)

$ErrorActionPreference = "Continue"

# Ensure tools are in PATH
$AdbDir = "C:\Android\Sdk\platform-tools"
$EmulatorDir = "C:\Android\Sdk\emulator"
if ($env:PATH -notlike "*$AdbDir*") { $env:PATH = "$AdbDir;$env:PATH" }
if ($env:PATH -notlike "*$EmulatorDir*") { $env:PATH = "$EmulatorDir;$env:PATH" }

Write-Host "=== [1/4] Checking ADB ===" -ForegroundColor Cyan
try {
    $adbVer = & adb version
    Write-Host ($adbVer | Select-Object -First 1) -ForegroundColor Green
} catch {
    Write-Error "ADB executable not found in PATH or at $AdbDir."
    exit 1
}

Write-Host "=== [2/4] Checking Connected Devices ===" -ForegroundColor Cyan
$devices = & adb devices -l
Write-Host ($devices -join "`n")

$deviceRunning = $devices | Select-String -Pattern "$DeviceId\s+device"

if (-not $deviceRunning) {
    Write-Host "Emulator $DeviceId is not currently running. Launching visible emulator ($AvdName)..." -ForegroundColor Yellow
    $emulatorPath = Join-Path $EmulatorDir "emulator.exe"
    if (-not (Test-Path $emulatorPath)) {
        Write-Error "Emulator executable not found at $emulatorPath"
        exit 1
    }

    Start-Process -FilePath $emulatorPath -ArgumentList "-avd", $AvdName
    Write-Host "Waiting for device to connect to ADB..." -ForegroundColor Yellow
    & adb wait-for-device
} else {
    Write-Host "Emulator $DeviceId is already running." -ForegroundColor Green
}

Write-Host "=== [3/4] Waiting for Android Boot Completion ===" -ForegroundColor Cyan
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$bootCompleted = $false

while ($sw.Elapsed.TotalSeconds -lt $BootTimeoutSeconds) {
    $bootProp = & adb -s $DeviceId shell getprop sys.boot_completed 2>$null
    if ($bootProp -and $bootProp.Trim() -eq "1") {
        $bootCompleted = $true
        break
    }
    Start-Sleep -Seconds 2
}

if (-not $bootCompleted) {
    Write-Error "Emulator $DeviceId timed out waiting for sys.boot_completed after $BootTimeoutSeconds seconds."
    exit 1
}

Write-Host "Android boot completed successfully!" -ForegroundColor Green

Write-Host "=== [4/4] Verifying Device Calibration ===" -ForegroundColor Cyan
$avdCheck = & adb -s $DeviceId emu avd name 2>$null
$wmSize = & adb -s $DeviceId shell wm size 2>$null
Write-Host "AVD Name: $($avdCheck -join ' ')" -ForegroundColor Green
Write-Host "Resolution: $($wmSize)" -ForegroundColor Green

Write-Host "`n[SUCCESS] Android emulator $DeviceId ($AvdName) is ready for testing." -ForegroundColor Green
exit 0
