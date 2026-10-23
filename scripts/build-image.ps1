# ─────────────────────────────────────────────────────────────────────────────
# build-image.ps1 — Branch-aware Docker image builder (Windows / PowerShell)
#
# Usage:
#   .\scripts\build-image.ps1
#   .\scripts\build-image.ps1 -Push
#   .\scripts\build-image.ps1 -Registry "ghcr.io/your-org" -Push
#   .\scripts\build-image.ps1 -Registry "ghcr.io/your-org" -PreviousImage "ghcr.io/your-org/site-monitor:v20.166.0"
#
# Previous releases' page files (2026-10-23, multi-pod release 2b · MP-47): the image also carries the own assets of
# the last releases (Dockerfile PREVIOUS_IMAGE) so a rolling update does not serve a blank page. With -Registry the
# previous image defaults to this branch's moving tag in that registry (master → latest, release/* → staging,
# develop → develop), digest-pinned; -PreviousImage <ref> picks another one (e.g. the image running in prod),
# -NoPrevious builds without it (like a local build). Not readable → built without carried files (warning).
# ─────────────────────────────────────────────────────────────────────────────
param(
    [string]$Registry = "",
    [switch]$Push,
    [string]$PreviousImage = "",
    [switch]$NoPrevious
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ImageName  = "site-monitor"
$Branch     = & git rev-parse --abbrev-ref HEAD
$GitSha     = & git rev-parse --short HEAD
# UTC'ye çevir: eskiden yerel saate literal "Z" ekleniyordu (Europe/Istanbul makinede 3 saat ileri etiket).
$BuildDate  = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
$Version    = (Get-Content "VERSION" -Raw).Trim()

# ── Determine tags based on branch ──────────────────────────
$Tags        = @()
$EnvValues   = ""

switch -Wildcard ($Branch) {
    "master" {
        $Tags      = @($Version, "latest")
        $EnvValues = "helm/site-monitor/environments/master.yaml"
    }
    "release/*" {
        $Tags      = @("$Version-rc", "staging")
        $EnvValues = "helm/site-monitor/environments/release.yaml"
    }
    "develop" {
        $Tags      = @("develop-$GitSha", "develop")
        $EnvValues = "helm/site-monitor/environments/develop.yaml"
    }
    default {
        $SafeBranch = $Branch -replace '[/\\]', '-' -replace '[^a-zA-Z0-9-]', ''
        $Tags       = @("$SafeBranch-$GitSha")
    }
}

$Prefix      = if ($Registry) { "$Registry/" } else { "" }
$PrimaryTag  = $Tags[0]
$FullImage   = "${Prefix}${ImageName}:${PrimaryTag}"

# ── Previous image (MP-47): digest-pinned, empty = nothing carried ──
$PrevRef = ""
if (-not $NoPrevious) {
    if ($PreviousImage) { $PrevRef = $PreviousImage }
    elseif ($Registry -and $Tags.Count -gt 1) { $PrevRef = "${Registry}/${ImageName}:$($Tags[-1])" }
}
$PrevPinned = ""
if ($PrevRef) {
    if ($PrevRef -match '@sha256:[0-9a-f]{64}$') {
        $PrevPinned = $PrevRef
    } else {
        # Windows PowerShell 5.1: native stderr under ErrorActionPreference=Stop would throw — read it non-fatally.
        $digest = ""
        $saved = $ErrorActionPreference
        try {
            $ErrorActionPreference = "Continue"
            $digest = (& docker buildx imagetools inspect $PrevRef --format '{{json .Manifest.Digest}}' 2>$null | Out-String).Trim().Trim('"')
        } catch { $digest = "" } finally { $ErrorActionPreference = $saved }
        if ($digest -match '^sha256:[0-9a-f]{64}$') { $PrevPinned = "${PrevRef}@${digest}" }
        else { Write-Warning "Previous image $PrevRef could not be read - building WITHOUT the previous releases' page files" }
    }
}

Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan
Write-Host "  Branch    : $Branch"
Write-Host "  Version   : $Version"
Write-Host "  Git SHA   : $GitSha"
Write-Host "  Tags      : $($Tags -join ', ')"
Write-Host "  Registry  : $(if ($Registry) { $Registry } else { '<local>' })"
Write-Host "  Previous  : $(if ($PrevPinned) { $PrevPinned } else { '<none - nothing carried>' })"
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan

# ── Build ────────────────────────────────────────────────────
docker build `
    --build-arg VERSION="$Version" `
    --build-arg BUILD_DATE="$BuildDate" `
    --build-arg GIT_COMMIT="$GitSha" `
    --build-arg PREVIOUS_IMAGE="$PrevPinned" `
    -t $FullImage `
    .

if ($LASTEXITCODE -ne 0) { throw "Docker build failed" }

# Apply additional tags
foreach ($tag in $Tags | Select-Object -Skip 1) {
    $Alias = "${Prefix}${ImageName}:${tag}"
    docker tag $FullImage $Alias
    Write-Host "  Tagged: $Alias" -ForegroundColor Green
}

Write-Host ""
Write-Host "Build complete: $FullImage" -ForegroundColor Green

# ── Push ────────────────────────────────────────────────────
if ($Push) {
    if (-not $Registry) {
        Write-Error "-Push requires -Registry <registry-url>"
        exit 1
    }
    foreach ($tag in $Tags) {
        $img = "${Registry}/${ImageName}:${tag}"
        docker push $img
        Write-Host "  Pushed: $img" -ForegroundColor Green
    }
}

# ── Helm hint ────────────────────────────────────────────────
if ($EnvValues) {
    Write-Host ""
    Write-Host "Deploy to Kubernetes:" -ForegroundColor Yellow
    Write-Host "  helm upgrade --install site-monitor ./helm/site-monitor ``"
    Write-Host "    -f helm/site-monitor/values.yaml ``"
    Write-Host "    -f $EnvValues ``"
    Write-Host "    --set image.tag=$PrimaryTag ``"
    Write-Host "    --set image.repository=${Prefix}${ImageName} ``"
    Write-Host "    -n site-monitor --create-namespace"
}
