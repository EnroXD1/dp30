param([string] $LanIP)
$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$tools = Join-Path (Split-Path -Parent $project) 'work/dp30-tools'
$python = Join-Path $tools 'venv/Scripts/python.exe'
$caddy = Join-Path $tools 'caddy/caddy.exe'
$localCA = Join-Path $tools 'tls/ca.pem'
$android = Join-Path $project 'android'
$port = 8444
$proxy = $null

try {
    foreach ($path in @($python, $caddy, $localCA, "$android/gradlew.bat", "$project/ops/Caddyfile.wifi")) {
        if (-not (Test-Path -LiteralPath $path)) { throw "Missing $path. Prepare the Windows demo as described in README.md." }
    }
    # Restrict the listener to a selected private IPv4 address, not every adapter.
    $addresses = @(Get-NetIPAddress -AddressFamily IPv4 -AddressState Preferred |
        Where-Object { $_.IPAddress -match '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)' -and -not $_.SkipAsSource } |
        Sort-Object InterfaceIndex, IPAddress)
    if (-not $addresses.Count) { throw 'No private IPv4 address found. Enable the hotspot or connect to a trusted Wi-Fi network first.' }
    if ($LanIP) {
        $selected = $addresses | Where-Object IPAddress -eq $LanIP | Select-Object -First 1
    } elseif ($addresses.Count -eq 1) {
        $selected = $addresses[0]
    } else {
        Write-Host 'Select the network interface used by the phones:'
        for ($i = 0; $i -lt $addresses.Count; $i++) {
            Write-Host ('  {0}. {1} ({2})' -f ($i + 1), $addresses[$i].InterfaceAlias, $addresses[$i].IPAddress)
        }
        $default = 0
        for ($i = 0; $i -lt $addresses.Count; $i++) { if ($addresses[$i].IPAddress -eq '192.168.137.1') { $default = $i + 1; break } }
        $answer = Read-Host "Interface number (hotspot default: $default; 0 means no default)"
        $index = 0
        if (-not $answer -and $default -gt 0) { $index = $default }
        elseif (-not [int]::TryParse($answer, [ref]$index)) { throw 'Invalid selection.' }
        if ($index -lt 1 -or $index -gt $addresses.Count) { throw 'Invalid selection.' }
        $selected = $addresses[$index - 1]
    }
    if (-not $selected) { throw 'The specified IP is not assigned to a private local interface.' }
    $ip = $selected.IPAddress
    $url = "https://${ip}:$port"
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalAddress -in @($ip, '0.0.0.0', '::') }) { throw "Port $port is already in use. Close the other Wi-Fi server first." }

    # Keep a distinct CA per address. The existing loopback certificate is untouched.
    $wifi = Join-Path $tools "wifi/$ip"
    $tls = Join-Path $wifi 'tls'
    $resource = Join-Path $wifi 'demo-res'
    New-Item -ItemType Directory -Force -Path "$resource/raw" | Out-Null
    $tlsFiles = @('ca.pem', 'server.pem', 'server.key')
    $existing = @($tlsFiles | Where-Object { Test-Path -LiteralPath (Join-Path $tls $_) })
    if ($existing.Count -eq 0) {
        Push-Location "$project/server"
        try {
            & $python -m dp30.tls $tls $ip
            if ($LASTEXITCODE -ne 0) { throw 'TLS generation failed.' }
        } finally { Pop-Location }
    } elseif ($existing.Count -ne 3) { throw "Incomplete TLS files in $tls. Move the folder aside and retry." }
    $wifiCA = Join-Path $tls 'ca.pem'
    $bundle = (Get-Content -Raw -LiteralPath $localCA).Trim() + "`n" + (Get-Content -Raw -LiteralPath $wifiCA).Trim() + "`n"
    Set-Content -LiteralPath "$resource/raw/dp30_demo_ca.pem" -Value $bundle -Encoding ASCII

    # Build an APK trusting both the original CA and this Wi-Fi CA, keeping the old APK.
    $env:ANDROID_HOME = Join-Path $tools 'sdk'
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    if (-not $env:JAVA_HOME -and -not (Get-Command java.exe -ErrorAction SilentlyContinue)) {
        throw 'JDK 17 is required. Set JAVA_HOME or add Java to PATH, then retry.'
    }
    $demoAPK = Join-Path $android 'app/build/outputs/apk/demo/app-demo.apk'
    $wifiAPK = Join-Path $wifi 'DP30-WiFi.apk'
    $backup = $null
    if (Test-Path -LiteralPath $demoAPK) {
        $backup = Join-Path $wifi ('original-' + [guid]::NewGuid().ToString() + '.apk')
        Copy-Item -LiteralPath $demoAPK -Destination $backup
    }
    Push-Location $android
    try {
        & .\gradlew.bat "-Pdp30DemoRes=$resource" :app:assembleDemo
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $demoAPK)) { throw 'APK build failed.' }
        Copy-Item -LiteralPath $demoAPK -Destination $wifiAPK -Force
    } finally {
        Pop-Location
        if ($backup) {
            Copy-Item -LiteralPath $backup -Destination $demoAPK -Force
            Remove-Item -LiteralPath $backup
        }
    }
    # Reuse the same database and API processes as the normal launcher.
    & "$project/start-demo.ps1" -ServerOnly

    # Ask Windows for admin rights only to add a narrowly scoped firewall rule.
    $rule = "DP30-WiFi-$ip"
    $safeAlias = $selected.InterfaceAlias.Replace("'", "''")
    $safeProgram = $caddy.Replace("'", "''")
    $firewall = @"
`$ErrorActionPreference = 'Stop'
try {
    Get-NetFirewallRule -Name '$rule' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    New-NetFirewallRule -Name '$rule' -DisplayName '$rule' -Direction Inbound -Action Allow -Protocol TCP -LocalPort $port -LocalAddress '$ip' -RemoteAddress LocalSubnet -InterfaceAlias '$safeAlias' -Program '$safeProgram' -Profile Any | Out-Null
    exit 0
} catch { Write-Error `$_; exit 1 }
"@
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($firewall))
    $admin = Start-Process powershell.exe -Verb RunAs -ArgumentList @('-NoProfile','-EncodedCommand',$encoded) -PassThru -Wait
    if ($admin.ExitCode -ne 0) { throw 'Could not configure Windows Firewall.' }

    $env:DP30_WIFI_IP = $ip
    $env:DP30_WIFI_CERT = (Join-Path $tls 'server.pem').Replace('\','/')
    $env:DP30_WIFI_KEY = (Join-Path $tls 'server.key').Replace('\','/')
    $proxy = Start-Process $caddy -WorkingDirectory "$project/ops" -ArgumentList @('run','--adapter','caddyfile','--config','Caddyfile.wifi') -PassThru -WindowStyle Hidden -RedirectStandardOutput "$wifi/caddy.out.log" -RedirectStandardError "$wifi/caddy.err.log"
    $probe = 'import sys,ssl,json,urllib.request; ctx=ssl.create_default_context(cafile=sys.argv[2]); opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=ctx)); r=opener.open(sys.argv[1]+''/health'',timeout=2); sys.exit(0 if json.load(r)=={''status'':''ok''} else 1)'
    $healthy = $false
    for ($i = 0; $i -lt 20; $i++) {
        if ($proxy.HasExited) { break }
        try {
            & $python -c $probe $url $wifiCA 2>$null
            if ($LASTEXITCODE -eq 0) { $healthy = $true; break }
        } catch { }
        Start-Sleep -Seconds 1
    }
    if (-not $healthy) { throw "Wi-Fi HTTPS health check failed. See $wifi/caddy.err.log" }
    Write-Host "`nDP-30 Wi-Fi is ready: $url" -ForegroundColor Green
    Write-Host "APK: $wifiAPK"
    Write-Host 'Connect phones to the selected Wi-Fi network, install this APK and enter the URL on the login screen.'
    Write-Host 'The original USB/emulator server stays at https://127.0.0.1:8443.'
    Read-Host 'Press Enter to stop the Wi-Fi listener' | Out-Null
} catch {
    Write-Host $_ -ForegroundColor Red
    exit 1
} finally {
    if ($proxy -and -not $proxy.HasExited) { Stop-Process -Id $proxy.Id -ErrorAction SilentlyContinue }
}
