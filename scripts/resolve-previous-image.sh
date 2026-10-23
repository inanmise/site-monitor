#!/usr/bin/env bash
# Önceki sürüm imajını çözümler (2026-10-23, çok pod sürüm 2b · MP-47) — release.yml, docker-build.yml ve ci.yml
# "image-assets" işi çağırır; çıktı Dockerfile'ın PREVIOUS_IMAGE derleme argümanıdır (önceki sürümün sayfa dosyaları
# yeni imaja taşınır, bkz. Dockerfile "previous-release" ve scripts/carry-previous-assets.mjs).
#
# Kullanım:  bash scripts/resolve-previous-image.sh <registry/ad:etiket>
# Çıktı (stdout): "<registry/ad:etiket>@sha256:<64 hex>" — özete sabitli, yeniden itilen bir etiket derlemeyi
# değiştiremez; imaj yoksa / okunamazsa BOŞ (derleme bugünkü gibi, hiçbir şey taşımadan sürer — ilk derleme de böyle).
# Her zaman 0 ile çıkar: önceki imajın olmaması bir sürümü durdurmaz. Açıklama stderr'e (GitHub ::notice:: / ::warning::).
set -uo pipefail

ref="${1:-}"
if [ -z "$ref" ]; then
  echo "::notice::önceki sürüm imajı verilmedi — sayfa dosyaları taşınmadan derlenecek" >&2
  exit 0
fi
case "$ref" in
  *@*) echo "::warning::önceki imaj referansı zaten özet taşıyor, olduğu gibi kullanılmayacak: $ref" >&2; exit 0 ;;
esac

digest="$(timeout 90 docker buildx imagetools inspect "$ref" --format '{{json .Manifest.Digest}}' 2>/dev/null | tr -d '"[:space:]')"
if [[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]]; then
  echo "önceki sürüm imajı: $ref@$digest (sayfa dosyaları yeni imaja taşınacak)" >&2
  echo "$ref@$digest"
else
  echo "::warning::önceki sürüm imajı okunamadı ($ref) — bu imaj önceki sürümün sayfa dosyalarını TAŞIMAYACAK; yuvarlanan dağıtımda eski sekmeler bir kez yenilenir (boot-guard)" >&2
fi
exit 0
