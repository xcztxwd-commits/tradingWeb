import assert from 'node:assert/strict'
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync
} from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const scriptsDir = dirname(fileURLToPath(import.meta.url))
const platformRoot = join(scriptsDir, '..')
const runner = join(scriptsDir, 'run-spot-perp-scenario-tests.ps1')
const report = join(platformRoot, 'docs', 'testing', 'spot-perp-test-report.md')
const matrix = join(
  platformRoot,
  'docs',
  'testing',
  'spot-perp-scenario-matrix.json'
)

test('runner dry-run is side-effect free and emits the owned execution plan', () => {
  assert.equal(existsSync(runner), true, 'PowerShell runner must exist')

  const before = snapshot(report)
  const result = spawnSync(
    'powershell.exe',
    [
      '-NoProfile',
      '-ExecutionPolicy',
      'Bypass',
      '-File',
      runner,
      '-DryRun'
    ],
    {
      cwd: platformRoot,
      encoding: 'utf8',
      env: {
        ...process.env,
        SCENARIO_RUNNER_TEST_SENTINEL: 'dry-run-must-not-mutate'
      }
    }
  )

  assert.equal(
    result.status,
    0,
    `dry-run failed\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`
  )
  const planLine = result.stdout
    .split(/\r?\n/u)
    .find(line => line.startsWith('SCENARIO_DRY_RUN_PLAN='))
  assert.ok(planLine, `dry-run plan marker missing:\n${result.stdout}`)
  const plan = JSON.parse(planLine.slice('SCENARIO_DRY_RUN_PLAN='.length))

  assert.equal(plan.dryRun, true)
  assert.match(plan.databaseName, /^fx_scenario_it_[a-z0-9_]{1,45}$/u)
  assert.equal(plan.commands.some(command => /docker\s+compose.+up\s+-d\s+postgres/iu.test(command)), true)
  assert.equal(plan.commands.some(command => /mvn(?:\.cmd)?/iu.test(command)), true)
  assert.equal(plan.commands.some(command => /down\s+-v|docker\s+volume/iu.test(command)), false)
  assert.equal(plan.matrixCoverage.totalCases > 0, true)
  assert.equal(
    plan.matrixCoverage.spotCases + plan.matrixCoverage.perpetualCases,
    plan.matrixCoverage.totalCases
  )
  assert.deepEqual(
    plan.reportSections,
    [
      'Summary',
      'Product coverage',
      'Scope',
      'Fixed issues',
      'Unresolved issues',
      'Command results',
      'Key files',
      'Matrix coverage',
      'Database lifecycle',
      'Scenario failure details',
      'Exchange semantics'
    ]
  )
  assert.deepEqual(snapshot(report), before)
})

test('runner source has strict ownership, cleanup, environment, report and surefire gates', () => {
  assert.equal(existsSync(runner), true, 'PowerShell runner must exist')
  const source = readFileSync(runner, 'utf8')

  assert.match(source, /\^fx_scenario_it_\[a-z0-9_\]\{1,45\}\$/u)
  assert.match(source, /fx_platform/u)
  assert.match(source, /template0/u)
  assert.match(source, /template1/u)
  assert.match(source, /try\s*\{/u)
  assert.match(source, /finally\s*\{/u)
  assert.match(source, /DROP DATABASE IF EXISTS/u)
  assert.match(source, /WITH \(FORCE\)/u)
  assert.match(source, /SCENARIO_DATABASE_NAME/u)
  assert.match(source, /SCENARIO_DATABASE_URL/u)
  assert.match(source, /SPRING_DATASOURCE_URL/u)
  assert.match(source, /Restore-Environment/u)
  assert.match(source, /TEST-com\.fxplatform\.trading\.scenario\./u)
  assert.match(source, /failures/u)
  assert.match(source, /errors/u)
  assert.match(source, /skipped/u)
  assert.match(source, /spot-perp-test-report\.md/u)
  assert.match(source, /Move-Item/u)
  assert.match(source, /datname = '\$DatabaseName'/u)

  assert.doesNotMatch(source, /docker\s+compose[^\r\n]*down/iu)
  assert.doesNotMatch(source, /docker\s+volume/iu)
  assert.doesNotMatch(source, /DROP DATABASE\s+(?:fx_platform|postgres|template0|template1)/iu)
  assert.doesNotMatch(source, /--set=db|:'db'|:"db"/u)
})

test('runner isolates scenario-it to the matrix Maven invocation', () => {
  const source = readFileSync(runner, 'utf8')

  assert.match(
    source,
    /Set-ProcessEnvironment -Name 'SPRING_PROFILES_ACTIVE' -Value \$null/u
  )
  assert.doesNotMatch(
    source,
    /Set-ProcessEnvironment -Name 'SPRING_PROFILES_ACTIVE' -Value 'scenario-it'/u
  )
  assert.match(
    source,
    /-Name 'Spot, Perpetual and resilience scenario matrix'[\s\S]*'-Dspring\.profiles\.active=scenario-it'/u
  )
})

test('runner report contract is complete and preserves failing scenario evidence', () => {
  const source = readFileSync(runner, 'utf8')

  for (const heading of [
    '## Summary',
    '## Product coverage',
    '## Scope',
    '## Fixed issues',
    '## Unresolved issues',
    '## Command results',
    '## Key files',
    '## Matrix coverage',
    '## Database lifecycle',
    '## Scenario failure details',
    '## Exchange semantics'
  ]) {
    assert.equal(source.includes(heading), true, `missing report heading: ${heading}`)
  }

  for (const marker of [
    'Get-MatrixCoverage',
    'Read-ScenarioSurefire',
    'PassedCases',
    'FailedCases',
    'SpotCases',
    'PerpetualCases',
    'FailureDetails',
    'caseId',
    'testClass',
    'message',
    'DatabaseCreateStatus',
    'CleanupStatus',
    'KeyFiles',
    'Result'
  ]) {
    assert.equal(source.includes(marker), true, `missing report contract marker: ${marker}`)
  }

  assert.match(source, /failure|error/u)
  assert.match(source, /testcase/u)
  assert.match(source, /Surefire/u)
  assert.match(source, /NOT_REPORTED/u)
  assert.doesNotMatch(source, /FailedCases\s*=\s*0[\s\S]*Status\s*=\s*'PASSED'/u)

  for (const statement of [
    'This DEMO matrix verifies one full fill per order.',
    'Real partial fills and DEPTH matching are explicitly out of scope',
    '`PARTIALLY_FILLED` is retained only as compatibility input',
    '`PARTIAL_FILL_NOT_SUPPORTED` before committed mutation'
  ]) {
    assert.equal(source.includes(statement), true, `missing scope statement: ${statement}`)
  }
  for (const forbiddenClaim of [
    'Deterministic DEPTH fill identity with idempotent replay and payload conflict detection',
    'Spot DEPTH partial-fill hold, fee and wallet settlement with DEMO-only isolation'
  ]) {
    assert.equal(
      source.includes(forbiddenClaim),
      false,
      `runner must not claim undelivered DEPTH behavior: ${forbiddenClaim}`
    )
  }
})

test('scenario artifact gate requires fresh expected and actual evidence for every case', () => {
  const tempRoot = mkdtempSync(join(tmpdir(), 'scenario-artifacts-gate-'))
  const artifactsRoot = join(tempRoot, 'artifacts')
  const matrixPath = join(tempRoot, 'matrix.json')
  const cases = [{ caseId: 'SPOT_CASE' }, { caseId: 'PERP_CASE' }]
  writeFileSync(matrixPath, JSON.stringify(cases), 'utf8')
  for (const scenario of cases) {
    const directory = join(artifactsRoot, scenario.caseId)
    mkdirSync(directory, { recursive: true })
    writeFileSync(join(directory, 'expected.json'), '{"expected":true}', 'utf8')
    writeFileSync(join(directory, 'actual.json'), '{"actual":true}', 'utf8')
  }

  const before = snapshot(report)
  const quote = value => value.replaceAll("'", "''")
  const probe = () => {
    const command = [
      `& { . '${quote(runner)}' -DryRun | Out-Null;`,
      `$count = Assert-ScenarioArtifacts -ArtifactsRoot '${quote(artifactsRoot)}'`,
      `-MatrixPath '${quote(matrixPath)}';`,
      'Write-Output "COUNT=$count" }'
    ].join(' ')
    return spawnSync(
      'powershell.exe',
      ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', command],
      { cwd: platformRoot, encoding: 'utf8' }
    )
  }

  try {
    const valid = probe()
    assert.equal(
      valid.status,
      0,
      `artifact gate rejected complete evidence\nstdout:\n${valid.stdout}\nstderr:\n${valid.stderr}`
    )
    assert.match(valid.stdout, /COUNT=2/u)

    rmSync(join(artifactsRoot, 'PERP_CASE', 'actual.json'))
    const incomplete = probe()
    assert.notEqual(incomplete.status, 0, 'artifact gate accepted a missing actual.json')
    assert.match(`${incomplete.stdout}\n${incomplete.stderr}`, /PERP_CASE[\\/]actual\.json/u)
    assert.deepEqual(snapshot(report), before)
  } finally {
    rmSync(tempRoot, { recursive: true, force: true })
  }
})

test('dry-run-loaded Surefire parser preserves caseId, testClass and message', () => {
  const scenarios = JSON.parse(readFileSync(matrix, 'utf8'))
  const scenario = scenarios.find(entry =>
    entry.testClass.endsWith('.SpotScenarioMatrixIT')
  )
  assert.ok(scenario)

  const reportsRoot = mkdtempSync(join(tmpdir(), 'scenario-surefire-'))
  const xmlPath = join(
    reportsRoot,
    'TEST-com.fxplatform.trading.scenario.SpotScenarioMatrixIT.xml'
  )
  const message = 'expected wallet balance but persisted value differed'
  writeFileSync(
    xmlPath,
    [
      '<testsuite tests="1" failures="1" errors="0" skipped="0">',
      `<testcase classname="${scenario.testClass}" name="${scenario.caseId}">`,
      `<failure message="${message}">scenario assertion stack</failure>`,
      '</testcase>',
      '</testsuite>'
    ].join(''),
    'utf8'
  )

  const before = snapshot(report)
  try {
    const quote = value => value.replaceAll("'", "''")
    const command = [
      `& { . '${quote(runner)}' -DryRun | Out-Null;`,
      `$summary = Read-ScenarioSurefire -ReportsRoot '${quote(reportsRoot)}'`,
      `-MatrixPath '${quote(matrix)}';`,
      '$summary | ConvertTo-Json -Depth 8 -Compress }'
    ].join(' ')
    const result = spawnSync(
      'powershell.exe',
      ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', command],
      { cwd: platformRoot, encoding: 'utf8' }
    )
    assert.equal(
      result.status,
      0,
      `Surefire parser failed\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`
    )
    const summary = JSON.parse(result.stdout.trim().split(/\r?\n/u).at(-1))
    const detail = summary.FailureDetails.find(
      entry => entry.caseId === scenario.caseId
    )
    assert.ok(detail)
    assert.equal(detail.testClass, scenario.testClass)
    assert.equal(detail.message, message)
    assert.equal(summary.FailedCases, 1)
    assert.equal(summary.NotReportedCases, scenarios.length - 1)
    assert.deepEqual(snapshot(report), before)
  } finally {
    rmSync(reportsRoot, { recursive: true, force: true })
  }
})

test('Surefire parameter index maps to the catalog-sorted stream for its test class', () => {
  const scenarios = JSON.parse(readFileSync(matrix, 'utf8'))
  const classScenarios = scenarios
    .filter(entry => entry.testClass.endsWith('.SpotScenarioMatrixIT'))
    .sort((left, right) => left.caseId.localeCompare(right.caseId))
  const scenario = classScenarios[1]
  assert.ok(scenario)

  const reportsRoot = mkdtempSync(join(tmpdir(), 'scenario-surefire-index-'))
  const xmlPath = join(
    reportsRoot,
    'TEST-com.fxplatform.trading.scenario.SpotScenarioMatrixIT.xml'
  )
  const message = 'real parameterized invocation failed'
  writeFileSync(
    xmlPath,
    [
      '<testsuite tests="1" failures="1" errors="0" skipped="0">',
      `<testcase classname="${scenario.testClass}" name="executesScenario(String, ScenarioDefinition)[2]">`,
      `<failure message="${message}">scenario assertion stack</failure>`,
      '</testcase>',
      '</testsuite>'
    ].join(''),
    'utf8'
  )

  const before = snapshot(report)
  try {
    const quote = value => value.replaceAll("'", "''")
    const command = [
      `& { . '${quote(runner)}' -DryRun | Out-Null;`,
      `$summary = Read-ScenarioSurefire -ReportsRoot '${quote(reportsRoot)}'`,
      `-MatrixPath '${quote(matrix)}';`,
      '$summary | ConvertTo-Json -Depth 8 -Compress }'
    ].join(' ')
    const result = spawnSync(
      'powershell.exe',
      ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', command],
      { cwd: platformRoot, encoding: 'utf8' }
    )
    assert.equal(
      result.status,
      0,
      `Surefire parser failed\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`
    )
    const summary = JSON.parse(result.stdout.trim().split(/\r?\n/u).at(-1))
    const detail = summary.FailureDetails.find(
      entry => entry.caseId === scenario.caseId
    )
    assert.ok(detail)
    assert.equal(detail.testClass, scenario.testClass)
    assert.equal(detail.message, message)
    assert.equal(summary.FailedCases, 1)
    assert.equal(summary.NotReportedCases, scenarios.length - 1)
    assert.deepEqual(snapshot(report), before)
  } finally {
    rmSync(reportsRoot, { recursive: true, force: true })
  }
})

test('direct Surefire caseId mapping never crosses its declared test class', () => {
  const scenarios = JSON.parse(readFileSync(matrix, 'utf8'))
  const spotClass = scenarios.find(entry =>
    entry.testClass.endsWith('.SpotScenarioMatrixIT')
  )?.testClass
  const perpetual = scenarios.find(entry =>
    entry.testClass.endsWith('.PerpetualScenarioMatrixIT')
  )
  assert.ok(spotClass)
  assert.ok(perpetual)

  const before = snapshot(report)
  const quote = value => value.replaceAll("'", "''")
  const command = [
    `& { . '${quote(runner)}' -DryRun | Out-Null;`,
    `$items = @(Read-ScenarioMatrix -MatrixPath '${quote(matrix)}');`,
    `$match = @(Resolve-ScenarioCase -TestName '${quote(perpetual.caseId)}'`,
    `-TestClass '${quote(spotClass)}' -Matrix $items);`,
    'Write-Output "MATCH_COUNT=$($match.Count)" }'
  ].join(' ')
  const result = spawnSync(
    'powershell.exe',
    ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', command],
    { cwd: platformRoot, encoding: 'utf8' }
  )

  assert.equal(
    result.status,
    0,
    `cross-class probe failed\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`
  )
  assert.match(result.stdout, /MATCH_COUNT=0/u)
  assert.deepEqual(snapshot(report), before)
})

test('duplicate and out-of-range Surefire indexes never produce a false pass', () => {
  const scenarios = JSON.parse(readFileSync(matrix, 'utf8'))
  const classScenarios = scenarios
    .filter(entry => entry.testClass.endsWith('.SpotScenarioMatrixIT'))
    .sort((left, right) => left.caseId.localeCompare(right.caseId))
  const scenario = classScenarios[0]
  assert.ok(scenario)

  const reportsRoot = mkdtempSync(join(tmpdir(), 'scenario-surefire-invalid-'))
  const xmlPath = join(
    reportsRoot,
    'TEST-com.fxplatform.trading.scenario.SpotScenarioMatrixIT.xml'
  )
  writeFileSync(
    xmlPath,
    [
      '<testsuite tests="3" failures="0" errors="0" skipped="0">',
      `<testcase classname="${scenario.testClass}" name="executesScenario(String, ScenarioDefinition)[1]" />`,
      `<testcase classname="${scenario.testClass}" name="executesScenario(String, ScenarioDefinition)[1]" />`,
      `<testcase classname="${scenario.testClass}" name="executesScenario(String, ScenarioDefinition)[999]" />`,
      '</testsuite>'
    ].join(''),
    'utf8'
  )

  const before = snapshot(report)
  try {
    const quote = value => value.replaceAll("'", "''")
    const command = [
      `& { . '${quote(runner)}' -DryRun | Out-Null;`,
      `$summary = Read-ScenarioSurefire -ReportsRoot '${quote(reportsRoot)}'`,
      `-MatrixPath '${quote(matrix)}';`,
      '$summary | ConvertTo-Json -Depth 8 -Compress }'
    ].join(' ')
    const result = spawnSync(
      'powershell.exe',
      ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', command],
      { cwd: platformRoot, encoding: 'utf8' }
    )
    assert.equal(
      result.status,
      0,
      `Surefire parser failed\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`
    )
    const summary = JSON.parse(result.stdout.trim().split(/\r?\n/u).at(-1))
    assert.equal(summary.PassedCases, 0)
    assert.equal(summary.FailedCases, 1)
    assert.equal(summary.NotReportedCases, scenarios.length - 1)
    assert.equal(
      summary.FailureDetails.some(
        entry =>
          entry.caseId === scenario.caseId &&
          entry.message === 'Duplicate Surefire invocation for the same caseId'
      ),
      true
    )
    assert.equal(
      summary.FailureDetails.some(
        entry =>
          entry.caseId === '' &&
          entry.message.includes(
            'executesScenario(String, ScenarioDefinition)[999]'
          ) &&
          entry.message.includes('could not be mapped')
      ),
      true
    )
    assert.deepEqual(snapshot(report), before)
  } finally {
    rmSync(reportsRoot, { recursive: true, force: true })
  }
})

test('recorded steps accept the initially empty command log', () => {
  const before = snapshot(report)
  const quote = value => value.replaceAll("'", "''")
  const command = [
    `& { . '${quote(runner)}' -DryRun | Out-Null;`,
    "$log = New-Object 'System.Collections.Generic.List[object]';",
    "Invoke-RecordedStep -Name 'first step' -Command 'noop'",
    "-Action { return 'ok' } -CommandLog $log | Out-Null;",
    "$log | ConvertTo-Json -Depth 4 -Compress }"
  ].join(' ')
  const result = spawnSync(
    'powershell.exe',
    ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', command],
    { cwd: platformRoot, encoding: 'utf8' }
  )

  assert.equal(
    result.status,
    0,
    `empty command log was rejected\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`
  )
  const entry = JSON.parse(result.stdout.trim().split(/\r?\n/u).at(-1))
  assert.equal(entry.Name, 'first step')
  assert.equal(entry.Status, 'PASS')
  assert.deepEqual(snapshot(report), before)
})

test('Surefire gate rejects extra or unmapped invocations', () => {
  const before = snapshot(report)
  const quote = value => value.replaceAll("'", "''")
  const command = [
    `& { . '${quote(runner)}' -DryRun | Out-Null;`,
    "$summary = [pscustomobject]@{MatrixCases=191;Tests=192;Failures=0;Errors=0;Skipped=0;PassedCases=191;FailedCases=0;SkippedCases=0;NotReportedCases=0;FailureDetails=@([pscustomobject]@{message='unmapped extra invocation'})};",
    'Assert-ScenarioSurefireSummary -Summary $summary }'
  ].join(' ')
  const result = spawnSync(
    'powershell.exe',
    ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', command],
    { cwd: platformRoot, encoding: 'utf8' }
  )

  assert.notEqual(result.status, 0, 'strict gate accepted an extra invocation')
  assert.match(`${result.stdout}\n${result.stderr}`, /Scenario Surefire gate failed/u)
  assert.deepEqual(snapshot(report), before)
})

test('Surefire gate rejects a complete but undersized matrix', () => {
  const before = snapshot(report)
  const quote = value => value.replaceAll("'", "''")
  const command = [
    `& { . '${quote(runner)}' -DryRun | Out-Null;`,
    '$summary = [pscustomobject]@{MatrixCases=190;Tests=190;Failures=0;Errors=0;Skipped=0;PassedCases=190;FailedCases=0;SkippedCases=0;NotReportedCases=0;FailureDetails=@()};',
    'Assert-ScenarioSurefireSummary -Summary $summary }'
  ].join(' ')
  const result = spawnSync(
    'powershell.exe',
    ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', command],
    { cwd: platformRoot, encoding: 'utf8' }
  )

  assert.notEqual(result.status, 0, 'strict gate accepted only 190 cases')
  assert.match(
    `${result.stdout}\n${result.stderr}`,
    /Scenario Surefire gate failed/u
  )
  assert.deepEqual(snapshot(report), before)
})

test('matrix coverage gate enforces the approved 191, 36 and 155 counts', () => {
  const before = snapshot(report)
  const quote = value => value.replaceAll("'", "''")
  const command = [
    `& { . '${quote(runner)}' -DryRun | Out-Null;`,
    '$valid = [pscustomobject]@{TotalCases=191;SpotCases=36;PerpetualCases=155};',
    'Assert-MatrixCoverage -Coverage $valid;',
    '$drift = [pscustomobject]@{TotalCases=190;SpotCases=35;PerpetualCases=155};',
    'Assert-MatrixCoverage -Coverage $drift }'
  ].join(' ')
  const result = spawnSync(
    'powershell.exe',
    ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', command],
    { cwd: platformRoot, encoding: 'utf8' }
  )

  assert.notEqual(result.status, 0, 'matrix coverage drift was accepted')
  assert.match(
    `${result.stdout}\n${result.stderr}`,
    /Scenario matrix coverage gate failed/u
  )
  assert.deepEqual(snapshot(report), before)
})

test('dry-run-loaded report writer renders truthful totals and lifecycle evidence', () => {
  const tempRoot = mkdtempSync(join(tmpdir(), 'scenario-report-'))
  const output = join(tempRoot, 'report.md')
  const before = snapshot(report)
  try {
    const quote = value => value.replaceAll("'", "''")
    const command = [
      `& { . '${quote(runner)}' -DryRun | Out-Null;`,
      "$log = New-Object 'System.Collections.Generic.List[object]';",
      "$log.Add([pscustomobject]@{Name='matrix';Command='mvn test';Status='FAIL';Result='BUILD FAILURE';StartedAt='start';FinishedAt='finish';Error='boom'});",
      "$summary=[pscustomobject]@{MatrixCases=2;PassedCases=1;FailedCases=1;SkippedCases=0;NotReportedCases=0;Tests=2;Failures=1;Errors=0;SpotPassed=1;SpotFailed=0;SpotSkipped=0;SpotNotReported=0;PerpetualPassed=0;PerpetualFailed=1;PerpetualSkipped=0;PerpetualNotReported=0;FailureDetails=@([pscustomobject]@{caseId='PERP_FAIL';testClass='PerpetualScenarioMatrixIT';message='wallet mismatch'})};",
      "$coverage=[pscustomobject]@{TotalCases=2;SpotCases=1;PerpetualCases=1;PricePaths=@('FLAT');ActionTypes=@('BUY');PositionModes=@('ONE_WAY');MarginModes=@('CASH','CROSS');OrderTypes=@('MARKET');QuantityUnits=@('QUOTE','CONTRACTS');TestClasses=@('SpotScenarioMatrixIT','PerpetualScenarioMatrixIT');ExecutionStatuses=@('NOT_RUN')};",
      `Write-AtomicReport -Path '${quote(output)}' -Status 'FAILED'`,
      "-DatabaseName 'fx_scenario_it_report_fixture' -CommandLog $log",
      "-ScenarioSummary $summary -MatrixCoverage $coverage",
      "-FixedIssues @('fixed hold rollback') -UnresolvedIssues @('perp mismatch')",
      "-KeyFiles @('matrix.json') -FailureMessage 'matrix failed'",
      "-DatabaseCreateStatus 'CREATED_AND_CONFIRMED' -CleanupStatus 'DROPPED_AND_CONFIRMED' }"
    ].join(' ')
    const result = spawnSync(
      'powershell.exe',
      ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', command],
      { cwd: platformRoot, encoding: 'utf8' }
    )
    assert.equal(
      result.status,
      0,
      `report writer failed\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`
    )
    const content = readFileSync(output, 'utf8')
    for (const expected of [
      '- Total cases: 2',
      '- Passed: 1',
      '- Failed: 1',
      '## Scope',
      'This DEMO matrix verifies one full fill per order.',
      'Real partial fills and DEPTH matching are explicitly out of scope',
      '`PARTIALLY_FILLED` is retained only as compatibility input',
      '`PARTIAL_FILL_NOT_SUPPORTED` before committed mutation',
      'fixed hold rollback',
      'perp mismatch',
      'mvn test',
      '- Result: FAIL - BUILD FAILURE',
      'matrix.json',
      'PERP_FAIL',
      'wallet mismatch',
      '- Creation: CREATED_AND_CONFIRMED',
      '- Cleanup: DROPPED_AND_CONFIRMED'
    ]) {
      assert.equal(content.includes(expected), true, `missing report content: ${expected}`)
    }
    for (const forbiddenClaim of [
      'Deterministic DEPTH fill identity with idempotent replay and payload conflict detection',
      'Spot DEPTH partial-fill hold, fee and wallet settlement with DEMO-only isolation'
    ]) {
      assert.equal(
        content.includes(forbiddenClaim),
        false,
        `report must not claim undelivered DEPTH behavior: ${forbiddenClaim}`
      )
    }
    assert.deepEqual(snapshot(report), before)
  } finally {
    rmSync(tempRoot, { recursive: true, force: true })
  }
})

function snapshot(path) {
  if (!existsSync(path)) return { exists: false }
  const stat = statSync(path)
  return {
    exists: true,
    size: stat.size,
    modifiedMs: stat.mtimeMs,
    content: readFileSync(path, 'utf8')
  }
}
