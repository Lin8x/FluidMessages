# Installs (or updates) FluidMessages Web, the computer app, on Windows. In PowerShell:
#
#   irm https://raw.githubusercontent.com/Lin8x/FluidMessages/main/install.ps1 | iex
#
# To remove it:
#
#   & ([scriptblock]::Create((irm https://raw.githubusercontent.com/Lin8x/FluidMessages/main/install.ps1))) -Uninstall
#
# Puts the app in %LOCALAPPDATA%\Programs\FluidMessages Web and adds it to the Start menu. No administrator needed.
# The download is checked against the release's SHA256SUMS.txt before anything is installed. Your messages and
# settings are kept on uninstall.
param([switch]$Uninstall)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'  # Invoke-WebRequest is much faster without its progress bar.

$Latest = 'https://github.com/Lin8x/FluidMessages/releases/latest/download'
$App = 'FluidMessages Web'
$Name = 'FluidMessages-Web-Windows.exe'
$Home_ = Join-Path $env:LOCALAPPDATA "Programs\$App"
$Exe = Join-Path $Home_ 'FluidMessages Web.exe'
$Shortcut = Join-Path ([Environment]::GetFolderPath('Programs')) "$App.lnk"

if ($Uninstall) {
    Get-Process -Name $App -ErrorAction SilentlyContinue | Stop-Process -Force
    Remove-Item -Force -ErrorAction SilentlyContinue $Shortcut, $Exe
    Remove-Item -Force -ErrorAction SilentlyContinue $Home_
    Write-Host "Removed $App. Your messages and settings are still in $(Join-Path $env:LOCALAPPDATA $App); delete that folder to remove them too."
    return
}

$Tmp = Join-Path ([IO.Path]::GetTempPath()) ("fluidmessages-" + [Guid]::NewGuid())
New-Item -ItemType Directory -Path $Tmp | Out-Null
try {
    Write-Host "Downloading $Name..."
    Invoke-WebRequest -UseBasicParsing -Uri "$Latest/$Name" -OutFile (Join-Path $Tmp $Name)
    $Sums = (Invoke-WebRequest -UseBasicParsing -Uri "$Latest/SHA256SUMS.txt").Content
    if ($Sums -is [byte[]]) { $Sums = [Text.Encoding]::UTF8.GetString($Sums) }
    $Want = ($Sums -split "`n" | Where-Object { ($_ -split '\s+')[1] -eq $Name } | ForEach-Object { ($_ -split '\s+')[0] }) | Select-Object -First 1
    if (-not $Want) { throw "$Name isn't listed in SHA256SUMS.txt." }
    $Have = (Get-FileHash -Algorithm SHA256 (Join-Path $Tmp $Name)).Hash.ToLower()
    if ($Have -ne $Want.Trim().ToLower()) { throw "$Name doesn't match its SHA-256 in SHA256SUMS.txt, so nothing was installed." }
    Write-Host "Checked: $Name matches the release's SHA-256."

    Get-Process -Name $App -ErrorAction SilentlyContinue | Stop-Process -Force
    New-Item -ItemType Directory -Force -Path $Home_ | Out-Null
    Move-Item -Force (Join-Path $Tmp $Name) $Exe

    $Shell = New-Object -ComObject WScript.Shell
    $Link = $Shell.CreateShortcut($Shortcut)
    $Link.TargetPath = $Exe
    $Link.WorkingDirectory = $Home_
    $Link.Description = "Your phone's texts, calls, and chats on your computer"
    $Link.Save()

    Write-Host ""
    Write-Host "Installed $App. Open it from the Start menu. To update later, run the same command again."
} finally {
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $Tmp
}
