# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

CertMonitor — full-stack SSL/TLS certificate monitoring system. Spring Boot 3.3 / Java 21 backend serves a React 18 (Vite 5) SPA; PostgreSQL is the system of record. Single deployable: the React `dist/` is served as static content from the Spring Boot jar in prod. Single VERSION file (`./VERSION`) is authoritative for both backend (`pom.xml` is currently out of sync — version comes from the file) and frontend (`vite.config.js` reads it at build time).

The UI is bilingual TR/EN; default language is Turkish. Most identifiers, log messages, and admin-panel strings are Turkish — keep i18n keys in sync (see `i18n-parity.test.jsx`).

## Common commands

Java + Maven paths are user-specific: `JAVA_HOME=C:\Program Files\Zulu\zulu-21`, Maven at `D:\portablePrograms\apache-maven-3.9.9\bin\mvn.cmd`. Backend health endpoint is `/health` (Actuator remapped, base-path is `/`).

### Backend (from `backend/`)
```
mvn spring-boot:run           # dev — runs on :8080
mvn -B clean verify           # what CI runs (Surefire + Jacoco)
mvn test                      # tests only; report in target/site/jacoco/index.html
mvn -Dtest=SchedulerServiceTest test           # single class
mvn -Dtest=SchedulerServiceTest#methodName test # single method
mvn package -DskipTests       # produces target/*.jar (consumed by start-local.ps1)
```

### Frontend (from `frontend/`)
```
npm install
npm run dev                   # Vite on :5173, proxies /api → :8080
npm run build                 # outputs dist/, embeds VERSION via define
npm run test                  # vitest single-run (what CI runs)
npm run test:watch
npm run test:coverage         # coverage/index.html
npx vitest run src/test/Nav.test.jsx   # single file
```

### Local full-stack (Windows)
- `start-local.ps1` — kills running `java.exe`, reads `.env`, maps env vars to `-D` system properties, launches the freshly built jar, polls `/health` for UP. Backend log goes to `backend\app.log`; errors to `backend\app-err.log`.
- `START.bat` / `start-backend.bat` / `start-frontend.bat` — simpler convenience launchers.

### Smoke + perf
```
pwsh ./scripts/smoke.ps1 -BaseUrl http://localhost:8080
k6 run -e BASE_URL=http://localhost:8080 ./perf/k6-smoke.js
```

### Docker / image
```
docker-compose up -d                          # local stack (app on :8080)
./scripts/build-image.ps1                     # branch-aware tags (master→VERSION+latest, develop→develop-<sha>+develop, release/*→VERSION-rc+staging)
./scripts/build-image.ps1 -Registry ghcr.io/your-org -Push
```

## Architecture — what you need to know across files

### Single-process deployment, multi-replica safe
Frontend ships **inside** the backend jar (`spring.web.resources.static-locations=file:./frontend/dist/,classpath:/static/`). In Kubernetes the same image runs N replicas, so anything stateful must survive pod death and not double-fire:

- **Sessions**: Spring Session JDBC (`spring.session.store-type=jdbc` in `prod` profile) persists to `spring_session` / `spring_session_attributes`. Local dev uses `none` (in-memory).
- **Scheduler distributed lock**: `SchedulerService` self-manages a `scheduler_lock` table (insert/expire by `locked_until`). Only one pod runs the hourly cert sweep. Lock TTL = `SCHEDULER_LOCK_TTL_MINUTES` (10). Admin can force-release via `DELETE /api/admin/system/scheduler-lock`.
- **JPA**: `spring.jpa.open-in-view=false` — entities must be DTO-mapped inside the service transaction; lazy associations after return will throw. Hibernate `ddl-auto=update` is the schema-evolution mechanism (no Flyway/Liquibase).

### Auth — custom interceptor, not Spring Security filter chain
`AuthInterceptor` (registered in `WebConfig`) guards `/api/**`. Login goes through `AuthController`, sessions are HttpSession-based with a remember-me cookie validated by `RememberMeService` (token table: `remember_me_tokens`). Three orthogonal lock states on `AppUser`:
- `lockoutUntil` — temporary progressive lockout (durations: 30s→120s→600s→1800s by default).
- `permanentLock` — set after max escalation; admin-only release.
- `mustChangePassword` — while set, only `/api/me`, `/api/me/change-password`, `/api/login`, `/api/logout` are reachable. Don't add bypass paths.

Spring Security is **not** on the classpath as a filter chain — only `spring-security-crypto` for BCrypt. Don't pull in `spring-boot-starter-security`; it conflicts with the manual interceptor.

### Permissions
Per-resource permission model layered on top of `systemRole` (`USER` / `AUDIT` / `ADMIN`) and org role (`PO`/`TECH`/`MANAGER`/`CLEVEL`). Single source of truth: `PermissionCatalog.ALL` — every resource key + allowed actions (`view`/`edit`/`execute`). The Permission Matrix UI renders from this; bootstrap seed is driven from it. **If you add a new resource_key in code, add it to `PermissionCatalog` in the same change** — otherwise the matrix UI won't show it and grants won't bootstrap.

### The check pipeline
`SchedulerService.@Scheduled(cron=…)` (default `0 0 * * * *`) acquires the distributed lock and fans out to `CertificateCheckerService`, plus parallel sweeps for `PortCheckerService`, `DnsCheckerService`, `UptimeHttpCheckerService`. Concurrency uses a dedicated `certCheckExecutor` `ThreadPoolTaskExecutor` (bean in `WebConfig`, sized by `EXECUTOR_CORE_SIZE` / `EXECUTOR_MAX_SIZE` / `EXECUTOR_QUEUE_CAPACITY`).

- `CertificateCheckerService` retries **only NETWORK-class** failures once (`CHECK_RETRY=true`). SSL/DNS/CERT errors are never retried — they're real findings.
- TLS handshake fingerprint: `TLS_MODE=browser` forces TLS 1.2 + ALPN `[h2,http/1.1]` so WAFs (Akamai/F5/Imperva) don't RST. Switch to `default` only to debug.
- Outbound proxy: `HTTP_PROXY_HOST`/`PORT` + `NO_PROXY` (suffix match) — applies to OCSP/CRL fetches too.
- `ChainValidationService` does chain + OCSP (primary) + CRL (fallback) via BouncyCastle. CRL responses are Caffeine-cached (`CRL_CACHE_TTL_HOURS`).
- **Bulk network outage detection**: if `NETWORK_ERROR_THRESHOLD` (default 0.50) of a run fails with network errors and at least `NETWORK_MIN_ERRORS` (3) failed, individual cert alarms for that run are suppressed and a single `NetworkOutageEvent` + admin email is raised instead. This is intentional — don't "fix" it by always alerting.

### Escalation & notifications
`EscalationService` selects recipients from `escalation_contacts` based on alert severity (`minAlertLevel`) and org role. `EmailNotificationService` and `WebhookService` (Teams/Slack) deliver. Every attempt logs to `notification_logs` (SENT/FAILED + error). Re-alert dedupe is daily — `EscalationServiceTest` pins this behaviour; preserve it.

### Frontend shape
- Single-page app in `App.jsx` — `tab` state switches between Dashboard / Stats / Warnings / All Certs / Forecast / Inventory / Activity / MyActivity / AlertHistory / Admin / Permissions / AuditLog / SystemHealth / Help / Uptime / Port / DNS. No React Router.
- API client is a flat `api.*` object in `api/client.js` — `fetch` with `credentials: 'include'`. A 401 after a successful login (flagged via `sessionStorage.cm.session.active`) forces `window.location.assign('/?session=expired')`. The initial bootstrap 401 returns `null` and lets `App.jsx` render `<Login>`.
- i18n: `useT(key)` hook + `TR`/`EN` dicts in `i18n/index.jsx`. The parity test (`i18n-parity.test.jsx`) enforces both languages have every key, no empty values, matching placeholder counts.
- Theme: `[data-theme="dark"]` on `<html>`, switched via `useTheme()` in `i18n/theme.jsx`. Persisted to localStorage.
- Icons: `lucide-react` only — never emoji.
- Tests use `test-utils.jsx#render()` which wires up i18n + theme providers; mock the API client via `vi.mock('../api/client', ...)`. Never let a real `fetch` escape.

### Configuration
All config is `application.properties` keys overridden by env vars. Three profile files:
- `application.properties` — defaults (dev-leaning: in-memory session, dev CORS origins, `ddl-auto=update`).
- `application-prod.properties` — JDBC session, secure cookies, `same-site=strict`, log path `/var/log/cert-monitor`.
- `application-local-pg.properties` — serves pre-built `frontend/dist` from `:8080` so you can run the whole stack on a single port without Vite.

`.env` is consumed by `docker-compose.yml` and `start-local.ps1`. Real values never committed — `.env.example` and `k8s/secret.example.yaml` are templates.

### Timezone
Backend stores timestamps as UTC (audit, alerts, notifications). Log timestamps are localized via `LOG_TIMEZONE` (default `Europe/Istanbul`). Frontend formats with `date-fns` against Europe/Istanbul (UTC+3, no DST). When doing date arithmetic, use `setUTC*` to avoid the 3-hour drift trap.

## When adding tests

- **Backend**: mirror the package layout under `src/test/java/`. Prefer `@ExtendWith(MockitoExtension.class)` for unit isolation; reach for `@SpringBootTest` only when you need full context (currently exactly one: `SqlSamplesIntegrationTest`). H2 is on the test classpath.
- **Frontend**: place `.test.jsx` next to the component file under `src/test/`. Use `test-utils.jsx#render()`. Add new i18n keys to **both** `TR` and `EN` in the same change or the parity test will fail.

## CI gates (`.github/workflows/`)

- `ci.yml` — Java 21 + Node 24. Backend runs `mvn -B clean verify` (uploads Surefire + Jacoco artifacts). Frontend runs `npm ci` → `lint --if-present` → `test --silent -- --run` → `build` → `npm audit --audit-level=high --omit=dev` (non-blocking). Helm-lint runs against all three env values files.
- `docker-build.yml` — multi-arch image build + Trivy scan (HIGH/CRITICAL, currently report-only).
- `release.yml` — runs on `main`; detects bump from conventional commit prefix (`feat:` → minor, `fix:` → patch, `BREAKING CHANGE` → major), writes `VERSION`, publishes Helm chart. Does **not** re-run tests; trusts `ci.yml`.

## Things that bite

- After any backend change, the user expects: `mvn verify` → if green, `npm run build` (so the frontend is bundled) → restart backend → smoke. Don't skip the restart, login regressions show as 500.
- Frontend always runs on port **5173**. Kill stale processes on 5173/5179 before `npm run dev`.
- Don't put `*` directly in i18n strings for required-field markers — use `<span className="req-star">*</span>` as a separate JSX node. Double-star bug has happened more than once.
- When removing a `useState` or handler, grep for **all** JSX references too — guards in render branches will throw at runtime, not at build time.
- Email credentials in `.env` are committed-locally-only. The Gmail app password in chat history must be rotated before any production deploy (see memory).
