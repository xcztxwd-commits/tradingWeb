[CmdletBinding()]
param(
  [switch]$ContractOnly,
  [switch]$SkipBrowser,
  [switch]$KeepValidationRunning,
  [string]$ArtifactsDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:TradingLabScriptsRoot = Split-Path -Parent $PSCommandPath
$script:TradingLabPlatformRoot = [System.IO.Path]::GetFullPath(
  (Join-Path $script:TradingLabScriptsRoot '..')
)
$script:TradingLabRepositoryRoot = [System.IO.Path]::GetFullPath(
  (Join-Path $script:TradingLabPlatformRoot '..')
)
$script:TradingLabComposeFile = [System.IO.Path]::GetFullPath(
  (Join-Path $script:TradingLabPlatformRoot 'infra\docker-compose.validation.yml')
)
$script:TradingLabComposeProject = 'fx-trading-validation'
$script:TradingLabValidationNetwork = 'fx-trading-validation-internal'
$script:TradingLabArtifactsRoot = [System.IO.Path]::GetFullPath(
  (Join-Path $script:TradingLabPlatformRoot '.run-logs\trading-lab')
)
$script:TradingLabArtifactMarker = '.trading-lab-owner.json'
$script:TradingLabMainBackendPort = 18086
$script:TradingLabValidationRelayPort = 18087
$script:TradingLabSupervisorPort = 18088
$script:TradingLabAdminPort = 5174
$script:TradingLabSupervisorHealthDeadlineSeconds = 300
$script:TradingLabSupervisorHealthInitialBackoffMilliseconds = 500
$script:TradingLabSupervisorHealthMaximumBackoffMilliseconds = 5000
$script:TradingLabSupervisorErrorBodyLimitBytes = 4096
$script:TradingLabMainQueueIdleDeadlineSeconds = 1800
$script:TradingLabMainQueueIdlePollMilliseconds = 1000
$script:TradingLabOwnedValidationSecretSentinel = (
  '__TRADING_LAB_OWNED_VALIDATION_SECRET__'
)
$script:TradingLabMavenOpts = (
  '-Xms64m -Xmx512m -XX:MaxMetaspaceSize=192m ' +
  '-XX:ReservedCodeCacheSize=96m -XX:+UseSerialGC'
)
$script:TradingLabOwnedProcessRegistry = (
  [System.Collections.Generic.Dictionary[string, object]]::new(
    [System.StringComparer]::OrdinalIgnoreCase
  )
)
$script:TradingLabOwnedRunSecretRegistry = (
  [System.Collections.Generic.Dictionary[string, object]]::new(
    [System.StringComparer]::Ordinal
  )
)

function Get-TradingLabValidationContract {
  [CmdletBinding()]
  param()

  return [pscustomobject]@{
    ComposeFile = $script:TradingLabComposeFile
    ComposeProject = $script:TradingLabComposeProject
    ArtifactsRoot = $script:TradingLabArtifactsRoot
    ArtifactMarker = $script:TradingLabArtifactMarker
    MainBackendPort = $script:TradingLabMainBackendPort
    ValidationRelayPort = $script:TradingLabValidationRelayPort
    SupervisorPort = $script:TradingLabSupervisorPort
    SupervisorHealthDeadlineSeconds = (
      $script:TradingLabSupervisorHealthDeadlineSeconds
    )
    SupervisorHealthInitialBackoffMilliseconds = (
      $script:TradingLabSupervisorHealthInitialBackoffMilliseconds
    )
    SupervisorHealthMaximumBackoffMilliseconds = (
      $script:TradingLabSupervisorHealthMaximumBackoffMilliseconds
    )
    MainQueueIdleDeadlineSeconds = (
      $script:TradingLabMainQueueIdleDeadlineSeconds
    )
    MainQueueIdlePollMilliseconds = (
      $script:TradingLabMainQueueIdlePollMilliseconds
    )
  }
}

function Get-TradingLabSupervisorActionOrder {
  [CmdletBinding()]
  param()

  return @('start', 'status', 'health')
}

function Get-TradingLabNormalPhasePlan {
  [CmdletBinding()]
  param()

  $names = @(
    'preflight-and-dirty-tree-snapshot',
    'backend-unit-tests-before-docker',
    'build-validation-image',
    'start-validation-compose',
    'start-supervisor',
    'start-or-verify-main-backend-and-admin',
    'explicit-validation-http-integration-tests',
    'admin-tests-and-build',
    'web-tests-and-build',
    'architecture-verification',
    'browser-smoke',
    'large-report-and-isolation-checks',
    'required-full-backend-tests',
    'report-generation',
    'cleanup-and-stop'
  )
  for ($index = 0; $index -lt $names.Count; $index++) {
    [pscustomobject]@{
      Number = $index + 1
      Name = $names[$index]
    }
  }
}

function Test-TradingLabPackageAlias {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$PackageScripts,

    [Parameter(Mandatory = $true)]
    [string]$Alias
  )

  if ($PackageScripts -is [System.Collections.IDictionary]) {
    if (-not $PackageScripts.Contains($Alias)) {
      return $false
    }
    return -not [string]::IsNullOrWhiteSpace(
      [string]$PackageScripts[$Alias]
    )
  }
  $property = $PackageScripts.PSObject.Properties[$Alias]
  return (
    $null -ne $property -and
    -not [string]::IsNullOrWhiteSpace([string]$property.Value)
  )
}

function New-TradingLabNormalCommandSpec {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [int]$PhaseNumber,

    [Parameter(Mandatory = $true)]
    [string]$PhaseName,

    [Parameter(Mandatory = $true)]
    [string]$Id,

    [Parameter(Mandatory = $true)]
    [string]$Operation,

    [Parameter(Mandatory = $true)]
    [string]$Executable,

    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [string[]]$Arguments,

    [Parameter(Mandatory = $true)]
    [string]$WorkingDirectory,

    [string]$RequiredAlias,

    [ValidateSet('READY', 'BLOCKED')]
    [string]$Availability = 'READY',

    [string]$UnavailableReason,

    [string]$SourcePath,

    [string]$DestinationPath,

    [AllowNull()]
    [object]$Environment,

    [string]$HealthUri,

    [AllowNull()]
    [string[]]$RequiredTestClasses = @()
  )

  return [pscustomobject][ordered]@{
    PhaseNumber = $PhaseNumber
    PhaseName = $PhaseName
    Id = $Id
    Operation = $Operation
    Executable = $Executable
    Arguments = @($Arguments)
    WorkingDirectory = $WorkingDirectory
    RequiredAlias = if (
      [string]::IsNullOrWhiteSpace($RequiredAlias)
    ) { $null } else { $RequiredAlias }
    Availability = $Availability
    UnavailableReason = if (
      [string]::IsNullOrWhiteSpace($UnavailableReason)
    ) { $null } else { $UnavailableReason }
    SourcePath = if (
      [string]::IsNullOrWhiteSpace($SourcePath)
    ) { $null } else { $SourcePath }
    DestinationPath = if (
      [string]::IsNullOrWhiteSpace($DestinationPath)
    ) { $null } else { $DestinationPath }
    Environment = if ($null -eq $Environment) {
      [pscustomobject]@{}
    } else {
      $Environment
    }
    HealthUri = if (
      [string]::IsNullOrWhiteSpace($HealthUri)
    ) { $null } else { $HealthUri }
    RequiredTestClasses = @($RequiredTestClasses)
    Payload = $null
  }
}

function New-TradingLabRunSecretMarker {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Z][A-Z0-9_]+$')]
    [string]$Name
  )

  return "__TRADING_LAB_RUN_SECRET:${Name}__"
}

function New-TradingLabRunAccountMarker {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Z][A-Z0-9_]+$')]
    [string]$Name
  )

  return "__TRADING_LAB_RUN_ACCOUNT:${Name}__"
}

function New-TradingLabValidationComposeEnvironment {
  [CmdletBinding()]
  param()

  $environment = [ordered]@{}
  foreach ($name in @(
    'VALIDATION_DATABASE_PASSWORD',
    'VALIDATION_REDIS_PASSWORD',
    'VALIDATION_JWT_SECRET',
    'VALIDATION_CONFIG_ENCRYPTION_KEY',
    'VALIDATION_INTERNAL_SECRET'
  )) {
    $environment[$name] = New-TradingLabRunSecretMarker $name
  }
  return [pscustomobject]$environment
}

function New-TradingLabSupervisorEnvironment {
  [CmdletBinding()]
  param()

  $environment = [ordered]@{
    SUPERVISOR_INTERNAL_TOKEN = (
      New-TradingLabRunSecretMarker 'SUPERVISOR_INTERNAL_TOKEN'
    )
  }
  foreach (
    $property in (New-TradingLabValidationComposeEnvironment).PSObject.Properties
  ) {
    $environment[$property.Name] = $property.Value
  }
  return [pscustomobject]$environment
}

function New-TradingLabBrowserSmokeEnvironment {
  [CmdletBinding()]
  param()

  $environment = [ordered]@{}
  foreach ($name in @(
    'SUPERVISOR_INTERNAL_TOKEN',
    'VALIDATION_DATABASE_PASSWORD',
    'VALIDATION_REDIS_PASSWORD',
    'VALIDATION_JWT_SECRET',
    'VALIDATION_CONFIG_ENCRYPTION_KEY',
    'VALIDATION_INTERNAL_SECRET',
    'JWT_SECRET',
    'CONFIG_ENCRYPTION_KEY',
    'ADMIN_BOOTSTRAP_PASSWORD'
  )) {
    $environment[$name] = New-TradingLabRunSecretMarker $name
  }
  foreach ($name in @(
    'SUPER_EMAIL',
    'SUPER_PASSWORD',
    'VIEW_EMAIL',
    'VIEW_PASSWORD',
    'EXECUTE_EMAIL',
    'EXECUTE_PASSWORD',
    'ORDINARY_EMAIL',
    'ORDINARY_PASSWORD',
    'OWNED_AUTH_USER_IDS'
  )) {
    $environment["TRADING_LAB_SMOKE_$name"] = (
      New-TradingLabRunAccountMarker $name
    )
  }
  return [pscustomobject]$environment
}

function Register-TradingLabOwnedRunSecrets {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$RunToken
  )

  Assert-TradingLabRunToken -RunToken $RunToken
  if ($script:TradingLabOwnedRunSecretRegistry.ContainsKey($RunToken)) {
    throw 'Trading Lab run secret registry collision'
  }
  $secrets = [ordered]@{}
  foreach ($name in @(
    'VALIDATION_DATABASE_PASSWORD',
    'VALIDATION_REDIS_PASSWORD',
    'VALIDATION_JWT_SECRET',
    'VALIDATION_CONFIG_ENCRYPTION_KEY',
    'VALIDATION_INTERNAL_SECRET',
    'SUPERVISOR_INTERNAL_TOKEN',
    'JWT_SECRET',
    'CONFIG_ENCRYPTION_KEY'
  )) {
    $secrets[$name] = New-TradingLabSecureRunToken
  }
  $secrets['ADMIN_BOOTSTRAP_PASSWORD'] = (
    (New-TradingLabSecureRunToken) + 'Aa1!'
  )
  foreach ($name in @(
    'VIEW_PASSWORD',
    'EXECUTE_PASSWORD',
    'ORDINARY_PASSWORD'
  )) {
    $secrets[$name] = (New-TradingLabSecureRunToken) + 'Aa1!'
  }
  $superSuffix = [guid]::NewGuid().ToString('N').ToLowerInvariant()
  $viewSuffix = [guid]::NewGuid().ToString('N').ToLowerInvariant()
  $executeSuffix = [guid]::NewGuid().ToString('N').ToLowerInvariant()
  $ordinarySuffix = [guid]::NewGuid().ToString('N').ToLowerInvariant()
  $roleSuffix = [guid]::NewGuid().ToString('N').ToLowerInvariant()
  $viewUserId = [guid]::NewGuid().ToString().ToLowerInvariant()
  $executeUserId = [guid]::NewGuid().ToString().ToLowerInvariant()
  $ordinaryUserId = [guid]::NewGuid().ToString().ToLowerInvariant()
  $accounts = [pscustomobject][ordered]@{
    SuperEmail = "trading-lab-$superSuffix-super@local.invalid"
    ViewEmail = "trading-lab-$viewSuffix-view@local.invalid"
    ExecuteEmail = "trading-lab-$executeSuffix-execute@local.invalid"
    OrdinaryEmail = "trading-lab-$ordinarySuffix-ordinary@local.invalid"
    SuperUserId = $null
    ViewUserId = $viewUserId
    ExecuteUserId = $executeUserId
    OrdinaryUserId = $ordinaryUserId
    ViewRoleId = [guid]::NewGuid().ToString().ToLowerInvariant()
    ExecuteRoleId = [guid]::NewGuid().ToString().ToLowerInvariant()
    ViewPermissionId = [guid]::NewGuid().ToString().ToLowerInvariant()
    ExecutePermissionId = [guid]::NewGuid().ToString().ToLowerInvariant()
    ViewBindingId = [guid]::NewGuid().ToString().ToLowerInvariant()
    ExecuteBindingId = [guid]::NewGuid().ToString().ToLowerInvariant()
    RoleSuffix = $roleSuffix
    Provisioned = $false
    Cleaned = $false
  }
  $accounts | Add-Member -MemberType NoteProperty -Name Environment -Value (
    [ordered]@{
      SUPER_EMAIL = $accounts.SuperEmail
      SUPER_PASSWORD = [string]$secrets['ADMIN_BOOTSTRAP_PASSWORD']
      VIEW_EMAIL = $accounts.ViewEmail
      VIEW_PASSWORD = [string]$secrets['VIEW_PASSWORD']
      EXECUTE_EMAIL = $accounts.ExecuteEmail
      EXECUTE_PASSWORD = [string]$secrets['EXECUTE_PASSWORD']
      ORDINARY_EMAIL = $accounts.OrdinaryEmail
      ORDINARY_PASSWORD = [string]$secrets['ORDINARY_PASSWORD']
      OWNED_AUTH_USER_IDS = ''
    }
  )
  $bundle = [pscustomobject]@{
    Secrets = $secrets
    Accounts = $accounts
  }
  $script:TradingLabOwnedRunSecretRegistry.Add($RunToken, $bundle)
  return $bundle
}

function Get-TradingLabOwnedRunSecrets {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$RunToken
  )

  Assert-TradingLabRunToken -RunToken $RunToken
  if (-not $script:TradingLabOwnedRunSecretRegistry.ContainsKey($RunToken)) {
    throw 'Trading Lab run secret registry entry is missing'
  }
  return $script:TradingLabOwnedRunSecretRegistry[$RunToken]
}

function Resolve-TradingLabRunEnvironment {
  [CmdletBinding()]
  param(
    [AllowNull()]
    [object]$Environment,

    [Parameter(Mandatory = $true)]
    [string]$RunToken
  )

  if ($null -eq $Environment) {
    return [pscustomobject]@{}
  }
  $bundle = Get-TradingLabOwnedRunSecrets -RunToken $RunToken
  $resolved = [ordered]@{}
  foreach ($property in $Environment.PSObject.Properties) {
    $value = [string]$property.Value
    if ($value -match '^__TRADING_LAB_RUN_SECRET:([A-Z][A-Z0-9_]+)__$') {
      $name = $Matches[1]
      if (-not $bundle.Secrets.Contains($name)) {
        throw 'Trading Lab run secret marker is unknown'
      }
      $resolved[$property.Name] = [string]$bundle.Secrets[$name]
    } elseif (
      $value -match '^__TRADING_LAB_RUN_ACCOUNT:([A-Z][A-Z0-9_]+)__$'
    ) {
      $name = $Matches[1]
      if (-not $bundle.Accounts.Environment.Contains($name)) {
        throw 'Trading Lab run account marker is unknown'
      }
      if ($name -ceq 'OWNED_AUTH_USER_IDS') {
        if (
          -not [bool]$bundle.Accounts.Provisioned -or
          [string]$bundle.Accounts.SuperUserId -notmatch '^[0-9a-f-]{36}$'
        ) {
          throw 'Trading Lab run account ownership is not provisioned'
        }
        $resolved[$property.Name] = @(
          $bundle.Accounts.SuperUserId,
          $bundle.Accounts.ViewUserId,
          $bundle.Accounts.ExecuteUserId,
          $bundle.Accounts.OrdinaryUserId
        ) -join ','
      } else {
        $resolved[$property.Name] = [string]$bundle.Accounts.Environment[$name]
      }
    } else {
      $resolved[$property.Name] = $value
    }
  }
  return [pscustomobject]$resolved
}

function New-TradingLabDefaultRunContext {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$ArtifactsDirectory,

    [Parameter(Mandatory = $true)]
    [string]$RunToken,

    [Parameter(Mandatory = $true)]
    [string]$CandidateId,

    [switch]$KeepValidationRunning
  )

  Assert-TradingLabRunToken -RunToken $RunToken
  if ($CandidateId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{7,80}$') {
    throw 'Trading Lab candidate ID is invalid'
  }
  $artifacts = [System.IO.Path]::GetFullPath($ArtifactsDirectory)
  $candidateParent = Get-TradingLabCandidateBasePath
  $candidateRoot = [System.IO.Path]::GetFullPath(
    (Join-Path (Join-Path $candidateParent $CandidateId) 'fx-trading-platform')
  )
  $runtimeRoot = Join-Path $artifacts 'runtime'
  Register-TradingLabOwnedRunSecrets -RunToken $RunToken | Out-Null
  return [pscustomobject][ordered]@{
    CandidatePlatformRoot = $candidateRoot
    ArtifactsDirectory = $artifacts
    OwnedBackendJarPath = Join-Path `
      $runtimeRoot `
      'fx-platform-backend-0.1.0.jar'
    RunToken = $RunToken
    KeepValidationRunning = [bool]$KeepValidationRunning
    FixedStackGuardPath = Join-Path `
      $script:TradingLabArtifactsRoot `
      '.fixed-stack-owner-lease.json'
    FixedStackLeasePath = Join-Path $artifacts 'fixed-stack-lease.json'
    SupervisorReceiptPath = Join-Path `
      $artifacts `
      'supervisor-process-receipt.json'
    MainBackendReceiptPath = Join-Path `
      $artifacts `
      'main-backend-process-receipt.json'
    AdminReceiptPath = Join-Path $artifacts 'admin-process-receipt.json'
    CleanupReceiptPath = Join-Path $artifacts 'cleanup-receipt.json'
    ProvisionalReportPath = Join-Path `
      $artifacts `
      'trading-lab-verification-report.provisional.md'
    FinalReportPath = Join-Path `
      $artifacts `
      'trading-lab-verification-report.md'
  }
}

function Get-TradingLabNormalPhaseSpecs {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$Context,

    [Parameter(Mandatory = $true)]
    [object]$PackageScripts
  )

  foreach ($requiredProperty in @(
    'CandidatePlatformRoot',
    'ArtifactsDirectory',
    'OwnedBackendJarPath',
    'RunToken'
  )) {
    $property = $Context.PSObject.Properties[$requiredProperty]
    if (
      $null -eq $property -or
      [string]::IsNullOrWhiteSpace([string]$property.Value)
    ) {
      throw "Trading Lab normal run context is missing $requiredProperty"
    }
  }
  Assert-TradingLabRunToken -RunToken ([string]$Context.RunToken)

  $candidateRoot = [System.IO.Path]::GetFullPath(
    [string]$Context.CandidatePlatformRoot
  )
  $artifacts = [System.IO.Path]::GetFullPath(
    [string]$Context.ArtifactsDirectory
  )
  $ownedBackendJar = [System.IO.Path]::GetFullPath(
    [string]$Context.OwnedBackendJarPath
  )
  $fixedStackGuardPath = Join-Path `
    $script:TradingLabArtifactsRoot `
    '.fixed-stack-owner-lease.json'
  $candidateBackend = Join-Path $candidateRoot 'backend'
  $phasePlan = @(Get-TradingLabNormalPhasePlan)
  $commandsByPhase = @{}
  foreach ($phase in $phasePlan) {
    $commandsByPhase[$phase.Number] = [System.Collections.Generic.List[object]]::new()
  }

  foreach ($preflight in @(Get-TradingLabReadOnlyPreflightSpecs)) {
    $commandsByPhase[1].Add(
      (New-TradingLabNormalCommandSpec `
        -PhaseNumber 1 `
        -PhaseName $phasePlan[0].Name `
        -Id ("preflight-" + $preflight.Name) `
        -Operation 'COMMAND' `
        -Executable $preflight.Executable `
        -Arguments @($preflight.Arguments) `
      -WorkingDirectory $script:TradingLabRepositoryRoot)
    )
  }
  $commandsByPhase[1].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 1 `
      -PhaseName $phasePlan[0].Name `
      -Id 'assert-runner-ports-free' `
      -Operation 'ASSERT_PORTS_FREE' `
      -Executable '[internal]' `
      -Arguments @(
        '8080',
        [string]$script:TradingLabMainBackendPort,
        [string]$script:TradingLabValidationRelayPort,
        [string]$script:TradingLabSupervisorPort,
        [string]$script:TradingLabAdminPort
      ) `
      -WorkingDirectory $artifacts)
  )
  $commandsByPhase[1].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 1 `
      -PhaseName $phasePlan[0].Name `
      -Id 'preflight-main-compose-status' `
      -Operation 'COMMAND' `
      -Executable 'docker' `
      -Arguments @(
        'compose',
        '-f',
        (Join-Path $script:TradingLabPlatformRoot 'infra\docker-compose.yml'),
        'ps',
        '--format',
        'json',
        '--all'
      ) `
      -WorkingDirectory $script:TradingLabPlatformRoot)
  )
  $commandsByPhase[1].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 1 `
      -PhaseName $phasePlan[0].Name `
      -Id 'assert-main-data-services-ready' `
      -Operation 'ASSERT_MAIN_DATA_SERVICES' `
      -Executable 'docker' `
      -Arguments @() `
      -WorkingDirectory $script:TradingLabPlatformRoot)
  )
  $commandsByPhase[1].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 1 `
      -PhaseName $phasePlan[0].Name `
      -Id 'preflight-validation-compose-status' `
      -Operation 'CAPTURE_DOCKER_PS_JSON' `
      -Executable 'docker' `
      -Arguments @(
        'ps',
        '--all',
        '--no-trunc',
        '--filter',
        "label=com.docker.compose.project=$script:TradingLabComposeProject",
        '--format',
        '{{json .}}'
      ) `
      -WorkingDirectory $script:TradingLabPlatformRoot `
      -Environment (New-TradingLabValidationComposeEnvironment))
  )
  $commandsByPhase[1].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 1 `
      -PhaseName $phasePlan[0].Name `
      -Id 'create-isolated-platform-candidate' `
      -Operation 'CREATE_ISOLATED_CANDIDATE' `
      -Executable '[internal]' `
      -Arguments @($script:TradingLabPlatformRoot, $candidateRoot) `
      -WorkingDirectory $artifacts `
      -SourcePath $script:TradingLabPlatformRoot `
      -DestinationPath $candidateRoot)
  )

  $commandsByPhase[2].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 2 `
      -PhaseName $phasePlan[1].Name `
      -Id 'backend-pre-docker-tests' `
      -Operation 'MAVEN_TEST' `
      -Executable 'mvn' `
      -Arguments @(
        'clean',
        '-Dtest=TradingLabReportCanonicalizerTest,TradingLabReportChunkCodecTest,TradingLabAuthorizationTest',
        'test'
      ) `
      -WorkingDirectory $candidateBackend `
      -DestinationPath (Join-Path `
        (Join-Path $artifacts 'surefire') `
        'backend-pre-docker-tests') `
      -Environment ([pscustomobject]@{
        MAVEN_OPTS = $script:TradingLabMavenOpts
      }) `
      -RequiredTestClasses @(
        'com.fxplatform.tradinglab.report.TradingLabReportCanonicalizerTest',
        'com.fxplatform.tradinglab.report.TradingLabReportChunkCodecTest',
        'com.fxplatform.tradinglab.security.TradingLabAuthorizationTest'
      ))
  )
  $commandsByPhase[2].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 2 `
      -PhaseName $phasePlan[1].Name `
      -Id 'backend-boot-jar-package' `
      -Operation 'COMMAND' `
      -Executable 'mvn' `
      -Arguments @('clean', '-DskipTests', 'package') `
      -WorkingDirectory $candidateBackend `
      -Environment ([pscustomobject]@{
        MAVEN_OPTS = $script:TradingLabMavenOpts
      }))
  )
  $candidateJar = Join-Path `
    $candidateBackend `
    'target\fx-platform-backend-0.1.0.jar'
  $commandsByPhase[2].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 2 `
      -PhaseName $phasePlan[1].Name `
      -Id 'copy-owned-backend-jar' `
      -Operation 'COPY_OWNED_BOOT_JAR' `
      -Executable '[internal]' `
      -Arguments @($candidateJar, $ownedBackendJar) `
      -WorkingDirectory $artifacts `
      -SourcePath $candidateJar `
      -DestinationPath $ownedBackendJar)
  )

  $commandsByPhase[3].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 3 `
      -PhaseName $phasePlan[2].Name `
      -Id 'acquire-fixed-stack-guard' `
      -Operation 'ACQUIRE_FIXED_STACK_GUARD' `
      -Executable '[internal]' `
      -Arguments @('preflight-validation-compose-status') `
      -WorkingDirectory $artifacts `
      -DestinationPath $fixedStackGuardPath)
  )
  $commandsByPhase[3].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 3 `
      -PhaseName $phasePlan[2].Name `
      -Id 'assert-fixed-stack-baseline-current-after-guard' `
      -Operation 'ASSERT_FIXED_STACK_BASELINE_CURRENT' `
      -Executable '[internal]' `
      -Arguments @() `
      -WorkingDirectory $artifacts)
  )
  $commandsByPhase[3].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 3 `
      -PhaseName $phasePlan[2].Name `
      -Id 'build-validation-image' `
      -Operation 'COMMAND' `
      -Executable 'docker' `
      -Arguments @(
        'compose',
        '-p',
        $script:TradingLabComposeProject,
        '-f',
        $script:TradingLabComposeFile,
        'build',
        'validation-backend'
      ) `
      -WorkingDirectory $script:TradingLabPlatformRoot `
      -Environment (New-TradingLabValidationComposeEnvironment))
  )
  $commandsByPhase[4].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 4 `
      -PhaseName $phasePlan[3].Name `
      -Id 'assert-fixed-stack-baseline-current-before-up' `
      -Operation 'ASSERT_FIXED_STACK_BASELINE_CURRENT' `
      -Executable '[internal]' `
      -Arguments @() `
      -WorkingDirectory $artifacts)
  )
  $commandsByPhase[4].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 4 `
      -PhaseName $phasePlan[3].Name `
      -Id 'start-validation-compose' `
      -Operation 'COMMAND' `
      -Executable 'docker' `
      -Arguments @(
        'compose',
        '-p',
        $script:TradingLabComposeProject,
        '-f',
        $script:TradingLabComposeFile,
        'up',
        '-d'
      ) `
      -WorkingDirectory $script:TradingLabPlatformRoot `
      -Environment (New-TradingLabValidationComposeEnvironment))
  )
  $commandsByPhase[4].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 4 `
      -PhaseName $phasePlan[3].Name `
      -Id 'capture-fixed-stack-lease' `
      -Operation 'CAPTURE_FIXED_STACK_LEASE' `
      -Executable 'docker' `
      -Arguments @(
        'ps',
        '--all',
        '--no-trunc',
        '--filter',
        "label=com.docker.compose.project=$script:TradingLabComposeProject",
        '--format',
        '{{json .}}'
      ) `
      -WorkingDirectory $script:TradingLabPlatformRoot `
      -DestinationPath (Join-Path $artifacts 'fixed-stack-lease.json'))
  )
  $commandsByPhase[4].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 4 `
      -PhaseName $phasePlan[3].Name `
      -Id 'reconcile-validation-postgres-credential' `
      -Operation 'RECONCILE_VALIDATION_POSTGRES_CREDENTIAL' `
      -Executable '[internal]' `
      -Arguments @() `
      -WorkingDirectory $script:TradingLabPlatformRoot)
  )

  $commandsByPhase[5].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 5 `
      -PhaseName $phasePlan[4].Name `
      -Id 'start-supervisor-owned-process' `
      -Operation 'START_OWNED_PROCESS' `
      -Executable 'node' `
      -Arguments @('scripts/validation-supervisor.mjs') `
      -WorkingDirectory $script:TradingLabPlatformRoot `
      -DestinationPath (Join-Path $artifacts 'supervisor-process-receipt.json') `
      -Environment (New-TradingLabSupervisorEnvironment) `
      -HealthUri "http://127.0.0.1:$script:TradingLabSupervisorPort/validation-supervisor")
  )
  foreach ($action in @(Get-TradingLabSupervisorActionOrder)) {
    $commandsByPhase[5].Add(
      (New-TradingLabNormalCommandSpec `
        -PhaseNumber 5 `
        -PhaseName $phasePlan[4].Name `
        -Id "supervisor-$action" `
        -Operation 'SUPERVISOR_ACTION' `
        -Executable '[internal]' `
        -Arguments @($action) `
        -WorkingDirectory $artifacts)
    )
  }
  $commandsByPhase[5].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 5 `
      -PhaseName $phasePlan[4].Name `
      -Id 'verify-fixed-stack-lease-after-supervisor' `
      -Operation 'ASSERT_FIXED_STACK_LEASE_CURRENT' `
      -Executable '[internal]' `
      -Arguments @() `
      -WorkingDirectory $artifacts)
  )

  $commandsByPhase[6].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 6 `
      -PhaseName $phasePlan[5].Name `
      -Id 'recheck-main-process-ports-free' `
      -Operation 'ASSERT_PORTS_FREE' `
      -Executable '[internal]' `
      -Arguments @(
        '8080',
        [string]$script:TradingLabMainBackendPort,
        [string]$script:TradingLabAdminPort
      ) `
      -WorkingDirectory $artifacts)
  )
  $commandsByPhase[6].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 6 `
      -PhaseName $phasePlan[5].Name `
      -Id 'start-or-verify-owned-main-backend' `
      -Operation 'START_OR_VERIFY_OWNED_PROCESS' `
      -Executable 'java' `
      -Arguments @(
        '-jar',
        $ownedBackendJar,
        '--spring.profiles.active=dev',
        "--server.port=$($script:TradingLabMainBackendPort)"
      ) `
      -WorkingDirectory $artifacts `
      -DestinationPath (Join-Path $artifacts 'main-backend-process-receipt.json') `
      -Environment ([pscustomobject]@{
        EXECUTION_MODE = 'demo'
        ENGAGEMENT_OUTBOX_ENABLED = 'false'
        ENGAGEMENT_RETENTION_ENABLED = 'false'
        ENGAGEMENT_SCHEDULER_ENABLED = 'false'
        HOME_COUNTERS_GROWTH_ENABLED = 'false'
        MARKET_DEMO_QUOTES_ENABLED = 'false'
        MARKET_REALTIME_ENABLED = 'false'
        MARKET_REALTIME_BACKFILL_ENABLED = 'false'
        MARKET_REALTIME_DYNAMIC_SYMBOLS_ENABLED = 'false'
        MARKET_PROVIDER_INSTRUMENT_SYNC_ENABLED = 'false'
        MARKET_QUOTE_BROADCAST_ENABLED = 'false'
        MARKET_TEST_DATA_ENABLED = 'false'
        MARKET_TEST_CONTROL_ENABLED = 'false'
        MARKET_WRITE_QUOTES_TO_DB = 'false'
        MASSIVE_WRITE_QUOTES_TO_DB = 'false'
        TRADING_PENDING_ORDER_EXECUTION_ENABLED = 'false'
        TRADING_PROTECTIVE_ORDER_EXECUTION_ENABLED = 'false'
        TRADING_FUNDING_ENABLED = 'false'
        TRADING_FX_FINANCING_ENABLED = 'false'
        TRADING_LIQUIDATION_ENABLED = 'false'
        WALLET_RECONCILIATION_ENABLED = 'false'
        WALLET_SNAPSHOT_ENABLED = 'false'
        TRADING_LAB_QUEUE_ENABLED = 'true'
        TRADING_LAB_REPORT_CHUNK_BYTES = '16384'
        TRADING_LAB_REPORT_CLEANUP_ENABLED = 'false'
        DATABASE_PASSWORD = 'password'
        JWT_SECRET = New-TradingLabRunSecretMarker 'JWT_SECRET'
        CONFIG_ENCRYPTION_KEY = (
          New-TradingLabRunSecretMarker 'CONFIG_ENCRYPTION_KEY'
        )
        ADMIN_BOOTSTRAP_ENABLED = 'true'
        ADMIN_BOOTSTRAP_EMAIL = (
          New-TradingLabRunAccountMarker 'SUPER_EMAIL'
        )
        ADMIN_BOOTSTRAP_PASSWORD = (
          New-TradingLabRunSecretMarker 'ADMIN_BOOTSTRAP_PASSWORD'
        )
        SUPERVISOR_INTERNAL_TOKEN = (
          New-TradingLabRunSecretMarker 'SUPERVISOR_INTERNAL_TOKEN'
        )
        TRADING_LAB_VALIDATION_INTERNAL_TOKEN = (
          $script:TradingLabOwnedValidationSecretSentinel
        )
      }) `
      -HealthUri "http://127.0.0.1:$script:TradingLabMainBackendPort/actuator/health")
  )
  $commandsByPhase[6].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 6 `
      -PhaseName $phasePlan[5].Name `
      -Id 'provision-owned-smoke-accounts' `
      -Operation 'PROVISION_SMOKE_ACCOUNTS' `
      -Executable '[internal]' `
      -Arguments @() `
      -WorkingDirectory $artifacts)
  )
  $commandsByPhase[6].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 6 `
      -PhaseName $phasePlan[5].Name `
      -Id 'start-or-verify-owned-admin' `
      -Operation 'START_OR_VERIFY_OWNED_PROCESS' `
      -Executable 'npm' `
      -Arguments @(
        '--workspace',
        'apps/admin',
        'run',
        'dev',
        '--',
        '--host',
        '127.0.0.1',
        '--port',
        [string]$script:TradingLabAdminPort
      ) `
      -WorkingDirectory $script:TradingLabPlatformRoot `
      -DestinationPath (Join-Path $artifacts 'admin-process-receipt.json') `
      -Environment ([pscustomobject]@{
        VITE_API_BASE_URL = "http://127.0.0.1:$script:TradingLabMainBackendPort"
      }) `
      -HealthUri "http://127.0.0.1:$script:TradingLabAdminPort/trading/lab")
  )
  $commandsByPhase[6].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 6 `
      -PhaseName $phasePlan[5].Name `
      -Id 'wait-main-trading-lab-idle' `
      -Operation 'WAIT_MAIN_TRADING_LAB_IDLE' `
      -Executable '[internal]' `
      -Arguments @(
        [string]$script:TradingLabMainQueueIdleDeadlineSeconds,
        [string]$script:TradingLabMainQueueIdlePollMilliseconds
      ) `
      -WorkingDirectory $artifacts)
  )

  $httpClasses = @(
    'TradingLabSpotHttpIT',
    'TradingLabIsolatedPerpetualHttpIT',
    'TradingLabCrossMultiSymbolHttpIT',
    'TradingLabProtectionFundingHttpIT',
    'TradingLabNegativeHttpIT',
    'TradingLabLifecycleHttpIT',
    'TradingLabIsolationHttpIT'
  ) -join ','
  $commandsByPhase[7].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 7 `
      -PhaseName $phasePlan[6].Name `
      -Id 'validation-http-integration-tests' `
      -Operation 'MAVEN_TEST' `
      -Executable 'mvn' `
      -Arguments @(
        'clean',
        "-Dtest=$httpClasses",
        '-Dapi.version=1.40',
        'test'
      ) `
      -WorkingDirectory $candidateBackend `
      -DestinationPath (Join-Path `
        (Join-Path $artifacts 'surefire') `
        'validation-http-integration-tests') `
      -Environment ([pscustomobject]@{
        MAVEN_OPTS = $script:TradingLabMavenOpts
        VALIDATION_INTERNAL_SECRET = (
          New-TradingLabRunSecretMarker 'VALIDATION_INTERNAL_SECRET'
        )
      }) `
      -RequiredTestClasses @(
        'com.fxplatform.tradinglab.e2e.TradingLabSpotHttpIT',
        'com.fxplatform.tradinglab.e2e.TradingLabIsolatedPerpetualHttpIT',
        'com.fxplatform.tradinglab.e2e.TradingLabCrossMultiSymbolHttpIT',
        'com.fxplatform.tradinglab.e2e.TradingLabProtectionFundingHttpIT',
        'com.fxplatform.tradinglab.e2e.TradingLabNegativeHttpIT',
        'com.fxplatform.tradinglab.e2e.TradingLabLifecycleHttpIT',
        'com.fxplatform.tradinglab.e2e.TradingLabIsolationHttpIT'
      ))
  )

  foreach ($command in @(
    @('admin-tests', @('--workspace', 'apps/admin', 'test')),
    @('admin-build', @('--workspace', 'apps/admin', 'run', 'build'))
  )) {
    $commandsByPhase[8].Add(
      (New-TradingLabNormalCommandSpec `
        -PhaseNumber 8 `
        -PhaseName $phasePlan[7].Name `
        -Id $command[0] `
        -Operation 'COMMAND' `
        -Executable 'npm' `
        -Arguments @($command[1]) `
        -WorkingDirectory $script:TradingLabPlatformRoot)
    )
  }
  foreach ($command in @(
    @('web-tests', @('--workspace', 'apps/web', 'test')),
    @('web-build', @('--workspace', 'apps/web', 'run', 'build'))
  )) {
    $commandsByPhase[9].Add(
      (New-TradingLabNormalCommandSpec `
        -PhaseNumber 9 `
        -PhaseName $phasePlan[8].Name `
        -Id $command[0] `
        -Operation 'COMMAND' `
        -Executable 'npm' `
        -Arguments @($command[1]) `
        -WorkingDirectory $script:TradingLabPlatformRoot)
    )
  }
  $commandsByPhase[10].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 10 `
      -PhaseName $phasePlan[9].Name `
      -Id 'architecture-verification' `
      -Operation 'COMMAND' `
      -Executable 'npm' `
      -Arguments @('run', 'verify:architecture') `
      -WorkingDirectory $script:TradingLabPlatformRoot)
  )

  $isolatedAlias = 'smoke:trading-lab:isolated'
  $isolatedReady = Test-TradingLabPackageAlias `
    -PackageScripts $PackageScripts `
    -Alias $isolatedAlias
  $commandsByPhase[11].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 11 `
      -PhaseName $phasePlan[10].Name `
      -Id 'isolated-browser-smoke' `
      -Operation 'COMMAND' `
      -Executable 'npm' `
      -Arguments @(
        'run',
        $isolatedAlias,
        '--',
        "--artifacts=$artifacts",
        "--admin-url=http://127.0.0.1:$script:TradingLabAdminPort",
        "--api-url=http://127.0.0.1:$script:TradingLabMainBackendPort",
        '--attach-admin'
      ) `
      -WorkingDirectory $script:TradingLabPlatformRoot `
      -RequiredAlias $isolatedAlias `
      -Availability $(if ($isolatedReady) { 'READY' } else { 'BLOCKED' }) `
      -UnavailableReason $(if ($isolatedReady) {
        $null
      } else {
        "Missing required package alias: $isolatedAlias"
      }) `
      -Environment (New-TradingLabBrowserSmokeEnvironment))
  )

  $largeClasses = @(
    'TradingLabLargeCoordinatorHttpIT',
    'TradingLabLargeReportIT'
  ) -join ','
  $commandsByPhase[12].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 12 `
      -PhaseName $phasePlan[11].Name `
      -Id 'large-report-tests' `
      -Operation 'MAVEN_TEST' `
      -Executable 'mvn' `
      -Arguments @(
        'clean',
        "-Dtest=$largeClasses",
        '-Dapi.version=1.40',
        'test'
      ) `
      -WorkingDirectory $candidateBackend `
      -DestinationPath (Join-Path `
        (Join-Path $artifacts 'surefire') `
        'large-report-tests') `
      -Environment ([pscustomobject]@{
        MAVEN_OPTS = $script:TradingLabMavenOpts
        VALIDATION_INTERNAL_SECRET = (
          New-TradingLabRunSecretMarker 'VALIDATION_INTERNAL_SECRET'
        )
      }) `
      -RequiredTestClasses @(
        'com.fxplatform.tradinglab.e2e.TradingLabLargeCoordinatorHttpIT',
        'com.fxplatform.tradinglab.e2e.TradingLabLargeReportIT'
      ))
  )
  $commandsByPhase[12].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 12 `
      -PhaseName $phasePlan[11].Name `
      -Id 'runtime-isolation-contract-tests' `
      -Operation 'COMMAND' `
      -Executable 'npm' `
      -Arguments @('run', 'test:trading-lab-runtime-isolation') `
      -WorkingDirectory $script:TradingLabPlatformRoot)
  )
  $commandsByPhase[13].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 13 `
      -PhaseName $phasePlan[12].Name `
      -Id 'required-full-backend-tests' `
      -Operation 'MAVEN_TEST' `
      -Executable 'mvn' `
      -Arguments @('clean', 'test', '-Dapi.version=1.40') `
      -WorkingDirectory $candidateBackend `
      -DestinationPath (Join-Path `
        (Join-Path $artifacts 'surefire') `
        'required-full-backend-tests') `
      -Environment ([pscustomobject]@{
        MAVEN_OPTS = $script:TradingLabMavenOpts
        VALIDATION_INTERNAL_SECRET = (
          New-TradingLabRunSecretMarker 'VALIDATION_INTERNAL_SECRET'
        )
      }))
  )
  $commandsByPhase[14].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 14 `
      -PhaseName $phasePlan[13].Name `
      -Id 'provisional-verification-report' `
      -Operation 'PROVISIONAL_REPORT' `
      -Executable '[internal]' `
      -Arguments @() `
      -WorkingDirectory $artifacts `
      -DestinationPath (Join-Path `
        $artifacts `
        'trading-lab-verification-report.provisional.md'))
  )
  $commandsByPhase[15].Add(
    (New-TradingLabNormalCommandSpec `
      -PhaseNumber 15 `
      -PhaseName $phasePlan[14].Name `
      -Id 'owned-cleanup' `
      -Operation 'OWNED_CLEANUP' `
      -Executable '[internal]' `
      -Arguments @(
        (Join-Path $artifacts 'supervisor-process-receipt.json'),
        (Join-Path $artifacts 'main-backend-process-receipt.json'),
        (Join-Path $artifacts 'admin-process-receipt.json'),
        (Join-Path $artifacts 'fixed-stack-lease.json')
      ) `
      -WorkingDirectory $artifacts `
      -DestinationPath (Join-Path $artifacts 'cleanup-receipt.json'))
  )

  foreach ($phase in $phasePlan) {
    [pscustomobject][ordered]@{
      Number = $phase.Number
      Name = $phase.Name
      AlwaysRun = $phase.Number -ge 14
      Commands = @($commandsByPhase[$phase.Number])
    }
  }
}

function Get-TradingLabValidationStopSpec {
  [CmdletBinding()]
  param()

  return [pscustomobject]@{
    Executable = 'docker'
    Arguments = @(
      'compose',
      '-p',
      $script:TradingLabComposeProject,
      '-f',
      $script:TradingLabComposeFile,
      'stop'
    )
  }
}

function Stop-TradingLabValidationProject {
  [CmdletBinding()]
  param()

  $spec = Get-TradingLabValidationStopSpec
  $arguments = @($spec.Arguments)
  & $spec.Executable $arguments
  if ($LASTEXITCODE -ne 0) {
    throw "Fixed validation project stop failed with exit code $LASTEXITCODE"
  }
}

function Get-TradingLabReadOnlyPreflightSpecs {
  [CmdletBinding()]
  param()

  $safeDirectory = $script:TradingLabRepositoryRoot.Replace('\', '/')
  return @(
    [pscustomobject]@{
      Name = 'git-branch'
      Executable = 'git'
      Arguments = @(
        '-c',
        "safe.directory=$safeDirectory",
        'branch',
        '--show-current'
      )
    },
    [pscustomobject]@{
      Name = 'git-head'
      Executable = 'git'
      Arguments = @(
        '-c',
        "safe.directory=$safeDirectory",
        'rev-parse',
        'HEAD'
      )
    },
    [pscustomobject]@{
      Name = 'git-dirty-tree'
      Executable = 'git'
      Arguments = @(
        '-c',
        "safe.directory=$safeDirectory",
        'status', '--short'
      )
    },
    [pscustomobject]@{
      Name = 'java-version'
      Executable = 'java'
      Arguments = @('-version')
    },
    [pscustomobject]@{
      Name = 'maven-version'
      Executable = 'mvn'
      Arguments = @('-version')
    },
    [pscustomobject]@{
      Name = 'node-version'
      Executable = 'node'
      Arguments = @('--version')
    },
    [pscustomobject]@{
      Name = 'npm-version'
      Executable = 'npm'
      Arguments = @('--version')
    },
    [pscustomobject]@{
      Name = 'docker-version'
      Executable = 'docker'
      Arguments = @('version')
    },
    [pscustomobject]@{
      Name = 'docker-compose-version'
      Executable = 'docker'
      Arguments = @('compose', 'version')
    }
  )
}

function Resolve-TradingLabArtifactDirectory {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$ArtifactsRoot,

    [Parameter(Mandatory = $true)]
    [string]$ArtifactsDirectory
  )

  if ([string]::IsNullOrWhiteSpace($ArtifactsRoot)) {
    throw 'Trading Lab artifact root is required'
  }
  if ([string]::IsNullOrWhiteSpace($ArtifactsDirectory)) {
    throw 'Trading Lab artifact directory is required'
  }

  $root = [System.IO.Path]::GetFullPath($ArtifactsRoot)
  $candidate = if ([System.IO.Path]::IsPathRooted($ArtifactsDirectory)) {
    [System.IO.Path]::GetFullPath($ArtifactsDirectory)
  } else {
    [System.IO.Path]::GetFullPath((Join-Path $root $ArtifactsDirectory))
  }
  $trimSeparators = [char[]]@(
    [System.IO.Path]::DirectorySeparatorChar,
    [System.IO.Path]::AltDirectorySeparatorChar
  )
  $rootPrefix = $root.TrimEnd($trimSeparators) +
    [System.IO.Path]::DirectorySeparatorChar
  if (
    -not $candidate.StartsWith(
      $rootPrefix,
      [System.StringComparison]::OrdinalIgnoreCase
    ) -or
    $candidate.Length -le $rootPrefix.Length
  ) {
    throw 'Trading Lab artifact directory is outside the fixed artifact root'
  }

  Assert-TradingLabArtifactPathHasNoReparsePoint -Path $candidate
  return $candidate
}

function Assert-TradingLabArtifactPathHasNoReparsePoint {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$Path
  )

  $fullPath = [System.IO.Path]::GetFullPath($Path)
  $pathRoot = [System.IO.Path]::GetPathRoot($fullPath)
  if ([string]::IsNullOrWhiteSpace($pathRoot)) {
    throw 'Trading Lab artifact path has no filesystem root'
  }

  $separators = [char[]]@(
    [System.IO.Path]::DirectorySeparatorChar,
    [System.IO.Path]::AltDirectorySeparatorChar
  )
  $segments = $fullPath.Substring($pathRoot.Length).Split(
    $separators,
    [System.StringSplitOptions]::RemoveEmptyEntries
  )
  $current = $pathRoot
  foreach ($segment in $segments) {
    $current = Join-Path $current $segment
    if (-not (Test-Path -LiteralPath $current)) {
      break
    }
    $item = Get-Item -LiteralPath $current -Force
    if (
      ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0
    ) {
      throw "Trading Lab artifact path contains a reparse point: $current"
    }
  }
}

function Assert-TradingLabRunToken {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$RunToken
  )

  $bytes = [System.Text.Encoding]::UTF8.GetByteCount($RunToken)
  if (
    [string]::IsNullOrWhiteSpace($RunToken) -or
    $bytes -lt 32 -or
    $RunToken -match '\s'
  ) {
    throw 'Trading Lab artifact owner token must contain at least 32 non-whitespace bytes'
  }
}

function New-TradingLabOwnedArtifactDirectory {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$ArtifactsRoot,

    [Parameter(Mandatory = $true)]
    [string]$ArtifactsDirectory,

    [Parameter(Mandatory = $true)]
    [string]$RunToken
  )

  Assert-TradingLabRunToken -RunToken $RunToken
  $root = [System.IO.Path]::GetFullPath($ArtifactsRoot)
  $candidate = Resolve-TradingLabArtifactDirectory `
    -ArtifactsRoot $root `
    -ArtifactsDirectory $ArtifactsDirectory
  if (Test-Path -LiteralPath $candidate) {
    throw 'Trading Lab artifact directory already exists'
  }

  [System.IO.Directory]::CreateDirectory($root) | Out-Null
  Assert-TradingLabArtifactPathHasNoReparsePoint -Path $root
  [System.IO.Directory]::CreateDirectory($candidate) | Out-Null
  $candidate = Resolve-TradingLabArtifactDirectory `
    -ArtifactsRoot $root `
    -ArtifactsDirectory $candidate

  $markerPath = Join-Path $candidate $script:TradingLabArtifactMarker
  $markerJson = [ordered]@{
    schemaVersion = 1
    runToken = $RunToken
  } | ConvertTo-Json -Compress
  $payload = [System.Text.Encoding]::UTF8.GetBytes(
    $markerJson + [System.Environment]::NewLine
  )
  $stream = [System.IO.FileStream]::new(
    $markerPath,
    [System.IO.FileMode]::CreateNew,
    [System.IO.FileAccess]::Write,
    [System.IO.FileShare]::None
  )
  try {
    $stream.Write($payload, 0, $payload.Length)
    $stream.Flush($true)
  } finally {
    $stream.Dispose()
  }

  return Assert-TradingLabOwnedArtifactDirectory `
    -ArtifactsRoot $root `
    -ArtifactsDirectory $candidate `
    -RunToken $RunToken
}

function Assert-TradingLabOwnedArtifactDirectory {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$ArtifactsRoot,

    [Parameter(Mandatory = $true)]
    [string]$ArtifactsDirectory,

    [Parameter(Mandatory = $true)]
    [string]$RunToken
  )

  Assert-TradingLabRunToken -RunToken $RunToken
  $candidate = Resolve-TradingLabArtifactDirectory `
    -ArtifactsRoot $ArtifactsRoot `
    -ArtifactsDirectory $ArtifactsDirectory
  if (-not (Test-Path -LiteralPath $candidate -PathType Container)) {
    throw 'Trading Lab owned artifact directory is missing'
  }

  $markerPath = Join-Path $candidate $script:TradingLabArtifactMarker
  if (-not (Test-Path -LiteralPath $markerPath -PathType Leaf)) {
    throw 'Trading Lab artifact owner marker is missing'
  }
  $markerItem = Get-Item -LiteralPath $markerPath -Force
  if (
    ($markerItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0
  ) {
    throw 'Trading Lab artifact owner marker cannot be a reparse point'
  }

  try {
    $marker = [System.IO.File]::ReadAllText($markerPath) | ConvertFrom-Json
  } catch {
    throw 'Trading Lab artifact owner marker is invalid'
  }
  $markerFields = @($marker.PSObject.Properties.Name)
  if (
    $markerFields.Count -ne 2 -or
    $markerFields[0] -ne 'schemaVersion' -or
    $markerFields[1] -ne 'runToken' -or
    $marker.schemaVersion -ne 1 -or
    $marker.runToken -cne $RunToken
  ) {
    throw 'Trading Lab artifact owner marker does not match this run'
  }

  $candidate = Resolve-TradingLabArtifactDirectory `
    -ArtifactsRoot $ArtifactsRoot `
    -ArtifactsDirectory $candidate
  return $candidate
}

function Read-StrictTradingLabSurefireSummary {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$ReportsRoot,

    [Parameter(Mandatory = $true)]
    [string[]]$RequiredClasses,

    [Parameter(Mandatory = $true)]
    [DateTimeOffset]$InvocationStartedAtUtc,

    [Parameter(Mandatory = $true)]
    [string]$DumpRoot
  )

  if (-not (Test-Path -LiteralPath $ReportsRoot -PathType Container)) {
    throw 'Trading Lab Surefire reports root is missing'
  }
  if ($null -eq $RequiredClasses -or $RequiredClasses.Count -eq 0) {
    throw 'Trading Lab required Surefire classes are missing'
  }
  if ($InvocationStartedAtUtc -eq [DateTimeOffset]::MinValue) {
    throw 'Trading Lab Surefire invocation start time is missing'
  }
  if (-not (Test-Path -LiteralPath $DumpRoot -PathType Container)) {
    throw 'Trading Lab Surefire dump root is missing'
  }
  $dumpArtifacts = @(
    Get-ChildItem -LiteralPath $DumpRoot -Recurse -Force -File |
      Where-Object { $_.Name -match '\.(?:dump|dumpstream)$' }
  )
  if ($dumpArtifacts.Count -ne 0) {
    throw (
      'Trading Lab Surefire dump artifact found: ' +
      $dumpArtifacts[0].FullName
    )
  }

  $seen = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::Ordinal
  )
  $totals = [ordered]@{
    Tests = 0L
    Failures = 0L
    Errors = 0L
    Skipped = 0L
    Classes = 0
  }
  foreach ($requiredClass in $RequiredClasses) {
    if (
      [string]::IsNullOrWhiteSpace($requiredClass) -or
      $requiredClass -notmatch '^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*$'
    ) {
      throw 'Trading Lab required Surefire class name is invalid'
    }
    if (-not $seen.Add($requiredClass)) {
      throw "Duplicate required Trading Lab Surefire class: $requiredClass"
    }

    $reportPath = Join-Path $ReportsRoot "TEST-$requiredClass.xml"
    if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
      throw "Missing Trading Lab Surefire report: $reportPath"
    }
    $reportItem = Get-Item -LiteralPath $reportPath -Force
    if (
      ($reportItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0
    ) {
      throw "Trading Lab Surefire report cannot be a reparse point: $reportPath"
    }
    if ($reportItem.LastWriteTimeUtc -lt $InvocationStartedAtUtc.UtcDateTime) {
      throw (
        'Trading Lab Surefire report is stale for this invocation: ' +
        $reportPath
      )
    }

    $settings = [System.Xml.XmlReaderSettings]::new()
    $settings.DtdProcessing = [System.Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $reader = [System.Xml.XmlReader]::Create($reportPath, $settings)
    $document = [System.Xml.XmlDocument]::new()
    $document.XmlResolver = $null
    try {
      $document.Load($reader)
    } catch {
      throw "Unreadable Trading Lab Surefire report: $reportPath"
    } finally {
      $reader.Dispose()
    }
    $suite = $document.DocumentElement
    if ($null -eq $suite -or $suite.LocalName -ne 'testsuite') {
      throw "Trading Lab Surefire report has no testsuite root: $reportPath"
    }
    if ($suite.GetAttribute('name') -cne $requiredClass) {
      throw (
        "Trading Lab Surefire suite name does not match ${requiredClass}: " +
        $suite.GetAttribute('name')
      )
    }

    $tests = Read-TradingLabSurefireCount -Suite $suite -Name 'tests'
    $failures = Read-TradingLabSurefireCount -Suite $suite -Name 'failures'
    $errors = Read-TradingLabSurefireCount -Suite $suite -Name 'errors'
    $skipped = Read-TradingLabSurefireCount -Suite $suite -Name 'skipped'
    if (
      $tests -eq 0 -or
      $failures -ne 0 -or
      $errors -ne 0 -or
      $skipped -ne 0
    ) {
      throw (
        "Trading Lab Surefire gate failed for ${requiredClass}: " +
        "tests=$tests failures=$failures errors=$errors skipped=$skipped"
      )
    }
    $testcases = @(
      $suite.ChildNodes |
        Where-Object {
          $_.NodeType -eq [System.Xml.XmlNodeType]::Element -and
          $_.LocalName -eq 'testcase'
        }
    )
    if ($testcases.Count -ne $tests) {
      throw (
        "Trading Lab Surefire direct testcase count mismatch for " +
        "${requiredClass}: declared=$tests actual=$($testcases.Count)"
      )
    }
    foreach ($testcase in $testcases) {
      if ($testcase.GetAttribute('classname') -cne $requiredClass) {
        throw (
          "Trading Lab Surefire testcase classname mismatch for " +
          $requiredClass
        )
      }
      if ([string]::IsNullOrWhiteSpace($testcase.GetAttribute('name'))) {
        throw (
          "Trading Lab Surefire testcase name is empty for " +
          $requiredClass
        )
      }
    }
    $hiddenOutcomes = @(
      $suite.SelectNodes(
        ".//*[local-name()='failure' or local-name()='error' or " +
        "local-name()='skipped']"
      )
    )
    if ($hiddenOutcomes.Count -ne 0) {
      throw (
        "Trading Lab Surefire hidden failure/error/skipped node found for " +
        $requiredClass
      )
    }

    $totals.Tests += $tests
    $totals.Failures += $failures
    $totals.Errors += $errors
    $totals.Skipped += $skipped
    $totals.Classes += 1
  }

  return [pscustomobject]$totals
}

function Read-TradingLabSurefireCount {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [System.Xml.XmlElement]$Suite,

    [Parameter(Mandatory = $true)]
    [string]$Name
  )

  $raw = $Suite.GetAttribute($Name)
  $parsed = 0L
  $valid = [long]::TryParse(
    $raw,
    [System.Globalization.NumberStyles]::None,
    [System.Globalization.CultureInfo]::InvariantCulture,
    [ref]$parsed
  )
  if (-not $valid -or $parsed -lt 0) {
    throw "Trading Lab Surefire $Name count is invalid"
  }
  return $parsed
}

function New-TradingLabRecordedCommandResult {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$Name,

    [Parameter(Mandatory = $true)]
    [string]$Executable,

    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [string[]]$Arguments,

    [Parameter(Mandatory = $true)]
    [DateTimeOffset]$StartedAtUtc,

    [Parameter(Mandatory = $true)]
    [DateTimeOffset]$EndedAtUtc,

    [Parameter(Mandatory = $true)]
    [int]$ExitCode,

    [Parameter(Mandatory = $true)]
    [string]$StdoutPath,

    [Parameter(Mandatory = $true)]
    [string]$StderrPath
  )

  if (
    [string]::IsNullOrWhiteSpace($Name) -or
    [string]::IsNullOrWhiteSpace($Executable)
  ) {
    throw 'Trading Lab recorded command name and executable are required'
  }
  if ($EndedAtUtc -lt $StartedAtUtc) {
    throw 'Trading Lab recorded command end cannot precede its start'
  }
  if (
    [string]::IsNullOrWhiteSpace($StdoutPath) -or
    [string]::IsNullOrWhiteSpace($StderrPath)
  ) {
    throw 'Trading Lab recorded command output paths are required'
  }

  return [pscustomobject][ordered]@{
    Name = $Name
    Executable = $Executable
    Arguments = @($Arguments)
    Classification = if ($ExitCode -eq 0) { 'PASS' } else { 'FAIL' }
    Status = if ($ExitCode -eq 0) { 'PASS' } else { 'FAIL' }
    StartedAtUtc = $StartedAtUtc.ToString('o')
    EndedAtUtc = $EndedAtUtc.ToString('o')
    ExitCode = $ExitCode
    StdoutPath = $StdoutPath
    StderrPath = $StderrPath
    Reason = $null
  }
}

function New-TradingLabNotRunCommandResult {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$Name,

    [Parameter(Mandatory = $true)]
    [string]$Executable,

    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [string[]]$Arguments,

    [Parameter(Mandatory = $true)]
    [string]$Reason
  )

  if (
    [string]::IsNullOrWhiteSpace($Name) -or
    [string]::IsNullOrWhiteSpace($Executable) -or
    [string]::IsNullOrWhiteSpace($Reason)
  ) {
    throw 'Trading Lab NOT_RUN command requires name, executable, and reason'
  }

  return [pscustomobject][ordered]@{
    Name = $Name
    Executable = $Executable
    Arguments = @($Arguments)
    Classification = 'NOT_RUN'
    Status = 'NOT_RUN'
    StartedAtUtc = $null
    EndedAtUtc = $null
    ExitCode = $null
    StdoutPath = $null
    StderrPath = $null
    Reason = $Reason
  }
}

function Get-TradingLabFixedStackServiceNames {
  [CmdletBinding()]
  param()

  return @(
    'validation-backend',
    'validation-postgres',
    'validation-redis'
  )
}

function ConvertTo-TradingLabFixedStackIdentityMap {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [object[]]$ObservedContainers
  )

  $requiredServices = @(Get-TradingLabFixedStackServiceNames)
  $required = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::Ordinal
  )
  foreach ($service in $requiredServices) {
    $required.Add($service) | Out-Null
  }
  $identities = [System.Collections.Generic.Dictionary[string, object]]::new(
    [System.StringComparer]::Ordinal
  )
  foreach ($container in @($ObservedContainers)) {
    if ($null -eq $container) {
      throw 'Trading Lab fixed stack container identity is missing'
    }
    $serviceProperty = $container.PSObject.Properties['Service']
    $idProperty = $container.PSObject.Properties['ContainerId']
    $runningProperty = $container.PSObject.Properties['Running']
    if (
      $null -eq $serviceProperty -or
      $null -eq $idProperty -or
      $null -eq $runningProperty
    ) {
      throw 'Trading Lab fixed stack container identity is incomplete'
    }
    $service = [string]$serviceProperty.Value
    $containerId = [string]$idProperty.Value
    if (-not $required.Contains($service)) {
      throw "Unexpected fixed validation service: $service"
    }
    if ($containerId -notmatch '^[a-f0-9]{64}$') {
      throw "Invalid fixed validation container ID for $service"
    }
    if ($runningProperty.Value -isnot [bool]) {
      throw "Invalid fixed validation running state for $service"
    }
    $stateProperty = $container.PSObject.Properties['State']
    $state = if ($null -eq $stateProperty) {
      if ([bool]$runningProperty.Value) { 'running' } else { 'exited' }
    } else {
      [string]$stateProperty.Value
    }
    if (
      $state -cnotin @('running', 'exited', 'created', 'restarting', 'paused') -or
      ([bool]$runningProperty.Value) -ne ($state -ceq 'running')
    ) {
      throw "Invalid fixed validation state for $service"
    }
    if ($identities.ContainsKey($service)) {
      throw "Duplicate fixed validation service identity: $service"
    }
    $identities.Add(
      $service,
      [pscustomobject][ordered]@{
        Service = $service
        ContainerId = $containerId
        State = $state
        Running = [bool]$runningProperty.Value
      }
    )
  }
  return $identities
}

function Assert-TradingLabFixedStackCanStart {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [object[]]$ObservedContainers
  )

  $identities = ConvertTo-TradingLabFixedStackIdentityMap `
    -ObservedContainers $ObservedContainers
  if ($identities.Count -eq 0) {
    return $true
  }
  $requiredCount = @(Get-TradingLabFixedStackServiceNames).Count
  if ($identities.Count -ne $requiredCount) {
    throw 'Fixed validation stack is in a partial pre-existing state'
  }
  $allRunning = $true
  $allExited = $true
  foreach ($identity in $identities.Values) {
    if ([string]$identity.State -cne 'running') {
      $allRunning = $false
    }
    if ([string]$identity.State -cne 'exited') {
      $allExited = $false
    }
  }
  if ($allExited) {
    return $true
  }
  if ($allRunning) {
    throw 'Fixed validation stack is already running and pre-existing'
  }
  throw 'Fixed validation stack state is not safely exited'
}

function New-TradingLabFixedStackLease {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$RunToken,

    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [object[]]$ObservedContainers,

    [AllowNull()]
    [object]$ExistingLease
  )

  Assert-TradingLabRunToken -RunToken $RunToken
  if ($null -ne $ExistingLease) {
    throw 'Fixed validation stack lease collision'
  }
  $identities = ConvertTo-TradingLabFixedStackIdentityMap `
    -ObservedContainers $ObservedContainers
  $services = @(Get-TradingLabFixedStackServiceNames)
  if ($identities.Count -ne $services.Count) {
    throw 'Fixed validation stack lease requires every service identity'
  }
  $orderedIdentities = @(
    foreach ($service in $services) {
      if (-not $identities.ContainsKey($service)) {
        throw "Fixed validation stack lease is missing service: $service"
      }
      $identity = $identities[$service]
      if (
        (
          $service -ceq 'validation-backend' -and
          [string]$identity.State -cnotin @('running', 'exited')
        ) -or
        (
          $service -cne 'validation-backend' -and
          [string]$identity.State -cne 'running'
        )
      ) {
        throw "Fixed validation stack lease service is not running: $service"
      }
      $identity
    }
  )
  return [pscustomobject][ordered]@{
    SchemaVersion = 1
    RunToken = $RunToken
    ComposeProject = $script:TradingLabComposeProject
    ComposeFile = $script:TradingLabComposeFile
    ServiceIdentities = $orderedIdentities
  }
}

function Assert-TradingLabFixedStackCanStop {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$RunToken,

    [Parameter(Mandatory = $true)]
    [object]$Lease,

    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [object[]]$ObservedContainers,

    [switch]$RequireRunning
  )

  Assert-TradingLabRunToken -RunToken $RunToken
  if ($null -eq $Lease) {
    throw 'Fixed validation stack lease is missing'
  }
  if (
    $Lease.PSObject.Properties['SchemaVersion'].Value -ne 1 -or
    [string]$Lease.PSObject.Properties['RunToken'].Value -cne $RunToken -or
    [string]$Lease.PSObject.Properties['ComposeProject'].Value -cne
      $script:TradingLabComposeProject -or
    [string]$Lease.PSObject.Properties['ComposeFile'].Value -cne
      $script:TradingLabComposeFile
  ) {
    throw 'Fixed validation stack lease does not belong to this run'
  }
  $leased = ConvertTo-TradingLabFixedStackIdentityMap `
    -ObservedContainers @($Lease.PSObject.Properties['ServiceIdentities'].Value)
  $observed = ConvertTo-TradingLabFixedStackIdentityMap `
    -ObservedContainers $ObservedContainers
  $services = @(Get-TradingLabFixedStackServiceNames)
  if (
    $leased.Count -ne $services.Count -or
    $observed.Count -ne $services.Count
  ) {
    throw 'Fixed validation stack identity drift prevents stop'
  }
  foreach ($service in $services) {
    if (
      -not $leased.ContainsKey($service) -or
      -not $observed.ContainsKey($service)
    ) {
      throw 'Fixed validation stack identity drift prevents stop'
    }
    $leasedIdentity = $leased[$service]
    $observedIdentity = $observed[$service]
    if (
      $leasedIdentity.ContainerId -cne $observedIdentity.ContainerId -or
      [string]$leasedIdentity.State -cnotin @('running', 'exited') -or
      [string]$observedIdentity.State -cnotin @('running', 'exited') -or
      ($RequireRunning -and -not $observedIdentity.Running)
    ) {
      throw (
        "Fixed validation stack container ID identity drift prevents stop: " +
        $service
      )
    }
  }
  return $true
}

function ConvertFrom-TradingLabDockerPsJson {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [AllowEmptyString()]
    [string]$Json
  )

  if ([string]::IsNullOrWhiteSpace($Json)) {
    return @()
  }
  try {
    $parsed = $Json | ConvertFrom-Json
    $rows = @($parsed)
  } catch {
    try {
      $rows = @(
        $Json -split '\r?\n' |
          Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
          ForEach-Object { $_ | ConvertFrom-Json }
      )
    } catch {
      throw 'Fixed validation Docker ps JSON is invalid'
    }
  }

  $required = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::Ordinal
  )
  foreach ($service in @(Get-TradingLabFixedStackServiceNames)) {
    $required.Add($service) | Out-Null
  }
  $seen = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::Ordinal
  )
  $identities = [System.Collections.Generic.List[object]]::new()
  foreach ($row in $rows) {
    foreach ($propertyName in @('ID', 'State', 'Labels')) {
      if ($null -eq $row.PSObject.Properties[$propertyName]) {
        throw "Fixed validation Docker ps row is missing $propertyName"
      }
    }
    $containerId = [string]$row.ID
    $state = [string]$row.State
    $labels = @{}
    if ($row.Labels -is [System.Collections.IDictionary]) {
      foreach ($key in $row.Labels.Keys) {
        $labels[[string]$key] = [string]$row.Labels[$key]
      }
    } elseif (
      $row.Labels -isnot [string] -and
      $null -ne $row.Labels.PSObject
    ) {
      foreach ($property in $row.Labels.PSObject.Properties) {
        $labels[[string]$property.Name] = [string]$property.Value
      }
    } else {
      foreach ($entry in ([string]$row.Labels -split ',')) {
        $separator = $entry.IndexOf('=')
        if ($separator -gt 0) {
          $labels[$entry.Substring(0, $separator)] =
            $entry.Substring($separator + 1)
        }
      }
    }
    $labeledProject = [string]$labels['com.docker.compose.project']
    $labeledService = [string]$labels['com.docker.compose.service']
    $project = if ($null -eq $row.PSObject.Properties['Project']) {
      $labeledProject
    } else {
      [string]$row.Project
    }
    $service = if ($null -eq $row.PSObject.Properties['Service']) {
      $labeledService
    } else {
      [string]$row.Service
    }
    if ($containerId -notmatch '^[a-f0-9]{64}$') {
      throw "Invalid fixed validation Docker container ID for $service"
    }
    if (
      $project -cne $script:TradingLabComposeProject -or
      $labeledProject -cne $script:TradingLabComposeProject
    ) {
      throw "Fixed validation Docker project label mismatch: $project"
    }
    if (
      -not $required.Contains($service) -or
      $labeledService -cne $service
    ) {
      throw "Unexpected fixed validation Docker service label: $service"
    }
    if (-not $seen.Add($service)) {
      throw "Duplicate fixed validation Docker service: $service"
    }
    if ($state -cnotin @('running', 'exited', 'created', 'restarting', 'paused')) {
      throw "Invalid fixed validation Docker state for $service"
    }
    if (
      $labeledProject -cne $script:TradingLabComposeProject -or
      $labeledService -cne $service
    ) {
      throw "Fixed validation Docker ownership label mismatch for $service"
    }
    $identities.Add(
      [pscustomobject][ordered]@{
        Service = $service
        ContainerId = $containerId
        State = $state
        Running = $state -ceq 'running'
      }
    )
  }

  foreach ($service in @(Get-TradingLabFixedStackServiceNames)) {
    foreach ($identity in $identities) {
      if ($identity.Service -ceq $service) {
        $identity
      }
    }
  }
}

function Get-TradingLabFixedStackIdentityFingerprint {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [object[]]$ObservedContainers
  )

  $identities = ConvertTo-TradingLabFixedStackIdentityMap `
    -ObservedContainers $ObservedContainers
  $lines = [System.Collections.Generic.List[string]]::new()
  foreach ($service in @(Get-TradingLabFixedStackServiceNames)) {
    if ($identities.ContainsKey($service)) {
      $identity = $identities[$service]
      $lines.Add(
        "$service|$([string]$identity.ContainerId)|$([string]$identity.State)"
      ) | Out-Null
    }
  }
  return Get-TradingLabSha256Hex `
    -Bytes ([System.Text.Encoding]::UTF8.GetBytes(($lines -join "`n")))
}

function Read-TradingLabBoundedJsonEvidenceFile {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$Path,

    [Parameter(Mandatory = $true)]
    [int64]$MaximumBytes,

    [Parameter(Mandatory = $true)]
    [string]$Label
  )

  if (
    $MaximumBytes -le 0 -or
    $MaximumBytes -ge [int]::MaxValue
  ) {
    throw "$Label byte limit is invalid"
  }
  $target = [System.IO.Path]::GetFullPath($Path)
  if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
    throw "$Label is missing"
  }
  Assert-TradingLabArtifactPathHasNoReparsePoint -Path $target
  $stream = $null
  try {
    $stream = [System.IO.FileStream]::new(
      $target,
      [System.IO.FileMode]::Open,
      [System.IO.FileAccess]::Read,
      [System.IO.FileShare]::Read
    )
  } catch {
    throw "$Label is invalid"
  }
  $capacity = [int]($MaximumBytes + 1)
  $buffer = [byte[]]::new($capacity)
  $totalBytes = 0
  try {
    while ($totalBytes -lt $capacity) {
      $read = $stream.Read(
        $buffer,
        $totalBytes,
        $capacity - $totalBytes
      )
      if ($read -eq 0) {
        break
      }
      $totalBytes += $read
    }
  } catch {
    throw "$Label is invalid"
  } finally {
    $stream.Dispose()
  }
  if ($totalBytes -le 0 -or $totalBytes -gt $MaximumBytes) {
    throw "$Label size is invalid"
  }
  $bytes = [byte[]]::new($totalBytes)
  [System.Buffer]::BlockCopy($buffer, 0, $bytes, 0, $totalBytes)
  try {
    $utf8 = [System.Text.UTF8Encoding]::new($false, $true)
    $value = $utf8.GetString($bytes) | ConvertFrom-Json
  } catch {
    throw "$Label is invalid"
  }
  return [pscustomobject][ordered]@{
    Value = $value
    Bytes = $bytes
    Sha256 = Get-TradingLabSha256Hex -Bytes $bytes
  }
}

function Read-TradingLabBoundedJsonFile {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$Path,

    [Parameter(Mandatory = $true)]
    [int64]$MaximumBytes,

    [Parameter(Mandatory = $true)]
    [string]$Label
  )

  return (
    Read-TradingLabBoundedJsonEvidenceFile `
      -Path $Path `
      -MaximumBytes $MaximumBytes `
      -Label $Label
  ).Value
}

function ConvertTo-TradingLabEvidenceDateTimeOffset {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [AllowNull()]
    [object]$Value,

    [Parameter(Mandatory = $true)]
    [string]$Label
  )

  if (
    $null -eq $Value -or
    (
      $Value -is [string] -and
      [string]::IsNullOrWhiteSpace([string]$Value)
    )
  ) {
    throw "$Label timestamp is invalid"
  }
  try {
    return [DateTimeOffset]$Value
  } catch {
    throw "$Label timestamp is invalid"
  }
}

function Get-TradingLabStoppedStackRecoveryProofFromDirectory {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$ArtifactsRoot,

    [Parameter(Mandatory = $true)]
    [string]$ArtifactsDirectory,

    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [object[]]$ObservedContainers,

    [switch]$AttemptOnly
  )

  $root = [System.IO.Path]::GetFullPath($ArtifactsRoot)
  $directory = Resolve-TradingLabArtifactDirectory `
    -ArtifactsRoot $root `
    -ArtifactsDirectory $ArtifactsDirectory
  $markerPath = Join-Path $directory $script:TradingLabArtifactMarker
  $leasePath = Join-Path $directory 'fixed-stack-lease.json'
  $cleanupPath = Join-Path $directory 'cleanup-receipt.json'
  $markerEvidence = Read-TradingLabBoundedJsonEvidenceFile `
    -Path $markerPath `
    -MaximumBytes 4096 `
    -Label 'Historical Trading Lab owner marker'
  $marker = $markerEvidence.Value
  $markerFields = @($marker.PSObject.Properties.Name)
  if (
    $markerFields.Count -ne 2 -or
    $markerFields[0] -cne 'schemaVersion' -or
    $markerFields[1] -cne 'runToken' -or
    $marker.schemaVersion -ne 1
  ) {
    throw 'Historical Trading Lab owner marker schema is invalid'
  }
  $runToken = [string]$marker.runToken
  Assert-TradingLabRunToken -RunToken $runToken
  if ($runToken -cnotmatch '^[a-f0-9]{64}$') {
    throw 'Historical Trading Lab owner token format is invalid'
  }
  Assert-TradingLabOwnedArtifactDirectory `
    -ArtifactsRoot $root `
    -ArtifactsDirectory $directory `
    -RunToken $runToken | Out-Null

  $leaseEvidence = Read-TradingLabBoundedJsonEvidenceFile `
    -Path $leasePath `
    -MaximumBytes 65536 `
    -Label 'Historical fixed validation stack lease'
  $lease = $leaseEvidence.Value
  $leaseFields = @($lease.PSObject.Properties.Name | Sort-Object)
  if (
    (@($leaseFields) -join '|') -cne
      (@(
        'ComposeFile',
        'ComposeProject',
        'RunToken',
        'SchemaVersion',
        'ServiceIdentities'
      ) -join '|') -or
    $lease.SchemaVersion -ne 1 -or
    [string]$lease.RunToken -cne $runToken -or
    [string]$lease.ComposeProject -cne $script:TradingLabComposeProject -or
    [string]$lease.ComposeFile -cne $script:TradingLabComposeFile
  ) {
    throw 'Historical fixed validation stack lease schema is invalid'
  }
  $leased = ConvertTo-TradingLabFixedStackIdentityMap `
    -ObservedContainers @($lease.ServiceIdentities)
  foreach ($identity in @($lease.ServiceIdentities)) {
    $identityFields = @($identity.PSObject.Properties.Name | Sort-Object)
    $identityShape = @($identityFields) -join '|'
    if (
      $identityShape -cne 'ContainerId|Running|Service' -and
      $identityShape -cne 'ContainerId|Running|Service|State'
    ) {
      throw 'Historical fixed validation stack lease identity schema is invalid'
    }
  }
  $observed = ConvertTo-TradingLabFixedStackIdentityMap `
    -ObservedContainers $ObservedContainers
  $services = @(Get-TradingLabFixedStackServiceNames)
  if (
    $leased.Count -ne $services.Count -or
    $observed.Count -ne $services.Count
  ) {
    throw 'Historical fixed validation stack lease identity count mismatches'
  }
  foreach ($service in $services) {
    if (
      -not $leased.ContainsKey($service) -or
      -not $observed.ContainsKey($service) -or
      [string]$leased[$service].ContainerId -cne
        [string]$observed[$service].ContainerId -or
      [string]$observed[$service].State -cne 'exited'
    ) {
      throw 'Historical fixed validation stack lease identity mismatches'
    }
    if (
      (
        $service -ceq 'validation-backend' -and
        [string]$leased[$service].State -cnotin @('running', 'exited')
      ) -or
      (
        $service -cne 'validation-backend' -and
        [string]$leased[$service].State -cne 'running'
      )
    ) {
      throw 'Historical fixed validation stack lease state is invalid'
    }
  }
  if ($AttemptOnly) {
    return [pscustomobject][ordered]@{
      ArtifactsDirectory = $directory
      IdentityFingerprint = Get-TradingLabFixedStackIdentityFingerprint `
        -ObservedContainers $ObservedContainers
      RunToken = $runToken
      OwnerMarkerSha256 = $markerEvidence.Sha256
      LeaseSha256 = $leaseEvidence.Sha256
    }
  }

  $cleanupEvidence = Read-TradingLabBoundedJsonEvidenceFile `
    -Path $cleanupPath `
    -MaximumBytes 1048576 `
    -Label 'Historical Trading Lab cleanup receipt'
  $cleanup = $cleanupEvidence.Value
  $cleanupFields = @($cleanup.PSObject.Properties.Name | Sort-Object)
  if (
    (@($cleanupFields) -join '|') -cne
      (@(
        'completedAtUtc',
        'details',
        'runToken',
        'schemaVersion',
        'status'
      ) -join '|') -or
    $cleanup.schemaVersion -ne 1 -or
    [string]$cleanup.runToken -cne $runToken -or
    [string]$cleanup.status -cne 'PASS'
  ) {
    throw 'Historical Trading Lab cleanup receipt schema is invalid'
  }
  $completedAt = ConvertTo-TradingLabEvidenceDateTimeOffset `
    -Value $cleanup.completedAtUtc `
    -Label 'Historical Trading Lab cleanup receipt'
  $cleanupDetails = @($cleanup.details)
  $cleanupResources = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::Ordinal
  )
  if ($cleanupDetails.Count -eq 0) {
    throw 'Historical Trading Lab cleanup receipt details are invalid'
  }
  foreach ($detail in $cleanupDetails) {
    $detailFields = @($detail.PSObject.Properties.Name)
    $resource = [string]$detail.resource
    if (
      -not $detailFields.Contains('resource') -or
      -not $detailFields.Contains('status') -or
      [string]::IsNullOrWhiteSpace($resource) -or
      [string]$detail.status -cne 'PASS' -or
      -not $cleanupResources.Add($resource)
    ) {
      throw 'Historical Trading Lab cleanup receipt details are invalid'
    }
  }
  $fixedDetails = @(
    $cleanupDetails |
      Where-Object {
        [string]$_.resource -ceq 'fixed-validation-stack'
      }
  )
  if (
    $fixedDetails.Count -ne 1 -or
    (
      @($fixedDetails[0].PSObject.Properties.Name | Sort-Object) -join '|'
    ) -cne 'detail|resource|status' -or
    [string]$fixedDetails[0].status -cne 'PASS' -or
    [string]$fixedDetails[0].detail -cne
      'Fixed validation stack stopped with leased container IDs'
  ) {
    throw 'Historical fixed validation cleanup proof is invalid'
  }

  return [pscustomobject][ordered]@{
    SchemaVersion = 2
    ArtifactsDirectory = $directory
    IdentityFingerprint = Get-TradingLabFixedStackIdentityFingerprint `
      -ObservedContainers $ObservedContainers
    CompletedAtUtc = $completedAt.ToUniversalTime().ToString('o')
    OwnerMarkerSha256 = $markerEvidence.Sha256
    LeaseSha256 = $leaseEvidence.Sha256
    CleanupReceiptSha256 = $cleanupEvidence.Sha256
    RecoveryClosures = @()
  }
}

function Test-TradingLabStoppedStackRecoveryLink {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$ArtifactsRoot,

    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [object[]]$ObservedContainers,

    [AllowNull()]
    [object]$SourceAttempt,

    [Parameter(Mandatory = $true)]
    [object]$RecoveryProof
  )

  $root = [System.IO.Path]::GetFullPath($ArtifactsRoot)
  $recoveryDirectory = Resolve-TradingLabArtifactDirectory `
    -ArtifactsRoot $root `
    -ArtifactsDirectory ([string]$RecoveryProof.ArtifactsDirectory)
  $linkPath = Join-Path $recoveryDirectory 'cleanup-recovery-link.json'
  if (-not (Test-Path -LiteralPath $linkPath -PathType Leaf)) {
    return $false
  }

  $linkEvidence = Read-TradingLabBoundedJsonEvidenceFile `
    -Path $linkPath `
    -MaximumBytes 65536 `
    -Label 'Historical Trading Lab cleanup recovery link'
  $link = $linkEvidence.Value
  $linkFields = @($link.PSObject.Properties.Name | Sort-Object)
  $expectedLinkFields = @(
    'createdAtUtc',
    'recoveryCleanupReceiptSha256',
    'recoveryLeaseSha256',
    'recoveryOwnerMarkerSha256',
    'runToken',
    'schemaVersion',
    'sourceArtifactsDirectory',
    'sourceCleanupReceiptSha256',
    'sourceLeaseSha256',
    'sourceOwnerMarkerSha256'
  ) | Sort-Object
  if (
    (@($linkFields) -join '|') -cne (@($expectedLinkFields) -join '|') -or
    $link.schemaVersion -ne 1 -or
    [string]$link.runToken -cnotmatch '^[a-f0-9]{64}$'
  ) {
    throw 'Historical Trading Lab cleanup recovery link schema is invalid'
  }
  foreach ($hashField in @(
    'recoveryCleanupReceiptSha256',
    'recoveryLeaseSha256',
    'recoveryOwnerMarkerSha256',
    'sourceCleanupReceiptSha256',
    'sourceLeaseSha256',
    'sourceOwnerMarkerSha256'
  )) {
    if ([string]$link.$hashField -cnotmatch '^[a-f0-9]{64}$') {
      throw 'Historical Trading Lab cleanup recovery link hash is invalid'
    }
  }

  $sourceDirectory = Resolve-TradingLabArtifactDirectory `
    -ArtifactsRoot $root `
    -ArtifactsDirectory ([string]$link.sourceArtifactsDirectory)
  if ($null -eq $SourceAttempt) {
    $SourceAttempt = Get-TradingLabStoppedStackRecoveryProofFromDirectory `
      -ArtifactsRoot $root `
      -ArtifactsDirectory $sourceDirectory `
      -ObservedContainers $ObservedContainers `
      -AttemptOnly
  }
  if (-not [string]::Equals(
    $sourceDirectory,
    [System.IO.Path]::GetFullPath([string]$SourceAttempt.ArtifactsDirectory),
    [System.StringComparison]::OrdinalIgnoreCase
  )) {
    return $false
  }
  if (
    [string]$link.runToken -cne [string]$SourceAttempt.RunToken -or
    [string]$link.sourceOwnerMarkerSha256 -cne
      [string]$SourceAttempt.OwnerMarkerSha256 -or
    [string]$link.sourceLeaseSha256 -cne
      [string]$SourceAttempt.LeaseSha256
  ) {
    throw 'Historical Trading Lab cleanup recovery link source ownership is invalid'
  }

  $sourceMarkerEvidence = Read-TradingLabBoundedJsonEvidenceFile `
    -Path (Join-Path $sourceDirectory $script:TradingLabArtifactMarker) `
    -MaximumBytes 4096 `
    -Label 'Recovery source Trading Lab owner marker'
  $sourceLeaseEvidence = Read-TradingLabBoundedJsonEvidenceFile `
    -Path (Join-Path $sourceDirectory 'fixed-stack-lease.json') `
    -MaximumBytes 65536 `
    -Label 'Recovery source fixed validation stack lease'
  $sourceCleanupEvidence = Read-TradingLabBoundedJsonEvidenceFile `
    -Path (Join-Path $sourceDirectory 'cleanup-receipt.json') `
    -MaximumBytes 1048576 `
    -Label 'Recovery source Trading Lab cleanup receipt'
  if (
    [string]$sourceMarkerEvidence.Sha256 -cne
      [string]$link.sourceOwnerMarkerSha256 -or
    [string]$sourceLeaseEvidence.Sha256 -cne
      [string]$link.sourceLeaseSha256 -or
    [string]$sourceCleanupEvidence.Sha256 -cne
      [string]$link.sourceCleanupReceiptSha256
  ) {
    throw 'Historical Trading Lab cleanup recovery link source hash drifted'
  }

  $recoveryMarkerEvidence = Read-TradingLabBoundedJsonEvidenceFile `
    -Path (Join-Path $recoveryDirectory $script:TradingLabArtifactMarker) `
    -MaximumBytes 4096 `
    -Label 'Recovery Trading Lab owner marker'
  if (
    [string]$recoveryMarkerEvidence.Value.runToken -cne
      [string]$link.runToken -or
    [string]$recoveryMarkerEvidence.Sha256 -cne
      [string]$link.recoveryOwnerMarkerSha256 -or
    [string]$RecoveryProof.OwnerMarkerSha256 -cne
      [string]$link.recoveryOwnerMarkerSha256 -or
    [string]$RecoveryProof.LeaseSha256 -cne
      [string]$link.recoveryLeaseSha256 -or
    [string]$RecoveryProof.CleanupReceiptSha256 -cne
      [string]$link.recoveryCleanupReceiptSha256
  ) {
    throw 'Historical Trading Lab cleanup recovery link proof hash drifted'
  }

  $sourceCleanup = $sourceCleanupEvidence.Value
  $sourceCleanupFields = @($sourceCleanup.PSObject.Properties.Name | Sort-Object)
  if (
    (@($sourceCleanupFields) -join '|') -cne
      (@(
        'completedAtUtc',
        'details',
        'runToken',
        'schemaVersion',
        'status'
      ) -join '|') -or
    $sourceCleanup.schemaVersion -ne 1 -or
    [string]$sourceCleanup.runToken -cne [string]$link.runToken -or
    [string]$sourceCleanup.status -cne 'FAIL'
  ) {
    throw 'Historical Trading Lab cleanup recovery link source receipt is invalid'
  }
  $sourceCompletedAt = ConvertTo-TradingLabEvidenceDateTimeOffset `
    -Value $sourceCleanup.completedAtUtc `
    -Label 'Recovery source Trading Lab cleanup receipt'
  $recoveryCompletedAt = ConvertTo-TradingLabEvidenceDateTimeOffset `
    -Value $RecoveryProof.CompletedAtUtc `
    -Label 'Recovery Trading Lab cleanup receipt'
  $linkCreatedAt = ConvertTo-TradingLabEvidenceDateTimeOffset `
    -Value $link.createdAtUtc `
    -Label 'Historical Trading Lab cleanup recovery link'
  if (
    $sourceCompletedAt.UtcDateTime.Ticks -ge
      $recoveryCompletedAt.UtcDateTime.Ticks -or
    $recoveryCompletedAt.UtcDateTime.Ticks -gt
      $linkCreatedAt.UtcDateTime.Ticks
  ) {
    throw 'Historical Trading Lab cleanup recovery link chronology is invalid'
  }

  return [pscustomobject][ordered]@{
    SchemaVersion = 1
    LinkSha256 = [string]$linkEvidence.Sha256
    SourceArtifactsDirectory = $sourceDirectory
    SourceOwnerMarkerSha256 = [string]$sourceMarkerEvidence.Sha256
    SourceLeaseSha256 = [string]$sourceLeaseEvidence.Sha256
    SourceCleanupReceiptSha256 = [string]$sourceCleanupEvidence.Sha256
    SourceCompletedAtUtc = $sourceCompletedAt.ToUniversalTime().ToString('o')
    RecoveryArtifactsDirectory = $recoveryDirectory
    RecoveryOwnerMarkerSha256 = [string]$RecoveryProof.OwnerMarkerSha256
    RecoveryLeaseSha256 = [string]$RecoveryProof.LeaseSha256
    RecoveryCleanupReceiptSha256 = [string]$RecoveryProof.CleanupReceiptSha256
    RecoveryCompletedAtUtc = $recoveryCompletedAt.ToUniversalTime().ToString('o')
    LinkCreatedAtUtc = $linkCreatedAt.ToUniversalTime().ToString('o')
  }
}

function Get-TradingLabStoppedStackRecoveryClosureSortKey {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$Closure
  )

  $payload = (
    [string]$Closure.LinkSha256 + "`n" +
    [string]$Closure.SourceArtifactsDirectory + "`n" +
    [string]$Closure.RecoveryArtifactsDirectory
  )
  return Get-TradingLabSha256Hex `
    -Bytes ([System.Text.Encoding]::UTF8.GetBytes($payload))
}

function Sort-TradingLabStoppedStackRecoveryClosuresOrdinal {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [object[]]$Closures
  )

  $records = [System.Collections.Generic.List[object]]::new()
  foreach ($closure in @($Closures)) {
    $records.Add([pscustomobject][ordered]@{
      Key = Get-TradingLabStoppedStackRecoveryClosureSortKey `
        -Closure $closure
      Closure = $closure
    }) | Out-Null
  }
  $comparison = [System.Comparison[object]]{
    param($left, $right)
    return [System.StringComparer]::Ordinal.Compare(
      [string]$left.Key,
      [string]$right.Key
    )
  }
  $records.Sort($comparison)
  return @($records | ForEach-Object { $_.Closure })
}

function Select-TradingLabStoppedStackRecoveryProof {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object[]]$Proofs
  )

  if ($Proofs.Count -eq 0) {
    throw 'Fixed validation stopped stack has no recovery proof to select'
  }
  $ordered = [System.Collections.Generic.List[object]]::new()
  foreach ($proof in @($Proofs)) {
    $ordered.Add($proof) | Out-Null
  }
  $comparison = [System.Comparison[object]]{
    param($left, $right)
    $leftCompletedAt = [DateTimeOffset]$left.CompletedAtUtc
    $rightCompletedAt = [DateTimeOffset]$right.CompletedAtUtc
    $timeOrder = $rightCompletedAt.UtcDateTime.Ticks.CompareTo(
      $leftCompletedAt.UtcDateTime.Ticks
    )
    if ($timeOrder -ne 0) {
      return $timeOrder
    }
    return [System.StringComparer]::Ordinal.Compare(
      [string]$left.ArtifactsDirectory,
      [string]$right.ArtifactsDirectory
    )
  }
  $ordered.Sort($comparison)
  return $ordered[0]
}

function Resolve-TradingLabStoppedStackRecoveryProof {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [object[]]$ObservedContainers,

    [string]$ArtifactsRoot = $script:TradingLabArtifactsRoot
  )

  Assert-TradingLabFixedStackCanStart `
    -ObservedContainers $ObservedContainers | Out-Null
  $identities = ConvertTo-TradingLabFixedStackIdentityMap `
    -ObservedContainers $ObservedContainers
  if ($identities.Count -eq 0) {
    return $null
  }
  $root = [System.IO.Path]::GetFullPath($ArtifactsRoot)
  if (-not (Test-Path -LiteralPath $root -PathType Container)) {
    throw 'Fixed validation stopped-stack recovery artifacts are missing'
  }
  Assert-TradingLabArtifactPathHasNoReparsePoint -Path $root
  $matches = [System.Collections.Generic.List[object]]::new()
  $blockedAttempts = [System.Collections.Generic.List[object]]::new()
  $pending = [System.Collections.Generic.Queue[object]]::new()
  $pending.Enqueue([pscustomobject]@{ Path = $root; Depth = 0 })
  $artifactDirectories = [System.Collections.Generic.List[object]]::new()
  $visitedDirectoryCount = 0
  while ($pending.Count -ne 0) {
    $currentDirectory = $pending.Dequeue()
    foreach ($directory in @(
      Get-ChildItem `
        -LiteralPath ([string]$currentDirectory.Path) `
        -Directory `
        -Force `
        -ErrorAction Stop
    )) {
      if (
        ($directory.Attributes -band
          [System.IO.FileAttributes]::ReparsePoint) -ne 0
      ) {
        continue
      }
      $visitedDirectoryCount += 1
      if ($visitedDirectoryCount -gt 10000) {
        throw 'Fixed validation recovery artifact scan is too large'
      }
      $markerPath = Join-Path `
        $directory.FullName `
        $script:TradingLabArtifactMarker
      if (Test-Path -LiteralPath $markerPath -PathType Leaf) {
        $artifactDirectories.Add($directory) | Out-Null
      }
      $nextDepth = [int]$currentDirectory.Depth + 1
      if ($nextDepth -lt 8) {
        $pending.Enqueue([pscustomobject]@{
          Path = $directory.FullName
          Depth = $nextDepth
        })
      } else {
        $nestedDirectories = @(
          Get-ChildItem `
            -LiteralPath $directory.FullName `
            -Directory `
            -Force `
            -ErrorAction Stop |
            Where-Object {
              (
                $_.Attributes -band
                  [System.IO.FileAttributes]::ReparsePoint
              ) -eq 0
            }
        )
        if ($nestedDirectories.Count -ne 0) {
          throw 'Fixed validation recovery proof artifact scan exceeded its depth limit'
        }
      }
    }
  }
  foreach ($directory in @($artifactDirectories)) {
    if (
      ($directory.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne
        0
    ) {
      continue
    }
    $markerPath = Join-Path $directory.FullName $script:TradingLabArtifactMarker
    $leasePath = Join-Path $directory.FullName 'fixed-stack-lease.json'
    $cleanupPath = Join-Path $directory.FullName 'cleanup-receipt.json'
    if (
      -not (Test-Path -LiteralPath $markerPath -PathType Leaf) -or
      -not (Test-Path -LiteralPath $leasePath -PathType Leaf)
    ) {
      continue
    }
    $attempt = $null
    try {
      $attempt = Get-TradingLabStoppedStackRecoveryProofFromDirectory `
        -ArtifactsRoot $root `
        -ArtifactsDirectory $directory.FullName `
        -ObservedContainers $ObservedContainers `
        -AttemptOnly
    } catch {
      continue
    }
    if (-not (Test-Path -LiteralPath $cleanupPath -PathType Leaf)) {
      $blockedAttempts.Add($attempt) | Out-Null
      continue
    }
    try {
      $proof = Get-TradingLabStoppedStackRecoveryProofFromDirectory `
        -ArtifactsRoot $root `
        -ArtifactsDirectory $directory.FullName `
        -ObservedContainers $ObservedContainers
      $matches.Add($proof) | Out-Null
    } catch {
      $blockedAttempts.Add($attempt) | Out-Null
    }
  }
  $validatedMatches = [System.Collections.Generic.List[object]]::new()
  foreach ($proof in @($matches)) {
    $linkPath = Join-Path `
      ([string]$proof.ArtifactsDirectory) `
      'cleanup-recovery-link.json'
    if (-not (Test-Path -LiteralPath $linkPath -PathType Leaf)) {
      $validatedMatches.Add($proof) | Out-Null
      continue
    }
    try {
      $closure = Test-TradingLabStoppedStackRecoveryLink `
        -ArtifactsRoot $root `
        -ObservedContainers $ObservedContainers `
        -SourceAttempt $null `
        -RecoveryProof $proof
    } catch {
      throw 'Fixed validation stopped stack has an invalid cleanup recovery link'
    }
    $linkedAttempts = @(
      $blockedAttempts |
        Where-Object {
          [string]::Equals(
            [string]$_.ArtifactsDirectory,
            [string]$closure.SourceArtifactsDirectory,
            [System.StringComparison]::OrdinalIgnoreCase
          ) -and
          [string]$_.OwnerMarkerSha256 -ceq
            [string]$closure.SourceOwnerMarkerSha256 -and
          [string]$_.LeaseSha256 -ceq
            [string]$closure.SourceLeaseSha256
        }
    )
    if ($linkedAttempts.Count -ne 1) {
      throw 'Fixed validation stopped stack cleanup recovery source is invalid'
    }
    $proof.RecoveryClosures = @($closure)
    $validatedMatches.Add($proof) | Out-Null
  }
  $matches = $validatedMatches

  if ($blockedAttempts.Count -ne 0) {
    $unclosedAttempts = [System.Collections.Generic.List[object]]::new()
    foreach ($attempt in @($blockedAttempts)) {
      $closed = $false
      foreach ($proof in @($matches)) {
        foreach ($closure in @($proof.RecoveryClosures)) {
          if (
            [string]::Equals(
              [string]$attempt.ArtifactsDirectory,
              [string]$closure.SourceArtifactsDirectory,
              [System.StringComparison]::OrdinalIgnoreCase
            ) -and
            [string]$attempt.OwnerMarkerSha256 -ceq
              [string]$closure.SourceOwnerMarkerSha256 -and
            [string]$attempt.LeaseSha256 -ceq
              [string]$closure.SourceLeaseSha256
          ) {
            $closed = $true
            break
          }
        }
        if ($closed) {
          break
        }
      }
      if (-not $closed) {
        $unclosedAttempts.Add($attempt) | Out-Null
      }
    }
    if ($unclosedAttempts.Count -ne 0) {
      throw (
        'Fixed validation stopped stack has an unclosed owned lease attempt'
      )
    }
  }
  if ($matches.Count -eq 0) {
    throw 'Fixed validation stopped stack has no durable owned cleanup proof'
  }
  $selectedProof = Select-TradingLabStoppedStackRecoveryProof `
    -Proofs @($matches)
  $closures = @(
    $matches |
      ForEach-Object { @($_.RecoveryClosures) }
  )
  $selectedProof.RecoveryClosures = @(
    Sort-TradingLabStoppedStackRecoveryClosuresOrdinal -Closures $closures
  )
  return $selectedProof
}

function Assert-TradingLabStoppedStackRecoveryProof {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$Proof,

    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [object[]]$ObservedContainers,

    [string]$ArtifactsRoot = $script:TradingLabArtifactsRoot
  )

  $fields = @($Proof.PSObject.Properties.Name | Sort-Object)
  if (
    (@($fields) -join '|') -cne
      (@(
        'ArtifactsDirectory',
        'CleanupReceiptSha256',
        'CompletedAtUtc',
        'IdentityFingerprint',
        'LeaseSha256',
        'OwnerMarkerSha256',
        'RecoveryClosures',
        'SchemaVersion'
      ) -join '|') -or
    $Proof.SchemaVersion -ne 2 -or
    $Proof.RecoveryClosures -isnot [System.Array]
  ) {
    throw 'Fixed validation stopped-stack recovery proof schema is invalid'
  }
  $current = Resolve-TradingLabStoppedStackRecoveryProof `
    -ArtifactsRoot $ArtifactsRoot `
    -ObservedContainers $ObservedContainers
  if (
    $current.SchemaVersion -ne 2 -or
    $current.RecoveryClosures -isnot [System.Array]
  ) {
    throw 'Fixed validation stopped-stack recovery proof schema drifted'
  }
  foreach ($field in @(
    'ArtifactsDirectory',
    'IdentityFingerprint',
    'OwnerMarkerSha256',
    'LeaseSha256',
    'CleanupReceiptSha256'
  )) {
    if ([string]$current.$field -cne [string]$Proof.$field) {
      throw 'Fixed validation stopped-stack recovery proof drifted'
    }
  }
  $expectedClosures = @($Proof.RecoveryClosures)
  $currentClosures = @($current.RecoveryClosures)
  if ($expectedClosures.Count -ne $currentClosures.Count) {
    throw 'Fixed validation stopped-stack recovery proof closure count drifted'
  }
  for ($closureIndex = 0; $closureIndex -lt $expectedClosures.Count; $closureIndex++) {
    $expectedClosure = $expectedClosures[$closureIndex]
    $currentClosure = $currentClosures[$closureIndex]
    $expectedClosureFields = @(
      $expectedClosure.PSObject.Properties.Name | Sort-Object
    )
    $currentClosureFields = @(
      $currentClosure.PSObject.Properties.Name | Sort-Object
    )
    $closureFields = @(
      'LinkCreatedAtUtc',
      'LinkSha256',
      'RecoveryArtifactsDirectory',
      'RecoveryCleanupReceiptSha256',
      'RecoveryCompletedAtUtc',
      'RecoveryLeaseSha256',
      'RecoveryOwnerMarkerSha256',
      'SchemaVersion',
      'SourceArtifactsDirectory',
      'SourceCleanupReceiptSha256',
      'SourceCompletedAtUtc',
      'SourceLeaseSha256',
      'SourceOwnerMarkerSha256'
    ) | Sort-Object
    if (
      (@($expectedClosureFields) -join '|') -cne
        (@($closureFields) -join '|') -or
      (@($currentClosureFields) -join '|') -cne
        (@($closureFields) -join '|') -or
      $expectedClosure.SchemaVersion -ne 1 -or
      $currentClosure.SchemaVersion -ne 1
    ) {
      throw 'Fixed validation stopped-stack recovery proof link schema drifted'
    }
    foreach ($hashField in @(
      'LinkSha256',
      'RecoveryCleanupReceiptSha256',
      'RecoveryLeaseSha256',
      'RecoveryOwnerMarkerSha256',
      'SourceCleanupReceiptSha256',
      'SourceLeaseSha256',
      'SourceOwnerMarkerSha256'
    )) {
      if (
        [string]$expectedClosure.$hashField -cnotmatch '^[a-f0-9]{64}$' -or
        [string]$currentClosure.$hashField -cne
          [string]$expectedClosure.$hashField
      ) {
        throw 'Fixed validation stopped-stack recovery proof link hash drifted'
      }
    }
    foreach ($pathField in @(
      'SourceArtifactsDirectory',
      'RecoveryArtifactsDirectory'
    )) {
      $expectedDirectory = Resolve-TradingLabArtifactDirectory `
        -ArtifactsRoot $ArtifactsRoot `
        -ArtifactsDirectory ([string]$expectedClosure.$pathField)
      if (
        -not [string]::Equals(
          $expectedDirectory,
          [string]$currentClosure.$pathField,
          [System.StringComparison]::OrdinalIgnoreCase
        )
      ) {
        throw 'Fixed validation stopped-stack recovery proof link path drifted'
      }
    }
    foreach ($timestampField in @(
      'SourceCompletedAtUtc',
      'RecoveryCompletedAtUtc',
      'LinkCreatedAtUtc'
    )) {
      $expectedTimestamp = ConvertTo-TradingLabEvidenceDateTimeOffset `
        -Value $expectedClosure.$timestampField `
        -Label "Expected stopped-stack recovery link $timestampField"
      $currentTimestamp = ConvertTo-TradingLabEvidenceDateTimeOffset `
        -Value $currentClosure.$timestampField `
        -Label "Current stopped-stack recovery link $timestampField"
      if (
        $expectedTimestamp.UtcDateTime.Ticks -ne
          $currentTimestamp.UtcDateTime.Ticks
      ) {
        throw 'Fixed validation stopped-stack recovery proof link timestamp drifted'
      }
    }
  }
  $expectedCompletedAt = ConvertTo-TradingLabEvidenceDateTimeOffset `
    -Value $Proof.CompletedAtUtc `
    -Label 'Expected fixed validation stopped-stack recovery proof'
  $currentCompletedAt = ConvertTo-TradingLabEvidenceDateTimeOffset `
    -Value $current.CompletedAtUtc `
    -Label 'Current fixed validation stopped-stack recovery proof'
  if (
    $expectedCompletedAt.UtcDateTime.Ticks -ne
      $currentCompletedAt.UtcDateTime.Ticks
  ) {
    throw 'Fixed validation stopped-stack recovery proof timestamp drifted'
  }
  return $true
}

function New-TradingLabFixedStackStartBaseline {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [object[]]$ObservedContainers,

    [string]$ArtifactsRoot = $script:TradingLabArtifactsRoot
  )

  Assert-TradingLabFixedStackCanStart `
    -ObservedContainers $ObservedContainers | Out-Null
  $identities = ConvertTo-TradingLabFixedStackIdentityMap `
    -ObservedContainers $ObservedContainers
  $ordered = @(
    foreach ($service in @(Get-TradingLabFixedStackServiceNames)) {
      if ($identities.ContainsKey($service)) {
        $identities[$service]
      }
    }
  )
  $mode = if ($identities.Count -eq 0) { 'EMPTY' } else { 'FULL_EXITED' }
  $proof = if ($mode -ceq 'EMPTY') {
    $null
  } else {
    Resolve-TradingLabStoppedStackRecoveryProof `
      -ObservedContainers $ObservedContainers `
      -ArtifactsRoot $ArtifactsRoot
  }
  return [pscustomobject][ordered]@{
    SchemaVersion = 1
    Mode = $mode
    IdentityFingerprint = Get-TradingLabFixedStackIdentityFingerprint `
      -ObservedContainers $ObservedContainers
    ServiceIdentities = $ordered
    RecoveryProof = $proof
  }
}

function Assert-TradingLabFixedStackBaselineCurrent {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$Baseline,

    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [object[]]$ObservedContainers,

    [string]$ArtifactsRoot = $script:TradingLabArtifactsRoot
  )

  $fields = @($Baseline.PSObject.Properties.Name | Sort-Object)
  if (
    (@($fields) -join '|') -cne
      (@(
        'IdentityFingerprint',
        'Mode',
        'RecoveryProof',
        'SchemaVersion',
        'ServiceIdentities'
      ) -join '|') -or
    $Baseline.SchemaVersion -ne 1 -or
    [string]$Baseline.Mode -cnotin @('EMPTY', 'FULL_EXITED')
  ) {
    throw 'Fixed validation stack start baseline schema is invalid'
  }
  Assert-TradingLabFixedStackCanStart `
    -ObservedContainers $ObservedContainers | Out-Null
  $observedFingerprint = Get-TradingLabFixedStackIdentityFingerprint `
    -ObservedContainers $ObservedContainers
  $baselineFingerprint = Get-TradingLabFixedStackIdentityFingerprint `
    -ObservedContainers @($Baseline.ServiceIdentities)
  if (
    $observedFingerprint -cne [string]$Baseline.IdentityFingerprint -or
    $baselineFingerprint -cne [string]$Baseline.IdentityFingerprint
  ) {
    throw 'Fixed validation stack start baseline identity drifted'
  }
  $observedCount = @($ObservedContainers).Count
  if (
    (
      [string]$Baseline.Mode -ceq 'EMPTY' -and
      ($observedCount -ne 0 -or $null -ne $Baseline.RecoveryProof)
    ) -or
    (
      [string]$Baseline.Mode -ceq 'FULL_EXITED' -and
      (
        $observedCount -ne @(Get-TradingLabFixedStackServiceNames).Count -or
        $null -eq $Baseline.RecoveryProof
      )
    )
  ) {
    throw 'Fixed validation stack start baseline mode drifted'
  }
  if ([string]$Baseline.Mode -ceq 'FULL_EXITED') {
    Assert-TradingLabStoppedStackRecoveryProof `
      -Proof $Baseline.RecoveryProof `
      -ObservedContainers $ObservedContainers `
      -ArtifactsRoot $ArtifactsRoot | Out-Null
  }
  return $true
}

function Write-TradingLabFixedStackLeaseAtomic {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$LeasePath,

    [Parameter(Mandatory = $true)]
    [object]$Lease
  )

  $target = [System.IO.Path]::GetFullPath($LeasePath)
  $parent = Split-Path -Parent $target
  if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
    throw 'Fixed validation stack lease parent is missing'
  }
  if (Test-Path -LiteralPath $target) {
    throw 'Fixed validation stack lease collision: target already exists'
  }
  $temporary = Join-Path `
    $parent `
    ('.fixed-stack-lease-' + [guid]::NewGuid().ToString('N') + '.tmp')
  $json = $Lease | ConvertTo-Json -Depth 12 -Compress
  $payload = [System.Text.UTF8Encoding]::new($false).GetBytes(
    $json + [System.Environment]::NewLine
  )
  try {
    $stream = [System.IO.FileStream]::new(
      $temporary,
      [System.IO.FileMode]::CreateNew,
      [System.IO.FileAccess]::Write,
      [System.IO.FileShare]::None
    )
    try {
      $stream.Write($payload, 0, $payload.Length)
      $stream.Flush($true)
    } finally {
      $stream.Dispose()
    }
    try {
      [System.IO.File]::Move($temporary, $target)
    } catch {
      throw 'Fixed validation stack lease collision during atomic publish'
    }
  } finally {
    if (Test-Path -LiteralPath $temporary -PathType Leaf) {
      Remove-Item -LiteralPath $temporary -Force
    }
  }
  return $target
}

function Read-TradingLabFixedStackLease {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$LeasePath,

    [Parameter(Mandatory = $true)]
    [string]$RunToken
  )

  Assert-TradingLabRunToken -RunToken $RunToken
  $target = [System.IO.Path]::GetFullPath($LeasePath)
  if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
    throw 'Fixed validation stack lease is missing'
  }
  $item = Get-Item -LiteralPath $target -Force
  if (
    ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0
  ) {
    throw 'Fixed validation stack lease cannot be a reparse point'
  }
  try {
    $lease = [System.IO.File]::ReadAllText($target) | ConvertFrom-Json
  } catch {
    throw 'Fixed validation stack lease is invalid'
  }
  if (
    $lease.SchemaVersion -ne 1 -or
    [string]$lease.RunToken -cne $RunToken -or
    [string]$lease.ComposeProject -cne $script:TradingLabComposeProject -or
    [string]$lease.ComposeFile -cne $script:TradingLabComposeFile
  ) {
    throw 'Fixed validation stack lease does not belong to this run'
  }
  $identities = ConvertTo-TradingLabFixedStackIdentityMap `
    -ObservedContainers @($lease.ServiceIdentities)
  if ($identities.Count -ne @(Get-TradingLabFixedStackServiceNames).Count) {
    throw 'Fixed validation stack lease service identities are incomplete'
  }
  foreach ($identity in $identities.Values) {
    if (
      (
        [string]$identity.Service -ceq 'validation-backend' -and
        [string]$identity.State -cnotin @('running', 'exited')
      ) -or
      (
        [string]$identity.Service -cne 'validation-backend' -and
        [string]$identity.State -cne 'running'
      )
    ) {
      throw 'Fixed validation stack lease contains a non-running service'
    }
  }
  return $lease
}

function Acquire-TradingLabFixedStackGuardAtomic {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$GuardPath,

    [Parameter(Mandatory = $true)]
    [string]$RunToken,

    [Parameter(Mandatory = $true)]
    [object]$Baseline
  )

  Assert-TradingLabRunToken -RunToken $RunToken
  $baselineArtifactsRoot = Split-Path -Parent (
    [System.IO.Path]::GetFullPath($GuardPath)
  )
  Assert-TradingLabFixedStackBaselineCurrent `
    -Baseline $Baseline `
    -ObservedContainers @($Baseline.ServiceIdentities) `
    -ArtifactsRoot $baselineArtifactsRoot | Out-Null
  $guard = [ordered]@{
    schemaVersion = 2
    runToken = $RunToken
    composeProject = $script:TradingLabComposeProject
    composeFile = $script:TradingLabComposeFile
    acquiredAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    baseline = $Baseline
  }
  $guardPayload = ($guard | ConvertTo-Json -Depth 16 -Compress) +
    [System.Environment]::NewLine
  $guardByteCount = [System.Text.UTF8Encoding]::new($false).GetByteCount(
    $guardPayload
  )
  if ($guardByteCount -gt 1048576) {
    throw 'Fixed validation stack global guard size exceeds its read limit'
  }
  Write-TradingLabOwnedJsonAtomic `
    -Path $GuardPath `
    -Value $guard `
    -Label 'Fixed validation stack global guard' | Out-Null
  return [pscustomobject]$guard
}

function Assert-TradingLabFixedStackGuardOwned {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$GuardPath,

    [Parameter(Mandatory = $true)]
    [string]$RunToken
  )

  Assert-TradingLabRunToken -RunToken $RunToken
  $guard = Read-TradingLabBoundedJsonFile `
    -Path $GuardPath `
    -MaximumBytes 1048576 `
    -Label 'Fixed validation stack global guard'
  if (
    (@($guard.PSObject.Properties.Name | Sort-Object) -join '|') -cne
      (@(
        'acquiredAtUtc',
        'baseline',
        'composeFile',
        'composeProject',
        'runToken',
        'schemaVersion'
      ) -join '|') -or
    $guard.schemaVersion -ne 2 -or
    [string]$guard.runToken -cne $RunToken -or
    [string]$guard.composeProject -cne $script:TradingLabComposeProject -or
    [string]$guard.composeFile -cne $script:TradingLabComposeFile -or
    $null -eq $guard.baseline
  ) {
    throw 'Fixed validation stack global guard belongs to another run'
  }
  $acquiredAt = [DateTimeOffset]::MinValue
  if (-not [DateTimeOffset]::TryParse(
    [string]$guard.acquiredAtUtc,
    [ref]$acquiredAt
  )) {
    throw 'Fixed validation stack global guard timestamp is invalid'
  }
  $baselineArtifactsRoot = Split-Path -Parent (
    [System.IO.Path]::GetFullPath($GuardPath)
  )
  Assert-TradingLabFixedStackBaselineCurrent `
    -Baseline $guard.baseline `
    -ObservedContainers @($guard.baseline.ServiceIdentities) `
    -ArtifactsRoot $baselineArtifactsRoot | Out-Null
  return $guard
}

function Release-TradingLabFixedStackGuard {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$GuardPath,

    [Parameter(Mandatory = $true)]
    [string]$RunToken
  )

  Assert-TradingLabFixedStackGuardOwned `
    -GuardPath $GuardPath `
    -RunToken $RunToken | Out-Null
  [System.IO.File]::Delete([System.IO.Path]::GetFullPath($GuardPath))
  if (Test-Path -LiteralPath $GuardPath) {
    throw 'Fixed validation stack global guard release failed'
  }
  return $true
}

function Get-TradingLabCommandFingerprint {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$Executable,

    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [string[]]$Arguments
  )

  if ([string]::IsNullOrWhiteSpace($Executable)) {
    throw 'Trading Lab process executable is required'
  }
  $canonical = (@($Executable) + @($Arguments)) -join "`0"
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($canonical)
  $sha = [System.Security.Cryptography.SHA256]::Create()
  try {
    $hex = [System.BitConverter]::ToString($sha.ComputeHash($bytes))
    return $hex.Replace('-', '').ToLowerInvariant()
  } finally {
    $sha.Dispose()
  }
}

function New-TradingLabOwnedProcessReceipt {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$RunToken,

    [Parameter(Mandatory = $true)]
    [string]$Name,

    [Parameter(Mandatory = $true)]
    [int]$ProcessId,

    [Parameter(Mandatory = $true)]
    [DateTimeOffset]$StartedAtUtc,

    [Parameter(Mandatory = $true)]
    [string]$Executable,

    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [string[]]$Arguments
  )

  Assert-TradingLabRunToken -RunToken $RunToken
  if ([string]::IsNullOrWhiteSpace($Name) -or $ProcessId -le 0) {
    throw 'Trading Lab owned process name and PID are required'
  }
  return [pscustomobject][ordered]@{
    SchemaVersion = 1
    RunToken = $RunToken
    Name = $Name
    ProcessId = $ProcessId
    StartedAtUtc = $StartedAtUtc.ToString('o')
    Executable = $Executable
    Arguments = @($Arguments)
    CommandFingerprint = Get-TradingLabCommandFingerprint `
      -Executable $Executable `
      -Arguments @($Arguments)
  }
}

function Assert-TradingLabOwnedProcessCanStop {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$RunToken,

    [Parameter(Mandatory = $true)]
    [object]$Receipt,

    [Parameter(Mandatory = $true)]
    [object]$ObservedProcess
  )

  Assert-TradingLabRunToken -RunToken $RunToken
  foreach ($propertyName in @(
    'SchemaVersion',
    'RunToken',
    'Name',
    'ProcessId',
    'StartedAtUtc',
    'Executable',
    'Arguments',
    'CommandFingerprint'
  )) {
    if ($null -eq $Receipt.PSObject.Properties[$propertyName]) {
      throw "Trading Lab owned process receipt is missing $propertyName"
    }
  }
  foreach ($propertyName in @(
    'ProcessId',
    'StartedAtUtc',
    'Executable',
    'Arguments'
  )) {
    if ($null -eq $ObservedProcess.PSObject.Properties[$propertyName]) {
      throw "Observed Trading Lab process is missing $propertyName"
    }
  }
  $observedFingerprint = Get-TradingLabCommandFingerprint `
    -Executable ([string]$ObservedProcess.Executable) `
    -Arguments @($ObservedProcess.Arguments)
  $receiptFingerprint = Get-TradingLabCommandFingerprint `
    -Executable ([string]$Receipt.Executable) `
    -Arguments @($Receipt.Arguments)
  $receiptStarted = [DateTimeOffset]$Receipt.StartedAtUtc
  $observedStarted = [DateTimeOffset]$ObservedProcess.StartedAtUtc
  if (
    $Receipt.SchemaVersion -ne 1 -or
    [string]$Receipt.RunToken -cne $RunToken -or
    [int]$Receipt.ProcessId -ne [int]$ObservedProcess.ProcessId -or
    $receiptStarted -ne $observedStarted -or
    [string]$Receipt.Executable -cne [string]$ObservedProcess.Executable -or
    [string]$Receipt.CommandFingerprint -cne $receiptFingerprint -or
    [string]$Receipt.CommandFingerprint -cne $observedFingerprint
  ) {
    throw 'Trading Lab owned process identity drift prevents stop'
  }
  return $true
}

function New-TradingLabRunCommandRecord {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$Spec,

    [Parameter(Mandatory = $true)]
    [ValidateSet('PASS', 'FAIL', 'BLOCKED', 'NOT_RUN', 'PARTIAL')]
    [string]$Classification,

    [AllowNull()]
    [object]$StartedAtUtc,

    [AllowNull()]
    [object]$EndedAtUtc,

    [AllowNull()]
    [object]$ExitCode,

    [AllowNull()]
    [string]$StdoutPath,

    [AllowNull()]
    [string]$StderrPath,

    [AllowNull()]
    [string]$Reason,

    [AllowNull()]
    [object]$SurefireSummary
  )

  $normalizedSurefire = $null
  if ($null -ne $SurefireSummary) {
    $surefireValues = [ordered]@{}
    foreach ($field in @(
      'Tests',
      'Failures',
      'Errors',
      'Skipped',
      'Classes'
    )) {
      if ($null -eq $SurefireSummary.PSObject.Properties[$field]) {
        throw "Trading Lab Surefire command summary is missing $field"
      }
      $value = 0
      if (
        -not [int]::TryParse(
          [string]$SurefireSummary.PSObject.Properties[$field].Value,
          [ref]$value
        ) -or
        $value -lt 0
      ) {
        throw "Trading Lab Surefire command summary has invalid $field"
      }
      $surefireValues[$field] = $value
    }
    if (
      $surefireValues.Tests -lt 1 -or
      $surefireValues.Classes -lt 1 -or
      (
        $surefireValues.Failures +
        $surefireValues.Errors +
        $surefireValues.Skipped
      ) -gt $surefireValues.Tests
    ) {
      throw 'Trading Lab Surefire command summary counts are invalid'
    }
    $normalizedSurefire = [pscustomobject]$surefireValues
  }

  return [pscustomobject][ordered]@{
    PhaseNumber = [int]$Spec.PhaseNumber
    PhaseName = [string]$Spec.PhaseName
    Name = [string]$Spec.Id
    Operation = [string]$Spec.Operation
    Executable = [string]$Spec.Executable
    Arguments = @($Spec.Arguments)
    WorkingDirectory = [string]$Spec.WorkingDirectory
    Classification = $Classification
    Status = $Classification
    StartedAtUtc = if ($null -eq $StartedAtUtc) {
      $null
    } else {
      ([DateTimeOffset]$StartedAtUtc).ToString('o')
    }
    EndedAtUtc = if ($null -eq $EndedAtUtc) {
      $null
    } else {
      ([DateTimeOffset]$EndedAtUtc).ToString('o')
    }
    ExitCode = if ($null -eq $ExitCode) {
      $null
    } else {
      [int]$ExitCode
    }
    StdoutPath = if (
      [string]::IsNullOrWhiteSpace($StdoutPath)
    ) { $null } else { $StdoutPath }
    StderrPath = if (
      [string]::IsNullOrWhiteSpace($StderrPath)
    ) { $null } else { $StderrPath }
    Reason = if (
      [string]::IsNullOrWhiteSpace($Reason)
    ) { $null } else { $Reason }
    Surefire = $normalizedSurefire
  }
}

function Get-TradingLabPhaseResultStatus {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [object[]]$Commands
  )

  if ($Commands.Count -eq 0) {
    return 'NOT_RUN'
  }
  $classifications = @($Commands.Classification)
  if ($classifications -contains 'FAIL') {
    return 'FAIL'
  }
  if ($classifications -contains 'BLOCKED') {
    return 'BLOCKED'
  }
  if ($classifications -contains 'PARTIAL') {
    return 'PARTIAL'
  }
  if ($classifications -notcontains 'PASS') {
    return 'NOT_RUN'
  }
  if ($classifications -contains 'NOT_RUN') {
    return 'PARTIAL'
  }
  return 'PASS'
}

function Get-TradingLabRunResultStatus {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [object[]]$Phases
  )

  $statuses = @($Phases.Status)
  if ($statuses -contains 'FAIL') {
    return 'FAIL'
  }
  if ($statuses -contains 'BLOCKED') {
    return 'BLOCKED'
  }
  if (
    $statuses -contains 'PARTIAL' -or
    $statuses -contains 'NOT_RUN'
  ) {
    return 'PARTIAL'
  }
  return 'PASS'
}

function Get-TradingLabVerificationReportTemplate {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$RunResult
  )

  $lines = [System.Collections.Generic.List[string]]::new()
  $lines.Add('# Trading Lab Verification Report (provisional)') | Out-Null
  $lines.Add('') | Out-Null
  $lines.Add("Overall status: $($RunResult.Status)") | Out-Null
  $lines.Add('Cleanup status: PENDING') | Out-Null
  $lines.Add(
    'This provisional report must be finalized only after an owned cleanup receipt.'
  ) | Out-Null
  $lines.Add('') | Out-Null
  $lines.Add('## Implementation and verification scope') | Out-Null
  $lines.Add('') | Out-Null
  $lines.Add(
    'The runner records the Admin Trading Lab route, permissions, APIs, ' +
    'validation lifecycle, local calculation, report schema, fixed/random ' +
    'scenarios, isolation, large-report, backend, frontend, and architecture gates.'
  ) | Out-Null
  $lines.Add('') | Out-Null
  $lines.Add('## Admin route and permissions') | Out-Null
  $lines.Add('') | Out-Null
  $lines.Add(
    '- Desktop-only Admin route: `/trading/lab`.'
  ) | Out-Null
  $lines.Add(
    '- `TRADING_LAB_VIEW` reads scenarios, runs, events, and reports.'
  ) | Out-Null
  $lines.Add(
    '- `TRADING_LAB_EXECUTE` creates and controls runs but cannot control infrastructure.'
  ) | Out-Null
  $lines.Add(
    '- `SUPER_ADMIN` alone controls validation infrastructure and confirms >50 MiB print.'
  ) | Out-Null
  $lines.Add('') | Out-Null
  $lines.Add('## API surface and validation lifecycle') | Out-Null
  $lines.Add('') | Out-Null
  foreach ($api in @(
    '/api/admin/trading-lab/config',
    '/api/admin/trading-lab/scenarios',
    '/api/admin/trading-lab/scenarios/{id}',
    '/api/admin/trading-lab/scenarios/{id}/runs',
    '/api/admin/trading-lab/runs/{id}',
    '/api/admin/trading-lab/runs/{id}/events',
    '/api/admin/trading-lab/runs/{id}/pause',
    '/api/admin/trading-lab/runs/{id}/resume',
    '/api/admin/trading-lab/runs/{id}/cancel',
    '/api/admin/trading-lab/reports/{id}',
    '/api/admin/trading-lab/reports/{id}/download',
    '/api/admin/trading-lab/reports/{id}/permanent',
    '/api/admin/trading-lab/reports/{id}/print-info',
    '/api/admin/trading-lab/reports/{id}/print-confirmation',
    '/api/admin/trading-lab/reports/{id}/print',
    '/api/admin/trading-lab/environment',
    '/api/admin/trading-lab/environment/{action}',
    '/internal/validation/reset',
    '/internal/validation/state',
    '/internal/validation/accounts/{accountId}/seed',
    '/internal/validation/market/path',
    '/internal/validation/system/step',
    '/internal/validation/runs'
  )) {
    $lines.Add('- `' + $api + '`') | Out-Null
  }
  $lines.Add(
    '- Lifecycle evidence orders Supervisor `start` -> `status` -> `health`, ' +
    'then performs validation `reset`; phase 15 records owned `stop` cleanup.'
  ) | Out-Null
  $lines.Add('') | Out-Null
  $lines.Add('## Independent local Oracle rules') | Out-Null
  $lines.Add('') | Out-Null
  $lines.Add(
    '- Browser Oracle uses decimal fixed-point arithmetic independently of backend trading code.'
  ) | Out-Null
  $lines.Add(
    '- Spot checks wallet debits/credits, weighted-average cost, asset ledger, and account summary.'
  ) | Out-Null
  $lines.Add(
    '- Perpetual checks isolated and cross margin, realized/unrealized PnL, protection, funding, and liquidation.'
  ) | Out-Null
  $lines.Add(
    '- Deterministic virtual time and canonical scenario hashes make fixed and random replay comparable.'
  ) | Out-Null
  $lines.Add('') | Out-Null
  $lines.Add('## Report schema and scenario fixtures') | Out-Null
  $lines.Add('') | Out-Null
  $lines.Add(
    '- The 14-section schema is: metadata, actor, environment, scenario, ' +
    'modelVersion, configSnapshot, localCalculation, lifecycle, apiTrace, ' +
    'marketTicks, checkpoints, actualState, errors, cleanup.'
  ) | Out-Null
  $lines.Add(
    '- `API_TRACE` evidence is bounded and credential-sanitized before durable report append.'
  ) | Out-Null
  $lines.Add(
    '- Fixtures contain 24 fixed categories (from `phase4-fixed-01-spot-cycle`) ' +
    'and deterministic 12 legal plus 12 negative random seeds ' +
    '(from `phase4-random-legal-01-spot-balanced`).'
  ) | Out-Null
  $lines.Add('') | Out-Null
  $lines.Add('## Primary implementation files') | Out-Null
  $lines.Add('') | Out-Null
  foreach ($relativePath in @(
    'scripts\run-trading-lab-validation.ps1',
    'scripts\smoke-trading-lab.mjs',
    'scripts\smoke-trading-lab-isolated.mjs',
    'scripts\verify-trading-lab-runtime-isolation.mjs',
    'apps\admin\src\features\tradingLab\TradingLabPage.tsx',
    'apps\admin\src\features\tradingLab\api\tradingLabApi.ts',
    'backend\src\main\java\com\fxplatform\tradinglab\admin\TradingLabAdminController.java',
    'backend\src\main\java\com\fxplatform\tradinglab\admin\TradingLabReportController.java',
    'docs\testing\trading-lab\fixed-scenarios.json',
    'docs\testing\trading-lab\random-seeds.json',
    'docs\testing\trading-lab\report-schema.json'
  )) {
    $lines.Add(
      '- ' + [System.IO.Path]::GetFullPath(
        (Join-Path $script:TradingLabPlatformRoot $relativePath)
      )
    ) | Out-Null
  }
  $lines.Add('') | Out-Null
  $lines.Add('## Command evidence') | Out-Null
  $lines.Add('') | Out-Null
  $lines.Add(
    '| Phase | Command | Status | Exit | Tests | Failures | Errors | ' +
    'Skipped | CWD | Executable and argv | Started UTC | Ended UTC | ' +
    'stdout | stderr |'
  ) | Out-Null
  $lines.Add(
    '|---:|---|---|---:|---:|---:|---:|---:|---|---|---|---|---|---|'
  ) | Out-Null
  foreach ($phase in @($RunResult.Phases)) {
    foreach ($command in @($phase.Commands)) {
      $exit = if ($null -eq $command.ExitCode) {
        ''
      } else {
        [string]$command.ExitCode
      }
      $surefire = if (
        $null -eq $command.PSObject.Properties['Surefire']
      ) {
        $null
      } else {
        $command.Surefire
      }
      $tests = if ($null -eq $surefire) { '' } else {
        [string]$surefire.Tests
      }
      $failures = if ($null -eq $surefire) { '' } else {
        [string]$surefire.Failures
      }
      $errors = if ($null -eq $surefire) { '' } else {
        [string]$surefire.Errors
      }
      $skipped = if ($null -eq $surefire) { '' } else {
        [string]$surefire.Skipped
      }
      $argv = (@($command.Executable) + @($command.Arguments)) -join ' '
      $lines.Add(
        "| $($phase.Number) | $($command.Name) | " +
        "$($command.Classification) | $exit | $tests | $failures | " +
        "$errors | $skipped | " +
        "$($command.WorkingDirectory) | $argv | " +
        "$($command.StartedAtUtc) | $($command.EndedAtUtc) | " +
        "$($command.StdoutPath) | $($command.StderrPath) |"
      ) | Out-Null
    }
  }
  $lines.Add('') | Out-Null
  $lines.Add('## Failed, blocked, partial, or not-run items') | Out-Null
  $lines.Add('') | Out-Null
  $nonPassing = @(
    $RunResult.Phases |
      ForEach-Object { @($_.Commands) } |
      Where-Object Classification -cne 'PASS'
  )
  if ($nonPassing.Count -eq 0) {
    $lines.Add('None in the provisional command set.') | Out-Null
  } else {
    foreach ($command in $nonPassing) {
      $lines.Add(
        "- $($command.Name): $($command.Classification) - $($command.Reason)"
      ) | Out-Null
    }
  }
  $lines.Add('') | Out-Null
  $lines.Add('## Known limitations') | Out-Null
  $lines.Add('') | Out-Null
  $lines.Add(
    'This document is provisional until phase 15 supplies a cleanup receipt; ' +
    'no skipped or not-run gate is counted as PASS.'
  ) | Out-Null
  $lines.Add('') | Out-Null
  $lines.Add('## Cleanup receipt') | Out-Null
  $lines.Add('') | Out-Null
  $lines.Add('Cleanup receipt: {{CLEANUP_RECEIPT}}') | Out-Null
  return $lines -join [System.Environment]::NewLine
}

function Invoke-TradingLabValidationRun {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$Context,

    [Parameter(Mandatory = $true)]
    [object]$PackageScripts,

    [Parameter(Mandatory = $true)]
    [scriptblock]$InvokeCommand,

    [Parameter(Mandatory = $true)]
    [scriptblock]$GetUtcNow,

    [switch]$SkipBrowser,

    [switch]$ThrowOnFailure
  )

  $phaseSpecs = @(
    Get-TradingLabNormalPhaseSpecs `
      -Context $Context `
      -PackageScripts $PackageScripts
  )
  $phaseResults = [System.Collections.Generic.List[object]]::new()
  $failures = [System.Collections.Generic.List[System.Exception]]::new()
  $businessHalted = $false
  $provisionalTemplate = $null

  foreach ($phaseSpec in $phaseSpecs) {
    $commandResults = [System.Collections.Generic.List[object]]::new()
    foreach ($command in @($phaseSpec.Commands)) {
      if ($phaseSpec.Number -eq 14) {
        $provisional = [pscustomobject]@{
          Status = Get-TradingLabRunResultStatus -Phases @($phaseResults)
          Phases = @($phaseResults)
        }
        $provisionalTemplate = Get-TradingLabVerificationReportTemplate `
          -RunResult $provisional
        $command.Payload = $provisionalTemplate
      }

      $skipReason = $null
      $skipClassification = $null
      if ($phaseSpec.Number -le 13 -and $businessHalted) {
        $skipClassification = 'NOT_RUN'
        $skipReason = 'Not run after the first blocking or failed command'
      } elseif ($phaseSpec.Number -eq 11 -and $SkipBrowser) {
        $skipClassification = 'NOT_RUN'
        $skipReason = 'Browser smoke was explicitly skipped by -SkipBrowser'
      } elseif ($command.Availability -ceq 'BLOCKED') {
        $skipClassification = 'BLOCKED'
        $skipReason = [string]$command.UnavailableReason
      }

      if ($null -ne $skipClassification) {
        $record = New-TradingLabRunCommandRecord `
          -Spec $command `
          -Classification $skipClassification `
          -StartedAtUtc $null `
          -EndedAtUtc $null `
          -ExitCode $null `
          -StdoutPath $null `
          -StderrPath $null `
          -Reason $skipReason
        $commandResults.Add($record) | Out-Null
        if ($skipClassification -eq 'BLOCKED') {
          $failures.Add(
            [System.InvalidOperationException]::new(
              "$($command.Id) BLOCKED: $skipReason"
            )
          ) | Out-Null
          if ($phaseSpec.Number -le 13) {
            $businessHalted = $true
          }
        }
        continue
      }

      $startedAt = [DateTimeOffset](& $GetUtcNow)
      $rawResult = $null
      $invocationFailure = $null
      try {
        $runState = [pscustomobject]@{
          Context = $Context
          CompletedPhases = @($phaseResults)
          CurrentPhaseCommands = @($commandResults)
          Failures = @($failures)
          ProvisionalReportTemplate = $provisionalTemplate
        }
        $rawResult = & $InvokeCommand $command $runState
      } catch {
        $invocationFailure = $_.Exception
      }
      $endedAt = [DateTimeOffset](& $GetUtcNow)

      if ($null -ne $invocationFailure) {
        $reason = "$($command.Id) threw: $($invocationFailure.Message)"
        $record = New-TradingLabRunCommandRecord `
          -Spec $command `
          -Classification 'FAIL' `
          -StartedAtUtc $startedAt `
          -EndedAtUtc $endedAt `
          -ExitCode $null `
          -StdoutPath $null `
          -StderrPath $null `
          -Reason $reason
        $commandResults.Add($record) | Out-Null
        $failures.Add(
          [System.Exception]::new($reason, $invocationFailure)
        ) | Out-Null
        if ($phaseSpec.Number -le 13) {
          $businessHalted = $true
        }
        continue
      }
      if (
        $null -eq $rawResult -or
        $null -eq $rawResult.PSObject.Properties['ExitCode'] -or
        $null -eq $rawResult.PSObject.Properties['StdoutPath'] -or
        $null -eq $rawResult.PSObject.Properties['StderrPath']
      ) {
        $reason = "$($command.Id) returned an invalid executor result"
        $record = New-TradingLabRunCommandRecord `
          -Spec $command `
          -Classification 'FAIL' `
          -StartedAtUtc $startedAt `
          -EndedAtUtc $endedAt `
          -ExitCode $null `
          -StdoutPath $null `
          -StderrPath $null `
          -Reason $reason
        $commandResults.Add($record) | Out-Null
        $failures.Add([System.InvalidOperationException]::new($reason)) |
          Out-Null
        if ($phaseSpec.Number -le 13) {
          $businessHalted = $true
        }
        continue
      }

      $exitCode = 0
      $validExitCode = [int]::TryParse(
        [string]$rawResult.ExitCode,
        [ref]$exitCode
      )
      if (-not $validExitCode) {
        $reason = "$($command.Id) returned an invalid exit code"
        $classification = 'FAIL'
        $exitValue = $null
      } elseif (
        $exitCode -eq 0 -and
        $null -ne $rawResult.PSObject.Properties['Classification'] -and
        [string]$rawResult.Classification -ceq 'PARTIAL'
      ) {
        $reason = 'Command completed with an explicitly partial result'
        $classification = 'PARTIAL'
        $exitValue = 0
      } elseif ($exitCode -eq 0) {
        $reason = $null
        $classification = 'PASS'
        $exitValue = 0
      } else {
        $reason = "$($command.Id) failed with exit code $exitCode"
        $classification = 'FAIL'
        $exitValue = $exitCode
      }
      $surefireSummary = if (
        $null -eq $rawResult.PSObject.Properties['SurefireSummary']
      ) {
        $null
      } else {
        $rawResult.SurefireSummary
      }
      $record = New-TradingLabRunCommandRecord `
        -Spec $command `
        -Classification $classification `
        -StartedAtUtc $startedAt `
        -EndedAtUtc $endedAt `
        -ExitCode $exitValue `
        -StdoutPath ([string]$rawResult.StdoutPath) `
        -StderrPath ([string]$rawResult.StderrPath) `
        -Reason $reason `
        -SurefireSummary $surefireSummary
      $commandResults.Add($record) | Out-Null
      if ($classification -eq 'FAIL') {
        $failures.Add([System.Exception]::new($reason)) | Out-Null
        if ($phaseSpec.Number -le 13) {
          $businessHalted = $true
        }
      }
    }
    $phaseCommands = @($commandResults)
    $phaseResults.Add(
      [pscustomobject][ordered]@{
        Number = $phaseSpec.Number
        Name = $phaseSpec.Name
        Status = Get-TradingLabPhaseResultStatus -Commands $phaseCommands
        Commands = $phaseCommands
      }
    ) | Out-Null
  }

  $phases = @($phaseResults)
  $phaseStatus = Get-TradingLabRunResultStatus -Phases $phases
  $finalStatus = $phaseStatus
  $finalReportTemplate = $null
  $canFinalizeReport = (
    $null -ne $Context.PSObject.Properties['CleanupReceiptPath'] -and
    $null -ne $Context.PSObject.Properties['FinalReportPath'] -and
    -not [string]::IsNullOrWhiteSpace(
      [string]$Context.CleanupReceiptPath
    ) -and
    -not [string]::IsNullOrWhiteSpace([string]$Context.FinalReportPath)
  )
  if ($canFinalizeReport) {
    try {
      $finalReportTemplate = Get-TradingLabVerificationReportTemplate `
        -RunResult ([pscustomobject]@{
          Status = $phaseStatus
          Phases = $phases
        })
      $cleanupReceipt = Read-TradingLabCleanupReceipt -Context $Context
      $finalStatus = Get-TradingLabFinalVerificationStatus `
        -BusinessStatus $phaseStatus `
        -CleanupStatus ([string]$cleanupReceipt.status)
      Complete-TradingLabVerificationReport `
        -Context $Context `
        -ProvisionalTemplate $finalReportTemplate `
        -CleanupReceipt $cleanupReceipt | Out-Null
    } catch {
      $finalStatus = 'FAIL'
      $failures.Add(
        [System.Exception]::new(
          "final verification report failed: $($_.Exception.Message)",
          $_.Exception
        )
      ) | Out-Null
    }
  }
  $runResult = [pscustomobject][ordered]@{
    Status = $finalStatus
    Phases = $phases
    Failures = @($failures | ForEach-Object Message)
    ProvisionalReportTemplate = $provisionalTemplate
    FinalReportTemplate = $finalReportTemplate
  }
  if ($ThrowOnFailure -and $runResult.Status -ne 'PASS') {
    if ($failures.Count -gt 1) {
      throw [System.AggregateException]::new(
        "Trading Lab validation run completed with $($failures.Count) failures",
        [System.Exception[]]@($failures)
      )
    }
    if ($failures.Count -eq 1) {
      throw $failures[0]
    }
    throw [System.InvalidOperationException]::new(
      "Trading Lab validation run completed with status $($runResult.Status)"
    )
  }
  return $runResult
}

function ConvertTo-TradingLabWindowsArgument {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [AllowEmptyString()]
    [string]$Value
  )

  if ($Value -match "[`0`r`n]") {
    throw 'Trading Lab command argument contains a forbidden control character'
  }
  if ($Value.Length -gt 0 -and $Value -notmatch '[\s"]') {
    return $Value
  }
  $builder = [System.Text.StringBuilder]::new()
  $builder.Append('"') | Out-Null
  $backslashes = 0
  foreach ($character in $Value.ToCharArray()) {
    if ($character -eq '\') {
      $backslashes++
      continue
    }
    if ($character -eq '"') {
      $builder.Append(('\' * (($backslashes * 2) + 1)) -join '') |
        Out-Null
      $builder.Append('"') | Out-Null
      $backslashes = 0
      continue
    }
    if ($backslashes -gt 0) {
      $builder.Append(('\' * $backslashes) -join '') | Out-Null
      $backslashes = 0
    }
    $builder.Append($character) | Out-Null
  }
  if ($backslashes -gt 0) {
    $builder.Append(('\' * ($backslashes * 2)) -join '') | Out-Null
  }
  $builder.Append('"') | Out-Null
  return $builder.ToString()
}

function Resolve-TradingLabApplicationPath {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$Executable
  )

  $matches = @(
    Get-Command $Executable -CommandType Application -ErrorAction Stop
  )
  foreach ($match in $matches) {
    $source = [string]$match.Source
    if ([string]::IsNullOrWhiteSpace($source)) {
      continue
    }
    $item = Get-Item -LiteralPath $source -Force -ErrorAction SilentlyContinue
    if ($null -ne $item -and -not $item.PSIsContainer) {
      return [System.IO.Path]::GetFullPath([string]$item.FullName)
    }
  }
  throw "Trading Lab executable did not resolve to an existing file: $Executable"
}

function Get-TradingLabProcessLaunchSpec {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$Executable,

    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [string[]]$Arguments
  )

  if ($Executable -in @('npm', 'mvn')) {
    $batch = Resolve-TradingLabApplicationPath "$Executable.cmd"
    $inner = @(
      (ConvertTo-TradingLabWindowsArgument -Value $batch)
    ) + @(
      $Arguments |
        ForEach-Object {
          ConvertTo-TradingLabWindowsArgument -Value ([string]$_)
        }
    )
    return [pscustomobject]@{
      FileName = $env:ComSpec
      Arguments = '/d /s /c "' + ($inner -join ' ') + '"'
    }
  }
  $resolved = Resolve-TradingLabApplicationPath $Executable
  return [pscustomobject]@{
    FileName = $resolved
    Arguments = (
      @(
        $Arguments |
          ForEach-Object {
            ConvertTo-TradingLabWindowsArgument -Value ([string]$_)
          }
      ) -join ' '
    )
  }
}

function Write-TradingLabOwnedTextCreateNew {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$Path,

    [Parameter(Mandatory = $true)]
    [AllowEmptyString()]
    [string]$Text
  )

  $target = [System.IO.Path]::GetFullPath($Path)
  $parent = Split-Path -Parent $target
  [System.IO.Directory]::CreateDirectory($parent) | Out-Null
  $payload = [System.Text.UTF8Encoding]::new($false).GetBytes($Text)
  $stream = [System.IO.FileStream]::new(
    $target,
    [System.IO.FileMode]::CreateNew,
    [System.IO.FileAccess]::Write,
    [System.IO.FileShare]::Read
  )
  try {
    $stream.Write($payload, 0, $payload.Length)
    $stream.Flush($true)
  } finally {
    $stream.Dispose()
  }
  return $target
}

function Add-TradingLabOwnedText {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$Path,

    [Parameter(Mandatory = $true)]
    [AllowEmptyString()]
    [string]$Text
  )

  $target = [System.IO.Path]::GetFullPath($Path)
  if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
    throw "Trading Lab owned evidence file does not exist: $target"
  }
  $payload = [System.Text.UTF8Encoding]::new($false).GetBytes($Text)
  $stream = [System.IO.FileStream]::new(
    $target,
    [System.IO.FileMode]::Open,
    [System.IO.FileAccess]::Write,
    [System.IO.FileShare]::Read
  )
  try {
    $stream.Seek(0, [System.IO.SeekOrigin]::End) | Out-Null
    $stream.Write($payload, 0, $payload.Length)
    $stream.Flush($true)
  } finally {
    $stream.Dispose()
  }
}

function Add-TradingLabOwnedJsonLine {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$Path,

    [Parameter(Mandatory = $true)]
    [object]$Value
  )

  Add-TradingLabOwnedText `
    -Path $Path `
    -Text (
      ($Value | ConvertTo-Json -Depth 8 -Compress) +
      [System.Environment]::NewLine
    )
}

function Write-TradingLabOwnedJsonAtomic {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$Path,

    [Parameter(Mandatory = $true)]
    [object]$Value,

    [Parameter(Mandatory = $true)]
    [string]$Label
  )

  $target = [System.IO.Path]::GetFullPath($Path)
  $parent = Split-Path -Parent $target
  if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
    throw "$Label parent is missing"
  }
  if (Test-Path -LiteralPath $target) {
    throw "$Label collision: target already exists"
  }
  $temporary = Join-Path `
    $parent `
    ('.trading-lab-' + [guid]::NewGuid().ToString('N') + '.tmp')
  try {
    Write-TradingLabOwnedTextCreateNew `
      -Path $temporary `
      -Text (($Value | ConvertTo-Json -Depth 16 -Compress) +
        [System.Environment]::NewLine) | Out-Null
    try {
      [System.IO.File]::Move($temporary, $target)
    } catch {
      throw "$Label collision during atomic publish"
    }
  } finally {
    if (Test-Path -LiteralPath $temporary -PathType Leaf) {
      Remove-Item -LiteralPath $temporary -Force
    }
  }
  return $target
}

function Invoke-TradingLabExternalProcess {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$Executable,

    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [string[]]$Arguments,

    [Parameter(Mandatory = $true)]
    [string]$WorkingDirectory,

    [Parameter(Mandatory = $true)]
    [string]$StdoutPath,

    [Parameter(Mandatory = $true)]
    [string]$StderrPath,

    [AllowNull()]
    [object]$Environment,

    [AllowNull()]
    [string]$StdinText
  )

  $working = [System.IO.Path]::GetFullPath($WorkingDirectory)
  if (-not (Test-Path -LiteralPath $working -PathType Container)) {
    throw "Trading Lab command cwd is missing: $working"
  }
  $launch = Get-TradingLabProcessLaunchSpec `
    -Executable $Executable `
    -Arguments @($Arguments)
  $info = [System.Diagnostics.ProcessStartInfo]::new()
  $info.FileName = $launch.FileName
  $info.Arguments = $launch.Arguments
  $info.WorkingDirectory = $working
  $info.UseShellExecute = $false
  $info.RedirectStandardOutput = $true
  $info.RedirectStandardError = $true
  $info.RedirectStandardInput = $null -ne $StdinText
  $info.CreateNoWindow = $true
  if ($null -ne $Environment) {
    foreach ($property in $Environment.PSObject.Properties) {
      if ($property.Name -match "[`0=`r`n]") {
        throw 'Trading Lab command environment key is invalid'
      }
      $info.EnvironmentVariables[$property.Name] = [string]$property.Value
    }
  }

  $startedAt = [DateTimeOffset]::UtcNow
  $process = [System.Diagnostics.Process]::new()
  $process.StartInfo = $info
  if (-not $process.Start()) {
    throw "Trading Lab command failed to start: $Executable"
  }
  if ($null -ne $StdinText) {
    $stdinBytes = [System.Text.UTF8Encoding]::new($false).GetBytes($StdinText)
    try {
      $process.StandardInput.BaseStream.Write(
        $stdinBytes,
        0,
        $stdinBytes.Length
      )
      $process.StandardInput.BaseStream.Flush()
    } finally {
      $stdinBytes = $null
      $process.StandardInput.Close()
    }
  }
  $stdoutTask = $process.StandardOutput.ReadToEndAsync()
  $stderrTask = $process.StandardError.ReadToEndAsync()
  $process.WaitForExit()
  $stdout = $stdoutTask.GetAwaiter().GetResult()
  $stderr = $stderrTask.GetAwaiter().GetResult()
  $exitCode = $process.ExitCode
  $process.Dispose()
  Write-TradingLabOwnedTextCreateNew -Path $StdoutPath -Text $stdout |
    Out-Null
  Write-TradingLabOwnedTextCreateNew -Path $StderrPath -Text $stderr |
    Out-Null
  return [pscustomobject]@{
    ExitCode = $exitCode
    StdoutPath = [System.IO.Path]::GetFullPath($StdoutPath)
    StderrPath = [System.IO.Path]::GetFullPath($StderrPath)
    InvocationStartedAtUtc = $startedAt
    InvocationEndedAtUtc = [DateTimeOffset]::UtcNow
  }
}

function Assert-TradingLabPortsAreFree {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [int[]]$Ports
  )

  $networkProperties = (
    [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties()
  )
  $listeners = @($networkProperties.GetActiveTcpListeners())
  foreach ($port in $Ports) {
    if ($port -lt 1 -or $port -gt 65535) {
      throw "Trading Lab port is invalid: $port"
    }
    if (@($listeners | Where-Object Port -eq $port).Count -ne 0) {
      throw "Trading Lab required port is not free: $port"
    }
  }
  return $true
}

function Assert-TradingLabMainDataServicesReady {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$WorkingDirectory,

    [Parameter(Mandatory = $true)]
    [string]$StdoutPath,

    [Parameter(Mandatory = $true)]
    [string]$StderrPath
  )

  $result = Invoke-TradingLabExternalProcess `
    -Executable 'docker' `
    -Arguments @(
      'container',
      'inspect',
      '--format',
      '{{.Name}}|{{.State.Running}}|{{json .NetworkSettings.Ports}}',
      'fx-platform-postgres',
      'fx-platform-redis'
    ) `
    -WorkingDirectory $WorkingDirectory `
    -StdoutPath $StdoutPath `
    -StderrPath $StderrPath
  if ($result.ExitCode -ne 0) {
    throw (
      'Main PostgreSQL/Redis prerequisites are missing; start only the ' +
      'existing infra/docker-compose.yml dependencies before this runner'
    )
  }
  $expected = [ordered]@{
    '/fx-platform-postgres' = '5432'
    '/fx-platform-redis' = '6379'
  }
  $observed = @{}
  foreach ($line in @(
    [System.IO.File]::ReadAllLines([string]$result.StdoutPath) |
      Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
  )) {
    $parts = $line -split '\|', 3
    if ($parts.Count -ne 3 -or $parts[1] -cne 'true') {
      throw 'Main PostgreSQL/Redis prerequisite identity is not running'
    }
    if (-not $expected.Contains([string]$parts[0])) {
      throw 'Main PostgreSQL/Redis prerequisite identity is unexpected'
    }
    $ports = $parts[2] | ConvertFrom-Json
    $containerPort = "$($expected[[string]$parts[0]])/tcp"
    $binding = $ports.PSObject.Properties[$containerPort]
    $hostPorts = if ($null -eq $binding) {
      @()
    } else {
      @($binding.Value | ForEach-Object { [string]$_.HostPort })
    }
    if (
      $null -eq $binding -or
      $hostPorts.Count -eq 0 -or
      @($hostPorts | Where-Object {
        $_ -cne $expected[[string]$parts[0]]
      }).Count -ne 0
    ) {
      throw 'Main PostgreSQL/Redis prerequisite port binding is invalid'
    }
    $observed[[string]$parts[0]] = $true
  }
  if ($observed.Count -ne $expected.Count) {
    throw 'Main PostgreSQL/Redis prerequisite identity is incomplete'
  }
  foreach ($port in @(5432, 6379)) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
      $connect = $client.BeginConnect('127.0.0.1', $port, $null, $null)
      if (-not $connect.AsyncWaitHandle.WaitOne(2000)) {
        throw "Main data prerequisite port is not reachable: $port"
      }
      $client.EndConnect($connect)
    } catch {
      throw "Main data prerequisite port is not reachable: $port"
    } finally {
      $client.Dispose()
    }
  }
  return $result
}

function ConvertFrom-TradingLabMainQueueSnapshotJson {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$Json
  )

  if (
    [string]::IsNullOrWhiteSpace($Json) -or
    [System.Text.Encoding]::UTF8.GetByteCount($Json) -gt 4096
  ) {
    throw 'Main Trading Lab queue snapshot is empty or oversized'
  }
  try {
    $document = $Json | ConvertFrom-Json
  } catch {
    throw 'Main Trading Lab queue snapshot is not valid JSON'
  }
  if (
    $null -eq $document -or
    $document -is [System.Array]
  ) {
    throw 'Main Trading Lab queue snapshot root is invalid'
  }
  $propertyNames = @(
    $document.PSObject.Properties |
      ForEach-Object Name |
      Sort-Object
  )
  if (
    $propertyNames.Count -ne 2 -or
    $propertyNames[0] -cne 'nonterminalCount' -or
    $propertyNames[1] -cne 'states'
  ) {
    throw 'Main Trading Lab queue snapshot schema is invalid'
  }

  $nonterminalCount = 0L
  if (
    -not [long]::TryParse(
      [string]$document.nonterminalCount,
      [ref]$nonterminalCount
    ) -or
    $nonterminalCount -lt 0
  ) {
    throw 'Main Trading Lab queue snapshot count is invalid'
  }
  $states = $document.states
  if (
    $null -eq $states -or
    $states -is [System.Array] -or
    $states -isnot [System.Management.Automation.PSCustomObject]
  ) {
    throw 'Main Trading Lab queue snapshot states are invalid'
  }
  $allowedStates = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::Ordinal
  )
  foreach ($state in @(
    'DRAFT',
    'VALIDATING',
    'QUEUED',
    'RESETTING',
    'RUNNING',
    'PAUSED',
    'CANCELLING',
    'CLEANING'
  )) {
    $allowedStates.Add($state) | Out-Null
  }
  $normalizedStates = [ordered]@{}
  $stateTotal = 0L
  foreach ($property in @(
    $states.PSObject.Properties | Sort-Object Name
  )) {
    if (-not $allowedStates.Contains([string]$property.Name)) {
      throw 'Main Trading Lab queue snapshot contains an invalid state'
    }
    $stateCount = 0L
    if (
      -not [long]::TryParse([string]$property.Value, [ref]$stateCount) -or
      $stateCount -le 0 -or
      $stateTotal -gt ([long]::MaxValue - $stateCount)
    ) {
      throw 'Main Trading Lab queue snapshot state count is invalid'
    }
    $normalizedStates[[string]$property.Name] = $stateCount
    $stateTotal += $stateCount
  }
  if ($stateTotal -ne $nonterminalCount) {
    throw 'Main Trading Lab queue snapshot total is inconsistent'
  }
  return [pscustomobject][ordered]@{
    NonterminalCount = $nonterminalCount
    States = [pscustomobject]$normalizedStates
  }
}

function ConvertTo-TradingLabMainQueueSnapshot {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$Snapshot
  )

  $propertyNames = @(
    $Snapshot.PSObject.Properties |
      ForEach-Object Name |
      Sort-Object
  )
  if (
    $propertyNames.Count -ne 2 -or
    $propertyNames[0] -cne 'NonterminalCount' -or
    $propertyNames[1] -cne 'States'
  ) {
    throw 'Main Trading Lab queue snapshot object schema is invalid'
  }
  $json = [pscustomobject][ordered]@{
    nonterminalCount = $Snapshot.NonterminalCount
    states = $Snapshot.States
  } | ConvertTo-Json -Depth 4 -Compress
  return ConvertFrom-TradingLabMainQueueSnapshotJson -Json $json
}

function Invoke-TradingLabBoundedProcessCapture {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$Executable,

    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [string[]]$Arguments,

    [Parameter(Mandatory = $true)]
    [string]$WorkingDirectory,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 60000)]
    [int]$TimeoutMilliseconds
  )

  $launch = Get-TradingLabProcessLaunchSpec `
    -Executable $Executable `
    -Arguments @($Arguments)
  $info = [System.Diagnostics.ProcessStartInfo]::new()
  $info.FileName = $launch.FileName
  $info.Arguments = $launch.Arguments
  $info.WorkingDirectory = [System.IO.Path]::GetFullPath($WorkingDirectory)
  $info.UseShellExecute = $false
  $info.RedirectStandardOutput = $true
  $info.RedirectStandardError = $true
  $info.CreateNoWindow = $true
  $process = [System.Diagnostics.Process]::new()
  $process.StartInfo = $info
  try {
    if (-not $process.Start()) {
      throw "Trading Lab bounded process failed to start: $Executable"
    }
    $processId = $process.Id
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit($TimeoutMilliseconds)) {
      $terminationFailure = $null
      try {
        if (-not $process.HasExited) {
          $process.Kill()
        }
      } catch {
        $terminationFailure = $_.Exception
      }
      try {
        $process.WaitForExit(1000) | Out-Null
      } catch {
        if ($null -eq $terminationFailure) {
          $terminationFailure = $_.Exception
        }
      }
      $clientStillRunning = $true
      try {
        $clientStillRunning = -not $process.HasExited
      } catch {
        if ($null -eq $terminationFailure) {
          $terminationFailure = $_.Exception
        }
      }
      if ($clientStillRunning) {
        $terminationMessage = (
          'Trading Lab bounded process timed out and client termination ' +
          "failed; processId=$processId"
        )
        if ($null -ne $terminationFailure) {
          throw [System.InvalidOperationException]::new(
            $terminationMessage,
            $terminationFailure
          )
        }
        throw [System.InvalidOperationException]::new($terminationMessage)
      }
      throw [System.TimeoutException]::new(
        (
          "Trading Lab bounded process did not exit within " +
          "$TimeoutMilliseconds milliseconds: $Executable"
        )
      )
    }
    $stdout = $stdoutTask.GetAwaiter().GetResult()
    $stderr = $stderrTask.GetAwaiter().GetResult()
    return [pscustomobject]@{
      ExitCode = $process.ExitCode
      Stdout = $stdout
      Stderr = $stderr
    }
  } finally {
    $process.Dispose()
  }
}

function Get-TradingLabMainPostgresIdentity {
  [CmdletBinding()]
  param(
    [ValidateRange(1, 60000)]
    [int]$TimeoutMilliseconds = 10000,

    [scriptblock]$InvokeOnce
  )

  $arguments = @(
    'container',
    'inspect',
    '--format',
    '{{.Id}}|{{.Name}}|{{.State.Running}}',
    'fx-platform-postgres'
  )
  if ($null -eq $InvokeOnce) {
    $InvokeOnce = {
      param($requestedArguments, $requestedTimeoutMilliseconds)
      Invoke-TradingLabBoundedProcessCapture `
        -Executable 'docker' `
        -Arguments @($requestedArguments) `
        -WorkingDirectory $script:TradingLabPlatformRoot `
        -TimeoutMilliseconds $requestedTimeoutMilliseconds
    }
  }
  $result = & $InvokeOnce $arguments $TimeoutMilliseconds
  if (
    $null -eq $result -or
    $null -eq $result.PSObject.Properties['ExitCode'] -or
    $null -eq $result.PSObject.Properties['Stdout'] -or
    $null -eq $result.PSObject.Properties['Stderr'] -or
    [int]$result.ExitCode -ne 0 -or
    -not [string]::IsNullOrWhiteSpace([string]$result.Stderr)
  ) {
    throw 'Main PostgreSQL identity inspection failed'
  }
  $lines = @(
    ([string]$result.Stdout) -split "`r?`n" |
      Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
  )
  if ($lines.Count -ne 1) {
    throw 'Main PostgreSQL identity inspection is ambiguous'
  }
  $parts = $lines[0].Trim() -split '\|', 3
  if (
    $parts.Count -ne 3 -or
    [string]$parts[0] -cnotmatch '^[0-9a-f]{64}$' -or
    [string]$parts[1] -cne '/fx-platform-postgres' -or
    [string]$parts[2] -cne 'true'
  ) {
    throw 'Main PostgreSQL identity is invalid'
  }
  return [string]$parts[0]
}

function Invoke-TradingLabMainQueueSnapshotQuery {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{64}$')]
    [string]$ContainerId,

    [ValidateRange(1, 60000)]
    [int]$TimeoutMilliseconds = 10000,

    [scriptblock]$InvokeOnce
  )

  $sql = (
    "SELECT json_build_object(" +
    "'nonterminalCount',COALESCE(SUM(state_count),0)," +
    "'states',COALESCE(json_object_agg(state,state_count) " +
    "FILTER (WHERE state IS NOT NULL),'{}'::json))::text " +
    "FROM (SELECT state,COUNT(*)::bigint AS state_count " +
    "FROM trading_lab.runs WHERE state NOT IN " +
    "('CANCELLED','FAILED','COMPLETED') GROUP BY state) AS nonterminal;"
  )
  $arguments = @(
    'exec',
    '--user',
    'postgres',
    '--env',
    'PGOPTIONS=-cstatement_timeout=5000',
    $ContainerId,
    'psql',
    '--no-psqlrc',
    '--quiet',
    '--tuples-only',
    '--no-align',
    '--set=ON_ERROR_STOP=1',
    '--username',
    'postgres',
    '--dbname',
    'fx_platform',
    '-c',
    $sql
  )
  if ($null -eq $InvokeOnce) {
    $InvokeOnce = {
      param($requestedArguments, $requestedTimeoutMilliseconds)
      Invoke-TradingLabBoundedProcessCapture `
        -Executable 'docker' `
        -Arguments @($requestedArguments) `
        -WorkingDirectory $script:TradingLabPlatformRoot `
        -TimeoutMilliseconds $requestedTimeoutMilliseconds
    }
  }
  try {
    $result = & $InvokeOnce $arguments $TimeoutMilliseconds
    if (
      $null -eq $result -or
      $null -eq $result.PSObject.Properties['ExitCode'] -or
      $null -eq $result.PSObject.Properties['Stdout'] -or
      $null -eq $result.PSObject.Properties['Stderr'] -or
      [int]$result.ExitCode -ne 0 -or
      -not [string]::IsNullOrWhiteSpace([string]$result.Stderr)
    ) {
      throw 'Main Trading Lab queue snapshot query failed'
    }
    return ConvertFrom-TradingLabMainQueueSnapshotJson `
      -Json ([string]$result.Stdout).Trim()
  } finally {
    $sql = $null
  }
}

function Wait-TradingLabMainQueueIdle {
  [CmdletBinding()]
  param(
    [ValidateRange(1, 3600)]
    [int]$DeadlineSeconds = $script:TradingLabMainQueueIdleDeadlineSeconds,

    [ValidateRange(50, 60000)]
    [int]$PollMilliseconds = $script:TradingLabMainQueueIdlePollMilliseconds,

    [scriptblock]$ReadSnapshot,

    [scriptblock]$OnObservation,

    [scriptblock]$UtcNow = { [DateTimeOffset]::UtcNow },

    [scriptblock]$Sleep = {
      param($milliseconds)
      [System.Threading.Thread]::Sleep($milliseconds)
    }
  )

  if ($null -eq $ReadSnapshot) {
    $resolvedContainerId = Get-TradingLabMainPostgresIdentity
    $queryCommand = Get-Command `
      -Name 'Invoke-TradingLabMainQueueSnapshotQuery' `
      -CommandType Function `
      -ErrorAction Stop
    $ReadSnapshot = {
      param($timeoutMilliseconds)
      & $queryCommand `
        -ContainerId $resolvedContainerId `
        -TimeoutMilliseconds $timeoutMilliseconds
    }.GetNewClosure()
  }
  $startedAt = [DateTimeOffset](& $UtcNow)
  $deadline = $startedAt.AddSeconds($DeadlineSeconds)
  $observations = 0
  $consecutiveIdleObservations = 0
  $initialNonterminalCount = $null
  $initialStates = $null
  $lastSnapshot = $null
  while ($true) {
    $current = [DateTimeOffset](& $UtcNow)
    if ($current -ge $deadline) {
      break
    }
    $snapshotTimeoutMilliseconds = [int][Math]::Max(
      1,
      [Math]::Min(
        10000,
        [long][Math]::Floor(($deadline - $current).TotalMilliseconds)
      )
    )
    $lastSnapshot = ConvertTo-TradingLabMainQueueSnapshot `
      -Snapshot (& $ReadSnapshot $snapshotTimeoutMilliseconds)
    $observedAt = [DateTimeOffset](& $UtcNow)
    $observations++
    if ($null -ne $OnObservation) {
      & $OnObservation ([pscustomobject][ordered]@{
        ObservedAtUtc = $observedAt.ToString(
          'o',
          [System.Globalization.CultureInfo]::InvariantCulture
        )
        Index = $observations
        NonterminalCount = [long]$lastSnapshot.NonterminalCount
        States = $lastSnapshot.States
      })
    }
    if ($null -eq $initialNonterminalCount) {
      $initialNonterminalCount = [long]$lastSnapshot.NonterminalCount
      $initialStates = $lastSnapshot.States
    }
    if ([long]$lastSnapshot.NonterminalCount -eq 0) {
      $consecutiveIdleObservations++
      if ($consecutiveIdleObservations -ge 2) {
        return [pscustomobject][ordered]@{
          Status = 'PASS'
          InitialNonterminalCount = [long]$initialNonterminalCount
          InitialStates = $initialStates
          FinalNonterminalCount = 0L
          FinalStates = $lastSnapshot.States
          Observations = $observations
          WaitedMilliseconds = [long][Math]::Floor(
            ($observedAt - $startedAt).TotalMilliseconds
          )
        }
      }
    } else {
      $consecutiveIdleObservations = 0
    }
    $current = $observedAt
    $remainingMilliseconds = [long][Math]::Floor(
      ($deadline - $current).TotalMilliseconds
    )
    if ($remainingMilliseconds -le 0) {
      break
    }
    & $Sleep ([int][Math]::Min($PollMilliseconds, $remainingMilliseconds))
  }
  $stateSummary = if ($null -eq $lastSnapshot) {
    'unobserved'
  } else {
    @(
      $lastSnapshot.States.PSObject.Properties |
        Sort-Object Name |
        ForEach-Object { "$($_.Name):$($_.Value)" }
    ) -join ','
  }
  $lastCount = if ($null -eq $lastSnapshot) {
    'unobserved'
  } else {
    [string]$lastSnapshot.NonterminalCount
  }
  throw [System.TimeoutException]::new(
    (
      'Main Trading Lab queue did not become idle within the strict ' +
      "deadline of $DeadlineSeconds seconds; " +
      "nonterminalCount=$lastCount; states=$stateSummary"
    )
  )
}

function Invoke-TradingLabMainQueueIdleGate {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$StdoutPath,

    [Parameter(Mandatory = $true)]
    [string]$StderrPath,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 3600)]
    [int]$DeadlineSeconds,

    [Parameter(Mandatory = $true)]
    [ValidateRange(50, 60000)]
    [int]$PollMilliseconds,

    [scriptblock]$GetIdentity,

    [scriptblock]$ReadSnapshot,

    [scriptblock]$UtcNow = { [DateTimeOffset]::UtcNow },

    [scriptblock]$Sleep = {
      param($milliseconds)
      [System.Threading.Thread]::Sleep($milliseconds)
    }
  )

  $stdoutInitialized = $false
  $stderrInitialized = $false
  $containerId = $null
  $finalContainerId = $null
  try {
    Write-TradingLabOwnedTextCreateNew -Path $StderrPath -Text '' | Out-Null
    $stderrInitialized = $true
    if ($null -eq $GetIdentity) {
      $GetIdentity = { Get-TradingLabMainPostgresIdentity }
    }
    $containerId = [string](& $GetIdentity)
    if ($containerId -cnotmatch '^[0-9a-f]{64}$') {
      throw 'Main PostgreSQL identity is invalid'
    }
    $startedAt = [DateTimeOffset](& $UtcNow)
    $header = [pscustomobject][ordered]@{
      schemaVersion = 1
      type = 'header'
      status = 'RUNNING'
      startedAtUtc = $startedAt.ToString(
        'o',
        [System.Globalization.CultureInfo]::InvariantCulture
      )
      containerId = $containerId
      deadlineSeconds = $DeadlineSeconds
      pollMilliseconds = $PollMilliseconds
    }
    Write-TradingLabOwnedTextCreateNew `
      -Path $StdoutPath `
      -Text (
        ($header | ConvertTo-Json -Depth 8 -Compress) +
        [System.Environment]::NewLine
      ) | Out-Null
    $stdoutInitialized = $true
    if ($null -eq $ReadSnapshot) {
      $queryCommand = Get-Command `
        -Name 'Invoke-TradingLabMainQueueSnapshotQuery' `
        -CommandType Function `
        -ErrorAction Stop
      $ReadSnapshot = {
        param($timeoutMilliseconds)
        & $queryCommand `
          -ContainerId $containerId `
          -TimeoutMilliseconds $timeoutMilliseconds
      }.GetNewClosure()
    }
    $appendJsonLineCommand = Get-Command `
      -Name 'Add-TradingLabOwnedJsonLine' `
      -CommandType Function `
      -ErrorAction Stop
    $onObservation = {
      param($observation)
      $observationRecord = [pscustomobject][ordered]@{
          schemaVersion = 1
          type = 'observation'
          observedAtUtc = [string]$observation.ObservedAtUtc
          containerId = $containerId
          index = [int]$observation.Index
          nonterminalCount = [long]$observation.NonterminalCount
          states = $observation.States
      }
      & $appendJsonLineCommand `
        -Path $StdoutPath `
        -Value $observationRecord
    }.GetNewClosure()
    $summary = Wait-TradingLabMainQueueIdle `
      -DeadlineSeconds $DeadlineSeconds `
      -PollMilliseconds $PollMilliseconds `
      -ReadSnapshot $ReadSnapshot `
      -OnObservation $onObservation `
      -UtcNow $UtcNow `
      -Sleep $Sleep
    $finalContainerId = [string](& $GetIdentity)
    if ($finalContainerId -cnotmatch '^[0-9a-f]{64}$') {
      throw 'Main PostgreSQL identity is invalid'
    }
    if ($finalContainerId -cne $containerId) {
      throw 'Main PostgreSQL identity drifted during the queue idle gate'
    }
    Add-TradingLabOwnedJsonLine `
      -Path $StdoutPath `
      -Value ([pscustomobject][ordered]@{
        schemaVersion = 1
        type = 'result'
        status = 'PASS'
        completedAtUtc = (
          [DateTimeOffset](& $UtcNow)
        ).ToString(
          'o',
          [System.Globalization.CultureInfo]::InvariantCulture
        )
        containerId = $containerId
        finalContainerId = $finalContainerId
        summary = [pscustomobject][ordered]@{
          initialNonterminalCount = [long]$summary.InitialNonterminalCount
          initialStates = $summary.InitialStates
          finalNonterminalCount = [long]$summary.FinalNonterminalCount
          finalStates = $summary.FinalStates
          observations = [int]$summary.Observations
          waitedMilliseconds = [long]$summary.WaitedMilliseconds
        }
      })
    return [pscustomobject]@{
      ExitCode = 0
      StdoutPath = [System.IO.Path]::GetFullPath($StdoutPath)
      StderrPath = [System.IO.Path]::GetFullPath($StderrPath)
    }
  } catch {
    $exception = $_.Exception
    $failure = [pscustomobject][ordered]@{
      schemaVersion = 1
      type = 'result'
      status = 'FAIL'
      completedAtUtc = [DateTimeOffset]::UtcNow.ToString(
        'o',
        [System.Globalization.CultureInfo]::InvariantCulture
      )
      errorCode = 'MAIN_QUEUE_IDLE_GATE_FAILED'
      errorType = $exception.GetType().FullName
    }
    if (-not [string]::IsNullOrWhiteSpace($containerId)) {
      $failure | Add-Member `
        -NotePropertyName containerId `
        -NotePropertyValue $containerId
    }
    if (
      -not [string]::IsNullOrWhiteSpace($finalContainerId) -and
      $finalContainerId -cmatch '^[0-9a-f]{64}$'
    ) {
      $failure | Add-Member `
        -NotePropertyName finalContainerId `
        -NotePropertyValue $finalContainerId
    }
    if ($stdoutInitialized) {
      Add-TradingLabOwnedJsonLine -Path $StdoutPath -Value $failure
    } else {
      Write-TradingLabOwnedTextCreateNew `
        -Path $StdoutPath `
        -Text (
          ($failure | ConvertTo-Json -Depth 8 -Compress) +
          [System.Environment]::NewLine
        ) | Out-Null
    }
    $errorText = $exception.Message + [System.Environment]::NewLine
    if ($stderrInitialized) {
      Add-TradingLabOwnedText -Path $StderrPath -Text $errorText
    } else {
      Write-TradingLabOwnedTextCreateNew `
        -Path $StderrPath `
        -Text $errorText | Out-Null
    }
    return [pscustomobject]@{
      ExitCode = 1
      StdoutPath = [System.IO.Path]::GetFullPath($StdoutPath)
      StderrPath = [System.IO.Path]::GetFullPath($StderrPath)
    }
  }
}

function Protect-TradingLabOwnedSecretEvidence {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$RunToken,

    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [string[]]$Paths
  )

  $bundle = Get-TradingLabOwnedRunSecrets -RunToken $RunToken
  $secretValues = @(
    $bundle.Secrets.Values |
      ForEach-Object { [string]$_ } |
      Where-Object { -not [string]::IsNullOrEmpty($_) } |
      Sort-Object -Unique
  )
  try {
    foreach ($path in $Paths) {
      if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        continue
      }
      $text = [System.IO.File]::ReadAllText($path)
      $redacted = $text
      foreach ($secret in $secretValues) {
        $redacted = $redacted.Replace($secret, '[REDACTED]')
      }
      if ($redacted -cne $text) {
        [System.IO.File]::WriteAllText(
          $path,
          $redacted,
          [System.Text.UTF8Encoding]::new($false)
        )
      }
    }
  } finally {
    $secretValues = $null
    $bundle = $null
  }
}

function Invoke-TradingLabValidationPostgresCredentialReconciliation {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$Context,

    [Parameter(Mandatory = $true)]
    [string]$StdoutPath,

    [Parameter(Mandatory = $true)]
    [string]$StderrPath
  )

  $runToken = [string]$Context.RunToken
  Assert-TradingLabFixedStackGuardOwned `
    -GuardPath ([string]$Context.FixedStackGuardPath) `
    -RunToken $runToken | Out-Null
  $lease = Read-TradingLabFixedStackLease `
    -LeasePath ([string]$Context.FixedStackLeasePath) `
    -RunToken $runToken
  $observed = @(
    Get-TradingLabFixedStackRuntimeSnapshot `
      -Context $Context `
      -Label 'credential-reconcile-before'
  )
  Assert-TradingLabFixedStackCanStop `
    -RunToken $runToken `
    -Lease $lease `
    -ObservedContainers $observed | Out-Null

  $leased = ConvertTo-TradingLabFixedStackIdentityMap `
    -ObservedContainers @($lease.ServiceIdentities)
  $current = ConvertTo-TradingLabFixedStackIdentityMap `
    -ObservedContainers $observed
  $postgres = $current['validation-postgres']
  if ($null -eq $postgres -or -not $postgres.Running) {
    throw 'Validation PostgreSQL is not running for credential reconciliation'
  }
  $postgresContainerId = [string]$leased['validation-postgres'].ContainerId
  $backendContainerId = [string]$leased['validation-backend'].ContainerId

  $bundle = Get-TradingLabOwnedRunSecrets -RunToken $runToken
  $databasePassword = [string]$bundle.Secrets['VALIDATION_DATABASE_PASSWORD']
  $wrongDatabasePassword = [string]$bundle.Secrets['VALIDATION_REDIS_PASSWORD']
  if (
    $databasePassword -cnotmatch '^[a-f0-9]{64}$' -or
    $wrongDatabasePassword -cnotmatch '^[a-f0-9]{64}$' -or
    $wrongDatabasePassword -ceq $databasePassword
  ) {
    throw 'Validation PostgreSQL run credential is invalid'
  }

  $stdout = [System.IO.Path]::GetFullPath($StdoutPath)
  $stderr = [System.IO.Path]::GetFullPath($StderrPath)
  if (-not $stdout.EndsWith(
    '.stdout.log',
    [System.StringComparison]::Ordinal
  )) {
    throw 'Validation PostgreSQL reconciliation stdout path is invalid'
  }
  $prefix = $stdout.Substring(0, $stdout.Length - '.stdout.log'.Length)
  $expectedStderr = $prefix + '.stderr.log'
  if ($stderr -cne $expectedStderr) {
    throw 'Validation PostgreSQL reconciliation evidence paths do not match'
  }
  $rotateStdout = $prefix + '.rotate.stdout.log'
  $rotateStderr = $prefix + '.rotate.stderr.log'
  $probeStdout = $prefix + '.probe.stdout.log'
  $probeStderr = $prefix + '.probe.stderr.log'
  $restartStdout = $prefix + '.restart.stdout.log'
  $restartStderr = $prefix + '.restart.stderr.log'

  try {
    $lineFeed = [string][char]10
    $rotateSql = (
      '\set ON_ERROR_STOP on' +
      $lineFeed +
      "ALTER ROLE fx_validation_app WITH PASSWORD '$databasePassword';" +
      $lineFeed
    )
    try {
      $rotate = Invoke-TradingLabExternalProcess `
        -Executable 'docker' `
        -Arguments @(
          'exec',
          '-i',
          '--user',
          'postgres',
          $postgresContainerId,
          'psql',
          '--no-psqlrc',
          '--quiet',
          '--set',
          'ON_ERROR_STOP=1',
          '--dbname',
          'fx_validation_lab',
          '--username',
          'fx_validation_app'
        ) `
        -WorkingDirectory $script:TradingLabPlatformRoot `
        -StdoutPath $rotateStdout `
        -StderrPath $rotateStderr `
        -StdinText $rotateSql
    } finally {
      Protect-TradingLabOwnedSecretEvidence `
        -RunToken $runToken `
        -Paths @($rotateStdout, $rotateStderr)
      $rotateSql = $null
    }
    if ($rotate.ExitCode -ne 0) {
      throw 'Validation PostgreSQL credential rotation failed'
    }

    $probeScript = @(
      'set -eu',
      (
        "if PGPASSWORD='$wrongDatabasePassword' " +
        'psql --no-psqlrc --quiet --tuples-only --no-align ' +
        '--set ON_ERROR_STOP=1 --host validation-postgres --port 5432 ' +
        '--username fx_validation_app --dbname fx_validation_lab ' +
        "--command 'SELECT 1' >/dev/null 2>&1; then"
      ),
      '  echo ''Validation PostgreSQL accepted an invalid credential'' >&2',
      '  exit 41',
      'fi',
      "PGPASSWORD='$databasePassword'",
      'export PGPASSWORD',
      (
        'probe="$(psql --no-psqlrc --quiet --tuples-only --no-align ' +
        '--set ON_ERROR_STOP=1 --host validation-postgres --port 5432 ' +
        '--username fx_validation_app --dbname fx_validation_lab ' +
        "--command 'SELECT 1')" + '"'
      ),
      '[ "$probe" = "1" ]',
      'printf ''%s\n'' "$probe"'
    ) -join $lineFeed
    try {
      $probe = Invoke-TradingLabExternalProcess `
        -Executable 'docker' `
        -Arguments @(
          'run',
          '--rm',
          '--network',
          $script:TradingLabValidationNetwork,
          '-i',
          'postgres:16',
          'sh',
          '-s'
        ) `
        -WorkingDirectory $script:TradingLabPlatformRoot `
        -StdoutPath $probeStdout `
        -StderrPath $probeStderr `
        -StdinText $probeScript
    } finally {
      Protect-TradingLabOwnedSecretEvidence `
        -RunToken $runToken `
        -Paths @($probeStdout, $probeStderr)
      $probeScript = $null
      $databasePassword = $null
      $wrongDatabasePassword = $null
      $bundle = $null
    }
    if ($probe.ExitCode -ne 0) {
      throw 'Validation PostgreSQL authenticated TCP probe failed'
    }

    try {
      $restart = Invoke-TradingLabExternalProcess `
        -Executable 'docker' `
        -Arguments @(
          'container',
          'restart',
          $backendContainerId
        ) `
        -WorkingDirectory $script:TradingLabPlatformRoot `
        -StdoutPath $restartStdout `
        -StderrPath $restartStderr
    } finally {
      Protect-TradingLabOwnedSecretEvidence `
        -RunToken $runToken `
        -Paths @($restartStdout, $restartStderr)
    }
    if ($restart.ExitCode -ne 0) {
      throw 'Validation backend restart after credential rotation failed'
    }

    $after = @(
      Get-TradingLabFixedStackRuntimeSnapshot `
        -Context $Context `
        -Label 'credential-reconcile-after'
    )
    Assert-TradingLabFixedStackCanStop `
      -RunToken $runToken `
      -Lease $lease `
      -ObservedContainers $after `
      -RequireRunning | Out-Null

    Write-TradingLabOwnedTextCreateNew `
      -Path $stdout `
      -Text (
        'credential-reconciled=true authenticated=true ' +
        'backend-restarted=true'
      ) | Out-Null
    Write-TradingLabOwnedTextCreateNew -Path $stderr -Text '' | Out-Null
    return [pscustomobject]@{
      ExitCode = 0
      StdoutPath = $stdout
      StderrPath = $stderr
    }
  } finally {
    $lineFeed = $null
    $databasePassword = $null
    $wrongDatabasePassword = $null
    $bundle = $null
  }
}

function Invoke-TradingLabSmokeAccountProvision {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$Context,

    [Parameter(Mandatory = $true)]
    [string]$StdoutPath,

    [Parameter(Mandatory = $true)]
    [string]$StderrPath
  )

  $bundle = Get-TradingLabOwnedRunSecrets `
    -RunToken ([string]$Context.RunToken)
  $accounts = $bundle.Accounts
  $viewPassword = [string]$bundle.Secrets['VIEW_PASSWORD']
  $executePassword = [string]$bundle.Secrets['EXECUTE_PASSWORD']
  $ordinaryPassword = [string]$bundle.Secrets['ORDINARY_PASSWORD']
  if ([bool]$accounts.Provisioned) {
    throw 'Trading Lab smoke accounts were already provisioned'
  }
  foreach ($value in @(
    $accounts.ViewUserId,
    $accounts.ExecuteUserId,
    $accounts.OrdinaryUserId,
    $accounts.ViewRoleId,
    $accounts.ExecuteRoleId,
    $accounts.ViewPermissionId,
    $accounts.ExecutePermissionId,
    $accounts.ViewBindingId,
    $accounts.ExecuteBindingId
  )) {
    if ([string]$value -notmatch '^[0-9a-f-]{36}$') {
      throw 'Trading Lab smoke account UUID is invalid'
    }
  }
  foreach ($email in @(
    $accounts.SuperEmail,
    $accounts.ViewEmail,
    $accounts.ExecuteEmail,
    $accounts.OrdinaryEmail
  )) {
    if ([string]$email -notmatch '^[a-z0-9-]+@local\.invalid$') {
      throw 'Trading Lab smoke account email is invalid'
    }
  }
  if ([string]$accounts.RoleSuffix -notmatch '^[0-9a-f]{32}$') {
    throw 'Trading Lab smoke account role suffix is invalid'
  }

  $sql = @"
\set ON_ERROR_STOP on
BEGIN;
INSERT INTO auth.users (
  id, email, password_hash, status, role
) VALUES (
  '$($accounts.ViewUserId)'::uuid,
  '$($accounts.ViewEmail)',
  crypt('$viewPassword', gen_salt('bf', 12)),
  'ACTIVE',
  'ADMIN'
);
INSERT INTO auth.users (
  id, email, password_hash, status, role
) VALUES (
  '$($accounts.ExecuteUserId)'::uuid,
  '$($accounts.ExecuteEmail)',
  crypt('$executePassword', gen_salt('bf', 12)),
  'ACTIVE',
  'ADMIN'
);
INSERT INTO auth.users (
  id, email, password_hash, status, role
) VALUES (
  '$($accounts.OrdinaryUserId)'::uuid,
  '$($accounts.OrdinaryEmail)',
  crypt('$ordinaryPassword', gen_salt('bf', 12)),
  'ACTIVE',
  'ADMIN'
);
INSERT INTO admin.roles (
  id, role_name, role_code, enabled, system_managed, sort_order, description
) VALUES
  (
    '$($accounts.ViewRoleId)'::uuid,
    'Trading Lab runner VIEW',
    'TL_RUN_VIEW_$($accounts.RoleSuffix)',
    TRUE,
    FALSE,
    901,
    'Run-owned Trading Lab VIEW proof'
  ),
  (
    '$($accounts.ExecuteRoleId)'::uuid,
    'Trading Lab runner EXECUTE',
    'TL_RUN_EXECUTE_$($accounts.RoleSuffix)',
    TRUE,
    FALSE,
    902,
    'Run-owned Trading Lab EXECUTE proof'
  );
INSERT INTO admin.role_menu_permissions (
  id, role_id, menu_id, buttons, enabled
) VALUES
  (
    '$($accounts.ViewPermissionId)'::uuid,
    '$($accounts.ViewRoleId)'::uuid,
    '00000000-0000-0000-0000-000000000062'::uuid,
    '[]'::jsonb,
    TRUE
  ),
  (
    '$($accounts.ExecutePermissionId)'::uuid,
    '$($accounts.ExecuteRoleId)'::uuid,
    '00000000-0000-0000-0000-000000000062'::uuid,
    '["TRADING_LAB_EXECUTE"]'::jsonb,
    TRUE
  );
INSERT INTO admin.user_roles (id, user_id, role_id) VALUES
  (
    '$($accounts.ViewBindingId)'::uuid,
    '$($accounts.ViewUserId)'::uuid,
    '$($accounts.ViewRoleId)'::uuid
  ),
  (
    '$($accounts.ExecuteBindingId)'::uuid,
    '$($accounts.ExecuteUserId)'::uuid,
    '$($accounts.ExecuteRoleId)'::uuid
  );
SELECT 'RUNNER_SUPER_ID=' || id::text
FROM auth.users
WHERE email = '$($accounts.SuperEmail)' AND role = 'ADMIN';
COMMIT;
"@
  try {
    try {
      $result = Invoke-TradingLabExternalProcess `
        -Executable 'docker' `
        -Arguments @(
          'exec',
          '-i',
          'fx-platform-postgres',
          'psql',
          '--no-psqlrc',
          '--set=ON_ERROR_STOP=1',
          '--tuples-only',
          '--no-align',
          '-U',
          'postgres',
          '-d',
          'fx_platform'
        ) `
        -WorkingDirectory $script:TradingLabPlatformRoot `
        -StdoutPath $StdoutPath `
        -StderrPath $StderrPath `
        -StdinText $sql
    } finally {
      Protect-TradingLabOwnedSecretEvidence `
        -RunToken ([string]$Context.RunToken) `
        -Paths @($StdoutPath, $StderrPath)
    }
    if ($result.ExitCode -ne 0) {
      throw 'Trading Lab smoke account provisioning failed'
    }
    $matches = @(
      [regex]::Matches(
        [System.IO.File]::ReadAllText([string]$result.StdoutPath),
        'RUNNER_SUPER_ID=([0-9a-f]{8}-[0-9a-f-]{27})'
      )
    )
    if ($matches.Count -ne 1) {
      throw 'Trading Lab smoke SUPER_ADMIN identity is missing'
    }
    $accounts.SuperUserId = [string]$matches[0].Groups[1].Value
    $accounts.Provisioned = $true
    return $result
  } finally {
    $sql = $null
    $viewPassword = $null
    $executePassword = $null
    $ordinaryPassword = $null
    $bundle = $null
  }
}

function Invoke-TradingLabSmokeAccountCleanup {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$Context,

    [Parameter(Mandatory = $true)]
    [string]$StdoutPath,

    [Parameter(Mandatory = $true)]
    [string]$StderrPath
  )

  $bundle = Get-TradingLabOwnedRunSecrets `
    -RunToken ([string]$Context.RunToken)
  $accounts = $bundle.Accounts
  if ([bool]$accounts.Cleaned) {
    throw 'Trading Lab smoke accounts were already cleaned'
  }
  foreach ($value in @(
    $accounts.ViewUserId,
    $accounts.ExecuteUserId,
    $accounts.OrdinaryUserId,
    $accounts.ViewRoleId,
    $accounts.ExecuteRoleId,
    $accounts.ViewPermissionId,
    $accounts.ExecutePermissionId,
    $accounts.ViewBindingId,
    $accounts.ExecuteBindingId
  )) {
    if ([string]$value -notmatch '^[0-9a-f-]{36}$') {
      throw 'Trading Lab smoke account cleanup UUID is invalid'
    }
  }
  $superIdLiteral = ''
  if (-not [string]::IsNullOrWhiteSpace([string]$accounts.SuperUserId)) {
    if ([string]$accounts.SuperUserId -notmatch '^[0-9a-f-]{36}$') {
      throw 'Trading Lab smoke SUPER cleanup UUID is invalid'
    }
    $superIdLiteral = [string]$accounts.SuperUserId
  }
  foreach ($email in @(
    $accounts.SuperEmail,
    $accounts.ViewEmail,
    $accounts.ExecuteEmail,
    $accounts.OrdinaryEmail
  )) {
    if ([string]$email -notmatch '^[a-z0-9-]+@local\.invalid$') {
      throw 'Trading Lab smoke account cleanup email is invalid'
    }
  }
  if ([string]$accounts.RoleSuffix -notmatch '^[0-9a-f]{32}$') {
    throw 'Trading Lab smoke account cleanup role suffix is invalid'
  }
  $provisionedSql = if ([bool]$accounts.Provisioned) {
    'TRUE'
  } else {
    'FALSE'
  }
  $sql = @"
\set ON_ERROR_STOP on
BEGIN;
CREATE TEMP TABLE trading_lab_runner_cleanup_receipt (
  deleted_users INTEGER NOT NULL,
  tombstoned_super INTEGER NOT NULL,
  deleted_sessions INTEGER NOT NULL,
  deleted_devices INTEGER NOT NULL,
  deleted_user_roles INTEGER NOT NULL,
  deleted_permissions INTEGER NOT NULL,
  deleted_roles INTEGER NOT NULL,
  remaining_owned_access INTEGER NOT NULL,
  super_disposition TEXT NOT NULL
) ON COMMIT DROP;
DO `$trading_lab_runner_cleanup`$
DECLARE
  v_expected_super_id UUID := NULLIF('$superIdLiteral', '')::uuid;
  v_super_id UUID;
  v_owned_user_ids UUID[];
  v_graph_rows INTEGER := 0;
  v_deleted_users INTEGER := 0;
  v_tombstoned_super INTEGER := 0;
  v_deleted_sessions INTEGER := 0;
  v_deleted_devices INTEGER := 0;
  v_deleted_user_roles INTEGER := 0;
  v_deleted_permissions INTEGER := 0;
  v_deleted_roles INTEGER := 0;
  v_remaining INTEGER := 0;
  v_super_disposition TEXT := 'NOT_CREATED';
BEGIN
  SELECT id
  INTO v_super_id
  FROM auth.users
  WHERE email = '$($accounts.SuperEmail)';

  IF v_expected_super_id IS NOT NULL
     AND v_super_id IS DISTINCT FROM v_expected_super_id THEN
    RAISE EXCEPTION 'Trading Lab SUPER cleanup identity drift';
  END IF;
  IF v_super_id IS NOT NULL AND EXISTS (
    SELECT 1
    FROM auth.users
    WHERE id = v_super_id
      AND (
        email IS DISTINCT FROM '$($accounts.SuperEmail)'
        OR status <> 'ACTIVE'
        OR role <> 'ADMIN'
      )
  ) THEN
    RAISE EXCEPTION 'Trading Lab SUPER cleanup ownership drift';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM auth.users
    WHERE
      (
        id = '$($accounts.ViewUserId)'::uuid
        AND (
          email IS DISTINCT FROM '$($accounts.ViewEmail)'
          OR role <> 'ADMIN'
        )
      )
      OR (
        id = '$($accounts.ExecuteUserId)'::uuid
        AND (
          email IS DISTINCT FROM '$($accounts.ExecuteEmail)'
          OR role <> 'ADMIN'
        )
      )
      OR (
        id = '$($accounts.OrdinaryUserId)'::uuid
        AND (
          email IS DISTINCT FROM '$($accounts.OrdinaryEmail)'
          OR role <> 'ADMIN'
        )
      )
  ) THEN
    RAISE EXCEPTION 'Trading Lab account cleanup ownership drift';
  END IF;
  IF EXISTS (
    SELECT 1
    FROM auth.users
    WHERE (
      email = '$($accounts.ViewEmail)'
      AND id <> '$($accounts.ViewUserId)'::uuid
    ) OR (
      email = '$($accounts.ExecuteEmail)'
      AND id <> '$($accounts.ExecuteUserId)'::uuid
    ) OR (
      email = '$($accounts.OrdinaryEmail)'
      AND id <> '$($accounts.OrdinaryUserId)'::uuid
    )
  ) THEN
    RAISE EXCEPTION 'Trading Lab account cleanup email drift';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM admin.roles
    WHERE (
      id = '$($accounts.ViewRoleId)'::uuid
      AND (
        role_code <> 'TL_RUN_VIEW_$($accounts.RoleSuffix)'
        OR system_managed
      )
    ) OR (
      id = '$($accounts.ExecuteRoleId)'::uuid
      AND (
        role_code <> 'TL_RUN_EXECUTE_$($accounts.RoleSuffix)'
        OR system_managed
      )
    ) OR (
      role_code IN (
        'TL_RUN_VIEW_$($accounts.RoleSuffix)',
        'TL_RUN_EXECUTE_$($accounts.RoleSuffix)'
      )
      AND id NOT IN (
        '$($accounts.ViewRoleId)'::uuid,
        '$($accounts.ExecuteRoleId)'::uuid
      )
    )
  ) THEN
    RAISE EXCEPTION 'Trading Lab role cleanup ownership drift';
  END IF;
  IF EXISTS (
    SELECT 1
    FROM admin.role_menu_permissions
    WHERE (
      id = '$($accounts.ViewPermissionId)'::uuid
      AND (
        role_id <> '$($accounts.ViewRoleId)'::uuid
        OR menu_id <> '00000000-0000-0000-0000-000000000062'::uuid
      )
    ) OR (
      id = '$($accounts.ExecutePermissionId)'::uuid
      AND (
        role_id <> '$($accounts.ExecuteRoleId)'::uuid
        OR menu_id <> '00000000-0000-0000-0000-000000000062'::uuid
      )
    )
  ) THEN
    RAISE EXCEPTION 'Trading Lab permission cleanup ownership drift';
  END IF;
  IF EXISTS (
    SELECT 1
    FROM admin.user_roles
    WHERE (
      id = '$($accounts.ViewBindingId)'::uuid
      AND (
        user_id <> '$($accounts.ViewUserId)'::uuid
        OR role_id <> '$($accounts.ViewRoleId)'::uuid
      )
    ) OR (
      id = '$($accounts.ExecuteBindingId)'::uuid
      AND (
        user_id <> '$($accounts.ExecuteUserId)'::uuid
        OR role_id <> '$($accounts.ExecuteRoleId)'::uuid
      )
    )
  ) THEN
    RAISE EXCEPTION 'Trading Lab binding cleanup ownership drift';
  END IF;

  SELECT COUNT(*)
  INTO v_graph_rows
  FROM (
    SELECT id
    FROM auth.users
    WHERE id IN (
      '$($accounts.ViewUserId)'::uuid,
      '$($accounts.ExecuteUserId)'::uuid,
      '$($accounts.OrdinaryUserId)'::uuid
    )
    UNION ALL
    SELECT id
    FROM admin.roles
    WHERE id IN (
      '$($accounts.ViewRoleId)'::uuid,
      '$($accounts.ExecuteRoleId)'::uuid
    )
    UNION ALL
    SELECT id
    FROM admin.role_menu_permissions
    WHERE id IN (
      '$($accounts.ViewPermissionId)'::uuid,
      '$($accounts.ExecutePermissionId)'::uuid
    )
    UNION ALL
    SELECT id
    FROM admin.user_roles
    WHERE id IN (
      '$($accounts.ViewBindingId)'::uuid,
      '$($accounts.ExecuteBindingId)'::uuid
    )
  ) AS owned_graph;
  IF v_graph_rows NOT IN (0, 9) THEN
    RAISE EXCEPTION 'Trading Lab account cleanup graph is partial';
  END IF;
  IF $provisionedSql AND v_graph_rows <> 9 THEN
    RAISE EXCEPTION 'Trading Lab provisioned account cleanup graph is missing';
  END IF;

  v_owned_user_ids := ARRAY[
    COALESCE(v_super_id, v_expected_super_id),
    '$($accounts.ViewUserId)'::uuid,
    '$($accounts.ExecuteUserId)'::uuid,
    '$($accounts.OrdinaryUserId)'::uuid
  ];

  DELETE FROM auth.user_sessions
  WHERE user_id = ANY(v_owned_user_ids);
  GET DIAGNOSTICS v_deleted_sessions = ROW_COUNT;

  DELETE FROM auth.user_devices
  WHERE user_id = ANY(v_owned_user_ids);
  GET DIAGNOSTICS v_deleted_devices = ROW_COUNT;

  DELETE FROM admin.user_roles
  WHERE user_id = ANY(v_owned_user_ids);
  GET DIAGNOSTICS v_deleted_user_roles = ROW_COUNT;

  DELETE FROM admin.role_menu_permissions
  WHERE role_id IN (
    '$($accounts.ViewRoleId)'::uuid,
    '$($accounts.ExecuteRoleId)'::uuid
  );
  GET DIAGNOSTICS v_deleted_permissions = ROW_COUNT;

  DELETE FROM admin.roles
  WHERE (
    id = '$($accounts.ViewRoleId)'::uuid
    AND role_code = 'TL_RUN_VIEW_$($accounts.RoleSuffix)'
  ) OR (
    id = '$($accounts.ExecuteRoleId)'::uuid
    AND role_code = 'TL_RUN_EXECUTE_$($accounts.RoleSuffix)'
  );
  GET DIAGNOSTICS v_deleted_roles = ROW_COUNT;

  DELETE FROM auth.users
  WHERE (
    id = '$($accounts.ViewUserId)'::uuid
    AND email = '$($accounts.ViewEmail)'
  ) OR (
    id = '$($accounts.ExecuteUserId)'::uuid
    AND email = '$($accounts.ExecuteEmail)'
  ) OR (
    id = '$($accounts.OrdinaryUserId)'::uuid
    AND email = '$($accounts.OrdinaryEmail)'
  );
  GET DIAGNOSTICS v_deleted_users = ROW_COUNT;

  IF v_super_id IS NOT NULL THEN
    UPDATE auth.users
    SET email = NULL,
        phone = NULL,
        password_hash = crypt(
          gen_random_uuid()::text || gen_random_uuid()::text,
          gen_salt('bf', 12)
        ),
        status = 'DISABLED',
        role = 'USER',
        updated_at = now()
    WHERE id = v_super_id
      AND email = '$($accounts.SuperEmail)'
      AND role = 'ADMIN';
    GET DIAGNOSTICS v_tombstoned_super = ROW_COUNT;
    IF v_tombstoned_super <> 1 THEN
      RAISE EXCEPTION 'Trading Lab SUPER tombstone failed';
    END IF;
    v_super_disposition := 'TOMBSTONED_FOR_RETAINED_TRADING_LAB_FK';
  END IF;

  SELECT COUNT(*)
  INTO v_remaining
  FROM (
    SELECT id::text
    FROM auth.user_sessions
    WHERE user_id = ANY(v_owned_user_ids)
    UNION ALL
    SELECT id::text
    FROM auth.user_devices
    WHERE user_id = ANY(v_owned_user_ids)
    UNION ALL
    SELECT id::text
    FROM admin.user_roles
    WHERE user_id = ANY(v_owned_user_ids)
    UNION ALL
    SELECT id::text
    FROM admin.role_menu_permissions
    WHERE role_id IN (
      '$($accounts.ViewRoleId)'::uuid,
      '$($accounts.ExecuteRoleId)'::uuid
    )
    UNION ALL
    SELECT id::text
    FROM admin.roles
    WHERE id IN (
      '$($accounts.ViewRoleId)'::uuid,
      '$($accounts.ExecuteRoleId)'::uuid
    )
    UNION ALL
    SELECT id::text
    FROM auth.users
    WHERE id IN (
      '$($accounts.ViewUserId)'::uuid,
      '$($accounts.ExecuteUserId)'::uuid,
      '$($accounts.OrdinaryUserId)'::uuid
    )
       OR email IN (
         '$($accounts.ViewEmail)',
         '$($accounts.ExecuteEmail)',
         '$($accounts.OrdinaryEmail)'
       )
    UNION ALL
    SELECT id::text
    FROM auth.users
    WHERE id = v_super_id
      AND (
        email IS NOT NULL
        OR phone IS NOT NULL
        OR status <> 'DISABLED'
        OR role <> 'USER'
        OR left(password_hash, 4) NOT IN ('`$2a`$', '`$2b`$', '`$2y`$')
      )
  ) AS residual;
  IF v_remaining <> 0 THEN
    RAISE EXCEPTION 'Trading Lab account cleanup remainingOwnedAccess is nonzero';
  END IF;

  INSERT INTO trading_lab_runner_cleanup_receipt (
    deleted_users,
    tombstoned_super,
    deleted_sessions,
    deleted_devices,
    deleted_user_roles,
    deleted_permissions,
    deleted_roles,
    remaining_owned_access,
    super_disposition
  ) VALUES (
    v_deleted_users,
    v_tombstoned_super,
    v_deleted_sessions,
    v_deleted_devices,
    v_deleted_user_roles,
    v_deleted_permissions,
    v_deleted_roles,
    v_remaining,
    v_super_disposition
  );
END
`$trading_lab_runner_cleanup`$;
SELECT json_build_object(
  'status', 'PASS',
  'deletedUsers', deleted_users,
  'tombstonedSuper', tombstoned_super,
  'deletedSessions', deleted_sessions,
  'deletedDevices', deleted_devices,
  'deletedUserRoles', deleted_user_roles,
  'deletedPermissions', deleted_permissions,
  'deletedRoles', deleted_roles,
  'remainingOwnedAccess', remaining_owned_access,
  'superDisposition', super_disposition
)::text
FROM trading_lab_runner_cleanup_receipt;
COMMIT;
"@
  try {
    $result = Invoke-TradingLabExternalProcess `
      -Executable 'docker' `
      -Arguments @(
        'exec',
        '-i',
        'fx-platform-postgres',
        'psql',
        '--no-psqlrc',
        '--set=ON_ERROR_STOP=1',
        '--tuples-only',
        '--no-align',
        '--quiet',
        '-U',
        'postgres',
        '-d',
        'fx_platform'
      ) `
      -WorkingDirectory $script:TradingLabPlatformRoot `
      -StdoutPath $StdoutPath `
      -StderrPath $StderrPath `
      -StdinText $sql
    Protect-TradingLabOwnedSecretEvidence `
      -RunToken ([string]$Context.RunToken) `
      -Paths @($StdoutPath, $StderrPath)
    if ($result.ExitCode -ne 0) {
      throw 'Trading Lab smoke account cleanup failed'
    }
    $documents = @(
      [System.IO.File]::ReadAllLines([string]$result.StdoutPath) |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_.StartsWith('{') -and $_.EndsWith('}') }
    )
    if ($documents.Count -ne 1) {
      throw 'Trading Lab smoke account cleanup receipt is missing'
    }
    $detail = $documents[0] | ConvertFrom-Json
    $expectedFields = @(
      'status',
      'deletedUsers',
      'tombstonedSuper',
      'deletedSessions',
      'deletedDevices',
      'deletedUserRoles',
      'deletedPermissions',
      'deletedRoles',
      'remainingOwnedAccess',
      'superDisposition'
    )
    if (
      (
        @($detail.PSObject.Properties.Name | Sort-Object) -join '|'
      ) -cne (@($expectedFields | Sort-Object) -join '|') -or
      [string]$detail.status -cne 'PASS'
    ) {
      throw 'Trading Lab smoke account cleanup receipt is invalid'
    }
    foreach ($field in @(
      'deletedUsers',
      'tombstonedSuper',
      'deletedSessions',
      'deletedDevices',
      'deletedUserRoles',
      'deletedPermissions',
      'deletedRoles',
      'remainingOwnedAccess'
    )) {
      $count = 0
      if (
        -not [int]::TryParse(
          [string]$detail.PSObject.Properties[$field].Value,
          [ref]$count
        ) -or
        $count -lt 0
      ) {
        throw "Trading Lab smoke account cleanup count is invalid: $field"
      }
    }
    if (
      [int]$detail.remainingOwnedAccess -ne 0 -or
      [int]$detail.tombstonedSuper -gt 1 -or
      [string]$detail.superDisposition -notin @(
        'NOT_CREATED',
        'TOMBSTONED_FOR_RETAINED_TRADING_LAB_FK'
      )
    ) {
      throw 'Trading Lab smoke account cleanup did not remove owned access'
    }
    if (
      [bool]$accounts.Provisioned -and
      (
        [int]$detail.deletedUsers -ne 3 -or
        [int]$detail.tombstonedSuper -ne 1 -or
        [int]$detail.deletedUserRoles -lt 2 -or
        [int]$detail.deletedPermissions -ne 2 -or
        [int]$detail.deletedRoles -ne 2 -or
        [string]$detail.superDisposition -cne
          'TOMBSTONED_FOR_RETAINED_TRADING_LAB_FK'
      )
    ) {
      throw 'Trading Lab provisioned account cleanup counts are incomplete'
    }
    $accounts.Cleaned = $true
    return [pscustomobject]@{
      Status = 'PASS'
      Detail = $detail
      StdoutPath = [string]$result.StdoutPath
      StderrPath = [string]$result.StderrPath
    }
  } finally {
    $sql = $null
    $bundle = $null
  }
}

function Get-TradingLabCandidateBasePath {
  [CmdletBinding()]
  param()

  return [System.IO.Path]::GetFullPath(
    (Join-Path ([System.IO.Path]::GetTempPath()) 'tlv')
  )
}

function New-TradingLabCandidateId {
  [CmdletBinding()]
  param()

  return 'tl-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
}

function Get-TradingLabRelativePathWithinRoot {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$Root,

    [Parameter(Mandatory = $true)]
    [string]$Path
  )

  $rootPrefix = [System.IO.Path]::GetFullPath($Root).TrimEnd('\', '/') +
    [System.IO.Path]::DirectorySeparatorChar
  $child = [System.IO.Path]::GetFullPath($Path)
  if (-not $child.StartsWith(
    $rootPrefix,
    [System.StringComparison]::OrdinalIgnoreCase
  )) {
    throw 'Trading Lab candidate path is outside its root'
  }
  return $child.Substring($rootPrefix.Length)
}

function Get-TradingLabSha256Hex {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [byte[]]$Bytes
  )

  $sha = [System.Security.Cryptography.SHA256]::Create()
  try {
    return (
      [System.BitConverter]::ToString($sha.ComputeHash($Bytes))
    ).Replace('-', '').ToLowerInvariant()
  } finally {
    $sha.Dispose()
  }
}

function Get-TradingLabCandidateTreeManifest {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$Root,

    [switch]$ExcludeBuildDirectories
  )

  $rootPath = [System.IO.Path]::GetFullPath($Root)
  Assert-TradingLabArtifactPathHasNoReparsePoint -Path $rootPath
  if (-not (Test-Path -LiteralPath $rootPath -PathType Container)) {
    throw 'Trading Lab candidate manifest root is missing'
  }
  $excluded = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::OrdinalIgnoreCase
  )
  if ($ExcludeBuildDirectories) {
    foreach (
      $name in @('.git', 'target', 'node_modules', 'dist', '.run-logs')
    ) {
      $excluded.Add($name) | Out-Null
    }
  }
  $entries = [System.Collections.Generic.List[object]]::new()
  $queue = [System.Collections.Generic.Queue[string]]::new()
  $queue.Enqueue($rootPath)
  while ($queue.Count -gt 0) {
    $directory = $queue.Dequeue()
    Assert-TradingLabArtifactPathHasNoReparsePoint -Path $directory
    foreach ($item in @(Get-ChildItem -LiteralPath $directory -Force)) {
      Assert-TradingLabArtifactPathHasNoReparsePoint -Path $item.FullName
      if (
        ($item.Attributes -band
          [System.IO.FileAttributes]::ReparsePoint) -ne 0
      ) {
        throw (
          'Trading Lab candidate manifest contains a reparse point: ' +
          $item.FullName
        )
      }
      if (
        $item.PSIsContainer -and
        $ExcludeBuildDirectories -and
        $excluded.Contains($item.Name)
      ) {
        continue
      }
      $relativePath = (
        Get-TradingLabRelativePathWithinRoot `
          -Root $rootPath `
          -Path $item.FullName
      ).Replace('\', '/')
      if ($relativePath -ceq '.trading-lab-candidate-owner.json') {
        continue
      }
      if ($item.PSIsContainer) {
        $entries.Add([pscustomobject][ordered]@{
          Type = 'D'
          RelativePath = $relativePath
          Sha256 = $null
        }) | Out-Null
        $queue.Enqueue($item.FullName)
      } else {
        $entries.Add([pscustomobject][ordered]@{
          Type = 'F'
          RelativePath = $relativePath
          Sha256 = (
            Get-TradingLabFileSha256 -Path $item.FullName
          ).ToLowerInvariant()
        }) | Out-Null
      }
    }
  }
  $manifestLines = [System.Collections.Generic.List[string]]::new()
  foreach ($entry in $entries) {
    $manifestLines.Add(
      "$($entry.Type)`0$($entry.RelativePath)`0$($entry.Sha256)"
    ) | Out-Null
  }
  [string[]]$sortedLines = @($manifestLines)
  [System.Array]::Sort($sortedLines, [System.StringComparer]::Ordinal)
  $manifestBytes = [System.Text.Encoding]::UTF8.GetBytes(
    $sortedLines -join "`n"
  )
  return [pscustomobject][ordered]@{
    SchemaVersion = 1
    Root = $rootPath
    Entries = @($entries)
    FileCount = @($entries | Where-Object Type -ceq 'F').Count
    DirectoryCount = @($entries | Where-Object Type -ceq 'D').Count
    Sha256 = Get-TradingLabSha256Hex -Bytes $manifestBytes
  }
}

function Assert-TradingLabCandidateSourceEntryUnchanged {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$SourceRoot,

    [Parameter(Mandatory = $true)]
    [object]$Entry
  )

  $root = [System.IO.Path]::GetFullPath($SourceRoot)
  Assert-TradingLabArtifactPathHasNoReparsePoint -Path $root
  $relativePath = [string]$Entry.RelativePath
  if (
    [string]::IsNullOrWhiteSpace($relativePath) -or
    [System.IO.Path]::IsPathRooted($relativePath)
  ) {
    throw 'SOURCE_CHANGED: invalid source manifest path'
  }
  $target = [System.IO.Path]::GetFullPath(
    (Join-Path $root $relativePath.Replace(
      '/',
      [System.IO.Path]::DirectorySeparatorChar
    ))
  )
  $roundTrip = (
    Get-TradingLabRelativePathWithinRoot -Root $root -Path $target
  ).Replace('\', '/')
  if ($roundTrip -cne $relativePath) {
    throw "SOURCE_CHANGED: source path identity drift: $relativePath"
  }
  Assert-TradingLabArtifactPathHasNoReparsePoint -Path $target
  if (-not (Test-Path -LiteralPath $target)) {
    throw "SOURCE_CHANGED: source entry disappeared: $relativePath"
  }
  $item = Get-Item -LiteralPath $target -Force
  if (
    ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0
  ) {
    throw "SOURCE_CHANGED: source entry became a reparse point: $relativePath"
  }
  if (
    ([string]$Entry.Type -ceq 'D' -and -not $item.PSIsContainer) -or
    ([string]$Entry.Type -ceq 'F' -and $item.PSIsContainer) -or
    [string]$Entry.Type -cnotin @('D', 'F')
  ) {
    throw "SOURCE_CHANGED: source entry type drift: $relativePath"
  }
  if (
    [string]$Entry.Type -ceq 'F' -and
    (
      Get-TradingLabFileSha256 -Path $target
    ).ToLowerInvariant() -cne [string]$Entry.Sha256
  ) {
    throw "SOURCE_CHANGED: source entry SHA drift: $relativePath"
  }
  return $target
}

function Assert-TradingLabCandidateCopyManifests {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$SourceBefore,

    [Parameter(Mandatory = $true)]
    [object]$SourceAfter,

    [Parameter(Mandatory = $true)]
    [object]$Candidate
  )

  $before = [System.Collections.Generic.Dictionary[string, object]]::new(
    [System.StringComparer]::OrdinalIgnoreCase
  )
  $after = [System.Collections.Generic.Dictionary[string, object]]::new(
    [System.StringComparer]::OrdinalIgnoreCase
  )
  $copied = [System.Collections.Generic.Dictionary[string, object]]::new(
    [System.StringComparer]::OrdinalIgnoreCase
  )
  foreach ($pair in @(
    [pscustomobject]@{ Manifest = $SourceBefore; Map = $before },
    [pscustomobject]@{ Manifest = $SourceAfter; Map = $after },
    [pscustomobject]@{ Manifest = $Candidate; Map = $copied }
  )) {
    foreach ($entry in @($pair.Manifest.Entries)) {
      $relativePath = [string]$entry.RelativePath
      if ($pair.Map.ContainsKey($relativePath)) {
        throw "COPY_EXTRA: duplicate manifest path $($entry.RelativePath)"
      }
      $pair.Map.Add($relativePath, $entry)
    }
  }
  foreach ($path in $after.Keys) {
    if (-not $before.ContainsKey($path)) {
      throw "SOURCE_ADDED: $path"
    }
  }
  foreach ($path in $before.Keys) {
    if (-not $after.ContainsKey($path)) {
      throw "SOURCE_CHANGED: removed $path"
    }
    $expected = $before[$path]
    $observed = $after[$path]
    if (
      [string]$expected.Type -cne [string]$observed.Type -or
      (
        [string]$expected.Type -ceq 'F' -and
        [string]$expected.Sha256 -cne [string]$observed.Sha256
      )
    ) {
      throw "SOURCE_CHANGED: $path"
    }
    if (-not $copied.ContainsKey($path)) {
      throw "COPY_MISSING: $path"
    }
    $candidateEntry = $copied[$path]
    if ([string]$expected.Type -cne [string]$candidateEntry.Type) {
      throw "COPY_MISSING: type mismatch $path"
    }
    if (
      [string]$expected.Type -ceq 'F' -and
      [string]$expected.Sha256 -cne [string]$candidateEntry.Sha256
    ) {
      throw "COPY_SHA: $path"
    }
  }
  foreach ($path in $copied.Keys) {
    if (-not $before.ContainsKey($path)) {
      throw "COPY_EXTRA: $path"
    }
  }
  return $true
}

function Copy-TradingLabFreshPlatformCandidate {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$SourceRoot,

    [Parameter(Mandatory = $true)]
    [string]$DestinationRoot,

    [Parameter(Mandatory = $true)]
    [string]$RunToken
  )

  Assert-TradingLabRunToken -RunToken $RunToken
  $source = [System.IO.Path]::GetFullPath($SourceRoot)
  $destination = [System.IO.Path]::GetFullPath($DestinationRoot)
  $candidateBase = Get-TradingLabCandidateBasePath
  $candidateParent = [System.IO.Path]::GetFullPath(
    (Split-Path -Parent $destination)
  )
  $candidatePrefix = $candidateBase.TrimEnd('\', '/') +
    [System.IO.Path]::DirectorySeparatorChar
  if (
    -not $destination.StartsWith(
      $candidatePrefix,
      [System.StringComparison]::OrdinalIgnoreCase
    ) -or
    [System.IO.Path]::GetFullPath((Split-Path -Parent $candidateParent)) -cne
      $candidateBase -or
    [System.IO.Path]::GetFileName($candidateParent) -notmatch
      '^[A-Za-z0-9][A-Za-z0-9_-]{7,80}$' -or
    [System.IO.Path]::GetFileName($destination) -cne 'fx-trading-platform'
  ) {
    throw 'Trading Lab Maven candidate must be under the fixed temp root'
  }
  foreach ($path in @(
    $source,
    $candidateBase,
    $candidateParent,
    $destination
  )) {
    Assert-TradingLabArtifactPathHasNoReparsePoint -Path $path
  }
  if (
    (Test-Path -LiteralPath $candidateParent) -or
    (Test-Path -LiteralPath $destination)
  ) {
    throw 'Trading Lab Maven candidate ID already exists'
  }
  if (-not (Test-Path -LiteralPath $source -PathType Container)) {
    throw 'Trading Lab platform source is missing'
  }
  $sourceBefore = Get-TradingLabCandidateTreeManifest `
    -Root $source `
    -ExcludeBuildDirectories
  if ($sourceBefore.FileCount -eq 0) {
    throw 'SOURCE_CHANGED: Trading Lab source manifest contains no files'
  }
  $longestDestination = [System.IO.Path]::GetFullPath(
    (Join-Path $destination '.trading-lab-candidate-owner.json')
  )
  foreach ($entry in @($sourceBefore.Entries)) {
    $childDestination = [System.IO.Path]::GetFullPath(
      (Join-Path $destination ([string]$entry.RelativePath).Replace(
        '/',
        [System.IO.Path]::DirectorySeparatorChar
      ))
    )
    if ($childDestination.Length -gt $longestDestination.Length) {
      $longestDestination = $childDestination
    }
  }
  if ($longestDestination.Length -ge 255) {
    throw (
      'Trading Lab Maven candidate destination exceeds the safe Windows ' +
      "path budget: $($longestDestination.Length) must be below 255"
    )
  }

  foreach ($path in @($candidateBase, $candidateParent, $destination)) {
    Assert-TradingLabArtifactPathHasNoReparsePoint -Path $path
  }
  [System.IO.Directory]::CreateDirectory($destination) | Out-Null
  foreach ($path in @($candidateBase, $candidateParent, $destination)) {
    Assert-TradingLabArtifactPathHasNoReparsePoint -Path $path
  }
  foreach ($entry in @($sourceBefore.Entries)) {
    $sourceEntry = Assert-TradingLabCandidateSourceEntryUnchanged `
      -SourceRoot $source `
      -Entry $entry
    $entryDestination = [System.IO.Path]::GetFullPath(
      (Join-Path $destination ([string]$entry.RelativePath).Replace(
        '/',
        [System.IO.Path]::DirectorySeparatorChar
      ))
    )
    Assert-TradingLabArtifactPathHasNoReparsePoint -Path $entryDestination
    if ([string]$entry.Type -ceq 'D') {
      [System.IO.Directory]::CreateDirectory($entryDestination) | Out-Null
    } else {
      [System.IO.File]::Copy($sourceEntry, $entryDestination, $false)
    }
    Assert-TradingLabArtifactPathHasNoReparsePoint -Path $entryDestination
  }
  try {
    $sourceAfter = Get-TradingLabCandidateTreeManifest `
      -Root $source `
      -ExcludeBuildDirectories
  } catch {
    throw "SOURCE_CHANGED: $($_.Exception.Message)"
  }
  $candidateManifest = Get-TradingLabCandidateTreeManifest -Root $destination
  Assert-TradingLabCandidateCopyManifests `
    -SourceBefore $sourceBefore `
    -SourceAfter $sourceAfter `
    -Candidate $candidateManifest | Out-Null
  $markerPath = Join-Path $destination '.trading-lab-candidate-owner.json'
  Assert-TradingLabArtifactPathHasNoReparsePoint -Path $markerPath
  Write-TradingLabOwnedJsonAtomic `
    -Path $markerPath `
    -Value ([ordered]@{
      schemaVersion = 1
      runToken = $RunToken
      sourceRoot = $source
      sourceManifestSha256 = [string]$sourceBefore.Sha256
      sourceFileCount = [int]$sourceBefore.FileCount
      sourceDirectoryCount = [int]$sourceBefore.DirectoryCount
      candidateManifestSha256 = [string]$candidateManifest.Sha256
      candidateFileCount = [int]$candidateManifest.FileCount
      candidateDirectoryCount = [int]$candidateManifest.DirectoryCount
    }) `
    -Label 'Trading Lab candidate owner marker' | Out-Null
  Assert-TradingLabArtifactPathHasNoReparsePoint -Path $markerPath
  return $destination
}

function Copy-TradingLabOwnedBootJar {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$SourcePath,

    [Parameter(Mandatory = $true)]
    [string]$DestinationPath
  )

  $source = [System.IO.Path]::GetFullPath($SourcePath)
  $destination = [System.IO.Path]::GetFullPath($DestinationPath)
  if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
    throw 'Trading Lab packaged Boot jar is missing'
  }
  if (Test-Path -LiteralPath $destination) {
    throw 'Trading Lab owned runtime jar already exists'
  }
  [System.IO.Directory]::CreateDirectory(
    (Split-Path -Parent $destination)
  ) | Out-Null
  [System.IO.File]::Copy($source, $destination, $false)
  $sourceHash = Get-TradingLabFileSha256 -Path $source
  $destinationHash = Get-TradingLabFileSha256 -Path $destination
  if ($sourceHash -cne $destinationHash) {
    throw 'Trading Lab owned runtime jar copy hash mismatch'
  }
  return $destinationHash
}

function Get-TradingLabFileSha256 {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$Path
  )

  $target = [System.IO.Path]::GetFullPath($Path)
  if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
    throw 'Trading Lab SHA256 source file is missing'
  }
  $stream = [System.IO.File]::OpenRead($target)
  $sha = [System.Security.Cryptography.SHA256]::Create()
  try {
    return (
      [System.BitConverter]::ToString($sha.ComputeHash($stream))
    ).Replace('-', '')
  } finally {
    $sha.Dispose()
    $stream.Dispose()
  }
}

function Assert-TradingLabMavenEvidence {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$Command,

    [Parameter(Mandatory = $true)]
    [DateTimeOffset]$InvocationStartedAtUtc
  )

  $target = Join-Path $Command.WorkingDirectory 'target'
  $reports = Join-Path $target 'surefire-reports'
  $required = @($Command.RequiredTestClasses)
  if ($required.Count -eq 0) {
    if (-not (Test-Path -LiteralPath $reports -PathType Container)) {
      throw 'Full Maven gate produced no Surefire report directory'
    }
    $required = @(
      Get-ChildItem -LiteralPath $reports -Filter 'TEST-*.xml' -File |
        ForEach-Object {
          $_.BaseName.Substring('TEST-'.Length)
        }
    )
  }
  return Read-StrictTradingLabSurefireSummary `
    -ReportsRoot $reports `
    -RequiredClasses $required `
    -InvocationStartedAtUtc $InvocationStartedAtUtc `
    -DumpRoot $target
}

function Copy-TradingLabSurefireEvidence {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$Command
  )

  $sourceRoot = Join-Path $Command.WorkingDirectory 'target\surefire-reports'
  if ([string]::IsNullOrWhiteSpace([string]$Command.DestinationPath)) {
    throw 'Trading Lab Maven evidence destination is missing'
  }
  $destination = [System.IO.Path]::GetFullPath(
    [string]$Command.DestinationPath
  )
  if (Test-Path -LiteralPath $destination) {
    throw 'Trading Lab Maven evidence destination already exists'
  }
  [System.IO.Directory]::CreateDirectory($destination) | Out-Null
  $manifest = [System.Collections.Generic.List[object]]::new()
  foreach ($report in @(
    Get-ChildItem -LiteralPath $sourceRoot -Filter 'TEST-*.xml' -File |
      Sort-Object Name
  )) {
    $target = Join-Path $destination $report.Name
    [System.IO.File]::Copy($report.FullName, $target, $false)
    $manifest.Add([ordered]@{
      file = $report.Name
      bytes = (Get-Item -LiteralPath $target).Length
      sha256 = Get-TradingLabFileSha256 -Path $target
    }) | Out-Null
  }
  if ($manifest.Count -eq 0) {
    throw 'Trading Lab Maven evidence copy found no Surefire XML'
  }
  Write-TradingLabOwnedJsonAtomic `
    -Path (Join-Path $destination 'manifest.json') `
    -Value ([ordered]@{
      schemaVersion = 1
      command = [string]$Command.Id
      reports = @($manifest)
    }) `
    -Label 'Trading Lab Maven evidence manifest' | Out-Null
  return $destination
}

function Wait-TradingLabHttpReady {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [uri]$Uri,

    [Parameter(Mandatory = $true)]
    [System.Diagnostics.Process]$Process,

    [int]$TimeoutSeconds = 60
  )

  $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
  do {
    if ($Process.HasExited) {
      throw "Owned process exited before readiness: $($Process.Id)"
    }
    try {
      $request = [System.Net.HttpWebRequest]::CreateHttp($Uri)
      $request.Method = 'GET'
      $request.Timeout = 2000
      $request.ReadWriteTimeout = 2000
      $request.AllowAutoRedirect = $false
      $response = [System.Net.HttpWebResponse]$request.GetResponse()
      try {
        if ([int]$response.StatusCode -ge 200 -and
          [int]$response.StatusCode -lt 400) {
          return $true
        }
      } finally {
        $response.Dispose()
      }
    } catch {
      if ([DateTimeOffset]::UtcNow -ge $deadline) {
        throw "Owned process readiness timed out: $Uri"
      }
    }
    [System.Threading.Thread]::Sleep(250)
  } while ([DateTimeOffset]::UtcNow -lt $deadline)
  throw "Owned process readiness timed out: $Uri"
}

function Wait-TradingLabTcpPortReady {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [int]$Port,

    [Parameter(Mandatory = $true)]
    [System.Diagnostics.Process]$Process,

    [int]$TimeoutSeconds = 30
  )

  $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
  do {
    if ($Process.HasExited) {
      throw "Owned process exited before TCP readiness: $($Process.Id)"
    }
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
      $connect = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
      if ($connect.AsyncWaitHandle.WaitOne(500)) {
        $client.EndConnect($connect)
        return $true
      }
    } catch {
      if ([DateTimeOffset]::UtcNow -ge $deadline) {
        throw "Owned process TCP readiness timed out: 127.0.0.1:$Port"
      }
    } finally {
      $client.Dispose()
    }
    [System.Threading.Thread]::Sleep(200)
  } while ([DateTimeOffset]::UtcNow -lt $deadline)
  throw "Owned process TCP readiness timed out: 127.0.0.1:$Port"
}

function Get-TradingLabValidationSecretFromContainerEnvironment {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [AllowEmptyString()]
    [string[]]$Lines
  )

  $prefix = 'VALIDATION_INTERNAL_SECRET='
  $matches = @(
    $Lines |
      Where-Object { $_.StartsWith($prefix, [System.StringComparison]::Ordinal) }
  )
  if ($matches.Count -ne 1) {
    throw 'Owned validation container must expose exactly one internal secret'
  }
  $secret = $matches[0].Substring($prefix.Length)
  $secretBytes = [System.Text.Encoding]::UTF8.GetByteCount($secret)
  if (
    [string]::IsNullOrWhiteSpace($secret) -or
    $secretBytes -lt 32 -or
    $secret -match "[`0`r`n]"
  ) {
    throw 'Owned validation container internal secret is invalid'
  }
  return $secret
}

function Read-TradingLabValidationSecretFromOwnedContainer {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$Context
  )

  $lease = Read-TradingLabFixedStackLease `
    -LeasePath ([string]$Context.FixedStackLeasePath) `
    -RunToken ([string]$Context.RunToken)
  $observed = @(
    Get-TradingLabFixedStackRuntimeSnapshot `
      -Context $Context `
      -Label 'before-owned-secret-read'
  )
  Assert-TradingLabFixedStackCanStop `
    -RunToken ([string]$Context.RunToken) `
    -Lease $lease `
    -ObservedContainers $observed | Out-Null
  $backend = @(
    $lease.ServiceIdentities |
      Where-Object Service -CEQ 'validation-backend'
  )
  if ($backend.Count -ne 1) {
    throw 'Owned validation backend identity is missing'
  }
  $containerId = [string]$backend[0].ContainerId
  if ($containerId -notmatch '^[a-f0-9]{64}$') {
    throw 'Owned validation backend container ID is invalid'
  }

  $launch = Get-TradingLabProcessLaunchSpec `
    -Executable 'docker' `
    -Arguments @(
      'inspect',
      '--format',
      '{{range .Config.Env}}{{println .}}{{end}}',
      $containerId
    )
  $info = [System.Diagnostics.ProcessStartInfo]::new()
  $info.FileName = $launch.FileName
  $info.Arguments = $launch.Arguments
  $info.WorkingDirectory = $script:TradingLabPlatformRoot
  $info.UseShellExecute = $false
  $info.RedirectStandardOutput = $true
  $info.RedirectStandardError = $true
  $info.CreateNoWindow = $true
  $process = [System.Diagnostics.Process]::new()
  $process.StartInfo = $info
  $stdout = $null
  $stderr = $null
  try {
    if (-not $process.Start()) {
      throw 'Owned validation backend secret inspection failed to start'
    }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $stdout = $stdoutTask.GetAwaiter().GetResult()
    $stderr = $stderrTask.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) {
      throw 'Owned validation backend secret inspection failed'
    }
    return Get-TradingLabValidationSecretFromContainerEnvironment `
      -Lines @($stdout -split '\r?\n')
  } finally {
    $stdout = $null
    $stderr = $null
    $process.Dispose()
  }
}

function Resolve-TradingLabOwnedProcessEnvironment {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$Environment,

    [AllowNull()]
    [string]$ValidationSecret
  )

  $resolved = [ordered]@{}
  foreach ($property in $Environment.PSObject.Properties) {
    if ($property.Name -match "[`0=`r`n]") {
      throw 'Trading Lab owned process environment key is invalid'
    }
    $value = [string]$property.Value
    if ($value -ceq $script:TradingLabOwnedValidationSecretSentinel) {
      if (
        $property.Name -cne 'TRADING_LAB_VALIDATION_INTERNAL_TOKEN' -or
        [string]::IsNullOrWhiteSpace($ValidationSecret) -or
        [System.Text.Encoding]::UTF8.GetByteCount($ValidationSecret) -lt 32
      ) {
        throw 'Trading Lab owned validation secret resolution failed'
      }
      $resolved[$property.Name] = $ValidationSecret
    } else {
      $resolved[$property.Name] = $value
    }
  }
  return [pscustomobject]$resolved
}

function Start-TradingLabOwnedProcess {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$Command,

    [Parameter(Mandatory = $true)]
    [object]$Context,

    [Parameter(Mandatory = $true)]
    [string]$StdoutPath,

    [Parameter(Mandatory = $true)]
    [string]$StderrPath,

    [switch]$WaitForHealth
  )

  if ([string]::IsNullOrWhiteSpace([string]$Command.DestinationPath)) {
    throw 'Owned process receipt path is missing'
  }
  $receiptPath = [System.IO.Path]::GetFullPath(
    [string]$Command.DestinationPath
  )
  if (
    (Test-Path -LiteralPath $receiptPath) -or
    $script:TradingLabOwnedProcessRegistry.ContainsKey($receiptPath)
  ) {
    throw 'Owned process receipt collision'
  }
  Write-TradingLabOwnedTextCreateNew -Path $StdoutPath -Text '' | Out-Null
  Write-TradingLabOwnedTextCreateNew -Path $StderrPath -Text '' | Out-Null

  $launch = Get-TradingLabProcessLaunchSpec `
    -Executable ([string]$Command.Executable) `
    -Arguments @($Command.Arguments)
  $info = [System.Diagnostics.ProcessStartInfo]::new()
  $info.FileName = $launch.FileName
  $info.Arguments = $launch.Arguments
  $info.WorkingDirectory = [string]$Command.WorkingDirectory
  $info.UseShellExecute = $false
  $info.RedirectStandardOutput = $true
  $info.RedirectStandardError = $true
  $info.CreateNoWindow = $true
  $validationSecret = $null
  $environment = Resolve-TradingLabRunEnvironment `
    -Environment $Command.Environment `
    -RunToken ([string]$Context.RunToken)
  if (
    @(
      $environment.PSObject.Properties |
        Where-Object {
          [string]$_.Value -ceq
            $script:TradingLabOwnedValidationSecretSentinel
        }
    ).Count -ne 0
  ) {
    $validationSecret = Read-TradingLabValidationSecretFromOwnedContainer `
      -Context $Context
  }
  $resolvedEnvironment = Resolve-TradingLabOwnedProcessEnvironment `
    -Environment $environment `
    -ValidationSecret $validationSecret
  foreach ($property in $resolvedEnvironment.PSObject.Properties) {
    $info.EnvironmentVariables[$property.Name] = [string]$property.Value
  }
  $validationSecret = $null
  $environment = $null
  $resolvedEnvironment = $null
  $process = [System.Diagnostics.Process]::new()
  $process.StartInfo = $info
  if (-not $process.Start()) {
    throw "Owned process failed to start: $($Command.Id)"
  }
  $stdoutTask = $process.StandardOutput.ReadToEndAsync()
  $stderrTask = $process.StandardError.ReadToEndAsync()
  $startedAt = [DateTimeOffset]$process.StartTime.ToUniversalTime()
  $registered = $false
  try {
    $receipt = New-TradingLabOwnedProcessReceipt `
      -RunToken ([string]$Context.RunToken) `
      -Name ([string]$Command.Id) `
      -ProcessId $process.Id `
      -StartedAtUtc $startedAt `
      -Executable ([string]$Command.Executable) `
      -Arguments @($Command.Arguments)
    Write-TradingLabOwnedJsonAtomic `
      -Path $receiptPath `
      -Value $receipt `
      -Label 'Trading Lab owned process receipt' | Out-Null
    $script:TradingLabOwnedProcessRegistry.Add(
      $receiptPath,
      [pscustomobject]@{
        Process = $process
        Receipt = $receipt
        Arguments = @($Command.Arguments)
        StdoutTask = $stdoutTask
        StderrTask = $stderrTask
        StdoutPath = [System.IO.Path]::GetFullPath($StdoutPath)
        StderrPath = [System.IO.Path]::GetFullPath($StderrPath)
      }
    )
    $registered = $true
    if (
      $WaitForHealth -and
      -not [string]::IsNullOrWhiteSpace([string]$Command.HealthUri)
    ) {
      Wait-TradingLabHttpReady `
        -Uri ([uri]$Command.HealthUri) `
        -Process $process | Out-Null
    } elseif (
      [string]$Command.Id -ceq 'start-supervisor-owned-process'
    ) {
      Wait-TradingLabTcpPortReady `
        -Port $script:TradingLabSupervisorPort `
        -Process $process | Out-Null
    }
    return $receipt
  } catch {
    $startFailure = $_.Exception
    if (-not $process.HasExited) {
      $process.Kill()
      $process.WaitForExit(10000) | Out-Null
    }
    if (-not $registered -and $process.HasExited) {
      [System.IO.File]::WriteAllText(
        [System.IO.Path]::GetFullPath($StdoutPath),
        $stdoutTask.GetAwaiter().GetResult(),
        [System.Text.UTF8Encoding]::new($false)
      )
      [System.IO.File]::WriteAllText(
        [System.IO.Path]::GetFullPath($StderrPath),
        $stderrTask.GetAwaiter().GetResult(),
        [System.Text.UTF8Encoding]::new($false)
      )
    }
    if (-not $registered) {
      $process.Dispose()
    }
    throw $startFailure
  }
}

function Read-TradingLabOwnedProcessReceipt {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$Path,

    [Parameter(Mandatory = $true)]
    [string]$RunToken
  )

  $target = [System.IO.Path]::GetFullPath($Path)
  if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
    throw 'Trading Lab owned process receipt is missing'
  }
  $item = Get-Item -LiteralPath $target -Force
  if (
    ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0
  ) {
    throw 'Trading Lab owned process receipt cannot be a reparse point'
  }
  try {
    $receipt = [System.IO.File]::ReadAllText($target) | ConvertFrom-Json
  } catch {
    throw 'Trading Lab owned process receipt is invalid'
  }
  if (
    $receipt.SchemaVersion -ne 1 -or
    [string]$receipt.RunToken -cne $RunToken
  ) {
    throw 'Trading Lab owned process receipt belongs to another run'
  }
  $expectedFingerprint = Get-TradingLabCommandFingerprint `
    -Executable ([string]$receipt.Executable) `
    -Arguments @($receipt.Arguments)
  if ([string]$receipt.CommandFingerprint -cne $expectedFingerprint) {
    throw 'Trading Lab owned process receipt fingerprint is invalid'
  }
  return $receipt
}

function Stop-TradingLabOwnedProcesses {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$Context
  )

  $failures = [System.Collections.Generic.List[System.Exception]]::new()
  $details = [System.Collections.Generic.List[object]]::new()
  $receiptPaths = @(
    [string]$Context.AdminReceiptPath,
    [string]$Context.MainBackendReceiptPath,
    [string]$Context.SupervisorReceiptPath
  )
  foreach ($receiptPath in $receiptPaths) {
    if (-not (Test-Path -LiteralPath $receiptPath -PathType Leaf)) {
      $details.Add([pscustomobject]@{
        receiptPath = $receiptPath
        status = 'NOT_STARTED'
      }) | Out-Null
      continue
    }
    try {
      $receipt = Read-TradingLabOwnedProcessReceipt `
        -Path $receiptPath `
        -RunToken ([string]$Context.RunToken)
      if (-not $script:TradingLabOwnedProcessRegistry.ContainsKey($receiptPath)) {
        throw 'Live owned process registry is missing; refusing recovery stop'
      }
      $owned = $script:TradingLabOwnedProcessRegistry[$receiptPath]
      $process = [System.Diagnostics.Process]$owned.Process
      $observed = [pscustomobject]@{
        ProcessId = $process.Id
        StartedAtUtc = [DateTimeOffset]$process.StartTime.ToUniversalTime()
        Executable = [string]$receipt.Executable
        Arguments = @($owned.Arguments)
      }
      Assert-TradingLabOwnedProcessCanStop `
        -RunToken ([string]$Context.RunToken) `
        -Receipt $receipt `
        -ObservedProcess $observed | Out-Null
      if (-not $process.HasExited) {
        $taskkillOut = Join-Path `
          $Context.ArtifactsDirectory `
          ("cleanup-taskkill-$($process.Id).stdout.log")
        $taskkillErr = Join-Path `
          $Context.ArtifactsDirectory `
          ("cleanup-taskkill-$($process.Id).stderr.log")
        $stopResult = Invoke-TradingLabExternalProcess `
          -Executable 'taskkill.exe' `
          -Arguments @('/PID', [string]$process.Id, '/T', '/F') `
          -WorkingDirectory $Context.ArtifactsDirectory `
          -StdoutPath $taskkillOut `
          -StderrPath $taskkillErr
        if ($stopResult.ExitCode -ne 0 -and -not $process.HasExited) {
          throw "Owned process tree stop failed with exit code $($stopResult.ExitCode)"
        }
        $process.WaitForExit(10000) | Out-Null
      }
      if ($process.HasExited) {
        $stdout = $owned.StdoutTask.GetAwaiter().GetResult()
        $stderr = $owned.StderrTask.GetAwaiter().GetResult()
        [System.IO.File]::WriteAllText(
          $owned.StdoutPath,
          $stdout,
          [System.Text.UTF8Encoding]::new($false)
        )
        [System.IO.File]::WriteAllText(
          $owned.StderrPath,
          $stderr,
          [System.Text.UTF8Encoding]::new($false)
        )
      }
      $details.Add([pscustomobject]@{
        receiptPath = $receiptPath
        processId = $receipt.ProcessId
        status = 'STOPPED'
      }) | Out-Null
      $script:TradingLabOwnedProcessRegistry.Remove($receiptPath) | Out-Null
      $process.Dispose()
    } catch {
      $failures.Add($_.Exception) | Out-Null
      $details.Add([pscustomobject]@{
        receiptPath = $receiptPath
        status = 'FAIL'
        reason = $_.Exception.Message
      }) | Out-Null
    }
  }
  if ($failures.Count -ne 0) {
    throw [System.AggregateException]::new(
      'Failed to stop one or more owned Trading Lab processes',
      [System.Exception[]]@($failures)
    )
  }
  return @($details)
}

function New-TradingLabSupervisorHttpFailure {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(100, 599)]
    [int]$StatusCode,

    [string]$ErrorCode,

    [System.Exception]$InnerException
  )

  $safeErrorCode = $null
  if (
    -not [string]::IsNullOrWhiteSpace($ErrorCode) -and
    $ErrorCode -cmatch '^[A-Z][A-Z0-9_]{0,63}$'
  ) {
    $safeErrorCode = $ErrorCode
  }
  $message = "Supervisor action failed with HTTP $StatusCode"
  if ($null -ne $safeErrorCode) {
    $message += " ($safeErrorCode)"
  }
  $failure = [System.InvalidOperationException]::new(
    $message,
    $InnerException
  )
  $failure.Data['TradingLabSupervisorHttpStatusCode'] = $StatusCode
  if ($null -ne $safeErrorCode) {
    $failure.Data['TradingLabSupervisorErrorCode'] = $safeErrorCode
  }
  return $failure
}

function ConvertFrom-TradingLabSupervisorErrorJson {
  [CmdletBinding()]
  param(
    [AllowEmptyString()]
    [string]$Json
  )

  try {
    $document = $Json | ConvertFrom-Json
    if (
      $null -eq $document -or
      $document.PSObject.Properties['ok'].Value -ne $false
    ) {
      return $null
    }
    $errorProperty = $document.PSObject.Properties['error']
    if ($null -eq $errorProperty -or $null -eq $errorProperty.Value) {
      return $null
    }
    $codeProperty = $errorProperty.Value.PSObject.Properties['code']
    if ($null -eq $codeProperty) {
      return $null
    }
    $code = [string]$codeProperty.Value
    if ($code -cnotmatch '^[A-Z][A-Z0-9_]{0,63}$') {
      return $null
    }
    return $code
  } catch {
    return $null
  }
}

function Read-TradingLabSupervisorErrorCode {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [System.Net.WebResponse]$Response
  )

  if (
    $Response.ContentLength -gt
      $script:TradingLabSupervisorErrorBodyLimitBytes
  ) {
    return $null
  }
  $responseStream = $null
  $buffer = [byte[]]::new(1024)
  $memory = [System.IO.MemoryStream]::new()
  try {
    $responseStream = $Response.GetResponseStream()
    if ($null -eq $responseStream) {
      return $null
    }
    while ($true) {
      $read = $responseStream.Read($buffer, 0, $buffer.Length)
      if ($read -eq 0) {
        break
      }
      if (
        $memory.Length + $read -gt
          $script:TradingLabSupervisorErrorBodyLimitBytes
      ) {
        return $null
      }
      $memory.Write($buffer, 0, $read)
    }
    $text = [System.Text.UTF8Encoding]::new($false, $true).GetString(
      $memory.ToArray()
    )
    return ConvertFrom-TradingLabSupervisorErrorJson -Json $text
  } catch {
    return $null
  } finally {
    if ($null -ne $responseStream) {
      $responseStream.Dispose()
    }
    $memory.Dispose()
  }
}

function Invoke-TradingLabSupervisorActionOnce {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('start', 'status', 'health')]
    [string]$Action,

    [Parameter(Mandatory = $true)]
    [string]$Token,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 10000)]
    [int]$RequestTimeoutMilliseconds
  )

  Assert-TradingLabRunToken -RunToken $Token
  $uri = [uri]"http://127.0.0.1:$script:TradingLabSupervisorPort/validation-supervisor"
  $request = [System.Net.HttpWebRequest]::CreateHttp($uri)
  $request.Method = 'POST'
  $request.ContentType = 'application/json'
  $request.Headers['Authorization'] = "Bearer $Token"
  $request.Timeout = $RequestTimeoutMilliseconds
  $request.ReadWriteTimeout = $RequestTimeoutMilliseconds
  $payload = [System.Text.UTF8Encoding]::new($false).GetBytes(
    (@{ action = $Action } | ConvertTo-Json -Compress)
  )
  $request.ContentLength = $payload.Length
  $stream = $request.GetRequestStream()
  try {
    $stream.Write($payload, 0, $payload.Length)
  } finally {
    $stream.Dispose()
  }
  try {
    $response = [System.Net.HttpWebResponse]$request.GetResponse()
  } catch [System.Net.WebException] {
    $webFailure = $_.Exception
    $errorResponse = $webFailure.Response
    if ($null -eq $errorResponse -or
      $errorResponse -isnot [System.Net.HttpWebResponse]) {
      throw
    }
    try {
      $statusCode = [int](
        [System.Net.HttpWebResponse]$errorResponse
      ).StatusCode
      $errorCode = Read-TradingLabSupervisorErrorCode `
        -Response $errorResponse
      throw (New-TradingLabSupervisorHttpFailure `
        -StatusCode $statusCode `
        -ErrorCode $errorCode `
        -InnerException $webFailure)
    } finally {
      $errorResponse.Dispose()
    }
  }
  try {
    $reader = [System.IO.StreamReader]::new($response.GetResponseStream())
    try {
      $body = $reader.ReadToEnd()
    } finally {
      $reader.Dispose()
    }
    if ([int]$response.StatusCode -ne 200) {
      throw (New-TradingLabSupervisorHttpFailure `
        -StatusCode ([int]$response.StatusCode))
    }
    $document = $body | ConvertFrom-Json
    if ($document.ok -ne $true -or [string]$document.action -cne $Action) {
      throw 'Supervisor action response is invalid'
    }
    return $body
  } finally {
    $response.Dispose()
  }
}

function Test-TradingLabTransientSupervisorHealthFailure {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [System.Exception]$Failure
  )

  $statusKey = 'TradingLabSupervisorHttpStatusCode'
  $codeKey = 'TradingLabSupervisorErrorCode'
  if (
    $Failure.Data.Contains($statusKey) -and
    $Failure.Data.Contains($codeKey)
  ) {
    $statusCode = [int]$Failure.Data[$statusKey]
    $errorCode = [string]$Failure.Data[$codeKey]
    return (
      (
        $statusCode -in @(502, 503) -and
        $errorCode -ceq 'HEALTH_UNAVAILABLE'
      ) -or (
        $statusCode -eq 504 -and
        $errorCode -ceq 'HEALTH_TIMEOUT'
      )
    )
  }
  if ($Failure -isnot [System.Net.WebException]) {
    return $false
  }
  return $Failure.Status -in @(
    [System.Net.WebExceptionStatus]::ConnectFailure,
    [System.Net.WebExceptionStatus]::ConnectionClosed,
    [System.Net.WebExceptionStatus]::KeepAliveFailure,
    [System.Net.WebExceptionStatus]::PipelineFailure,
    [System.Net.WebExceptionStatus]::ReceiveFailure,
    [System.Net.WebExceptionStatus]::SendFailure,
    [System.Net.WebExceptionStatus]::Timeout
  )
}

function Invoke-TradingLabSupervisorHealthWithRetry {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string]$Token,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 600)]
    [int]$DeadlineSeconds,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 60000)]
    [int]$InitialBackoffMilliseconds,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 60000)]
    [int]$MaximumBackoffMilliseconds,

    [Parameter(Mandatory = $true)]
    [scriptblock]$InvokeOnce,

    [Parameter(Mandatory = $true)]
    [scriptblock]$UtcNow,

    [Parameter(Mandatory = $true)]
    [scriptblock]$Sleep
  )

  if ($InitialBackoffMilliseconds -gt $MaximumBackoffMilliseconds) {
    throw 'Supervisor health initial backoff exceeds its maximum'
  }
  $startedAt = [DateTimeOffset](& $UtcNow)
  $deadline = $startedAt.AddSeconds($DeadlineSeconds)
  $current = $startedAt
  $backoffMilliseconds = $InitialBackoffMilliseconds
  $lastFailure = $null
  do {
    $remainingMilliseconds = [int][Math]::Floor(
      ($deadline - $current).TotalMilliseconds
    )
    if ($remainingMilliseconds -le 0) {
      break
    }
    $requestTimeoutMilliseconds = [Math]::Min(
      10000,
      $remainingMilliseconds
    )
    try {
      return & $InvokeOnce `
        'health' `
        $Token `
        $requestTimeoutMilliseconds
    } catch {
      $lastFailure = $_.Exception
      if (-not (
        Test-TradingLabTransientSupervisorHealthFailure `
          -Failure $lastFailure
      )) {
        throw
      }
    }
    $current = [DateTimeOffset](& $UtcNow)
    $remainingMilliseconds = [int][Math]::Floor(
      ($deadline - $current).TotalMilliseconds
    )
    if ($remainingMilliseconds -le 0) {
      break
    }
    $sleepMilliseconds = [Math]::Min(
      $backoffMilliseconds,
      $remainingMilliseconds
    )
    & $Sleep $sleepMilliseconds
    $backoffMilliseconds = [int][Math]::Min(
      $MaximumBackoffMilliseconds,
      ([long]$backoffMilliseconds * 2)
    )
    $current = [DateTimeOffset](& $UtcNow)
  } while ($current -lt $deadline)

  throw [System.TimeoutException]::new(
    (
      'Supervisor health did not become ready within the strict deadline ' +
      "of $DeadlineSeconds seconds"
    ),
    $lastFailure
  )
}

function Invoke-TradingLabSupervisorAction {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('start', 'status', 'health')]
    [string]$Action,

    [Parameter(Mandatory = $true)]
    [string]$Token,

    [ValidateRange(1, 600)]
    [int]$HealthDeadlineSeconds = (
      $script:TradingLabSupervisorHealthDeadlineSeconds
    ),

    [ValidateRange(1, 60000)]
    [int]$InitialHealthBackoffMilliseconds = (
      $script:TradingLabSupervisorHealthInitialBackoffMilliseconds
    ),

    [ValidateRange(1, 60000)]
    [int]$MaximumHealthBackoffMilliseconds = (
      $script:TradingLabSupervisorHealthMaximumBackoffMilliseconds
    ),

    [scriptblock]$InvokeOnce,

    [scriptblock]$UtcNow = { [DateTimeOffset]::UtcNow },

    [scriptblock]$Sleep = {
      param($milliseconds)
      [System.Threading.Thread]::Sleep($milliseconds)
    }
  )

  Assert-TradingLabRunToken -RunToken $Token
  if ($null -eq $InvokeOnce) {
    $InvokeOnce = {
      param($requestedAction, $requestedToken, $requestTimeoutMilliseconds)
      Invoke-TradingLabSupervisorActionOnce `
        -Action $requestedAction `
        -Token $requestedToken `
        -RequestTimeoutMilliseconds $requestTimeoutMilliseconds
    }
  }
  if ($Action -cne 'health') {
    return & $InvokeOnce $Action $Token 10000
  }
  return Invoke-TradingLabSupervisorHealthWithRetry `
    -Token $Token `
    -DeadlineSeconds $HealthDeadlineSeconds `
    -InitialBackoffMilliseconds $InitialHealthBackoffMilliseconds `
    -MaximumBackoffMilliseconds $MaximumHealthBackoffMilliseconds `
    -InvokeOnce $InvokeOnce `
    -UtcNow $UtcNow `
    -Sleep $Sleep
}

function Get-TradingLabFixedStackRuntimeSnapshot {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$Context,

    [Parameter(Mandatory = $true)]
    [string]$Label
  )

  $suffix = [guid]::NewGuid().ToString('N')
  $stdout = Join-Path `
    $Context.ArtifactsDirectory `
    ("$Label-$suffix.stdout.log")
  $stderr = Join-Path `
    $Context.ArtifactsDirectory `
    ("$Label-$suffix.stderr.log")
  $result = Invoke-TradingLabExternalProcess `
    -Executable 'docker' `
    -Arguments @(
      'ps',
      '--all',
      '--no-trunc',
      '--filter',
      "label=com.docker.compose.project=$script:TradingLabComposeProject",
      '--format',
      '{{json .}}'
    ) `
    -WorkingDirectory $script:TradingLabPlatformRoot `
    -StdoutPath $stdout `
    -StderrPath $stderr
  if ($result.ExitCode -ne 0) {
    throw "Fixed validation Docker snapshot failed with exit code $($result.ExitCode)"
  }
  return @(
    ConvertFrom-TradingLabDockerPsJson `
      -Json ([System.IO.File]::ReadAllText($stdout))
  )
}

function Stop-TradingLabOwnedFixedStack {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$Context
  )

  $leasePath = [string]$Context.FixedStackLeasePath
  $guardPath = [string]$Context.FixedStackGuardPath
  if (
    [bool]$Context.KeepValidationRunning -and
    (Test-Path -LiteralPath $leasePath -PathType Leaf)
  ) {
    Assert-TradingLabFixedStackGuardOwned `
      -GuardPath $guardPath `
      -RunToken ([string]$Context.RunToken) | Out-Null
    return [pscustomobject]@{
      Status = 'PARTIAL'
      Detail = 'Fixed validation stack retained by request'
    }
  }
  if (-not (Test-Path -LiteralPath $leasePath -PathType Leaf)) {
    if (-not (Test-Path -LiteralPath $guardPath -PathType Leaf)) {
      return [pscustomobject]@{
        Status = 'PASS'
        Detail = 'Fixed validation stack guard was not acquired'
      }
    }
    $guard = Assert-TradingLabFixedStackGuardOwned `
      -GuardPath $guardPath `
      -RunToken ([string]$Context.RunToken)
    $unleased = @(
      Get-TradingLabFixedStackRuntimeSnapshot `
        -Context $Context `
        -Label 'cleanup-unleased-stack-snapshot'
    )
    Assert-TradingLabFixedStackBaselineCurrent `
      -Baseline $guard.baseline `
      -ObservedContainers $unleased `
      -ArtifactsRoot $script:TradingLabArtifactsRoot | Out-Null
    Release-TradingLabFixedStackGuard `
      -GuardPath $guardPath `
      -RunToken ([string]$Context.RunToken) | Out-Null
    return [pscustomobject]@{
      Status = 'PASS'
      Detail = 'Fixed validation stack baseline was unchanged before lease'
    }
  }
  $lease = Read-TradingLabFixedStackLease `
    -LeasePath $leasePath `
    -RunToken ([string]$Context.RunToken)
  Assert-TradingLabFixedStackGuardOwned `
    -GuardPath $guardPath `
    -RunToken ([string]$Context.RunToken) | Out-Null
  $before = @(
    Get-TradingLabFixedStackRuntimeSnapshot `
      -Context $Context `
      -Label 'cleanup-before-stack-stop'
  )
  Assert-TradingLabFixedStackCanStop `
    -RunToken ([string]$Context.RunToken) `
    -Lease $lease `
    -ObservedContainers $before | Out-Null
  $stopStdout = Join-Path `
    $Context.ArtifactsDirectory `
    'cleanup-fixed-stack-stop.stdout.log'
  $stopStderr = Join-Path `
    $Context.ArtifactsDirectory `
    'cleanup-fixed-stack-stop.stderr.log'
  $runningIds = @(
    foreach ($service in @(Get-TradingLabFixedStackServiceNames)) {
      $identity = @(
        $before | Where-Object Service -CEQ $service
      )[0]
      if ([string]$identity.State -ceq 'running') {
        [string]$identity.ContainerId
      }
    }
  )
  if ($runningIds.Count -eq 0) {
    Write-TradingLabOwnedTextCreateNew `
      -Path $stopStdout `
      -Text 'leased-running-containers=0' | Out-Null
    Write-TradingLabOwnedTextCreateNew -Path $stopStderr -Text '' | Out-Null
    $stop = [pscustomobject]@{ ExitCode = 0 }
  } else {
    $stop = Invoke-TradingLabExternalProcess `
      -Executable 'docker' `
      -Arguments (@('stop') + $runningIds) `
      -WorkingDirectory $script:TradingLabPlatformRoot `
      -StdoutPath $stopStdout `
      -StderrPath $stopStderr
  }
  if ($stop.ExitCode -ne 0) {
    throw "Fixed validation container stop failed with exit code $($stop.ExitCode)"
  }
  $after = @(
    Get-TradingLabFixedStackRuntimeSnapshot `
      -Context $Context `
      -Label 'cleanup-after-stack-stop'
  )
  $leasedIds = @{}
  foreach ($identity in @($lease.ServiceIdentities)) {
    $leasedIds[[string]$identity.Service] = [string]$identity.ContainerId
  }
  if ($after.Count -ne $leasedIds.Count) {
    throw 'Fixed validation stack identity count drifted after stop'
  }
  foreach ($identity in $after) {
    if (
      -not $leasedIds.ContainsKey([string]$identity.Service) -or
      [string]$identity.ContainerId -cne
        $leasedIds[[string]$identity.Service] -or
      [string]$identity.State -cne 'exited'
    ) {
      throw 'Fixed validation stack did not stop with leased identities'
    }
  }
  Release-TradingLabFixedStackGuard `
    -GuardPath $guardPath `
    -RunToken ([string]$Context.RunToken) | Out-Null
  return [pscustomobject]@{
    Status = 'PASS'
    Detail = 'Fixed validation stack stopped with leased container IDs'
  }
}

function Write-TradingLabCleanupReceipt {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$Context,

    [Parameter(Mandatory = $true)]
    [ValidateSet('PASS', 'PARTIAL', 'FAIL')]
    [string]$Status,

    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [object[]]$Details
  )

  $receipt = [ordered]@{
    schemaVersion = 1
    runToken = [string]$Context.RunToken
    status = $Status
    completedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    details = @($Details)
  }
  Write-TradingLabOwnedJsonAtomic `
    -Path ([string]$Context.CleanupReceiptPath) `
    -Value $receipt `
    -Label 'Trading Lab cleanup receipt' | Out-Null
  return [pscustomobject]$receipt
}

function Read-TradingLabCleanupReceipt {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$Context
  )

  $artifacts = [System.IO.Path]::GetFullPath(
    [string]$Context.ArtifactsDirectory
  )
  $path = [System.IO.Path]::GetFullPath(
    [string]$Context.CleanupReceiptPath
  )
  if (
    [System.IO.Path]::GetFullPath((Split-Path -Parent $path)) -cne
      $artifacts
  ) {
    throw 'Trading Lab cleanup receipt must stay in the owned artifact directory'
  }
  if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
    throw 'Trading Lab cleanup receipt is missing'
  }
  Assert-TradingLabArtifactPathHasNoReparsePoint -Path $path
  try {
    $receipt = [System.IO.File]::ReadAllText($path) | ConvertFrom-Json
  } catch {
    throw 'Trading Lab cleanup receipt is invalid'
  }
  $fields = @($receipt.PSObject.Properties.Name | Sort-Object)
  if (
    (@($fields) -join '|') -cne
      (@(
        'completedAtUtc',
        'details',
        'runToken',
        'schemaVersion',
        'status'
      ) -join '|') -or
    $receipt.schemaVersion -ne 1 -or
    [string]$receipt.runToken -cne [string]$Context.RunToken -or
    [string]$receipt.status -notin @('PASS', 'PARTIAL', 'FAIL')
  ) {
    throw 'Trading Lab cleanup receipt ownership or schema is invalid'
  }
  ConvertTo-TradingLabEvidenceDateTimeOffset `
    -Value $receipt.completedAtUtc `
    -Label 'Trading Lab cleanup receipt' | Out-Null
  if ($null -eq $receipt.PSObject.Properties['details']) {
    throw 'Trading Lab cleanup receipt timestamp or details are invalid'
  }
  return $receipt
}

function Get-TradingLabOwnedCandidateRetentionEvidence {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$Context
  )

  $candidate = [System.IO.Path]::GetFullPath(
    [string]$Context.CandidatePlatformRoot
  )
  $candidateBase = Get-TradingLabCandidateBasePath
  $candidatePrefix = $candidateBase.TrimEnd('\', '/') +
    [System.IO.Path]::DirectorySeparatorChar
  if (-not $candidate.StartsWith(
    $candidatePrefix,
    [System.StringComparison]::OrdinalIgnoreCase
  )) {
    throw 'Trading Lab candidate cleanup target is outside the fixed temp root'
  }
  $candidateParent = [System.IO.Path]::GetFullPath(
    (Split-Path -Parent $candidate)
  )
  $candidateId = [System.IO.Path]::GetFileName($candidateParent)
  if (
    [System.IO.Path]::GetFullPath((Split-Path -Parent $candidateParent)) -cne
      $candidateBase -or
    [System.IO.Path]::GetFileName($candidate) -cne 'fx-trading-platform' -or
    $candidateId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{7,80}$'
  ) {
    throw 'Trading Lab candidate retention target shape is invalid'
  }
  $markerPath = [System.IO.Path]::GetFullPath(
    (Join-Path $candidate '.trading-lab-candidate-owner.json')
  )
  foreach ($path in @(
    $candidateBase,
    $candidateParent,
    $candidate,
    $markerPath
  )) {
    Assert-TradingLabArtifactPathHasNoReparsePoint -Path $path
  }
  if (-not (Test-Path -LiteralPath $candidate -PathType Container)) {
    return [pscustomobject][ordered]@{
      state = 'NOT_CREATED'
      candidatePath = $candidate
      candidateId = $candidateId
      ownerMarkerPath = $markerPath
      ownerMarkerSha256 = $null
      sourceTreeSha256 = $null
      sourceFileCount = 0
      sourceDirectoryCount = 0
      sourceManifestSha256 = $null
      candidateManifestSha256 = $null
    }
  }
  $candidateItem = Get-Item -LiteralPath $candidate -Force
  if (
    ($candidateItem.Attributes -band
      [System.IO.FileAttributes]::ReparsePoint) -ne 0
  ) {
    throw 'Trading Lab retained candidate cannot be a reparse point'
  }
  if (-not (Test-Path -LiteralPath $markerPath -PathType Leaf)) {
    throw 'Trading Lab candidate retention owner marker is missing'
  }
  $markerItem = Get-Item -LiteralPath $markerPath -Force
  if (
    ($markerItem.Attributes -band
      [System.IO.FileAttributes]::ReparsePoint) -ne 0
  ) {
    throw 'Trading Lab candidate retention owner marker cannot be a reparse point'
  }
  try {
    $marker = [System.IO.File]::ReadAllText($markerPath) | ConvertFrom-Json
  } catch {
    throw 'Trading Lab candidate retention owner marker is invalid'
  }
  $markerFields = @($marker.PSObject.Properties.Name | Sort-Object)
  if (
    (@($markerFields) -join '|') -cne
      (@(
        'candidateDirectoryCount',
        'candidateFileCount',
        'candidateManifestSha256',
        'runToken',
        'schemaVersion',
        'sourceDirectoryCount',
        'sourceFileCount',
        'sourceManifestSha256',
        'sourceRoot'
      ) -join '|') -or
    $marker.schemaVersion -ne 1 -or
    [string]$marker.runToken -cne [string]$Context.RunToken -or
    [System.IO.Path]::GetFullPath([string]$marker.sourceRoot) -cne
      [System.IO.Path]::GetFullPath($script:TradingLabPlatformRoot) -or
    [string]$marker.sourceManifestSha256 -notmatch '^[a-f0-9]{64}$' -or
    [string]$marker.candidateManifestSha256 -notmatch '^[a-f0-9]{64}$' -or
    [int]$marker.sourceFileCount -le 0 -or
    [int]$marker.candidateFileCount -ne [int]$marker.sourceFileCount -or
    [int]$marker.sourceDirectoryCount -lt 0 -or
    [int]$marker.candidateDirectoryCount -ne
      [int]$marker.sourceDirectoryCount -or
    [string]$marker.candidateManifestSha256 -cne
      [string]$marker.sourceManifestSha256
  ) {
    throw 'Trading Lab candidate retention owner marker does not match this run'
  }
  $retainedManifest = Get-TradingLabCandidateTreeManifest `
    -Root $candidate `
    -ExcludeBuildDirectories
  if ($retainedManifest.FileCount -eq 0) {
    throw 'Trading Lab retained candidate source manifest is empty'
  }
  if (
    [string]$retainedManifest.Sha256 -cne
      [string]$marker.candidateManifestSha256 -or
    [int]$retainedManifest.FileCount -ne [int]$marker.candidateFileCount -or
    [int]$retainedManifest.DirectoryCount -ne
      [int]$marker.candidateDirectoryCount
  ) {
    throw 'Trading Lab retained candidate manifest does not match owner evidence'
  }
  return [pscustomobject][ordered]@{
    state = 'RETAINED'
    candidatePath = $candidate
    candidateId = $candidateId
    ownerMarkerPath = $markerPath
    ownerMarkerSha256 = (
      Get-TradingLabFileSha256 -Path $markerPath
    ).ToLowerInvariant()
    sourceTreeSha256 = [string]$retainedManifest.Sha256
    sourceFileCount = [int]$retainedManifest.FileCount
    sourceDirectoryCount = [int]$retainedManifest.DirectoryCount
    sourceManifestSha256 = [string]$marker.sourceManifestSha256
    candidateManifestSha256 = [string]$marker.candidateManifestSha256
  }
}

function Get-TradingLabFinalVerificationStatus {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('PASS', 'PARTIAL', 'BLOCKED', 'FAIL')]
    [string]$BusinessStatus,

    [Parameter(Mandatory = $true)]
    [ValidateSet('PASS', 'PARTIAL', 'FAIL')]
    [string]$CleanupStatus
  )

  if ($BusinessStatus -eq 'FAIL' -or $CleanupStatus -eq 'FAIL') {
    return 'FAIL'
  }
  if ($BusinessStatus -eq 'BLOCKED') {
    return 'BLOCKED'
  }
  if ($BusinessStatus -eq 'PARTIAL' -or $CleanupStatus -eq 'PARTIAL') {
    return 'PARTIAL'
  }
  return 'PASS'
}

function Complete-TradingLabVerificationReport {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$Context,

    [Parameter(Mandatory = $true)]
    [string]$ProvisionalTemplate,

    [Parameter(Mandatory = $true)]
    [object]$CleanupReceipt
  )

  if ($null -eq $CleanupReceipt.PSObject.Properties['status']) {
    throw 'Trading Lab cleanup receipt status is missing'
  }
  $cleanupStatus = [string]$CleanupReceipt.status
  if ($cleanupStatus -notin @('PASS', 'PARTIAL', 'FAIL')) {
    throw 'Trading Lab cleanup receipt status is invalid'
  }
  $overallMatches = [regex]::Matches(
    $ProvisionalTemplate,
    '(?m)^Overall status: (?<status>PASS|PARTIAL|BLOCKED|FAIL)\r?$'
  )
  if ($overallMatches.Count -ne 1) {
    throw 'Trading Lab provisional report has an invalid overall status marker'
  }
  $businessStatus = [string]$overallMatches[0].Groups['status'].Value
  $finalStatus = Get-TradingLabFinalVerificationStatus `
    -BusinessStatus $businessStatus `
    -CleanupStatus $cleanupStatus
  $provisionalSentence = (
    'This provisional report must be finalized only after an owned cleanup receipt.'
  )
  $provisionalLimitation = (
    'This document is provisional until phase 15 supplies a cleanup receipt; ' +
    'no skipped or not-run gate is counted as PASS.'
  )
  foreach ($marker in @(
    '# Trading Lab Verification Report (provisional)',
    "Overall status: $businessStatus",
    'Cleanup status: PENDING',
    $provisionalSentence,
    $provisionalLimitation,
    '{{CLEANUP_RECEIPT}}'
  )) {
    if (
      [regex]::Matches(
        $ProvisionalTemplate,
        [regex]::Escape($marker)
      ).Count -ne 1
    ) {
      throw "Trading Lab provisional report marker is not unique: $marker"
    }
  }

  $receiptJson = $CleanupReceipt | ConvertTo-Json -Depth 12 -Compress
  $final = $ProvisionalTemplate.
    Replace(
      '# Trading Lab Verification Report (provisional)',
      '# Trading Lab Verification Report'
    ).
    Replace(
      "Overall status: $businessStatus",
      "Overall status: $finalStatus"
    ).
    Replace(
      'Cleanup status: PENDING',
      "Cleanup status: $cleanupStatus"
    ).
    Replace(
      $provisionalSentence,
      'This final report includes the owned cleanup receipt and cleanup-aware status.'
    ).
    Replace(
      $provisionalLimitation,
      (
        'Phase 15 cleanup is reflected in the final status; ' +
        'no skipped or not-run gate is counted as PASS.'
      )
    ).
    Replace(
      '{{CLEANUP_RECEIPT}}',
      $receiptJson
    )
  if (
    $final -match '(?i)\(provisional\)' -or
    $final.Contains('Cleanup status: PENDING') -or
    $final.Contains($provisionalSentence) -or
    $final.Contains($provisionalLimitation) -or
    $final.Contains('{{CLEANUP_RECEIPT}}')
  ) {
    throw 'Trading Lab final report still contains provisional state'
  }
  $final += [System.Environment]::NewLine
  Write-TradingLabOwnedTextCreateNew `
    -Path ([string]$Context.FinalReportPath) `
    -Text $final | Out-Null
  return [string]$Context.FinalReportPath
}

function Invoke-TradingLabOwnedCleanup {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$Context,

    [AllowNull()]
    [string]$ProvisionalTemplate
  )

  $failures = [System.Collections.Generic.List[System.Exception]]::new()
  $details = [System.Collections.Generic.List[object]]::new()
  try {
    $processes = @(Stop-TradingLabOwnedProcesses -Context $Context)
    $details.Add([pscustomobject]@{
      resource = 'owned-processes'
      status = 'PASS'
      processes = $processes
    }) | Out-Null
  } catch {
    $failures.Add($_.Exception) | Out-Null
    $details.Add([pscustomobject]@{
      resource = 'owned-processes'
      status = 'FAIL'
      reason = $_.Exception.Message
    }) | Out-Null
  }
  try {
    $commandsRoot = Join-Path `
      ([string]$Context.ArtifactsDirectory) `
      'commands'
    [System.IO.Directory]::CreateDirectory($commandsRoot) | Out-Null
    $accountCleanup = Invoke-TradingLabSmokeAccountCleanup `
      -Context $Context `
      -StdoutPath (Join-Path `
        $commandsRoot `
        'owned-account-cleanup.stdout.log') `
      -StderrPath (Join-Path `
        $commandsRoot `
        'owned-account-cleanup.stderr.log')
    $details.Add([pscustomobject]@{
      resource = 'owned-smoke-accounts'
      status = $accountCleanup.Status
      detail = $accountCleanup.Detail
      stdoutPath = $accountCleanup.StdoutPath
      stderrPath = $accountCleanup.StderrPath
    }) | Out-Null
  } catch {
    $failures.Add($_.Exception) | Out-Null
    $details.Add([pscustomobject]@{
      resource = 'owned-smoke-accounts'
      status = 'FAIL'
      reason = $_.Exception.Message
    }) | Out-Null
  }
  try {
    $stack = Stop-TradingLabOwnedFixedStack -Context $Context
    $details.Add([pscustomobject]@{
      resource = 'fixed-validation-stack'
      status = $stack.Status
      detail = $stack.Detail
    }) | Out-Null
  } catch {
    $failures.Add($_.Exception) | Out-Null
    $details.Add([pscustomobject]@{
      resource = 'fixed-validation-stack'
      status = 'FAIL'
      reason = $_.Exception.Message
    }) | Out-Null
  }
  try {
    $candidateEvidence = Get-TradingLabOwnedCandidateRetentionEvidence `
      -Context $Context
    $details.Add([pscustomobject]@{
      resource = 'temporary-maven-candidate'
      status = 'PASS'
      detail = $candidateEvidence
    }) | Out-Null
  } catch {
    $failures.Add($_.Exception) | Out-Null
    $details.Add([pscustomobject]@{
      resource = 'temporary-maven-candidate'
      status = 'FAIL'
      reason = $_.Exception.Message
    }) | Out-Null
  }
  if ([bool]$Context.KeepValidationRunning) {
    $details.Add([pscustomobject]@{
      resource = 'post-cleanup-ports'
      status = 'PARTIAL'
      detail = 'Port release is not asserted while validation remains running'
    }) | Out-Null
  } else {
    try {
      $cleanupPorts = @(
        8080,
        $script:TradingLabMainBackendPort,
        $script:TradingLabValidationRelayPort,
        $script:TradingLabSupervisorPort,
        $script:TradingLabAdminPort
      )
      Assert-TradingLabPortsAreFree -Ports $cleanupPorts | Out-Null
      $details.Add([pscustomobject]@{
        resource = 'post-cleanup-ports'
        status = 'PASS'
        ports = $cleanupPorts
      }) | Out-Null
    } catch {
      $failures.Add($_.Exception) | Out-Null
      $details.Add([pscustomobject]@{
        resource = 'post-cleanup-ports'
        status = 'FAIL'
        reason = $_.Exception.Message
      }) | Out-Null
    }
  }
  $status = if ($failures.Count -ne 0) {
    'FAIL'
  } elseif ([bool]$Context.KeepValidationRunning) {
    'PARTIAL'
  } else {
    'PASS'
  }
  try {
    $receipt = Write-TradingLabCleanupReceipt `
      -Context $Context `
      -Status $status `
      -Details @($details)
  } catch {
    $failures.Add($_.Exception) | Out-Null
  }
  if ($failures.Count -ne 0) {
    throw [System.AggregateException]::new(
      'Trading Lab owned cleanup failed',
      [System.Exception[]]@($failures)
    )
  }
  $script:TradingLabOwnedRunSecretRegistry.Remove(
    [string]$Context.RunToken
  ) | Out-Null
  return [pscustomobject]@{
    Status = $status
    ReceiptPath = [string]$Context.CleanupReceiptPath
    Receipt = $receipt
  }
}

function Get-TradingLabCompletedCommandRecord {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$RunState,

    [Parameter(Mandatory = $true)]
    [string]$Name
  )

  $matches = @(
    $RunState.CompletedPhases |
      ForEach-Object { @($_.Commands) } |
      Where-Object Name -ceq $Name
  )
  if ($matches.Count -ne 1) {
    throw "Trading Lab prerequisite command record is missing: $Name"
  }
  return $matches[0]
}

function Invoke-TradingLabRunExternalProcess {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$Command,

    [Parameter(Mandatory = $true)]
    [object]$Context,

    [Parameter(Mandatory = $true)]
    [string]$StdoutPath,

    [Parameter(Mandatory = $true)]
    [string]$StderrPath
  )

  $environment = Resolve-TradingLabRunEnvironment `
    -Environment $Command.Environment `
    -RunToken ([string]$Context.RunToken)
  try {
    return Invoke-TradingLabExternalProcess `
      -Executable ([string]$Command.Executable) `
      -Arguments @($Command.Arguments) `
      -WorkingDirectory ([string]$Command.WorkingDirectory) `
      -StdoutPath $StdoutPath `
      -StderrPath $StderrPath `
      -Environment $environment
  } finally {
    $environment = $null
  }
}

function Invoke-TradingLabDefaultCommand {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$Command,

    [Parameter(Mandatory = $true)]
    [object]$RunState
  )

  $commandsRoot = Join-Path `
    $RunState.Context.ArtifactsDirectory `
    'commands'
  [System.IO.Directory]::CreateDirectory($commandsRoot) | Out-Null
  $safeId = ([string]$Command.Id) -replace '[^A-Za-z0-9_.-]', '_'
  $stdout = Join-Path $commandsRoot "$safeId.stdout.log"
  $stderr = Join-Path $commandsRoot "$safeId.stderr.log"
  try {
    switch ([string]$Command.Operation) {
      { $_ -in @('COMMAND', 'CAPTURE_DOCKER_PS_JSON') } {
        return Invoke-TradingLabRunExternalProcess `
          -Command $Command `
          -Context $RunState.Context `
          -StdoutPath $stdout `
          -StderrPath $stderr
      }
      'MAVEN_TEST' {
        $result = Invoke-TradingLabRunExternalProcess `
          -Command $Command `
          -Context $RunState.Context `
          -StdoutPath $stdout `
          -StderrPath $stderr
        if ($result.ExitCode -eq 0) {
          $summary = Assert-TradingLabMavenEvidence `
            -Command $Command `
            -InvocationStartedAtUtc $result.InvocationStartedAtUtc
          $evidencePath = Copy-TradingLabSurefireEvidence -Command $Command
          $result | Add-Member `
            -NotePropertyName 'SurefireSummary' `
            -NotePropertyValue $summary `
            -Force
          [System.IO.File]::AppendAllText(
            $stdout,
            [System.Environment]::NewLine +
              ($summary | ConvertTo-Json -Compress) +
              [System.Environment]::NewLine +
              "surefireEvidence=$evidencePath" +
              [System.Environment]::NewLine,
            [System.Text.UTF8Encoding]::new($false)
          )
        }
        return $result
      }
      'ASSERT_PORTS_FREE' {
        Assert-TradingLabPortsAreFree `
          -Ports @($Command.Arguments | ForEach-Object { [int]$_ }) |
          Out-Null
        Write-TradingLabOwnedTextCreateNew `
          -Path $stdout `
          -Text ('ports-free=' + (@($Command.Arguments) -join ',')) |
          Out-Null
        Write-TradingLabOwnedTextCreateNew -Path $stderr -Text '' | Out-Null
      }
      'ASSERT_MAIN_DATA_SERVICES' {
        return Assert-TradingLabMainDataServicesReady `
          -WorkingDirectory ([string]$Command.WorkingDirectory) `
          -StdoutPath $stdout `
          -StderrPath $stderr
      }
      'CREATE_ISOLATED_CANDIDATE' {
        Copy-TradingLabFreshPlatformCandidate `
          -SourceRoot ([string]$Command.SourcePath) `
          -DestinationRoot ([string]$Command.DestinationPath) `
          -RunToken ([string]$RunState.Context.RunToken) | Out-Null
        Write-TradingLabOwnedTextCreateNew `
          -Path $stdout `
          -Text ([string]$Command.DestinationPath) | Out-Null
        Write-TradingLabOwnedTextCreateNew -Path $stderr -Text '' | Out-Null
      }
      'COPY_OWNED_BOOT_JAR' {
        $hash = Copy-TradingLabOwnedBootJar `
          -SourcePath ([string]$Command.SourcePath) `
          -DestinationPath ([string]$Command.DestinationPath)
        Write-TradingLabOwnedTextCreateNew `
          -Path $stdout `
          -Text "sha256=$hash" | Out-Null
        Write-TradingLabOwnedTextCreateNew -Path $stderr -Text '' | Out-Null
      }
      'ASSERT_FIXED_STACK_STARTABLE' {
        $observed = @(
          Get-TradingLabFixedStackRuntimeSnapshot `
            -Context $RunState.Context `
            -Label ([string]$Command.Id)
        )
        Assert-TradingLabFixedStackCanStart `
          -ObservedContainers $observed | Out-Null
        Write-TradingLabOwnedTextCreateNew `
          -Path $stdout `
          -Text 'fixed-stack-startable=true' | Out-Null
        Write-TradingLabOwnedTextCreateNew -Path $stderr -Text '' | Out-Null
      }
      'ACQUIRE_FIXED_STACK_GUARD' {
        $observed = @(
          Get-TradingLabFixedStackRuntimeSnapshot `
            -Context $RunState.Context `
            -Label 'guard-baseline'
        )
        $baseline = New-TradingLabFixedStackStartBaseline `
          -ObservedContainers $observed `
          -ArtifactsRoot $script:TradingLabArtifactsRoot
        Acquire-TradingLabFixedStackGuardAtomic `
          -GuardPath ([string]$Command.DestinationPath) `
          -RunToken ([string]$RunState.Context.RunToken) `
          -Baseline $baseline | Out-Null
        Write-TradingLabOwnedTextCreateNew `
          -Path $stdout `
          -Text (
            'fixed-stack-guard-acquired=true baseline-mode=' +
            [string]$baseline.Mode +
            ' identity-fingerprint=' +
            [string]$baseline.IdentityFingerprint
          ) | Out-Null
        Write-TradingLabOwnedTextCreateNew -Path $stderr -Text '' | Out-Null
      }
      'ASSERT_FIXED_STACK_BASELINE_CURRENT' {
        $guard = Assert-TradingLabFixedStackGuardOwned `
          -GuardPath ([string]$RunState.Context.FixedStackGuardPath) `
          -RunToken ([string]$RunState.Context.RunToken)
        $observed = @(
          Get-TradingLabFixedStackRuntimeSnapshot `
            -Context $RunState.Context `
            -Label ([string]$Command.Id)
        )
        Assert-TradingLabFixedStackBaselineCurrent `
          -Baseline $guard.baseline `
          -ObservedContainers $observed `
          -ArtifactsRoot $script:TradingLabArtifactsRoot | Out-Null
        Write-TradingLabOwnedTextCreateNew `
          -Path $stdout `
          -Text (
            'fixed-stack-baseline-current=true baseline-mode=' +
            [string]$guard.baseline.Mode +
            ' identity-fingerprint=' +
            [string]$guard.baseline.IdentityFingerprint
          ) | Out-Null
        Write-TradingLabOwnedTextCreateNew -Path $stderr -Text '' | Out-Null
      }
      'CAPTURE_FIXED_STACK_LEASE' {
        Assert-TradingLabFixedStackGuardOwned `
          -GuardPath ([string]$RunState.Context.FixedStackGuardPath) `
          -RunToken ([string]$RunState.Context.RunToken) | Out-Null
        $result = Invoke-TradingLabExternalProcess `
          -Executable ([string]$Command.Executable) `
          -Arguments @($Command.Arguments) `
          -WorkingDirectory ([string]$Command.WorkingDirectory) `
          -StdoutPath $stdout `
          -StderrPath $stderr
        if ($result.ExitCode -eq 0) {
          $observed = @(
            ConvertFrom-TradingLabDockerPsJson `
              -Json ([System.IO.File]::ReadAllText($stdout))
          )
          $lease = New-TradingLabFixedStackLease `
            -RunToken ([string]$RunState.Context.RunToken) `
            -ObservedContainers $observed
          Write-TradingLabFixedStackLeaseAtomic `
            -LeasePath ([string]$Command.DestinationPath) `
            -Lease $lease | Out-Null
        }
        return $result
      }
      'RECONCILE_VALIDATION_POSTGRES_CREDENTIAL' {
        return Invoke-TradingLabValidationPostgresCredentialReconciliation `
          -Context $RunState.Context `
          -StdoutPath $stdout `
          -StderrPath $stderr
      }
      'ASSERT_FIXED_STACK_LEASE_CURRENT' {
        $lease = Read-TradingLabFixedStackLease `
          -LeasePath ([string]$RunState.Context.FixedStackLeasePath) `
          -RunToken ([string]$RunState.Context.RunToken)
        $observed = @(
          Get-TradingLabFixedStackRuntimeSnapshot `
            -Context $RunState.Context `
            -Label 'after-supervisor-stack-snapshot'
        )
        Assert-TradingLabFixedStackCanStop `
          -RunToken ([string]$RunState.Context.RunToken) `
          -Lease $lease `
          -ObservedContainers $observed `
          -RequireRunning | Out-Null
        Write-TradingLabOwnedTextCreateNew `
          -Path $stdout `
          -Text 'fixed-stack-lease-current=true' | Out-Null
        Write-TradingLabOwnedTextCreateNew -Path $stderr -Text '' | Out-Null
      }
      'START_OWNED_PROCESS' {
        $receipt = Start-TradingLabOwnedProcess `
          -Command $Command `
          -Context $RunState.Context `
          -StdoutPath $stdout `
          -StderrPath $stderr
        return [pscustomobject]@{
          ExitCode = 0
          StdoutPath = $stdout
          StderrPath = $stderr
          ReceiptPath = [string]$Command.DestinationPath
          ProcessId = $receipt.ProcessId
        }
      }
      'START_OR_VERIFY_OWNED_PROCESS' {
        $receipt = Start-TradingLabOwnedProcess `
          -Command $Command `
          -Context $RunState.Context `
          -StdoutPath $stdout `
          -StderrPath $stderr `
          -WaitForHealth
        return [pscustomobject]@{
          ExitCode = 0
          StdoutPath = $stdout
          StderrPath = $stderr
          ReceiptPath = [string]$Command.DestinationPath
          ProcessId = $receipt.ProcessId
        }
      }
      'WAIT_MAIN_TRADING_LAB_IDLE' {
        return Invoke-TradingLabMainQueueIdleGate `
          -StdoutPath $stdout `
          -StderrPath $stderr `
          -DeadlineSeconds ([int]$Command.Arguments[0]) `
          -PollMilliseconds ([int]$Command.Arguments[1])
      }
      'PROVISION_SMOKE_ACCOUNTS' {
        return Invoke-TradingLabSmokeAccountProvision `
          -Context $RunState.Context `
          -StdoutPath $stdout `
          -StderrPath $stderr
      }
      'SUPERVISOR_ACTION' {
        $runSecrets = Get-TradingLabOwnedRunSecrets `
          -RunToken ([string]$RunState.Context.RunToken)
        $body = Invoke-TradingLabSupervisorAction `
          -Action ([string]$Command.Arguments[0]) `
          -Token ([string]$runSecrets.Secrets['SUPERVISOR_INTERNAL_TOKEN'])
        $runSecrets = $null
        Write-TradingLabOwnedTextCreateNew -Path $stdout -Text $body |
          Out-Null
        Write-TradingLabOwnedTextCreateNew -Path $stderr -Text '' | Out-Null
      }
      'PROVISIONAL_REPORT' {
        if ([string]::IsNullOrWhiteSpace([string]$Command.Payload)) {
          throw 'Provisional Trading Lab report payload is missing'
        }
        Write-TradingLabOwnedTextCreateNew `
          -Path ([string]$Command.DestinationPath) `
          -Text ([string]$Command.Payload) | Out-Null
        Write-TradingLabOwnedTextCreateNew `
          -Path $stdout `
          -Text ([string]$Command.DestinationPath) | Out-Null
        Write-TradingLabOwnedTextCreateNew -Path $stderr -Text '' | Out-Null
      }
      'OWNED_CLEANUP' {
        $cleanup = Invoke-TradingLabOwnedCleanup `
          -Context $RunState.Context
        Write-TradingLabOwnedTextCreateNew `
          -Path $stdout `
          -Text ($cleanup | ConvertTo-Json -Compress) | Out-Null
        Write-TradingLabOwnedTextCreateNew -Path $stderr -Text '' | Out-Null
        return [pscustomobject]@{
          ExitCode = 0
          StdoutPath = $stdout
          StderrPath = $stderr
          Classification = $cleanup.Status
          CleanupReceipt = $cleanup.Receipt
        }
      }
      default {
        throw "Unsupported Trading Lab command operation: $($Command.Operation)"
      }
    }
    return [pscustomobject]@{
      ExitCode = 0
      StdoutPath = $stdout
      StderrPath = $stderr
    }
  } catch {
    if (Test-Path -LiteralPath $stderr -PathType Leaf) {
      [System.IO.File]::WriteAllText(
        $stderr,
        $_.Exception.Message + [System.Environment]::NewLine,
        [System.Text.UTF8Encoding]::new($false)
      )
    } else {
      Write-TradingLabOwnedTextCreateNew `
        -Path $stderr `
        -Text ($_.Exception.Message + [System.Environment]::NewLine) |
        Out-Null
    }
    if (-not (Test-Path -LiteralPath $stdout -PathType Leaf)) {
      Write-TradingLabOwnedTextCreateNew -Path $stdout -Text '' | Out-Null
    }
    return [pscustomobject]@{
      ExitCode = 1
      StdoutPath = $stdout
      StderrPath = $stderr
      FailureType = $_.Exception.GetType().FullName
    }
  }
}

function Invoke-TradingLabValidationContract {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [scriptblock]$InvokeSupervisorAction,

    [Parameter(Mandatory = $true)]
    [scriptblock]$RunBody,

    [Parameter(Mandatory = $true)]
    [scriptblock]$StopValidation,

    [switch]$KeepValidationRunning
  )

  $primaryFailure = $null
  try {
    foreach ($action in Get-TradingLabSupervisorActionOrder) {
      & $InvokeSupervisorAction $action
    }
    & $RunBody
  } catch {
    $primaryFailure = $_.Exception
  }

  $cleanupFailure = $null
  if (-not $KeepValidationRunning) {
    try {
      & $StopValidation
    } catch {
      $cleanupFailure = $_.Exception
    }
  }

  if ($null -ne $primaryFailure -and $null -ne $cleanupFailure) {
    $message = (
      "Trading Lab primary failure: $($primaryFailure.Message); " +
      "cleanup failure: $($cleanupFailure.Message)"
    )
    throw [System.AggregateException]::new(
      $message,
      [System.Exception[]]@($primaryFailure, $cleanupFailure)
    )
  }
  if ($null -ne $primaryFailure) {
    throw $primaryFailure
  }
  if ($null -ne $cleanupFailure) {
    throw $cleanupFailure
  }
}

function New-TradingLabSecureRunToken {
  [CmdletBinding()]
  param()

  $bytes = [byte[]]::new(32)
  $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  try {
    $random.GetBytes($bytes)
  } finally {
    $random.Dispose()
  }
  $hex = [System.BitConverter]::ToString($bytes)
  return $hex.Replace('-', '').ToLowerInvariant()
}

function Invoke-TradingLabDefaultNormalPath {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [object]$PackageScripts,

    [switch]$SkipBrowser,

    [switch]$KeepValidationRunning,

    [string]$RequestedArtifactsDirectory
  )

  $runToken = New-TradingLabSecureRunToken
  $runId = (
    [DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-' +
    [guid]::NewGuid().ToString('N').Substring(0, 8)
  )
  $requested = if (
    [string]::IsNullOrWhiteSpace($RequestedArtifactsDirectory)
  ) {
    $runId
  } else {
    $RequestedArtifactsDirectory
  }
  $ownedArtifacts = New-TradingLabOwnedArtifactDirectory `
    -ArtifactsRoot $script:TradingLabArtifactsRoot `
    -ArtifactsDirectory $requested `
    -RunToken $runToken
  $candidateId = New-TradingLabCandidateId
  $context = New-TradingLabDefaultRunContext `
    -ArtifactsDirectory $ownedArtifacts `
    -RunToken $runToken `
    -CandidateId $candidateId `
    -KeepValidationRunning:$KeepValidationRunning
  $executor = {
    param($command, $runState)
    Invoke-TradingLabDefaultCommand `
      -Command $command `
      -RunState $runState
  }
  $clock = { [DateTimeOffset]::UtcNow }
  return Invoke-TradingLabValidationRun `
    -Context $context `
    -PackageScripts $PackageScripts `
    -InvokeCommand $executor `
    -GetUtcNow $clock `
    -SkipBrowser:$SkipBrowser `
    -ThrowOnFailure
}

if ($ContractOnly) {
  return
}

$packageJsonPath = Join-Path $script:TradingLabPlatformRoot 'package.json'
try {
  $defaultPackageScripts = (
    [System.IO.File]::ReadAllText($packageJsonPath) | ConvertFrom-Json
  ).scripts
} catch {
  throw 'BLOCKED: Trading Lab runner cannot read package scripts'
}
$requiredIsolatedSmokeAlias = 'smoke:trading-lab:isolated'
if (-not $SkipBrowser -and -not (
  Test-TradingLabPackageAlias `
    -PackageScripts $defaultPackageScripts `
    -Alias $requiredIsolatedSmokeAlias
)) {
  throw (
    'BLOCKED: missing required Task 5 isolated smoke package alias ' +
    $requiredIsolatedSmokeAlias
  )
}
$defaultRun = Invoke-TradingLabDefaultNormalPath `
  -PackageScripts $defaultPackageScripts `
  -SkipBrowser:$SkipBrowser `
  -KeepValidationRunning:$KeepValidationRunning `
  -RequestedArtifactsDirectory $ArtifactsDirectory
$defaultRun
