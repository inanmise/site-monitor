package com.sitemonitor.controller;

import com.sitemonitor.service.AppSettingsCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI — 2026-09-10 ürün kararı: kapsamlı müdür (AD ADMIN) Ayarlar'ı görür ve operasyonel
 * bölümleri düzenler; v20.50.29'un kapattığı sızıntı/RCE yüzeyleri ise global yöneticide kalır.
 *
 * <p>İki katman birlikte kilitlenir: (1) DÖRT sır denetleyicisi {@code requireNotScopedAdmin}'i
 * TAŞIMAK zorunda, açılan dördü TAŞIMAMALI (kaynak taraması — biri sessizce geri gelirse ya da
 * biri sessizce açılırsa kırmızı); (2) {@code AppSettingsCatalog.GLOBAL_ONLY} anahtarları katalogda
 * var olmalı (yazım hatası = kapı yok) ve riskli sınıfların her biri listede olmalı.
 */
class SettingsScopedAdminGateTest {

    private static final Path CTRL = Path.of("src/main/java/com/sitemonitor/controller");

    /** Kimlik bilgisi/sır taşıyan yüzeyler — müdüre KAPALI kalır. */
    private static final List<String> MUST_GATE = List.of(
            "SmtpAdminController", "LdapAdminController", "SecretToolsController", "DatabaseInfoController");

    /** Operasyonel yüzeyler — müdüre AÇIK (matris izni yeter). */
    private static final List<String> MUST_NOT_GATE = List.of(
            "GeneralSettingsController", "BrandingController", "LoginAnomalyController", "StormSettingsController");

    private static String src(String cls) throws IOException {
        return Files.readString(CTRL.resolve(cls + ".java"), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("sır yüzeyleri (SMTP/LDAP/Secret/DB) requireNotScopedAdmin TAŞIR")
    void secretSurfaces_keepGate() throws IOException {
        for (String c : MUST_GATE) {
            assertThat(src(c)).as(c + " müdür kapısını kaybetmiş — v20.50.29 KRİTİK bulgusu geri açılır")
                    .contains("SessionScope.requireNotScopedAdmin(");
        }
    }

    @Test
    @DisplayName("operasyonel yüzeyler (Genel/Marka/LoginAnomali/Storm) müdüre açık — kapı YOK")
    void operationalSurfaces_open() throws IOException {
        for (String c : MUST_NOT_GATE) {
            assertThat(src(c)).as(c + " müdürü hâlâ reddediyor — 2026-09-10 kararı uygulanmamış")
                    .doesNotContain("SessionScope.requireNotScopedAdmin(");
        }
    }

    @Test
    @DisplayName("GLOBAL_ONLY anahtarlarının HEPSİ katalogda (yazım hatası = sessiz kapı yokluğu)")
    void globalOnlyKeys_existInCatalog() {
        Set<String> catalog = AppSettingsCatalog.ALL.stream().map(AppSettingsCatalog.Setting::key)
                .collect(java.util.stream.Collectors.toSet());
        for (String k : AppSettingsCatalog.GLOBAL_ONLY) {
            assertThat(catalog).as("GLOBAL_ONLY anahtarı katalogda yok: " + k).contains(k);
        }
    }

    @Test
    @DisplayName("riskli sınıfların her biri GLOBAL_ONLY'de: SSRF, DNS, CA/TOFU, k6 ikili, push URL/başlık, base-url, CORS, log")
    void riskClasses_covered() {
        assertThat(AppSettingsCatalog.GLOBAL_ONLY).contains(
                "site.monitor.monitoring.allow-internal-targets",
                "site.monitor.monitoring.allow-loopback-targets",
                "site.monitor.dns.resolvers",
                "site.monitor.trust.ca-bundle-pem",
                "site.monitor.trust.auto-pin.enabled",
                "site.monitor.scripted.k6-bin",
                "site.monitor.userpush.url",
                "site.monitor.userpush.headers",
                "site.monitor.app.base-url",
                "site.monitor.cors.allowed-origins",
                "logging.level.com.sitemonitor");
    }

    @Test
    @DisplayName("BO6: LDAP müdür/üyelik eşlemesi (yetkilendirme girdisi) GLOBAL_ONLY — takım kapsamlı müdür değiştiremez")
    void ldapAuthorisationInputs_globalOnly() {
        assertThat(AppSettingsCatalog.GLOBAL_ONLY).contains(
                "site.monitor.ldap.manager-attributes",
                "site.monitor.ldap.prune-unsupported-teams",
                "site.monitor.ldap.manager-refresh-hours");
    }

    @Test
    @DisplayName("org geneli görünürlük anahtarları (envanter + Olaylar, 2026-09-28) GLOBAL_ONLY — müdür diğer takımlar adına açamaz/kapatamaz")
    void orgVisibilitySwitches_globalOnly() {
        assertThat(AppSettingsCatalog.GLOBAL_ONLY).contains(
                "site.monitor.inventory.visible-to-all",
                IncidentsController.VISIBLE_TO_ALL_KEY);
    }

    @Test
    @DisplayName("2026-09-28: veri saklama anahtarları GLOBAL_ONLY — yasal saklama, parti boyutu, HER politikanın süre + onay anahtarı")
    void retentionKeys_globalOnly() {
        // Envanter çöp kutusu oto-boşaltma anahtarı 2026-10-07'de kalktı (silme kalıcı) — katalogda da yok.
        assertThat(AppSettingsCatalog.GLOBAL_ONLY).doesNotContain("site.monitor.inventory.auto-purge-days");
        assertThat(AppSettingsCatalog.GLOBAL_ONLY).contains(
                com.sitemonitor.service.retention.RetentionCatalog.HOLD_KEY,
                com.sitemonitor.service.retention.RetentionCatalog.BATCH_KEY,
                "site.monitor.activity.retention-days",
                "site.monitor.audit.retention-days",
                "site.monitor.failed-login.retention-days");
        for (var p : com.sitemonitor.service.retention.RetentionCatalog.ALL) {
            if (p.settingKey() != null) {
                assertThat(AppSettingsCatalog.isGlobalOnly(p.settingKey())).as("süre anahtarı açık: " + p.settingKey()).isTrue();
            }
            assertThat(AppSettingsCatalog.isGlobalOnly(AppSettingsCatalog.RETENTION_APPROVAL_PREFIX + p.id()))
                    .as("uyum onayı anahtarı açık: " + p.id()).isTrue();
        }
    }

    @Test
    @DisplayName("2026-10-02: Giriş Yöntemleri (LDAP girişi, kodla giriş, deneme/sınır eşikleri, global yönetici izni) GLOBAL_ONLY; uç global yöneticiye kapılı")
    void loginMethods_globalOnly() throws IOException {
        assertThat(AppSettingsCatalog.GLOBAL_ONLY).contains(
                "site.monitor.login.ldap-enabled",
                "site.monitor.login.otp.push.enabled",
                "site.monitor.login.otp.email.enabled",
                "site.monitor.login.otp.push.ttl-seconds",
                "site.monitor.login.otp.email.ttl-seconds",
                "site.monitor.login.otp.max-attempts",
                "site.monitor.login.otp.resend-cooldown-seconds",
                "site.monitor.login.otp.max-requests-per-user",
                "site.monitor.login.otp.max-requests-per-ip",
                "site.monitor.login.otp.max-failed-verifications",
                "site.monitor.login.otp.allow-global-admins",
                "site.monitor.login.otp.retention-days",
                // 2026-10-03: kişi bilgisi doğrulaması (telefon / e-posta da sorulsun) + eşleşmeme sınırı
                "site.monitor.login.otp.push.require-phone",
                "site.monitor.login.otp.email.require-email",
                "site.monitor.login.otp.max-contact-mismatches",
                // 2026-10-03: kodla giriş push metni (müdür kurum geneli giriş kodu iletisini değiştiremez)
                "site.monitor.login.otp.push.title-tr",
                "site.monitor.login.otp.push.title-en",
                "site.monitor.login.otp.push.message-tr",
                "site.monitor.login.otp.push.message-en",
                // 2026-10-21: kullanıcı adı ön eki kuralı (müdür kuralı kapatıp açamaz, ön ekleri / muafları değiştiremez)
                "site.monitor.login.username-prefix.enabled",
                "site.monitor.login.username-prefix.allowed",
                "site.monitor.login.username-prefix.exempt");
        assertThat(AppSettingsCatalog.GLOBAL_ONLY).containsAll(com.sitemonitor.service.otp.LoginMethodsService.KEYS);
        assertThat(AppSettingsCatalog.GLOBAL_ONLY).containsAll(com.sitemonitor.service.loginpolicy.UsernamePrefixRule.KEYS);
        assertThat(src("LoginMethodsController")).contains("SessionScope.requireNotScopedAdmin(")
                .contains("SessionScope.isGlobalAdmin(session)");
    }

    @Test
    @DisplayName("2026-10-05: tema politikası (açık temalar + varsayılan) GLOBAL_ONLY; uç müdüre SALT OKUNUR açık, yazma global yöneticiye kapılı")
    void themes_globalOnly_readOnlyForScoped() throws IOException {
        assertThat(AppSettingsCatalog.GLOBAL_ONLY).contains(
                com.sitemonitor.service.ThemeCatalog.KEY_ENABLED,
                com.sitemonitor.service.ThemeCatalog.KEY_DEFAULT);
        String src = src("ThemeSettingsController");
        // Okuma müdüre açık (sayfa salt okunur çizilir) — requireNotScopedAdmin YOK; yazma isGlobalAdmin ile kapılı.
        assertThat(src).doesNotContain("SessionScope.requireNotScopedAdmin(")
                .contains("SessionScope.isGlobalAdmin(session)");
    }

    @Test
    @DisplayName("2026-10-18: teknik hata raporu push anahtarları (olay başına + dönem hatırlatması) GLOBAL_ONLY; uç müdüre SALT OKUNUR, yazma global yöneticiye kapılı")
    void techErrorPush_globalOnly() throws IOException {
        assertThat(AppSettingsCatalog.GLOBAL_ONLY).containsAll(com.sitemonitor.service.techerror.TechErrorReportSettings.PUSH_KEYS)
                .contains(com.sitemonitor.service.techerror.TechErrorReportSettings.KEY_REMINDER_PUSH);
        String src = src("TechErrorSettingsController");
        int put = src.indexOf("@PutMapping(\"/settings/push\")");
        assertThat(put).as("push PUT ucu").isPositive();
        assertThat(src.substring(put, src.indexOf("pushSettings()", put))).contains("requireGlobalWriter(session)");
    }

    @Test
    @DisplayName("2026-10-23 (çok pod R3-1): kalıcı değerlendirme kipi + lider erteleme anahtarları katalogda, doğru tip/grupta ve GLOBAL_ONLY")
    void multiPodR31Keys_globalOnly() {
        String mode = com.sitemonitor.service.evalstate.EvalStateMode.SETTING_KEY;
        String yield = com.sitemonitor.service.schedule.SideJob.LEADER_YIELD_KEY;
        assertThat(mode).isEqualTo("site.monitor.eval-state.mode");
        assertThat(yield).isEqualTo("site.monitor.scheduler.leader-yield.enabled");
        assertThat(AppSettingsCatalog.GLOBAL_ONLY).contains(mode, yield);

        AppSettingsCatalog.Setting m = AppSettingsCatalog.byKey(mode);
        assertThat(m).as("katalogda: " + mode).isNotNull();
        assertThat(m.group()).isEqualTo("monitoring");
        assertThat(m.type()).isEqualTo(AppSettingsCatalog.Type.ENUM);
        // Seçenekler kipin kalıcı değerleriyle AYNI (ayarda saklanan dize = EvalStateMode.key()).
        assertThat(m.enumOptions()).containsExactly(
                java.util.Arrays.stream(com.sitemonitor.service.evalstate.EvalStateMode.values())
                        .map(com.sitemonitor.service.evalstate.EvalStateMode::key).toArray(String[]::new));

        AppSettingsCatalog.Setting y = AppSettingsCatalog.byKey(yield);
        assertThat(y).as("katalogda: " + yield).isNotNull();
        assertThat(y.group()).isEqualTo("scheduler");
        assertThat(y.type()).isEqualTo(AppSettingsCatalog.Type.BOOL);
    }

    @Test
    @DisplayName("2026-10-28 (çok pod R3-2): devirde okuma anahtarı katalogda, BOOL, grup monitoring, kip anahtarının yanında ve GLOBAL_ONLY")
    void multiPodR32Key_globalOnly() {
        String read = com.sitemonitor.service.evalstate.EvalStateReadSwitch.KEY;
        assertThat(read).isEqualTo("site.monitor.eval-state.read-enabled");
        assertThat(AppSettingsCatalog.GLOBAL_ONLY).contains(read);
        assertThat(AppSettingsCatalog.isGlobalOnly(read)).isTrue();

        AppSettingsCatalog.Setting r = AppSettingsCatalog.byKey(read);
        assertThat(r).as("katalogda: " + read).isNotNull();
        assertThat(r.group()).isEqualTo("monitoring");
        assertThat(r.type()).isEqualTo(AppSettingsCatalog.Type.BOOL);

        // Ayarlar → Genel → İzleme Alarmları'nda kip ayarının hemen ardından gösterilsin (katalog sırası = ekran sırası)
        List<String> keys = AppSettingsCatalog.ALL.stream().map(AppSettingsCatalog.Setting::key).toList();
        assertThat(keys.indexOf(read)).isEqualTo(keys.indexOf(com.sitemonitor.service.evalstate.EvalStateMode.SETTING_KEY) + 1);
    }

    @Test
    @DisplayName("2026-10-29 (çok pod R3-3): dağıtık kontrol ana anahtarı (BOOL) + tür listesi (CSV) katalogda, grup scheduler, lider erteleme anahtarının ardından ve GLOBAL_ONLY")
    void multiPodR33Keys_globalOnly() {
        String enabled = com.sitemonitor.service.checks.DistributedChecks.KEY_ENABLED;
        String types = com.sitemonitor.service.checks.DistributedChecks.KEY_TYPES;
        assertThat(enabled).isEqualTo("site.monitor.checks.distributed.enabled");
        assertThat(types).isEqualTo("site.monitor.checks.distributed.types");
        assertThat(AppSettingsCatalog.GLOBAL_ONLY).contains(enabled, types);
        assertThat(AppSettingsCatalog.isGlobalOnly(enabled)).isTrue();
        assertThat(AppSettingsCatalog.isGlobalOnly(types)).isTrue();

        AppSettingsCatalog.Setting e = AppSettingsCatalog.byKey(enabled);
        assertThat(e).as("katalogda: " + enabled).isNotNull();
        assertThat(e.group()).isEqualTo("scheduler");
        assertThat(e.type()).isEqualTo(AppSettingsCatalog.Type.BOOL);
        AppSettingsCatalog.Setting t = AppSettingsCatalog.byKey(types);
        assertThat(t).as("katalogda: " + types).isNotNull();
        assertThat(t.group()).isEqualTo("scheduler");
        assertThat(t.type()).isEqualTo(AppSettingsCatalog.Type.CSV);

        // Ayarlar → Genel → Zamanlayıcı'da lider erteleme ayarının hemen ardından: ana anahtar, sonra tür listesi.
        List<String> keys = AppSettingsCatalog.ALL.stream().map(AppSettingsCatalog.Setting::key).toList();
        int yield = keys.indexOf(com.sitemonitor.service.schedule.SideJob.LEADER_YIELD_KEY);
        assertThat(keys.indexOf(enabled)).isEqualTo(yield + 1);
        assertThat(keys.indexOf(types)).isEqualTo(yield + 2);
    }

    @Test
    @DisplayName("2026-10-29 (çok pod R3-4): parçalı sertifika taraması anahtarı (BOOL) katalogda, grup scheduler, 3-3 tür listesinin ardından ve GLOBAL_ONLY")
    void multiPodR34Key_globalOnly() {
        String key = com.sitemonitor.service.certsweep.CertChunking.KEY_ENABLED;
        assertThat(key).isEqualTo("site.monitor.checks.cert-chunks.enabled");
        assertThat(AppSettingsCatalog.GLOBAL_ONLY).contains(key);
        assertThat(AppSettingsCatalog.isGlobalOnly(key)).isTrue();

        AppSettingsCatalog.Setting s = AppSettingsCatalog.byKey(key);
        assertThat(s).as("katalogda: " + key).isNotNull();
        assertThat(s.group()).isEqualTo("scheduler");
        assertThat(s.type()).isEqualTo(AppSettingsCatalog.Type.BOOL);

        // Ayarlar → Genel → Zamanlayıcı'da 3-3'ün iki anahtarının hemen ardından (katalog sırası = ekran sırası)
        List<String> keys = AppSettingsCatalog.ALL.stream().map(AppSettingsCatalog.Setting::key).toList();
        assertThat(keys.indexOf(key))
                .isEqualTo(keys.indexOf(com.sitemonitor.service.checks.DistributedChecks.KEY_TYPES) + 1);
        // Pod başına açılış ayarları katalogda DEĞİL (3-3 işçi ayarları gibi; Helm config.extraEnv taşır)
        assertThat(keys).noneMatch(k -> k.startsWith("site.monitor.checks.cert-chunks.") && !k.equals(key));
    }

    @Test
    @DisplayName("2026-09-28: sistem geneli yüzeyler (Veri Saklama yazma uçları, Sorun Bildirimleri yönetimi) requireNotScopedAdmin TAŞIR")
    void systemWideSurfaces_keepGate() throws IOException {
        for (String c : List.of("RetentionAdminController", "LoginIssueController")) {
            assertThat(src(c)).as(c + " müdür kapısını kaybetmiş").contains("SessionScope.requireNotScopedAdmin(");
        }
    }

    @Test
    @DisplayName("2026-10-31: veritabanı sorgu izlemesi kapasite / güvenlik sınırları GLOBAL_ONLY; alarm aç/kapa ve elle bekleme müdüre açık")
    void dbQueryLimits_globalOnly() {
        assertThat(AppSettingsCatalog.GLOBAL_ONLY).contains(
                "site.monitor.dbquery.max-concurrency",
                "site.monitor.dbquery.per-connection-concurrency",
                "site.monitor.dbquery.max-timeout-seconds",
                "site.monitor.dbquery.max-rows-cap",
                "site.monitor.dbquery.mail-max-rows",
                "site.monitor.dbquery.mail-max-columns",
                "site.monitor.dbquery.min-interval-seconds",
                "site.monitor.dbquery.plan-check-mode",
                "site.monitor.dbquery.max-plan-cost",
                "site.monitor.dbquery.max-plan-rows",
                // saklama anahtarları otomatik GLOBAL_ONLY (RetentionCatalog)
                "site.monitor.metrics.dbquery.retention-days",
                "site.monitor.dbquery.alert-rows.retention-days");
        // Türün alarm anahtarı öteki türlerle aynı kapsamda (müdür kendi takımı için değil, ama global de değil — mevcut kural)
        assertThat(AppSettingsCatalog.GLOBAL_ONLY).doesNotContain("site.monitor.dbquery.alert-enabled",
                "site.monitor.dbquery.manual-cooldown-seconds");
    }

    @Test
    @DisplayName("isScopedAdmin / isScopedAdminInRequest: ADMIN + viewTeamIds dolu = müdür; global ADMIN ve USER değil; bağlam yoksa false")
    void scopedAdminDetection() {
        try {
            MockHttpSession scoped = new MockHttpSession();
            scoped.setAttribute("systemRole", "ADMIN");
            scoped.setAttribute("viewTeamIds", new java.util.ArrayList<>(List.of(7L)));
            MockHttpSession global = new MockHttpSession();
            global.setAttribute("systemRole", "ADMIN");
            MockHttpSession user = new MockHttpSession();
            user.setAttribute("systemRole", "USER");
            user.setAttribute("viewTeamIds", new java.util.ArrayList<>(List.of(7L)));

            assertThat(SessionScope.isScopedAdmin(scoped)).isTrue();
            assertThat(SessionScope.isScopedAdmin(global)).isFalse();
            assertThat(SessionScope.isScopedAdmin(user)).isFalse();
            assertThat(SessionScope.isScopedAdmin(null)).isFalse();

            assertThat(SessionScope.isScopedAdminInRequest()).as("istek bağlamı yok").isFalse();
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.setSession(scoped);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
            assertThat(SessionScope.isScopedAdminInRequest()).isTrue();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
