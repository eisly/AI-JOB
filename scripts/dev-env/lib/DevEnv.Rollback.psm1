Set-StrictMode -Version 2.0

function Add-ChangeEntry {
    param(
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)][string]$Kind,
        [Parameter(Mandatory = $true)][string]$Target,
        [bool]$CreatedByThisRun = $true,
        [bool]$IsReversible = $true
    )
    if ($Context.DryRun) { return }
    $journal = $null
    if (Test-Path -LiteralPath $Context.JournalPath) {
        try { $journal = Get-Content -LiteralPath $Context.JournalPath -Raw | ConvertFrom-Json } catch { $journal = $null }
    }
    if (-not $journal) {
        $journal = [pscustomobject]@{ RunId = $Context.RunId; Entries = @(); StartedAt = (Get-Date).ToString('o'); Completed = $false }
    }
    $entry = [pscustomobject]@{
        RunId            = $Context.RunId
        Kind             = $Kind
        Target           = $Target
        CreatedByThisRun = $CreatedByThisRun
        OccurredAt       = (Get-Date).ToString('o')
        IsReversible     = $IsReversible
    }
    $journal.Entries = @($journal.Entries) + $entry
    $temporary = $Context.JournalPath + '.tmp'
    Write-Utf8NoBom -Path $temporary -Content ($journal | ConvertTo-Json -Depth 8)
    Move-Item -LiteralPath $temporary -Destination $Context.JournalPath -Force
    $Context.Changes = @($Context.Changes) + $entry
}

function Read-ChangeJournal {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not $Path -or -not (Test-Path -LiteralPath $Path)) { return $null }
    try { return (Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json) }
    catch { return $null }
}

function Invoke-Stop {
    param(
        [Parameter(Mandatory = $true)]$Context,
        [switch]$KeepBackend
    )
    $actions = @()
    $journalPath = Get-LatestJournalPath -Context $Context
    $journal = if ($journalPath) { Read-ChangeJournal -Path $journalPath } else { $null }
    $backendPid = 0
    if ($journal) {
        $entry = @($journal.Entries) | Where-Object { $_.Kind -eq 'BackendProcessStarted' -and $_.CreatedByThisRun } | Select-Object -Last 1
        if ($entry) { $backendPid = [int]$entry.Target }
    }
    if (-not $KeepBackend) {
        $stopResult = Stop-BackendProcess -Context $Context -ProcessId $backendPid
        $actions += ('L1 停止后端: ' + $stopResult.Message)
    }
    $docker = Resolve-ExecutablePath -Name 'docker.exe'
    if ($docker) {
        $stopContainer = Invoke-External -FilePath $docker -ArgumentList @('compose', 'stop', 'mysql') -WorkingDirectory $Context.ProjectRoot -TimeoutSeconds 120 -AllowFailure
        $actions += ('L2 停止 MySQL 容器: 退出码 ' + $stopContainer.ExitCode)
        $volumeStillExists = Test-DockerVolumeExists -DockerCommand $docker -VolumeName 'ai-job_mysql_data'
        $actions += ('数据卷保留: ' + $volumeStillExists)
        if (-not $volumeStillExists) {
            return [pscustomobject]@{ Passed = $false; Actions = $actions; Message = '停止过程未发现数据卷，请人工确认' }
        }
    }
    else {
        $actions += 'Docker 不可用，跳过容器停止'
    }
    return [pscustomobject]@{ Passed = $true; Actions = $actions; Message = '已停止后端与 MySQL，数据卷保留' }
}

function Invoke-Rollback {
    param(
        [Parameter(Mandatory = $true)]$Context,
        [string]$ApproveRemoveCondaEnv,
        [string]$ApproveRemoveEnvFile
    )
    $actions = @()
    $stop = Invoke-Stop -Context $Context
    $actions += $stop.Actions
    if (-not $stop.Passed) {
        return [pscustomobject]@{ Passed = $false; ExitCodeName = 'RollbackFailed'; Actions = $actions; Message = '回滚在停止阶段失败，已停止后续动作' }
    }

    $journalPath = Get-LatestJournalPath -Context $Context
    $journal = if ($journalPath) { Read-ChangeJournal -Path $journalPath } else { $null }
    if (-not $journal) {
        return [pscustomobject]@{ Passed = $true; ExitCodeName = 'Success'; Actions = ($actions + '未找到变更日志，跳过 L3/L4'); Message = '无变更日志，仅执行 L1/L2' }
    }

    $condaEntry = @($journal.Entries) | Where-Object { $_.Kind -eq 'CondaEnvCreated' -and $_.CreatedByThisRun } | Select-Object -Last 1
    if ($condaEntry) {
        if ($ApproveRemoveCondaEnv -eq 'RemoveCondaEnv') {
            $conda = Resolve-CondaCommand
            $remove = Invoke-External -FilePath $conda -ArgumentList @('--no-plugins', 'env', 'remove', '-n', 'ai-job', '-y') -TimeoutSeconds 300 -AllowFailure
            $actions += ('L3 删除本次创建的 Conda 环境: 退出码 ' + $remove.ExitCode)
        }
        else {
            $actions += 'L3 跳过删除 Conda 环境（未提供 -Approve RemoveCondaEnv）'
        }
    }

    $envEntry = @($journal.Entries) | Where-Object { $_.Kind -eq 'EnvFileCreated' -and $_.CreatedByThisRun } | Select-Object -Last 1
    if ($envEntry) {
        if ($ApproveRemoveEnvFile -eq 'RemoveEnvFile') {
            $envPath = Join-Path $Context.ProjectRoot '.env'
            if (Test-Path -LiteralPath $envPath) { Remove-Item -LiteralPath $envPath -Force }
            $actions += 'L4 删除本次创建的 .env'
        }
        else {
            $actions += 'L4 跳过删除 .env（未提供 -Approve RemoveEnvFile）'
        }
    }

    return [pscustomobject]@{ Passed = $true; ExitCodeName = 'Success'; Actions = $actions; Message = '回滚完成（默认保留数据卷、环境与 .env）' }
}

function Invoke-Purge {
    param(
        [Parameter(Mandatory = $true)]$Context,
        [string]$ConfirmDataLoss
    )
    if ($ConfirmDataLoss -ne 'AI-JOB') {
        return [pscustomobject]@{ Passed = $false; ExitCodeName = 'ConfirmationRequired'; Message = 'purge 需要 -ConfirmDataLoss AI-JOB，数据不可恢复' }
    }
    $docker = Resolve-ExecutablePath -Name 'docker.exe'
    if (-not $docker) {
        return [pscustomobject]@{ Passed = $false; ExitCodeName = 'DockerUnavailable'; Message = 'docker 不可用' }
    }
    $result = Invoke-External -FilePath $docker -ArgumentList @('compose', 'down', '-v') -WorkingDirectory $Context.ProjectRoot -TimeoutSeconds 300 -AllowFailure
    return [pscustomobject]@{
        Passed       = ($result.ExitCode -eq 0)
        ExitCodeName = if ($result.ExitCode -eq 0) { 'Success' } else { 'ContainerStartFailed' }
        Message      = '已删除容器与数据卷；数据不可恢复，仅能依赖外部备份'
    }
}

Export-ModuleMember -Function @(
    'Add-ChangeEntry',
    'Read-ChangeJournal',
    'Invoke-Stop',
    'Invoke-Rollback',
    'Invoke-Purge'
)