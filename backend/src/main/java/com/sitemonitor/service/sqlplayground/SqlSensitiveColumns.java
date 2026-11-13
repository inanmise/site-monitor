package com.sitemonitor.service.sqlplayground;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SQL Playground hassas kolon kataloğu — TEK KAYNAK (2026-11-13, A1).
 *
 * <p><b>Ne korur.</b> Global yönetici SQL Playground'da her tabloyu okuyabilir; ama bazı kolonlar ekranda hiçbir yerde
 * gösterilmeyen sırlardır: parola özetleri, oturum / beni-hatırla / onay belirteçleri, tek kullanımlık kod HMAC'leri,
 * şifreli ({@code *_enc}) değerler, k6 ortam değişkenleri. Bunlar sorgu sonucunda {@link #MASK} olarak döner
 * (kökene göre — {@code SqlResultMasker}) ve ifade içinde kullanılamaz ({@link SqlSensitiveGuard}).
 *
 * <p><b>Üç kural.</b> (1) {@link #PAIRS}: açıkça listelenen (tablo, kolon) çiftleri. (2) Ad deseni: kolon adı
 * {@code password|passwd|secret|token|hmac|api_key|private_key|session_id} parçalarından birini içeriyorsa ya da
 * {@code _enc} ile bitiyorsa hassastır — yeni bir tablo eklendiğinde bile koruma kendiliğinden gelir. (3) {@link #EXEMPT}:
 * desene uyan ama sır OLMAYAN kolonlar (bayrak / zaman / eşzamanlılık talebi), GEREKÇESİYLE. Kapı testi
 * ({@code SqlSensitiveColumnsGateTest}) varlık ve ham DDL kolonlarını tarar: desene uyan her kolon ya {@link #PAIRS}'te
 * ya {@link #EXEMPT}'te olmalı — yeni kolon için bilinçli karar verilmeden derleme geçmez.
 *
 * <p><b>Değer kuralı.</b> Şifreli değer biçimi ({@code enc:v1:…}) hangi kolonda olursa olsun hücre maskelenir
 * ({@link #isEncryptedValue}) — {@code app_settings.setting_value} gibi düz ve şifreli değerleri karıştıran kolonlar için.
 *
 * <p>Tüm adlar küçük harf ({@link Locale#ROOT}) karşılaştırılır; tarama doğrusaldır (düzenli ifade yok).
 */
public final class SqlSensitiveColumns {

    private SqlSensitiveColumns() {}

    /** Maskelenen hücrenin değeri (null hücre null kalır). */
    public static final String MASK = "••••";

    /** Ad deseninin parçaları — kolon adında GEÇERSE hassas (alt dize). */
    static final List<String> NAME_PARTS = List.of(
            "password", "passwd", "secret", "token", "hmac", "api_key", "private_key", "session_id");

    /** Şifreli kolon soneki. */
    static final String ENC_SUFFIX = "_enc";

    /** Şifreli değer biçimi ({@code SecretCipher.PREFIX}). */
    static final String ENC_VALUE_MARK = "enc:v1:";

    /** Açık (tablo → kolonlar) çiftleri. */
    public static final Map<String, Set<String>> PAIRS;

    /** Desene uyan ama sır olmayan kolonlar: "tablo.kolon" → gerekçe. */
    public static final Map<String, String> EXEMPT;

    /**
     * Örnek değer taşıyan PostgreSQL istatistik görünümleri — {@code histogram_bounds} / {@code most_common_vals}
     * herhangi bir kolonun (hassaslar dahil) gerçek değerlerinden örnek taşır. Kullanıcı sorgusunda adı geçemez.
     */
    public static final Set<String> STATS_RELATIONS = Set.of(
            "pg_stats", "pg_stats_ext", "pg_stats_ext_exprs", "pg_statistic", "pg_statistic_ext",
            "pg_statistic_ext_data");

    /**
     * Metinden sorgu çalıştıran ya da tabloyu adıyla (dize olarak) okuyan fonksiyonlar — içlerindeki tablo / kolon adı
     * bir DİZE olduğu için hiçbir ad denetimi onu göremez ({@code query_to_xml('select password_hash …')}). Her zaman
     * reddedilir.
     */
    public static final Set<String> DYNAMIC_SQL_FUNCTIONS = Set.of(
            "query_to_xml", "query_to_xml_and_xmlschema", "query_to_xmlschema",
            "table_to_xml", "table_to_xml_and_xmlschema", "table_to_xmlschema",
            "cursor_to_xml", "cursor_to_xmlschema",
            "schema_to_xml", "schema_to_xml_and_xmlschema", "schema_to_xmlschema",
            "database_to_xml", "database_to_xml_and_xmlschema", "database_to_xmlschema",
            "ts_stat", "ts_rewrite");

    /**
     * Bir satırı (bileşik değeri) TEK argüman olarak kabul eden fonksiyonların adları — PostgreSQL 16 çekirdeği (2026-11-13,
     * 3. tur). PostgreSQL {@code u.f} yazımını, {@code u}'nun {@code f} adlı kolonu YOKSA {@code f(u)} çağrısı olarak
     * yorumlar ("alan biçiminde fonksiyon çağrısı"): {@code u.to_jsonb}, {@code u.concat}, {@code u.json_agg},
     * {@code u.quote_literal} satırın tamamını — hassas kolonlarla — metne çevirir. Canlı veritabanında liste
     * {@code pg_proc}'tan okunur (ilk argüman türü {@code record} / çok biçimli / bileşik, tek argümanla çağrılabilir);
     * bu sabit yalnız o okuma yapılamadığında (ya da H2'de) kullanılan TABAN — canlı liste her zaman bununla birleştirilir.
     */
    public static final Set<String> ROW_FUNCTIONS_FALLBACK = Set.of(
            "any_out", "any_value", "anycompatible_out", "anycompatiblenonarray_out", "anyelement_out", "anynonarray_out",
            "array_agg", "concat", "count", "cume_dist", "dense_rank", "first_value", "hash_record", "json_agg",
            "json_agg_strict", "json_build_array", "json_build_object", "jsonb_agg", "jsonb_agg_strict",
            "jsonb_build_array", "jsonb_build_object", "lag", "last_value", "lead", "mode", "num_nonnulls", "num_nulls",
            "percent_rank", "pg_collation_for", "pg_column_compression", "pg_column_size", "pg_typeof", "quote_literal",
            "quote_nullable", "rank", "record_out", "record_send", "row_to_json", "to_json", "to_jsonb",
            // hstore eklentisi: hstore(record) — kurulu bir veritabanında canlı okuma da bulur
            "hstore");

    static {
        Map<String, Set<String>> p = new LinkedHashMap<>();
        pair(p, "app_users", "password_hash", "active_session_id");
        pair(p, "password_history", "password_hash");
        pair(p, "remember_me_tokens", "token");
        pair(p, "login_otp_challenges", "code_hmac");
        // Spring Session JDBC (uygulamanın kodunda DDL yok — kütüphanenin şeması)
        pair(p, "spring_session", "session_id", "primary_id");
        pair(p, "spring_session_attributes", "session_primary_id", "attribute_bytes");
        // PostgreSQL katalogu: rol parola özetleri + dış sunucu kullanıcı eşlemesi seçenekleri (FDW parolası). Uygulama
        // kullanıcısı süper kullanıcı değilse zaten okuyamaz; okuyabildiği kurulumda maskelenir / ifadede reddedilir.
        pair(p, "pg_authid", "rolpassword");
        pair(p, "pg_shadow", "passwd");
        pair(p, "pg_user_mapping", "umoptions");
        pair(p, "pg_user_mappings", "umoptions");
        pair(p, "tech_error_approval_tokens", "token_hash");
        pair(p, "weekly_reports", "approval_token");
        pair(p, "audit_log", "session_id");
        // k6 ortam değişkenleri (gizli değerler şifreli ama ad / değer yapısı da maskelenir) + düzenleme taslağı (form
        // içinde düz metin gizli değer taşıyabilir) + şablonların ortam tanımları
        pair(p, "scripted_monitors", "env_json");
        pair(p, "scripted_script_versions", "env_json");
        pair(p, "scripted_drafts", "form_json");
        pair(p, "scripted_templates", "env_json");
        pair(p, "scripted_template_versions", "env_json");
        // Şifreli değerler ({@code *_enc}) — desen zaten yakalar; açık liste kapı testinin karşılaştırması için
        pair(p, "db_connection_profiles", "password_enc");
        pair(p, "ldap_settings", "bind_password_enc");
        pair(p, "smtp_settings", "password_enc");
        pair(p, "http_monitors", "custom_headers_enc", "basic_auth_pass_enc");
        pair(p, "keyword_monitors", "custom_headers_enc", "basic_auth_pass_enc");
        pair(p, "pagespeed_monitors", "custom_headers_enc", "basic_auth_pass_enc");
        Map<String, Set<String>> frozen = new LinkedHashMap<>();
        p.forEach((k, v) -> frozen.put(k, Collections.unmodifiableSet(v)));
        PAIRS = Collections.unmodifiableMap(frozen);

        Map<String, String> e = new LinkedHashMap<>();
        e.put("app_users.must_change_password",
                "Geçici parola sonrası değiştirme zorunluluğu bayrağı (true / false) — sır değil.");
        e.put("app_users.temp_password_expires_at",
                "Geçici parolanın bitiş zamanı — parolanın kendisi değil.");
        e.put("weekly_reports.approval_token_expires_at",
                "Onay bağlantısının bitiş zamanı — belirtecin kendisi (approval_token) maskelenir.");
        e.put("domain_expiry_reminders.claim_token",
                "Eşzamanlı gönderim talebinin rastgele kimliği (iki pod aynı hatırlatmayı göndermesin) — kimlik doğrulama "
              + "verisi değil, dışarıya verilmez.");
        e.put("monitor_check_jobs.claim_token",
                "Dağıtık kontrol işinin sahiplenme kimliği (32 onaltılık, iş başına) — kimlik doğrulama verisi değil.");
        e.put("cert_sweep_chunks.claim_token",
                "Parçalı sertifika taraması parçasının sahiplenme kimliği — kimlik doğrulama verisi değil.");
        EXEMPT = Collections.unmodifiableMap(e);
    }

    private static void pair(Map<String, Set<String>> m, String table, String... cols) {
        Set<String> s = m.computeIfAbsent(table, k -> new LinkedHashSet<>());
        for (String c : cols) s.add(c);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /** Ad deseni (alt dize parçaları ya da {@code _enc} soneki) — tablodan bağımsız. */
    public static boolean matchesNamePattern(String column) {
        String c = norm(column);
        if (c.isEmpty()) return false;
        if (c.endsWith(ENC_SUFFIX)) return true;
        for (String part : NAME_PARTS) if (c.contains(part)) return true;
        return false;
    }

    /** {@link #EXEMPT}'te mi? */
    public static boolean isExempt(String table, String column) {
        return EXEMPT.containsKey(norm(table) + "." + norm(column));
    }

    /** (tablo, kolon) hassas mı — açık çift ya da desen, muafiyet hariç. */
    public static boolean isSensitive(String table, String column) {
        String t = norm(table), c = norm(column);
        if (t.isEmpty() || c.isEmpty()) return false;
        if (isExempt(t, c)) return false;
        Set<String> s = PAIRS.get(t);
        if (s != null && s.contains(c)) return true;
        return matchesNamePattern(c);
    }

    /** Hücre şifreli değer biçiminde mi ({@code enc:v1:} içerir)? — hangi kolonda olursa olsun maskelenir. */
    public static boolean isEncryptedValue(Object v) {
        if (v instanceof String s) return s.contains(ENC_VALUE_MARK);
        // Dizi hücresi listeye çevrilir (SqlResultColumns.plain — en çok 10 000 öğe, 6 boyut): öğelerden biri yeter
        if (v instanceof java.util.Collection<?> c) {
            for (Object e : c) if (isEncryptedValue(e)) return true;
            return false;
        }
        // PostgreSQL json / jsonb / diğer özel türler sürücünün PGobject'i olarak gelir (metin değeri toString)
        if (v != null && v.getClass().getName().startsWith("org.postgresql.util.PG")) {
            return String.valueOf(v).contains(ENC_VALUE_MARK);
        }
        return false;
    }

    /** İstatistik görünümü mü (ad karşılaştırması küçük harf)? */
    public static boolean isStatsRelation(String name) {
        return STATS_RELATIONS.contains(norm(name));
    }

    /** Metinden sorgu çalıştıran fonksiyon mu? */
    public static boolean isDynamicSqlFunction(String name) {
        return DYNAMIC_SQL_FUNCTIONS.contains(norm(name));
    }

    /**
     * Bir şemanın hassas kolonları: statik {@link #PAIRS} + canlı şemada desene uyan (muaf olmayan) her kolon.
     *
     * @param columnsByTable canlı şema (tablo → kolonlar); null ya da boş olabilir (yalnız statik çiftler)
     */
    public static Schema schemaOf(Map<String, ? extends java.util.Collection<String>> columnsByTable) {
        return schemaOf(columnsByTable, null);
    }

    /**
     * {@link #schemaOf(Map)} + canlı veritabanının satır alan fonksiyon adları ({@code pg_proc}; null / boş →
     * yalnız {@link #ROW_FUNCTIONS_FALLBACK}). Liste her zaman tabanla BİRLEŞTİRİLİR (asla daralmaz).
     */
    public static Schema schemaOf(Map<String, ? extends java.util.Collection<String>> columnsByTable,
                                  java.util.Collection<String> liveRowFunctions) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        Map<String, Set<String>> all = new LinkedHashMap<>();
        PAIRS.forEach((t, cols) -> out.computeIfAbsent(t, k -> new LinkedHashSet<>()).addAll(cols));
        if (columnsByTable != null) {
            columnsByTable.forEach((table, cols) -> {
                if (cols == null) return;
                for (String col : cols) {
                    if (col == null) continue;
                    all.computeIfAbsent(norm(table), k -> new LinkedHashSet<>()).add(col);   // tam ad (harf duyarlı)
                    if (isSensitive(table, col)) out.computeIfAbsent(norm(table), k -> new LinkedHashSet<>()).add(norm(col));
                }
            });
        }
        Set<String> fns = new LinkedHashSet<>(ROW_FUNCTIONS_FALLBACK);
        if (liveRowFunctions != null) for (String f : liveRowFunctions) if (f != null && !f.isBlank()) fns.add(norm(f));
        return new Schema(out, all, fns);
    }

    /**
     * Hassas kolon şeması — tablo → hassas kolonlar (küçük harf). {@link #sensitiveNames()}: en az bir tabloda hassas
     * olan kolon adları (kökeni bilinmeyen sonuç kolonu için ad yedeği).
     */
    public static final class Schema {
        private final Map<String, Set<String>> byTable;
        private final Set<String> names;
        /** Canlı şemadaki (public) TÜM kolonlar — yalnız alan biçiminde fonksiyon kuralının "gerçek kolon mu" sorusu için. */
        private final Map<String, Set<String>> allColumns;
        private final Set<String> rowFunctions;

        Schema(Map<String, Set<String>> byTable, Map<String, Set<String>> allColumns, Set<String> rowFunctions) {
            Map<String, Set<String>> f = new LinkedHashMap<>();
            Set<String> n = new LinkedHashSet<>();
            byTable.forEach((t, cols) -> {
                f.put(t, Collections.unmodifiableSet(new LinkedHashSet<>(cols)));
                n.addAll(cols);
            });
            this.byTable = Collections.unmodifiableMap(f);
            this.names = Collections.unmodifiableSet(n);
            Map<String, Set<String>> a = new LinkedHashMap<>();
            allColumns.forEach((t, cols) -> a.put(t, Collections.unmodifiableSet(new LinkedHashSet<>(cols))));
            this.allColumns = Collections.unmodifiableMap(a);
            this.rowFunctions = Collections.unmodifiableSet(new LinkedHashSet<>(rowFunctions));
        }

        /** {@code u.f} yazımında {@code f} bir satırı argüman alan fonksiyonun adı mı (küçük harf)? */
        public boolean isRowFunction(String name) {
            return rowFunctions.contains(norm(name));
        }

        /**
         * Tablonun (public şeması) bu adlı GERÇEK bir kolonu olduğu biliniyor mu? Kolon adı TAM eşleşir (PostgreSQL'in
         * katlanmış adıyla — {@code "Mode"} ile {@code mode} farklı kolonlardır); kolonları bilinmiyorsa false.
         */
        public boolean hasColumn(String table, String column) {
            Set<String> s = allColumns.get(norm(table));
            return s != null && column != null && s.contains(column);
        }

        public Map<String, Set<String>> byTable() { return byTable; }

        public Set<String> sensitiveNames() { return names; }

        /** Tablonun hassas kolonları (küçük harf); yoksa boş. */
        public Set<String> columnsOf(String table) {
            Set<String> s = byTable.get(norm(table));
            return s == null ? Set.of() : s;
        }

        public boolean hasSensitiveColumns(String table) {
            return !columnsOf(table).isEmpty();
        }

        /** Kökeni bilinen sonuç kolonu ya da yalnız adı bilinen kolon hassas mı? */
        public boolean isSensitive(String table, String column) {
            String t = norm(table), c = norm(column);
            if (!t.isEmpty() && columnsOf(t).contains(c)) return true;
            return !t.isEmpty() && SqlSensitiveColumns.isSensitive(t, c);
        }

        /** Ad yedeği: en az bir tabloda hassas olan bir ad mı? */
        public boolean isSensitiveName(String column) {
            String c = norm(column);
            return !c.isEmpty() && (names.contains(c) || c.endsWith(ENC_SUFFIX));
        }
    }
}
