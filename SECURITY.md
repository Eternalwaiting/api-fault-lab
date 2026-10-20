# 安全边界与报告

本项目仅供受信任电脑上的本地学习演练。Spring Boot 与 WireMock 均绑定回环地址。
演练接口不接受主机、URL、请求脚本或任意 Java 代码。

模型服务使用用户显式配置的 HTTPS Base URL 与 Bearer API Key；HTTP 仅允许回环测试。
禁止重定向，模型网络请求不自动重试。用户需求、相关知识和执行记录会发送给配置的模型服务。
请不要输入真实业务数据或秘密；本地报告和向量缓存保存在忽略提交的 `runtime/`。

POST 拒绝其他网页 Origin；该检查不能替代身份认证，不应将应用暴露到公网。
没有用户权限、多租户隔离、生产压力测试或可用性保证。
公开发布前需做依赖漏洞扫描；本地测试通过不能替代该检查。

当前尚无公开仓库或安全报告邮箱。公开后请通过 GitHub Security Advisories
或仓库维护者配置的私密渠道报告安全问题；不要在公开 Issue 中粘贴密钥、完整模型响应、
本地路径或生产数据。

## 2026-10-19 依赖审查记录

已将 Spring Boot 升级到 4.1.1（Spring Framework 7.0.9）、Spring AI 升级到
2.0.1，并使用 Jackson 2.21.7、Jackson 3.1.7、Log4j 2.25.5 与 Tomcat 11.0.25；
56 个 Maven 测试全部通过。许可证插件解析了编译、运行时和测试范围的 112 项传递依赖，
完整清单见 `third-party/MAVEN-THIRD-PARTY.txt`，
本次没有生成未知许可证模板。

OSV Scanner 2.7.0 在 2026-10-19 复扫退出码为 0，报告 0 条漏洞记录、0 个唯一漏洞 ID 和
0 个受影响包。该结果是历史工程扫描证据，不是永久安全保证；公开发布前仍需按发布时点
重新扫描，并在发布记录中保存扫描工具版本、日期和依赖树范围。

## 2026-10-20 发布前扫描

使用官方 OSV Scanner 2.7.0 Windows x64 发布包，并先核对官方 SHA-256。针对 Maven
解析出的 112 个直接与传递依赖版本生成 CycloneDX 清单后扫描，退出码为 0，报告 0 个
受影响依赖和 0 条漏洞记录。脱敏后的机器报告保存在本机忽略目录
`runtime/evidence/osv-scanner-2.7.0-2026-10-20.json`，不提交到 Git。该结果只反映扫描时
OSV 数据库对清单组件的已知漏洞匹配，不构成完整安全保证。

## 公开发布门禁

- `mvnw.ps1 -B '-Dstyle.color=never' verify` 必须退出 0。
- `scripts/check-repository.ps1` 必须报告 0 个疑似凭证文件。
- 公开候选文件不得包含 `.env`、`runtime/`、`target/`、本机路径、PID 或完整模型响应。
- `scripts/clean-room-verify.ps1` 是可选隔离环境复核，不要求 Windows VM。
- 上传后检查 GitHub Actions 的 Windows CI；CI 只验证构建和测试，不代表真实模型或生产验收。
