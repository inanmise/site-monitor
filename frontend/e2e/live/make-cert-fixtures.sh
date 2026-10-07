#!/usr/bin/env bash
# CANLI e2e sertifika fikstürleri (manual-cert.live.spec.js) — SAHTE bir test CA'sı ve *.example.test adları.
# Her çağrı YENİ anahtarlar üretir (parmak izleri koşudan koşuya değişir). Çıktı dizini depo DIŞINDA olmalıdır:
# özel anahtarlar ve keystore'lar ASLA commit edilmez.
#
#   bash e2e/live/make-cert-fixtures.sh "$TMPDIR/sm-mcert"   →   E2E_CERT_DIR="$TMPDIR/sm-mcert"
#
# Üretilenler (şifre: Test1234):
#   chain-v1.pem   yaprak (20 gün) + ara CA + kök CA (yaprak önce)
#   leaf-v1.der    yaprak v1, DER
#   chain-v1.p7b   aynı zincir, PKCS#7 (PEM)
#   leaf-v2.pfx    yaprak v2 (AYNI anahtar, 397 gün) + zincir, PKCS#12
#   leaf-v2.jks    leaf-v2.pfx'in JKS kopyası (alias odeme-api)
#   truststore.jks yalnız iki CA sertifikası (root, issuing)
#   with-key.pem   yaprak v1 + özel anahtar (sunucu anahtarı yok sayar)
#   leaf.csr       yaprağın CSR'ı (sertifika değil)
#   bundle.zip     leaf-v1.pem + int.pem + root.pem
# Gerekenler: openssl (1.1.1+), JDK (keytool + jar; $JAVA_HOME/bin ya da PATH).
set -euo pipefail

OUT="${1:?kullanım: $0 <çıktı-dizini>}"
PASS="Test1234"
# Git Bash (MSYS) "/CN=…" konu adını Windows yoluna çevirmesin
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" -o -x "$JAVA_HOME/bin/keytool.exe" ]; then
  KEYTOOL="$JAVA_HOME/bin/keytool"; JAR="$JAVA_HOME/bin/jar"
else
  KEYTOOL="keytool"; JAR="jar"
fi
command -v openssl >/dev/null || { echo "openssl bulunamadı" >&2; exit 1; }

mkdir -p "$OUT"
cd "$OUT"
rm -f root.* int.* leaf* chain-v1.* truststore.jks with-key.pem bundle.zip cas.pem ca.ext leaf.ext

cat > ca.ext <<'X'
basicConstraints=critical,CA:true
keyUsage=critical,keyCertSign,cRLSign
X
cat > leaf.ext <<'X'
subjectAltName=DNS:odeme-api.example.test,DNS:odeme-api-internal.example.test
extendedKeyUsage=serverAuth,clientAuth
X

# Kök CA (10 yıl) → ara CA (5 yıl)
openssl req -x509 -new -newkey rsa:2048 -nodes -sha256 -days 3650 -keyout root.key -out root.pem \
  -subj "/CN=Test Root CA/O=Example Test" \
  -addext "basicConstraints=critical,CA:true" -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null
openssl req -new -newkey rsa:2048 -nodes -keyout int.key -out int.csr -subj "/CN=Test Issuing CA/O=Example Test" 2>/dev/null
openssl x509 -req -in int.csr -CA root.pem -CAkey root.key -CAcreateserial -days 1825 -sha256 -extfile ca.ext -out int.pem 2>/dev/null

# Yaprak: tek anahtar, iki sürüm (v1 20 gün → EXPIRES_SOON; v2 397 gün → "anahtar değişmedi")
openssl req -new -newkey rsa:2048 -nodes -keyout leaf.key -out leaf.csr \
  -subj "/CN=odeme-api.example.test/O=Example Test/L=Istanbul/C=TR" \
  -addext "subjectAltName=DNS:odeme-api.example.test,DNS:odeme-api-internal.example.test" 2>/dev/null
openssl x509 -req -in leaf.csr -CA int.pem -CAkey int.key -CAcreateserial -days 20 -sha256 -extfile leaf.ext -out leaf-v1.pem 2>/dev/null
openssl x509 -req -in leaf.csr -CA int.pem -CAkey int.key -CAcreateserial -days 397 -sha256 -extfile leaf.ext -out leaf-v2.pem 2>/dev/null

cat leaf-v1.pem int.pem root.pem > chain-v1.pem
cat int.pem root.pem > cas.pem
openssl x509 -in leaf-v1.pem -outform DER -out leaf-v1.der
openssl crl2pkcs7 -nocrl -certfile chain-v1.pem -out chain-v1.p7b
openssl pkcs12 -export -inkey leaf.key -in leaf-v2.pem -certfile cas.pem -name odeme-api -passout "pass:$PASS" -out leaf-v2.pfx
"$KEYTOOL" -importkeystore -noprompt -srckeystore leaf-v2.pfx -srcstoretype PKCS12 -srcstorepass "$PASS" \
  -destkeystore leaf-v2.jks -deststoretype JKS -deststorepass "$PASS" -destkeypass "$PASS" >/dev/null 2>&1
"$KEYTOOL" -importcert -noprompt -alias root -file root.pem -keystore truststore.jks -storetype JKS -storepass "$PASS" >/dev/null 2>&1
"$KEYTOOL" -importcert -noprompt -alias issuing -file int.pem -keystore truststore.jks -storetype JKS -storepass "$PASS" >/dev/null 2>&1
cat leaf-v1.pem leaf.key > with-key.pem
"$JAR" --create --no-manifest --file bundle.zip leaf-v1.pem int.pem root.pem

rm -f cas.pem ./*.srl int.csr
for f in chain-v1.pem leaf-v1.der chain-v1.p7b leaf-v2.pfx leaf-v2.jks truststore.jks with-key.pem leaf.csr bundle.zip; do
  [ -s "$f" ] || { echo "üretilemedi: $f" >&2; exit 1; }
done
echo "sertifika fikstürleri hazır: $OUT"
