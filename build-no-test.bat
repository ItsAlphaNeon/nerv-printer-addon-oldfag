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
set dest1="C:\Users\Neon\Documents\Prism Launcher\instances\Nerv Printer\minecraft\mods\nerv-printer-1.21.11.jar"
set dest2="C:\Users\Neon\Documents\Prism Launcher\instances\Nerv Printer (Helper)\minecraft\mods\nerv-printer-1.21.11.jar"
set dest3="C:\Users\Neon\Documents\Prism Launcher\instances\Nerv Printer (Helper 2)\minecraft\mods\nerv-printer-1.21.11.jar"

echo Copying %source% to %dest1%
xcopy %source% %dest1% /Y
echo Copying %source% to %dest2%
xcopy %source% %dest2% /Y
echo Copying %source% to %dest3%
xcopy %source% %dest3% /Y

pause
