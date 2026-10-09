@echo off
setlocal
cd /d "%~dp0"
set "JAVA="
for /d %%D in ("..\tools\jdk25\*") do set "JAVA=%%D\bin\java.exe"
"%JAVA%" -Xms2G -Xmx2G -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -jar paper-26.2-132.jar --nogui
