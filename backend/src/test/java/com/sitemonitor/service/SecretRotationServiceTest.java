package com.sitemonitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Gizli anahtar döndürme servisi (2026-10-11) — gerçek SQL (H2): sayım, yalnız önceki anahtardaki değerlerin koşullu
 * yeniden yazımı, JSON sırlarının yapısı korunur, sayfalama, ret kodları, eksik tablo.
 */
class SecretRotationServiceTest {

    static final String OLD = "eski-anahtar-111";
    static final String NEW = "yeni-anahtar-222";

    JdbcTemplate jdbc;
    SecretCipher oldCipher;
    SecretCipher rotating;
    SecretRotationService svc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:rot" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE ldap_settings (id BIGINT PRIMARY KEY, bind_password_enc TEXT, updated_at VARCHAR(30))");
        jdbc.execute("CREATE TABLE smtp_settings (id BIGINT PRIMARY KEY, password_enc TEXT, updated_at VARCHAR(30))");
        jdbc.execute("CREATE TABLE http_monitors (id BIGINT PRIMARY KEY, name VARCHAR(200), basic_auth_pass_enc TEXT, custom_headers_enc TEXT)");
        jdbc.execute("CREATE TABLE keyword_monitors (id BIGINT PRIMARY KEY, name VARCHAR(200), custom_headers_enc TEXT)");
        jdbc.execute("CREATE TABLE pagespeed_monitors (id BIGINT PRIMARY KEY, name VARCHAR(200), basic_auth_pass_enc TEXT, custom_headers_enc TEXT)");
        jdbc.execute("CREATE TABLE scripted_monitors (id BIGINT PRIMARY KEY, name VARCHAR(200), env_json TEXT)");
        jdbc.execute("CREATE TABLE scripted_script_versions (id BIGINT PRIMARY KEY, version VARCHAR(40), env_json TEXT)");
        jdbc.execute("CREATE TABLE app_settings (id BIGINT PRIMARY KEY, setting_key VARCHAR(150), setting_value TEXT, updated_at VARCHAR(30))");
        oldCipher = SecretCipherRotationTest.cipher(OLD, null);
        rotating = SecretCipherRotationTest.cipher(NEW, OLD);
        svc = new SecretRotationService(jdbc, rotating);
    }

    private String envJson(String secretValue) {
        return "[{\"name\":\"API_USER\",\"value\":\"demo\",\"secret\":false},"
                + "{\"name\":\"API_TOKEN\",\"value\":\"" + secretValue + "\",\"secret\":true}]";
    }

    private void seed() {
        jdbc.update("INSERT INTO ldap_settings VALUES (1, ?, '2026-10-01T00:00:00')", oldCipher.encrypt("ldap-pw"));
        jdbc.update("INSERT INTO smtp_settings VALUES (1, ?, '2026-10-01T00:00:00')", rotating.encrypt("smtp-pw"));
        jdbc.update("INSERT INTO http_monitors VALUES (1, 'Ödeme API', ?, NULL)", oldCipher.encrypt("basic"));
        jdbc.update("INSERT INTO http_monitors VALUES (2, 'Bozuk kayıt', ?, ?)",
                SecretCipherRotationTest.cipher("kayip-anahtar-000", null).encrypt("x"), rotating.encrypt("X-Key: 1"));
        jdbc.update("INSERT INTO scripted_monitors VALUES (5, 'Sepet akışı', ?)", envJson(oldCipher.encrypt("tok-5")));
        jdbc.update("INSERT INTO scripted_script_versions VALUES (9, '1.0.2', ?)", envJson(oldCipher.encrypt("tok-9")));
        jdbc.update("INSERT INTO app_settings VALUES (1, 'site.monitor.userpush.headers', ?, '2026-10-01T00:00:00')",
                "[{\"name\":\"Authorization\",\"value\":\"" + oldCipher.encrypt("Bearer abc") + "\",\"secret\":true}]");
        jdbc.update("INSERT INTO app_settings VALUES (2, 'site.monitor.other', ?, '2026-10-01T00:00:00')",
                "[{\"value\":\"" + oldCipher.encrypt("dokunma") + "\",\"secret\":true}]");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loc(Map<String, Object> status, String id) {
        return ((List<Map<String, Object>>) status.get("locations")).stream()
                .filter(m -> id.equals(m.get("id"))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("durum: konum başına güncel / önceki / okunamayan; okunamayan kayıt adıyla; yalnız tanımlı ayar anahtarı sayılır")
    @SuppressWarnings("unchecked")
    void statusCounts() {
        seed();
        Map<String, Object> s = svc.status();
        Map<String, Object> totals = (Map<String, Object>) s.get("totals");
        assertThat(totals).containsEntry("current", 2).containsEntry("previous", 5).containsEntry("unreadable", 1);
        assertThat(loc(s, "ldap_bind_password")).containsEntry("previous", 1);
        assertThat(loc(s, "smtp_password")).containsEntry("current", 1);
        assertThat(loc(s, "http_basic_auth")).containsEntry("previous", 1).containsEntry("unreadable", 1);
        assertThat((List<String>) loc(s, "http_basic_auth").get("unreadable_records")).containsExactly("Bozuk kayıt (#2)");
        assertThat(loc(s, "scripted_env")).containsEntry("previous", 1);
        assertThat(loc(s, "scripted_versions")).containsEntry("previous", 1);
        assertThat(loc(s, "push_headers")).containsEntry("previous", 1).containsEntry("rows", 1);
        assertThat(s).containsEntry("can_reencrypt", true).containsEntry("previous_removable", false);
        assertThat((Map<String, Object>) s.get("key")).containsEntry("current_configured", true)
                .containsEntry("previous_configured", true);
    }

    @Test
    @DisplayName("yeniden şifreleme: yalnız önceki anahtardakiler yeniden yazılır; güncel ve okunamayan aynen kalır; JSON yapısı korunur")
    @SuppressWarnings("unchecked")
    void reencryptRewritesOnlyPrevious() {
        seed();
        String smtpBefore = jdbc.queryForObject("SELECT password_enc FROM smtp_settings", String.class);
        String brokenBefore = jdbc.queryForObject("SELECT basic_auth_pass_enc FROM http_monitors WHERE id = 2", String.class);
        String otherBefore = jdbc.queryForObject("SELECT setting_value FROM app_settings WHERE id = 2", String.class);

        Map<String, Object> r = svc.reencrypt("admin");
        assertThat(r).containsEntry("values_rewritten", 5).containsEntry("rows_rewritten", 5)
                .containsEntry("changed_meanwhile", 0).containsEntry("unreadable", 1);

        // Değerler artık YALNIZ yeni anahtarla çözülür
        SecretCipher newOnly = SecretCipherRotationTest.cipher(NEW, null);
        assertThat(newOnly.decrypt(jdbc.queryForObject("SELECT bind_password_enc FROM ldap_settings", String.class))).isEqualTo("ldap-pw");
        assertThat(newOnly.decrypt(jdbc.queryForObject("SELECT basic_auth_pass_enc FROM http_monitors WHERE id = 1", String.class)))
                .isEqualTo("basic");
        // Güncel anahtardaki, okunamayan ve başka ayar anahtarındaki değer DEĞİŞMEDİ
        assertThat(jdbc.queryForObject("SELECT password_enc FROM smtp_settings", String.class)).isEqualTo(smtpBefore);
        assertThat(jdbc.queryForObject("SELECT basic_auth_pass_enc FROM http_monitors WHERE id = 2", String.class)).isEqualTo(brokenBefore);
        assertThat(jdbc.queryForObject("SELECT setting_value FROM app_settings WHERE id = 2", String.class)).isEqualTo(otherBefore);
        // Önbelleği updated_at ile tazelenen tekil tablo damgalandı
        assertThat(jdbc.queryForObject("SELECT updated_at FROM ldap_settings", String.class)).isNotEqualTo("2026-10-01T00:00:00");
        // JSON: düz değişken, ad ve bayrak aynı; yalnız sır değeri değişti
        String env = jdbc.queryForObject("SELECT env_json FROM scripted_monitors WHERE id = 5", String.class);
        assertThat(env).contains("\"name\":\"API_USER\",\"value\":\"demo\",\"secret\":false").contains("\"name\":\"API_TOKEN\"");
        String tok = env.replaceAll(".*\"API_TOKEN\",\"value\":\"(enc:v1:[^\"]+)\".*", "$1");
        assertThat(newOnly.decrypt(tok)).isEqualTo("tok-5");

        Map<String, Object> after = (Map<String, Object>) r.get("status");
        assertThat((Map<String, Object>) after.get("totals")).containsEntry("previous", 0).containsEntry("unreadable", 1);
        assertThat(after).containsEntry("previous_removable", true).containsEntry("can_reencrypt", false);

        // İkinci koşu hiçbir şey yazmaz (idempotent)
        assertThat(svc.reencrypt("admin")).containsEntry("rows_rewritten", 0);
    }

    @Test
    @DisplayName("koşullu yazma: okunan değer arada değiştiyse satır ezilmez")
    void conditionalWrite() {
        jdbc.update("INSERT INTO keyword_monitors VALUES (3, 'K', ?)", oldCipher.encrypt("H: 1"));
        SecretRotationService.Location loc = SecretRotationService.LOCATIONS.stream()
                .filter(l -> l.id().equals("keyword_headers")).findFirst().orElseThrow();
        assertThat(svc.write(loc, 3, "enc:v1:baskasi-yazdi", rotating.encrypt("H: 2"))).isFalse();
        assertThat(oldCipher.decrypt(jdbc.queryForObject("SELECT custom_headers_enc FROM keyword_monitors WHERE id = 3", String.class)))
                .isEqualTo("H: 1");
    }

    @Test
    @DisplayName("sayfalama: 500'den fazla satır eksiksiz işlenir")
    void paging() {
        for (int i = 1; i <= SecretRotationService.PAGE * 2 + 3; i++) {
            jdbc.update("INSERT INTO pagespeed_monitors VALUES (?, ?, ?, NULL)", i, "P" + i, oldCipher.encrypt("pw" + i));
        }
        Map<String, Object> r = svc.reencrypt("admin");
        assertThat(r).containsEntry("rows_rewritten", SecretRotationService.PAGE * 2 + 3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pagespeed_monitors", Integer.class)).isEqualTo(1003);
        SecretRotationService.LocationCount c = svc.count(SecretRotationService.LOCATIONS.stream()
                .filter(l -> l.id().equals("pagespeed_basic_auth")).findFirst().orElseThrow());
        assertThat(c.current()).isEqualTo(1003);
        assertThat(c.previous()).isZero();
    }

    @Test
    @DisplayName("ret: güncel anahtar ayarlı değil / önceki yok / iş başka yerde sürüyor")
    void refusals() {
        SecretCipher dev = new SecretCipher();
        dev.init();
        assertThatThrownBy(() -> new SecretRotationService(jdbc, dev).reencrypt("admin"))
                .isInstanceOf(SecretRotationService.RotationRefused.class)
                .extracting(e -> ((SecretRotationService.RotationRefused) e).code()).isEqualTo("SECRET_KEY_NOT_CONFIGURED");
        assertThatThrownBy(() -> new SecretRotationService(jdbc, SecretCipherRotationTest.cipher(NEW, null)).reencrypt("admin"))
                .extracting(e -> ((SecretRotationService.RotationRefused) e).code()).isEqualTo("PREVIOUS_KEY_NOT_CONFIGURED");
        SchedulerService scheduler = mock(SchedulerService.class);
        when(scheduler.callWithSchedulerLock(eq(SecretRotationService.LOCK), any())).thenReturn(Optional.empty());
        svc.setScheduler(scheduler);
        assertThatThrownBy(() -> svc.reencrypt("admin"))
                .extracting(e -> ((SecretRotationService.RotationRefused) e).code()).isEqualTo("REENCRYPT_RUNNING");
    }

    @Test
    @DisplayName("push başlıkları yeniden yazılınca ayar önbelleği hemen tazelenir; yazılmayınca dokunulmaz")
    void refreshesSettingsCache() {
        AppSettingsService settings = mock(AppSettingsService.class);
        svc.setAppSettings(settings);
        svc.reencrypt("admin");
        verify(settings, never()).refreshFromDb();
        jdbc.update("INSERT INTO app_settings VALUES (1, 'site.monitor.userpush.headers', ?, NULL)",
                "[{\"name\":\"Authorization\",\"value\":\"" + oldCipher.encrypt("Bearer abc") + "\",\"secret\":true}]");
        svc.reencrypt("admin");
        verify(settings).refreshFromDb();
    }

    @Test
    @DisplayName("eksik tablo: konum hata taşır, önceki anahtar 'kaldırılabilir' denmez")
    void missingTable() {
        jdbc.execute("DROP TABLE scripted_script_versions");
        Map<String, Object> s = svc.status();
        assertThat(loc(s, "scripted_versions")).containsKey("error");
        assertThat(s).containsEntry("previous_removable", false);
    }

    @Test
    @DisplayName("2026-10-12: sayım tavanda kesilen konum (truncated) varken önceki anahtar 'kaldırılabilir' denmez")
    void truncatedCountIsNotRemovable() {
        var loc = SecretRotationService.LOCATIONS.get(0);
        var full = new SecretRotationService.LocationCount(loc, 10, 10, 0, 0, List.of(), false, null);
        var cut = new SecretRotationService.LocationCount(loc, SecretRotationService.MAX_ROWS, SecretRotationService.MAX_ROWS, 0, 0,
                List.of(), true, null);
        assertThat(svc.statusJson(List.of(full))).containsEntry("previous_removable", true);
        assertThat(svc.statusJson(List.of(full, cut))).containsEntry("previous_removable", false);
    }

    @Test
    @DisplayName("bozuk JSON okunamayan sayılır ve yeniden yazılmaz")
    void brokenJson() {
        jdbc.update("INSERT INTO scripted_monitors VALUES (7, 'Bozuk', ?)", "{enc:v1:yarim");
        Map<String, Object> s = svc.status();
        assertThat(loc(s, "scripted_env")).containsEntry("unreadable", 1);
        assertThat(svc.reencrypt("admin")).containsEntry("rows_rewritten", 0);
        assertThat(jdbc.queryForObject("SELECT env_json FROM scripted_monitors WHERE id = 7", String.class)).isEqualTo("{enc:v1:yarim");
    }

    @Test
    @DisplayName("2026-10-12: tavanda kesilen konum yeniden şifrelemede KALDIĞI YERDEN sürer (baştan başlayıp ilerleyemez değil); tavan varken can_reencrypt açık")
    void reencryptResumesAcrossRuns() {
        List<Object[]> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= 1200; i++) rows.add(new Object[]{ i, "izleme-" + i, oldCipher.encrypt("p" + i) });
        jdbc.batchUpdate("INSERT INTO http_monitors VALUES (?, ?, ?, NULL)", rows);
        svc.maxRows = SecretRotationService.PAGE;   // çalıştırma başına bir sayfa
        Map<String, Object> st = svc.status();
        assertThat(loc(st, "http_basic_auth")).containsEntry("truncated", true);
        assertThat(st).containsEntry("can_reencrypt", true);

        Map<String, Object> r1 = svc.reencrypt("admin");
        assertThat(r1).containsEntry("rows_rewritten", 500).containsEntry("partial", true);
        assertThat(loc(r1, "http_basic_auth")).containsEntry("partial", true);
        Map<String, Object> r2 = svc.reencrypt("admin");
        assertThat(r2).containsEntry("rows_rewritten", 500);   // 501..1000 — baştan DEĞİL
        Map<String, Object> r3 = svc.reencrypt("admin");
        assertThat(r3).containsEntry("rows_rewritten", 200).doesNotContainKey("partial");
        Integer left = jdbc.queryForObject("SELECT COUNT(*) FROM http_monitors", Integer.class);
        assertThat(left).isEqualTo(1200);
        // Hepsi güncel anahtarda: yeni çalıştırma hiçbir şey yazmaz
        svc.maxRows = SecretRotationService.MAX_ROWS;
        assertThat(svc.reencrypt("admin")).containsEntry("rows_rewritten", 0);
        assertThat(svc.status()).containsEntry("previous_removable", true);
    }

    @Test
    @DisplayName("2026-10-12: süre bütçesi dolarsa konum 'yarım' döner ve sonraki çalıştırma kaldığı id'den devam eder")
    void reencryptDeadlineResumes() {
        for (int i = 1; i <= 3; i++) {
            jdbc.update("INSERT INTO http_monitors VALUES (?, ?, ?, NULL)", i, "izleme-" + i, oldCipher.encrypt("p" + i));
        }
        SecretRotationService.Location httpLoc = SecretRotationService.LOCATIONS.stream()
                .filter(l -> "http_basic_auth".equals(l.id())).findFirst().orElseThrow();
        // Süresi geçmiş bütçe: ilk sayfa işlenir (3 satır < sayfa → konum biter) — bitmiş konum yarım sayılmaz
        SecretRotationService.LocationResult done = svc.reencrypt(httpLoc, 0L);
        assertThat(done.partial()).isFalse();
        assertThat(done.rowsRewritten()).isEqualTo(3);

        // Sayfadan fazla satır + süresi geçmiş bütçe: bir sayfa sonra YARIM, sonraki çağrı kaldığı id'den sürer
        List<Object[]> more = new java.util.ArrayList<>();
        for (int i = 4; i <= 603; i++) more.add(new Object[]{ i, "izleme-" + i, oldCipher.encrypt("p" + i) });
        jdbc.batchUpdate("INSERT INTO http_monitors VALUES (?, ?, ?, NULL)", more);
        SecretRotationService.LocationResult first = svc.reencrypt(httpLoc, 0L);
        assertThat(first.partial()).isTrue();
        assertThat(first.rowsRewritten()).isEqualTo(497);                  // ilk sayfa: 3 güncel + 497 önceki
        SecretRotationService.LocationResult rest = svc.reencrypt(httpLoc, Long.MAX_VALUE);
        assertThat(rest.partial()).isFalse();
        assertThat(rest.rowsRewritten()).isEqualTo(103);                   // 501..603 — baştan DEĞİL
    }
}
