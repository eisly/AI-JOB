param(
    [string]$Root = (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
)

$encoding = New-Object System.Text.UTF8Encoding($true)
$files = @(Get-ChildItem -LiteralPath (Join-Path $Root 'scripts/dev-env') -Recurse -File | Where-Object { $_.Extension -eq '.ps1' -or $_.Extension -eq '.psm1' })
foreach ($file in $files) {
    $content = [System.IO.File]::ReadAllText($file.FullName)
    [System.IO.File]::WriteAllText($file.FullName, $content, $encoding)
    Write-Host ('UTF8-BOM: ' + $file.Name)
}