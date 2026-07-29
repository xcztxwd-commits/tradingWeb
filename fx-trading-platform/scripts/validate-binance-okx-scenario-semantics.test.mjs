import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";

import {
  auditScenarioEvidence,
  buildValidationDocument,
  decimalToFixed,
  multiplyDecimal,
} from "./validate-binance-okx-scenario-semantics.mjs";

const platformRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const auditModulePath = resolve(platformRoot, "scripts/validate-binance-okx-scenario-semantics.mjs");

test("uses exact decimal arithmetic for exchange formulas", () => {
  assert.equal(decimalToFixed(multiplyDecimal("110.51105000", "1.80880000"), 8), "199.89238724");
  assert.equal(decimalToFixed(multiplyDecimal("199.89238724", "0.0005"), 8), "0.09994619");
  assert.equal(decimalToFixed(multiplyDecimal("100.10000000", "0.001"), 8), "0.10010000");
});

test("is independent from project expected snapshots and Java oracles", () => {
  const source = readFileSync(auditModulePath, "utf8");
  assert.doesNotMatch(source, /expected\.json/i);
  assert.doesNotMatch(source, /ScenarioOracle/);
  assert.doesNotMatch(source, /generate-readable-trading-scenario-report/);
});

test("audits exactly 191 fresh actual scenarios with one Binance and one OKX conclusion each", () => {
  const audit = auditScenarioEvidence(platformRoot);

  assert.equal(audit.records.length, 191);
  assert.equal(new Set(audit.records.map((record) => record.caseId)).size, 191);
  assert.equal(audit.records.filter((record) => record.productType === "CRYPTO_SPOT").length, 36);
  assert.equal(audit.records.filter((record) => record.productType === "LINEAR_PERP").length, 155);

  for (const record of audit.records) {
    assert.ok(record.operationSteps.length >= 1, record.caseId);
    assert.ok(record.actualResult.trim(), record.caseId);
    assert.ok(record.independentCalculation.trim(), record.caseId);
    assert.ok(record.numericChecks.length >= 1, record.caseId);
    assert.ok(record.binance?.status, record.caseId);
    assert.ok(record.binance?.conclusion?.trim(), record.caseId);
    assert.ok(record.okx?.status, record.caseId);
    assert.ok(record.okx?.conclusion?.trim(), record.caseId);
    assert.ok(["PASS", "CONDITIONAL", "FAIL", "NOT_APPLICABLE"].includes(record.binance.status), record.caseId);
    assert.ok(["PASS", "CONDITIONAL", "FAIL", "NOT_APPLICABLE"].includes(record.okx.status), record.caseId);
  }

  for (const exchange of ["binance", "okx"]) {
    const summary = audit.summary[exchange];
    assert.equal(summary.pass + summary.conditional + summary.fail + summary.notApplicable, 191, exchange);
  }
});

test("recomputes every persisted trade notional and fee instead of trusting actual labels", () => {
  const audit = auditScenarioEvidence(platformRoot);
  const tradeChecks = audit.records.flatMap((record) =>
    record.numericChecks.filter((check) => check.kind === "TRADE_NOTIONAL" || check.kind === "TRADE_FEE"),
  );

  assert.ok(tradeChecks.length > 191);
  assert.equal(tradeChecks.filter((check) => !check.equal).length, 0);
  assert.ok(tradeChecks.every((check) => check.formula && check.actual != null && check.calculated != null));
});

test("detects the current Spot buy commission difference under Binance default semantics", () => {
  const audit = auditScenarioEvidence(platformRoot);
  const spot = audit.records.find((record) => record.caseId === "SPOT_SELL_PROFIT");

  assert.equal(spot.binance.status, "FAIL");
  assert.match(spot.binance.conclusion, /买入手续费|base|BTC/i);
  assert.ok(spot.binance.differences.some((difference) => difference.ruleId === "BINANCE_SPOT_COMMISSION_RECEIVED_ASSET"));
  assert.equal(spot.okx.status, "CONDITIONAL");
  assert.match(spot.okx.conclusion, /feeType=1|计价币/i);
});

test("validates representative perpetual PnL, funding, and exchange quantity mappings", () => {
  const audit = auditScenarioEvidence(platformRoot);
  const close = audit.records.find((record) => record.caseId === "PERP_CLOSE_PROFIT");
  const funding = audit.records.find((record) => record.caseId === "PERP_FUNDING_POSITIVE_LONG");
  const initialPositionClose = audit.records.find((record) =>
    record.caseId === "PERP_CORE_DOWN_DOWN_DOWN_PARTIAL_CLOSE_ADD_LONG"
  );

  assert.ok(close.numericChecks.some((check) => check.kind === "REALIZED_PNL" && check.equal));
  assert.ok(initialPositionClose.numericChecks.some((check) =>
    check.kind === "REALIZED_PNL"
      && check.calculated === "-10.50895000"
      && check.equal
  ));
  assert.match(close.binance.mapping, /quantity.*BTC|基础币数量/i);
  assert.match(close.okx.mapping, /sz.*contracts|合约张数/i);
  assert.ok(funding.numericChecks.some((check) => check.kind === "FUNDING" && check.equal));
  assert.equal(
    audit.records.flatMap((record) => record.numericChecks)
      .filter((check) => check.exchange === "BOTH" && !check.equal).length,
    0,
  );
});

test("builds a complete portable-report source document with all rules and records", () => {
  const audit = auditScenarioEvidence(platformRoot);
  const document = buildValidationDocument(audit, "2026-07-18T00:00:00.000Z");

  assert.equal(document.schemaVersion, 1);
  assert.equal(document.generatedAt, "2026-07-18T00:00:00.000Z");
  assert.equal(document.records.length, 191);
  assert.ok(document.rules.length >= 12);
  assert.ok(document.rules.every((rule) => /^https:\/\//.test(rule.officialUrl)));
});
