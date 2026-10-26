# Builds the shared runtime plugin (paperclj), the three example plugins and the reusable modules,
# and copies their jars to server\plugins. Uses the portable JDK 25 in tools\jdk25; nothing is
# installed or changed system-wide. Maven dependencies (and the locally installed paperclj
# artifact the plugins compile against) live in .\.m2.
#
#   .\build.ps1          build everything and copy the jars to server\plugins
#   .\build.ps1 -Test    run `lein test` for every plugin that has tests before building it
#
# Stop the server first: Windows locks the jars a running server has loaded.
param(
    [switch]$Test
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

$jdk = (Get-ChildItem (Join-Path $root 'tools\jdk25') -Directory | Select-Object -First 1).FullName
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"

$serverPlugins = Join-Path $root 'server\plugins'
New-Item -ItemType Directory -Force $serverPlugins | Out-Null

function Invoke-Lein([string]$dir, [string[]]$tasks) {
    Push-Location $dir
    try {
        foreach ($task in $tasks) {
            lein.bat $task
            if ($LASTEXITCODE -ne 0) { throw "lein $task failed in $dir" }
        }
    } finally {
        Pop-Location
    }
}

# paperclj first: `install` publishes the API jar that plugins compile against,
# `uberjar` makes the runtime plugin (Clojure + nREPL + DSL) the server loads.
Write-Host '== building paperclj'
$framework = Join-Path $root 'framework'
Invoke-Lein $framework @('install', 'uberjar')
Copy-Item (Join-Path $framework 'target\paperclj.jar') $serverPlugins -Force

# The examples, then skycore (the library the reusable modules depend on) and the modules.
$plugins = 'hello', 'join-greeter', 'tasks-demo', 'skycore', 'auth', 'economy', 'ranks', 'teleport', 'chat', 'admin'

foreach ($name in $plugins) {
    $dir = Join-Path $root "plugins\$name"
    if ($Test -and (Test-Path (Join-Path $dir 'test'))) {
        Write-Host "== testing $name"
        Invoke-Lein $dir @('test')
    }
    Write-Host "== building $name"
    Invoke-Lein $dir @('uberjar')
    Copy-Item (Join-Path $dir "target\$name.jar") $serverPlugins -Force
}

Get-ChildItem $serverPlugins -Filter *.jar | Select-Object Name, Length
