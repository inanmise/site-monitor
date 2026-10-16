package com.sitemonitor.service.techerror;

import com.sitemonitor.service.techerror.TechErrorTemplate.Calc;
import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Values;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Otomatik hesaplanan alanlar (2026-10-16, kullanıcı isteği: "yüzdeleri kullanıcı değil sistem hesaplasın; kullanıcı
 * adetleri girer, ilgili yüzdelik alanlar yazarken otomatik dolar"). Formül {@link Calc}'tadır; burada SONUÇ:
 * <ul>
 *   <li>{@code OK} — değer hesaplandı (saklanır, PDF / e-posta / özet / trend onu okur);</li>
 *   <li>{@code MISSING} / {@code ZERO} / {@code NEGATIVE} / {@code EQUAL} / {@code RANGE} — formül kullanılabilir ama bu
 *       ayın rakamlarıyla sonuç yok (girdi boş, bölen 0, düşülen sayı büyük, değişim yok, aralık dışı): alan BOŞ kalır;</li>
 *   <li>{@code MANUAL} / {@code BAD_REF} — formül kullanılamıyor (geçen ayın raporu ya da değeri yok; başvurulan alan
 *       şablonda yok, sayı değil ya da kendisi hesaplanıyor): alan ELLE girilir, girilen değer korunur.</li>
 * </ul>
 * Sunucu kaydederken ({@code saveValues}), şablon yenilenirken ve PDF / e-posta üretilirken uygular; istemci aynı kuralı
 * yazarken gösterir ({@code techErrorModel.calcResult} — doğruluk tablosu {@code TechErrorCalcTest.TABLE}, JS testi
 * Java kaynağından okur). Hesaplanan alanlar başka hesaplanan alana başvuramaz: sıra ve döngü sorunu yok.
 */
public final class TechErrorCalc {

    private TechErrorCalc() {}

    public enum Status { OK, MISSING, ZERO, NEGATIVE, EQUAL, RANGE, MANUAL, BAD_REF }

    /** Hesap sonucu: {@code value} yalnız OK'de dolu (oran / değişim: ham yüzde; artış: Boolean); {@code missing} boş girdiler. */
    public record Result(Status status, Object value, List<String> missing) {
        static Result of(Status s) { return new Result(s, null, List.of()); }
    }

    /** Formül girdisi olabilecek alan: şablonda, SAYI türünde, kendisi hesaplanmayan. */
    static boolean input(Field f) {
        return f != null && "number".equals(f.type()) && f.calc() == null;
    }

    /**
     * Tek alanın sonucu. {@code fields} bu raporun değerleri (muaf alanlar düşmüş), {@code previous} geçen ayın raporunun
     * AYNI kimlik ve türdeki alanları (rapor yoksa null). Hesaplanmayan alan → null.
     */
    public static Result result(Field f, Map<String, Field> byId, Map<String, Object> fields, Map<String, Object> previous) {
        Calc c = f == null ? null : f.calc();
        if (c == null) return null;
        if ("ratio".equals(c.kind())) {
            List<String> missing = new ArrayList<>(3);
            Double[] v = new Double[3];
            String[] refs = {c.num(), c.minus(), c.den()};
            for (int i = 0; i < refs.length; i++) {
                if (refs[i] == null) continue;
                if (!input(byId.get(refs[i]))) return Result.of(Status.BAD_REF);
                v[i] = TechErrorValues.number(fields.get(refs[i]));
                if (v[i] == null) missing.add(refs[i]);
            }
            if (!missing.isEmpty()) return new Result(Status.MISSING, null, List.copyOf(missing));
            if (v[2] == 0) return Result.of(Status.ZERO);
            double numerator = v[0] - (v[1] == null ? 0 : v[1]);
            if (numerator < 0) return Result.of(Status.NEGATIVE);
            return ranged(numerator / v[2] * 100);
        }
        // change / increase: geçen ayın aynı alanına göre
        if (!input(byId.get(c.of()))) return Result.of(Status.BAD_REF);
        Double prev = previous == null ? null : TechErrorValues.number(previous.get(c.of()));
        if (prev == null) return Result.of(Status.MANUAL);
        Double cur = TechErrorValues.number(fields.get(c.of()));
        if (cur == null) return new Result(Status.MISSING, null, List.of(c.of()));
        if ("increase".equals(c.kind())) {
            int cmp = Double.compare(cur, prev);
            return cmp == 0 ? Result.of(Status.EQUAL) : new Result(Status.OK, cmp > 0, List.of());
        }
        if (prev == 0) return Result.of(Status.ZERO);
        return ranged(Math.abs(cur - prev) / prev * 100);
    }

    /**
     * Saklanabilir yüzde aralığında mı (TechErrorValues.MAX_PERCENT): dışındaki sonuç okurken düşerdi → boş. Sonuç
     * {@link #roundRate} ile yuvarlanır — istemci {@code roundCalc} ile aynı: ekran, PDF, e-posta ve özet aynı değeri gösterir.
     */
    private static Result ranged(double pct) {
        if (!Double.isFinite(pct) || Math.abs(pct) > TechErrorValues.MAX_PERCENT) return Result.of(Status.RANGE);
        return new Result(Status.OK, roundRate(pct), List.of());
    }

    /**
     * Hesaplanan oranın yuvarlaması (kullanıcı kararı 2026-10-16): virgülden sonra 2 hane, yarım yukarı; sıfırdan büyük ama
     * 0,01'in altındaki oran 2 ANLAMLI haneyle (en çok 6 ondalık — saklama hassasiyeti): 0,0028 · 0,0041 — hata varken
     * sahte %0 yazılmaz. Ondalık GÖSTERİMDEN ({@code BigDecimal.valueOf} = en kısa gösterim): 1,4749999999999999 → 1,47;
     * istemci aynı basamaklardan yuvarlar (eskiden ekranda 1,48, PDF'te 1,47 çıkıyordu — inceleme 2026-10-16).
     */
    static double roundRate(double d) {
        if (d == 0) return 0;
        java.math.BigDecimal b = java.math.BigDecimal.valueOf(d);
        int scale = Math.abs(d) < 0.01 ? smallScale(b) : 2;
        return b.setScale(scale, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    /** 0,01'in altındaki sayının 2 anlamlı hanesi için ondalık sayısı (en çok 6): 0,0028 → 4, 0,00042 → 5. */
    public static int smallScale(java.math.BigDecimal b) {
        return Math.min(6, 2 - (b.precision() - b.scale()));
    }

    /**
     * Bu raporda en az bir oran GERÇEKTEN hesaplandı mı (PDF / e-posta notu "oranları Site Monitor hesapladı" der): muaf
     * olmayan, formülü kullanılabilen ve sonucu çıkan bir oran alanı. Geçen ayı isteyen değişim / artış bakılmaz — elle
     * girilmiş olabilir (ilk rapor). {@code fields} raporun değerleri.
     */
    public static boolean anyRatioComputed(Template t, Map<String, Object> fields) {
        if (t == null || fields == null) return false;
        Map<String, Field> byId = new HashMap<>();
        for (Field f : t.allFields()) byId.put(f.id(), f);
        for (Field f : t.allFields()) {
            if (f.calc() == null || !"ratio".equals(f.calc().kind()) || TechErrorTemplate.exempt(f, fields)) continue;
            Result r = result(f, byId, fields, null);
            if (r != null && r.status() == Status.OK) return true;
        }
        return false;
    }

    /**
     * Şablondaki tüm hesaplanan alanları uygular: OK → hesaplanan değer (virgülden sonra 2 hane), boş sonuç → değer kaldırılır, formül kullanılamıyorsa (MANUAL / BAD_REF) girilen değer kalır. Muaf
     * hesaplanan alan hesaplanmaz (değeri zaten saklanmaz). Sorgu yapmaz; geçen ay {@code previous} ile gelir.
     */
    public static Values apply(Template t, Values v, Map<String, Object> previous) {
        if (t == null || v == null) return v;
        Map<String, Field> byId = new HashMap<>();
        boolean any = false;
        for (Field f : t.allFields()) {
            byId.put(f.id(), f);
            any |= f.calc() != null;
        }
        if (!any) return v;
        Map<String, Object> fields = new LinkedHashMap<>(v.fields());
        Map<String, Object> inputs = v.fields();
        for (Field f : t.allFields()) {
            if (f.calc() == null) continue;
            if (TechErrorTemplate.exempt(f, inputs)) {
                fields.remove(f.id());
                continue;
            }
            Result r = result(f, byId, inputs, previous);
            switch (r.status()) {
                case OK -> fields.put(f.id(), r.value() instanceof Double d ? TechErrorValues.compact(d) : r.value());
                case MANUAL, BAD_REF -> { /* elle girilen değer kalır */ }
                default -> fields.remove(f.id());
            }
        }
        return new Values(fields, v.issues(), v.texts(), v.tables());
    }
}
