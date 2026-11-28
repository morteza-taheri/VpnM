@echo off
REM ============================================================
REM  VpnM - refresh Servers.csv (the app's emergency server list)
REM
REM  The list lives at the REPOSITORY ROOT as Servers.csv and is
REM  produced by vpngate_collector.py (which delegates the 29-column
REM  schema to emergency_csv_publisher.py). Running this file
REM  REPLACES the existing Servers.csv, or creates it when missing.
REM
REM  Usage:
REM    1.bat                     refresh Servers.csv only (fast)
REM    1.bat --max-servers 400   same, with a different row cap
REM    1.bat --skip-health-check skip the TCP probe (faster, unverified)
REM    1.bat --api-only          API feed only (no HTML protocol columns)
REM    1.bat --full              also run the full analysis (JSON reports)
REM    1.bat --full --max-servers 400
REM
REM  Nothing is committed or pushed here: review `git diff Servers.csv`
REM  and commit it yourself (the release workflow publishes it from main).
REM ============================================================
setlocal
cd /d "%~dp0"

where py >nul 2>nul && (set "PY=py") || (set "PY=python")

REM Default: refresh Servers.csv only. `--full` (first argument) runs the
REM complete analysis as well, which also refreshes Servers.csv at the end.
set "MODE=--emergency-only"
if /i "%~1"=="--full" (
    set "MODE="
    shift
)

echo Running: %PY% vpngate_collector.py %MODE% %*
echo.
%PY% vpngate_collector.py %MODE% %*
set "RC=%ERRORLEVEL%"
echo.
echo Exit code: %RC%

if exist "Servers.csv" (
    for %%A in ("Servers.csv") do echo Servers.csv: %%~zA bytes, modified %%~tA
) else (
    echo ERROR: Servers.csv was not created.
    if "%RC%"=="0" set "RC=1"
)

if "%1"=="" pause
exit /b %RC%
