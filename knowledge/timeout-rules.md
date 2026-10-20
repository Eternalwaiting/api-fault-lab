# LAB-DEFAULT-TIMEOUT 项目默认超时规则
API Fault Lab 项目特定规则：用户省略客户端超时时，requestTimeoutMs 固定为 1200。
这不是 Java 校验器的默认值，而是必须由检索上下文提供给规划模型的规则。修改本文件并重建知识库后应使用新值。
单次超时必须在 100..5000 毫秒。DELAY 的 responseDelayMs 为 0..3000。
延迟明显大于超时预期 TIMEOUT；明显小于超时预期 SUCCESS。避免相等边界。
用户明确给出超时值时优先遵守用户值，不用项目默认规则覆盖。
