package com.sitemonitor.service;

import com.sitemonitor.model.SmtpSettings;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.mail.MailKit;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Site Monitor'ün gönderdiği HER e-posta türünün örnek çıktısı (e-posta yeniden tasarımı 2026-09-26).
 * Galeri ({@link EmailGalleryTest}) ve sözleşme testi ({@link EmailResponsiveContractTest}) aynı
 * listeyi kullanır — yeni bir e-posta türü eklendiğinde BURAYA da eklenmezse iki kapı da onu görmez.
 *
 * <p>Veri yer tutucudur ({@code example.com}, {@code Takım A}); zorlayıcı örnekler bilinçlidir:
 * 80 karakterlik host adları, uzun URL/tarayıcı imzaları, Türkçe karakterler, 8+ DNS değeri,
 * tam haftalık erişilebilirlik, bulgulu envanter.
 */
final class EmailSamples {

    /** Tek örnek: {@code family} sözleşme gruplaması, {@code logo} marka logosu varyantı (ok/warning/critical). */
    record Sample(String slug, String family, String title, String html, String text, String logo) { }

    static final String BASE = "https://sitemonitor.example.com";
    static final String LONG_HOST = "very-long-subdomain-name-for-stress-testing.internal-services.region-a.example.com";
    static final String LONG_URL = "https://portal.example.com/app/very/long/path/segment/that/keeps/going/and/going/index.html?query=parametre&ikinci=değer";
    static final String LONG_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36 Edg/129.0.0.0";
    static final String TR = "Ödeme Ağ Geçidi — İşlem Şüphesi (ğüşıöç İĞÜŞÖÇ)";

    final EmailNotificationService svc;
    final EmailTemplateBuilder tb;
    final SmtpMailService smtp;
    private final SmtpSettings smtpSettings;

    EmailSamples() {
        AppSettingsService appSettings = mock(AppSettingsService.class);
        lenient().when(appSettings.getString(any(), any())).thenAnswer(inv -> BASE);
        SmtpSettingsService settingsService = mock(SmtpSettingsService.class);
        smtpSettings = new SmtpSettings();
        smtpSettings.setEnabled(true);
        smtpSettings.setHost("smtp.example.com");
        smtpSettings.setPort(587);
        smtpSettings.setStartTlsEnable(true);
        lenient().when(settingsService.getOrDefaults()).thenReturn(smtpSettings);
        tb = new EmailTemplateBuilder(appSettings);
        ReflectionTestUtils.setField(tb, "appBaseUrl", BASE);
        smtp = new SmtpMailService(settingsService);
        svc = new EmailNotificationService(settingsService, smtp, mock(NotificationLogRepository.class), appSettings, tb);
        ReflectionTestUtils.setField(svc, "appBaseUrl", BASE);
    }

    private final List<Sample> out = new ArrayList<>();

    private void add(String slug, String family, String title, String html, String text, String logo) {
        out.add(new Sample(slug, family, title, html, text, logo));
    }

    /** Yalnız HTML dönen kurucular: metin MailDoc'un kaydından (gönderim hunisinin kullandığı yol). */
    private void addHtml(String slug, String family, String title, String html, String logo) {
        add(slug, family, title, html, MailKit.plainTextFor(html), logo);
    }

    private void alert(String slug, String title, String message, String domain, String level, String type, Integer days,
                       Map<String, Object> ctx) {
        add(slug, "alert", title, svc.buildAlertEmailHtml(title, message, domain, level, type, days, ctx),
                svc.buildAlertEmailText(title, message, domain, level, type, days, ctx), BrandMailAssets.variantForLevel(level));
    }

    private void resolved(String slug, String title, String domain, String type, String level, String by, String at, String created,
                          Map<String, Object> ctx, String team, EmailNotificationService.UptimeSummary up) {
        add(slug, "resolved", title, svc.buildResolutionEmailHtml(domain, type, level, null, by, at, created, ctx, team, up),
                svc.buildResolutionEmailText(domain, type, by, at, created, ctx), "ok");
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static List<Map<String, Object>> attempts(int n, String err) {
        List<Map<String, Object>> l = new ArrayList<>();
        for (int i = 1; i <= n; i++) l.add(map("attempt", i, "checked_at", "2026-09-26T07:0" + i + ":30", "status", "down", "error", err));
        return l;
    }

    List<Sample> all() {
        out.clear();
        alerts();
        resolutions();
        storms();
        admin();
        issues();
        reminders();
        incidents();
        weekly();
        reports();
        noc();
        systemMaintenance();
        loginCode();
        addHtml("smtp-test", "smtp", "[Site Monitor] SMTP test e-postası", smtp.buildTestHtml(smtpSettings), "ok");
        return List.copyOf(out);
    }

    // ── Kodla giriş e-postası (2026-10-02) ──────────────────────────────────

    /** Örnek kod sabit ("004219" — baştaki sıfırlar korunur); uzun ad + uzun tarayıcı imzası + IPv6. */
    static final String LOGIN_SAMPLE_CODE = "004219";

    private void loginCode() {
        var info = new com.sitemonitor.service.mail.LoginCodeMail.Info("Kişi A (" + TR + ")", LOGIN_SAMPLE_CODE, 45,
                "02.10.2026 14:05:09", "2001:db8:85a3::8a2e:370:7334",
                UserAgentSummary.labelOf(LONG_UA));
        var m = com.sitemonitor.service.mail.LoginCodeMail.build(info);
        add("login-code", "admin", com.sitemonitor.service.mail.LoginCodeMail.subject(), m.html(), m.text(), "ok");
    }

    // ── Sistem Bakım Modu duyuruları (2026-10-02) ───────────────────────────

    private void systemMaintenance() {
        for (var kind : com.sitemonitor.service.mail.SystemMaintenanceMail.Kind.values()) {
            // ENDED ("bakım tamamlandı", 2026-10-02): plan ≠ gerçekleşen (uzatılmış) — en uzun satır düzeni
            boolean ended = kind == com.sitemonitor.service.mail.SystemMaintenanceMail.Kind.ENDED;
            var info = new com.sitemonitor.service.mail.SystemMaintenanceMail.Info(kind, "02.10.2026 22:00 – 03.10.2026 01:30",
                    "3 sa 30 dk", "Veritabanı sunucusu sürüm yükseltmesi ve " + TR, null,
                    "BT Destek Masası · dahili 1234 · destek@example.com", BASE,
                    ended ? "02.10.2026 22:00 – 03.10.2026 01:00" : null,
                    ended ? com.sitemonitor.service.mail.SystemMaintenanceMail.EndShift.EXTENDED : null);
            var m = com.sitemonitor.service.mail.SystemMaintenanceMail.build(info);
            add("system-maintenance-" + kind.name().toLowerCase(java.util.Locale.ROOT), "admin",
                    com.sitemonitor.service.mail.SystemMaintenanceMail.subject(info), m.html(), m.text(), "ok");
        }
    }

    // ── 7/24 İzleme Ekibi (NOC) ailesi (2026-09-27) ─────────────────────────

    private void noc(String slug, com.sitemonitor.service.mail.MailDoc.Mail m, String title, String logo) {
        add(slug, "noc", title, m.html(), m.text(), logo);
    }

    private void noc() {
        var person = (java.util.function.BiFunction<String, String, com.sitemonitor.service.noc.NocMailComposer.Person>)
                (name, phone) -> new com.sitemonitor.service.noc.NocMailComposer.Person(name, "Kıdemli Sistem Uzmanı", phone);
        var team = new com.sitemonitor.service.noc.NocMailComposer.TeamBlock("Takım A",
                List.of(person.apply("Kişi A", "+90 555 000 00 00"), person.apply("Kişi B", "0555 000 00 01"),
                        person.apply("Kişi C (" + TR + ")", null)),
                true, new com.sitemonitor.service.noc.NocMailComposer.Person("Kişi M", "Müdür", "+90 555 000 00 09"),
                List.of(new com.sitemonitor.service.noc.NocMailComposer.Contact("Kişi M", "Müdür", "kisi.m@example.com"),
                        new com.sitemonitor.service.noc.NocMailComposer.Contact("Kişi T", "Teknik Sorumlu", "kisi.t@example.com")));
        String instructions = "Önce listedeki ilk kişiyi arayın; 10 dakikada ulaşılamazsa sıradakine geçin.\n"
                + "Gece 00:00–07:00 arası yalnız KRİTİK için arayın. <b>kalın değil</b> & \"tırnak\"";
        noc("noc-open", com.sitemonitor.service.noc.NocMailComposer.open(new com.sitemonitor.service.noc.NocMailComposer.AlarmInfo(
                "CRITICAL", "HTTP / Web", "HTTP/Web erişilemez", LONG_URL, "Ödeme portalı", "2026-09-26T21:05:00",
                "java.net.http.HttpConnectTimeoutException: HTTP connect timed out after 10000 ms", team, instructions,
                BASE + "/?tab=http&monitor=42", BASE + "/?tab=incidents&incident=4242",
                BASE + "/?tab=alerthistory&alert=4242&n_call=1", List.of("NOC Ana", "NOC Yedek"))),
                "[Site Monitor] [7/24] KRİTİK", "critical");
        var noList = new com.sitemonitor.service.noc.NocMailComposer.TeamBlock("Takım B", List.of(), false,
                new com.sitemonitor.service.noc.NocMailComposer.Person("Kişi M", "Müdür", null), List.of());
        noc("noc-open-no-call-list", com.sitemonitor.service.noc.NocMailComposer.open(new com.sitemonitor.service.noc.NocMailComposer.AlarmInfo(
                "HIGH", "DNS", "DNS kaydı değişti", "example.com (A)", "example.com", "2026-09-26T21:05:00", null,
                noList, null, BASE + "/?tab=dns&monitor=7", BASE + "/?tab=incidents&incident=4243",
                BASE + "/?tab=alerthistory&alert=4243&n_call=1", List.of("NOC Ana"))),
                "[Site Monitor] [7/24] YÜKSEK", "warning");
        noc("noc-resolved", com.sitemonitor.service.noc.NocMailComposer.resolved(new com.sitemonitor.service.noc.NocMailComposer.ResolvedInfo(
                "Port", "Port yanıt vermiyor", "db.example.com:5432", "Veritabanı", "2026-09-26T21:05:00",
                "2026-09-26T21:40:00", "Sistem (otomatik)", team, BASE + "/?tab=port&monitor=12", BASE + "/?tab=incidents&incident=4244")),
                "[Site Monitor] [7/24] ÇÖZÜLDÜ", "ok");
        List<com.sitemonitor.service.noc.NocMailComposer.Member> members = new ArrayList<>();
        for (int i = 1; i <= 14; i++)
            members.add(new com.sitemonitor.service.noc.NocMailComposer.Member("srv" + i + ".example.com:443", "Port",
                    i % 2 == 0 ? "Takım A" : "Takım B", BASE + "/?tab=port&monitor=" + i,
                    BASE + "/?tab=alerthistory&alert=" + (5000 + i) + "&n_call=1"));
        noc("noc-storm", com.sitemonitor.service.noc.NocMailComposer.storm(23, "Tüm izlemeler", "Port Kesintisi",
                "2026-09-26T21:05:00", members, List.of(team, noList), instructions, BASE + "/?tab=noc", List.of("NOC Ana")),
                "[Site Monitor] [7/24] ALARM FIRTINASI", "critical");
        noc("noc-storm-resolved", com.sitemonitor.service.noc.NocMailComposer.stormResolved(members.subList(0, 10),
                members.subList(10, 14), "2026-09-26T21:05:00", "2026-09-26T21:50:00"),
                "[Site Monitor] [7/24] ÇÖZÜLDÜ — fırtına", "ok");
        noc("noc-test", com.sitemonitor.service.noc.NocMailComposer.test("NOC Ana", instructions),
                "[Site Monitor] [7/24] Test", "ok");
    }

    // ── #1–6 Alarm ailesi ────────────────────────────────────────────────────

    private void alerts() {
        Map<String, Object> inv = map(
                "not_after", "2026-09-28T02:59:00Z", "issuer_cn", "DigiCert EV RSA CA G2", "subject", "CN=" + LONG_HOST,
                "fingerprint", "EB0B59B1AA31C0F5C2D8E4A76B93F1D0C4A85E2739BD61FA0C8E7B4D53B8DD8C3",
                "first_alert_at", "2026-09-10T06:00:00Z", "realert_count", 3, "checked_at", "2026-09-26T07:00:00Z",
                "team_name", "Takım A", "alert_event_id", 4242L,
                "inv_ops", List.of("Netscaler", "WAF'ta Var", "Kullanım Durumu", "OpenShift Route", "Sunucuda Değişecek"),
                "inv_contacts", new LinkedHashMap<>(Map.of("Servis Yönetimi", "Ad Soyad - ad.soyad@example.com",
                        "IISAdmin Ekibi", "iisadmin@example.com")),
                "inv_change_desc", "1. Sertifika alım süreci IISAdmins tarafından yapılır; PFX güvenlik ekibiyle paylaşılır.\n"
                        + "2. Değişiklik planlaması Servis Yönetimi tarafından ilgili ekiplerle koordineli yapılır.\n"
                        + "3. Sertifika Netscaler ve WAF'ta güncellenir.");
        alert("alert-cert-expiry-critical", "[Site Monitor] ACİL 2 GÜN KALDI · " + LONG_HOST,
                LONG_HOST + " adresindeki sertifikanın süresi 2 gün içinde doluyor.", LONG_HOST, "CRITICAL", "EXPIRY", 2, inv);
        alert("alert-cert-expiry-warning", "[Site Monitor] 25 GÜN KALDI · www.example.com",
                "www.example.com adresindeki sertifikanın süresi 25 gün içinde doluyor.", "www.example.com", "MEDIUM", "EXPIRY", 25,
                map("not_after", "2026-10-21T00:00:00Z", "issuer_cn", "Example Internal CA", "team_name", "Takım A"));
        alert("alert-cert-revoked", "[Site Monitor] KRİTİK · api.example.com — sertifika iptal edildi",
                "Sertifika yayıncı tarafından iptal edildi (OCSP: revoked).", "api.example.com", "CRITICAL", "REVOKED", null,
                map("issuer_cn", "Example Internal CA", "team_name", "Takım A"));
        alert("alert-domain-expiry", "[Site Monitor] YÜKSEK · example.com — alan adı 12 gün içinde doluyor",
                "example.com alan adının kaydı 12 gün içinde doluyor.", "example.com", "HIGH", "DOMAINMON_EXPIRY", 12,
                map("expiry_date", "2026-10-08T12:37:46Z", "registrar", "Örnek Registrar Ltd. Şti.", "source", "RDAP",
                        "status_codes", "client transfer prohibited, client delete prohibited, redemptionPeriod",
                        "nameservers", "ns1.example.com, ns2.example.com", "team_name", "Takım A"));
        alert("alert-domain-status", "[Site Monitor] UYARI · example.org — alan adı durum kodu",
                "Alan adında kısıtlayıcı bir EPP durum kodu tespit edildi.", "example.org", "WARNING", "DOMAINMON_STATUS", null,
                map("status_codes", "clientHold", "registrar", "Örnek Registrar", "team_name", "Takım A"));
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= 10; i++) rows.append(i % 3 == 0 ? "TIMEOUT" : "BROKEN").append('\t').append(LONG_URL).append("&n=").append(i).append("\t404\n");
        alert("alert-page-integrity", "[Site Monitor] YÜKSEK · www.example.com — sayfa bütünlüğü",
                "Sayfada 12 kırık kaynak ve 1 zaman aşımı tespit edildi.", "https://www.example.com/", "HIGH", "PAGE_INTEGRITY", null,
                map("page_status", "DEGRADED", "page_mode", "SITE_CRAWL", "broken_resources", 12, "mixed_content_count", 0,
                        "alert_third_party", false, "alert_mixed_content", true, "alert_timeout", true,
                        "monitor_confirm_attempts", 3, "monitor_confirm_interval_ms", 30000L,
                        "problem_rows", rows.toString().trim(), "problem_total", 13, "team_name", "Takım A"));
        alert("alert-scripted-fail", "[Site Monitor] KRİTİK · Ödeme akışı — sentetik test başarısız",
                "Sentetik senaryo 3 doğrulamadan 2'sinde başarısız oldu.", TR, "CRITICAL", "SCRIPTED_FAIL", null,
                map("scripted_status", "1 ✓ / 2 ✗", "error", "status is 200 — beklenen 200, gelen 503",
                        "failed_checks", "login yanıtı 200 döner\ntoken claim 'aud' doğru",
                        "output_tail", "running (00m03.2s), 0/1 VUs, 1 complete\n✗ login yanıtı 200 döner\n  ↳  0% — ✓ 0 / ✗ 1\n"
                                + "http_req_duration..: avg=812ms min=120ms med=640ms max=2.1s p(90)=1.9s p(95)=2s",
                        "team_name", "Takım A", "alert_event_id", 77L));
        alert("alert-scripted-disabled", "[Site Monitor] Sentetik izleme devre dışı bırakıldı: " + TR,
                "Senaryo art arda 20 kez zaman aşımına uğradı; izleme otomatik olarak DEVRE DIŞI bırakıldı.\n\nİzleme: " + TR
                        + "\nDurum: DEVRE DIŞI (otomatik)", TR, "CRITICAL", "SCRIPTED_FAIL", null, Map.of());
        alert("alert-weak-algorithm", "[Site Monitor] Zayıf algoritma — legacy.example.com",
                "Sertifika güvenlik standardını karşılamıyor: SHA-1 imza, RSA 1024. Sorumlu takımla koordineli yenileme planı gerekiyor.",
                "legacy.example.com", "WARNING", "WEAK_ALGORITHM", 140, null);
        alert("alert-ping-slow", "[Site Monitor] YÜKSEK · gw.example.com — yanıt süresi yükseldi",
                "gw.example.com yanıt süresi taban çizgisinin üstüne çıktı (p95 340 ms, taban 40 ms).", "gw.example.com", "HIGH", "PING_SLOW", null,
                map("detail", "p95 340 ms / taban 40 ms", "checked_at", "2026-09-26T07:00:00Z", "team_name", "Takım A"));
        alert("alert-accessibility", "[Site Monitor KRİTİK] " + LONG_HOST + " — Erişim Kesintisi",
                "KRİTİK: " + LONG_HOST + " adresine erişilemiyor.", LONG_HOST, "CRITICAL", "ACCESSIBILITY", null,
                map("port", 443, "first_failure_at", "2026-09-26T07:00:00", "last_error",
                        "java.net.http.HttpConnectTimeoutException: HTTP connect timed out after 10000 ms (proxy: none)",
                        "confirm_attempt_count", 3, "confirm_delay_ms", 30000L, "confirm_attempts", attempts(3, "connect timed out"),
                        "team_name", "Takım A", "alert_event_id", 4242L));
        alert("alert-port-down", "[Site Monitor KRİTİK] db.example.com — Port Kesintisi", "KRİTİK: db.example.com:5432 yanıt vermiyor.",
                "db.example.com", "CRITICAL", "PORT_DOWN", null,
                map("port", 5432, "protocol", "TCP", "first_failure_at", "2026-09-26T07:00:00", "confirm_attempt_count", 3,
                        "confirm_delay_ms", 30000L, "confirm_attempts", attempts(3, "Connection refused"), "monitor_id", 12, "team_name", "Takım A"));
        // O-b1 (2026-09-29): UYARI seviyeli izleme alarmı — rozet ve "Seviye" satırı olayın seviyesini yazar (eskiden sabit "KRİTİK").
        alert("alert-port-down-warning", "[Site Monitor] UYARI · db.example.com — Port Kesintisi", "UYARI: db.example.com:5432 yanıt vermiyor.",
                "db.example.com", "WARNING", "PORT_DOWN", null,
                map("port", 5432, "protocol", "TCP", "first_failure_at", "2026-09-26T07:00:00", "confirm_attempt_count", 3,
                        "confirm_delay_ms", 30000L, "confirm_attempts", attempts(3, "Connection refused"), "monitor_id", 12, "team_name", "Takım A"));
        alert("alert-dns-failure", "[Site Monitor KRİTİK] mail.example.com — DNS Çözümleme Hatası", "KRİTİK: MX kaydı çözülemiyor.",
                "mail.example.com", "CRITICAL", "DNS_FAILURE", null,
                map("record_type", "MX", "first_failure_at", "2026-09-26T07:00:00", "last_error", "SERVFAIL", "monitor_id", 7));
        alert("alert-keyword-absent", "[Site Monitor KRİTİK] keyword", "KRİTİK: sayfada istenmeyen ifade bulundu.", LONG_URL, "CRITICAL", "KEYWORD", null,
                map("keyword", "Bakım çalışması", "operator", "EQ", "match_count", 0, "occurrences", 2, "http_status", 200, "response_ms", 412,
                        "snippet", "…Sayın müşterimiz, sistemlerimizde planlı bakım çalışması nedeniyle 02:00–04:00 arası…",
                        "first_failure_at", "2026-09-26T07:00:00", "monitor_id", 42, "confirm_attempt_count", 2,
                        "confirm_attempts", attempts(2, "koşul sağlanmadı"), "team_name", "Takım A", "alert_event_id", 91L));
        alert("alert-keyword", "[Site Monitor KRİTİK] keyword", "KRİTİK: kelime bulunamıyor.", "https://www.example.com/", "CRITICAL", "KEYWORD", null,
                map("keyword", "Giriş Yap", "operator", "GTE", "match_count", 1, "occurrences", 0, "http_status", 200,
                        "first_failure_at", "2026-09-26T07:00:00", "monitor_id", 42));
        alert("alert-ping-down", "[Site Monitor KRİTİK] 10.0.0.7 — Ping", "KRİTİK: host yanıt vermiyor.", "10.0.0.7", "CRITICAL", "PING_DOWN", null,
                map("ip_version", "v4", "packet_loss", 100, "first_failure_at", "2026-09-26T07:00:00", "monitor_id", 5,
                        "confirm_attempts", attempts(3, "100% packet loss"), "team_name", "Takım A"));
        List<String> many = new ArrayList<>();
        for (int i = 1; i <= 9; i++) many.add("10.20.30." + (40 + i));
        alert("alert-dns-changed", "[Site Monitor YÜKSEK] example.com — DNS değişikliği", "YÜKSEK: A kaydı değişti.", "example.com", "HIGH", "DNS_CHANGED", null,
                map("record_type", "A", "changed_at", "2026-09-26T07:00:00", "old_values", many,
                        "new_values", List.of("10.20.40.1", "v=DKIM1; k=rsa; p=" + "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA".repeat(4)),
                        "alert_event_id", 4243L, "team_name", "Takım A"));
        alert("alert-dns-changed-empty-old", "[Site Monitor YÜKSEK] new.example.com — DNS değişikliği", "YÜKSEK: yeni kayıt eklendi.",
                "new.example.com", "HIGH", "DNS_CHANGED", null, map("record_type", "CNAME", "old_values", List.of(), "new_values", List.of("edge.example.net")));
        alert("alert-simple", "[Site Monitor] KRİTİK — Sistem bildirimi",
                "Genel bildirim metni.\nİkinci satır: ayrıntılar burada.\n\n" + TR, null, "CRITICAL", null, null, null);
        // Runbook notu (2026-10-01): izlemenin "Rehber & Notlar" rehberi varsa gövde sonunda "Ne yapılmalı" kartı —
        // tür-özel MailDoc belgesi (içerik doğrulama) ve EmailTemplateBuilder ailesi (HTTP) birer örnek; uzun metin
        // tavana (1000) kırpılmış hâliyle sözleşme/galeri kapılarından geçer.
        String runbook = com.sitemonitor.service.mail.RunbookNote.truncate(
                com.sitemonitor.service.mail.RunbookNote.toPlainText("## Alarm gelince\n1. **Ödeme servisini** kontrol et: `systemctl status odeme`\n"
                        + "2. Yanıt yoksa [runbook sayfası](https://wiki.example.com/odeme) adımlarını uygula <b>(eşik < 5 dk)</b>\n"
                        + "3. Çözülmezse nöbetçi DBA'yı ara — " + TR + "\n\n" + "Ayrıntı: bağlantı havuzu, kuyruk derinliği, son dağıtım. ".repeat(30)),
                com.sitemonitor.service.mail.RunbookNote.EMAIL_MAX);
        alert("alert-keyword-runbook", "[Site Monitor KRİTİK] keyword", "KRİTİK: kelime bulunamıyor.", "https://www.example.com/", "CRITICAL", "KEYWORD", null,
                map("keyword", "Giriş Yap", "operator", "GTE", "match_count", 1, "occurrences", 0, "http_status", 200,
                        "first_failure_at", "2026-09-26T07:00:00", "monitor_id", 42, "team_name", "Takım A", "alert_event_id", 92L,
                        com.sitemonitor.service.mail.RunbookNote.CTX_KEY, runbook));
        alert("alert-http-runbook", "[Site Monitor] KRİTİK · Ödeme portalı · HTTP/Website erişilemez", "KRİTİK: " + LONG_URL + " erişilemiyor (HTTP 503).",
                LONG_URL, "CRITICAL", "HTTP_DOWN", null,
                map("monitor_name", "Ödeme portalı", "http_status", 503, "first_failure_at", "2026-09-26T07:00:00", "monitor_id", 43,
                        "team_name", "Takım A", "alert_event_id", 93L, com.sitemonitor.service.mail.RunbookNote.CTX_KEY, runbook));
    }

    // ── #7–10 Çözüm ailesi ───────────────────────────────────────────────────

    private void resolutions() {
        resolved("resolved-cert", "[Site Monitor ✅ ÇÖZÜLDÜ] www.example.com", "www.example.com", "EXPIRY", "CRITICAL", "admin",
                "2026-09-26T07:15:00", "2026-09-20T22:00:00",
                map("not_after", "2027-09-26T00:00:00", "days_remaining", 365, "issuer_cn", "DigiCert EV RSA CA G2", "alert_event_id", 4242L),
                "Takım A", null);
        resolved("resolved-domain", "[Site Monitor ✅ ÇÖZÜLDÜ] example.com", "example.com", "DOMAINMON_EXPIRY", "INFO", "Sistem (otomatik)",
                "2026-09-26T07:15:00", "2026-09-20T09:00:00",
                map("expiry_date", "2027-10-08T00:00:00", "days_remaining", 377, "registrar", "Örnek Registrar Ltd. Şti.", "source", "RDAP",
                        "status_codes", "clientTransferProhibited, clientDeleteProhibited", "nameservers", "ns1.example.com, ns2.example.com"),
                "Takım A", null);
        resolved("resolved-page", "[Site Monitor ✅ ÇÖZÜLDÜ] www.example.com", "https://www.example.com/", "PAGE_INTEGRITY", "HIGH",
                "Sistem (otomatik)", "2026-09-26T06:42:00", "2026-09-25T16:35:00",
                map("detail", "1 kırık, 1 zaman aşımı, 0 mixed content", "problem_rows",
                        "BROKEN\t" + LONG_URL + "\t404\nTIMEOUT\thttps://cdn.example.com/app.js\t", "problem_total", 2,
                        "resolved_page_status", "OK", "resolved_total_resources", 135, "resolved_checked_at", "2026-09-26T06:42:00"),
                "Takım A", null);
        resolved("resolved-scripted-slow", "[Site Monitor ✅ ÇÖZÜLDÜ] Ödeme akışı", TR, "SCRIPTED_SLOW", "HIGH", "Sistem (otomatik)",
                "2026-09-26T06:42:00", "2026-09-26T05:00:00", map("duration_ms", 8120, "threshold_ms", 5000), "Takım A", null);
        resolved("resolved-accessibility", "[Site Monitor ✅ ÇÖZÜLDÜ] " + LONG_HOST, LONG_HOST, "ACCESSIBILITY", "CRITICAL",
                "Sistem (otomatik)", "2026-09-26T09:14:25", "2026-09-26T07:00:00", map("alert_event_id", 4242L), "Takım A",
                new EmailNotificationService.UptimeSummary(99.95, 1, 99.80, 3));
        resolved("resolved-port", "[Site Monitor ✅ ÇÖZÜLDÜ] db.example.com", "db.example.com", "PORT_DOWN", "CRITICAL", "admin",
                "2026-09-26T07:40:00", "2026-09-26T07:00:00", null, null, null);
        resolved("resolved-dns-changed", "[Site Monitor ✅ ÇÖZÜLDÜ] example.com", "example.com", "DNS_CHANGED", "HIGH", "admin",
                "2026-09-26T09:00:00", "2026-09-26T07:00:00", null, "Takım A", null);
        resolved("resolved-keyword", "[Site Monitor ✅ ÇÖZÜLDÜ] keyword", LONG_URL, "KEYWORD", "CRITICAL", "Sistem (otomatik)",
                "2026-09-26T08:00:00", "2026-09-26T07:00:00",
                map("keyword", "Giriş Yap", "operator", "GTE", "match_count", 1, "occurrences", 0, "monitor_id", 42, "alert_event_id", 91L),
                "Takım A", new EmailNotificationService.UptimeSummary(98.40, 2, 99.61, 2));
        resolved("resolved-ping", "[Site Monitor ✅ ÇÖZÜLDÜ] 10.0.0.7", "10.0.0.7", "PING_DOWN", "CRITICAL", "Sistem (otomatik)",
                "2026-09-26T08:00:00", "2026-09-26T07:59:20", map("ip_version", "v6", "monitor_id", 5), null, null);
    }

    // ── #11–12 Alarm fırtınası ───────────────────────────────────────────────

    private void storms() {
        List<String> targets = new ArrayList<>();
        for (int i = 1; i <= 12; i++) targets.add(i == 1 ? LONG_HOST : "host" + i + ".example.com");
        String html = svc.buildStormAlertHtml(40, "Takım A · Erişilebilirlik", "Ortak alt ağ 10.20.30.0/24 (ağ geçidi yanıt vermiyor)",
                "2026-09-26T07:00:00", targets, 28);
        add("storm-alert", "storm", "[Site Monitor 🌩 ALARM FIRTINASI] 40 monitör", html,
                svc.buildStormAlertText(40, "Takım A · Erişilebilirlik", "Ortak alt ağ", "2026-09-26T07:00:00", targets, 28), "critical");
        // D-c7 (2026-09-29): UYARI seviyeli üyelerin fırtınası — rozet, kutu ve "Seviye" satırı üyelerin en yüksek seviyesi.
        add("storm-alert-warning", "storm", "[Site Monitor 🌩 ALARM FIRTINASI] 5 monitör",
                svc.buildStormAlertHtml(5, "Takım A · Sentetik", "Sentetik Test Başarısız", "2026-09-26T07:00:00",
                        targets.subList(0, 5), 0, "WARNING"),
                svc.buildStormAlertText(5, "Takım A · Sentetik", "Sentetik Test Başarısız", "2026-09-26T07:00:00",
                        targets.subList(0, 5), 0, "WARNING"), "warning");
        List<String> still = List.of("host3.example.com", "host7.example.com");
        String rec = svc.buildStormRecoveryHtml(38, 2, "Takım A · Erişilebilirlik", "2026-09-26T07:00:00", "2026-09-26T07:48:00",
                targets, 26, still);
        add("storm-recovery", "storm", "[Site Monitor ✅ ÇÖZÜLDÜ] Alarm fırtınası sona erdi", rec,
                svc.buildStormRecoveryText(38, 2, "Takım A", "2026-09-26T07:00:00", "2026-09-26T07:48:00", targets, 26, still), "ok");
        String clean = svc.buildStormRecoveryHtml(6, 0, "Takım A", "2026-09-26T07:00:00", "2026-09-26T07:05:00",
                List.of("a.example.com", "b.example.com", "c.example.com", "d.example.com", "e.example.com", "f.example.com"), 0);
        addHtml("storm-recovery-clean", "storm", "[Site Monitor ✅ ÇÖZÜLDÜ] Alarm fırtınası sona erdi", clean, "ok");
    }

    // ── #13–19 Yönetici / güvenlik ───────────────────────────────────────────

    private void admin() {
        addHtml("admin-password-reset", "admin", "[Site Monitor] Şifreniz sıfırlandı",
                svc.buildPasswordResetHtml("kullanici.a", "Kullanıcı Ağaoğlu", "Xk7#pQ2!mZ9w"), "ok");
        addHtml("admin-new-device", "admin", "[Site Monitor] Yeni cihazdan giriş",
                svc.buildNewDeviceHtml("Kullanıcı Ağaoğlu", LONG_UA, "10.20.30.40", "İstanbul, Türkiye", "26.09.2026 10:00"), "ok");
        addHtml("admin-deploy-upgrade", "admin", "[Site Monitor] 🚀 Yükseltme", svc.buildDeploymentNoticeHtml(
                new EmailNotificationService.DeploymentNotice("UPGRADE", "prod", "20.86.0", "20.87.0", "0123abcd", "2026-09-26T06:30:00Z",
                        List.of("feat(mail): tüm e-postalar mobil uyumlu ve yeni tasarımda",
                                "fix(page): zaman aşımı yeniden deneme davranışı düzeltildi", "docs: Grafana anotasyon rehberi"), false)), "ok");
        addHtml("admin-deploy-rollback", "admin", "[Site Monitor] ⏪ Geri alma", svc.buildDeploymentNoticeHtml(
                new EmailNotificationService.DeploymentNotice("ROLLBACK", "prod", "20.87.0", "20.86.0", "fedc4321", "2026-09-26T07:10:00Z",
                        List.of(), true)), "critical");
        addHtml("admin-network-alert", "admin", "[Site Monitor] ⚠ Ağ Erişim Sorunu",
                svc.buildAdminNetworkAlertHtml("2026-09-26T07:00:00", 184, 212, 0.868, 0.5), "critical");
        addHtml("admin-network-resolved", "admin", "[Site Monitor] ✅ Ağ Erişim Sorunu Çözüldü",
                svc.buildAdminNetworkResolvedHtml("2026-09-26T07:00:00", "2026-09-26T07:24:10", 1_450_000L, 184, 212, 0.868), "ok");
        FailedLoginAnomalyService.AnomalyReport report = new FailedLoginAnomalyService.AnomalyReport(
                "2026-09-26T07:00:00", "2026-09-26T07:10:00", 10, 147, 3.4,
                List.of(new FailedLoginAnomalyService.RuleHit("GLOBAL_VOLUME", 147, 20, "147 başarısız login / 10 dk"),
                        new FailedLoginAnomalyService.RuleHit("ACCOUNT_TARGETED", 42, 5, "'kullanici.a' hesabına 42 deneme"),
                        new FailedLoginAnomalyService.RuleHit("IP_CREDENTIAL_STUFFING", 31, 10, "10.20.30.40 → 31 farklı kullanıcı")),
                List.of(new FailedLoginAnomalyService.KV("kullanici.a", 42), new FailedLoginAnomalyService.KV("kullanici.b", 17)),
                List.of(new FailedLoginAnomalyService.KV("10.20.30.40", 96), new FailedLoginAnomalyService.KV("2001:db8:85a3::8a2e:370:7334", 22)),
                List.of(new FailedLoginAnomalyService.KV("10.20.30.40", 31)), List.of(),
                new LinkedHashMap<>(Map.of("BAD_PASSWORD", 110L, "UNKNOWN_USER", 37L)));
        addHtml("admin-login-anomaly", "admin", "[Site Monitor] ⚠ Anomali", svc.buildLoginAnomalyHtml(report, "ESCALATION"), "critical");
        addHtml("admin-login-anomaly-resolved", "admin", "[Site Monitor] ✅ Login anomalisi normale döndü",
                svc.buildLoginAnomalyResolvedHtml("2026-09-26T07:00:00", "2026-09-26T07:40:00", 147), "ok");
    }

    // ── #20–25 Sorun bildirimleri ────────────────────────────────────────────

    private void issues() {
        List<EmailNotificationService.InlineImage> shots = List.of(
                new EmailNotificationService.InlineImage("shot0", new byte[]{1}, "image/png"),
                new EmailNotificationService.InlineImage("shot1", new byte[]{1}, "image/png"));
        addHtml("issue-login-report", "issue", "[Site Monitor] 🛟 Giriş Sorunu Bildirimi",
                svc.buildLoginIssueHtml("LIR-2026-000042", "kullanici.a", "kullanici.a@example.com",
                        "HTTP 423 Locked — hesap geçici olarak kilitlendi (5 başarısız deneme)", "Şifremi doğru girdiğim hâlde giriş yapamıyorum.\nDün akşamdan beri böyle.",
                        shots, "10.20.30.40", LONG_UA, "2026-09-26T07:00:00", false), "ok");
        addHtml("issue-login-ack", "issue", "[Site Monitor] Sorun bildiriminiz alındı",
                svc.buildLoginIssueHtml("LIR-2026-000042", "kullanici.a", "kullanici.a@example.com", "HTTP 423 Locked",
                        "Şifremi doğru girdiğim hâlde giriş yapamıyorum.", shots, null, null, "2026-09-26T07:00:00", true), "ok");
        // Kimliksiz login "sorun bildir" onayı — NÖTR (2026-10-08): kullanıcı girdisi/görsel yok.
        addHtml("issue-login-ack-neutral", "issue", "[Site Monitor] Sorun bildiriminiz alındı",
                svc.buildLoginIssueAckNeutralHtml("LIR-2026-000042", "2026-09-26T07:00:00"), "ok");
        addHtml("issue-client-error", "issue", "[Site Monitor] 🐞 Uygulama Hatası",
                svc.buildClientErrorHtml("CER-2026-000007", "kullanici.a",
                        "TypeError: Cannot read properties of undefined (reading 'map')\n    at CertificateTable (https://sitemonitor.example.com/assets/index-8f3a9c2d.js:1:48213)\n"
                        + "    at renderWithHooks (https://sitemonitor.example.com/assets/vendor-react-1a2b3c.js:12:2211)",
                        "Sekme: Sertifika Envanteri · Tema: açık · Dil: tr", "10.20.30.40", LONG_UA, "2026-09-26T07:00:00"), "ok");
        addHtml("issue-user-report", "issue", "[Site Monitor] 📝 Sorun Bildirimi",
                svc.buildUserIssueHtml("USR-2026-000015", "kullanici.a", "kullanici.a@example.com", "BLOCKER",
                        "Rapor dışa aktarımı PDF'te Türkçe karakterleri (ğüşıöç) bozuk gösteriyor.\nEk ekran görüntülerine bakabilirsiniz.",
                        "Error: export failed (500)", "CER-2026-000007", "reports", "20.87.0", shots, "10.20.30.40", LONG_UA,
                        "2026-09-26T07:00:00"), "ok");
        List<Map<String, String>> items = new ArrayList<>();
        for (int i = 1; i <= 5; i++) items.add(Map.of("refCode", "USR-2026-00001" + i, "username", "kullanici." + (char) ('a' + i),
                "summary", i == 3 ? "Çok uzun bir özet: " + TR + " — rapor ekranında filtreler sıfırlanıyor ve sayfa yeniden yükleniyor" : "Kısa özet " + i));
        addHtml("issue-digest", "issue", "[Site Monitor] 📝 Sorun Bildirimleri Özeti", svc.buildIssueDigestHtml(items, "25.09.2026 10:00 – 26.09.2026 10:00"), "ok");
        addHtml("issue-login-resolved", "issue", "[Site Monitor] ✅ Giriş sorunu çözümlendi",
                svc.buildLoginIssueResolvedHtml("LIR-2026-000042", "kullanici.a", "kullanici.a@example.com", "HTTP 423 Locked",
                        "Şifremi doğru girdiğim hâlde giriş yapamıyorum.", "2026-09-26T07:00:00",
                        "Hesap kilidi kaldırıldı; parola politikası gereği yeni parola belirlemeniz gerekiyor.", "2026-09-26T09:30:00", shots), "ok");
        // Konuşma dizisi (2026-09-26): yönetici yanıtı + durum geçişi — kısa mailler, "Bildirimi aç" derin bağlantısıyla.
        addHtml("issue-admin-reply", "issue", "[Site Monitor] Bildiriminize yanıt verildi",
                svc.buildIssueReplyHtml(42L, "LIR-2026-000042", "kullanici.a", "IN_PROGRESS",
                        "Merhaba, kaydınızı inceledik. Hesabınızın kilidi kaldırıldı; lütfen bir kez daha deneyip sonucu buradan yazar mısınız?\n\nTeşekkürler.",
                        "2026-09-26T08:15:00", "Şifremi doğru girdiğim hâlde giriş yapamıyorum.\nDün akşamdan beri böyle."), "ok");
        addHtml("issue-status-in-progress", "issue", "[Site Monitor] Bildiriminizin durumu güncellendi",
                svc.buildIssueStatusHtml(42L, "LIR-2026-000042", "kullanici.a", "IN_PROGRESS",
                        "Kullanıcıyla iletişime geçildi; AD tarafında kilit kaydı inceleniyor.",
                        "2026-09-26T08:10:00", TR + " — " + LONG_URL), "ok");
    }

    // ── #29–30 Hatırlatmalar ─────────────────────────────────────────────────

    private void reminders() {
        addHtml("reminder-weekly", "reminder", "[Site Monitor] Takım A — Haftalık rapor hatırlatması",
                svc.buildWeeklyReportReminderHtml("Takım A", "2026-W39 (22–26 Eylül 2026)", BASE + "/?tab=weeklyreports", "bugün saat 15:00"), "ok");
        addHtml("reminder-domain", "reminder", "[Site Monitor] example.com — alan adı bitişine 12 gün",
                svc.buildDomainExpiryReminderHtml("Kurumsal Web Sitesi", "example.com", 12, "2026-10-08T00:00:00Z", 14,
                        "Örnek Registrar Ltd. Şti.", "WARNING", 17L), "ok");
        addHtml("reminder-domain-expired", "reminder", "[Site Monitor] " + LONG_HOST + " — alan adı kaydı doldu",
                svc.buildDomainExpiryReminderHtml(TR, LONG_HOST, -3, "2026-09-23T00:00:00Z", 0, null, "CRITICAL", 18L), "critical");
        // Sessiz saat özeti (2026-10-01, onaylı öneri 15): açık + pencerede çözülmüş karışık; uzun ad/hedef ve tümü çözülmüş.
        List<EmailNotificationService.QuietDigestRow> mixed = List.of(
                new EmailNotificationService.QuietDigestRow(41L, "WARNING", "Kurumsal Web Sitesi", "Erişim Kesintisi",
                        "2026-09-30T20:12:00", null, false),
                new EmailNotificationService.QuietDigestRow(42L, "HIGH", LONG_HOST, "Port Kesintisi",
                        "2026-09-30T22:40:00", null, false),
                new EmailNotificationService.QuietDigestRow(43L, "WARNING", "ödeme-api.example.com/health", "HTTP Yavaş Yanıt",
                        "2026-09-30T23:05:00", "2026-09-30T23:41:00", true));
        addHtml("reminder-quiet-digest", "reminder", EmailNotificationService.quietDigestSubject("Takım A", mixed),
                svc.buildQuietDigestHtml("Takım A", "30.09.2026 22:00–07:00", mixed), "ok");
        List<EmailNotificationService.QuietDigestRow> allResolved = List.of(
                new EmailNotificationService.QuietDigestRow(44L, "WARNING", TR, "Sertifika Süre Bitişi",
                        "2026-09-30T19:30:00", "2026-09-30T21:10:00", true));
        addHtml("reminder-quiet-digest-resolved", "reminder", EmailNotificationService.quietDigestSubject(TR, allResolved),
                svc.buildQuietDigestHtml(TR, "30.09.2026 22:00–07:00", allResolved), "ok");
    }

    // ── #33 Olay & Hata bildirimi ────────────────────────────────────────────

    private void incidents() {
        Map<String, Object> inc = map("title", "Ödeme servisi kesintisi — kart işlemleri reddediliyor", "team_name", "Takım A",
                "severity", "CRITICAL", "status", "INVESTIGATING", "channel", "Mobil · İnternet", "service", LONG_HOST,
                "category", "Altyapı", "occurred_at", "2026-09-26T06:40:00", "detected_at", "2026-09-26T06:45:00",
                "sla_breached", true, "error_budget_burn_pct", 38,
                "rca_summary", "Yük dengeleyicideki **sertifika zinciri** eksik dağıtıldı.\n\n| Bileşen | Durum |\n| --- | --- |\n| LB-1 | Hatalı |\n| LB-2 | Sağlıklı |",
                "description", "- İstemci TLS el sıkışması `unknown_ca` ile düştü\n- [ ] Ara sertifika eklenecek\n- [x] Trafik LB-2'ye alındı\n\n![Grafik](/api/incidents/images/9)",
                "business_impact", "Kart ile ödeme adımında işlemlerin yaklaşık %35'i reddedildi.",
                "resolution_steps", "1. Trafik sağlıklı düğüme yönlendirildi.\n2. Zincir yeniden dağıtılacak.");
        addHtml("incident-new", "incident", "[Site Monitor] Yeni Olay",
                svc.buildIncidentNotificationHtml(inc, "Müdür Bey", "NEW", BASE + "/?tab=incident-history&incident=12"), "ok");
        Map<String, Object> res = new LinkedHashMap<>(inc);
        res.put("status", "RESOLVED");
        res.put("resolved_at", "2026-09-26T08:10:00");
        res.put("duration_minutes", 90);
        addHtml("incident-resolved", "incident", "[Site Monitor] Olay Çözüldü",
                svc.buildIncidentNotificationHtml(res, "Müdür Bey", "RESOLVED", BASE + "/?tab=incident-history&incident=12"), "ok");
        Map<String, Object> upd = map("title", "Raporlama gecikmesi", "team_name", "Takım A", "severity", "LOW", "status", "MITIGATED",
                "occurred_at", "2026-09-26T06:40:00");
        addHtml("incident-updated", "incident", "[Site Monitor] Olay Güncellendi",
                svc.buildIncidentNotificationHtml(upd, null, "UPDATED", BASE + "/?tab=incident-history&incident=13"), "ok");
    }

    // ── #26–28 Haftalık rapor (PO onayı / müdür / iade) + onaya sunuldu ──────

    static final String WR_CONTENT = """
        {"version":1,
         "item1":{"total":12,"urgent":2,"high":3,"medium":4,"low":3,"status_counts":{"working":7,"planned":2,"on_hold":1,"done":2},"tracking_url":"https://jira.example.com/x",
                  "notes_md":"| Kayıt | Durum |\\n| --- | --- |\\n| SSL yenileme | Tamamlandı |\\n| DNS temizliği | Devam ediyor |"},
         "item2":{"open_incidents":0,"problem_records":0,"postmortems":0,"incidents_url":"https://jira.example.com/inc","notes_md":""},
         "item3":{"notes_md":"- Çalışma A — tamamlandı\\n- [x] Çalışma B\\n- [ ] Çalışma C — devam ediyor\\n\\n```\\nkubectl rollout status deploy/site-monitor\\n```"},
         "item4":{"channels":[{"id":"c-1","name":"İnternet Şubesi","notes_md":"![Grafik](/api/weekly-reports/images/5)"},
                              {"id":"c-2","name":"Mobil","notes_md":"Kritik iş yok."}]}}
        """;

    private void weekly() {
        Map<String, Object> kpi = map("score", 82, "score_band", "amber",
                "manager_text", "Bu hafta 2 sertifika 7 gün içinde doluyor; kesinti süresi geçen haftaya göre %40 azaldı.",
                "actions", List.of(map("name", LONG_HOST, "type", "cert", "tier", 1, "days_left", 5),
                        map("name", "example.com", "type", "domain", "tier", 2, "days_left", 12)),
                "lookahead", List.of(map("name", "api.example.com", "type", "cert", "tier", 3, "days_left", 24)),
                "total_certs", 212, "expiring", 3, "alarms", 14, "critical", 2, "uptime_pct", 99.87,
                "monitoring", List.of(map("type", "http", "active", 40, "success_pct", 99.9, "alarms", 2),
                        map("type", "scripted", "active", 6, "success_pct", 97.5, "alarms", 3), map("type", "ping", "active", 0)),
                "domain_protection", map("total", 18, "listed", 0, "unlocked", 2, "lock_unverified", 1));
        addHtml("weekly-report-po", "weekly", "[Site Monitor] Takım A — raporu onayınızı bekliyor",
                svc.buildWeeklyReportHtml("Takım A", "2026-W39 (22–26 Eylül 2026)", "PO Kişi", WR_CONTENT, true,
                        Map.of(5L, 1600), null, null, null, BASE + "/api/weekly-reports/approve-link?token=ABC123", kpi), "ok");
        addHtml("weekly-report-manager", "weekly", "[Site Monitor] [Takım A] Haftalık Rapor",
                svc.buildWeeklyReportHtml("Takım A", "2026-W39 (22–26 Eylül 2026)", "Müdür Bey", WR_CONTENT, true, Map.of(5L, 480),
                        "PO Kişi", "2026-09-26T09:00:00", "2026-09-26T09:05:00", null, kpi), "ok");
        addHtml("weekly-report-rejected", "weekly", "[Site Monitor] Takım A — raporu iade edildi",
                svc.buildWeeklyReportRejectedHtml("Takım A", "2026-W39", "Madde 2 eksik.\nPostmortem bağlantısını ekleyiniz.\n- Olay A\n- Olay B", "PO Kişi"), "ok");
        addHtml("weekly-report-submitted", "weekly", "[Site Monitor] Takım A — raporu onayınızı bekliyor",
                svc.buildWeeklyReportSubmittedHtml("Takım A", "2026-W39", "Ekip Üyesi", BASE + "/api/weekly-reports/approve-link?token=ABC123"), "ok");
    }

    // ── #31–32 Raporlar ──────────────────────────────────────────────────────

    private void reports() {
        // Haftalık Erişilebilirlik (2026-09-28 yeniden tasarım): olağan / %100 / kötü / büyük takım / veri yok —
        // alarm tarafı GERÇEK eşleyiciden geçer (WeeklyAvailabilitySamples).
        for (WeeklyAvailabilitySamples.Case c : WeeklyAvailabilitySamples.all()) {
            addHtml(c.slug(), "report", "[Site Monitor] Takım A — Haftalık Erişilebilirlik", c.html(svc), "ok");
        }
        // Aylık envanter (2026-09-28 yeniden tasarım): veri GERÇEK özetleyiciden geçer (CertInventorySamples).
        addHtml("report-inventory", "report", "[Site Monitor] Aylık Sertifika Envanteri",
                svc.buildCertInventoryReportHtml(com.sitemonitor.service.report.CertInventorySamples.large()), "ok");
        addHtml("report-inventory-mid", "report", "[Site Monitor] Aylık Sertifika Envanteri",
                svc.buildCertInventoryReportHtml(com.sitemonitor.service.report.CertInventorySamples.medium()), "ok");
        addHtml("report-inventory-clean", "report", "[Site Monitor] Aylık Sertifika Envanteri",
                svc.buildCertInventoryReportHtml(com.sitemonitor.service.report.CertInventorySamples.quiet()), "ok");
        addHtml("report-inventory-empty", "report", "[Site Monitor] Aylık Sertifika Envanteri",
                svc.buildCertInventoryReportHtml(com.sitemonitor.service.report.CertInventorySamples.empty()), "ok");
    }
}
