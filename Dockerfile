# Base image pinning (prod gate 2026-09-26, P9-1): every FROM carries an EXACT tag (patch + OS
# release) AND the multi-arch index digest. The tag is for humans; Docker pulls by digest, so a
# re-pushed tag can no longer change what a rebuild of the same commit ships (rollback images
# stay reproducible). Digests were read from registry-1.docker.io on 2026-09-26.
# To update an image: choose the new exact tag, read its index digest with
#   docker buildx imagetools inspect <image>:<tag> --format '{{json .Manifest.Digest}}'
# (or `crane digest <image>:<tag>`), and replace tag AND digest together on the same line.
# Keep the Node major in step with CI (.github/workflows/ci.yml, node-version "24").

# k6 binary (Senaryo İzleme / 10. tür) — sürüm-sabitli. `--from`'da değişken genişletme buildx'te desteklenmez;
# bu yüzden global-scope ARG + named stage kullanılır (yalnız binary'yi kopyalamak için ara imaj).
# K6_VERSION and K6_DIGEST belong together: overriding only K6_VERSION still pulls the pinned digest.
ARG K6_VERSION=0.49.0
ARG K6_DIGEST=sha256:8cd78f9d0de5f50bc8821cceecf356d5d9e839e6611c226a3fcf13c591080fbd
FROM grafana/k6:${K6_VERSION}@${K6_DIGEST} AS k6-bin

# ── Stage 1: React build ─────────────────────────────
# Node 24 LTS = the CI version (Node 20 reached end-of-life on 2026-04-30).
FROM node:24.21.0-alpine3.24@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1 AS frontend-build
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm ci
COPY VERSION /app/VERSION
COPY frontend/ .
RUN npm run build

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
FROM eclipse-temurin:26.0.2_10-jre-alpine-3.24@sha256:2db9a5fb7c52fb44f9ffcf2d5caa16a39d4e8eb2977045ef3bb6589ae1c63ef5
WORKDIR /app

# Debug/troubleshoot tools — pod içinden: curl (HTTP), bash (shell),
# dig/nslookup (DNS), telnet (port), less (log gezintisi),
# openssl (derin SSL/TLS tanılama), iproute2 (ip addr/route), traceroute
# (ağ derin analizi — NetworkDiagnosticsService)
# iputils: ping monitor için non-root ICMP (net.ipv4.ping_group_range sysctl ile DGRAM-ICMP).
RUN apk add --no-cache curl bash bind-tools busybox-extras less openssl iproute2 traceroute iputils

# Varsayılan YOK: parametresiz build eskiden sessizce "1.0.0" etiketliyordu; yanlış sürüm > boş sürüm.
ARG VERSION=
ARG BUILD_DATE
ARG GIT_COMMIT
# Senaryo İzleme (10. tür): sabitlenmiş sürümlü k6 binary'si — yukarıdaki named stage'den kopyalanır (alpine/musl
# uyumlu statik binary). Her kontrolde kısa ömürlü sandboxlu alt süreç olarak çalışır.
COPY --from=k6-bin /usr/bin/k6 /usr/bin/k6

LABEL org.opencontainers.image.title="Site Monitor" \
      org.opencontainers.image.description="SSL/TLS Certificate Monitoring System" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.revision="${GIT_COMMIT}" \
      org.opencontainers.image.source="https://github.com/inanmise/site-monitor" \
      org.opencontainers.image.vendor="Site Monitor" \
      org.opencontainers.image.licenses="MIT"

RUN addgroup -g 1000 appgroup && adduser -u 1000 -G appgroup -s /bin/sh -D appuser

RUN mkdir -p /app/data && chown -R appuser:appgroup /app

COPY --from=backend-build --chown=appuser:appgroup /app/target/*.jar app.jar
COPY --from=frontend-build --chown=appuser:appgroup /app/frontend/dist ./frontend/dist
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
ENTRYPOINT ["sh", "-c", "exec java -XX:+UseContainerSupport -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dfile.encoding=UTF-8 $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar app.jar"]
