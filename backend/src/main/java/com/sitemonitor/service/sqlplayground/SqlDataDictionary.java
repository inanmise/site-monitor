package com.sitemonitor.service.sqlplayground;

import com.sitemonitor.util.Msg;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL Playground veri sözlüğü (2026-11-13, D3): uygulamanın ana tablolarının ve önemli kolonlarının ne olduğu, TR / EN
 * ({@link Msg#t} — istek dilinde). Hassas kolonların açıklamasına "maskelenir" eklenir ({@link SqlSensitiveColumns}).
 *
 * <p>Kapı: {@code SqlDataDictionaryTest} — sözlükteki her tablo / kolon varlık şemasında gerçekten var (yanlış ad bırakılmaz).
 * Yeni tablo eklerken buraya eklemek isteğe bağlıdır; eklenen her ad ise doğru olmalıdır.
 */
public final class SqlDataDictionary {

    private SqlDataDictionary() {}

    record Col(String name, String tr, String en) {}

    record Tbl(String name, String tr, String en, List<Col> cols) {}

    private static Col c(String name, String tr, String en) { return new Col(name, tr, en); }

    private static Tbl t(String name, String tr, String en, Col... cols) { return new Tbl(name, tr, en, List.of(cols)); }

    private static final Col ID = c("id", "Kayıt kimliği.", "Record id.");
    private static final Col TEAM = c("team_id", "Sahip takımın kimliği (teams.id).", "Owning team id (teams.id).");
    private static final Col ACTIVE = c("active", "false = duraklatılmış (izlenmiyor, kayıt duruyor).", "false = paused (not monitored, record kept).");
    private static final Col MONITOR = c("monitor_id", "Ait olduğu izlemenin kimliği.", "Id of the monitor it belongs to.");
    private static final Col CHECKED = c("checked_at", "Kontrol anı (UTC, ISO metin).", "Check time (UTC, ISO text).");
    private static final Col INTERVAL = c("interval_seconds", "Kontrol aralığı (saniye).", "Check interval (seconds).");
    private static final Col GROUP = c("group_name", "İzleme grubu adı (serbest metin).", "Monitor group name (free text).");

    static final List<Tbl> TABLES = List.of(
        t("certificate_inventory", "Sertifika / alan adı envanteri — izlenen her uç nokta bir satır.",
                "Certificate / domain inventory — one row per monitored endpoint.",
                ID, c("domain", "Alan adı (manuel kayıtta izleme anahtarı).", "Domain (tracking key for a manual record)."),
                c("port", "Kontrol edilen TLS portu.", "The TLS port checked."),
                c("tier", "Katman: 1 müşteri üretimi, 2 iç üretim, 3 UAT, 4 geliştirme; boş = sınıflanmamış.",
                        "Tier: 1 customer production, 2 internal production, 3 UAT, 4 development; empty = unclassified."),
                TEAM, c("ug_team_id", "İkinci (uygulama geliştirme) takımı.", "Secondary (application development) team."),
                ACTIVE, c("use_proxy", "true = bu alan adı kurumsal proxy üzerinden kontrol edilir.", "true = this domain is checked through the corporate proxy."),
                c("cert_source", "MANUAL = yüklenen sertifika dosyası; boş = ağdan okunan.", "MANUAL = uploaded certificate file; empty = read from the network."),
                c("deleted_at", "Eski çöp kutusu damgası (artık yazılmaz; silme kalıcıdır).", "Legacy recycle-bin stamp (no longer written; deletes are permanent)."),
                c("created_at", "Oluşturulma anı (UTC).", "Creation time (UTC).")),
        t("latest_checks", "Her alan adının EN SON sertifika kontrolü (tek satır).", "The LATEST certificate check of each domain (one row).",
                c("domain", "Alan adı.", "Domain."), c("status", "valid / expiring / expired / error.", "valid / expiring / expired / error."),
                c("days_remaining", "Sertifikanın bitişine kalan gün (negatif = dolmuş).", "Days until the certificate expires (negative = expired)."),
                c("not_after", "Sertifika bitiş anı.", "Certificate expiry time."), c("issuer", "Veren CA.", "Issuing CA."),
                c("chain_status", "Zincir durumu.", "Chain status."), c("trust_status", "Güven durumu (güven deposuna göre).", "Trust status (against the trust store)."),
                c("tls_version", "Anlaşılan TLS sürümü.", "Negotiated TLS version."), c("fingerprint", "Sertifikanın SHA-256 parmak izi.", "SHA-256 fingerprint of the certificate."),
                CHECKED),
        t("certificate_checks", "Sertifika kontrol geçmişi (ham seri).", "Certificate check history (raw series).",
                ID, c("domain", "Alan adı.", "Domain."), c("status", "Kontrol sonucu.", "Check result."),
                c("days_remaining", "Kalan gün.", "Days remaining."), c("error_class", "Hata sınıfı (NETWORK / DNS / SSL …).", "Error class (NETWORK / DNS / SSL …)."),
                c("run_id", "Tarama koşusunun kimliği.", "Id of the sweep run."), CHECKED),
        t("alert_events", "Alarmlar — açık ve kapanmış.", "Alerts — open and resolved.",
                ID, c("domain", "Alarm anahtarı (hedef: alan adı / URL / izleme adı).", "Alert key (target: domain / URL / monitor name)."),
                c("alert_type", "Alarm türü (SSL_EXPIRY, PORT_DOWN, HTTP_DOWN …).", "Alert type (SSL_EXPIRY, PORT_DOWN, HTTP_DOWN …)."),
                c("alert_level", "Seviye: CRITICAL / HIGH / WARNING.", "Level: CRITICAL / HIGH / WARNING."),
                c("resolved", "true = kapandı.", "true = resolved."), c("acknowledged", "true = biri sahiplendi.", "true = someone acknowledged it."),
                TEAM, c("storm_id", "Bağlı olduğu alarm fırtınası.", "Alert storm it belongs to."),
                c("created_at", "Açılış anı (UTC).", "Opening time (UTC)."), c("resolved_at", "Kapanış anı (UTC).", "Resolution time (UTC)."),
                c("resolved_by", "Kapatan kişi ya da sistem nedeni.", "Who or what system reason resolved it.")),
        t("notification_logs", "Her bildirim kararı (e-posta, webhook) — gönderildi, atlandı ya da hata.",
                "Every notification decision (e-mail, webhook) — sent, skipped or failed.",
                ID, c("alert_event_id", "İlgili alarm (0 = alarmsız bildirim).", "Related alert (0 = notification without an alert)."),
                c("sent_at", "Karar anı (UTC).", "Decision time (UTC)."), c("recipient_email", "Alıcılar (255'e sığdırılmış).", "Recipients (fitted to 255)."),
                c("subject", "Konu.", "Subject."), c("email_status", "SENT / FAILED: … / SKIPPED: … / QUEUED_RETRY…", "SENT / FAILED: … / SKIPPED: … / QUEUED_RETRY…"),
                c("webhook_status", "Teams / Slack webhook sonucu.", "Teams / Slack webhook result.")),
        t("user_push_deliveries", "Kişisel push bildirimleri — kişi başına satır.", "Personal push notifications — one row per person.",
                ID, c("alert_event_id", "İlgili alarm.", "Related alert."), c("username", "Alıcı kullanıcı adı.", "Recipient user name."),
                c("status", "PENDING / SENT / FAILED / SKIPPED_…", "PENDING / SENT / FAILED / SKIPPED_…"),
                c("created_at", "Oluşturulma anı (UTC).", "Creation time (UTC)."), c("sent_at", "Gönderim anı.", "Send time.")),
        t("app_users", "Kullanıcı hesapları.", "User accounts.",
                ID, c("username", "Kullanıcı adı.", "User name."), c("display_name", "Görünen ad.", "Display name."),
                c("email", "E-posta adresi.", "E-mail address."), c("system_role", "USER / AUDIT / ADMIN.", "USER / AUDIT / ADMIN."),
                c("team_id", "Birincil takım.", "Primary team."), c("active", "false = pasif (giriş yapamaz).", "false = passive (can't sign in)."),
                c("password_hash", "Yerel parolanın BCrypt özeti.", "BCrypt hash of the local password."),
                c("active_session_id", "Tek oturum kaydı (oturum kimliği).", "Single-session registry (session id)."),
                c("last_login_at", "Son başarılı giriş (UTC).", "Last successful sign-in (UTC).")),
        t("teams", "Takımlar.", "Teams.",
                ID, c("name", "Takım adı.", "Team name."), c("email", "Takım e-posta adresi (alarm alıcısı).", "Team e-mail address (alert recipient)."),
                c("active", "false = pasif takım.", "false = passive team."), c("leader_id", "Takım lideri (app_users.id).", "Team leader (app_users.id)."),
                c("manager_id", "Müdür (app_users.id).", "Manager (app_users.id).")),
        t("escalation_contacts", "Eskalasyon kişileri — HIGH / CRITICAL alarmlarda takıma ek alıcılar.",
                "Escalation contacts — extra recipients for HIGH / CRITICAL alerts.",
                ID, TEAM, c("name", "Kişi adı.", "Contact name."), c("email", "E-posta.", "E-mail."),
                c("min_alert_level", "Bu kişiye giden en düşük seviye.", "Lowest level sent to this contact."), ACTIVE,
                c("delay_minutes", "Zamanlı eskalasyon adımı (dk); boş = hemen.", "Timed escalation step (min); empty = immediately.")),
        t("oncall_schedules", "Nöbet çizelgeleri — takım başına bir satır.", "On-call rotations — one row per team.",
                ID, TEAM, c("enabled", "Çizelge açık mı.", "Is the rotation enabled."),
                c("rotation", "DAILY / WEEKLY.", "DAILY / WEEKLY."), c("notify_oncall", "Nöbetçi HIGH / CRITICAL alarmda bildirilir mi.", "Is the on-call person notified of HIGH / CRITICAL alerts.")),
        t("port_monitors", "Port izlemeleri.", "Port monitors.",
                ID, c("name", "İzleme adı.", "Monitor name."), c("host", "Hedef ana makine.", "Target host."), c("port", "Hedef port.", "Target port."),
                TEAM, ACTIVE, INTERVAL, GROUP),
        t("port_checks", "Port kontrol geçmişi.", "Port check history.",
                ID, MONITOR, c("open", "Bağlantı kuruldu mu.", "Did the connection succeed."), c("response_ms", "Bağlantı süresi (ms).", "Connect time (ms)."),
                c("failure_reason", "Hata nedeni kodu.", "Failure reason code."), CHECKED),
        t("ping_monitors", "Ping izlemeleri.", "Ping monitors.", ID, c("name", "İzleme adı.", "Monitor name."), c("host", "Hedef.", "Target."), TEAM, ACTIVE, INTERVAL),
        t("ping_checks", "Ping kontrol geçmişi.", "Ping check history.",
                ID, MONITOR, c("up", "Yanıt alındı mı.", "Was there a reply."), c("rtt_ms", "Gidiş-dönüş süresi (ms).", "Round-trip time (ms)."),
                c("packet_loss", "Paket kaybı (%).", "Packet loss (%)."), CHECKED),
        t("http_monitors", "HTTP izlemeleri.", "HTTP monitors.",
                ID, c("name", "İzleme adı.", "Monitor name."), c("url", "Hedef adres.", "Target URL."), c("method", "HTTP yöntemi.", "HTTP method."),
                TEAM, ACTIVE, INTERVAL,
                c("custom_headers_enc", "Şifreli özel istek başlıkları.", "Encrypted custom request headers."),
                c("basic_auth_pass_enc", "Şifreli temel kimlik doğrulama parolası.", "Encrypted basic auth password.")),
        t("http_checks", "HTTP kontrol geçmişi.", "HTTP check history.",
                ID, MONITOR, c("ok", "Başarılı mı.", "Successful."), c("http_status", "HTTP durum kodu.", "HTTP status code."),
                c("response_ms", "Yanıt süresi (ms).", "Response time (ms)."), CHECKED),
        t("keyword_monitors", "Anahtar kelime izlemeleri.", "Keyword monitors.",
                ID, c("name", "İzleme adı.", "Monitor name."), c("url", "Hedef adres.", "Target URL."), c("keyword", "Aranan kelime.", "Keyword searched."),
                TEAM, ACTIVE, INTERVAL),
        t("keyword_results", "Anahtar kelime kontrol geçmişi.", "Keyword check history.",
                ID, MONITOR, c("ok", "Koşul sağlandı mı.", "Was the condition met."), c("found", "Kelime bulundu mu.", "Was the keyword found."),
                c("occurrences", "Geçiş sayısı.", "Number of occurrences."), CHECKED),
        t("page_monitors", "Sayfa bütünlüğü izlemeleri.", "Page integrity monitors.",
                ID, c("name", "İzleme adı.", "Monitor name."), c("url", "Hedef sayfa.", "Target page."), TEAM, ACTIVE, INTERVAL),
        t("page_checks", "Sayfa kontrol geçmişi.", "Page check history.",
                ID, MONITOR, c("ok", "Başarılı mı.", "Successful."), c("broken_resources", "Kırık kaynak sayısı.", "Number of broken resources."), CHECKED),
        t("scripted_monitors", "Sentetik (k6) izlemeler.", "Synthetic (k6) monitors.",
                ID, c("name", "İzleme adı.", "Monitor name."), c("script", "k6 betiği.", "k6 script."),
                c("env_json", "Ortam değişkenleri (gizli değerler şifreli).", "Environment variables (secret values encrypted)."),
                TEAM, ACTIVE, INTERVAL),
        t("scripted_checks", "Sentetik koşu geçmişi.", "Synthetic run history.",
                ID, MONITOR, c("status", "PASS / FAIL / ERROR / TIMEOUT / SKIPPED.", "PASS / FAIL / ERROR / TIMEOUT / SKIPPED."),
                c("duration_ms", "Koşu süresi (ms).", "Run duration (ms)."), c("k6_version", "Koşan k6 sürümü.", "k6 version that ran."), CHECKED),
        t("domain_monitors", "Alan adı kayıt izlemeleri.", "Domain registration monitors.",
                ID, c("domain", "Alan adı.", "Domain."), TEAM, ACTIVE,
                c("warning_days", "Uyarı eşiği (gün).", "Warning threshold (days)."), c("critical_days", "Kritik eşik (gün).", "Critical threshold (days).")),
        t("domain_checks", "Alan adı kayıt kontrol geçmişi.", "Domain registration check history.",
                ID, MONITOR, c("days_remaining", "Kayıt bitişine kalan gün.", "Days until registration expiry."),
                c("expiry_date", "Kayıt bitiş tarihi.", "Registration expiry date."), c("registrar", "Kayıt kuruluşu.", "Registrar."), CHECKED),
        t("dns_monitors", "DNS izlemeleri.", "DNS monitors.",
                ID, c("domain", "Sorgulanan ad.", "Name queried."), c("record_type", "Kayıt türü (A, AAAA, MX …).", "Record type (A, AAAA, MX …)."), TEAM, ACTIVE),
        t("dns_records", "DNS kontrol geçmişi.", "DNS check history.",
                ID, MONITOR, c("value", "Çözülen değer.", "Resolved value."), c("changed", "Önceki kontrole göre değişti mi.", "Changed since the previous check."), CHECKED),
        t("uptime_checks", "Erişilebilirlik kontrol geçmişi (envanter uç noktaları).", "Accessibility check history (inventory endpoints).",
                ID, c("domain", "Alan adı.", "Domain."), c("port", "Port.", "Port."), c("status", "Sonuç.", "Result."),
                c("response_ms", "Yanıt süresi (ms).", "Response time (ms)."), CHECKED),
        t("tls_grade_status", "Envanter kaydı başına son TLS yapılandırma notu.", "Latest TLS configuration grade per inventory record.",
                c("inventory_id", "Envanter kaydı (certificate_inventory.id).", "Inventory record (certificate_inventory.id)."),
                c("domain", "Alan adı.", "Domain."), c("grade", "Not (A+ … F).", "Grade (A+ … F)."), c("evaluated_at", "Değerlendirme anı.", "Evaluation time.")),
        t("audit_log", "Denetim kaydı — kim, ne zaman, ne yaptı.", "Audit log — who did what, when.",
                ID, c("event_type", "Olay türü (LOGIN, SQL_EXECUTE …).", "Event type (LOGIN, SQL_EXECUTE …)."),
                c("event_time", "Olay anı (UTC).", "Event time (UTC)."), c("actor", "İşlemi yapan kullanıcı adı.", "User name of the actor."),
                c("ip_address", "İstemci IP adresi.", "Client IP address."), c("outcome", "SUCCESS / FAILURE / BLOCKED.", "SUCCESS / FAILURE / BLOCKED."),
                c("session_id", "İşlemin oturum kimliği.", "Session id of the action."), c("detail", "Ayrıntı (JSON).", "Detail (JSON).")),
        t("remember_me_tokens", "\"Beni hatırla\" belirteçleri.", "\"Remember me\" tokens.",
                ID, c("token", "Belirtecin özeti.", "Hash of the token."), c("username", "Sahibi.", "Owner."),
                c("expires_at", "Bitiş (epoch saniye).", "Expiry (epoch seconds).")),
        t("app_settings", "Çalışma zamanı ayarları (anahtar / değer).", "Runtime settings (key / value).",
                ID, c("setting_key", "Ayar anahtarı.", "Setting key."),
                c("setting_value", "Değer (şifreli değerler maskelenir).", "Value (encrypted values are masked)."),
                c("updated_at", "Son değişiklik (UTC).", "Last change (UTC)."), c("updated_by", "Değiştiren.", "Changed by.")),
        t("deployment_history", "Pod açılışları — sürüm ve dağıtım geçmişi.", "Pod starts — version and deployment history.",
                ID, c("started_at", "Açılış anı.", "Start time."), c("version", "Uygulama sürümü.", "Application version."),
                c("environment", "Ortam.", "Environment."), c("pod_name", "Pod adı.", "Pod name."), c("end_reason", "Kapanış nedeni.", "End reason.")),
        t("sql_query_history", "SQL Playground çalıştırma geçmişi.", "SQL Playground run history.",
                ID, c("executed_by", "Çalıştıran kullanıcı adı.", "User name who ran it."), c("sql_text", "Sorgu metni (parametre değerleri yok).", "Query text (no parameter values)."),
                c("row_count", "Dönen satır.", "Rows returned."), c("duration_ms", "Süre (ms).", "Duration (ms)."),
                c("success", "Başarılı mı.", "Successful."), c("executed_at", "Çalıştırma anı (UTC).", "Run time (UTC)."),
                c("cancelled", "Kullanıcı durdurdu mu.", "Stopped by the user.")),
        t("sql_saved_queries", "SQL Playground kayıtlı sorguları.", "SQL Playground saved queries.",
                ID, c("owner_username", "Sahibi.", "Owner."), c("title", "Başlık.", "Title."), c("sql_text", "Sorgu metni.", "Query text."),
                c("shared", "Diğer yöneticilerle paylaşıldı mı.", "Shared with other admins."), c("updated_at", "Son değişiklik (UTC).", "Last change (UTC)."))
    );

    /** {@code {tables: {tablo: {description, columns: {kolon: açıklama}}}}} — istek dilinde. */
    public static Map<String, Object> render() {
        Map<String, Object> tables = new LinkedHashMap<>();
        for (Tbl t : TABLES) {
            Map<String, Object> cols = new LinkedHashMap<>();
            for (Col c : t.cols()) {
                String d = Msg.t(c.tr(), c.en());
                if (SqlSensitiveColumns.isSensitive(t.name(), c.name())) {
                    d = d + " " + Msg.t("(Hassas — SQL Playground'da maskelenir, ifade içinde kullanılamaz.)",
                            "(Sensitive — masked in the SQL Playground and can't be used in expressions.)");
                }
                cols.put(c.name(), d);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("description", Msg.t(t.tr(), t.en()));
            m.put("columns", cols);
            tables.put(t.name(), m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tables", tables);
        return out;
    }
}
