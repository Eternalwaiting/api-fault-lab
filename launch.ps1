param(
    [switch]$SkipBuild,
    [switch]$NoBrowser
)
$ErrorActionPreference = 'Stop'
if ([IntPtr]::Size -ne 8) { throw '需要使用 64 位 PowerShell。' }
Push-Location $PSScriptRoot
try {
    $jar = Join-Path $PSScriptRoot 'target/api-fault-lab-0.1.0-SNAPSHOT.jar'
    $pidPath = Join-Path $PSScriptRoot 'runtime/server.pid'
    $existingUrl = $null
    if (Test-Path -LiteralPath $pidPath) {
        $rawPid = (Get-Content -LiteralPath $pidPath -Raw).Trim()
        if ($rawPid -match '^\d+$') {
            $owned = Get-CimInstance Win32_Process -Filter "ProcessId=$rawPid" -ErrorAction SilentlyContinue
            if ($owned -and $owned.Name -eq 'java.exe' -and $owned.CommandLine -like "*$jar*") {
                $portMatch = [regex]::Match($owned.CommandLine, '--server\.port=(\d+)')
                if ($portMatch.Success) {
                    $candidate = "http://127.0.0.1:$($portMatch.Groups[1].Value)"
                    try {
                        $null = Invoke-RestMethod "$candidate/api/status" -TimeoutSec 2
                        $existingUrl = $candidate
                    } catch { }
                }
            }
        }
    }
    if (-not $existingUrl) {
        if (-not (Test-Path -LiteralPath $jar) -or -not $SkipBuild) {
            & (Join-Path $PSScriptRoot 'mvnw.ps1') -B package
            if ($LASTEXITCODE -ne 0) { throw '构建失败，未启动应用。' }
        }
        & (Join-Path $PSScriptRoot 'start.ps1') -SkipBuild
        $startedPidText = (Get-Content -LiteralPath $pidPath -Raw -ErrorAction SilentlyContinue).Trim()
        if ($startedPidText -notmatch '^\d+$') { throw '启动脚本未写入有效项目 PID，请检查 runtime/server.err.log。' }
        $started = Get-CimInstance Win32_Process -Filter "ProcessId=$startedPidText" -ErrorAction SilentlyContinue
        if (-not $started -or $started.Name -ne 'java.exe' -or $started.CommandLine -notlike "*$jar*") {
            throw '启动脚本写入的 PID 不是本项目实例，请检查 runtime/server.err.log。'
        }
        $portMatch = [regex]::Match($started.CommandLine, '--server\.port=(\d+)')
        if (-not $portMatch.Success) { throw '本项目实例未声明有效端口，请检查 runtime/server.err.log。' }
        $existingUrl = "http://127.0.0.1:$($portMatch.Groups[1].Value)"
        for ($i = 0; $i -lt 20; $i++) {
            try { $null = Invoke-RestMethod "$existingUrl/api/status" -TimeoutSec 2; break }
            catch {
                if ($i -eq 19) { throw "尚未确认本项目就绪：$existingUrl，请检查 runtime/server.err.log。" }
                Start-Sleep -Milliseconds 300
            }
        }
    }
    if (-not $NoBrowser) { Start-Process $existingUrl }
    Write-Host "URL=$existingUrl"
} finally { Pop-Location }
