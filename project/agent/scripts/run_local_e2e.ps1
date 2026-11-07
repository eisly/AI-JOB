param(
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$agent = Join-Path $root "project\agent"
$backend = Join-Path $root "project\backend"
$data = Join-Path $agent "data"
New-Item -ItemType Directory -Path $data -Force | Out-Null

function Wait-Http($uri, $timeoutSeconds = 5) {
    try {
        $response = Invoke-WebRequest -Uri $uri -UseBasicParsing -TimeoutSec $timeoutSeconds
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 500
    } catch {
        return $false
    }
}

foreach ($port in @(8080, 8100)) {
    $connections = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($connections) {
        $connections | Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object {
            Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue
        }
    }
}
Start-Sleep -Seconds 2

$agentLog = Join-Path $data "agent-e2e.log"
$agentCommand = "`$env:AGENT_LLM_PROVIDER='fake'; Set-Location '$agent'; conda run -n ai-job python run.py *> '$agentLog'"
$agentProcess = Start-Process powershell.exe -ArgumentList "-NoProfile", "-Command", $agentCommand -WindowStyle Hidden -PassThru

$javaOut = Join-Path $backend "run-e2e.log"
$javaErr = Join-Path $backend "run-e2e.err.log"
$javaProcess = Start-Process -FilePath "mvn.cmd" `
    -ArgumentList "-q", "-DskipTests", "spring-boot:run", "-Dspring-boot.run.profiles=local" `
    -WorkingDirectory $backend -RedirectStandardOutput $javaOut -RedirectStandardError $javaErr -WindowStyle Hidden -PassThru

$agentReady = $false
$javaPort = $false
$deadline = (Get-Date).AddMinutes(5)
while ((Get-Date) -lt $deadline) {
    if (-not $agentReady) {
        $agentReady = Wait-Http "http://127.0.0.1:8100/healthz" 2
    }
    if (-not $javaPort) {
        $javaPort = [bool](Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)
    }
    if ($agentReady -and $javaPort) {
        break
    }
    Start-Sleep -Seconds 2
}
if (-not $agentReady) {
    Write-Host "Agent did not become ready. Log: $agentLog"
    if (Test-Path $agentLog) { Get-Content $agentLog -Tail 80 }
    exit 1
}
if (-not $javaPort) {
    Write-Host "Java did not listen on 8080. Logs: $javaOut / $javaErr"
    if (Test-Path $javaOut) { Get-Content $javaOut -Tail 80 }
    if (Test-Path $javaErr) { Get-Content $javaErr -Tail 80 }
    exit 1
}

$env:MYSQL_PWD = "12345"
$migrationReady = $false
$deadline = (Get-Date).AddMinutes(4)
while ((Get-Date) -lt $deadline) {
    $count = & mysql -h 127.0.0.1 -P 3306 -u root -N -B -e "SELECT COUNT(*) FROM career_business.flyway_schema_history WHERE success=1" 2>$null
    if ($LASTEXITCODE -eq 0 -and "$count".Trim() -match '^\d+$' -and [int]"$count".Trim() -ge 5) {
        $migrationReady = $true
        break
    }
    Start-Sleep -Seconds 2
}
if (-not $migrationReady) {
    Write-Host "Flyway migrations did not reach V5. Log: $javaOut"
    if (Test-Path $javaOut) { Get-Content $javaOut -Tail 120 }
    exit 1
}
Write-Host "Services ready: agent=8100 java=8080 migrations=5"

if (-not $SkipTests) {
    Push-Location $backend
    mvn -q test "-Dtest=CompanySizesTest,JobSearchFingerprintTest,JobSearchLinksTest" "-DfailIfNoTests=false"
    $testExit = $LASTEXITCODE
    Pop-Location
    if ($testExit -ne 0) {
        Write-Host "Java unit tests failed."
        exit $testExit
    }
    Write-Host "Java rule tests passed."

    Push-Location $agent
    conda run -n ai-job python -m pytest tests/test_job_search.py tests/test_job_search_sources.py tests/test_job_search_dedupe.py tests/test_job_search_lever.py tests/test_job_search_verification_flow.py tests/test_search_providers.py tests/test_search_planning.py tests/test_verification.py tests/test_search_metrics.py -q
    $pythonTestExit = $LASTEXITCODE
    Pop-Location
    if ($pythonTestExit -ne 0) {
        Write-Host "Python search tests failed."
        exit $pythonTestExit
    }
    Write-Host "Python search tests passed."
}

Push-Location $agent
conda run -n ai-job python scripts/e2e_job_search.py
$e2eExit = $LASTEXITCODE
Pop-Location

Write-Host "E2E exit code: $e2eExit"
Write-Host "Agent log: $agentLog"
Write-Host "Java log: $javaOut"
Write-Host "Report: $(Join-Path $data 'e2e_job_search_report.json')"
exit $e2eExit