package com.sitemonitor.service.otp;

import com.sitemonitor.service.PushText;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kodla giriş PUSH metni şablonu (2026-10-03, kullanıcı isteği: "push metnini login settings sayfasında değiştirebilmem
 * lazım") — saf, durumsuz. Yönetici başlığı ve mesajı TR / EN ayrı ayrı düzenler (Ayarlar → Güvenlik → Giriş Yöntemleri);
 * boş değer = yerleşik varsayılan.
 *
 * <p><b>Yer tutucular</b> (yalnız mesajda): {@code {kod}} (6 haneli kod — TAM BİR KEZ, zorunlu), {@code {sure}} (geçerlilik,
 * sn), {@code {saat}} (isteğin saati, HH:mm Europe/Istanbul). Başlık düz metindir (yer tutucu yok — kod kilit ekranı
 * başlığında görünmesin), en çok {@value #TITLE_MAX} karakter.
 *
 * <p><b>Uzunluk</b> EN KÖTÜ dolumla ve kanal süzgecinden ({@link PushText#pushSafe}) SONRA ölçülür: push mesaj tavanını
 * ({@code UserPushService.messageCharLimit}) aşan bir şablon kırpılır ve kod kesilebilirdi — bu yüzden kayıtta reddedilir;
 * teslimde de (tavan sonradan düşürüldüyse / şablon başka yoldan yazıldıysa) varsayılana düşülür. Kod hiçbir durumda
 * eksik gitmez.
 *
 * <p><b>Gizlilik:</b> bu sınıf dolu metni (kod içerir) hiçbir yere yazmaz; doğrulama iletileri yalnız ŞABLONDAN üretilir.
 */
public final class OtpPushTemplate {

    private OtpPushTemplate() { }

    public static final String PH_CODE = "kod";
    public static final String PH_TTL = "sure";
    public static final String PH_TIME = "saat";
    /** Kullanılabilen yer tutucular (sıra = arayüzdeki çip sırası). */
    public static final List<String> PLACEHOLDERS = List.of(PH_CODE, PH_TTL, PH_TIME);

    /** Başlık tavanı (kanal süzgecinden sonra). */
    public static final int TITLE_MAX = 60;
    /** Ham girdi tavanı — doğrulamadan önce kaba sınır (istek gövdesi şişirilmesin). */
    public static final int RAW_MAX = 1000;

    /** Önizleme / test gönderimi örneği. */
    public static final String SAMPLE_CODE = "123456";
    /** En kötü dolum: kod 6 hane, süre en çok 3 hane (≤ 300 sn), saat 5 karakter. */
    static final String WORST_CODE = "000000";
    static final String WORST_TTL = String.valueOf(LoginMethodsService.TTL.max());
    static final String WORST_TIME = "23:59";

    /** Yerleşik metinler — kanal-güvenli (ISO-8859-9): ayraç "-" (em-dash telefonda "?" oluyordu, 2026-10-03). */
    public static final String DEFAULT_TITLE_TR = "SiteMonitor giriş kodu";
    public static final String DEFAULT_TITLE_EN = "SiteMonitor sign-in code";
    public static final String DEFAULT_MESSAGE_TR =
            "SiteMonitor giriş kodunuz: {kod} - {sure} sn geçerli. Bu isteği siz yapmadıysanız dikkate almayın.";
    public static final String DEFAULT_MESSAGE_EN =
            "Your SiteMonitor sign-in code: {kod} - valid for {sure} s. If you did not request it, ignore this message.";

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(IST);
    /** Herhangi bir süslü parantez çifti — adı ne olursa olsun (boşluklu / yanlış yazılmış da yakalanır). */
    private static final Pattern BRACED = Pattern.compile("\\{([^{}]*)\\}");

    public static String defaultTitle(boolean english) {
        return english ? DEFAULT_TITLE_EN : DEFAULT_TITLE_TR;
    }

    public static String defaultMessage(boolean english) {
        return english ? DEFAULT_MESSAGE_EN : DEFAULT_MESSAGE_TR;
    }

    /** Doğrulama sorunu: {@code part} = {@code title} / {@code message}; ileti TR + EN (çağıran {@code Msg.t} ile seçer). */
    public record Problem(String part, String tr, String en) { }

    /** Şablondaki süslü parantez adları (sırayla, tekrarlar dahil). */
    static List<String> placeholdersIn(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        Matcher m = BRACED.matcher(s);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** Başlık geçerli mi — boş = varsayılan (geçerli). */
    public static Problem validateTitle(String title) {
        if (title == null || title.isBlank()) return null;
        if (title.length() > RAW_MAX) return tooLongRaw("title");
        List<String> ph = placeholdersIn(title);
        if (ph.contains(PH_CODE)) {
            return new Problem("title", "{kod} başlıkta kullanılamaz: kod yalnız mesajda yer alır (kilit ekranında başlık herkese görünür).",
                    "{kod} cannot be used in the title: the code goes only in the message (the title is visible on the lock screen).");
        }
        if (!ph.isEmpty()) {
            return new Problem("title", "Başlıkta yer tutucu kullanılamaz; düz metin yazın.",
                    "Placeholders cannot be used in the title; enter plain text.");
        }
        String safe = PushText.pushSafe(title);
        if (safe == null || safe.isBlank()) {
            return new Problem("title", "Başlık telefonda boş kalıyor (yalnız telefonda gösterilemeyen karakterler var).",
                    "The title would be empty on the phone (it only has characters the phone cannot show).");
        }
        if (safe.length() > TITLE_MAX) {
            return new Problem("title", "Başlık en çok " + TITLE_MAX + " karakter olabilir (şu an " + safe.length() + ").",
                    "The title can be at most " + TITLE_MAX + " characters (currently " + safe.length() + ").");
        }
        return null;
    }

    /** Mesaj geçerli mi — boş = varsayılan (geçerli). {@code maxChars}: push mesaj tavanı. */
    public static Problem validateMessage(String message, int maxChars) {
        if (message == null || message.isBlank()) return null;
        if (message.length() > RAW_MAX) return tooLongRaw("message");
        List<String> ph = placeholdersIn(message);
        Set<String> unknown = new LinkedHashSet<>();
        for (String p : ph) if (!PLACEHOLDERS.contains(p)) unknown.add("{" + p + "}");
        if (!unknown.isEmpty()) {
            String list = String.join(", ", unknown);
            return new Problem("message", "Bilinmeyen yer tutucu: " + list + ". Kullanılabilenler: {kod}, {sure}, {saat}.",
                    "Unknown placeholder: " + list + ". Available: {kod}, {sure}, {saat}.");
        }
        long codes = ph.stream().filter(PH_CODE::equals).count();
        if (codes == 0) {
            return new Problem("message", "Mesaj {kod} yer tutucusunu içermeli; yoksa kullanıcı kodu göremez.",
                    "The message must contain the {kod} placeholder, otherwise the user cannot see the code.");
        }
        if (codes > 1) {
            return new Problem("message", "{kod} yalnız bir kez kullanılabilir.", "{kod} can be used only once.");
        }
        int worst = worstCaseLength(message);
        if (worst > maxChars) {
            return new Problem("message", "Mesaj en uzun değerlerle " + worst + " karakter; push mesaj sınırı " + maxChars
                    + ". Kısaltın, yoksa telefonda kod kesilebilir.",
                    "With the longest values the message is " + worst + " characters; the push message limit is " + maxChars
                    + ". Shorten it, otherwise the code could be cut off on the phone.");
        }
        return null;
    }

    /** En kötü dolumla, kanal süzgecinden SONRAKİ uzunluk (arayüz sayacı ve kayıt doğrulaması aynı hesap). */
    public static int worstCaseLength(String message) {
        String filled = fill(message, WORST_CODE, WORST_TTL, WORST_TIME);
        String safe = PushText.pushSafe(filled);
        return safe == null ? 0 : safe.length();
    }

    private static Problem tooLongRaw(String part) {
        return new Problem(part, "Metin çok uzun (en çok " + RAW_MAX + " karakter).",
                "The text is too long (at most " + RAW_MAX + " characters).");
    }

    /** Yer tutucuları doldurur (bilinmeyenlere dokunmaz — doğrulama onları zaten reddeder). */
    public static String fill(String template, String code, String ttl, String time) {
        if (template == null) return null;
        Matcher m = BRACED.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 16);
        while (m.find()) {
            String name = m.group(1);
            String v = switch (name) {
                case PH_CODE -> code == null ? "" : code;
                case PH_TTL -> ttl == null ? "" : ttl;
                case PH_TIME -> time == null ? "" : time;
                default -> m.group(0);
            };
            m.appendReplacement(out, Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** İstanbul saatiyle {@code HH:mm} ({@code null} → şimdi). */
    public static String clock(Instant at) {
        return HHMM.format(at == null ? Instant.now() : at);
    }

    /** Kullanılacak başlık: kayıtlı ve geçerliyse o, değilse yerleşik varsayılan. */
    public static String effectiveTitle(String stored, boolean english) {
        if (stored == null || stored.isBlank() || validateTitle(stored) != null) return defaultTitle(english);
        return stored.strip();
    }

    /** Kullanılacak mesaj ŞABLONU: kayıtlı ve (bu tavanla) geçerliyse o, değilse yerleşik varsayılan. */
    public static String effectiveMessage(String stored, boolean english, int maxChars) {
        if (stored == null || stored.isBlank() || validateMessage(stored, maxChars) != null) return defaultMessage(english);
        return stored.strip();
    }

    /** Kayıtlı şablon kullanılabilir değil mi (teslimde varsayılana düşülecek) — yalnız log / görünüm için. */
    public static boolean storedInvalid(String storedTitle, String storedMessage, int maxChars) {
        return (storedTitle != null && !storedTitle.isBlank() && validateTitle(storedTitle) != null)
                || (storedMessage != null && !storedMessage.isBlank() && validateMessage(storedMessage, maxChars) != null);
    }

    /** Doldurulmuş mesaj (kod içerir — çağıran ASLA loglamaz). */
    public static String render(String storedMessage, boolean english, String code, int ttlSeconds, Instant requestedAt,
                                int maxChars) {
        return fill(effectiveMessage(storedMessage, english, maxChars), code, String.valueOf(ttlSeconds), clock(requestedAt));
    }

    /** Arayüz için yer tutucu tanımları: ad, zorunlu mu, örnek değer, en kötü uzunluk. */
    public static List<Map<String, Object>> placeholderView(int sampleTtl) {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(ph(PH_CODE, true, SAMPLE_CODE, WORST_CODE.length()));
        out.add(ph(PH_TTL, false, String.valueOf(sampleTtl), WORST_TTL.length()));
        out.add(ph(PH_TIME, false, clock(null), WORST_TIME.length()));
        return out;
    }

    private static Map<String, Object> ph(String key, boolean required, String sample, int worst) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("token", "{" + key + "}");
        m.put("required", required);
        m.put("sample", sample);
        m.put("worst_len", worst);
        return m;
    }
}
