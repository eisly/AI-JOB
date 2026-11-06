param(
    [string]$Root = (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
)

$files = @(Get-ChildItem -LiteralPath $Root -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.Extension -eq '.ps1' -or $_.Extension -eq '.psm1' })
$failed = 0
foreach ($file in $files) {
    $tokens = $null
    $errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$errors) | Out-Null
    if ($errors.Count -gt 0) {
        $failed++
        Write-Host ('FAIL ' + $file.FullName)
        foreach ($parseError in $errors) {
            Write-Host ('  line ' + $parseError.Extent.StartLineNumber + ': ' + $parseError.Message)
        }
    }
    else {
        Write-Host ('OK   ' + $file.Name)
    }
}
if ($failed -gt 0) { exit 1 }
exit 0