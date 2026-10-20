# 支持 Windows PowerShell 5.1+ 与 PowerShell 7+。未选中已安装的 Maven 时才下载到项目本地。
param([Parameter(ValueFromRemainingArguments = $true)][string[]] $MavenArgs)
$ErrorActionPreference = 'Stop'
if ([IntPtr]::Size -ne 8) { throw '需要使用 64 位 PowerShell。' }
Push-Location $PSScriptRoot
try {
    $maven = $env:MAVEN_CMD
    if (-not $maven) {
        $found = Get-Command mvn.cmd -ErrorAction SilentlyContinue
        if ($found) { $maven = $found.Source }
    }
    if (-not $maven) {
        $version = '3.9.16'
        $tools = Join-Path $PSScriptRoot '.tools'
        $maven = Join-Path $tools "apache-maven-$version/bin/mvn.cmd"
        if (-not (Test-Path -LiteralPath $maven)) {
            New-Item -ItemType Directory -Path $tools -Force | Out-Null
            $zip = Join-Path $tools "apache-maven-$version-bin.zip"
            $url = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$version/apache-maven-$version-bin.zip"
            Invoke-WebRequest $url -UseBasicParsing -OutFile $zip
            $expected = ((Invoke-WebRequest "$url.sha512" -UseBasicParsing).Content.Trim() -split '\s+')[0]
            if ((Get-FileHash -LiteralPath $zip -Algorithm SHA512).Hash -ne $expected) { throw 'Maven 压缩包校验值不匹配。' }
            Expand-Archive -LiteralPath $zip -DestinationPath $tools
        }
    }
    & $maven @MavenArgs
    exit $LASTEXITCODE
} finally { Pop-Location }
