# Starts Paper using the portable JDK 25 in ..\tools\jdk25 (does not touch system Java/PATH).
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$java = (Get-ChildItem (Join-Path $root '..\tools\jdk25') -Directory | Select-Object -First 1).FullName + '\bin\java.exe'
Set-Location $root
& $java -Xms2G -Xmx2G -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 `
  -jar paper-26.2-132.jar --nogui
