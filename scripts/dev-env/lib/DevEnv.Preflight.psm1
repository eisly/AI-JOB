Set-StrictMode -Version 2.0

$script:RequiredFiles = @(
    '.env.example',
    'docker-compose.yml',
    'requirements.txt',
    'project/backend/pom.xml'
)

function Resolve-ExecutablePath {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [string[]]$Candidates = @()
    )
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    foreach ($candidate in $Candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) { return $candidate }
    }
    return $null
}

function Resolve-CondaCommand {
    $candidates = @()
    if ($env:CONDA_EXE) { $candidates += $env:CONDA_EXE }
    if ($env:CONDA_PREFIX) {
        $candidates += (Join-Path $env:CONDA_PREFIX 'Scripts/conda.exe')
        $candidates += (Join-Path $env:CONDA_PREFIX 'condabin/conda.bat')
    }
    if ($env:USERPROFILE) {
        $candidates += (Join-Path $env:USERPROFILE 'anaconda3/Scripts/conda.exe')
        $candidates += (Join-Path $env:USERPROFILE 'miniconda3/Scripts/conda.exe')
    }
    $exe = Resolve-ExecutablePath -Name 'conda.exe' -Candidates $candidates
    if ($exe) { return $exe }
    $batch = Resolve-ExecutablePath -Name 'conda.bat' -Candidates $candidates
    if ($batch) {
        $condaRoot = Split-Path -Parent (Split-Path -Parent $batch)
        $scriptExecutable = Join-Path $condaRoot 'Scripts/conda.exe'
        if (Test-Path -LiteralPath $scriptExecutable) { return $scriptExecutable }
        return $batch
    }
    return $null
}

function ConvertTo-ToolVersion {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Raw,
        [string]$RequiredRange = '',
        [switch]$Compatible
    )
    $major = 0
    $minor = 0
    $patch = 0
    $match = [regex]::Match($Raw, '(\d+)(?:\.(\d+))?(?:\.(\d+))?')
    if ($match.Success) {
        $major = [int]$match.Groups[1].Value
        if ($match.Groups[2].Success) { $minor = [int]$match.Groups[2].Value }
        if ($match.Groups[3].Success) { $patch = [int]$match.Groups[3].Value }
    }
    return [pscustomobject]@{
        Name          = $Name
        Raw           = $Raw
        Major         = $major
        Minor         = $minor
        Patch         = $patch
        RequiredRange = $RequiredRange
        Compatible    = [bool]$Compatible
    }
}

function Get-JavaVersionTool {
    param([string]$Executable)
    $javaPath = $Executable
    if ([string]::IsNullOrWhiteSpace($javaPath)) {
        $javaPath = Resolve-ExecutablePath -Name 'java.exe'
    }
    if ([string]::IsNullOrWhiteSpace($javaPath)) {
        return (ConvertTo-ToolVersion -Name 'java' -Raw '' -RequiredRange '21' -Compatible:$false)
    }
    $result = Invoke-External -FilePath $javaPath -ArgumentList @('-version') -TimeoutSeconds 20 -AllowFailure
    $raw = ($result.StdErr + "`n" + $result.StdOut).Trim()
    $match = [regex]::Match($raw, 'version\s+"(\d+)(?:\.(\d+))?(?:\.\d+)*"')
    $compatible = $false
    $major = 0
    if ($match.Success) {
        $major = [int]$match.Groups[1].Value
        if ($major -eq 1 -and $match.Groups[2].Success) { $major = [int]$match.Groups[2].Value }
        $compatible = ($result.ExitCode -eq 0 -and $major -eq 21)
    }
    $cleanJavaRaw = ($raw -replace "`r?`n", ' ').Trim()
    if ($cleanJavaRaw.Length -gt 400) { $cleanJavaRaw = $cleanJavaRaw.Substring(0, 400) }
    $tool = ConvertTo-ToolVersion -Name 'java' -Raw $cleanJavaRaw -RequiredRange '21' -Compatible:$compatible
    $tool.Major = $major
    $tool | Add-Member -NotePropertyName Path -NotePropertyValue $javaPath
    return $tool
}

function Get-MavenVersionTool {
    param([string]$Executable)
    $mvnPath = $Executable
    if ([string]::IsNullOrWhiteSpace($mvnPath)) {
        $mvnPath = Resolve-ExecutablePath -Name 'mvn.cmd'
        if (-not $mvnPath) { $mvnPath = Resolve-ExecutablePath -Name 'mvn.exe' }
    }
    if ([string]::IsNullOrWhiteSpace($mvnPath)) {
        return [pscustomobject]@{
            Name = 'maven'; Raw = ''; Major = 0; Minor = 0; Patch = 0
            RequiredRange = '>=3.9'; Compatible = $false; JavaMajor = 0; JavaRaw = ''
        }
    }
    $result = Invoke-External -FilePath $mvnPath -ArgumentList @('-version') -TimeoutSeconds 30 -AllowFailure
    $raw = $result.StdOut
    $versionMatch = [regex]::Match($raw, 'Apache Maven\s+(\d+)\.(\d+)\.(\d+)')
    $javaMatch = [regex]::Match($raw, 'Java version:\s*(\d+)(?:\.(\d+))?(?:\.(\d+))?')
    $major = if ($versionMatch.Success) { [int]$versionMatch.Groups[1].Value } else { 0 }
    $minor = if ($versionMatch.Success) { [int]$versionMatch.Groups[2].Value } else { 0 }
    $patch = if ($versionMatch.Success) { [int]$versionMatch.Groups[3].Value } else { 0 }
    $javaMajor = if ($javaMatch.Success) { [int]$javaMatch.Groups[1].Value } else { 0 }
    if ($javaMajor -eq 1 -and $javaMatch.Success -and $javaMatch.Groups[2].Success) {
        $javaMajor = [int]$javaMatch.Groups[2].Value
    }
    $compatible = ($result.ExitCode -eq 0 -and $major -eq 3 -and $minor -ge 9)
    $cleanRaw = ($raw -replace "`r?`n", ' ').Trim()
    if ($cleanRaw.Length -gt 400) { $cleanRaw = $cleanRaw.Substring(0, 400) }
    return [pscustomobject]@{
        Name         = 'maven'
        Raw          = $cleanRaw
        Major        = $major
        Minor        = $minor
        Patch        = $patch
        RequiredRange= '>=3.9'
        Compatible   = $compatible
        JavaMajor    = $javaMajor
        JavaRaw      = if ($javaMatch.Success) { $javaMatch.Groups[1].Value + $(if ($javaMatch.Groups[2].Success) { '.' + $javaMatch.Groups[2].Value } else { '' }) } else { '' }
        Path         = $mvnPath
    }
}

function Get-CondaVersionTool {
    param([string]$Executable)
    if ([string]::IsNullOrWhiteSpace($Executable)) {
        return (ConvertTo-ToolVersion -Name 'conda' -Raw '' -RequiredRange 'any' -Compatible:$false)
    }
    $result = Invoke-External -FilePath $Executable -ArgumentList @('--version') -TimeoutSeconds 45 -AllowFailure
    $raw = ($result.StdOut + $result.StdErr).Trim()
    $tool = ConvertTo-ToolVersion -Name 'conda' -Raw $raw -RequiredRange 'any' -Compatible:($result.ExitCode -eq 0)
    $tool | Add-Member -NotePropertyName Path -NotePropertyValue $Executable
    return $tool
}

function Get-DockerState {
    param([int]$TimeoutSeconds = 30)
    $docker = Resolve-ExecutablePath -Name 'docker.exe'
    if (-not $docker) {
        return [pscustomobject]@{
            ClientPresent = $false
            ClientVersion = ''
            ComposeVersion = ''
            ServerVersion = ''
            Reachable = $false
            Message = 'docker 命令不在 PATH，请安装/启动 Docker Desktop'
        }
    }
    $client = Invoke-External -FilePath $docker -ArgumentList @('--version') -TimeoutSeconds 20 -AllowFailure
    $compose = Invoke-External -FilePath $docker -ArgumentList @('compose', 'version') -TimeoutSeconds 20 -AllowFailure
    $server = Invoke-External -FilePath $docker -ArgumentList @('info', '--format', '{{.ServerVersion}}') -TimeoutSeconds $TimeoutSeconds -AllowFailure
    $reachable = ($server.ExitCode -eq 0 -and -not $server.TimedOut)
    return [pscustomobject]@{
        ClientPresent  = $true
        ClientVersion  = $client.StdOut.Trim()
        ComposeVersion = $compose.StdOut.Trim()
        ServerVersion  = $server.StdOut.Trim()
        Reachable      = $reachable
        Message        = if ($reachable) { 'Docker 守护进程可用' } else { 'Docker 守护进程不可达，请先启动 Docker Desktop' }
    }
}

function Get-PortState {
    param([Parameter(Mandatory = $true)][int]$Port)
    $entries = @()
    $connections = @()
    try {
        $connections = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop)
    }
    catch {
        $netstat = Invoke-External -FilePath 'netstat.exe' -ArgumentList @('-ano') -TimeoutSeconds 20 -AllowFailure
        foreach ($line in ($netstat.StdOut -split "`n")) {
            if ($line -match ('[:.]' + $Port + '\s') -and $line -match 'LISTENING') {
                $parts = @($line.Trim() -split '\s+')
                $entries += [pscustomobject]@{ Port = $Port; Pid = [int]$parts[-1]; ProcessName = '' }
            }
        }
    }
    foreach ($connection in $connections) {
        $processName = ''
        try { $processName = (Get-Process -Id $connection.OwningProcess -ErrorAction SilentlyContinue).ProcessName } catch { $processName = '' }
        $entries += [pscustomobject]@{
            Port = $Port
            Pid = [int]$connection.OwningProcess
            ProcessName = $processName
        }
    }
    return [pscustomobject]@{
        Port    = $Port
        InUse   = ($entries.Count -gt 0)
        Owners  = $entries
    }
}

function Get-RequiredFileState {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)
    $missing = @()
    foreach ($relative in $script:RequiredFiles) {
        if (-not (Test-Path -LiteralPath (Join-Path $ProjectRoot $relative))) { $missing += $relative }
    }
    return [pscustomobject]@{
        Missing = $missing
        Passed  = ($missing.Count -eq 0)
    }
}

function Invoke-Preflight {
    param(
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)]$EnvConfig,
        [ValidateSet('ReadOnly', 'Strict')][string]$Mode = 'Strict',
        [int]$TimeoutSeconds = 30
    )

    $criteria = @()
    $remediation = @()
    $exitCodeName = 'Success'

    $files = Get-RequiredFileState -ProjectRoot $Context.ProjectRoot
    $criteria += ('必需文件: ' + $(if ($files.Passed) { '齐全' } else { '缺失 ' + ($files.Missing -join ',') }))
    if (-not $files.Passed) {
        $exitCodeName = 'WrongDirectory'
        $remediation += '请在项目根目录执行（应包含 .env.example、docker-compose.yml、requirements.txt、project/backend/pom.xml）'
    }

    $condaCommand = Resolve-CondaCommand
    $condaTool = Get-CondaVersionTool -Executable $condaCommand
    $criteria += ('Conda: ' + $(if ($condaTool.Compatible) { $condaTool.Raw } else { '不可用' }))
    if (-not $condaTool.Compatible -and $exitCodeName -eq 'Success') {
        $exitCodeName = 'CondaUnavailable'
        $remediation += '安装 Conda 或将 condabin 加入 PATH；本项目要求 conda 可用'
    }

    $javaHome = $env:JAVA_HOME
    $javaExecutable = $null
    if ($javaHome) {
        $candidate = Join-Path $javaHome 'bin/java.exe'
        if (Test-Path -LiteralPath $candidate) { $javaExecutable = $candidate }
    }
    if (-not $javaExecutable) { $javaExecutable = Resolve-ExecutablePath -Name 'java.exe' }
    $javaTool = Get-JavaVersionTool -Executable $javaExecutable
    $criteria += ('JDK: ' + $(if ($javaTool.Compatible) { $javaTool.Raw } else { '需要 21，当前 ' + $javaTool.Major }))
    if (-not $javaTool.Compatible -and $exitCodeName -eq 'Success') {
        $exitCodeName = 'JdkIncompatible'
        $remediation += '安装 JDK 21 并将 JAVA_HOME 指向它（当前 PATH 中的 java 也会被使用）'
    }

    $mavenPath = Resolve-ExecutablePath -Name 'mvn.cmd'
    if (-not $mavenPath) { $mavenPath = Resolve-ExecutablePath -Name 'mvn.exe' }
    $mavenTool = Get-MavenVersionTool -Executable $mavenPath
    $criteria += ('Maven: ' + $(if ($mavenTool.Compatible) { $mavenTool.Major.ToString() + '.' + $mavenTool.Minor.ToString() + '.' + $mavenTool.Patch.ToString() } else { '不可用或版本低于 3.9' }))
    if (-not $mavenTool.Compatible -and $exitCodeName -eq 'Success') {
        $exitCodeName = 'MavenIncompatible'
        $remediation += '安装 Maven 3.9+ 并确认 mvn -version 的 Java version 为 21'
    }
    if ($mavenTool.Compatible -and $mavenTool.JavaMajor -ne $javaTool.Major -and $exitCodeName -eq 'Success') {
        $exitCodeName = 'MavenIncompatible'
        $criteria += ('Maven 使用的 Java(' + $mavenTool.JavaMajor + ') 与 java 命令(' + $javaTool.Major + ') 不一致')
        $remediation += '调整 JAVA_HOME 与 PATH 顺序，确保 mvn -version 的 Java version 也是 21'
    }

    $dockerState = Get-DockerState -TimeoutSeconds $TimeoutSeconds
    $criteria += ('Docker: ' + $dockerState.Message)
    if (-not $dockerState.Reachable -and $exitCodeName -eq 'Success') {
        $exitCodeName = 'DockerUnavailable'
        $remediation += '启动 Docker Desktop 后重试：docker info'
    }

    $ports = @(
        (Get-PortState -Port $EnvConfig.Db.Port),
        (Get-PortState -Port $EnvConfig.Backend.Port)
    )
    foreach ($port in $ports) {
        $criteria += ('端口 ' + $port.Port + ': ' + $(if ($port.InUse) { '被占用 PID=' + (($port.Owners | ForEach-Object { $_.Pid }) -join ',') } else { '空闲' }))
    }

    return [pscustomobject]@{
        ExitCodeName   = $exitCodeName
        Mode           = $Mode
        Tools          = @($condaTool, $javaTool, $mavenTool)
        MissingFiles   = $files.Missing
        DockerState    = $dockerState
        PortStates     = $ports
        Criteria       = $criteria
        Remediation    = $remediation
        Passed         = ($exitCodeName -eq 'Success')
    }
}

function Invoke-PreflightSelfTest {
    $assertions = @()
    function Add-Assertion {
        param([string]$Name, [bool]$Passed, [string]$Detail)
        $script:SelfTestAssertions += [pscustomobject]@{ Name = $Name; Passed = $Passed; Detail = $Detail }
    }
    $script:SelfTestAssertions = @()

    $java = ConvertTo-ToolVersion -Name 'java' -Raw 'java version "17.0.13" 2024-10-15 LTS' -RequiredRange '21'
    Add-Assertion -Name 'java-17-major' -Passed ($java.Major -eq 17) -Detail ('major=' + $java.Major)

    $java21 = ConvertTo-ToolVersion -Name 'java' -Raw 'openjdk version "21.0.4" 2024-07-16' -RequiredRange '21'
    Add-Assertion -Name 'java-21-minor' -Passed ($java21.Major -eq 21 -and $java21.Minor -eq 0 -and $java21.Patch -eq 4) -Detail ('21.0.4 -> ' + $java21.Major + '.' + $java21.Minor + '.' + $java21.Patch)

    $mavenRaw = "Apache Maven 3.9.9 (8e8579a9e76f7d015ee5ec7bfcdc97d260186937)`nJava version: 21.0.4, vendor: Oracle Corporation"
    $mavenVersion = [regex]::Match($mavenRaw, 'Apache Maven\s+(\d+)\.(\d+)\.(\d+)')
    $mavenJava = [regex]::Match($mavenRaw, 'Java version:\s*(\d+)')
    Add-Assertion -Name 'maven-version-parse' -Passed ($mavenVersion.Success -and $mavenVersion.Groups[1].Value -eq '3' -and $mavenVersion.Groups[2].Value -eq '9') -Detail 'Apache Maven 3.9.9'
    Add-Assertion -Name 'maven-java-parse' -Passed ($mavenJava.Success -and $mavenJava.Groups[1].Value -eq '21') -Detail 'Java version: 21'

    $conda = ConvertTo-ToolVersion -Name 'conda' -Raw 'conda 24.11.0' -RequiredRange 'any' -Compatible
    Add-Assertion -Name 'conda-version-parse' -Passed ($conda.Major -eq 24 -and $conda.Minor -eq 11) -Detail 'conda 24.11.0'

    return $script:SelfTestAssertions
}

Export-ModuleMember -Function @(
    'Resolve-ExecutablePath',
    'Resolve-CondaCommand',
    'ConvertTo-ToolVersion',
    'Get-JavaVersionTool',
    'Get-MavenVersionTool',
    'Get-CondaVersionTool',
    'Get-DockerState',
    'Get-PortState',
    'Get-RequiredFileState',
    'Invoke-Preflight',
    'Invoke-PreflightSelfTest'
)