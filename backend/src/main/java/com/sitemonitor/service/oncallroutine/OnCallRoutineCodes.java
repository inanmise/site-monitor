package com.sitemonitor.service.oncallroutine;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

/**
 * Nöbet rutinleri (2026-11-26) — kalıcı kodlar ve sabitler, TEK kaynak. Ön yüz ({@code routineServerContract.test.js}) bu
 * dosyadaki listeleri okur: hata kodları, olay türleri, gün / görev durumları, bildirim neden kodları ve sabitler. Adlar
 * KALICIDIR (veritabanında ve ön yüzde yazılı); yeni değer listenin SONUNA eklenir.
 */
public final class OnCallRoutineCodes {

    private OnCallRoutineCodes() {}

    public static final ZoneId TZ = ZoneId.of("Europe/Istanbul");

    // ── Bildirim tetikleyicileri / push türü / kilit ─────────────────────────────────────────────
    public static final String TRIGGER_DONE = "ONCALL_ROUTINE_DONE";
    public static final String TRIGGER_REMINDER = "ONCALL_ROUTINE_REMINDER";
    public static final String TRIGGER_HANDOVER = "ONCALL_ROUTINE_HANDOVER";
    public static final String PUSH_TYPE = "ONCALL_ROUTINE";
    public static final String LOCK_NAME = "oncall-routine";
    /** Tamamlanma bildirimini hemen gönderen yan iş adı (kilitsiz; her bildirim sahiplenmeyle korunur). */
    public static final String NOTIFY_JOB_NAME = "oncall-routine-notify";

    // ── Sınırlar ─────────────────────────────────────────────────────────────────────────────────
    public static final int EDIT_WINDOW_DAYS = 7;
    public static final int REMINDER_CATCHUP_MIN = 30;
    public static final int DUE_CATCHUP_MIN = 120;
    public static final int NOTICE_WINDOW_HOURS = 24;
    public static final Duration STALE_SENDING = Duration.ofMinutes(15);
    public static final int MAX_ATTEMPTS = 3;
    public static final Duration RETRY_AFTER = Duration.ofMinutes(10);
    public static final int MAX_TEMPLATE_VERSIONS = 200;
    public static final int MAX_IMAGES_PER_PROBLEM = 4;
    public static final long MAX_TEAM_IMAGE_BYTES = 200L * 1024 * 1024;
    public static final int EXPORT_MAX_ROWS = 20000;
    public static final int MAX_RANGE_DAYS = 366;
    /** Devralma anahtarının ileri sınırı (gün). */
    public static final int ASSIGN_AHEAD_DAYS = 56;
    /** Devir paneli yeni dönemin ilk kaç iş gününde gösterilir (onaylanmadıysa). */
    public static final int HANDOVER_SHOW_WORKDAYS = 3;

    // ── Hata kodları (§4.4) ──────────────────────────────────────────────────────────────────────
    public static final List<String> ERROR_CODES = List.of(
            "ROUTINE_MODULE_HIDDEN", "TEAM_NOT_FOUND", "ROUTINE_NOT_CONFIGURED", "ROUTINE_PAUSED", "TASK_NOT_FOUND",
            "PERIOD_MISMATCH", "ITEM_CHANGED", "NOTE_CHANGED", "DAY_COMPLETED", "DAY_NOT_READY", "DAY_LOCKED",
            "DAY_IN_FUTURE", "NOT_WORKDAY", "CONCURRENT_MODIFICATION", "VERSION_NOT_FOUND", "PROBLEM_NOT_FOUND",
            "PROBLEM_IMAGE_LIMIT", "IMAGE_NOT_FOUND", "INCIDENT_NOT_FOUND", "HANDOVER_NOT_DUE", "ASSIGN_NOT_ALLOWED",
            "ASSIGNEE_NOT_MEMBER", "EXPORT_TOO_LARGE", "EXPORT_BUSY", "RATE_LIMITED", "VALIDATION_FAILED");

    // ── Olay türleri (§2.9) ──────────────────────────────────────────────────────────────────────
    public static final String EV_ITEM_DONE = "ITEM_DONE";
    public static final String EV_ITEM_PENDING = "ITEM_PENDING";
    public static final String EV_ITEM_SKIPPED = "ITEM_SKIPPED";
    public static final String EV_ITEM_NOTE = "ITEM_NOTE";
    public static final String EV_SECTION_DONE = "SECTION_DONE";
    public static final String EV_PROBLEM_OPEN = "PROBLEM_OPEN";
    public static final String EV_PROBLEM_UPDATE = "PROBLEM_UPDATE";
    public static final String EV_PROBLEM_RESOLVE = "PROBLEM_RESOLVE";
    public static final String EV_PROBLEM_REOPEN = "PROBLEM_REOPEN";
    public static final String EV_PROBLEM_DELETE = "PROBLEM_DELETE";
    public static final String EV_PROBLEM_IMAGE = "PROBLEM_IMAGE";
    public static final String EV_INCIDENT_LINK = "INCIDENT_LINK";
    public static final String EV_DAY_COMPLETE = "DAY_COMPLETE";
    public static final String EV_DAY_REOPEN = "DAY_REOPEN";
    public static final String EV_DAY_NOTE = "DAY_NOTE";
    public static final String EV_DAY_CLOSE = "DAY_CLOSE";
    public static final String EV_HANDOVER_NOTE = "HANDOVER_NOTE";
    public static final String EV_HANDOVER_ACK = "HANDOVER_ACK";
    public static final String EV_TAKEOVER = "TAKEOVER";
    public static final String EV_TAKEOVER_CLEAR = "TAKEOVER_CLEAR";

    public static final List<String> EVENT_KINDS = List.of(
            "ITEM_DONE", "ITEM_PENDING", "ITEM_SKIPPED", "ITEM_NOTE", "SECTION_DONE", "PROBLEM_OPEN", "PROBLEM_UPDATE",
            "PROBLEM_RESOLVE", "PROBLEM_REOPEN", "PROBLEM_DELETE", "PROBLEM_IMAGE", "INCIDENT_LINK", "DAY_COMPLETE",
            "DAY_REOPEN", "DAY_NOTE", "DAY_CLOSE", "HANDOVER_NOTE", "HANDOVER_ACK", "TAKEOVER", "TAKEOVER_CLEAR");

    // ── Gün durumları (§1.6) ─────────────────────────────────────────────────────────────────────
    public static final String DAY_OPEN = "OPEN";
    public static final String DAY_COMPLETED = "COMPLETED";
    public static final String DAY_COMPLETED_PARTIAL = "COMPLETED_PARTIAL";
    public static final String DAY_INCOMPLETE = "INCOMPLETE";
    public static final String DAY_MISSED = "MISSED";
    public static final String DAY_NONE = "NONE";
    public static final String DAY_NOT_WORKDAY = "NOT_WORKDAY";
    public static final String DAY_FUTURE = "FUTURE";
    public static final String DAY_PAUSED = "PAUSED";

    /** Satırda saklanan gün durumları. */
    public static final List<String> DAY_STATUSES = List.of("OPEN", "COMPLETED", "COMPLETED_PARTIAL", "INCOMPLETE", "MISSED");
    /** Yalnız telde görülen (satırı olmayan) gün durumları. */
    public static final List<String> WIRE_DAY_STATUSES = List.of("NONE", "NOT_WORKDAY", "FUTURE", "PAUSED");

    public static boolean completed(String dayStatus) {
        return DAY_COMPLETED.equals(dayStatus) || DAY_COMPLETED_PARTIAL.equals(dayStatus);
    }

    // ── Görev durumları (§1.7) ───────────────────────────────────────────────────────────────────
    public static final String PENDING = "PENDING";
    public static final String DONE = "DONE";
    public static final String SKIPPED = "SKIPPED";
    public static final String MISSED = "MISSED";
    public static final List<String> ITEM_STATES = List.of("PENDING", "DONE", "SKIPPED", "MISSED");

    public static boolean resolved(String state) {
        return DONE.equals(state) || SKIPPED.equals(state);
    }

    // ── Dönem / sıklık ───────────────────────────────────────────────────────────────────────────
    public static final String KIND_DAY = "DAY";
    public static final String KIND_WEEK = "WEEK";
    public static final String KIND_MONTH = "MONTH";
    public static final List<String> PERIOD_KINDS = List.of("DAY", "WEEK", "MONTH");
    public static final List<String> FREQUENCIES = List.of("DAILY", "WEEKLY", "MONTHLY");
    public static final List<String> MONTHLY_RULES = List.of("FIRST_WORKDAY", "LAST_WORKDAY", "DAY", "NTH_WEEKDAY");

    /** Sıklık → dönem türü. */
    public static String kindOf(String frequency) {
        if ("WEEKLY".equals(frequency)) return KIND_WEEK;
        if ("MONTHLY".equals(frequency)) return KIND_MONTH;
        return KIND_DAY;
    }

    // ── Görev satırı kaynağı ─────────────────────────────────────────────────────────────────────
    public static final String SRC_TOUCH = "TOUCH";
    public static final String SRC_COMPLETE = "COMPLETE";
    public static final String SRC_CLOSE = "CLOSE";

    // ── Sorun ────────────────────────────────────────────────────────────────────────────────────
    public static final List<String> SEVERITIES = List.of("LOW", "MEDIUM", "HIGH");
    public static final String PROBLEM_OPEN = "OPEN";
    public static final String PROBLEM_RESOLVED = "RESOLVED";
    public static final List<String> PROBLEM_STATUSES = List.of("OPEN", "RESOLVED");

    // ── Sorumlu kaynağı (§1.4) ───────────────────────────────────────────────────────────────────
    public static final String RESP_SCHEDULE = "SCHEDULE";
    public static final String RESP_OVERRIDE = "OVERRIDE";
    public static final String RESP_TAKEOVER_DAY = "TAKEOVER_DAY";
    public static final String RESP_TAKEOVER_WEEK = "TAKEOVER_WEEK";
    public static final String RESP_NONE = "NONE";
    public static final List<String> RESPONSIBLE_SOURCES = List.of("SCHEDULE", "OVERRIDE", "TAKEOVER_DAY", "TAKEOVER_WEEK", "NONE");

    // ── Kilit nedenleri (gün sayfası) ────────────────────────────────────────────────────────────
    public static final List<String> LOCKED_REASONS = List.of(
            "COMPLETED", "TOO_OLD", "FUTURE", "NO_PERMISSION", "PAUSED", "NOT_WORKDAY", "NOT_CONFIGURED");

    // ── Şablon sürüm kaynağı ─────────────────────────────────────────────────────────────────────
    public static final List<String> TEMPLATE_SOURCES = List.of("EDITOR", "IMPORT", "SAMPLE", "RESTORE");

    // ── Bildirimler (§2.10) ──────────────────────────────────────────────────────────────────────
    public static final String NOTICE_COMPLETE = "COMPLETE";
    public static final String NOTICE_REMINDER = "REMINDER";
    public static final String NOTICE_SECTION = "SECTION";
    public static final String NOTICE_DUE = "DUE";
    public static final String NOTICE_HANDOVER = "HANDOVER";
    public static final List<String> NOTICE_KINDS = List.of("COMPLETE", "REMINDER", "SECTION", "DUE", "HANDOVER");

    public static final String N_SENDING = "SENDING";
    public static final String N_SENT = "SENT";
    public static final String N_PARTIAL = "PARTIAL";
    public static final String N_SKIPPED = "SKIPPED";
    public static final String N_FAILED = "FAILED";
    public static final String N_UNKNOWN = "UNKNOWN";
    public static final List<String> NOTICE_STATUSES = List.of("SENDING", "SENT", "PARTIAL", "SKIPPED", "FAILED", "UNKNOWN");

    public static final List<String> NOTICE_REASONS = List.of(
            "DISABLED", "MAINTENANCE", "NO_RESPONSIBLE", "RESPONSIBLE_INACTIVE", "NOTHING_PENDING", "NO_TEAM",
            "TEAM_INACTIVE", "CHANNELS_OFF", "NO_RECIPIENT", "MISSED", "INTERRUPTED", "ERROR", "MODULE_HIDDEN");

    // ── Denetim türleri (§4.5) ───────────────────────────────────────────────────────────────────
    public static final String AUDIT_TEMPLATE_SAVE = "ONCALL_ROUTINE_TEMPLATE_SAVE";
    public static final String AUDIT_SETTINGS_SAVE = "ONCALL_ROUTINE_SETTINGS_SAVE";
    public static final String AUDIT_DAY_COMPLETE = "ONCALL_ROUTINE_DAY_COMPLETE";
    public static final String AUDIT_DAY_REOPEN = "ONCALL_ROUTINE_DAY_REOPEN";
    public static final String AUDIT_TAKEOVER = "ONCALL_ROUTINE_TAKEOVER";
    public static final String AUDIT_TAKEOVER_CLEAR = "ONCALL_ROUTINE_TAKEOVER_CLEAR";
    public static final String AUDIT_HANDOVER_ACK = "ONCALL_ROUTINE_HANDOVER_ACK";
    public static final String AUDIT_PROBLEM_DELETE = "ONCALL_ROUTINE_PROBLEM_DELETE";
    public static final String AUDIT_INCIDENT_LINK = "ONCALL_ROUTINE_INCIDENT_LINK";
    public static final String AUDIT_EXPORT = "ONCALL_ROUTINE_EXPORT";
    public static final String AUDIT_ADMIN_SETTINGS_SAVE = "ONCALL_ROUTINE_ADMIN_SETTINGS_SAVE";
}
