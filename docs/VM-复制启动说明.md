# VM 复制与快捷启动

本包用于复制到干净 Windows VM。支持 64 位 Windows PowerShell 5.1 和 PowerShell 7+。
包内不包含 `.env`、`target/`、`runtime/`、
`.tools/` 或任何远端模型凭证。

## 1. 复制项目

将整个 `api-fault-lab` 文件夹复制到 VM，例如：

```text
C:\path\to\api-fault-lab
```

使用 64 位 Windows PowerShell 5.1+ 或 PowerShell 7+ 进入项目目录：

```powershell
Set-Location -LiteralPath 'C:\path\to\api-fault-lab'
```

## 2. 干净环境门禁

先运行不需要模型配置的干净验证：

```powershell
./scripts/clean-room-verify.ps1
```

它会下载并校验 Maven（如果 VM 没有 Maven），运行测试、打包、启动无模型配置的服务，
并执行一次本地 `503 -> 200` 手动演练。它不会调用远端模型。

PowerShell 5.1 从文件管理器右键运行脚本时，如果执行策略阻止，请在 PowerShell 窗口中运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\clean-room-verify.ps1 -ProjectRoot $PWD.Path
```

## 3. 创建桌面快捷方式

```powershell
./scripts/create-shortcut.ps1
```

脚本会根据当前 VM 项目目录生成 `API Fault Lab.lnk`，不会引用宿主机路径。

## 4. 启动

双击桌面的 `API Fault Lab.lnk`，或执行：

```powershell
./launch.ps1
```

默认服务地址为 `http://127.0.0.1:19090`。未配置模型时仍可使用手动演练；
真实 AI/RAG 需要在 VM 项目目录单独创建 `.env`，不要从宿主机复制凭证。
