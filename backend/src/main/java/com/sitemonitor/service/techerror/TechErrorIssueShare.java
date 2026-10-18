package com.sitemonitor.service.techerror;

import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Issue;
import com.sitemonitor.service.techerror.TechErrorValues.Values;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anomali konusunun PAYI (2026-10-18, kullanıcı isteği: "konunun teknik hata adedinin, rapordaki TÜM teknik hatalar içindeki
 * payı otomatik hesaplansın"): {@code pay = konunun hata adedi ÷ "tüm teknik hatalar" alanı × 100} — alan bölümün
 * {@code ratio_of}'u (varsayılan "Ay içindeki teknik hata adedi"). Yuvarlama oranlarla AYNI ({@link TechErrorCalc#roundRate}:
 * 2 hane, yarım yukarı; 0,01'in altı 2 anlamlı hane). Sonuç:
 * <ul>
 *   <li>{@code OK} — pay hesaplandı;</li>
 *   <li>{@code MISSING} — konunun hata adedi ({@code missing = errors}) ya da toplam ({@code missing = total}) girilmedi;</li>
 *   <li>{@code ZERO} — toplam 0; {@code OVER} — konunun adedi toplamdan büyük (adetleri kontrol edin);</li>
 *   <li>{@code EXEMPT} — toplam alanı "Hayır" cevabıyla muaf;</li>
 *   <li>{@code BAD_REF} — {@code ratio_of} şablonda yok ya da sayı alanı değil.</li>
 * </ul>
 * Pay yoksa hiçbir yerde sahte 0 yazılmaz. Sunucu kaydederken, şablon yenilenirken ve PDF / e-posta üretilirken
 * ({@link TechErrorCalc#apply} içinden) uygular; istemci aynı kuralı CANLI gösterir ({@code techErrorModel.issueShare} —
 * doğruluk tablosu {@code TechErrorIssueShareTest.SHARE_TABLE}, JS testi Java kaynağından okur).
 */
public final class TechErrorIssueShare {

    private TechErrorIssueShare() {}

    public enum Status { OK, MISSING, ZERO, OVER, EXEMPT, BAD_REF }

    /** Sonuç: {@code value} yalnız OK'de; {@code missing} MISSING'de "errors" (konunun adedi) ya da "total" (toplam alanı). */
    public record Result(Status status, Double value, String missing) {
        static Result of(Status s) { return new Result(s, null, null); }
    }

    /**
     * Tek konunun payı. {@code fields} raporun (hesaplar uygulanmış) değerleri — muaf alanlar düşmüş, evet/hayır cevapları
     * duruyor. Bölümde {@code ratio_of} yoksa {@code null} (pay sorulmuyor).
     */
    public static Result of(Section s, Issue i, Map<String, Field> byId, Map<String, Object> fields) {
        String ref = s == null ? null : s.opts().ratioOf();
        if (ref == null || i == null) return null;
        Field total = byId.get(ref);
        if (total == null || !"number".equals(total.type())) return Result.of(Status.BAD_REF);
        if (TechErrorTemplate.exempt(total, fields)) return Result.of(Status.EXEMPT);
        Double errors = i.errors() == null ? null : TechErrorValues.number(i.errors());
        if (errors == null) return new Result(Status.MISSING, null, "errors");
        Double all = TechErrorValues.number(fields == null ? null : fields.get(ref));
        if (all == null) return new Result(Status.MISSING, null, "total");
        if (all == 0) return Result.of(Status.ZERO);
        if (errors > all) return Result.of(Status.OVER);
        double pct = errors / all * 100;
        if (!Double.isFinite(pct)) return Result.of(Status.ZERO);
        return new Result(Status.OK, TechErrorCalc.roundRate(pct), null);
    }

    /** Bölümün başvurduğu "tüm teknik hatalar" alanı (yoksa / sayı değilse {@code null}). */
    public static Field totalField(Template t, Section s) {
        String ref = s == null ? null : s.opts().ratioOf();
        if (t == null || ref == null) return null;
        for (Field f : t.allFields()) if (f.id().equals(ref)) return "number".equals(f.type()) ? f : null;
        return null;
    }

    /**
     * Her konunun payını yazar ({@code share}); pay sorulmayan bölümde ya da sonuç yoksa {@code share} boş. Değişecek bir şey
     * yoksa AYNI nesne döner (anomali payı olmayan raporun değerleri değişmez).
     */
    public static Values apply(Template t, Values v) {
        if (t == null || v == null || v.issues().isEmpty()) return v;
        Map<String, Field> byId = null;
        Map<String, List<Issue>> out = null;
        for (Section s : t.sections()) {
            if (!"issues".equals(s.kind())) continue;
            List<Issue> list = v.issues().get(s.id());
            if (list == null || list.isEmpty()) continue;
            boolean asked = s.opts().ratioOf() != null;
            List<Issue> next = new ArrayList<>(list.size());
            boolean changed = false;
            for (Issue i : list) {
                Number share = null;
                if (asked) {
                    if (byId == null) {
                        byId = new HashMap<>();
                        for (Field f : t.allFields()) byId.put(f.id(), f);
                    }
                    Result r = of(s, i, byId, v.fields());
                    if (r != null && r.status() == Status.OK) share = TechErrorValues.compact(r.value());
                }
                Issue x = sameNumber(i.share(), share) ? i : i.withShare(share);
                changed |= x != i;
                next.add(x);
            }
            if (!changed) continue;
            if (out == null) out = new LinkedHashMap<>(v.issues());
            out.put(s.id(), List.copyOf(next));
        }
        if (out == null) return v;
        return new Values(v.fields(), out, v.texts(), v.tables(), v.targets(), v.units());
    }

    private static boolean sameNumber(Number a, Number b) {
        if (a == null || b == null) return a == b;
        return Double.compare(a.doubleValue(), b.doubleValue()) == 0;
    }
}
