# 使用隔离知识副本和独立应用进程验证真实模型。
# 不修改受版本控制的知识文件，不重启用户现有服务。
param([int]$Port = 19290)
$ErrorActionPreference = 'Stop'
if ([IntPtr]::Size -ne 8) { throw '需要使用 64 位 PowerShell。' }
$root = Split-Path $PSScriptRoot
Push-Location $root
$app = $null
$saved = @{}
try {
    $names = @('AFL_CHAT_BASE_URL','AFL_CHAT_MODEL','AFL_CHAT_API_KEY','AFL_EMBEDDING_BASE_URL','AFL_EMBEDDING_MODEL','AFL_EMBEDDING_API_KEY')
    foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
    if (Test-Path -LiteralPath .env) {
        foreach ($line in Get-Content -LiteralPath .env) {
            if ($line -match '^\s*(AFL_[A-Z_]+)\s*=(.*)$' -and $names -contains $Matches[1]) {
                [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim().Trim('"').Trim("'"), 'Process')
            }
        }
    }
    New-Item -ItemType Directory -Path runtime/evidence -Force | Out-Null
    $missing = @($names | Where-Object { -not [Environment]::GetEnvironmentVariable($_, 'Process') })
    if ($missing.Count -gt 0) {
        $result = @{ checkedAt = [DateTimeOffset]::Now.ToString('o'); A05 = 'NOT_VERIFIED'; reason = '缺少本项目专用配置'; missingNames = $missing }
        $result | ConvertTo-Json | Set-Content -LiteralPath runtime/evidence/rag-change.json -Encoding utf8
        Write-Output ($result | ConvertTo-Json)
        exit 2
    }
    $jar = Join-Path $root 'target/api-fault-lab-0.1.0-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $jar)) { throw '请先构建项目。' }
    $runDirectory = Join-Path $root ("runtime/rag-change-" + [guid]::NewGuid().ToString())
    $knowledge = Join-Path $runDirectory 'knowledge'
    New-Item -ItemType Directory -Path $knowledge -Force | Out-Null
    Get-ChildItem -LiteralPath (Join-Path $root 'knowledge') -Filter '*.md' | Copy-Item -Destination $knowledge
    $rule = Join-Path $knowledge 'timeout-rules.md'
    $original = Get-Content -LiteralPath $rule -Raw
    if ($original -notmatch 'requestTimeoutMs 固定为 (\d+)') { throw '隔离副本中找不到项目默认超时规则。' }
    $before = [int]$Matches[1]
    $after = $(if ($before -eq 1400) { 1600 } else { 1400 })
    $chosenPort = $null
    foreach ($candidate in $Port..($Port + 20)) {
        $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $candidate)
        try { $probe.Start(); $chosenPort = $candidate; break }
        catch [Net.Sockets.SocketException] {}
        finally { $probe.Stop() }
    }
    if (-not $chosenPort) { throw '独立验收端口范围内没有空闲端口。' }
    $arguments = @('-jar', "`"$jar`"", "--server.port=$chosenPort", "--lab.knowledge-dir=`"$knowledge`"", "--lab.runtime-dir=`"$runDirectory/state`"")
    $app = Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList $arguments -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput "$runDirectory/server.out.log" -RedirectStandardError "$runDirectory/server.err.log"
    $url = "http://127.0.0.1:$chosenPort"
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        if ($app.HasExited) { throw '独立验收应用已退出。' }
        try { $null = Invoke-RestMethod "$url/api/status" -TimeoutSec 2; $ready = $true; break }
        catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $ready) { throw '尚未确认独立验收应用启动成功。' }
    function Invoke-RuleDrill {
        $body = @{ requirement = '遵循LAB-DEFAULT-TIMEOUT项目默认超时规则，正常返回订单，最多请求一次，期望首次成功。我没有指定超时数值。'; manualScenario = $null } | ConvertTo-Json
        Invoke-RestMethod "$url/api/drills" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 360
    }
    $firstIndex = Invoke-RestMethod "$url/api/knowledge/rebuild" -Method Post -TimeoutSec 360
    $first = Invoke-RuleDrill
    $original.Replace("requestTimeoutMs 固定为 $before", "requestTimeoutMs 固定为 $after") | Set-Content -LiteralPath $rule -Encoding utf8
    $nextIndex = Invoke-RestMethod "$url/api/knowledge/rebuild" -Method Post -TimeoutSec 360
    $next = Invoke-RuleDrill
    $firstRound = $first.rounds | Select-Object -Last 1
    $nextRound = $next.rounds | Select-Object -Last 1
    $firstContext = @($first.knowledge | Where-Object { $_.source -eq 'timeout-rules.md' -and $_.content.Contains("固定为 $before") }).Count -gt 0
    $nextContext = @($next.knowledge | Where-Object { $_.source -eq 'timeout-rules.md' -and $_.content.Contains("固定为 $after") }).Count -gt 0
    $passed = $firstContext -and $nextContext -and $firstRound.plan.scenario.requestTimeoutMs -eq $before -and $nextRound.plan.scenario.requestTimeoutMs -eq $after -and $firstIndex.fingerprint -ne $nextIndex.fingerprint -and $first.status -eq 'PASS' -and $next.status -eq 'PASS'
    $result = [ordered]@{ checkedAt = [DateTimeOffset]::Now.ToString('o'); A05 = $(if ($passed) { 'PASS' } else { 'FAIL' }); firstTask = $first.id; nextTask = $next.id; expectedBefore = $before; expectedAfter = $after; actualBefore = $firstRound.plan.scenario.requestTimeoutMs; actualAfter = $nextRound.plan.scenario.requestTimeoutMs; firstRuleRetrieved = $firstContext; updatedRuleRetrieved = $nextContext; fingerprintChanged = $firstIndex.fingerprint -ne $nextIndex.fingerprint; isolatedArtifacts = $runDirectory; trackedKnowledgeUntouched = $true }
    $result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath runtime/evidence/rag-change.json -Encoding utf8
    Write-Output ($result | ConvertTo-Json -Depth 8)
    if (-not $passed) { exit 1 }
} finally {
    # 这里只清理上面启动的子进程，不根据端口查找并停止其他程序。
    if ($app -and -not $app.HasExited) { $app.Kill(); $null = $app.WaitForExit(10000) }
    foreach ($entry in $saved.GetEnumerator()) { [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process') }
    Pop-Location
}
