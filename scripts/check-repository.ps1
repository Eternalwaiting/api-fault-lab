$ErrorActionPreference = 'Stop'
if ([IntPtr]::Size -ne 8) { throw '需要使用 64 位 PowerShell。' }
Push-Location (Split-Path $PSScriptRoot)
try {
    $isGitRepository = Test-Path -LiteralPath (Join-Path (Get-Location) '.git')
    if ($isGitRepository) {
        $files = @(git -c core.quotepath=false ls-files --cached --others --exclude-standard)
        if ($LASTEXITCODE -ne 0) { throw 'Git 文件枚举失败。' }
    } else {
        $files = @(Get-ChildItem -Recurse -File -Force |
            Where-Object { $_.FullName -notmatch '[\\/](target|runtime|\.tools|\.git)([\\/]|$)' } |
            ForEach-Object { $_.FullName.Substring((Get-Location).Path.Length + 1) })
    }
    $findings = @()
    foreach ($file in $files) {
        if ($file -match '\.(png|jpg|ico)$') { continue }
        if (Select-String -LiteralPath $file -Quiet -Pattern 'sk-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----') {
            $findings += $file
        }
    }
    $result = [ordered]@{
        checkedAt = [DateTimeOffset]::Now.ToString('o')
        status = $(if ($findings.Count -eq 0) { 'PASS' } else { 'FAIL' })
        inspectedFiles = $files.Count
        suspectedSecretFiles = $findings
        ignoredLocalConfig = $(if (Test-Path -LiteralPath '.env') { '.env-present' } else { '.env-absent' })
        remoteCount = $(if ($isGitRepository) { @(git remote).Count } else { 0 })
        limitation = '仅按常见凭证模式进行启发式检查，不等于完整安全或依赖审计。'
    }
    New-Item -ItemType Directory -Path runtime/evidence -Force | Out-Null
    $result | ConvertTo-Json | Set-Content -LiteralPath runtime/evidence/repository-check.json -Encoding utf8
    Write-Output ($result | ConvertTo-Json)
    if ($findings.Count -gt 0) { exit 1 }
} finally { Pop-Location }
