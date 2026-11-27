param([switch] $Headless, [switch] $ServerOnly)
$ErrorActionPreference = 'Stop'
$dpProject = $PSScriptRoot
$dpTools = Join-Path (Split-Path -Parent $dpProject) 'work/dp30-tools'
$dpPython = Join-Path $dpTools 'venv/Scripts/python.exe'
$dpPg = Join-Path $env:ProgramFiles 'PostgreSQL/18/bin'
$dpAdb = Join-Path $dpTools 'sdk/platform-tools/adb.exe'
$dpEmulator = Join-Path $dpTools 'sdk/emulator/emulator.exe'
$dpApk = Join-Path $dpProject 'android/app/build/outputs/apk/demo/app-demo.apk'
$dpCa = Join-Path $dpTools 'tls/ca.pem'
$dpCert = Join-Path $dpTools 'tls/server.pem'
$dpKey = Join-Path $dpTools 'tls/server.key'
$dpProxy = Join-Path $dpTools 'caddy/caddy.exe'
$dpRequired = @($dpPython,$dpCa,$dpCert,$dpKey,$dpProxy,"$dpPg/pg_ctl.exe")
if (-not $ServerOnly) { $dpRequired += @($dpAdb,$dpEmulator,$dpApk) }
foreach ($dpPath in $dpRequired) {
    if (-not (Test-Path -LiteralPath $dpPath)) { throw "Отсутствует файл $dpPath. См. README.md." }
}
& "$dpPg/pg_isready.exe" -q -h 127.0.0.1 -p 55430
if ($LASTEXITCODE -ne 0) {
    & "$dpPg/pg_ctl.exe" -D "$dpTools/pgdata" -l "$dpTools/postgres.log" -o "-h 127.0.0.1 -p 55430" -w start
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось запустить PostgreSQL.' }
}
$dpConnection = Get-NetTCPConnection -State Listen -LocalAddress 127.0.0.1 -LocalPort 8443 -ErrorAction SilentlyContinue
if (-not $dpConnection) {
    $env:DP30_DATABASE_URL = 'postgresql://dp30_local@127.0.0.1:55430/dp30'
    foreach ($dpPort in @(8451,8452)) {
        $dpBackend = Get-NetTCPConnection -State Listen -LocalAddress 127.0.0.1 -LocalPort $dpPort -ErrorAction SilentlyContinue
        if (-not $dpBackend) {
            Start-Process $dpPython -WorkingDirectory "$dpProject/server" -WindowStyle Hidden -ArgumentList @(
                '-m','uvicorn','dp30.main:app','--host','127.0.0.1','--port',"$dpPort",'--no-access-log'
            ) -RedirectStandardError "$dpTools/api-launch-$dpPort.log"
        }
    }
    $env:DP30_CERT_FILE = $dpCert.Replace('\','/')
    $env:DP30_KEY_FILE = $dpKey.Replace('\','/')
    $env:DP30_PROXY_PORT = '8443'
    $env:DP30_API_PORT_A = '8451'
    $env:DP30_API_PORT_B = '8452'
    Start-Process $dpProxy -WorkingDirectory "$dpProject/ops" -WindowStyle Hidden -ArgumentList @(
        'run','--adapter','caddyfile','--config','Caddyfile.local'
    ) -RedirectStandardError "$dpTools/proxy-launch.log"
}
$dpDeadline = (Get-Date).AddSeconds(20)
$dpHealthy = $false
do {
    try {
        & $dpPython -c "import ssl,sys,urllib.request; urllib.request.urlopen('https://127.0.0.1:8443/health',context=ssl.create_default_context(cafile=sys.argv[1]),timeout=2)" $dpCa 2>$null
        if ($LASTEXITCODE -eq 0) { $dpHealthy = $true; break }
    } catch {
        # A backend can still be starting when Caddy starts listening.
    }
    Start-Sleep -Seconds 1
} while ((Get-Date) -lt $dpDeadline)
if (-not $dpHealthy) { throw 'HTTPS-сервер не ответил. Проверьте журналы proxy-launch.log и api-launch.' }
if ($ServerOnly) {
    Write-Output 'DP-30 API is ready at https://127.0.0.1:8443'
    return
}
$env:ANDROID_HOME = Join-Path $dpTools 'sdk'
$env:ANDROID_AVD_HOME = Join-Path $dpTools 'avds'
$env:ANDROID_EMULATOR_HOME = Join-Path $dpTools 'emulator-home'
New-Item -ItemType Directory -Force -Path $env:ANDROID_EMULATOR_HOME | Out-Null
$dpDevices = & $dpAdb devices
if (-not ($dpDevices -match '^emulator-5556\s+device')) {
    $dpEmulatorArguments = @(
        '-avd','dp30_api29','-port','5556','-no-audio','-gpu','software','-memory','4096',
        '-no-snapshot','-no-boot-anim','-no-metrics'
    )
    if ($Headless) {
        Start-Process $dpEmulator -ArgumentList ($dpEmulatorArguments + '-no-window') -WindowStyle Hidden -RedirectStandardOutput "$dpTools/launch-emu.out.log" -RedirectStandardError "$dpTools/launch-emu.err.log"
    } else {
        Start-Process $dpEmulator -ArgumentList $dpEmulatorArguments -RedirectStandardOutput "$dpTools/launch-emu.out.log" -RedirectStandardError "$dpTools/launch-emu.err.log"
    }
}
$dpStop = (Get-Date).AddMinutes(3)
do {
    try {
        $dpBoot = & $dpAdb -s emulator-5556 shell getprop sys.boot_completed 2>$null
    } catch {
        $dpBoot = $null
    }
    if ($dpBoot -eq '1') { break }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $dpStop)
if ($dpBoot -ne '1') { throw 'Android не успел загрузиться.' }
& $dpAdb -s emulator-5556 install -r $dpApk
if ($LASTEXITCODE -ne 0) { throw 'Не удалось установить APK.' }
& $dpAdb -s emulator-5556 shell am start -n ru.tulsu.dp30/.MainActivity
