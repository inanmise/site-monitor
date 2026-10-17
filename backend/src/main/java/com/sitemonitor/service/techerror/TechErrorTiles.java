package com.sitemonitor.service.techerror;

import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Values;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Yönetici özetinin başındaki RENKLİ KUTUCUK ŞERİDİ (2026-10-17, 4b — kullanıcı kararı: "başta 4–6 renkli kutucuk: değer,
 * önceki aya ok + fark, durum rengi; PDF / e-posta / ekran aynı"). Kutucuklar özet bölümünün {@code tiles} listesinden
 * gelir ({@link Section#tiles()}); her biri bir sayı / yüzde alanı. Kurallar (istemci aynası {@code techErrorModel.summaryTiles},
 * ortak doğruluk tablosu {@code TechErrorTilesTest.TILES_TABLE}):
 * <ul>
 *   <li>Şablonda olmayan, sayı / yüzde olmayan ya da MUAF alan çizilmez; değeri boş alan "—" ile ve {@code unknown} tonunda
 *       çizilir (yönetici eksik rakamı görsün — boş kutucuk gizlenmez).</li>
 *   <li>{@code delta}: geçen ayın aynı alanı varsa {@link TechErrorFormat#delta} ("+1.250 (+%3,2)" / "−0,05 puan" /
 *       "değişim yok"), {@code dir} up / down / flat; geçen ay yoksa yok.</li>
 *   <li>Renk ({@code tone}, kullanıcı kararı "hedef + yön"): hedefi olan alan → hedef tutturuldu {@code good}, tutturulamadı
 *       {@code bad} (eşitlik tutturuldu); hedefsiz alan → "daha iyi" yönüne göre önceki aya karşı iyileşme {@code good},
 *       kötüleşme {@code bad}; yön yok (adet), geçen ay yok ya da değişim yok → {@code neutral}; değer yok → {@code unknown}.</li>
 *   <li>{@code target}: etkin hedef biçimli (raporun o aya özel hedefi, yoksa şablonun), {@code met}: tutturuldu mu (hedefsiz null).</li>
 * </ul>
 */
public final class TechErrorTiles {

    private TechErrorTiles() {}

    public static final int MAX = TechErrorTemplate.MAX_TILES;

    /** Çizilecek kutucuk: etiket + biçimli değer; fark metni / yönü (geçen ay yoksa null); ton; hedef (yoksa null); tutturuldu mu. */
    public record Tile(String id, String label, String value, String delta, String dir, String tone, String target, Boolean met) { }

    public static List<Tile> build(Template t, Section s, Values v, Map<String, Object> previous, String channel, int year, int month) {
        return build(t, s, v.fields(), v.targets(), previous, channel, year, month);
    }

    public static List<Tile> build(Template t, Section s, Map<String, Object> fields, Map<String, Object> targets,
                                   Map<String, Object> previous, String channel, int year, int month) {
        List<Tile> out = new ArrayList<>();
        if (t == null || s == null || s.tiles() == null || s.tiles().isEmpty()) return out;
        Map<String, Object> vals = fields == null ? Map.of() : fields;
        Map<String, Field> byId = new HashMap<>();
        for (Field f : t.allFields()) byId.put(f.id(), f);
        String lang = t.lang();
        boolean en = t.en();
        for (String id : s.tiles()) {
            if (out.size() >= MAX) break;
            Field f = byId.get(id);
            if (f == null || (!"number".equals(f.type()) && !"percent".equals(f.type()))) continue;
            if (TechErrorTemplate.exempt(f, vals)) continue;
            Object cur = vals.get(id);
            Double c = TechErrorValues.number(cur);
            String label = TechErrorTemplate.resolve(f.label() == null ? "" : f.label(), channel, year, month, lang);
            String value = TechErrorFormat.value(f, c == null ? null : cur, lang);
            TechErrorFormat.Delta d = TechErrorFormat.delta(f, previous == null ? null : previous.get(id), cur, lang);
            Double target = TechErrorTemplate.effectiveTarget(f, targets);
            String better = TechErrorTemplate.effectiveBetter(f);
            Boolean met = null;
            String tone;
            if (c == null) {
                tone = "unknown";
            } else if (target != null && !"none".equals(better)) {
                // Hedefli alan: tutturdu mu (eşitlik tutturuldu); yönsüz hedef karşılaştırılamaz → aşağıdaki nötr yol
                met = "lower".equals(better) ? c <= target : c >= target;
                tone = met ? "good" : "bad";
            } else {
                if ("none".equals(better) || d == null || "flat".equals(d.dir())) tone = "neutral";
                else tone = "up".equals(d.dir()) == "higher".equals(better) ? "good" : "bad";
            }
            out.add(new Tile(id, label == null ? "" : label, value, d == null ? null : d.text(), d == null ? null : d.dir(), tone,
                    target == null ? null : TechErrorFormat.percent(target, en), met));
        }
        return out;
    }
}
