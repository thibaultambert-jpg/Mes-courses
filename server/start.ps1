# Démarre l'hôte « Mes courses » sous Windows (PowerShell).
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (Test-Path .env) {
  Get-Content .env | Where-Object { $_ -match '^\s*[^#].*=' } | ForEach-Object {
    $k, $v = $_ -split '=', 2; [Environment]::SetEnvironmentVariable($k.Trim(), $v.Trim())
  }
}
if (-not $env:MES_COURSES_TOKEN) { throw "Crée server\.env à partir de .env.example (MES_COURSES_TOKEN)" }
if (-not $env:MES_COURSES_DATA) { $env:MES_COURSES_DATA = Join-Path $PWD "data" }
if (-not $env:MES_COURSES_CONFIG) { $env:MES_COURSES_CONFIG = Join-Path $PWD "fr-grocery.json" }
New-Item -ItemType Directory -Force $env:MES_COURSES_DATA | Out-Null

$major = [int]((node -p "process.versions.node.split('.')[0]") 2>$null)
if ($major -lt 26) { throw "Node.js >= 26 est requis (fr-grocery-mcp)." }

try { Invoke-WebRequest http://127.0.0.1:9222/json/version -UseBasicParsing | Out-Null }
catch {
  $chrome = "$env:ProgramFiles\Google\Chrome\Application\chrome.exe"
  if (Test-Path $chrome) {
    Start-Process $chrome "--remote-debugging-port=9222 --user-data-dir=`"$env:MES_COURSES_DATA\chrome`" --no-first-run https://www.e.leclerc/"
    Start-Sleep 3
  } else { Write-Warning "Chrome introuvable : Leclerc ne fonctionnera pas." }
}

if (Get-Command docker -ErrorAction SilentlyContinue) { docker compose up -d searxng }

Write-Host "Passerelle MCP sur http://0.0.0.0:8811 (/carrefour/mcp, /leclerc/mcp)"
npx -y supergateway@4.2.1 --config supergateway.jsonc
