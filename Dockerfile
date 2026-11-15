# Base image pinning (prod gate 2026-09-26, P9-1): every FROM carries an EXACT tag (patch + OS
# release) AND the multi-arch index digest. The tag is for humans; Docker pulls by digest, so a
# re-pushed tag can no longer change what a rebuild of the same commit ships (rollback images
# stay reproducible). Digests were read from registry-1.docker.io on 2026-09-26.
# To update an image: choose the new exact tag, read its index digest with
#   docker buildx imagetools inspect <image>:<tag> --format '{{json .Manifest.Digest}}'
# (or `crane digest <image>:<tag>`), and replace tag AND digest together on the same line.
# Keep the Node major in step with CI (.github/workflows/ci.yml, node-version "24").

# k6 binary (Senaryo İzleme / 10. tür) — sürüm-sabitli GitHub sürüm tarball'ı, sabit SHA-256 (k6-v<sürüm>-checksums.txt
# ile aynı). Docker imajı DEĞİL: tarball go1.26.8 ile derlenmiş, Go 1.27'de kaldırılan TLS kaçışları (GODEBUG tlsrsakex /
# tls3des) burada hâlâ çalışır. k6 0.49 → 2.3 geçişi 2026-10-30'da tamamlandı (Yayın B + C tek sürümde). Etkin ikili
# /usr/bin/k6; 2026-11-16'dan beri imajda bir de ADAY ikili var (/opt/k6-candidate/k6, aşağıdaki K6_CANDIDATE_* — Ayarlar →
# Platform → k6 motoru, uyumluluk taraması — docs/RUNBOOK.md "k6 sürüm geçişi"). Uygulama yalnız 2.0 ve üstünü koşar
# (K6Profile.MIN_SUPPORTED_MAJOR). Sürüm + iki sağlama toplamı BİRLİKTE değişir.
ARG K6_VERSION=2.3.0
ARG K6_SHA256_AMD64=39c3117b6af817592dcd0ce4242105c0a7af10948c2a425306f0be8f7a8a8ab1
ARG K6_SHA256_ARM64=5ca3433e8201da72a284aaa241a1bb5fb47f4abb4e384d39410ddd8062f49b90
# ADAY k6 (2026-11-16, k6 2.3 → 2.4 geçişi, 1. adım): v2.4.0 (go1.26.11, golang.org/x/net v0.62.0 — 2.3.0'daki 6 HIGH Trivy
# bulgusu 2.4.0'da kapalı) imaja KENDİ yolunda girer: /opt/k6-candidate/k6. Etkin ikili (/usr/bin/k6) 2.3.0 kalır — çalışan
# izlemelerde davranış değişmez. Yönetici Ayarlar → Platform → k6 motoru'nda uyumluluk taramasını koşup "Adayı etkin yap"
# der (site.monitor.scripted.k6-bin-candidate varsayılanı bu yol). 2.3.0 imajda kaldıkça Trivy onun bulgularını göstermeye
# devam eder; bulgular 2. adımda (terfi sürümü: 2.4.0 /usr/bin/k6'ya, 2.3.0 çıkar, /opt/k6-candidate/k6 → /usr/bin/k6
# sembolik bağı) kapanır — docs/RUNBOOK.md "k6 sürüm geçişi" → "2.3 → 2.4 (aday ikili)". Üç değer BİRLİKTE değişir.
ARG K6_CANDIDATE_VERSION=2.4.0
ARG K6_CANDIDATE_SHA256_AMD64=5a5ecf23529ccf93135bc7e5b0c1cd5126ecf5f2672e22eed1556a0ab74abb87
ARG K6_CANDIDATE_SHA256_ARM64=7bb415f59d81ad649a86f6da6b3e1bd2e4e64e84909deef13e9bca70608abccb
# Global (before the first FROM, or FROM lines cannot see it) — see the previous-release stage below (MP-47).
ARG PREVIOUS_IMAGE=

# k6 ikilileri (yukarıdaki notlar). Çalışma imajıyla aynı sabitlenmiş tabanda indirilir; sağlama toplamı tutmazsa ya da ikili
# beklenen sürümü söylemezse derleme DÜŞER (yalnız sürüm değiştirilip toplam unutulursa sessizce eski ikili gelmesin — k6
# raporu R11). Tek aşama, İKİ çıktı (2026-11-16): /k6 = etkin (K6_VERSION), /k6-candidate = aday (K6_CANDIDATE_VERSION);
# indirme / doğrulama adımları tek bir kabuk işlevinde (kopya yok). Aşama adı ve /k6 çıktısı scripts/fetch-k6-binaries.sh
# ve CI (backend-k6-contract) için SABİT.
FROM eclipse-temurin:25.0.4_7-jre-alpine-3.24@sha256:2ca9adf44f5c29d28ecd26cf92d75cc0c66b7f32bfd839a4439e363a8b428af8 AS k6-bin
# BuildKit'in otomatik değeri yalnız DEĞERSİZ ARG'a geçer (varsayılan verilirse varsayılan kazanır, arm64 derlemesi
# amd64 ikiliyi alırdı — inceleme 2026-10-30, L4). Eski derleyicide boş kalır → amd64.
ARG TARGETARCH
ARG K6_VERSION
ARG K6_SHA256_AMD64
ARG K6_SHA256_ARM64
ARG K6_CANDIDATE_VERSION
ARG K6_CANDIDATE_SHA256_AMD64
ARG K6_CANDIDATE_SHA256_ARM64
# İndirme süresi sınırlı (curl --connect-timeout 30 / --max-time 300, en çok 3 yeniden deneme) — takılan bir GitHub isteği
# derlemeyi süresiz tutmasın.
RUN set -eu; \
    arch="${TARGETARCH:-amd64}"; \
    case "$arch" in \
      amd64|arm64) ;; \
      *) echo "k6: desteklenmeyen mimari $arch" >&2; exit 1 ;; \
    esac; \
    apk add --no-cache curl >/dev/null; \
    fetch_k6() { \
      ver="$1"; out="$4"; \
      if [ "$arch" = arm64 ]; then sum="$3"; else sum="$2"; fi; \
      if [ -z "$ver" ] || [ -z "$sum" ]; then echo "k6: sürüm ya da sağlama toplamı boş ($out)" >&2; exit 1; fi; \
      name="k6-v${ver}-linux-${arch}"; \
      curl -fsSL --retry 3 --connect-timeout 30 --max-time 300 -o "/tmp/${name}.tgz" \
        "https://github.com/grafana/k6/releases/download/v${ver}/${name}.tar.gz"; \
      echo "${sum}  /tmp/${name}.tgz" | sha256sum -c -; \
      tar -xzf "/tmp/${name}.tgz" -C /tmp; \
      install -m 0755 "/tmp/${name}/k6" "$out"; \
      "$out" version | grep -q "v${ver} " || { echo "k6: sürüm tutmuyor ($out): $("$out" version)" >&2; exit 1; }; \
      rm -rf "/tmp/${name}" "/tmp/${name}.tgz"; \
    }; \
    fetch_k6 "$K6_VERSION" "$K6_SHA256_AMD64" "$K6_SHA256_ARM64" /k6; \
    fetch_k6 "$K6_CANDIDATE_VERSION" "$K6_CANDIDATE_SHA256_AMD64" "$K6_CANDIDATE_SHA256_ARM64" /k6-candidate

# Previous releases' page files (2026-10-23, multi-pod release 2b · MP-47). During a rolling update old and new pods
# serve together: an old pod's index.html asks for its own hashed /assets/* files and the request may land on a new
# pod (and a tab opened before the deploy loads a lazy chunk later) → 404 → blank page. The image therefore also
# carries the OWN assets of the last CARRY_RELEASES releases (default 5 — prod is deployed by hand and skips releases;
# scripts/carry-previous-assets.mjs). Each image writes /app/frontend/asset-history.json (outside the served dist:
# itself + the releases it carried, newest first); the next build reads only the PREVIOUS image and takes the files of
# that list's first CARRY_RELEASES entries — bounded (older releases drop out, ≤ 5000 files / 200 MB, oldest first).
# PREVIOUS_IMAGE = full reference of the previous release image, ideally digest-pinned (release.yml / docker-build.yml /
# build-image.ps1 resolve it with scripts/resolve-previous-image.sh). Empty (default, local builds, the very first
# build) = today's build: nothing is carried. The ARG itself is declared at the top (global ARGs must precede the
# first FROM). The other direction (new index → OLD pod) cannot be carried — public/boot-guard.js reloads once.
FROM scratch AS previous-release-none
COPY VERSION /app/frontend/NO_PREVIOUS_RELEASE
COPY VERSION /app/VERSION
FROM ${PREVIOUS_IMAGE:-previous-release-none} AS previous-release

# ── Stage 1: React build ─────────────────────────────
# Node 24 LTS = the CI version (Node 20 reached end-of-life on 2026-04-30).
FROM node:26.13.1-alpine3.24@sha256:6bd9ab43c0ec0190ca7b2d193906cb8b7edf6e7f4afa43ddeccd6452710bffb5 AS frontend-build
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm ci
COPY VERSION /app/VERSION
COPY frontend/ .
RUN npm run build

# ── Stage 1b: carry the previous releases' own assets (MP-47) ────
# Same node image (node runs the script); only /carry/out goes into the runtime image. /carry/check-previous = the
# previous image's inputs (asset listing + lists) for the independent CI check (scripts/check-carry-output.mjs).
FROM frontend-build AS asset-carry
ARG CARRY_RELEASES=5
COPY scripts/carry-previous-assets.mjs /carry/carry-previous-assets.mjs
COPY --from=previous-release /app/frontend/ /carry/previous/
COPY --from=previous-release /app/VERSION /carry/previous-VERSION
RUN node /carry/carry-previous-assets.mjs --previous /carry/previous --own /app/frontend/dist/assets --out /carry/out \
      --release "$(tr -d '[:space:]' < /app/VERSION)" --previous-release "$(tr -d '[:space:]' < /carry/previous-VERSION)" \
      --releases "${CARRY_RELEASES}" \
 && mkdir -p /carry/check-previous \
 && (ls -1A /carry/previous/dist/assets 2>/dev/null || true) > /carry/check-previous/assets.txt \
 && for f in asset-history.json own-assets.txt; do if [ -f "/carry/previous/$f" ]; then cp "/carry/previous/$f" /carry/check-previous/; fi; done

# CI check target (ci.yml "image-assets"): the carry result + the previous image's inputs, without node_modules.
FROM scratch AS asset-carry-check
COPY --from=asset-carry /carry/out/ /out/
COPY --from=asset-carry /carry/check-previous/ /previous/

# ── Stage 2: Spring Boot build ───────────────────────
FROM maven:3.9.16-eclipse-temurin-25-noble@sha256:dd8e01b3be719853578c07b57ff8d9bbbbfe746f802226f05b19689420815221 AS backend-build
WORKDIR /app
COPY backend/pom.xml .
RUN mvn dependency:go-offline -q
COPY backend/src ./src
# pom <version> = ${revision} (CI-friendly). Sürümün tek kaynağı kök VERSION dosyası: burada jar
# manifest'ine de aynı sürüm yazılır → AppVersion'ın manifest yedeği artık bayat 7.2.0 değil.
COPY VERSION /app/VERSION
RUN mvn package -DskipTests -q -Drevision="$(tr -d '[:space:]' < /app/VERSION)"

# ── Stage 3: Runtime ─────────────────────────────────
FROM eclipse-temurin:25.0.4_7-jre-alpine-3.24@sha256:2ca9adf44f5c29d28ecd26cf92d75cc0c66b7f32bfd839a4439e363a8b428af8
WORKDIR /app

# Debug/troubleshoot tools — pod içinden: curl (HTTP), bash (shell),
# dig/nslookup (DNS), telnet (port), less (log gezintisi),
# openssl (derin SSL/TLS tanılama), iproute2 (ip addr/route), traceroute
# (ağ derin analizi — NetworkDiagnosticsService)
# iputils: ping monitor için non-root ICMP (net.ipv4.ping_group_range sysctl ile DGRAM-ICMP).
# Güvenlik yamaları (2026-11-15 prod kapısı): taban digest'i ve bu komut değişmediği için CI'ın katman önbelleği (type=gha)
# bu satırı HİÇ yeniden koşmuyordu → imaja Alpine'in yamalanmış paketleri girmiyordu (Trivy: 50 HIGH/CRITICAL, hepsi
# "fixed"). APK_REFRESH her yayında değişir (release.yml / docker-build.yml derleme anını verir) → katman yenilenir;
# `apk upgrade` taban imajdan gelen paketleri de (musl, zlib, krb5, expat, brotli …) güncel yamaya çıkarır.
ARG APK_REFRESH=
RUN echo "apk yenileme: ${APK_REFRESH:-yok}" \
    && apk upgrade --no-cache \
    && apk add --no-cache curl bash bind-tools busybox-extras less openssl iproute2 traceroute iputils

# Varsayılan YOK: parametresiz build eskiden sessizce "1.0.0" etiketliyordu; yanlış sürüm > boş sürüm.
ARG VERSION=
ARG BUILD_DATE
ARG GIT_COMMIT
# Senaryo İzleme (10. tür): sabitlenmiş sürümlü k6 binary'si — yukarıdaki named stage'den kopyalanır (statik Go
# binary'si, alpine/musl'da çalışır). Her kontrolde kısa ömürlü sandboxlu alt süreç olarak çalışır.
COPY --from=k6-bin /k6 /usr/bin/k6
# ADAY k6 (2026-11-16, k6 2.3 → 2.4 geçişi, 1. adım): v2.4.0 KENDİ yolunda. site.monitor.scripted.k6-bin-candidate
# varsayılanı bu yol (application.properties); uyumluluk taraması bunu koşar, "Adayı etkin yap" etkin ayarı bu yola yazar.
# TERFİ SÜRÜMÜNDE (2. adım: 2.4.0 /usr/bin/k6'ya geçer, 2.3.0 imajdan çıkar) bu yol /usr/bin/k6'ya SEMBOLİK BAĞ olarak
# KALMALI — kayıtlı etkin değer bu yol olacak (/opt/k6-next/k6 ile aynı desen); bağı kaldırma kararı KAYITLI DEĞERE bakar.
COPY --from=k6-bin /k6-candidate /opt/k6-candidate/k6
# Uyumluluk (2026-10-30): 20.172.x'te "Adayı etkin yap" etkin ikili ayarını /opt/k6-next/k6'ya yazıyordu. Dağıtım sürerken
# eski ve yeni pod aynı ayarı okur; yol yeni imajda da çalışsın diye /usr/bin/k6'ya sembolik bağ. O kayıtlı etkin değer
# BİLEREK korunur (K6SettingsNormalizer yalnız yedek / aday yolunu sıfırlar): 20.172.x'e helm rollback'te aynı ayar gerçek
# 2.3'ü gösterir. Bu yüzden bağ, kayıtlı bir /opt/k6-next/k6 değerini ÖNCE taşıyan bir sürüme dek kalır — kaldırma kararı
# KAYITLI DEĞERE bakmalı. Aday ikili KENDİ yolunda (/opt/k6-candidate/k6) — bu yolda değil.
RUN mkdir -p /opt/k6-next && ln -s /usr/bin/k6 /opt/k6-next/k6
# İkililer beklenen sürümü söylemeli (yanlış imaj / katman sessizce geçmesin); uyumluluk yolu etkin ikiliye çıkmalı.
ARG K6_VERSION
ARG K6_CANDIDATE_VERSION
RUN /usr/bin/k6 version | grep -q "v${K6_VERSION} " \
    && /opt/k6-next/k6 version | grep -q "v${K6_VERSION} " \
    && /opt/k6-candidate/k6 version | grep -q "v${K6_CANDIDATE_VERSION} " \
    || { echo "k6 sürümü tutmuyor: $(/usr/bin/k6 version) / $(/opt/k6-next/k6 version) / aday $(/opt/k6-candidate/k6 version)" >&2; exit 1; }
# İşlem sertleştirmesi (2026-10-31, k6 inceleme A2): k6 ikilisi root sahipli ve YALNIZ ÇALIŞTIRILIR (0111 — okunamaz).
# Linux okunamayan bir ikiliyi execve eden süreci dump-edilemez yapar: her k6 sürecinin /proc/<pid>/environ dosyası root
# sahipli olur ve AYNI kullanıcıdaki kardeş k6 süreçleri (başka takımların çözülmüş gizli değerleri) birbirini okuyamaz.
# JVM'in kendisi açılışta prctl(PR_SET_DUMPABLE, 0) ile aynı korumayı alır (ScriptedProcessHardening). Sembolik bağ
# (/opt/k6-next/k6) izinleri hedeften alır. Aday ikili (uyumluluk taraması aynı betikleri aynı gizli değerlerle koşar) AYNI
# korumayı alır. scripts/image-boot-check.sh adım 9 doğrular.
RUN chown root:root /usr/bin/k6 /opt/k6-candidate/k6 && chmod 0111 /usr/bin/k6 /opt/k6-candidate/k6

LABEL org.opencontainers.image.title="Site Monitor" \
      org.opencontainers.image.description="SSL/TLS Certificate Monitoring System" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.revision="${GIT_COMMIT}" \
      org.opencontainers.image.source="https://github.com/inanmise/site-monitor" \
      org.opencontainers.image.vendor="Site Monitor" \
      org.opencontainers.image.licenses="NOASSERTION"

RUN addgroup -g 1000 appgroup && adduser -u 1000 -G appgroup -s /bin/sh -D appuser

RUN mkdir -p /app/data && chown -R appuser:appgroup /app

COPY --from=backend-build --chown=appuser:appgroup /app/target/*.jar app.jar
# Carried previous-release assets FIRST, then this build's dist on top (a hashed name never differs in content; the
# carry already skips names this build has). Lists outside dist (never served): asset-history.json (read by the NEXT
# build), own-assets.txt, carried-assets.txt and carry-report.json (image boot check, runbook).
COPY --from=asset-carry --chown=appuser:appgroup /carry/out/assets/ ./frontend/dist/assets/
COPY --from=frontend-build --chown=appuser:appgroup /app/frontend/dist ./frontend/dist
COPY --from=asset-carry --chown=appuser:appgroup /carry/out/own-assets.txt /carry/out/carried-assets.txt /carry/out/asset-history.json /carry/out/carry-report.json ./frontend/
# Ürün sürümü (AppVersion.resolve → /app/VERSION). Olmadığında jar manifest'ine (pom <version>, bayat)
# düşülüyor ve pod logu yanlış sürüm raporluyordu — hangi release'in koştuğu loglardan doğrulanamıyordu.
COPY --chown=appuser:appgroup VERSION /app/VERSION
# Yayın indeksi (hangi sürüm ne zaman çıktı) — CI her release'te docs/releases/index.json'a ekler;
# ReleaseIndexService /app/releases.json'dan okur. Uygulama çalışma anında GitHub'a ÇIKMAZ.
COPY --chown=appuser:appgroup docs/releases/index.json /app/releases.json

USER appuser

# Konteyner locale'ini UTF-8 yap: JVM native/console encoding'i C.UTF-8'e sabitlenir → logback-DIŞI
# stdout/stderr yolları da UTF-8 olur (Türkçe karakterler kubectl/aggregator'da mojibake olmaz).
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8
# Build meta'yı ÇALIŞAN uygulamaya taşı (eskiden yalnız OCI LABEL'daydı; pod kendi commit'ini bilmiyordu).
# application.properties: site.monitor.build.commit/time/image-version ← BuildInfo → /api/system/version,
# dağıtım kaydı (deployment_history), StartupLogger, sitemonitor_build_info metriği.
ENV APP_GIT_COMMIT=${GIT_COMMIT} \
    APP_BUILD_TIME=${BUILD_DATE} \
    APP_IMAGE_VERSION=${VERSION}

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8080/health/liveness || exit 1

# JAVA_OPTS is injected at runtime (ConfigMap / env var).
# -XX:+UseContainerSupport is always on so the JVM reads cgroup limits.
# Shell-form ENTRYPOINT is required to expand $JAVA_OPTS.
# --enable-native-access=ALL-UNNAMED (2026-10-31): işlem sertleştirmesi prctl'i Java FFM ile çağırır (kısıtlı yöntem);
# bayraksız JVM açılış günlüğüne "WARNING: A restricted method …" basar (image-boot-check.sh adım 9 bunu arar).
ENTRYPOINT ["sh", "-c", "exec java -XX:+UseContainerSupport --enable-native-access=ALL-UNNAMED -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dfile.encoding=UTF-8 $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar app.jar"]
