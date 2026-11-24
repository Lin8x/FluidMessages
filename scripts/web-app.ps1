# Runs FluidMessages Web on this computer and opens it as an app (a PWA), for Windows.
#
#   powershell -ExecutionPolicy Bypass -File scripts\web-app.ps1
#   ... -Port 9090      use another port
#   ... -Full           also install the optional XMPP and encrypted-Matrix support (into a private venv)
#   ... -NoOpen         don't open a window, just print the address
#
# Your messages and accounts stay in %LOCALAPPDATA%\FluidMessages Web. To install it as an app, use Edge's or Chrome's
# "Install FluidMessages" button once it opens. localhost counts as secure, so it installs without HTTPS.
param([int]$Port = 8080, [switch]$Full, [switch]$NoOpen)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

# Works in Windows PowerShell 5.1 and PowerShell 7.
$python = Get-Command py -ErrorAction SilentlyContinue
if (-not $python) { $python = Get-Command python -ErrorAction SilentlyContinue }
if (-not $python) { throw 'Install Python 3.11 or newer from python.org first.' }
$python = $python.Source
$data = Join-Path $env:LOCALAPPDATA 'FluidMessages Web'
New-Item -ItemType Directory -Force -Path $data | Out-Null

$runner = $python
$venv = Join-Path $data 'venv\Scripts\python.exe'
if ($Full) {
  if (-not (Test-Path $venv)) { & $python -m venv (Join-Path $data 'venv') }
  & $venv -m pip install --quiet 'slixmpp==1.17.0' 'aiohttp>=3.10' 'matrix-nio[e2e]==0.26.0'
}
if (Test-Path $venv) { $runner = $venv }

# The access token is made once and kept in the private folder.
$tokenFile = Join-Path $data 'token'
if (-not (Test-Path $tokenFile)) {
  & $python -c "import secrets; print(secrets.token_hex(32))" | Set-Content -NoNewline -Path $tokenFile
}
$token = (Get-Content $tokenFile -Raw).Trim()

$env:FLUID_TOKEN = $token
$env:FLUID_DB = Join-Path $data 'fluid.sqlite'
$env:FLUID_BIND = '127.0.0.1'
$env:FLUID_PORT = "$Port"
$server = Start-Process -FilePath $runner -ArgumentList 'server\main.py' -NoNewWindow -PassThru
$url = "http://localhost:$Port"
for ($i = 0; $i -lt 50; $i++) {
  try { Invoke-WebRequest -UseBasicParsing -Uri "$url/" -TimeoutSec 1 | Out-Null; break } catch { Start-Sleep -Milliseconds 200 }
}

Write-Host ""
Write-Host "  FluidMessages Web is running at $url"
Write-Host "  It opens without a password in any browser on this computer. Other devices need a"
Write-Host "  password, which you can set in Settings -> Phone and other devices."
Write-Host ""
Write-Host "  To install it as an app: in Edge or Chrome, use the Install button in the address bar or the menu."
Write-Host "  Press Ctrl+C to stop."
Write-Host ""

if (-not $NoOpen) {
  $browsers = @("${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe", "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe",
    "$env:ProgramFiles\Google\Chrome\Application\chrome.exe", "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe")
  $browser = $browsers | Where-Object { Test-Path $_ } | Select-Object -First 1
  if ($browser) { Start-Process $browser "--app=$url/#token=$token" } else { Start-Process "$url/#token=$token" }
}
try { Wait-Process -Id $server.Id } finally { if (-not $server.HasExited) { Stop-Process -Id $server.Id } }
