package com.sitemonitor.service.dbquery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Saf kural değerlendirici (sözleşme §3). Girdi: kurallar, birleşim (ANY | ALL), sorgu sonucu ve önceki zamanlanmış
 * çalıştırmanın kural başına TABAN ölçüleri ({@link RuleResult#measure()} — değişim kuralı için). Asla fırlatmaz.
 *
 * <p>Kurallar:
 * <ul>
 *   <li>Sayısal kuralda kritik eşik önce denetlenir; sağlanırsa CRITICAL, değilse uyarı eşiği sağlanırsa WARNING. "Sağlanır"
 *       = operatörün İHLAL koşulu ({@code GT 10} → değer &gt; 10).</li>
 *   <li>Metin eşleşmesi tek seviyelidir (kritik alanı doluysa kritik, değilse uyarı); ölçü = koşulu sağlayan satır sayısı.
 *       Okunur metne hücre değeri ASLA yazılmaz (yalnız sayı) — arayüz sorgu verisini göstermez.</li>
 *   <li>Değişim kuralı önceki ölçüye göredir; önceki yoksa değerlendirilmez ("ilk çalıştırma"); yüzde kipinde önceki 0 ise
 *       adet farkı gibi davranır.</li>
 *   <li>Değerlendirilemeyen kural (kolon yok, sayı değil, satır yok …) ihlal SAYILMAZ. Kanıt EKSİKLİĞİ notlu olanı
 *       ({@link #INCONCLUSIVE_NOTES}: kolon yok, sayı değil, ilk çalıştırma, önceki çalıştırma okunamadı) "geçti" de
 *       sayılmaz — eşik alarmının kararı "bilinmiyor" olur (açılmaz, açık alarm kanıtsız kapanmaz; 2026-10-31 inceleme D3).
 *       Satır yok / değer boş (NULL) ise ihlal edecek değer yoktur — geçti sayılır.</li>
 *   <li>ANY: en az bir ihlal → BREACH, seviye = en yükseği; ALL: değerlendirilen TÜM kurallar ihlalde (hepsi
 *       değerlendirilmiş olmalı) → BREACH, seviye = en düşüğü.</li>
 *   <li>Akıllı kurallar (dbq2 D) geçmiş ölçülerle değerlendirilir ({@link Baselines#histories}): olağan dışı değer
 *       ({@code ANOMALY}) ortalama ± k·σ bandına, donmuş değer ({@code FROZEN}) ardışık aynı değer sayısına bakar. Geçmiş
 *       yetersizse not {@link #NOTE_BUILDING_BASELINE} — ihlal değil, kanıt eksikliği değil ("taban kuruluyor").</li>
 * </ul>
 * Satır sayısı sınırda kesildiyse ({@code rowCountCapped}) satır sayısı alt sınırdır ("≥ N"); kolon hesapları okunan
 * satırlar üzerindedir (not {@code partial}).
 */
public final class DbQueryEvaluator {

    public static final String LEVEL_NONE = "NONE", LEVEL_WARNING = "WARNING", LEVEL_CRITICAL = "CRITICAL";

    /** Not kodları (ön yüz {@code dbq.rr.note.<kod>} ile çevirir). */
    public static final String NOTE_COLUMN_MISSING = "column_missing", NOTE_NOT_NUMERIC = "not_numeric",
            NOTE_NO_ROWS = "no_rows", NOTE_FIRST_RUN = "first_run", NOTE_PREV_ZERO = "prev_zero",
            NOTE_PARTIAL = "partial", NOTE_CAPPED = "capped", NOTE_QUERY_FAILED = "query_failed",
            /** Değer boş (NULL — ör. boş kümede MAX) — ihlal edecek değer yok (2026-10-31). */
            NOTE_NULL_VALUE = "null_value",
            /** Değişim kuralının önceki çalıştırması okunamadı (veritabanı hatası) — ilk çalıştırma DEĞİL (2026-10-31). */
            NOTE_PREVIOUS_UNREADABLE = "prev_unreadable",
            /**
             * Değişim kuralının tabanı başka bir sorguyla ya da başka bir kural tanımıyla ölçülmüş (sorgu / kolon / hesap /
             * değişimin konusu değişti — r177, H-1): eski ölçüyle kıyaslanmaz, taban bu çalıştırmayla yeniden kurulur.
             */
            NOTE_BASELINE_RESET = "baseline_reset",
            /** Mevsimsel taban (dün / geçen hafta aynı saat) için uygun zamanlanmış çalıştırma yok (r177). */
            NOTE_NO_SEASONAL_BASELINE = "no_seasonal_baseline",
            /** Tazelik kuralı: kolon değeri bir zaman damgası olarak okunamadı (r177). */
            NOTE_NOT_TIMESTAMP = "not_timestamp",
            /** Tazelik kuralı: veritabanı saati okunamadı — yaş hesaplanamaz (r177). */
            NOTE_DB_CLOCK_UNAVAILABLE = "db_clock_unavailable",
            /**
             * Tazelik kuralı: değer veritabanı saatinden {@value #FUTURE_TOLERANCE_SECONDS} sn'den fazla ileride — büyük
             * olasılıkla yanlış saat dilimi (r177 ikinci tur, O-1). Kanıt yok: asla "taze / geçti" sayılmaz.
             */
            NOTE_FUTURE_TIMESTAMP = "future_timestamp",
            /**
             * Akıllı kural (olağan dışı / donmuş değer, dbq2 D): taban için yeterli geçmiş ölçü yok — ihlal DEĞİL, kanıt
             * eksikliği DEĞİL ({@link #WAITING_NOTES}); sonuçta {@code samples} / {@code min_samples} "n / gereken" gösterir.
             */
            NOTE_BUILDING_BASELINE = "building_baseline",
            /**
             * Olağan dışı değer (dbq2 D, inceleme D11): geçmiş taban için YETERLİ ÇALIŞTIRMA içeriyor ama örnekler
             * {@code min_samples}'ın altında — değeri boş (NULL) çalıştırmalar ya da tabandan dışlanan ihlal satırları örnek
             * vermedi. Kanıt EKSİKLİĞİ ({@link #INCONCLUSIVE_NOTES}): "geçti" sayılmaz, kontrol satırı UNKNOWN, ardışık
             * değerlendirilemeyen çalıştırma alarmını besler (bekleme notu DEĞİL).
             */
            NOTE_INSUFFICIENT_SAMPLES = "insufficient_samples";

    /** Tazelik: veritabanı saatinden bu kadar ileride olan değer saat farkı sayılır (yaş 0); daha ilerisi "gelecekte". */
    public static final long FUTURE_TOLERANCE_SECONDS = 300;

    /** Tazelik kuralında dilimsiz değerlerin varsayılan yorum dilimi (ayar okunamazsa). */
    public static final java.time.ZoneId DEFAULT_LOCAL_ZONE = java.time.ZoneId.of("Europe/Istanbul");

    /**
     * Kanıt eksikliği notları (2026-10-31 inceleme D3): bu notla değerlendirilemeyen kural "geçti" sayılmaz — eşik kaleminin
     * kararı bilinmiyor ({@code DbQueryAlarmText#thresholdVerdict}), kontrol satırı {@code UNKNOWN}. Satır yok / değer boş bu
     * kümede DEĞİL (değer yoksa ihlal de yoktur; aksi hâlde "takılı iş yok → satır yok" sorgusunun alarmı hiç kapanmazdı) —
     * tazelik kuralı hariç ({@link #isInconclusive}). "Taban kuruluyor" notları ({@link #WAITING_NOTES}) da bu kümede DEĞİL.
     */
    public static final java.util.Set<String> INCONCLUSIVE_NOTES =
            java.util.Set.of(NOTE_COLUMN_MISSING, NOTE_NOT_NUMERIC, NOTE_PREVIOUS_UNREADABLE, NOTE_NOT_TIMESTAMP,
                    NOTE_DB_CLOCK_UNAVAILABLE, NOTE_FUTURE_TIMESTAMP, NOTE_INSUFFICIENT_SAMPLES);

    /**
     * "Taban kuruluyor" notları (r177 ikinci tur, O-6): ihlal DEĞİL ve kanıt eksikliği de DEĞİL — eşik kararında kural
     * "geçti" sayılır (ANY'de başka ihlal yoksa karar OK, açık eşik alarmı kapanabilir), kontrol satırı UNKNOWN olmaz
     * (not kural sonucunda görünür), ardışık değerlendirilemeyen çalıştırma alarmını beslemez. Aksi hâlde yeni bir "geçen
     * hafta aynı saat" kuralı bir hafta boyunca açık eşik alarmının kapanmasını engellerdi.
     */
    public static final java.util.Set<String> WAITING_NOTES =
            java.util.Set.of(NOTE_FIRST_RUN, NOTE_BASELINE_RESET, NOTE_NO_SEASONAL_BASELINE, NOTE_BUILDING_BASELINE);

    /**
     * Olağan dışı değer: σ bu oranın altındaysa |ortalama| × oran kullanılır (TEK yer — "göreli taban"): hiç değişmeyen
     * (σ = 0) ya da çok az oynayan bir değerde küçük bir kıpırtı sınırsız sapma sayılmasın. Ortalama da 0 ise etkin σ = 0:
     * ortalamadan farklı her değer sınırsız sapmadır.
     */
    public static final double SIGMA_FLOOR_RATIO = 0.02;
    /** σ = 0 iken sapmanın JSON'da yazılan sınırsız değeri (JSON sonsuz taşımaz). */
    public static final double UNBOUNDED_DEVIATION = 1e9;
    /** Donmuş değer: iki ölçü bu bağıl farkın içindeyse "aynı" (kayan nokta yuvarlama farkı değişim sayılmasın). */
    static final double FROZEN_EQUAL_EPS = 1e-12;

    /**
     * Değerlendirilemeyen bir kural sonucu KANIT EKSİKLİĞİ mi (geçti sayılmaz): {@link #INCONCLUSIVE_NOTES}; tazelik kuralında
     * ayrıca satır yok / değer boş (zaman damgası yoksa tazelik bilinemez — boş tablo "taze" değildir).
     */
    public static boolean isInconclusive(String kind, String note) {
        if (note == null) return false;
        if (INCONCLUSIVE_NOTES.contains(note)) return true;
        return DbQueryRules.KIND_FRESHNESS.equals(kind) && (NOTE_NO_ROWS.equals(note) || NOTE_NULL_VALUE.equals(note));
    }

    /** Kanıt eksikliği ve "taban kuruluyor" değil — yapılandırma / veri sorunu (ardışık değerlendirilemeyen alarmı besler). */
    public static boolean isConfigUnevaluable(String kind, String note) {
        return isInconclusive(kind, note) && !WAITING_NOTES.contains(note);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param actual    ölçülen değerin okunur metni ("1.250", "%12,5 artış", "'ERROR' içeren 3 satır")
     * @param expected  beklenen koşulun okunur metni ("≤ 10 (uyarı) · ≤ 100 (kritik)")
     * @param level     NONE | WARNING | CRITICAL — ihlalin seviyesi
     * @param passed    ihlal YOK
     * @param evaluated false = değerlendirilemedi (kolon yok, sayı değil, ilk çalıştırma …) — ihlal sayılmaz
     * @param note      kısa açıklama kodu ya da metni (null olabilir)
     * @param measure   TABAN ölçü (satır sayısı / kolon toplamı …) — bir sonraki çalıştırmanın değişim kuralı bunu okur
     * @param compared  eşikle karşılaştırılan sayı (değişim kuralında değişim miktarı; diğerlerinde = measure)
     * @param fingerprint kuralın ölçüm tanımının özeti ({@link DbQueryRules#fingerprint}, r177) — bir sonraki çalıştırmanın
     *                    değişim tabanı ve geriye dönük deneme bununla eşler; bu sürümden önceki satırlarda yok (null)
     * @param stats     akıllı kuralın taban özeti ({@link Stats}, dbq2 D); öteki türlerde null
     */
    public record RuleResult(String id, String label, String kind, String actual, String expected, String level,
                             boolean passed, boolean evaluated, String note, Double measure, Double compared,
                             String fingerprint, Stats stats) {
        /** Eski on bir alanlı biçim (parmak izi yok). */
        public RuleResult(String id, String label, String kind, String actual, String expected, String level,
                          boolean passed, boolean evaluated, String note, Double measure, Double compared) {
            this(id, label, kind, actual, expected, level, passed, evaluated, note, measure, compared, null, null);
        }

        /** On iki alanlı biçim (taban özeti yok). */
        public RuleResult(String id, String label, String kind, String actual, String expected, String level,
                          boolean passed, boolean evaluated, String note, Double measure, Double compared,
                          String fingerprint) {
            this(id, label, kind, actual, expected, level, passed, evaluated, note, measure, compared, fingerprint, null);
        }

        /** Parmak izi eklenmiş kopya. */
        RuleResult withFingerprint(String fp) {
            return new RuleResult(id, label, kind, actual, expected, level, passed, evaluated, note, measure, compared, fp,
                    stats);
        }
    }

    /**
     * Akıllı kuralın taban özeti (dbq2 D) — API / {@code rule_results_json}'a yalnız dolu alanlar yazılır ({@code samples},
     * {@code min_samples}, {@code baseline_mean}, {@code baseline_sigma}, {@code band_low}, {@code band_high},
     * {@code deviation}, {@code same_runs}). Ölçülen değerlerden türetilir (satır verisi değil).
     *
     * @param samples    tabandaki örnek sayısı (donmuş değerde: görülen önceki çalıştırma sayısı)
     * @param minSamples karar için gereken örnek (donmuş değerde: uyarı için gereken önceki çalıştırma)
     * @param mean       taban ortalaması (olağan dışı değer)
     * @param sigma      ETKİN standart sapma ({@link #effectiveSigma})
     * @param bandLow    olağan aralığın alt ucu (ortalama − k_warning·σ)
     * @param bandHigh   olağan aralığın üst ucu (ortalama + k_warning·σ)
     * @param deviation  işaretli sapma (değer − ortalama) / σ; σ = 0 ve değer ≠ ortalama → ±{@link #UNBOUNDED_DEVIATION}
     * @param sameRuns   donmuş değer: bu çalıştırma dahil ardışık aynı değer sayısı (taban kuruluyorsa alt sınır)
     */
    public record Stats(Integer samples, Integer minSamples, Double mean, Double sigma, Double bandLow, Double bandHigh,
                        Double deviation, Integer sameRuns) {}

    /**
     * @param status      OK | BREACH
     * @param level       NONE | WARNING | CRITICAL
     * @param metricValue grafik değeri: ilk değerlendirilen sayısal kuralın compared değeri; yoksa satır sayısı
     */
    public record Outcome(String status, String level, List<RuleResult> results, Double metricValue) {
        /** En az bir kural kanıt eksikliğiyle değerlendirilemedi ({@link #isInconclusive}) — r177 kontrol durumu UNKNOWN. */
        public boolean inconclusive() {
            if (results == null) return false;
            for (RuleResult r : results) if (r != null && !r.evaluated() && isInconclusive(r.kind(), r.note())) return true;
            return false;
        }

        /** En az bir kural YAPILANDIRMA sorunuyla değerlendirilemedi ({@link #isConfigUnevaluable}). */
        public boolean configUnevaluable() {
            if (results == null) return false;
            for (RuleResult r : results) if (r != null && !r.evaluated() && isConfigUnevaluable(r.kind(), r.note())) return true;
            return false;
        }
    }

    /**
     * Değişim kuralının tabanları (r177): kural kimliği → taban ölçü; ölçü yoksa {@code notes}'taki not (tabanın neden olmadığı:
     * {@link #NOTE_BASELINE_RESET}, {@link #NOTE_NO_SEASONAL_BASELINE}, {@link #NOTE_PREVIOUS_UNREADABLE}); not da yoksa
     * {@link #NOTE_FIRST_RUN}.
     */
    public record Baselines(Map<String, Double> measures, Map<String, String> notes, Map<String, History> histories) {
        public static final Baselines NONE = new Baselines(Map.of(), Map.of(), Map.of());

        /** İki alanlı biçim (akıllı kural geçmişi yok). */
        public Baselines(Map<String, Double> measures, Map<String, String> notes) {
            this(measures, notes, Map.of());
        }

        Double measure(String id) { return measures == null || id == null ? null : measures.get(id); }

        String note(String id) { return notes == null || id == null ? null : notes.get(id); }

        History history(String id) { return histories == null || id == null ? null : histories.get(id); }
    }

    /**
     * Akıllı kural geçmişinin bir çalıştırması (dbq2 D).
     *
     * @param value  ölçülen değer; null = değeri olmayan çalıştırma (satır yok / NULL — sorgu çalıştı ama ölçü yok)
     * @param breach çalıştırma BU olağan dışı değer kuralını ihlal etti (canlıda saklı sonuç: tür ANOMALY, değerlendirilmiş ve
     *               geçmemiş; eşik denemesinde yeniden oynatılan karar) — tabandan dışlanır ({@link #anomalyBase}, Y1)
     */
    public record Point(Double value, boolean breach) {
        public Point {
            if (value != null && !Double.isFinite(value)) value = null;
        }
    }

    /**
     * Akıllı kuralın geçmiş ölçüleri (dbq2 D; {@code DbQueryHistory} / eşik denemesi kurar).
     *
     * @param points   YENİDEN ESKİYE geçmiş çalıştırmalar — olağan dışı değerde taban adayları (mevsimsiz: önceki
     *                 çalıştırmalar; mevsimsel: önceki günlerin / haftaların aynı saatindeki çalıştırmalar, hedef başına en
     *                 yakını), donmuş değerde bu çalıştırmadan önceki ARDIŞIK değerli çalıştırmalar
     * @param ended    donmuş değer: dizi KESİN bir kırılmayla bitti (değeri boş bir çalıştırma) — daha eskisi bu diziye
     *                 eklenemez; false = geçmiş bitti / okunmadı (daha eskisi bilinmiyor)
     * @param reset    geçmiş, ölçüm tanımı (sorgu / kolon / hesap / tür) değişen bir çalıştırmada kesildi
     * @param complete noktalar kuralın karşılaştırılabilir geçmişinin TAMAMI (okuma sınırı / tavanı kesmedi) — olağan dışı
     *                 değerde "ihlal dizisi geçmişin başına kadar uzanıyor" kabulü ({@link #anomalyBase}) yalnız o zaman
     */
    public record History(List<Point> points, boolean ended, boolean reset, boolean complete) {
        public static final History EMPTY = new History(List.of(), false, false, true);

        public History {
            points = points == null ? List.of() : List.copyOf(points);
        }

        /** Eski biçim (değerler — ihlal olmayan, değeri olan çalıştırmalar; geçmiş tam). */
        public History(List<Double> values, boolean ended, boolean reset) {
            this(toPoints(values), ended, reset, true);
        }

        private static List<Point> toPoints(List<Double> values) {
            List<Point> out = new ArrayList<>();
            if (values != null) for (Double v : values) if (v != null) out.add(new Point(v, false));
            return out;
        }

        /** Değeri olan çalıştırmaların değerleri (yeniden eskiye; ihlal satırları dahil). */
        public List<Double> values() {
            List<Double> out = new ArrayList<>(points.size());
            for (Point p : points) if (p.value() != null) out.add(p.value());
            return out;
        }
    }

    private DbQueryEvaluator() {}

    /**
     * @param previousMeasures kural kimliği → önceki zamanlanmış çalıştırmanın {@code measure} değeri (boş = ilk çalıştırma)
     * @param lang             "tr" | "en" — actual / expected metinlerinin dili
     */
    public static Outcome evaluate(List<DbQueryRules.Rule> rules, String logic, DbQueryRunner.QueryResult result,
                                   Map<String, Double> previousMeasures, String lang) {
        return evaluate(rules, logic, result, previousMeasures, lang, false);
    }

    /**
     * @param previousUnreadable önceki çalıştırma OKUNAMADI (veritabanı hatası) — değişim kuralı "ilk çalıştırma" değil
     *                           {@link #NOTE_PREVIOUS_UNREADABLE} notuyla değerlendirilemez (kanıt eksikliği, D3)
     */
    public static Outcome evaluate(List<DbQueryRules.Rule> rules, String logic, DbQueryRunner.QueryResult result,
                                   Map<String, Double> previousMeasures, String lang, boolean previousUnreadable) {
        Map<String, String> notes = new HashMap<>();
        if (previousUnreadable && rules != null) {
            for (DbQueryRules.Rule r : rules) if (r != null && r.id() != null) notes.put(r.id(), NOTE_PREVIOUS_UNREADABLE);
        }
        return evaluate(rules, logic, result, new Baselines(previousMeasures == null ? Map.of() : previousMeasures, notes), lang);
    }

    /** Kural başına taban ölçü + tabanın yokluğunun nedeni ({@link Baselines}) — r177; dilimsiz değerler {@link #DEFAULT_LOCAL_ZONE}. */
    public static Outcome evaluate(List<DbQueryRules.Rule> rules, String logic, DbQueryRunner.QueryResult result,
                                   Baselines baselines, String lang) {
        return evaluate(rules, logic, result, baselines, lang, DEFAULT_LOCAL_ZONE);
    }

    /**
     * @param localZone tazelik kuralında saat dilimi OLMAYAN değerlerin yorum dilimi (kuralın kendi {@code timezone}'u yoksa;
     *                  ayar {@code site.monitor.dbquery.local-timezone}); null = {@link #DEFAULT_LOCAL_ZONE}
     */
    public static Outcome evaluate(List<DbQueryRules.Rule> rules, String logic, DbQueryRunner.QueryResult result,
                                   Baselines baselines, String lang, java.time.ZoneId localZone) {
        boolean en = "en".equalsIgnoreCase(lang);
        List<DbQueryRules.Rule> rs = rules == null ? List.of() : rules;
        Baselines base = baselines == null ? Baselines.NONE : baselines;
        Double rowMetric = result == null ? null : (double) result.rowCount();
        if (result == null || !result.ok()) {
            List<RuleResult> out = new ArrayList<>();
            for (DbQueryRules.Rule r : rs) {
                if (r == null) continue;
                out.add(new RuleResult(r.id(), r.label(), r.kind(), "—", expected(r, en), LEVEL_NONE, true, false,
                        NOTE_QUERY_FAILED, null, null, DbQueryRules.fingerprint(r)));
            }
            return new Outcome("OK", LEVEL_NONE, List.copyOf(out), null);
        }
        Ctx ctx = new Ctx(result, en, localZone == null ? DEFAULT_LOCAL_ZONE : localZone);
        List<RuleResult> out = new ArrayList<>();
        for (DbQueryRules.Rule r : rs) {
            if (r == null) continue;
            RuleResult rr;
            try {
                rr = evaluateRule(r, ctx, base.measure(r.id()), base.note(r.id()), base.history(r.id()));
            } catch (RuntimeException e) {
                rr = new RuleResult(r.id(), r.label(), r.kind(), "—", expected(r, en), LEVEL_NONE, true, false,
                        NOTE_NOT_NUMERIC, null, null);
            }
            out.add(rr.withFingerprint(DbQueryRules.fingerprint(r)));
        }
        boolean all = "ALL".equalsIgnoreCase(logic == null ? "" : logic.trim());
        String status = "OK", level = LEVEL_NONE;
        if (!out.isEmpty()) {
            if (all) {
                boolean breach = true;
                String min = LEVEL_CRITICAL;
                for (RuleResult rr : out) {
                    if (!rr.evaluated() || rr.passed()) { breach = false; break; }
                    if (rank(rr.level()) < rank(min)) min = rr.level();
                }
                if (breach) { status = "BREACH"; level = min; }
            } else {
                String max = LEVEL_NONE;
                for (RuleResult rr : out)
                    if (rr.evaluated() && !rr.passed() && rank(rr.level()) > rank(max)) max = rr.level();
                if (!LEVEL_NONE.equals(max)) { status = "BREACH"; level = max; }
            }
        }
        Double metric = rowMetric;
        for (RuleResult rr : out) {
            // Akıllı kural taban kurulurken de değeri ölçer — grafik o kuralın değerini çizsin (satır sayısına düşmesin)
            boolean measured = rr.evaluated() || DbQueryRules.isSmart(rr.kind());
            if (measured && rr.compared() != null && !DbQueryRules.KIND_TEXT_MATCH.equals(rr.kind())) {
                metric = rr.compared();
                break;
            }
        }
        return new Outcome(status, level, List.copyOf(out), metric);
    }

    private static int rank(String level) {
        if (LEVEL_CRITICAL.equals(level)) return 2;
        if (LEVEL_WARNING.equals(level)) return 1;
        return 0;
    }

    // ── Kural başına ─────────────────────────────────────────────────────────

    private record Ctx(DbQueryRunner.QueryResult result, boolean en, java.time.ZoneId localZone) {}

    private static RuleResult evaluateRule(DbQueryRules.Rule r, Ctx ctx, Double previous, String missingNote,
                                           History history) {
        boolean en = ctx.en();
        String exp = expected(r, en);
        DbQueryRunner.QueryResult qr = ctx.result();
        switch (r.kind()) {
            case DbQueryRules.KIND_ROW_COUNT -> {
                double v = qr.rowCount();
                String level = numericLevel(r, v);
                String actual = (qr.rowCountCapped() ? "≥ " : "") + fmt(v, en) + (en ? " rows" : " satır");
                return new RuleResult(r.id(), r.label(), r.kind(), actual, exp, level, LEVEL_NONE.equals(level), true,
                        qr.rowCountCapped() ? NOTE_CAPPED : null, v, v);
            }
            case DbQueryRules.KIND_COLUMN_VALUE -> {
                Agg a = aggregate(qr, r.column(), r.aggregate());
                if (a.value() == null)
                    return notEvaluated(r, exp, a.note());
                String level = numericLevel(r, a.value());
                return new RuleResult(r.id(), r.label(), r.kind(), fmt(a.value(), en), exp, level,
                        LEVEL_NONE.equals(level), true, a.note(), a.value(), a.value());
            }
            case DbQueryRules.KIND_TEXT_MATCH -> {
                return textMatch(r, qr, exp, en);
            }
            case DbQueryRules.KIND_CHANGE -> {
                return change(r, qr, previous, missingNote, exp, en);
            }
            case DbQueryRules.KIND_FRESHNESS -> {
                return freshness(r, qr, exp, en, ruleZone(r, ctx.localZone()));
            }
            case DbQueryRules.KIND_ANOMALY, DbQueryRules.KIND_FROZEN -> {
                Agg a = aggregate(qr, r.column(), r.aggregate());
                if (a.value() == null) return notEvaluated(r, exp, a.note());
                return DbQueryRules.KIND_ANOMALY.equals(r.kind())
                        ? anomalyAgainst(r, a.value(), history, missingNote, a.note(), exp, en)
                        : frozenAgainst(r, a.value(), history, missingNote, a.note(), exp, en);
            }
            default -> {
                return notEvaluated(r, exp, NOTE_NOT_NUMERIC);
            }
        }
    }

    private static RuleResult notEvaluated(DbQueryRules.Rule r, String exp, String note) {
        return new RuleResult(r.id(), r.label(), r.kind(), "—", exp, LEVEL_NONE, true, false, note, null, null);
    }

    /** Kritik önce; ihlal koşulu operatöre göre. */
    static String numericLevel(DbQueryRules.Rule r, double v) {
        if (r.critical() != null && violates(r.operator(), v, r.critical())) return LEVEL_CRITICAL;
        if (r.warning() != null && violates(r.operator(), v, r.warning())) return LEVEL_WARNING;
        return LEVEL_NONE;
    }

    static boolean violates(String op, double v, DbQueryRules.Threshold t) {
        if (t == null) return false;
        if ("BETWEEN".equals(op) || "OUTSIDE".equals(op)) {
            if (!t.isRange()) return false;
            boolean inside = v >= t.low() && v <= t.high();
            return "BETWEEN".equals(op) == inside;
        }
        if (t.value() == null) return false;
        double x = t.value();
        return switch (op) {
            case "GT" -> v > x;
            case "GTE" -> v >= x;
            case "LT" -> v < x;
            case "LTE" -> v <= x;
            case "EQ" -> Double.compare(v, x) == 0;
            case "NEQ" -> Double.compare(v, x) != 0;
            default -> false;
        };
    }

    // ── Kolon hesabı ─────────────────────────────────────────────────────────

    private record Agg(Double value, String note) {}

    static int columnIndex(DbQueryRunner.QueryResult qr, String column) {
        if (column == null || qr.columns() == null) return -1;
        String c = column.trim();
        for (int i = 0; i < qr.columns().size(); i++) if (qr.columns().get(i).name().equalsIgnoreCase(c)) return i;
        return -1;
    }

    private static Agg aggregate(DbQueryRunner.QueryResult qr, String column, String aggregate) {
        int idx = columnIndex(qr, column);
        if (idx < 0) return new Agg(null, NOTE_COLUMN_MISSING);
        List<List<Object>> rows = qr.rows() == null ? List.of() : qr.rows();
        String partial = qr.rowCountCapped() || qr.valuesTruncated() || rows.size() < qr.rowCount() ? NOTE_PARTIAL : null;
        String agg = aggregate == null ? "FIRST" : aggregate;
        if ("COUNT_NONNULL".equals(agg)) {
            long n = 0;
            for (List<Object> row : rows) if (idx < row.size() && row.get(idx) != null) n++;
            return new Agg((double) n, partial);
        }
        if ("FIRST".equals(agg)) {
            if (rows.isEmpty()) return new Agg(null, NOTE_NO_ROWS);
            List<Object> first = rows.get(0);
            Object cell = idx < first.size() ? first.get(idx) : null;
            if (cell == null) return new Agg(null, NOTE_NULL_VALUE);   // boş kümede MAX(...) = NULL — ihlal edecek değer yok
            Double d = numeric(cell);
            return d == null ? new Agg(null, NOTE_NOT_NUMERIC) : new Agg(d, null);
        }
        double sum = 0, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        long count = 0, nonNumeric = 0;
        for (List<Object> row : rows) {
            Object cell = idx < row.size() ? row.get(idx) : null;
            if (cell == null) continue;
            Double d = numeric(cell);
            if (d == null) { nonNumeric++; continue; }
            sum += d;
            if (d < min) min = d;
            if (d > max) max = d;
            count++;
        }
        if (count == 0) {
            if ("SUM".equals(agg) && nonNumeric == 0) return new Agg(0.0, rows.isEmpty() ? NOTE_NO_ROWS : partial);
            if (nonNumeric > 0) return new Agg(null, NOTE_NOT_NUMERIC);
            return new Agg(null, rows.isEmpty() ? NOTE_NO_ROWS : NOTE_NULL_VALUE);   // satır var ama hepsi NULL
        }
        double v = switch (agg) {
            case "MAX" -> max;
            case "MIN" -> min;
            case "SUM" -> sum;
            case "AVG" -> sum / count;
            default -> Double.NaN;
        };
        if (!Double.isFinite(v)) return new Agg(null, NOTE_NOT_NUMERIC);
        return new Agg(v, partial);
    }

    /** Hücre → sayı: BigDecimal / Number; metin ise yalın sayı biçimi (nokta ondalık); diğerleri sayı değil. */
    static Double numeric(Object cell) {
        if (cell == null || cell instanceof Boolean) return null;
        if (cell instanceof Number n) {
            double d = n instanceof BigDecimal bd ? bd.doubleValue() : n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        if (cell instanceof String s) return DbQueryRules.toDouble(s);
        return null;
    }

    // ── Metin eşleşmesi ──────────────────────────────────────────────────────

    private static RuleResult textMatch(DbQueryRules.Rule r, DbQueryRunner.QueryResult qr, String exp, boolean en) {
        int idx = columnIndex(qr, r.column());
        if (idx < 0) return notEvaluated(r, exp, NOTE_COLUMN_MISSING);
        List<List<Object>> rows = qr.rows() == null ? List.of() : qr.rows();
        String scope = r.scope() == null ? "ANY_ROW" : r.scope();
        DbQueryRules.Threshold t = r.critical() != null ? r.critical() : r.warning();
        String lvl = r.critical() != null ? LEVEL_CRITICAL : LEVEL_WARNING;
        String needle = t == null || t.text() == null ? "" : t.text();
        TextMatcher m = new TextMatcher(r.operator(), needle);
        if ("FIRST_ROW".equals(scope) || "ALL_ROWS".equals(scope)) {
            if (rows.isEmpty()) return notEvaluated(r, exp, NOTE_NO_ROWS);
        }
        long matched = 0, total = 0;
        int limit = "FIRST_ROW".equals(scope) ? Math.min(1, rows.size()) : rows.size();
        for (int i = 0; i < limit; i++) {
            List<Object> row = rows.get(i);
            Object cell = idx < row.size() ? row.get(idx) : null;
            total++;
            if (m.matches(cell == null ? null : cellText(cell))) matched++;
        }
        boolean violated = switch (scope) {
            case "ALL_ROWS" -> total > 0 && matched == total;
            default -> matched > 0;
        };
        String note = qr.rowCountCapped() || qr.valuesTruncated() || rows.size() < qr.rowCount() ? NOTE_PARTIAL : null;
        String what = textCondition(r.operator(), needle, en);
        String actual;
        if ("FIRST_ROW".equals(scope)) {
            actual = en ? (violated ? "first row: " + what : "first row: not " + what)
                    : (violated ? "ilk satır: " + what : "ilk satır: " + what + " değil");
        } else if ("ALL_ROWS".equals(scope)) {
            actual = en ? fmt(matched, true) + " / " + fmt(total, true) + " rows " + what
                    : what + " " + fmt(matched, false) + " / " + fmt(total, false) + " satır";
        } else {
            actual = en ? fmt(matched, true) + " rows " + what : what + " " + fmt(matched, false) + " satır";
        }
        String level = violated ? lvl : LEVEL_NONE;
        return new RuleResult(r.id(), r.label(), r.kind(), actual, exp, level, !violated, true, note,
                (double) matched, (double) matched);
    }

    /** Hücrenin metin biçimi (sayı → düz yazım). */
    static String cellText(Object cell) {
        if (cell instanceof BigDecimal bd) return bd.toPlainString();
        return String.valueOf(cell);
    }

    /**
     * Büyük-küçük harf duyarsız metin karşılaştırıcı. İçerme araması KMP ile DOĞRUSAL (hücre ≤ 4000 karakter × çok satır;
     * saf {@code indexOf} kötü durumda karesel olurdu). Karşılaştırma karakter katlamasıyla (İ/i, I/ı dahil).
     */
    static final class TextMatcher {
        private final String op;
        private final String needle;
        private final int[] fail;

        TextMatcher(String op, String rawNeedle) {
            this.op = op == null ? "CONTAINS" : op;
            this.needle = fold(rawNeedle == null ? "" : rawNeedle);
            this.fail = kmpTable(this.needle);
        }

        boolean matches(String value) {
            switch (op) {
                case "EMPTY":
                    return value == null || value.isBlank();
                case "NOT_EMPTY":
                    return value != null && !value.isBlank();
                case "EQUALS":
                    return value != null && fold(value.trim()).equals(needle.trim());
                case "NOT_EQUALS":
                    return value == null || !fold(value.trim()).equals(needle.trim());
                case "NOT_CONTAINS":
                    return value == null || !contains(fold(value));
                case "CONTAINS":
                default:
                    return value != null && contains(fold(value));
            }
        }

        private boolean contains(String hay) {
            if (needle.isEmpty()) return true;
            int k = 0;
            for (int i = 0; i < hay.length(); i++) {
                char c = hay.charAt(i);
                while (k > 0 && needle.charAt(k) != c) k = fail[k - 1];
                if (needle.charAt(k) == c) k++;
                if (k == needle.length()) return true;
            }
            return false;
        }

        private static int[] kmpTable(String p) {
            int[] f = new int[Math.max(1, p.length())];
            int k = 0;
            for (int i = 1; i < p.length(); i++) {
                while (k > 0 && p.charAt(k) != p.charAt(i)) k = f[k - 1];
                if (p.charAt(k) == p.charAt(i)) k++;
                f[i] = k;
            }
            return f;
        }

        /** Karakter katlaması: büyük harf → küçük harf (İ → i, I → i, ı → i) — dil bağımsız, uzunluk korunur. */
        static String fold(String s) {
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                sb.append(Character.toLowerCase(Character.toUpperCase(s.charAt(i))));
            }
            return sb.toString();
        }
    }

    // ── Değişim ──────────────────────────────────────────────────────────────

    private static RuleResult change(DbQueryRules.Rule r, DbQueryRunner.QueryResult qr, Double previous,
                                     String missingNote, String exp, boolean en) {
        DbQueryRules.Change ch = r.change() == null ? new DbQueryRules.Change("ROW_COUNT", "PERCENT", "ANY") : r.change();
        Double base;
        String baseNote = null;
        if ("COLUMN".equals(ch.of())) {
            Agg a = aggregate(qr, r.column(), r.aggregate());
            if (a.value() == null) return notEvaluated(r, exp, a.note());
            base = a.value();
            baseNote = a.note();
        } else {
            base = (double) qr.rowCount();
            if (qr.rowCountCapped()) baseNote = NOTE_CAPPED;
        }
        if (previous == null || !Double.isFinite(previous)) {
            return new RuleResult(r.id(), r.label(), r.kind(), fmt(base, en), exp, LEVEL_NONE, true, false,
                    missingNote != null ? missingNote : NOTE_FIRST_RUN, base, null);
        }
        return changeAgainst(r, base, previous, baseNote, exp, en);
    }

    /**
     * Değişim kuralının eşik kararı — ölçü ({@code base}) ve taban ({@code previous}) bilindiğinde. Geriye dönük deneme saklı
     * ölçülerle de bunu kullanır (aynı hesap, aynı metin).
     */
    static RuleResult changeAgainst(DbQueryRules.Rule r, double base, double previous, String baseNote, String exp,
                                    boolean en) {
        DbQueryRules.Change ch = r.change() == null ? new DbQueryRules.Change("ROW_COUNT", "PERCENT", "ANY") : r.change();
        double diff = base - previous;
        double signed = switch (ch.direction()) {
            case "INCREASE" -> diff;
            case "DECREASE" -> -diff;
            default -> Math.abs(diff);
        };
        boolean percent = "PERCENT".equals(ch.mode());
        String note = baseNote;
        double compared;
        if (percent && previous != 0) {
            compared = signed / Math.abs(previous) * 100.0;
        } else {
            compared = signed;
            if (percent) { percent = false; note = NOTE_PREV_ZERO; }
        }
        String level = numericLevel(r, compared);
        String dirWord = changeWord(diff, ch.direction(), en);
        String amount = percent ? pct(Math.abs(diff) / Math.abs(previous) * 100.0, en) : fmt(Math.abs(diff), en);
        String span = " (" + fmt(previous, en) + " → " + fmt(base, en) + ")";
        String actual = (diff == 0 ? dirWord : amount + " " + dirWord) + span;
        return new RuleResult(r.id(), r.label(), r.kind(), actual, exp, level, LEVEL_NONE.equals(level), true, note,
                base, round(compared));
    }

    // ── Akıllı kurallar (dbq2 D) ─────────────────────────────────────────────

    /** Etkin σ ("göreli taban", TEK yer): {@code max(σ, |ortalama| × }{@value #SIGMA_FLOOR_RATIO}{@code )}. */
    public static double effectiveSigma(double mean, double sigma) {
        double floor = Math.abs(mean) * SIGMA_FLOOR_RATIO;
        double s = Double.isFinite(sigma) ? sigma : 0;
        return Math.max(s, floor);
    }

    /**
     * Olağan dışı değerin tabanı (Y1 — ürün kararı 2026-11-05): geçmiş YENİDEN ESKİYE yürünür, bu kuralı İHLAL EDEN
     * çalıştırmalar tabandan DIŞLANIR (kalıcı bir sapma kendi tabanını şişirip alarmı sahte kapatmasın). KONTROLLÜ UYUM:
     * ardışık ihlal dizisi (değeri boş çalıştırma diziyi kırmaz) {@code window} çalıştırmaya ulaştıysa — ya da dizi geçmişin
     * TAMAMINI ({@link History#complete}) kaplıyor ve en az {@code min_samples} uzunluktaysa (saklama süresinden uzun
     * mevsimsel pencere) — o dizinin satırları tabana yeniden girer: yeni düzey olağan sayılır. Taban = dışlanmayan en yeni
     * {@code window} çalıştırma; değeri boş çalıştırma pencereyi doldurur ama örnek değildir. Canlı, teyit / kurtarma, elle
     * kontrol ve eşik denemesi AYNI kuralı kullanır (yalnız ihlal bayrağının kaynağı farklı).
     *
     * @return örnekler, yürünen çalıştırma sayısı ({@code seen}, dışlananlar dahil), pencereyi dolduran çalıştırma sayısı
     *         ({@code consumed}) ve geçmişin okuma sınırında tükenip tükenmediği ({@code cut})
     */
    static AnomalyBase anomalyBase(History history, int window, int minSamples) {
        List<Point> pts = history == null ? List.of() : history.points();
        boolean complete = history == null || history.complete();
        List<Double> samples = new ArrayList<>();
        int seen = 0, consumed = 0, i = 0, n = pts.size();
        while (i < n && consumed < window) {
            Point p = pts.get(i);
            if (p.value() == null || !p.breach()) {
                seen++;
                consumed++;
                if (p.value() != null) samples.add(p.value());
                i++;
                continue;
            }
            int j = i, len = 0;   // ardışık ihlal dizisi [i, j)
            while (j < n) {
                Point q = pts.get(j);
                if (q.value() != null && !q.breach()) break;
                if (q.value() != null) len++;
                j++;
            }
            boolean accept = len >= window || (j >= n && complete && len >= minSamples);
            for (int k = i; k < j && consumed < window; k++) {
                Point q = pts.get(k);
                seen++;
                if (q.value() == null) {
                    consumed++;
                } else if (accept) {
                    consumed++;
                    samples.add(q.value());
                }
            }
            i = j;
        }
        return new AnomalyBase(List.copyOf(samples), seen, consumed, i >= n && !complete);
    }

    /** {@link #anomalyBase} sonucu. */
    record AnomalyBase(List<Double> samples, int seen, int consumed, boolean cut) {}

    /**
     * Olağan dışı değer kararı — değer ({@code v}) ve geçmiş ({@code history}) bilindiğinde; eşik denemesi saklı ölçülerle de
     * bunu kullanır (aynı hesap, aynı metin). Taban {@link #anomalyBase} (ihlal satırları dışlanır, kontrollü uyum).
     * Örnek &lt; {@code min_samples}: geçmiş yeterince çalıştırma içeriyorsa (mevsimsiz: pencere doldu ya da geçmiş okuma
     * sınırında tükendi; mevsimsel: en az {@code min_samples} hedefte çalıştırma var) ama değeri boş / dışlanan satırlar örnek
     * vermediyse {@link #NOTE_INSUFFICIENT_SAMPLES} (kanıt eksikliği, D11); değilse {@link #NOTE_BUILDING_BASELINE} (taban
     * sıfırlandıysa ve örnek yoksa {@link #NOTE_BASELINE_RESET}). Ortalama ve ÖRNEK standart sapması (n − 1); etkin σ
     * {@link #effectiveSigma}; sapma z = (değer − ortalama) / σ (6 ondalığa yuvarlı). Yön: iki yön |z|, yukarı z, aşağı −z;
     * kritik katsayısını AŞAN sapma kritik, uyarı katsayısını aşan uyarı (sınırın kendisi olağan). Geçmiş okunamadıysa
     * ({@code missingNote}) değerlendirilmez, kanıt eksikliği.
     */
    static RuleResult anomalyAgainst(DbQueryRules.Rule r, double v, History history, String missingNote, String valueNote,
                                     String exp, boolean en) {
        DbQueryRules.Anomaly p = DbQueryRules.anomalyOf(r);
        String val = fmt(v, en);
        if (history == null && missingNote != null)
            return new RuleResult(r.id(), r.label(), r.kind(), val, exp, LEVEL_NONE, true, false, missingNote, v, v, null, null);
        AnomalyBase base = anomalyBase(history, p.window(), p.minSamples());
        List<Double> all = base.samples();
        int n = all.size();
        if (n < p.minSamples()) {
            boolean seasonal = !DbQueryRules.SEASON_NONE.equals(p.season());
            boolean insufficient = seasonal ? base.seen() >= p.minSamples() : base.consumed() >= p.window() || base.cut();
            String note = insufficient ? NOTE_INSUFFICIENT_SAMPLES
                    : n == 0 && history != null && history.reset() ? NOTE_BASELINE_RESET : NOTE_BUILDING_BASELINE;
            String actual = val + " · " + (insufficient ? (en ? "too few samples (" : "örnek yetersiz (")
                    : (en ? "building baseline (" : "taban kuruluyor (")) + n + " / " + p.minSamples() + ")";
            return new RuleResult(r.id(), r.label(), r.kind(), actual, exp, LEVEL_NONE, true, false, note, v, v, null,
                    new Stats(n, p.minSamples(), null, null, null, null, null, null));
        }
        double sum = 0, min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            double x = all.get(i);
            sum += x;
            if (x < min) min = x;
        }
        double mean = sum / n, ss = 0;
        for (int i = 0; i < n; i++) {
            double d = all.get(i) - mean;
            ss += d * d;
        }
        double sigma = effectiveSigma(mean, n > 1 ? Math.sqrt(ss / (n - 1)) : 0);
        double dev;
        // İlkel eşitlik (D2): −0.0 == 0.0 — "−0" sonucu sınırsız sapma sayılmaz
        if (sigma == 0) dev = v == mean ? 0 : (v > mean ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY);
        else dev = round((v - mean) / sigma);
        double toward = switch (p.direction()) {
            case DbQueryRules.DIRECTION_UP -> dev;
            case DbQueryRules.DIRECTION_DOWN -> -dev;
            default -> Math.abs(dev);
        };
        String level = toward > p.kCritical() ? LEVEL_CRITICAL : toward > p.kWarning() ? LEVEL_WARNING : LEVEL_NONE;
        double low = mean - p.kWarning() * sigma, high = mean + p.kWarning() * sigma;
        // Görüntü: geçmişin hepsi ≥ 0 ise (sayılar) alt uç 0'ın altına yazılmaz — karar ham sapmayla verilir
        if (min >= 0 && low < 0) low = 0;
        String actual = val + " · " + bandText(p.direction(), low, high, sigma == 0, en) + " · "
                + (en ? "deviation " : "sapma ") + deviationText(dev, en);
        double shown = Double.isInfinite(dev) ? Math.copySign(UNBOUNDED_DEVIATION, dev) : dev;
        return new RuleResult(r.id(), r.label(), r.kind(), actual, exp, level, LEVEL_NONE.equals(level), true, valueNote,
                v, v, null, new Stats(n, p.minSamples(), round(mean), round(sigma), round(low), round(high), shown, null));
    }

    /** "olağan 300–700" / "olağan ≤ 700" (yukarı) / "olağan ≥ 300" (aşağı) / "olağan 500" (σ = 0). */
    private static String bandText(String direction, double low, double high, boolean flat, boolean en) {
        String word = en ? "usual " : "olağan ";
        if (flat) return word + fmt(DbQueryRules.DIRECTION_DOWN.equals(direction) ? low : high, en);
        if (DbQueryRules.DIRECTION_UP.equals(direction)) return word + "≤ " + fmt(high, en);
        if (DbQueryRules.DIRECTION_DOWN.equals(direction)) return word + "≥ " + fmt(low, en);
        return word + fmt(low, en) + "–" + fmt(high, en);
    }

    /** "+9,4σ" / "−3σ" / "sınırsız" (σ = 0). */
    private static String deviationText(double dev, boolean en) {
        if (Double.isInfinite(dev)) return (dev > 0 ? "+" : "−") + (en ? "unbounded" : "sınırsız");
        double one = BigDecimal.valueOf(Math.abs(dev)).setScale(1, RoundingMode.HALF_UP).doubleValue();
        return (dev < 0 && one > 0 ? "−" : "+") + fmt(one, en) + "σ";
    }

    /**
     * Donmuş değer kararı — değer ({@code v}) ve önceki ardışık ölçüler ({@code history}, yeniden eskiye) bilindiğinde;
     * eşik denemesi de bunu kullanır. Dizi = bu çalıştırma + değeri AYNI ({@link #sameValue}) kalan önceki ölçüler. Dizi
     * {@code runs_critical}'e ulaştıysa kritik, {@code runs_warning}'e ulaştıysa uyarı. Geçmiş tükendi ve dizi henüz uyarıya
     * ulaşmadıysa (daha eskisi bilinmiyor) {@link #NOTE_BUILDING_BASELINE}; farklı bir değer ya da KESİN kırılma (değeri boş
     * çalıştırma, {@link History#ended}) dizinin uzunluğunu kesinleştirir.
     */
    static RuleResult frozenAgainst(DbQueryRules.Rule r, double v, History history, String missingNote, String valueNote,
                                    String exp, boolean en) {
        DbQueryRules.Frozen p = DbQueryRules.frozenOf(r);
        String val = fmt(v, en);
        if (history == null && missingNote != null)
            return new RuleResult(r.id(), r.label(), r.kind(), val, exp, LEVEL_NONE, true, false, missingNote, v, v, null, null);
        List<Double> prev = history == null ? List.of() : history.values();
        int streak = 1;
        boolean known = false;
        for (Double x : prev) {
            if (x != null && sameValue(x, v)) {
                streak++;
            } else {
                known = true;
                break;
            }
        }
        if (!known) known = (history != null && history.ended()) || prev.size() >= p.runsMax() - 1;
        String level = p.runsCritical() != null && streak >= p.runsCritical() ? LEVEL_CRITICAL
                : streak >= p.runsWarning() ? LEVEL_WARNING : LEVEL_NONE;
        Stats stats = new Stats(prev.size(), p.runsWarning() - 1, null, null, null, null, null, streak);
        if (!known && LEVEL_NONE.equals(level)) {
            String note = prev.isEmpty() && history != null && history.reset() ? NOTE_BASELINE_RESET : NOTE_BUILDING_BASELINE;
            String actual = streak >= 2
                    ? (en ? "same value for at least " + streak + " runs · " : "aynı değer en az " + streak + " çalıştırmadır · ") + val
                    : val + " · " + (en ? "building baseline (" : "taban kuruluyor (") + prev.size() + " / "
                            + (p.runsWarning() - 1) + ")";
            return new RuleResult(r.id(), r.label(), r.kind(), actual, exp, LEVEL_NONE, true, false, note, v, v, null, stats);
        }
        String actual = streak >= 2
                ? (en ? "same value for " + streak + " runs · " : "aynı değer " + streak + " çalıştırmadır · ") + val
                : (en ? "value changed · " : "değer değişti · ") + val;
        return new RuleResult(r.id(), r.label(), r.kind(), actual, exp, level, LEVEL_NONE.equals(level), true, valueNote,
                v, v, null, stats);
    }

    /**
     * Donmuş değerin "aynı" kuralı: tam sayılar birebir ({@code −0.0} ile {@code 0.0} aynı); ondalıklarda bağıl
     * {@value #FROZEN_EQUAL_EPS} farkı (aynı verinin toplamı / ortalaması satır sırasına göre son bitte değişebilir) aynı
     * sayılır.
     */
    static boolean sameValue(double a, double b) {
        if (a == b) return true;   // ilkel eşitlik (D2): −0.0 == 0.0
        if (!Double.isFinite(a) || !Double.isFinite(b)) return false;
        if (a == Math.rint(a) && b == Math.rint(b)) return false;
        return Math.abs(a - b) <= FROZEN_EQUAL_EPS * Math.max(Math.abs(a), Math.abs(b));
    }

    // ── Tazelik (r177) ───────────────────────────────────────────────────────

    /** Zaman damgası metninin üst sınırı (daha uzunu tarih değildir — doğrusal, sınırlı ayrıştırma). */
    static final int MAX_TIMESTAMP_CHARS = 64;

    /** Kuralın dilimi (geçerliyse), yoksa ayardaki yerel dilim. */
    static java.time.ZoneId ruleZone(DbQueryRules.Rule r, java.time.ZoneId fallback) {
        java.time.ZoneId base = fallback == null ? DEFAULT_LOCAL_ZONE : fallback;
        if (r == null || r.timezone() == null) return base;
        try {
            return java.time.ZoneId.of(r.timezone());
        } catch (RuntimeException e) {
            return base;
        }
    }

    /**
     * Tazelik: kolondaki zaman damgalarının hesabı (MAX = en yeni, MIN = en eski, FIRST = ilk satır) veritabanı saatine göre
     * YAŞA (saniye) çevrilir — yaş = veritabanının şu anki ANI − değer. Saat dilimli değerler (PostgreSQL {@code timestamptz},
     * MSSQL {@code datetimeoffset}, Oracle {@code TIMESTAMP WITH TIME ZONE}) mutlaktır — çalıştırıcı anlarını ayrıca verir
     * ({@link DbQueryRunner.QueryResult#instants}); dilimsiz değerler (PostgreSQL {@code timestamp}, MSSQL
     * {@code datetime / datetime2}, Oracle {@code DATE / TIMESTAMP}) TÜM türlerde aynı kuralla {@code zone}'da (kuralın
     * {@code timezone}'u, yoksa ayar) yorumlanır (r177 ikinci tur, O-1). Değer veritabanı saatinden
     * {@value #FUTURE_TOLERANCE_SECONDS} sn'ye kadar ileride → yaş 0 (saat farkı); daha ilerisi →
     * {@link #NOTE_FUTURE_TIMESTAMP} (değerlendirilemez — yanlış dilim; asla "geçti"). Satır yok / değer boş → kanıt eksikliği
     * ({@link #isInconclusive}); veritabanı saati okunamadı → {@link #NOTE_DB_CLOCK_UNAVAILABLE}; değer tarih değil →
     * {@link #NOTE_NOT_TIMESTAMP}.
     */
    private static RuleResult freshness(DbQueryRules.Rule r, DbQueryRunner.QueryResult qr, String exp, boolean en,
                                        java.time.ZoneId zone) {
        int idx = columnIndex(qr, r.column());
        if (idx < 0) return notEvaluated(r, exp, NOTE_COLUMN_MISSING);
        List<List<Object>> rows = qr.rows() == null ? List.of() : qr.rows();
        if (rows.isEmpty()) return notEvaluated(r, exp, NOTE_NO_ROWS);
        DbQueryRunner.DbClock clock = qr.dbClock();
        if (clock == null || clock.now() == null) return notEvaluated(r, exp, NOTE_DB_CLOCK_UNAVAILABLE);
        List<Long> absolute = qr.instants() == null ? null : qr.instants().get(idx);
        String agg = r.aggregate() == null ? "MAX" : r.aggregate();
        java.time.Instant picked = null;
        boolean anyValue = false;
        int limit = "FIRST".equals(agg) ? Math.min(1, rows.size()) : rows.size();
        for (int i = 0; i < limit; i++) {
            List<Object> row = rows.get(i);
            Object cell = idx < row.size() ? row.get(idx) : null;
            if (cell == null) continue;
            anyValue = true;
            java.time.Instant t;
            if (absolute != null) {
                Long ms = i < absolute.size() ? absolute.get(i) : null;
                t = ms == null ? null : java.time.Instant.ofEpochMilli(ms);
            } else {
                t = timestampOf(cell, zone);
            }
            if (t == null) return notEvaluated(r, exp, NOTE_NOT_TIMESTAMP);
            if (picked == null || ("MIN".equals(agg) ? t.isBefore(picked) : t.isAfter(picked))) picked = t;
        }
        if (!anyValue || picked == null) return notEvaluated(r, exp, NOTE_NULL_VALUE);
        double age = (clock.now().toEpochMilli() - picked.toEpochMilli()) / 1000.0;
        if (age < -FUTURE_TOLERANCE_SECONDS) {
            String ahead = en ? "in the future (" + duration(-age, true) + " ahead of the database clock)"
                    : "gelecekte (veritabanı saatinden " + duration(-age, false) + " ileride)";
            return new RuleResult(r.id(), r.label(), r.kind(), ahead, exp, LEVEL_NONE, true, false, NOTE_FUTURE_TIMESTAMP,
                    null, null);
        }
        age = round(Math.max(0, age));
        String level = numericLevel(r, age);
        String partial = qr.rowCountCapped() || qr.valuesTruncated() || rows.size() < qr.rowCount() ? NOTE_PARTIAL : null;
        String actual = en ? "age " + duration(age, true) : "yaş " + duration(age, false);
        return new RuleResult(r.id(), r.label(), r.kind(), actual, exp, level, LEVEL_NONE.equals(level), true, partial,
                age, age);
    }

    /**
     * Hücre → an. Çalıştırıcının okuduğu biçimler: ISO yerel tarih-saat ({@code 2026-10-31T10:15:30.123}), saat dilimli ISO
     * ({@code …+03:00} / {@code …Z}), yalnız tarih ({@code 2026-10-31} — günün başı); sürücülerin metin biçimleri
     * ({@code 2026-10-31 10:15:30.1234567 +03:00}). Sayı, mantıksal ve tanınmayan metin → null (tahmin YOK — örn. epoch).
     * Doğrusal: girdi ≤ {@value #MAX_TIMESTAMP_CHARS} karakter, düzenli ifade yok.
     */
    static java.time.Instant timestampOf(Object cell, java.time.ZoneId zone) {
        if (!(cell instanceof String raw)) return null;
        String s = raw.trim();
        if (s.length() < 10 || s.length() > MAX_TIMESTAMP_CHARS) return null;
        java.time.ZoneId z = zone == null ? DEFAULT_LOCAL_ZONE : zone;
        try {
            if (s.length() == 10) return java.time.LocalDate.parse(s).atStartOfDay(z).toInstant();
            StringBuilder b = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (i == 10 && c == ' ') { b.append('T'); continue; }
                b.append(c);
            }
            String t = b.toString();
            // Dilim eki: "Z", "+03:00", "+0300", "+03", ve önündeki tek boşluk (" +03:00")
            int sign = -1;
            for (int i = t.length() - 1; i > 10; i--) {
                char c = t.charAt(i);
                // Tarih bölümünün tireleri 4 ve 7. konumda; 'T'den (10) sonraki her '-' dilim işaretidir — saniyesi sıfır
                // değer "2026-10-31T10:15-05:00" yazılır (eksi 16. konumda, r177 ikinci tur D-5)
                if (c == '+' || (c == '-' && i > 10)) { sign = i; break; }
                if (c == 'Z' && i == t.length() - 1) { sign = i; break; }
            }
            if (sign < 0) return java.time.LocalDateTime.parse(t).atZone(z).toInstant();
            String local = t.substring(0, sign).trim();
            String off = t.substring(sign);
            java.time.ZoneOffset o;
            if ("Z".equals(off)) o = java.time.ZoneOffset.UTC;
            else {
                String d = off.substring(1);
                if (d.length() == 4 && d.indexOf(':') < 0) d = d.substring(0, 2) + ":" + d.substring(2);
                if (d.length() == 2) d = d + ":00";
                o = java.time.ZoneOffset.of(off.charAt(0) + d);
            }
            return java.time.LocalDateTime.parse(local).atOffset(o).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Süre metni: "45 sn", "12 dk 3 sn", "3 sa 5 dk", "2 g 4 sa" (EN "45 s", "12 min 3 s", "3 h 5 min", "2 d 4 h"). */
    static String duration(double seconds, boolean en) {
        if (!Double.isFinite(seconds)) return "—";
        long s = Math.round(Math.abs(seconds));
        long d = s / 86_400, h = (s % 86_400) / 3_600, m = (s % 3_600) / 60, sec = s % 60;
        String ds = en ? " d" : " g", hs = en ? " h" : " sa", ms = en ? " min" : " dk", ss = en ? " s" : " sn";
        if (d > 0) return d + ds + (h > 0 ? " " + h + hs : "");
        if (h > 0) return h + hs + (m > 0 ? " " + m + ms : "");
        if (m > 0) return m + ms + (sec > 0 ? " " + sec + ss : "");
        return sec + ss;
    }

    private static String changeWord(double diff, String direction, boolean en) {
        if (diff > 0) return en ? "increase" : "artış";
        if (diff < 0) return en ? "decrease" : "azalış";
        return en ? "no change" : "değişim yok";
    }

    // ── Okunur "beklenen" metni ──────────────────────────────────────────────

    /** Sağlıklı durumun metni: ihlal koşulunun tersi + seviye. */
    static String expected(DbQueryRules.Rule r, boolean en) {
        if (r == null) return "";
        if (DbQueryRules.KIND_TEXT_MATCH.equals(r.kind())) {
            DbQueryRules.Threshold t = r.critical() != null ? r.critical() : r.warning();
            String needle = t == null || t.text() == null ? "" : t.text();
            String cond = textCondition(r.operator(), needle, en);
            String scope = r.scope() == null ? "ANY_ROW" : r.scope();
            String lvl = levelWord(r.critical() != null ? LEVEL_CRITICAL : LEVEL_WARNING, en);
            String body = switch (scope) {
                case "ALL_ROWS" -> en ? "not every row " + cond : "satırların hepsi " + cond + " olmamalı";
                case "FIRST_ROW" -> en ? "first row not " + cond : "ilk satır " + cond + " olmamalı";
                default -> en ? "no row " + cond : "hiçbir satır " + cond + " olmamalı";
            };
            return body + " (" + lvl + ")";
        }
        if (DbQueryRules.KIND_ANOMALY.equals(r.kind())) return anomalyExpected(r, en);
        if (DbQueryRules.KIND_FROZEN.equals(r.kind())) {
            DbQueryRules.Frozen f = DbQueryRules.frozenOf(r);
            String w = en ? "value must not stay the same for " + f.runsWarning() + " runs (warning)"
                    : "değer " + f.runsWarning() + " çalıştırma boyunca aynı kalmamalı (uyarı)";
            if (f.runsCritical() == null) return w;
            return w + " · " + f.runsCritical() + (en ? " runs (critical)" : " çalıştırma (kritik)");
        }
        String prefix = "";
        boolean percent = false;
        if (DbQueryRules.KIND_FRESHNESS.equals(r.kind())) {
            List<String> parts = new ArrayList<>();
            String ageWord = en ? "age " : "yaş ";
            if (r.warning() != null) parts.add(ageWord + healthyAge(r.operator(), r.warning(), en) + " (" + levelWord(LEVEL_WARNING, en) + ")");
            if (r.critical() != null) parts.add(ageWord + healthyAge(r.operator(), r.critical(), en) + " (" + levelWord(LEVEL_CRITICAL, en) + ")");
            return String.join(" · ", parts);
        }
        if (DbQueryRules.KIND_CHANGE.equals(r.kind()) && r.change() != null) {
            percent = "PERCENT".equals(r.change().mode());
            prefix = switch (r.change().direction()) {
                case "INCREASE" -> en ? "increase " : "artış ";
                case "DECREASE" -> en ? "decrease " : "azalış ";
                default -> en ? "change " : "değişim ";
            };
        }
        List<String> parts = new ArrayList<>();
        if (r.warning() != null) parts.add(prefix + healthy(r.operator(), r.warning(), percent, en) + " (" + levelWord(LEVEL_WARNING, en) + ")");
        if (r.critical() != null) parts.add(prefix + healthy(r.operator(), r.critical(), percent, en) + " (" + levelWord(LEVEL_CRITICAL, en) + ")");
        return String.join(" · ", parts);
    }

    private static String healthy(String op, DbQueryRules.Threshold t, boolean percent, boolean en) {
        if (t.isRange()) {
            String range = num(t.low(), percent, en) + " – " + num(t.high(), percent, en);
            if ("BETWEEN".equals(op)) return en ? "outside " + range : range + " dışında";
            return en ? "within " + range : range + " arasında";
        }
        String v = t.value() == null ? "—" : num(t.value(), percent, en);
        return switch (op) {
            case "GT" -> "≤ " + v;
            case "GTE" -> "< " + v;
            case "LT" -> "≥ " + v;
            case "LTE" -> "> " + v;
            case "EQ" -> "≠ " + v;
            case "NEQ" -> "= " + v;
            default -> v;
        };
    }

    /**
     * Olağan dışı değerin beklenen metni: "olağan aralıkta (±2,5σ uyarı · ±3,5σ kritik) · taban: son 30 çalıştırma"
     * (yukarı: "olağandan yüksek olmamalı (+2,5σ …)", aşağı: "olağandan düşük olmamalı (−2,5σ …)").
     */
    private static String anomalyExpected(DbQueryRules.Rule r, boolean en) {
        DbQueryRules.Anomaly p = DbQueryRules.anomalyOf(r);
        String head, sign;
        switch (p.direction()) {
            case DbQueryRules.DIRECTION_UP -> {
                head = en ? "not above the usual range" : "olağandan yüksek olmamalı";
                sign = "+";
            }
            case DbQueryRules.DIRECTION_DOWN -> {
                head = en ? "not below the usual range" : "olağandan düşük olmamalı";
                sign = "−";
            }
            default -> {
                head = en ? "within the usual range" : "olağan aralıkta";
                sign = "±";
            }
        }
        String levels = " (" + sign + fmt(p.kWarning(), en) + "σ " + levelWord(LEVEL_WARNING, en) + " · " + sign
                + fmt(p.kCritical(), en) + "σ " + levelWord(LEVEL_CRITICAL, en) + ")";
        String base = switch (p.season()) {
            case DbQueryRules.SEASON_DAILY -> en ? "baseline: the same time on the previous " + p.window() + " days"
                    : "taban: önceki " + p.window() + " günün aynı saati";
            case DbQueryRules.SEASON_WEEKLY -> en ? "baseline: the same day and time in the previous " + p.window() + " weeks"
                    : "taban: önceki " + p.window() + " haftanın aynı gün ve saati";
            default -> en ? "baseline: the last " + p.window() + " runs" : "taban: son " + p.window() + " çalıştırma";
        };
        return head + levels + " · " + base;
    }

    /** Tazelik eşiğinin sağlıklı metni: GT 600 → "≤ 10 dk", GTE 600 → "< 10 dk". */
    private static String healthyAge(String op, DbQueryRules.Threshold t, boolean en) {
        String v = t.value() == null ? "—" : duration(t.value(), en);
        return ("GTE".equals(op) ? "< " : "≤ ") + v;
    }

    private static String num(double v, boolean percent, boolean en) {
        return percent ? pct(v, en) : fmt(v, en);
    }

    private static String textCondition(String op, String needle, boolean en) {
        String q = "'" + (needle.length() > 60 ? needle.substring(0, 60) + "…" : needle) + "'";
        return switch (op == null ? "" : op) {
            case "NOT_CONTAINS" -> en ? "not containing " + q : q + " içermeyen";
            case "EQUALS" -> en ? "equal to " + q : q + " olan";
            case "NOT_EQUALS" -> en ? "not equal to " + q : q + " olmayan";
            case "EMPTY" -> en ? "empty" : "boş";
            case "NOT_EMPTY" -> en ? "not empty" : "dolu";
            default -> en ? "containing " + q : q + " içeren";
        };
    }

    private static String levelWord(String level, boolean en) {
        if (LEVEL_CRITICAL.equals(level)) return en ? "critical" : "kritik";
        return en ? "warning" : "uyarı";
    }

    // ── Sayı biçimi ──────────────────────────────────────────────────────────

    /** TR "1.250,5" / EN "1,250.5"; en çok 2 ondalık (|x| &lt; 1 ise 4), sondaki sıfırlar yok. */
    public static String fmt(double v, boolean en) {
        if (!Double.isFinite(v)) return "—";
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(en ? Locale.US : Locale.forLanguageTag("tr-TR"));
        DecimalFormat df = new DecimalFormat("#,##0.##", sym);
        if (v != 0 && Math.abs(v) < 1) df.setMaximumFractionDigits(4);
        df.setRoundingMode(RoundingMode.HALF_UP);
        String s = df.format(v);
        return "-0".equals(s) ? "0" : s;
    }

    private static String pct(double v, boolean en) {
        return en ? fmt(v, true) + "%" : "%" + fmt(v, false);
    }

    private static double round(double v) {
        if (!Double.isFinite(v)) return v;
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }

    // ── Kontrol satırı JSON'u (rule_results_json) ────────────────────────────

    /** {@code rule_results_json} biçimi: API RuleResult ile aynı snake_case alanlar. */
    public static String toJson(List<RuleResult> results) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (results != null) {
            for (RuleResult r : results) {
                if (r == null) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r.id());
                m.put("label", r.label());
                m.put("kind", r.kind());
                m.put("actual", r.actual());
                m.put("expected", r.expected());
                m.put("level", r.level());
                m.put("passed", r.passed());
                m.put("evaluated", r.evaluated());
                m.put("note", r.note());
                m.put("measure", r.measure());
                m.put("compared", r.compared());
                if (r.fingerprint() != null) m.put("fingerprint", r.fingerprint());
                putStats(m, r.stats());
                out.add(m);
            }
        }
        try {
            return JSON.writeValueAsString(out);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** Gevşek okuma (bozuk öğe atlanır, asla fırlatmaz). */
    public static List<RuleResult> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> list = JSON.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            List<RuleResult> out = new ArrayList<>();
            if (list == null) return out;
            for (Map<String, Object> m : list) {
                if (m == null || !(m.get("id") instanceof String id)) continue;
                out.add(new RuleResult(id, str(m.get("label")), str(m.get("kind")), str(m.get("actual")),
                        str(m.get("expected")), str(m.get("level")), Boolean.TRUE.equals(m.get("passed")),
                        Boolean.TRUE.equals(m.get("evaluated")), str(m.get("note")),
                        DbQueryRules.toDouble(m.get("measure")), DbQueryRules.toDouble(m.get("compared")),
                        str(m.get("fingerprint")), statsOf(m)));
            }
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Taban özetini API / JSON haritasına yazar (yalnız dolu alanlar; özet yoksa hiçbir anahtar). */
    public static void putStats(Map<String, Object> m, Stats st) {
        if (m == null || st == null) return;
        if (st.samples() != null) m.put("samples", st.samples());
        if (st.minSamples() != null) m.put("min_samples", st.minSamples());
        if (st.mean() != null) m.put("baseline_mean", st.mean());
        if (st.sigma() != null) m.put("baseline_sigma", st.sigma());
        if (st.bandLow() != null) m.put("band_low", st.bandLow());
        if (st.bandHigh() != null) m.put("band_high", st.bandHigh());
        if (st.deviation() != null) m.put("deviation", st.deviation());
        if (st.sameRuns() != null) m.put("same_runs", st.sameRuns());
    }

    /** {@link #putStats} tersi (gevşek; alan yoksa null, hiçbiri yoksa özet null). */
    static Stats statsOf(Map<String, Object> m) {
        if (m == null) return null;
        Integer samples = DbQueryRules.intOf(m.get("samples")), min = DbQueryRules.intOf(m.get("min_samples")),
                same = DbQueryRules.intOf(m.get("same_runs"));
        Double mean = DbQueryRules.toDouble(m.get("baseline_mean")), sigma = DbQueryRules.toDouble(m.get("baseline_sigma")),
                low = DbQueryRules.toDouble(m.get("band_low")), high = DbQueryRules.toDouble(m.get("band_high")),
                dev = DbQueryRules.toDouble(m.get("deviation"));
        if (samples == null && min == null && same == null && mean == null && sigma == null && low == null && high == null
                && dev == null) return null;
        return new Stats(samples, min, mean, sigma, low, high, dev, same);
    }

    /** Değişim kuralının "önceki ölçüleri": kural kimliği → measure (null ölçüler atlanır). */
    public static Map<String, Double> measuresFromJson(String json) {
        Map<String, Double> out = new HashMap<>();
        for (RuleResult r : fromJson(json)) if (r.measure() != null) out.put(r.id(), r.measure());
        return out;
    }

    /**
     * Saklı bir kural ölçüsü bugünkü kuralın TABANI olabilir mi (r177, H-1): aynı kimlik zaten eşleşmiş; parmak izi varsa
     * eşit olmalı ({@link DbQueryRules#fingerprint}); bu sürümden ÖNCEKİ satırda (parmak izi yok) yalnız tür denetlenir —
     * eski satırı reddetmek her değişim kuralını yayından sonra bir çalıştırma "değerlendirilemedi" bırakırdı (günlük cron'da
     * bir gün); sorgu özeti ayrıca çağıranda karşılaştırılır.
     */
    public static boolean sameMeasureDefinition(RuleResult stored, DbQueryRules.Rule current) {
        if (stored == null || current == null) return false;
        if (stored.fingerprint() != null) return stored.fingerprint().equals(DbQueryRules.fingerprint(current));
        return stored.kind() == null || stored.kind().equals(current.kind());
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
