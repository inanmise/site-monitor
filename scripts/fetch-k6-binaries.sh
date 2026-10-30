#!/usr/bin/env bash
# k6 sözleşme testleri için GERÇEK ikiliyi getirir (2026-10-29, k6 0.49 → 2.3 geçişi, A13; 2026-10-30'dan beri imajda tek
# ikili). Docker gerekir.
#
# Kullanım:  bash scripts/fetch-k6-binaries.sh <çıkış-dizini>
#   → <çıkış-dizini>/k6   Dockerfile'ın `k6-bin` aşaması DERLENİR (tarball indirme + SHA-256 + sürüm denetimi orada) ve
#                         ikili oradan çıkarılır — prod imajının kopyaladığı aşamayla aynı adımlar, aynı sağlama toplamı.
#
# Sürümün ve sağlama toplamlarının TEK kaynağı Dockerfile'dır: sürüm değişince bu betik değişmez.
set -euo pipefail

OUT="${1:?kullanım: fetch-k6-binaries.sh <çıkış-dizini>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DF="$ROOT/Dockerfile"
arg() { sed -n "s/^ARG $1=//p" "$DF" | head -1 | tr -d '[:space:]'; }

K6_VERSION="$(arg K6_VERSION)"
[ -n "$K6_VERSION" ] || { echo "::error::Dockerfile'da K6_VERSION bulunamadı" >&2; exit 1; }
mkdir -p "$OUT"

cid=""
cleanup() { [ -n "$cid" ] && docker rm -f "$cid" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker build -q --target k6-bin -t sitemonitor-k6-bin:contract "$ROOT" >/dev/null
cid="$(docker create sitemonitor-k6-bin:contract)"
docker cp "$cid:/k6" "$OUT/k6"
chmod 0755 "$OUT/k6"

"$OUT/k6" version | grep -q "v${K6_VERSION} " || { echo "::error::k6 sürümü tutmuyor: $("$OUT/k6" version)" >&2; exit 1; }
echo "k6: $("$OUT/k6" version)"
