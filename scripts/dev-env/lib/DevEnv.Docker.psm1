Set-StrictMode -Version 2.0

function ConvertFrom-ComposePsJson {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return @() }
    try {
        $parsed = $Text | ConvertFrom-Json
        return @($parsed)
    }
    catch {
        $items = @()
        foreach ($line in ($Text -split "`n")) {
            if ([string]::IsNullOrWhiteSpace($line)) { continue }
            try { $items += ($line | ConvertFrom-Json) } catch { }
        }
        return $items
    }
}

function Get-DockerContainerState {
    param(
        [Parameter(Mandatory = $true)][string]$DockerCommand,
        [Parameter(Mandatory = $true)][string]$ProjectRoot
    )
    $result = Invoke-External -FilePath $DockerCommand -ArgumentList @('compose', 'ps', '--format', 'json') -WorkingDirectory $ProjectRoot -TimeoutSeconds 60 -AllowFailure
    $containers = @(ConvertFrom-ComposePsJson -Text $result.StdOut)
    $mysql = $containers | Where-Object { $_.Service -eq 'mysql' -or $_.Name -match 'mysql' } | Select-Object -First 1
    if (-not $mysql) {
        return [pscustomobject]@{ Exists = $false; State = 'missing'; Health = 'unknown'; Ports = ''; Name = '' }
    }
    return [pscustomobject]@{
        Exists = $true
        Name   = [string]$mysql.Name
        State  = if ($mysql.State) { [string]$mysql.State } elseif ($mysql.Status) { [string]$mysql.Status } else { 'unknown' }
        Health = if ($mysql.Health) { [string]$mysql.Health } else { 'unknown' }
        Ports  = if ($mysql.Publishers) { (@($mysql.Publishers | ForEach-Object { [string]$_.URL + ':' + [string]$_.PublishedPort + '->' + [string]$_.TargetPort }) -join ',') } else { '' }
    }
}

function Test-DockerVolumeExists {
    param(
        [Parameter(Mandatory = $true)][string]$DockerCommand,
        [Parameter(Mandatory = $true)][string]$VolumeName
    )
    $result = Invoke-External -FilePath $DockerCommand -ArgumentList @('volume', 'ls', '--format', '{{.Name}}') -TimeoutSeconds 60 -AllowFailure
    if ($result.ExitCode -ne 0) { return $false }
    return (@($result.StdOut -split "`n") -contains $VolumeName)
}

function Test-DatabaseConnectivity {
    param(
        [Parameter(Mandatory = $true)][string]$DockerCommand,
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][string]$UserName,
        [Parameter(Mandatory = $true)][string]$Password,
        [Parameter(Mandatory = $true)][string]$DatabaseName
    )
    Add-DevEnvSecret -Secret @($Password)
    $result = Invoke-External -FilePath $DockerCommand -ArgumentList @(
        'compose', 'exec', '-T', '-e', ('MYSQL_PWD=' + $Password), 'mysql',
        'mysql', ('-u' + $UserName), ('-D' + $DatabaseName), '-N', '-B', '-e', 'SELECT 1'
    ) -WorkingDirectory $ProjectRoot -TimeoutSeconds 60 -AllowFailure
    return [pscustomobject]@{
        Success    = ($result.ExitCode -eq 0 -and $result.StdOut.Trim() -match '^1$')
        ExitCode   = $result.ExitCode
        Output     = $result.StdOut.Trim()
        Error      = $result.StdErr.Trim()
    }
}

function Get-DockerFailureClass {
    param([string]$LogText)
    if ($LogText -match 'Access denied') { return '认证失败：库内账号密码与 .env 不一致' }
    if ($LogText -match 'port is already allocated|address already in use') { return '端口冲突：宿主端口被占用' }
    if ($LogText -match 'no space left|disk') { return '资源不足：磁盘空间不足' }
    if ($LogText -match 'pull access denied|failed to resolve|no such host') { return '镜像拉取失败：网络或镜像名问题' }
    if ($LogText -match 'Initializing database|starting as process') { return '初始化较慢：首次初始化未完成' }
    return '配置错误：请查看容器日志'
}

function Invoke-DockerStage {
    param(
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)]$EnvConfig,
        [string]$LogLevel = 'normal'
    )

    $docker = Resolve-ExecutablePath -Name 'docker.exe'
    $criteria = @()
    $messages = @()
    $remediation = @()

    if (-not $docker) {
        return [pscustomobject]@{
            StageResult = (New-StageResult -Stage 'DbStart' -Status 'Failed' -ExitCodeName 'DockerUnavailable' -Criteria @('docker 命令不可用') -Remediation @('安装并启动 Docker Desktop'))
            DbState = $null
        }
    }

    $composeProjectName = 'ai-job'
    $volumeName = $composeProjectName + '_' + $EnvConfig.Db.VolumeName
    $volumeExists = Test-DockerVolumeExists -DockerCommand $docker -VolumeName $volumeName
    $criteria += ('命名卷 ' + $volumeName + ': ' + $(if ($volumeExists) { '已存在' } else { '不存在' }))

    $existing = Get-DockerContainerState -DockerCommand $docker -ProjectRoot $Context.ProjectRoot
    if ($existing.Exists -and $existing.State -eq 'running') {
        $criteria += '复用已运行的 mysql 容器'
    }
    else {
        if ($Context.DryRun) {
            $criteria += 'DryRun: 将执行 docker compose up -d mysql'
        }
        else {
            $up = Invoke-External -FilePath $docker -ArgumentList @('compose', 'up', '-d', 'mysql') -WorkingDirectory $Context.ProjectRoot -TimeoutSeconds 900 -AllowFailure -LogLevel $LogLevel
            if ($up.ExitCode -ne 0) {
                $codeName = if ($up.StdErr -match 'pull|resolve|no such host|connection') { 'ImagePullFailed' } else { 'ContainerStartFailed' }
                return [pscustomobject]@{
                    StageResult = (New-StageResult -Stage 'DbStart' -Status 'Failed' -ExitCodeName $codeName -Criteria $criteria -Messages @(($up.StdErr + ' ' + $up.StdOut).Trim()) -Remediation @('检查 Docker Desktop 状态、网络与镜像源；容器启动失败不会删除数据卷'))
                    DbState = $null
                }
            }
            $criteria += 'docker compose up -d mysql 已执行'
        }
    }

    $health = 'unknown'
    $state = 'unknown'
    $portMapping = ''
    if (-not $Context.DryRun) {
        $deadline = (Get-Date).AddSeconds($Context.DbTimeoutSeconds)
        while ((Get-Date) -lt $deadline) {
            $current = Get-DockerContainerState -DockerCommand $docker -ProjectRoot $Context.ProjectRoot
            $state = $current.State
            $health = $current.Health
            $portMapping = $current.Ports
            if ($state -eq 'running' -and $health -eq 'healthy') { break }
            Start-Sleep -Seconds 5
        }
        $criteria += ('容器状态: ' + $state + ', 健康: ' + $health)
        if ($state -ne 'running' -or $health -ne 'healthy') {
            $logs = Invoke-External -FilePath $docker -ArgumentList @('compose', 'logs', '--tail=200', 'mysql') -WorkingDirectory $Context.ProjectRoot -TimeoutSeconds 120 -AllowFailure
            $classification = Get-DockerFailureClass -LogText ($logs.StdOut + $logs.StdErr)
            $null = Invoke-External -FilePath $docker -ArgumentList @('compose', 'stop', 'mysql') -WorkingDirectory $Context.ProjectRoot -TimeoutSeconds 120 -AllowFailure
            return [pscustomobject]@{
                StageResult = (New-StageResult -Stage 'DbStart' -Status 'Failed' -ExitCodeName 'DbHealthTimeout' -Criteria $criteria -Messages @($classification) -Remediation @('已自动停止容器并保留数据卷；修复后重试，禁止使用 docker compose down -v'))
                DbState = [pscustomobject]@{
                    ContainerState = $state
                    Health         = $health
                    PortMapping    = $portMapping
                    VolumeExists   = $volumeExists
                    CredentialsInitialized = $volumeExists
                }
            }
        }

        $password = [string]$EnvConfig.EnvValues['MYSQL_PASSWORD']
        $connectivity = Test-DatabaseConnectivity -DockerCommand $docker -ProjectRoot $Context.ProjectRoot -UserName $EnvConfig.Db.User -Password $password -DatabaseName $EnvConfig.Db.Database
        $criteria += ('SELECT 1 连通性: ' + $(if ($connectivity.Success) { '成功' } else { '失败' }))
        if (-not $connectivity.Success) {
            $codeName = if ($connectivity.Error -match 'Access denied') { 'DbAuthFailed' } else { 'DbHealthTimeout' }
            $null = Invoke-External -FilePath $docker -ArgumentList @('compose', 'stop', 'mysql') -WorkingDirectory $Context.ProjectRoot -TimeoutSeconds 120 -AllowFailure
            return [pscustomobject]@{
                StageResult = (New-StageResult -Stage 'DbStart' -Status 'Failed' -ExitCodeName $codeName -Criteria $criteria -Messages @($connectivity.Error) -Remediation @('核对 .env 中的 MYSQL_USER/MYSQL_PASSWORD 与库内初始化值；已存在卷修改 .env 不会改密'))
                DbState = [pscustomobject]@{
                    ContainerState = $state
                    Health         = $health
                    PortMapping    = $portMapping
                    VolumeExists   = $volumeExists
                    CredentialsInitialized = $volumeExists
                }
            }
        }
    }
    else {
        $health = 'dry-run'
        $state = 'dry-run'
    }

    if ($volumeExists) {
        $messages += '账号密码沿用库内已初始化值，修改 .env 不会改密；如需改密请在数据库内执行并同步 .env'
    }

    $criteria += '声明: 默认流程不会执行 docker compose down -v'
    return [pscustomobject]@{
        StageResult = (New-StageResult -Stage 'DbStart' -Status 'Passed' -ExitCodeName 'Success' -Criteria $criteria -Messages $messages)
        DbState = [pscustomobject]@{
            ContainerState         = $state
            Health                 = $health
            PortMapping            = $portMapping
            VolumeExists           = $volumeExists
            CredentialsInitialized = $volumeExists
        }
    }
}

Export-ModuleMember -Function @(
    'ConvertFrom-ComposePsJson',
    'Get-DockerContainerState',
    'Test-DockerVolumeExists',
    'Test-DatabaseConnectivity',
    'Get-DockerFailureClass',
    'Invoke-DockerStage'
)