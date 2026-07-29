import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  symlinkSync,
  unlinkSync,
  utimesSync,
  writeFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import { basename, dirname, join, relative, resolve } from 'node:path'
import { spawnSync } from 'node:child_process'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const scriptsRoot = dirname(fileURLToPath(import.meta.url))
const platformRoot = resolve(scriptsRoot, '..')
const runner = join(scriptsRoot, 'run-trading-lab-validation.ps1')
const packageJsonPath = join(platformRoot, 'package.json')

test('Phase 4 Trading Lab validation runner exists', () => {
  assert.equal(
    existsSync(runner),
    true,
    `missing Phase 4 runner: ${runner}`,
  )
})

test('package exposes the one-command validation:run alias', () => {
  const packageJson = JSON.parse(readFileSync(packageJsonPath, 'utf8'))
  assert.equal(
    packageJson.scripts?.['validation:run'],
    'powershell -ExecutionPolicy Bypass -File scripts/run-trading-lab-validation.ps1',
  )
})

test('runner source freezes the destructive safety contract', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing; the existence test is the intentional RED')
    return
  }
  const source = readFileSync(runner, 'utf8')

  for (const required of [
    'docker-compose.validation.yml',
    'fx-trading-validation',
    '.run-logs',
    'trading-lab',
    'try',
    'finally',
    "'status', '--short'",
    'Stop-TradingLabValidationProject',
    'Assert-TradingLabOwnedArtifactDirectory',
    'Read-StrictTradingLabSurefireSummary',
  ]) {
    assert.equal(source.includes(required), true, `missing runner contract: ${required}`)
  }

  assert.doesNotMatch(source, /\bdown\b[^\r\n]*\s-v(?:\s|$)/iu)
  assert.doesNotMatch(source, /Remove-Item[^\r\n]*(?:\*|\?)/iu)
  assert.doesNotMatch(
    source,
    /(?:FilePath|Executable)\s*=\s*['"]git(?:\.exe)?['"][\s\S]{0,180}Arguments\s*=\s*@\([^)]*['"](?:reset|clean|checkout|add|commit|push)['"]/iu,
  )
})

test('contract freezes paths, ports, read-only preflight, stop argv, and Supervisor order', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const result = runPowerShell(`
    $contract = Get-TradingLabValidationContract
    $preflight = @(Get-TradingLabReadOnlyPreflightSpecs | ForEach-Object {
      [pscustomobject]@{
        executable = $_.Executable
        arguments = @($_.Arguments)
      }
    })
    $stop = Get-TradingLabValidationStopSpec
    [pscustomobject]@{
      composeFile = $contract.ComposeFile
      composeProject = $contract.ComposeProject
      artifactsRoot = $contract.ArtifactsRoot
      mainBackendPort = $contract.MainBackendPort
      validationRelayPort = $contract.ValidationRelayPort
      supervisorPort = $contract.SupervisorPort
      supervisorActions = @(Get-TradingLabSupervisorActionOrder)
      stopExecutable = $stop.Executable
      stopArguments = @($stop.Arguments)
      preflight = $preflight
    } | ConvertTo-Json -Depth 8 -Compress
  `)
  assertPowerShellSucceeded(result)
  const contract = parseLastJson(result.stdout)

  assert.equal(resolve(contract.composeFile), join(platformRoot, 'infra', 'docker-compose.validation.yml'))
  assert.equal(contract.composeProject, 'fx-trading-validation')
  assert.equal(resolve(contract.artifactsRoot), join(platformRoot, '.run-logs', 'trading-lab'))
  assert.equal(contract.mainBackendPort, 18086)
  assert.notEqual(contract.mainBackendPort, 8080)
  assert.equal(contract.validationRelayPort, 18087)
  assert.equal(contract.supervisorPort, 18088)
  assert.deepEqual(contract.supervisorActions, ['start', 'status', 'health'])
  assert.equal(contract.stopExecutable, 'docker')
  assert.deepEqual(contract.stopArguments, [
    'compose',
    '-p',
    'fx-trading-validation',
    '-f',
    join(platformRoot, 'infra', 'docker-compose.validation.yml'),
    'stop',
  ])

  const gitStatus = contract.preflight.find((entry) =>
    entry.executable === 'git'
    && entry.arguments.at(-2) === 'status'
    && entry.arguments.at(-1) === '--short')
  assert.ok(gitStatus, 'preflight must record git status --short')
  const flattened = contract.preflight
    .flatMap((entry) => [entry.executable, ...entry.arguments])
    .join(' ')
  assert.doesNotMatch(flattened, /\b(?:reset|clean|checkout|add|commit|push)\b/iu)
})

test('process launch spec selects one canonical first PATH application match', () => {
  const fixtureRoot = mkdtempSync(join(tmpdir(), 'trading-lab-launch-spec-'))
  const firstBin = join(fixtureRoot, 'first')
  const secondBin = join(fixtureRoot, 'second')
  const probeName = 'trading-lab-duplicate-probe'
  const executableNames = [`${probeName}.exe`, 'npm.cmd', 'mvn.cmd']
  mkdirSync(firstBin, { recursive: true })
  mkdirSync(secondBin, { recursive: true })

  try {
    for (const name of executableNames) {
      writeFileSync(join(firstBin, name), '', 'utf8')
      writeFileSync(join(secondBin, name), '', 'utf8')
    }
    const result = runPowerShell(`
      $originalPath = $env:Path
      try {
        $env:Path = ${psQuote(firstBin)} + [System.IO.Path]::PathSeparator + ${psQuote(secondBin)}
        $nativeMatches = @(Get-Command ${psQuote(probeName)} -CommandType Application -ErrorAction Stop)
        $npmMatches = @(Get-Command 'npm.cmd' -CommandType Application -ErrorAction Stop)
        $mvnMatches = @(Get-Command 'mvn.cmd' -CommandType Application -ErrorAction Stop)
        $native = Get-TradingLabProcessLaunchSpec -Executable ${psQuote(probeName)} -Arguments @('--check')
        $npm = Get-TradingLabProcessLaunchSpec -Executable 'npm' -Arguments @('--version')
        $mvn = Get-TradingLabProcessLaunchSpec -Executable 'mvn' -Arguments @('--version')
        [pscustomobject]@{
          nativeMatchCount = $nativeMatches.Count
          npmMatchCount = $npmMatches.Count
          mvnMatchCount = $mvnMatches.Count
          nativeFileName = $native.FileName
          nativeFileNameExists = Test-Path -LiteralPath $native.FileName -PathType Leaf
          npmFileName = $npm.FileName
          npmArguments = $npm.Arguments
          mvnFileName = $mvn.FileName
          mvnArguments = $mvn.Arguments
        } | ConvertTo-Json -Depth 4 -Compress
      } finally {
        $env:Path = $originalPath
      }
    `)
    assertPowerShellSucceeded(result)
    const contract = parseLastJson(result.stdout)

    assert.equal(contract.nativeMatchCount, 2)
    assert.equal(contract.npmMatchCount, 2)
    assert.equal(contract.mvnMatchCount, 2)
    assert.equal(typeof contract.nativeFileName, 'string')
    assert.equal(resolve(contract.nativeFileName), resolve(firstBin, `${probeName}.exe`))
    assert.equal(contract.nativeFileNameExists, true)
    assert.equal(resolve(contract.npmFileName), resolve(process.env.ComSpec))
    assert.equal(contract.npmArguments.includes(resolve(firstBin, 'npm.cmd')), true)
    assert.equal(contract.npmArguments.includes(resolve(secondBin, 'npm.cmd')), false)
    assert.equal(resolve(contract.mvnFileName), resolve(process.env.ComSpec))
    assert.equal(contract.mvnArguments.includes(resolve(firstBin, 'mvn.cmd')), true)
    assert.equal(contract.mvnArguments.includes(resolve(secondBin, 'mvn.cmd')), false)
  } finally {
    rmSync(fixtureRoot, { recursive: true, force: true })
  }
})

test('runner source is ASCII-safe for Windows PowerShell 5.1 reports', () => {
  const source = readFileSync(runner, 'utf8')
  assert.doesNotMatch(source, /[^\x00-\x7f]/u)
  assert.match(
    source,
    /"- \$\(\$command\.Name\): \$\(\$command\.Classification\) - \$\(\$command\.Reason\)"/u,
  )
})

test('normal runner phase plan is exactly the required 1..15 order', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const result = runPowerShell(`
    @(Get-TradingLabNormalPhasePlan) | ConvertTo-Json -Depth 6 -Compress
  `)
  assertPowerShellSucceeded(result)
  assert.deepEqual(parseLastJson(result.stdout), [
    { Number: 1, Name: 'preflight-and-dirty-tree-snapshot' },
    { Number: 2, Name: 'backend-unit-tests-before-docker' },
    { Number: 3, Name: 'build-validation-image' },
    { Number: 4, Name: 'start-validation-compose' },
    { Number: 5, Name: 'start-supervisor' },
    { Number: 6, Name: 'start-or-verify-main-backend-and-admin' },
    { Number: 7, Name: 'explicit-validation-http-integration-tests' },
    { Number: 8, Name: 'admin-tests-and-build' },
    { Number: 9, Name: 'web-tests-and-build' },
    { Number: 10, Name: 'architecture-verification' },
    { Number: 11, Name: 'browser-smoke' },
    { Number: 12, Name: 'large-report-and-isolation-checks' },
    { Number: 13, Name: 'required-full-backend-tests' },
    { Number: 14, Name: 'report-generation' },
    { Number: 15, Name: 'cleanup-and-stop' },
  ])
})

test('artifact ownership rejects traversal, marker mismatch, and reparse-point escape', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const fixtureRoot = mkdtempSync(join(tmpdir(), 'trading-lab-runner-artifacts-'))
  const artifactsRoot = join(fixtureRoot, 'artifacts')
  const owned = join(artifactsRoot, '2026-07-25T00-00-00Z-deadbeef')
  const outside = join(fixtureRoot, 'outside')
  const token = 'task1-owned-artifact-token-0123456789abcdef'
  mkdirSync(outside, { recursive: true })

  try {
    const created = runPowerShell(`
      $created = New-TradingLabOwnedArtifactDirectory -ArtifactsRoot ${psQuote(artifactsRoot)} -ArtifactsDirectory ${psQuote(owned)} -RunToken ${psQuote(token)}
      $checked = Assert-TradingLabOwnedArtifactDirectory -ArtifactsRoot ${psQuote(artifactsRoot)} -ArtifactsDirectory $created -RunToken ${psQuote(token)}
      [pscustomobject]@{
        created = $created
        checked = $checked
      } | ConvertTo-Json -Compress
    `)
    assertPowerShellSucceeded(created)
    const paths = parseLastJson(created.stdout)
    assert.equal(resolve(paths.created), owned)
    assert.equal(resolve(paths.checked), owned)

    const markerPath = join(owned, '.trading-lab-owner.json')
    const marker = JSON.parse(readFileSync(markerPath, 'utf8'))
    assert.deepEqual(marker, {
      schemaVersion: 1,
      runToken: token,
    })

    const traversal = runPowerShell(`
      Resolve-TradingLabArtifactDirectory -ArtifactsRoot ${psQuote(artifactsRoot)} -ArtifactsDirectory ${psQuote(outside)} | Out-Null
    `)
    assert.notEqual(traversal.status, 0, 'artifact traversal escaped the fixed root')
    assert.match(`${traversal.stdout}\n${traversal.stderr}`, /outside|artifact root/iu)

    const mismatch = runPowerShell(`
      Assert-TradingLabOwnedArtifactDirectory -ArtifactsRoot ${psQuote(artifactsRoot)} -ArtifactsDirectory ${psQuote(owned)} -RunToken 'wrong-owner-token-0123456789abcdef' | Out-Null
    `)
    assert.notEqual(mismatch.status, 0, 'marker mismatch was accepted')
    assert.match(`${mismatch.stdout}\n${mismatch.stderr}`, /marker|owner/iu)

    const reparseTarget = join(outside, 'reparse-target')
    const reparsePath = join(artifactsRoot, 'reparse-owned')
    mkdirSync(reparseTarget, { recursive: true })
    writeFileSync(
      join(reparseTarget, '.trading-lab-owner.json'),
      `${JSON.stringify({ schemaVersion: 1, runToken: token })}\n`,
      'utf8',
    )
    symlinkSync(reparseTarget, reparsePath, 'junction')

    const reparse = runPowerShell(`
      Assert-TradingLabOwnedArtifactDirectory -ArtifactsRoot ${psQuote(artifactsRoot)} -ArtifactsDirectory ${psQuote(reparsePath)} -RunToken ${psQuote(token)} | Out-Null
    `)
    assert.notEqual(reparse.status, 0, 'reparse-point artifact escape was accepted')
    assert.match(`${reparse.stdout}\n${reparse.stderr}`, /reparse/iu)
  } finally {
    rmSync(fixtureRoot, { recursive: true, force: true })
  }
})

test('strict Surefire parser rejects stale, structurally false, hidden, and dumped results', async (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const fixtureRoot = mkdtempSync(join(tmpdir(), 'trading-lab-runner-surefire-'))
  const dumpRoot = join(fixtureRoot, 'target')
  const reportsRoot = join(dumpRoot, 'surefire-reports')
  mkdirSync(reportsRoot, { recursive: true })
  const requiredClass = 'com.fxplatform.tradinglab.e2e.TradingLabSpotHttpIT'
  const reportPath = join(reportsRoot, `TEST-${requiredClass}.xml`)
  const invocationStartedAt = new Date(Date.now() - 10_000)

  const readSummary = (classes = [requiredClass]) => runPowerShell(`
    Read-StrictTradingLabSurefireSummary -ReportsRoot ${psQuote(reportsRoot)} -RequiredClasses @(${classes.map(psQuote).join(', ')}) -InvocationStartedAtUtc ([DateTimeOffset]::Parse(${psQuote(invocationStartedAt.toISOString())})) -DumpRoot ${psQuote(dumpRoot)} | ConvertTo-Json -Depth 6 -Compress
  `)

  try {
    writeSurefire(reportPath, { tests: 2, requiredClass })
    const valid = readSummary()
    assertPowerShellSucceeded(valid)
    assert.deepEqual(parseLastJson(valid.stdout), {
      Tests: 2,
      Failures: 0,
      Errors: 0,
      Skipped: 0,
      Classes: 1,
    })

    await t.test('missing class', () => {
      const result = readSummary(['com.fxplatform.tradinglab.e2e.MissingHttpIT'])
      assert.notEqual(result.status, 0)
      assert.match(`${result.stdout}\n${result.stderr}`, /missing.*Surefire/iu)
    })

    for (const entry of [
      { name: 'zero tests', values: { tests: 0 }, pattern: /tests=0|zero tests/iu },
      { name: 'failure', values: { tests: 1, failures: 1 }, pattern: /failures=1/iu },
      { name: 'error', values: { tests: 1, errors: 1 }, pattern: /errors=1/iu },
      { name: 'skipped', values: { tests: 1, skipped: 1 }, pattern: /skipped=1/iu },
    ]) {
      await t.test(entry.name, () => {
        writeSurefire(reportPath, { ...entry.values, requiredClass })
        const result = readSummary()
        assert.notEqual(result.status, 0, `strict gate accepted ${entry.name}`)
        assert.match(`${result.stdout}\n${result.stderr}`, entry.pattern)
      })
    }

    await t.test('stale report mtime', () => {
      writeSurefire(reportPath, { tests: 1, requiredClass })
      const stale = new Date(invocationStartedAt.getTime() - 60_000)
      utimesSync(reportPath, stale, stale)
      const result = readSummary()
      assert.notEqual(result.status, 0, 'strict gate accepted a stale XML')
      assert.match(`${result.stdout}\n${result.stderr}`, /stale|invocation/iu)
    })

    await t.test('testsuite name mismatch', () => {
      writeSurefire(reportPath, {
        tests: 1,
        requiredClass,
        suiteName: 'com.fxplatform.tradinglab.e2e.OtherHttpIT',
      })
      const result = readSummary()
      assert.notEqual(result.status, 0, 'strict gate accepted the wrong suite name')
      assert.match(`${result.stdout}\n${result.stderr}`, /suite.*name|name.*match/iu)
    })

    await t.test('direct testcase count mismatch', () => {
      writeSurefire(reportPath, {
        tests: 2,
        requiredClass,
        testcases: [{ classname: requiredClass, name: 'onlyOne' }],
      })
      const result = readSummary()
      assert.notEqual(result.status, 0, 'strict gate trusted the root count')
      assert.match(`${result.stdout}\n${result.stderr}`, /testcase.*count|count.*testcase/iu)
    })

    await t.test('testcase classname mismatch', () => {
      writeSurefire(reportPath, {
        tests: 1,
        requiredClass,
        testcases: [{
          classname: 'com.fxplatform.tradinglab.e2e.OtherHttpIT',
          name: 'wrongClass',
        }],
      })
      const result = readSummary()
      assert.notEqual(result.status, 0, 'strict gate accepted another class testcase')
      assert.match(`${result.stdout}\n${result.stderr}`, /classname/iu)
    })

    await t.test('empty testcase name', () => {
      writeSurefire(reportPath, {
        tests: 1,
        requiredClass,
        testcases: [{ classname: requiredClass, name: '' }],
      })
      const result = readSummary()
      assert.notEqual(result.status, 0, 'strict gate accepted an unnamed testcase')
      assert.match(`${result.stdout}\n${result.stderr}`, /testcase.*name|name.*testcase/iu)
    })

    for (const node of ['failure', 'error', 'skipped']) {
      await t.test(`hidden ${node} node`, () => {
        writeSurefire(reportPath, {
          tests: 1,
          requiredClass,
          testcases: [{
            classname: requiredClass,
            name: `hidden-${node}`,
            childXml: `<${node} message="hidden"/>`,
          }],
        })
        const result = readSummary()
        assert.notEqual(result.status, 0, `strict gate accepted hidden ${node}`)
        assert.match(`${result.stdout}\n${result.stderr}`, /hidden|failure|error|skipped/iu)
      })
    }

    for (const extension of ['dump', 'dumpstream']) {
      await t.test(`Surefire .${extension} artifact`, () => {
        writeSurefire(reportPath, { tests: 1, requiredClass })
        const dumpPath = join(dumpRoot, `2026-07-25-jvmRun1.${extension}`)
        writeFileSync(dumpPath, 'unexpected fork artifact', 'utf8')
        try {
          const result = readSummary()
          assert.notEqual(result.status, 0, `strict gate accepted .${extension}`)
          assert.match(`${result.stdout}\n${result.stderr}`, /dump/iu)
        } finally {
          rmSync(dumpPath, { force: true })
        }
      })
    }
  } finally {
    rmSync(fixtureRoot, { recursive: true, force: true })
  }
})

test('full Maven evidence discovers every fresh Surefire XML when required classes are omitted', () => {
  if (!existsSync(runner)) {
    return
  }
  const fixtureRoot = mkdtempSync(join(tmpdir(), 'trading-lab-full-surefire-'))
  const backendRoot = join(fixtureRoot, 'backend')
  const targetRoot = join(backendRoot, 'target')
  const reportsRoot = join(targetRoot, 'surefire-reports')
  const evidenceRoot = join(fixtureRoot, 'evidence')
  const requiredClasses = [
    'com.fxplatform.tradinglab.e2e.TradingLabSpotHttpIT',
    'com.fxplatform.validation.ValidationProfileSafetyTest',
  ]
  const invocationStartedAt = new Date(Date.now() - 10_000)
  mkdirSync(reportsRoot, { recursive: true })

  try {
    writeSurefire(
      join(reportsRoot, `TEST-${requiredClasses[0]}.xml`),
      { tests: 2, requiredClass: requiredClasses[0] },
    )
    writeSurefire(
      join(reportsRoot, `TEST-${requiredClasses[1]}.xml`),
      { tests: 1, requiredClass: requiredClasses[1] },
    )
    const result = runPowerShell(`
      $command = New-TradingLabNormalCommandSpec -PhaseNumber 13 -PhaseName 'required-full-backend-tests' -Id 'required-full-backend-tests' -Operation 'MAVEN_TEST' -Executable 'mvn' -Arguments @('clean', 'test') -WorkingDirectory ${psQuote(backendRoot)} -DestinationPath ${psQuote(evidenceRoot)}
      $summary = Assert-TradingLabMavenEvidence -Command $command -InvocationStartedAtUtc ([DateTimeOffset]::Parse(${psQuote(invocationStartedAt.toISOString())}))
      [pscustomobject]@{
        requiredTestClasses = @($command.RequiredTestClasses)
        summary = $summary
      } | ConvertTo-Json -Depth 6 -Compress
    `)
    assertPowerShellSucceeded(result)
    const evidence = parseLastJson(result.stdout)
    assert.deepEqual(evidence.requiredTestClasses, [])
    assert.deepEqual(evidence.summary, {
      Tests: 3,
      Failures: 0,
      Errors: 0,
      Skipped: 0,
      Classes: 2,
    })
  } finally {
    rmSync(fixtureRoot, { recursive: true, force: true })
  }
})

test('orchestration always orders Supervisor actions and stops in finally on failure', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const result = runPowerShell(`
    $events = [System.Collections.Generic.List[string]]::new()
    try {
      $invoke = @{
        InvokeSupervisorAction = {
          param($action)
          $events.Add("supervisor:$action") | Out-Null
        }
        RunBody = {
          $events.Add('body') | Out-Null
          throw 'intentional body failure'
        }
        StopValidation = {
          $events.Add('stop') | Out-Null
        }
      }
      Invoke-TradingLabValidationContract @invoke
    } catch {
      $events.Add('caught') | Out-Null
    }
    @($events) | ConvertTo-Json -Compress
  `)
  assertPowerShellSucceeded(result)
  assert.deepEqual(parseLastJson(result.stdout), [
    'supervisor:start',
    'supervisor:status',
    'supervisor:health',
    'body',
    'stop',
    'caught',
  ])
})

test('Supervisor health retries only transient startup failures within a strict deadline', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const result = runPowerShell(`
    $token = 'fixture-supervisor-health-token-0123456789abcdef'
    $contract = Get-TradingLabValidationContract
    $parsedErrorCodes = [pscustomobject]@{
      unavailable = ConvertFrom-TradingLabSupervisorErrorJson -Json '{"ok":false,"error":{"code":"HEALTH_UNAVAILABLE"}}'
      timeout = ConvertFrom-TradingLabSupervisorErrorJson -Json '{"ok":false,"error":{"code":"HEALTH_TIMEOUT"}}'
      invalidResponse = ConvertFrom-TradingLabSupervisorErrorJson -Json '{"ok":false,"error":{"code":"HEALTH_INVALID_RESPONSE"}}'
      malformedIsNull = $null -eq (
        ConvertFrom-TradingLabSupervisorErrorJson -Json 'not-json'
      )
      lowercaseIsNull = $null -eq (
        ConvertFrom-TradingLabSupervisorErrorJson -Json '{"ok":false,"error":{"code":"health_unavailable"}}'
      )
      successIsNull = $null -eq (
        ConvertFrom-TradingLabSupervisorErrorJson -Json '{"ok":true,"error":{"code":"HEALTH_UNAVAILABLE"}}'
      )
    }

    $script:transientNow = [DateTimeOffset]::Parse('2026-07-26T00:00:00Z')
    $script:transientCalls = [System.Collections.Generic.List[object]]::new()
    $script:transientSleeps = [System.Collections.Generic.List[int]]::new()
    $transientBody = Invoke-TradingLabSupervisorAction -Action 'health' -Token $token -HealthDeadlineSeconds 5 -InitialHealthBackoffMilliseconds 100 -MaximumHealthBackoffMilliseconds 200 -InvokeOnce {
      param($action, $receivedToken, $requestTimeoutMilliseconds)
      $script:transientCalls.Add([pscustomobject]@{
        action = $action
        tokenMatches = $receivedToken -ceq $token
        requestTimeoutMilliseconds = $requestTimeoutMilliseconds
      }) | Out-Null
      switch ($script:transientCalls.Count) {
        1 {
          throw (New-TradingLabSupervisorHttpFailure -StatusCode 502 -ErrorCode 'HEALTH_UNAVAILABLE')
        }
        2 {
          throw (New-TradingLabSupervisorHttpFailure -StatusCode 503 -ErrorCode 'HEALTH_UNAVAILABLE')
        }
        3 {
          throw (New-TradingLabSupervisorHttpFailure -StatusCode 504 -ErrorCode 'HEALTH_TIMEOUT')
        }
        4 {
          throw ([System.Net.WebException]::new(
            'connection not ready',
            [System.Net.WebExceptionStatus]::ConnectFailure
          ))
        }
        default {
          return '{"ok":true,"action":"health"}'
        }
      }
    } -UtcNow {
      $script:transientNow
    } -Sleep {
      param($milliseconds)
      $script:transientSleeps.Add($milliseconds) | Out-Null
      $script:transientNow = $script:transientNow.AddMilliseconds($milliseconds)
    }

    $permanentCases = @(
      [pscustomobject]@{ statusCode = 502; errorCode = 'HEALTH_INVALID_RESPONSE' },
      [pscustomobject]@{ statusCode = 502; errorCode = 'HEALTH_RESPONSE_LIMIT' },
      [pscustomobject]@{ statusCode = 401; errorCode = 'UNAUTHORIZED' },
      [pscustomobject]@{ statusCode = 403; errorCode = 'FORBIDDEN' },
      [pscustomobject]@{ statusCode = 409; errorCode = 'MUTATION_BUSY' },
      [pscustomobject]@{ statusCode = 500; errorCode = 'INTERNAL_ERROR' },
      [pscustomobject]@{ statusCode = 503; errorCode = 'SUPERVISOR_CLOSING' },
      [pscustomobject]@{ statusCode = 502; errorCode = 'HEALTH_TIMEOUT' },
      [pscustomobject]@{ statusCode = 504; errorCode = 'HEALTH_UNAVAILABLE' }
    )
    $permanent = @(
      foreach ($case in $permanentCases) {
        $script:permanentStatusCode = $case.statusCode
        $script:permanentErrorCode = $case.errorCode
        $script:permanentCalls = 0
        $script:permanentSleeps = 0
        $failure = try {
          Invoke-TradingLabSupervisorAction -Action 'health' -Token $token -HealthDeadlineSeconds 5 -InvokeOnce {
            param($action, $receivedToken, $requestTimeoutMilliseconds)
            $script:permanentCalls++
            throw (New-TradingLabSupervisorHttpFailure -StatusCode $script:permanentStatusCode -ErrorCode $script:permanentErrorCode)
          } -UtcNow {
            [DateTimeOffset]::Parse('2026-07-26T00:00:00Z')
          } -Sleep {
            param($milliseconds)
            $script:permanentSleeps++
          } | Out-Null
          $null
        } catch {
          $_.Exception
        }
        [pscustomobject]@{
          statusCode = $case.statusCode
          errorCode = $case.errorCode
          calls = $script:permanentCalls
          sleeps = $script:permanentSleeps
          message = $failure.Message
        }
      }
    )

    $script:deadlineNow = [DateTimeOffset]::Parse('2026-07-26T00:00:00Z')
    $script:deadlineCalls = 0
    $script:deadlineSleeps = [System.Collections.Generic.List[int]]::new()
    $deadlineFailure = try {
      Invoke-TradingLabSupervisorAction -Action 'health' -Token $token -HealthDeadlineSeconds 1 -InitialHealthBackoffMilliseconds 600 -MaximumHealthBackoffMilliseconds 600 -InvokeOnce {
        param($action, $receivedToken, $requestTimeoutMilliseconds)
        $script:deadlineCalls++
        throw (New-TradingLabSupervisorHttpFailure -StatusCode 502 -ErrorCode 'HEALTH_UNAVAILABLE')
      } -UtcNow {
        $script:deadlineNow
      } -Sleep {
        param($milliseconds)
        $script:deadlineSleeps.Add($milliseconds) | Out-Null
        $script:deadlineNow = $script:deadlineNow.AddMilliseconds($milliseconds)
      } | Out-Null
      $null
    } catch {
      $_.Exception
    }

    $script:startCalls = 0
    $startFailure = try {
      Invoke-TradingLabSupervisorAction -Action 'start' -Token $token -InvokeOnce {
        param($action, $receivedToken, $requestTimeoutMilliseconds)
        $script:startCalls++
        throw (New-TradingLabSupervisorHttpFailure -StatusCode 502 -ErrorCode 'HEALTH_UNAVAILABLE')
      } | Out-Null
      $null
    } catch {
      $_.Exception
    }

    [pscustomobject]@{
      contract = [pscustomobject]@{
        deadlineSeconds = $contract.SupervisorHealthDeadlineSeconds
        initialBackoffMilliseconds = $contract.SupervisorHealthInitialBackoffMilliseconds
        maximumBackoffMilliseconds = $contract.SupervisorHealthMaximumBackoffMilliseconds
      }
      parsedErrorCodes = $parsedErrorCodes
      transient = [pscustomobject]@{
        body = $transientBody
        calls = @($script:transientCalls)
        sleeps = @($script:transientSleeps)
      }
      permanent = $permanent
      deadline = [pscustomobject]@{
        calls = $script:deadlineCalls
        sleeps = @($script:deadlineSleeps)
        message = $deadlineFailure.Message
        innerMessage = $deadlineFailure.InnerException.Message
        leakedToken = (
          $deadlineFailure.ToString().IndexOf(
            $token,
            [System.StringComparison]::Ordinal
          ) -ge 0
        )
      }
      start = [pscustomobject]@{
        calls = $script:startCalls
        message = $startFailure.Message
      }
    } | ConvertTo-Json -Depth 12 -Compress
  `)
  assertPowerShellSucceeded(result)
  const evidence = parseLastJson(result.stdout)

  assert.deepEqual(evidence.contract, {
    deadlineSeconds: 300,
    initialBackoffMilliseconds: 500,
    maximumBackoffMilliseconds: 5000,
  })
  assert.equal(evidence.contract.deadlineSeconds >= 270, true)
  assert.deepEqual(evidence.parsedErrorCodes, {
    unavailable: 'HEALTH_UNAVAILABLE',
    timeout: 'HEALTH_TIMEOUT',
    invalidResponse: 'HEALTH_INVALID_RESPONSE',
    malformedIsNull: true,
    lowercaseIsNull: true,
    successIsNull: true,
  })
  assert.equal(evidence.transient.body, '{"ok":true,"action":"health"}')
  assert.deepEqual(
    evidence.transient.calls.map(({ action, tokenMatches }) => ({
      action,
      tokenMatches,
    })),
    [
      { action: 'health', tokenMatches: true },
      { action: 'health', tokenMatches: true },
      { action: 'health', tokenMatches: true },
      { action: 'health', tokenMatches: true },
      { action: 'health', tokenMatches: true },
    ],
  )
  assert.deepEqual(
    evidence.transient.calls.map(({ requestTimeoutMilliseconds }) =>
      requestTimeoutMilliseconds),
    [5000, 4900, 4700, 4500, 4300],
  )
  assert.deepEqual(evidence.transient.sleeps, [100, 200, 200, 200])

  assert.deepEqual(
    evidence.permanent.map(({ statusCode, errorCode, calls, sleeps }) => ({
      statusCode,
      errorCode,
      calls,
      sleeps,
    })),
    [
      { statusCode: 502, errorCode: 'HEALTH_INVALID_RESPONSE', calls: 1, sleeps: 0 },
      { statusCode: 502, errorCode: 'HEALTH_RESPONSE_LIMIT', calls: 1, sleeps: 0 },
      { statusCode: 401, errorCode: 'UNAUTHORIZED', calls: 1, sleeps: 0 },
      { statusCode: 403, errorCode: 'FORBIDDEN', calls: 1, sleeps: 0 },
      { statusCode: 409, errorCode: 'MUTATION_BUSY', calls: 1, sleeps: 0 },
      { statusCode: 500, errorCode: 'INTERNAL_ERROR', calls: 1, sleeps: 0 },
      { statusCode: 503, errorCode: 'SUPERVISOR_CLOSING', calls: 1, sleeps: 0 },
      { statusCode: 502, errorCode: 'HEALTH_TIMEOUT', calls: 1, sleeps: 0 },
      { statusCode: 504, errorCode: 'HEALTH_UNAVAILABLE', calls: 1, sleeps: 0 },
    ],
  )
  for (const entry of evidence.permanent) {
    assert.match(entry.message, new RegExp(`HTTP ${entry.statusCode}`, 'u'))
    assert.match(entry.message, new RegExp(entry.errorCode, 'u'))
  }

  assert.equal(evidence.deadline.calls, 2)
  assert.deepEqual(evidence.deadline.sleeps, [600, 400])
  assert.match(evidence.deadline.message, /health.*deadline|deadline.*health/iu)
  assert.match(evidence.deadline.innerMessage, /HTTP 502/iu)
  assert.equal(evidence.deadline.leakedToken, false)

  assert.equal(evidence.start.calls, 1)
  assert.match(evidence.start.message, /HTTP 502/iu)
})

test('main Trading Lab queue idle gate is ordered, bounded, and fails closed', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const artifactsRoot = mkdtempSync(
    join(tmpdir(), 'trading-lab-main-queue-gate-artifacts-'),
  )
  t.after(() => rmSync(artifactsRoot, { recursive: true, force: true }))
  const gatePassStdout = join(artifactsRoot, 'gate-pass.stdout.jsonl')
  const gatePassStderr = join(artifactsRoot, 'gate-pass.stderr.log')
  const gateInterruptedStdout = join(
    artifactsRoot,
    'gate-interrupted.stdout.jsonl',
  )
  const gateInterruptedStderr = join(
    artifactsRoot,
    'gate-interrupted.stderr.log',
  )
  const gateDriftStdout = join(artifactsRoot, 'gate-drift.stdout.jsonl')
  const gateDriftStderr = join(artifactsRoot, 'gate-drift.stderr.log')
  const gateDefaultStdout = join(artifactsRoot, 'gate-default.stdout.jsonl')
  const gateDefaultStderr = join(artifactsRoot, 'gate-default.stderr.log')
  const result = runPowerShell(`
    $valid = ConvertFrom-TradingLabMainQueueSnapshotJson -Json '{"nonterminalCount":2,"states":{"RESETTING":1,"CLEANING":1}}'
    $invalidJson = @(
      '{"nonterminalCount":1,"states":{"COMPLETED":1}}',
      '{"nonterminalCount":1,"states":{"RUNNING":2}}',
      '{"nonterminalCount":-1,"states":{}}',
      '{"nonterminalCount":0,"states":{},"extra":true}'
    )
    $invalidMessages = @(
      foreach ($json in $invalidJson) {
        try {
          ConvertFrom-TradingLabMainQueueSnapshotJson -Json $json | Out-Null
          'accepted'
        } catch {
          $_.Exception.Message
        }
      }
    )
    $containerId = 'a' * 64
    $script:identityCall = $null
    $identity = Get-TradingLabMainPostgresIdentity -TimeoutMilliseconds 777 -InvokeOnce {
      param($arguments, $timeoutMilliseconds)
      $script:identityCall = [pscustomobject]@{
        arguments = @($arguments)
        timeoutMilliseconds = $timeoutMilliseconds
      }
      [pscustomobject]@{
        ExitCode = 0
        Stdout = "$containerId|/fx-platform-postgres|true"
        Stderr = ''
      }
    }
    $script:queryCall = $null
    $querySnapshot = Invoke-TradingLabMainQueueSnapshotQuery -ContainerId $containerId -TimeoutMilliseconds 888 -InvokeOnce {
      param($arguments, $timeoutMilliseconds)
      $script:queryCall = [pscustomobject]@{
        arguments = @($arguments)
        timeoutMilliseconds = $timeoutMilliseconds
      }
      [pscustomobject]@{
        ExitCode = 0
        Stdout = '{"nonterminalCount":1,"states":{"RUNNING":1}}'
        Stderr = ''
      }
    }
    $boundedStarted = [DateTimeOffset]::UtcNow
    $boundedFailure = try {
      Invoke-TradingLabBoundedProcessCapture -Executable 'powershell.exe' -Arguments @(
        '-NoProfile',
        '-Command',
        'Start-Sleep -Seconds 5'
      ) -WorkingDirectory ${psQuote(platformRoot)} -TimeoutMilliseconds 100 | Out-Null
      $null
    } catch {
      $_.Exception
    }
    $boundedElapsedMilliseconds = [long][Math]::Floor(
      ([DateTimeOffset]::UtcNow - $boundedStarted).TotalMilliseconds
    )

    $snapshots = [System.Collections.Generic.Queue[object]]::new()
    $snapshots.Enqueue([pscustomobject]@{
      NonterminalCount = 2
      States = [pscustomobject]@{ RESETTING = 1; CLEANING = 1 }
    })
    $snapshots.Enqueue([pscustomobject]@{
      NonterminalCount = 1
      States = [pscustomobject]@{ CLEANING = 1 }
    })
    $snapshots.Enqueue([pscustomobject]@{
      NonterminalCount = 0
      States = [pscustomobject]@{}
    })
    $snapshots.Enqueue([pscustomobject]@{
      NonterminalCount = 0
      States = [pscustomobject]@{}
    })
    $script:idleNow = [DateTimeOffset]::Parse('2026-07-27T06:00:00Z')
    $script:idleReads = 0
    $script:idleSleeps = [System.Collections.Generic.List[int]]::new()
    $script:idleObservations = [System.Collections.Generic.List[object]]::new()
    $idle = Wait-TradingLabMainQueueIdle -DeadlineSeconds 5 -PollMilliseconds 250 -ReadSnapshot {
      $script:idleReads++
      $script:idleNow = $script:idleNow.AddMilliseconds(20)
      $snapshots.Dequeue()
    } -UtcNow {
      $script:idleNow
    } -Sleep {
      param($milliseconds)
      $script:idleSleeps.Add($milliseconds) | Out-Null
      $script:idleNow = $script:idleNow.AddMilliseconds($milliseconds)
    } -OnObservation {
      param($observation)
      $script:idleObservations.Add($observation) | Out-Null
    }

    $script:timeoutNow = [DateTimeOffset]::Parse('2026-07-27T06:00:00Z')
    $script:timeoutReads = 0
    $timeoutFailure = try {
      Wait-TradingLabMainQueueIdle -DeadlineSeconds 1 -PollMilliseconds 400 -ReadSnapshot {
        $script:timeoutReads++
        [pscustomobject]@{
          NonterminalCount = 1
          States = [pscustomobject]@{ RUNNING = 1 }
        }
      } -UtcNow {
        $script:timeoutNow
      } -Sleep {
        param($milliseconds)
        $script:timeoutNow = $script:timeoutNow.AddMilliseconds($milliseconds)
      } | Out-Null
      $null
    } catch {
      $_.Exception
    }

    $script:mainQueueContainerId = $containerId
    $script:gatePassSnapshots = [System.Collections.Generic.Queue[object]]::new()
    $script:gatePassSnapshots.Enqueue([pscustomobject]@{
      NonterminalCount = 1
      States = [pscustomobject]@{ RUNNING = 1 }
    })
    $script:gatePassSnapshots.Enqueue([pscustomobject]@{
      NonterminalCount = 0
      States = [pscustomobject]@{}
    })
    $script:gatePassSnapshots.Enqueue([pscustomobject]@{
      NonterminalCount = 0
      States = [pscustomobject]@{}
    })
    $script:gatePassNow = [DateTimeOffset]::Parse('2026-07-27T06:10:00Z')
    $script:gatePassIdentityCalls = 0
    $script:gatePassHeaderObserved = $false
    $gatePassParameters = @{
      StdoutPath = ${psQuote(gatePassStdout)}
      StderrPath = ${psQuote(gatePassStderr)}
      DeadlineSeconds = 5
      PollMilliseconds = 250
      GetIdentity = {
        $script:gatePassIdentityCalls++
        $script:mainQueueContainerId
      }
      ReadSnapshot = {
        param($timeoutMilliseconds)
        if (-not (Test-Path -LiteralPath ${psQuote(gatePassStdout)} -PathType Leaf)) {
          throw 'durable gate header was not created before the first observation'
        }
        $script:gatePassHeaderObserved = $true
        $script:gatePassSnapshots.Dequeue()
      }
      UtcNow = {
        $script:gatePassNow
      }
      Sleep = {
        param($milliseconds)
        $script:gatePassNow = $script:gatePassNow.AddMilliseconds($milliseconds)
      }
    }
    $gatePassResult = Invoke-TradingLabMainQueueIdleGate @gatePassParameters
    $gatePassEvidence = @(
      Get-Content -LiteralPath ${psQuote(gatePassStdout)} |
        ForEach-Object { $_ | ConvertFrom-Json }
    )

    $script:gateInterruptedNow = [DateTimeOffset]::Parse('2026-07-27T06:20:00Z')
    $script:gateInterruptedReads = 0
    $gateInterruptedParameters = @{
      StdoutPath = ${psQuote(gateInterruptedStdout)}
      StderrPath = ${psQuote(gateInterruptedStderr)}
      DeadlineSeconds = 5
      PollMilliseconds = 250
      GetIdentity = { $script:mainQueueContainerId }
      ReadSnapshot = {
        param($timeoutMilliseconds)
        $script:gateInterruptedReads++
        if ($script:gateInterruptedReads -eq 1) {
          return [pscustomobject]@{
            NonterminalCount = 1
            States = [pscustomobject]@{ CLEANING = 1 }
          }
        }
        throw 'synthetic queue read interruption'
      }
      UtcNow = {
        $script:gateInterruptedNow
      }
      Sleep = {
        param($milliseconds)
        $script:gateInterruptedNow = (
          $script:gateInterruptedNow.AddMilliseconds($milliseconds)
        )
      }
    }
    $gateInterruptedResult = Invoke-TradingLabMainQueueIdleGate @gateInterruptedParameters
    $gateInterruptedEvidence = @(
      Get-Content -LiteralPath ${psQuote(gateInterruptedStdout)} |
        ForEach-Object { $_ | ConvertFrom-Json }
    )

    $script:driftContainerId = 'b' * 64
    $script:gateDriftNow = [DateTimeOffset]::Parse('2026-07-27T06:30:00Z')
    $script:gateDriftIdentityCalls = 0
    $gateDriftParameters = @{
      StdoutPath = ${psQuote(gateDriftStdout)}
      StderrPath = ${psQuote(gateDriftStderr)}
      DeadlineSeconds = 5
      PollMilliseconds = 250
      GetIdentity = {
        $script:gateDriftIdentityCalls++
        if ($script:gateDriftIdentityCalls -eq 1) {
          return $script:mainQueueContainerId
        }
        return $script:driftContainerId
      }
      ReadSnapshot = {
        param($timeoutMilliseconds)
        [pscustomobject]@{
          NonterminalCount = 0
          States = [pscustomobject]@{}
        }
      }
      UtcNow = {
        $script:gateDriftNow
      }
      Sleep = {
        param($milliseconds)
        $script:gateDriftNow = $script:gateDriftNow.AddMilliseconds($milliseconds)
      }
    }
    $gateDriftResult = Invoke-TradingLabMainQueueIdleGate @gateDriftParameters
    $gateDriftEvidence = @(
      Get-Content -LiteralPath ${psQuote(gateDriftStdout)} |
        ForEach-Object { $_ | ConvertFrom-Json }
    )

    $script:gateDefaultNow = [DateTimeOffset]::Parse('2026-07-27T06:40:00Z')
    $script:gateDefaultIdentityCalls = 0
    $script:gateDefaultQueryCalls = 0
    function Invoke-TradingLabMainQueueSnapshotQuery {
      [CmdletBinding()]
      param(
        [Parameter(Mandatory = $true)]
        [string]$ContainerId,
        [int]$TimeoutMilliseconds = 10000
      )
      $script:gateDefaultQueryCalls++
      if ($ContainerId -cne $script:mainQueueContainerId) {
        throw 'default gate queried an unexpected container'
      }
      [pscustomobject]@{
        NonterminalCount = 0
        States = [pscustomobject]@{}
      }
    }
    $gateDefaultParameters = @{
      StdoutPath = ${psQuote(gateDefaultStdout)}
      StderrPath = ${psQuote(gateDefaultStderr)}
      DeadlineSeconds = 5
      PollMilliseconds = 250
      GetIdentity = {
        $script:gateDefaultIdentityCalls++
        $script:mainQueueContainerId
      }
      UtcNow = {
        $script:gateDefaultNow
      }
      Sleep = {
        param($milliseconds)
        $script:gateDefaultNow = $script:gateDefaultNow.AddMilliseconds(
          $milliseconds
        )
      }
    }
    $gateDefaultResult = Invoke-TradingLabMainQueueIdleGate @gateDefaultParameters
    $gateDefaultEvidence = @(
      Get-Content -LiteralPath ${psQuote(gateDefaultStdout)} |
        ForEach-Object { $_ | ConvertFrom-Json }
    )

    $context = New-TradingLabDefaultRunContext -ArtifactsDirectory ${psQuote(artifactsRoot)} -RunToken 'main-queue-gate-owner-token-0123456789abcdef' -CandidateId 'main-queue-gate-candidate-a1'
    $phase6 = @(
      Get-TradingLabNormalPhaseSpecs -Context $context -PackageScripts @{
        'smoke:trading-lab:isolated' = 'node scripts/run-trading-lab-isolated-smoke.mjs'
      } | Where-Object Number -eq 6
    )[0]
    $gate = @($phase6.Commands | Where-Object Id -eq 'wait-main-trading-lab-idle')[0]
    [pscustomobject]@{
      valid = $valid
      invalidMessages = $invalidMessages
      identity = $identity
      identityCall = $script:identityCall
      querySnapshot = $querySnapshot
      queryCall = $script:queryCall
      bounded = [pscustomobject]@{
        type = $boundedFailure.GetType().FullName
        message = $boundedFailure.Message
        elapsedMilliseconds = $boundedElapsedMilliseconds
      }
      idle = $idle
      idleReads = $script:idleReads
      idleSleeps = @($script:idleSleeps)
      idleObservations = @($script:idleObservations)
      timeout = [pscustomobject]@{
        type = $timeoutFailure.GetType().FullName
        message = $timeoutFailure.Message
        reads = $script:timeoutReads
      }
      gatePass = [pscustomobject]@{
        result = $gatePassResult
        identityCalls = $script:gatePassIdentityCalls
        headerObserved = $script:gatePassHeaderObserved
        evidence = $gatePassEvidence
        stderr = [System.IO.File]::ReadAllText(${psQuote(gatePassStderr)})
      }
      gateInterrupted = [pscustomobject]@{
        result = $gateInterruptedResult
        reads = $script:gateInterruptedReads
        evidence = $gateInterruptedEvidence
        stderr = [System.IO.File]::ReadAllText(${psQuote(gateInterruptedStderr)})
      }
      gateDrift = [pscustomobject]@{
        result = $gateDriftResult
        identityCalls = $script:gateDriftIdentityCalls
        evidence = $gateDriftEvidence
        stderr = [System.IO.File]::ReadAllText(${psQuote(gateDriftStderr)})
      }
      gateDefault = [pscustomobject]@{
        result = $gateDefaultResult
        identityCalls = $script:gateDefaultIdentityCalls
        queryCalls = $script:gateDefaultQueryCalls
        evidence = $gateDefaultEvidence
        stderr = [System.IO.File]::ReadAllText(${psQuote(gateDefaultStderr)})
      }
      phase6Ids = @($phase6.Commands | ForEach-Object Id)
      gate = $gate
    } | ConvertTo-Json -Depth 12 -Compress
  `)
  assertPowerShellSucceeded(result)
  const contract = parseLastJson(result.stdout)
  assert.equal(contract.valid.NonterminalCount, 2)
  assert.deepEqual(contract.valid.States, {
    RESETTING: 1,
    CLEANING: 1,
  })
  assert.equal(
    contract.invalidMessages.every((message) => message !== 'accepted'),
    true,
  )
  assert.equal(contract.identity, 'a'.repeat(64))
  assert.deepEqual(contract.identityCall.arguments, [
    'container',
    'inspect',
    '--format',
    '{{.Id}}|{{.Name}}|{{.State.Running}}',
    'fx-platform-postgres',
  ])
  assert.equal(contract.identityCall.timeoutMilliseconds, 777)
  assert.deepEqual(contract.querySnapshot, {
    NonterminalCount: 1,
    States: { RUNNING: 1 },
  })
  assert.equal(contract.queryCall.timeoutMilliseconds, 888)
  assert.equal(contract.queryCall.arguments.includes('exec'), true)
  assert.equal(contract.queryCall.arguments.includes('--user'), true)
  assert.equal(contract.queryCall.arguments.includes('postgres'), true)
  assert.equal(
    contract.queryCall.arguments.includes('PGOPTIONS=-cstatement_timeout=5000'),
    true,
  )
  assert.equal(contract.queryCall.arguments.includes('a'.repeat(64)), true)
  assert.equal(
    contract.queryCall.arguments.includes('fx-platform-postgres'),
    false,
  )
  assert.equal(contract.bounded.type, 'System.TimeoutException')
  assert.match(contract.bounded.message, /did not exit within 100 milliseconds/iu)
  assert.equal(contract.bounded.elapsedMilliseconds < 2000, true)
  assert.deepEqual(contract.idle, {
    Status: 'PASS',
    InitialNonterminalCount: 2,
    InitialStates: {
      CLEANING: 1,
      RESETTING: 1,
    },
    FinalNonterminalCount: 0,
    FinalStates: {},
    Observations: 4,
    WaitedMilliseconds: 830,
  })
  assert.equal(contract.idleReads, 4)
  assert.deepEqual(contract.idleSleeps, [250, 250, 250])
  assert.deepEqual(
    contract.idleObservations.map((observation) => ({
      index: observation.Index,
      count: observation.NonterminalCount,
      states: observation.States,
    })),
    [
      { index: 1, count: 2, states: { CLEANING: 1, RESETTING: 1 } },
      { index: 2, count: 1, states: { CLEANING: 1 } },
      { index: 3, count: 0, states: {} },
      { index: 4, count: 0, states: {} },
    ],
  )
  assert.equal(contract.timeout.type, 'System.TimeoutException')
  assert.match(contract.timeout.message, /main Trading Lab queue.*1 seconds/iu)
  assert.equal(contract.timeout.reads >= 2, true)
  assert.equal(
    contract.gatePass.result.ExitCode,
    0,
    JSON.stringify(contract.gatePass, null, 2),
  )
  assert.equal(contract.gatePass.identityCalls, 2)
  assert.equal(contract.gatePass.headerObserved, true)
  assert.equal(contract.gatePass.stderr, '')
  assert.deepEqual(
    contract.gatePass.evidence.map((entry) => entry.type),
    ['header', 'observation', 'observation', 'observation', 'result'],
  )
  assert.equal(contract.gatePass.evidence[0].containerId, 'a'.repeat(64))
  assert.equal(contract.gatePass.evidence[0].status, 'RUNNING')
  assert.deepEqual(
    contract.gatePass.evidence.slice(1, 4).map((entry) => ({
      count: entry.nonterminalCount,
      states: entry.states,
      containerId: entry.containerId,
    })),
    [
      { count: 1, states: { RUNNING: 1 }, containerId: 'a'.repeat(64) },
      { count: 0, states: {}, containerId: 'a'.repeat(64) },
      { count: 0, states: {}, containerId: 'a'.repeat(64) },
    ],
  )
  assert.equal(contract.gatePass.evidence.at(-1).status, 'PASS')
  assert.equal(
    contract.gatePass.evidence.at(-1).finalContainerId,
    'a'.repeat(64),
  )
  assert.equal(contract.gateInterrupted.result.ExitCode, 1)
  assert.equal(contract.gateInterrupted.reads, 2)
  assert.deepEqual(
    contract.gateInterrupted.evidence.map((entry) => entry.type),
    ['header', 'observation', 'result'],
  )
  assert.equal(contract.gateInterrupted.evidence[1].nonterminalCount, 1)
  assert.deepEqual(contract.gateInterrupted.evidence[1].states, { CLEANING: 1 })
  assert.equal(contract.gateInterrupted.evidence.at(-1).status, 'FAIL')
  assert.match(contract.gateInterrupted.stderr, /synthetic queue read interruption/iu)
  assert.equal(contract.gateDrift.result.ExitCode, 1)
  assert.equal(contract.gateDrift.identityCalls, 2)
  assert.deepEqual(
    contract.gateDrift.evidence.map((entry) => entry.type),
    ['header', 'observation', 'observation', 'result'],
  )
  assert.equal(contract.gateDrift.evidence.at(-1).status, 'FAIL')
  assert.equal(
    contract.gateDrift.evidence.at(-1).finalContainerId,
    'b'.repeat(64),
  )
  assert.match(contract.gateDrift.stderr, /identity drifted/iu)
  assert.equal(
    contract.gateDefault.result.ExitCode,
    0,
    JSON.stringify(contract.gateDefault, null, 2),
  )
  assert.equal(contract.gateDefault.identityCalls, 2)
  assert.equal(contract.gateDefault.queryCalls, 2)
  assert.equal(contract.gateDefault.stderr, '')
  assert.deepEqual(
    contract.gateDefault.evidence.map((entry) => entry.type),
    ['header', 'observation', 'observation', 'result'],
  )
  assert.equal(contract.gateDefault.evidence.at(-1).status, 'PASS')
  assert.equal(
    contract.phase6Ids.indexOf('start-or-verify-owned-admin')
      < contract.phase6Ids.indexOf('wait-main-trading-lab-idle'),
    true,
  )
  assert.equal(
    contract.phase6Ids.at(-1),
    'wait-main-trading-lab-idle',
  )
  assert.equal(contract.gate.Operation, 'WAIT_MAIN_TRADING_LAB_IDLE')
  assert.deepEqual(contract.gate.Arguments, ['1800', '1000'])
})

test('orchestration preserves primary and cleanup failures as an AggregateException', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const result = runPowerShell(`
    $events = [System.Collections.Generic.List[string]]::new()
    try {
      Invoke-TradingLabValidationContract -InvokeSupervisorAction {
        param($action)
        $events.Add("supervisor:$action") | Out-Null
      } -RunBody {
        $events.Add('body') | Out-Null
        throw 'primary body failure'
      } -StopValidation {
        $events.Add('stop') | Out-Null
        throw 'cleanup stop failure'
      }
    } catch {
      $exception = $_.Exception
      [pscustomobject]@{
        events = @($events)
        type = $exception.GetType().FullName
        message = $exception.Message
        innerMessages = @($exception.InnerExceptions | ForEach-Object { $_.Message })
      } | ConvertTo-Json -Depth 6 -Compress
    }
  `)
  assertPowerShellSucceeded(result)
  const caught = parseLastJson(result.stdout)
  assert.deepEqual(caught.events, [
    'supervisor:start',
    'supervisor:status',
    'supervisor:health',
    'body',
    'stop',
  ])
  assert.equal(caught.type, 'System.AggregateException')
  assert.deepEqual(caught.innerMessages, [
    'primary body failure',
    'cleanup stop failure',
  ])
  assert.match(caught.message, /primary.*cleanup/iu)
})

test('recorded command results distinguish executed evidence from truthful NOT_RUN', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const result = runPowerShell(`
    $started = [DateTimeOffset]::Parse('2026-07-25T00:00:00Z')
    $ended = [DateTimeOffset]::Parse('2026-07-25T00:00:03Z')
    $executed = New-TradingLabRecordedCommandResult -Name 'architecture' -Executable 'npm' -Arguments @('run', 'verify:architecture') -StartedAtUtc $started -EndedAtUtc $ended -ExitCode 0 -StdoutPath 'stdout.log' -StderrPath 'stderr.log'
    $notRun = New-TradingLabNotRunCommandResult -Name 'browser-smoke' -Executable 'node' -Arguments @('scripts/smoke-trading-lab.mjs') -Reason 'blocked by an earlier phase'
    @($executed, $notRun) | ConvertTo-Json -Depth 8 -Compress
  `)
  assertPowerShellSucceeded(result)
  const [executed, notRun] = parseLastJson(result.stdout)
  assert.deepEqual(executed, {
    Name: 'architecture',
    Executable: 'npm',
    Arguments: ['run', 'verify:architecture'],
    Classification: 'PASS',
    Status: 'PASS',
    StartedAtUtc: '2026-07-25T00:00:00.0000000+00:00',
    EndedAtUtc: '2026-07-25T00:00:03.0000000+00:00',
    ExitCode: 0,
    StdoutPath: 'stdout.log',
    StderrPath: 'stderr.log',
    Reason: null,
  })
  assert.deepEqual(notRun, {
    Name: 'browser-smoke',
    Executable: 'node',
    Arguments: ['scripts/smoke-trading-lab.mjs'],
    Classification: 'NOT_RUN',
    Status: 'NOT_RUN',
    StartedAtUtc: null,
    EndedAtUtc: null,
    ExitCode: null,
    StdoutPath: null,
    StderrPath: null,
    Reason: 'blocked by an earlier phase',
  })
})

test('fixed validation stack lease allows a stopped full project but rejects unsafe pre-existing state', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const result = runPowerShell(`
    $services = @(Get-TradingLabFixedStackServiceNames)
    $ids = @(
      (('a' * 64) -join ''),
      (('b' * 64) -join ''),
      (('c' * 64) -join '')
    )
    $running = @(
      for ($index = 0; $index -lt $services.Count; $index++) {
        [pscustomobject]@{
          Service = $services[$index]
          ContainerId = $ids[$index]
          Running = $true
        }
      }
    )
    $token = 'task8-fixed-stack-owner-token-0123456789abcdef'
    Assert-TradingLabFixedStackCanStart -ObservedContainers @()
    $stopped = @(
      $running | ForEach-Object {
        [pscustomobject]@{
          Service = $_.Service
          ContainerId = $_.ContainerId
          Running = $false
        }
      }
    )
    $stoppedCanStart = Assert-TradingLabFixedStackCanStart -ObservedContainers $stopped
    $lease = New-TradingLabFixedStackLease -RunToken $token -ObservedContainers $running
    $validStop = Assert-TradingLabFixedStackCanStop -RunToken $token -Lease $lease -ObservedContainers $running
    $exitedBackend = @(
      $running | ForEach-Object {
        [pscustomobject]@{
          Service = $_.Service
          ContainerId = $_.ContainerId
          Running = $_.Running
        }
      }
    )
    @($exitedBackend | Where-Object Service -CEQ 'validation-backend')[0].Running = $false
    $exitedBackendLease = try {
      New-TradingLabFixedStackLease -RunToken $token -ObservedContainers $exitedBackend |
        Out-Null
      $null
    } catch { $_.Exception.Message }
    $exitedPostgres = @(
      $running | ForEach-Object {
        [pscustomobject]@{
          Service = $_.Service
          ContainerId = $_.ContainerId
          Running = $_.Running
        }
      }
    )
    @($exitedPostgres | Where-Object Service -CEQ 'validation-postgres')[0].Running = $false
    $exitedPostgresLease = try {
      New-TradingLabFixedStackLease -RunToken $token -ObservedContainers $exitedPostgres | Out-Null
      $null
    } catch { $_.Exception.Message }

    $preExisting = try {
      Assert-TradingLabFixedStackCanStart -ObservedContainers $running
      $null
    } catch { $_.Exception.Message }
    $partial = try {
      Assert-TradingLabFixedStackCanStart -ObservedContainers @($running[0])
      $null
    } catch { $_.Exception.Message }
    $collision = try {
      New-TradingLabFixedStackLease -RunToken $token -ObservedContainers $running -ExistingLease $lease | Out-Null
      $null
    } catch { $_.Exception.Message }
    $drifted = @($running | ForEach-Object {
      [pscustomobject]@{
        Service = $_.Service
        ContainerId = $_.ContainerId
        Running = $_.Running
      }
    })
    $drifted[1].ContainerId = (('d' * 64) -join '')
    $identityDrift = try {
      Assert-TradingLabFixedStackCanStop -RunToken $token -Lease $lease -ObservedContainers $drifted | Out-Null
      $null
    } catch { $_.Exception.Message }

    [pscustomobject]@{
      services = $services
      stoppedCanStart = $stoppedCanStart
      leaseProject = $lease.ComposeProject
      leasedServices = @($lease.ServiceIdentities | ForEach-Object { $_.Service })
      validStop = $validStop
      exitedBackendLease = $exitedBackendLease
      exitedPostgresLease = $exitedPostgresLease
      preExisting = $preExisting
      partial = $partial
      collision = $collision
      identityDrift = $identityDrift
    } | ConvertTo-Json -Depth 8 -Compress
  `)
  assertPowerShellSucceeded(result)
  const contract = parseLastJson(result.stdout)
  assert.deepEqual(contract.services, [
    'validation-backend',
    'validation-postgres',
    'validation-redis',
  ])
  assert.equal(contract.stoppedCanStart, true)
  assert.equal(contract.leaseProject, 'fx-trading-validation')
  assert.deepEqual(contract.leasedServices, contract.services)
  assert.equal(contract.validStop, true)
  assert.equal(contract.exitedBackendLease, null)
  assert.match(contract.exitedPostgresLease, /not running|service.*running/iu)
  assert.match(contract.preExisting, /pre-existing|already.*running/iu)
  assert.match(contract.partial, /partial/iu)
  assert.match(contract.collision, /lease.*collision|collision.*lease/iu)
  assert.match(contract.identityDrift, /identity.*drift|container.*id/iu)
})

test('fixed-stack recovery exposes explicit baseline and historical-proof APIs', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const source = readFileSync(runner, 'utf8')

  for (const functionName of [
    'New-TradingLabFixedStackStartBaseline',
    'Assert-TradingLabFixedStackBaselineCurrent',
    'Resolve-TradingLabStoppedStackRecoveryProof',
  ]) {
    assert.equal(
      source.includes(`function ${functionName}`),
      true,
      `missing fixed-stack recovery API: ${functionName}`,
    )
  }
})

test('bounded durable JSON evidence parses and hashes one capped file-handle read', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const source = readFileSync(runner, 'utf8')
  const boundedStart = source.indexOf(
    'function Read-TradingLabBoundedJsonEvidenceFile',
  )
  const proofStart = source.indexOf(
    'function Get-TradingLabStoppedStackRecoveryProofFromDirectory',
  )
  assert.notEqual(boundedStart, -1, 'missing single-handle bounded evidence reader')
  assert.equal(proofStart > boundedStart, true)
  const boundedSource = source.slice(boundedStart, proofStart)
  assert.match(boundedSource, /FileStream/iu)
  assert.doesNotMatch(boundedSource, /ReadAllText|ReadAllBytes/iu)

  const fixtureRoot = mkdtempSync(join(tmpdir(), 'trading-lab-bounded-json-'))
  const validPath = join(fixtureRoot, 'valid.json')
  const oversizedPath = join(fixtureRoot, 'oversized.json')
  writeFileSync(validPath, '{"schemaVersion":2}\n', 'utf8')
  writeFileSync(oversizedPath, `{"value":"${'x'.repeat(256)}"}`, 'utf8')
  try {
    const result = runPowerShell(`
      $evidence = Read-TradingLabBoundedJsonEvidenceFile \`
        -Path ${psQuote(validPath)} \`
        -MaximumBytes 64 \`
        -Label 'Fixture JSON'
      $oversized = try {
        Read-TradingLabBoundedJsonEvidenceFile \`
          -Path ${psQuote(oversizedPath)} \`
          -MaximumBytes 64 \`
          -Label 'Oversized fixture JSON' | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }
      [pscustomobject]@{
        schemaVersion = $evidence.Value.schemaVersion
        byteCount = $evidence.Bytes.Count
        sha256 = $evidence.Sha256
        oversized = $oversized
      } | ConvertTo-Json -Compress
    `)
    assertPowerShellSucceeded(result)
    const contract = parseLastJson(result.stdout)
    assert.equal(contract.schemaVersion, 2)
    assert.equal(contract.byteCount, 20)
    assert.match(contract.sha256, /^[a-f0-9]{64}$/u)
    assert.equal(typeof contract.oversized, 'string')
    assert.match(contract.oversized, /size|limit|large|bytes/iu)
  } finally {
    rmSync(fixtureRoot, { recursive: true, force: true })
  }
})

test('Docker ps state is preserved and only exact exited containers pass the stopped-state gate', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const result = runPowerShell(`
    function New-TestFixedStackDockerJson {
      param([string[]]$States)
      $services = @(Get-TradingLabFixedStackServiceNames)
      $ids = @(
        (('a' * 64) -join ''),
        (('b' * 64) -join ''),
        (('c' * 64) -join '')
      )
      $rows = @(
        for ($index = 0; $index -lt $services.Count; $index++) {
          $service = $services[$index]
          [pscustomobject][ordered]@{
            ID = $ids[$index]
            State = $States[$index]
            Project = 'fx-trading-validation'
            Service = $service
            Labels = (
              'com.docker.compose.project=fx-trading-validation,' +
              "com.docker.compose.service=$service"
            )
          }
        }
      )
      return ($rows | ConvertTo-Json -Depth 6 -Compress)
    }

    $exited = @(
      ConvertFrom-TradingLabDockerPsJson -Json (
        New-TestFixedStackDockerJson -States @('exited', 'exited', 'exited')
      )
    )
    $exactExitedStartable = try {
      Assert-TradingLabFixedStackCanStart -ObservedContainers $exited
    } catch {
      $_.Exception.Message
    }
    $unsafe = [ordered]@{}
    foreach ($state in @('created', 'paused', 'restarting', 'running')) {
      $observed = @(
        ConvertFrom-TradingLabDockerPsJson -Json (
          New-TestFixedStackDockerJson -States @($state, 'exited', 'exited')
        )
      )
      $unsafe[$state] = try {
        Assert-TradingLabFixedStackCanStart -ObservedContainers $observed | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }
    }

    [pscustomobject]@{
      exitedStates = @(
        $exited | ForEach-Object {
          $stateProperty = $_.PSObject.Properties['State']
          if ($null -eq $stateProperty) { $null } else { [string]$stateProperty.Value }
        }
      )
      exitedRunning = @($exited | ForEach-Object { [bool]$_.Running })
      exactExitedStartable = $exactExitedStartable
      unsafe = [pscustomobject]$unsafe
    } | ConvertTo-Json -Depth 8 -Compress
  `)
  assertPowerShellSucceeded(result)
  const contract = parseLastJson(result.stdout)

  assert.deepEqual(contract.exitedStates, ['exited', 'exited', 'exited'])
  assert.deepEqual(contract.exitedRunning, [false, false, false])
  assert.equal(contract.exactExitedStartable, true)
  for (const state of ['created', 'paused', 'restarting', 'running']) {
    assert.equal(
      typeof contract.unsafe[state],
      'string',
      `${state} must not be accepted as a safely exited container`,
    )
    assert.match(contract.unsafe[state], /state|exited|pre-existing|start/iu)
  }
})

test('non-empty exited stack requires matching historical owner, lease, and PASS cleanup provenance', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const fixtureRoot = mkdtempSync(join(tmpdir(), 'trading-lab-stopped-proof-'))
  const validRoot = join(fixtureRoot, 'valid')
  const emptyRoot = join(fixtureRoot, 'empty')
  const failedCleanupRoot = join(fixtureRoot, 'failed-cleanup')
  const mixedCleanupRoot = join(fixtureRoot, 'mixed-cleanup')
  const mismatchedLeaseRoot = join(fixtureRoot, 'mismatched-lease')
  const missingOwnerRoot = join(fixtureRoot, 'missing-owner')
  const staleFallbackFailedRoot = join(fixtureRoot, 'stale-fallback-failed')
  const staleFallbackMissingRoot = join(fixtureRoot, 'stale-fallback-missing')
  const nestedHistoryRoot = join(fixtureRoot, 'nested-history')
  const markerAncestorRoot = join(fixtureRoot, 'marker-ancestor')
  const depthBoundaryRoot = join(fixtureRoot, 'depth-boundary')
  const expectedIds = ['a'.repeat(64), 'b'.repeat(64), 'c'.repeat(64)]
  mkdirSync(emptyRoot, { recursive: true })
  writeStoppedStackRecoveryHistory(validRoot, { ids: expectedIds })
  writeStoppedStackRecoveryHistory(failedCleanupRoot, {
    ids: expectedIds,
    cleanupStatus: 'FAIL',
    fixedStackStatus: 'FAIL',
  })
  writeStoppedStackRecoveryHistory(mixedCleanupRoot, {
    ids: expectedIds,
    extraCleanupDetails: [{
      resource: 'post-cleanup-ports',
      status: 'FAIL',
      detail: 'fixture port remains occupied',
    }],
  })
  writeStoppedStackRecoveryHistory(mismatchedLeaseRoot, {
    ids: ['d'.repeat(64), 'e'.repeat(64), 'f'.repeat(64)],
  })
  writeStoppedStackRecoveryHistory(missingOwnerRoot, {
    ids: expectedIds,
    includeOwner: false,
  })
  writeStoppedStackRecoveryHistory(staleFallbackFailedRoot, {
    runDirectory: 'old-pass',
    ids: expectedIds,
  })
  writeStoppedStackRecoveryHistory(staleFallbackFailedRoot, {
    runDirectory: 'same-id-failed-attempt',
    ids: expectedIds,
    cleanupStatus: 'FAIL',
    fixedStackStatus: 'FAIL',
  })
  writeStoppedStackRecoveryHistory(staleFallbackMissingRoot, {
    runDirectory: 'old-pass',
    ids: expectedIds,
  })
  const missingCleanupAttempt = writeStoppedStackRecoveryHistory(
    staleFallbackMissingRoot,
    {
      runDirectory: 'same-id-missing-cleanup-attempt',
      ids: expectedIds,
    },
  )
  unlinkSync(join(missingCleanupAttempt, 'cleanup-receipt.json'))
  writeStoppedStackRecoveryHistory(nestedHistoryRoot, {
    runDirectory: join('archive', 'completed', 'nested-pass'),
    ids: expectedIds,
  })
  writeStoppedStackRecoveryHistory(markerAncestorRoot, {
    runDirectory: 'old-pass',
    ids: expectedIds,
  })
  const markerAncestorMissingCleanup = writeStoppedStackRecoveryHistory(
    markerAncestorRoot,
    {
      runDirectory: join('old-pass', 'hidden-attempt'),
      ids: expectedIds,
    },
  )
  unlinkSync(join(markerAncestorMissingCleanup, 'cleanup-receipt.json'))
  writeStoppedStackRecoveryHistory(depthBoundaryRoot, {
    runDirectory: 'old-pass',
    ids: expectedIds,
  })
  const depthBoundaryMissingCleanup = writeStoppedStackRecoveryHistory(
    depthBoundaryRoot,
    {
      runDirectory: join(
        'archive-1',
        'archive-2',
        'archive-3',
        'archive-4',
        'archive-5',
        'archive-6',
        'archive-7',
        'archive-8',
        'hidden-attempt',
      ),
      ids: expectedIds,
    },
  )
  unlinkSync(join(depthBoundaryMissingCleanup, 'cleanup-receipt.json'))

  try {
    const result = runPowerShell(`
      $services = @(Get-TradingLabFixedStackServiceNames)
      $ids = @(
        (('a' * 64) -join ''),
        (('b' * 64) -join ''),
        (('c' * 64) -join '')
      )
      $stopped = @(
        for ($index = 0; $index -lt $services.Count; $index++) {
          [pscustomobject][ordered]@{
            Service = $services[$index]
            ContainerId = $ids[$index]
            State = 'exited'
            Running = $false
          }
        }
      )

      $validProof = $null
      $validProofError = $null
      try {
        $validProof = Resolve-TradingLabStoppedStackRecoveryProof \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(validRoot)}
      } catch {
        $validProofError = $_.Exception.Message
      }
      $validBaseline = $null
      $validBaselineError = $null
      try {
        $validBaseline = New-TradingLabFixedStackStartBaseline \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(validRoot)}
      } catch {
        $validBaselineError = $_.Exception.Message
      }
      $nestedProof = $null
      $nestedProofError = $null
      try {
        $nestedProof = Resolve-TradingLabStoppedStackRecoveryProof \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(nestedHistoryRoot)}
      } catch {
        $nestedProofError = $_.Exception.Message
      }
      $withoutHistory = try {
        New-TradingLabFixedStackStartBaseline \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(emptyRoot)} | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }
      $failedCleanup = try {
        New-TradingLabFixedStackStartBaseline \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(failedCleanupRoot)} | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }
      $mixedCleanup = try {
        New-TradingLabFixedStackStartBaseline \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(mixedCleanupRoot)} | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }
      $mismatchedLease = try {
        New-TradingLabFixedStackStartBaseline \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(mismatchedLeaseRoot)} | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }
      $missingOwner = try {
        New-TradingLabFixedStackStartBaseline \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(missingOwnerRoot)} | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }
      $staleFallbackFailed = try {
        New-TradingLabFixedStackStartBaseline \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(staleFallbackFailedRoot)} | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }
      $staleFallbackMissing = try {
        New-TradingLabFixedStackStartBaseline \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(staleFallbackMissingRoot)} | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }
      $markerAncestor = try {
        New-TradingLabFixedStackStartBaseline \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(markerAncestorRoot)} | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }
      $depthBoundary = try {
        New-TradingLabFixedStackStartBaseline \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(depthBoundaryRoot)} | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }

      [pscustomobject]@{
        validProofFound = $null -ne $validProof
        validProofError = $validProofError
        validBaselineFound = $null -ne $validBaseline
        validBaselineError = $validBaselineError
        nestedProofFound = $null -ne $nestedProof
        nestedProofError = $nestedProofError
        withoutHistory = $withoutHistory
        failedCleanup = $failedCleanup
        mixedCleanup = $mixedCleanup
        mismatchedLease = $mismatchedLease
        missingOwner = $missingOwner
        staleFallbackFailed = $staleFallbackFailed
        staleFallbackMissing = $staleFallbackMissing
        markerAncestor = $markerAncestor
        depthBoundary = $depthBoundary
      } | ConvertTo-Json -Depth 8 -Compress
    `)
    assertPowerShellSucceeded(result)
    const contract = parseLastJson(result.stdout)

    assert.equal(contract.validProofFound, true)
    assert.equal(contract.validProofError, null)
    assert.equal(contract.validBaselineFound, true)
    assert.equal(contract.validBaselineError, null)
    assert.equal(contract.nestedProofFound, true)
    assert.equal(contract.nestedProofError, null)
    for (const field of [
      'withoutHistory',
      'failedCleanup',
      'mixedCleanup',
      'mismatchedLease',
      'missingOwner',
      'staleFallbackFailed',
      'staleFallbackMissing',
      'markerAncestor',
      'depthBoundary',
    ]) {
      assert.equal(typeof contract[field], 'string', `${field} must fail closed`)
      assert.match(contract[field], /history|provenance|owner|lease|cleanup|proof|match/iu)
      assert.doesNotMatch(contract[field], /not recognized|not found as the name/iu)
    }
  } finally {
    rmSync(fixtureRoot, { recursive: true, force: true })
  }
})

test('a later exact hash-linked PASS closes only its named failed stopped-stack attempt', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const fixtureRoot = mkdtempSync(join(tmpdir(), 'trading-lab-stopped-recovery-link-'))
  const linkedRoot = join(fixtureRoot, 'linked')
  const forgedRoot = join(fixtureRoot, 'forged')
  const partiallyLinkedRoot = join(fixtureRoot, 'partially-linked')
  const missingSourceMarkerRoot = join(fixtureRoot, 'missing-source-marker')
  const missingSourceLeaseRoot = join(fixtureRoot, 'missing-source-lease')
  const partialSourceRoot = join(fixtureRoot, 'partial-source')
  const newerNormalPassRoot = join(fixtureRoot, 'newer-normal-pass')
  const multipleClosuresRoot = join(fixtureRoot, 'multiple-closures')
  const expectedIds = ['a'.repeat(64), 'b'.repeat(64), 'c'.repeat(64)]

  const linkedSource = writeStoppedStackRecoveryHistory(linkedRoot, {
    runDirectory: 'failed-attempt',
    ids: expectedIds,
    cleanupStatus: 'FAIL',
    fixedStackStatus: 'PASS',
    completedAtUtc: '2026-07-26T00:00:00.1234567+00:00',
  })
  const linkedRecovery = writeStoppedStackRecoveryHistory(linkedRoot, {
    runDirectory: 'manual-recovery',
    ids: expectedIds,
    completedAtUtc: '2026-07-26T00:00:00.1234568+00:00',
  })
  writeStoppedStackRecoveryLink(linkedRecovery, linkedSource)

  const forgedSource = writeStoppedStackRecoveryHistory(forgedRoot, {
    runDirectory: 'failed-attempt',
    ids: expectedIds,
    cleanupStatus: 'FAIL',
    fixedStackStatus: 'PASS',
    completedAtUtc: '2026-07-26T00:00:00.1234567+00:00',
  })
  const forgedRecovery = writeStoppedStackRecoveryHistory(forgedRoot, {
    runDirectory: 'manual-recovery',
    ids: expectedIds,
    completedAtUtc: '2026-07-26T00:00:00.1234568+00:00',
  })
  writeStoppedStackRecoveryLink(forgedRecovery, forgedSource, {
    sourceCleanupReceiptSha256: 'f'.repeat(64),
  })
  const partiallyLinkedSource = writeStoppedStackRecoveryHistory(
    partiallyLinkedRoot,
    {
      runDirectory: 'failed-attempt-1',
      ids: expectedIds,
      cleanupStatus: 'FAIL',
      fixedStackStatus: 'PASS',
      completedAtUtc: '2026-07-26T00:00:00.1234567+00:00',
    },
  )
  writeStoppedStackRecoveryHistory(partiallyLinkedRoot, {
    runDirectory: 'failed-attempt-2',
    ids: expectedIds,
    cleanupStatus: 'FAIL',
    fixedStackStatus: 'PASS',
    completedAtUtc: '2026-07-26T00:00:00.1234567+00:00',
  })
  const partiallyLinkedRecovery = writeStoppedStackRecoveryHistory(
    partiallyLinkedRoot,
    {
      runDirectory: 'manual-recovery-1',
      ids: expectedIds,
      completedAtUtc: '2026-07-26T00:00:00.1234568+00:00',
    },
  )
  writeStoppedStackRecoveryLink(
    partiallyLinkedRecovery,
    partiallyLinkedSource,
  )
  const missingSourceMarker = writeStoppedStackLinkedRecoveryCase(
    missingSourceMarkerRoot,
  )
  const missingSourceLease = writeStoppedStackLinkedRecoveryCase(
    missingSourceLeaseRoot,
  )
  writeStoppedStackLinkedRecoveryCase(partialSourceRoot, {
    sourceCleanupStatus: 'PARTIAL',
  })
  const invalidLinkRoots = {}
  for (const name of [
    'missing-field',
    'extra-field',
    'wrong-source',
    'wrong-token',
    'source-not-earlier',
    'link-before-recovery',
    'recovery-detail-fail',
    'recovery-detail-partial',
  ]) {
    const root = join(fixtureRoot, `invalid-${name}`)
    invalidLinkRoots[name] = root
    if (name === 'source-not-earlier') {
      writeStoppedStackLinkedRecoveryCase(root, {
        sourceCompletedAtUtc: '2026-07-26T00:00:00.1234568+00:00',
      })
      continue
    }
    if (
      name === 'recovery-detail-fail'
      || name === 'recovery-detail-partial'
    ) {
      writeStoppedStackLinkedRecoveryCase(root, {
        recoveryExtraCleanupDetails: [{
          resource: 'post-cleanup-ports',
          status: name.endsWith('fail') ? 'FAIL' : 'PARTIAL',
          detail: 'fixture port remains occupied',
        }],
      })
      continue
    }
    const recoveryCase = writeStoppedStackLinkedRecoveryCase(root)
    const linkPath = join(
      recoveryCase.recoveryDirectory,
      'cleanup-recovery-link.json',
    )
    rewriteJsonFile(linkPath, (link) => {
      if (name === 'missing-field') {
        delete link.sourceLeaseSha256
      } else if (name === 'extra-field') {
        link.unexpected = true
      } else if (name === 'wrong-source') {
        link.sourceArtifactsDirectory = recoveryCase.recoveryDirectory
      } else if (name === 'wrong-token') {
        link.runToken = '2'.repeat(64)
      } else if (name === 'link-before-recovery') {
        link.createdAtUtc = '2026-07-26T00:00:00.1234567+00:00'
      }
    })
  }
  const olderLinkedSource = writeStoppedStackRecoveryHistory(
    newerNormalPassRoot,
    {
      runDirectory: 'failed-attempt',
      ids: expectedIds,
      cleanupStatus: 'FAIL',
      fixedStackStatus: 'PASS',
      completedAtUtc: '2026-07-26T00:00:00.1234567+00:00',
    },
  )
  const olderLinkedRecovery = writeStoppedStackRecoveryHistory(
    newerNormalPassRoot,
    {
      runDirectory: 'linked-recovery',
      ids: expectedIds,
      completedAtUtc: '2026-07-26T00:00:00.1234568+00:00',
    },
  )
  writeStoppedStackRecoveryLink(olderLinkedRecovery, olderLinkedSource)
  writeStoppedStackRecoveryHistory(newerNormalPassRoot, {
    runDirectory: 'newer-normal-pass',
    ids: expectedIds,
    completedAtUtc: '2026-07-26T00:00:00.1234570+00:00',
  })

  const firstClosureSource = writeStoppedStackRecoveryHistory(
    multipleClosuresRoot,
    {
      runDirectory: 'failed-attempt-1',
      ids: expectedIds,
      cleanupStatus: 'FAIL',
      fixedStackStatus: 'PASS',
      completedAtUtc: '2026-07-26T00:00:00.1234561+00:00',
    },
  )
  const firstClosureRecovery = writeStoppedStackRecoveryHistory(
    multipleClosuresRoot,
    {
      runDirectory: 'linked-recovery-1',
      ids: expectedIds,
      completedAtUtc: '2026-07-26T00:00:00.1234562+00:00',
    },
  )
  writeStoppedStackRecoveryLink(firstClosureRecovery, firstClosureSource)
  const secondClosureSource = writeStoppedStackRecoveryHistory(
    multipleClosuresRoot,
    {
      runDirectory: 'failed-attempt-2',
      ids: expectedIds,
      cleanupStatus: 'FAIL',
      fixedStackStatus: 'PASS',
      completedAtUtc: '2026-07-26T00:00:00.1234561+00:00',
    },
  )
  const secondClosureRecovery = writeStoppedStackRecoveryHistory(
    multipleClosuresRoot,
    {
      runDirectory: 'linked-recovery-2',
      ids: expectedIds,
      completedAtUtc: '2026-07-26T00:00:00.1234563+00:00',
    },
  )
  writeStoppedStackRecoveryLink(secondClosureRecovery, secondClosureSource)

  try {
    const result = runPowerShell(`
      $services = @(Get-TradingLabFixedStackServiceNames)
      $ids = @(
        (('a' * 64) -join ''),
        (('b' * 64) -join ''),
        (('c' * 64) -join '')
      )
      $stopped = @(
        for ($index = 0; $index -lt $services.Count; $index++) {
          [pscustomobject][ordered]@{
            Service = $services[$index]
            ContainerId = $ids[$index]
            State = 'exited'
            Running = $false
          }
        }
      )

      $linkedProof = $null
      $linkedError = $null
      try {
        $linkedProof = Resolve-TradingLabStoppedStackRecoveryProof \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(linkedRoot)}
      } catch {
        $linkedError = $_.Exception.Message
      }
      $roundTripProof = $linkedProof | ConvertTo-Json -Depth 8 -Compress |
        ConvertFrom-Json
      if ($roundTripProof.CompletedAtUtc -is [string]) {
        $roundTripProof.CompletedAtUtc = (
          [DateTimeOffset]::Parse(
            [string]$roundTripProof.CompletedAtUtc
          ).LocalDateTime
        )
      }
      $roundTripError = try {
        Assert-TradingLabStoppedStackRecoveryProof \`
          -Proof $roundTripProof \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(linkedRoot)} | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }
      $scalarClosureProof = $linkedProof |
        ConvertTo-Json -Depth 16 -Compress |
        ConvertFrom-Json
      $scalarClosureProof.RecoveryClosures = @(
        $scalarClosureProof.RecoveryClosures
      )[0]
      $scalarClosureError = try {
        Assert-TradingLabStoppedStackRecoveryProof \`
          -Proof $scalarClosureProof \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(linkedRoot)} | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }
      $nullClosureProof = $linkedProof |
        ConvertTo-Json -Depth 16 -Compress |
        ConvertFrom-Json
      $nullClosureProof.RecoveryClosures = $null
      $nullClosureError = try {
        Assert-TradingLabStoppedStackRecoveryProof \`
          -Proof $nullClosureProof \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(linkedRoot)} | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }
      $driftedProof = $linkedProof | ConvertTo-Json -Depth 8 -Compress |
        ConvertFrom-Json
      $driftedProof.CompletedAtUtc = (
        [DateTimeOffset]$driftedProof.CompletedAtUtc
      ).AddTicks(1).LocalDateTime
      $oneTickDriftError = try {
        Assert-TradingLabStoppedStackRecoveryProof \`
          -Proof $driftedProof \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(linkedRoot)} | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }
      $closureTickErrors = [ordered]@{}
      foreach ($timestampField in @(
        'SourceCompletedAtUtc',
        'RecoveryCompletedAtUtc',
        'LinkCreatedAtUtc'
      )) {
        $closureDriftProof = $linkedProof |
          ConvertTo-Json -Depth 16 -Compress |
          ConvertFrom-Json
        $closure = @($closureDriftProof.RecoveryClosures)[0]
        $closure.$timestampField = (
          [DateTimeOffset]$closure.$timestampField
        ).AddTicks(1).LocalDateTime
        $closureTickErrors[$timestampField] = try {
          Assert-TradingLabStoppedStackRecoveryProof \`
            -Proof $closureDriftProof \`
            -ObservedContainers $stopped \`
            -ArtifactsRoot ${psQuote(linkedRoot)} | Out-Null
          $null
        } catch {
          $_.Exception.Message
        }
      }
      [System.IO.File]::WriteAllText(
        ${psQuote(join(linkedRecovery, 'cleanup-recovery-link.json'))},
        '{}'
      )
      $linkDriftError = try {
        Assert-TradingLabStoppedStackRecoveryProof \`
          -Proof $linkedProof \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(linkedRoot)} | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }
      $forgedError = try {
        Resolve-TradingLabStoppedStackRecoveryProof \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(forgedRoot)} | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }
      $partiallyLinkedError = try {
        Resolve-TradingLabStoppedStackRecoveryProof \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(partiallyLinkedRoot)} | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }
      $missingMarkerProof = Resolve-TradingLabStoppedStackRecoveryProof \`
        -ObservedContainers $stopped \`
        -ArtifactsRoot ${psQuote(missingSourceMarkerRoot)}
      Remove-Item -LiteralPath ${
        psQuote(join(
          missingSourceMarker.sourceDirectory,
          '.trading-lab-owner.json',
        ))
      } -Force
      $missingSourceMarkerError = try {
        Assert-TradingLabStoppedStackRecoveryProof \`
          -Proof $missingMarkerProof \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(missingSourceMarkerRoot)} | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }
      $missingLeaseProof = Resolve-TradingLabStoppedStackRecoveryProof \`
        -ObservedContainers $stopped \`
        -ArtifactsRoot ${psQuote(missingSourceLeaseRoot)}
      Remove-Item -LiteralPath ${
        psQuote(join(
          missingSourceLease.sourceDirectory,
          'fixed-stack-lease.json',
        ))
      } -Force
      $missingSourceLeaseError = try {
        Assert-TradingLabStoppedStackRecoveryProof \`
          -Proof $missingLeaseProof \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(missingSourceLeaseRoot)} | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }
      $partialSourceError = try {
        Resolve-TradingLabStoppedStackRecoveryProof \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(partialSourceRoot)} | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }
      $invalidLinkRoots = [ordered]@{
        ${Object.entries(invalidLinkRoots).map(([name, path]) => (
          `${psQuote(name)} = ${psQuote(path)}`
        )).join('\n        ')}
      }
      $invalidLinkErrors = [ordered]@{}
      foreach ($entry in $invalidLinkRoots.GetEnumerator()) {
        $invalidLinkErrors[[string]$entry.Key] = try {
          Resolve-TradingLabStoppedStackRecoveryProof \`
            -ObservedContainers $stopped \`
            -ArtifactsRoot ([string]$entry.Value) | Out-Null
          $null
        } catch {
          $_.Exception.Message
        }
      }
      $newerNormalProof = Resolve-TradingLabStoppedStackRecoveryProof \`
        -ObservedContainers $stopped \`
        -ArtifactsRoot ${psQuote(newerNormalPassRoot)}
      Remove-Item -LiteralPath ${
        psQuote(join(olderLinkedSource, '.trading-lab-owner.json'))
      } -Force
      Remove-Item -LiteralPath ${
        psQuote(join(olderLinkedRecovery, 'cleanup-recovery-link.json'))
      } -Force
      $unselectedClosureError = try {
        Assert-TradingLabStoppedStackRecoveryProof \`
          -Proof $newerNormalProof \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(newerNormalPassRoot)} | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }
      $multipleClosuresProof = Resolve-TradingLabStoppedStackRecoveryProof \`
        -ObservedContainers $stopped \`
        -ArtifactsRoot ${psQuote(multipleClosuresRoot)}
      Remove-Item -LiteralPath ${
        psQuote(join(firstClosureSource, '.trading-lab-owner.json'))
      } -Force
      Remove-Item -LiteralPath ${
        psQuote(join(firstClosureRecovery, 'cleanup-recovery-link.json'))
      } -Force
      $unselectedMultipleClosureError = try {
        Assert-TradingLabStoppedStackRecoveryProof \`
          -Proof $multipleClosuresProof \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(multipleClosuresRoot)} | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }

      [pscustomobject]@{
        linkedProofFound = $null -ne $linkedProof
        linkedProofDirectory = if ($null -eq $linkedProof) {
          $null
        } else {
          [string]$linkedProof.ArtifactsDirectory
        }
        linkedCompletedAtUtc = if ($null -eq $linkedProof) {
          $null
        } else {
          [string]$linkedProof.CompletedAtUtc
        }
        linkedError = $linkedError
        roundTripError = $roundTripError
        scalarClosureError = $scalarClosureError
        nullClosureError = $nullClosureError
        oneTickDriftError = $oneTickDriftError
        closureTickErrors = [pscustomobject]$closureTickErrors
        linkDriftError = $linkDriftError
        forgedError = $forgedError
        partiallyLinkedError = $partiallyLinkedError
        missingSourceMarkerError = $missingSourceMarkerError
        missingSourceLeaseError = $missingSourceLeaseError
        partialSourceError = $partialSourceError
        invalidLinkErrors = [pscustomobject]$invalidLinkErrors
        unselectedClosureError = $unselectedClosureError
        unselectedMultipleClosureError = $unselectedMultipleClosureError
        linkedClosureCount = @($linkedProof.RecoveryClosures).Count
        newerNormalClosureCount = @($newerNormalProof.RecoveryClosures).Count
        multipleClosureCount = @($multipleClosuresProof.RecoveryClosures).Count
      } | ConvertTo-Json -Compress
    `)
    assertPowerShellSucceeded(result)
    const contract = parseLastJson(result.stdout)

    assert.equal(contract.linkedProofFound, true)
    assert.equal(resolve(contract.linkedProofDirectory), resolve(linkedRecovery))
    assert.equal(
      contract.linkedCompletedAtUtc,
      '2026-07-26T00:00:00.1234568+00:00',
    )
    assert.equal(contract.linkedError, null)
    assert.equal(contract.roundTripError, null)
    assert.match(contract.scalarClosureError, /schema|array|closure|proof/iu)
    assert.match(contract.nullClosureError, /schema|array|closure|proof/iu)
    assert.match(contract.oneTickDriftError, /timestamp.*drift|proof.*drift/iu)
    assert.deepEqual(
      Object.keys(contract.closureTickErrors).sort(),
      [
        'LinkCreatedAtUtc',
        'RecoveryCompletedAtUtc',
        'SourceCompletedAtUtc',
      ],
    )
    for (const error of Object.values(contract.closureTickErrors)) {
      assert.match(error, /timestamp.*drift|proof.*drift/iu)
    }
    assert.match(contract.linkDriftError, /recovery|cleanup|proof|lease/iu)
    assert.equal(typeof contract.forgedError, 'string')
    assert.match(contract.forgedError, /recovery|cleanup|proof|hash|lease/iu)
    assert.equal(typeof contract.partiallyLinkedError, 'string')
    assert.match(
      contract.partiallyLinkedError,
      /recovery|cleanup|proof|lease/iu,
    )
    assert.equal(typeof contract.missingSourceMarkerError, 'string')
    assert.match(
      contract.missingSourceMarkerError,
      /recovery|cleanup|proof|owner|lease/iu,
    )
    assert.equal(typeof contract.missingSourceLeaseError, 'string')
    assert.match(
      contract.missingSourceLeaseError,
      /recovery|cleanup|proof|owner|lease/iu,
    )
    assert.equal(typeof contract.partialSourceError, 'string')
    assert.match(
      contract.partialSourceError,
      /recovery|cleanup|proof|lease/iu,
    )
    assert.deepEqual(
      Object.keys(contract.invalidLinkErrors).sort(),
      Object.keys(invalidLinkRoots).sort(),
    )
    for (const [name, error] of Object.entries(contract.invalidLinkErrors)) {
      assert.equal(typeof error, 'string', `${name} must fail closed`)
      assert.match(error, /recovery|cleanup|proof|owner|lease|link/iu)
    }
    assert.equal(typeof contract.unselectedClosureError, 'string')
    assert.match(
      contract.unselectedClosureError,
      /recovery|cleanup|proof|owner|lease|link/iu,
    )
    assert.equal(typeof contract.unselectedMultipleClosureError, 'string')
    assert.match(
      contract.unselectedMultipleClosureError,
      /recovery|cleanup|proof|owner|lease|link/iu,
    )
    assert.equal(contract.linkedClosureCount, 1)
    assert.equal(contract.newerNormalClosureCount, 1)
    assert.equal(contract.multipleClosureCount, 2)
  } finally {
    rmSync(fixtureRoot, { recursive: true, force: true })
  }
})

test('stopped-stack closure order is identical in Windows PowerShell and pwsh', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const pwshVersion = spawnSync(
    'pwsh.exe',
    ['-NoProfile', '-Command', '$PSVersionTable.PSVersion.ToString()'],
    { encoding: 'utf8', windowsHide: true },
  )
  if (pwshVersion.status !== 0) {
    t.skip('pwsh is unavailable')
    return
  }
  const fixtureRoot = mkdtempSync(join(tmpdir(), 'trading-lab-closure-order-'))
  const ids = ['a'.repeat(64), 'b'.repeat(64), 'c'.repeat(64)]
  const labels = ['a', 'ä', '😀', '中']
  try {
    labels.forEach((label) => {
      const sourceDirectory = writeStoppedStackRecoveryHistory(fixtureRoot, {
        runDirectory: `${label}-failed`,
        ids,
        cleanupStatus: 'FAIL',
        fixedStackStatus: 'PASS',
        completedAtUtc: '2026-07-26T00:00:00.1234561+00:00',
      })
      const recoveryDirectory = writeStoppedStackRecoveryHistory(fixtureRoot, {
        runDirectory: `${label}-recovery`,
        ids,
        completedAtUtc: '2026-07-26T00:00:00.1234562+00:00',
      })
      writeStoppedStackRecoveryLink(recoveryDirectory, sourceDirectory)
    })
    const body = `
      $services = @(Get-TradingLabFixedStackServiceNames)
      $ids = @(
        (('a' * 64) -join ''),
        (('b' * 64) -join ''),
        (('c' * 64) -join '')
      )
      $stopped = @(
        for ($index = 0; $index -lt $services.Count; $index++) {
          [pscustomobject][ordered]@{
            Service = $services[$index]
            ContainerId = $ids[$index]
            State = 'exited'
            Running = $false
          }
        }
      )
      $proof = Resolve-TradingLabStoppedStackRecoveryProof \`
        -ObservedContainers $stopped \`
        -ArtifactsRoot ${psQuote(fixtureRoot)}
      [pscustomobject]@{
        selected = Split-Path -Leaf ([string]$proof.ArtifactsDirectory)
        closures = @(
          $proof.RecoveryClosures |
            ForEach-Object {
              Split-Path -Leaf ([string]$_.SourceArtifactsDirectory)
            }
        )
      } | ConvertTo-Json -Compress
    `
    const windowsPowerShell = runPowerShell(body)
    const pwsh = runPowerShell(body, 'pwsh.exe')
    assertPowerShellSucceeded(windowsPowerShell)
    assertPowerShellSucceeded(pwsh)
    assert.deepEqual(
      parseLastJson(windowsPowerShell.stdout),
      parseLastJson(pwsh.stdout),
    )
  } finally {
    rmSync(fixtureRoot, { recursive: true, force: true })
  }
})

test('fixed-stack guard persists schema-2 baseline and rejects exact ID or state drift', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const fixtureRoot = mkdtempSync(join(tmpdir(), 'trading-lab-stack-baseline-'))
  const historyRoot = join(fixtureRoot, 'history')
  const guardPath = join(fixtureRoot, 'fixed-stack-guard.json')
  const expectedIds = ['a'.repeat(64), 'b'.repeat(64), 'c'.repeat(64)]
  writeStoppedStackRecoveryHistory(historyRoot, { ids: expectedIds })

  try {
    const result = runPowerShell(`
      $token = 'task8-baseline-guard-owner-token-0123456789abcdef'
      $services = @(Get-TradingLabFixedStackServiceNames)
      $ids = @(
        (('a' * 64) -join ''),
        (('b' * 64) -join ''),
        (('c' * 64) -join '')
      )
      $stopped = @(
        for ($index = 0; $index -lt $services.Count; $index++) {
          [pscustomobject][ordered]@{
            Service = $services[$index]
            ContainerId = $ids[$index]
            State = 'exited'
            Running = $false
          }
        }
      )
      $baseline = New-TradingLabFixedStackStartBaseline \`
        -ObservedContainers $stopped \`
        -ArtifactsRoot ${psQuote(historyRoot)}
      $originalArtifactsRoot = $script:TradingLabArtifactsRoot
      try {
        $script:TradingLabArtifactsRoot = ${psQuote(historyRoot)}
        Acquire-TradingLabFixedStackGuardAtomic \`
          -GuardPath ${psQuote(guardPath)} \`
          -RunToken $token \`
          -Baseline $baseline | Out-Null
      } finally {
        $script:TradingLabArtifactsRoot = $originalArtifactsRoot
      }
      $ownedGuard = Assert-TradingLabFixedStackGuardOwned \`
        -GuardPath ${psQuote(guardPath)} \`
        -RunToken $token
      $same = Assert-TradingLabFixedStackBaselineCurrent \`
        -Baseline $baseline \`
        -ObservedContainers $stopped \`
        -ArtifactsRoot ${psQuote(historyRoot)}

      $idDrifted = @(
        $stopped | ForEach-Object {
          [pscustomobject][ordered]@{
            Service = $_.Service
            ContainerId = $_.ContainerId
            State = $_.State
            Running = $_.Running
          }
        }
      )
      $idDrifted[1].ContainerId = (('d' * 64) -join '')
      $idDrift = try {
        Assert-TradingLabFixedStackBaselineCurrent \`
          -Baseline $baseline \`
          -ObservedContainers $idDrifted \`
          -ArtifactsRoot ${psQuote(historyRoot)} | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }

      $stateDrifted = @(
        $stopped | ForEach-Object {
          [pscustomobject][ordered]@{
            Service = $_.Service
            ContainerId = $_.ContainerId
            State = $_.State
            Running = $_.Running
          }
        }
      )
      $stateDrifted[2].State = 'running'
      $stateDrifted[2].Running = $true
      $stateDrift = try {
        Assert-TradingLabFixedStackBaselineCurrent \`
          -Baseline $baseline \`
          -ObservedContainers $stateDrifted \`
          -ArtifactsRoot ${psQuote(historyRoot)} | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }
      $caseDrifted = $baseline | ConvertTo-Json -Depth 12 | ConvertFrom-Json
      $caseDrifted.Mode = 'full_exited'
      $modeCaseDrift = try {
        Assert-TradingLabFixedStackBaselineCurrent \`
          -Baseline $caseDrifted \`
          -ObservedContainers $stopped \`
          -ArtifactsRoot ${psQuote(historyRoot)} | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }

      $guardFile = [System.IO.File]::ReadAllText(${psQuote(guardPath)}) | ConvertFrom-Json
      $baselineProperty = $guardFile.PSObject.Properties['baseline']
      $publishedBaseline = if ($null -eq $baselineProperty) {
        $null
      } else {
        $baselineProperty.Value
      }
      $publishedIdentities = if ($null -eq $publishedBaseline) {
        @()
      } else {
        @($publishedBaseline.ServiceIdentities)
      }
      [pscustomobject]@{
        guardSchema = $guardFile.schemaVersion
        ownedGuardSchema = $ownedGuard.schemaVersion
        baselinePublished = $null -ne $publishedBaseline
        publishedIds = @($publishedIdentities | ForEach-Object { $_.ContainerId })
        same = $same
        idDrift = $idDrift
        stateDrift = $stateDrift
        modeCaseDrift = $modeCaseDrift
      } | ConvertTo-Json -Depth 10 -Compress
    `)
    assertPowerShellSucceeded(result)
    const contract = parseLastJson(result.stdout)

    assert.equal(contract.guardSchema, 2)
    assert.equal(contract.ownedGuardSchema, 2)
    assert.equal(contract.baselinePublished, true)
    assert.deepEqual(contract.publishedIds, expectedIds)
    assert.equal(contract.same, true)
    assert.equal(typeof contract.idDrift, 'string')
    assert.match(contract.idDrift, /baseline|identity|container.*id|drift/iu)
    assert.equal(typeof contract.stateDrift, 'string')
    assert.match(contract.stateDrift, /baseline|state|exited|drift/iu)
    assert.equal(typeof contract.modeCaseDrift, 'string')
    assert.match(contract.modeCaseDrift, /baseline|mode|schema/iu)
  } finally {
    rmSync(fixtureRoot, { recursive: true, force: true })
  }
})

test('fixed-stack guard rejects an oversized payload before atomic publish', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const fixtureRoot = mkdtempSync(join(tmpdir(), 'trading-lab-oversized-guard-'))
  const guardPath = join(fixtureRoot, 'fixed-stack-guard.json')
  try {
    const result = runPowerShell(`
      function Assert-TradingLabFixedStackBaselineCurrent {
        return $true
      }
      $errorMessage = try {
        Acquire-TradingLabFixedStackGuardAtomic \`
          -GuardPath ${psQuote(guardPath)} \`
          -RunToken 'task8-oversized-guard-owner-0123456789abcdef' \`
          -Baseline ([pscustomobject]@{
            payload = ('x' * 1048576)
            ServiceIdentities = @()
          }) | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }
      [pscustomobject]@{
        errorMessage = $errorMessage
        guardExists = Test-Path -LiteralPath ${psQuote(guardPath)}
      } | ConvertTo-Json -Compress
    `)
    assertPowerShellSucceeded(result)
    const contract = parseLastJson(result.stdout)
    assert.match(contract.errorMessage, /guard.*(?:size|large|bytes|limit)/iu)
    assert.equal(contract.guardExists, false)
  } finally {
    rmSync(fixtureRoot, { recursive: true, force: true })
  }
})

test('fixed-stack guard rejects a recovery proof outside its trusted artifact root', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const guardRoot = mkdtempSync(join(tmpdir(), 'trading-lab-guard-root-'))
  const proofRoot = mkdtempSync(join(tmpdir(), 'trading-lab-foreign-proof-'))
  const guardPath = join(guardRoot, 'fixed-stack-guard.json')
  const ids = ['a'.repeat(64), 'b'.repeat(64), 'c'.repeat(64)]
  writeStoppedStackRecoveryHistory(proofRoot, { ids })
  try {
    const result = runPowerShell(`
      $token = '2' * 64
      $services = @(Get-TradingLabFixedStackServiceNames)
      $ids = @((('a' * 64) -join ''), (('b' * 64) -join ''), (('c' * 64) -join ''))
      $stopped = @(
        for ($index = 0; $index -lt $services.Count; $index++) {
          [pscustomobject][ordered]@{
            Service = $services[$index]
            ContainerId = $ids[$index]
            State = 'exited'
            Running = $false
          }
        }
      )
      $baseline = New-TradingLabFixedStackStartBaseline \`
        -ObservedContainers $stopped \`
        -ArtifactsRoot ${psQuote(proofRoot)}
      $errorMessage = try {
        Acquire-TradingLabFixedStackGuardAtomic \`
          -GuardPath ${psQuote(guardPath)} \`
          -RunToken $token \`
          -Baseline $baseline | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }
      [pscustomobject]@{
        errorMessage = $errorMessage
        guardExists = Test-Path -LiteralPath ${psQuote(guardPath)}
      } | ConvertTo-Json -Compress
    `)
    assertPowerShellSucceeded(result)
    const contract = parseLastJson(result.stdout)
    assert.equal(typeof contract.errorMessage, 'string')
    assert.match(contract.errorMessage, /artifact.*root|outside|proof/iu)
    assert.equal(contract.guardExists, false)
  } finally {
    rmSync(guardRoot, { recursive: true, force: true })
    rmSync(proofRoot, { recursive: true, force: true })
  }
})

test('fixed-stack cleanup uses docker stop with only exact running leased IDs and rejects drift', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const fixtureRoot = mkdtempSync(join(tmpdir(), 'trading-lab-exited-cleanup-'))
  const leasePath = join(fixtureRoot, 'fixed-stack-lease.json')
  const guardPath = join(fixtureRoot, 'fixed-stack-guard.json')
  writeFileSync(leasePath, '{}', 'utf8')
  try {
    const result = runPowerShell(`
    $token = 'task8-exited-backend-cleanup-token-0123456789abcdef'
    Register-TradingLabOwnedRunSecrets -RunToken $token | Out-Null
    $services = @(Get-TradingLabFixedStackServiceNames)
    $ids = @(
      (('a' * 64) -join ''),
      (('b' * 64) -join ''),
      (('c' * 64) -join '')
    )
    $leased = @(
      for ($index = 0; $index -lt $services.Count; $index++) {
        [pscustomobject]@{
          Service = $services[$index]
          ContainerId = $ids[$index]
          State = 'running'
          Running = $true
        }
      }
    )
    $script:testLease = New-TradingLabFixedStackLease -RunToken $token -ObservedContainers $leased
    $script:testScenario = 'exited'
    $script:testStopCalls = [System.Collections.Generic.List[object]]::new()
    $script:testReleaseCalls = 0

    function Read-TradingLabFixedStackLease {
      param([string]$LeasePath, [string]$RunToken)
      return $script:testLease
    }
    function Assert-TradingLabFixedStackGuardOwned {
      param([string]$GuardPath, [string]$RunToken)
      return $true
    }
    function Release-TradingLabFixedStackGuard {
      param([string]$GuardPath, [string]$RunToken)
      $script:testReleaseCalls += 1
      return $true
    }
    function Get-TradingLabFixedStackRuntimeSnapshot {
      param([object]$Context, [string]$Label)
      $rows = @(
        $script:testLease.ServiceIdentities | ForEach-Object {
          [pscustomobject]@{
            Service = [string]$_.Service
            ContainerId = [string]$_.ContainerId
            State = 'running'
            Running = $true
          }
        }
      )
      if ($Label -like 'cleanup-before-*') {
        $backend = @($rows | Where-Object Service -CEQ 'validation-backend')[0]
        $backend.State = 'exited'
        $backend.Running = $false
        if ($script:testScenario -ceq 'mismatch') {
          $backend.ContainerId = (('d' * 64) -join '')
        }
      } else {
        foreach ($row in $rows) {
          $row.State = 'exited'
          $row.Running = $false
        }
      }
      return $rows
    }
    function Invoke-TradingLabExternalProcess {
      param(
        [string]$Executable,
        [string[]]$Arguments,
        [string]$WorkingDirectory,
        [string]$StdoutPath,
        [string]$StderrPath,
        [object]$Environment,
        [AllowNull()][string]$StdinText
      )
      $script:testStopCalls.Add([pscustomobject]@{
        Executable = $Executable
        Arguments = @($Arguments)
      }) | Out-Null
      return [pscustomobject]@{
        ExitCode = 0
        StdoutPath = $StdoutPath
        StderrPath = $StderrPath
      }
    }

    $context = [pscustomobject]@{
      RunToken = $token
      KeepValidationRunning = $false
      FixedStackLeasePath = ${psQuote(leasePath)}
      FixedStackGuardPath = ${psQuote(guardPath)}
      ArtifactsDirectory = ${psQuote(fixtureRoot)}
    }
    $exitedResult = try {
      (Stop-TradingLabOwnedFixedStack -Context $context).Status
    } catch {
      $_.Exception.Message
    }
    $exitedCalls = @($script:testStopCalls)
    $exitedReleaseCalls = $script:testReleaseCalls

    $script:testScenario = 'mismatch'
    $script:testStopCalls.Clear()
    $script:testReleaseCalls = 0
    $mismatch = try {
      Stop-TradingLabOwnedFixedStack -Context $context | Out-Null
      $null
    } catch {
      $_.Exception.Message
    }
    [pscustomobject]@{
      exitedResult = $exitedResult
      exitedStopCalls = $exitedCalls.Count
      exitedExecutable = if ($exitedCalls.Count -eq 0) { $null } else {
        [string]$exitedCalls[0].Executable
      }
      exitedArguments = if ($exitedCalls.Count -eq 0) { @() } else {
        @($exitedCalls[0].Arguments)
      }
      exitedReleaseCalls = $exitedReleaseCalls
      mismatch = $mismatch
      mismatchStopCalls = $script:testStopCalls.Count
      mismatchReleaseCalls = $script:testReleaseCalls
    } | ConvertTo-Json -Depth 6 -Compress
  `)
    assertPowerShellSucceeded(result)
    const cleanup = parseLastJson(result.stdout)

    assert.equal(cleanup.exitedResult, 'PASS')
    assert.equal(cleanup.exitedStopCalls, 1)
    assert.equal(cleanup.exitedExecutable, 'docker')
    assert.deepEqual(cleanup.exitedArguments, [
      'stop',
      'b'.repeat(64),
      'c'.repeat(64),
    ])
    assert.doesNotMatch(
      cleanup.exitedArguments.join(' '),
      /\bcompose\b|fx-trading-validation|docker-compose\.validation\.yml/iu,
    )
    assert.equal(cleanup.exitedReleaseCalls, 1)
    assert.match(cleanup.mismatch, /identity.*drift|container.*id/iu)
    assert.equal(cleanup.mismatchStopCalls, 0)
    assert.equal(cleanup.mismatchReleaseCalls, 0)
  } finally {
    rmSync(fixtureRoot, { recursive: true, force: true })
  }
})

test('no-lease cleanup is untouched without a guard and fail-closed on guarded baseline drift', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const fixtureRoot = mkdtempSync(join(tmpdir(), 'trading-lab-no-lease-baseline-'))
  const leasePath = join(fixtureRoot, 'missing-fixed-stack-lease.json')
  const guardPath = join(fixtureRoot, 'fixed-stack-guard.json')
  const missingGuardPath = join(fixtureRoot, 'missing-fixed-stack-guard.json')
  writeFileSync(guardPath, '{}', 'utf8')

  try {
    const result = runPowerShell(`
      $token = 'task8-no-lease-baseline-token-0123456789abcdef'
      $services = @(Get-TradingLabFixedStackServiceNames)
      $ids = @(
        (('a' * 64) -join ''),
        (('b' * 64) -join ''),
        (('c' * 64) -join '')
      )
      $script:testStopped = @(
        for ($index = 0; $index -lt $services.Count; $index++) {
          [pscustomobject][ordered]@{
            Service = $services[$index]
            ContainerId = $ids[$index]
            State = 'exited'
            Running = $false
          }
        }
      )
      $script:testBaseline = [pscustomobject][ordered]@{
        SchemaVersion = 1
        Mode = 'FULL_EXITED'
        ServiceIdentities = @($script:testStopped)
        RecoveryProof = [pscustomobject]@{
          RunToken = 'historical-owner-token-0123456789abcdef'
          Status = 'PASS'
        }
      }
      $script:testStopCalls = 0
      $script:testReleaseCalls = 0
      $script:testBaselineChecks = 0
      $script:testSnapshotCalls = 0
      $script:testScenario = 'unchanged'

      function Assert-TradingLabFixedStackGuardOwned {
        param([string]$GuardPath, [string]$RunToken)
        return [pscustomobject][ordered]@{
          schemaVersion = 2
          runToken = $RunToken
          composeProject = 'fx-trading-validation'
          composeFile = 'fixture-compose-file'
          baseline = $script:testBaseline
        }
      }
      function Assert-TradingLabFixedStackBaselineCurrent {
        param(
          [object]$Baseline,
          [object[]]$ObservedContainers,
          [string]$ArtifactsRoot
        )
        $script:testBaselineChecks += 1
        if ($script:testScenario -ceq 'drift') {
          throw 'Fixed validation stack start baseline identity drifted'
        }
        return $true
      }
      function Release-TradingLabFixedStackGuard {
        param([string]$GuardPath, [string]$RunToken)
        $script:testReleaseCalls += 1
        return $true
      }
      function Get-TradingLabFixedStackRuntimeSnapshot {
        param([object]$Context, [string]$Label)
        $script:testSnapshotCalls += 1
        return @(
          $script:testStopped | ForEach-Object {
            [pscustomobject][ordered]@{
              Service = $_.Service
              ContainerId = $_.ContainerId
              State = $_.State
              Running = $_.Running
            }
          }
        )
      }
      function Invoke-TradingLabExternalProcess {
        param(
          [string]$Executable,
          [string[]]$Arguments,
          [string]$WorkingDirectory,
          [string]$StdoutPath,
          [string]$StderrPath,
          [object]$Environment,
          [AllowNull()][string]$StdinText
        )
        $script:testStopCalls += 1
        return [pscustomobject]@{
          ExitCode = 0
          StdoutPath = $StdoutPath
          StderrPath = $StderrPath
        }
      }

      $context = [pscustomobject]@{
        RunToken = $token
        KeepValidationRunning = $false
        FixedStackLeasePath = ${psQuote(leasePath)}
        FixedStackGuardPath = ${psQuote(guardPath)}
        ArtifactsRoot = ${psQuote(fixtureRoot)}
        ArtifactsDirectory = ${psQuote(fixtureRoot)}
      }
      $cleanupStatus = $null
      $cleanupError = $null
      try {
        $cleanupStatus = (
          Stop-TradingLabOwnedFixedStack -Context $context
        ).Status
      } catch {
        $cleanupError = $_.Exception.Message
      }

      $unchangedStopCalls = $script:testStopCalls
      $unchangedReleaseCalls = $script:testReleaseCalls
      $unchangedBaselineChecks = $script:testBaselineChecks
      $unchangedSnapshotCalls = $script:testSnapshotCalls

      $script:testScenario = 'drift'
      $script:testStopCalls = 0
      $script:testReleaseCalls = 0
      $script:testBaselineChecks = 0
      $script:testSnapshotCalls = 0
      $driftError = try {
        Stop-TradingLabOwnedFixedStack -Context $context | Out-Null
        $null
      } catch {
        $_.Exception.Message
      }

      $driftStopCalls = $script:testStopCalls
      $driftReleaseCalls = $script:testReleaseCalls
      $driftBaselineChecks = $script:testBaselineChecks
      $driftSnapshotCalls = $script:testSnapshotCalls

      $script:testScenario = 'unchanged'
      $script:testStopCalls = 0
      $script:testReleaseCalls = 0
      $script:testBaselineChecks = 0
      $script:testSnapshotCalls = 0
      $keepWithoutLeaseContext = [pscustomobject]@{
        RunToken = $token
        KeepValidationRunning = $true
        FixedStackLeasePath = ${psQuote(leasePath)}
        FixedStackGuardPath = ${psQuote(guardPath)}
        ArtifactsRoot = ${psQuote(fixtureRoot)}
        ArtifactsDirectory = ${psQuote(fixtureRoot)}
      }
      $keepWithoutLeaseStatus = (
        Stop-TradingLabOwnedFixedStack -Context $keepWithoutLeaseContext
      ).Status
      $keepWithoutLeaseStopCalls = $script:testStopCalls
      $keepWithoutLeaseReleaseCalls = $script:testReleaseCalls
      $keepWithoutLeaseBaselineChecks = $script:testBaselineChecks
      $keepWithoutLeaseSnapshotCalls = $script:testSnapshotCalls

      $script:testScenario = 'no-guard'
      $script:testStopCalls = 0
      $script:testReleaseCalls = 0
      $script:testBaselineChecks = 0
      $script:testSnapshotCalls = 0
      $noGuardContext = [pscustomobject]@{
        RunToken = $token
        KeepValidationRunning = $false
        FixedStackLeasePath = ${psQuote(leasePath)}
        FixedStackGuardPath = ${psQuote(missingGuardPath)}
        ArtifactsRoot = ${psQuote(fixtureRoot)}
        ArtifactsDirectory = ${psQuote(fixtureRoot)}
      }
      $noGuardStatus = $null
      $noGuardError = $null
      try {
        $noGuardStatus = (
          Stop-TradingLabOwnedFixedStack -Context $noGuardContext
        ).Status
      } catch {
        $noGuardError = $_.Exception.Message
      }
      [pscustomobject]@{
        cleanupStatus = $cleanupStatus
        cleanupError = $cleanupError
        unchangedStopCalls = $unchangedStopCalls
        unchangedReleaseCalls = $unchangedReleaseCalls
        unchangedBaselineChecks = $unchangedBaselineChecks
        unchangedSnapshotCalls = $unchangedSnapshotCalls
        driftError = $driftError
        driftStopCalls = $driftStopCalls
        driftReleaseCalls = $driftReleaseCalls
        driftBaselineChecks = $driftBaselineChecks
        driftSnapshotCalls = $driftSnapshotCalls
        keepWithoutLeaseStatus = $keepWithoutLeaseStatus
        keepWithoutLeaseStopCalls = $keepWithoutLeaseStopCalls
        keepWithoutLeaseReleaseCalls = $keepWithoutLeaseReleaseCalls
        keepWithoutLeaseBaselineChecks = $keepWithoutLeaseBaselineChecks
        keepWithoutLeaseSnapshotCalls = $keepWithoutLeaseSnapshotCalls
        noGuardStatus = $noGuardStatus
        noGuardError = $noGuardError
        noGuardStopCalls = $script:testStopCalls
        noGuardReleaseCalls = $script:testReleaseCalls
        noGuardBaselineChecks = $script:testBaselineChecks
        noGuardSnapshotCalls = $script:testSnapshotCalls
      } | ConvertTo-Json -Compress
    `)
    assertPowerShellSucceeded(result)
    const cleanup = parseLastJson(result.stdout)

    assert.equal(cleanup.cleanupError, null)
    assert.equal(cleanup.cleanupStatus, 'PASS')
    assert.equal(cleanup.unchangedStopCalls, 0)
    assert.equal(cleanup.unchangedReleaseCalls, 1)
    assert.equal(cleanup.unchangedBaselineChecks, 1)
    assert.equal(cleanup.unchangedSnapshotCalls, 1)
    assert.match(cleanup.driftError, /baseline|identity|drift/iu)
    assert.equal(cleanup.driftStopCalls, 0)
    assert.equal(cleanup.driftReleaseCalls, 0)
    assert.equal(cleanup.driftBaselineChecks, 1)
    assert.equal(cleanup.driftSnapshotCalls, 1)
    assert.equal(cleanup.keepWithoutLeaseStatus, 'PASS')
    assert.equal(cleanup.keepWithoutLeaseStopCalls, 0)
    assert.equal(cleanup.keepWithoutLeaseReleaseCalls, 1)
    assert.equal(cleanup.keepWithoutLeaseBaselineChecks, 1)
    assert.equal(cleanup.keepWithoutLeaseSnapshotCalls, 1)
    assert.equal(cleanup.noGuardError, null)
    assert.equal(cleanup.noGuardStatus, 'PASS')
    assert.equal(cleanup.noGuardStopCalls, 0)
    assert.equal(cleanup.noGuardReleaseCalls, 0)
    assert.equal(cleanup.noGuardBaselineChecks, 0)
    assert.equal(cleanup.noGuardSnapshotCalls, 0)
  } finally {
    rmSync(fixtureRoot, { recursive: true, force: true })
  }
})

test('normal run specs freeze all 15 phases and the required Task 8 command architecture', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const candidateRoot = join(tmpdir(), 'trading-lab-fake-candidate')
  const artifactsRoot = join(tmpdir(), 'trading-lab-fake-artifacts')
  const result = runPowerShell(`
    $context = [pscustomobject]@{
      CandidatePlatformRoot = ${psQuote(candidateRoot)}
      ArtifactsDirectory = ${psQuote(artifactsRoot)}
      OwnedBackendJarPath = ${psQuote(join(artifactsRoot, 'owned-main-backend.jar'))}
      RunToken = 'task8-normal-spec-token-0123456789abcdef'
    }
    $packageScripts = @{
      'smoke:trading-lab:isolated' = 'node scripts/run-trading-lab-isolated-smoke.mjs'
    }
    $phases = @(Get-TradingLabNormalPhaseSpecs -Context $context -PackageScripts $packageScripts)
    $commands = @($phases | ForEach-Object { @($_.Commands) })
    [pscustomobject]@{
      phaseNumbers = @($phases.Number)
      phaseNames = @($phases.Name)
      alwaysRun = @($phases | Where-Object AlwaysRun | ForEach-Object Number)
      commandIds = @($commands.Id)
      phase2 = @($commands | Where-Object PhaseNumber -eq 2)
      phase7 = @($commands | Where-Object PhaseNumber -eq 7)
      phase8 = @($commands | Where-Object PhaseNumber -eq 8)
      phase9 = @($commands | Where-Object PhaseNumber -eq 9)
      phase10 = @($commands | Where-Object PhaseNumber -eq 10)
      phase11 = @($commands | Where-Object PhaseNumber -eq 11)
      phase12 = @($commands | Where-Object PhaseNumber -eq 12)
      phase13 = @($commands | Where-Object PhaseNumber -eq 13)
      phase14 = @($commands | Where-Object PhaseNumber -eq 14)
      phase15 = @($commands | Where-Object PhaseNumber -eq 15)
    } | ConvertTo-Json -Depth 12 -Compress
  `)
  assertPowerShellSucceeded(result)
  const contract = parseLastJson(result.stdout)
  assert.deepEqual(contract.phaseNumbers, Array.from({ length: 15 }, (_, index) => index + 1))
  assert.deepEqual(contract.alwaysRun, [14, 15])
  assert.deepEqual(contract.phase2.map(({ Id }) => Id), [
    'backend-pre-docker-tests',
    'backend-boot-jar-package',
    'copy-owned-backend-jar',
  ])
  for (const command of contract.phase2.slice(0, 2)) {
    assert.equal(resolve(command.WorkingDirectory), join(candidateRoot, 'backend'))
    assert.equal(command.Executable, 'mvn')
  }
  assert.equal(contract.phase2[2].Operation, 'COPY_OWNED_BOOT_JAR')
  assert.equal(
    resolve(contract.phase2[2].SourcePath),
    join(candidateRoot, 'backend', 'target', 'fx-platform-backend-0.1.0.jar'),
  )
  assert.equal(
    resolve(contract.phase2[2].DestinationPath),
    join(artifactsRoot, 'owned-main-backend.jar'),
  )

  assert.equal(contract.phase7.length, 1)
  assert.equal(contract.phase7[0].Executable, 'mvn')
  assert.equal(resolve(contract.phase7[0].WorkingDirectory), join(candidateRoot, 'backend'))
  const httpItArgs = contract.phase7[0].Arguments.join(' ')
  for (const requiredClass of [
    'TradingLabSpotHttpIT',
    'TradingLabIsolatedPerpetualHttpIT',
    'TradingLabCrossMultiSymbolHttpIT',
    'TradingLabProtectionFundingHttpIT',
    'TradingLabNegativeHttpIT',
    'TradingLabLifecycleHttpIT',
    'TradingLabIsolationHttpIT',
  ]) {
    assert.match(httpItArgs, new RegExp(requiredClass, 'u'))
  }
  assert.match(httpItArgs, /-Dapi\.version=1\.40/u)
  assert.deepEqual(contract.phase8.map(({ Id }) => Id), [
    'admin-tests',
    'admin-build',
  ])
  assert.deepEqual(contract.phase9.map(({ Id }) => Id), [
    'web-tests',
    'web-build',
  ])
  assert.equal(contract.phase10[0].Arguments.at(-1), 'verify:architecture')
  assert.equal(contract.phase11[0].RequiredAlias, 'smoke:trading-lab:isolated')
  assert.equal(contract.phase11[0].Availability, 'READY')
  assert.deepEqual(
    contract.phase11[0].Arguments.slice(-4),
    [
      `--artifacts=${artifactsRoot}`,
      '--admin-url=http://127.0.0.1:5174',
      '--api-url=http://127.0.0.1:18086',
      '--attach-admin',
    ],
  )
  assert.deepEqual(contract.phase12.map(({ Id }) => Id), [
    'large-report-tests',
    'runtime-isolation-contract-tests',
  ])
  assert.deepEqual(contract.phase13[0].Arguments, [
    'clean',
    'test',
    '-Dapi.version=1.40',
  ])
  assert.equal(contract.phase14[0].Operation, 'PROVISIONAL_REPORT')
  assert.equal(contract.phase15[0].Operation, 'OWNED_CLEANUP')
})

test('fixed-stack guard and exact gates surround the fixed-tag image build before startup', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const candidateRoot = join(tmpdir(), 'trading-lab-owned-build-order-candidate')
  const artifactsRoot = join(tmpdir(), 'trading-lab-owned-build-order-artifacts')
  const result = runPowerShell(`
    $context = [pscustomobject]@{
      CandidatePlatformRoot = ${psQuote(candidateRoot)}
      ArtifactsDirectory = ${psQuote(artifactsRoot)}
      OwnedBackendJarPath = ${psQuote(join(artifactsRoot, 'owned-main-backend.jar'))}
      RunToken = 'task8-owned-build-order-token-0123456789abcdef'
    }
    $phases = @(Get-TradingLabNormalPhaseSpecs -Context $context -PackageScripts @{
      'smoke:trading-lab:isolated' = 'node scripts/run-trading-lab-isolated-smoke.mjs'
    })
    [pscustomobject]@{
      commandIds = @(
        $phases |
          ForEach-Object { @($_.Commands) } |
          ForEach-Object { [string]$_.Id }
      )
      phase3Ids = @(
        $phases |
          ForEach-Object { @($_.Commands) } |
          Where-Object PhaseNumber -eq 3 |
          ForEach-Object { [string]$_.Id }
      )
      phase4Ids = @(
        $phases |
          ForEach-Object { @($_.Commands) } |
          Where-Object PhaseNumber -eq 4 |
          ForEach-Object { [string]$_.Id }
      )
    } | ConvertTo-Json -Depth 6 -Compress
  `)
  assertPowerShellSucceeded(result)
  const { commandIds, phase3Ids, phase4Ids } = parseLastJson(result.stdout)
  const guard = commandIds.indexOf('acquire-fixed-stack-guard')
  const afterGuardGate = commandIds.indexOf(
    'assert-fixed-stack-baseline-current-after-guard',
  )
  const build = commandIds.indexOf('build-validation-image')
  const beforeUpGate = commandIds.indexOf(
    'assert-fixed-stack-baseline-current-before-up',
  )
  const start = commandIds.indexOf('start-validation-compose')

  for (const [name, index] of [
    ['fixed-stack guard', guard],
    ['post-guard exact gate', afterGuardGate],
    ['fixed-tag image build', build],
    ['pre-up exact gate', beforeUpGate],
    ['validation Compose start', start],
  ]) {
    assert.notEqual(index, -1, `${name} command is missing`)
  }
  assert.deepEqual(phase3Ids, [
    'acquire-fixed-stack-guard',
    'assert-fixed-stack-baseline-current-after-guard',
    'build-validation-image',
  ])
  assert.deepEqual(phase4Ids, [
    'assert-fixed-stack-baseline-current-before-up',
    'start-validation-compose',
    'capture-fixed-stack-lease',
    'reconcile-validation-postgres-credential',
  ])
  assert.equal(
    guard < afterGuardGate
      && afterGuardGate < build
      && build < beforeUpGate
      && beforeUpGate < start,
    true,
    `unsafe fixed-stack command order: ${commandIds.join(' -> ')}`,
  )
})

test('Phase 4 reconciles the leased validation PostgreSQL credential before Supervisor startup', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const candidateRoot = join(tmpdir(), 'trading-lab-credential-order-candidate')
  const artifactsRoot = join(tmpdir(), 'trading-lab-credential-order-artifacts')
  const result = runPowerShell(`
    $context = [pscustomobject]@{
      CandidatePlatformRoot = ${psQuote(candidateRoot)}
      ArtifactsDirectory = ${psQuote(artifactsRoot)}
      OwnedBackendJarPath = ${psQuote(join(artifactsRoot, 'owned-main-backend.jar'))}
      RunToken = 'task8-credential-order-token-0123456789abcdef'
    }
    $phases = @(Get-TradingLabNormalPhaseSpecs -Context $context -PackageScripts @{
      'smoke:trading-lab:isolated' = 'node scripts/run-trading-lab-isolated-smoke.mjs'
    })
    $commands = @($phases | ForEach-Object { @($_.Commands) })
    [pscustomobject]@{
      commands = @($commands | ForEach-Object {
        [pscustomobject]@{
          Id = [string]$_.Id
          Operation = [string]$_.Operation
          PhaseNumber = [int]$_.PhaseNumber
        }
      })
    } | ConvertTo-Json -Depth 8 -Compress
  `)
  assertPowerShellSucceeded(result)
  const { commands } = parseLastJson(result.stdout)
  const captureIndex = commands.findIndex(({ Id }) =>
    Id === 'capture-fixed-stack-lease')
  const reconcileIndex = commands.findIndex(({ Id }) =>
    Id === 'reconcile-validation-postgres-credential')
  const supervisorIndex = commands.findIndex(({ Id }) =>
    Id === 'start-supervisor-owned-process')

  assert.notEqual(captureIndex, -1, 'the fixed-stack lease capture is missing')
  assert.notEqual(
    reconcileIndex,
    -1,
    'Phase 4 must reconcile the validation PostgreSQL credential after lease capture',
  )
  assert.equal(
    commands[reconcileIndex].Operation,
    'RECONCILE_VALIDATION_POSTGRES_CREDENTIAL',
  )
  assert.equal(commands[reconcileIndex].PhaseNumber, 4)
  assert.equal(captureIndex < reconcileIndex, true)
  assert.equal(reconcileIndex < supervisorIndex, true)
})

test('fake executor records evidence and continues only report/cleanup after the first failure', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const candidateRoot = join(tmpdir(), 'trading-lab-fake-run-candidate')
  const artifactsRoot = join(tmpdir(), 'trading-lab-fake-run-artifacts')
  const result = runPowerShell(`
    $context = [pscustomobject]@{
      CandidatePlatformRoot = ${psQuote(candidateRoot)}
      ArtifactsDirectory = ${psQuote(artifactsRoot)}
      OwnedBackendJarPath = ${psQuote(join(artifactsRoot, 'owned-main-backend.jar'))}
      RunToken = 'task8-fake-run-owner-token-0123456789abcdef'
    }
    $packageScripts = @{
      'smoke:trading-lab:isolated' = 'node scripts/run-trading-lab-isolated-smoke.mjs'
    }
    $calls = [System.Collections.Generic.List[string]]::new()
    $script:fakeClock = [DateTimeOffset]::Parse('2026-07-25T01:00:00Z')
    $clock = {
      $value = $script:fakeClock
      $script:fakeClock = $script:fakeClock.AddSeconds(1)
      return $value
    }
    $executor = {
      param($command, $runState)
      $calls.Add($command.Id) | Out-Null
      [pscustomobject]@{
        ExitCode = if ($command.Id -eq 'validation-http-integration-tests') { 23 } else { 0 }
        StdoutPath = Join-Path ${psQuote(artifactsRoot)} ($command.Id + '.stdout.log')
        StderrPath = Join-Path ${psQuote(artifactsRoot)} ($command.Id + '.stderr.log')
        SurefireSummary = if ($command.Id -eq 'backend-pre-docker-tests') {
          [pscustomobject]@{
            Tests = 17
            Failures = 0
            Errors = 0
            Skipped = 0
            Classes = 3
          }
        } else {
          $null
        }
      }
    }
    $run = Invoke-TradingLabValidationRun -Context $context -PackageScripts $packageScripts -InvokeCommand $executor -GetUtcNow $clock
    $failed = @($run.Phases[6].Commands)[0]
    [pscustomobject]@{
      status = $run.Status
      phaseStatuses = @($run.Phases.Status)
      calls = @($calls)
      failed = $failed
      surefireRecord = @($run.Phases[1].Commands)[0]
      provisionalTemplate = $run.ProvisionalReportTemplate
    } | ConvertTo-Json -Depth 12 -Compress
  `)
  assertPowerShellSucceeded(result)
  const run = parseLastJson(result.stdout)
  assert.equal(run.status, 'FAIL')
  assert.deepEqual(run.phaseStatuses.slice(0, 7), [
    'PASS', 'PASS', 'PASS', 'PASS', 'PASS', 'PASS', 'FAIL',
  ])
  assert.deepEqual(run.phaseStatuses.slice(7, 13), Array(6).fill('NOT_RUN'))
  assert.deepEqual(run.phaseStatuses.slice(13), ['PASS', 'PASS'])
  assert.equal(run.calls.includes('admin-tests'), false)
  assert.equal(run.calls.includes('large-report-tests'), false)
  assert.equal(run.calls.includes('required-full-backend-tests'), false)
  assert.deepEqual(run.calls.slice(-2), [
    'provisional-verification-report',
    'owned-cleanup',
  ])
  assert.equal(run.failed.PhaseNumber, 7)
  assert.equal(run.failed.Executable, 'mvn')
  assert.equal(resolve(run.failed.WorkingDirectory), join(candidateRoot, 'backend'))
  assert.equal(run.failed.ExitCode, 23)
  assert.equal(run.failed.Classification, 'FAIL')
  assert.equal(run.failed.Status, 'FAIL')
  assert.match(run.failed.StartedAtUtc, /^2026-07-25T01:/u)
  assert.equal(
    Date.parse(run.failed.EndedAtUtc) - Date.parse(run.failed.StartedAtUtc),
    1000,
  )
  assert.match(run.failed.StdoutPath, /validation-http-integration-tests\.stdout\.log$/u)
  assert.match(run.failed.StderrPath, /validation-http-integration-tests\.stderr\.log$/u)
  assert.deepEqual(run.surefireRecord.Surefire, {
    Tests: 17,
    Failures: 0,
    Errors: 0,
    Skipped: 0,
    Classes: 3,
  })
  assert.match(run.provisionalTemplate, /provisional/iu)
  assert.match(run.provisionalTemplate, /cleanup[^\r\n]*pending/iu)
  assert.match(run.provisionalTemplate, /validation-http-integration-tests[^\r\n]*FAIL/iu)
})

test('final report publishes truthful cleanup-aware status and Surefire totals', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const fixtureRoot = mkdtempSync(join(tmpdir(), 'trading-lab-final-report-'))
  try {
    const result = runPowerShell(`
      $spec = [pscustomobject]@{
        PhaseNumber = 2
        PhaseName = 'backend pre-Docker tests'
        Id = 'backend-pre-docker-tests'
        Operation = 'MAVEN_TEST'
        Executable = 'mvn'
        Arguments = @('clean', 'test')
        WorkingDirectory = 'C:\\candidate\\backend'
      }
      $record = New-TradingLabRunCommandRecord -Spec $spec -Classification 'PASS' -StartedAtUtc ([DateTimeOffset]::Parse('2026-07-26T00:00:00Z')) -EndedAtUtc ([DateTimeOffset]::Parse('2026-07-26T00:01:00Z')) -ExitCode 0 -StdoutPath 'commands\\backend.stdout.log' -StderrPath 'commands\\backend.stderr.log' -Reason $null -SurefireSummary ([pscustomobject]@{
        Tests = 17
        Failures = 0
        Errors = 0
        Skipped = 0
        Classes = 3
      })
      function Invoke-FinalReportCase {
        param(
          [string]$Name,
          [string]$BusinessStatus,
          [string]$CleanupStatus
        )
        $template = Get-TradingLabVerificationReportTemplate -RunResult ([pscustomobject]@{
          Status = $BusinessStatus
          Phases = @(
            [pscustomobject]@{
              Number = 2
              Name = 'backend pre-Docker tests'
              Status = 'PASS'
              Commands = @($record)
            }
          )
        })
        $path = Join-Path ${psQuote(fixtureRoot)} ($Name + '.md')
        Complete-TradingLabVerificationReport -Context ([pscustomobject]@{
          FinalReportPath = $path
        }) -ProvisionalTemplate $template -CleanupReceipt ([pscustomobject]@{
          status = $CleanupStatus
          details = @()
        }) | Out-Null
        [pscustomobject]@{
          name = $Name
          content = [System.IO.File]::ReadAllText($path)
        }
      }
      @(
        Invoke-FinalReportCase -Name 'pass-pass' -BusinessStatus 'PASS' -CleanupStatus 'PASS'
        Invoke-FinalReportCase -Name 'pass-partial' -BusinessStatus 'PASS' -CleanupStatus 'PARTIAL'
        Invoke-FinalReportCase -Name 'pass-fail' -BusinessStatus 'PASS' -CleanupStatus 'FAIL'
        Invoke-FinalReportCase -Name 'blocked-pass' -BusinessStatus 'BLOCKED' -CleanupStatus 'PASS'
        Invoke-FinalReportCase -Name 'fail-partial' -BusinessStatus 'FAIL' -CleanupStatus 'PARTIAL'
      ) | ConvertTo-Json -Depth 12 -Compress
    `)
    assertPowerShellSucceeded(result)
    const reports = parseLastJson(result.stdout)
    const expected = new Map([
      ['pass-pass', { overall: 'PASS', cleanup: 'PASS' }],
      ['pass-partial', { overall: 'PARTIAL', cleanup: 'PARTIAL' }],
      ['pass-fail', { overall: 'FAIL', cleanup: 'FAIL' }],
      ['blocked-pass', { overall: 'BLOCKED', cleanup: 'PASS' }],
      ['fail-partial', { overall: 'FAIL', cleanup: 'PARTIAL' }],
    ])
    for (const report of reports) {
      const statuses = expected.get(report.name)
      assert.ok(statuses, `unexpected final report case: ${report.name}`)
      assert.match(report.content, /^# Trading Lab Verification Report$/mu)
      assert.match(
        report.content,
        new RegExp(`^Overall status: ${statuses.overall}$`, 'mu'),
      )
      assert.match(
        report.content,
        new RegExp(`^Cleanup status: ${statuses.cleanup}$`, 'mu'),
      )
      assert.doesNotMatch(report.content, /\(provisional\)|\bPENDING\b/iu)
      assert.doesNotMatch(report.content, /document is provisional/iu)
      assert.doesNotMatch(report.content, /\{\{[A-Z_]+\}\}/u)
      assert.match(report.content, /"status":"(?:PASS|PARTIAL|FAIL)"/u)
      assert.match(
        report.content,
        /\| Tests \| Failures \| Errors \| Skipped \|/u,
      )
      assert.match(
        report.content,
        /\| backend-pre-docker-tests \| PASS \| 0 \| 17 \| 0 \| 0 \| 0 \|/u,
      )
    }
  } finally {
    rmSync(fixtureRoot, { recursive: true, force: true })
  }
})

test('verification report template enumerates the stable Phase 4 Task 8 scope', () => {
  const result = runPowerShell(`
    [pscustomobject]@{
      content = Get-TradingLabVerificationReportTemplate -RunResult ([pscustomobject]@{
        Status = 'PASS'
        Phases = @()
      })
    } | ConvertTo-Json -Compress
  `)
  assertPowerShellSucceeded(result)
  const { content } = parseLastJson(result.stdout)
  for (const required of [
    '/trading/lab',
    'TRADING_LAB_VIEW',
    'TRADING_LAB_EXECUTE',
    'SUPER_ADMIN',
    '/api/admin/trading-lab/scenarios',
    '/api/admin/trading-lab/runs/{id}/events',
    '/api/admin/trading-lab/reports/{id}/download',
    '/api/admin/trading-lab/environment/{action}',
    '/internal/validation/reset',
    'start',
    'status',
    'health',
    'stop',
    'decimal fixed-point',
    'weighted-average',
    'isolated',
    'cross',
    'funding',
    'liquidation',
    '14-section',
    'API_TRACE',
    'metadata',
    'actor',
    'configSnapshot',
    'localCalculation',
    'apiTrace',
    'marketTicks',
    'checkpoints',
    'actualState',
    'errors',
    'cleanup',
    '24 fixed',
    '12 legal',
    '12 negative',
    'phase4-fixed-01-spot-cycle',
    'phase4-random-legal-01-spot-balanced',
    resolve(join(platformRoot, 'scripts', 'run-trading-lab-validation.ps1')),
    resolve(join(platformRoot, 'scripts', 'smoke-trading-lab.mjs')),
    resolve(join(platformRoot, 'docs', 'testing', 'trading-lab', 'report-schema.json')),
  ]) {
    assert.equal(
      content.includes(required),
      true,
      `verification report is missing stable Task 8 content: ${required}`,
    )
  }
})

test('phase 14 failure remains FAIL in a final report containing phase 14 and 15 evidence', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const fixtureRoot = mkdtempSync(join(tmpdir(), 'trading-lab-phase14-final-'))
  try {
    const finalReportPath = join(fixtureRoot, 'final.md')
    const result = runPowerShell(`
      $context = [pscustomobject]@{
        CandidatePlatformRoot = 'C:\\fake-candidate'
        ArtifactsDirectory = ${psQuote(fixtureRoot)}
        OwnedBackendJarPath = ${psQuote(join(fixtureRoot, 'owned-backend.jar'))}
        RunToken = 'task8-phase14-final-owner-0123456789abcdef'
        KeepValidationRunning = $false
        CleanupReceiptPath = ${psQuote(join(fixtureRoot, 'cleanup-receipt.json'))}
        ProvisionalReportPath = ${psQuote(join(fixtureRoot, 'provisional.md'))}
        FinalReportPath = ${psQuote(finalReportPath)}
      }
      $scripts = @{
        'smoke:trading-lab:isolated' = 'node scripts/smoke-trading-lab-isolated.mjs'
      }
      $script:phase14Clock = [DateTimeOffset]::Parse('2026-07-26T03:00:00Z')
      $run = Invoke-TradingLabValidationRun -Context $context -PackageScripts $scripts -GetUtcNow {
        $value = $script:phase14Clock
        $script:phase14Clock = $script:phase14Clock.AddSeconds(1)
        $value
      } -InvokeCommand {
        param($command, $runState)
        if ($command.Id -eq 'owned-cleanup') {
          $receipt = [ordered]@{
            schemaVersion = 1
            runToken = [string]$runState.Context.RunToken
            status = 'PASS'
            completedAtUtc = '2026-07-26T03:30:00Z'
            details = @()
          }
          [System.IO.File]::WriteAllText(
            [string]$runState.Context.CleanupReceiptPath,
            ($receipt | ConvertTo-Json -Depth 8 -Compress)
          )
        }
        [pscustomobject]@{
          ExitCode = if (
            $command.Id -eq 'provisional-verification-report'
          ) { 97 } else { 0 }
          StdoutPath = Join-Path ${psQuote(fixtureRoot)} ($command.Id + '.stdout.log')
          StderrPath = Join-Path ${psQuote(fixtureRoot)} ($command.Id + '.stderr.log')
        }
      }
      $finalExists = Test-Path -LiteralPath $context.FinalReportPath -PathType Leaf
      [pscustomobject]@{
        status = $run.Status
        phase14 = $run.Phases[13].Status
        phase15 = $run.Phases[14].Status
        finalExists = $finalExists
        final = if ($finalExists) {
          [System.IO.File]::ReadAllText($context.FinalReportPath)
        } else {
          $null
        }
      } | ConvertTo-Json -Depth 8 -Compress
    `)
    assertPowerShellSucceeded(result)
    const evidence = parseLastJson(result.stdout)
    assert.equal(evidence.status, 'FAIL')
    assert.equal(evidence.phase14, 'FAIL')
    assert.equal(evidence.phase15, 'PASS')
    assert.equal(evidence.finalExists, true)
    assert.match(evidence.final, /^Overall status: FAIL$/mu)
    assert.match(
      evidence.final,
      /\| 14 \| provisional-verification-report \| FAIL \|/u,
    )
    assert.match(
      evidence.final,
      /\| 15 \| owned-cleanup \| PASS \|/u,
    )
  } finally {
    rmSync(fixtureRoot, { recursive: true, force: true })
  }
})

test('missing isolated-smoke alias is BLOCKED while an explicit browser skip is only PARTIAL', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const result = runPowerShell(`
    function Invoke-FakeRun {
      param([hashtable]$Scripts, [switch]$SkipBrowser)
      $context = [pscustomobject]@{
        CandidatePlatformRoot = 'C:\\fake-candidate'
        ArtifactsDirectory = 'C:\\fake-artifacts'
        OwnedBackendJarPath = 'C:\\fake-artifacts\\owned-main-backend.jar'
        RunToken = 'task8-alias-run-owner-token-0123456789abcdef'
      }
      $calls = [System.Collections.Generic.List[string]]::new()
      $script:aliasClock = [DateTimeOffset]::Parse('2026-07-25T02:00:00Z')
      $run = Invoke-TradingLabValidationRun -Context $context -PackageScripts $Scripts -SkipBrowser:$SkipBrowser -GetUtcNow {
        $value = $script:aliasClock
        $script:aliasClock = $script:aliasClock.AddSeconds(1)
        $value
      } -InvokeCommand {
        param($command, $runState)
        $calls.Add($command.Id) | Out-Null
        [pscustomobject]@{
          ExitCode = 0
          StdoutPath = "C:\\fake-artifacts\\$($command.Id).stdout.log"
          StderrPath = "C:\\fake-artifacts\\$($command.Id).stderr.log"
        }
      }
      return [pscustomobject]@{
        Status = $run.Status
        PhaseStatuses = @($run.Phases.Status)
        Calls = @($calls)
        BrowserReason = @($run.Phases[10].Commands)[0].Reason
      }
    }
    $blocked = Invoke-FakeRun -Scripts @{}
    $partial = Invoke-FakeRun -Scripts @{
      'smoke:trading-lab:isolated' = 'node scripts/run-trading-lab-isolated-smoke.mjs'
    } -SkipBrowser
    [pscustomobject]@{
      blocked = $blocked
      partial = $partial
    } | ConvertTo-Json -Depth 10 -Compress
  `)
  assertPowerShellSucceeded(result)
  const { blocked, partial } = parseLastJson(result.stdout)
  assert.equal(blocked.Status, 'BLOCKED')
  assert.equal(blocked.PhaseStatuses[10], 'BLOCKED')
  assert.deepEqual(blocked.PhaseStatuses.slice(11, 13), ['NOT_RUN', 'NOT_RUN'])
  assert.match(blocked.BrowserReason, /missing.*smoke:trading-lab:isolated/iu)
  assert.equal(blocked.Calls.includes('isolated-browser-smoke'), false)
  assert.deepEqual(blocked.Calls.slice(-2), [
    'provisional-verification-report',
    'owned-cleanup',
  ])

  assert.equal(partial.Status, 'PARTIAL')
  assert.equal(partial.PhaseStatuses[10], 'NOT_RUN')
  assert.equal(partial.Calls.includes('isolated-browser-smoke'), false)
  assert.equal(partial.Calls.includes('large-report-tests'), true)
  assert.equal(partial.Calls.includes('required-full-backend-tests'), true)
  assert.deepEqual(partial.Calls.slice(-2), [
    'provisional-verification-report',
    'owned-cleanup',
  ])
})

test('normal run throws a combined primary and cleanup failure without losing either cause', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const result = runPowerShell(`
    $context = [pscustomobject]@{
      CandidatePlatformRoot = 'C:\\fake-candidate'
      ArtifactsDirectory = 'C:\\fake-artifacts'
      OwnedBackendJarPath = 'C:\\fake-artifacts\\owned-main-backend.jar'
      RunToken = 'task8-combined-run-owner-token-0123456789abcdef'
    }
    try {
      Invoke-TradingLabValidationRun -Context $context -PackageScripts @{
        'smoke:trading-lab:isolated' = 'node scripts/run-trading-lab-isolated-smoke.mjs'
      } -ThrowOnFailure -GetUtcNow {
        [DateTimeOffset]::Parse('2026-07-25T03:00:00Z')
      } -InvokeCommand {
        param($command, $runState)
        [pscustomobject]@{
          ExitCode = if ($command.Id -eq 'validation-http-integration-tests') {
            31
          } elseif ($command.Id -eq 'owned-cleanup') {
            41
          } else {
            0
          }
          StdoutPath = "C:\\fake-artifacts\\$($command.Id).stdout.log"
          StderrPath = "C:\\fake-artifacts\\$($command.Id).stderr.log"
        }
      } | Out-Null
      throw 'expected the run to throw'
    } catch {
      $exception = $_.Exception
      [pscustomobject]@{
        type = $exception.GetType().FullName
        innerMessages = @($exception.InnerExceptions | ForEach-Object Message)
      } | ConvertTo-Json -Depth 6 -Compress
    }
  `)
  assertPowerShellSucceeded(result)
  const caught = parseLastJson(result.stdout)
  assert.equal(caught.type, 'System.AggregateException')
  assert.equal(caught.innerMessages.length, 2)
  assert.match(caught.innerMessages[0], /validation-http-integration-tests.*31/iu)
  assert.match(caught.innerMessages[1], /owned-cleanup.*41/iu)
})

test('atomic stack lease, Docker JSON ownership, and owned-process receipts fail closed', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const fixtureRoot = mkdtempSync(join(tmpdir(), 'trading-lab-runner-ownership-'))
  const leasePath = join(fixtureRoot, 'fixed-stack-lease.json')
  const processReceiptPath = join(fixtureRoot, 'owned-process-receipt.json')
  try {
    const result = runPowerShell(`
      $token = 'task8-atomic-lease-owner-token-0123456789abcdef'
      $ids = @(
        (('a' * 64) -join ''),
        (('b' * 64) -join ''),
        (('c' * 64) -join '')
      )
      $services = @(Get-TradingLabFixedStackServiceNames)
      $dockerRows = @(
        for ($index = 0; $index -lt $services.Count; $index++) {
          [ordered]@{
            ID = $ids[$index]
            State = 'running'
            Labels = (
              'com.docker.compose.project=fx-trading-validation,' +
              "com.docker.compose.service=$($services[$index])"
            )
          }
        }
      )
      $observed = @(ConvertFrom-TradingLabDockerPsJson -Json ($dockerRows | ConvertTo-Json -Depth 6 -Compress))
      $lease = New-TradingLabFixedStackLease -RunToken $token -ObservedContainers $observed
      Write-TradingLabFixedStackLeaseAtomic -LeasePath ${psQuote(leasePath)} -Lease $lease
      $reloaded = Read-TradingLabFixedStackLease -LeasePath ${psQuote(leasePath)} -RunToken $token
      $collision = try {
        Write-TradingLabFixedStackLeaseAtomic -LeasePath ${psQuote(leasePath)} -Lease $lease
        $null
      } catch { $_.Exception.Message }
      $badLabels = @($dockerRows | ForEach-Object {
        [ordered]@{
          ID = $_.ID
          State = $_.State
          Labels = $_.Labels
        }
      })
      $badLabels[0].Labels = (
        'com.docker.compose.project=another-project,' +
        'com.docker.compose.service=validation-backend'
      )
      $labelFailure = try {
        ConvertFrom-TradingLabDockerPsJson -Json ($badLabels | ConvertTo-Json -Depth 6 -Compress) | Out-Null
        $null
      } catch { $_.Exception.Message }

      $started = [DateTimeOffset]::Parse('2026-07-25T04:00:00.1234567Z')
      $receipt = New-TradingLabOwnedProcessReceipt -RunToken $token -Name 'main-backend' -ProcessId 4242 -StartedAtUtc $started -Executable 'java' -Arguments @('-jar', 'owned-main-backend.jar')
      $observedProcess = [pscustomobject]@{
        ProcessId = 4242
        StartedAtUtc = $started
        Executable = 'java'
        Arguments = @('-jar', 'owned-main-backend.jar')
      }
      $validProcessStop = Assert-TradingLabOwnedProcessCanStop -RunToken $token -Receipt $receipt -ObservedProcess $observedProcess
      Write-TradingLabOwnedJsonAtomic -Path ${psQuote(processReceiptPath)} -Value $receipt -Label 'test owned process receipt' | Out-Null
      $reloadedReceipt = Read-TradingLabOwnedProcessReceipt -Path ${psQuote(processReceiptPath)} -RunToken $token
      if ($reloadedReceipt.StartedAtUtc -is [string]) {
        $reloadedReceipt.StartedAtUtc = (
          [DateTimeOffset]::Parse($reloadedReceipt.StartedAtUtc).LocalDateTime
        )
      }
      $roundTripProcessStopError = try {
        Assert-TradingLabOwnedProcessCanStop -RunToken $token -Receipt $reloadedReceipt -ObservedProcess $observedProcess | Out-Null
        $null
      } catch { $_.Exception.Message }
      $observedProcess.ProcessId = 4343
      $processDrift = try {
        Assert-TradingLabOwnedProcessCanStop -RunToken $token -Receipt $receipt -ObservedProcess $observedProcess | Out-Null
        $null
      } catch { $_.Exception.Message }
      [pscustomobject]@{
        observedServices = @($observed.Service)
        leaseProject = $reloaded.ComposeProject
        leaseIds = @($reloaded.ServiceIdentities.ContainerId)
        collision = $collision
        labelFailure = $labelFailure
        validProcessStop = $validProcessStop
        roundTripProcessStopError = $roundTripProcessStopError
        processDrift = $processDrift
      } | ConvertTo-Json -Depth 8 -Compress
    `)
    assertPowerShellSucceeded(result)
    const ownership = parseLastJson(result.stdout)
    assert.deepEqual(ownership.observedServices, [
      'validation-backend',
      'validation-postgres',
      'validation-redis',
    ])
    assert.equal(ownership.leaseProject, 'fx-trading-validation')
    assert.equal(ownership.leaseIds.length, 3)
    assert.match(ownership.collision, /lease.*collision|already exists/iu)
    assert.match(ownership.labelFailure, /label|project/iu)
    assert.equal(ownership.validProcessStop, true)
    assert.equal(ownership.roundTripProcessStopError, null)
    assert.match(ownership.processDrift, /process.*identity|pid|drift/iu)
    assert.deepEqual(
      readdirSync(fixtureRoot).sort(),
      ['fixed-stack-lease.json', 'owned-process-receipt.json'],
      'atomic lease write left a temporary file behind',
    )
  } finally {
    rmSync(fixtureRoot, { recursive: true, force: true })
  }
})

test('main data readiness accepts dual-stack exact ports and rejects missing or foreign ports', () => {
  const fixtureRoot = mkdtempSync(join(tmpdir(), 'trading-lab-main-data-ready-'))
  const dualStackPath = join(fixtureRoot, 'dual-stack.stdout.log')
  const foreignPortPath = join(fixtureRoot, 'foreign-port.stdout.log')
  const missingPortPath = join(fixtureRoot, 'missing-port.stdout.log')
  const stderrPath = join(fixtureRoot, 'docker-inspect.stderr.log')
  const inspectLine = (name, containerPort, bindings) =>
    `${name}|true|${JSON.stringify({ [`${containerPort}/tcp`]: bindings })}`
  writeFileSync(
    dualStackPath,
    [
      inspectLine('/fx-platform-postgres', '5432', [
        { HostIp: '0.0.0.0', HostPort: '5432' },
        { HostIp: '::', HostPort: '5432' },
      ]),
      inspectLine('/fx-platform-redis', '6379', [
        { HostIp: '0.0.0.0', HostPort: '6379' },
        { HostIp: '::', HostPort: '6379' },
      ]),
    ].join('\n'),
    'utf8',
  )
  writeFileSync(
    foreignPortPath,
    [
      inspectLine('/fx-platform-postgres', '5432', [
        { HostIp: '0.0.0.0', HostPort: '5432' },
        { HostIp: '::', HostPort: '15432' },
      ]),
      inspectLine('/fx-platform-redis', '6379', [
        { HostIp: '0.0.0.0', HostPort: '6379' },
        { HostIp: '::', HostPort: '6379' },
      ]),
    ].join('\n'),
    'utf8',
  )
  writeFileSync(
    missingPortPath,
    [
      `/fx-platform-postgres|true|${JSON.stringify({})}`,
      inspectLine('/fx-platform-redis', '6379', [
        { HostIp: '0.0.0.0', HostPort: '6379' },
        { HostIp: '::', HostPort: '6379' },
      ]),
    ].join('\n'),
    'utf8',
  )
  writeFileSync(stderrPath, '', 'utf8')

  try {
    const result = runPowerShell(`
      $script:TradingLabTestMainDataStdoutPath = ${psQuote(dualStackPath)}
      function Invoke-TradingLabExternalProcess {
        param(
          [string]$Executable,
          [string[]]$Arguments,
          [string]$WorkingDirectory,
          [string]$StdoutPath,
          [string]$StderrPath
        )
        [pscustomobject]@{
          ExitCode = 0
          StdoutPath = $script:TradingLabTestMainDataStdoutPath
          StderrPath = ${psQuote(stderrPath)}
        }
      }
      $dualStack = try {
        Assert-TradingLabMainDataServicesReady -WorkingDirectory ${psQuote(platformRoot)} -StdoutPath ${psQuote(join(fixtureRoot, 'ignored.stdout.log'))} -StderrPath ${psQuote(stderrPath)} | Out-Null
        'READY'
      } catch { $_.Exception.Message }
      $script:TradingLabTestMainDataStdoutPath = ${psQuote(foreignPortPath)}
      $foreignPort = try {
        Assert-TradingLabMainDataServicesReady -WorkingDirectory ${psQuote(platformRoot)} -StdoutPath ${psQuote(join(fixtureRoot, 'ignored.stdout.log'))} -StderrPath ${psQuote(stderrPath)} | Out-Null
        $null
      } catch { $_.Exception.Message }
      $script:TradingLabTestMainDataStdoutPath = ${psQuote(missingPortPath)}
      $missingPort = try {
        Assert-TradingLabMainDataServicesReady -WorkingDirectory ${psQuote(platformRoot)} -StdoutPath ${psQuote(join(fixtureRoot, 'ignored.stdout.log'))} -StderrPath ${psQuote(stderrPath)} | Out-Null
        $null
      } catch { $_.Exception.Message }
      [pscustomobject]@{
        dualStack = $dualStack
        foreignPort = $foreignPort
        missingPort = $missingPort
      } | ConvertTo-Json -Compress
    `)
    assertPowerShellSucceeded(result)
    const readiness = parseLastJson(result.stdout)

    assert.doesNotMatch(readiness.dualStack, /port binding is invalid/iu)
    assert.match(readiness.dualStack, /^(?:READY|Main data prerequisite port is not reachable: \d+)$/u)
    assert.match(readiness.foreignPort, /port binding is invalid/iu)
    assert.match(readiness.missingPort, /port binding is invalid/iu)
  } finally {
    rmSync(fixtureRoot, { recursive: true, force: true })
  }
})

test('Docker ps JSON parser accepts empty stdout but rejects malformed non-empty JSON', () => {
  const result = runPowerShell(`
    $emptyCount = @(ConvertFrom-TradingLabDockerPsJson -Json '').Count
    $whitespaceCount = @(ConvertFrom-TradingLabDockerPsJson -Json "  \`r\`n  ").Count
    $malformed = try {
      ConvertFrom-TradingLabDockerPsJson -Json '{not-json' | Out-Null
      $null
    } catch { $_.Exception.Message }
    [pscustomobject]@{
      emptyCount = $emptyCount
      whitespaceCount = $whitespaceCount
      malformed = $malformed
    } | ConvertTo-Json -Compress
  `)
  assertPowerShellSucceeded(result)
  const parsed = parseLastJson(result.stdout)

  assert.equal(parsed.emptyCount, 0)
  assert.equal(parsed.whitespaceCount, 0)
  assert.match(parsed.malformed, /Docker ps JSON is invalid/iu)
})

test('default executor source is shell-safe, evidence-recording, and ownership-gated', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const source = readFileSync(runner, 'utf8')
  for (const required of [
    'Invoke-TradingLabDefaultNormalPath',
    'Invoke-TradingLabDefaultCommand',
    'Invoke-TradingLabExternalProcess',
    'Copy-TradingLabFreshPlatformCandidate',
    'Copy-TradingLabOwnedBootJar',
    'Start-TradingLabOwnedProcess',
    'Stop-TradingLabOwnedProcesses',
    'Stop-TradingLabOwnedFixedStack',
    'Invoke-TradingLabSmokeAccountCleanup',
    'Write-TradingLabCleanupReceipt',
    'UseShellExecute = $false',
    'RedirectStandardOutput = $true',
    'RedirectStandardError = $true',
    'Assert-TradingLabPortsAreFree',
    'Assert-TradingLabFixedStackCanStop',
    'Assert-TradingLabOwnedProcessCanStop',
    'fixed-stack-startable=true',
    "'post-cleanup-ports'",
  ]) {
    assert.equal(source.includes(required), true, `missing default executor contract: ${required}`)
  }
  assert.doesNotMatch(source, /\bInvoke-Expression\b/iu)
  assert.doesNotMatch(source, /\bdown\b[^\r\n]*\s-v(?:\s|$)/iu)
  assert.doesNotMatch(source, /Remove-Item[^\r\n]*(?:\*|\?)/iu)
  assert.doesNotMatch(source, /default external executor is not connected/iu)
  assert.doesNotMatch(source, /fixed-stack-absent=true/iu)
})

test('external process stdin is BOM-free UTF-8', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const fixtureRoot = mkdtempSync(join(tmpdir(), 'trading-lab-stdin-encoding-'))
  const helperPath = join(fixtureRoot, 'read-stdin.mjs')
  const stdoutPath = join(fixtureRoot, 'stdin.stdout.log')
  const stderrPath = join(fixtureRoot, 'stdin.stderr.log')
  writeFileSync(
    helperPath,
    [
      "const chunks = []",
      "for await (const chunk of process.stdin) chunks.push(chunk)",
      "const input = Buffer.concat(chunks)",
      "process.stdout.write(JSON.stringify({",
      "  firstThreeHex: input.subarray(0, 3).toString('hex'),",
      "  utf8: input.toString('utf8'),",
      "}))",
    ].join('\n'),
    'utf8',
  )
  try {
    const result = runPowerShell(`
      $result = Invoke-TradingLabExternalProcess -Executable 'node' -Arguments @(
        ${psQuote(helperPath)}
      ) -WorkingDirectory ${psQuote(platformRoot)} -StdoutPath ${psQuote(stdoutPath)} -StderrPath ${psQuote(stderrPath)} -StdinText 'set -eu'
      [pscustomobject]@{
        exitCode = $result.ExitCode
        evidence = (
          [System.IO.File]::ReadAllText(${psQuote(stdoutPath)}) |
            ConvertFrom-Json
        )
      } | ConvertTo-Json -Depth 4 -Compress
    `)
    assertPowerShellSucceeded(result)
    const contract = parseLastJson(result.stdout)
    assert.equal(contract.exitCode, 0)
    assert.equal(contract.evidence.firstThreeHex, '736574')
    assert.equal(contract.evidence.utf8, 'set -eu')
    const source = readFileSync(runner, 'utf8')
    assert.match(
      source,
      /\[System\.Text\.UTF8Encoding\]::new\(\$false\)[\s\S]{0,500}StandardInput\.BaseStream\.Write/u,
    )
  } finally {
    rmSync(fixtureRoot, { recursive: true, force: true })
  }
})

test('default context and command specs enforce fresh temp Maven, port gates, and durable receipts', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const artifactsRoot = join(tmpdir(), 'trading-lab-default-contract-artifacts')
  const result = runPowerShell(`
    $token = 'task8-default-context-owner-token-0123456789abcdef'
    $context = New-TradingLabDefaultRunContext -ArtifactsDirectory ${psQuote(artifactsRoot)} -RunToken $token -CandidateId 'task8-default-candidate-a1'
    $phases = @(Get-TradingLabNormalPhaseSpecs -Context $context -PackageScripts @{
      'smoke:trading-lab:isolated' = 'node scripts/run-trading-lab-isolated-smoke.mjs'
    })
    $commands = @($phases | ForEach-Object { @($_.Commands) })
    [pscustomobject]@{
      context = $context
      portGate = @($commands | Where-Object Id -eq 'assert-runner-ports-free')[0]
      maven = @($commands | Where-Object {
        $_.Operation -eq 'MAVEN_TEST'
      })
      candidate = @($commands | Where-Object Operation -eq 'CREATE_ISOLATED_CANDIDATE')[0]
      jarCopy = @($commands | Where-Object Operation -eq 'COPY_OWNED_BOOT_JAR')[0]
      stackLease = @($commands | Where-Object Operation -eq 'CAPTURE_FIXED_STACK_LEASE')[0]
      stackGuard = @($commands | Where-Object Operation -eq 'ACQUIRE_FIXED_STACK_GUARD')[0]
      phase1Ids = @($commands | Where-Object PhaseNumber -eq 1 | ForEach-Object Id)
      phase4Ids = @($commands | Where-Object PhaseNumber -eq 4 | ForEach-Object Id)
      ownedProcesses = @($commands | Where-Object {
        $_.Operation -in @('START_OWNED_PROCESS', 'START_OR_VERIFY_OWNED_PROCESS')
      })
      cleanup = @($commands | Where-Object Operation -eq 'OWNED_CLEANUP')[0]
      phase5Ids = @($commands | Where-Object PhaseNumber -eq 5 | ForEach-Object Id)
      composeStart = @($commands | Where-Object Id -eq 'start-validation-compose')[0]
      supervisor = @($commands | Where-Object Id -eq 'start-supervisor-owned-process')[0]
      mainBackend = @($commands | Where-Object Id -eq 'start-or-verify-owned-main-backend')[0]
      accountProvision = @($commands | Where-Object Id -eq 'provision-owned-smoke-accounts')[0]
      browser = @($commands | Where-Object Id -eq 'isolated-browser-smoke')[0]
    } | ConvertTo-Json -Depth 14 -Compress
  `)
  assertPowerShellSucceeded(result)
  const contract = parseLastJson(result.stdout)
  const systemTemp = resolve(tmpdir())
  assert.equal(
    resolve(contract.context.CandidatePlatformRoot).startsWith(`${systemTemp}\\`),
    true,
  )
  assert.equal(
    resolve(contract.context.CandidatePlatformRoot).endsWith('\\fx-trading-platform'),
    true,
  )
  assert.equal(resolve(contract.context.ArtifactsDirectory), artifactsRoot)
  assert.equal(
    resolve(contract.context.OwnedBackendJarPath),
    join(artifactsRoot, 'runtime', 'fx-platform-backend-0.1.0.jar'),
  )
  assert.equal(contract.context.KeepValidationRunning, false)
  assert.equal(contract.portGate.Operation, 'ASSERT_PORTS_FREE')
  assert.deepEqual(contract.portGate.Arguments, ['8080', '18086', '18087', '18088', '5174'])
  assert.equal(
    contract.phase1Ids.includes('assert-main-data-services-ready'),
    true,
  )

  assert.ok(contract.maven.length >= 4)
  for (const command of contract.maven) {
    assert.equal(
      resolve(command.WorkingDirectory),
      join(resolve(contract.context.CandidatePlatformRoot), 'backend'),
    )
    assert.equal(command.Arguments[0], 'clean')
    assert.match(command.Environment.MAVEN_OPTS, /-Xmx512m/u)
    assert.equal(
      resolve(command.DestinationPath).startsWith(
        `${join(artifactsRoot, 'surefire')}\\`,
      ),
      true,
    )
  }
  assert.equal(resolve(contract.candidate.DestinationPath), resolve(contract.context.CandidatePlatformRoot))
  assert.equal(resolve(contract.jarCopy.DestinationPath), resolve(contract.context.OwnedBackendJarPath))
  assert.equal(
    resolve(contract.stackLease.DestinationPath),
    join(artifactsRoot, 'fixed-stack-lease.json'),
  )
  assert.match(
    resolve(contract.context.FixedStackGuardPath),
    /[\\\/]\.run-logs[\\\/]trading-lab[\\\/]\.fixed-stack-owner-lease\.json$/u,
  )
  assert.equal(
    resolve(contract.stackGuard.DestinationPath),
    resolve(contract.context.FixedStackGuardPath),
  )
  assert.deepEqual(contract.phase4Ids, [
    'assert-fixed-stack-baseline-current-before-up',
    'start-validation-compose',
    'capture-fixed-stack-lease',
    'reconcile-validation-postgres-credential',
  ])
  assert.equal(contract.accountProvision.Operation, 'PROVISION_SMOKE_ACCOUNTS')
  assert.equal(contract.mainBackend.Environment.DATABASE_PASSWORD, 'password')
  for (const name of [
    'JWT_SECRET',
    'CONFIG_ENCRYPTION_KEY',
    'ADMIN_BOOTSTRAP_PASSWORD',
    'SUPERVISOR_INTERNAL_TOKEN',
  ]) {
    assert.match(
      contract.mainBackend.Environment[name],
      /^__TRADING_LAB_RUN_SECRET:[A-Z_]+__$/u,
    )
  }
  assert.match(
    contract.mainBackend.Environment.ADMIN_BOOTSTRAP_EMAIL,
    /^__TRADING_LAB_RUN_ACCOUNT:[A-Z_]+__$/u,
  )
  assert.deepEqual(
    Object.keys(contract.composeStart.Environment).sort(),
    [
      'VALIDATION_CONFIG_ENCRYPTION_KEY',
      'VALIDATION_DATABASE_PASSWORD',
      'VALIDATION_INTERNAL_SECRET',
      'VALIDATION_JWT_SECRET',
      'VALIDATION_REDIS_PASSWORD',
    ],
  )
  assert.deepEqual(
    Object.keys(contract.supervisor.Environment).sort(),
    [
      'SUPERVISOR_INTERNAL_TOKEN',
      'VALIDATION_CONFIG_ENCRYPTION_KEY',
      'VALIDATION_DATABASE_PASSWORD',
      'VALIDATION_INTERNAL_SECRET',
      'VALIDATION_JWT_SECRET',
      'VALIDATION_REDIS_PASSWORD',
    ],
  )
  for (const [name, marker] of Object.entries(contract.supervisor.Environment)) {
    assert.equal(marker, `__TRADING_LAB_RUN_SECRET:${name}__`)
  }
  assert.deepEqual(
    contract.ownedProcesses.map(({ DestinationPath }) => resolve(DestinationPath)),
    [
      join(artifactsRoot, 'supervisor-process-receipt.json'),
      join(artifactsRoot, 'main-backend-process-receipt.json'),
      join(artifactsRoot, 'admin-process-receipt.json'),
    ],
  )
  assert.match(contract.ownedProcesses[1].HealthUri, /127\.0\.0\.1:18086\/actuator\/health/u)
  assert.match(contract.ownedProcesses[2].HealthUri, /127\.0\.0\.1:5174\/trading\/lab/u)
  assert.equal(
    contract.ownedProcesses[2].Environment.VITE_API_BASE_URL,
    'http://127.0.0.1:18086',
  )
  assert.equal(
    contract.ownedProcesses[1].Environment.TRADING_LAB_QUEUE_ENABLED,
    'true',
  )
  assert.equal(
    contract.ownedProcesses[1].Environment.TRADING_LAB_REPORT_CHUNK_BYTES,
    '16384',
  )
  assert.equal(
    contract.ownedProcesses[1].Environment.TRADING_LAB_REPORT_CLEANUP_ENABLED,
    'false',
  )
  for (const name of [
    'ENGAGEMENT_OUTBOX_ENABLED',
    'ENGAGEMENT_RETENTION_ENABLED',
    'ENGAGEMENT_SCHEDULER_ENABLED',
    'HOME_COUNTERS_GROWTH_ENABLED',
    'MARKET_DEMO_QUOTES_ENABLED',
    'MARKET_PROVIDER_INSTRUMENT_SYNC_ENABLED',
    'MARKET_QUOTE_BROADCAST_ENABLED',
    'MARKET_REALTIME_BACKFILL_ENABLED',
    'MARKET_REALTIME_DYNAMIC_SYMBOLS_ENABLED',
    'MARKET_REALTIME_ENABLED',
    'MARKET_TEST_CONTROL_ENABLED',
    'MARKET_TEST_DATA_ENABLED',
    'MARKET_WRITE_QUOTES_TO_DB',
    'MASSIVE_WRITE_QUOTES_TO_DB',
    'TRADING_FUNDING_ENABLED',
    'TRADING_FX_FINANCING_ENABLED',
    'TRADING_LIQUIDATION_ENABLED',
    'TRADING_PENDING_ORDER_EXECUTION_ENABLED',
    'TRADING_PROTECTIVE_ORDER_EXECUTION_ENABLED',
    'WALLET_RECONCILIATION_ENABLED',
    'WALLET_SNAPSHOT_ENABLED',
  ]) {
    assert.equal(
      contract.ownedProcesses[1].Environment[name],
      'false',
      `${name} must be frozen for the owned main backend`,
    )
  }
  assert.equal(
    contract.ownedProcesses[1].Environment.TRADING_LAB_VALIDATION_INTERNAL_TOKEN,
    '__TRADING_LAB_OWNED_VALIDATION_SECRET__',
  )
  assert.equal(
    resolve(contract.cleanup.DestinationPath),
    join(artifactsRoot, 'cleanup-receipt.json'),
  )
  assert.equal(
    contract.phase5Ids.at(-1),
    'verify-fixed-stack-lease-after-supervisor',
  )
  assert.equal(
    contract.browser.Arguments.includes(`--artifacts=${artifactsRoot}`),
    true,
  )
  assert.equal(
    contract.browser.Arguments.includes('--admin-url=http://127.0.0.1:5174'),
    true,
  )
  assert.equal(
    contract.browser.Arguments.includes('--api-url=http://127.0.0.1:18086'),
    true,
  )
  assert.equal(contract.browser.Arguments.includes('--attach-admin'), true)
  for (const name of [
    'SUPERVISOR_INTERNAL_TOKEN',
    'VALIDATION_DATABASE_PASSWORD',
    'VALIDATION_REDIS_PASSWORD',
    'VALIDATION_JWT_SECRET',
    'VALIDATION_CONFIG_ENCRYPTION_KEY',
    'VALIDATION_INTERNAL_SECRET',
    'JWT_SECRET',
    'CONFIG_ENCRYPTION_KEY',
    'ADMIN_BOOTSTRAP_PASSWORD',
    'TRADING_LAB_SMOKE_SUPER_EMAIL',
    'TRADING_LAB_SMOKE_SUPER_PASSWORD',
    'TRADING_LAB_SMOKE_VIEW_EMAIL',
    'TRADING_LAB_SMOKE_VIEW_PASSWORD',
    'TRADING_LAB_SMOKE_EXECUTE_EMAIL',
    'TRADING_LAB_SMOKE_EXECUTE_PASSWORD',
    'TRADING_LAB_SMOKE_ORDINARY_EMAIL',
    'TRADING_LAB_SMOKE_ORDINARY_PASSWORD',
    'TRADING_LAB_SMOKE_OWNED_AUTH_USER_IDS',
  ]) {
    assert.equal(typeof contract.browser.Environment[name], 'string')
    assert.match(
      contract.browser.Environment[name],
      /^__TRADING_LAB_RUN_(?:SECRET|ACCOUNT):[A-Z_]+__$/u,
    )
  }
})

test('owned main backend freezes all 18 scheduled writers behind property guards', () => {
  const guardedSchedulers = [
    {
      file: 'admin/service/ProviderInstrumentSyncScheduler.java',
      prefix: 'market.provider-instrument-sync',
      name: 'enabled',
      flag: 'MARKET_PROVIDER_INSTRUMENT_SYNC_ENABLED',
      value: 'false',
    },
    {
      file: 'engagement/application/outbox/EngagementOutboxDispatcher.java',
      prefix: 'app.engagement.outbox',
      name: 'enabled',
      flag: 'ENGAGEMENT_OUTBOX_ENABLED',
      value: 'false',
    },
    {
      file: 'engagement/scheduling/EngagementScheduledDispatcher.java',
      prefix: 'app.engagement.scheduler',
      name: 'enabled',
      flag: 'ENGAGEMENT_SCHEDULER_ENABLED',
      value: 'false',
    },
    {
      file: 'engagement/scheduling/PopupDeliveryRetentionScheduler.java',
      prefix: 'app.engagement.retention',
      name: 'enabled',
      flag: 'ENGAGEMENT_RETENTION_ENABLED',
      value: 'false',
    },
    {
      file: 'home/service/HomeCountersGrowthScheduler.java',
      prefix: 'home.counters',
      name: 'growth-enabled',
      flag: 'HOME_COUNTERS_GROWTH_ENABLED',
      value: 'false',
    },
    {
      file: 'market/realtime/BinanceRealtimeRotationScheduler.java',
      prefix: 'market.realtime',
      name: 'enabled',
      flag: 'MARKET_REALTIME_ENABLED',
      value: 'false',
    },
    {
      file: 'market/realtime/BinanceSubscriptionMaintenanceScheduler.java',
      prefix: 'market.realtime',
      name: 'enabled',
      flag: 'MARKET_REALTIME_ENABLED',
      value: 'false',
    },
    {
      file: 'market/service/MarketTestDataScheduler.java',
      prefix: 'market.test-data',
      name: 'enabled',
      flag: 'MARKET_TEST_DATA_ENABLED',
      value: 'false',
    },
    {
      file: 'market/service/QuoteBroadcastScheduler.java',
      prefix: 'market',
      name: 'quote-broadcast-enabled',
      flag: 'MARKET_QUOTE_BROADCAST_ENABLED',
      value: 'false',
    },
    {
      file: 'trading/service/ForexFinancingScheduler.java',
      prefix: 'trading.fx-financing',
      name: 'enabled',
      flag: 'TRADING_FX_FINANCING_ENABLED',
      value: 'false',
    },
    {
      file: 'trading/service/FundingSettlementScheduler.java',
      prefix: 'trading.funding',
      name: 'enabled',
      flag: 'TRADING_FUNDING_ENABLED',
      value: 'false',
    },
    {
      file: 'trading/service/LiquidationScanScheduler.java',
      prefix: 'trading.liquidation',
      name: 'enabled',
      flag: 'TRADING_LIQUIDATION_ENABLED',
      value: 'false',
    },
    {
      file: 'trading/service/PendingOrderExecutionScheduler.java',
      prefix: 'trading',
      name: 'pending-order-execution-enabled',
      flag: 'TRADING_PENDING_ORDER_EXECUTION_ENABLED',
      value: 'false',
    },
    {
      file: 'trading/service/ProtectiveOrderExecutionScheduler.java',
      prefix: 'trading',
      name: 'protective-order-execution-enabled',
      flag: 'TRADING_PROTECTIVE_ORDER_EXECUTION_ENABLED',
      value: 'false',
    },
    {
      file: 'tradinglab/queue/TradingLabQueueWorker.java',
      prefix: 'trading-lab.queue',
      name: 'enabled',
      flag: 'TRADING_LAB_QUEUE_ENABLED',
      value: 'true',
    },
    {
      file: 'tradinglab/report/TradingLabReportCleanupScheduler.java',
      prefix: 'trading-lab.report.cleanup',
      name: 'enabled',
      flag: 'TRADING_LAB_REPORT_CLEANUP_ENABLED',
      value: 'false',
    },
    {
      file: 'wallet/service/WalletDailySnapshotJob.java',
      prefix: 'wallet.snapshot',
      name: 'enabled',
      flag: 'WALLET_SNAPSHOT_ENABLED',
      value: 'false',
    },
    {
      file: 'wallet/service/WalletReconciliationJob.java',
      prefix: 'wallet.reconciliation',
      name: 'enabled',
      flag: 'WALLET_RECONCILIATION_ENABLED',
      value: 'false',
    },
  ]
  const javaRoot = join(
    platformRoot,
    'backend',
    'src',
    'main',
    'java',
    'com',
    'fxplatform',
  )
  const scheduledFiles = []
  const visit = (directory) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = join(directory, entry.name)
      if (entry.isDirectory()) {
        visit(path)
      } else if (
        entry.name.endsWith('.java') &&
        readFileSync(path, 'utf8').includes('@Scheduled')
      ) {
        scheduledFiles.push(relative(javaRoot, path).replaceAll('\\', '/'))
      }
    }
  }
  visit(javaRoot)
  assert.equal(guardedSchedulers.length, 18)
  assert.deepEqual(
    scheduledFiles.sort(),
    guardedSchedulers.map(({ file }) => file).sort(),
    'the frozen inventory must match every @Scheduled main source',
  )
  const runnerSource = readFileSync(runner, 'utf8')
  for (const scheduler of guardedSchedulers) {
    const source = readFileSync(join(javaRoot, scheduler.file), 'utf8')
    assert.match(source, /@ConditionalOnProperty\s*\(/u, scheduler.file)
    assert.match(
      source,
      new RegExp(`prefix\\s*=\\s*"${scheduler.prefix.replaceAll('.', '\\.')}"`, 'u'),
      scheduler.file,
    )
    assert.match(
      source,
      new RegExp(`name\\s*=\\s*"${scheduler.name}"`, 'u'),
      scheduler.file,
    )
    assert.match(source, /havingValue\s*=\s*"true"/u, scheduler.file)
    assert.match(
      runnerSource,
      new RegExp(`${scheduler.flag}\\s*=\\s*'${scheduler.value}'`, 'u'),
      `${scheduler.flag} must override any inherited parent value`,
    )
  }
  for (const flag of [
    'MARKET_DEMO_QUOTES_ENABLED',
    'MARKET_REALTIME_BACKFILL_ENABLED',
    'MARKET_REALTIME_DYNAMIC_SYMBOLS_ENABLED',
    'MARKET_TEST_CONTROL_ENABLED',
    'MARKET_WRITE_QUOTES_TO_DB',
    'MASSIVE_WRITE_QUOTES_TO_DB',
  ]) {
    assert.match(
      runnerSource,
      new RegExp(`${flag}\\s*=\\s*'false'`, 'u'),
      `${flag} must freeze non-scheduled startup/background writers`,
    )
  }
})

test('default contexts generate isolated run-owned secrets without exposing values', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const result = runPowerShell(`
    $firstToken = 'task8-secret-registry-first-0123456789abcdef'
    $secondToken = 'task8-secret-registry-second-0123456789abcdef'
    New-TradingLabDefaultRunContext -ArtifactsDirectory 'C:\\first-artifacts' -RunToken $firstToken -CandidateId 'task8-secret-first-a1' | Out-Null
    New-TradingLabDefaultRunContext -ArtifactsDirectory 'C:\\second-artifacts' -RunToken $secondToken -CandidateId 'task8-secret-second-a1' | Out-Null
    $first = Get-TradingLabOwnedRunSecrets -RunToken $firstToken
    $second = Get-TradingLabOwnedRunSecrets -RunToken $secondToken
    $names = @($first.Secrets.Keys | Sort-Object)
    $firstValues = @($names | ForEach-Object { [string]$first.Secrets[$_] })
    $secondValues = @($names | ForEach-Object { [string]$second.Secrets[$_] })
    $accountPasswords = @(
      [string]$first.Secrets['ADMIN_BOOTSTRAP_PASSWORD'],
      [string]$first.Secrets['VIEW_PASSWORD'],
      [string]$first.Secrets['EXECUTE_PASSWORD'],
      [string]$first.Secrets['ORDINARY_PASSWORD']
    )
    $environmentPasswords = @(
      [string]$first.Accounts.Environment.SUPER_PASSWORD,
      [string]$first.Accounts.Environment.VIEW_PASSWORD,
      [string]$first.Accounts.Environment.EXECUTE_PASSWORD,
      [string]$first.Accounts.Environment.ORDINARY_PASSWORD
    )
    $identityTokens = @(
      $first.Accounts.SuperEmail,
      $first.Accounts.ViewEmail,
      $first.Accounts.ExecuteEmail,
      $first.Accounts.OrdinaryEmail
    ) | ForEach-Object {
      if ([string]$_ -match '^trading-lab-([0-9a-f]{32})-[a-z]+@local\\.invalid$') {
        $Matches[1]
      }
    }
    [pscustomobject]@{
      names = $names
      minimumLength = @($firstValues | Where-Object {
        [System.Text.Encoding]::UTF8.GetByteCount($_) -lt 32
      }).Count -eq 0
      uniqueWithinRun = @($firstValues | Sort-Object -Unique).Count -eq $firstValues.Count
      uniqueAcrossRuns = @(
        Compare-Object $firstValues $secondValues -IncludeEqual |
          Where-Object SideIndicator -eq '=='
      ).Count -eq 0
      accountPasswordsUnique = (
        @($accountPasswords | Sort-Object -Unique).Count -eq 4
      )
      environmentPasswordsUnique = (
        @($environmentPasswords | Sort-Object -Unique).Count -eq 4
      )
      identityTokensUnique = (
        @($identityTokens | Sort-Object -Unique).Count -eq 4
      )
    } | ConvertTo-Json -Compress
  `)
  assertPowerShellSucceeded(result)
  const contract = parseLastJson(result.stdout)
  assert.deepEqual(contract.names, [
    'ADMIN_BOOTSTRAP_PASSWORD',
    'CONFIG_ENCRYPTION_KEY',
    'EXECUTE_PASSWORD',
    'JWT_SECRET',
    'ORDINARY_PASSWORD',
    'SUPERVISOR_INTERNAL_TOKEN',
    'VALIDATION_CONFIG_ENCRYPTION_KEY',
    'VALIDATION_DATABASE_PASSWORD',
    'VALIDATION_INTERNAL_SECRET',
    'VALIDATION_JWT_SECRET',
    'VALIDATION_REDIS_PASSWORD',
    'VIEW_PASSWORD',
  ])
  assert.equal(contract.minimumLength, true)
  assert.equal(contract.uniqueWithinRun, true)
  assert.equal(contract.uniqueAcrossRuns, true)
  assert.equal(contract.accountPasswordsUnique, true)
  assert.equal(contract.environmentPasswordsUnique, true)
  assert.equal(contract.identityTokensUnique, true)
})

test('owned smoke-account cleanup is exact, transactional, and tombstones only SUPER', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const fixtureRoot = mkdtempSync(join(tmpdir(), 'trading-lab-account-cleanup-'))
  try {
    const result = runPowerShell(`
      $token = 'task8-account-cleanup-owner-0123456789abcdef'
      Register-TradingLabOwnedRunSecrets -RunToken $token | Out-Null
      $bundle = Get-TradingLabOwnedRunSecrets -RunToken $token
      $bundle.Accounts.SuperUserId = [guid]::NewGuid().ToString().ToLowerInvariant()
      $bundle.Accounts.Provisioned = $true
      $script:capturedCleanupSql = $null
      function Invoke-TradingLabExternalProcess {
        param(
          $Executable,
          $Arguments,
          $WorkingDirectory,
          $StdoutPath,
          $StderrPath,
          $StdinText
        )
        $script:capturedCleanupSql = [string]$StdinText
        [System.IO.File]::WriteAllText(
          $StdoutPath,
          '{"status":"PASS","deletedUsers":3,"tombstonedSuper":1,"deletedSessions":4,"deletedDevices":0,"deletedUserRoles":3,"deletedPermissions":2,"deletedRoles":2,"remainingOwnedAccess":0,"superDisposition":"TOMBSTONED_FOR_RETAINED_TRADING_LAB_FK"}'
        )
        [System.IO.File]::WriteAllText($StderrPath, '')
        [pscustomobject]@{
          ExitCode = 0
          StdoutPath = $StdoutPath
          StderrPath = $StderrPath
        }
      }
      $cleanup = Invoke-TradingLabSmokeAccountCleanup -Context ([pscustomobject]@{
        RunToken = $token
      }) -StdoutPath ${psQuote(join(fixtureRoot, 'cleanup.stdout.log'))} -StderrPath ${psQuote(join(fixtureRoot, 'cleanup.stderr.log'))}
      $sql = [string]$script:capturedCleanupSql
      $accounts = $bundle.Accounts
      [pscustomobject]@{
        status = $cleanup.Status
        detail = $cleanup.Detail
        cleaned = $accounts.Cleaned
        transaction = (
          $sql.Contains('BEGIN;') -and $sql.Contains('COMMIT;')
        )
        exactUsers = @(
          $accounts.SuperUserId,
          $accounts.ViewUserId,
          $accounts.ExecuteUserId,
          $accounts.OrdinaryUserId
        ) | ForEach-Object { $sql.Contains([string]$_) }
        exactEmails = @(
          $accounts.SuperEmail,
          $accounts.ViewEmail,
          $accounts.ExecuteEmail,
          $accounts.OrdinaryEmail
        ) | ForEach-Object { $sql.Contains([string]$_) }
        exactGraph = (
          $sql.Contains('DELETE FROM auth.user_sessions') -and
          $sql.Contains('DELETE FROM admin.user_roles') -and
          $sql.Contains('DELETE FROM admin.role_menu_permissions') -and
          $sql.Contains('DELETE FROM admin.roles')
        )
        physicalUsers = $sql -match 'DELETE FROM auth\\.users'
        superTombstone = (
          $sql -match "status\\s*=\\s*'DISABLED'" -and
          $sql -match "role\\s*=\\s*'USER'" -and
          $sql -match 'email\\s*=\\s*NULL' -and
          $sql -match "crypt\\("
        )
        residualGate = $sql -match 'remainingOwnedAccess'
      } | ConvertTo-Json -Depth 8 -Compress
    `)
    assertPowerShellSucceeded(result)
    const cleanup = parseLastJson(result.stdout)
    assert.equal(cleanup.status, 'PASS')
    assert.equal(cleanup.cleaned, true)
    assert.equal(cleanup.transaction, true)
    assert.deepEqual(cleanup.exactUsers, Array(4).fill(true))
    assert.deepEqual(cleanup.exactEmails, Array(4).fill(true))
    assert.equal(cleanup.exactGraph, true)
    assert.equal(cleanup.physicalUsers, true)
    assert.equal(cleanup.superTombstone, true)
    assert.equal(cleanup.residualGate, true)
    assert.equal(cleanup.detail.deletedUsers, 3)
    assert.equal(cleanup.detail.tombstonedSuper, 1)
    assert.equal(cleanup.detail.remainingOwnedAccess, 0)
  } finally {
    rmSync(fixtureRoot, { recursive: true, force: true })
  }
})

test('owned cleanup attempts account revocation after process-stop failure and preserves both failures', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const fixtureRoot = mkdtempSync(join(tmpdir(), 'trading-lab-cleanup-independent-'))
  try {
    const result = runPowerShell(`
      function Invoke-CleanupCase {
        param([bool]$FailAccountCleanup)
        $script:accountCleanupCalls = 0
        $script:capturedCleanupDetails = @()
        function Stop-TradingLabOwnedProcesses {
          throw [System.InvalidOperationException]::new('fixture process-stop failure')
        }
        function Invoke-TradingLabSmokeAccountCleanup {
          param($Context, $StdoutPath, $StderrPath)
          $script:accountCleanupCalls++
          if ($FailAccountCleanup) {
            throw [System.InvalidOperationException]::new(
              'fixture account-cleanup failure'
            )
          }
          [pscustomobject]@{
            Status = 'PASS'
            Detail = [pscustomobject]@{ remainingOwnedAccess = 0 }
            StdoutPath = $StdoutPath
            StderrPath = $StderrPath
          }
        }
        function Stop-TradingLabOwnedFixedStack {
          [pscustomobject]@{ Status = 'PASS'; Detail = 'fixture' }
        }
        function Get-TradingLabOwnedCandidateRetentionEvidence {
          [pscustomobject]@{
            state = 'NOT_CREATED'
            candidatePath = 'C:\\fixture-candidate'
          }
        }
        function Write-TradingLabCleanupReceipt {
          param($Context, $Status, $Details)
          $script:capturedCleanupDetails = @($Details)
          [pscustomobject]@{
            schemaVersion = 1
            runToken = [string]$Context.RunToken
            status = $Status
            details = @($Details)
          }
        }
        function Complete-TradingLabVerificationReport {}
        $caught = $null
        try {
          Invoke-TradingLabOwnedCleanup -Context ([pscustomobject]@{
            RunToken = 'task8-independent-cleanup-0123456789abcdef'
            ArtifactsDirectory = ${psQuote(fixtureRoot)}
            KeepValidationRunning = $true
            CleanupReceiptPath = ${psQuote(join(fixtureRoot, 'cleanup.json'))}
            FinalReportPath = ${psQuote(join(fixtureRoot, 'final.md'))}
          }) -ProvisionalTemplate 'fixture' | Out-Null
        } catch {
          $caught = $_.Exception
        }
        [pscustomobject]@{
          failAccountCleanup = $FailAccountCleanup
          accountCleanupCalls = $script:accountCleanupCalls
          aggregate = $caught -is [System.AggregateException]
          messages = @(
            $caught.InnerExceptions | ForEach-Object { $_.Message }
          )
          details = @($script:capturedCleanupDetails)
        }
      }
      @(
        Invoke-CleanupCase -FailAccountCleanup $false
        Invoke-CleanupCase -FailAccountCleanup $true
      ) | ConvertTo-Json -Depth 12 -Compress
    `)
    assertPowerShellSucceeded(result)
    const cases = parseLastJson(result.stdout)
    assert.equal(cases.length, 2)
    for (const evidence of cases) {
      assert.equal(evidence.accountCleanupCalls, 1)
      assert.equal(evidence.aggregate, true)
      assert.equal(
        evidence.messages.includes('fixture process-stop failure'),
        true,
      )
      assert.equal(
        evidence.details.find(({ resource }) => resource === 'owned-processes')?.status,
        'FAIL',
      )
    }
    assert.equal(
      cases[0].details.find(({ resource }) => resource === 'owned-smoke-accounts')?.status,
      'PASS',
    )
    assert.equal(
      cases[1].messages.includes('fixture account-cleanup failure'),
      true,
    )
    assert.equal(
      cases[1].details.find(({ resource }) => resource === 'owned-smoke-accounts')?.status,
      'FAIL',
    )
  } finally {
    rmSync(fixtureRoot, { recursive: true, force: true })
  }
})

test('owned cleanup retains the fresh Maven candidate with durable ownership and source digests', () => {
  const source = readFileSync(runner, 'utf8')
  assert.match(source, /Get-TradingLabOwnedCandidateRetentionEvidence/u)
  assert.doesNotMatch(source, /function\s+Remove-TradingLabOwnedCandidate/u)
  assert.doesNotMatch(
    source,
    /Remove-Item\s+-LiteralPath\s+\$candidateParent\s+-Recurse/u,
  )
  for (const required of [
    'candidatePath',
    'candidateId',
    'ownerMarkerPath',
    'ownerMarkerSha256',
    'sourceTreeSha256',
    'sourceFileCount',
    'sourceDirectoryCount',
    'sourceManifestSha256',
    'candidateManifestSha256',
    "'RETAINED'",
  ]) {
    assert.equal(
      source.includes(required),
      true,
      `missing durable candidate receipt field: ${required}`,
    )
  }
})

test('fresh candidate path budget copies and hashes retained test-results before Maven', () => {
  const sourceRoot = mkdtempSync(join(tmpdir(), 'tls-'))
  const overBudgetSourceRoot = mkdtempSync(join(tmpdir(), 'tlf-'))
  const candidateTempParent = mkdtempSync('C:\\tlc-')
  const candidateTempRoot = join(candidateTempParent, 'candidate-temp-root')
  const candidateId = `pb-${basename(sourceRoot).slice(-6)}`
  const overBudgetCandidateId = `pf-${basename(overBudgetSourceRoot).slice(-6)}`
  const retainedDirectory = join('backend', 'test-results')
  const relativePathWithLength = (length, fill) => {
    const fileNameLength = length - retainedDirectory.length - 1
    const extension = '.json'
    return join(
      retainedDirectory,
      `${fill.repeat(fileNameLength - extension.length)}${extension}`,
    )
  }
  const retainedRelativePath = relativePathWithLength(178, 'r')
  const overBudgetRelativePath = relativePathWithLength(205, 'f')
  const retainedSourcePath = join(sourceRoot, retainedRelativePath)
  const overBudgetSourcePath = join(overBudgetSourceRoot, overBudgetRelativePath)
  const token = 'path-budget-copy-owner-token-0123456789abcdef'
  const oldCandidateRoot = join(
    candidateTempRoot,
    'trading-lab-validation',
    candidateId,
    'fx-trading-platform',
  )
  mkdirSync(dirname(retainedSourcePath), { recursive: true })
  mkdirSync(dirname(overBudgetSourcePath), { recursive: true })
  mkdirSync(candidateTempRoot, { recursive: true })
  writeFileSync(retainedSourcePath, '{"retained":true}\n', 'utf8')
  writeFileSync(overBudgetSourcePath, '{"mustNotCopy":true}\n', 'utf8')

  try {
    assert.equal(retainedRelativePath.length, 178)
    assert.equal(overBudgetRelativePath.length, 205)
    assert.ok(
      join(oldCandidateRoot, retainedRelativePath).length >= 260,
      'fixture must reproduce the old MAX_PATH destination',
    )
    assert.ok(
      retainedSourcePath.length < 260 && overBudgetSourcePath.length < 260,
      'source fixtures themselves must remain below MAX_PATH',
    )

    const copied = runPowerShell(`
      $originalPlatformRoot = $script:TradingLabPlatformRoot
      $originalTemp = $env:TEMP
      $originalTmp = $env:TMP
      try {
        $env:TEMP = ${psQuote(candidateTempRoot)}
        $env:TMP = ${psQuote(candidateTempRoot)}
        $script:TradingLabPlatformRoot = [System.IO.Path]::GetFullPath(${psQuote(sourceRoot)})
        $context = New-TradingLabDefaultRunContext -ArtifactsDirectory ${psQuote(join(sourceRoot, 'artifacts'))} -RunToken ${psQuote(token)} -CandidateId ${psQuote(candidateId)}
        $copied = Copy-TradingLabFreshPlatformCandidate -SourceRoot ${psQuote(sourceRoot)} -DestinationRoot $context.CandidatePlatformRoot -RunToken ${psQuote(token)}
        $generatedOne = New-TradingLabCandidateId
        $generatedTwo = New-TradingLabCandidateId
        $destinationFile = Join-Path $copied ${psQuote(retainedRelativePath)}
        $markerPath = Join-Path $copied '.trading-lab-candidate-owner.json'
        $marker = [System.IO.File]::ReadAllText($markerPath) | ConvertFrom-Json
        $retention = Get-TradingLabOwnedCandidateRetentionEvidence -Context $context
        [pscustomobject]@{
          candidatePath = $copied
          candidateIsAbsolute = [System.IO.Path]::IsPathRooted($copied)
          destinationLength = $destinationFile.Length
          destinationExists = Test-Path -LiteralPath $destinationFile -PathType Leaf
          sourceHash = Get-TradingLabFileSha256 -Path ${psQuote(retainedSourcePath)}
          destinationHash = Get-TradingLabFileSha256 -Path $destinationFile
          markerRunToken = $marker.runToken
          markerSourceRoot = $marker.sourceRoot
          markerSourceManifestSha256 = $marker.sourceManifestSha256
          markerCandidateManifestSha256 = $marker.candidateManifestSha256
          markerSourceFileCount = $marker.sourceFileCount
          markerCandidateFileCount = $marker.candidateFileCount
          markerSourceDirectoryCount = $marker.sourceDirectoryCount
          markerCandidateDirectoryCount = $marker.candidateDirectoryCount
          generatedOne = $generatedOne
          generatedTwo = $generatedTwo
          retention = $retention
        } | ConvertTo-Json -Depth 8 -Compress
      } finally {
        $script:TradingLabPlatformRoot = $originalPlatformRoot
        $env:TEMP = $originalTemp
        $env:TMP = $originalTmp
      }
    `)
    assertPowerShellSucceeded(copied)
    const evidence = parseLastJson(copied.stdout)

    assert.equal(resolve(evidence.candidatePath), evidence.candidatePath)
    assert.equal(evidence.candidateIsAbsolute, true)
    assert.ok(evidence.destinationLength < 255)
    assert.equal(evidence.destinationExists, true)
    assert.equal(evidence.destinationHash, evidence.sourceHash)
    assert.equal(evidence.markerRunToken, token)
    assert.equal(resolve(evidence.markerSourceRoot), resolve(sourceRoot))
    assert.match(evidence.markerSourceManifestSha256, /^[a-f0-9]{64}$/u)
    assert.equal(
      evidence.markerCandidateManifestSha256,
      evidence.markerSourceManifestSha256,
    )
    assert.equal(evidence.markerSourceFileCount, 1)
    assert.equal(evidence.markerCandidateFileCount, 1)
    assert.equal(evidence.markerSourceDirectoryCount, 2)
    assert.equal(evidence.markerCandidateDirectoryCount, 2)
    assert.match(evidence.generatedOne, /^tl-[a-f0-9]{12}$/u)
    assert.match(evidence.generatedTwo, /^tl-[a-f0-9]{12}$/u)
    assert.notEqual(evidence.generatedOne, evidence.generatedTwo)
    assert.equal(evidence.retention.state, 'RETAINED')
    assert.equal(resolve(evidence.retention.candidatePath), resolve(evidence.candidatePath))
    assert.equal(evidence.retention.candidateId, candidateId)
    assert.match(evidence.retention.ownerMarkerSha256, /^[a-f0-9]{64}$/u)
    assert.match(evidence.retention.sourceTreeSha256, /^[a-f0-9]{64}$/u)
    assert.equal(evidence.retention.sourceFileCount, 1)
    assert.equal(evidence.retention.sourceDirectoryCount, 2)
    assert.equal(
      evidence.retention.candidateManifestSha256,
      evidence.markerCandidateManifestSha256,
    )

    const rejected = runPowerShell(`
      $originalTemp = $env:TEMP
      $originalTmp = $env:TMP
      try {
        $env:TEMP = ${psQuote(candidateTempRoot)}
        $env:TMP = ${psQuote(candidateTempRoot)}
        $context = New-TradingLabDefaultRunContext -ArtifactsDirectory ${psQuote(join(overBudgetSourceRoot, 'artifacts'))} -RunToken 'path-budget-reject-owner-token-0123456789abcdef' -CandidateId ${psQuote(overBudgetCandidateId)}
        $failure = try {
          Copy-TradingLabFreshPlatformCandidate -SourceRoot ${psQuote(overBudgetSourceRoot)} -DestinationRoot $context.CandidatePlatformRoot -RunToken 'path-budget-reject-owner-token-0123456789abcdef' | Out-Null
          $null
        } catch { $_.Exception.Message }
        [pscustomobject]@{
          failure = $failure
          destinationExists = Test-Path -LiteralPath $context.CandidatePlatformRoot
        } | ConvertTo-Json -Compress
      } finally {
        $env:TEMP = $originalTemp
        $env:TMP = $originalTmp
      }
    `)
    assertPowerShellSucceeded(rejected)
    const rejectedEvidence = parseLastJson(rejected.stdout)
    assert.match(rejectedEvidence.failure, /path budget|MAX_PATH|260/iu)
    assert.equal(rejectedEvidence.destinationExists, false)
  } finally {
    for (const path of [
      sourceRoot,
      overBudgetSourceRoot,
      candidateTempParent,
    ]) {
      rmSync(path, { recursive: true, force: true })
    }
  }
})

test('candidate manifests reject source drift and every candidate mismatch class', () => {
  const fixtureRoot = mkdtempSync(join(tmpdir(), 'tlm-'))
  const roots = Object.fromEntries(
    [
      'sourceBefore',
      'sourceModified',
      'sourceAdded',
      'sourceDeleted',
      'candidateExact',
      'candidateMissing',
      'candidateSha',
      'candidateExtra',
    ].map((name) => [name, join(fixtureRoot, name)]),
  )
  const writeTree = (root, {
    payload = 'stable\n',
    includeFile = true,
    added = false,
    marker = false,
  } = {}) => {
    mkdirSync(join(root, 'empty-directory'), { recursive: true })
    if (includeFile) {
      writeFileSync(join(root, 'evidence.txt'), payload, 'utf8')
    }
    if (added) {
      writeFileSync(join(root, 'added.txt'), 'added\n', 'utf8')
    }
    if (marker) {
      writeFileSync(
        join(root, '.trading-lab-candidate-owner.json'),
        '{"marker":"excluded"}\n',
        'utf8',
      )
    }
  }
  writeTree(roots.sourceBefore)
  writeTree(roots.sourceModified, { payload: 'modified\n' })
  writeTree(roots.sourceAdded, { added: true })
  writeTree(roots.sourceDeleted, { includeFile: false })
  writeTree(roots.candidateExact, { marker: true })
  writeTree(roots.candidateMissing, { includeFile: false })
  writeTree(roots.candidateSha, { payload: 'candidate-mismatch\n' })
  writeTree(roots.candidateExtra, { added: true })

  try {
    const result = runPowerShell(`
      $before = Get-TradingLabCandidateTreeManifest -Root ${psQuote(roots.sourceBefore)} -ExcludeBuildDirectories
      $exact = Get-TradingLabCandidateTreeManifest -Root ${psQuote(roots.candidateExact)}
      $valid = Assert-TradingLabCandidateCopyManifests -SourceBefore $before -SourceAfter (Get-TradingLabCandidateTreeManifest -Root ${psQuote(roots.sourceBefore)} -ExcludeBuildDirectories) -Candidate $exact
      $sourceModified = try {
        Assert-TradingLabCandidateCopyManifests -SourceBefore $before -SourceAfter (Get-TradingLabCandidateTreeManifest -Root ${psQuote(roots.sourceModified)} -ExcludeBuildDirectories) -Candidate $exact | Out-Null
        $null
      } catch { $_.Exception.Message }
      $sourceAdded = try {
        Assert-TradingLabCandidateCopyManifests -SourceBefore $before -SourceAfter (Get-TradingLabCandidateTreeManifest -Root ${psQuote(roots.sourceAdded)} -ExcludeBuildDirectories) -Candidate $exact | Out-Null
        $null
      } catch { $_.Exception.Message }
      $sourceDeleted = try {
        Assert-TradingLabCandidateCopyManifests -SourceBefore $before -SourceAfter (Get-TradingLabCandidateTreeManifest -Root ${psQuote(roots.sourceDeleted)} -ExcludeBuildDirectories) -Candidate $exact | Out-Null
        $null
      } catch { $_.Exception.Message }
      $copyMissing = try {
        Assert-TradingLabCandidateCopyManifests -SourceBefore $before -SourceAfter $before -Candidate (Get-TradingLabCandidateTreeManifest -Root ${psQuote(roots.candidateMissing)}) | Out-Null
        $null
      } catch { $_.Exception.Message }
      $copySha = try {
        Assert-TradingLabCandidateCopyManifests -SourceBefore $before -SourceAfter $before -Candidate (Get-TradingLabCandidateTreeManifest -Root ${psQuote(roots.candidateSha)}) | Out-Null
        $null
      } catch { $_.Exception.Message }
      $copyExtra = try {
        Assert-TradingLabCandidateCopyManifests -SourceBefore $before -SourceAfter $before -Candidate (Get-TradingLabCandidateTreeManifest -Root ${psQuote(roots.candidateExtra)}) | Out-Null
        $null
      } catch { $_.Exception.Message }
      [pscustomobject]@{
        valid = $valid
        markerExcluded = $exact.FileCount -eq 1
        sourceModified = $sourceModified
        sourceAdded = $sourceAdded
        sourceDeleted = $sourceDeleted
        copyMissing = $copyMissing
        copySha = $copySha
        copyExtra = $copyExtra
      } | ConvertTo-Json -Compress
    `)
    assertPowerShellSucceeded(result)
    const evidence = parseLastJson(result.stdout)

    assert.equal(evidence.valid, true)
    assert.equal(evidence.markerExcluded, true)
    assert.match(evidence.sourceModified, /SOURCE_CHANGED/iu)
    assert.match(evidence.sourceAdded, /SOURCE_ADDED/iu)
    assert.match(evidence.sourceDeleted, /SOURCE_CHANGED/iu)
    assert.match(evidence.copyMissing, /COPY_MISSING/iu)
    assert.match(evidence.copySha, /COPY_SHA/iu)
    assert.match(evidence.copyExtra, /COPY_EXTRA/iu)
  } finally {
    rmSync(fixtureRoot, { recursive: true, force: true })
  }
})

test('candidate copy rejects reparse roots, pre-created parent junctions, and scan replacement', () => {
  const fixtureRoot = mkdtempSync(join(tmpdir(), 'tlr-'))
  const candidateTempRoot = join(fixtureRoot, 'candidate-temp')
  const sourceRoot = join(fixtureRoot, 'source')
  const sourceScanRoot = join(fixtureRoot, 'source-scan')
  const candidateId = 'reparse-parent-a1'
  const candidateParent = join(candidateTempRoot, 'tlv', candidateId)
  const destination = join(candidateParent, 'fx-trading-platform')
  const sourceJunction = join(fixtureRoot, 'source-junction')
  const scanSwap = join(sourceScanRoot, 'swap')
  const candidateEscapeRoot = mkdtempSync(join(tmpdir(), 'tle-candidate-'))
  const sourceEscapeRoot = mkdtempSync(join(tmpdir(), 'tle-source-'))
  const scanEscapeRoot = mkdtempSync(join(tmpdir(), 'tle-scan-'))
  mkdirSync(sourceRoot, { recursive: true })
  mkdirSync(scanSwap, { recursive: true })
  mkdirSync(dirname(candidateParent), { recursive: true })
  writeFileSync(join(sourceRoot, 'source.txt'), 'source\n', 'utf8')
  writeFileSync(join(sourceEscapeRoot, 'escape-source.txt'), 'escape source\n', 'utf8')
  writeFileSync(join(scanSwap, 'before.txt'), 'before\n', 'utf8')
  writeFileSync(join(candidateEscapeRoot, 'sentinel.txt'), 'candidate sentinel\n', 'utf8')
  writeFileSync(join(scanEscapeRoot, 'sentinel.txt'), 'scan sentinel\n', 'utf8')
  symlinkSync(candidateEscapeRoot, candidateParent, 'junction')
  symlinkSync(sourceEscapeRoot, sourceJunction, 'junction')

  let scanSwapIsJunction = false
  try {
    const parentResult = runPowerShell(`
      $originalTemp = $env:TEMP
      $originalTmp = $env:TMP
      try {
        $env:TEMP = ${psQuote(candidateTempRoot)}
        $env:TMP = ${psQuote(candidateTempRoot)}
        $parentFailure = try {
          Copy-TradingLabFreshPlatformCandidate -SourceRoot ${psQuote(sourceRoot)} -DestinationRoot ${psQuote(destination)} -RunToken 'candidate-parent-reparse-token-0123456789abcdef' | Out-Null
          $null
        } catch { $_.Exception.Message }
        $sourceFailure = try {
          Copy-TradingLabFreshPlatformCandidate -SourceRoot ${psQuote(sourceJunction)} -DestinationRoot (Join-Path (Get-TradingLabCandidateBasePath) 'source-root-a1\\fx-trading-platform') -RunToken 'source-root-reparse-token-0123456789abcdef' | Out-Null
          $null
        } catch { $_.Exception.Message }
        [pscustomobject]@{
          parentFailure = $parentFailure
          sourceFailure = $sourceFailure
          destinationExists = Test-Path -LiteralPath ${psQuote(destination)}
        } | ConvertTo-Json -Compress
      } finally {
        $env:TEMP = $originalTemp
        $env:TMP = $originalTmp
      }
    `)
    assertPowerShellSucceeded(parentResult)
    const reparseEvidence = parseLastJson(parentResult.stdout)
    assert.match(reparseEvidence.parentFailure, /reparse/iu)
    assert.match(reparseEvidence.sourceFailure, /reparse/iu)
    assert.equal(reparseEvidence.destinationExists, false)
    assert.deepEqual(readdirSync(candidateEscapeRoot).sort(), ['sentinel.txt'])
    assert.deepEqual(readdirSync(sourceEscapeRoot).sort(), ['escape-source.txt'])

    const beforeResult = runPowerShell(`
      Get-TradingLabCandidateTreeManifest -Root ${psQuote(sourceScanRoot)} -ExcludeBuildDirectories | ConvertTo-Json -Depth 8 -Compress
    `)
    assertPowerShellSucceeded(beforeResult)
    const before = parseLastJson(beforeResult.stdout)
    const swapEntry = before.Entries.find(({ RelativePath }) =>
      RelativePath === 'swap/before.txt')
    assert.ok(swapEntry, 'scan fixture file is missing from the source-before manifest')

    rmSync(scanSwap, { recursive: true, force: true })
    symlinkSync(scanEscapeRoot, scanSwap, 'junction')
    scanSwapIsJunction = true
    const replacedResult = runPowerShell(`
      $entry = ${psQuote(JSON.stringify(swapEntry))} | ConvertFrom-Json
      $entryFailure = try {
        Assert-TradingLabCandidateSourceEntryUnchanged -SourceRoot ${psQuote(sourceScanRoot)} -Entry $entry | Out-Null
        $null
      } catch { $_.Exception.Message }
      $rescanFailure = try {
        Get-TradingLabCandidateTreeManifest -Root ${psQuote(sourceScanRoot)} -ExcludeBuildDirectories | Out-Null
        $null
      } catch { $_.Exception.Message }
      [pscustomobject]@{
        entryFailure = $entryFailure
        rescanFailure = $rescanFailure
      } | ConvertTo-Json -Compress
    `)
    assertPowerShellSucceeded(replacedResult)
    const replaced = parseLastJson(replacedResult.stdout)
    assert.match(replaced.entryFailure, /reparse|SOURCE_CHANGED/iu)
    assert.match(replaced.rescanFailure, /reparse|SOURCE_CHANGED/iu)
    assert.deepEqual(readdirSync(scanEscapeRoot).sort(), ['sentinel.txt'])
  } finally {
    if (scanSwapIsJunction) {
      unlinkSync(scanSwap)
    }
    unlinkSync(candidateParent)
    unlinkSync(sourceJunction)
    rmSync(fixtureRoot, { recursive: true, force: true })
  }
})

test('owned main backend resolves its validation token only from the leased backend container', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const result = runPowerShell(`
    $environment = [pscustomobject]@{
      STATIC_VALUE = 'kept'
      TRADING_LAB_VALIDATION_INTERNAL_TOKEN = '__TRADING_LAB_OWNED_VALIDATION_SECRET__'
    }
    $resolved = Resolve-TradingLabOwnedProcessEnvironment -Environment $environment -ValidationSecret 'fixture-secret-at-least-32-characters'
    $valid = Get-TradingLabValidationSecretFromContainerEnvironment -Lines @(
      'SPRING_PROFILES_ACTIVE=validation',
      'VALIDATION_INTERNAL_SECRET=fixture-secret-at-least-32-characters',
      'EXECUTION_MODE=demo'
    )
    $environmentLines = @(
      'SPRING_PROFILES_ACTIVE=validation',
      'VALIDATION_INTERNAL_SECRET=fixture-secret-at-least-32-characters',
      'EXECUTION_MODE=demo'
    )
    $lf = [string][char]10
    $crlf = ([string][char]13) + [char]10
    $lfStdout = ($environmentLines -join $lf) + $lf
    $crlfStdout = ($environmentLines -join $crlf) + $crlf
    $lfLines = @($lfStdout -split '\r?\n')
    $crlfLines = @($crlfStdout -split '\r?\n')
    $lfTrailing = Get-TradingLabValidationSecretFromContainerEnvironment -Lines $lfLines
    $crlfTrailing = Get-TradingLabValidationSecretFromContainerEnvironment -Lines $crlfLines
    $missing = try {
      Get-TradingLabValidationSecretFromContainerEnvironment -Lines @(
        'SPRING_PROFILES_ACTIVE=validation'
      ) | Out-Null
      $null
    } catch { $_.Exception.Message }
    $empty = try {
      Get-TradingLabValidationSecretFromContainerEnvironment -Lines @(
        '',
        'VALIDATION_INTERNAL_SECRET=',
        ''
      ) | Out-Null
      $null
    } catch { $_.Exception.Message }
    $duplicate = try {
      Get-TradingLabValidationSecretFromContainerEnvironment -Lines @(
        'VALIDATION_INTERNAL_SECRET=fixture-secret-at-least-32-characters',
        'VALIDATION_INTERNAL_SECRET=another-fixture-secret-at-least-32'
      ) | Out-Null
      $null
    } catch { $_.Exception.Message }
    [pscustomobject]@{
      static = $resolved.STATIC_VALUE
      resolved = $resolved.TRADING_LAB_VALIDATION_INTERNAL_TOKEN
      valid = $valid
      lfTrailing = $lfTrailing
      crlfTrailing = $crlfTrailing
      lfHasTrailingEmpty = $lfLines[-1] -ceq ''
      crlfHasTrailingEmpty = $crlfLines[-1] -ceq ''
      missing = $missing
      empty = $empty
      duplicate = $duplicate
      failureLeaksSecret = (
        @($missing, $empty, $duplicate) -join [Environment]::NewLine
      ).Contains('fixture-secret-at-least-32-characters')
    } | ConvertTo-Json -Compress
  `)
  assertPowerShellSucceeded(result)
  const contract = parseLastJson(result.stdout)
  assert.equal(contract.static, 'kept')
  assert.equal(
    contract.resolved,
    'fixture-secret-at-least-32-characters',
  )
  assert.equal(contract.valid, contract.resolved)
  assert.equal(contract.lfHasTrailingEmpty, true)
  assert.equal(contract.crlfHasTrailingEmpty, true)
  assert.equal(contract.lfTrailing, contract.resolved)
  assert.equal(contract.crlfTrailing, contract.resolved)
  assert.match(contract.missing, /secret.*missing|exactly one/iu)
  assert.match(contract.empty, /secret.*invalid/iu)
  assert.match(contract.duplicate, /secret.*duplicate|exactly one/iu)
  assert.equal(contract.failureLeaksSecret, false)

  const source = readFileSync(runner, 'utf8')
  assert.match(source, /Read-TradingLabValidationSecretFromOwnedContainer/u)
  assert.doesNotMatch(
    source,
    /Write-TradingLabOwnedTextCreateNew[\s\S]{0,240}VALIDATION_INTERNAL_SECRET/iu,
  )
})

test('validation PostgreSQL reconciliation keeps the secret out of argv and evidence while proving authenticated TCP access', (t) => {
  if (!existsSync(runner)) {
    t.skip('runner is missing')
    return
  }
  const fixtureRoot = mkdtempSync(join(tmpdir(), 'trading-lab-postgres-credential-'))
  const stdoutPath = join(fixtureRoot, 'reconcile.stdout.log')
  const stderrPath = join(fixtureRoot, 'reconcile.stderr.log')
  try {
    const result = runPowerShell(`
      $token = 'task8-postgres-reconcile-token-0123456789abcdef'
      $bundle = Register-TradingLabOwnedRunSecrets -RunToken $token
      $script:credentialSecret = [string]$bundle.Secrets['VALIDATION_DATABASE_PASSWORD']
      $script:wrongCredentialSecret = [string]$bundle.Secrets['VALIDATION_REDIS_PASSWORD']
      $script:credentialCalls = [System.Collections.Generic.List[object]]::new()
      $services = @(Get-TradingLabFixedStackServiceNames)
      $ids = @(
        (('a' * 64) -join ''),
        (('b' * 64) -join ''),
        (('c' * 64) -join '')
      )
      $observed = @(
        for ($index = 0; $index -lt $services.Count; $index++) {
          [pscustomobject]@{
            Service = $services[$index]
            ContainerId = $ids[$index]
            Running = $true
          }
        }
      )
      $script:credentialLease = New-TradingLabFixedStackLease -RunToken $token -ObservedContainers $observed

      function Read-TradingLabFixedStackLease {
        param([string]$LeasePath, [string]$RunToken)
        return $script:credentialLease
      }
      function Assert-TradingLabFixedStackGuardOwned {
        param([string]$GuardPath, [string]$RunToken)
        return $true
      }
      function Get-TradingLabFixedStackRuntimeSnapshot {
        param([object]$Context, [string]$Label)
        return @($script:credentialLease.ServiceIdentities)
      }
      function Invoke-TradingLabExternalProcess {
        param(
          [string]$Executable,
          [string[]]$Arguments,
          [string]$WorkingDirectory,
          [string]$StdoutPath,
          [string]$StderrPath,
          [object]$Environment,
          [AllowNull()][string]$StdinText
        )
        $script:credentialCalls.Add([pscustomobject]@{
          Executable = $Executable
          Arguments = @($Arguments)
          StdinText = [string]$StdinText
        })
        [System.IO.File]::WriteAllText(
          $StdoutPath,
          "deliberate-fixture-leak=$script:credentialSecret",
          [System.Text.UTF8Encoding]::new($false)
        )
        [System.IO.File]::WriteAllText(
          $StderrPath,
          "deliberate-fixture-leak=$script:credentialSecret",
          [System.Text.UTF8Encoding]::new($false)
        )
        return [pscustomobject]@{
          ExitCode = 0
          StdoutPath = $StdoutPath
          StderrPath = $StderrPath
        }
      }

      $context = [pscustomobject]@{
        RunToken = $token
        FixedStackLeasePath = 'C:\\fixture\\fixed-stack-lease.json'
        FixedStackGuardPath = 'C:\\fixture\\fixed-stack-guard.json'
        ArtifactsDirectory = ${psQuote(fixtureRoot)}
      }
      Invoke-TradingLabValidationPostgresCredentialReconciliation -Context $context -StdoutPath ${psQuote(stdoutPath)} -StderrPath ${psQuote(stderrPath)} | Out-Null

      $argv = @(
        $script:credentialCalls |
          ForEach-Object { @($_.Arguments) -join [char]0x20 }
      ) -join [Environment]::NewLine
      $stdin = @(
        $script:credentialCalls |
          ForEach-Object { [string]$_.StdinText }
      ) -join [Environment]::NewLine
      $networkProbeCall = @(
        $script:credentialCalls |
          Where-Object { @($_.Arguments) -contains 'postgres:16' }
      )[0]
      $networkProbeStdin = [string]$networkProbeCall.StdinText
      $evidence = @(
        [System.IO.File]::ReadAllText(${psQuote(stdoutPath)}),
        [System.IO.File]::ReadAllText(${psQuote(stderrPath)})
      ) -join [Environment]::NewLine
      [pscustomobject]@{
        callCount = $script:credentialCalls.Count
        secretInArgv = (
          $argv.Contains($script:credentialSecret) -or
          $argv.Contains($script:wrongCredentialSecret)
        )
        secretInStdin = $stdin.Contains($script:credentialSecret)
        wrongSecretInStdin = $stdin.Contains($script:wrongCredentialSecret)
        secretInEvidence = (
          $evidence.Contains($script:credentialSecret) -or
          $evidence.Contains($script:wrongCredentialSecret)
        )
        alterRoleFromStdin = $stdin -match '(?i)ALTER\\s+ROLE\\s+fx_validation_app'
        authenticatedProbe = (
          $stdin -match '(?i)PGPASSWORD' -and
          $stdin -match '(?i)(?:-h|--host(?:=|\\s+))\\s*validation-postgres' -and
          $stdin -match '(?i)SELECT\\s+1'
        )
        rejectsWrongCredential = (
          $stdin -match '(?is)if\\s+PGPASSWORD=.*?psql' -and
          $stdin -match '(?i)exit\\s+41'
        )
        probeHasCarriageReturn = $networkProbeStdin.Contains([char]13)
        usesLeasedPostgresId = $argv.Contains((('b' * 64) -join ''))
        usesDockerExec = $argv -match '(?i)(?:^|\\s)exec(?:\\s|$)'
        usesIsolatedNetworkClient = (
          $argv -match '(?i)(?:^|\\s)run(?:\\s|$)' -and
          $argv -match '(?i)(?:^|\\s)--rm(?:\\s|$)' -and
          $argv.Contains('fx-trading-validation-internal') -and
          $argv.Contains('postgres:16')
        )
      } | ConvertTo-Json -Compress
    `)
    assertPowerShellSucceeded(result)
    const contract = parseLastJson(result.stdout)

    assert.equal(contract.callCount >= 1, true)
    assert.equal(contract.secretInArgv, false)
    assert.equal(contract.secretInStdin, true)
    assert.equal(contract.wrongSecretInStdin, true)
    assert.equal(contract.secretInEvidence, false)
    assert.equal(contract.alterRoleFromStdin, true)
    assert.equal(contract.authenticatedProbe, true)
    assert.equal(contract.rejectsWrongCredential, true)
    assert.equal(contract.probeHasCarriageReturn, false)
    assert.equal(contract.usesLeasedPostgresId, true)
    assert.equal(contract.usesDockerExec, true)
    assert.equal(contract.usesIsolatedNetworkClient, true)
  } finally {
    rmSync(fixtureRoot, { recursive: true, force: true })
  }
})

function writeStoppedStackRecoveryHistory(artifactsRoot, {
  runDirectory = '20260726T000000000Z-deadbeef',
  runToken = '1'.repeat(64),
  ids = ['a'.repeat(64), 'b'.repeat(64), 'c'.repeat(64)],
  cleanupStatus = 'PASS',
  fixedStackStatus = 'PASS',
  includeOwner = true,
  completedAtUtc = '2026-07-26T00:00:00.0000000+00:00',
  extraCleanupDetails = [],
} = {}) {
  const artifactsDirectory = join(artifactsRoot, runDirectory)
  mkdirSync(artifactsDirectory, { recursive: true })
  if (includeOwner) {
    writeFileSync(
      join(artifactsDirectory, '.trading-lab-owner.json'),
      `${JSON.stringify({
        schemaVersion: 1,
        runToken,
      })}\n`,
      'utf8',
    )
  }
  const services = [
    'validation-backend',
    'validation-postgres',
    'validation-redis',
  ]
  writeFileSync(
    join(artifactsDirectory, 'fixed-stack-lease.json'),
    `${JSON.stringify({
      SchemaVersion: 1,
      RunToken: runToken,
      ComposeProject: 'fx-trading-validation',
      ComposeFile: join(platformRoot, 'infra', 'docker-compose.validation.yml'),
      ServiceIdentities: services.map((Service, index) => ({
        Service,
        ContainerId: ids[index],
        State: 'running',
        Running: true,
      })),
    })}\n`,
    'utf8',
  )
  writeFileSync(
    join(artifactsDirectory, 'cleanup-receipt.json'),
    `${JSON.stringify({
      schemaVersion: 1,
      runToken,
      status: cleanupStatus,
      completedAtUtc,
      details: [
        {
          resource: 'fixed-validation-stack',
          status: fixedStackStatus,
          detail: 'Fixed validation stack stopped with leased container IDs',
        },
        ...extraCleanupDetails,
      ],
    })}\n`,
    'utf8',
  )
  return artifactsDirectory
}

function writeStoppedStackRecoveryLink(recoveryDirectory, sourceDirectory, overrides = {}) {
  const recoveryOwnerPath = join(recoveryDirectory, '.trading-lab-owner.json')
  const recoveryLeasePath = join(recoveryDirectory, 'fixed-stack-lease.json')
  const recoveryCleanupPath = join(recoveryDirectory, 'cleanup-receipt.json')
  const sourceOwnerPath = join(sourceDirectory, '.trading-lab-owner.json')
  const sourceLeasePath = join(sourceDirectory, 'fixed-stack-lease.json')
  const sourceCleanupPath = join(sourceDirectory, 'cleanup-receipt.json')
  const runToken = JSON.parse(readFileSync(recoveryOwnerPath, 'utf8')).runToken
  const link = {
    schemaVersion: 1,
    runToken,
    sourceArtifactsDirectory: resolve(sourceDirectory),
    sourceOwnerMarkerSha256: sha256File(sourceOwnerPath),
    sourceLeaseSha256: sha256File(sourceLeasePath),
    sourceCleanupReceiptSha256: sha256File(sourceCleanupPath),
    recoveryOwnerMarkerSha256: sha256File(recoveryOwnerPath),
    recoveryLeaseSha256: sha256File(recoveryLeasePath),
    recoveryCleanupReceiptSha256: sha256File(recoveryCleanupPath),
    createdAtUtc: '2026-07-26T00:00:00.1234569+00:00',
    ...overrides,
  }
  const linkPath = join(recoveryDirectory, 'cleanup-recovery-link.json')
  writeFileSync(
    linkPath,
    `${JSON.stringify(link)}\n`,
    'utf8',
  )
  return linkPath
}

function writeStoppedStackLinkedRecoveryCase(artifactsRoot, {
  sourceCleanupStatus = 'FAIL',
  sourceCompletedAtUtc = '2026-07-26T00:00:00.1234567+00:00',
  recoveryCompletedAtUtc = '2026-07-26T00:00:00.1234568+00:00',
  recoveryExtraCleanupDetails = [],
} = {}) {
  const ids = ['a'.repeat(64), 'b'.repeat(64), 'c'.repeat(64)]
  const sourceDirectory = writeStoppedStackRecoveryHistory(artifactsRoot, {
    runDirectory: 'failed-attempt',
    ids,
    cleanupStatus: sourceCleanupStatus,
    fixedStackStatus: 'PASS',
    completedAtUtc: sourceCompletedAtUtc,
  })
  const recoveryDirectory = writeStoppedStackRecoveryHistory(artifactsRoot, {
    runDirectory: 'manual-recovery',
    ids,
    completedAtUtc: recoveryCompletedAtUtc,
    extraCleanupDetails: recoveryExtraCleanupDetails,
  })
  writeStoppedStackRecoveryLink(recoveryDirectory, sourceDirectory)
  return { recoveryDirectory, sourceDirectory }
}

function rewriteJsonFile(path, mutate) {
  const value = JSON.parse(readFileSync(path, 'utf8'))
  mutate(value)
  writeFileSync(path, `${JSON.stringify(value)}\n`, 'utf8')
}

function sha256File(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex')
}

function runPowerShell(body, executable = 'powershell.exe') {
  return spawnSync(
    executable,
    [
      '-NoProfile',
      '-ExecutionPolicy',
      'Bypass',
      '-Command',
      `& { . ${psQuote(runner)} -ContractOnly; ${body} }`,
    ],
    {
      cwd: platformRoot,
      encoding: 'utf8',
      windowsHide: true,
    },
  )
}

function assertPowerShellSucceeded(result) {
  assert.equal(
    result.status,
    0,
    `PowerShell contract probe failed\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`,
  )
}

function parseLastJson(stdout) {
  const line = stdout.trim().split(/\r?\n/u).filter(Boolean).at(-1)
  assert.ok(line, 'PowerShell probe returned no JSON')
  return JSON.parse(line)
}

function psQuote(value) {
  return `'${String(value).replaceAll("'", "''")}'`
}

function writeSurefire(path, {
  tests = 1,
  failures = 0,
  errors = 0,
  skipped = 0,
  requiredClass = basename(path).slice('TEST-'.length, -'.xml'.length),
  suiteName = requiredClass,
  testcases = Array.from({ length: tests }, (_, index) => ({
    classname: requiredClass,
    name: `case-${index + 1}`,
  })),
} = {}) {
  const testcaseXml = testcases.map((testcase) => [
    `<testcase classname="${testcase.classname}" name="${testcase.name}">`,
    testcase.childXml ?? '',
    '</testcase>',
  ].join('')).join('')
  writeFileSync(
    path,
    [
      `<?xml version="1.0" encoding="UTF-8"?>`,
      `<testsuite name="${suiteName}" tests="${tests}" failures="${failures}" errors="${errors}" skipped="${skipped}">`,
      testcaseXml,
      '</testsuite>',
    ].join(''),
    'utf8',
  )
}
