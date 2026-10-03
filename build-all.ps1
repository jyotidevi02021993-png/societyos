# Windows version of build-all.sh. Each service is an independent project; this only loops.
# Usage: .\build-all.ps1 [-DskipTests] [-Pintegration]
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

Get-ChildItem -Directory services | ForEach-Object {
  Write-Host "==> $($_.Name)"
  Push-Location $_.FullName
  & .\mvnw.cmd -q -B package @args
  if ($LASTEXITCODE -ne 0) { Pop-Location; throw "Build failed: $($_.Name)" }
  Pop-Location
}
Write-Host "All services built."
