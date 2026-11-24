# ============================================================
# Smart Attendance - Backend Launcher (Development)
# Run this from the 'backend' directory in PowerShell.
# ============================================================
# It reads DB_PASSWORD and JWT_SECRET from environment
# variables YOU must have set before running this script.
# It DOES NOT hardcode or print any secret.
# ============================================================

# --- Validate required environment variables ----------------
if (-not $env:DB_PASSWORD -or $env:DB_PASSWORD -eq "") {
    Write-Error "DB_PASSWORD environment variable is not set. Set it before running this script."
    Write-Host "  Example:  `$env:DB_PASSWORD = 'your_mysql_password'"
    exit 1
}

if (-not $env:JWT_SECRET -or $env:JWT_SECRET -eq "") {
    Write-Error "JWT_SECRET environment variable is not set. Set it before running this script."
    Write-Host "  Example:  `$env:JWT_SECRET = 'some-32-char-or-longer-random-secret'"
    exit 1
}

if ($env:JWT_SECRET.Length -lt 32) {
    Write-Error "JWT_SECRET must be at least 32 characters long. Current length: $($env:JWT_SECRET.Length)"
    exit 1
}

# --- Compute optional variables with PowerShell 5.1 compatible fallbacks ----
# DB_USERNAME defaults to 'root' if not set
if ($env:DB_USERNAME) {
    $dbUser = $env:DB_USERNAME
} else {
    $dbUser = 'root'
}
# GEOFENCE_ENABLED defaults to the value from application.properties (true) if not set
if ($env:GEOFENCE_ENABLED) {
    $geofenceEnabled = $env:GEOFENCE_ENABLED
} else {
    $geofenceEnabled = 'true (from application.properties)'
}
# ATTENDANCE_VERIFICATION_EXPIRATION_SECONDS defaults to '120s (default)' if not set
if ($env:ATTENDANCE_VERIFICATION_EXPIRATION_SECONDS) {
    $attendanceExp = $env:ATTENDANCE_VERIFICATION_EXPIRATION_SECONDS
} else {
    $attendanceExp = '120s (default)'
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  Smart Attendance Backend - Starting...   " -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  DB_USERNAME  : $dbUser" -ForegroundColor Green
Write-Host "  DB_PASSWORD  : [set, not shown]"            -ForegroundColor Green
Write-Host "  JWT_SECRET   : [set, not shown]"            -ForegroundColor Green
Write-Host "  Geofence     : $geofenceEnabled" -ForegroundColor Yellow
Write-Host "  ATTEND_EXP   : $attendanceExp" -ForegroundColor Green
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# --- Launch Maven -------------------------------------------
#mvn spring-boot:run
# Use dotnet host to ensure PowerShell 5.1 runs the command correctly
mvn spring-boot:run