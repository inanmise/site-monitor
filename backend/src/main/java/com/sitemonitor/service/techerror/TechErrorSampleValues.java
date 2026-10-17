package com.sitemonitor.service.techerror;

import com.sitemonitor.service.techerror.TechErrorTemplate.Column;
import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.TableRow;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Values;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ŞABLON ÖNİZLEMESİNİN ÖRNEK DEĞERLERİ (2026-10-17, R19 — kullanıcı onayı): düzenleyicideki taslak şablonun "doldurulmuş
 * hâli" PDF olarak görülsün diye her alana makul, DETERMİNİSTİK bir değer üretir (aynı şablon → aynı PDF). Saf: sorgu yok,
 * hiçbir şey yazılmaz; sonuç yalnız {@code TechErrorReportPdfWriter}'a girer ve "ÖRNEK ÖNİZLEME" şeridiyle basılır.
 *
 * <ul>
 *   <li><b>Sayı</b> alanları: kimliğin özetinden 1 000–49 999; bir oranın yalnız PAYDASI olan alan (ör. toplam işlem) kök
 *       sayılır ve 1 000 000–1 499 000 alır. Sonra oran formülleri tutarlı kılınır: sınırı (payda / 4) aşan pay paydanın
 *       kimliğe göre %3–24'üne iner, düşülen ≤ pay / 2 (oranlar %25'i aşmaz, "pay − düşülen" negatife düşmez) — en çok
 *       {@link #MAX_PASSES} geçiş, değerler yalnız azalır.</li>
 *   <li><b>Yüzde</b>: formülü olan alan {@link TechErrorCalc#apply} ile örnek adetlerden HESAPLANIR; formülsüz alan hedefe göre
 *       (yüksek iyi → hedef ile %100'ün ortası, düşük iyi → hedefin yarısı — hedef tutturulmuş görünür), hedefsizse
 *       %0,10–0,99. Formülü sonuç vermeyen (bozuk / eksik başvurulu) hesaplı alan da formülsüz gibi doldurulur — önizlemede
 *       hiçbir alan "—" kalmaz.</li>
 *   <li><b>Evet / hayır</b>: hepsi "Evet" — "Hayır cevabında sorma" bağlı alanlar da önizlemede görünsün diye (hiçbir alan
 *       muaf kalmaz); artış / hedef karşılaştırması gibi hesaplanan evet / hayır alanı hesaplanır.</li>
 *   <li><b>Metin</b>: "Örnek metin"; <b>uzun açıklama</b>: iki maddelik zengin metin; her <b>anomali</b> bölümüne bir örnek
 *       konu; <b>tablo</b> hücreleri sütun türüne göre; yönetici özeti ve metin bölümleri şablondaki kalıptan (elle metin
 *       yok); görsel yuvaları BOŞ ("görsel yüklenmedi" kutusu); trend boş ("en az iki ay" notu).</li>
 *   <li><b>Geçen ay</b>: adetler kimliğe göre %2–8 düşük (formülsüz yüzde de; yüksek-iyi hedefli yüzde 0,02 puan düşük),
 *       oranlar ondan hesaplanır — {@code {d:…}} cümleleri ve kutucuk farkları görünür.</li>
 * </ul>
 */
public final class TechErrorSampleValues {

    private TechErrorSampleValues() {}

    /** Oran tutarlılığı geçişlerinin üst sınırı (alan sayısı ≤ 30; her geçiş değerleri yalnız küçültür). */
    static final int MAX_PASSES = 40;

    /** Örnek değerler + aynı kimlik ve türdeki "geçen ay" değerleri. */
    public record Sample(Values values, Map<String, Object> previous) { }

    /** Şablon için örnek değerler ({@code lang}: örnek metinlerin dili — normalde şablonun dili). */
    public static Sample build(Template t, String lang) {
        boolean en = "en".equals(lang);
        List<Field> all = t.allFields();
        Map<String, Field> byId = new HashMap<>();
        for (Field f : all) byId.put(f.id(), f);

        // 1. Sayı girdileri (formülsüz sayı alanları): özetten taban değer; yalnız payda olan kök büyük
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Field f : all) {
            if (!TechErrorCalc.input(f)) continue;
            int h = hash(f.id());
            counts.put(f.id(), root(f.id(), all) ? 1_000_000L + (h % 500) * 1_000L : 1_000L + (h % 49_000));
        }
        // 2. Oran formüllerini tutarlı kıl: pay ≤ payda / 4, düşülen ≤ pay / 2 (yalnız azaltır → sonlu). Sınırı aşan pay,
        //    paydanın kimliğe göre %3–24'üne iner — aynı paydaya bağlı adetler birbirinin aynısı görünmesin
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean changed = false;
            for (Field f : all) {
                TechErrorTemplate.Calc c = f.calc();
                if (c == null || !"ratio".equals(c.kind())) continue;
                Long num = counts.get(c.num()), den = counts.get(c.den());
                if (num != null && den != null && num > den / 4) {
                    long v = Math.max(1L, den * (3 + hash(c.num()) % 22) / 100);
                    if (v != num) { counts.put(c.num(), v); num = v; changed = true; }
                }
                Long minus = c.minus() == null ? null : counts.get(c.minus());
                if (num != null && minus != null && minus > num / 2) {
                    long v = Math.max(0L, num / 2);
                    if (v != minus) { counts.put(c.minus(), v); changed = true; }
                }
            }
            if (!changed) break;
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, Object> prev = new LinkedHashMap<>();
        for (Field f : all) {
            if (f.calc() != null) continue;                     // hesaplanır (aşağıda TechErrorCalc.apply)
            // Geçen ay: kimliğe göre %2–8 düşük (hepsi aynı oranda düşseydi oranların farkı hep 0 çıkardı)
            double lower = 0.92 + (hash(f.id()) % 7) / 100.0;
            switch (f.type()) {
                case "number" -> {
                    long v = counts.getOrDefault(f.id(), 1_000L);
                    fields.put(f.id(), v);
                    prev.put(f.id(), Math.round(v * lower));
                }
                case "percent" -> {
                    double v = samplePercent(f);
                    fields.put(f.id(), v);
                    boolean higher = f.target() != null && "higher".equals(TechErrorTemplate.effectiveBetter(f));
                    prev.put(f.id(), round2(higher ? Math.max(0, v - 0.02) : v * lower));
                }
                case "yesno" -> {
                    fields.put(f.id(), Boolean.TRUE);
                    prev.put(f.id(), Boolean.TRUE);
                }
                case "text" -> fields.put(f.id(), en ? "Sample text" : "Örnek metin");
                default -> fields.put(f.id(), en
                        ? "- Sample note: this line was written for the template preview.\n- A second point with **bold** text"
                        : "- Örnek açıklama: bu satır şablon önizlemesi için yazıldı.\n- **Kalın** vurgulu ikinci madde");
            }
        }

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("fields", fields);
        Map<String, Object> issues = new LinkedHashMap<>();
        Map<String, Object> tables = new LinkedHashMap<>();
        for (Section s : t.sections()) {
            if ("issues".equals(s.kind())) {
                Map<String, Object> issue = new LinkedHashMap<>();
                issue.put("id", "sample-1");
                issue.put("topic", en ? "Sample topic" : "Örnek konu");
                issue.put("title_auto", true);
                issue.put("description", en
                        ? "What happened, where and whom it affected — sample text.\n- Impact: sample point\n- Fix: **sample** point"
                        : "Ne oldu, nerede, kimleri etkiledi — örnek metin.\n- Etki: örnek madde\n- Çözüm: **örnek** madde");
                issue.put("customers", 1_250);
                issue.put("errors", 3_400);
                issues.put(s.id(), List.of(issue));
            } else if ("table".equals(s.kind())) {
                Map<String, Object> rows = new LinkedHashMap<>();
                for (TableRow r : s.rows()) {
                    Map<String, Object> cells = new LinkedHashMap<>();
                    for (Column col : s.columns()) {
                        int h = hash(s.id() + ":" + r.id() + ":" + col.id());
                        cells.put(col.id(), switch (col.type()) {
                            case "number" -> 100L + (h % 9_900);
                            case "percent" -> round2(95 + (h % 500) / 100.0);
                            default -> en ? "Sample" : "Örnek";
                        });
                    }
                    rows.put(r.id(), cells);
                }
                tables.put(s.id(), rows);
            }
        }
        raw.put("issues", issues);
        raw.put("tables", tables);

        // Hoşgörülü okuma: şablonla eşleşmeyen / sınırı aşan her şey düşer (önizleme 400 ile reddedilmez)
        Values parsed = TechErrorValues.fromStored(TechErrorTemplate.JSON.writeValueAsString(raw), t);
        Map<String, Object> previous = new LinkedHashMap<>(TechErrorCalc.apply(t, new Values(prev, Map.of(), Map.of()), null).fields());
        Values computed = TechErrorCalc.apply(t, parsed, previous);
        // 3. Hesabı sonuç vermeyen alan (formülü bozuk / eksik başvurulu ya da "elle" kalan oran, sonucu çıkmayan karşılaştırma)
        //    önizlemede "—" basılmasın: yüzde formülsüz alan gibi örnek değer, evet / hayır "Evet" alır (R19 inceleme). Geçen ay
        //    da doldurulur ({d:} cümleleri / kutucuk farkı); muaf alan ("Hayır" cevabı) doldurulmaz.
        Map<String, Object> filled = new LinkedHashMap<>(computed.fields());
        for (Field f : all) {
            if (filled.get(f.id()) != null || TechErrorTemplate.exempt(f, filled)) continue;
            double lower = 0.92 + (hash(f.id()) % 7) / 100.0;
            switch (f.type()) {
                case "percent" -> {
                    double v = samplePercent(f);
                    filled.put(f.id(), v);
                    previous.putIfAbsent(f.id(), round2(v * lower));
                }
                case "yesno" -> {
                    filled.put(f.id(), Boolean.TRUE);
                    previous.putIfAbsent(f.id(), Boolean.TRUE);
                }
                default -> { /* sayı / metin alanları yukarıda doldu */ }
            }
        }
        return new Sample(computed.withFields(filled), previous);
    }

    /**
     * Şablon önizleme PDF'inin girdisi: örnek değerler, geçen ay, boş görseller ve trend, kimlik satırı yok (eski
     * "Hazırlayan" satırı = önizlemeyi isteyen), {@code sample = true}.
     */
    public static TechErrorPdfInput previewInput(Template t, String channelName, String teamName, String preparedBy, int year,
                                                 int month, Instant generatedAt) {
        Sample s = build(t, t.lang());
        return new TechErrorPdfInput(t, s.values(), channelName, teamName, year, month, preparedBy, generatedAt, Map.of(),
                TechErrorTrend.empty(), s.previous(), null, true);
    }

    /** Yalnız bir oranın PAYDASI olan (pay / düşülen / karşılaştırılan olmayan) sayı alanı — ör. toplam işlem adedi. */
    private static boolean root(String id, List<Field> all) {
        boolean den = false;
        for (Field f : all) {
            TechErrorTemplate.Calc c = f.calc();
            if (c == null) continue;
            if ("ratio".equals(c.kind())) {
                if (id.equals(c.num()) || id.equals(c.minus())) return false;
                if (id.equals(c.den())) den = true;
            }
        }
        return den;
    }

    private static double samplePercent(Field f) {
        if (f.target() != null) {
            double t = f.target();
            return "higher".equals(TechErrorTemplate.effectiveBetter(f)) ? round2(Math.min(100, (t + 100) / 2)) : round2(t / 2);
        }
        return round2(0.10 + (hash(f.id()) % 90) / 100.0);
    }

    /** Kimliğin kararlı özeti (String.hashCode tanımlıdır — her JVM'de aynı) → negatif olmayan tamsayı. */
    private static int hash(String s) {
        return Math.floorMod(s.hashCode(), 1_000_003);
    }

    private static double round2(double d) {
        return BigDecimal.valueOf(d).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
