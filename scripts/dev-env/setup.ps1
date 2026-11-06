<#
.SYNOPSIS
    AI-JOB 本地开发环境编排入口。

.DESCRIPTION
    按 readme.md 复现本地开发环境：Conda/Python、.env、Docker MySQL、后端 Flyway、演示数据、测试与报告。
    默认不启动 Redis、Qdrant、Python Agent 与前端。

.PARAMETER Command
    子命令：check | python | env | db | backend | demo | test | all | status | report | stop | rollback | purge

.PARAMETER Approve
    确认型参数集合：RemoveCondaEnv | RemoveEnvFile | KillProcess

.EXAMPLE
    .\scripts\dev-env\setup.ps1 -Command check -DryRun

.EXAMPLE
    .\scripts\dev-env\setup.ps1 -Command all

.EXAMPLE
    .\scripts\dev-env\setup.ps1 -Command stop
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)][string]$Command = 'all',
    [string]$ProjectRoot,
    [switch]$NonInteractive,
    [switch]$SkipDemo,
    [switch]$IntegrationTest,
    [switch]$RecreatePython,
    [switch]$AllowNonLoopback,
    [string]$ConfirmDataLoss,
    [string[]]$Approve = @(),
    [int]$BackendTimeoutSeconds = 60,
    [int]$DbTimeoutSeconds = 0,
    [int]$TestTimeoutSeconds = 300,
    [string]$ReportPath,
    [string]$LogLevel = 'normal',
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

$libraryPath = Join-Path $PSScriptRoot 'lib'
Import-Module (Join-Path $libraryPath 'DevEnv.Core.psm1') -Force
Import-Module (Join-Path $libraryPath 'DevEnv.Preflight.psm1') -Force
Import-Module (Join-Path $libraryPath 'DevEnv.Python.psm1') -Force
Import-Module (Join-Path $libraryPath 'DevEnv.Docker.psm1') -Force
Import-Module (Join-Path $libraryPath 'DevEnv.Backend.psm1') -Force
Import-Module (Join-Path $libraryPath 'DevEnv.Data.psm1') -Force
Import-Module (Join-Path $libraryPath 'DevEnv.Report.psm1') -Force
Import-Module (Join-Path $libraryPath 'DevEnv.Rollback.psm1') -Force

$validCommands = @('check', 'python', 'env', 'db', 'backend', 'demo', 'test', 'all', 'status', 'report', 'stop', 'rollback', 'purge')
$validLogLevels = @('quiet', 'normal', 'verbose')
if ($validCommands -notcontains $Command) {
    Write-Host ('非法子命令: ' + $Command)
    Write-Host ('可用子命令: ' + ($validCommands -join ', '))
    exit 2
}
if ($validLogLevels -notcontains $LogLevel) {
    Write-Host ('非法日志级别: ' + $LogLevel)
    exit 2
}
$invalidApprovals = @($Approve | Where-Object { @('RemoveCondaEnv', 'RemoveEnvFile', 'KillProcess') -notcontains $_ })
if ($invalidApprovals.Count -gt 0) {
    Write-Host ('非法 -Approve 值: ' + ($invalidApprovals -join ','))
    exit 2
}
if (-not $ProjectRoot) {
    $ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
}
if (-not (Test-Path -LiteralPath $ProjectRoot)) {
    Write-Host ('项目根目录不存在: ' + $ProjectRoot)
    exit 11
}

$context = New-DevEnvContext -ProjectRoot $ProjectRoot -LogLevel $LogLevel -ReportPath $ReportPath -DryRun:$DryRun
$context | Add-Member -NotePropertyName Command -NotePropertyValue $Command -Force
$context | Add-Member -NotePropertyName BackendTimeoutSeconds -NotePropertyValue $BackendTimeoutSeconds -Force
$context | Add-Member -NotePropertyName TestTimeoutSeconds -NotePropertyValue $TestTimeoutSeconds -Force
$context | Add-Member -NotePropertyName DbTimeoutSeconds -NotePropertyValue ($(if ($DbTimeoutSeconds -gt 0) { $DbTimeoutSeconds } else { 900 })) -Force

$logPath = if ($DryRun) { $null } else { $context.LogPath }
$state = @{
    Tools          = @()
    PythonState    = $null
    DbState        = $null
    BackendState   = $null
    MigrationState = $null
    DemoState      = $null
    TestState      = $null
}
$exitCodeName = 'Success'
$lockAcquired = $false

function Write-Step {
    param([string]$Message, [string]$Level = 'Info')
    Write-DevLog -Message $Message -Level $Level -LogPath $logPath -LogLevel $LogLevel
}

function Set-EnvValue {
    param([string]$Path, [string]$Key, [string]$Value)
    $lines = @([System.IO.File]::ReadAllLines($Path))
    $found = $false
    for ($index = 0; $index -lt $lines.Length; $index++) {
        if ($lines[$index] -match ('^\s*' + [regex]::Escape($Key) + '\s*=')) {
            $lines[$index] = $Key + '=' + $Value
            $found = $true
        }
    }
    if (-not $found) { $lines += ($Key + '=' + $Value) }
    Write-Utf8NoBom -Path $Path -Content ($lines -join "`n")
}

function Read-PlainSecret {
    param([string]$Prompt)
    $secure = Read-Host -Prompt $Prompt -AsSecureString
    $pointer = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        return [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($pointer)
    }
    finally {
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Invoke-SmokeAssertions {
    $results = @()
    $results += [pscustomobject]@{ Name = 'exit-code-jdk'; Passed = ((Get-DevEnvExitCode -Name 'JdkIncompatible') -eq 12); Detail = 'JdkIncompatible=12' }
    $results += [pscustomobject]@{ Name = 'exit-code-docker'; Passed = ((Get-DevEnvExitCode -Name 'DockerUnavailable') -eq 14); Detail = 'DockerUnavailable=14' }
    Clear-DevEnvSecrets
    $results += [pscustomobject]@{ Name = 'protect-empty'; Passed = ((Protect-Secret -Text '' ) -eq ''); Detail = '空值不替换' }
    Add-DevEnvSecret -Secret @('SecretAlpha123', 'SecretBeta456')
    $redacted = Protect-Secret -Text 'a=SecretAlpha123 b=SecretBeta456'
    $results += [pscustomobject]@{ Name = 'protect-multiple'; Passed = ($redacted -eq 'a=<redacted> b=<redacted>'); Detail = $redacted }
    $results += [pscustomobject]@{ Name = 'protect-mysqlpwd'; Passed = ((Protect-Secret -Text 'MYSQL_PWD=abc') -eq 'MYSQL_PWD=<redacted>'); Detail = 'MYSQL_PWD 脱敏' }
    $preamble = (New-Object System.Text.UTF8Encoding($false)).GetPreamble()
    $results += [pscustomobject]@{ Name = 'utf8-no-bom'; Passed = ($preamble.Length -eq 0); Detail = 'UTF8 无 BOM' }
    Clear-DevEnvSecrets
    return $results
}

function Show-Assertions {
    param($Assertions, [string]$Title)
    $failed = @($Assertions | Where-Object { -not $_.Passed })
    Write-Step -Message ($Title + ': ' + ($Assertions.Count - $failed.Count) + '/' + $Assertions.Count + ' 通过')
    foreach ($assertion in $failed) {
        Write-Step -Message ('断言失败 ' + $assertion.Name + ' (' + $assertion.Detail + ')') -Level 'Error'
    }
    return ($failed.Count -eq 0)
}

function Get-ValidatedEnvironment {
    param([switch]$RequireConfigured)
    if (-not (Test-Path -LiteralPath $context.ProjectRoot)) { return @{ Code = 'WrongDirectory'; Config = $null; Validation = $null } }
    $config = Resolve-EnvConfig -ProjectRoot $context.ProjectRoot
    if (-not $config.EnvExists) {
        return @{ Code = 'EnvFileMissing'; Config = $config; Validation = $null }
    }
    Add-DevEnvSecret -Secret @([string]$config.EnvValues['MYSQL_PASSWORD'], [string]$config.EnvValues['MYSQL_ROOT_PASSWORD'])
    $validation = Test-EnvFile -Path $config.EnvPath -GitRoot $context.ProjectRoot -AllowNonLoopback:$AllowNonLoopback
    if ($RequireConfigured -and $validation.ExitCodeName -ne 'Success') {
        return @{ Code = $validation.ExitCodeName; Config = $config; Validation = $validation }
    }
    return @{ Code = 'Success'; Config = $config; Validation = $validation }
}

try {
    if (@('check', 'status', 'report') -notcontains $Command) {
        $lockAcquired = Enter-DevEnvLock -Context $context
    }
    Write-Step -Message ('命令: ' + $Command + '; 项目根: ' + $context.ProjectRoot + '; RunId: ' + $context.RunId)

    switch ($Command) {
        'check' {
            if ($DryRun) {
                $coreAssertions = Invoke-SmokeAssertions
                $preflightAssertions = Invoke-PreflightSelfTest
                $passed = (Show-Assertions -Assertions $coreAssertions -Title '核心断言') -and (Show-Assertions -Assertions $preflightAssertions -Title '版本解析断言')
                if (-not $passed) { $exitCodeName = 'InternalError'; break }
            }
            $config = Resolve-EnvConfig -ProjectRoot $context.ProjectRoot
            Add-DevEnvSecret -Secret @([string]$config.EnvValues['MYSQL_PASSWORD'], [string]$config.EnvValues['MYSQL_ROOT_PASSWORD'])
            $preflight = Invoke-Preflight -Context $context -EnvConfig $config -Mode Strict -TimeoutSeconds 30
            $state.Tools = $preflight.Tools
            Add-StageResult -Context $context -Result (New-StageResult -Stage 'Preflight' -Status $(if ($preflight.Passed) { 'Passed' } else { 'Failed' }) -ExitCodeName $preflight.ExitCodeName -Criteria $preflight.Criteria -Remediation $preflight.Remediation)
            $preflight.Tools | Format-Table Name, Raw, Compatible, RequiredRange | Out-String | Write-Host
            $exitCodeName = $preflight.ExitCodeName
        }
        'status' {
            $snapshot = Get-ReportSummaryOnly -Context $context
            $state.Tools = $snapshot.Tools
        }
        'report' {
            $snapshot = Get-ReportSnapshot -Context $context
            $state.Tools = $snapshot.Tools
            $state.DbState = [pscustomobject]@{
                ContainerState = $snapshot.ContainerState.State
                Health         = $snapshot.ContainerState.Health
                PortMapping    = $snapshot.ContainerState.Ports
                VolumeExists   = $snapshot.VolumeExists
                CredentialsInitialized = $snapshot.VolumeExists
            }
        }
        'env' {
            $init = Initialize-EnvFile -ProjectRoot $context.ProjectRoot
            if ($init.Created) {
                Add-ChangeEntry -Context $context -Kind 'EnvFileCreated' -Target $init.Path
                Write-Step -Message '已从 .env.example 生成 .env'
            }
            $config = Resolve-EnvConfig -ProjectRoot $context.ProjectRoot
            $needsPassword = (-not $config.Db.HasPassword) -or ($config.EnvValues['MYSQL_PASSWORD'] -match 'replace_with')
            if ($needsPassword) {
                if ($NonInteractive) {
                    $exitCodeName = 'EnvFileMissing'
                    Write-Step -Message '非交互模式下 .env 尚未配置 MYSQL_PASSWORD；请先设置密码' -Level 'Error'
                    break
                }
                $password = Read-PlainSecret -Prompt 'MYSQL_PASSWORD（输入不回显）'
                $rootPassword = Read-PlainSecret -Prompt 'MYSQL_ROOT_PASSWORD（输入不回显）'
                if (-not [string]::IsNullOrWhiteSpace($password)) { Set-EnvValue -Path $init.Path -Key 'MYSQL_PASSWORD' -Value $password }
                if (-not [string]::IsNullOrWhiteSpace($rootPassword)) { Set-EnvValue -Path $init.Path -Key 'MYSQL_ROOT_PASSWORD' -Value $rootPassword }
                $config = Resolve-EnvConfig -ProjectRoot $context.ProjectRoot
            }
            Add-DevEnvSecret -Secret @([string]$config.EnvValues['MYSQL_PASSWORD'], [string]$config.EnvValues['MYSQL_ROOT_PASSWORD'])
            $validation = Test-EnvFile -Path $config.EnvPath -GitRoot $context.ProjectRoot -AllowNonLoopback:$AllowNonLoopback
            $validation.Rules | Format-Table RuleId, Passed, Message | Out-String | Write-Host
            $failedRules = @($validation.Rules | Where-Object { -not $_.Passed })
            Add-StageResult -Context $context -Result (New-StageResult -Stage 'EnvConfig' -Status $(if ($failedRules.Count -eq 0) { 'Passed' } else { 'Failed' }) -ExitCodeName $validation.ExitCodeName -Criteria @($validation.Rules | ForEach-Object { $_.RuleId + ': ' + $_.Message }) -Remediation @($(if ($failedRules.Count -gt 0) { '按失败规则修正 .env；密码不得为占位值且两个密码必须不同' } else { '' })))
            $exitCodeName = $validation.ExitCodeName
        }
        'python' {
            $config = Resolve-EnvConfig -ProjectRoot $context.ProjectRoot
            $conda = Resolve-CondaCommand
            $condaTool = Get-CondaVersionTool -Executable $conda
            $state.Tools += $condaTool
            if (-not $condaTool.Compatible) {
                Add-StageResult -Context $context -Result (New-StageResult -Stage 'PythonEnv' -Status 'Failed' -ExitCodeName 'CondaUnavailable' -Criteria @('Conda 不可用') -Remediation @('安装 Conda 或将 condabin 加入 PATH'))
                $exitCodeName = 'CondaUnavailable'
                break
            }
            $python = Invoke-PythonStage -Context $context -EnvConfig $config -CondaCommand $conda -RecreatePython:$RecreatePython -TimeoutSeconds 900
            $state.PythonState = $python.PythonState
            Add-StageResult -Context $context -Result $python.StageResult
            if ($python.PythonState -and $python.PythonState.CreatedByThisRun) {
                Add-ChangeEntry -Context $context -Kind 'CondaEnvCreated' -Target $config.Python.EnvName
            }
            if ($python.StageResult.Status -ne 'Passed') { $exitCodeName = $python.StageResult.ExitCode }
        }
        'db' {
            $envResult = Get-ValidatedEnvironment -RequireConfigured
            $config = $envResult.Config
            if ($envResult.Code -ne 'Success') {
                Add-StageResult -Context $context -Result (New-StageResult -Stage 'EnvConfig' -Status 'Failed' -ExitCodeName $envResult.Code -Criteria @('环境文件校验未通过') -Remediation @('先执行 env 子命令并修正 .env'))
                $exitCodeName = $envResult.Code
                break
            }
            Add-DevEnvSecret -Secret @([string]$config.EnvValues['MYSQL_PASSWORD'])
            $portState = Get-PortState -Port $config.Db.Port
            if ($portState.InUse) {
                $docker = Resolve-ExecutablePath -Name 'docker.exe'
                $owned = $false
                if ($docker) {
                    $container = Get-DockerContainerState -DockerCommand $docker -ProjectRoot $context.ProjectRoot
                    $owned = ($container.Exists -and $container.State -eq 'running')
                }
                if (-not $owned -and ($Approve -notcontains 'KillProcess')) {
                    Add-StageResult -Context $context -Result (New-StageResult -Stage 'DbStart' -Status 'Failed' -ExitCodeName 'PortInUse' -Criteria @(('端口 ' + $config.Db.Port + ' 被占用 PID=' + (($portState.Owners | ForEach-Object { $_.Pid }) -join ','))) -Remediation @('修改 .env 的 MYSQL_PORT（仅首次启动前）或经批准结束占用进程'))
                    $exitCodeName = 'PortInUse'
                    break
                }
            }
            $db = Invoke-DockerStage -Context $context -EnvConfig $config -LogLevel $LogLevel
            $state.DbState = $db.DbState
            Add-StageResult -Context $context -Result $db.StageResult
            if ($db.StageResult.Status -eq 'Passed') {
                Add-ChangeEntry -Context $context -Kind 'ContainerStarted' -Target 'mysql'
                if ($db.DbState -and -not $db.DbState.VolumeExists) {
                    Add-ChangeEntry -Context $context -Kind 'VolumeCreated' -Target 'ai-job_mysql_data'
                }
            }
            if ($db.StageResult.Status -ne 'Passed') { $exitCodeName = $db.StageResult.ExitCode }
        }
        'backend' {
            $envResult = Get-ValidatedEnvironment -RequireConfigured
            $config = $envResult.Config
            if ($envResult.Code -ne 'Success') {
                $exitCodeName = $envResult.Code
                break
            }
            $docker = Resolve-ExecutablePath -Name 'docker.exe'
            if (-not $docker) { $exitCodeName = 'DockerUnavailable'; break }
            $container = Get-DockerContainerState -DockerCommand $docker -ProjectRoot $context.ProjectRoot
            if ($container.State -ne 'running' -or $container.Health -ne 'healthy') {
                Add-StageResult -Context $context -Result (New-StageResult -Stage 'BackendStart' -Status 'Failed' -ExitCodeName 'DbHealthTimeout' -Criteria @('MySQL 容器未处于 healthy 状态') -Remediation @('先执行 db 子命令并等待 healthy'))
                $exitCodeName = 'DbHealthTimeout'
                break
            }
            $backend = Invoke-BackendStage -Context $context -EnvConfig $config
            $state.BackendState = $backend.BackendState
            $state.MigrationState = $backend.MigrationState
            Add-StageResult -Context $context -Result $backend.StageResult
            if ($backend.BackendState -and $backend.BackendState.ProcessId -gt 0) {
                Add-ChangeEntry -Context $context -Kind 'BackendProcessStarted' -Target ([string]$backend.BackendState.ProcessId)
            }
            if ($backend.StageResult.Status -ne 'Passed') { $exitCodeName = $backend.StageResult.ExitCode }
        }
        'demo' {
            $envResult = Get-ValidatedEnvironment -RequireConfigured
            $config = $envResult.Config
            if ($envResult.Code -ne 'Success') { $exitCodeName = $envResult.Code; break }
            $docker = Resolve-ExecutablePath -Name 'docker.exe'
            if (-not $docker) { $exitCodeName = 'DockerUnavailable'; break }
            $demo = Invoke-DemoStage -Context $context -EnvConfig $config -SkipDemo:$SkipDemo
            $state.DemoState = $demo.DemoState
            Add-StageResult -Context $context -Result $demo.StageResult
            if ($demo.DemoState -and $demo.DemoState.Imported) {
                Add-ChangeEntry -Context $context -Kind 'DemoDataImported' -Target 'career_business'
            }
            if ($demo.StageResult.Status -eq 'Failed') { $exitCodeName = $demo.StageResult.ExitCode }
        }
        'test' {
            $test = Invoke-BackendTests -Context $context -IntegrationTest:$IntegrationTest
            $state.TestState = $test.TestState
            Add-StageResult -Context $context -Result $test.StageResult
            if ($test.StageResult.Status -ne 'Passed') { $exitCodeName = $test.StageResult.ExitCode }
        }
        'stop' {
            $stop = Invoke-Stop -Context $context
            Write-Step -Message ($stop.Actions -join '; ')
            if (-not $stop.Passed) { $exitCodeName = 'RollbackFailed' }
        }
        'rollback' {
            $rollback = Invoke-Rollback -Context $context -ApproveRemoveCondaEnv ($(if ($Approve -contains 'RemoveCondaEnv') { 'RemoveCondaEnv' } else { '' })) -ApproveRemoveEnvFile ($(if ($Approve -contains 'RemoveEnvFile') { 'RemoveEnvFile' } else { '' }))
            Write-Step -Message ($rollback.Actions -join '; ')
            if (-not $rollback.Passed) { $exitCodeName = $rollback.ExitCodeName }
        }
        'purge' {
            $purge = Invoke-Purge -Context $context -ConfirmDataLoss $ConfirmDataLoss
            Write-Step -Message $purge.Message -Level $(if ($purge.Passed) { 'Warn' } else { 'Error' })
            if (-not $purge.Passed) { $exitCodeName = $purge.ExitCodeName }
        }
        'all' {
            $config = Resolve-EnvConfig -ProjectRoot $context.ProjectRoot
            Add-DevEnvSecret -Secret @([string]$config.EnvValues['MYSQL_PASSWORD'], [string]$config.EnvValues['MYSQL_ROOT_PASSWORD'])
            $preflight = Invoke-Preflight -Context $context -EnvConfig $config -Mode Strict -TimeoutSeconds 30
            $state.Tools = $preflight.Tools
            Add-StageResult -Context $context -Result (New-StageResult -Stage 'Preflight' -Status $(if ($preflight.Passed) { 'Passed' } else { 'Failed' }) -ExitCodeName $preflight.ExitCodeName -Criteria $preflight.Criteria -Remediation $preflight.Remediation)
            if (-not $preflight.Passed) { $exitCodeName = $preflight.ExitCodeName; break }

            $init = Initialize-EnvFile -ProjectRoot $context.ProjectRoot
            if ($init.Created) { Add-ChangeEntry -Context $context -Kind 'EnvFileCreated' -Target $init.Path }
            $config = Resolve-EnvConfig -ProjectRoot $context.ProjectRoot
            $needsPassword = (-not $config.Db.HasPassword) -or ($config.EnvValues['MYSQL_PASSWORD'] -match 'replace_with')
            if ($needsPassword) {
                if ($NonInteractive) {
                    $exitCodeName = 'EnvFileMissing'
                    Add-StageResult -Context $context -Result (New-StageResult -Stage 'EnvConfig' -Status 'Blocked' -ExitCodeName 'EnvFileMissing' -Criteria @('.env 尚未配置密码') -Remediation @('设置 MYSQL_PASSWORD 与 MYSQL_ROOT_PASSWORD 后重试'))
                    break
                }
                $password = Read-PlainSecret -Prompt 'MYSQL_PASSWORD（输入不回显）'
                $rootPassword = Read-PlainSecret -Prompt 'MYSQL_ROOT_PASSWORD（输入不回显）'
                if (-not [string]::IsNullOrWhiteSpace($password)) { Set-EnvValue -Path $init.Path -Key 'MYSQL_PASSWORD' -Value $password }
                if (-not [string]::IsNullOrWhiteSpace($rootPassword)) { Set-EnvValue -Path $init.Path -Key 'MYSQL_ROOT_PASSWORD' -Value $rootPassword }
                $config = Resolve-EnvConfig -ProjectRoot $context.ProjectRoot
                Add-DevEnvSecret -Secret @([string]$config.EnvValues['MYSQL_PASSWORD'], [string]$config.EnvValues['MYSQL_ROOT_PASSWORD'])
            }
            $validation = Test-EnvFile -Path $config.EnvPath -GitRoot $context.ProjectRoot -AllowNonLoopback:$AllowNonLoopback
            Add-StageResult -Context $context -Result (New-StageResult -Stage 'EnvConfig' -Status $(if ($validation.ExitCodeName -eq 'Success') { 'Passed' } else { 'Failed' }) -ExitCodeName $validation.ExitCodeName -Criteria @($validation.Rules | ForEach-Object { $_.RuleId + ': ' + $_.Message }))
            if ($validation.ExitCodeName -ne 'Success') { $exitCodeName = $validation.ExitCodeName; break }

            $python = Invoke-PythonStage -Context $context -EnvConfig $config -CondaCommand (Resolve-CondaCommand) -RecreatePython:$RecreatePython
            $state.PythonState = $python.PythonState
            Add-StageResult -Context $context -Result $python.StageResult
            if ($python.PythonState -and $python.PythonState.CreatedByThisRun) { Add-ChangeEntry -Context $context -Kind 'CondaEnvCreated' -Target $config.Python.EnvName }
            if ($python.StageResult.Status -ne 'Passed') { $exitCodeName = $python.StageResult.ExitCode; break }

            $db = Invoke-DockerStage -Context $context -EnvConfig $config -LogLevel $LogLevel
            $state.DbState = $db.DbState
            Add-StageResult -Context $context -Result $db.StageResult
            if ($db.StageResult.Status -eq 'Passed') {
                Add-ChangeEntry -Context $context -Kind 'ContainerStarted' -Target 'mysql'
                if ($db.DbState -and -not $db.DbState.VolumeExists) { Add-ChangeEntry -Context $context -Kind 'VolumeCreated' -Target 'ai-job_mysql_data' }
            }
            if ($db.StageResult.Status -ne 'Passed') { $exitCodeName = $db.StageResult.ExitCode; break }

            $backend = Invoke-BackendStage -Context $context -EnvConfig $config
            $state.BackendState = $backend.BackendState
            $state.MigrationState = $backend.MigrationState
            Add-StageResult -Context $context -Result $backend.StageResult
            if ($backend.BackendState -and $backend.BackendState.ProcessId -gt 0) { Add-ChangeEntry -Context $context -Kind 'BackendProcessStarted' -Target ([string]$backend.BackendState.ProcessId) }
            if ($backend.StageResult.Status -ne 'Passed') { $exitCodeName = $backend.StageResult.ExitCode; break }

            $demo = Invoke-DemoStage -Context $context -EnvConfig $config -SkipDemo:$SkipDemo
            $state.DemoState = $demo.DemoState
            Add-StageResult -Context $context -Result $demo.StageResult
            if ($demo.DemoState -and $demo.DemoState.Imported) { Add-ChangeEntry -Context $context -Kind 'DemoDataImported' -Target 'career_business' }

            $test = Invoke-BackendTests -Context $context -IntegrationTest:$IntegrationTest
            $state.TestState = $test.TestState
            Add-StageResult -Context $context -Result $test.StageResult
            if ($test.StageResult.Status -ne 'Passed') { $exitCodeName = $test.StageResult.ExitCode }
        }
    }
}
catch {
    $exitCodeName = Resolve-DevEnvExitCode -ErrorRecord $_
    $remediation = @()
    if ($_.Exception.Data -and $_.Exception.Data.Contains('Remediation')) { $remediation = @($_.Exception.Data['Remediation']) }
    Write-Step -Message ('未捕获异常映射为 ' + $exitCodeName + ': ' + $_.Exception.Message) -Level 'Error'
    foreach ($item in $remediation) { Write-Step -Message ('整改建议: ' + $item) -Level 'Warn' }
    if ($LogLevel -eq 'verbose') {
        Write-Step -Message ($_.ScriptStackTrace) -Level 'Debug'
    }
}
finally {
    $context.ExitCodeName = $exitCodeName
    try {
        if (-not $DryRun -and $Command -ne 'status') {
            Write-EnvReport -Context $context -Tools $state.Tools -DbState $state.DbState -BackendState $state.BackendState -MigrationState $state.MigrationState -DemoState $state.DemoState -TestState $state.TestState | Out-Null
        }
    }
    catch {
        Write-Host ('生成报告失败: ' + $_.Exception.Message)
    }
    if ($lockAcquired) { Exit-DevEnvLock -Context $context }
}

Write-Step -Message ('退出码: ' + $exitCodeName + ' (' + (Get-DevEnvExitCode -Name $exitCodeName) + ')')
exit (Get-DevEnvExitCode -Name $exitCodeName)