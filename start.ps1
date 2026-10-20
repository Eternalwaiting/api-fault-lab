param([int]$Port = 19090, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
if ([IntPtr]::Size -ne 8) { throw '需要使用 64 位 PowerShell。' }
Push-Location $PSScriptRoot
try {
    $envFile = Join-Path $PSScriptRoot '.env'
    if (Test-Path -LiteralPath $envFile) {
        foreach ($line in Get-Content -LiteralPath $envFile) {
            if ($line -match '^\s*(AFL_[A-Z_]+)\s*=(.*)$') {
                $name = $Matches[1]
                $value = $Matches[2].Trim().Trim('"').Trim("'")
                [Environment]::SetEnvironmentVariable($name, $value, 'Process')
            }
        }
    }
    if ($env:AFL_PORT) { $Port = [int]$env:AFL_PORT }
    if (-not $SkipBuild) {
        & (Join-Path $PSScriptRoot 'mvnw.ps1') -B package
        if ($LASTEXITCODE -ne 0) { throw '构建失败。' }
    }
    $chosenPort = $null
    foreach ($candidate in $Port..($Port + 20)) {
        $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $candidate)
        try { $probe.Start(); $chosenPort = $candidate; break }
        catch [Net.Sockets.SocketException] { }
        finally { $probe.Stop() }
    }
    if (-not $chosenPort) { throw '指定范围内没有空闲端口。' }
    New-Item -ItemType Directory -Path runtime -Force | Out-Null
    $java = (Get-Command java.exe -ErrorAction Stop).Source
    $jar = Join-Path $PSScriptRoot 'target/api-fault-lab-0.1.0-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $jar)) { throw '应用 JAR 不存在，请去掉 -SkipBuild 参数重新运行。' }
    $arguments = @('-jar', "`"$jar`"", "--server.port=$chosenPort")
    $process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput 'runtime/server.out.log' -RedirectStandardError 'runtime/server.err.log'
    $process.Id | Set-Content -LiteralPath runtime/server.pid
    $url = "http://127.0.0.1:$chosenPort"
    for ($i = 0; $i -lt 45; $i++) {
        if ($process.HasExited) { throw '应用已退出，请检查 runtime/server.err.log 和 server.out.log。' }
        try {
            $status = Invoke-RestMethod "$url/api/status" -TimeoutSec 2
            Write-Host "URL=$url PID=$($process.Id) AI_CONFIGURED=$($status.ai.configured)"
            return
        } catch { Start-Sleep -Milliseconds 500 }
    }
    throw "尚未确认启动成功，PID=$($process.Id)，请检查日志。"
} finally { Pop-Location }
