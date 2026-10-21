package com.sitemonitor.service.loginpolicy;

import com.sitemonitor.util.Msg;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Kullanıcı adı ön eki kuralı (2026-10-21, kullanıcı isteği: "kullanıcılar iki ayrı username ile login olabiliyor — biri
 * N, diğeri MP ile başlıyor; yalnız N ile başlayana izin verelim, harfleri ayarlardan yöneteyim"). TEK kaynak, SAF
 * (veritabanı / oturum / saat yok) — giriş ucu, remember-me, kodla giriş, oturum kapısı ve önizleme aynı
 * {@link #allows} kararını kullanır.
 *
 * <h3>Doğruluk tablosu</h3>
 * <ol>
 *   <li>Kural kapalı ya da kullanılabilir ön ek yok ({@link #active()} false) → izin (bugünkü davranış birebir).</li>
 *   <li>Boş ad → izin (yargılanacak bir ad yok; normal akış zaten reddeder).</li>
 *   <li>Kurulumdaki bootstrap yönetici ({@code site.monitor.username}) ve muaf listesindeki adlar → izin (tam eşleşme,
 *       harf duyarsız). Global yönetici OTOMATİK muaf DEĞİL: kural "hangi hesapla girileceği" ile ilgilidir.</li>
 *   <li>Aksi hâlde ad, izinli ön eklerden biriyle başlamalı. Ad {@code DOMAIN\kullanıcı} ya da {@code kullanıcı@alan}
 *       biçimindeyse YAZILAN ad ile alan kısmı atılmış ad İKİSİ DE kurala uymalı (temkinli: {@code CORP\n123} C ile
 *       başlar → ret; {@code n123@corp} → izin; {@code n\mp123} → ret).</li>
 * </ol>
 * Karşılaştırma {@link Locale#ROOT} büyük harfle yapılır ve Türkçe i harfleri katlanır: {@code ı}, {@code i},
 * {@code İ} hepsi {@code I} sayılır (Türkçe yerel ayarındaki "i → İ" tuzağı yok).
 *
 * <p>Değerler: izinli ön ek 1–{@value #PREFIX_MAX_LEN} karakter {@code [A-Za-z0-9]}, en çok {@value #MAX_PREFIXES};
 * muaf ad 1–{@value #EXEMPT_MAX_LEN} karakter (boşluk / virgül / noktalı virgül / denetim karakteri yok), en çok
 * {@value #MAX_EXEMPT}. Ham değer {@value #MAX_RAW_CHARS} karakteri aşarsa ayrıştırılmadan reddedilir (sınırsız döngü yok).
 */
public final class UsernamePrefixRule {

    public static final String KEY_ENABLED = "site.monitor.login.username-prefix.enabled";
    public static final String KEY_ALLOWED = "site.monitor.login.username-prefix.allowed";
    public static final String KEY_EXEMPT = "site.monitor.login.username-prefix.exempt";
    /** Üç anahtar — hepsi grup {@code login-methods}, hepsi GLOBAL_ONLY. */
    public static final List<String> KEYS = List.of(KEY_ENABLED, KEY_ALLOWED, KEY_EXEMPT);

    /** Telde (Giriş Yöntemleri kaydı / önizleme) alan adları — hata yanıtının {@code field} değeri de bunlar. */
    public static final String FIELD_ENABLED = "username_prefix_enabled";
    public static final String FIELD_ALLOWED = "username_prefix_allowed";
    public static final String FIELD_EXEMPT = "username_prefix_exempt";

    /** Anahtar → tel alanı (AppSettingsService hangi uçtan gelirse gelsin aynı alan adıyla hata verir). */
    public static final Map<String, String> FIELD_OF = Map.of(
            KEY_ENABLED, FIELD_ENABLED, KEY_ALLOWED, FIELD_ALLOWED, KEY_EXEMPT, FIELD_EXEMPT);

    public static final int PREFIX_MAX_LEN = 10;
    public static final int MAX_PREFIXES = 20;
    public static final int EXEMPT_MAX_LEN = 100;
    public static final int MAX_EXEMPT = 50;
    /** Ham CSV / liste toplam uzunluğu — ayrıştırmadan ÖNCE denetlenir. */
    public static final int MAX_RAW_CHARS = 6000;

    /** Giriş reddi kodu (403 giriş, 401 oturum / remember-me) — istemci bunu tanır. */
    public static final String CODE_DENIED = "USERNAME_PREFIX_NOT_ALLOWED";
    /** Kaydeden yöneticinin kendi hesabı kurala uymuyor. */
    public static final String CODE_LOCKOUT_SELF = "LOCKOUT_SELF";
    /** Denetim nedeni öneki ({@code failure_reason}) ve kodla giriş tuzak nedeni. */
    public static final String REASON = "USERNAME_PREFIX";

    private static final Pattern PREFIX_RE = Pattern.compile("[A-Za-z0-9]{1," + PREFIX_MAX_LEN + "}");

    private final boolean enabled;
    private final List<String> prefixes;
    private final Set<String> exempt;
    private final String bootstrapAdmin;

    private UsernamePrefixRule(boolean enabled, List<String> prefixes, Set<String> exempt, String bootstrapAdmin) {
        this.enabled = enabled;
        this.prefixes = List.copyOf(prefixes);
        this.exempt = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(exempt));   // GİRİŞ sırası (her pod aynı liste)
        this.bootstrapAdmin = bootstrapAdmin == null || bootstrapAdmin.isBlank() ? null : fold(bootstrapAdmin);
    }

    /**
     * Saklı değerlerden kural — HOŞGÖRÜLÜ okuma: geçersiz öğeler atlanır (kayıt yolu zaten doğrular; elle bozulmuş bir
     * değer girişi tümden kilitlemesin). Açık ama kullanılabilir ön eki olmayan kural ETKİN DEĞİLDİR (kimse kilitlenmez).
     */
    public static UsernamePrefixRule of(boolean enabled, String allowedCsv, String exemptCsv, String bootstrapAdmin) {
        List<String> p = new ArrayList<>();
        for (String s : splitBounded(allowedCsv)) {
            if (PREFIX_RE.matcher(s).matches()) {
                String f = s.toUpperCase(Locale.ROOT);
                if (!p.contains(f) && p.size() < MAX_PREFIXES) p.add(f);
            }
        }
        Set<String> e = new LinkedHashSet<>();
        for (String s : splitBounded(exemptCsv)) {
            if (validExempt(s) && e.size() < MAX_EXEMPT) e.add(fold(s));
        }
        return new UsernamePrefixRule(enabled, p, e, bootstrapAdmin);
    }

    /** Kapalı kural (her ad geçer). */
    public static UsernamePrefixRule disabled(String bootstrapAdmin) {
        return new UsernamePrefixRule(false, List.of(), Set.of(), bootstrapAdmin);
    }

    /** Kural UYGULANIYOR mu: açık VE en az bir kullanılabilir ön ek var. */
    public boolean active() {
        return enabled && !prefixes.isEmpty();
    }

    public boolean enabled() {
        return enabled;
    }

    /** İzinli ön ekler (büyük harf, sıralı, tekil). */
    public List<String> prefixes() {
        return prefixes;
    }

    /** Muaf adlar (katlanmış büyük harf). */
    public Set<String> exempt() {
        return exempt;
    }

    /** Bu ad giriş yapabilir mi — sınıf belgesindeki doğruluk tablosu. */
    public boolean allows(String username) {
        if (!active()) return true;
        if (username == null || username.isBlank()) return true;
        if (isExempt(username)) return true;
        String full = fold(username);
        if (!startsWithAllowed(full)) return false;
        String bare = stripDomain(full);
        return bare.equals(full) || startsWithAllowed(bare);
    }

    /** Ad muaf mı (bootstrap yönetici ya da muaf listesi; tam eşleşme, harf duyarsız). Kural kapalıyken de yanıtlar. */
    public boolean isExempt(String username) {
        if (username == null || username.isBlank()) return false;
        String f = fold(username);
        return f.equals(bootstrapAdmin) || exempt.contains(f);
    }

    private boolean startsWithAllowed(String folded) {
        for (String p : prefixes) {
            if (folded.startsWith(p)) return true;
        }
        return false;
    }

    // ── Biçim ──────────────────────────────────────────────────────────────────

    /**
     * Karşılaştırma biçimi: kırpılmış, {@link Locale#ROOT} büyük harf; Türkçe {@code İ} (U+0130) → {@code I}, birleşik
     * nokta (U+0307) atılır ({@code ı} zaten ROOT büyük harfte {@code I} olur).
     */
    public static String fold(String s) {
        if (s == null) return "";
        String u = s.strip().toUpperCase(Locale.ROOT);
        if (u.indexOf('İ') >= 0) u = u.replace('İ', 'I');
        if (u.indexOf('̇') >= 0) u = u.replace("̇", "");
        return u;
    }

    /** {@code DOMAIN\kullanıcı} → {@code kullanıcı}; {@code kullanıcı@alan} → {@code kullanıcı}. */
    static String stripDomain(String s) {
        String out = s;
        int bs = out.lastIndexOf('\\');
        if (bs >= 0) out = out.substring(bs + 1);
        int at = out.indexOf('@');
        if (at >= 0) out = out.substring(0, at);
        return out;
    }

    /** Ham CSV → kırpılmış, boş olmayan öğeler; ham değer {@link #MAX_RAW_CHARS}'ı aşarsa boş (okuma yolu). */
    private static List<String> splitBounded(String csv) {
        if (csv == null || csv.isBlank() || csv.length() > MAX_RAW_CHARS) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : csv.split("[,;\\n]")) {
            String t = part.strip();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static boolean validExempt(String s) {
        if (s == null || s.isEmpty() || s.length() > EXEMPT_MAX_LEN) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c) || c == ',' || c == ';') return false;
        }
        return true;
    }

    // ── Kayıt doğrulaması (SIKI) ───────────────────────────────────────────────

    /** Alan bazlı doğrulama sonucu: tel alanı + kararlı kod + TR / EN ileti. */
    public record Problem(String field, String code, String tr, String en) {
        public String message() { return Msg.t(tr, en); }
    }

    /** Doğrulama hatası (alan + kod). {@code IllegalArgumentException} — eski yakalayıcılar 400 görür. */
    public static final class InvalidValue extends IllegalArgumentException {
        private final transient Problem problem;
        public InvalidValue(Problem p) { super(p.message()); this.problem = p; }
        public Problem problem() { return problem; }
    }

    /**
     * İzinli ön ekler (liste ya da CSV) → normalize CSV ({@code "N,MP"}); her öğe {@code [A-Za-z0-9]{1,10}}, en çok
     * {@value #MAX_PREFIXES}, tekrar atılır. Geçersiz öğe hatası öğeyi adıyla söyler.
     */
    public static String normalizePrefixes(Object raw) {
        List<String> items = items(raw, FIELD_ALLOWED);
        List<String> out = new ArrayList<>();
        for (String s : items) {
            if (!PREFIX_RE.matcher(s).matches()) {
                throw new InvalidValue(new Problem(FIELD_ALLOWED, "VALIDATION_FAILED",
                        "Geçersiz ön ek: \"" + clip(s) + "\". Ön ek 1–" + PREFIX_MAX_LEN
                                + " karakter olmalı ve yalnız harf (A–Z) ile rakam içermeli; Türkçe karakter ve boşluk kullanmayın.",
                        "Invalid prefix: \"" + clip(s) + "\". A prefix must be 1–" + PREFIX_MAX_LEN
                                + " characters of letters (A–Z) and digits only; don't use Turkish characters or spaces."));
            }
            String u = s.toUpperCase(Locale.ROOT);
            if (!out.contains(u)) out.add(u);
        }
        if (out.size() > MAX_PREFIXES) {
            throw new InvalidValue(new Problem(FIELD_ALLOWED, "VALIDATION_FAILED",
                    "En çok " + MAX_PREFIXES + " ön ek girilebilir; fazlasını silin.",
                    "At most " + MAX_PREFIXES + " prefixes can be entered; remove the extra ones."));
        }
        return String.join(",", out);
    }

    /** Muaf kullanıcı adları (liste ya da CSV) → normalize CSV (büyük harf, tekil); en çok {@value #MAX_EXEMPT}. */
    public static String normalizeExempt(Object raw) {
        List<String> items = items(raw, FIELD_EXEMPT);
        List<String> out = new ArrayList<>();
        for (String s : items) {
            if (!validExempt(s)) {
                throw new InvalidValue(new Problem(FIELD_EXEMPT, "VALIDATION_FAILED",
                        "Geçersiz kullanıcı adı: \"" + clip(s) + "\". Ad 1–" + EXEMPT_MAX_LEN
                                + " karakter olmalı; boşluk, virgül ve noktalı virgül içeremez.",
                        "Invalid username: \"" + clip(s) + "\". A username must be 1–" + EXEMPT_MAX_LEN
                                + " characters with no spaces, commas or semicolons."));
            }
            String u = fold(s);
            if (!out.contains(u)) out.add(u);
        }
        if (out.size() > MAX_EXEMPT) {
            throw new InvalidValue(new Problem(FIELD_EXEMPT, "VALIDATION_FAILED",
                    "En çok " + MAX_EXEMPT + " muaf kullanıcı adı girilebilir; fazlasını silin.",
                    "At most " + MAX_EXEMPT + " exempt usernames can be entered; remove the extra ones."));
        }
        return String.join(",", out);
    }

    /** Liste / CSV → kırpılmış boş olmayan öğeler; toplam uzunluk ve öğe sayısı ayrıştırmadan ÖNCE sınırlı. */
    private static List<String> items(Object raw, String field) {
        if (raw == null) return List.of();
        List<String> out = new ArrayList<>();
        if (raw instanceof Collection<?> c) {
            if (c.size() > 4 * Math.max(MAX_PREFIXES, MAX_EXEMPT)) throw tooLong(field);
            int total = 0;
            for (Object o : c) {
                String s = o == null ? "" : o.toString();
                total += s.length();
                if (total > MAX_RAW_CHARS) throw tooLong(field);
                String t = s.strip();
                if (!t.isEmpty()) out.add(t);
            }
            return out;
        }
        String s = raw.toString();
        if (s.length() > MAX_RAW_CHARS) throw tooLong(field);
        return splitBounded(s);
    }

    private static InvalidValue tooLong(String field) {
        return new InvalidValue(new Problem(field, "VALIDATION_FAILED",
                "Liste çok uzun; en çok " + MAX_RAW_CHARS + " karakter gönderilebilir. Fazla öğeleri silin.",
                "The list is too long; at most " + MAX_RAW_CHARS + " characters can be sent. Remove the extra items."));
    }

    private static String clip(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }

    /**
     * Kayıt anı alanlar-arası kuralı (etkin değerlerle): açıkken en az bir ön ek ZORUNLU; kaydeden yöneticinin KENDİ hesabı
     * kurala uymalı (ön ek ya da muaf) — yoksa {@link #CODE_LOCKOUT_SELF} (kayıt reddedilir, kimse kilitlenmez).
     *
     * @param saver oturumdaki kullanıcı adı (istek bağlamı yoksa null → kendi hesap denetimi atlanır)
     * @return sorun ya da null
     */
    public static Problem validateSave(boolean enabled, String allowedCsv, String exemptCsv, String saver,
                                       String bootstrapAdmin) {
        if (!enabled) return null;
        UsernamePrefixRule r = of(true, allowedCsv, exemptCsv, bootstrapAdmin);
        if (r.prefixes().isEmpty()) {
            return new Problem(FIELD_ALLOWED, "VALIDATION_FAILED",
                    "Kuralı açmak için en az bir izinli ön ek girin (örneğin N). Liste boşken kural açılamaz.",
                    "Enter at least one allowed prefix (for example N) to turn the rule on. The rule can't be on with an empty list.");
        }
        if (saver != null && !saver.isBlank() && !r.allows(saver)) {
            String name = clip(saver.strip());
            return new Problem(FIELD_ALLOWED, CODE_LOCKOUT_SELF,
                    "Bu ayarla kendi hesabınız (" + name + ") giriş yapamaz. Hesabınızın ön ekini izinli listeye ekleyin ya da "
                            + "kullanıcı adınızı muaf listesine yazın, sonra yeniden kaydedin.",
                    "With this setting your own account (" + name + ") couldn't sign in. Add your account's prefix to the allowed "
                            + "list or put your username on the exempt list, then save again.");
        }
        return null;
    }

    // ── Yanıt gövdesi ──────────────────────────────────────────────────────────

    /** Kullanıcıya gösterilen ret metni — izinli ön ekleri adlandırır (ön ek gizli değildir). */
    public static String deniedMessage(List<String> prefixes) {
        String list = prefixes == null || prefixes.isEmpty() ? "?" : String.join(", ", prefixes);
        return Msg.t("Bu kullanıcı adıyla giriş yapılamaz. Yalnız " + list + " ile başlayan kullanıcı adlarıyla giriş yapılabilir; "
                        + "o hesabınızla yeniden deneyin.",
                "You can't sign in with this username. Only usernames that start with " + list
                        + " can sign in; try again with that account.");
    }

    /** Ret gövdesi: {@code {success:false, code, error_code, error, allowed_prefixes}}. */
    public static Map<String, Object> deniedBody(List<String> prefixes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("code", CODE_DENIED);
        m.put("error_code", CODE_DENIED);
        m.put("error", deniedMessage(prefixes));
        m.put("allowed_prefixes", prefixes == null ? List.of() : List.copyOf(prefixes));
        return m;
    }
}
