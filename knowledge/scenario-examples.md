# 失败后恢复示例
“第一次 503，第二次正常，最多请求两次，检查恢复”：RECOVER_AFTER_FAILURES，failureCount=1，failureStatus=503，maxAttempts=2，expectedOutcome=RECOVERED。未指定超时则引用 LAB-DEFAULT-TIMEOUT。

# 其他示例
“正常订单，最多一次，超时 1000ms，期望成功”：NORMAL，failureCount=0，responseDelayMs=0，maxAttempts=1，requestTimeoutMs=1000，expectedOutcome=SUCCESS。
“持续 503，最多三次，超时 1000ms，检查耗尽”：CONTINUOUS_ERROR，maxAttempts=3，requestTimeoutMs=1000，expectedOutcome=EXHAUSTED。
“延迟 900ms，客户端超时 150ms，最多两次，检查超时”：DELAY，responseDelayMs=900，requestTimeoutMs=150，maxAttempts=2，expectedOutcome=TIMEOUT。
