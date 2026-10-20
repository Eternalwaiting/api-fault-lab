# 第三方依赖与参考

项目代码采用 MIT。第三方组件保持各自许可证，不将它们的已有能力宣称为本项目原创。

| 组件 | 固定版本 | 许可证 | 用途 |
|---|---|---|---|
| Spring Boot starters | 4.1.1 | Apache-2.0 | HTTP API、静态资源、校验、测试 |
| Spring AI OpenAI / Vector Store | 2.0.1 | Apache-2.0 | 模型协议客户端、Embedding 接口、SimpleVectorStore |
| WireMock standalone | 3.13.2 | Apache-2.0 | 状态化故障响应、延迟与请求日志 |
| Lucide | 1.8.0 | ISC，含所列 Feather 来源声明 | 本地托管的图标库 |
| JUnit / Mockito / AssertJ | 由 Boot 测试依赖管理 | 各自上游许可证 | 仅测试使用 |

前三项已读取本机下载的 Maven POM 中 licenses 字段；Lucide 已保留完整上游
`third-party/lucide-LICENSE`，资源原样复制自已安装的官方包。JAR 中的第三方
LICENSE/NOTICE 保留在 Spring Boot 嵌套依赖内。2026-10-19 使用
`license-maven-plugin:2.7.1:add-third-party` 解析了编译、运行时和测试范围的
传递依赖，完整清单保存在 `third-party/MAVEN-THIRD-PARTY.txt`，本次共 112 项，
未生成未知许可证模板。该清单是工程审计材料，不替代法律意见；发布前仍需按
实际分发内容复核 EPL/LGPL 及 GPL2 with CPE 等多许可证表达和 NOTICE 义务。

参考概念与官方文档（未复制教程业务代码）：

- Spring AI 2.0 入门与 Boot 兼容性：https://docs.spring.io/spring-ai/reference/2.0/getting-started.html
- SimpleVectorStore：https://docs.spring.io/spring-ai/reference/2.0/api/vectordbs/simple.html
- Spring Boot 4.1 Java 要求：https://docs.spring.io/spring-boot/4.1/system-requirements.html
- WireMock stateful behaviour：https://wiremock.org/docs/stateful-behaviour/
- WireMock delays / faults：https://wiremock.org/docs/simulating-faults/
- WireMock 源码：https://github.com/wiremock/wiremock
- Lucide：https://lucide.dev/license

本项目新增的是：受限场景对象与校验、真实 HTTP 尝试及断言、知识分块和缓存指纹、
固定规划/审核角色的有界编排、引用检查、报告与学习材料。没有实现自己的大模型、
Embedding 算法或通用故障注入引擎。
