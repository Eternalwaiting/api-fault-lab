param([string]$BaseUrl = 'http://127.0.0.1:19090')
$ErrorActionPreference = 'Stop'
if ([IntPtr]::Size -ne 8) { throw '需要使用 64 位 PowerShell。' }
Push-Location (Split-Path $PSScriptRoot)
try {
    New-Item -ItemType Directory -Path runtime/evidence -Force | Out-Null
    $status = Invoke-RestMethod "$BaseUrl/api/status"
    if (-not $status.ai.configured) {
        $evidence = [ordered]@{ checkedAt = [DateTimeOffset]::Now.ToString('o'); status = 'NOT_VERIFIED'; reason = '本项目的对话和 Embedding 配置不完整'; A01 = 'NOT_VERIFIED'; A02 = 'NOT_VERIFIED'; A03 = 'NOT_VERIFIED'; A04 = 'NOT_VERIFIED'; A05 = 'NOT_VERIFIED'; A06 = 'NOT_VERIFIED' }
        $evidence | ConvertTo-Json | Set-Content -LiteralPath runtime/evidence/live-ai.json -Encoding utf8
        Write-Output ($evidence | ConvertTo-Json)
        exit 2
    }
    $rebuilt = Invoke-RestMethod "$BaseUrl/api/knowledge/rebuild" -Method Post -TimeoutSec 360
    $body = @{ requirement = '模拟查询订单接口：第一次返回503，第二次返回正常订单。客户端最多请求两次，检查是否恢复成功。'; manualScenario = $null } | ConvertTo-Json
    $drill = Invoke-RestMethod "$BaseUrl/api/drills" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 360
    $planner = @($drill.modelCalls | Where-Object { $_.role -eq 'PLANNER' -and $_.status -eq 'SUCCESS' })
    $reviewer = @($drill.modelCalls | Where-Object { $_.role -eq 'REVIEWER' -and $_.status -eq 'SUCCESS' })
    $embedding = @($drill.modelCalls | Where-Object { $_.role -eq 'EMBEDDING' -and $_.status -eq 'SUCCESS' })
    $last = $drill.rounds | Select-Object -Last 1
    $codes = @($last.execution.attempts | Where-Object { $null -ne $_ } | ForEach-Object status)
    $rule = @($drill.knowledge | Where-Object { $_.source -eq 'timeout-rules.md' -and $_.content -match 'requestTimeoutMs 固定为 (\d+)' }) | Select-Object -First 1
    $expectedTimeout = $null
    if ($rule -and $rule.content -match 'requestTimeoutMs 固定为 (\d+)') { $expectedTimeout = [int]$Matches[1] }
    $ruleCited = $null -ne $rule -and $last.plan.knowledgeChunkIds -contains $rule.chunkId
    $defaultVerified = $null -ne $expectedTimeout -and $last.plan.scenario.requestTimeoutMs -eq $expectedTimeout -and $ruleCited
    $passed = $drill.status -eq 'PASS' -and $planner.Count -gt 0 -and $reviewer.Count -gt 0 -and $embedding.Count -gt 0 -and $drill.knowledge.Count -gt 0 -and ($codes -join ',') -eq '503,200' -and $last.review.status -eq 'APPROVED' -and $defaultVerified
    $evidence = [ordered]@{ checkedAt = [DateTimeOffset]::Now.ToString('o'); status = $(if ($passed) { 'PASS' } else { 'FAIL' }); taskId = $drill.id; reportStatus = $drill.status; stage = $drill.stage; error = $drill.error; chatModel = $status.ai.chatModel; embeddingModel = $status.ai.embeddingModel; dimensions = $rebuilt.dimensions; chunks = $rebuilt.chunks; contextChunks = @($drill.knowledge).Count; defaultRuleRetrieved = $null -ne $rule; defaultRuleCited = $ruleCited; expectedDefaultTimeout = $expectedTimeout; actualTimeout = $last.plan.scenario.requestTimeoutMs; plannerCalls = $planner.Count; reviewerCalls = $reviewer.Count; queryEmbeddingCalls = $embedding.Count; actualHttpCodes = $codes; review = $last.review.status; A05 = 'NOT_VERIFIED：请运行 verify-rag-change.ps1'; note = '仅证明本次供应商调用，不代表模型普遍准确。' }
    $evidence | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath runtime/evidence/live-ai.json -Encoding utf8
    Write-Output ($evidence | ConvertTo-Json -Depth 10)
    if (-not $passed) { exit 1 }
} finally { Pop-Location }
