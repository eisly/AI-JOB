Set-StrictMode -Version 2.0

$script:UncoveredScope = @(
    'Redis（默认流程不启动）',
    'Qdrant（默认流程不启动）',
    'Python Agent 服务（仅验证依赖，不验证可启动性）',
    '前端（默认流程不启动）',
    '真实 MySQL 行为以外的服务间联调',
    '生产部署与 CI/CD'
)

function Get-ReportSnapshot {
    param([Parameter(Mandatory = $true)]$Context)

    $docker = Resolve-ExecutablePath -Name 'docker.exe'
    $tools = @()
    $conda = Resolve-CondaCommand
    $tools += Get-CondaVersionTool -Executable $conda
    $tools += Get-JavaVersionTool -Executable $null
    $tools += Get-MavenVersionTool -Executable $null
    $dockerState = if ($docker) { Get-DockerState -TimeoutSeconds 15 } else { [pscustomobject]@{ Reachable = $false; Message = 'docker 不在 PATH' } }
    $container = if ($docker) { Get-DockerContainerState -DockerCommand $docker -ProjectRoot $Context.ProjectRoot } else { [pscustomobject]@{ Exists = $false; State = 'missing'; Health = 'unknown' } }
    $volumeExists = if ($docker) { Test-DockerVolumeExists -DockerCommand $docker -VolumeName ('ai-job_' + 'mysql_data') } else { $false }
    $backendPort = 8080
    $dbPort = 3307
    $envPath = Join-Path $Context.ProjectRoot '.env'
    if (Test-Path -LiteralPath $envPath) {
        $envData = Read-EnvFile -Path $envPath
        if ($envData.Values.Contains('MYSQL_PORT') -and $envData.Values['MYSQL_PORT'] -match '^\d+$') { $dbPort = [int]$envData.Values['MYSQL_PORT'] }
    }
    return [pscustomobject]@{
        Tools          = $tools
        DockerState    = $dockerState
        ContainerState = $container
        VolumeExists   = $volumeExists
        DbPort         = $dbPort
        BackendPort    = $backendPort
    }
}

function Write-EnvReport {
    param(
        [Parameter(Mandatory = $true)]$Context,
        $Tools = @(),
        $DbState = $null,
        $BackendState = $null,
        $MigrationState = $null,
        $DemoState = $null,
        $TestState = $null
    )
    if ($Context.DryRun) { return $null }
    $report = [pscustomobject]@{
        SchemaVersion  = '1.0'
        RunId          = $Context.RunId
        GeneratedAt    = (Get-Date).ToString('o')
        OperatingSystem= [System.Environment]::OSVersion.VersionString
        Tools          = @($Tools)
        Stages         = @($Context.Stages)
        DbState        = $DbState
        BackendState   = $BackendState
        MigrationState = $MigrationState
        DemoState      = $DemoState
        TestState      = $TestState
        OverallExitCode= $Context.ExitCodeName
        UncoveredScope = $script:UncoveredScope
    }
    $json = $report | ConvertTo-Json -Depth 12
    $json = Protect-Secret -Text $json
    Write-Utf8NoBom -Path $Context.ReportPath -Content $json

    $summary = @(
        '========== AI-JOB 本地开发环境报告 ==========',
        ('RunId: ' + $Context.RunId),
        ('总体结论: ' + $Context.ExitCodeName),
        ('报告: ' + $Context.ReportPath)
    )
    foreach ($stage in @($Context.Stages)) {
        $summary += ('- ' + $stage.Stage + ': ' + $stage.Status + $(if ($stage.ExitCode -ne 'Success') { ' (' + $stage.ExitCode + ')' } else { '' }))
    }
    $summary += ('未覆盖范围: ' + ($script:UncoveredScope -join '；'))
    Write-Host ($summary -join [Environment]::NewLine)
    return $report
}

function Get-LatestJournalPath {
    param([Parameter(Mandatory = $true)]$Context)
    $files = @(Get-ChildItem -LiteralPath $Context.StateDirectory -Filter 'journal-*.json' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)
    if ($files.Count -eq 0) { return $null }
    return $files[0].FullName
}

function Get-ReportSummaryOnly {
    param([Parameter(Mandatory = $true)]$Context)
    $snapshot = Get-ReportSnapshot -Context $Context
    $lines = @(
        '========== 当前环境快照（只读） ==========',
        ('Docker: ' + $snapshot.DockerState.Message),
        ('MySQL 容器: ' + $snapshot.ContainerState.State + ' / ' + $snapshot.ContainerState.Health),
        ('命名卷存在: ' + $snapshot.VolumeExists),
        ('MySQL 端口: ' + $snapshot.DbPort),
        ('后端端口: ' + $snapshot.BackendPort + ', 监听: ' + (Test-TcpPortOpen -Port $snapshot.BackendPort)),
        ('报告文件: ' + $Context.ReportPath)
    )
    foreach ($tool in $snapshot.Tools) {
        $lines += ('- ' + $tool.Name + ': ' + $tool.Raw)
    }
    Write-Host ($lines -join [Environment]::NewLine)
    return $snapshot
}

Export-ModuleMember -Function @(
    'Get-ReportSnapshot',
    'Write-EnvReport',
    'Get-LatestJournalPath',
    'Get-ReportSummaryOnly'
)