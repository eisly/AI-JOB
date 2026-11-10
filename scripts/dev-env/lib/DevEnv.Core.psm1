Set-StrictMode -Version 2.0

$script:DevEnvExitCodes = [ordered]@{
    Success                    = 0
    InvalidArgument            = 2
    PrerequisiteMissing        = 10
    WrongDirectory             = 11
    JdkIncompatible            = 12
    MavenIncompatible          = 13
    DockerUnavailable          = 14
    CondaUnavailable           = 20
    PythonVersionMismatch      = 21
    DependencyInstallFailed    = 22
    DependencyConflict         = 23
    EnvFileMissing             = 30
    EnvFileInvalid             = 31
    EnvFileEncodingInvalid     = 32
    ImagePullFailed            = 40
    ContainerStartFailed       = 41
    PortInUse                  = 42
    DbHealthTimeout            = 43
    DbAuthFailed               = 44
    BackendStartTimeout        = 50
    MigrationFailed            = 51
    BackendDbUnreachable       = 52
    DemoTableMissing           = 60
    DemoDbNotEmpty             = 61
    DemoImportFailed           = 62
    DemoCharsetMismatch        = 63
    TestFailed                 = 70
    TestCompileError           = 71
    TestAssertionFailed        = 72
    TestContextFailed          = 73
    RollbackFailed             = 80
    ConfirmationRequired       = 90
    InternalError              = 99
}

$script:DevEnvSecrets = New-Object 'System.Collections.Generic.List[string]'

function Get-DevEnvExitCode {
    param(
        [Parameter(Mandatory = $true)][string]$Name
    )
    if (-not $script:DevEnvExitCodes.Contains($Name)) {
        throw "Unknown exit code name: $Name"
    }
    return [int]$script:DevEnvExitCodes[$Name]
}

function Get-DevEnvExitCodeNames {
    return @($script:DevEnvExitCodes.Keys)
}

function New-DevEnvException {
    param(
        [Parameter(Mandatory = $true)][string]$Code,
        [Parameter(Mandatory = $true)][string]$Message,
        [string[]]$Remediation = @()
    )
    $exception = New-Object System.Exception($Message)
    $exception.Data['DevEnvExitCode'] = $Code
    $exception.Data['Remediation'] = $Remediation
    return $exception
}

function Resolve-DevEnvExitCode {
    param(
        [Parameter(Mandatory = $true)][System.Management.Automation.ErrorRecord]$ErrorRecord
    )
    $exception = $ErrorRecord.Exception
    if ($exception -and $exception.Data -and $exception.Data.Contains('DevEnvExitCode')) {
        return [string]$exception.Data['DevEnvExitCode']
    }
    return 'InternalError'
}

function Add-DevEnvSecret {
    param([string[]]$Secret)
    foreach ($value in @($Secret)) {
        if ([string]::IsNullOrWhiteSpace($value)) { continue }
        if ($value -eq 'replace_with_a_strong_password') { continue }
        if ($value -eq 'replace_with_a_different_strong_password') { continue }
        if (-not $script:DevEnvSecrets.Contains($value)) {
            $script:DevEnvSecrets.Add($value)
        }
    }
}

function Clear-DevEnvSecrets {
    $script:DevEnvSecrets.Clear()
}

function Protect-Secret {
    param([string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return $Text }
    $result = $Text
    foreach ($secret in $script:DevEnvSecrets) {
        if ([string]::IsNullOrEmpty($secret)) { continue }
        $result = $result.Replace($secret, '<redacted>')
    }
    $result = [regex]::Replace($result, 'MYSQL_PWD=([^\s"'';]+)', 'MYSQL_PWD=<redacted>')
    $result = [regex]::Replace($result, '(--password=)([^\s"'';]+)', '$1<redacted>')
    $result = [regex]::Replace($result, '(-p)\s+([^\s"'';]+)', '$1 <redacted>')
    return $result
}

function Write-DevLog {
    param(
        [Parameter(Mandatory = $true)][string]$Message,
        [ValidateSet('Debug', 'Info', 'Warn', 'Error')][string]$Level = 'Info',
        [string]$LogPath,
        [ValidateSet('quiet', 'normal', 'verbose')][string]$LogLevel = 'normal'
    )
    $safeMessage = Protect-Secret -Text $Message
    $line = '{0} [{1}] {2}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level.ToUpperInvariant(), $safeMessage
    if (-not [string]::IsNullOrWhiteSpace($LogPath)) {
        $directory = Split-Path -Parent $LogPath
        if ($directory -and -not (Test-Path -LiteralPath $directory)) {
            New-Item -ItemType Directory -Path $directory -Force | Out-Null
        }
        Add-Content -LiteralPath $LogPath -Value $line -Encoding UTF8
    }
    $visible = $true
    if ($Level -eq 'Debug' -and $LogLevel -ne 'verbose') { $visible = $false }
    if ($Level -eq 'Info' -and $LogLevel -eq 'quiet') { $visible = $false }
    if (-not $visible) { return }
    switch ($Level) {
        'Error' { Write-Host $line -ForegroundColor Red }
        'Warn' { Write-Host $line -ForegroundColor Yellow }
        default { Write-Host $line }
    }
}

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Content
    )
    $directory = Split-Path -Parent $Path
    if ($directory -and -not (Test-Path -LiteralPath $directory)) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }
    $normalized = $Content -replace "`r`n", "`n"
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $normalized, $encoding)
}

function Test-FileHasBom {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $false }
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    return ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)
}

function Invoke-External {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$ArgumentList = @(),
        [string]$WorkingDirectory,
        [int]$TimeoutSeconds = 120,
        [switch]$AllowFailure,
        [string]$LogPath,
        [ValidateSet('quiet', 'normal', 'verbose')][string]$LogLevel = 'normal'
    )

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $timedOut = $false
    $exitCode = -1
    $stdout = ''
    $stderr = ''
    $quotedArguments = @()
    foreach ($argument in $ArgumentList) {
        if ($argument -match '[\s"]') {
            $quotedArguments += '"' + ($argument -replace '"', '\"') + '"'
        }
        else {
            $quotedArguments += $argument
        }
    }
    $argumentLine = $quotedArguments -join ' '

    $actualFile = $FilePath
    $actualArguments = $argumentLine
    $extension = [System.IO.Path]::GetExtension($FilePath).ToLowerInvariant()
    if ($extension -eq '.cmd' -or $extension -eq '.bat') {
        $actualFile = 'cmd.exe'
        if ([string]::IsNullOrWhiteSpace($argumentLine)) {
            $actualArguments = '/d /c ""' + $FilePath + '"'
        }
        else {
            $actualArguments = '/d /c ""' + $FilePath + '" ' + $argumentLine + '"'
        }
    }

    $process = $null
    try {
        $startInfo = New-Object System.Diagnostics.ProcessStartInfo
        $startInfo.FileName = $actualFile
        $startInfo.Arguments = $actualArguments
        $startInfo.UseShellExecute = $false
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $startInfo.CreateNoWindow = $true
        $startInfo.StandardOutputEncoding = New-Object System.Text.UTF8Encoding($false)
        $startInfo.StandardErrorEncoding = New-Object System.Text.UTF8Encoding($false)
        if (-not [string]::IsNullOrWhiteSpace($WorkingDirectory)) {
            $startInfo.WorkingDirectory = $WorkingDirectory
        }
        $process = New-Object System.Diagnostics.Process
        $process.StartInfo = $startInfo
        if (-not $process.Start()) {
            throw '进程启动失败'
        }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $timedOut = $true
            $null = & taskkill.exe /PID $process.Id /T /F 2>&1
            $null = $process.WaitForExit(5000)
        }
        else {
            $process.WaitForExit()
        }
        if (-not $timedOut) {
            $exitCode = $process.ExitCode
        }
        try { $stdout = $stdoutTask.Result } catch { $stdout = '' }
        try { $stderr = $stderrTask.Result } catch { $stderr = '' }
    }
    catch {
        throw (New-DevEnvException -Code 'PrerequisiteMissing' -Message ('无法执行命令: ' + (Split-Path -Leaf $FilePath) + '; ' + $_.Exception.Message))
    }
    finally {
        if ($stopwatch.IsRunning) { $stopwatch.Stop() }
        if ($process) { $process.Dispose() }
    }

    $result = [pscustomobject]@{
        FilePath    = $FilePath
        ExitCode    = $exitCode
        StdOut      = (Protect-Secret -Text $stdout)
        StdErr      = (Protect-Secret -Text $stderr)
        TimedOut    = $timedOut
        DurationMs  = [int]$stopwatch.ElapsedMilliseconds
    }

    if (-not $AllowFailure -and ($timedOut -or $exitCode -ne 0)) {
        $summary = if ($timedOut) { '命令执行超时' } else { '命令返回非零退出码 ' + $exitCode }
        Write-DevLog -Message ($summary + ': ' + (Split-Path -Leaf $FilePath)) -Level 'Warn' -LogPath $LogPath -LogLevel $LogLevel
    }
    return $result
}

function Read-EnvFile {
    param([Parameter(Mandatory = $true)][string]$Path)
    $result = [pscustomobject]@{
        Exists       = (Test-Path -LiteralPath $Path)
        HasBom       = $false
        Values       = [ordered]@{}
        Duplicates   = @()
        ParseErrors  = @()
        Lines        = @()
    }
    if (-not $result.Exists) { return $result }

    $result.HasBom = Test-FileHasBom -Path $Path
    $text = [System.IO.File]::ReadAllText($Path)
    if ($result.HasBom -and $text.Length -gt 0) {
        $text = $text.Substring(1)
    }
    $result.Lines = @($text -split "`n")

    $seen = @{}
    $lineNumber = 0
    foreach ($rawLine in $result.Lines) {
        $lineNumber++
        $line = $rawLine.Trim()
        if ($line.Length -eq 0 -or $line.StartsWith('#')) { continue }
        $match = [regex]::Match($line, '^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$')
        if (-not $match.Success) {
            $result.ParseErrors += ('第 {0} 行无法解析' -f $lineNumber)
            continue
        }
        $key = $match.Groups[1].Value
        $value = $match.Groups[2].Value.Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        if ($seen.ContainsKey($key)) {
            $result.Duplicates += $key
        }
        else {
            $seen[$key] = $true
        }
        $result.Values[$key] = $value
    }
    return $result
}

function Read-PropertiesFile {
    param([Parameter(Mandatory = $true)][string]$Path)
    $values = [ordered]@{}
    if (-not (Test-Path -LiteralPath $Path)) { return $values }
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
        $index = $trimmed.IndexOf('=')
        if ($index -le 0) { continue }
        $values[$trimmed.Substring(0, $index).Trim()] = $trimmed.Substring($index + 1).Trim()
    }
    return $values
}

function Read-Requirements {
    param([Parameter(Mandatory = $true)][string]$Path)
    $dependencies = @()
    if (-not (Test-Path -LiteralPath $Path)) { return $dependencies }
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
        $match = [regex]::Match($trimmed, '^([A-Za-z0-9_.\-]+(?:\[[^\]]+\])?)\s*(==|>=|<=|~=|>|<)?\s*(.*)$')
        if (-not $match.Success) { continue }
        $name = $match.Groups[1].Value
        $bracket = $name.IndexOf('[')
        if ($bracket -gt 0) { $name = $name.Substring(0, $bracket) }
        $dependencies += [pscustomobject]@{
            Name            = $name
            NormalizedName  = ($name.ToLowerInvariant() -replace '[-_]', '-')
            Operator        = $match.Groups[2].Value
            ExpectedVersion = $match.Groups[3].Value.Trim()
        }
    }
    return $dependencies
}

function Resolve-EnvConfig {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)

    $envPath = Join-Path $ProjectRoot '.env'
    $envData = Read-EnvFile -Path $envPath
    $composePath = Join-Path $ProjectRoot 'docker-compose.yml'
    $composeText = ''
    if (Test-Path -LiteralPath $composePath) {
        $composeText = [System.IO.File]::ReadAllText($composePath)
    }

    $image = 'mysql:8.0.42'
    $imageMatch = [regex]::Match($composeText, 'image:\s*([^\s#]+)')
    if ($imageMatch.Success) { $image = $imageMatch.Groups[1].Value }

    $database = 'career_business'
    $databaseMatch = [regex]::Match($composeText, 'MYSQL_DATABASE:\s*([^\s#]+)')
    if ($databaseMatch.Success) { $database = $databaseMatch.Groups[1].Value }

    $volumeName = 'mysql_data'
    $volumeMatch = [regex]::Match($composeText, '(?m)^volumes:\s*\r?\n\s+([A-Za-z0-9_.\-]+):')
    if ($volumeMatch.Success) { $volumeName = $volumeMatch.Groups[1].Value }

    $javaVersion = 21
    $pomPath = Join-Path $ProjectRoot 'project/backend/pom.xml'
    if (Test-Path -LiteralPath $pomPath) {
        $pomText = [System.IO.File]::ReadAllText($pomPath)
        $javaMatch = [regex]::Match($pomText, '<java\.version>\s*(\d+)\s*</java\.version>')
        if ($javaMatch.Success) { $javaVersion = [int]$javaMatch.Groups[1].Value }
    }

    $backendPort = 8080
    $applicationPath = Join-Path $ProjectRoot 'project/backend/src/main/resources/application.yml'
    if (Test-Path -LiteralPath $applicationPath) {
        $ymlText = [System.IO.File]::ReadAllText($applicationPath)
        $portMatch = [regex]::Match($ymlText, 'SERVER_PORT:(\d+)')
        if ($portMatch.Success) { $backendPort = [int]$portMatch.Groups[1].Value }
    }

    $dbPort = 3307
    if ($envData.Values.Contains('MYSQL_PORT') -and $envData.Values['MYSQL_PORT'] -match '^\d+$') {
        $dbPort = [int]$envData.Values['MYSQL_PORT']
    }

    $bindAddress = '127.0.0.1'
    if ($envData.Values.Contains('MYSQL_BIND_ADDRESS') -and -not [string]::IsNullOrWhiteSpace($envData.Values['MYSQL_BIND_ADDRESS'])) {
        $bindAddress = $envData.Values['MYSQL_BIND_ADDRESS']
    }

    $user = 'aijob'
    if ($envData.Values.Contains('MYSQL_USER') -and -not [string]::IsNullOrWhiteSpace($envData.Values['MYSQL_USER'])) {
        $user = $envData.Values['MYSQL_USER']
    }

    $hasPassword = $envData.Values.Contains('MYSQL_PASSWORD') -and -not [string]::IsNullOrWhiteSpace($envData.Values['MYSQL_PASSWORD'])
    $hasRootPassword = $envData.Values.Contains('MYSQL_ROOT_PASSWORD') -and -not [string]::IsNullOrWhiteSpace($envData.Values['MYSQL_ROOT_PASSWORD'])

    return [pscustomobject]@{
        EnvPath        = $envPath
        EnvExists      = $envData.Exists
        EnvHasBom      = $envData.HasBom
        EnvValues      = $envData.Values
        EnvDuplicates  = $envData.Duplicates
        EnvParseErrors = $envData.ParseErrors
        Db             = [pscustomobject]@{
            BindAddress        = $bindAddress
            Port               = $dbPort
            Database           = $database
            User               = $user
            HasPassword        = $hasPassword
            HasRootPassword    = $hasRootPassword
            IsLoopback         = ($bindAddress -in @('127.0.0.1', 'localhost', '::1'))
            VolumeName         = $volumeName
            Image              = $image
        }
        Backend        = [pscustomobject]@{
            Port        = $backendPort
            JavaVersion = $javaVersion
            Marker      = 'Started BackendApplication'
        }
        Python         = [pscustomobject]@{
            EnvName              = 'ai-job'
            RequiredVersion      = '3.11'
            DirectDependencies   = (Read-Requirements -Path (Join-Path $ProjectRoot 'requirements.txt'))
        }
    }
}

function Initialize-EnvFile {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectRoot
    )
    $envPath = Join-Path $ProjectRoot '.env'
    $examplePath = Join-Path $ProjectRoot '.env.example'
    if (Test-Path -LiteralPath $envPath) {
        return [pscustomobject]@{ Status = 'Exists'; Path = $envPath; Created = $false }
    }
    if (-not (Test-Path -LiteralPath $examplePath)) {
        throw (New-DevEnvException -Code 'WrongDirectory' -Message '.env.example 缺失，无法生成 .env')
    }
    $content = [System.IO.File]::ReadAllText($examplePath)
    Write-Utf8NoBom -Path $envPath -Content $content
    return [pscustomobject]@{ Status = 'PendingConfiguration'; Path = $envPath; Created = $true }
}

function Test-EnvFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$GitRoot,
        [switch]$AllowNonLoopback
    )

    $requiredKeys = @('MYSQL_BIND_ADDRESS', 'MYSQL_PORT', 'MYSQL_USER', 'MYSQL_PASSWORD', 'MYSQL_ROOT_PASSWORD')
    $data = Read-EnvFile -Path $Path
    $rules = @()
    $values = $data.Values
    $password = if ($values.Contains('MYSQL_PASSWORD')) { [string]$values['MYSQL_PASSWORD'] } else { '' }
    $rootPassword = if ($values.Contains('MYSQL_ROOT_PASSWORD')) { [string]$values['MYSQL_ROOT_PASSWORD'] } else { '' }
    $user = if ($values.Contains('MYSQL_USER')) { [string]$values['MYSQL_USER'] } else { '' }
    $bindAddress = if ($values.Contains('MYSQL_BIND_ADDRESS')) { [string]$values['MYSQL_BIND_ADDRESS'] } else { '' }
    $port = if ($values.Contains('MYSQL_PORT')) { [string]$values['MYSQL_PORT'] } else { '' }

    if (-not $data.Exists) {
        $rules += [pscustomobject]@{ RuleId = 'E1'; Passed = $false; Message = '.env 不存在' }
        return [pscustomobject]@{ ExitCodeName = 'EnvFileMissing'; Rules = $rules; Values = $values }
    }

    $missing = @()
    foreach ($key in $requiredKeys) {
        if (-not $values.Contains($key) -or [string]::IsNullOrWhiteSpace([string]$values[$key])) { $missing += $key }
    }
    $rules += [pscustomobject]@{ RuleId = 'E2'; Passed = ($missing.Count -eq 0); Message = if ($missing.Count -eq 0) { '五个必需键齐全' } else { '缺少或为空: ' + ($missing -join ',') } }

    $placeholder = ($password -match 'replace_with') -or ($rootPassword -match 'replace_with')
    $rules += [pscustomobject]@{ RuleId = 'E3'; Passed = (-not $placeholder); Message = if ($placeholder) { '密码仍为模板占位值' } else { '密码非占位值' } }

    $same = (-not [string]::IsNullOrEmpty($password)) -and ($password -eq $rootPassword)
    $rules += [pscustomobject]@{ RuleId = 'E4'; Passed = (-not $same); Message = if ($same) { 'MYSQL_PASSWORD 与 MYSQL_ROOT_PASSWORD 相同' } else { '两个密码互不相同' } }

    $userIsRoot = ($user -ieq 'root')
    $rules += [pscustomobject]@{ RuleId = 'E5'; Passed = (-not $userIsRoot); Message = if ($userIsRoot) { 'MYSQL_USER 不得为 root' } else { '未使用 root 账号' } }

    $portValid = $port -match '^\d+$' -and [int]$port -ge 1 -and [int]$port -le 65535
    $rules += [pscustomobject]@{ RuleId = 'E6'; Passed = $portValid; Message = if ($portValid) { 'MYSQL_PORT 在 1-65535 范围内' } else { 'MYSQL_PORT 非法' } }

    $isLoopback = $bindAddress -in @('127.0.0.1', 'localhost', '::1')
    $bindPassed = $isLoopback -or [bool]$AllowNonLoopback
    $rules += [pscustomobject]@{ RuleId = 'E7'; Passed = $bindPassed; Message = if ($isLoopback) { '绑定回环地址' } elseif ($AllowNonLoopback) { '绑定非回环地址（已显式允许）' } else { '非回环地址需 -AllowNonLoopback 并二次确认' } }

    $bom = $data.HasBom
    $duplicates = @($data.Duplicates | Select-Object -Unique)
    $parseErrors = @($data.ParseErrors)
    $encodingPassed = (-not $bom) -and ($duplicates.Count -eq 0) -and ($parseErrors.Count -eq 0)
    $encodingMessage = '编码与解析正常'
    if ($bom) { $encodingMessage = 'BOM 会污染首个键名，请保存为 UTF-8 无 BOM' }
    elseif ($duplicates.Count -gt 0) { $encodingMessage = '存在重复键: ' + ($duplicates -join ',') }
    elseif ($parseErrors.Count -gt 0) { $encodingMessage = ($parseErrors -join '; ') }
    $rules += [pscustomobject]@{ RuleId = 'E8'; Passed = $encodingPassed; Message = $encodingMessage }

    $gitIgnored = $true
    if (-not [string]::IsNullOrWhiteSpace($GitRoot)) {
        $git = Invoke-External -FilePath 'git.exe' -ArgumentList @('check-ignore', '-q', '.env') -WorkingDirectory $GitRoot -TimeoutSeconds 20 -AllowFailure
        $gitIgnored = ($git.ExitCode -eq 0)
    }
    $rules += [pscustomobject]@{ RuleId = 'E9'; Passed = $gitIgnored; Message = if ($gitIgnored) { '.env 已被 Git 忽略' } else { '.env 未被 Git 忽略，禁止继续' } }

    $charsetPassed = ($password -match '^[A-Za-z0-9\-_@.]{12,}$') -and ($rootPassword -match '^[A-Za-z0-9\-_@.]{12,}$')
    $rules += [pscustomobject]@{ RuleId = 'E10'; Passed = $charsetPassed; Message = if ($charsetPassed) { '密码长度与字符集符合要求' } else { '密码需至少 12 位且仅含 A-Za-z0-9-_@.' } }

    $failed = @($rules | Where-Object { -not $_.Passed })
    $exitCodeName = 'Success'
    if ($failed.Count -gt 0) {
        if (-not $encodingPassed) { $exitCodeName = 'EnvFileEncodingInvalid' }
        else { $exitCodeName = 'EnvFileInvalid' }
    }
    return [pscustomobject]@{ ExitCodeName = $exitCodeName; Rules = $rules; Values = $values }
}

function New-DevEnvContext {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [ValidateSet('quiet', 'normal', 'verbose')][string]$LogLevel = 'normal',
        [string]$ReportPath,
        [switch]$DryRun
    )
    $resolvedRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
    $runId = '{0}-{1}' -f (Get-Date -Format 'yyyyMMddHHmmss'), (Get-Random -Minimum 1000 -Maximum 9999)
    $stateDirectory = Join-Path $resolvedRoot '.local-dev-env'
    $logDirectory = Join-Path $stateDirectory 'logs'
    $logPath = Join-Path $logDirectory ('run-{0}.log' -f $runId)
    $resolvedReportPath = if ([string]::IsNullOrWhiteSpace($ReportPath)) { Join-Path $stateDirectory 'report.json' } else { $ReportPath }
    if (-not $DryRun) {
        if (-not (Test-Path -LiteralPath $stateDirectory)) { New-Item -ItemType Directory -Path $stateDirectory -Force | Out-Null }
        if (-not (Test-Path -LiteralPath $logDirectory)) { New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null }
    }
    return [pscustomobject]@{
        ProjectRoot   = $resolvedRoot
        BackendRoot   = (Join-Path $resolvedRoot 'project/backend')
        RunId         = $runId
        StateDirectory= $stateDirectory
        LogPath       = $logPath
        ReportPath    = $resolvedReportPath
        JournalPath   = (Join-Path $stateDirectory ('journal-{0}.json' -f $runId))
        LogLevel      = $LogLevel
        DryRun        = [bool]$DryRun
        StartedAt     = (Get-Date)
        Stages        = @()
        Changes       = @()
        ExitCodeName  = 'Success'
    }
}

function Enter-DevEnvLock {
    param([Parameter(Mandatory = $true)]$Context)
    if ($Context.DryRun) { return $false }
    $lockPath = Join-Path $Context.StateDirectory 'orchestrator.lock'
    if (Test-Path -LiteralPath $lockPath) {
        $existing = $null
        try { $existing = Get-Content -LiteralPath $lockPath -Raw | ConvertFrom-Json } catch { $existing = $null }
        $isStale = $true
        if ($existing -and $existing.pid) {
            $process = Get-Process -Id ([int]$existing.pid) -ErrorAction SilentlyContinue
            if ($process) {
                $startedAt = [datetime]::MinValue
                [void][datetime]::TryParse([string]$existing.startedAt, [ref]$startedAt)
                $isStale = ((Get-Date) - $startedAt).TotalHours -ge 2
            }
        }
        if (-not $isStale) {
            throw (New-DevEnvException -Code 'InternalError' -Message '已有编排在运行（检测到存活锁），请等待其结束或清理 .local-dev-env/orchestrator.lock')
        }
        Remove-Item -LiteralPath $lockPath -Force -ErrorAction SilentlyContinue
    }
    $payload = [pscustomobject]@{
        pid       = $PID
        startedAt = (Get-Date).ToString('o')
        command   = $Context.Command
    }
    Write-Utf8NoBom -Path $lockPath -Content ($payload | ConvertTo-Json -Depth 5)
    return $true
}

function Exit-DevEnvLock {
    param([Parameter(Mandatory = $true)]$Context)
    if ($Context.DryRun) { return }
    $lockPath = Join-Path $Context.StateDirectory 'orchestrator.lock'
    if (-not (Test-Path -LiteralPath $lockPath)) { return }
    try {
        $existing = Get-Content -LiteralPath $lockPath -Raw | ConvertFrom-Json
        if ($existing -and [int]$existing.pid -eq $PID) {
            Remove-Item -LiteralPath $lockPath -Force -ErrorAction SilentlyContinue
        }
    }
    catch {
        Remove-Item -LiteralPath $lockPath -Force -ErrorAction SilentlyContinue
    }
}

function New-StageResult {
    param(
        [Parameter(Mandatory = $true)][string]$Stage,
        [Parameter(Mandatory = $true)][string]$Status,
        [string]$ExitCodeName = 'Success',
        [string[]]$Criteria = @(),
        [string[]]$Messages = @(),
        [string[]]$Remediation = @(),
        [int]$DurationMs = 0
    )
    return [pscustomobject]@{
        Stage      = $Stage
        Status     = $Status
        ExitCode   = $ExitCodeName
        StartedAt  = (Get-Date).ToString('o')
        DurationMs = $DurationMs
        Criteria   = @($Criteria)
        Messages   = @($Messages | ForEach-Object { Protect-Secret -Text $_ })
        Remediation= @($Remediation | ForEach-Object { Protect-Secret -Text $_ })
    }
}

function Add-StageResult {
    param(
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)]$Result
    )
    $Context.Stages = @($Context.Stages) + $Result
    if ($Result.Status -eq 'Failed' -and $Context.ExitCodeName -eq 'Success') {
        $Context.ExitCodeName = $Result.ExitCode
    }
}

Export-ModuleMember -Function @(
    'Get-DevEnvExitCode',
    'Get-DevEnvExitCodeNames',
    'New-DevEnvException',
    'Resolve-DevEnvExitCode',
    'Add-DevEnvSecret',
    'Clear-DevEnvSecrets',
    'Protect-Secret',
    'Write-DevLog',
    'Write-Utf8NoBom',
    'Test-FileHasBom',
    'Invoke-External',
    'Read-EnvFile',
    'Read-PropertiesFile',
    'Read-Requirements',
    'Resolve-EnvConfig',
    'Initialize-EnvFile',
    'Test-EnvFile',
    'New-DevEnvContext',
    'Enter-DevEnvLock',
    'Exit-DevEnvLock',
    'New-StageResult',
    'Add-StageResult'
)