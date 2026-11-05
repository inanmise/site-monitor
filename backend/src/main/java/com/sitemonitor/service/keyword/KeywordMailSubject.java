package com.sitemonitor.service.keyword;

import com.sitemonitor.util.SubjectTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Anahtar kelime alarmının ÖZEL posta konusu (kw sözleşmesi §8.3, E3, 2026-11-05). İzlemenin {@code mail_subject_template}'i
 * doluysa KEYWORD alarm postalarının (açılış, eskalasyon, yeniden uyarı, elle yeniden gönderim) konusu bu şablondan kurulur;
 * boşsa (ya da çözülen konu boş kalırsa) standart "[Site Monitor] SEVİYE · ad · özet" konusu kullanılır. Çözüm postası standart
 * konuda kalır (veritabanı sorgu izlemesi emsali).
 *
 * <p>Yer tutucular (Türkçe ad ve İngilizce eşi aynı değeri verir): {@code {ad}} / {@code {name}} izleme adı, {@code {url}},
 * {@code {kelime}} / {@code {keyword}} aranan metin (kural #1), {@code {adet}} / {@code {count}} bulunan adet, {@code {neden}} /
 * {@code {reason}} kısa TR neden, {@code {seviye}} / {@code {level}} seviye sözcüğü, {@code {durum}} / {@code {status}} HTTP
 * durum kodu, {@code {zaman}} / {@code {time}} zaman (İstanbul). Bilinmeyen yer tutucu olduğu gibi kalır; sonuç tek satır,
 * ≤ {@value #MAX_LENGTH}. Çözücü genel {@link SubjectTemplate} (DÜZENLİ İFADE YOK — doğrusal).
 */
public final class KeywordMailSubject {

    /** Konu üst sınırı (izleme alanıyla aynı). */
    public static final int MAX_LENGTH = KeywordRequestRules.MAX_SUBJECT;

    /** Yer tutucu adı (küçük harf) → değer anahtarı. İngilizce eşler aynı anahtara bağlanır. */
    static final Map<String, String> ALIASES = aliases();

    private static Map<String, String> aliases() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ad", "name");         m.put("name", "name");
        m.put("url", "url");
        m.put("kelime", "keyword");  m.put("keyword", "keyword");
        m.put("adet", "count");      m.put("count", "count");
        m.put("neden", "reason");    m.put("reason", "reason");
        m.put("seviye", "level");    m.put("level", "level");
        m.put("durum", "status");    m.put("status", "status");
        m.put("zaman", "time");      m.put("time", "time");
        return Map.copyOf(m);
    }

    private KeywordMailSubject() {}

    /** Şablon dolu mu (boşluktan ibaret değil). */
    public static boolean hasTemplate(String template) {
        return template != null && !template.isBlank();
    }

    /**
     * Şablonu çözer.
     *
     * @param template izlemenin şablonu (null / boş → null)
     * @param values   anahtar ({@code name, url, keyword, count, reason, level, status, time}) → değer (null = boş)
     * @return tek satırlık konu ya da null (şablon yok / sonuç boş — çağıran standart konuya düşer)
     */
    public static String render(String template, Map<String, String> values) {
        if (!hasTemplate(template)) return null;
        return SubjectTemplate.render(template, ALIASES, values, MAX_LENGTH);
    }

    /**
     * Alarm bağlamından yer tutucu değerleri. {@code levelWord} seviye sözcüğü (KRİTİK / YÜKSEK / UYARI), {@code time} İstanbul
     * biçimli zaman — ikisi çağıranda kurulur.
     */
    public static Map<String, String> values(Map<String, Object> ctx, String levelWord, String time) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("name", str(ctx, "monitor_name"));
        v.put("url", str(ctx, "url"));
        v.put("keyword", str(ctx, "keyword"));
        boolean failed = ctx != null && KeywordMailText.requestFailed(str(ctx, "failure_reason"), ctx.get("error"));
        v.put("count", failed ? null : str(ctx, "occurrences"));   // sayfa açılamadıysa "0" yazılmaz (B1)
        v.put("reason", ctx == null ? null : KeywordMailText.shortReason(str(ctx, "failure_reason"), ctx.get("http_status")));
        v.put("level", levelWord);
        v.put("status", str(ctx, "http_status"));
        v.put("time", time);
        return v;
    }

    private static String str(Map<String, Object> ctx, String k) {
        if (ctx == null) return null;
        Object o = ctx.get(k);
        if (o == null) return null;
        String s = String.valueOf(o);
        return s.isBlank() || "null".equalsIgnoreCase(s) ? null : s;
    }
}
