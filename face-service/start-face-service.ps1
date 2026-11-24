# ============================================================
# Smart Attendance - Flask Face Service Launcher
# Run this from the 'face-service' directory in PowerShell.
# ============================================================

$VENV_PYTHON = Join-Path $PSScriptRoot ".venv\Scripts\python.exe"
$APP        = Join-Path $PSScriptRoot "app.py"

if (-not (Test-Path $VENV_PYTHON)) {
    Write-Error "Virtual environment Python not found at: $VENV_PYTHON"
    Write-Host "Create it with:  python -m venv .venv"
    Write-Host "Then:            .venv\Scripts\pip install -r requirements.txt"
    exit 1
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  Face Recognition Service - Starting...   " -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  Python  : $VENV_PYTHON"                    -ForegroundColor Green
Write-Host "  App     : $APP"                            -ForegroundColor Green
Write-Host "  Port    : 5001"                            -ForegroundColor Green
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

& $VENV_PYTHON $APP
