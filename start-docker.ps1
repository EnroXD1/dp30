param(
    [ValidateSet('Start','Stop','Update')][string]$Mode = 'Start',
    [string]$BindAddress = ''
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$root = $PSScriptRoot
$envFile = '.local/docker.env'
$compose = @('compose','--project-name','dp30-docker','--env-file',$envFile,'-f','compose.yaml','-f','compose.launcher.yaml')
function Invoke-Compose([string[]]$Arguments) {
    & docker @compose @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed: $($Arguments -join ' ')" }
}
function New-Secret {
    $bytes = New-Object byte[] 24
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return ([BitConverter]::ToString($bytes)).Replace('-','').ToLowerInvariant()
}
try {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Install Docker Desktop first.' }
    & docker info --format '{{.ServerVersion}}'
    if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop and wait for the Linux engine.' }
    if ($Mode -eq 'Stop') {
        if (-not (Test-Path $envFile)) { throw 'No Docker launcher configuration exists yet.' }
        Invoke-Compose -Arguments @('stop')
        Write-Host 'Stopped. Database and TLS files are preserved.'
        exit 0
    }
    New-Item -ItemType Directory -Force '.local','.local/docker-tls','.local/docker-backup' | Out-Null
    if (-not (Test-Path $envFile)) {
        @("POSTGRES_PASSWORD=$(New-Secret)","DP30_DEMO_PASSWORD=$(New-Secret)") | Set-Content -LiteralPath $envFile -Encoding ASCII
    }
    $settings = ConvertFrom-StringData (Get-Content -LiteralPath $envFile -Raw)
    if ($settings.POSTGRES_PASSWORD -notmatch '^[a-f0-9]{48}$' -or $settings.DP30_DEMO_PASSWORD -notmatch '^[a-f0-9]{48}$') {
        throw 'Invalid .local/docker.env. Restore the original file; do not reset an existing DB password.'
    }
    $env:POSTGRES_PASSWORD = $settings.POSTGRES_PASSWORD
    $env:DP30_DEMO_PASSWORD = $settings.DP30_DEMO_PASSWORD
    $addresses = @(Get-NetIPAddress -AddressFamily IPv4 | Where-Object {
        $_.AddressState -eq 'Preferred' -and $_.IPAddress -notmatch '^(127\.|169\.254\.|0\.)'
    } | Sort-Object -Property InterfaceAlias,IPAddress)
    if (-not $BindAddress) {
        Write-Host '0. Local only (127.0.0.1)'
        for ($i=0; $i -lt $addresses.Count; $i++) {
            Write-Host ('{0}. {1} - {2}' -f ($i+1),$addresses[$i].InterfaceAlias,$addresses[$i].IPAddress)
        }
        $default = 0
        for ($i=0; $i -lt $addresses.Count; $i++) { if ($addresses[$i].IPAddress -eq '192.168.137.1') { $default = $i+1; break } }
        $choice = Read-Host "Select Wi-Fi / hotspot interface (Enter = $default)"
        if ([string]::IsNullOrWhiteSpace($choice)) { $choice = [string]$default }
        $number = 0
        if (-not [int]::TryParse($choice,[ref]$number) -or $number -lt 0 -or $number -gt $addresses.Count) { throw 'Invalid interface selection.' }
        $BindAddress = if ($number -eq 0) { '127.0.0.1' } else { $addresses[$number-1].IPAddress }
    }
    if ($BindAddress -ne '127.0.0.1' -and $BindAddress -notin @($addresses | ForEach-Object IPAddress)) { throw 'Choose an IPv4 currently assigned to this computer.' }
    $env:DP30_DOCKER_BIND = $BindAddress
    Invoke-Compose -Arguments @('config','--quiet')
    $image = & docker image ls --quiet 'dp30-launcher-server:local'
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect local images.' }
    if ($Mode -eq 'Update' -or -not $image) {
        Invoke-Compose -Arguments @('build','api1')
    }
    Invoke-Compose -Arguments @('run','--rm','--no-deps','tls',$BindAddress)
    Invoke-Compose -Arguments @('up','-d','--no-build')
    Invoke-Compose -Arguments @('run','--rm','--no-deps','-e','DP30_DEMO_PASSWORD','api1','python','-m','dp30.demo')
    # Reload certificates even if Caddy was already running.
    Invoke-Compose -Arguments @('up','-d','--no-deps','--no-build','--force-recreate','https')
    $url = "https://${BindAddress}:8443"
    $ca = Join-Path $root '.local/docker-tls/ca.pem'
    $ready = $false
    for ($i=0; $i -lt 30; $i++) {
        & curl.exe --noproxy '*' --cacert $ca --connect-timeout 2 --max-time 3 --fail --silent "$url/health" | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'HTTPS health check failed. Check Docker logs, port 8443, selected interface and firewall. Containers may still be running.' }
    Write-Host "`nDP30 Docker server ready: $url" -ForegroundColor Green
    Write-Host "Android public CA: $(Join-Path $root '.local/docker-tls/DP30-CA.crt')"
    Write-Host 'Demo login: dispatcher / worker / manager / admin'
    Write-Host "Initial demo password: $($settings.DP30_DEMO_PASSWORD)"
    Write-Host 'Existing account passwords are not changed by the seed.'
    if ($BindAddress -ne '127.0.0.1') {
        $rule = "DP30 Docker HTTPS $BindAddress"
        $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
        $admin = (New-Object Security.Principal.WindowsPrincipal($identity)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
        if ($admin) {
            $allow = Read-Host 'Add a Windows firewall rule for this IP and local subnet? [y/N]'
            if ($allow -eq 'y' -and -not (Get-NetFirewallRule -DisplayName $rule -ErrorAction SilentlyContinue)) {
                New-NetFirewallRule -DisplayName $rule -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8443 -LocalAddress $BindAddress -RemoteAddress LocalSubnet -Profile Any | Out-Null
            }
        } else {
            Write-Host 'If phone cannot connect, run the following in an Administrator PowerShell:'
            Write-Host "New-NetFirewallRule -DisplayName '$rule' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8443 -LocalAddress $BindAddress -RemoteAddress LocalSubnet -Profile Any"
        }
    }
    Write-Host 'You can close this window. Use Stop-DP30-Docker.cmd to stop the server.'
    exit 0
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
