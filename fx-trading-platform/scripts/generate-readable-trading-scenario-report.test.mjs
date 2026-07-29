import assert from "node:assert/strict";
import { readFileSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import { test } from "node:test";
import { isDeepStrictEqual } from "node:util";

import {
  buildCaseRecord,
  buildReportArtifact,
  classifyScenario,
  loadScenarioEvidence,
} from "./generate-readable-trading-scenario-report.mjs";

const platformRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const generatedAt = "2026-07-17T08:00:00.000Z";

function parseAuditCase(row) {
  const [, caseId, segment] = row.case.split(" · ");
  const [segmentIndex, segmentCount] = segment.split("/").map(Number);
  return { caseId, segmentIndex, segmentCount };
}

test("loads exactly the 191 verified scenarios with the required product and outcome coverage", () => {
  const evidence = loadScenarioEvidence(platformRoot);

  assert.equal(evidence.scenarios.length, 191);
  assert.equal(evidence.records.length, 191);
  assert.equal(evidence.expectedByCaseId.size, 191);
  assert.equal(evidence.expectedOptionsByCaseId.size, 191);
  assert.equal(new Set(evidence.records.map((row) => row.caseId)).size, 191);
  assert.equal(evidence.records.filter((row) => row.productType === "CRYPTO_SPOT").length, 36);
  assert.equal(evidence.records.filter((row) => row.productType === "LINEAR_PERP").length, 155);
  assert.equal(evidence.records.filter((row) => row.outcome === "EXPECTED_SUCCESS").length, 163);
  assert.equal(evidence.records.filter((row) => row.outcome === "EXPECTED_REJECTION").length, 28);
  assert.equal(evidence.summary.testGeneratedAt, "2026-07-17T07:10:10.4475993Z");
});

test("preserves both valid expected winner branches for the four race scenarios and selects the actual match", () => {
  const evidence = loadScenarioEvidence(platformRoot);
  const multiOptionCases = [
    "PERP_CLOSE_VS_BATCH_RACE",
    "PERP_CLOSE_VS_LIQUIDATION_RACE",
    "PERP_CLOSE_VS_PROTECTION_RACE",
    "SPOT_OCO_DUAL_TRIGGER_RACE",
  ];

  assert.equal(
    [...evidence.expectedOptionsByCaseId.values()].filter((options) => options.length === 1).length,
    187,
  );
  for (const caseId of multiOptionCases) {
    const options = evidence.expectedOptionsByCaseId.get(caseId);
    const actual = evidence.actualByCaseId.get(caseId);
    assert.equal(options.length, 2, caseId);
    assert.equal(options.filter((option) => isDeepStrictEqual(option.checkpoints, actual.checkpoints)).length, 1, caseId);
    assert.match(evidence.records.find((row) => row.caseId === caseId).comparisonResult, /2 个合法 expected 分支/);
    assert.match(evidence.records.find((row) => row.caseId === caseId).algorithmAndExpectedData, /expected 合法分支 1\/2/);
    assert.match(evidence.records.find((row) => row.caseId === caseId).algorithmAndExpectedData, /expected 合法分支 2\/2/);
  }
});

test("keeps every matrix action aligned with one cumulative actual checkpoint and unique final evidence keys", () => {
  const evidence = loadScenarioEvidence(platformRoot);

  assert.equal(evidence.actualByCaseId.size, 191);
  for (const scenario of evidence.scenarios) {
    const actual = evidence.actualByCaseId.get(scenario.caseId);
    const expected = evidence.expectedByCaseId.get(scenario.caseId);
    assert.equal(actual.caseId, scenario.caseId);
    assert.equal(expected.caseId, scenario.caseId);
    assert.equal(actual.checkpoints.length, scenario.actions.length, scenario.caseId);
    assert.deepEqual(actual.checkpoints, expected.checkpoints, `${scenario.caseId}.actual-vs-expected`);

    actual.checkpoints.forEach((checkpoint, index) => {
      assert.equal(checkpoint.actionIndex, index + 1, scenario.caseId);
      assert.equal(checkpoint.actionId, scenario.actions[index].parameters.actionId, scenario.caseId);
    });

    const snapshot = actual.checkpoints.at(-1).snapshot;
    const tradeRefs = snapshot.trades.map((trade) => trade.ref);
    const orderRefs = snapshot.orders.map((order) => order.ref);
    const ledgerSequences = snapshot.ledger.map((entry) => entry.sequence);
    assert.equal(new Set(tradeRefs).size, tradeRefs.length, `${scenario.caseId}.trades`);
    assert.equal(new Set(orderRefs).size, orderRefs.length, `${scenario.caseId}.orders`);
    assert.equal(new Set(ledgerSequences).size, ledgerSequences.length, `${scenario.caseId}.ledger`);
    assert.deepEqual([...ledgerSequences].sort((left, right) => left - right), ledgerSequences, scenario.caseId);

    if (scenario.expectedError) {
      assert.equal(actual.checkpoints.at(-1).failure?.code, scenario.expectedError, scenario.caseId);
    }
  }
});

test("assigns every case once to one of nine non-empty business categories", () => {
  const evidence = loadScenarioEvidence(platformRoot);

  assert.equal(evidence.categories.length, 9);
  assert.deepEqual(
    evidence.categories.map((category) => category.id),
    Array.from({ length: 9 }, (_, index) => `category_${index + 1}`),
  );
  assert.ok(evidence.categories.every((category) => category.records.length > 0));
  assert.equal(evidence.categories.flatMap((category) => category.records).length, 191);
  assert.equal(
    new Set(evidence.categories.flatMap((category) => category.records.map((row) => row.caseId))).size,
    191,
  );

  for (const scenario of evidence.scenarios) {
    const row = evidence.records.find((record) => record.caseId === scenario.caseId);
    assert.equal(row.categoryId, classifyScenario(scenario), scenario.caseId);
  }

  const byCaseId = new Map(evidence.records.map((row) => [row.caseId, row]));
  assert.equal(byCaseId.get("PERP_STOP_MARKET_CROSS").categoryId, "category_5");
  for (const caseId of [
    "PERP_TP_LIMIT",
    "PERP_SL_LIMIT",
    "PERP_PROTECTION_LIMIT_10",
    "PERP_PROTECTION_LIMIT_11_REJECT",
  ]) {
    assert.equal(byCaseId.get(caseId).categoryId, "category_8", caseId);
  }
  for (const caseId of [
    "PERP_REDUCE_ONLY_BELOW",
    "PERP_REDUCE_ONLY_EQUAL",
    "PERP_REDUCE_ONLY_ABOVE_REJECT",
  ]) {
    assert.equal(byCaseId.get(caseId).categoryId, "category_4", caseId);
  }
  for (const caseId of [
    "PERP_INCOMPLETE_MARKET",
    "PERP_LEDGER_ROLLBACK",
    "PERP_LOCK_WAIT_EXPIRY",
    "PERP_TRADE_ROLLBACK",
    "PERP_SAME_POSITION_DOUBLE_CLOSE",
  ]) {
    assert.equal(byCaseId.get(caseId).categoryId, "category_9", caseId);
  }
});

test("turns every case into a complete seven-field reader-facing record without invented ETH trades", () => {
  const evidence = loadScenarioEvidence(platformRoot);
  const readerFields = [
    "caseNumber",
    "scenario",
    "conditions",
    "tradeProcess",
    "pnlSummary",
    "algorithm",
    "result",
  ];

  for (const row of evidence.records) {
    for (const field of readerFields) {
      assert.notEqual(row[field], undefined, `${row.caseId}.${field}`);
      assert.notEqual(String(row[field]).trim(), "", `${row.caseId}.${field}`);
    }
    assert.doesNotMatch(`${row.conditions}\n${row.tradeProcess}`, /ETHUSDT|ETH-PERP/i, row.caseId);
  }

  const matrixScenario = evidence.scenarios.find((scenario) => scenario.caseId === "SPOT_SELL_PROFIT");
  const actual = evidence.actualByCaseId.get("SPOT_SELL_PROFIT");
  const expected = evidence.expectedByCaseId.get("SPOT_SELL_PROFIT");
  const directRecord = buildCaseRecord(matrixScenario, actual, expected);
  assert.equal(directRecord.caseId, "SPOT_SELL_PROFIT");
  assert.match(directRecord.scenario, /现货/);
});

test("builds a complete plain-language audit record for all 191 actual and expected scenario pairs", () => {
  const evidence = loadScenarioEvidence(platformRoot);
  const detailedFields = [
    "scenarioAndInitialData",
    "detailedSteps",
    "actualFinalData",
    "algorithmAndExpectedData",
    "comparisonResult",
  ];

  for (const row of evidence.records) {
    for (const field of detailedFields) {
      assert.ok(String(row[field] ?? "").trim(), `${row.caseId}.${field}`);
    }
    assert.equal(row.comparisonPassed, true, row.caseId);
    assert.match(row.comparisonResult, /总比对：actual 与 expected 完全相等/);
    assert.match(row.actualFinalData, /订单（\d+）/);
    assert.match(row.actualFinalData, /成交（\d+）/);
    assert.match(row.actualFinalData, /仓位（\d+）/);
    assert.match(row.actualFinalData, /钱包（\d+）/);
    assert.match(row.actualFinalData, /账户：/);
    assert.match(row.actualFinalData, /账本（\d+）/);
    assert.match(row.actualFinalData, /保护单（\d+）/);
    assert.match(row.actualFinalData, /事件（\d+）/);
    assert.match(row.algorithmAndExpectedData, /算法计算轨迹/);
    assert.doesNotMatch(
      `${row.scenarioAndInitialData}\n${row.detailedSteps}\n${row.actualFinalData}\n${row.algorithmAndExpectedData}`,
      /ETHUSDT|ETH-PERP/i,
      row.caseId,
    );
  }
});

test("shows exact step data, final data, algorithm output and equality for representative scenarios", () => {
  const evidence = loadScenarioEvidence(platformRoot);
  const byCaseId = new Map(evidence.records.map((row) => [row.caseId, row]));

  const spot = byCaseId.get("SPOT_SELL_PROFIT");
  assert.match(spot.scenarioAndInitialData, /初始余额：USDT=100000\.00000000，BTC=10\.00000000/);
  assert.match(spot.scenarioAndInitialData, /行情序号 0[^。]+last=100\.00000000/);
  assert.match(spot.detailedSteps, /步骤 1（买入）/);
  assert.match(spot.detailedSteps, /行情序号 1[^。]+ask=110\.50000000/);
  assert.match(spot.detailedSteps, /完整请求参数/);
  assert.match(spot.detailedSteps, /成交价 110\.51105000 USDT/);
  assert.match(spot.detailedSteps, /步骤 2（卖出）/);
  assert.match(spot.detailedSteps, /成交价 119\.48805000 USDT/);
  assert.match(spot.actualFinalData, /订单（2）/);
  assert.match(spot.actualFinalData, /账本（6）/);
  assert.match(spot.actualFinalData, /已实现盈亏=8\.86197280/);
  assert.match(spot.algorithmAndExpectedData, /quoteBudget=200/);
  assert.match(spot.algorithmAndExpectedData, /realized=8\.86197280/);
  assert.match(spot.comparisonResult, /第 1 步：11\/11 项相等/);
  assert.match(spot.comparisonResult, /逐项范围：动作序号、actionId、订单、成交、仓位、钱包、账户、账本、保护单、事件、失败/);

  const perp = byCaseId.get("PERP_CLOSE_PROFIT");
  assert.match(perp.detailedSteps, /多仓开仓\/加仓 1\.00000000 BTC/);
  assert.match(perp.detailedSteps, /多仓平仓 1\.00000000 BTC/);
  assert.match(perp.actualFinalData, /余额=100008\.86200044/);
  assert.match(perp.algorithmAndExpectedData, /realized=8\.97700000, fee=0\.05974403/);

  const reduceReject = byCaseId.get("PERP_REDUCE_ONLY_ABOVE_REJECT");
  assert.match(reduceReject.detailedSteps, /请求数量=2/);
  assert.match(reduceReject.detailedSteps, /reduceOnly=true/);
  assert.match(reduceReject.detailedSteps, /REDUCE_ONLY_EXCEEDS_POSITION/);
  assert.match(reduceReject.algorithmAndExpectedData, /rejected from input\/state: REDUCE_ONLY_EXCEEDS_POSITION/);

  const precisionReject = byCaseId.get("SPOT_PRICE_PRECISION_REJECT");
  assert.match(precisionReject.detailedSteps, /委托价=100\.123456789/);

  const oco = byCaseId.get("SPOT_OCO_STOP_WIN");
  assert.match(oco.actualFinalData, /类型=LIMIT[^}]+状态=CANCELED/);
  assert.match(oco.actualFinalData, /类型=STOP_MARKET[^}]+状态=FILLED/);

  const netting = byCaseId.get("PERP_ONE_WAY_NETTING");
  assert.match(netting.detailedSteps, /先平多仓 1\.00000000 BTC，再开空仓 1\.00000000 BTC/);

  const funding = byCaseId.get("PERP_FUNDING_ISOLATED_LIQUIDATION");
  assert.match(funding.actualFinalData, /引用类型=POSITION/);
  assert.match(funding.detailedSteps, /强平结算重分类 -1005\.10050000 USDT（非新增资金费现金流）/);

  const provider = byCaseId.get("PERP_PROVIDER_SWITCH_WITH_GAP");
  assert.match(provider.detailedSteps, /source=binance/);
  assert.match(provider.detailedSteps, /source=okx/);
  assert.match(provider.algorithmAndExpectedData, /symbol binding/);
});

test("deduplicates the matched expected snapshot by an explicit actual-data reference while preserving race alternatives", () => {
  const evidence = loadScenarioEvidence(platformRoot);
  const ordinary = evidence.records.find((row) => row.caseId === "SPOT_SELL_PROFIT");
  const race = evidence.records.find((row) => row.caseId === "SPOT_OCO_DUAL_TRIGGER_RACE");

  assert.match(
    ordinary.algorithmAndExpectedData,
    /actual 命中[\s\S]+完整 checkpoint 数据与“最终 actual 检查点完整数据”逐字段相同/,
  );
  assert.doesNotMatch(ordinary.algorithmAndExpectedData, /本次未命中但属于合法竞态结果/);
  assert.match(race.algorithmAndExpectedData, /actual 命中/);
  assert.match(race.algorithmAndExpectedData, /本次未命中但属于合法竞态结果/);
  assert.match(race.algorithmAndExpectedData, /未命中分支的完整 expected 最终检查点/);
  assert.match(race.algorithmAndExpectedData, /订单（\d+）/);
});

test("explains no-fill replay, zero-funding and safe-liquidation steps in business language", () => {
  const evidence = loadScenarioEvidence(platformRoot);
  const byCaseId = new Map(evidence.records.map((row) => [row.caseId, row]));

  assert.match(byCaseId.get("SPOT_CLIENT_ORDER_REPLAY").tradeProcess, /幂等重放/);
  assert.match(byCaseId.get("PERP_CLIENT_ORDER_REPLAY").tradeProcess, /幂等重放/);
  assert.match(byCaseId.get("PERP_FUNDING_ZERO_LONG").tradeProcess, /资金费率为 0/);
  assert.match(byCaseId.get("PERP_FUNDING_FULL_CLOSE_BEFORE_SETTLEMENT").tradeProcess, /已无未平仓仓位/);
  assert.match(byCaseId.get("PERP_LIQUIDATION_SAFE").tradeProcess, /未达到强平条件/);
  for (const row of evidence.records) {
    assert.doesNotMatch(row.tradeProcess, /该步无新增成交；实际快照包含/, row.caseId);
  }
});

test("preserves the exact actual prices, fees and PnL for the two representative cases", () => {
  const evidence = loadScenarioEvidence(platformRoot);
  const spot = evidence.records.find((row) => row.caseId === "SPOT_SELL_PROFIT");
  const perp = evidence.records.find((row) => row.caseId === "PERP_CLOSE_PROFIT");

  assert.match(spot.tradeProcess, /110\.51105000/);
  assert.match(spot.tradeProcess, /119\.48805000/);
  assert.match(spot.tradeProcess, /0\.00090485 BTC/);
  assert.match(spot.tradeProcess, /0\.05974403 USDT/);
  assert.match(spot.pnlSummary, /8\.86197280 USDT/);

  assert.match(perp.tradeProcess, /110\.51105000/);
  assert.match(perp.tradeProcess, /119\.48805000/);
  assert.match(perp.tradeProcess, /0\.05525553 USDT/);
  assert.match(perp.tradeProcess, /0\.05974403 USDT/);
  assert.match(perp.pnlSummary, /8\.97700000 USDT/);
  assert.match(perp.pnlSummary, /8\.86200044 USDT/);
});

test("uses cumulative trade realized PnL for reversal and one-way netting instead of the reset final position", () => {
  const evidence = loadScenarioEvidence(platformRoot);
  const expected = new Map([
    ["PERP_LONG_TO_SHORT_REVERSAL", "-11.01700000 USDT"],
    ["PERP_SHORT_TO_LONG_REVERSAL", "-11.02300000 USDT"],
    ["PERP_ONE_WAY_NETTING", "-11.02100000 USDT"],
  ]);

  for (const [caseId, realizedPnl] of expected) {
    const row = evidence.records.find((record) => record.caseId === caseId);
    assert.match(row.pnlSummary, new RegExp(realizedPnl.replace(".", "\\.")), caseId);
  }
});

test("uses FUNDING_SETTLEMENT cash flow without double-counting position funding reclassification at liquidation", () => {
  const evidence = loadScenarioEvidence(platformRoot);
  const cross = evidence.records.find((row) => row.caseId === "PERP_FUNDING_CROSS_LIQUIDATION");
  const isolated = evidence.records.find((row) => row.caseId === "PERP_FUNDING_ISOLATED_LIQUIDATION");

  assert.match(cross.pnlSummary, /资金费结算现金流 -2002\.00000000 USDT/);
  assert.match(isolated.pnlSummary, /资金费结算现金流 -2002\.00000000 USDT/);
  assert.doesNotMatch(isolated.pnlSummary, /-3007\.10050000/);
  assert.match(isolated.pnlSummary, /穿仓差额 1153\.61902750 USDT/);
  assert.match(isolated.tradeProcess, /强平结算重分类 -1005\.10050000 USDT（非新增资金费现金流）/);
  assert.doesNotMatch(isolated.tradeProcess, /资金费变动 -1005\.10050000 USDT/);
});

test("distinguishes a triggered opening STOP_MARKET from a triggered reduce-only protection close", () => {
  const evidence = loadScenarioEvidence(platformRoot);
  const openingStop = evidence.records.find((row) => row.caseId === "PERP_STOP_MARKET_EXACT");
  const takeProfit = evidence.records.find((row) => row.caseId === "PERP_TP_MARKET");

  assert.match(openingStop.tradeProcess, /第2步[^。]+多仓开仓\/加仓/);
  assert.doesNotMatch(openingStop.tradeProcess, /第2步[^。]+平仓/);
  assert.match(takeProfit.tradeProcess, /第3步[^。]+多仓平仓/);
  assert.match(takeProfit.tradeProcess, /止盈已成交/);
});

test("describes per-action quantity units and reduceOnly transitions instead of misleading scenario defaults", () => {
  const evidence = loadScenarioEvidence(platformRoot);
  const spot = evidence.records.find((row) => row.caseId === "SPOT_SELL_PROFIT");
  const perp = evidence.records.find((row) => row.caseId === "PERP_CLOSE_PROFIT");
  const closeAll = evidence.records.find((row) => row.caseId === "PERP_CLOSE_ALL_SUCCESS");

  assert.match(spot.conditions, /动作数量单位：计价币金额 → 基础币数量/);
  assert.match(perp.conditions, /动作 reduceOnly：false → true/);
  assert.match(closeAll.conditions, /动作 reduceOnly：false → true/);
  assert.match(
    evidence.records.find((row) => row.caseId === "PERP_REDUCE_ONLY_ABOVE_REJECT").conditions,
    /动作 reduceOnly：false → true/,
  );
});

test("shows the exact rejected request parameters that caused validation to fail", () => {
  const evidence = loadScenarioEvidence(platformRoot);
  const byCaseId = new Map(evidence.records.map((row) => [row.caseId, row]));

  assert.match(byCaseId.get("SPOT_MIN_NOTIONAL_REJECT").tradeProcess, /请求数量 0\.0001 BTC（基础币数量）/);
  assert.match(byCaseId.get("SPOT_MIN_NOTIONAL_REJECT").tradeProcess, /委托价 100/);
  assert.match(byCaseId.get("SPOT_PRICE_PRECISION_REJECT").tradeProcess, /委托价 100\.123456789/);
  assert.match(byCaseId.get("PERP_REDUCE_ONLY_ABOVE_REJECT").tradeProcess, /请求数量 2（合约张数）/);
  assert.match(byCaseId.get("PERP_REDUCE_ONLY_ABOVE_REJECT").tradeProcess, /reduceOnly true/);
  assert.match(byCaseId.get("PERP_LEVERAGE_OVER_MAX_REJECT").tradeProcess, /请求杠杆 101x/);
  assert.match(byCaseId.get("PERP_LEVERAGE_DOWN_INSUFFICIENT_REJECT").tradeProcess, /请求杠杆 1x/);
  assert.match(byCaseId.get("PERP_ISOLATED_MARGIN_REDUCE_UNSAFE_REJECT").tradeProcess, /保证金调整 -100000 USDT/);
});

test("shows stop trigger prices and trigger price types while orders are waiting", () => {
  const evidence = loadScenarioEvidence(platformRoot);
  const spot = evidence.records.find((row) => row.caseId === "SPOT_STOP_MARKET_CROSS");
  const perp = evidence.records.find((row) => row.caseId === "PERP_STOP_MARKET_CROSS");

  assert.match(spot.tradeProcess, /第1步[^。]+触发价 105/);
  assert.match(spot.tradeProcess, /第1步[^。]+LAST_PRICE（最新成交价）/);
  assert.match(perp.tradeProcess, /第1步[^。]+触发价 105/);
  assert.match(perp.tradeProcess, /第1步[^。]+MARK_PRICE（标记价格）/);
});

test("reports the canceled sibling leg after either OCO leg wins", () => {
  const evidence = loadScenarioEvidence(platformRoot);
  const stopWins = evidence.records.find((row) => row.caseId === "SPOT_OCO_STOP_WIN");
  const limitWins = evidence.records.find((row) => row.caseId === "SPOT_OCO_LIMIT_WIN");

  assert.match(stopWins.tradeProcess, /关联订单：LIMIT 已撤销/);
  assert.match(limitWins.tradeProcess, /关联订单：STOP_MARKET 已撤销/);
});

test("keeps actual financial effects from legal setup or race winners in rejected scenarios", () => {
  const evidence = loadScenarioEvidence(platformRoot);
  const spotRace = evidence.records.find((row) => row.caseId === "SPOT_BALANCE_RACE");
  const doubleClose = evidence.records.find((row) => row.caseId === "PERP_SAME_POSITION_DOUBLE_CLOSE");
  const reduceReject = evidence.records.find((row) => row.caseId === "PERP_REDUCE_ONLY_ABOVE_REJECT");

  assert.match(spotRace.pnlSummary, /实际资金影响（仅来自场景预置状态、已成功前置步骤或并发合法赢家）/);
  assert.match(spotRace.pnlSummary, /未实现盈亏 -33\.43246507 USDT/);
  assert.match(spotRace.pnlSummary, /最终钱包总额 BTC 69\.66565225，USDT 4000\.00231022/);
  assert.match(spotRace.pnlSummary, /相对初始变化 BTC \+59\.66565225，USDT -5999\.99768978/);
  assert.match(doubleClose.pnlSummary, /交易已实现盈亏 -1\.02000000 USDT/);
  assert.match(doubleClose.pnlSummary, /最终余额 99998\.87999994 USDT；最终权益 99998\.87999994 USDT/);
  assert.match(reduceReject.pnlSummary, /未实现盈亏 -0\.41005000 USDT/);
  assert.match(reduceReject.pnlSummary, /实际交易手续费/);
  assert.match(reduceReject.pnlSummary, /未伪造被拒动作盈亏/);

  const leverageReject = evidence.records.find((row) => row.caseId === "PERP_LEVERAGE_DOWN_INSUFFICIENT_REJECT");
  assert.match(leverageReject.pnlSummary, /未实现盈亏 100\.00000000 USDT/);
  assert.match(leverageReject.pnlSummary, /最终余额 50000\.00000000 USDT；最终权益 50100\.00000000 USDT；占用保证金 10000\.00000000 USDT/);
});

test("explains one-way netting as closing the old side before opening the remainder", () => {
  const evidence = loadScenarioEvidence(platformRoot);
  const row = evidence.records.find((record) => record.caseId === "PERP_ONE_WAY_NETTING");

  assert.match(row.tradeProcess, /先平多仓 1\.00000000 BTC，再开空仓 1\.00000000 BTC/);
  assert.match(row.algorithm, /单向净持仓/);
  assert.match(row.algorithm, /先抵扣原方向仓位/);
});

test("cross-checks each expected rejection against checkpoint.failure and never fabricates rejected-case profit", () => {
  const evidence = loadScenarioEvidence(platformRoot);
  const rejected = evidence.records.filter((row) => row.outcome === "EXPECTED_REJECTION");

  assert.equal(rejected.length, 28);
  for (const row of rejected) {
    const expected = evidence.scenarios.find((scenario) => scenario.caseId === row.caseId).expectedError;
    const failures = evidence.actualByCaseId
      .get(row.caseId)
      .checkpoints.map((checkpoint) => checkpoint.failure)
      .filter(Boolean);
    assert.ok(failures.some((failure) => failure.code === expected), row.caseId);
    assert.match(row.pnlSummary, new RegExp(expected));
    assert.match(row.pnlSummary, /未伪造被拒动作盈亏/);
    assert.match(row.result, /按预期拒绝.*测试成功/);
  }
});

test("explains concurrent expected rejections as one legal winner without claiming whole-scenario zero mutation", () => {
  const evidence = loadScenarioEvidence(platformRoot);

  for (const caseId of ["SPOT_BALANCE_RACE", "PERP_SAME_POSITION_DOUBLE_CLOSE"]) {
    const row = evidence.records.find((record) => record.caseId === caseId);
    assert.match(row.pnlSummary, /并发步骤新增 1 笔合法赢家成交/);
    assert.match(row.pnlSummary, /拒绝分支未产生额外成交/);
    assert.doesNotMatch(row.pnlSummary, /整场零变更/);
    assert.match(row.algorithm, /只允许一个合法赢家/);
    assert.doesNotMatch(row.algorithm, /前置校验/);
  }
});

test("describes injected execution failures as atomic transaction rollback rather than a generic pre-check", () => {
  const evidence = loadScenarioEvidence(platformRoot);
  for (const caseId of ["PERP_LEDGER_ROLLBACK", "PERP_TRADE_ROLLBACK"]) {
    const row = evidence.records.find((record) => record.caseId === caseId);
    assert.match(row.algorithm, /事务整体回滚/);
    assert.match(row.algorithm, /订单、成交、资金和账本/);
  }
});

test("explains combined funding-triggered liquidation and provider-switch algorithms", () => {
  const evidence = loadScenarioEvidence(platformRoot);
  for (const caseId of ["PERP_FUNDING_CROSS_LIQUIDATION", "PERP_FUNDING_ISOLATED_LIQUIDATION"]) {
    const row = evidence.records.find((record) => record.caseId === caseId);
    assert.match(row.algorithm, /资金费扣款后/);
    assert.match(row.algorithm, /维护保证金/);
    assert.match(row.algorithm, /强平/);
  }

  const providerSwitch = evidence.records.find((row) => row.caseId === "PERP_PROVIDER_SWITCH_WITH_GAP");
  assert.match(providerSwitch.algorithm, /binance → okx/);
  assert.match(providerSwitch.algorithm, /symbol binding/);
  assert.match(providerSwitch.algorithm, /新鲜度/);
});

test("builds a canonical report artifact with answer-first blocks, traceable sources and nine six-column audit tables", () => {
  const evidence = loadScenarioEvidence(platformRoot);
  const artifact = buildReportArtifact(evidence, generatedAt);

  assert.equal(artifact.surface, "report");
  assert.equal(artifact.manifest.surface, "report");
  assert.equal(artifact.manifest.generatedAt, generatedAt);
  assert.equal(artifact.snapshot.generatedAt, generatedAt);
  assert.equal(artifact.snapshot.status, "ready");
  assert.equal(artifact.manifest.blocks[0].type, "markdown");
  assert.equal(artifact.manifest.blocks[0].body, `# ${artifact.manifest.title}`);
  assert.equal(artifact.manifest.blocks[1].type, "markdown");
  assert.match(artifact.manifest.blocks[1].body, /^## 技术摘要/);
  assert.match(artifact.manifest.blocks[1].body, /191 个 actual 与 expected/);
  assert.match(artifact.manifest.blocks[1].body, /2026-07-17T07:10:10\.4475993Z/);

  assert.equal(artifact.manifest.charts.length, 1);
  const coverageChart = artifact.manifest.charts[0];
  assert.equal(coverageChart.type, "bar");
  assert.equal(coverageChart.dataset, "category_summary");
  assert.ok(coverageChart.sourceId);
  assert.equal(coverageChart.encodings.x.field, "categoryShort");
  assert.equal(coverageChart.encodings.x.type, "nominal");
  assert.equal(coverageChart.encodings.y.field, "caseCount");
  assert.equal(coverageChart.encodings.y.type, "quantitative");
  assert.match(coverageChart.subtitle, /9 个业务分类，共 191 个实际通过用例/);
  assert.equal(artifact.snapshot.datasets.category_summary.length, 9);
  assert.equal(
    artifact.snapshot.datasets.category_summary.reduce((sum, row) => sum + row.caseCount, 0),
    191,
  );
  const chartBlockIndex = artifact.manifest.blocks.findIndex(
    (block) => block.type === "chart" && block.chartId === coverageChart.id,
  );
  assert.ok(chartBlockIndex > 0);
  assert.equal(artifact.manifest.blocks[chartBlockIndex - 1].type, "markdown");
  assert.match(artifact.manifest.blocks[chartBlockIndex - 1].body, /^## 191 个用例如何分布/);

  const examples = artifact.manifest.blocks.find((block) => block.id === "representative_cases");
  assert.match(examples.body, /测试用例1：现货/);
  assert.match(examples.body, /测试用例2：永续合约/);
  assert.match(examples.body, /BTCUSDT/);
  assert.match(examples.body, /BTCUSDT-PERP/);
  assert.match(examples.body, /这里的实际证据只有 BTCUSDT 和 BTCUSDT-PERP/);

  const semantics = artifact.manifest.blocks.find((block) => block.id === "reading_rules");
  assert.match(semantics.body, /https:\/\/developers\.binance\.com\/en\/docs\/products\/spot\/rest-api/);
  assert.match(semantics.body, /https:\/\/www\.okx\.com\/docs-v5\/en\//);
  assert.match(semantics.body, /https:\/\/www\.okx\.com\/en-us\/help\/perps-funding-fee-mechanism/);

  const methodology = artifact.manifest.blocks.find((block) => block.id === "comparison_method");
  assert.match(methodology.body, /^## 怎样判定“两个结果相等”/);
  assert.match(methodology.body, /orders、trades、positions、wallets、account、ledger、protections、events、failure/);
  assert.match(methodology.body, /4 个竞态场景/);

  assert.equal(artifact.manifest.tables.length, 9);
  const expectedColumns = [
    ["case", "编号/分段"],
    ["initial", "场景与初始数据"],
    ["steps", "详细操作步骤"],
    ["actual", "最终实际结果"],
    ["expected", "算法与 expected 校验数据"],
    ["check", "实际/预期比对结论"],
  ];
  for (const table of artifact.manifest.tables) {
    assert.ok(table.sourceId);
    assert.equal(table.density, "spacious", `${table.id} must wrap long audit data instead of clipping it`);
    assert.deepEqual(table.columns.map(({ field, label }) => [field, label]), expectedColumns);
    assert.ok(
      table.columns.every((column) => column.sizing === "content"),
      `${table.id} must break uninterrupted identifiers and numeric traces instead of hiding overflow`,
    );
    assert.ok(table.columns.some((column) => column.field === table.defaultSort.field));
    assert.match(table.defaultSort.direction, /^(asc|desc)$/);
    const tableBlockIndex = artifact.manifest.blocks.findIndex(
      (block) => block.type === "table" && block.tableId === table.id,
    );
    assert.ok(tableBlockIndex > 0, table.id);
    const heading = artifact.manifest.blocks[tableBlockIndex - 1];
    assert.equal(heading.type, "markdown");
    const scenarioCount = new Set(
      artifact.snapshot.datasets[table.dataset].map((row) => parseAuditCase(row).caseId),
    ).size;
    assert.match(heading.body, new RegExp(`^## .+（${scenarioCount} 个）`));
  }
  const auditRows = artifact.manifest.tables.flatMap((table) => artifact.snapshot.datasets[table.dataset]);
  assert.ok(auditRows.length > 191);
  assert.equal(new Set(auditRows.map((row) => parseAuditCase(row).caseId)).size, 191);
  assert.ok(
    auditRows.every((row) =>
      Object.values(row).every((value) => typeof value !== "string" || value.length <= 4000)
    ),
  );
  const detailedFields = [
    ["initial", "scenarioAndInitialData"],
    ["steps", "detailedSteps"],
    ["actual", "actualFinalData"],
    ["expected", "algorithmAndExpectedData"],
    ["check", "comparisonResult"],
  ];
  for (const record of evidence.records) {
    const segments = auditRows
      .filter((row) => parseAuditCase(row).caseId === record.caseId)
      .sort((left, right) => parseAuditCase(left).segmentIndex - parseAuditCase(right).segmentIndex);
    assert.equal(segments.length, parseAuditCase(segments[0]).segmentCount, record.caseId);
    segments.forEach((segment, index) => assert.equal(parseAuditCase(segment).segmentIndex, index + 1, record.caseId));
    for (const [artifactField, recordField] of detailedFields) {
      assert.equal(
        segments.map((segment) => segment[artifactField]).filter((value) => value !== "—").join(""),
        record[recordField],
        `${record.caseId}.${recordField}`,
      );
    }
  }
  assert.ok(
    Buffer.byteLength(JSON.stringify(artifact), "utf8") < 3_000_000,
    "canonical artifact must stay below the renderer's 3 MB payload limit",
  );

  assert.ok(artifact.manifest.cards.length >= 5);
  assert.ok(artifact.manifest.cards.every((card) => card.sourceId && card.metrics.length === 1));
  assert.deepEqual(
    artifact.manifest.cards.map((card) => card.metrics[0].field),
    ["totalCases", "passedCases", "failedCases", "matchedCases", "spotCases", "perpetualCases", "expectedRejections"],
  );
  assert.equal(artifact.snapshot.datasets.summary[0].matchedCases, 191);
  assert.equal(artifact.snapshot.datasets.summary[0].failedCases, 0);
  assert.ok(artifact.manifest.sources.every((source) => source.id && source.label && source.path));
  assert.ok(artifact.manifest.sources.some((source) => source.id === "scenario_expecteds"));
  assert.ok(
    artifact.sources.every(
      (source) =>
        source.id &&
        source.query?.engine === "duckdb" &&
        source.query?.language === "sql" &&
        source.query?.description &&
        source.query?.executed_at === generatedAt,
    ),
  );
  const sourceIds = new Set([
    ...artifact.manifest.sources.map((source) => source.id),
    ...artifact.sources.map((source) => source.id),
  ]);
  for (const item of [...artifact.manifest.cards, ...artifact.manifest.tables]) {
    assert.ok(sourceIds.has(item.sourceId), `${item.id}.${item.sourceId}`);
  }
  const sourcesById = new Map(artifact.sources.map((source) => [source.id, source]));
  for (const item of [
    ...artifact.manifest.cards,
    ...artifact.manifest.charts,
    ...artifact.manifest.tables,
  ]) {
    assert.match(sourcesById.get(item.sourceId).query.sql, /\bSELECT\b/i, item.sourceId);
  }
});

test("CLI writes the complete UTF-8 canonical artifact requested by --output", () => {
  const temporaryDirectory = mkdtempSync(join(tmpdir(), "readable-trading-report-"));
  const output = join(temporaryDirectory, "artifact.json");
  const generator = fileURLToPath(new URL("./generate-readable-trading-scenario-report.mjs", import.meta.url));

  try {
    const result = spawnSync(process.execPath, [generator, "--output", output], {
      cwd: platformRoot,
      encoding: "utf8",
    });
    assert.equal(result.status, 0, result.stderr || result.stdout);
    const artifact = JSON.parse(readFileSync(output, "utf8"));
    assert.equal(artifact.surface, "report");
    assert.equal(artifact.manifest.tables.length, 9);
    const auditRows = artifact.manifest.tables.flatMap(
      (table) => artifact.snapshot.datasets[table.dataset],
    );
    assert.ok(auditRows.length > 191);
    assert.equal(new Set(auditRows.map((row) => parseAuditCase(row).caseId)).size, 191);
  } finally {
    rmSync(temporaryDirectory, { recursive: true, force: true });
  }
});
