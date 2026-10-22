package com.sitemonitor.service.lockout;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Kalıcı satırları SAYAN oran sınırları (2026-10-23, çok pod Sürüm 2a — MP-32, MP-33). Pod belleğindeki kayan pencere
 * (deque) N pod'da N× gevşiyordu; burada sayım, yan etkisi olan eylemin ZATEN yazdığı satırdan yapılır — hangi pod'da
 * olursa olsun aynı sayı:
 * <ul>
 *   <li>{@link #secretReveals}: k6 gizli değer gösterimi — her başarılı gösterim {@code audit_log}'a
 *       {@code SCRIPTED_SECRET_REVEAL} yazar ({@code AuditService.persist} EŞZAMANLI, yanıt dönmeden satır işlenmiş olur).
 *       Kullanıcı başına dakikada / saatte sayılır (tek sorgu, {@code idx_audit_actor_id}).</li>
 *   <li>{@link #issueReports}: oturumsuz sorun bildirimi / istemci hatası — her kabul edilen bildirim
 *       {@code login_issue_reports}'a kaynak ({@code LOGIN} / {@code CLIENT_ERROR}) + IP ile yazılır. IP başına saatlik
 *       sayım ({@code idx_lir_ip_reported}, DeferredIndexBuilder).</li>
 * </ul>
 * Zaman damgaları uygulamanın ISO biçimi ({@code yyyy-MM-dd'T'HH:mm:ss}, UTC, saniye) — dizgesel karşılaştırma güvenli.
 * Okuma hatası {@code null} döner: çağıran bellek içi sınırına güvenir (20.165.1 davranışı, asla daha zayıf değil). Eşzamanlı
 * isteklerin aşımı eşzamanlılıkla sınırlıdır (sayım satır yazılmadan önce yapılır).
 */
@Slf4j
@Service
public class PersistedRowLimits {

    public static final String EVENT_SECRET_REVEAL = "SCRIPTED_SECRET_REVEAL";

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter PARSE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** {@code login_issue_reports.ip_address} genişliği — kayıt {@code LoginIssueService} ile aynı kırpılır. */
    static final int IP_MAX = 50;

    static final String SQL_REVEALS = "SELECT COUNT(*), MIN(event_time), "
            + "COALESCE(SUM(CASE WHEN event_time >= ? THEN 1 ELSE 0 END), 0), "
            + "MIN(CASE WHEN event_time >= ? THEN event_time END) "
            + "FROM audit_log WHERE event_type = '" + EVENT_SECRET_REVEAL + "' AND %s AND event_time >= ?";

    static final String SQL_REPORTS = "SELECT COUNT(*) FROM login_issue_reports "
            + "WHERE source = ? AND ip_address = ? AND reported_at >= ?";

    private final JdbcTemplate jdbc;

    @Autowired
    public PersistedRowLimits(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Bir kullanıcının pencere içindeki başarılı gizli değer gösterimleri: son dakikadaki ve son saatteki sayı ile her
     * pencerenin EN ESKİ gösterim anı (epoch ms, saniyeye yuvarlanmış; yeniden deneme süresi için).
     */
    public record Window(int minute, Long minuteOldestMs, int hour, Long hourOldestMs) {}

    /**
     * @param actorId oturumdaki kullanıcı kimliği (öncelikli; denetim satırının {@code actor_id}'si)
     * @param actor   kimlik yoksa kullanıcı adı (denetim satırının {@code actor}'ü)
     * @return sayım; okunamadı ya da kimlik yok → {@code null}
     */
    public Window secretReveals(Long actorId, String actor, long nowMs, long minuteMs, long hourMs) {
        if (actorId == null && (actor == null || actor.isBlank())) return null;
        String minuteCut = ISO.format(Instant.ofEpochMilli(nowMs - minuteMs));
        String hourCut = ISO.format(Instant.ofEpochMilli(nowMs - hourMs));
        String sql = String.format(SQL_REVEALS, actorId != null ? "actor_id = ?" : "actor = ?");
        Object who = actorId != null ? actorId : actor;
        try {
            List<Window> rows = jdbc.query(sql, (rs, i) -> new Window(
                    rs.getInt(3), parse(rs.getString(4)), rs.getInt(1), parse(rs.getString(2))),
                    minuteCut, minuteCut, who, hourCut);
            return rows.isEmpty() ? new Window(0, null, 0, null) : rows.getFirst();
        } catch (Exception e) {
            log.warn("Gizli değer gösterim sayısı okunamadı — yalnız pod sınırı uygulanır: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Bir IP'den {@code sinceMs}'ten bu yana kabul edilmiş bildirim sayısı ({@code source} = {@code LOGIN} /
     * {@code CLIENT_ERROR}).
     *
     * @return sayı; okunamadı → {@code null}
     */
    public Integer issueReports(String source, String ip, long sinceMs) {
        String key = ip == null ? null : ip.strip();
        if (key != null && key.length() > IP_MAX) key = key.substring(0, IP_MAX);
        if (source == null || key == null || key.isEmpty()) return null;
        try {
            return jdbc.queryForObject(SQL_REPORTS, Integer.class, source, key, ISO.format(Instant.ofEpochMilli(sinceMs)));
        } catch (Exception e) {
            log.warn("Sorun bildirimi sayısı okunamadı — yalnız pod sınırı uygulanır: {}", e.getMessage());
            return null;
        }
    }

    /** ISO damga → epoch ms; boş / bozuk → null. */
    static Long parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            String s = iso.length() > 19 ? iso.substring(0, 19) : iso;
            return LocalDateTime.parse(s, PARSE).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }
}
