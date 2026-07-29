import assert from "node:assert/strict";
import { readFileSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import { test } from "node:test";

import { auditScenarioEvidence } from "./validate-binance-okx-scenario-semantics.mjs";
import {
  buildOfficialValidationReport,
  buildScenarioCard,
} from "./generate-binance-okx-official-validation-report.mjs";

const platformRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const generatedAt = "2026-07-18T00:00:00.000Z";

test("renders one vertical scenario card without truncating any evidence", () => {
  const audit = auditScenarioEvidence(platformRoot);
  const record = audit.records.find((candidate) => candidate.caseId === "SPOT_SELL_PROFIT");
  const block = buildScenarioCard(record);

  assert.equal(block.type, "markdown");
  assert.equal(block.id, "scenario_spot_187_SPOT_SELL_PROFIT");
  assert.ok(block.body.includes(record.reportOperation));
  assert.ok(block.body.includes(record.independentCalculation));
  assert.ok(block.body.includes(record.reportExchangeConclusion));
  assert.match(block.body, /Binance.*FAIL/s);
  assert.match(block.body, /OKX.*CONDITIONAL/s);
});

test("builds an answer-first report with exact Binance and OKX totals and official sources", () => {
  const audit = auditScenarioEvidence(platformRoot);
  const artifact = buildOfficialValidationReport(audit, generatedAt);

  assert.equal(artifact.surface, "report");
  assert.equal(artifact.manifest.generatedAt, generatedAt);
  assert.equal(artifact.snapshot.generatedAt, generatedAt);
  assert.equal(artifact.manifest.tables.length, 0);
  assert.equal(artifact.manifest.charts.length, 1);
  assert.deepEqual(artifact.snapshot.datasets.summary[0], {
    totalCases: 191,
    numericChecks: 2694,
    commonNumericMismatches: 0,
    binancePass: 31,
    binanceConditional: 141,
    binanceFail: 19,
    okxPass: 25,
    okxConditional: 35,
    okxFail: 131,
  });

  const summary = artifact.manifest.blocks.find((block) => block.id === "technical_summary");
  assert.match(summary.body, /Binance.*31.*141.*19/s);
  assert.match(summary.body, /OKX.*25.*35.*131/s);
  assert.match(summary.body, /191\/191/);
  assert.match(summary.body, /现货买入手续费/);
  assert.match(summary.body, /全仓初始保证金/);
  assert.match(JSON.stringify(artifact), /https:\/\/developers\.binance\.com/);
  assert.match(JSON.stringify(artifact), /https:\/\/www\.okx\.com/);
});

test("contains every one of the 191 scenarios once as full-width vertical cards", () => {
  const audit = auditScenarioEvidence(platformRoot);
  const artifact = buildOfficialValidationReport(audit, generatedAt);
  const scenarioBlocks = artifact.manifest.blocks.filter((block) => block.id.startsWith("scenario_"));
  const spotBlocks = scenarioBlocks.filter((block) => block.id.startsWith("scenario_spot_"));
  const perpetualBlocks = scenarioBlocks.filter((block) => block.id.startsWith("scenario_perpetual_"));

  assert.equal(scenarioBlocks.length, 191);
  assert.equal(new Set(scenarioBlocks.map((block) => block.id)).size, 191);
  assert.equal(spotBlocks.length, 36);
  assert.equal(perpetualBlocks.length, 155);
  assert.equal(artifact.manifest.blocks.some((block) => block.type === "table"), false);
  for (const record of audit.records) {
    const prefix = record.productType === "CRYPTO_SPOT" ? "spot" : "perpetual";
    const id = `scenario_${prefix}_${String(record.caseNumber).padStart(3, "0")}_${record.caseId}`;
    const block = scenarioBlocks.find((candidate) => candidate.id === id);
    assert.ok(block, record.caseId);
    assert.ok(block.body.includes(record.reportOperation), `${record.caseId}: operation`);
    assert.ok(block.body.includes(record.independentCalculation), `${record.caseId}: calculation`);
    assert.ok(block.body.includes(record.reportExchangeConclusion), `${record.caseId}: exchange conclusion`);
  }
});

test("renders all official rules as vertical cards with direct source links", () => {
  const audit = auditScenarioEvidence(platformRoot);
  const artifact = buildOfficialValidationReport(audit, generatedAt);
  const ruleBlocks = artifact.manifest.blocks.filter((block) => block.id.startsWith("official_rule_"));

  assert.equal(ruleBlocks.length, audit.rules.length);
  for (const rule of audit.rules) {
    const block = ruleBlocks.find((candidate) => candidate.id === `official_rule_${rule.ruleId}`);
    assert.ok(block, rule.ruleId);
    assert.ok(block.body.includes(rule.semantic));
    assert.ok(block.body.includes(rule.officialUrl));
  }
});

test("keeps the canonical report payload within the portable renderer budget", () => {
  const audit = auditScenarioEvidence(platformRoot);
  const artifact = buildOfficialValidationReport(audit, generatedAt);
  assert.ok(Buffer.byteLength(JSON.stringify(artifact), "utf8") < 3_000_000);
});

test("CLI writes the complete UTF-8 canonical report artifact", () => {
  const directory = mkdtempSync(join(tmpdir(), "official-validation-report-"));
  const output = join(directory, "artifact.json");
  const generator = fileURLToPath(new URL("./generate-binance-okx-official-validation-report.mjs", import.meta.url));
  try {
    const result = spawnSync(process.execPath, [generator, "--output", output], {
      cwd: platformRoot,
      encoding: "utf8",
    });
    assert.equal(result.status, 0, result.stderr || result.stdout);
    const artifact = JSON.parse(readFileSync(output, "utf8"));
    assert.equal(artifact.surface, "report");
    assert.equal(artifact.manifest.blocks.filter((block) => block.id.startsWith("scenario_")).length, 191);
    assert.equal(artifact.manifest.blocks.some((block) => block.type === "table"), false);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
