# Execution Mode Safety Implementation

## 背景

原配置在默认 profile 和 `prod` profile 下都使用 `execution.mode=broker`。但当前
`BrokerExecutionAdapter`、`FixExecutionAdapter`、`LpExecutionAdapter` 仍是占位实现：
它们会被注册为 `ExecutionAdapter`，但在市场单执行时才抛出 `*_NOT_ENABLED`。

这会让生产环境出现语义风险：后端已经启动，配置看起来是 live/broker 模式，但真实成交能力并不存在。

## 实现目标

- 默认和生产 profile 不再进入 live execution，改为 `execution.mode=disabled`。
- dev profile 保持 `execution.mode=demo`，继续支持本地模拟交易。
- `disabled` 模式允许后端启动，但任何订单执行都会返回明确的 `EXECUTION_DISABLED`。
- `broker` / `fix` / `lp` 模式必须经过启动期校验。
- 当前 live adapter 仍是占位实现时，即使配置完整也不能作为生产交易模式启动。

## 配置行为

默认配置：

```yaml
execution:
  mode: ${EXECUTION_MODE:disabled}
```

生产 profile：

```yaml
execution:
  mode: ${EXECUTION_MODE:disabled}
```

开发 profile：

```yaml
execution:
  mode: ${EXECUTION_MODE:demo}
```

live mode 的配置入口统一放在 `execution.<mode>` 下：

```yaml
execution:
  mode: broker
  broker:
    endpoint: ${EXECUTION_BROKER_ENDPOINT:}
    api-key: ${EXECUTION_BROKER_API_KEY:}
    account-id: ${EXECUTION_BROKER_ACCOUNT_ID:}
```

`fix` 和 `lp` 使用同样的基础字段：

- `execution.fix.endpoint`
- `execution.fix.api-key`
- `execution.fix.account-id`
- `execution.lp.endpoint`
- `execution.lp.api-key`
- `execution.lp.account-id`

## 代码结构

- `ExecutionMode`
  - 定义 `DISABLED`、`DEMO`、`BROKER`、`FIX`、`LP`。
  - 标记哪些 mode 是 live mode。

- `ExecutionProperties`
  - 绑定 `execution.*` 配置。
  - 统一提供 broker/fix/lp 的必填字段检查。

- `DisabledExecutionAdapter`
  - 只在 `execution.mode=disabled` 时注册。
  - 对订单执行抛出 `EXECUTION_DISABLED`，避免静默假成交。

- `ExecutionAdapterReadiness`
  - live adapter 暴露自身是否真正可用于 live trading。
  - 当前 `broker` / `fix` / `lp` 返回 `false`。
  - 未来真实 adapter 接入后，只需要让对应 adapter 返回 `true` 并保留配置校验。

- `ExecutionModeStartupValidator`
  - 应用启动时执行 fail-fast 校验。
  - `disabled` 和 `demo` 直接通过。
  - `broker` / `fix` / `lp` 先检查必填配置，再检查 adapter readiness。

## 启动校验规则

| `execution.mode` | 启动结果 |
| --- | --- |
| `disabled` | 允许启动，订单执行返回 `EXECUTION_DISABLED` |
| `demo` | 允许启动，使用 `SimulatedExecutionAdapter` |
| `broker` 缺少配置 | 启动失败，提示缺少 `execution.broker.*` |
| `broker` 配置完整但 adapter 仍占位 | 启动失败，提示 broker 仍是 future live trading |
| `fix` / `lp` | 同 broker 规则 |

## 未来接入真实 broker adapter 的最小步骤

1. 在对应 live adapter 中实现真实成交逻辑。
2. 保留 `ExecutionAdapterReadiness`，将 `readyForLiveTrading()` 改为只在真实可用时返回 `true`。
3. 如果 broker/fix/lp 需要不同字段，在 `ExecutionProperties.LiveAdapterProperties` 或新的专用配置类中扩展字段。
4. 补充启动校验测试，证明配置缺失会失败、配置完整且 adapter ready 会通过。
5. 补充真实 adapter 的下单成功、拒单、超时和异常映射测试。

## 验证记录

已执行：

```bash
git diff --check -- fx-trading-platform/backend/src/main/java/com/fxplatform/execution fx-trading-platform/backend/src/main/java/com/fxplatform/FxPlatformApplication.java fx-trading-platform/backend/src/main/resources/application.yml fx-trading-platform/backend/src/main/resources/application-prod.yml fx-trading-platform/backend/src/test/java/com/fxplatform/execution fx-trading-platform/backend/src/test/java/com/fxplatform/config/ProviderModeApplicationContextTest.java fx-trading-platform/backend/src/test/java/com/fxplatform/common/security/ProductionConfigurationSafetyTest.java fx-trading-platform/docs/execution-mode-safety-implementation.md
```

结果：退出码 `0`，没有 whitespace/error diff 问题；命令输出只有 Windows 行尾转换 warning。

当前 fresh compile 命令：

```bash
cmd.exe /d /s /c "mvn -DskipTests compile"
```

结果：被既有无关 main compile 问题阻塞：

- `RiskCheckService` 依赖缺失 `InstrumentRulesEngine`
- 相关文件状态显示 `RiskCheckService.java` 已修改，`InstrumentRules.java` 是未跟踪文件

targeted test 命令：

```bash
cmd.exe /d /s /c "mvn -Dtest=ProviderModeApplicationContextTest,ExecutionAdapterApplicationContextTest,ExecutionModeStartupValidatorTest,ProductionConfigurationSafetyTest test"
```

结果：被既有无关 testCompile 问题阻塞：

- `InstrumentRulesEngineTest` 缺少 `InstrumentRules`
- `InstrumentRulesEngineTest` / `RiskCheckServiceTest` 缺少 `InstrumentRulesEngine`

这些错误不来自本次 execution mode 改动；在 Maven 编译源码/测试源码阶段发生，导致本次新增 targeted tests 还不能通过 Maven surefire 单独执行。
