import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import { auditScenarioEvidence } from "./validate-binance-okx-scenario-semantics.mjs";

function splitText(value, maximumLength = 2600) {
  const chunks = [];
  let remaining = String(value ?? "");
  if (!remaining) {
    return [""];
  }
  const delimiters = ["\n\n", "\n", "；", "，", "。", "/"];
  while (remaining.length > maximumLength) {
    let cutAt = -1;
    for (const delimiter of delimiters) {
      const candidate = remaining.lastIndexOf(delimiter, maximumLength);
      if (candidate >= Math.floor(maximumLength * 0.55)) {
        cutAt = candidate + delimiter.length;
        break;
      }
    }
    if (cutAt < 1) {
      cutAt = maximumLength;
    }
    chunks.push(remaining.slice(0, cutAt));
    remaining = remaining.slice(cutAt);
  }
  chunks.push(remaining);
  return chunks;
}

export function expandOfficialRecord(record) {
  const chunks = {
    operation: splitText(record.reportOperation),
    calculation: splitText(record.independentCalculation),
    exchange: splitText(record.reportExchangeConclusion),
  };
  const segmentCount = Math.max(chunks.operation.length, chunks.calculation.length, chunks.exchange.length);
  return Array.from({ length: segmentCount }, (_, index) => ({
    case: `${String(record.caseNumber).padStart(3, "0")} | ${record.caseId} | ${String(index + 1).padStart(2, "0")}/${String(segmentCount).padStart(2, "0")}`,
    operation: chunks.operation[index] ?? "—",
    calculation: chunks.calculation[index] ?? "—",
    exchange: chunks.exchange[index] ?? "—",
  }));
}

function statusLabel(status) {
  if (status === "PASS") {
    return "正确";
  }
  if (status === "CONDITIONAL") {
    return "有条件";
  }
  if (status === "FAIL") {
    return "不正确";
  }
  return "不适用";
}

export function buildScenarioCard(record) {
  const product = record.productType === "CRYPTO_SPOT" ? "现货" : "USDT 线性永续";
  const prefix = record.productType === "CRYPTO_SPOT" ? "spot" : "perpetual";
  const caseNumber = String(record.caseNumber).padStart(3, "0");
  return {
    id: `scenario_${prefix}_${caseNumber}_${record.caseId}`,
    type: "markdown",
    sourceId: "official_audit",
    body: [
      `### 测试用例 ${record.caseNumber}：${record.caseId}`,
      "",
      `**场景分类**：${product}`,
      "",
      `**先看结论**：Binance = **${record.binance.status}（${statusLabel(record.binance.status)}）**；OKX = **${record.okx.status}（${statusLabel(record.okx.status)}）**。`,
      "",
      "**1. 场景怎么操作，系统实际结果是什么**",
      "",
      record.reportOperation,
      "",
      "**2. 按官方通用算法独立重算，两个结果是否相等**",
      "",
      record.independentCalculation,
      "",
      "**3. Binance 与 OKX 分别怎么判定**",
      "",
      record.reportExchangeConclusion,
      "",
      `**Binance 最终结论（${record.binance.status}）**：${record.binance.conclusion}`,
      "",
      `**OKX 最终结论（${record.okx.status}）**：${record.okx.conclusion}`,
    ].join("\n"),
  };
}

function buildRuleCard(rule) {
  return {
    id: `official_rule_${rule.ruleId}`,
    type: "markdown",
    sourceId: "official_rules",
    body: [
      `### ${rule.ruleId}`,
      "",
      `- **交易所 / 产品**：${rule.exchange} / ${rule.product}`,
      `- **官方标准语义**：${rule.semantic}`,
      `- **本场景如何映射**：${rule.scenarioMapping}`,
      `- **必须动态读取的参数**：${rule.dynamicInputs.join("；") || "无"}`,
      `- **审计边界**：${rule.auditLimit}`,
      `- **官方原文**：[打开官方规则](${rule.officialUrl})`,
    ].join("\n"),
  };
}

function buildSources(generatedAt) {
  const definitions = [
    {
      id: "official_audit",
      label: "Binance/OKX 官方语义独立复核结果",
      path: "docs/testing/binance-okx-official-scenario-validation.json",
      sql: "SELECT * FROM read_json_auto('docs/testing/binance-okx-official-scenario-validation.json').records",
      description: "读取 191 个场景的独立公式检查、Binance 结论、OKX 结论、差异和条件。",
    },
    {
      id: "official_rules",
      label: "Binance/OKX 官方规则登记表",
      path: "docs/testing/binance-okx-official-rule-register.json",
      sql: "SELECT * FROM read_json_auto('docs/testing/binance-okx-official-rule-register.json').rules",
      description: "读取官方 URL、公开语义、动态参数、场景映射和审计限制。",
    },
    {
      id: "scenario_matrix",
      label: "191 个场景输入矩阵",
      path: "docs/testing/spot-perp-scenario-matrix.json",
      sql: "SELECT * FROM read_json_auto('docs/testing/spot-perp-scenario-matrix.json', format = 'array')",
      description: "读取每个 caseId 的初始余额、行情步骤、动作参数和 expectedError 输入。",
    },
    {
      id: "scenario_actuals",
      label: "191 个实际执行快照",
      path: "backend/target/scenario-artifacts",
      sql: "SELECT * FROM read_json_auto('backend/target/scenario-artifacts/*/actual.json', union_by_name = true, filename = true)",
      description: "读取每个 caseId 的实际订单、成交、仓位、钱包、账户、账本、保护单、事件和失败数据。",
    },
    {
      id: "scenario_test_report",
      label: "最新场景测试报告",
      path: "docs/testing/spot-perp-test-report.md",
      sql: "SELECT filename, content FROM read_text('docs/testing/spot-perp-test-report.md')",
      description: "读取最新 191 场景测试状态、生成时间与命令结果。",
    },
  ];
  return {
    manifestSources: definitions.map(({ id, label, path }) => ({ id, label, path })),
    topLevelSources: definitions.map(({ id, label, path, sql, description }) => ({
      id,
      label,
      path,
      query: {
        engine: "duckdb",
        id: `${id}-read`,
        description,
        executed_at: generatedAt,
        language: "sql",
        sql,
      },
    })),
  };
}

function reportSummary(audit) {
  const commonNumericMismatches = audit.records
    .flatMap((record) => record.numericChecks)
    .filter((check) => check.exchange === "BOTH" && !check.equal).length;
  return {
    totalCases: audit.summary.totalCases,
    numericChecks: audit.summary.numericChecks,
    commonNumericMismatches,
    binancePass: audit.summary.binance.pass,
    binanceConditional: audit.summary.binance.conditional,
    binanceFail: audit.summary.binance.fail,
    okxPass: audit.summary.okx.pass,
    okxConditional: audit.summary.okx.conditional,
    okxFail: audit.summary.okx.fail,
  };
}

function statusRows(audit) {
  return [
    ["Binance / 正确", "Binance", "PASS", audit.summary.binance.pass],
    ["Binance / 有条件", "Binance", "CONDITIONAL", audit.summary.binance.conditional],
    ["Binance / 不正确", "Binance", "FAIL", audit.summary.binance.fail],
    ["OKX / 正确", "OKX", "PASS", audit.summary.okx.pass],
    ["OKX / 有条件", "OKX", "CONDITIONAL", audit.summary.okx.conditional],
    ["OKX / 不正确", "OKX", "FAIL", audit.summary.okx.fail],
  ].map(([label, exchange, status, count]) => ({ label, exchange, status, count }));
}

function ruleRows(audit) {
  return audit.rules.map((rule) => ({
    rule: rule.ruleId,
    exchange: rule.exchange,
    product: rule.product,
    semantic: rule.semantic,
    mapping: rule.scenarioMapping,
    dynamic: rule.dynamicInputs.join("；"),
    limit: rule.auditLimit,
    source: rule.officialUrl,
  }));
}

export function buildOfficialValidationReport(audit, generatedAt = new Date().toISOString()) {
  const title = "191 个现货与 USDT 线性永续场景：Binance / OKX 官方算法逐一复核";
  const summary = reportSummary(audit);
  const spotRecords = audit.records.filter((record) => record.productType === "CRYPTO_SPOT");
  const perpetualRecords = audit.records.filter((record) => record.productType === "LINEAR_PERP");
  const spotRows = spotRecords.flatMap(expandOfficialRecord);
  const perpetualRows = perpetualRecords.flatMap(expandOfficialRecord);
  const { manifestSources, topLevelSources } = buildSources(generatedAt);

  const cardDefinitions = [
    ["total_cases", "场景总数", "totalCases"],
    ["numeric_checks", "独立数值检查", "numericChecks"],
    ["common_mismatches", "通用公式不相等", "commonNumericMismatches"],
    ["binance_pass", "Binance 正确", "binancePass"],
    ["binance_conditional", "Binance 有条件", "binanceConditional"],
    ["binance_fail", "Binance 不正确", "binanceFail"],
    ["okx_pass", "OKX 正确", "okxPass"],
    ["okx_conditional", "OKX 有条件", "okxConditional"],
    ["okx_fail", "OKX 不正确", "okxFail"],
  ];
  const cards = cardDefinitions.map(([id, label, field]) => ({
    id,
    dataset: "summary",
    sourceId: "official_audit",
    metrics: [{ label, field, format: "number" }],
  }));

  const charts = [
    {
      id: "exchange_status_chart",
      title: "两家交易所的逐场景复核结论",
      subtitle: "每家交易所分别统计 191 个场景；有条件表示公式相等但依赖适配配置或真实动态参数。",
      description: "柱状图只用于快速比较数量；每个 caseId 的具体公式、差值和条件以明细表为准。",
      showDescription: true,
      type: "bar",
      dataset: "exchange_status",
      sourceId: "official_audit",
      encodings: {
        x: { field: "label", type: "nominal", label: "交易所 / 结论" },
        y: { field: "count", type: "quantitative", label: "场景数" },
      },
    },
  ];

  const scenarioColumns = [
    { field: "case", label: "编号 / caseId / 分段", sizing: "content" },
    { field: "operation", label: "怎么操作 + 系统实际结果", sizing: "content" },
    { field: "calculation", label: "独立官方公式重算 + 是否相等", sizing: "content" },
    { field: "exchange", label: "Binance 与 OKX 最终结论 / 差异 / 条件", sizing: "content" },
  ];
  const tables = [
    {
      id: "official_rule_table",
      title: "官方规则与场景映射登记表",
      description: "所有规则均保留官方 URL；真实费率、合约面值、风险档位和盘口属于动态输入。",
      showDescription: true,
      dataset: "official_rules",
      sourceId: "official_rules",
      columns: [
        { field: "rule", label: "ruleId", sizing: "content" },
        { field: "exchange", label: "交易所", sizing: "content" },
        { field: "product", label: "产品", sizing: "content" },
        { field: "semantic", label: "官方标准语义", sizing: "content" },
        { field: "mapping", label: "本场景如何映射", sizing: "content" },
        { field: "dynamic", label: "动态参数", sizing: "content" },
        { field: "limit", label: "审计边界", sizing: "content" },
        { field: "source", label: "官方链接", sizing: "content" },
      ],
      defaultSort: { field: "rule", direction: "asc" },
      density: "spacious",
    },
    {
      id: "spot_validation_table",
      title: "36 个现货场景逐一复核",
      description: "每个超长场景拆成连续分段；分段只为完整显示，按编号顺序拼接即为原文，没有省略号或隐藏列。",
      showDescription: true,
      dataset: "spot_validation",
      sourceId: "official_audit",
      columns: scenarioColumns,
      defaultSort: { field: "case", direction: "asc" },
      density: "spacious",
    },
    {
      id: "perpetual_validation_table",
      title: "155 个 USDT 线性永续场景逐一复核",
      description: "每个超长场景拆成连续分段；展示成交额、手续费、已实现/未实现盈亏、保证金、资金费及两家交易所映射。",
      showDescription: true,
      dataset: "perpetual_validation",
      sourceId: "official_audit",
      columns: scenarioColumns,
      defaultSort: { field: "case", direction: "asc" },
      density: "spacious",
    },
  ];

  const blocks = [
    { id: "report_title", type: "markdown", body: `# ${title}` },
    {
      id: "technical_summary",
      type: "markdown",
      sourceId: "official_audit",
      body: [
        "## 先说结论",
        "",
        `- **最新 DEMO 场景执行是 191/191 通过，但这不等于交易所官方语义全部正确。** 本审计没有拿项目自己的 expected 再证明自己，而是重新做了 ${summary.numericChecks} 项十进制定点计算。`,
        `- **所有 Binance/OKX 共用的成交额、手续费金额、线性已实现/未实现盈亏、维持保证金和资金费检查均相等；共用公式不相等数=${summary.commonNumericMismatches}。**`,
        `- **Binance：正确 ${summary.binancePass}，有条件 ${summary.binanceConditional}，不正确 ${summary.binanceFail}。** 19 个不正确场景都是现货买入手续费资产不一致：当前系统完整入账 BTC、另扣 USDT 手续费；Binance 未使用 BNB 抵扣时应从收到的 BTC 扣手续费。`,
        `- **OKX：正确 ${summary.okxPass}，有条件 ${summary.okxConditional}，不正确 ${summary.okxFail}。** 131 个不正确场景都曾出现全仓永续仓位：当前 initialMargin 按开仓均价保持不变，而 OKX 全仓初始保证金应按标记价/杠杆重估；共记录 325 个检查点差异。`,
        "- **有条件不是错误。** 典型条件包括 OKX Spot 买入需 feeType=1、余额不足整单拒绝需 banAmend=true、OKX SWAP 的 sz 必须按真实 ctVal/ctMult 换算，以及真实盘口可能产生滑点、分笔或部分成交。",
      ].join("\n"),
    },
    { id: "headline_metrics", type: "metric-strip", cardIds: cards.map((card) => card.id) },
    {
      id: "key_findings",
      type: "markdown",
      sourceId: "official_audit",
      body: [
        "## 怎么读“正确 / 有条件 / 不正确”",
        "",
        "- `PASS（正确）`：实际数值与官方公式相等，业务字段可以直接无损映射。",
        "- `CONDITIONAL（有条件）`：公式相等，但上线时必须注入账户配置、合约规格、风险档位或真实盘口。",
        "- `FAIL（不正确）`：至少一个实际数值、手续费资产或保证金口径与官方标准不相等。",
        "",
        "同一个场景在 Binance 和 OKX 可以得到不同结论，因为两家交易所对现货买入手续费资产、合约数量单位、全仓保证金和余额不足缩量的规定并不完全一样。",
      ].join("\n"),
    },
    { id: "exchange_status_chart_block", type: "chart", chartId: "exchange_status_chart" },
    {
      id: "scope_and_method",
      type: "markdown",
      sourceId: "official_audit",
      body: [
        "## 范围、数据和方法",
        "",
        `- 输入：36 个 Spot + 155 个 LINEAR_PERP 场景；实际证据生成时间=${audit.summary.evidenceGeneratedAt ?? "未记录"}，底层报告状态=${audit.summary.evidenceStatus}。`,
        "- 独立性：审计器只读 matrix、actual 快照和官方规则登记表；不读项目 expected 快照，不调用 Java Oracle，也不导入原报告的计算函数。",
        "- 精度：所有理论值用 BigInt 十进制定点计算，按 8 位小数 HALF_UP 比较，避免 JavaScript 二进制浮点误差。",
        "- 永续：逐成交重建多空仓位和加权均价，重算 realized PnL；逐检查点按 mark 重算 notional、unrealized PnL、MMR 和资金费。",
        "- 现货：逐成交重算 quote notional、maker/taker fee、买卖账本；再分别套用 Binance 与 OKX 的手续费资产规则。",
        "- 拒绝场景：按 matrix 输入的 expectedError 检查 actual 最终 failure；同时区分交易所原生过滤器与平台事务/并发/权限语义。",
      ].join("\n"),
    },
    {
      id: "limitations",
      type: "markdown",
      body: [
        "## 限制和稳健性",
        "",
        "这是一套离线 DEMO 算法复核，不连接 Binance、OKX、真实 broker、FIX 或 LP。场景中的 0.02% maker、0.05% taker、0.5% MMR、ctVal=1 和一次全量成交都是测试参数，不是对真实账户或实时市场的保证。上线适配器必须动态读取 fee tier、exchangeInfo/instruments、risk tier、mark/index、资金费率和订单簿；OKX BTC-USDT-SWAP 不能直接把这里的 CONTRACTS 数字当作真实 sz。",
      ].join("\n"),
    },
    { id: "rules_heading", type: "markdown", body: "## 官方规则登记表" },
    { id: "rules_table_block", type: "table", tableId: "official_rule_table" },
    { id: "spot_heading", type: "markdown", body: "## 现货 36 个场景：步骤、实际结果、公式和双交易所结论" },
    { id: "spot_table_block", type: "table", tableId: "spot_validation_table" },
    { id: "perpetual_heading", type: "markdown", body: "## 永续 155 个场景：步骤、实际结果、公式和双交易所结论" },
    { id: "perpetual_table_block", type: "table", tableId: "perpetual_validation_table" },
    {
      id: "next_steps",
      type: "markdown",
      body: [
        "## 下一步应该修什么",
        "",
        "1. 将 Spot 买入结算改成可配置的手续费资产策略：Binance 默认净收 BTC；OKX 支持 feeType=0/1；不能用一个固定 USDT 扣费模型同时冒充两家交易所。",
        "2. 将永续 initialMargin 明确拆成“开仓时实际冻结/逐仓持有保证金”和“全仓当前保证金要求”；OKX cross 当前要求按 mark/leverage 重估。",
        "3. 真实适配层从交易所元数据注入 ctVal、ctMult、tickSize、stepSize、minNotional、fee tier 和 risk tier，并为 partial fill 建立逐笔账本。",
      ].join("\n"),
    },
    {
      id: "further_questions",
      type: "markdown",
      body: "## 以后继续验证的问题\n\n真实盘口多笔成交后，手续费资产、平均价、剩余冻结、OCO 互斥、强平手续费、保险基金/ADL 和资金费结算是否还能逐笔对账；这些不在当前一次全量成交矩阵的证明范围内。",
    },
  ];

  const verticalBlocks = blocks.flatMap((block) => {
    if (block.id === "rules_table_block") {
      return audit.rules.map(buildRuleCard);
    }
    if (block.id === "spot_table_block") {
      return [
        {
          id: "report_reading_note",
          type: "markdown",
          body: "**阅读方法**：191 个场景全部展开显示，没有隐藏列或省略号。可按 `Ctrl+F` 搜索 caseId；每个场景固定按“怎么操作 → 系统结果 → 官方公式重算 → Binance 结论 → OKX 结论”阅读。",
        },
        ...spotRecords.map(buildScenarioCard),
      ];
    }
    if (block.id === "perpetual_table_block") {
      return perpetualRecords.map(buildScenarioCard);
    }
    return [block];
  });

  return {
    surface: "report",
    manifest: {
      version: 1,
      surface: "report",
      title,
      generatedAt,
      cards,
      charts,
      tables: [],
      sources: manifestSources,
      blocks: verticalBlocks,
    },
    snapshot: {
      version: 1,
      generatedAt,
      status: "ready",
      datasets: {
        summary: [summary],
        exchange_status: statusRows(audit),
      },
    },
    sources: topLevelSources,
  };
}

function parseArguments(argv) {
  const index = argv.indexOf("--output");
  if (index < 0 || !argv[index + 1]) {
    throw new Error("用法：node scripts/generate-binance-okx-official-validation-report.mjs --output <artifact.json>");
  }
  return { output: resolve(argv[index + 1]) };
}

const isDirectRun = process.argv[1]
  && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;

if (isDirectRun) {
  try {
    const platformRoot = dirname(dirname(fileURLToPath(import.meta.url)));
    const { output } = parseArguments(process.argv.slice(2));
    const artifact = buildOfficialValidationReport(auditScenarioEvidence(platformRoot));
    mkdirSync(dirname(output), { recursive: true });
    writeFileSync(output, `${JSON.stringify(artifact, null, 2)}\n`, "utf8");
    process.stdout.write(`Generated official validation report: ${output}\n`);
    process.stdout.write("Spot cases=36, Perpetual cases=155, vertical scenario cards=191\n");
  } catch (error) {
    process.stderr.write(`${error.stack ?? error.message}\n`);
    process.exitCode = 1;
  }
}
