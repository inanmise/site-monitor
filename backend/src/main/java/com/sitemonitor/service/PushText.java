package com.sitemonitor.service;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Kişi-webhook (push) metin kuralları — saf, durumsuz.
 *
 * <p><b>Neden ayrı bir sınıf.</b> Buradaki üç kural da bir SINIRDA geçerli: mesaj uygulamadan
 * çıkıp başka bir kanala giriyor. Kuralları gönderim mantığının içine serpiştirmek, her yeni
 * şablon/alan eklendiğinde birinin unutulması demekti — nitekim {@code sendTest} örnek haritası
 * ile gerçek gönderimin yer tutucuları birbirinden kaydığında mesajda çıplak {@code {baslangic}}
 * kalıyordu. Saf sınıf, kuralı testte tek başına sıkıştırmayı da mümkün kılıyor.
 *
 * <p><b>E-postaya UYGULANMAZ.</b> E-posta UTF-8 HTML'dir ve tipografi orada doğru görünür;
 * burada yapılan sadeleştirme e-postada bilgi kaybı olurdu.
 */
public final class PushText {

    private PushText() { }

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    // ── 1) Kanalın taşıyabildiği karakter kümesi ────────────────────────────

    /**
     * Alıcı push kanalının repertuvarı: <b>ISO-8859-9 (Latin-5)</b>.
     *
     * <p>Kullanıcının telefonundaki kanıt bu kümeyi tam olarak işaret ediyor: {@code ğ İ ş Ü Ç ı}
     * sağlam geliyor ama {@code ▸ — ✓ ✗} soru işaretine dönüyordu. Türkçe harfler Latin-5'te var,
     * tipografik tire/onay imleri yok. (Windows-1254 olsaydı em-dash ve üç nokta da geçerdi —
     * geçmediğine göre ISO varyantı.)
     */
    private static final Charset CHANNEL = Charset.forName("ISO-8859-9");

    /** Kanalın taşıyamadığı ama ANLAMI olan işaretler — düşürülmez, karşılığına çevrilir. */
    private static final Map<Character, String> TRANSLATE = Map.ofEntries(
            Map.entry('▸', "-"),   // ▸ şablon ayracı
            Map.entry('▶', "-"),   // ▶
            Map.entry('—', "-"),   // — em dash
            Map.entry('–', "-"),   // – en dash
            Map.entry('−', "-"),   // − minus
            Map.entry('•', "-"),   // • bullet
            Map.entry('…', "..."), // … ellipsis
            Map.entry('✓', "OK"),  // ✓
            Map.entry('✔', "OK"),  // ✔
            Map.entry('✗', "X"),   // ✗
            Map.entry('✘', "X"),   // ✘
            Map.entry('→', "->"),  // →
            Map.entry('⇒', "->"),  // ⇒
            Map.entry('≥', ">="),  // ≥
            Map.entry('≤', "<="),  // ≤
            Map.entry('“', "\""),  // “
            Map.entry('”', "\""),  // ”
            Map.entry('„', "\""),  // „
            Map.entry('‘', "'"),   // ‘
            Map.entry('’', "'"),   // ’
            Map.entry('‚', "'"),   // ‚
            Map.entry('₺', "TL"),  // ₺
            Map.entry('⚠', "!"));  // ⚠

    /**
     * Metni push kanalının taşıyabileceği hâle getirir: bilinen işaretler karşılığına çevrilir,
     * kalan taşınamaz karakterler (emoji, vekil çiftler, geometrik şekiller) DÜŞÜRÜLÜR.
     *
     * <p>Düşürme bilinçli: kanal onları zaten {@code ?} yapıyor ve bir dizi soru işareti,
     * yokluklarından daha kötü okunuyor.
     */
    public static String pushSafe(String s) {
        if (s == null || s.isEmpty()) return s;
        CharsetEncoder enc = CHANNEL.newEncoder();
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            String mapped = TRANSLATE.get(ch);
            if (mapped != null) { out.append(mapped); continue; }
            if (enc.canEncode(ch)) out.append(ch);
        }
        // Çeviri/düşürme sonrası oluşan çift boşlukları topla (fillTemplate ile aynı kural).
        return out.toString().replaceAll("\\s{2,}", " ").trim();
    }

    // ── 2) Süre biçimi ──────────────────────────────────────────────────────

    /**
     * Süre → ürünün KENDİ kısa biçimi: {@code "45 sn"}, {@code "5 dk"}, {@code "2 sa 3 dk"},
     * {@code "3 g 4 sa"} (en anlamlı iki birim).
     *
     * <p>Bu biçim burada icat edilmiyor: {@code frontend/src/utils/incidentMeta.js} içindeki
     * {@code formatDuration} yıllardır aynı merdiveni kullanıyor ve arayüzde görülen birimler
     * bunlar. Push tarafı ise {@code dk} / {@code sa dk} / yalnız {@code gün} üretiyordu — yani
     * aynı olayın süresi iki ekranda iki farklı biçimde görünüyordu ve gün sınırında saat bilgisi
     * tamamen düşüyordu.
     */
    public static String compactDuration(Duration d) {
        if (d == null || d.isNegative()) return "-";
        long sec = d.getSeconds();
        if (sec < 60) return sec + " sn";
        long min = sec / 60;
        if (min < 60) return min + " dk";
        long hr = min / 60;
        if (hr < 24) return hr + " sa" + (min % 60 > 0 ? " " + (min % 60) + " dk" : "");
        long day = hr / 24;
        return day + " g" + (hr % 24 > 0 ? " " + (hr % 24) + " sa" : "");
    }

    // ── 3) Saklanan damga → okunur saat ─────────────────────────────────────

    /**
     * {@code AlertEvent.createdAt} damgasını {@code Instant}'a çevirir.
     *
     * <p><b>Damga UTC'dir</b> ({@code EscalationService.now()} → {@code ISO.withZone(UTC)}), ama
     * saat dilimi taşımayan düz bir metin olarak saklanıyor. Onu çıplak {@code LocalDateTime}
     * sanıp İstanbul saatiyle karşılaştırmak, kesinti sürelerine sabit <b>+3 saat</b> ekliyordu:
     * 5 dakikalık bir kesinti kullanıcının telefonuna "3 sa 5 dk" diye düşüyordu.
     *
     * <p>{@code EmailTemplateBuilder.parseInstant} ile aynı hoşgörü: önce ISO-instant, olmazsa
     * çıplak yerel-zaman + UTC.
     */
    public static Instant parseStoredUtc(String stamp) {
        if (stamp == null || stamp.isBlank()) return null;
        String v = stamp.trim().replace(' ', 'T');
        try { return Instant.parse(v); } catch (Exception ignore) { /* düz damga dene */ }
        try { return LocalDateTime.parse(v).atZone(ZoneOffset.UTC).toInstant(); }
        catch (Exception ignore) { return null; }
    }

    /** UTC damga → İstanbul saatiyle {@code HH:mm}; ayrıştırılamazsa {@code "-"}. */
    public static String istClock(String storedUtc) {
        Instant i = parseStoredUtc(storedUtc);
        return i == null ? "-" : HHMM.format(i.atZone(IST));
    }

    /** Şu anın İstanbul saatiyle {@code HH:mm} karşılığı. */
    public static String istClockNow(Instant now) {
        return HHMM.format((now == null ? Instant.now() : now).atZone(IST));
    }

    // ── 4) Alarm metninden SEBEP ────────────────────────────────────────────

    private static final java.util.regex.Pattern LEVEL_PREFIX = java.util.regex.Pattern.compile(
            "^(KR[İI]T[İI]K|Y[ÜU]KSEK|UYARI|B[İI]LG[İI])\\s*:\\s*", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * {@code AlertEvent.message} → push mesajının {@code {neden}} parçası.
     *
     * <p>O metin E-POSTA için yazılmış tam bir cümledir ve zaten {@code "KRİTİK: <domain> ..."}
     * ile başlar. Push şablonu başına ayrıca {@code {seviye}} ve {@code {ad}} koyduğu için
     * kullanıcının telefonuna seviye İKİ, adres ÜÇ kez düşüyordu:
     * {@code "KRİTİK - http://x: http://x yanıt vermiyor - KRİTİK: ht..."}.
     *
     * <p>Burada yalnız baştaki seviye öneki ve onu izleyen adres tekrarı kırpılır — cümlenin
     * bilgi taşıyan kısmına dokunulmaz.
     *
     * @param message alarm metni (çok satırlıysa ilk satır)
     * @param domain  şablonun {@code {ad}} olarak zaten yazdığı adres (null olabilir)
     */
    public static String reasonOf(String message, String domain) {
        if (message == null || message.isBlank()) return "";
        int nl = message.indexOf('\n');
        String line = (nl < 0 ? message : message.substring(0, nl)).trim();
        line = LEVEL_PREFIX.matcher(line).replaceFirst("").trim();
        if (domain != null && !domain.isBlank()) {
            String d = domain.trim();
            if (line.regionMatches(true, 0, d, 0, d.length())) {
                line = line.substring(d.length()).trim();
                // Adres kırpıldıktan sonra kalan bağlaç/noktalama başı temizlenir.
                while (!line.isEmpty() && ":-,".indexOf(line.charAt(0)) >= 0) line = line.substring(1).trim();
            }
        }
        if (line.length() > 120) line = line.substring(0, 120).trim();
        return line;
    }

    /** İlk harfi büyüten yardımcı — sebep cümlesi adres kırpıldıktan sonra küçük harfle başlayabilir. */
    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Yalnız test/tanılama için: kanalın taşıyamadığı bir karakter var mı. */
    public static boolean isChannelSafe(String s) {
        if (s == null) return true;
        return CHANNEL.newEncoder().canEncode(s);
    }

    /** Kırpma işareti de kanal-güvenli olmalı: {@code …} tek başına soru işaretine dönüyordu. */
    public static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 3)).trim() + "...";
    }
}
