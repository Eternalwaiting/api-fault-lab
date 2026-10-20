# 程序断言与审核
程序状态 PASS 表示本次预期断言满足；FAIL 表示已执行但不满足预期；基础设施异常是 EXECUTION_ERROR。
expectedOutcome 为 SUCCESS（首次有效成功）、RECOVERED（失败后成功）、EXHAUSTED（5xx 耗尽）、TIMEOUT（全部超时）。
用户期望恢复但请求额度不足时 expectedOutcome 仍为 RECOVERED，程序应得出 FAIL。
审核状态 APPROVED 只说明需求、配置、记录与结论一致，可以同时出现 FAIL 和 APPROVED。
模型只解释与审核，不能修改 Java 原始记录。实际命中的 chunkId 才能引用。
