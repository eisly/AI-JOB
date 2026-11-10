Set-StrictMode -Version 2.0

function Get-CondaEnvironmentState {
    param(
        [Parameter(Mandatory = $true)][string]$CondaCommand,
        [Parameter(Mandatory = $true)][string]$EnvName
    )
    $list = Invoke-External -FilePath $CondaCommand -ArgumentList @('--no-plugins', 'env', 'list', '--json') -TimeoutSeconds 90 -AllowFailure
    $environments = @()
    if ($list.ExitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace($list.StdOut)) {
        try {
            $parsed = $list.StdOut | ConvertFrom-Json
            $environments = @($parsed.envs)
        }
        catch {
            $environments = @()
        }
    }
    $exists = $false
    foreach ($path in $environments) {
        if ($path -and ((Split-Path -Leaf $path) -eq $EnvName)) { $exists = $true }
    }
    return [pscustomobject]@{ Exists = $exists; Environments = $environments }
}

function Get-PythonVersionInEnv {
    param(
        [Parameter(Mandatory = $true)][string]$CondaCommand,
        [Parameter(Mandatory = $true)][string]$EnvName
    )
    $result = Invoke-External -FilePath $CondaCommand -ArgumentList @('--no-plugins', 'run', '-n', $EnvName, 'python', '-c', "import sys; print('%d.%d.%d' % sys.version_info[:3])") -TimeoutSeconds 90 -AllowFailure
    if ($result.ExitCode -ne 0) { return $null }
    return ($result.StdOut.Trim() -split "`n")[-1].Trim()
}

function Get-UserSiteDisabledState {
    param(
        [Parameter(Mandatory = $true)][string]$CondaCommand,
        [Parameter(Mandatory = $true)][string]$EnvName
    )
    $result = Invoke-External -FilePath $CondaCommand -ArgumentList @('--no-plugins', 'run', '-n', $EnvName, 'python', '-c', "import site; print(site.ENABLE_USER_SITE)") -TimeoutSeconds 90 -AllowFailure
    if ($result.ExitCode -ne 0) { return $false }
    return (($result.StdOut.Trim() -split "`n")[-1].Trim() -eq 'False')
}

function Invoke-PythonStage {
    param(
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)]$EnvConfig,
        [Parameter(Mandatory = $true)][string]$CondaCommand,
        [switch]$RecreatePython,
        [int]$TimeoutSeconds = 900
    )

    $envName = $EnvConfig.Python.EnvName
    $criteria = @()
    $messages = @()
    $remediation = @()
    $created = $false

    $state = Get-CondaEnvironmentState -CondaCommand $CondaCommand -EnvName $envName
    if ($state.Exists -and $RecreatePython) {
        $messages += '按 -RecreatePython 重建环境'
        $remove = Invoke-External -FilePath $CondaCommand -ArgumentList @('--no-plugins', 'env', 'remove', '-n', $envName, '-y') -TimeoutSeconds 300 -AllowFailure
        if ($remove.ExitCode -ne 0) {
            return [pscustomobject]@{
                StageResult = (New-StageResult -Stage 'PythonEnv' -Status 'Failed' -ExitCodeName 'DependencyInstallFailed' -Criteria @('Conda 环境重建失败') -Messages $messages -Remediation @('手工执行 conda env remove -n ai-job -y 后重试'))
                PythonState = $null
            }
        }
        $state = [pscustomobject]@{ Exists = $false; Environments = $state.Environments }
    }

    if (-not $state.Exists) {
        if ($Context.DryRun) {
            $criteria += 'DryRun: 将创建 Conda 环境 ai-job'
        }
        else {
            $create = Invoke-External -FilePath $CondaCommand -ArgumentList @('--no-plugins', 'create', '-n', $envName, 'python=3.11', '-y') -TimeoutSeconds $TimeoutSeconds -AllowFailure
            if ($create.ExitCode -ne 0) {
                $messages += '创建 Conda 环境失败'
                return [pscustomobject]@{
                    StageResult = (New-StageResult -Stage 'PythonEnv' -Status 'Failed' -ExitCodeName 'DependencyInstallFailed' -Criteria $criteria -Messages $messages -Remediation @('检查网络与 Conda 源，或手工执行 conda create -n ai-job python=3.11 -y'))
                    PythonState = $null
                }
            }
            $created = $true
            $criteria += '已创建 Conda 环境 ai-job'
        }
    }
    else {
        $criteria += '复用存量 Conda 环境 ai-job'
    }

    $pythonVersion = $null
    if (-not $Context.DryRun) {
        $pythonVersion = Get-PythonVersionInEnv -CondaCommand $CondaCommand -EnvName $envName
        if (-not $pythonVersion) {
            return [pscustomobject]@{
                StageResult = (New-StageResult -Stage 'PythonEnv' -Status 'Failed' -ExitCodeName 'DependencyInstallFailed' -Criteria $criteria -Messages @('无法读取 ai-job 的 Python 版本') -Remediation @('执行 conda run -n ai-job python -V 排查环境损坏'))
                PythonState = $null
            }
        }
        $criteria += ('Python 版本: ' + $pythonVersion)
        if (-not $pythonVersion.StartsWith('3.11')) {
            return [pscustomobject]@{
                StageResult = (New-StageResult -Stage 'PythonEnv' -Status 'Failed' -ExitCodeName 'PythonVersionMismatch' -Criteria $criteria -Messages @('环境已存在但主次版本不是 3.11') -Remediation @('人工确认迁移或重建；脚本不会自动删除已有环境'))
                PythonState = $null
            }
        }

        $setVars = Invoke-External -FilePath $CondaCommand -ArgumentList @('--no-plugins', 'env', 'config', 'vars', 'set', '-n', $envName, 'PYTHONNOUSERSITE=1') -TimeoutSeconds 120 -AllowFailure
        if ($setVars.ExitCode -ne 0) {
            $criteria += 'PYTHONNOUSERSITE 设置命令返回非零，将按实际生效状态判定'
        }
        $noUserSite = Get-UserSiteDisabledState -CondaCommand $CondaCommand -EnvName $envName
        $criteria += ('PYTHONNOUSERSITE 生效: ' + $noUserSite)
        if (-not $noUserSite) {
            return [pscustomobject]@{
                StageResult = (New-StageResult -Stage 'PythonEnv' -Status 'Failed' -ExitCodeName 'PythonVersionMismatch' -Criteria $criteria -Messages @('site.ENABLE_USER_SITE 不是 False') -Remediation @('conda deactivate 后重新 conda activate ai-job，再重新运行'))
                PythonState = $null
            }
        }

        $requirementsPath = Join-Path $Context.ProjectRoot 'requirements.txt'
        $install = Invoke-External -FilePath $CondaCommand -ArgumentList @('--no-plugins', 'run', '-n', $envName, '--no-capture-output', 'python', '-m', 'pip', 'install', '-r', $requirementsPath) -WorkingDirectory $Context.ProjectRoot -TimeoutSeconds $TimeoutSeconds -AllowFailure
        if ($install.ExitCode -ne 0) {
            $messages += '依赖安装失败（保留已安装部分）'
            return [pscustomobject]@{
                StageResult = (New-StageResult -Stage 'PythonEnv' -Status 'Failed' -ExitCodeName 'DependencyInstallFailed' -Criteria $criteria -Messages $messages -Remediation @('查看日志中的失败包名；修复网络或索引配置后重试，脚本不会破坏性清理环境'))
                PythonState = $null
            }
        }
        $criteria += 'pip install 执行完成'

        $check = Invoke-External -FilePath $CondaCommand -ArgumentList @('--no-plugins', 'run', '-n', $envName, 'python', '-m', 'pip', 'check') -TimeoutSeconds 300 -AllowFailure
        $checkOk = ($check.ExitCode -eq 0 -and $check.StdOut -match 'No broken requirements found')
        $criteria += ('pip check: ' + $(if ($checkOk) { '无冲突' } else { '存在冲突或未通过' }))
        if (-not $checkOk) {
            return [pscustomobject]@{
                StageResult = (New-StageResult -Stage 'PythonEnv' -Status 'Failed' -ExitCodeName 'DependencyConflict' -Criteria $criteria -Messages @($check.StdOut.Trim()) -Remediation @('按 pip check 输出的冲突链路调整依赖版本后重试'))
                PythonState = $null
            }
        }

        $list = Invoke-External -FilePath $CondaCommand -ArgumentList @('--no-plugins', 'run', '-n', $envName, 'python', '-m', 'pip', 'list', '--format=json') -TimeoutSeconds 300 -AllowFailure
        $installed = @()
        if ($list.ExitCode -eq 0) {
            try { $installed = @($list.StdOut | ConvertFrom-Json) } catch { $installed = @() }
        }
        $dependencies = @()
        foreach ($dependency in $EnvConfig.Python.DirectDependencies) {
            $normalizedName = $dependency.NormalizedName
            $actual = $installed | Where-Object { (($_.name.ToLowerInvariant()) -replace '[-_]', '-') -eq $normalizedName } | Select-Object -First 1
            $actualVersion = if ($actual) { [string]$actual.version } else { '' }
            $dependencies += [pscustomobject]@{
                Name            = $dependency.Name
                ExpectedVersion = $dependency.ExpectedVersion
                ActualVersion   = $actualVersion
                Matched         = ($actualVersion -eq $dependency.ExpectedVersion)
            }
        }
        $mismatched = @($dependencies | Where-Object { -not $_.Matched })
        $criteria += ('直接依赖匹配: ' + ($dependencies.Count - $mismatched.Count).ToString() + '/' + $dependencies.Count.ToString())
        if ($mismatched.Count -gt 0) {
            $messages += ('版本不一致: ' + (($mismatched | ForEach-Object { $_.Name + ' 期望 ' + $_.ExpectedVersion + ' 实际 ' + $_.ActualVersion }) -join '; '))
            return [pscustomobject]@{
                StageResult = (New-StageResult -Stage 'PythonEnv' -Status 'Failed' -ExitCodeName 'DependencyInstallFailed' -Criteria $criteria -Messages $messages -Remediation @('确认 requirements.txt 与安装结果为何不一致，必要时重新安装该包'))
                PythonState = $null
            }
        }
    }
    else {
        $pythonVersion = '3.11 (DryRun 未验证)'
        $noUserSite = $true
        $dependencies = @($EnvConfig.Python.DirectDependencies | ForEach-Object {
            [pscustomobject]@{ Name = $_.Name; ExpectedVersion = $_.ExpectedVersion; ActualVersion = ''; Matched = $false }
        })
    }

    $criteria += '声明: 环境就绪仅代表 Python 依赖可用，不代表 AI 服务已实现或可启动'
    $result = New-StageResult -Stage 'PythonEnv' -Status 'Passed' -ExitCodeName 'Success' -Criteria $criteria -Messages $messages
    return [pscustomobject]@{
        StageResult = $result
        PythonState = [pscustomobject]@{
            EnvName      = $envName
            CreatedByThisRun = $created
            PythonVersion = $pythonVersion
            NoUserSite   = [bool]$noUserSite
            Dependencies = $dependencies
            Passed       = $true
            ScopeNote    = '环境就绪不代表 AI 服务已实现或可启动'
        }
    }
}

Export-ModuleMember -Function @(
    'Get-CondaEnvironmentState',
    'Get-PythonVersionInEnv',
    'Get-UserSiteDisabledState',
    'Invoke-PythonStage'
)