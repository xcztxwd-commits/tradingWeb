[CmdletBinding()]
param(
  [switch]$DryRun,
  [switch]$SkipFullSuite,
  [AllowEmptyCollection()][string[]]$FixedIssue = @(),
  [AllowEmptyCollection()][string[]]$UnresolvedIssue = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$OwnedDatabasePattern = '^fx_scenario_it_[a-z0-9_]{1,45}$'
$ForbiddenDatabases = @('fx_platform', 'postgres', 'template0', 'template1')
$ExpectedTotalCases = 191
$ExpectedSpotCases = 36
$ExpectedPerpetualCases = 155
$EnvironmentNames = @(
  'SCENARIO_DATABASE_NAME',
  'SCENARIO_DATABASE_URL',
  'SCENARIO_DATABASE_USERNAME',
  'SCENARIO_DATABASE_PASSWORD',
  'DATABASE_URL',
  'DATABASE_USERNAME',
  'DATABASE_PASSWORD',
  'SPRING_DATASOURCE_URL',
  'SPRING_DATASOURCE_USERNAME',
  'SPRING_DATASOURCE_PASSWORD',
  'SPRING_PROFILES_ACTIVE'
)

$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$PlatformRoot = [System.IO.Path]::GetFullPath((Join-Path $ScriptRoot '..'))
$WorkspaceRoot = [System.IO.Path]::GetFullPath((Join-Path $PlatformRoot '..'))
$BackendRoot = Join-Path $PlatformRoot 'backend'
$ComposeFile = Join-Path $PlatformRoot 'infra\docker-compose.yml'
$MatrixJson = Join-Path $PlatformRoot 'docs\testing\spot-perp-scenario-matrix.json'
$ReportPath = Join-Path $PlatformRoot 'docs\testing\spot-perp-test-report.md'
$SurefireRoot = Join-Path $BackendRoot 'target\surefire-reports'
$ScenarioArtifactsRoot = Join-Path $BackendRoot 'target\scenario-artifacts'
$ScenarioSurefirePrefix = 'TEST-com.fxplatform.trading.scenario.'
$ReportSections = @(
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
)
$KeyFiles = @(
  'docs/testing/spot-perp-scenario-matrix.csv',
  'docs/testing/spot-perp-scenario-matrix.json',
  'docs/testing/spot-perp-test-report.md',
  'scripts/run-spot-perp-scenario-tests.ps1',
  'backend/src/test/java/com/fxplatform/trading/scenario/ScenarioCatalog.java',
  'backend/src/test/java/com/fxplatform/trading/scenario/ScenarioExecutor.java',
  'backend/src/test/java/com/fxplatform/trading/scenario/ScenarioResultReader.java',
  'backend/src/test/java/com/fxplatform/trading/scenario/SpotScenarioMatrixIT.java',
  'backend/src/test/java/com/fxplatform/trading/scenario/PerpetualScenarioMatrixIT.java',
  'backend/src/test/java/com/fxplatform/trading/scenario/ScenarioResilienceIT.java',
  'backend/src/test/java/com/fxplatform/trading/scenario/oracle/SpotScenarioOracle.java',
  'backend/src/test/java/com/fxplatform/trading/scenario/oracle/PerpetualScenarioOracle.java',
  'backend/target/scenario-artifacts/<caseId>/expected.json',
  'backend/target/scenario-artifacts/<caseId>/actual.json'
)

function Assert-RequiredPath {
  param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)][string]$Label
  )
  if (-not (Test-Path -LiteralPath $Path)) {
    throw "$Label does not exist: $Path"
  }
}

function Assert-OwnedDatabaseName {
  param([Parameter(Mandatory = $true)][string]$DatabaseName)
  if ($DatabaseName -notmatch $OwnedDatabasePattern) {
    throw "Unsafe scenario database name: $DatabaseName"
  }
  if ($ForbiddenDatabases -contains $DatabaseName.ToLowerInvariant()) {
    throw "Refusing forbidden database name: $DatabaseName"
  }
}

function New-ScenarioDatabaseName {
  $stamp = [DateTime]::UtcNow.ToString('yyyyMMddHHmmss')
  $suffix = [Guid]::NewGuid().ToString('N').Substring(0, 8)
  $name = "fx_scenario_it_${stamp}_${PID}_${suffix}".ToLowerInvariant()
  Assert-OwnedDatabaseName -DatabaseName $name
  return $name
}

function Get-EnvironmentSnapshot {
  param([string[]]$Names)
  $snapshot = @{}
  foreach ($name in $Names) {
    $value = [Environment]::GetEnvironmentVariable($name, 'Process')
    $snapshot[$name] = @{
      HasValue = $null -ne $value
      Value = $value
    }
  }
  return $snapshot
}

function Set-ProcessEnvironment {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [AllowNull()][string]$Value
  )
  [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}

function Restore-Environment {
  param([hashtable]$Snapshot)
  foreach ($name in $Snapshot.Keys) {
    $entry = $Snapshot[$name]
    if ($entry.HasValue) {
      Set-ProcessEnvironment -Name $name -Value ([string]$entry.Value)
    } else {
      Set-ProcessEnvironment -Name $name -Value $null
    }
  }
}

function Invoke-Checked {
  param(
    [Parameter(Mandatory = $true)][string]$FilePath,
    [Parameter(Mandatory = $true)][string[]]$ArgumentList,
    [string]$WorkingDirectory = '',
    [switch]$Capture
  )

  $previous = Get-Location
  try {
    if ($WorkingDirectory) {
      Set-Location -LiteralPath $WorkingDirectory
    }
    if ($Capture) {
      $output = & $FilePath @ArgumentList 2>&1
      $exitCode = $LASTEXITCODE
      if ($exitCode -ne 0) {
        throw "$FilePath failed with exit code $exitCode`n$($output -join [Environment]::NewLine)"
      }
      return ($output -join [Environment]::NewLine).Trim()
    }

    & $FilePath @ArgumentList
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
      throw "$FilePath failed with exit code $exitCode"
    }
  } finally {
    Set-Location -LiteralPath $previous
  }
}

function Invoke-Docker {
  param(
    [Parameter(Mandatory = $true)][string[]]$Arguments,
    [switch]$Capture
  )
  return Invoke-Checked -FilePath 'docker' -ArgumentList $Arguments -Capture:$Capture
}

function Invoke-Psql {
  param(
    [Parameter(Mandatory = $true)][string]$ContainerId,
    [Parameter(Mandatory = $true)][string]$DatabaseName,
    [Parameter(Mandatory = $true)][string]$Sql,
    [switch]$Capture
  )
  Assert-OwnedDatabaseName -DatabaseName $DatabaseName
  $arguments = @(
    'exec',
    $ContainerId,
    'psql',
    '-X',
    '-v',
    'ON_ERROR_STOP=1',
    '-U',
    'postgres',
    '-d',
    'postgres',
    '--no-align',
    '--tuples-only',
    "--command=$Sql"
  )
  return Invoke-Docker -Arguments $arguments -Capture:$Capture
}

function Wait-PostgresReady {
  param([Parameter(Mandatory = $true)][string]$ContainerId)
  for ($attempt = 1; $attempt -le 30; $attempt++) {
    & docker exec $ContainerId pg_isready -U postgres -d postgres *> $null
    if ($LASTEXITCODE -eq 0) {
      return
    }
    Start-Sleep -Seconds 1
  }
  throw 'Compose PostgreSQL did not become ready within 30 seconds'
}

function New-OwnedDatabase {
  param(
    [Parameter(Mandatory = $true)][string]$ContainerId,
    [Parameter(Mandatory = $true)][string]$DatabaseName,
    [Parameter(Mandatory = $true)][ref]$DatabaseCreated
  )
  Assert-OwnedDatabaseName -DatabaseName $DatabaseName
  $existing = Invoke-Psql `
      -ContainerId $ContainerId `
      -DatabaseName $DatabaseName `
      -Sql "SELECT 1 FROM pg_database WHERE datname = '$DatabaseName'" `
      -Capture
  if ($existing -eq '1') {
    throw "Owned scenario database already exists: $DatabaseName"
  }
  $DatabaseCreated.Value = $true
  Invoke-Psql `
      -ContainerId $ContainerId `
      -DatabaseName $DatabaseName `
      -Sql "CREATE DATABASE `"$DatabaseName`""
  $created = Invoke-Psql `
      -ContainerId $ContainerId `
      -DatabaseName $DatabaseName `
      -Sql "SELECT 1 FROM pg_database WHERE datname = '$DatabaseName'" `
      -Capture
  if ($created -ne '1') {
    throw "Scenario database creation could not be confirmed: $DatabaseName"
  }
}

function Remove-OwnedDatabase {
  param(
    [Parameter(Mandatory = $true)][string]$ContainerId,
    [Parameter(Mandatory = $true)][string]$DatabaseName
  )
  Assert-OwnedDatabaseName -DatabaseName $DatabaseName
  Invoke-Psql `
      -ContainerId $ContainerId `
      -DatabaseName $DatabaseName `
      -Sql "DROP DATABASE IF EXISTS `"$DatabaseName`" WITH (FORCE)"
  $remaining = Invoke-Psql `
      -ContainerId $ContainerId `
      -DatabaseName $DatabaseName `
      -Sql "SELECT 1 FROM pg_database WHERE datname = '$DatabaseName'" `
      -Capture
  if ($remaining -eq '1') {
    throw "Scenario database cleanup could not be confirmed: $DatabaseName"
  }
}

function Resolve-Maven {
  $mvnCommand = Get-Command 'mvn.cmd' -ErrorAction SilentlyContinue
  if ($null -eq $mvnCommand) {
    $mvnCommand = Get-Command 'mvn' -ErrorAction Stop
  }
  return $mvnCommand.Source
}

function Read-ScenarioMatrix {
  param([Parameter(Mandatory = $true)][string]$MatrixPath)
  $parsed = Get-Content -LiteralPath $MatrixPath -Raw | ConvertFrom-Json
  $items = New-Object 'System.Collections.Generic.List[object]'
  foreach ($scenario in $parsed) {
    $items.Add($scenario)
  }
  return $items.ToArray()
}

function Get-MatrixCoverage {
  param([Parameter(Mandatory = $true)][string]$MatrixPath)

  $matrix = @(Read-ScenarioMatrix -MatrixPath $MatrixPath)
  $pricePaths = @($matrix |
      ForEach-Object { @($_.priceSteps) } |
      ForEach-Object { [string]$_.path } |
      Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
      Sort-Object -Unique)
  $actionTypes = @($matrix |
      ForEach-Object { @($_.actions) } |
      ForEach-Object { [string]$_.type } |
      Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
      Sort-Object -Unique)

  return [pscustomobject]@{
    TotalCases = $matrix.Count
    SpotCases = @($matrix | Where-Object { $_.productType -eq 'CRYPTO_SPOT' }).Count
    PerpetualCases = @($matrix | Where-Object { $_.productType -eq 'LINEAR_PERP' }).Count
    PricePaths = $pricePaths
    ActionTypes = $actionTypes
    PositionModes = @($matrix |
        ForEach-Object { [string]$_.positionMode } |
        Sort-Object -Unique)
    MarginModes = @($matrix |
        ForEach-Object { [string]$_.marginMode } |
        Sort-Object -Unique)
    OrderTypes = @($matrix |
        ForEach-Object { [string]$_.orderType } |
        Sort-Object -Unique)
    QuantityUnits = @($matrix |
        ForEach-Object { [string]$_.quantityUnit } |
        Sort-Object -Unique)
    TestClasses = @($matrix |
        ForEach-Object { [string]$_.testClass } |
        Sort-Object -Unique)
    ExecutionStatuses = @($matrix |
        ForEach-Object { [string]$_.executionStatus } |
        Sort-Object -Unique)
  }
}

function Assert-MatrixCoverage {
  param([Parameter(Mandatory = $true)][object]$Coverage)
  if (
    $Coverage.TotalCases -ne $ExpectedTotalCases -or
    $Coverage.SpotCases -ne $ExpectedSpotCases -or
    $Coverage.PerpetualCases -ne $ExpectedPerpetualCases -or
    $Coverage.SpotCases + $Coverage.PerpetualCases -ne $Coverage.TotalCases
  ) {
    throw "Scenario matrix coverage gate failed: total=$($Coverage.TotalCases)/$ExpectedTotalCases spot=$($Coverage.SpotCases)/$ExpectedSpotCases perpetual=$($Coverage.PerpetualCases)/$ExpectedPerpetualCases"
  }
}

function Clear-ScenarioArtifacts {
  param(
    [Parameter(Mandatory = $true)][string]$ArtifactsRoot,
    [Parameter(Mandatory = $true)][string]$ExpectedBackendRoot
  )

  $resolvedRoot = [System.IO.Path]::GetFullPath($ArtifactsRoot)
  $allowedRoot = [System.IO.Path]::GetFullPath(
      (Join-Path $ExpectedBackendRoot 'target\scenario-artifacts'))
  if (-not $resolvedRoot.Equals(
      $allowedRoot,
      [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Unsafe scenario artifact cleanup path: $resolvedRoot"
  }
  if (Test-Path -LiteralPath $resolvedRoot) {
    Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
  }
  New-Item -ItemType Directory -Path $resolvedRoot -Force | Out-Null
}

function Assert-ScenarioArtifacts {
  param(
    [Parameter(Mandatory = $true)][string]$ArtifactsRoot,
    [Parameter(Mandatory = $true)][string]$MatrixPath
  )

  Assert-RequiredPath -Path $ArtifactsRoot -Label 'Scenario artifacts root'
  Assert-RequiredPath -Path $MatrixPath -Label 'Scenario matrix JSON'
  $matrix = @(Read-ScenarioMatrix -MatrixPath $MatrixPath)
  $caseIds = @($matrix | ForEach-Object { [string]$_.caseId })
  $directories = @(Get-ChildItem -LiteralPath $ArtifactsRoot -Directory)
  if ($directories.Count -ne $caseIds.Count) {
    throw "Scenario artifact count gate failed: directories=$($directories.Count) cases=$($caseIds.Count)"
  }

  $expectedNames = @{}
  foreach ($caseId in $caseIds) {
    if ([string]::IsNullOrWhiteSpace($caseId)) {
      throw 'Scenario artifact gate found an empty caseId'
    }
    $expectedNames[$caseId] = $true
    foreach ($fileName in @('expected.json', 'actual.json')) {
      $artifact = Join-Path (Join-Path $ArtifactsRoot $caseId) $fileName
      if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) {
        throw "Missing scenario artifact: $artifact"
      }
      if ((Get-Item -LiteralPath $artifact).Length -le 0) {
        throw "Empty scenario artifact: $artifact"
      }
    }
  }
  foreach ($directory in $directories) {
    if (-not $expectedNames.ContainsKey($directory.Name)) {
      throw "Unexpected scenario artifact directory: $($directory.FullName)"
    }
  }
  return $caseIds.Count
}

function Add-CommandResult {
  param(
    [Parameter(Mandatory = $true)][AllowEmptyCollection()]
    [System.Collections.Generic.List[object]]$CommandLog,
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][string]$Command,
    [Parameter(Mandatory = $true)][string]$Status,
    [Parameter(Mandatory = $true)][datetime]$StartedAt,
    [Parameter(Mandatory = $true)][datetime]$FinishedAt,
    [Parameter(Mandatory = $true)][string]$Result,
    [AllowNull()][string]$ErrorMessage
  )

  $entry = [ordered]@{
    Name = $Name
    Command = $Command
    Status = $Status
    Result = $Result
    StartedAt = $StartedAt.ToString('o')
    FinishedAt = $FinishedAt.ToString('o')
  }
  if (-not [string]::IsNullOrWhiteSpace($ErrorMessage)) {
    $entry.Error = $ErrorMessage
  }
  $CommandLog.Add([pscustomobject]$entry)
}

function Invoke-RecordedStep {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][string]$Command,
    [Parameter(Mandatory = $true)][scriptblock]$Action,
    [Parameter(Mandatory = $true)][AllowEmptyCollection()]
    [System.Collections.Generic.List[object]]$CommandLog
  )

  $started = [DateTime]::UtcNow
  Write-Host "==> $Name"
  try {
    $value = & $Action
    Add-CommandResult `
        -CommandLog $CommandLog `
        -Name $Name `
        -Command $Command `
        -Status 'PASS' `
        -StartedAt $started `
        -FinishedAt ([DateTime]::UtcNow) `
        -Result 'Completed successfully' `
        -ErrorMessage $null
    return $value
  } catch {
    Add-CommandResult `
        -CommandLog $CommandLog `
        -Name $Name `
        -Command $Command `
        -Status 'FAIL' `
        -StartedAt $started `
        -FinishedAt ([DateTime]::UtcNow) `
        -Result 'Failed' `
        -ErrorMessage $_.Exception.Message
    throw
  }
}

function Invoke-MavenPhase {
  param(
    [Parameter(Mandatory = $true)][string]$Maven,
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][string[]]$Arguments,
    [Parameter(Mandatory = $true)][AllowEmptyCollection()]
    [System.Collections.Generic.List[object]]$CommandLog
  )
  $started = [DateTime]::UtcNow
  Write-Host "==> $Name"
  try {
    Invoke-Checked `
        -FilePath $Maven `
        -ArgumentList $Arguments `
        -WorkingDirectory $BackendRoot
    Add-CommandResult `
        -CommandLog $CommandLog `
        -Name $Name `
        -Command "mvn $($Arguments -join ' ')" `
        -Status 'PASS' `
        -StartedAt $started `
        -FinishedAt ([DateTime]::UtcNow) `
        -Result 'BUILD SUCCESS' `
        -ErrorMessage $null
  } catch {
    Add-CommandResult `
        -CommandLog $CommandLog `
        -Name $Name `
        -Command "mvn $($Arguments -join ' ')" `
        -Status 'FAIL' `
        -StartedAt $started `
        -FinishedAt ([DateTime]::UtcNow) `
        -Result 'BUILD FAILURE' `
        -ErrorMessage $_.Exception.Message
    throw
  }
}

function Get-SurefireMessage {
  param(
    [AllowNull()][System.Xml.XmlNode]$Node,
    [Parameter(Mandatory = $true)][string]$Fallback
  )
  if ($null -eq $Node) {
    return $Fallback
  }
  $message = ''
  if ($Node.Attributes -and $Node.Attributes['message']) {
    $message = [string]$Node.Attributes['message'].Value
  }
  if ([string]::IsNullOrWhiteSpace($message)) {
    $message = [string]$Node.InnerText
  }
  if ([string]::IsNullOrWhiteSpace($message)) {
    return $Fallback
  }
  return (($message -replace '\s+', ' ').Trim())
}

function Resolve-ScenarioCase {
  param(
    [Parameter(Mandatory = $true)][string]$TestName,
    [Parameter(Mandatory = $true)][string]$TestClass,
    [Parameter(Mandatory = $true)][object[]]$Matrix
  )

  $directMatch = @($Matrix |
      Where-Object {
        [string]$_.testClass -eq $TestClass -and
        $TestName.Contains([string]$_.caseId)
      } |
      Sort-Object @{
        Expression = { ([string]$_.caseId).Length }
        Descending = $true
      } |
      Select-Object -First 1)
  if ($directMatch.Count -gt 0) {
    return @($directMatch)
  }

  $indexMatch = [regex]::Match($TestName, '\[(\d+)\]$')
  if (-not $indexMatch.Success) {
    return @()
  }

  $invocationIndex = [int]$indexMatch.Groups[1].Value
  $classScenarios = @($Matrix |
      Where-Object { [string]$_.testClass -eq $TestClass } |
      Sort-Object { [string]$_.caseId })
  if ($invocationIndex -lt 1 -or $invocationIndex -gt $classScenarios.Count) {
    return @()
  }
  return @($classScenarios[$invocationIndex - 1])
}

function Read-ScenarioSurefire {
  param(
    [Parameter(Mandatory = $true)][string]$ReportsRoot,
    [Parameter(Mandatory = $true)][string]$MatrixPath,
    [switch]$IgnoreReports
  )

  $matrix = @(Read-ScenarioMatrix -MatrixPath $MatrixPath)
  $classes = @($matrix |
      ForEach-Object { [string]$_.testClass } |
      Sort-Object -Unique)
  $caseResults = @{}
  foreach ($scenario in $matrix) {
    $caseId = [string]$scenario.caseId
    $caseResults[$caseId] = [pscustomobject]@{
      CaseId = $caseId
      ProductType = [string]$scenario.productType
      TestClass = [string]$scenario.testClass
      Status = 'NOT_REPORTED'
      Message = ''
    }
  }
  $FailureDetails = New-Object 'System.Collections.Generic.List[object]'
  $totals = @{
    Tests = 0
    Failures = 0
    Errors = 0
    Skipped = 0
  }

  if (-not $IgnoreReports) {
    foreach ($testClass in $classes) {
      $className = ([string]$testClass).Split('.')[-1]
      $xmlPath = Join-Path $ReportsRoot "$ScenarioSurefirePrefix$className.xml"
      if (-not (Test-Path -LiteralPath $xmlPath)) {
        $FailureDetails.Add([pscustomobject]@{
          caseId = ''
          testClass = [string]$testClass
          message = "Missing scenario Surefire report: $xmlPath"
        })
        continue
      }

      try {
        [xml]$document = Get-Content -LiteralPath $xmlPath -Raw
      } catch {
        $FailureDetails.Add([pscustomobject]@{
          caseId = ''
          testClass = [string]$testClass
          message = "Unreadable scenario Surefire report: $($_.Exception.Message)"
        })
        continue
      }
      $suite = $document.testsuite
      if ($null -eq $suite) {
        $FailureDetails.Add([pscustomobject]@{
          caseId = ''
          testClass = [string]$testClass
          message = "Surefire XML has no testsuite root: $xmlPath"
        })
        continue
      }

      $totals.Tests += [int]$suite.GetAttribute('tests')
      $totals.Failures += [int]$suite.GetAttribute('failures')
      $totals.Errors += [int]$suite.GetAttribute('errors')
      $totals.Skipped += [int]$suite.GetAttribute('skipped')

      foreach ($testcase in @($suite.testcase)) {
        $testName = [string]$testcase.GetAttribute('name')
        $scenarioMatch = @(Resolve-ScenarioCase `
            -TestName $testName `
            -TestClass ([string]$testClass) `
            -Matrix $matrix)
        $failureNode = $testcase.SelectSingleNode('failure')
        $errorNode = $testcase.SelectSingleNode('error')
        $skippedNode = $testcase.SelectSingleNode('skipped')
        $status = 'PASSED'
        $message = ''
        if ($null -ne $failureNode) {
          $status = 'FAILED'
          $message = Get-SurefireMessage -Node $failureNode -Fallback 'JUnit assertion failed'
        } elseif ($null -ne $errorNode) {
          $status = 'ERROR'
          $message = Get-SurefireMessage -Node $errorNode -Fallback 'JUnit execution error'
        } elseif ($null -ne $skippedNode) {
          $status = 'SKIPPED'
          $message = Get-SurefireMessage -Node $skippedNode -Fallback 'JUnit invocation skipped'
        }

        if ($scenarioMatch.Count -eq 0) {
          $unmappedMessage = "Surefire invocation could not be mapped to a scenario case: $testName"
          if (-not [string]::IsNullOrWhiteSpace($message)) {
            $unmappedMessage = "$unmappedMessage`: $message"
          }
          $FailureDetails.Add([pscustomobject]@{
            caseId = ''
            testClass = [string]$testcase.GetAttribute('classname')
            message = $unmappedMessage
          })
          continue
        }

        $scenario = $scenarioMatch[0]
        $caseId = [string]$scenario.caseId
        $result = $caseResults[$caseId]
        if ($result.Status -ne 'NOT_REPORTED') {
          $result.Status = 'FAILED'
          $result.Message = 'Duplicate Surefire invocation for the same caseId'
          $FailureDetails.Add([pscustomobject]@{
            caseId = $caseId
            testClass = [string]$scenario.testClass
            message = $result.Message
          })
          continue
        }
        $result.Status = $status
        $result.Message = $message
        if ($status -ne 'PASSED') {
          $FailureDetails.Add([pscustomobject]@{
            caseId = $caseId
            testClass = [string]$scenario.testClass
            message = $message
          })
        }
      }
    }
  }

  foreach ($result in $caseResults.Values) {
    if ($result.Status -eq 'NOT_REPORTED') {
      $FailureDetails.Add([pscustomobject]@{
        caseId = $result.CaseId
        testClass = $result.TestClass
        message = 'No current Surefire testcase was reported for this matrix case'
      })
    }
  }

  $orderedResults = @($caseResults.Values | Sort-Object CaseId)
  $spotResults = @($orderedResults | Where-Object { $_.ProductType -eq 'CRYPTO_SPOT' })
  $perpetualResults = @($orderedResults | Where-Object { $_.ProductType -eq 'LINEAR_PERP' })
  $passedCases = ($orderedResults |
      Where-Object { $_.Status -eq 'PASSED' } |
      Measure-Object).Count
  $failedCases = ($orderedResults |
      Where-Object { $_.Status -eq 'FAILED' -or $_.Status -eq 'ERROR' } |
      Measure-Object).Count
  $skippedCases = ($orderedResults |
      Where-Object { $_.Status -eq 'SKIPPED' } |
      Measure-Object).Count
  $notReportedCases = ($orderedResults |
      Where-Object { $_.Status -eq 'NOT_REPORTED' } |
      Measure-Object).Count
  $spotPassed = ($spotResults |
      Where-Object { $_.Status -eq 'PASSED' } |
      Measure-Object).Count
  $spotFailed = ($spotResults |
      Where-Object { $_.Status -eq 'FAILED' -or $_.Status -eq 'ERROR' } |
      Measure-Object).Count
  $spotSkipped = ($spotResults |
      Where-Object { $_.Status -eq 'SKIPPED' } |
      Measure-Object).Count
  $spotNotReported = ($spotResults |
      Where-Object { $_.Status -eq 'NOT_REPORTED' } |
      Measure-Object).Count
  $perpetualPassed = ($perpetualResults |
      Where-Object { $_.Status -eq 'PASSED' } |
      Measure-Object).Count
  $perpetualFailed = ($perpetualResults |
      Where-Object { $_.Status -eq 'FAILED' -or $_.Status -eq 'ERROR' } |
      Measure-Object).Count
  $perpetualSkipped = ($perpetualResults |
      Where-Object { $_.Status -eq 'SKIPPED' } |
      Measure-Object).Count
  $perpetualNotReported = ($perpetualResults |
      Where-Object { $_.Status -eq 'NOT_REPORTED' } |
      Measure-Object).Count
  return [pscustomobject]@{
    MatrixCases = $matrix.Count
    Tests = $totals.Tests
    Failures = $totals.Failures
    Errors = $totals.Errors
    Skipped = $totals.Skipped
    PassedCases = $passedCases
    FailedCases = $failedCases
    SkippedCases = $skippedCases
    NotReportedCases = $notReportedCases
    SpotPassed = $spotPassed
    SpotFailed = $spotFailed
    SpotSkipped = $spotSkipped
    SpotNotReported = $spotNotReported
    PerpetualPassed = $perpetualPassed
    PerpetualFailed = $perpetualFailed
    PerpetualSkipped = $perpetualSkipped
    PerpetualNotReported = $perpetualNotReported
    FailureDetails = $FailureDetails.ToArray()
    CaseResults = $orderedResults
  }
}

function Assert-ScenarioSurefireSummary {
  param([Parameter(Mandatory = $true)][object]$Summary)
  if (
    $Summary.Tests -le 0 -or
    $Summary.MatrixCases -ne $ExpectedTotalCases -or
    $Summary.Tests -ne $Summary.MatrixCases -or
    $Summary.Failures -ne 0 -or
    $Summary.Errors -ne 0 -or
    $Summary.Skipped -ne 0 -or
    $Summary.FailedCases -ne 0 -or
    $Summary.SkippedCases -ne 0 -or
    $Summary.NotReportedCases -ne 0 -or
    $Summary.PassedCases -ne $Summary.MatrixCases -or
    @($Summary.FailureDetails).Count -ne 0
  ) {
    throw "Scenario Surefire gate failed: tests=$($Summary.Tests) matrix=$($Summary.MatrixCases) passed=$($Summary.PassedCases) failed=$($Summary.FailedCases) skipped=$($Summary.SkippedCases) notReported=$($Summary.NotReportedCases) errors=$($Summary.Errors) details=$(@($Summary.FailureDetails).Count)"
  }
}

function Clear-ScenarioSurefireReports {
  param(
    [Parameter(Mandatory = $true)][string]$ReportsRoot,
    [Parameter(Mandatory = $true)][string]$MatrixPath
  )
  $matrix = @(Read-ScenarioMatrix -MatrixPath $MatrixPath)
  $testClasses = @($matrix |
      ForEach-Object { [string]$_.testClass } |
      Sort-Object -Unique)
  foreach ($testClass in $testClasses) {
    $className = ([string]$testClass).Split('.')[-1]
    $xmlPath = Join-Path $ReportsRoot "$ScenarioSurefirePrefix$className.xml"
    if (Test-Path -LiteralPath $xmlPath) {
      Remove-Item -LiteralPath $xmlPath -Force
    }
  }
}

function ConvertTo-ReportText {
  param([AllowNull()][string]$Value)
  if ([string]::IsNullOrWhiteSpace($Value)) {
    return ''
  }
  $text = ($Value -replace '\s+', ' ').Trim()
  return $text.Replace('|', '\|').Replace('`', "'")
}

function Write-AtomicReport {
  param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)][string]$Status,
    [Parameter(Mandatory = $true)][string]$DatabaseName,
    [Parameter(Mandatory = $true)][AllowEmptyCollection()]
    [System.Collections.Generic.List[object]]$CommandLog,
    [Parameter(Mandatory = $true)][object]$ScenarioSummary,
    [Parameter(Mandatory = $true)][object]$MatrixCoverage,
    [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$FixedIssues,
    [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$UnresolvedIssues,
    [Parameter(Mandatory = $true)][string[]]$KeyFiles,
    [AllowNull()][string]$FailureMessage,
    [Parameter(Mandatory = $true)][string]$DatabaseCreateStatus,
    [Parameter(Mandatory = $true)][string]$CleanupStatus
  )
  $directory = Split-Path -Parent $Path
  if (-not (Test-Path -LiteralPath $directory)) {
    [System.IO.Directory]::CreateDirectory($directory) | Out-Null
  }

  $lines = New-Object System.Collections.Generic.List[string]
  $lines.Add('# Spot + Linear Perpetual DEMO Scenario Test Report')
  $lines.Add('')
  $lines.Add('## Summary')
  $lines.Add('')
  $lines.Add("- Status: **$Status**")
  $lines.Add("- Generated (UTC): $([DateTime]::UtcNow.ToString('o'))")
  $lines.Add("- Total cases: $($ScenarioSummary.MatrixCases)")
  $lines.Add("- Passed: $($ScenarioSummary.PassedCases)")
  $lines.Add("- Failed: $($ScenarioSummary.FailedCases)")
  $lines.Add("- Skipped: $($ScenarioSummary.SkippedCases)")
  $lines.Add("- Not reported: $($ScenarioSummary.NotReportedCases)")
  $lines.Add("- JUnit invocations: $($ScenarioSummary.Tests)")
  $lines.Add("- JUnit suite failures/errors: $($ScenarioSummary.Failures)/$($ScenarioSummary.Errors)")
  $lines.Add('')
  $lines.Add('## Product coverage')
  $lines.Add('')
  $lines.Add("- Spot (``CRYPTO_SPOT``): total=$($MatrixCoverage.SpotCases), passed=$($ScenarioSummary.SpotPassed), failed=$($ScenarioSummary.SpotFailed), skipped=$($ScenarioSummary.SpotSkipped), notReported=$($ScenarioSummary.SpotNotReported)")
  $lines.Add("- Linear Perpetual (``LINEAR_PERP``): total=$($MatrixCoverage.PerpetualCases), passed=$($ScenarioSummary.PerpetualPassed), failed=$($ScenarioSummary.PerpetualFailed), skipped=$($ScenarioSummary.PerpetualSkipped), notReported=$($ScenarioSummary.PerpetualNotReported)")
  $lines.Add('')
  $lines.Add('## Scope')
  $lines.Add('')
  $lines.Add('- This DEMO matrix verifies one full fill per order. Real partial fills and DEPTH matching are explicitly out of scope and are not connected to a production entry point.')
  $lines.Add('- `PARTIALLY_FILLED` is retained only as compatibility input and is rejected with `PARTIAL_FILL_NOT_SUPPORTED` before committed mutation.')
  $lines.Add('')
  $lines.Add('## Fixed issues')
  $lines.Add('')
  if (@($FixedIssues).Count -eq 0) {
    $lines.Add('- No fixed-issue metadata was supplied to this runner invocation.')
  } else {
    foreach ($issue in $FixedIssues) {
      $lines.Add("- $(ConvertTo-ReportText -Value $issue)")
    }
  }
  $lines.Add('')
  $lines.Add('## Unresolved issues')
  $lines.Add('')
  $effectiveUnresolved = New-Object System.Collections.Generic.List[string]
  foreach ($issue in @($UnresolvedIssues)) {
    if (-not [string]::IsNullOrWhiteSpace($issue)) {
      $effectiveUnresolved.Add($issue)
    }
  }
  if (-not [string]::IsNullOrWhiteSpace($FailureMessage)) {
    $effectiveUnresolved.Add("Runner failure: $FailureMessage")
  }
  if ($CleanupStatus.StartsWith('FAILED')) {
    $effectiveUnresolved.Add("Database cleanup: $CleanupStatus")
  }
  if ($effectiveUnresolved.Count -eq 0) {
    $lines.Add('- None reported by this execution.')
  } else {
    foreach ($issue in $effectiveUnresolved) {
      $lines.Add("- $(ConvertTo-ReportText -Value $issue)")
    }
  }
  $lines.Add('')
  $lines.Add('## Command results')
  $lines.Add('')
  foreach ($entry in $CommandLog) {
    $lines.Add("- **$($entry.Name)**")
    $lines.Add("  - Command: ``$(ConvertTo-ReportText -Value ([string]$entry.Command))``")
    $lines.Add("  - Result: $($entry.Status) - $(ConvertTo-ReportText -Value ([string]$entry.Result))")
    $lines.Add("  - Started/finished (UTC): $($entry.StartedAt) / $($entry.FinishedAt)")
    if ($entry.PSObject.Properties.Name -contains 'Error') {
      $lines.Add("  - Error: $(ConvertTo-ReportText -Value ([string]$entry.Error))")
    }
  }
  if ($CommandLog.Count -eq 0) {
    $lines.Add('- No external command completed before the runner stopped.')
  }
  $lines.Add('')
  $lines.Add('## Key files')
  $lines.Add('')
  foreach ($file in $KeyFiles) {
    $lines.Add("- ``$file``")
  }
  $lines.Add('')
  $lines.Add('## Matrix coverage')
  $lines.Add('')
  $lines.Add("- Cases: $($MatrixCoverage.TotalCases)")
  $lines.Add("- Price paths ($(@($MatrixCoverage.PricePaths).Count)): $(@($MatrixCoverage.PricePaths) -join ', ')")
  $lines.Add("- Action types ($(@($MatrixCoverage.ActionTypes).Count)): $(@($MatrixCoverage.ActionTypes) -join ', ')")
  $lines.Add("- Position modes: $(@($MatrixCoverage.PositionModes) -join ', ')")
  $lines.Add("- Margin modes: $(@($MatrixCoverage.MarginModes) -join ', ')")
  $lines.Add("- Order types: $(@($MatrixCoverage.OrderTypes) -join ', ')")
  $lines.Add("- Quantity units: $(@($MatrixCoverage.QuantityUnits) -join ', ')")
  $lines.Add("- Test classes: $(@($MatrixCoverage.TestClasses) -join ', ')")
  $lines.Add("- Checked-in executionStatus values: $(@($MatrixCoverage.ExecutionStatuses) -join ', ')")
  $lines.Add('')
  $lines.Add('## Database lifecycle')
  $lines.Add('')
  $lines.Add("- Owned database: ``$DatabaseName``")
  $lines.Add("- Creation: $DatabaseCreateStatus")
  $lines.Add("- Cleanup: $CleanupStatus")
  $lines.Add('')
  $lines.Add('## Scenario failure details')
  $lines.Add('')
  if (@($ScenarioSummary.FailureDetails).Count -eq 0) {
    $lines.Add('- None reported.')
  } else {
    $lines.Add('| caseId | testClass | message |')
    $lines.Add('| --- | --- | --- |')
    foreach ($detail in @($ScenarioSummary.FailureDetails)) {
      $lines.Add("| $(ConvertTo-ReportText -Value ([string]$detail.caseId)) | $(ConvertTo-ReportText -Value ([string]$detail.testClass)) | $(ConvertTo-ReportText -Value ([string]$detail.message)) |")
    }
  }
  $lines.Add('')
  $lines.Add('## Exchange semantics')
  $lines.Add('')
  $lines.Add('- Binance Spot REST/OCO: https://github.com/binance/binance-spot-api-docs/blob/master/rest-api.md')
  $lines.Add('- Binance Spot commission FAQ: https://developers.binance.com/en/docs/products/spot/faqs/commission_faq')
  $lines.Add('- Binance USD-M Futures API: https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api')
  $lines.Add('- OKX API v5: https://www.okx.com/docs-v5/en/')
  $lines.Add('- OKX funding FAQ: https://www.okx.com/en-us/help/funding-fees-for-perpetual-contracts-faq')
  $lines.Add('- OKX liquidation FAQ: https://www.okx.com/en-gb/help/liquidation-faq')
  $lines.Add('')

  $content = ($lines -join "`n") + "`n"
  $temporary = Join-Path $directory (
    '.spot-perp-test-report.' + [Guid]::NewGuid().ToString('N') + '.tmp')
  $encoding = New-Object System.Text.UTF8Encoding($false)
  [System.IO.File]::WriteAllText($temporary, $content, $encoding)
  Move-Item -LiteralPath $temporary -Destination $Path -Force
}

Assert-RequiredPath -Path $PlatformRoot -Label 'Platform root'
Assert-RequiredPath -Path $BackendRoot -Label 'Backend root'
Assert-RequiredPath -Path $ComposeFile -Label 'Compose file'
Assert-RequiredPath -Path $MatrixJson -Label 'Scenario matrix JSON'

$MatrixCoverage = Get-MatrixCoverage -MatrixPath $MatrixJson
Assert-MatrixCoverage -Coverage $MatrixCoverage
$DatabaseName = New-ScenarioDatabaseName
$plannedCommands = @(
  "docker compose -f `"$ComposeFile`" up -d postgres",
  'mvn -Dscenario.writeArtifacts=true -Dtest=ScenarioCatalogTest test',
  'mvn -Dtest=ScenarioCatalogTest,ScenarioDatabaseGuardTest,*ScenarioOracleTest,OracleIsolationContractTest test',
  "clear `"$ScenarioArtifactsRoot`"",
  'mvn -Dspring.profiles.active=scenario-it -Dscenario.it.enabled=true -Dtest=SpotScenarioMatrixIT,PerpetualScenarioMatrixIT,ScenarioResilienceIT test',
  "verify $ExpectedTotalCases scenario expected/actual artifact pairs",
  'mvn -Dtest=*Scenario*,*Spot*,*Perpetual*,*Protection*,*Funding*,*Liquidation* test'
)
if (-not $SkipFullSuite) {
  $plannedCommands += 'mvn test'
}

if ($DryRun) {
  $plan = [pscustomobject]@{
    dryRun = $true
    databaseName = $DatabaseName
    platformRoot = $PlatformRoot
    composeFile = $ComposeFile
    reportPath = $ReportPath
    commands = $plannedCommands
    matrixCoverage = [pscustomobject]@{
      totalCases = $MatrixCoverage.TotalCases
      spotCases = $MatrixCoverage.SpotCases
      perpetualCases = $MatrixCoverage.PerpetualCases
      pricePaths = @($MatrixCoverage.PricePaths)
    }
    reportSections = $ReportSections
  }
  $planJson = $plan | ConvertTo-Json -Depth 6 -Compress
  Write-Output ("SCENARIO_DRY_RUN_PLAN=$planJson")
  return
}

$EnvironmentSnapshot = Get-EnvironmentSnapshot -Names $EnvironmentNames
$CommandLog = New-Object 'System.Collections.Generic.List[object]'
$ContainerId = $null
$DatabaseCreated = $false
$DatabaseCreateStatus = 'NOT_STARTED'
$ScenarioSummary = Read-ScenarioSurefire `
    -ReportsRoot $SurefireRoot `
    -MatrixPath $MatrixJson `
    -IgnoreReports
$Failure = $null
$Status = 'FAILED'
$CleanupStatus = 'NOT_STARTED'

try {
  $Maven = Resolve-Maven
  Invoke-RecordedStep `
      -Name 'start Compose PostgreSQL' `
      -Command "docker compose -f `"$ComposeFile`" up -d postgres" `
      -CommandLog $CommandLog `
      -Action {
        Invoke-Docker -Arguments @(
          'compose', '-f', $ComposeFile, 'up', '-d', 'postgres')
      }
  $ContainerId = Invoke-RecordedStep `
      -Name 'resolve Compose PostgreSQL container' `
      -Command "docker compose -f `"$ComposeFile`" ps -q postgres" `
      -CommandLog $CommandLog `
      -Action {
        $resolvedContainer = Invoke-Docker -Arguments @(
          'compose', '-f', $ComposeFile, 'ps', '-q', 'postgres') -Capture
        if ([string]::IsNullOrWhiteSpace($resolvedContainer)) {
          throw 'Compose did not return a PostgreSQL container id'
        }
        return $resolvedContainer
      }
  Invoke-RecordedStep `
      -Name 'wait for PostgreSQL readiness' `
      -Command "docker exec $ContainerId pg_isready -U postgres -d postgres" `
      -CommandLog $CommandLog `
      -Action {
        Wait-PostgresReady -ContainerId $ContainerId
      }
  try {
    Invoke-RecordedStep `
        -Name 'create owned scenario database' `
        -Command "docker exec $ContainerId psql -U postgres -d postgres -c `"CREATE DATABASE $DatabaseName`"" `
        -CommandLog $CommandLog `
        -Action {
          New-OwnedDatabase `
              -ContainerId $ContainerId `
              -DatabaseName $DatabaseName `
              -DatabaseCreated ([ref]$script:DatabaseCreated)
        }
    $DatabaseCreateStatus = 'CREATED_AND_CONFIRMED'
  } catch {
    $DatabaseCreateStatus = 'FAILED: ' + $_.Exception.Message
    throw
  }

  $databaseUrl = "jdbc:postgresql://localhost:5432/$DatabaseName"
  Set-ProcessEnvironment -Name 'SCENARIO_DATABASE_NAME' -Value $DatabaseName
  Set-ProcessEnvironment -Name 'SCENARIO_DATABASE_URL' -Value $databaseUrl
  Set-ProcessEnvironment -Name 'SCENARIO_DATABASE_USERNAME' -Value 'postgres'
  Set-ProcessEnvironment -Name 'SCENARIO_DATABASE_PASSWORD' -Value 'password'
  Set-ProcessEnvironment -Name 'DATABASE_URL' -Value $databaseUrl
  Set-ProcessEnvironment -Name 'DATABASE_USERNAME' -Value 'postgres'
  Set-ProcessEnvironment -Name 'DATABASE_PASSWORD' -Value 'password'
  Set-ProcessEnvironment -Name 'SPRING_DATASOURCE_URL' -Value $databaseUrl
  Set-ProcessEnvironment -Name 'SPRING_DATASOURCE_USERNAME' -Value 'postgres'
  Set-ProcessEnvironment -Name 'SPRING_DATASOURCE_PASSWORD' -Value 'password'
  # Keep every non-matrix Maven phase on the default profile. The scenario matrix
  # selects scenario-it explicitly on its own command line below.
  Set-ProcessEnvironment -Name 'SPRING_PROFILES_ACTIVE' -Value $null

  Invoke-MavenPhase `
      -Maven $Maven `
      -Name 'matrix artifact update' `
      -Arguments @('-Dscenario.writeArtifacts=true', '-Dtest=ScenarioCatalogTest', 'test') `
      -CommandLog $CommandLog
  $MatrixCoverage = Get-MatrixCoverage -MatrixPath $MatrixJson
  Assert-MatrixCoverage -Coverage $MatrixCoverage
  Invoke-MavenPhase `
      -Maven $Maven `
      -Name 'catalog, database guard and independent Oracle tests' `
      -Arguments @(
        '-Dtest=ScenarioCatalogTest,ScenarioDatabaseGuardTest,*ScenarioOracleTest,OracleIsolationContractTest',
        'test') `
      -CommandLog $CommandLog
  Clear-ScenarioSurefireReports `
      -ReportsRoot $SurefireRoot `
      -MatrixPath $MatrixJson
  Invoke-RecordedStep `
      -Name 'clear stale scenario expected/actual evidence' `
      -Command "clear `"$ScenarioArtifactsRoot`"" `
      -CommandLog $CommandLog `
      -Action {
        Clear-ScenarioArtifacts `
            -ArtifactsRoot $ScenarioArtifactsRoot `
            -ExpectedBackendRoot $BackendRoot
      }
  try {
    Invoke-MavenPhase `
        -Maven $Maven `
        -Name 'Spot, Perpetual and resilience scenario matrix' `
        -Arguments @(
          '-Dspring.profiles.active=scenario-it',
          '-Dscenario.it.enabled=true',
          '-Dtest=SpotScenarioMatrixIT,PerpetualScenarioMatrixIT,ScenarioResilienceIT',
          'test') `
        -CommandLog $CommandLog
  } catch {
    $ScenarioSummary = Read-ScenarioSurefire `
        -ReportsRoot $SurefireRoot `
        -MatrixPath $MatrixJson
    throw
  }

  $ScenarioSummary = Read-ScenarioSurefire `
      -ReportsRoot $SurefireRoot `
      -MatrixPath $MatrixJson
  Assert-ScenarioSurefireSummary -Summary $ScenarioSummary
  Invoke-RecordedStep `
      -Name 'verify per-case expected/actual evidence' `
      -Command "verify $ExpectedTotalCases scenario expected/actual artifact pairs" `
      -CommandLog $CommandLog `
      -Action {
        $artifactCount = Assert-ScenarioArtifacts `
            -ArtifactsRoot $ScenarioArtifactsRoot `
            -MatrixPath $MatrixJson
        if ($artifactCount -ne $ExpectedTotalCases) {
          throw "Scenario artifact total gate failed: $artifactCount/$ExpectedTotalCases"
        }
      }

  Invoke-MavenPhase `
      -Maven $Maven `
      -Name 'focused Spot, Perpetual, protection, funding and liquidation suite' `
      -Arguments @(
        '-Dtest=*Scenario*,*Spot*,*Perpetual*,*Protection*,*Funding*,*Liquidation*',
        'test') `
      -CommandLog $CommandLog
  if (-not $SkipFullSuite) {
    Invoke-MavenPhase `
        -Maven $Maven `
        -Name 'full backend suite' `
        -Arguments @('test') `
        -CommandLog $CommandLog
  }
  $Status = 'PASSED'
} catch {
  $Failure = $_
  $Status = 'FAILED'
} finally {
  if ($DatabaseCreated -and -not [string]::IsNullOrWhiteSpace($ContainerId)) {
    try {
      Invoke-RecordedStep `
          -Name 'drop owned scenario database' `
          -Command "docker exec $ContainerId psql -U postgres -d postgres -c `"DROP DATABASE $DatabaseName WITH (FORCE)`"" `
          -CommandLog $CommandLog `
          -Action {
            Remove-OwnedDatabase `
                -ContainerId $ContainerId `
                -DatabaseName $DatabaseName
          }
      $CleanupStatus = 'DROPPED_AND_CONFIRMED'
    } catch {
      $CleanupStatus = 'FAILED: ' + $_.Exception.Message
      if ($null -eq $Failure) {
        $Failure = $_
        $Status = 'FAILED'
      }
    }
  } else {
    $CleanupStatus = 'NOT_CREATED'
  }

  Restore-Environment -Snapshot $EnvironmentSnapshot
  $failureMessage = $null
  if ($null -ne $Failure) {
    $failureMessage = $Failure.Exception.Message
  }
  try {
    $MatrixCoverage = Get-MatrixCoverage -MatrixPath $MatrixJson
  } catch {
    if ($null -eq $Failure) {
      $Failure = $_
      $Status = 'FAILED'
      $failureMessage = $_.Exception.Message
    }
  }
  Write-AtomicReport `
      -Path $ReportPath `
      -Status $Status `
      -DatabaseName $DatabaseName `
      -CommandLog $CommandLog `
      -ScenarioSummary $ScenarioSummary `
      -MatrixCoverage $MatrixCoverage `
      -FixedIssues $FixedIssue `
      -UnresolvedIssues $UnresolvedIssue `
      -KeyFiles $KeyFiles `
      -FailureMessage $failureMessage `
      -DatabaseCreateStatus $DatabaseCreateStatus `
      -CleanupStatus $CleanupStatus
}

if ($null -ne $Failure) {
  throw $Failure
}

Write-Host "Scenario matrix completed: $($ScenarioSummary.MatrixCases) cases"
