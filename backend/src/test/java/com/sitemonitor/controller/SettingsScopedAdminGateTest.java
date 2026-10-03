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
    @DisplayName("2026-09-28: veri saklama anahtarları GLOBAL_ONLY — yasal saklama, parti boyutu, envanter oto-boşaltma, HER politikanın süre + onay anahtarı")
    void retentionKeys_globalOnly() {
        assertThat(AppSettingsCatalog.GLOBAL_ONLY).contains(
                com.sitemonitor.service.retention.RetentionCatalog.HOLD_KEY,
                com.sitemonitor.service.retention.RetentionCatalog.BATCH_KEY,
                com.sitemonitor.service.InventoryAutoPurgeService.KEY,
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
                "site.monitor.login.otp.max-contact-mismatches");
        assertThat(AppSettingsCatalog.GLOBAL_ONLY).containsAll(com.sitemonitor.service.otp.LoginMethodsService.KEYS);
        assertThat(src("LoginMethodsController")).contains("SessionScope.requireNotScopedAdmin(")
                .contains("SessionScope.isGlobalAdmin(session)");
    }

    @Test
    @DisplayName("2026-09-28: sistem geneli yüzeyler (Veri Saklama yazma uçları, Sorun Bildirimleri yönetimi) requireNotScopedAdmin TAŞIR")
    void systemWideSurfaces_keepGate() throws IOException {
        for (String c : List.of("RetentionAdminController", "LoginIssueController")) {
            assertThat(src(c)).as(c + " müdür kapısını kaybetmiş").contains("SessionScope.requireNotScopedAdmin(");
        }
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
