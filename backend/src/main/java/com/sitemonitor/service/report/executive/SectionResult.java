package com.sitemonitor.service.report.executive;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bir özet bölümünün SUNUMDAN BAĞIMSIZ sonucu — aynı nesne uygulama ekranını, e-postayı ve PDF'i besler.
 *
 * <p><b>Genel çizim sözleşmesi:</b> e-posta ({@code ExecutiveSummaryMail}) ve PDF ({@code ExecutiveSummaryPdfWriter})
 * bölümleri tanımaz; yalnız bu yapıyı çizer: başlık + durum rozeti → hükümler ({@link Verdict}) → gösterge kutuları
 * ({@link Kpi}) → tablolar ({@link Table}) → notlar ({@link Note}). Yeni bir bölüm sağlayıcısı bu yapıyı doldurduğu an
 * üç yüzeyde de görünür; ekran ayrıca {@code data} alanından bölüme özel görseller çizebilir (isteğe bağlı).
 *
 * <p><b>Dil:</b> {@code title}/{@code text}/{@code label}/{@code hint} Türkçedir (posta ve PDF Türkçe gider). Arayüz
 * aynı içeriği {@code exec.<bölüm>.<tür>.<kod>} i18n anahtarlarıyla ve {@code params} ile iki dilde kurar; anahtar
 * yoksa Türkçe metne düşer. Kodlar bölüm içinde kararlıdır (kapı: {@code ExecutiveSummaryI18nGateTest}).
 *
 * <p><b>Değer biçimleri</b> ({@link Kpi#format()}, {@link Column#type()}): {@code int}, {@code pct} (yüzde, 0–100),
 * {@code pp} (yüzde puanı farkı), {@code minutes}, {@code days}, {@code date} (ISO gün), {@code text}, {@code team},
 * {@code status} (ton: ok/warn/bad/info/neutral), {@code bool}.
 */
public record SectionResult(
        String key,
        int order,
        String title,
        String status,
        /** Bölüm "rapor anı" verisi mi (ör. yaklaşan bitişler) — geçmiş ayda bile üretim anına göredir. */
        boolean snapshot,
        @JsonProperty("as_of") String asOf,
        List<Verdict> verdicts,
        List<Kpi> kpis,
        List<Table> tables,
        List<Note> notes,
        /** Üst şeritte gösterilecek gösterge kodu ({@code kpis} içinden; null = yok). */
        @JsonProperty("headline_kpi") String headlineKpi,
        Map<String, Object> data) {

    // ── Bölüm durumu (en kötüsü özetin genel durumudur) ──
    public static final String OK = "ok";
    public static final String ATTENTION = "attention";
    public static final String CRITICAL = "critical";
    public static final String NO_DATA = "no_data";
    public static final String ERROR = "error";
    /** Önem sırası (artan) — genel durum hesaplanırken. */
    public static final List<String> STATUS_ORDER = List.of(NO_DATA, OK, ATTENTION, ERROR, CRITICAL);

    // ── Ton (rozet / değer rengi) ──
    public static final String T_OK = "ok";
    public static final String T_WARN = "warn";
    public static final String T_BAD = "bad";
    public static final String T_INFO = "info";
    public static final String T_NEUTRAL = "neutral";

    public SectionResult {
        verdicts = verdicts == null ? List.of() : List.copyOf(verdicts);
        kpis = kpis == null ? List.of() : List.copyOf(kpis);
        tables = tables == null ? List.of() : List.copyOf(tables);
        notes = notes == null ? List.of() : List.copyOf(notes);
        data = data == null ? Map.of() : data;
    }

    /**
     * Biçimli parametre — arayüz değeri KENDİ dilinde biçimler ({@code format}: {@code pct}, {@code pp}, {@code num},
     * {@code minutes}, {@code pct_change}, {@code date}). Tam sayı ve metin parametreleri çıplak geçer.
     */
    public record Param(Object value, String format) { }

    public static Param pct(Double v) { return new Param(v, "pct"); }
    public static Param pp(Double v) { return new Param(v, "pp"); }
    public static Param num(Double v) { return new Param(v, "num"); }
    public static Param minutes(Double v) { return new Param(v, "minutes"); }
    public static Param date(String isoDayOrTs) { return new Param(isoDayOrTs, "date"); }
    /** UTC ISO damga — arayüz Türkiye saatinde gün + saat yazar. */
    public static Param datetime(String utcIso) { return new Param(utcIso, "datetime"); }
    /** İzleme türü anahtarı ({@code http}, {@code ping} …) — arayüz tür adını kendi dilinde yazar. */
    public static Param monitorType(String type) { return new Param(type, "monitor_type"); }

    /** Parametrenin çıplak değeri (testler ve Türkçe metin için). */
    public static Object raw(Object p) {
        if (p instanceof Param pa) return pa.value();
        if (p instanceof Map<?, ?> m && m.containsKey("format")) return m.get("value");
        return p;
    }

    /** Tek satırlık hüküm. {@code params} arayüzün {@code {0}…} yer tutucularıdır (metindekiyle aynı sıra). */
    public record Verdict(String code, String tone, String text, List<Object> params) {
        public Verdict {
            params = params == null ? List.of() : params;
        }
    }

    /**
     * Gösterge kutusu. {@code value} sayı (ya da metin) — biçim {@code format}. {@code delta} önceki aya göre fark
     * (biçimi {@code deltaFormat}; null = karşılaştırma yok), {@code deltaTone} farkın iyi/kötü yönü.
     */
    public record Kpi(String code, String label, Object value, String format, String tone, String hint,
                      @JsonProperty("hint_params") List<Object> hintParams,
                      Double delta, @JsonProperty("delta_format") String deltaFormat,
                      @JsonProperty("delta_tone") String deltaTone) {
        public Kpi {
            hintParams = hintParams == null ? List.of() : hintParams;
        }
    }

    /** Tablo sütunu — {@code type} hücre biçimidir (sınıf belgesi). */
    public record Column(String code, String label, String type) { }

    /**
     * Tablo. {@code rows} sütun koduyla anahtarlı ham değerler (biçim yüzeyde); {@code total} kırpılmadan önceki satır
     * sayısı ("+N daha" için), {@code empty} boş tablo metni.
     */
    public record Table(String code, String title, List<Column> columns, List<Map<String, Object>> rows,
                        int total, String empty) {
        public Table {
            columns = columns == null ? List.of() : List.copyOf(columns);
            rows = rows == null ? List.of() : rows;
        }
    }

    /** Açıklama / yöntem notu (dürüst etiket: kaynak, yaklaşıklık, kapsam). */
    public record Note(String code, String text, List<Object> params) {
        public Note {
            params = params == null ? List.of() : params;
        }
    }

    /** Durumların en kötüsü ({@link #STATUS_ORDER}); boşsa {@link #NO_DATA}. */
    public static String worst(List<String> statuses) {
        String w = NO_DATA;
        if (statuses != null) {
            for (String s : statuses) if (STATUS_ORDER.indexOf(s) > STATUS_ORDER.indexOf(w)) w = s;
        }
        return w;
    }

    public static Builder builder(String key, int order, String title) {
        return new Builder(key, order, title);
    }

    /** Sağlayıcıların okunur kurulumu için küçük kurucu. */
    public static final class Builder {
        private final String key;
        private final int order;
        private final String title;
        private String status = OK;
        private boolean snapshot;
        private String asOf;
        private String headlineKpi;
        private final List<Verdict> verdicts = new ArrayList<>();
        private final List<Kpi> kpis = new ArrayList<>();
        private final List<Table> tables = new ArrayList<>();
        private final List<Note> notes = new ArrayList<>();
        private final Map<String, Object> data = new LinkedHashMap<>();

        private Builder(String key, int order, String title) {
            this.key = key;
            this.order = order;
            this.title = title;
        }

        public Builder status(String s) { this.status = s; return this; }
        public Builder snapshot(String asOfIso) { this.snapshot = true; this.asOf = asOfIso; return this; }
        public Builder headlineKpi(String code) { this.headlineKpi = code; return this; }
        public Builder verdict(String code, String tone, String text, Object... params) {
            verdicts.add(new Verdict(code, tone, text, params == null ? List.of() : Arrays.asList(params)));
            return this;
        }
        public Builder kpi(Kpi k) { kpis.add(k); return this; }
        public Builder table(Table t) { tables.add(t); return this; }
        public Builder note(String code, String text, Object... params) {
            notes.add(new Note(code, text, params == null ? List.of() : Arrays.asList(params)));
            return this;
        }
        public Builder data(String k, Object v) { data.put(k, v); return this; }

        public SectionResult build() {
            return new SectionResult(key, order, title, status, snapshot, asOf, verdicts, kpis, tables, notes,
                    headlineKpi, data);
        }
    }

    /** Hata durumunda bölümün yerine çizilen sonuç — bir bölümün hatası özetin geri kalanını düşürmez. */
    public static SectionResult failed(String key, int order, String title) {
        return builder(key, order, title).status(ERROR)
                .note("ERROR", "Bu bölüm hesaplanamadı; ayrıntı sunucu günlüğünde. Diğer bölümler etkilenmedi.")
                .build();
    }
}
