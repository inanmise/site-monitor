# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Site Monitor — full-stack SSL/TLS certificate monitoring system. Spring Boot 4.1 / Java 25 backend serves a React 18 (Vite 5) SPA; PostgreSQL is the system of record. Single deployable: the React `dist/` is served as static content from the Spring Boot jar in prod. Single VERSION file (`./VERSION`) is authoritative for both backend (`pom.xml` is currently out of sync — version comes from the file) and frontend (`vite.config.js` reads it at build time).

The UI is bilingual TR/EN; default language is Turkish. Most identifiers, log messages, and admin-panel strings are Turkish — keep i18n keys in sync (see `i18n-parity.test.jsx`).

## Common commands

Java + Maven paths are user-specific: `JAVA_HOME=C:\Program Files\Zulu\zulu-25`, Maven at `D:\portablePrograms\apache-maven-3.9.9\bin\mvn.cmd`. Backend health endpoint is `/health` (Actuator remapped, base-path is `/`).

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

### Single active session per user
A user holds at most one live session. `UserService` keeps `AppUser.activeSessionId` (newest login wins) plus `lastSeenAt`; the SPA pings `/api/session/ping` (~15s) to keep it fresh. On every `/api/**` request `AuthInterceptor.isSessionSuperseded(user, currentSessionId)` compares the registered id — if it differs, the interceptor invalidates the session, clears the remember-me cookie, and returns **401** (the client then redirects to `/?session=expired`). Login returns **409** when another live session exists; the frontend re-posts with `forceLogin=true` after a confirmation modal. Admin force-logout (`POST /api/admin/system/terminate-session`) sets `activeSessionId` to a `TERMINATED:<uuid>` sentinel that can never match a real session. Two traps: sessions persist in the Spring Session JDBC store but this registry is a single column, so stale ids are cleared at startup; and `activeSessionId = null` means *grandfathered*, not *kicked* — `isSessionSuperseded` returns false for null.

### Permissions
Per-resource permission model layered on top of `systemRole` (`USER` / `AUDIT` / `ADMIN`) and org role (`PO`/`TECH`/`MANAGER`/`CLEVEL`). Single source of truth: `PermissionCatalog.ALL` — every resource key + allowed actions (`view`/`edit`/`execute`). The Permission Matrix UI renders from this; bootstrap seed is driven from it. **If you add a new resource_key in code, add it to `PermissionCatalog` in the same change** — otherwise the matrix UI won't show it and grants won't bootstrap.

### The check pipeline
`SchedulerService.@Scheduled(cron=…)` (default `0 0 * * * *`) acquires the distributed lock and fans out to `CertificateCheckerService`, plus parallel sweeps for `PortCheckerService`, `DnsCheckerService`, `UptimeHttpCheckerService`. Concurrency uses a dedicated `certCheckExecutor` `ThreadPoolTaskExecutor` (bean in `WebConfig`, sized by `EXECUTOR_CORE_SIZE` / `EXECUTOR_MAX_SIZE` / `EXECUTOR_QUEUE_CAPACITY`).

- `CertificateCheckerService` retries **only NETWORK-class** failures once (`CHECK_RETRY=true`). SSL/DNS/CERT errors are never retried — they're real findings.
- TLS handshake fingerprint: `TLS_MODE=browser` forces TLS 1.2 + ALPN `[h2,http/1.1]` so WAFs (Akamai/F5/Imperva) don't RST. Switch to `default` only to debug.
- Outbound proxy: `HTTP_PROXY_HOST`/`PORT` + `NO_PROXY` (suffix match) — applies to OCSP/CRL fetches too.
- `ChainValidationService` does chain + OCSP (primary) + CRL (fallback) via BouncyCastle. CRL responses are Caffeine-cached (`CRL_CACHE_TTL_HOURS`).
- **Bulk network outage detection**: if `NETWORK_ERROR_THRESHOLD` (default 0.50) of a run fails with network errors and at least `NETWORK_MIN_ERRORS` (3) failed, individual cert alarms for that run are suppressed and a single `NetworkOutageEvent` + admin email is raised instead. This is intentional — don't "fix" it by always alerting.
- **Per-domain proxy override (`use_proxy`)**: `CertificateInventory.useProxy` (column `use_proxy`, default false) flips a single domain to go through `HTTP_PROXY_HOST/PORT`. `SchedulerService.runCheckForDomains` builds a `domain→forceProxy` map and calls `checkAsync(domain, port, forceProxy)`. Decision: `forceProxy && proxyEnabled() && !shouldBypassProxy(domain)`. Direct outbound stays the default — added so production can route a couple of WAF-quirky domains through the corporate proxy without dragging the rest along.

### Inventory metadata that drives behavior
`CertificateInventory` carries a wide operational schema beyond the cert URL. The fields the rest of the system actually branches on:
- **`tier` (1-4)** — 1: Customer-Facing Prod, 2: Internal Prod, 3: UAT/Pre-Prod, 4: Dev/Sandbox. Drives sort order, criticality badges, and escalation contact selection.
- **`useProxy`** — see above.
- **`teamId` / `ugTeamId`** — primary (SY) and secondary (UG = Uygulama Geliştirici) team ownership for escalation routing.
- **`active` / soft-delete** — restore flow uses this; never hard-delete inventory rows from code.
- Boolean operational toggles: `externalVendor`, `openshift`, `sslPinning`, `internalCert`, `jksKeystore`, `wafEnabled`, `evCertificate`, `actionRequired`, `transferredToSy`, etc. These are admin-facing flags that show up in InventoryManager and on reports — they don't gate the check pipeline.

When you add a new boolean toggle: append to the `OPERATIONAL_FIELDS` array in `InventoryManager.jsx`, add the EMPTY default, wire it into the save payload, add the diff-builder entry in `AdminController.buildInventoryDiff`, and add the setter call in `updateInventory` — missing the setter is the most common bug (the field "saves" but disappears on reload).

### Request/response trace logging (off by default)
`RequestLoggingFilter` (`@Order(LOWEST_PRECEDENCE - 10)`) can log every HTTP request and response with method, URI, headers, body, status, and duration. It's behind `log.isTraceEnabled()` so the default `com.sitemonitor=DEBUG` level keeps it silent.
- Enable on demand: `logging.level.com.sitemonitor.config.RequestLoggingFilter=TRACE` (env or properties).
- Sensitive values are masked with `*******` via three regex patterns (JSON body / form body / URL query) covering ~60 field-name variants — EN + TR (`password|parola|sifre`, `*_password`, `token|*_token`, `secret|api_key|client_secret`, `private_key`, `session_id|jsessionid`, `pin|otp|mfa_*|verification_code`). Sensitive headers (`authorization`, `cookie`, `x-api-key`, etc.) are masked too.
- Skipped paths: `/health`, `/favicon.ico`, anything under `/assets/` or `/static/`, and common static extensions (`.js .css .map .png .svg .woff2 .ico`).
- Body log is truncated at 2000 chars.

### Escalation & notifications
`EscalationService` selects recipients from `escalation_contacts` based on alert severity (`minAlertLevel`) and org role. `EmailNotificationService` and `WebhookService` (Teams/Slack) deliver. Every attempt logs to `notification_logs` (SENT/FAILED + error). Re-alert dedupe is daily — `EscalationServiceTest` pins this behaviour; preserve it.

**Recipient routing (team vs escalation)** — two helpers, `isStandaloneMon(alertType)` + `includeManagerContacts(alertType, level)`, govern three paths (initial alert `processConfirmedOutage`, resolution `sendResolutionNotification`, manual resend `reNotify`) and MUST stay consistent across them:
- Standalone monitoring alerts (keyword/ping/http/`TYPE_DOMAIN_EXPIRY`/all `DOMAINMON_*`) resolve the team from `AlertEvent.teamId` (stamped at alarm creation) — **not** the certificate inventory. Standalone domain monitors aren't in `certificate_inventory`, so resolving their team from inventory silently drops to global contacts (the "resend went to the müdür, not the team" bug — fixed by routing all three paths through `event.getTeamId()`).
- Manager/escalation contacts are added **only for CRITICAL domain expiry** (`includeManagerContacts` → `getContactsForLevel(level, teamId)`); WARNING domain + all keyword/ping/http alerts stay team-only. This is a deliberate product policy (müdür paged only when a domain is critically close to expiry) — keep the CRITICAL gate.
- Cert alerts use inventory team + `getContactsForLevel` (adds escalation contacts by severity).
- Email **content** (detail table) for `DOMAINMON_*` is rebuilt fresh from the latest `DomainCheck` via `reconstructDomainContext(domain)` — `latestCheckRepo` is cert-only and returns nothing for a domain monitor, which is why resend/resolution mails looked empty before.
- Domain expiry alarm **severity** must mirror the card status tiers (`DomainCheckerService`: `days≤crit→CRITICAL`, `crit<days≤warn→WARNING`) — set in `SchedulerService.addDomainSweepItems`. Don't reintroduce a flat `HIGH`.

**Critical-domain second daily check** — `SchedulerService.runCriticalDomainChecks()` (`@Scheduled` cron `DOMAIN_CRITICAL_CHECK_CRON`, default `0 0 16 * * *` Europe/Istanbul; separate `scheduler_lock` key `domain-critical-sweep`) re-checks ONLY active domains whose last check `days_remaining ≤ DOMAIN_CRITICAL_CHECK_THRESHOLD_DAYS` (default 7). It calls the same `evaluateDomainAlarmsNow`, so a same-day renewal auto-closes the open `DOMAINMON_EXPIRY` alarm without waiting for the next morning sweep, while the daily re-alert dedupe still suppresses a second notification for a still-critical domain. Toggle with `DOMAIN_CRITICAL_CHECK_ENABLED`. Two same-day checks intentionally write two `domain_checks` rows.

### Frontend shape
- Single-page app in `App.jsx` — `tab` state (synced to `?tab=<key>` and validated against a whitelist) switches between Dashboard / All Certs / Stats / Warnings / Renewal (+ Renewal Guide) / Forecast / Inventory (`domains`) / Activity / MyActivity / AlertHistory / WeakAlgo / Uptime / Port / DNS / Weekly Reports / Incident History / Admin / Permissions / Settings (`AdminSettings`) / SystemHealth / AuditLog (`system`) / SQL Playground / Help. No React Router. Several tabs are visibility-gated: Permissions & SQL Playground (global admin), Settings (bootstrap `admin` user), AuditLog (global admin or AUDIT role).
- API client is a flat `api.*` object in `api/client.js` — `fetch` with `credentials: 'include'`. A 401 after a successful login (flagged via `sessionStorage.cm.session.active`) forces `window.location.assign('/?session=expired')`. The initial bootstrap 401 returns `null` and lets `App.jsx` render `<Login>`.
- i18n: `useT(key)` hook + `TR`/`EN` dicts in `i18n/index.jsx`. The parity test (`i18n-parity.test.jsx`) enforces both languages have every key, no empty values, matching placeholder counts.
- Theme: `[data-theme="dark"]` on `<html>`, switched via `useTheme()` in `i18n/theme.jsx`. Persisted to localStorage.
- Icons: `lucide-react` only — never emoji.
- Tests use `test-utils.jsx#render()` which wires up i18n + theme providers; mock the API client via `vi.mock('../api/client', ...)`. Never let a real `fetch` escape.

### Configuration
All config is `application.properties` keys overridden by env vars. Three profile files:
- `application.properties` — defaults (dev-leaning: in-memory session, dev CORS origins, `ddl-auto=update`).
- `application-prod.properties` — JDBC session, secure cookies, `same-site=strict`, log path `/var/log/site-monitor`.
- `application-local-pg.properties` — serves pre-built `frontend/dist` from `:8080` so you can run the whole stack on a single port without Vite.

`.env` is consumed by `docker-compose.yml` and `start-local.ps1`. Real values never committed — `.env.example` and `k8s/secret.example.yaml` are templates.

### Runtime schema patches (no Flyway/Liquibase)
Schema evolves through two mechanisms, not migrations:
1. `spring.jpa.hibernate.ddl-auto=update` handles new entities and new columns from `@Column` definitions.
2. **`SchedulerService.applySchemaPatches()`** runs idempotent `ALTER TABLE` / `CREATE TABLE` statements at startup via a `patch()` helper that swallows "column/table already exists" errors. This is how `certificate_inventory.use_proxy`, `escalation_contacts.team_id`, `TEXT` widenings, `scheduler_lock`, and the `port_*` / `dns_*` tables landed.

Rule: when you add a column an existing DB might not have, add a `patch()` line. Don't trust `ddl-auto` alone — it won't backfill defaults, rename columns, or widen types.

### Cert-check thread pool
`WebConfig` registers a `certCheckExecutor` (`ThreadPoolTaskExecutor`) shared by `CertificateCheckerService` / `PortCheckerService` / `DnsCheckerService` / `UptimeHttpCheckerService`. Sized by env:
- `EXECUTOR_CORE_SIZE` (default 20), `EXECUTOR_MAX_SIZE` (50), `EXECUTOR_QUEUE_CAPACITY` (1000).
Bump these when inventory grows past a few hundred rows or you see queue backpressure in metrics.

### Other services in the codebase
Services worth knowing about beyond the ones already mentioned:
- **`PortCheckerService` / `DnsCheckerService` / `UptimeHttpCheckerService`** — sibling sweep services with their own scheduler entry points; results flow to the Uptime / Port / DNS tabs. `DnsCheckerService` emits `CHANGED` / `ROTATED` events when records flip — these surface as alerts.
- **Domain (alan adı) registration monitoring** — `DomainCheckerService` + `RdapDomainClient` (proxy-aware: IANA bootstrap → registry RDAP → rdap.org fallback, 429-retry, per-monitor timeout `site.monitor.domain.rdap-timeout-ms`) + `WhoisDomainClient` (port-43 fallback, env-gated, off behind a 443/80-only proxy). `DomainMonitor` → append-only `domain_checks`; a daily sweep (hourly tick + `checkDue` per-domain, `scheduler_lock`) raises `DOMAINMON_UNKNOWN/EXPIRY/STATUS/CHANGED` via `MonitoringOutageService`. Manual `POST /monitoring/domain/{id}/check` (and the diagnostic) also evaluate alarms via `SchedulerService.evaluateDomainAlarmsNow`. The **"Domain Kaydı / Registration"** modal tab (`GET /monitoring/domain/{id}/registration?live=`, perm `domain.registration.view`) shows registrar + IANA ID, important dates, nameservers, resolved A/AAAA IPs + reverse-DNS hostnames, EPP status (tooltipped), DNSSEC — the four new fields (`registrar_iana_id` / `dnssec` / `resolved_ips` / `hostnames`) parse from the same RDAP/WHOIS response + `DnsCheckerService` and are patched into `domain_checks` in `applySchemaPatches()`. Corporate SSL-inspection breaks RDAP-over-443 with PKIX unless its CA is in `site.monitor.trust.ca-bundle-pem` (`TrustEvaluator`, live-reload); admin tools `/api/admin/diagnostics/domain-expiry` (step trace) and `/proxy-ca-chain` (capture the proxy CA as paste-ready PEM) diagnose it. `.tr` has no public RDAP → needs WHOIS/port-43, which the proxy can't carry (firewall opening required).
- **`ChainValidationService`** — chain + OCSP (primary) + CRL (fallback) via BouncyCastle 1.78. CRL responses are Caffeine-cached (max 200 entries, `CRL_CACHE_TTL_HOURS`). Proxy-aware independently of the main cert check.
- **`GeoIpService`** — login/audit IP enrichment via `ip-api.com`, 1h cache. Powers the audit viewer's country/city columns.
- **`HttpMetricsService` + `HttpMetricsInterceptor` + `MetricsService`** — feed `/actuator/prometheus`; `micrometer-registry-prometheus` is on the classpath.
- **`RememberMeService`** — token table `remember_me_tokens`, 7-day TTL, hourly cleanup task.
- **`ShutdownLogger`** — `@PreDestroy` hook that records the reason for JVM shutdown to the log; added because silent pod restarts were hard to diagnose.
- **`SqlPlaygroundService` / `SqlPlaygroundController`** — admin-only read-mostly SQL runner under `/api/admin/sql`. Audited.
- **`ExtendedHealthService`** — backs the System Health tab (heartbeat history, SMTP stats, DB metrics, scan staleness, scheduler-lock status).
- **`WebhookService`** — Teams / Slack delivery alongside email, 10s timeout, results logged to `notification_logs`.
- **`DbAnalyticsService`** — backs System Health → "Veritabanı" analytics (`GET /api/admin/system/db-analytics?days=1|7|30`). DB-wide top/slow SQL via **`pg_stat_statements`** (best-effort `CREATE EXTENSION` on `ApplicationReadyEvent`; falls back to the `sql_query_history` playground log when the extension isn't preloaded — branch on `summary.pgss`), plus `pg_stat_user_tables` (most-used tables + sizes) and `pg_stat_activity` (connections). `recent_queries` is the raw row list behind the "Sorgu"/"Ort. Süre" card drill-downs. Time-series bucketed in Java to Europe/Istanbul (hourly for 1-day, daily otherwise).
- **`UserActivityService`** — backs System Health → "Kullanıcı/Oturum". Aggregates `audit_logs` into login series, top users/sources, anomaly breakdown, and a 7×24 peak heatmap; lists active users from the single-session registry. Read access is global-admin **or** AUDIT (`requireSystemRead`), looser than the `requireAdmin` most admin endpoints use.
- **`AuditService`** (`@Async`) — writes every LOGIN / LOGIN_FAILED / LOGOUT / CRUD / SETTING event to `audit_logs` with IP+geo and a CSV `anomaly_flags` column (OFF_HOURS / UNUSUAL_IP / GEO_VELOCITY / BRUTE_FORCE / RATE_LIMITED). Detection windows are `site.monitor.audit.*` props.
- **`MonitoringOutageService`** — confirmation state machine for the uptime/port/DNS sweeps (alarm types ACCESSIBILITY / PORT_DOWN / DNS_FAILURE / DNS_CHANGED). Requires N consecutive confirmations before alerting to damp flapping; type-isolation keeps one signal type from masking another.
- **LDAP/AD** (`LdapSettingsService` / `LdapDirectoryService` / `LdapProvisioningService`, UI under `/api/admin/ldap`) — DB-persisted config with AES-GCM bind password and live reload (no restart); config + test-bind + attribute-viewer are live, login wiring/provisioning are later-phase. No JNDI dependency was added.
- **Weekly reports** (`WeeklyReportService`, `WeeklyReportReminderService`, `/api/weekly-reports`) — per-team DRAFT→PENDING→APPROVED state machine on ISO weeks; Friday 09:00 Europe/Istanbul reminder cron. Public **email-token approval links** intentionally bypass login.
- **Incidents** (`IncidentService`, `/api/incidents`) — SRE incident ledger with image attachments, trend rollups, and configurable status/priority/category options.
- **Admin runtime settings** — `AppSettingsService` (curated key/value, catalog in `AppSettingsCatalog`, live-reloads CORS), `SmtpSettingsService`/`SmtpMailService`, `GeneralSettingsController`, `DatabaseInfoService`, and `SecretCipher`/`SecretToolsService` (AES-GCM decrypt of stored SMTP/LDAP passwords, gated by `SITE_MONITOR_SECRET_KEY`). These settings + secret-tools endpoints are bootstrap-admin only.
- **Diagnostics** — `OpensslDiagnosticsService` / `NetworkDiagnosticsService` / `HstsDiagnosticsService` / `ConnectionDiagnosticsService`, persisted by `DiagnosticHistoryService` (`diagnostic_runs`); surfaced via the DiagnosticsModal under `/api/admin/diagnostics/*`.

### Deployment specifics
- **K8s manifests** (`k8s/`): 3 replicas, rolling update (`maxSurge=1, maxUnavailable=0`); HPA scales 3–10 on CPU 70% / mem 80%; PDB `minAvailable=2`; topology spread by hostname (`maxSkew=1`); non-root `UID 1000`, read-only root FS, all capabilities dropped. Probes: startup (12×10s), readiness (30s delay / 10s period), liveness (60s delay / 30s period). Also includes `postgres.yaml`, `ingress.yaml`, `openshift-route.yaml`.
- **Helm chart** (`helm/site-monitor/`): Bitnami PostgreSQL 18.x as a chart dependency; values split per environment under `environments/` (`develop.yaml` / `release.yaml` / `master.yaml`). `develop.yaml` runs 1 replica, email OFF, 2h scan, HPA OFF — useful diff to copy from when setting up a new lower env. `postgresql.primary.extendedConfiguration` preloads `shared_preload_libraries = 'pg_stat_statements'` so the DB-wide query analytics light up (needs a `helm upgrade` + DB restart in prod; the app falls back gracefully until then).
- **Dockerfile**: 3 stages — `node:20-alpine` (frontend) → `maven:3.9` (backend) → `eclipse-temurin:25-jre-alpine` (runtime). Container-aware JVM (`-XX:+UseContainerSupport`, heap ≈ 75%), in-pod debug tools (`curl bash dig`), healthcheck via `wget /health`. Final image runs as `appuser:1000`.
- **k6 smoke** (`perf/k6-smoke.js`): 50 VU × 30s against `/health` + `/api/system/network-status`. SLA: error rate < 1%, p95 < 500 ms, 99% checks pass. Auth-gated endpoints returning `401` are treated as healthy (gate working).
- **Local DB**: `data/` holds a SQLite file + WAL/SHM for dev runs (gitignored). Prod uses PostgreSQL exclusively.

### Timezone
Backend stores timestamps as UTC (audit, alerts, notifications). Log timestamps are localized via `LOG_TIMEZONE` (default `Europe/Istanbul`). Frontend formats with `date-fns` against Europe/Istanbul (UTC+3, no DST). When doing date arithmetic, use `setUTC*` to avoid the 3-hour drift trap.

## When adding tests

- **Backend**: mirror the package layout under `src/test/java/`. Prefer `@ExtendWith(MockitoExtension.class)` for unit isolation; reach for `@SpringBootTest` only when you need full context (currently exactly one: `SqlSamplesIntegrationTest`). H2 is on the test classpath.
- **Frontend**: place `.test.jsx` next to the component file under `src/test/`. Use `test-utils.jsx#render()`. Add new i18n keys to **both** `TR` and `EN` in the same change or the parity test will fail.

## CI gates (`.github/workflows/`)

- `ci.yml` — Java 25 + Node 24. Backend runs `mvn -B clean verify` (uploads Surefire + Jacoco artifacts). Frontend runs `npm ci` → `lint --if-present` → `test --silent -- --run` → `build` → `npm audit --audit-level=high --omit=dev` (non-blocking). Helm-lint runs against all three env values files.
- `docker-build.yml` — multi-arch image build + Trivy scan (HIGH/CRITICAL, currently report-only).
- `release.yml` — runs on `main`; detects bump from conventional commit prefix (`feat:` → minor, `fix:` → patch, `BREAKING CHANGE` → major), writes `VERSION`, publishes Helm chart. Does **not** re-run tests; trusts `ci.yml`.

## Things that bite

- After any backend change, the user expects: `mvn verify` → if green, `npm run build` (so the frontend is bundled) → restart backend → smoke. Don't skip the restart, login regressions show as 500.
- `start-local.ps1` launches the **packaged jar** (`backend/target/*.jar`), not `spring-boot:run`. `mvn test` does **not** repackage — after a backend code change run `mvn package -DskipTests` (or `verify`) *before* restarting, or the running app serves stale code (e.g. a new response field comes back empty).
- Don't hand-edit `VERSION` / `Chart.yaml` in a release commit — `release.yml` auto-bumps both after every push to `main` (a `chore(release): bump version … [skip ci]` commit). A manual bump makes the next `git pull --rebase` conflict on those files. Let CI own the version; just commit code with a conventional prefix (`feat:` → minor, `fix:` → patch) to drive the bump.
- Frontend always runs on port **5173**. Kill stale processes on 5173/5179 before `npm run dev`.
- Don't put `*` directly in i18n strings for required-field markers — use `<span className="req-star">*</span>` as a separate JSX node. Double-star bug has happened more than once.
- When removing a `useState` or handler, grep for **all** JSX references too — guards in render branches will throw at runtime, not at build time.
- Email credentials in `.env` are committed-locally-only. The Gmail app password in chat history must be rotated before any production deploy (see memory).
- **`.properties` files are read as ISO-8859-1**, not UTF-8. A raw Turkish character in a property *value* gets double-encoded at runtime (`Site Monitör` → UI shows `Site MonitÃ¶r`; a stray default like this even shadows a correct code-side default because Spring property beats the Java fallback arg). Write non-ASCII values as `\uXXXX` escapes (`Site Monit\u00f6r`). `PropertiesEncodingTest` fails the build on raw non-ASCII bytes in non-comment lines. Related trap: javac processes `\u` escapes even inside comments — an illustrative `\uXXXX` in Javadoc is a compile error; write `\\uXXXX`.
- **Renaming a settings-key prefix (e.g. `cert.monitor.*` → `site.monitor.*`) must touch the frontend too.** `AppSettingsCatalog` keys are hardcoded in admin components (`BrandingSettings.jsx` `K()`, `GeneralSettings.jsx` base-url lookup, `HttpMetricsExplorer.jsx` `RETENTION_KEY`) and — easy to miss — i18n labels are built dynamically as `t('general.lbl.' + it.key)`, so the `general.lbl.<full-key>` dict entries never show up when you grep for the construction site. A key mismatch fails *silently*: screens render with empty values / raw-key labels, and unit tests stay green because they mock the API with the same (wrong) keys. `scripts/check-brand.(sh|ps1)` now scans the dotted `cert.monitor` pattern as well — run it after any brand/key rename.
- When output contains mojibake (`Ã¶`, `ÃÂ¶`…), don't dismiss it as a console/terminal display artifact — verify the raw bytes end-to-end (`curl … | od -c`, check DB with `octet_length`, check the compiled class) before concluding. The 2026-08 branding bug shipped because the double-encoding was visible in a smoke output and got waved off as console encoding.
