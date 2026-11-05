package com.sitemonitor.service.dbquery;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Veritabanı sorgu alarmının ÖZEL mail konusu (2026-10-31, sözleşme §5). İzlemenin {@code mail_subject_template}'i doluysa
 * konu bu şablondan kurulur; boşsa (ya da çözülen konu boş kalırsa) standart "[Site Monitor] SEVİYE · ad · özet" konusu
 * kullanılır.
 *
 * <p>Yer tutucular (Türkçe adlar ve İngilizce eşleri aynı değeri verir): {@code {ad}} / {@code {name}} izleme adı,
 * {@code {amac}} / {@code {purpose}} alarm amacı, {@code {satir}} / {@code {rows}} dönen satır sayısı ("≥ N" sınırlıysa),
 * {@code {deger}} / {@code {value}} ölçülen değer, {@code {seviye}} / {@code {level}} seviye sözcüğü, {@code {baglanti}} /
 * {@code {connection}} bağlantı adı, {@code {zaman}} / {@code {time}} çalışma zamanı (İstanbul). Bilinmeyen yer tutucu
 * olduğu gibi kalır. Şablon ve değerler kullanıcı verisidir: DÜZENLİ İFADE YOK — doğrusal tarama; sonuç tek satır
 * (satır sonu / denetim karakteri boşluk olur), en çok {@link #MAX_LENGTH} karakter.
 */
public final class DbQueryMailSubject {

    /** Konu üst sınırı (izleme alanıyla aynı). */
    public static final int MAX_LENGTH = 200;

    /** Yer tutucu adı (küçük harf) → değer anahtarı. İngilizce eşler aynı anahtara bağlanır. */
    static final Map<String, String> ALIASES = aliases();

    private static Map<String, String> aliases() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ad", "name");           m.put("name", "name");
        m.put("amac", "purpose");      m.put("purpose", "purpose");
        m.put("satir", "rows");        m.put("rows", "rows");
        m.put("deger", "value");       m.put("value", "value");
        m.put("seviye", "level");      m.put("level", "level");
        m.put("baglanti", "connection"); m.put("connection", "connection");
        m.put("zaman", "time");        m.put("time", "time");
        return Map.copyOf(m);
    }

    private DbQueryMailSubject() {}

    /** Şablon dolu mu (boşluktan ibaret değil). */
    public static boolean hasTemplate(String template) {
        return template != null && !template.isBlank();
    }

    /**
     * Şablonu çözer.
     *
     * @param template izlemenin şablonu (null / boş → null)
     * @param values   anahtar ({@code name, purpose, rows, value, level, connection, time}) → değer (null = boş)
     * @return tek satırlık konu (≤ {@link #MAX_LENGTH}) ya da null (şablon yok / sonuç boş — çağıran standart konuya düşer)
     */
    public static String render(String template, Map<String, String> values) {
        if (!hasTemplate(template)) return null;
        // Döngü genel yardımcıda (kw sözleşmesi §8.3: anahtar kelime izlemesi aynı kuralı kullanır) — çıktı bayt bayt aynı.
        return com.sitemonitor.util.SubjectTemplate.render(template, ALIASES, values, MAX_LENGTH);
    }

    /** Satır sonu / sekme / denetim karakterleri boşluk olur, ardışık boşluk teke iner, uçlar kırpılır (doğrusal). */
    static String singleLine(String s) {
        return com.sitemonitor.util.SubjectTemplate.singleLine(s);
    }
}
