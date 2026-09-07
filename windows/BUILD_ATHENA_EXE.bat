@echo off
setlocal
cd /d "%~dp0"
where py >nul 2>&1 || (echo Python is required for the BUILD step only. & pause & exit /b 1)
py -m pip install --upgrade pyinstaller
py -m PyInstaller --noconfirm --clean --onefile --windowed --name Athena --icon athena.ico athena_pc.py
if exist "dist\Athena.exe" (
  echo.
  echo SUCCESS: dist\Athena.exe
  echo.
  echo Copy Athena.exe wherever you want.
) else (
  echo BUILD FAILED
)
pause
