# 191 个交易场景逐步验算报告设计

## 目标

把现有 191 个 Spot 与 USDT 线性永续 DEMO 场景改造成可以逐条审计的中文 HTML。读者无需打开 JSON，也能看懂每个场景如何操作、实际发生了什么、算法期望什么、两边是否一致。

## 受众与交付形式

- 受众：需要核对交易语义、数值和测试证据的技术读者。
- 交付：一个 canonical `artifact.json` 和一个由官方 portable renderer 打包的自包含 HTML。
- 保留现有 9 个业务分类、概览指标、分类覆盖图和官方语义说明。

## 证据边界

每个 caseId 只使用以下现有证据：

- `docs/testing/spot-perp-scenario-matrix.json`：初始余额、仓位模式、保证金模式、杠杆、价格步骤和请求动作。
- `backend/target/scenario-artifacts/<caseId>/actual.json`：系统真实检查点。
- `backend/target/scenario-artifacts/<caseId>/expected.json`：算法生成的一个或多个合法预期分支、检查点与 `calculationTrace`。187 个普通场景各 1 个分支，4 个竞态场景各 2 个合法赢家分支。
- `docs/testing/spot-perp-test-report.md`：191/191 总体通过结论及生成时间。

不得补造 ETH、部分成交、深度、成交价、手续费、盈亏、错误码或状态。

## 单条场景结构

每条场景固定显示六个阅读区：

1. **编号与场景**：大白话场景名、caseId、产品、成功/预期拒绝。
2. **初始条件**：初始钱包、初始仓位、持仓模式、保证金模式、杠杆、订单类型、数量单位和完整行情路径。
3. **操作步骤与实际反馈**：按 action/checkpoint 顺序显示对应行情、请求参数、实际新增或变化的订单、成交、仓位、钱包、账户、账本、保护单、事件和失败码。
4. **最终实际结果**：完整列出最终 snapshot 中的 orders、trades、positions、wallets、account、ledger、protections、events 和 failure。
5. **算法与校验结果**：先说明核心算法，再显示 `expected.json.calculationTrace` 和最终 expected snapshot 的完整摘要。
6. **逐项比对结论**：每个 checkpoint 分别比较 orders、trades、positions、wallets、account、ledger、protections、events、failure；最后显示 `actual == expected` 是否完全相等。

## 相等性规则

- JSON 数字按解析后的数值比较，因此 `0E-8` 与 `0.00000000`、`110.51105000` 与 `110.5110500000` 视为相等。
- 数组顺序、对象字段值、空数组、空字符串、布尔值和错误码必须一致。
- 只比较 actual 与 expected 共同定义的 `caseId` 和 `checkpoints`；`calculationTrace` 是 expected 独有的算法证据，不要求 actual 重复保存。
- 对 4 个双分支竞态场景，actual 必须且只能命中一个合法 expected 分支；报告同时展示两个分支的完整校验数据并标明实际命中项。
- 任一 checkpoint 任一组件不相等时，报告必须显示不一致组件，不能仍显示“测试成功”。

## 表格设计

继续使用 9 张分类表，每行一个场景，六列为：

1. 编号
2. 场景与初始数据
3. 详细操作步骤
4. 最终实际结果
5. 算法与 expected 校验数据
6. 实际/预期比对结论

这些是审计明细表，不再追求窄屏紧凑；长文本使用分段和换行，精确值保留最多 8 位小数或原始请求精度。

## 验收标准

- 191 个唯一 caseId，Spot 36 个、Perpetual 155 个、预期拒绝 28 个。
- 191 个 actual 与 191 个 expected 全部可解析。
- 191 个场景均有逐步操作、最终实际结果、算法 trace、expected 最终结果、逐 checkpoint 比对和总相等结论。
- 191 个 `actual == expected` 全部为 true；其中 187 个命中唯一分支，4 个竞态场景各命中两个合法分支中的唯一一个；否则生成器失败并指出 caseId 与组件。
- 代表用例保留精确成交、手续费和盈亏；拒绝用例保留请求参数、错误码和零副作用或合法前置影响。
- canonical validation/package 通过；若本机没有 Chromium，明确披露 `structural_only`。

## 范围限制

- 仍是 DEMO、单次全量成交语义，不代表真实 broker/FIX/LP 成交。
- 不新增真实行情、真实交易或外部写入。
- 不执行 Git 分支、暂存、提交、推送或 PR。
