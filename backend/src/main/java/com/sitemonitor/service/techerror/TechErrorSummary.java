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
 * YÖNETİCİ ÖZETİ (2026-10-14, kullanıcı isteği: "raporun başında o kanalın teknik hata oranı, map yapılmamış ve kota
 * hatası adetleri, SLA taahhüdü verilir"). Şablondaki cümle kalıpları girilen rakamlarla dolar:
 * <ul>
 *   <li>{@code {f:<alan>}} → alanın biçimli değeri (sayı / yüzde KALIN yazılır);</li>
 *   <li>{@code {f:<evet/hayır alanı>?evet metni|hayır metni}} → cevaba göre metin;</li>
 *   <li>{@code {kanal}} / {@code {ay}} / {@code {yil}} → kanal ve dönem.</li>
 * </ul>
 * Değeri boş, muaf ("kesinti yok") ya da şablonda olmayan bir alana dayanan CÜMLE kendiliğinden düşer — yanlış ya da
 * yarım cümle yönetime gitmez. Düz cümleler tek paragrafta akar; madde işaretiyle başlayan satır ayrı satırdır.
 * Kullanıcı metni elle düzenlediyse ({@code texts[<bölüm>]}) o metin kullanılır; boş elle metin otomatik sayılır.
 * Çıktı {@link TechErrorRichText} işaretlemesidir; uygulama aynı kuralı {@code techErrorModel.summaryAutoText} /
 * {@code summaryEffective} ile uygular.
 */
public final class TechErrorSummary {

    private TechErrorSummary() {}

    /** Bir cümlede en çok bu kadar başvuru okunur: 500 karakterlik cümleye sığabilecek en çok "{f:a}" kadar. */
    static final int MAX_REFS = 100;

    /** {@code {f:id}} ya da {@code {f:id?evet|hayır}}; {@code start}/{@code end} kalıptaki konum ({@code end} hariç). */
    public record Ref(String id, String yes, String no, int start, int end) { }

    /** Doğrusal tarama: "{f:" … "}" (iç içe yok; kapanmayan başvuru düz metin sayılır). */
    public static List<Ref> refs(String line) {
        List<Ref> out = new ArrayList<>();
        if (line == null) return out;
        int i = line.indexOf("{f:");
        while (i >= 0 && out.size() < MAX_REFS) {
            int close = line.indexOf('}', i + 3);
            if (close < 0) break;
            String body = line.substring(i + 3, close);
            // İç içe süslü parantez okunamaz ("{f:x?{ay} ayında|…}" ilk "}"de biterdi ve bozuk basılırdı): başvuru
            // sayılmaz → malformed() true → şablon kaydı reddeder
            if (body.indexOf('{') >= 0) {
                i = line.indexOf("{f:", close + 1);
                continue;
            }
            int q = body.indexOf('?');
            String id = TechErrorRichText.strip(q < 0 ? body : body.substring(0, q));
            String yes = null, no = null;
            if (q >= 0) {
                String choice = body.substring(q + 1);
                int bar = choice.indexOf('|');
                // Kesilmez: başvuru gövdeleri en çok 500 karakterlik cümlenin ayrık parçalarıdır (sınır zaten var)
                yes = bar < 0 ? choice : choice.substring(0, bar);
                no = bar < 0 ? "" : choice.substring(bar + 1);
            }
            if (TechErrorTemplate.ID.matcher(id).matches()) out.add(new Ref(id, yes, no, i, close + 1));
            i = line.indexOf("{f:", close + 1);
        }
        return out;
    }

    /** Kalıpta çözülemeyen bir "{f:" var mı (kapanmamış ya da geçersiz kimlik)? Şablon kaydında reddedilir. */
    public static boolean malformed(String line) {
        if (line == null) return false;
        int count = 0;
        for (int i = line.indexOf("{f:"); i >= 0; i = line.indexOf("{f:", i + 3)) count++;
        return count != refs(line).size();
    }

    /**
     * Özetin GÖRÜNEN metni: elle düzenlenmişse o (yer tutucular çözülür), değilse kalıplardan üretilen. BOŞ elle metin
     * otomatik sayılır (inceleme 2026-10-14: boş özetle "elle düzenle" özeti kalıcı olarak boş bırakıyordu).
     */
    public static String effective(Section s, Template t, Values v, String channel, int year, int month) {
        String override = v.texts().get(s.id());
        if (override != null && !TechErrorRichText.blank(override)) return TechErrorTemplate.resolveText(override, channel, year, month, t.lang());
        return auto(s, t, v.fields(), channel, year, month);
    }

    /**
     * Kalıplardan üretilen metin (rich text). Hiç cümle kalmadıysa boş. Bir cümlenin madde olup olmadığına KALIBI
     * karar verir (değer ya da kanal adı "1. …" ile başlasa da paragraf akışı bozulmaz). Metin sınırı aşılınca sonraki
     * cümleler üretilmez — sonuç zaten sınırda kesilir (inceleme 2026-10-14: her PDF / e-postada megabaytlarca metin
     * kurulmasın).
     */
    public static String auto(Section s, Template t, Map<String, Object> fields, String channel, int year, int month) {
        Map<String, Field> byId = new HashMap<>();
        for (Field f : t.allFields()) byId.put(f.id(), f);
        StringBuilder out = new StringBuilder();
        boolean inParagraph = false;
        for (String line : s.lines()) {
            if (out.length() > TechErrorTemplate.MAX_RESOLVED_TEXT) break;
            String r = line(line, byId, fields, t.lang(), channel, year, month);
            if (r == null || TechErrorRichText.blank(r)) continue;
            boolean item = isItem(line);
            // Madde kalıbı boş gövdeye çözüldüyse ("- {f:x?Var|}" → "-") cümle düşer: tek başına işaret basılmaz
            if (item && !isItem(r)) continue;
            // Paragraf başındaki düz cümle değer ya da kanal adıyla "1. " / "- " / "* " ile başlıyorsa işaretten sonraki
            // boşluk bölünmez boşluk olur: PDF / e-posta / önizleme onu madde olarak yeniden okumasın
            if (!item && !inParagraph) r = keepParagraph(r);
            if (item) {
                if (!out.isEmpty()) out.append('\n');
                out.append(r);
                inParagraph = false;
            } else if (inParagraph) {
                out.append(' ').append(r);
            } else {
                if (!out.isEmpty()) out.append("\n\n");
                out.append(r);
                inParagraph = true;
            }
        }
        return TechErrorTemplate.capResolved(out.toString(), TechErrorTemplate.MAX_RESOLVED_TEXT);
    }

    /**
     * Tek cümle; dayandığı bir değer yoksa {@code null} (cümle düşer). Uzun açıklama ({@code textarea}) alanına
     * başvuran cümle de düşer: çok satırlı not tek cümleye sığmaz ve 100 başvuru × 10 000 karakter bellek şişirirdi
     * (düzenleyici bu alanları sunmaz, elle yazılırsa uyarır).
     */
    static String line(String pattern, Map<String, Field> byId, Map<String, Object> fields, String lang, String channel,
                       int year, int month) {
        if (pattern == null || TechErrorRichText.blank(pattern)) return null;
        List<Ref> refs = refs(pattern);
        StringBuilder b = new StringBuilder();
        int pos = 0;
        for (Ref r : refs) {
            b.append(literal(pattern.substring(pos, r.start()), channel, year, month, lang));
            Field f = byId.get(r.id());
            if (f == null || "textarea".equals(f.type()) || TechErrorTemplate.exempt(f, fields)) return null;
            Object v = fields.get(f.id());
            if (v == null) return null;
            if (r.yes() != null) {
                if (!(v instanceof Boolean yes)) return null;
                b.append(literal(yes ? r.yes() : r.no(), channel, year, month, lang));
            } else if ("number".equals(f.type()) || "percent".equals(f.type())) {
                b.append("**").append(TechErrorFormat.value(f, v, lang)).append("**");
            } else {
                b.append(neutral(TechErrorFormat.value(f, v, lang)));
            }
            pos = r.end();
        }
        b.append(literal(pattern.substring(pos), channel, year, month, lang));
        return TechErrorRichText.strip(b.toString().replaceAll(" {2,}", " "));
    }

    private static String literal(String s, String channel, int year, int month, String lang) {
        return TechErrorTemplate.resolveText(s, channel, year, month, lang);
    }

    /**
     * Kullanıcının yazdığı değer işaretlemeyi bozmasın: iki ve daha uzun her yıldız dizisi TEK yıldıza iner (inceleme
     * 2026-10-14: "4543 **** 1234" eskiden "**" bırakıp cümlenin geri kalanını kalın yapıyordu), satır sonu boşluğa döner.
     */
    static String neutral(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') { b.append(' '); continue; }
            if (c == '*' && i > 0 && s.charAt(i - 1) == '*') continue;
            b.append(c);
        }
        return b.toString();
    }

    /** Satır başında madde gibi okunacak düz cümle: işaretten sonraki boşluk U+00A0 olur (madde olmaz). */
    static String keepParagraph(String r) {
        if (!isItem(r)) return r;
        int k = 0;
        while (k < r.length() && k < 3 && r.charAt(k) >= '0' && r.charAt(k) <= '9') k++;
        k = k > 0 ? k + 1 : 1;                            // "12." / "3)" ya da "-" / "*" / "•"
        return k < r.length() && r.charAt(k) == ' ' ? r.substring(0, k) + '\u00A0' + r.substring(k + 1) : r;
    }

    private static boolean isItem(String s) {
        List<TechErrorRichText.Block> b = TechErrorRichText.parse(s);
        return !b.isEmpty() && b.get(0).item();
    }
}
