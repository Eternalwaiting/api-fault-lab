# 参与开发

本项目优先让 Java 初学者沿一次请求读懂核心流程。请保持固定四类场景、
回环 WireMock、串行任务、最多一次修订等边界，避免添加只有透传作用的抽象层。

1. 使用 Windows PowerShell 5.1+ 或 PowerShell 7+（建议 64 位）、Java 21+（当前实测 Java 25）和 Maven 3.9.x；项目脚本会在找不到 Maven 时下载并校验 Maven 3.9.16。
2. 新行为先写会失败的测试，再完成最小实现。
3. 运行 `./mvnw.ps1 -B verify`；涉及页面时执行实际浏览器操作。
4. 不提交 `.env`、`runtime/`、密钥、完整模型响应和本机路径日志。
5. PR 说明区分测试桩、真实本地 HTTP、真实远端模型和生产边界。

提交前请运行 `./scripts/check-repository.ps1` 和 `./mvnw.ps1 -B verify`。
`./scripts/clean-room-verify.ps1` 可用于隔离环境复核，但不是贡献或发布的必需门禁，
且不得读取开发机 `.env`。
