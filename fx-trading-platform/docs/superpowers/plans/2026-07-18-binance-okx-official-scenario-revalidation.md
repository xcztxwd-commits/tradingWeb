# Binance / OKX 官方语义下的 191 场景独立复核实施计划

> 执行方式：本任务按用户要求直接执行，不等待中途确认。只连接本地 DEMO 测试数据，不连接 Binance、OKX 或任何真实 broker。

**目标：** 不复用项目原 `expected.json` 或 Java Oracle 的计算结果，以 Binance Spot、Binance USDⓈ-M Futures、OKX Spot、OKX USDT-margined SWAP 的官方规则为第二套独立依据，逐一复核 191 个场景，并生成一份完整、易读、表格不截断的 HTML 报告。

**判定原则：**

- `actual = expected` 只表示原项目自洽，不直接等于交易所合规。
- 价格、数量、名义价值、手续费、持仓均价、已实现/未实现盈亏、初始/维持保证金、资金费和钱包账本均重新计算。
- 交易所会随账户等级、交易对、风险档位变化的配置（费率、合约面值、最大杠杆、维持保证金率等）不冒充固定官方常数；公式按官方标准，数值参数按场景输入复算，并在报告中注明实盘接入需由 instrument/fee/risk-tier API 注入。
- Binance 和 OKX 原始 API 名称不同但业务语义等价时，按适配器映射判定；无法无损映射时标为“有条件符合”或“不符合”，不强行判通过。
- 所有金额和数量以十进制定点运算，输出保留 8 位；比较时明确舍入和容差来源。

---

## 任务 1：冻结输入证据并检查快照新鲜度

**涉及文件：**

- 读取：`docs/testing/spot-perp-scenario-matrix.json`
- 读取：`backend/target/scenario-artifacts/*/actual.json`
- 读取：`backend/target/scenario-artifacts/*/expected.json`
- 读取：`backend/src/test/java/com/fxplatform/trading/scenario/oracle/SpotScenarioOracle.java`
- 读取：`backend/src/test/java/com/fxplatform/trading/scenario/oracle/PerpetualScenarioOracle.java`

**步骤：**

1. 记录 191 个场景的产品、动作、模式、订单类型、数量单位和预期拒绝分布。
2. 运行 Oracle 单元测试，确认当前源码能否生成已保存证据中的关键结果。
3. 运行场景矩阵测试（本地 `scenario-it`），重新生成/验证 actual、expected 证据；若环境依赖阻塞，则保留明确证据边界，不把旧快照冒充当前运行结果。
4. 检查每个 `caseId` 是否恰好有一份矩阵定义、一份 actual 和至少一份合法 expected 分支。

**验证命令：**

```powershell
cd backend
mvn "-Dtest=SpotScenarioOracleTest,PerpetualScenarioOracleTest,OracleIsolationContractTest" test
mvn "-Dspring.profiles.active=scenario-it" "-Dscenario.it.enabled=true" "-Dtest=SpotScenarioMatrixIT,PerpetualScenarioMatrixIT,ScenarioResilienceIT" test
```

## 任务 2：建立官方规则登记表

**新增文件：**

- `docs/testing/binance-okx-official-rule-register.json`

**步骤：**

1. 登记 Binance Spot 的 `PRICE_FILTER`、`LOT_SIZE`、`MIN_NOTIONAL/NOTIONAL`、`quantity/quoteOrderQty`、OCO 和手续费资产规则。
2. 登记 Binance USDⓈ-M 的 `positionSide`、one-way/hedge、`reduceOnly`、`closePosition`、订单类型、标记价、线性 PnL、保证金与资金费方向规则。
3. 登记 OKX Spot 的 `tgtCcy`、余额自动缩量/`banAmend`、订单生命周期和手续费字段规则。
4. 登记 OKX USDT SWAP 的 `sz=contracts`、`ctVal × ctMult`、net/long-short、`reduceOnly`、线性 PnL、保证金、资金费和标记价规则。
5. 每条规则保存官方 URL、适用范围、公式/语义、场景映射、动态参数和审计限制。

**数据形态示例：**

```json
{
  "ruleId": "BINANCE_SPOT_COMMISSION_RECEIVED_ASSET",
  "exchange": "BINANCE",
  "product": "SPOT",
  "officialUrl": "https://developers.binance.com/en/docs/products/spot/faqs/commission_faq",
  "semantic": "无 BNB 抵扣时，手续费从成交后收到的资产扣除",
  "dynamicInputs": ["account commission rate", "discount asset setting"]
}
```

## 任务 3：先写失败测试，定义独立审计器契约

**新增文件：**

- `scripts/validate-binance-okx-scenario-semantics.test.mjs`

**测试覆盖：**

1. 恰好加载 191 个场景及 191 份 actual。
2. 审计器不得导入 Java Oracle、原报告计算函数或以 `expected.json` 作为公式输入。
3. 现货买单手续费按 Binance“收到的 base 资产扣费”复算；卖单按 quote 资产扣费。
4. 现货 `quoteOrderQty`、数量步长、价格 tick、最小名义价值和余额约束可独立复算。
5. USDT 线性永续多空未实现/已实现盈亏、加权均价、开平仓手续费、资金费方向可独立复算。
6. Binance Hedge 模式显式 `reduceOnly` 不允许；OKX long/short 模式平仓天然 reduce-only；场景适配判断明确。
7. 账户、钱包、账本、订单、成交和持仓的关键恒等式逐 checkpoint 校验。
8. 每个场景必须返回 Binance 和 OKX 两份结论、逐规则检查、数值差异、限制和中文操作摘要。
9. 汇总数必须满足 `pass + conditional + fail + not_applicable = 191`（分别对 Binance 和 OKX）。

**先运行并确认失败：**

```powershell
node --test scripts/validate-binance-okx-scenario-semantics.test.mjs
```

## 任务 4：实现十进制定点独立审计器

**新增文件：**

- `scripts/validate-binance-okx-scenario-semantics.mjs`
- `docs/testing/binance-okx-official-scenario-validation.json`（生成文件）

**核心实现：**

- 用 `BigInt` 十进制定点工具解析、乘除、向下取整和 8 位量化，避免 JavaScript 浮点误差。
- 从矩阵动作和 actual checkpoint 读取原始请求及结果，但所有理论值由审计器重算。
- Spot：逐成交复算成交额、手续费资产/金额、净入账、持仓成本、卖出已实现盈亏、钱包/账本流。
- Perpetual：逐成交复算合约名义价值、手续费、加权开仓均价、部分/全部平仓已实现盈亏、反手拆分、标记价未实现盈亏、初始和维持保证金、资金费。
- 订单语义：校验 market/limit/stop/OCO、状态流转、幂等、撤单、`reduceOnly`、one-way/hedge、cross/isolated 和拒绝后无非法副作用。
- 风险语义：区分“场景固定维持保证金率下公式正确”和“真实交易所动态档位尚未注入”；强平/穿仓类给出公式一致性与实盘不可直接外推的双重结论。
- OKX 合约数量按 `contracts × ctVal × ctMult` 计算；报告明确场景采用归一化 `ctVal=1`，真实 BTC-USDT-SWAP 必须由适配器换算。

**生成命令：**

```powershell
node scripts/validate-binance-okx-scenario-semantics.mjs --output docs/testing/binance-okx-official-scenario-validation.json
```

## 任务 5：把官方复核结果并入完整 191 场景报告

**修改文件：**

- `scripts/generate-readable-trading-scenario-report.test.mjs`
- `scripts/generate-readable-trading-scenario-report.mjs`
- `docs/testing/spot-perp-readable-test-report.artifact.json`（重新生成）
- `docs/testing/spot-perp-readable-test-report.html`（重新打包）

**报告新增内容：**

1. 顶部技术摘要：当前证据新鲜度、Binance/OKX 通过/有条件/失败数、实质差异数。
2. 关键发现图：按交易所和结论统计的柱状图；精确数量同时写入正文。
3. 官方规则与项目参数边界表：列出公式、动态参数、映射和官方链接。
4. 191 个场景逐一增加：
   - 大白话操作步骤；
   - 项目 actual 完整结果；
   - Binance 独立算法结果和逐项差异；
   - OKX 独立算法结果和逐项差异；
   - 两边最终是否相等、是否仅有配置条件、失败原因和建议。
5. 限制与稳健性：费率等级、风险档位、真实合约面值、市场深度/部分成交、标记价来源、真实交易所不确定因素。
6. 所有长文本列采用内容自适应和换行，不依赖横向隐藏；保留完整原报告，不删除现有明细。

**测试要求：**

```powershell
node --test scripts/generate-readable-trading-scenario-report.test.mjs
```

## 任务 6：生成、打包并验证 HTML

**步骤：**

1. 生成 canonical artifact JSON。
2. 使用工作区提供的 portable artifact 打包器生成单文件 HTML。
3. 执行结构验证，确认所有数据表、图表、来源链接和 191 个场景均在 HTML 中。
4. 检查表格单元格可换行、无 `nowrap`/固定裁切；若打包器只提供 structural-only 收据，在最终结果中如实披露。

**命令：**

```powershell
node scripts/generate-readable-trading-scenario-report.mjs --output docs/testing/spot-perp-readable-test-report.artifact.json
node <workspace-dependency-root>/deliver_portable_artifact.mjs docs/testing/spot-perp-readable-test-report.artifact.json docs/testing/spot-perp-readable-test-report.html
```

## 任务 7：完成前总验证与交付

**必须通过：**

- 独立官方审计器测试。
- 报告生成器测试。
- 当前 Oracle 单元测试与 191 场景矩阵测试，或明确记录无法运行的外部依赖阻塞。
- `verify:architecture`。
- 产物 JSON 可解析、HTML 存在且包含 191 个唯一 `caseId`、Binance/OKX 官方链接和每场景双交易所结论。

**最终交付说明：**

- 直接给出 HTML 和 JSON 的绝对路径链接。
- 先报告发现了多少“算法正确”“有条件正确”“不正确”，再解释主要差异。
- 列出全部实际执行的命令和结果，不用“应该通过”等推测性措辞。
- 明确这是一套 DEMO 离线算法复核，不代表真实账户费率、风险档位或实时撮合结果。
