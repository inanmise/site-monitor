#!/usr/bin/env bash
# k6 sözleşme testleri için GERÇEK ikilileri getirir (2026-10-29, k6 0.49 → 2.3 geçişi, A13). Docker gerekir.
#
# Kullanım:  bash scripts/fetch-k6-binaries.sh <çıkış-dizini>
#   → <çıkış-dizini>/k6-legacy  ASIL: Dockerfile'daki grafana/k6:${K6_VERSION}@${K6_DIGEST} imajından — prod imajının
#                                kopyaladığı katmanın birebir aynısı.
#   → <çıkış-dizini>/k6-next    ADAY: Dockerfile'ın `k6-next` aşaması DERLENİR (tarball indirme + SHA-256 + sürüm
#                                denetimi orada) ve ikili oradan çıkarılır — PR CI'ı o aşamayı her seferinde derler; prod
#                                imajıyla aynı adımlar, aynı sağlama toplamı.
#
# Sürümlerin ve özetlerin TEK kaynağı Dockerfile'dır: sürüm değişince bu betik değişmez.
set -euo pipefail

OUT="${1:?kullanım: fetch-k6-binaries.sh <çıkış-dizini>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DF="$ROOT/Dockerfile"
arg() { sed -n "s/^ARG $1=//p" "$DF" | head -1 | tr -d '[:space:]'; }

K6_VERSION="$(arg K6_VERSION)"
K6_DIGEST="$(arg K6_DIGEST)"
K6_NEXT_VERSION="$(arg K6_NEXT_VERSION)"
for v in K6_VERSION K6_DIGEST K6_NEXT_VERSION; do
  [ -n "${!v}" ] || { echo "::error::Dockerfile'da $v bulunamadı" >&2; exit 1; }
done
mkdir -p "$OUT"

cids=()
cleanup() { for c in "${cids[@]}"; do docker rm -f "$c" >/dev/null 2>&1 || true; done; }
trap cleanup EXIT

# Asıl ikili: prod imajının kopyaladığı katman.
img="grafana/k6:${K6_VERSION}@${K6_DIGEST}"
docker pull -q "$img" >/dev/null
cid="$(docker create "$img")"; cids+=("$cid")
docker cp "$cid:/usr/bin/k6" "$OUT/k6-legacy"
chmod 0755 "$OUT/k6-legacy"

# Aday ikili: Dockerfile'ın kendi k6-next aşaması (sağlama toplamı ve sürüm denetimi aşamanın içinde).
docker build -q --target k6-next -t sitemonitor-k6-next:contract "$ROOT" >/dev/null
cid="$(docker create sitemonitor-k6-next:contract)"; cids+=("$cid")
docker cp "$cid:/k6" "$OUT/k6-next"
chmod 0755 "$OUT/k6-next"

"$OUT/k6-legacy" version | grep -q "v${K6_VERSION} " || { echo "::error::k6-legacy sürümü tutmuyor: $("$OUT/k6-legacy" version)" >&2; exit 1; }
"$OUT/k6-next" version | grep -q "v${K6_NEXT_VERSION} " || { echo "::error::k6-next sürümü tutmuyor: $("$OUT/k6-next" version)" >&2; exit 1; }
echo "k6-legacy: $("$OUT/k6-legacy" version)"
echo "k6-next:   $("$OUT/k6-next" version)"
