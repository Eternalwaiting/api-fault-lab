# 只读取本项目 .env，不回显密钥或供应商错误原文，不改配置。
param([int]$TimeoutSeconds = 25)
$ErrorActionPreference = 'Stop'
if ([IntPtr]::Size -ne 8) { throw '需要使用 64 位 PowerShell。' }
$root = Split-Path $PSScriptRoot
$config = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $root '.env')) {
    if ($line -match '^\s*(AFL_[A-Z_]+)\s*=(.*)$') {
        $config[$Matches[1]] = $Matches[2].Trim().Trim('"').Trim("'")
    }
}
$base = [uri]$config.AFL_EMBEDDING_BASE_URL
if ($base.Scheme -ne 'https' -or $base.UserInfo -or $base.Query -or $base.Fragment) { throw '需要不含认证信息的 HTTPS Base URL。' }
if (-not $config.AFL_EMBEDDING_API_KEY -or -not $config.AFL_EMBEDDING_MODEL) { throw '缺少本项目向量配置。' }
$handler = [Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$client = [Net.Http.HttpClient]::new($handler)
$boundedTimeout = [Math]::Max(1, [Math]::Min(60, $TimeoutSeconds))
$client.Timeout = [TimeSpan]::FromSeconds($boundedTimeout)
if ($client.PSObject.Properties.Name -contains 'DefaultRequestVersion') {
    $client.DefaultRequestVersion = [Version]'1.1'
}
if ($client.PSObject.Properties.Name -contains 'DefaultVersionPolicy') {
    $client.DefaultVersionPolicy = [Net.Http.HttpVersionPolicy]::RequestVersionExact
}
$client.DefaultRequestHeaders.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $config.AFL_EMBEDDING_API_KEY)
$checks = [Collections.Generic.List[object]]::new()
function Invoke-Probe([string]$Name, [string]$Path, $Payload) {
    $request = [Net.Http.HttpRequestMessage]::new($(if ($null -eq $Payload) { 'GET' } else { 'POST' }), ($base.AbsoluteUri.TrimEnd('/') + $Path))
    $response = $null
    $timer = [Diagnostics.Stopwatch]::StartNew()
    try {
        if ($null -ne $Payload) {
            $request.Content = [Net.Http.StringContent]::new(($Payload | ConvertTo-Json -Depth 5 -Compress), [Text.Encoding]::UTF8, 'application/json')
        }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $raw = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $parsed = $null
        try {
            if ($PSVersionTable.PSVersion.Major -ge 6) {
                $parsed = $raw | ConvertFrom-Json -Depth 30 -ErrorAction Stop
            } else {
                $parsed = $raw | ConvertFrom-Json -ErrorAction Stop
            }
        } catch {}
        $patterns = [ordered]@{
            NO_AVAILABLE_CHANNEL = '(?i)no available channel|无可用渠道|没有可用渠道'
            MODEL_NOT_FOUND = '(?i)model.{0,80}(not found|does not exist|not exist|not available)|模型.{0,50}(不存在|未找到|不可用)'
            UNSUPPORTED = '(?i)not support|unsupported|不支持'
            AUTHENTICATION = '(?i)invalid.{0,12}(api.?key|token)|unauthori[sz]ed|authentication|鉴权|认证失败'
            QUOTA = '(?i)quota|insufficient|余额不足|额度不足'
            UPSTREAM = '(?i)upstream|上游'
            ROUTE_NOT_FOUND = '(?i)route.{0,30}not found|Cannot POST|404 page not found|page not found'
            EMBEDDINGS_PATH = '(?i)/embeddings'
            RESPONSES_PATH = '(?i)/responses'
            CHAT_PATH = '(?i)/chat/completions'
            BAD_GATEWAY = '(?i)bad gateway'
            TIMEOUT = '(?i)timeout|timed out|超时'
        }
        $tags = @($patterns.GetEnumerator() | Where-Object { $raw -match $_.Value } | ForEach-Object Key)
        $knownCodes = @('invalid_api_key','model_not_found','insufficient_quota','rate_limit_exceeded','server_error','upstream_error','invalid_request_error','bad_response_status_code','channel_not_found','no_available_channel','api_error','new_api_error','one_api_error')
        $vectors = @($parsed.data | Where-Object { $_.embedding -is [array] })
        $valid = $response.IsSuccessStatusCode -and $vectors.Count -gt 0
        foreach ($item in $vectors) {
            $norm = 0.0
            foreach ($value in $item.embedding) {
                $number = 0.0
                if ($value -isnot [ValueType] -or -not [double]::TryParse([string]$value, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$number) -or [double]::IsNaN($number) -or [double]::IsInfinity($number)) { $valid = $false; break }
                $norm += [double]$value * [double]$value
            }
            if ($item.embedding.Count -eq 0 -or $norm -eq 0) { $valid = $false }
        }
        $catalog = @()
        if ($Path -eq '/models' -and $response.IsSuccessStatusCode) {
            $catalog = @($parsed.data | ForEach-Object id | Where-Object { $_ -match '^[a-zA-Z0-9/_.:-]{1,100}$' -and $_ -notmatch '(?i)sk-' })
        }
        $record = [ordered]@{
            name = $Name; path = $Path; model = $Payload.model
            httpStatus = [int]$response.StatusCode; elapsedMs = $timer.ElapsedMilliseconds
            jsonParsed = $null -ne $parsed
            errorCode = $(if ($knownCodes -contains [string]$parsed.error.code) { [string]$parsed.error.code } else { 'OTHER_OR_ABSENT' })
            errorType = $(if ($knownCodes -contains [string]$parsed.error.type) { [string]$parsed.error.type } else { 'OTHER_OR_ABSENT' })
            diagnosticTags = $tags; validNonzeroFiniteVectors = $valid; vectorCount = $vectors.Count
            dimensions = @($vectors | ForEach-Object { $_.embedding.Count })
            catalogModels = $catalog
        }
    } catch {
        $record = [ordered]@{ name = $Name; path = $Path; model = $Payload.model; httpStatus = $null; elapsedMs = $timer.ElapsedMilliseconds; exceptionType = $_.Exception.GetType().Name }
    } finally {
        if ($response) { $response.Dispose() }
        $request.Dispose()
    }
    $checks.Add($record)
    $record | ConvertTo-Json -Depth 6 -Compress | Write-Output
}
try {
    Invoke-Probe 'catalog' '/models' $null
    Invoke-Probe 'current-array' '/embeddings' @{ model = $config.AFL_EMBEDDING_MODEL; input = @('API Fault Lab dimension check') }
    Invoke-Probe 'current-string' '/embeddings' @{ model = $config.AFL_EMBEDDING_MODEL; input = 'API Fault Lab dimension check' }
    Invoke-Probe 'current-array-float' '/embeddings' @{ model = $config.AFL_EMBEDDING_MODEL; input = @('API Fault Lab dimension check'); encoding_format = 'float' }
    if ($config.AFL_EMBEDDING_MODEL -ne 'text-embedding-3-small') {
        Invoke-Probe 'standard-model-control' '/embeddings' @{ model = 'text-embedding-3-small'; input = @('API Fault Lab dimension check'); encoding_format = 'float' }
    }
    $evidence = [ordered]@{
        checkedAt = [DateTimeOffset]::Now.ToOffset([TimeSpan]::FromHours(8)).ToString('o')
        embeddingBaseUrl = $base.AbsoluteUri.TrimEnd('/'); configuredModel = $config.AFL_EMBEDDING_MODEL
        checks = @($checks); configurationChanged = $false; providerBodySaved = $false
        note = '诊断标签仅为固定模式匹配；标准模型对照不意味着供应商声明支持该模型。'
    }
    $directory = Join-Path $root 'runtime/evidence'
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $path = Join-Path $directory ('30-embedding-diagnosis-' + [DateTimeOffset]::Now.ToString('yyyyMMdd-HHmmss') + '.json')
    $evidence | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $path -Encoding utf8
    Write-Output "EVIDENCE=$path"
} finally {
    $client.Dispose()
    $handler.Dispose()
    $config.Clear()
}
