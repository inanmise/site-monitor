package com.sitemonitor.service.loginstats;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Giriş denetim satırını SINIFLANDIRAN saf kural (2026-10-03, Giriş Yöntemleri → İstatistikler) — TEK kaynak: hem kurum
 * geneli istatistik ({@link LoginStatsService}) hem kullanıcı zaman çizelgesi (Sistem Sağlığı → Kullanıcı/Oturum) buradan
 * okur.
 *
 * <p><b>Tanımlar</b> (arayüzdeki bilgi ipuçlarıyla aynı):
 * <ul>
 *   <li>Başarılı = {@code LOGIN}. Başarısız = {@code LOGIN_FAILED} + kodla girişte {@code LOGIN_OTP_VERIFY_FAILED} /
 *       {@code LOGIN_OTP_EXPIRED} / {@code LOGIN_OTP_LOCKED}. Deneme = başarılı + başarısız.</li>
 *   <li>{@code LOGIN_OTP_REQUESTED} ve {@code LOGIN_OTP_DELIVERY_FAILED} deneme DEĞİL — kod hunisi (istendi → gönderildi →
 *       doğrulandı); teslim hatası sistem hatasıdır, ayrı gösterilir.</li>
 * </ul>
 *
 * <p><b>Kanal kararı</b> (sırayla): (1) bilinmeyen kullanıcı — neden {@code UNKNOWN_USER} ya da aktör hiçbir hesaba
 * çözülmüyorsa başarısız deneme kanala YAZILMAZ ("bilinmeyen kullanıcı" — tahminle bir kanala koymak numaralandırma
 * denemelerini gerçek kullanıcıların hanesine yazardı); (2) ayrıntıdaki {@code method} (2026-10-03'ten beri her giriş
 * yazar; eski ad PASSWORD = LOCAL); (3) kodla giriş olayının {@code channel} ayrıntısı; (4) eski ret satırlarının neden
 * metnindeki "(PASSWORD|LDAP|REMEMBER_ME|OTP_…)" eki; (5) yöntemsiz ESKİ satır → aktörün BUGÜNKÜ hesap kaynağı (LDAP / yerel)
 * ve {@code estimated=true} — bu sürümden önce parola girişi ile "beni hatırla" ayırt edilemiyordu.
 */
public final class LoginEventClassifier {

    private LoginEventClassifier() { }

    public static final String LOGIN = "LOGIN";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String OTP_REQUESTED = "LOGIN_OTP_REQUESTED";
    public static final String OTP_DELIVERY_FAILED = "LOGIN_OTP_DELIVERY_FAILED";
    public static final String OTP_VERIFY_FAILED = "LOGIN_OTP_VERIFY_FAILED";
    public static final String OTP_EXPIRED = "LOGIN_OTP_EXPIRED";
    public static final String OTP_LOCKED = "LOGIN_OTP_LOCKED";

    /** İstatistiğin okuduğu olay türleri (tek sorgu, {@code idx_audit_type_time}). */
    public static final List<String> EVENT_TYPES = List.of(LOGIN, LOGIN_FAILED, OTP_REQUESTED, OTP_DELIVERY_FAILED,
            OTP_VERIFY_FAILED, OTP_EXPIRED, OTP_LOCKED);
    /** Başarısız deneme sayılan türler. */
    public static final List<String> FAILURE_TYPES = List.of(LOGIN_FAILED, OTP_VERIFY_FAILED, OTP_EXPIRED, OTP_LOCKED);

    public static final String UNKNOWN_USER = "UNKNOWN_USER";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String OTHER = "OTHER";

    public enum Kind { SUCCESS, FAILURE, OTP_REQUEST, OTP_DELIVERY_FAILED, OTHER }

    /**
     * Sınıflandırma sonucu.
     *
     * @param channel     denemenin kanalı (bilinmeyen kullanıcıda / huni olaylarında kanal {@code otpChannel}'dadır)
     * @param estimated   kanal hesap kaynağından TAHMİN edildi (yöntemsiz eski satır)
     * @param unknownUser aktör bir hesaba çözülmüyor (kanala yazılmaz)
     * @param reason      başarısızlık nedeni kodu ({@code BAD_PASSWORD}, {@code OTP_INVALID}, {@code UNKNOWN_USER} …)
     * @param otpChannel  kodla giriş hunisinin kanalı (istek / teslim / doğrulama / kodla başarılı giriş)
     * @param otpResult   istek sonucu ({@code SENT} / {@code SUPPRESSED} / {@code REJECTED})
     * @param otpReason   bastırma nedeni ({@code UNKNOWN_USER}, {@code COOLDOWN} …)
     */
    public record Result(Kind kind, LoginChannel channel, boolean estimated, boolean unknownUser, String reason,
                         LoginChannel otpChannel, String otpResult, String otpReason) { }

    private static final Pattern REASON_CODE = Pattern.compile("^([A-Z][A-Z0-9_]{2,})(?=\\s*:|\\s*$)");
    private static final Pattern METHOD_SUFFIX =
            Pattern.compile("\\((PASSWORD|LOCAL|LDAP|REMEMBER_ME|OTP_PUSH|OTP_EMAIL)\\)\\s*$");

    /**
     * @param type          olay türü
     * @param actor         aktör (ham)
     * @param failureReason denetim nedeni (ham)
     * @param detail        denetim ayrıntısı (JSON metni)
     * @param sourceOf      aktör → hesap kaynağı ({@code LOCAL} / {@code LDAP} / boş = yerel); hesap YOKSA {@code null}
     */
    public static Result classify(String type, String actor, String failureReason, String detail,
                                  Function<String, String> sourceOf) {
        String t = type == null ? "" : type;
        switch (t) {
            case LOGIN -> {
                LoginChannel ch = LoginChannel.fromMethod(field(detail, "method"));
                if (ch != null) return new Result(Kind.SUCCESS, ch, false, false, null, ch.otp() ? ch : null, null, null);
                String src = source(actor, sourceOf);
                if (src == null) return new Result(Kind.SUCCESS, null, false, true, null, null, null, null);
                return new Result(Kind.SUCCESS, LoginChannel.ofAccountSource(src), true, false, null, null, null, null);
            }
            case LOGIN_FAILED, OTP_VERIFY_FAILED, OTP_EXPIRED, OTP_LOCKED -> {
                String reason = reasonCode(t, failureReason);
                LoginChannel otp = t.equals(LOGIN_FAILED) ? null : LoginChannel.fromOtpChannel(field(detail, "channel"));
                String src = source(actor, sourceOf);
                if (UNKNOWN_USER.equals(reason) || src == null) {
                    return new Result(Kind.FAILURE, null, false, true, UNKNOWN_USER, otp, null, null);
                }
                LoginChannel ch = LoginChannel.fromMethod(field(detail, "method"));
                if (ch == null) ch = otp;
                if (ch == null) ch = suffixChannel(failureReason);
                boolean estimated = false;
                if (ch == null) {
                    ch = LoginChannel.ofAccountSource(src);
                    estimated = true;
                }
                return new Result(Kind.FAILURE, ch, estimated, false, reason, otp, null, null);
            }
            case OTP_REQUESTED -> {
                LoginChannel otp = LoginChannel.fromOtpChannel(field(detail, "channel"));
                String result = field(detail, "result");
                String why = field(detail, "reason");
                return new Result(Kind.OTP_REQUEST, null, false, false, null, otp,
                        result == null ? null : result.toUpperCase(Locale.ROOT), why);
            }
            case OTP_DELIVERY_FAILED -> {
                LoginChannel otp = LoginChannel.fromOtpChannel(field(detail, "channel"));
                return new Result(Kind.OTP_DELIVERY_FAILED, null, false, false, null, otp, null, field(detail, "reason"));
            }
            default -> {
                return new Result(Kind.OTHER, null, false, false, null, null, null, null);
            }
        }
    }

    private static String source(String actor, Function<String, String> sourceOf) {
        if (actor == null || actor.isBlank() || sourceOf == null) return null;
        return sourceOf.apply(actor);
    }

    /**
     * Neden KODU: {@code failure_reason} öneki ("BAD_PASSWORD: attempt #1/5 …" → {@code BAD_PASSWORD}); oran sınırı satırı
     * ("Rate limited: …") → {@code RATE_LIMITED}; kod yoksa kodla giriş türünün varsayılanı ya da {@code OTHER}.
     */
    public static String reasonCode(String type, String failureReason) {
        if (failureReason != null) {
            String fr = failureReason.trim();
            if (fr.regionMatches(true, 0, "Rate limited", 0, 12)) return RATE_LIMITED;
            Matcher m = REASON_CODE.matcher(fr);
            if (m.find()) return m.group(1);
        }
        if (OTP_VERIFY_FAILED.equals(type)) return "OTP_INVALID";
        if (OTP_EXPIRED.equals(type)) return "OTP_EXPIRED";
        if (OTP_LOCKED.equals(type)) return "OTP_LOCKED";
        return OTHER;
    }

    /** Eski ret satırlarının neden ekinden kanal: "… (LDAP)" → LDAP, "(PASSWORD)" → LOCAL. */
    static LoginChannel suffixChannel(String failureReason) {
        if (failureReason == null) return null;
        Matcher m = METHOD_SUFFIX.matcher(failureReason);
        return m.find() ? LoginChannel.fromMethod(m.group(1)) : null;
    }

    /**
     * Düz JSON metninden tek bir DİZE alanı ({@code "key":"value"}) — tam ayrıştırıcı gerekmez: ayrıntıyı
     * {@code AuditDetail.of} yazar (düz nesne, kaçışlı dizeler). Alan yoksa / dize değilse {@code null}.
     */
    static String field(String json, String key) {
        if (json == null || json.isEmpty()) return null;
        String needle = "\"" + key + "\"";
        int at = json.indexOf(needle);
        while (at >= 0) {
            int i = at + needle.length();
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i < json.length() && json.charAt(i) == ':') {
                i++;
                while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
                if (i < json.length() && json.charAt(i) == '"') {
                    int end = json.indexOf('"', i + 1);
                    return end < 0 ? null : json.substring(i + 1, end);
                }
                return null;
            }
            at = json.indexOf(needle, at + 1);
        }
        return null;
    }
}
