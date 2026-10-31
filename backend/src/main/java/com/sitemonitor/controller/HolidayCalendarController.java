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
            // k6r D2 (2026-10-31): tatile bakan zamanlamalı sentetik izlemeler de aynı kuralla yeniden planlanır
            try {
                if (scheduler != null) scheduler.scriptedCalendarChanged();
            } catch (RuntimeException e) {
                log.warn("Tatil takvimi değişikliği senaryo izlemelerinin planına işlenemedi — bir sonraki vade hesabında yenilenir: {}",
                        e.getClass().getSimpleName());
            }
        }
        return ok(view(session));
    }

    /**
     * Bir yılın SABİT tarihli resmî tatillerini ekler (r177, H-7): 1 Ocak, 23 Nisan, 1 Mayıs, 19 Mayıs, 15 Temmuz, 30 Ağustos,
     * 29 Ekim. Var olan günlere dokunmaz; dini bayramlar (Ramazan / Kurban) her yıl kaydığından ELLE girilir — yanıt
     * {@code note} ile söyler. Yalnız global yönetici; denetim {@code HOLIDAY_CALENDAR_SAVE} ({@code kind: add_fixed}).
     */
    @PostMapping("/add-fixed")
    public ResponseEntity<Map<String, Object>> addFixed(@RequestParam("year") String year, HttpSession session) {
        if (!SessionScope.isGlobalAdmin(session)) {
            throw new SecurityException(Msg.t(
                    "Tatil takvimini yalnız global yönetici değiştirebilir.",
                    "Only a global administrator can change the holiday calendar."));
        }
        int y;
        try {
            y = Integer.parseInt(year == null ? "" : year.trim());
        } catch (NumberFormatException e) {
            y = -1;
        }
        if (y < 2000 || y > 2100) {
            Map<String, String> f = new LinkedHashMap<>();
            String msg = Msg.t("Yıl 2000–2100 arasında bir sayı olmalı.", "The year must be a number between 2000 and 2100.");
            f.put("year", msg);
            throw DbQueryApiException.validation(msg, f);
        }
        Object dn = session.getAttribute("displayName");
        Object un = session.getAttribute("username");
        String actorName = dn != null && !dn.toString().isBlank() ? dn.toString() : (un == null ? "system" : un.toString());
        int added = holidays.addFixed(y, actorName);
        if (added > 0) {
            auditService.recordAction("HOLIDAY_CALENDAR_SAVE", session, "HOLIDAY_CALENDAR", "holidays",
                    AuditDetail.of("kind", "add_fixed", "year", y, "added", added), null);
            try {
                if (scheduler != null) scheduler.dbQueryCalendarChanged();
            } catch (RuntimeException e) {
                log.warn("Tatil takvimi değişikliği sorgu izlemelerinin planına işlenemedi — bir sonraki vade hesabında yenilenir: {}",
                        e.getClass().getSimpleName());
            }
            // k6r D2 (2026-10-31): tatile bakan zamanlamalı sentetik izlemeler de aynı kuralla yeniden planlanır
            try {
                if (scheduler != null) scheduler.scriptedCalendarChanged();
            } catch (RuntimeException e) {
                log.warn("Tatil takvimi değişikliği senaryo izlemelerinin planına işlenemedi — bir sonraki vade hesabında yenilenir: {}",
                        e.getClass().getSimpleName());
            }
        }
        Map<String, Object> out = view(session);
        out.put("added", added);
        out.put("year", y);
        out.put("note", Msg.t(
                "Yalnız sabit tarihli resmî tatiller eklendi; Ramazan ve Kurban Bayramı günleri her yıl değiştiği için onları "
                        + "Diyanet takvimine göre elle girin.",
                "Only the fixed-date public holidays were added; the Ramadan and Sacrifice Feast days move every year, so enter "
                        + "them by hand from the official calendar."));
        return ok(out);
    }

    private Map<String, Object> view(HttpSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> days = holidays.list();
        out.put("days", days);
        out.put("can_edit", SessionScope.isGlobalAdmin(session));
        // r177 (H-7): takvim nereye kadar dolu — ≤ 60 gün kaldıysa ön yüz uyarır
        out.putAll(HolidayCalendarService.coverage(days, java.time.LocalDate.now(java.time.ZoneId.of("Europe/Istanbul"))));
        return out;
    }

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data, "timestamp", ISO.format(Instant.now())));
    }
}
