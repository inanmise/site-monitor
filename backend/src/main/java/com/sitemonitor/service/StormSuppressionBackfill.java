package com.sitemonitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Fırtına devrinin GERİYE DÖNÜK izi (2026-09-30, prod olayı — SY takımının sentetik test alarmları).
 *
 * <p>Alarm bir fırtınaya SESSİZCE bağlandığında ({@code StormService.evaluate} → SUPPRESSED) eskiden hiçbir kanal
 * çalışmıyor ve hiçbir kayıt kalmıyordu: alarm penceresi "0 bildirim", push "önce bildirim gitmemişti" diyor, takım
 * neden haber almadığını göremiyordu. Yeni kod bu dalda bir bildirim günlüğü satırı ({@code STORM} tetikli,
 * {@code SKIPPED: fırtına #N …}) ve bir push karar satırı ({@code SKIPPED_STORM}) bırakır. Bu yama aynı izi, geçmişte
 * fırtınaya bağlanmış ve hiç bildirim satırı olmayan ESKİ olaylara TEK SEFER yazar — "geriye dönük kayıt" notuyla,
 * olayın açılış anı damgasıyla. Böylece Alarm Geçmişi'nde eski olaylar da "neden gitmedi"yi söyler.
 *
 * <p>Sınırlar: yalnız {@code storm_id} hâlâ dolu olan olaylar bulunabilir (fırtınadan "bildirildi" sayılarak çözülen
 * üyelerin bağı silinmiştir; onlar için kanıt yok, uydurulmaz). Nişan tablosu
 * ({@code schema_patch_markers}, {@link StandaloneMonitorDeletionBackfill#MARKERS_DDL}) yamayı bir kez koşturur.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StormSuppressionBackfill {

    /** Nişan anahtarı — DEĞİŞTİRİLMEZ (değişirse yama yeniden koşar; satırlar tekrar yazılmaz ama boşa sorgu). */
    static final String KEY = "2026-09-30-storm-suppressed-notification-rows";

    static final String STATUS_SUFFIX = " — bireysel bildirim yerine toplu fırtına bildirimi (geriye dönük kayıt)";

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;

    /**
     * Yamayı en çok BİR KEZ uygular. Dönüş: bu çağrıda iz yazılan olay sayısı; nişan zaten varsa -1.
     * Tablolar (alert_events, notification_logs, user_push_deliveries, teams) önceden var olmalı.
     */
    @Transactional
    public int applyOnce() {
        jdbc.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
        Integer done = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class, KEY);
        if (done != null && done > 0) return -1;
        String now = ISO.format(Instant.now());
        int n = fill(now);
        jdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)", KEY, now, n);
        if (n > 0) log.info("Fırtına devri geriye dönük iz: {} olaya bildirim günlüğü + push karar satırı yazıldı", n);
        return n;
    }

    /** Nişansız çekirdek (test edilebilir): storm_id'li, hiç bildirim satırı olmayan her olaya iki satır. */
    int fill(String now) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT e.id, e.alert_type, e.domain, e.team_id, e.alert_level, e.storm_id, e.created_at, e.message, "
              + "t.name AS team_name, t.email AS team_email "
              + "FROM alert_events e LEFT JOIN teams t ON t.id = e.team_id "
              + "WHERE e.storm_id IS NOT NULL "
              + "AND NOT EXISTS (SELECT 1 FROM notification_logs n WHERE n.alert_event_id = e.id) "
              + "ORDER BY e.id");
        int n = 0;
        for (Map<String, Object> r : rows) {
            Object id = r.get("id");
            if (id == null) continue;
            Object stormId = r.get("storm_id");
            String createdAt = r.get("created_at") != null ? String.valueOf(r.get("created_at")) : now;
            String status = EscalationService.STATUS_STORM_PREFIX + stormId + STATUS_SUFFIX;
            String teamName = r.get("team_name") != null ? String.valueOf(r.get("team_name")) : "-";
            String teamEmail = r.get("team_email") != null ? String.valueOf(r.get("team_email")) : "";
            String message = r.get("message") != null ? String.valueOf(r.get("message")) : "";
            jdbc.update("INSERT INTO notification_logs (alert_event_id, sent_at, recipient_name, recipient_email, recipient_role, "
                      + "subject, message, email_status, webhook_status, trigger) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, createdAt, teamName, teamEmail, "COMBINED", "", message, status, "SKIPPED",
                    EscalationService.TRIGGER_STORM_SUPPRESSED);
            // Push karar satırı: OPEN fazında sistem satırı yoksa (bireysel yol hiç koşmadığı için yoktur).
            Integer pushRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM user_push_deliveries WHERE alert_event_id = ? AND dedupe_key = 'OPEN'", Integer.class, id);
            if (pushRows == null || pushRows == 0) {
                String alertType = r.get("alert_type") != null ? String.valueOf(r.get("alert_type")) : null;
                jdbc.update("INSERT INTO user_push_deliveries (alert_event_id, push_trigger, dedupe_key, monitor_type, monitor_name, "
                          + "team_id, alert_level, username, display_name, message, status, attempts, created_at) "
                          + "VALUES (?, 'OPEN', 'OPEN', ?, ?, ?, ?, '-', '(katman kararı)', NULL, ?, 0, ?)",
                        id, MonitorTypeCatalog.typeOfAlert(alertType), r.get("domain"), r.get("team_id"), r.get("alert_level"),
                        EscalationService.PUSH_SKIPPED_STORM, createdAt);
            }
            n++;
        }
        return n;
    }
}
