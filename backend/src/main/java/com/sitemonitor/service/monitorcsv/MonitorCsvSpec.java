package com.sitemonitor.service.monitorcsv;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.DomainMonitor;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.model.PlannedSchedule;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.service.ScriptedSchedule;
import com.sitemonitor.service.dbquery.DbQueryRules;
import com.sitemonitor.service.noc.NocGroupIds;
import org.springframework.beans.PropertyAccessorFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * İzleme CSV dışa / içe aktarımının SÜTUN SÖZLEŞMESİ (2026-11-19, kullanıcı isteği: "UAT'tan prod'a izleme taşıma, toplu
 * kurulum"). On tür, her biri için SABİT sütun listesi (snake_case başlık adları kalıcıdır — arayüz ve kullanıcıların
 * dosyaları bunlara bağlıdır).
 *
 * <p><b>Bir sütun = (başlık, gövde anahtarı, tür, güncel değer).</b> Gövde anahtarı izleme uçlarının (MonitoringController
 * create / update) okuduğu anahtardır — içe aktarma gerçek kaydı O uçlarla yapar (doğrulama, yetki, geçmiş, denetim, alarm
 * kuralları birebir). "Güncel değer" kayıtlı izlemenin değeri, GÖVDE biçiminde (başvurular kimlik olarak); dışa aktarım
 * başvuruları ADA çevirir, içe aktarım adı kimliğe çevirip karşılaştırır.
 *
 * <p><b>Sır yok.</b> Basic auth parolası, şifreli özel başlıklar ve k6 gizli değişken DEĞERLERİ hiçbir sütunda yer almaz;
 * {@link Kind#INFO} sütunları yalnız "kaynakta vardı" bilgisini taşır (içe aktarımda yazılmaz, satırın "girilmesi gereken
 * sırlar" listesini besler). k6 ortam değişkenlerinde gizli olanların değeri boş yazılır.
 *
 * <p>Kapı: {@code MonitorCsvBodyKeyGateTest} her gövde anahtarının denetleyicide gerçekten okunduğunu kaynakta sınar.
 */
public final class MonitorCsvSpec {

    private MonitorCsvSpec() { }

    /** Sütun türü — hücre biçimi, ayrıştırma ve karşılaştırma kuralı. */
    public enum Kind {
        /** Kısa metin: kırpılır. */
        TEXT,
        /** Uzun / çok satırlı metin (betik, SQL, gövde): kırpılmaz, satır sonları karşılaştırmada LF sayılır. */
        LONG_TEXT,
        /** Tam sayı. */
        INT,
        /** true / false (evet / hayır, 1 / 0 da kabul). */
        BOOL,
        /** JSON değer (liste / nesne). Boş liste = boş hücre. */
        JSON,
        /** Bildirim grubu — hedef takımdaki ETKİN grubun adı. */
        NOTIFY_GROUP,
        /** 7/24 grupları — adlar {@code ;} ile ayrılır. */
        NOC_GROUPS,
        /** dbquery bağlantısı — hedef takımdaki bağlantının adı. */
        CONNECTION,
        /** k6 ortam değişkenleri — JSON liste [{name, value, secret}]; gizli değerler boş. */
        ENV,
        /** Yalnız bilgi (dışa aktarılır, içe aktarımda yazılmaz). */
        INFO
    }

    /**
     * @param header   CSV başlığı (snake_case, kalıcı)
     * @param bodyKey  izleme ucunun gövde anahtarı (INFO'da null)
     * @param kind     tür
     * @param current  kayıtlı izlemenin değeri (gövde biçiminde)
     * @param nullAs   kayıtlı değer null iken karşılaştırmada sayılacak değer (ör. alarm seviyesi null = WARNING); null = ""
     * @param upper    karşılaştırma büyük / küçük harf duyarsız (sabit değer kümeleri: GET, AUTO, INTERVAL …)
     */
    public record Column(String header, String bodyKey, Kind kind, Function<Object, Object> current, String nullAs,
                         boolean upper) {
        public boolean info() { return kind == Kind.INFO; }
    }

    /**
     * Tür tanımı.
     *
     * @param type             tür anahtarı (sekme kimliği — http, keyword, …)
     * @param entity           kayıt sınıfı
     * @param permission       yazma izni kaynağı ({@code monitoring.crud} ya da k6 için {@code monitoring.scripted})
     * @param columns          sütunlar (sıra = dosyadaki sıra)
     * @param createRequired   yeni kayıtta dolu olması gereken başlıklar
     * @param nameUniqueGlobal ad TÜM takımlarda tekil (dbquery)
     */
    public record TypeSpec(String type, Class<?> entity, String permission, List<Column> columns, Set<String> createRequired,
                           boolean nameUniqueGlobal) {
        public Column column(String header) {
            for (Column c : columns) if (c.header().equals(header)) return c;
            return null;
        }

        public List<String> headers() {
            List<String> out = new ArrayList<>(columns.size());
            for (Column c : columns) out.add(c.header());
            return out;
        }
    }

    /** Anahtar başlıklar (her türde; içe aktarımda zorunlu başlık). */
    public static final String H_NAME = "name";
    public static final String H_TEAM = "team";

    /** Sır adları (satırın "girilmesi gereken sırlar" listesi — arayüz {@code mcsv.secret.<ad>} çizer). */
    public static final String SECRET_BASIC_AUTH_PASS = "basic_auth_pass";
    public static final String SECRET_CUSTOM_HEADERS = "custom_headers";
    /** k6 gizli değişkeni: {@code env:<AD>}. */
    public static final String SECRET_ENV_PREFIX = "env:";

    /** Bilgi sütunları (dışa aktarımda "kaynakta vardı" bayrağı). */
    public static final String H_BASIC_AUTH_PASS_SET = "basic_auth_pass_set";
    public static final String H_CUSTOM_HEADERS_SET = "custom_headers_set";

    /** Desteklenen türler — arayüz listesiyle (frontend {@code MONITOR_CSV_TYPES}) aynı sıra. */
    public static final List<String> TYPES = List.of("http", "keyword", "page", "pagespeed", "ping", "port", "dns", "domain",
            "scripted", "dbquery");

    static final ObjectMapper JSON = new ObjectMapper();

    private static final Map<String, TypeSpec> SPECS;

    static {
        Map<String, TypeSpec> m = new LinkedHashMap<>();
        m.put("http", http());
        m.put("keyword", keyword());
        m.put("page", page());
        m.put("pagespeed", pageSpeed());
        m.put("ping", ping());
        m.put("port", port());
        m.put("dns", dns());
        m.put("domain", domain());
        m.put("scripted", scripted());
        m.put("dbquery", dbQuery());
        SPECS = Collections.unmodifiableMap(m);
    }

    /** Tür tanımı; bilinmeyen tür → null. */
    public static TypeSpec of(String type) {
        return type == null ? null : SPECS.get(type);
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────────────────────────────────────────────

    /** Bean özelliği okuyucu (Lombok getter). */
    static Function<Object, Object> prop(String name) {
        return e -> e == null ? null : PropertyAccessorFactory.forBeanPropertyAccess(e).getPropertyValue(name);
    }

    static Column col(String header, String bodyKey, Kind kind, Function<Object, Object> current) {
        return new Column(header, bodyKey, kind, current, null, false);
    }

    /** Özellik adı = gövde anahtarı; başlık = snake_case. */
    static Column p(String prop, Kind kind) {
        return col(snake(prop), prop, kind, prop(prop));
    }

    static Column p(String prop, Kind kind, String nullAs) {
        return new Column(snake(prop), prop, kind, prop(prop), nullAs, false);
    }

    /** Sabit değer kümesi (büyük / küçük harf duyarsız karşılaştırma). */
    static Column enumCol(String header, String bodyKey, String prop, String nullAs) {
        return new Column(header, bodyKey, Kind.TEXT, prop(prop), nullAs, true);
    }

    static String snake(String camel) {
        StringBuilder sb = new StringBuilder(camel.length() + 8);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) { sb.append('_').append(Character.toLowerCase(c)); }
            else sb.append(c);
        }
        return sb.toString();
    }

    /** Her türün ortak başlangıcı: ad, takım, grup, etiket, etkin. */
    private static List<Column> head() {
        List<Column> l = new ArrayList<>();
        l.add(col(H_NAME, "name", Kind.TEXT, prop("name")));
        l.add(col(H_TEAM, "teamId", Kind.TEXT, prop("teamId")));   // anahtar — kimliğe çevrilir, güncellemede yazılmaz
        l.add(col("group_name", "groupName", Kind.TEXT, prop("groupName")));
        l.add(col("tags", "tags", Kind.TEXT, prop("tags")));
        l.add(p("active", Kind.BOOL));
        return l;
    }

    /** Teyit / kurtarma + bildirim ortak kuyruğu. */
    private static void tail(List<Column> l) {
        l.add(p("confirmAttempts", Kind.INT));
        l.add(p("confirmIntervalSeconds", Kind.INT));
        l.add(p("recoveryChecks", Kind.INT));
        l.add(p("recoveryIntervalSeconds", Kind.INT));
        l.add(enumCol("alert_level", "alertLevel", "alertLevel", "WARNING"));
        l.add(p("notifyEmail", Kind.BOOL));
        l.add(p("notifyWebhook", Kind.BOOL));
        l.add(col("notification_group", "notificationGroupId", Kind.NOTIFY_GROUP, prop("notificationGroupId")));
        l.add(p("nocNotify", Kind.BOOL, "false"));
        l.add(col("noc_groups", "nocGroupIds", Kind.NOC_GROUPS,
                e -> NocGroupIds.parse((String) prop("nocGroupIds").apply(e))));
    }

    private static TypeSpec spec(String type, Class<?> entity, List<Column> cols, String... required) {
        return spec(type, entity, "monitoring.crud", false, cols, required);
    }

    private static TypeSpec spec(String type, Class<?> entity, String permission, boolean globalName, List<Column> cols,
                                 String... required) {
        Set<String> req = new LinkedHashSet<>(List.of(H_NAME, H_TEAM, "group_name", "tags"));
        req.addAll(List.of(required));
        return new TypeSpec(type, entity, permission, List.copyOf(cols), Collections.unmodifiableSet(req), globalName);
    }

    private static Column secretFlag(String header, Function<Object, Object> has) {
        return col(header, null, Kind.INFO, has);
    }

    private static boolean nonBlank(Object o) {
        return o != null && !o.toString().isBlank();
    }

    /** Sentetik / Sayfa Hızı zamanlama sütunları (ScriptedSchedule.api görünümü). Aralık türün kendi gövde anahtarıyla. */
    private static void plannedSchedule(List<Column> l) {
        l.add(new Column("schedule_mode", "schedule_mode", Kind.TEXT,
                e -> ScriptedSchedule.api((PlannedSchedule) e).get("schedule_mode"), "INTERVAL", true));
        l.add(col("cron_expression", "cron_expression", Kind.TEXT, prop("cronExpression")));
        l.add(col("windows", "windows", Kind.JSON, e -> ScriptedSchedule.api((PlannedSchedule) e).get("windows")));
        l.add(col("blackouts", "blackouts", Kind.JSON, e -> ScriptedSchedule.api((PlannedSchedule) e).get("blackouts")));
        l.add(new Column("skip_holidays", "skip_holidays", Kind.BOOL, prop("skipHolidays"), "false", false));
        l.add(col("skip_dates", "skip_dates", Kind.JSON, e -> ScriptedSchedule.api((PlannedSchedule) e).get("skip_dates")));
    }

    static List<Object> jsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            Object v = JSON.readValue(json, Object.class);
            if (v instanceof List<?> l) return new ArrayList<>(l);
        } catch (Exception ignored) {
            // bozuk kayıt: boş liste (dışa aktarım çökmez)
        }
        return List.of();
    }

    // ── Türler ────────────────────────────────────────────────────────────────────────────────────────────────────

    private static TypeSpec http() {
        List<Column> l = head();
        l.add(p("url", Kind.TEXT));
        l.add(enumCol("method", "method", "method", "GET"));
        l.add(p("expectedStatus", Kind.TEXT));
        l.add(p("followRedirects", Kind.BOOL));
        l.add(p("verifySsl", Kind.BOOL));
        l.add(enumCol("use_proxy", "useProxy", "useProxy", null));
        l.add(p("intervalSeconds", Kind.INT));
        l.add(p("timeoutMs", Kind.INT));
        l.add(p("checkSslErrors", Kind.BOOL));
        l.add(p("sslExpiryReminders", Kind.BOOL));
        l.add(p("domainExpiryReminders", Kind.BOOL));
        l.add(p("sslReminderDays", Kind.TEXT));
        l.add(p("domainReminderDays", Kind.TEXT));
        l.add(p("requestBody", Kind.LONG_TEXT));
        l.add(p("requestContentType", Kind.TEXT));
        l.add(p("basicAuthUser", Kind.TEXT));
        l.add(p("slowResponseEnabled", Kind.BOOL));
        l.add(p("slowThresholdMs", Kind.INT));
        l.add(p("jsonPath", Kind.TEXT));
        l.add(p("jsonExpected", Kind.TEXT));
        tail(l);
        l.add(secretFlag(H_BASIC_AUTH_PASS_SET, e -> nonBlank(((HttpMonitor) e).getBasicAuthPassEnc())));
        l.add(secretFlag(H_CUSTOM_HEADERS_SET, e -> nonBlank(((HttpMonitor) e).getCustomHeadersEnc())));
        return spec("http", HttpMonitor.class, l, "url");
    }

    private static TypeSpec keyword() {
        List<Column> l = head();
        l.add(p("url", Kind.TEXT));
        l.add(p("keyword", Kind.TEXT));
        l.add(new Column("match_operator", "operator", Kind.TEXT, prop("matchOperator"), "GTE", true));
        l.add(new Column("match_count", "matchCount", Kind.INT, prop("matchCount"), "1", false));
        l.add(p("caseSensitive", Kind.BOOL, "false"));
        l.add(col("rules", "rules", Kind.JSON, e -> {
            String j = ((KeywordMonitor) e).getRulesJson();
            return j == null || j.isBlank() ? null : jsonList(j);
        }));
        l.add(enumCol("rule_logic", "ruleLogic", "ruleLogic", "AND"));
        l.add(p("intervalSeconds", Kind.INT));
        l.add(p("timeoutMs", Kind.INT));
        l.add(enumCol("use_proxy", "useProxy", "useProxy", null));
        l.add(enumCol("match_scope", "matchScope", "matchScope", "RAW"));
        l.add(p("normalizeWs", Kind.BOOL, "false"));
        l.add(enumCol("method", "method", "method", "GET"));
        l.add(p("requestBody", Kind.LONG_TEXT));
        l.add(p("requestContentType", Kind.TEXT));
        l.add(p("basicAuthUser", Kind.TEXT));
        l.add(enumCol("user_agent_mode", "userAgentMode", "userAgentMode", "DEFAULT"));
        l.add(p("userAgent", Kind.TEXT));
        l.add(p("expectedStatus", Kind.TEXT));
        l.add(p("expectedFinalUrl", Kind.TEXT));
        l.add(p("followRedirects", Kind.BOOL, "true"));
        l.add(p("mailSubjectTemplate", Kind.TEXT));
        l.add(p("slowResponseEnabled", Kind.BOOL));
        l.add(p("slowThresholdMs", Kind.INT));
        l.add(p("checkSslErrors", Kind.BOOL));
        l.add(p("sslExpiryReminders", Kind.BOOL));
        l.add(p("domainExpiryReminders", Kind.BOOL));
        l.add(p("sslReminderDays", Kind.TEXT));
        l.add(p("domainReminderDays", Kind.TEXT));
        l.add(p("minBytes", Kind.INT));
        l.add(p("maxBytes", Kind.INT));
        l.add(p("changeDetect", Kind.BOOL, "false"));
        l.add(p("changeMask", Kind.TEXT));
        l.add(p("changeSelector", Kind.TEXT));
        tail(l);
        l.add(secretFlag(H_BASIC_AUTH_PASS_SET, e -> nonBlank(((KeywordMonitor) e).getBasicAuthPassEnc())));
        l.add(secretFlag(H_CUSTOM_HEADERS_SET, e -> nonBlank(((KeywordMonitor) e).getCustomHeadersEnc())
                || nonBlank(((KeywordMonitor) e).getCustomHeaders())));
        return spec("keyword", KeywordMonitor.class, l, "url", "keyword");
    }

    private static TypeSpec page() {
        List<Column> l = head();
        l.add(p("url", Kind.TEXT));
        l.add(enumCol("mode", "mode", "mode", "SINGLE_PAGE"));
        l.add(p("crawlDepth", Kind.INT));
        l.add(p("crawlMaxPages", Kind.INT));
        l.add(p("excludePatterns", Kind.LONG_TEXT));
        l.add(enumCol("use_proxy", "useProxy", "useProxy", null));
        l.add(p("intervalSeconds", Kind.INT));
        l.add(p("timeoutMs", Kind.INT));
        l.add(p("slowResourceMs", Kind.INT));
        l.add(p("alertThirdParty", Kind.BOOL));
        l.add(p("alertMixedContent", Kind.BOOL));
        l.add(p("alertTimeout", Kind.BOOL));
        l.add(p("minBrokenResources", Kind.INT));
        l.add(p("resourceConcurrency", Kind.INT));
        tail(l);
        return spec("page", PageMonitor.class, l, "url");
    }

    private static TypeSpec pageSpeed() {
        List<Column> l = head();
        l.add(p("url", Kind.TEXT));
        l.add(p("intervalSeconds", Kind.INT));
        plannedSchedule(l);
        l.add(p("timeoutMs", Kind.INT));
        l.add(p("maxLoadMs", Kind.INT));
        l.add(p("maxTtfbMs", Kind.INT));
        l.add(p("maxPageKb", Kind.INT));
        l.add(p("maxRequests", Kind.INT));
        l.add(p("maxJsKb", Kind.INT));
        l.add(p("maxImgKb", Kind.INT));
        l.add(p("maxFontKb", Kind.INT));
        l.add(p("maxThirdPartyKb", Kind.INT));
        l.add(p("minPageKb", Kind.INT));
        l.add(p("extendedMeasurement", Kind.BOOL, "false"));
        l.add(p("growthBytesPct", Kind.INT));
        l.add(p("growthRequestsPct", Kind.INT));
        l.add(p("growthBytesBand", Kind.BOOL, "false"));
        l.add(p("userAgent", Kind.TEXT));
        l.add(p("sendDnt", Kind.BOOL));
        l.add(enumCol("use_proxy", "useProxy", "useProxy", null));
        l.add(p("excludeTrackers", Kind.BOOL));
        l.add(p("trackerPatterns", Kind.LONG_TEXT));
        l.add(p("resourceConcurrency", Kind.INT));
        l.add(p("basicAuthUser", Kind.TEXT));
        tail(l);
        l.add(secretFlag(H_BASIC_AUTH_PASS_SET, e -> nonBlank(((PageSpeedMonitor) e).getBasicAuthPassEnc())));
        l.add(secretFlag(H_CUSTOM_HEADERS_SET, e -> nonBlank(((PageSpeedMonitor) e).getCustomHeadersEnc())));
        return spec("pagespeed", PageSpeedMonitor.class, l, "url");
    }

    private static TypeSpec ping() {
        List<Column> l = head();
        l.add(p("host", Kind.TEXT));
        l.add(new Column("ip_version", "ipVersion", Kind.TEXT, prop("ipVersion"), "auto", true));
        l.add(p("intervalSeconds", Kind.INT));
        l.add(p("timeoutMs", Kind.INT));
        l.add(p("packetCount", Kind.INT));
        l.add(p("slowResponseEnabled", Kind.BOOL));
        l.add(p("slowBaselineWindowMinutes", Kind.INT));
        l.add(p("slowThresholdPercent", Kind.INT));
        tail(l);
        return spec("ping", PingMonitor.class, l, "host");
    }

    private static TypeSpec port() {
        List<Column> l = head();
        l.add(p("host", Kind.TEXT));
        l.add(p("port", Kind.INT));
        l.add(enumCol("protocol", "protocol", "protocol", null));
        l.add(p("expect", Kind.TEXT));
        l.add(p("sendData", Kind.LONG_TEXT));
        l.add(p("intervalSeconds", Kind.INT));
        l.add(p("timeoutMs", Kind.INT));
        l.add(p("slowResponseEnabled", Kind.BOOL));
        l.add(p("slowThresholdMs", Kind.INT));
        l.add(new Column("ip_version", "ipVersion", Kind.TEXT, prop("ipVersion"), "auto", true));
        l.add(enumCol("use_proxy", "useProxy", "useProxy", null));
        tail(l);
        return spec("port", PortMonitor.class, l, "host", "port");
    }

    private static TypeSpec dns() {
        List<Column> l = head();
        l.add(p("domain", Kind.TEXT));
        l.add(enumCol("record_type", "recordType", "recordType", null));
        l.add(p("expectedValue", Kind.LONG_TEXT));
        l.add(p("propagationCheck", Kind.BOOL, "false"));
        l.add(p("slowThresholdMs", Kind.INT));
        l.add(p("dnsChangeAlertEnabled", Kind.BOOL, "true"));
        l.add(p("intervalSeconds", Kind.INT));
        tail(l);
        return spec("dns", DnsMonitor.class, l, "domain", "record_type");
    }

    private static TypeSpec domain() {
        List<Column> l = head();
        l.add(p("domain", Kind.TEXT));
        l.add(p("intervalSeconds", Kind.INT));
        l.add(p("thresholdsCsv", Kind.TEXT));
        l.add(p("warningDays", Kind.INT));
        l.add(p("criticalDays", Kind.INT));
        l.add(p("transferLockAlert", Kind.BOOL));
        l.add(p("blacklistEnabled", Kind.BOOL));
        l.add(p("changeAlert", Kind.BOOL));
        l.add(p("checkTimeoutMs", Kind.INT));
        tail(l);
        return spec("domain", DomainMonitor.class, l, "domain");
    }

    private static TypeSpec scripted() {
        List<Column> l = head();
        l.add(p("description", Kind.LONG_TEXT));
        l.add(p("script", Kind.LONG_TEXT));
        l.add(col("env", "env", Kind.ENV, e -> envView(((ScriptedMonitor) e).getEnvJson())));
        l.add(p("timeoutSeconds", Kind.INT));
        l.add(enumCol("use_proxy", "useProxy", "useProxy", "AUTO"));
        l.add(p("intervalSeconds", Kind.INT));
        plannedSchedule(l);
        l.add(p("slowResponseEnabled", Kind.BOOL));
        l.add(p("slowThresholdMs", Kind.INT));
        tail(l);
        return spec("scripted", ScriptedMonitor.class, "monitoring.scripted", false, l, "script");
    }

    private static TypeSpec dbQuery() {
        List<Column> l = head();
        l.add(col("connection", "profile_id", Kind.CONNECTION, prop("profileId")));
        l.add(col("purpose", "purpose", Kind.LONG_TEXT, prop("purpose")));
        l.add(col("sql_text", "sql_text", Kind.LONG_TEXT, prop("sqlText")));
        l.add(new Column("schedule_mode", "schedule_mode", Kind.TEXT, prop("scheduleMode"), "INTERVAL", true));
        l.add(col("interval_seconds", "interval_seconds", Kind.INT, prop("intervalSeconds")));
        l.add(col("cron_expression", "cron_expression", Kind.TEXT, prop("cronExpression")));
        l.add(col("windows", "windows", Kind.JSON, e -> jsonList(((DbQueryMonitor) e).getWindowsJson())));
        l.add(col("blackouts", "blackouts", Kind.JSON, e -> jsonList(((DbQueryMonitor) e).getBlackoutJson())));
        l.add(new Column("skip_holidays", "skip_holidays", Kind.BOOL, prop("skipHolidays"), "false", false));
        l.add(col("skip_dates", "skip_dates", Kind.JSON, e -> jsonList(((DbQueryMonitor) e).getSkipDatesJson())));
        l.add(col("query_timeout_seconds", "query_timeout_seconds", Kind.INT, prop("queryTimeoutSeconds")));
        l.add(col("max_rows", "max_rows", Kind.INT, prop("maxRows")));
        l.add(col("rules", "rules", Kind.JSON, e -> {
            String j = ((DbQueryMonitor) e).getRulesJson();
            return j == null || j.isBlank() ? List.of() : DbQueryRules.toApi(DbQueryRules.fromJson(j));
        }));
        l.add(new Column("rule_logic", "rule_logic", Kind.TEXT, prop("ruleLogic"), "ANY", true));
        l.add(col("slow_query_ms", "slow_query_ms", Kind.INT, prop("slowQueryMs")));
        l.add(col("mail_include_rows", "mail_include_rows", Kind.BOOL, prop("mailIncludeRows")));
        l.add(col("mail_columns", "mail_columns", Kind.JSON, e -> jsonList(((DbQueryMonitor) e).getMailColumnsJson())));
        l.add(col("masked_columns", "masked_columns", Kind.JSON, e -> jsonList(((DbQueryMonitor) e).getMaskedColumnsJson())));
        l.add(new Column("mail_columns_mode", "mail_columns_mode", Kind.TEXT, prop("mailColumnsMode"), "FIRST_N", true));
        l.add(col("mail_subject_template", "mail_subject_template", Kind.TEXT, prop("mailSubjectTemplate")));
        tail(l);
        return spec("dbquery", DbQueryMonitor.class, "monitoring.crud", true, l, "connection", "purpose", "sql_text");
    }

    /** k6 ortam değişkenleri: gizli olanların DEĞERİ boş (sır asla dışarı çıkmaz). */
    static List<Map<String, Object>> envView(String envJson) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : jsonList(envJson)) {
            if (!(o instanceof Map<?, ?> e)) continue;
            Object nm = e.get("name");
            if (nm == null || nm.toString().isBlank()) continue;
            boolean secret = Boolean.TRUE.equals(e.get("secret")) || "true".equals(String.valueOf(e.get("secret")));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", nm.toString().trim());
            row.put("value", secret ? "" : (e.get("value") == null ? "" : e.get("value").toString()));
            row.put("secret", secret);
            out.add(row);
        }
        return out;
    }

    /** Kayıtlı k6 izlemesinde DOLU (şifreli) değeri olan gizli değişken adları. */
    static Set<String> storedSecretNames(String envJson) {
        Set<String> out = new LinkedHashSet<>();
        for (Object o : jsonList(envJson)) {
            if (!(o instanceof Map<?, ?> e)) continue;
            Object nm = e.get("name");
            boolean secret = Boolean.TRUE.equals(e.get("secret")) || "true".equals(String.valueOf(e.get("secret")));
            if (secret && nm != null && e.get("value") != null && !e.get("value").toString().isBlank()) out.add(nm.toString().trim());
        }
        return out;
    }
}
