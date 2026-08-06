<#
.SYNOPSIS
  Post-deploy smoke test for SiteMonitor. Probes the most load-bearing endpoints
  to verify the app is alive, the auth gate is honoured, and the public health
  surface returns the expected shape.

.PARAMETER BaseUrl
  Root URL of the running SiteMonitor instance. Defaults to http://localhost:8080.

.PARAMETER TimeoutSeconds
  HTTP timeout for each probe. Defaults to 5 seconds.

.EXAMPLE
  pwsh ./scripts/smoke.ps1
  pwsh ./scripts/smoke.ps1 -BaseUrl https://cert-monitor.example.com
#>
[CmdletBinding()]
param(
  [string] $BaseUrl = 'http://localhost:8080',
  [int]    $TimeoutSeconds = 5
)

$ErrorActionPreference = 'Stop'
$failures = @()

function Test-Probe {
  param(
    [string] $Name,
    [string] $Url,
    [int[]]  $ExpectedStatuses,
    [string] $Method = 'GET',
    [string] $Body = $null
  )
  Write-Host -NoNewline ("  [{0,-30}] " -f $Name)
  try {
    $params = @{
      Uri             = $Url
      Method          = $Method
      UseBasicParsing = $true
      TimeoutSec      = $TimeoutSeconds
      ErrorAction     = 'Stop'
    }
    if ($Body) {
      $params.Body = $Body
      $params.ContentType = 'application/json'
    }
    $resp = Invoke-WebRequest @params
    $status = [int]$resp.StatusCode
  } catch {
    $resp = $_.Exception.Response
    $status = if ($resp) { [int]$resp.StatusCode } else { 0 }
  }
  if ($ExpectedStatuses -contains $status) {
    Write-Host ("OK ({0})" -f $status) -ForegroundColor Green
  } else {
    Write-Host ("FAIL got {0}, expected one of {1}" -f $status, ($ExpectedStatuses -join ',')) -ForegroundColor Red
    $script:failures += $Name
  }
}

Write-Host ""
Write-Host "SiteMonitor smoke test against $BaseUrl" -ForegroundColor Cyan
Write-Host ("=" * 70)

Test-Probe -Name 'Health'                  -Url "$BaseUrl/health"                            -ExpectedStatuses 200
Test-Probe -Name 'Login gate (anon)'       -Url "$BaseUrl/api/certificates"                  -ExpectedStatuses 401
Test-Probe -Name 'Login endpoint reachable' -Url "$BaseUrl/api/login" -Method POST -Body '{"username":"x","password":"x"}' -ExpectedStatuses 401, 429
Test-Probe -Name 'Network status (anon)'   -Url "$BaseUrl/api/system/network-status"         -ExpectedStatuses 401
Test-Probe -Name 'Static asset (index)'    -Url "$BaseUrl/"                                  -ExpectedStatuses 200

Write-Host ("=" * 70)
if ($failures.Count -eq 0) {
  Write-Host "All smoke probes passed." -ForegroundColor Green
  exit 0
} else {
  Write-Host ("Smoke test FAILED ({0} probe(s)): {1}" -f $failures.Count, ($failures -join ', ')) -ForegroundColor Red
  exit 1
}
