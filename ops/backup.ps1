[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Destination,
    [string] $PgBin = 'C:/Program Files/PostgreSQL/18/bin'
)
$ErrorActionPreference = 'Stop'
if (-not $env:DP30_DATABASE_URL) { throw 'Set DP30_DATABASE_URL.' }
New-Item -ItemType Directory -Force -Path $Destination | Out-Null
$dpName = 'dp30-{0}-{1}' -f [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'), [guid]::NewGuid().ToString('N').Substring(0,8)
$dpPending = Join-Path $Destination "$dpName.pending"
$dpDump = Join-Path $Destination "$dpName.dump"
& (Join-Path $PgBin 'pg_dump.exe') --format=custom --file=$dpPending --dbname=$env:DP30_DATABASE_URL
if ($LASTEXITCODE -ne 0) { throw 'pg_dump failed.' }
& (Join-Path $PgBin 'pg_restore.exe') --list $dpPending | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Archive validation failed.' }
Move-Item -LiteralPath $dpPending -Destination $dpDump
Get-Item -LiteralPath $dpDump
