package com.sitemonitor.service.noc;

import com.sitemonitor.model.NocDelivery;
import com.sitemonitor.repository.NocDeliveryRepository;
import com.sitemonitor.service.EmailNotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 7/24 teslim satırlarının ({@code noc_deliveries}) SMTP 421 kuyruk sonucunu yazar (2026-10-21, çok pod R1 / MP-06).
 *
 * <p>7/24 postası 421 alınca teslim satırı ilk denemenin cevabıyla {@code QUEUED_RETRY…} kalır; yeniden denemenin sonucu
 * ({@code EmailNotificationService.writeBackRetryStatus}) yalnız posta GÜNLÜĞÜ satırına yazılır, teslim satırına hiç
 * yazılmıyordu. {@code QUEUED_RETRY} "gitti" sayıldığı için ({@code NocNotificationService.sent}, 7/24 konsolu,
 * {@code noc=sent}) gönderen pod öldüğünde ya da yeniden deneme sonunda başarısız olduğunda açılış sonsuza dek "gitti"
 * görünüyor, hiçbir tetik yeniden denemiyordu.
 *
 * <p>Kural (her tur, {@code MailRetryOrphanSweeper}'ın kilidi altında, posta günlüğünün yetim satırları yazıldıktan SONRA):
 * teslim satırının aynı denemede yazılan posta günlüğü satırı (rol {@code NOC}, aynı konu, gönderim anı denemeden
 * {@link #LOG_MATCH_WINDOW} içinde) KESİN bir sonuç taşıyorsa (SENT / FAILED…) teslim satırına o yazılır; günlük satırı
 * yoksa ya da hâlâ kuyruktaysa ve deneme yetim eşiğinden eskiyse
 * {@link EmailNotificationService#STATUS_RETRY_ORPHANED} (FAILED sınıfı). Yazım {@link NocDeliveryRepository#finishQueuedRetry}
 * ile koşullu ({@code reclaim} ile aynı koşul): iki pod aynı satırı iki kez yazmaz, arada yeniden sahiplenilmiş satıra dokunulmaz.
 * FAILED olan açılış mevcut kurallarla yeniden denenir (alarm hunisi en çok günde bir, fırtına tik'i 5 dk arayla en çok 3
 * deneme) — sonucu SENT olan bir daha gönderilmez; çözüm postası yalnız gitmiş açılışın ardından gider.
 */
@Slf4j
@Component
public class NocQueuedRetryReconciler {

    /** Geriye bakış ({@code created_at} aralığı). */
    static final Duration LOOKBACK = Duration.ofDays(7);
    /** Teslim satırının son denemesi ile posta günlüğü satırının yazılışı arasındaki en büyük fark. */
    static final Duration LOG_MATCH_WINDOW = Duration.ofMinutes(2);

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final NocDeliveryRepository deliveries;
    private final JdbcTemplate jdbc;

    public NocQueuedRetryReconciler(NocDeliveryRepository deliveries, JdbcTemplate jdbc) {
        this.deliveries = deliveries;
        this.jdbc = jdbc;
    }

    /** Bir tur — kesin sonucu yazılan teslim satırı sayısı. {@code orphanAge}: posta günlüğü süpürücüsüyle aynı eşik. */
    public int reconcile(Instant now, Duration orphanAge) {
        List<NocDelivery> queued = deliveries.findTop200ByStatusStartingWithAndCreatedAtGreaterThanEqualOrderByIdAsc(
                "QUEUED_RETRY", ISO.format(now.minus(LOOKBACK)));
        int done = 0;
        for (NocDelivery d : queued) {
            try {
                String at = d.getUpdatedAt() != null ? d.getUpdatedAt() : d.getCreatedAt();
                Instant attempt = parse(at);
                String logStatus = logStatusOf(d.getSubject(), attempt);
                String target;
                if (logStatus != null && !logStatus.startsWith("QUEUED_RETRY")) {
                    target = logStatus;
                } else if (attempt != null && !attempt.plus(orphanAge).isAfter(now)) {
                    target = EmailNotificationService.STATUS_RETRY_ORPHANED;
                } else {
                    continue;   // sonuç hâlâ gelebilir
                }
                int rows = deliveries.finishQueuedRetry(d.getId(), d.getStatus() == null ? "" : d.getStatus(),
                        d.getUpdatedAt() == null ? "" : d.getUpdatedAt(), target, ISO.format(now));
                if (rows == 1) {
                    done++;
                    log.info("7/24 teslimi ({}) kuyruk sonucu yazıldı: {}", d.getDedupeKey(), target.length() > 80 ? target.substring(0, 80) : target);
                }
            } catch (RuntimeException e) {
                log.warn("7/24 teslimi #{} kuyruk sonucu yazılamadı (sonraki tur yeniden dener): {}", d.getId(), e.getMessage());
            }
        }
        return done;
    }

    /** Aynı denemenin posta günlüğü satırının durumu (rol NOC, aynı konu, deneme anından {@link #LOG_MATCH_WINDOW} içinde). */
    private String logStatusOf(String subject, Instant attempt) {
        if (subject == null || attempt == null) return null;
        // Konu TEXT (bazı sürücülerde büyük nesne — eşitlik sorguda taşınabilir değil): 2 dk'lık aralığın satırları okunur,
        // konu Java'da karşılaştırılır (aralık idx_nl_sent_at ile dar; en çok 50 satır, en yeni önce).
        List<String[]> rows = jdbc.query("SELECT subject, email_status FROM notification_logs WHERE recipient_role = ? "
                        + "AND sent_at >= ? AND sent_at <= ? ORDER BY id DESC LIMIT 50",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2)},
                NocNotificationService.LOG_ROLE, ISO.format(attempt), ISO.format(attempt.plus(LOG_MATCH_WINDOW)));
        for (String[] r : rows) if (subject.equals(r[0])) return r[1];
        return null;
    }

    private static Instant parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            String s = iso.trim();
            if (s.endsWith("Z")) return Instant.parse(s);
            if (s.length() > 19) s = s.substring(0, 19);
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }
}
