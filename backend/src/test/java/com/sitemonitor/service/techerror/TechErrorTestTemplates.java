package com.sitemonitor.service.techerror;

import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test şablonları. Otomatik trend bölümü 2026-10-16'dan beri VARSAYILANDA yok (kullanıcı kararı: grafikleri görsel olarak
 * kendileri sağlıyor); özellik duruyor (ekip şablon düzenleyiciden ekleyebilir) — trend testleri bu şablonla koşar.
 */
public final class TechErrorTestTemplates {

    private TechErrorTestTemplates() {}

    /** Varsayılan şablon + 12 aylık özetlerin önünde eski varsayılan trend bölümü (beş alan). */
    public static Template withTrend(String lang) {
        boolean en = "en".equals(lang);
        Map<String, Object> raw = new LinkedHashMap<>(TechErrorTemplate.toMap(TechErrorTemplate.defaults(lang)));
        @SuppressWarnings("unchecked") List<Object> secs = new ArrayList<>((List<Object>) raw.get("sections"));
        int at = 0;
        for (int i = 0; i < secs.size(); i++) if ("charts".equals(((Map<?, ?>) secs.get(i)).get("id"))) at = i;
        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("id", "auto-trend");
        trend.put("kind", "trend");
        trend.put("title", en ? "Automatic Trends (Last 12 Months)" : "Otomatik Trendler (Son 12 Ay)");
        trend.put("series", List.of("error-rate", "total-tx", "error-count", "unmapped-count", "quota-count"));
        secs.add(at, trend);
        raw.put("sections", secs);
        return TechErrorTemplate.normalize(raw);
    }

    /** 4a / 4b düzeninde özet kartına gömülü alanlar (7. sürümde "Verileri" bölümüne taşındı). */
    public static final Set<String> HOSTED_FIELDS = Set.of("unmapped-count", "unmapped-note", "quota-count", "quota-note");

    /**
     * 4a / 4b düzeni (2026-10-17, 7. sürüm öncesi): map yapılmamış ve kota hataları (adet + açıklama) yönetici özeti kartına
     * GÖMÜLÜ iki gösterge bölümü ("Map Yapılmamış Hatalar" / "Kota Hataları", host = exec-summary) özetin hemen ardında;
     * "Verileri" bölümünde bu dört alan yok. Gömme (host) testleri ve göç zinciri (gömme → adetler Verileri'ne) bu düzenle koşar.
     * Başlıklar yazılı sabittir: göçün eski şekil eşleşmesi için gerçek bir kehanet (oracle).
     */
    public static Template hostedDefaults(String lang) {
        boolean en = "en".equals(lang);
        Template d = beforeAnomaly(lang);
        List<Section> out = new ArrayList<>();
        Map<String, Field> moved = new HashMap<>();
        for (Section s : d.sections()) {
            if ("summary".equals(s.id())) {
                List<Field> keep = new ArrayList<>();
                for (Field f : s.fields()) {
                    if (HOSTED_FIELDS.contains(f.id())) moved.put(f.id(), f);
                    else keep.add(f);
                }
                out.add(s.withFields(List.copyOf(keep)));
            } else {
                out.add(s);
            }
        }
        int at = -1;
        for (int i = 0; i < out.size(); i++) if ("exec-summary".equals(out.get(i).id())) at = i;
        out.add(at + 1, hosted("unmapped", en ? "Unmapped Errors" : "Map Yapılmamış Hatalar",
                moved.get("unmapped-count"), moved.get("unmapped-note")));
        out.add(at + 2, hosted("quota", en ? "Quota Errors" : "Kota Hataları", moved.get("quota-count"), moved.get("quota-note")));
        return TechErrorTemplate.normalize(TechErrorTemplate.toMap(new Template(d.lang(), d.title(), List.copyOf(out))));
    }

    /**
     * 2026-10-17 düzeni (anomali soruları ÖNCESİ): bugünkü varsayılan, anomali bölümünün soruları kapalı (ne zaman, kesinti,
     * müşteri etkisi, görseller, pay yok). Eski göç zincirlerinin çıktısı budur; anomali göçü
     * ({@link TechErrorAnomalyFieldsMigration}) bunu bugünkü varsayılana çevirir.
     */
    public static Template beforeAnomaly(String lang) {
        Template d = TechErrorTemplate.defaults(lang);
        List<Section> out = new ArrayList<>();
        for (Section s : d.sections()) out.add("issues".equals(s.kind()) ? s.withOptions(null) : s);
        return TechErrorTemplate.normalize(TechErrorTemplate.toMap(new Template(d.lang(), d.title(), List.copyOf(out))));
    }

    /**
     * Kesinti cümlesi göçü ({@link TechErrorOutageSentenceMigration}, 2026-10-18) ÖNCESİ: özet bölümlerinin cümle listesinden
     * bugünkü kesinti cümlesi ({@link TechErrorTemplate#OUTAGE_LINE_TR} / {@code _EN}) çıkarılır, başka hiçbir şey değişmez.
     * Üretimde bu cümle zincirin EN SONUNDA eklendi: eski göçlerin (gömme, SLA, adetler, anomali) o günkü çıktısı cümlesizdi —
     * zincir testleri kesinti adımını AÇIKÇA bu hâlden geçirir (son inceleme 2026-10-18: bugünkü varsayılanla karşılaştırma
     * kesinti adımının gerilemesini görmüyordu).
     */
    public static Template withoutOutageLine(Template t) {
        List<Section> out = new ArrayList<>();
        for (Section s : t.sections()) {
            if ("summary".equals(s.kind()) && s.lines() != null) {
                List<String> lines = new ArrayList<>(s.lines());
                lines.remove(TechErrorTemplate.OUTAGE_LINE_TR);
                lines.remove(TechErrorTemplate.OUTAGE_LINE_EN);
                out.add(s.withLines(List.copyOf(lines)));
            } else {
                out.add(s);
            }
        }
        return TechErrorTemplate.normalize(TechErrorTemplate.toMap(new Template(t.lang(), t.title(), List.copyOf(out))));
    }

    /** Bir şablonun özet cümleleri (tüm özet bölümleri, sırayla). */
    public static List<String> summaryLines(Template t) {
        List<String> out = new ArrayList<>();
        for (Section s : t.sections()) if ("summary".equals(s.kind()) && s.lines() != null) out.addAll(s.lines());
        return out;
    }

    private static Section hosted(String id, String title, Field count, Field note) {
        return new Section(id, "metrics", title, List.of(count, note), List.of(), null, null, null, null, null,
                List.of(), List.of(), List.of(), "exec-summary");
    }
}
