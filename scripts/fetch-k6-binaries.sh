#!/usr/bin/env bash
# k6 sözleşme testleri için GERÇEK ikilileri getirir (2026-10-29, k6 0.49 → 2.3 geçişi, A13; 2026-11-16'dan beri imajda
# etkin + ADAY ikili — k6 2.3 → 2.4 geçişi). Docker gerekir.
#
# Kullanım:  bash scripts/fetch-k6-binaries.sh <çıkış-dizini>
#   → <çıkış-dizini>/k6            etkin ikili (Dockerfile ARG K6_VERSION — imajda /usr/bin/k6)
#   → <çıkış-dizini>/k6-candidate  aday ikili (Dockerfile ARG K6_CANDIDATE_VERSION — imajda /opt/k6-candidate/k6)
# Dockerfile'ın `k6-bin` aşaması DERLENİR (tarball indirme + SHA-256 + sürüm denetimi orada) ve iki ikili oradan çıkarılır —
# prod imajının kopyaladığı aşamayla aynı adımlar, aynı sağlama toplamları.
#
# Sürümlerin ve sağlama toplamlarının TEK kaynağı Dockerfile'dır: sürüm değişince bu betik değişmez. Dockerfile'da aday ARG'ı
# yoksa (terfi sürümünden sonra) yalnız etkin ikili çıkarılır.
set -euo pipefail

OUT="${1:?kullanım: fetch-k6-binaries.sh <çıkış-dizini>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DF="$ROOT/Dockerfile"
arg() { sed -n "s/^ARG $1=//p" "$DF" | head -1 | tr -d '[:space:]'; }

K6_VERSION="$(arg K6_VERSION)"
K6_CANDIDATE_VERSION="$(arg K6_CANDIDATE_VERSION)"
[ -n "$K6_VERSION" ] || { echo "::error::Dockerfile'da K6_VERSION bulunamadı" >&2; exit 1; }
mkdir -p "$OUT"

cid=""
cleanup() { [ -n "$cid" ] && docker rm -f "$cid" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker build -q --target k6-bin -t sitemonitor-k6-bin:contract "$ROOT" >/dev/null
cid="$(docker create sitemonitor-k6-bin:contract)"

docker cp "$cid:/k6" "$OUT/k6"
chmod 0755 "$OUT/k6"
timeout 30 "$OUT/k6" version | grep -q "v${K6_VERSION} " || { echo "::error::k6 sürümü tutmuyor: $(timeout 30 "$OUT/k6" version)" >&2; exit 1; }
echo "k6 (etkin): $(timeout 30 "$OUT/k6" version)"

if [ -n "$K6_CANDIDATE_VERSION" ]; then
  docker cp "$cid:/k6-candidate" "$OUT/k6-candidate"
  chmod 0755 "$OUT/k6-candidate"
  timeout 30 "$OUT/k6-candidate" version | grep -q "v${K6_CANDIDATE_VERSION} " \
    || { echo "::error::aday k6 sürümü tutmuyor: $(timeout 30 "$OUT/k6-candidate" version)" >&2; exit 1; }
  echo "k6 (aday): $(timeout 30 "$OUT/k6-candidate" version)"
else
  echo "k6 (aday): Dockerfile'da K6_CANDIDATE_VERSION yok — aday ikili çıkarılmadı"
fi
