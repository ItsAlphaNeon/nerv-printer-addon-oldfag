@echo off

REM Run Gradle build (skip tests)
echo Running Gradle build...
call .\gradlew.bat build -x test
if %errorlevel% neq 0 (
    echo Gradle build failed. Aborting file copy.
    pause
    exit /b 1
)

set source="C:\Users\Neon\Documents\GitHub\nerv-printer-addon-oldfag\build\libs\nerv-printer-1.21.11.jar"
set destination="C:\Users\Neon\Documents\Prism Launcher\instances\Nerv Printer\minecraft\mods\nerv-printer-1.21.11.jar"

echo Copying %source% to %destination%
xcopy %source% %destination% /Y

pause
