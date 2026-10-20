# 本地故障演练能力
仅请求程序创建的回环 WireMock，GET /orders/ORD-1001。正常正文为 orderId=ORD-1001、status=PAID、amount=128.50。
场景为 NORMAL 正常、CONTINUOUS_ERROR 持续 5xx、DELAY 延迟、RECOVER_AFTER_FAILURES 前 N 次失败后恢复。
maxAttempts 为 1..5；failureCount 为 0..3，恢复场景必须 1..3，其他场景必须 0。
failureStatus 只能是 500、502、503、504。responseDelayMs 为 0..3000，仅 DELAY 可非零。
不接受外部目标、任意请求体、脚本、生产系统写入。
