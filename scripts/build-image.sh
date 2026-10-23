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
#   master          → site-monitor:1.0.0, site-monitor:latest
#   release/v1.0.0  → site-monitor:1.0.0-rc, site-monitor:staging
#   develop         → site-monitor:develop-<sha>, site-monitor:develop
#   feature/*       → site-monitor:feature-<sha>  (local only)
#
# Previous releases' page files (2026-10-23, multi-pod release 2b · MP-47): with a registry the image also carries the
# own assets of the last releases, read from this branch's moving tag (latest / staging / develop), digest-pinned
# (scripts/resolve-previous-image.sh). PREVIOUS_IMAGE=<ref> picks another image (e.g. the one running in prod),
# PREVIOUS_IMAGE=none builds without it.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

REGISTRY="${REGISTRY:-}"
IMAGE_NAME="site-monitor"
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
    ENV_VALUES="helm/site-monitor/environments/master.yaml"
    ;;
  release/*)
    RC_VERSION="${VERSION}-rc"
    TAGS+=("${RC_VERSION}" "staging")
    ENV_VALUES="helm/site-monitor/environments/release.yaml"
    ;;
  develop)
    TAGS+=("develop-${GIT_SHA}" "develop")
    ENV_VALUES="helm/site-monitor/environments/develop.yaml"
    ;;
  *)
    SAFE_BRANCH=$(echo "$BRANCH" | tr '/' '-' | tr -cd '[:alnum:]-')
    TAGS+=("${SAFE_BRANCH}-${GIT_SHA}")
    ENV_VALUES=""
    ;;
esac

# ── Previous image (MP-47): digest-pinned, empty = nothing carried ──
PREV_REF="${PREVIOUS_IMAGE:-}"
if [ "$PREV_REF" = "none" ]; then PREV_REF=""
elif [ -z "$PREV_REF" ] && [ -n "$REGISTRY" ] && [ "${#TAGS[@]}" -gt 1 ]; then PREV_REF="${REGISTRY}/${IMAGE_NAME}:${TAGS[-1]}"; fi
PREV_PINNED=""
if [ -n "$PREV_REF" ]; then
  case "$PREV_REF" in
    *@sha256:*) PREV_PINNED="$PREV_REF" ;;
    *) PREV_PINNED="$(bash "$(dirname "$0")/resolve-previous-image.sh" "$PREV_REF")" ;;
  esac
fi

# ── Build ────────────────────────────────────────────────────
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Branch    : $BRANCH"
echo "  Version   : $VERSION"
echo "  Git SHA   : $GIT_SHA"
echo "  Tags      : ${TAGS[*]}"
echo "  Registry  : ${REGISTRY:-<local>}"
echo "  Previous  : ${PREV_PINNED:-<none - nothing carried>}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

PRIMARY_TAG="${TAGS[0]}"
FULL_IMAGE="${REGISTRY:+${REGISTRY}/}${IMAGE_NAME}:${PRIMARY_TAG}"

docker build \
  --build-arg VERSION="$VERSION" \
  --build-arg BUILD_DATE="$BUILD_DATE" \
  --build-arg GIT_COMMIT="$GIT_SHA" \
  --build-arg PREVIOUS_IMAGE="$PREV_PINNED" \
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
  echo "  helm upgrade --install site-monitor ./helm/site-monitor \\"
  echo "    -f helm/site-monitor/values.yaml \\"
  echo "    -f ${ENV_VALUES} \\"
  echo "    --set image.tag=${PRIMARY_TAG} \\"
  echo "    --set image.repository=${REGISTRY:+${REGISTRY}/}${IMAGE_NAME} \\"
  echo "    -n site-monitor --create-namespace"
fi
