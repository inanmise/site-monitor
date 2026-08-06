# Testing

How Site Monitor is tested today, where the gaps are, and where the strategy is heading. Treat this as the source of truth — if a test or workflow disagrees, file an issue.

## TL;DR — run everything locally

```bash
# Backend (JUnit + Mockito + AssertJ + Spring Boot Test)
cd backend
mvn test
# Coverage report: target/site/jacoco/index.html  (Jacoco)

# Frontend (Vitest + React Testing Library + JSdom)
cd ../frontend
npm ci
npm run test            # all suites, single run
npm run test:watch      # watch mode while editing
npm run test:coverage   # coverage report -> coverage/index.html

# Smoke (PowerShell — Windows-friendly, also runs in pwsh on Linux/macOS)
pwsh ./scripts/smoke.ps1 -BaseUrl http://localhost:8080

# Performance smoke (requires k6 — https://k6.io)
k6 run -e BASE_URL=http://localhost:8080 ./perf/k6-smoke.js
```

## Test categories — what we cover, what's a gap

| Category | Status | Where |
|----------|--------|-------|
| **Unit** | **1686 backend tests** (JUnit 5 + Mockito + AssertJ), **399 frontend tests** (Vitest). Backend line coverage **67.2%** (enforced floor, see below); frontend line coverage **57.1%**. | `backend/src/test/`, `frontend/src/test/` |
| **Integration** | `@DataJpaTest` slices (H2 in PostgreSQL-compat mode) for repositories, `@WebMvcTest` slices for controllers, one full `@SpringBootTest` (`SqlSamplesIntegrationTest`). Real-PostgreSQL Testcontainers under an opt-in `it` Maven profile is **planned** (targeted at native/`@Query` repos). | `backend/src/test/` |
| **Network isolation (TLS/HTTPS)** | This is a monitoring app that opens outbound TLS/HTTPS connections on schedules — **no test ever touches a real external host.** Certificate/handshake behaviour is exercised against a **local self-signed HTTPS server** (`com.sun.net.httpserver.HttpsServer`) with certs generated at runtime via BouncyCastle at precise `notAfter` offsets (`HttpCheckerServiceTest`, `HstsDiagnosticsServiceTest`, `CertificateCheckerServiceTest`); expiry-day math is verified by feeding those certs through the real parse path. SSRF policy is unit-tested directly (`SsrfGuardTest`). | `backend/src/test/.../service/` |
| **Sanity / Smoke** | `scripts/smoke.ps1` hits `/health`, login gate, and a small list of APIs. Run after every deploy. | `scripts/smoke.ps1` |
| **Regression** | Built up organically — every bug fix lands with a test that pins the behaviour. Examples: `SchedulerServiceTest`'s outage detection paths, `EscalationServiceTest`'s daily-realert dedupe. | Throughout the suite |
| **User Acceptance (UAT)** | **Not yet automated.** Phase 3 plans a Cucumber BDD framework so business analysts can express acceptance scenarios in Gherkin. | — |
| **Performance** | `perf/k6-smoke.js` — basic load probe. Phase 2 adds full read/write profiles. | `perf/` |
| **Security** | CI runs `npm audit` (high+ severity on prod deps) and Trivy on the built Docker image (HIGH/CRITICAL). Both currently non-blocking until the baseline is clean; flip `exit-code: '1'` in the workflows once triaged. | `.github/workflows/ci.yml`, `docker-build.yml` |
| **Compatibility** | Backend pinned to Java 25 (Zulu) + PostgreSQL 17 in dev. Cross-version matrix is Phase 3. | — |
| **Localization** | `i18n-parity.test.jsx` enforces TR↔EN key parity, no empty values, and matching placeholder counts. | `frontend/src/test/i18n-parity.test.jsx` |
| **Usability** | Manual today. Phase 2 plans axe-core (vitest-axe) over every component test for an automated a11y subset; full WCAG audit stays manual. | — |

## CI gates (`.github/workflows/`)

- **`ci.yml`** — runs on every push to `develop`, `main`, `release/**` and on PRs to those branches.
  - Backend job: `mvn -B clean verify` — runs all JUnit tests via Surefire **and enforces the JaCoCo coverage gate** (`jacoco:check`, bound to `verify`). Test reports and JaCoCo coverage uploaded as artifacts.
  - Frontend job: `npm ci` → `npm run lint --if-present` → **`npm run test:coverage`** (enforces the Vitest coverage thresholds) → `npm run build` → `npm audit` (non-blocking).
  - Helm lint job: chart linted against each environment values file.
- **`docker-build.yml`** — builds and pushes the multi-arch image, then runs **Trivy** against the produced image for HIGH/CRITICAL CVEs (currently report-only).
- **`release.yml`** — semver bump from conventional commit prefix (`feat:` → minor, `fix:` → patch, `BREAKING CHANGE` → major) and Helm chart publish. Does not gate on tests; trusts `ci.yml`.

## Adding a new test

- **Backend:** Mirror the package layout under `src/test/java/`. Prefer `@ExtendWith(MockitoExtension.class)` for unit-level isolation; reach for `@SpringBootTest` only when you need the full context (e.g., transaction boundaries, JPA queries). Name the class after the SUT plus `Test` (`FooService` → `FooServiceTest`).
- **Frontend:** Put the file next to the component in `src/test/` with a `.test.jsx` suffix. Always render through `test-utils.jsx`'s `render()` so the i18n and theme providers are wired up. Mock the api client through `vi.mock('../api/client', ...)`; never let a real fetch escape into the test runner.
- **i18n keys:** Add to BOTH `TR` and `EN` in `src/i18n/index.jsx`. The parity test will fail loudly if one is missing.

## Coverage gates (enforced, not aspirational)

Strategy: **measure → floor just below current → ratchet up.** Floors are set just under the measured level so
they catch regressions today, and are raised as new tests land. They are **not** aspirational targets — a build
that dips below fails.

**Backend (`backend/pom.xml`, `jacoco:check` on `verify`):**
- Bundle floor: **line ≥ 0.65, instruction ≥ 0.62** (measured 2026-08-06: line 67.2%, instruction 64.6%).
- Per-class floors on the risk-critical cert/alarm classes (measured line today → floor):
  `SsrfGuard` 100%→0.95 · `MonitoringOutageService` 82%→0.80 · `EscalationService` 63%→0.62 ·
  `DomainCheckerService` 56%→0.55 · `RdapDomainExpiryService` 46%→0.45 · `CertificateCheckerService` 43%→0.42.
  These classes carry large network/proxy/RDAP-HTTP branches that are not unit-testable, so their floors track
  **real coverage**, not a blanket ≥90% — raise each as integration coverage grows.
- `PageCheckerService` (9. tür, Sayfa Bütünlüğü) `PageCheckerServiceTest` ile YEREL bir HTTP sunucusuna karşı test
  edilir (dış siteye bağımlılık yok): OK / DEGRADED / DOWN, redirect zinciri, HEAD→405→GET, hariç-tutma, SSRF (metadata
  kaynağı bloklanır), SITE_CRAWL derinlik + robots.txt. `MonitoringControllerTest` `/page` sahiplik izolasyonunu (IDOR)
  ve takım-zorunluluğunu kapsar.

**Frontend (`frontend/vite.config.js`, `test.coverage.thresholds`):**
- Global floor: **statements/lines ≥ 55, branches ≥ 60, functions ≥ 31** (measured 2026-08-06: 57.1 / 62.7 / 33.3).
- Per-file lock: `src/utils/incidentMeta.js` at **100%** lines/functions/statements (fully covered; regression = red).
- Guard suites: `naming-consistency.test.jsx` (rename bekçisi — eski marka/`cm.` öneki üretim ağacına giremez),
  `i18n-used-keys.test.jsx` (kodda `t('...')` ile çağrılan her literal anahtar iki sözlükte de olmak zorunda),
  `brand-default.test.jsx` (varsayılan turp logosu seti + override'sız render'lar korunur).
  Backend eşleri: `NamingConsistencyTest`, `PropertiesEncodingTest` (properties'te ham non-ASCII yasak),
  `EmailTemplateStandardTest` (Outlook-güvenli mail standardı: tek 32px lockup, 600px kart, style'sız,
  BRAND.md §5.1) ve `EmailPreviewHarnessTest` (her mail türü × severity HTML'ini
  `backend/target/email-previews/sonra-*.html`'e yazar — şablon değişimi gözle doğrulanır;
  `-Demail.preview.prefix=once` ile değişiklik-öncesi çift üretilir).

| Area | Today (measured) | Enforced floor | Direction |
|------|------------------|----------------|-----------|
| Backend line coverage | 67.2% | ≥ 65% bundle + per-class | ratchet toward 75% |
| Frontend line coverage | 57.1% | ≥ 55% | ratchet toward 65% |
| Critical-path E2E | none | none | login + cert lifecycle |
| A11y violations on dashboard | none | none | 0 serious / 0 critical |

## Phase roadmap

**Phase 1 (this PR)** — foundation:
- Jacoco + Vitest coverage tooling and CI artifacts.
- Frontend tests actually run in CI (previously only `npm build` did).
- `npm audit` + Trivy image scan in CI (report-only baseline).
- i18n parity regression guard.
- Three backend service smoke tests (DnsCheckerService, PortCheckerService, UptimeHttpCheckerService).
- Three frontend component smoke tests (Login, CertificateModal, StatsView).
- `scripts/smoke.ps1` and `perf/k6-smoke.js`.
- This document.

**Phase 2** — close the coverage gaps, one PR per area:
- SchedulerServiceTest, MonitoringControllerTest, AuditControllerTest, SystemControllerTest.
- Component tests for the remaining 24 frontend components.
- `AlertFlowIntegrationTest`, `AuthFlowIntegrationTest`, `NetworkOutageDetectionIntegrationTest` using the existing `@SpringBootTest` pattern.
- `vitest-axe` smoke a11y in every component test.
- Full k6 profiles (read-heavy dashboard, write-heavy bulk add) + nightly perf workflow.

**Phase 3** — operator confidence and business acceptance:
- Playwright cross-browser E2E (Chromium / Firefox / WebKit), nightly with a PR subset.
- Cucumber BDD stub so business analysts can author UAT scenarios in Gherkin.
- JDK + PostgreSQL version matrix in CI.
- Pa11y full WCAG AA audit + planned in-person usability sessions (5 × 30min).

## Triaging a CI failure

1. Open the failing job → "Build & test" step. The Maven Surefire summary and the Vitest `Test Files` summary print at the bottom.
2. For backend, download the `backend-test-report` artifact; the XML in `surefire-reports/` has stack traces. The `backend-coverage-report` artifact contains the Jacoco HTML you can open in a browser.
3. For frontend, the Vitest output is inline in the log. Re-run locally with `npm run test:watch` to iterate.
4. For Trivy or `npm audit` regressions, decide: is this an actual CVE that needs a dependency bump, or a false positive that needs an allowlist? Don't suppress without writing down the reasoning.

## Senaryo İzleme (k6) testleri

Karar mantığı, k6 özet-JSON ayrıştırma, secret maskeleme (`SecretMask.maskValues`), `--blacklist-ip` CIDR üretimi
(`SsrfGuard.blacklistCidrs`) ve sabit-kodlu-secret taraması **birim testlerle** (k6 gerektirmez) kapsanır:
`ScriptedCheckerServiceTest`, `SecretMaskTest`, `SsrfGuardTest`. Controller yetkisi (`monitoring.scripted` →
ADMIN/TEAM_ADMIN; USER 403) `MonitoringControllerTest`'te doğrulanır.

**Gerçek k6 ile entegrasyon (opsiyonel — CI imajında k6 binary'si olduğunda):** k6 kurulu bir ortamda
`ScriptedCheckerService` bir yerel HTTP sunucusuna karşı çalıştırılarak PASS / FAIL (check kasıtlı başarısız) /
ERROR (sentaks hatası) / TIMEOUT (sleep — sürecin öldüğü + temp dosyaların silindiği doğrulanır) senaryoları ve
`--blacklist-ip`'in iç ağ isteğini engellediği test edilir. k6 yoksa `ScriptedCheckerService.isAvailable()` false döner
ve bu testler anlamlı şekilde **atlanır** (`org.junit.jupiter.api.Assumptions.assumeTrue(...)`), açılış/derleme bozulmaz.

If you change anything that would invalidate this document, update it in the same commit.
