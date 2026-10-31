package com.sitemonitor.controller;

import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.dbquery.HolidayCalendarService;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kurum geneli tatil / özel gün takvimi (2026-10-31, dbquery-api.md §6) — veritabanı sorgu izlemesinin "tatillerde çalışma"
 * seçeneği bu günleri (Europe/Istanbul) atlar. Okuma: oturum açık herkes (form ve zamanlama önizlemesi gösterir); yazma:
 * YALNIZ global yönetici (kurum geneli karar — kapsamlı müdür değiştiremez). Liste tamamen değiştirilir; denetim
 * {@code HOLIDAY_CALENDAR_SAVE} (sayılar). Değişiklik öteki pod'lara önbellek veri yoluyla yayılır.
 */
@RestController
@RequestMapping("/api/admin/holidays")
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class HolidayCalendarController {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final HolidayCalendarService holidays;
    private final AuditService auditService;

    /** Tatil kaydından sonra sorgu izlemelerinin planı (@Lazy: SchedulerService ağır bean; dilim testlerinde yok). */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.SchedulerService scheduler;

    @ExceptionHandler(DbQueryApiException.class)
    public ResponseEntity<Map<String, Object>> onApiError(DbQueryApiException e) {
        return e.toResponse();
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(HttpSession session) {
        if (session == null || session.getAttribute("username") == null) {
            throw new SecurityException(Msg.t("Oturum açın.", "Sign in first."));
        }
        return ok(view(session));
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> save(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!SessionScope.isGlobalAdmin(session)) {
            throw new SecurityException(Msg.t(
                    "Tatil takvimini yalnız global yönetici değiştirebilir.",
                    "Only a global administrator can change the holiday calendar."));
        }
        List<HolidayCalendarService.Day> days = HolidayCalendarService.parse(body == null ? null : body.get("days"));
        Object dn = session.getAttribute("displayName");
        Object un = session.getAttribute("username");
        String actorName = dn != null && !dn.toString().isBlank() ? dn.toString() : (un == null ? "system" : un.toString());
        HolidayCalendarService.Change c = holidays.replaceAll(days, actorName);
        if (c.changed()) {
            auditService.recordAction("HOLIDAY_CALENDAR_SAVE", session, "HOLIDAY_CALENDAR", "holidays",
                    AuditDetail.of("added", c.added(), "removed", c.removed(), "renamed", c.renamed(), "total", c.total()), null);
            // Tatile bakan CRON izlemelerinin planı yeni takvimle yeniden kurulur (silinen gün yeniden planlanır).
            try {
                if (scheduler != null) scheduler.dbQueryCalendarChanged();
            } catch (RuntimeException e) {
                log.warn("Tatil takvimi değişikliği sorgu izlemelerinin planına işlenemedi — bir sonraki vade hesabında yenilenir: {}",
                        e.getClass().getSimpleName());
            }
        }
        return ok(view(session));
    }

    private Map<String, Object> view(HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", holidays.list());
        out.put("can_edit", SessionScope.isGlobalAdmin(session));
        return out;
    }

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data, "timestamp", ISO.format(Instant.now())));
    }
}
