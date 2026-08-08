package com.sitemonitor.service.retention;

/**
 * Tek bir tablonun saklama (retention) kuralı — BİLDİRİMSEL. Gece temizliği artık elle yazılmış
 * DELETE blokları değil, {@link RetentionCatalog#ALL} listesini dolaşarak çalışır.
 *
 * <p>Aynı liste beş yeri birden besler: gerçek silme, dry-run raporu, yönetim ekranı, Prometheus
 * metrikleri ve üretilen {@code docs/RETENTION_POLITIKASI.md}. Böylece "yeni izleme türü eklendi,
 * temizliği unutuldu" hata sınıfı {@code RetentionCoverageTest} bekçi testiyle kapanır.
 *
 * @param id            kalıcı kebab-case anahtar — metrik etiketi, çalışma kaydı ve i18n bunu kullanır.
 *                      Tablo adı DEĞİL: bir tablonun birden çok kuralı olabilir (ör. incident_images).
 * @param table         fiziksel tablo adı
 * @param timeColumn    yaş kıyası yapılan kolon; {@link Mode#ORPHAN_ONLY} ve {@link Mode#EXTERNAL}'de null
 * @param timeKind      cutoff değerinin ve SQL ifadesinin biçimi
 * @param settingKey    canlı ayar anahtarı (AppSettingsCatalog "retention" grubu); null → düzenlenemez
 * @param defaultDays   kod varsayılanı
 * @param minDays       TABAN: ayar bunun altına inemez (yanlış girilen değer aynı gece geri alınamaz
 *                      veri kaybıdır). {@code zeroMeansNever} kurallarında 0 ayrıca kabul edilir.
 * @param zeroMeansNever 0 = "hiç silme" (opt-in kural; incident_records tarihsel davranışı)
 * @param where         WHERE gövdesi. {@code {t}} yer tutucusu zaman ifadesiyle değiştirilir
 *                      (ör. {@code checked_at < ?}). Guard'ın zamandan ÖNCE mi SONRA mı geldiği
 *                      buradan belli olur — eski SQL metinleri birebir korunur.
 * @param mode          silme kipi
 * @param batched       true → 10k'lık dilimlerle sil + ANALYZE (tablo bir {@code id} kolonu İSTER)
 * @param dataClass     KVKK/uyum gruplaması ve ekrandaki bölümleme
 * @param rationale     bu sürenin NEDEN böyle olduğu — dokümana aynen basılır
 */
public record RetentionPolicy(
        String id,
        String table,
        String timeColumn,
        TimeKind timeKind,
        String settingKey,
        int defaultDays,
        int minDays,
        boolean zeroMeansNever,
        String where,
        Mode mode,
        boolean batched,
        DataClass dataClass,
        String rationale
) {

    /** Cutoff değerinin/SQL ifadesinin biçimi — tablolar tek tip değil. */
    public enum TimeKind {
        /** 19 karakterlik ISO-8601 String kolon; sözlüksel kıyas kronolojiktir (tabloların çoğu). */
        ISO_STRING,
        /** Gerçek TIMESTAMP kolon → parametre CAST edilmeli (system_heartbeat). */
        TIMESTAMP,
        /** 10 karakterlik gün kolonu (monitor_check_daily.day) → cutoff ilk 10 karaktere kırpılır. */
        DATE10
    }

    /** Silme kipi. */
    public enum Mode {
        /** Yaşa göre sil (kuralların çoğu). */
        AGE,
        /** Ebeveynin yaşına göre sil — WHERE alt sorgu içerir, tek parametre alır. */
        AGE_VIA_PARENT,
        /** Yaş kuralı YOK: ebeveyni kalmamış satırları sil (parametresiz). */
        ORPHAN_ONLY,
        /** Bu uygulama silmez — başka bir bileşen yönetir (Spring Session, RememberMeService).
         *  Katalogda yer alır ki bekçi testi "kapsanmamış" sanmasın; çalıştırılmaz. */
        EXTERNAL,
        /** Sınırlı büyüyen yapılandırma/referans tablosu — silinmez, yalnız belgelenir. */
        BOUNDED
    }

    /** Veri sınıfı — ekranda bölüm başlığı, dokümanda uyum onayı gereken satırların işareti. */
    public enum DataClass {
        /** İşletimsel telemetri (kontrol serileri, metrikler) — kişisel veri içermez. */
        OPERATIONAL,
        /** Kişisel veri içerir (kullanıcı adı, IP, e-posta, serbest metin) — uyum onayı gerekir. */
        PERSONAL,
        /** Denetim/güvenlik kaydı — mevzuat süresi baskındır. */
        SECURITY_AUDIT,
        /** Kullanıcı üretimi içerik (rapor, olay kaydı, görsel). */
        CONTENT
    }

    /** Bu kural gerçekten satır siliyor mu (EXTERNAL/BOUNDED yalnız belge amaçlı). */
    public boolean deletes() {
        return mode == Mode.AGE || mode == Mode.AGE_VIA_PARENT || mode == Mode.ORPHAN_ONLY;
    }

    /** Süre ayarı ekrandan değiştirilebilir mi. */
    public boolean configurable() {
        return settingKey != null && mode != Mode.ORPHAN_ONLY && deletes();
    }

    /** {@code {t}} yer tutucusu çözülmüş WHERE gövdesi. */
    public String resolvedWhere() {
        if (where == null) return null;
        return where.replace("{t}", timeExpression());
    }

    /** Kolonun tipine göre zaman kıyas ifadesi. */
    public String timeExpression() {
        if (timeColumn == null) return "";
        return timeKind == TimeKind.TIMESTAMP
                ? timeColumn + " < CAST(? AS timestamp)"
                : timeColumn + " < ?";
    }

    /** Tam DELETE cümlesi (batched kurallarda dilimleme servis tarafında sarılır). */
    public String deleteSql() {
        return "DELETE FROM " + table + " WHERE " + resolvedWhere();
    }

    /** Dry-run: aynı WHERE ile sayım — ASLA satır silmez. */
    public String countSql() {
        return "SELECT COUNT(*) FROM " + table + " WHERE " + resolvedWhere();
    }

    /** WHERE gövdesindeki parametre sayısı (0 = öksüz temizliği, 1 = diğer hepsi). */
    public int paramCount() {
        String w = resolvedWhere();
        if (w == null) return 0;
        return (int) w.chars().filter(c -> c == '?').count();
    }
}
