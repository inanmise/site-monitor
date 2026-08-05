#!/usr/bin/env bash
# Marka bekçisi — rename (CertMonitor -> Site Monitor) sonrasi kalinti tarar.
# Cikti YALNIZ bilincli birakilan satirlari icermeli: "# geriye-uyum" yorumlu alias'lar,
# CHANGELOG/docs tarihsel kayitlari (zaten haric) ve domain-disi bilerek birakilanlar.
# Kullanim: scripts/check-brand.sh   (repo kokunden)
set -uo pipefail
cd "$(dirname "$0")/.."

grep -rIn -i -e certmonitor -e cert-monitor -e cert_monitor . \
  --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=dist \
  --exclude-dir=target --exclude-dir=logs --exclude-dir=data --exclude-dir=docs \
  --exclude='*.log' --exclude='*.gz' --exclude='*.jar' --exclude='*.pdf' \
  --exclude='package-lock.json' --exclude='CHANGELOG.md' \
  | grep -v 'geriye-uyum' || true
