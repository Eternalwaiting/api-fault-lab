param([string]$ShortcutPath = (Join-Path ([Environment]::GetFolderPath('Desktop')) 'API Fault Lab.lnk'))
$ErrorActionPreference = 'Stop'
if ([IntPtr]::Size -ne 8) { throw '需要使用 64 位 PowerShell。' }
$project = Split-Path -Parent $PSScriptRoot
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($ShortcutPath)
$shellCommand = if ($PSVersionTable.PSEdition -eq 'Core') {
    (Get-Command pwsh.exe -ErrorAction Stop).Source
} else {
    (Get-Command powershell.exe -ErrorAction Stop).Source
}
$shortcut.TargetPath = $shellCommand
$shortcut.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$project\launch.ps1`""
$shortcut.WorkingDirectory = $project
$shortcut.Description = '启动 API Fault Lab；仅识别并启动本项目，不操作其他服务。'
$shortcut.IconLocation = "$env:SystemRoot\System32\shell32.dll,72"
$shortcut.Save()
if (-not (Test-Path -LiteralPath $ShortcutPath)) { throw '桌面快捷方式创建失败。' }
Get-Item -LiteralPath $ShortcutPath | Select-Object FullName,Length,LastWriteTime
