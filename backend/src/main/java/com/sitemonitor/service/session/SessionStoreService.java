package com.sitemonitor.service.session;

import com.sitemonitor.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Oturum deposu — çalışma anında GERÇEKTE hangisi kullanılıyor ve açılışta tek-oturum işaretleri nasıl temizlenir
 * (2026-10-09, bkz. {@link com.sitemonitor.config.SessionStoreAutoConfigurationFilter}).
 *
 * <p>Karar ayardan değil BAĞLAMDAN okunur: {@link JdbcIndexedSessionRepository} bean'i varsa JDBC, yoksa bellek. Ayar
 * "jdbc" olup bean oluşmasaydı (yapılandırma hatası) Sistem Sağlığı yine "bellek" der — yanıltmaz.
 *
 * <p><b>Açılış temizliği.</b> Tek-oturum kaydı ({@code app_users.active_session_id}) açılışta temizlenir, çünkü bellek
 * oturumları yeniden başlatmayı yaşamaz (aksi hâlde "başka yerde aktif oturum" onayı boşuna çıkar). JDBC deposunda
 * oturum yaşar: hepsini silmek her pod açılışında (çok pod'da her ölçeklemede) tüm kullanıcıların çevrimiçi sayımını
 * sıfırlar ve tek-oturum korumasını ilk ping'e kadar kaldırırdı. JDBC'de yalnız KARŞILIĞI KALMAMIŞ (süresi dolmuş ya
 * da silinmiş oturum) işaretler temizlenir; yönetici sonlandırma işareti ({@code TERMINATED:}) iki kipte de korunur.
 */
@Slf4j
@Service
public class SessionStoreService {

    public static final String MEMORY = "memory";
    public static final String JDBC = "jdbc";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    private final ObjectProvider<SessionRepository<?>> sessionRepository;
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final UserService userService;
    private final String table;
    private LongSupplier clock = System::currentTimeMillis;

    public SessionStoreService(ObjectProvider<SessionRepository<?>> sessionRepository, ObjectProvider<JdbcTemplate> jdbc,
                               UserService userService,
                               @Value("${spring.session.jdbc.table-name:SPRING_SESSION}") String table) {
        this.sessionRepository = sessionRepository;
        this.jdbc = jdbc;
        this.userService = userService;
        this.table = table;
    }

    /** Testler için saat. */
    void setClock(LongSupplier clock) { this.clock = clock; }

    /** Çalışan depo: {@code jdbc} ya da {@code memory}. */
    public String mode() {
        return sessionRepository.getIfAvailable() instanceof JdbcIndexedSessionRepository ? JDBC : MEMORY;
    }

    public boolean isJdbc() { return JDBC.equals(mode()); }

    /** Oturum tablosu adı — SQL'e yazılmadan önce tanımlayıcı olarak doğrulanır (ayardan geldiği için). */
    String safeTable() {
        String t = table == null ? "" : table.trim();
        if (!IDENTIFIER.matcher(t).matches()) throw new IllegalStateException("Geçersiz oturum tablosu adı: " + t);
        return t;
    }

    /**
     * Açılışta tek-oturum işaretlerini temizler; temizlenen satır sayısını döner.
     * Bellek kipi: bugünkü davranış ({@link UserService#clearAllActiveSessions}). JDBC kipi: yalnız depoda CANLI
     * karşılığı olmayan işaretler. JDBC sorgusu düşerse bellek kipinin davranışına düşer (açılış durmaz).
     */
    public int clearStaleActiveSessionMarkers() {
        if (!isJdbc()) return userService.clearAllActiveSessions();
        JdbcTemplate j = jdbc.getIfAvailable();
        if (j == null) return userService.clearAllActiveSessions();
        try {
            int n = j.update("UPDATE app_users SET active_session_id = NULL WHERE active_session_id IS NOT NULL "
                    + "AND active_session_id NOT LIKE 'TERMINATED:%' AND NOT EXISTS (SELECT 1 FROM " + safeTable()
                    + " s WHERE s.session_id = app_users.active_session_id AND s.expiry_time > ?)", clock.getAsLong());
            userService.evictActiveSessionCaches();
            return n;
        } catch (RuntimeException e) {
            log.warn("Oturum deposu sorgulanamadı ({}) — açılış temizliği bellek kipinin kuralıyla yapılıyor", e.toString());
            return userService.clearAllActiveSessions();
        }
    }

    /**
     * Sistem Sağlığı özeti: {@code store} (jdbc|memory), JDBC'de {@code table}, {@code live} (süresi dolmamış oturum) ve
     * {@code expired} (temizlik işini bekleyen). Sayım düşerse alanlar yazılmaz — sayfa bozulmaz.
     */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        String mode = mode();
        m.put("store", mode);
        if (!JDBC.equals(mode)) return m;
        m.put("table", table);
        JdbcTemplate j = jdbc.getIfAvailable();
        if (j == null) return m;
        try {
            long now = clock.getAsLong();
            String t = safeTable();
            m.put("live", j.queryForObject("SELECT COUNT(*) FROM " + t + " WHERE expiry_time > ?", Long.class, now));
            m.put("expired", j.queryForObject("SELECT COUNT(*) FROM " + t + " WHERE expiry_time <= ?", Long.class, now));
        } catch (RuntimeException e) {
            log.debug("Oturum sayımı okunamadı: {}", e.toString());
        }
        return m;
    }
}
