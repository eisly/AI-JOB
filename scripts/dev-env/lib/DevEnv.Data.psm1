Set-StrictMode -Version 2.0

function Get-TableCount {
    param(
        [Parameter(Mandatory = $true)][string]$DockerCommand,
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)]$EnvConfig,
        [Parameter(Mandatory = $true)][string]$TableName
    )
    $result = Get-DatabaseScalar -DockerCommand $DockerCommand -Context $Context -EnvConfig $EnvConfig -Sql ('SELECT COUNT(*) FROM `' + $TableName + '`')
    if ($result.Success -and $result.Output -match '^\d+$') { return [int]$result.Output }
    return -1
}

function Test-DemoPreconditions {
    param(
        [Parameter(Mandatory = $true)][string]$DockerCommand,
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)]$EnvConfig
    )
    $required = @('app_user', 'job', 'job_version', 'skill_catalog', 'question')
    $counts = [ordered]@{}
    $missing = @()
    foreach ($table in $required) {
        $count = Get-TableCount -DockerCommand $DockerCommand -Context $Context -EnvConfig $EnvConfig -TableName $table
        $counts[$table] = $count
        if ($count -lt 0) { $missing += $table }
    }
    return [pscustomobject]@{
        Passed  = ($missing.Count -eq 0)
        Missing = $missing
        Counts  = $counts
        AllEmpty= (@($counts.Values | Where-Object { $_ -ne 0 }).Count -eq 0)
    }
}

function Invoke-SqlWithInput {
    param(
        [Parameter(Mandatory = $true)][string]$DockerCommand,
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)]$EnvConfig,
        [Parameter(Mandatory = $true)][string]$SqlText
    )
    $password = [string]$EnvConfig.EnvValues['MYSQL_PASSWORD']
    Add-DevEnvSecret -Secret @($password)
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $DockerCommand
    $line = @(
        'compose', 'exec', '-T', '-e', ('"MYSQL_PWD=' + $password + '"'), 'mysql',
        'mysql', '--default-character-set=utf8mb4', ('-u' + $EnvConfig.Db.User), ('-D' + $EnvConfig.Db.Database)
    ) -join ' '
    $startInfo.Arguments = $line
    $startInfo.WorkingDirectory = $Context.ProjectRoot
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    if (-not $process.Start()) {
        return [pscustomobject]@{ ExitCode = -1; StdOut = ''; StdErr = '无法启动 docker compose exec' }
    }
    $writer = New-Object System.IO.StreamWriter($process.StandardInput.BaseStream, (New-Object System.Text.UTF8Encoding($false)))
    $writer.Write($SqlText)
    $writer.Close()
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    return [pscustomobject]@{
        ExitCode = $process.ExitCode
        StdOut   = (Protect-Secret -Text $stdout)
        StdErr   = (Protect-Secret -Text $stderr)
    }
}

function Invoke-DemoStage {
    param(
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)]$EnvConfig,
        [switch]$SkipDemo
    )
    $declaration = '演示数据仅限本地开发库、账号已禁用、数据为虚构、不可重复导入'
    if ($SkipDemo) {
        return [pscustomobject]@{
            StageResult = (New-StageResult -Stage 'DemoData' -Status 'Skipped' -ExitCodeName 'Success' -Criteria @('-SkipDemo 已启用，未执行任何 DML', $declaration))
            DemoState   = [pscustomobject]@{ Imported = $false; Skipped = $true; KeyRowCounts = [ordered]@{}; CharsetVerified = $false }
        }
    }
    if ($Context.DryRun) {
        return [pscustomobject]@{
            StageResult = (New-StageResult -Stage 'DemoData' -Status 'Passed' -ExitCodeName 'Success' -Criteria @('DryRun: 将导入演示数据', $declaration))
            DemoState   = [pscustomobject]@{ Imported = $false; Skipped = $true; KeyRowCounts = [ordered]@{}; CharsetVerified = $false }
        }
    }

    $docker = Resolve-ExecutablePath -Name 'docker.exe'
    if (-not $docker) {
        return [pscustomobject]@{
            StageResult = (New-StageResult -Stage 'DemoData' -Status 'Failed' -ExitCodeName 'DockerUnavailable' -Criteria @('docker 不可用', $declaration) -Remediation @('启动 Docker Desktop'))
            DemoState   = $null
        }
    }

    $preconditions = Test-DemoPreconditions -DockerCommand $docker -Context $Context -EnvConfig $EnvConfig
    if (-not $preconditions.Passed) {
        return [pscustomobject]@{
            StageResult = (New-StageResult -Stage 'DemoData' -Status 'Failed' -ExitCodeName 'DemoTableMissing' -Criteria @(('缺少表: ' + ($preconditions.Missing -join ',')) + '', $declaration) -Remediation @('先启动后端完成 Flyway 迁移'))
            DemoState   = $null
        }
    }
    if (-not $preconditions.AllEmpty) {
        return [pscustomobject]@{
            StageResult = (New-StageResult -Stage 'DemoData' -Status 'Skipped' -ExitCodeName 'Success' -Criteria @('五张关键表已存在数据，跳过导入（脚本不可重复导入）', $declaration))
            DemoState   = [pscustomobject]@{ Imported = $false; Skipped = $true; KeyRowCounts = $preconditions.Counts; CharsetVerified = $false }
        }
    }

    $demoPath = Join-Path $Context.BackendRoot 'src/main/resources/db/demo/demo_data.sql'
    if (-not (Test-Path -LiteralPath $demoPath)) {
        return [pscustomobject]@{
            StageResult = (New-StageResult -Stage 'DemoData' -Status 'Failed' -ExitCodeName 'DemoImportFailed' -Criteria @('演示数据脚本缺失', $declaration) -Remediation @('确认 demo_data.sql 存在'))
            DemoState   = $null
        }
    }
    $sql = [System.IO.File]::ReadAllText($demoPath)
    $import = Invoke-SqlWithInput -DockerCommand $docker -Context $Context -EnvConfig $EnvConfig -SqlText $sql
    if ($import.ExitCode -ne 0) {
        return [pscustomobject]@{
            StageResult = (New-StageResult -Stage 'DemoData' -Status 'Failed' -ExitCodeName 'DemoImportFailed' -Criteria @($declaration) -Messages @($import.StdErr) -Remediation @('检查 SQL 与账号权限；失败时不会清空数据库'))
            DemoState   = $null
        }
    }

    $nickname = Get-DatabaseScalar -DockerCommand $docker -Context $Context -EnvConfig $EnvConfig -Sql "SELECT nickname FROM app_user WHERE email='candidate@example.invalid'"
    $skills = Get-DatabaseScalar -DockerCommand $docker -Context $Context -EnvConfig $EnvConfig -Sql "SELECT GROUP_CONCAT(code) FROM skill_catalog WHERE code IN ('java','mysql','redis','spring')"
    $jobCount = Get-TableCount -DockerCommand $docker -Context $Context -EnvConfig $EnvConfig -TableName 'job'
    $charsetOk = ($nickname.Success -and $nickname.Output -eq '演示求职者') -and
                 ($skills.Success -and $skills.Output -match 'java' -and $skills.Output -match 'mysql' -and $skills.Output -match 'redis' -and $skills.Output -match 'spring') -and
                 ($jobCount -gt 0)
    if (-not $charsetOk) {
        return [pscustomobject]@{
            StageResult = (New-StageResult -Stage 'DemoData' -Status 'Failed' -ExitCodeName 'DemoCharsetMismatch' -Criteria @($declaration, '抽样校验未通过') -Messages @(('昵称=' + $nickname.Output + '; 技能=' + $skills.Output + '; 岗位数=' + $jobCount)) -Remediation @('检查连接字符集与 utf8mb4 设置'))
            DemoState   = $null
        }
    }

    $counts = Test-DemoPreconditions -DockerCommand $docker -Context $Context -EnvConfig $EnvConfig
    return [pscustomobject]@{
        StageResult = (New-StageResult -Stage 'DemoData' -Status 'Passed' -ExitCodeName 'Success' -Criteria @('演示数据导入成功', '中文抽样校验通过', $declaration))
        DemoState   = [pscustomobject]@{ Imported = $true; Skipped = $false; KeyRowCounts = $counts.Counts; CharsetVerified = $true }
    }
}

Export-ModuleMember -Function @(
    'Get-TableCount',
    'Test-DemoPreconditions',
    'Invoke-SqlWithInput',
    'Invoke-DemoStage'
)