[CmdletBinding()]
param([string]$JavaHome, [string]$Server, [switch]$Test)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$python = Join-Path (Split-Path $PSScriptRoot -Parent) '.ops-venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $python)) { $python = (Get-Command python -ErrorAction Stop).Source }
$arguments = @('build.py')
if ($JavaHome) { $arguments += @('--java-home', $JavaHome) }
if ($Server) { $arguments += @('--server', $Server) }
if ($Test) {
    & $python -m unittest discover -s tests -v
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $arguments += '--test'
}
& $python @arguments
exit $LASTEXITCODE
