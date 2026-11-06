Set-StrictMode -Version 2.0

function Test-TcpPortOpen {
    param([Parameter(Mandatory = $true)][int]$Port, [int]$TimeoutMilliseconds = 1000)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if ($async.AsyncWaitHandle.WaitOne($TimeoutMilliseconds)) {
            $client.EndConnect($async)
            return $true
        }
        return $false
    }
    catch { return $false }
    finally { $client.Close() }
}

function Get-BackendFailureClass {
    param([string]$LogText)
    if ($LogText -match 'Unsupported class file major version') { return @{ Code = 'JdkIncompatible'; Hint = 'JDK 版本低于编译目标，需 JDK 21' } }
    if ($LogText -match 'FlywayException|checksum mismatch|Validate failed') { return @{ Code = 'MigrationFailed'; Hint = '迁移失败或校验和不一致：不要修改已执行的 V1，应新增迁移版本' } }
    if ($LogText -match 'Access denied') { return @{ Code = 'BackendDbUnreachable'; Hint = '认证失败：先核对库内密码，再核对 .env' } }
    if ($LogText -match 'Communications link failure|Connection refused') { return @{ Code = 'BackendDbUnreachable'; Hint = '数据库不可达：确认容器 healthy 且端口映射正确' } }
    if ($LogText -match 'Port .* was already in use|Address already in use') { return @{ Code = 'BackendDbUnreachable'; Hint = '端口被占用：使用 SERVER_PORT 覆盖或释放端口' } }
    if ($LogText -match 'COMPILATION ERROR|cannot find symbol') { return @{ Code = 'TestCompileError'; Hint = '编译失败：检查 JDK 与源码' } }
    return @{ Code = 'BackendStartTimeout'; Hint = '查看后端日志定位启动失败原因' }
}

function Wait-BackendReady {
    param(
        [Parameter(Mandatory = $true)][string]$LogPath,
        [Parameter(Mandatory = $true)][int]$Port,
        [int]$TimeoutSeconds = 60
    )
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $markerSeen = $false
    while ((Get-Date) -lt $deadline) {
        if (Test-Path -LiteralPath $LogPath) {
            try {
                $text = [System.IO.File]::ReadAllText($LogPath)
                if ($text -match 'Started BackendApplication') { $markerSeen = $true }
            }
            catch { }
        }
        if ($markerSeen -and (Test-TcpPortOpen -Port $Port)) {
            return [pscustomobject]@{ Ready = $true; MarkerSeen = $true; PortOpen = $true; DurationMs = [int]$stopwatch.ElapsedMilliseconds }
        }
        Start-Sleep -Seconds 1
    }
    return [pscustomobject]@{
        Ready      = $false
        MarkerSeen = $markerSeen
        PortOpen   = (Test-TcpPortOpen -Port $Port)
        DurationMs = [int]$stopwatch.ElapsedMilliseconds
    }
}

function Start-BackendProcess {
    param(
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)]$EnvConfig
    )
    $logDirectory = Join-Path $Context.StateDirectory 'logs'
    if (-not (Test-Path -LiteralPath $logDirectory)) { New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null }
    $backendLog = Join-Path $logDirectory ('backend-{0}.log' -f $Context.RunId)
    $stdoutLog = $backendLog
    $stderrLog = Join-Path $logDirectory ('backend-{0}.err.log' -f $Context.RunId)
    $maven = Resolve-ExecutablePath -Name 'mvn.cmd'
    if (-not $maven) {
        throw (New-DevEnvException -Code 'MavenIncompatible' -Message 'mvn 命令不可用')
    }
    $arguments = '/c ""{0}" spring-boot:run"' -f $maven
    $process = Start-Process -FilePath 'cmd.exe' -ArgumentList $arguments -WorkingDirectory $Context.BackendRoot -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog -PassThru -NoNewWindow
    return [pscustomobject]@{
        ProcessId = $process.Id
        LogPath   = $backendLog
        StdErrLog = $stderrLog
        StartedAt = (Get-Date)
    }
}

function Stop-BackendProcess {
    param(
        [Parameter(Mandatory = $true)]$Context,
        [int]$ProcessId
    )
    if (-not $ProcessId -or $ProcessId -le 0) {
        $journalPath = Join-Path $Context.StateDirectory ('journal-{0}.json' -f $Context.RunId)
        if (Test-Path -LiteralPath $journalPath) {
            try {
                $journal = Get-Content -LiteralPath $journalPath -Raw | ConvertFrom-Json
                $entry = @($journal.Entries) | Where-Object { $_.Kind -eq 'BackendProcessStarted' -and $_.CreatedByThisRun } | Select-Object -Last 1
                if ($entry) { $ProcessId = [int]$entry.Target }
            }
            catch { }
        }
    }
    if (-not $ProcessId -or $ProcessId -le 0) {
        return [pscustomobject]@{ Stopped = $true; Message = '没有记录到本次启动的后端进程' }
    }
    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if (-not $process) {
        return [pscustomobject]@{ Stopped = $true; Message = '进程已不存在（幂等）' }
    }
    $null = & taskkill.exe /PID $ProcessId /T /F 2>&1
    Start-Sleep -Milliseconds 500
    $stillRunning = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    return [pscustomobject]@{ Stopped = (-not $stillRunning); Message = if ($stillRunning) { '进程仍存在，需人工检查' } else { '已结束进程树' } }
}

function Get-DatabaseScalar {
    param(
        [Parameter(Mandatory = $true)][string]$DockerCommand,
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)]$EnvConfig,
        [Parameter(Mandatory = $true)][string]$Sql
    )
    $password = [string]$EnvConfig.EnvValues['MYSQL_PASSWORD']
    Add-DevEnvSecret -Secret @($password)
    $result = Invoke-External -FilePath $DockerCommand -ArgumentList @(
        'compose', 'exec', '-T', '-e', ('MYSQL_PWD=' + $password), 'mysql',
        'mysql', '--default-character-set=utf8mb4', ('-u' + $EnvConfig.Db.User), ('-D' + $EnvConfig.Db.Database), '-N', '-B', '-e', $Sql
    ) -WorkingDirectory $Context.ProjectRoot -TimeoutSeconds 90 -AllowFailure
    return [pscustomobject]@{
        Success = ($result.ExitCode -eq 0)
        Output  = $result.StdOut.Trim()
        Error   = $result.StdErr.Trim()
    }
}

function Test-MigrationState {
    param(
        [Parameter(Mandatory = $true)][string]$DockerCommand,
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)]$EnvConfig
    )
    $history = Get-DatabaseScalar -DockerCommand $DockerCommand -Context $Context -EnvConfig $EnvConfig -Sql 'SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank'
    if (-not $history.Success) {
        return [pscustomobject]@{ Passed = $false; AppliedVersions = @(); TableCount = 0; Message = '无法读取 flyway_schema_history'; Error = $history.Error }
    }
    $versions = @($history.Output -split "`n" | Where-Object { $_ -match '^\d+$' } | ForEach-Object { [int]$_ })
    if ($versions -notcontains 1) {
        return [pscustomobject]@{ Passed = $false; AppliedVersions = $versions; TableCount = 0; Message = '缺少 version=1 的成功迁移记录'; Error = '' }
    }
    $count = Get-DatabaseScalar -DockerCommand $DockerCommand -Context $Context -EnvConfig $EnvConfig -Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'"
    $tableCount = if ($count.Success -and $count.Output -match '^\d+$') { [int]$count.Output } else { 0 }
    return [pscustomobject]@{
        Passed          = ($tableCount -eq 31)
        AppliedVersions = $versions
        TableCount      = $tableCount
        ExpectedTables  = 31
        Message         = if ($tableCount -eq 31) { 'V1 已应用，业务表 31 张' } else { '业务表数量不符，实际 ' + $tableCount + '，期望 31' }
        Error           = ''
    }
}

function Invoke-BackendStage {
    param(
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)]$EnvConfig,
        [switch]$SkipStart
    )
    $criteria = @()
    $messages = @()
    $docker = Resolve-ExecutablePath -Name 'docker.exe'

    if ($Context.DryRun) {
        return [pscustomobject]@{
            StageResult   = (New-StageResult -Stage 'BackendStart' -Status 'Passed' -ExitCodeName 'Success' -Criteria @('DryRun: 将启动后端并校验 Flyway'))
            BackendState  = [pscustomobject]@{ ProcessId = 0; Port = $EnvConfig.Backend.Port; StartupDurationMs = 0; LogPath = '' }
            MigrationState= $null
        }
    }

    $running = Get-PortState -Port $EnvConfig.Backend.Port
    if ($running.InUse) {
        $criteria += ('端口 ' + $EnvConfig.Backend.Port + ' 已占用，复用已有实例')
        $ready = [pscustomobject]@{ Ready = $true; MarkerSeen = $true; PortOpen = $true; DurationMs = 0 }
        $processId = 0
    }
    else {
        $started = Start-BackendProcess -Context $Context -EnvConfig $EnvConfig
        $processId = $started.ProcessId
        $messages += ('后端进程已启动，日志: ' + $started.LogPath)
        $ready = Wait-BackendReady -LogPath $started.LogPath -Port $EnvConfig.Backend.Port -TimeoutSeconds $Context.BackendTimeoutSeconds
        if (-not $ready.Ready) {
            $logText = ''
            if (Test-Path -LiteralPath $started.LogPath) {
                try {
                    $logText = [System.IO.File]::ReadAllText($started.LogPath)
                    $tail = ($logText -split "`n" | Select-Object -Last 80) -join "`n"
                    Add-Content -LiteralPath $started.LogPath -Value ($tail) -Encoding UTF8
                }
                catch { }
            }
            $failure = Get-BackendFailureClass -LogText $logText
            $null = Stop-BackendProcess -Context $Context -ProcessId $processId
            return [pscustomobject]@{
                StageResult = (New-StageResult -Stage 'BackendStart' -Status 'Failed' -ExitCodeName $failure.Code -Criteria $criteria -Messages @('后端未在时限内就绪', $failure.Hint) -Remediation @('查看日志尾部；确认 JDK 21、MySQL healthy、8080 空闲'))
                BackendState = [pscustomobject]@{ ProcessId = $processId; Port = $EnvConfig.Backend.Port; StartupDurationMs = $ready.DurationMs; LogPath = $started.LogPath }
                MigrationState = $null
            }
        }
    }

    if (-not $docker) {
        return [pscustomobject]@{
            StageResult = (New-StageResult -Stage 'BackendStart' -Status 'Failed' -ExitCodeName 'DockerUnavailable' -Criteria $criteria -Remediation @('docker 不可用，无法校验迁移'))
            BackendState = [pscustomobject]@{ ProcessId = $processId; Port = $EnvConfig.Backend.Port; StartupDurationMs = $ready.DurationMs; LogPath = '' }
            MigrationState = $null
        }
    }

    $migration = Test-MigrationState -DockerCommand $docker -Context $Context -EnvConfig $EnvConfig
    $criteria += $migration.Message
    $status = if ($migration.Passed) { 'Passed' } else { 'Failed' }
    $codeName = if ($migration.Passed) { 'Success' } else { 'MigrationFailed' }
    $result = New-StageResult -Stage 'BackendStart' -Status $status -ExitCodeName $codeName -Criteria $criteria -Messages $messages -Remediation @($(if (-not $migration.Passed) { '确认后端首次启动已完成 Flyway；不要修改已执行的 V1 脚本' } else { '' }))
    return [pscustomobject]@{
        StageResult    = $result
        BackendState   = [pscustomobject]@{ ProcessId = $processId; Port = $EnvConfig.Backend.Port; StartupDurationMs = $ready.DurationMs; LogPath = '' }
        MigrationState = $migration
    }
}

function Invoke-BackendTests {
    param(
        [Parameter(Mandatory = $true)]$Context,
        [switch]$IntegrationTest
    )
    if ($Context.DryRun) {
        return [pscustomobject]@{
            StageResult = (New-StageResult -Stage 'Test' -Status 'Passed' -ExitCodeName 'Success' -Criteria @('DryRun: 将执行 mvn test'))
            TestState   = [pscustomobject]@{ Total = 0; Passed = 0; Failed = 0; Errors = 0; Skipped = 0; DurationMs = 0 }
        }
    }
    $arguments = @('test')
    if ($IntegrationTest) {
        $arguments += '-DmysqlIt=true'
    }
    $result = Invoke-External -FilePath (Resolve-ExecutablePath -Name 'mvn.cmd') -ArgumentList $arguments -WorkingDirectory $Context.BackendRoot -TimeoutSeconds $Context.TestTimeoutSeconds -AllowFailure -LogLevel $Context.LogLevel

    $total = 0; $failures = 0; $errors = 0; $skipped = 0; $durationMs = 0
    $reportDirectory = Join-Path $Context.BackendRoot 'target/surefire-reports'
    if (Test-Path -LiteralPath $reportDirectory) {
        foreach ($file in @(Get-ChildItem -LiteralPath $reportDirectory -Filter '*.xml' -ErrorAction SilentlyContinue)) {
            try {
                [xml]$xml = [System.IO.File]::ReadAllText($file.FullName)
                $suite = $xml.testsuite
                $total += [int]$suite.tests
                $failures += [int]$suite.failures
                $errors += [int]$suite.errors
                $skipped += [int]$suite.skipped
                $durationMs += [int]([double]$suite.time * 1000)
            }
            catch { }
        }
    }

    $passed = ($result.ExitCode -eq 0 -and $failures -eq 0 -and $errors -eq 0)
    $codeName = 'Success'
    if (-not $passed) {
        $combined = $result.StdOut + "`n" + $result.StdErr
        if ($combined -match 'COMPILATION ERROR') { $codeName = 'TestCompileError' }
        elseif ($errors -gt 0) { $codeName = 'TestContextFailed' }
        elseif ($failures -gt 0) { $codeName = 'TestAssertionFailed' }
        else { $codeName = 'TestFailed' }
    }
    $criteria = @(
        ('Maven 退出码: ' + $result.ExitCode),
        ('用例总数: ' + $total + ', 失败: ' + $failures + ', 错误: ' + $errors + ', 跳过: ' + $skipped),
        '已验证: H2 上下文启动；未验证: 真实 MySQL 行为、Redis、服务间联调'
    )
    if ($IntegrationTest) {
        $criteria += '集成通道: mvn test -DmysqlIt=true（真实库用例）'
    }
    return [pscustomobject]@{
        StageResult = (New-StageResult -Stage 'Test' -Status $(if ($passed) { 'Passed' } else { 'Failed' }) -ExitCodeName $codeName -Criteria $criteria -Messages @($(if (-not $passed) { 'mvn test 未通过，详见 surefire 报告' } else { '' })) -Remediation @($(if (-not $passed) { '按失败类型检查编译、断言或 Spring 上下文；集成测试需先启动 MySQL' } else { '' })))
        TestState   = [pscustomobject]@{ Total = $total; Passed = ($total - $failures - $errors - $skipped); Failed = $failures; Errors = $errors; Skipped = $skipped; DurationMs = $durationMs }
    }
}

Export-ModuleMember -Function @(
    'Test-TcpPortOpen',
    'Get-BackendFailureClass',
    'Wait-BackendReady',
    'Start-BackendProcess',
    'Stop-BackendProcess',
    'Get-DatabaseScalar',
    'Test-MigrationState',
    'Invoke-BackendStage',
    'Invoke-BackendTests'
)