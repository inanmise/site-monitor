package com.sitemonitor.service.loginpolicy;

import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.service.AppSettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Kullanıcı adı ön eki politikası (2026-10-21) — {@link UsernamePrefixRule}'u CANLI ayarlardan kurar. Ayarlar
 * {@link AppSettingsService} belleğinden okunur (veritabanı yok; başka pod'un kaydı ≤ 10 sn'de yansır); ayrıştırılmış kural
 * ham üç değerle anahtarlanıp saklanır — {@code AuthInterceptor} her istekte çağırır, maliyet üç bellek okuması.
 *
 * <p><b>Okuma hatası girişi KAPATMAZ (fail-open)</b> — bakım kapısıyla aynı ilke: ayar okunamıyorsa kimse dışarıda kalmaz.
 */
@Slf4j
@Service
public class UsernamePrefixPolicy {

    /** Önizleme listesinin boyu. */
    public static final int SAMPLE_LIMIT = 50;

    private final AppSettingsService settings;
    private final AppUserRepository userRepo;
    private final String bootstrapAdmin;

    /** Son ayrıştırma: ham değerler + kural (değerler değişmedikçe yeniden ayrıştırılmaz). */
    private record Memo(boolean enabled, String allowed, String exempt, UsernamePrefixRule rule) { }

    private volatile Memo memo;

    public UsernamePrefixPolicy(AppSettingsService settings, AppUserRepository userRepo,
                                @Value("${site.monitor.username:user}") String bootstrapAdmin) {
        this.settings = settings;
        this.userRepo = userRepo;
        this.bootstrapAdmin = bootstrapAdmin;
    }

    /** Kurulumdaki bootstrap yönetici adı (her zaman muaf). */
    public String bootstrapAdmin() {
        return bootstrapAdmin;
    }

    /** Güncel kural; okunamazsa KAPALI kural (fail-open). */
    public UsernamePrefixRule rule() {
        try {
            boolean enabled = settings.getBoolean(UsernamePrefixRule.KEY_ENABLED, false);
            String allowed = settings.getString(UsernamePrefixRule.KEY_ALLOWED, "");
            String exempt = settings.getString(UsernamePrefixRule.KEY_EXEMPT, "");
            Memo m = memo;
            if (m != null && m.enabled() == enabled && Objects.equals(m.allowed(), allowed) && Objects.equals(m.exempt(), exempt)) {
                return m.rule();
            }
            UsernamePrefixRule r = UsernamePrefixRule.of(enabled, allowed, exempt, bootstrapAdmin);
            if (enabled && !r.active()) {
                log.warn("Kullanıcı adı ön eki kuralı açık ama kullanılabilir ön ek yok — kural UYGULANMIYOR (değer: '{}')", allowed);
            }
            memo = new Memo(enabled, allowed, exempt, r);
            return r;
        } catch (Exception e) {
            log.debug("Kullanıcı adı ön eki ayarı okunamadı — kural uygulanmıyor: {}", e.toString());
            return UsernamePrefixRule.disabled(bootstrapAdmin);
        }
    }

    /** Bu ad giriş yapabilir mi (kapalı / okunamayan kural → evet). */
    public boolean allows(String username) {
        try {
            return rule().allows(username);
        } catch (Exception e) {
            return true;
        }
    }

    /** Kural uygulanıyor mu. */
    public boolean active() {
        return rule().active();
    }

    /** Ret gövdesi (izinli ön eklerle). */
    public Map<String, Object> deniedBody() {
        return UsernamePrefixRule.deniedBody(rule().prefixes());
    }

    /**
     * Giriş sayfası ipucu (PUBLIC, eklemeli) — yalnız kural UYGULANIRKEN {@code {allowed: ["N"]}}; muaf liste ASLA yer
     * almaz (hesap adlarını açığa vururdu). Kapalıyken null (anahtar hiç yazılmaz → yanıt bugünküyle birebir).
     */
    public Map<String, Object> publicHint() {
        UsernamePrefixRule r = rule();
        if (!r.active()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("allowed", r.prefixes());
        return m;
    }

    /** Yönetici görünümü: açık mı, izinli ön ekler, muaf adlar, bootstrap yönetici (her zaman muaf). */
    public Map<String, Object> settingsView() {
        UsernamePrefixRule r = rule();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", r.enabled());
        m.put("active", r.active());
        m.put("allowed", r.prefixes());
        m.put("exempt", new ArrayList<>(r.exempt()));
        m.put("bootstrap_admin", bootstrapAdmin);
        m.put("limits", Map.of(
                "prefix_max_len", UsernamePrefixRule.PREFIX_MAX_LEN,
                "max_prefixes", UsernamePrefixRule.MAX_PREFIXES,
                "exempt_max_len", UsernamePrefixRule.EXEMPT_MAX_LEN,
                "max_exempt", UsernamePrefixRule.MAX_EXEMPT,
                "sample_limit", SAMPLE_LIMIT));
        return m;
    }

    /**
     * Önizleme (kaydetmeden): verilen ön ek / muaf listesiyle kural AÇIK olsaydı hangi AKTİF kullanıcılar giremezdi.
     * Tek projeksiyon sorgusu (kullanıcı dizini); kullanıcı başına sorgu yok. Kimlik numarası dönmez (ad + görünen ad).
     *
     * @param allowedCsv normalize ön ekler ({@link UsernamePrefixRule#normalizePrefixes})
     * @param exemptCsv  normalize muaf adlar
     * @param saver      oturumdaki yönetici — {@code self_refused} için
     */
    public Map<String, Object> preview(String allowedCsv, String exemptCsv, String saver) {
        UsernamePrefixRule r = UsernamePrefixRule.of(true, allowedCsv, exemptCsv, bootstrapAdmin);
        long active = 0;
        long exemptActive = 0;
        List<Map<String, Object>> refused = new ArrayList<>();
        long refusedCount = 0;
        for (Object[] row : userRepo.findLoginStatDirectory()) {
            if (row == null || row.length < 5 || !Boolean.TRUE.equals(row[4])) continue;
            String username = row[0] == null ? null : row[0].toString();
            if (username == null || username.isBlank()) continue;
            active++;
            if (r.isExempt(username)) {
                exemptActive++;
                continue;
            }
            if (!r.allows(username)) {
                refusedCount++;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("username", username);
                m.put("display_name", row[1] == null ? null : row[1].toString());
                m.put("auth_source", row[3] == null ? null : row[3].toString());
                refused.add(m);
            }
        }
        refused.sort(Comparator.comparing(m -> String.valueOf(m.get("username"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("allowed", r.prefixes());
        out.put("exempt", new ArrayList<>(r.exempt()));
        out.put("active_users", active);
        out.put("exempt_users", exemptActive);
        out.put("refused", refusedCount);
        out.put("allowed_users", active - refusedCount);
        out.put("sample", refused.size() > SAMPLE_LIMIT ? new ArrayList<>(refused.subList(0, SAMPLE_LIMIT)) : refused);
        out.put("sample_limit", SAMPLE_LIMIT);
        out.put("truncated", refused.size() > SAMPLE_LIMIT);
        out.put("self_refused", saver != null && !saver.isBlank() && !r.allows(saver));
        return out;
    }
}
