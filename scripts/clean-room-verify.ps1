param(
    [string]$ProjectRoot = (Split-Path $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
if ([IntPtr]::Size -ne 8) {
    throw '需要使用 64 位 PowerShell。'
}

$root = (Resolve-Path -LiteralPath $ProjectRoot).Path
$envPath = Join-Path $root '.env'
if (Test-Path -LiteralPath $envPath) {
    throw "干净环境禁止存在 .env：$envPath"
}

foreach ($forbidden in @('runtime', 'target', '.tools')) {
    if (Test-Path -LiteralPath (Join-Path $root $forbidden)) {
        throw "干净环境验证前请移除缓存目录：$forbidden"
    }
}

$env:AFL_CHAT_BASE_URL = ''
$env:AFL_CHAT_MODEL = ''
$env:AFL_CHAT_API_KEY = ''
$env:AFL_EMBEDDING_BASE_URL = ''
$env:AFL_EMBEDDING_MODEL = ''
$env:AFL_EMBEDDING_API_KEY = ''
$env:AFL_PORT = '19190'

Push-Location $root
try {
    & (Join-Path $root 'mvnw.ps1') -B '-Dstyle.color=never' verify
    if ($LASTEXITCODE -ne 0) { throw 'Maven verify 失败。' }

    & (Join-Path $root 'scripts/check-repository.ps1')
    if ($LASTEXITCODE -ne 0) { throw '仓库候选文件扫描失败。' }

    $jar = Join-Path $root 'target/api-fault-lab-0.1.0-SNAPSHOT.jar'
    $java = (Get-Command java.exe -ErrorAction Stop).Source
    $process = Start-Process -FilePath $java `
        -ArgumentList @('-jar', "`"$jar`"", '--server.port=19190') `
        -WorkingDirectory $root -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $root 'clean-room.out.log') `
        -RedirectStandardError (Join-Path $root 'clean-room.err.log')
    try {
        $ready = $false
        for ($i = 0; $i -lt 45; $i++) {
            if ($process.HasExited) { throw '应用在健康检查前退出。' }
            try {
                $status = Invoke-RestMethod 'http://127.0.0.1:19190/api/status' -TimeoutSec 2
                $ready = $true
                break
            } catch {
                Start-Sleep -Milliseconds 500
            }
        }
        if (-not $ready) { throw '干净环境服务未在限定时间内启动。' }
        if ($status.ai.configured) { throw '无模型配置的干净环境不应报告 AI 已配置。' }

        $body = @{
            requirement = '第一次返回503，第二次返回正常订单，最多请求两次。'
            manualScenario = @{
                scenarioType = 'RECOVER_AFTER_FAILURES'
                failureCount = 1
                failureStatus = 503
                responseDelayMs = 0
                maxAttempts = 2
                requestTimeoutMs = 1200
                expectedOutcome = 'RECOVERED'
            }
        } | ConvertTo-Json -Depth 10
        $report = Invoke-RestMethod 'http://127.0.0.1:19190/api/drills' -Method Post `
            -ContentType 'application/json; charset=utf-8' `
            -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 30
        if ($report.status -ne 'PASS' -or $report.rounds[0].execution.observedOutcome -ne 'RECOVERED') {
            throw '干净环境手动 HTTP 演练未通过。'
        }
        [ordered]@{
            checkedAt = [DateTimeOffset]::Now.ToString('o')
            status = 'PASS'
            aiConfigured = $status.ai.configured
            reportStatus = $report.status
            observedOutcome = $report.rounds[0].execution.observedOutcome
            serverRequests = $report.rounds[0].execution.serverRequests
            note = '未调用远端模型；仅验证干净构建、无配置启动和本地手动演练。'
        } | ConvertTo-Json
    } finally {
        if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force }
    }
} finally {
    Pop-Location
}
