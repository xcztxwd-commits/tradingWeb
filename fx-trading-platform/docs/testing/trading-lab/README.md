# Trading Lab Phase 4 fixture contract

本目录冻结 `trading-lab-v1` 的离线验收输入与报告结构，供后续 smoke runner 和持续集成复用。它是 demo-only 合同，不得连接真实 broker、FIX、LP、实时行情或生产账户。

## Files

- `fixed-scenarios.json` 按稳定顺序覆盖 24 fixed scenario categories；每项都有唯一 `scenarioId`、固定 `seed`、能力要求、初始余额、标的、默认持仓配置和虚拟时间轴。
- `random-seeds.json` 提供 deterministic named seeds，包含合法与预期失败两组；相同 seed 必须生成相同规范化哈希。
- `report-schema.json` 固定证据报告的 14 top-level report sections，并严格要求当前安全 API trace envelope 及其请求、响应追踪字段。
- `smoke-trading-lab.test.mjs` 是上述静态合同的离线测试。

## Focused verification

从仓库根目录运行：

```powershell
node --test fx-trading-platform/scripts/smoke-trading-lab.test.mjs
```

该命令只读取 fixture、调用本地纯函数并验证 JSON 合同。It does not start a backend, frontend, Supervisor, Docker, browser, Maven process, or listener；尤其不会占用宿主 `8080`。真正的 `smoke-trading-lab.mjs` 运行器属于后续任务，不由本目录合同测试创建或替代。
