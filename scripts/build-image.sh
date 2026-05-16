#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# build-image.sh — Branch-aware Docker image builder
#
# Usage:
#   ./scripts/build-image.sh                  # auto-detect branch
#   ./scripts/build-image.sh --push           # build + push to registry
#   ./scripts/build-image.sh --registry ghcr.io/your-org --push
#
# Branch → Image tag mapping:
#   master          → cert-monitor:1.0.0, cert-monitor:latest
#   release/v1.0.0  → cert-monitor:1.0.0-rc, cert-monitor:staging
#   develop         → cert-monitor:develop-<sha>, cert-monitor:develop
#   feature/*       → cert-monitor:feature-<sha>  (local only)
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

REGISTRY="${REGISTRY:-}"
IMAGE_NAME="cert-monitor"
PUSH=false

for arg in "$@"; do
  case $arg in
    --push)       PUSH=true ;;
    --registry=*) REGISTRY="${arg#*=}" ;;
    --registry)   shift; REGISTRY="$1" ;;
  esac
done

BRANCH=$(git rev-parse --abbrev-ref HEAD)
GIT_SHA=$(git rev-parse --short HEAD)
BUILD_DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
VERSION=$(cat VERSION | tr -d '[:space:]')

# ── Determine tags based on branch ──────────────────────────
TAGS=()

case "$BRANCH" in
  master)
    TAGS+=("${VERSION}" "latest")
    ENV_VALUES="helm/cert-monitor/environments/master.yaml"
    ;;
  release/*)
    RC_VERSION="${VERSION}-rc"
    TAGS+=("${RC_VERSION}" "staging")
    ENV_VALUES="helm/cert-monitor/environments/release.yaml"
    ;;
  develop)
    TAGS+=("develop-${GIT_SHA}" "develop")
    ENV_VALUES="helm/cert-monitor/environments/develop.yaml"
    ;;
  *)
    SAFE_BRANCH=$(echo "$BRANCH" | tr '/' '-' | tr -cd '[:alnum:]-')
    TAGS+=("${SAFE_BRANCH}-${GIT_SHA}")
    ENV_VALUES=""
    ;;
esac

# ── Build ────────────────────────────────────────────────────
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Branch    : $BRANCH"
echo "  Version   : $VERSION"
echo "  Git SHA   : $GIT_SHA"
echo "  Tags      : ${TAGS[*]}"
echo "  Registry  : ${REGISTRY:-<local>}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

PRIMARY_TAG="${TAGS[0]}"
FULL_IMAGE="${REGISTRY:+${REGISTRY}/}${IMAGE_NAME}:${PRIMARY_TAG}"

docker build \
  --build-arg VERSION="$VERSION" \
  --build-arg BUILD_DATE="$BUILD_DATE" \
  --build-arg GIT_COMMIT="$GIT_SHA" \
  -t "$FULL_IMAGE" \
  .

# Apply additional tags
for tag in "${TAGS[@]:1}"; do
  ALIAS="${REGISTRY:+${REGISTRY}/}${IMAGE_NAME}:${tag}"
  docker tag "$FULL_IMAGE" "$ALIAS"
  echo "  Tagged: $ALIAS"
done

echo ""
echo "Build complete: $FULL_IMAGE"

# ── Push ────────────────────────────────────────────────────
if [ "$PUSH" = true ]; then
  if [ -z "$REGISTRY" ]; then
    echo "ERROR: --push requires --registry=<registry-url>" >&2
    exit 1
  fi
  for tag in "${TAGS[@]}"; do
    docker push "${REGISTRY}/${IMAGE_NAME}:${tag}"
    echo "  Pushed: ${REGISTRY}/${IMAGE_NAME}:${tag}"
  done
fi

# ── Helm hint ───────────────────────────────────────────────
if [ -n "$ENV_VALUES" ]; then
  echo ""
  echo "Deploy to Kubernetes:"
  echo "  helm upgrade --install cert-monitor ./helm/cert-monitor \\"
  echo "    -f helm/cert-monitor/values.yaml \\"
  echo "    -f ${ENV_VALUES} \\"
  echo "    --set image.tag=${PRIMARY_TAG} \\"
  echo "    --set image.repository=${REGISTRY:+${REGISTRY}/}${IMAGE_NAME} \\"
  echo "    -n cert-monitor --create-namespace"
fi
