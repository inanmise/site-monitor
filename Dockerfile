# k6 binary (Senaryo İzleme / 10. tür) — sürüm-sabitli. `--from`'da değişken genişletme buildx'te desteklenmez;
# bu yüzden global-scope ARG + named stage kullanılır (yalnız binary'yi kopyalamak için ara imaj).
ARG K6_VERSION=0.49.0
FROM grafana/k6:${K6_VERSION} AS k6-bin

# ── Stage 1: React build ─────────────────────────────
FROM node:20-alpine AS frontend-build
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm ci
COPY VERSION /app/VERSION
COPY frontend/ .
RUN npm run build

# ── Stage 2: Spring Boot build ───────────────────────
FROM maven:3.9-eclipse-temurin-25 AS backend-build
WORKDIR /app
COPY backend/pom.xml .
RUN mvn dependency:go-offline -q
COPY backend/src ./src
RUN mvn package -DskipTests -q

# ── Stage 3: Runtime ─────────────────────────────────
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Debug/troubleshoot tools — pod içinden: curl (HTTP), bash (shell),
# dig/nslookup (DNS), telnet (port), less (log gezintisi),
# openssl (derin SSL/TLS tanılama), iproute2 (ip addr/route), traceroute
# (ağ derin analizi — NetworkDiagnosticsService)
# iputils: ping monitor için non-root ICMP (net.ipv4.ping_group_range sysctl ile DGRAM-ICMP).
RUN apk add --no-cache curl bash bind-tools busybox-extras less openssl iproute2 traceroute iputils

ARG VERSION=1.0.0
ARG BUILD_DATE
ARG GIT_COMMIT
# Senaryo İzleme (10. tür): sabitlenmiş sürümlü k6 binary'si — yukarıdaki named stage'den kopyalanır (alpine/musl
# uyumlu statik binary). Her kontrolde kısa ömürlü sandboxlu alt süreç olarak çalışır.
COPY --from=k6-bin /usr/bin/k6 /usr/bin/k6

LABEL org.opencontainers.image.title="CertMonitor" \
      org.opencontainers.image.description="SSL/TLS Certificate Monitoring System" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.revision="${GIT_COMMIT}" \
      org.opencontainers.image.source="https://github.com/inanmise/certmonitor" \
      org.opencontainers.image.vendor="CertMonitor" \
      org.opencontainers.image.licenses="MIT"

RUN addgroup -g 1000 appgroup && adduser -u 1000 -G appgroup -s /bin/sh -D appuser

RUN mkdir -p /app/data && chown -R appuser:appgroup /app

COPY --from=backend-build --chown=appuser:appgroup /app/target/*.jar app.jar
COPY --from=frontend-build --chown=appuser:appgroup /app/frontend/dist ./frontend/dist

USER appuser

# Konteyner locale'ini UTF-8 yap: JVM native/console encoding'i C.UTF-8'e sabitlenir → logback-DIŞI
# stdout/stderr yolları da UTF-8 olur (Türkçe karakterler kubectl/aggregator'da mojibake olmaz).
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8080/health/liveness || exit 1

# JAVA_OPTS is injected at runtime (ConfigMap / env var).
# -XX:+UseContainerSupport is always on so the JVM reads cgroup limits.
# Shell-form ENTRYPOINT is required to expand $JAVA_OPTS.
ENTRYPOINT ["sh", "-c", "exec java -XX:+UseContainerSupport -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dfile.encoding=UTF-8 $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar app.jar"]
