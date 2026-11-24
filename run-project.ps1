$ErrorActionPreference = "Stop"

$root = "C:\Users\kanis\Downloads\smart-college-attendance-geo-integrated\smart-college-attendance"
$faceDir = Join-Path $root "face-service"
$backendDir = Join-Path $root "backend"
$dbFile = Join-Path $root ".local-secrets\db-password.dpapi"

# Load shared face-service secret
$env:FACE_SERVICE_SECRET = [Environment]::GetEnvironmentVariable("FACE_SERVICE_SECRET", "User")

if (-not $env:FACE_SERVICE_SECRET) {
    throw "FACE_SERVICE_SECRET is missing from Windows user environment."
}
# Load and validate AES-256 face embedding encryption key
$env:FACE_EMB_KEY = [Environment]::GetEnvironmentVariable("FACE_EMB_KEY", "User")

if ([string]::IsNullOrWhiteSpace($env:FACE_EMB_KEY)) {
    throw "FACE_EMB_KEY is missing from Windows user environment."
}

try {
    $keyBytes = [Convert]::FromBase64String($env:FACE_EMB_KEY)

    if ($keyBytes.Length -ne 32) {
        throw "Invalid key length."
    }
}
catch {
    throw "FACE_EMB_KEY is invalid. Expected Base64-encoded 32-byte key."
}
finally {
    if ($keyBytes) {
        [Array]::Clear($keyBytes, 0, $keyBytes.Length)
        $keyBytes = $null
    }
}

Write-Host "Face encryption key validated." -ForegroundColor Green

# Avoid starting duplicate services
$flaskRunning = [bool](Get-NetTCPConnection -State Listen -LocalPort 5001 -ErrorAction SilentlyContinue)
$backendRunning = [bool](Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue)

# Start Flask
if (-not $flaskRunning) {
    $flaskCommand = "Set-Location -LiteralPath '$faceDir'; Write-Host 'FLASK FACE SERVICE - PORT 5001' -ForegroundColor Cyan; & '.\.venv\Scripts\python.exe' app.py"
    Start-Process powershell.exe -ArgumentList "-NoExit -ExecutionPolicy Bypass -Command `"$flaskCommand`""
    Write-Host "Flask startup requested." -ForegroundColor Green
}
else {
    Write-Host "Flask is already running on port 5001." -ForegroundColor Yellow
}

# Load JWT secret
$env:JWT_SECRET = [Environment]::GetEnvironmentVariable("JWT_SECRET", "User")

if (-not $env:JWT_SECRET -or $env:JWT_SECRET.Length -lt 32) {
    throw "JWT_SECRET is missing or too short."
}
# Validate development demo staff credentials.
# If an env var is already set (Process or User scope) and at least 12 chars,
# reuse it without prompting.  Otherwise prompt securely with Read-Host
# -AsSecureString, validate the length, and set a process-scoped env var so
# Spring Boot receives it before DataInitializer runs.
function Ensure-DemoPassword([string]$VarName, [string]$PromptLabel) {
    $proc = [Environment]::GetEnvironmentVariable($VarName, "Process")
    if (-not [string]::IsNullOrWhiteSpace($proc) -and $proc.Length -ge 12) { return }
    $user = [Environment]::GetEnvironmentVariable($VarName, "User")
    if (-not [string]::IsNullOrWhiteSpace($user) -and $user.Length -ge 12) {
        Set-Item -Path "Env:$VarName" -Value $user
        return
    }
    while ($true) {
        $secure = Read-Host "Enter $PromptLabel (min 12 chars)" -AsSecureString
        $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try { $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
        $secure.Dispose()
        if ($plain.Length -ge 12) {
            Set-Item -Path "Env:$VarName" -Value $plain
            return
        }
        Write-Host "Password too short ($($plain.Length) chars). Need at least 12." -ForegroundColor Yellow
    }
}

Ensure-DemoPassword "DEMO_TEACHER_PASSWORD" "Demo Teacher password"
Ensure-DemoPassword "DEMO_HOD_PASSWORD" "Demo HOD password"
# Load encrypted MySQL password
if (-not (Test-Path $dbFile)) {
    throw "Encrypted database password file was not found."
}

$encrypted = (Get-Content $dbFile -Raw).Trim()
$securePassword = ConvertTo-SecureString $encrypted
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)

try {
    $env:DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
}
finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    $securePassword.Dispose()
}

$env:SPRING_PROFILES_ACTIVE = "dev"

# Start Spring Boot
if (-not $backendRunning) {
    $backendCommand = "Set-Location -LiteralPath '$backendDir'; Write-Host 'SPRING BOOT BACKEND - PORT 8080' -ForegroundColor Cyan; .\start-backend.ps1"
    Start-Process powershell.exe -ArgumentList "-NoExit -ExecutionPolicy Bypass -Command `"$backendCommand`""
    Write-Host "Spring Boot startup requested." -ForegroundColor Green
}
else {
    Write-Host "Backend is already running on port 8080." -ForegroundColor Yellow
}

# Clear credentials from this launcher process
Remove-Item Env:DB_PASSWORD -ErrorAction SilentlyContinue
Remove-Item Env:JWT_SECRET -ErrorAction SilentlyContinue
Remove-Item Env:FACE_SERVICE_SECRET -ErrorAction SilentlyContinue
Remove-Item Env:FACE_EMB_KEY -ErrorAction SilentlyContinue
Remove-Item Env:DEMO_TEACHER_PASSWORD -ErrorAction SilentlyContinue
Remove-Item Env:DEMO_HOD_PASSWORD -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "Startup commands completed." -ForegroundColor Green
Write-Host "Flask:       http://localhost:5001"
Write-Host "Spring Boot: http://localhost:8080"
Write-Host "Health:      http://localhost:8080/api/health"
Write-Host ""
Write-Host "Keep both service windows open while using the application."

