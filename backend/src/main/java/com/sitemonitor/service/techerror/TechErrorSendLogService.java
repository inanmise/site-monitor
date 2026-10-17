package com.sitemonitor.service.techerror;

import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Kanal başına gönderim günlüğü (2026-10-18, R18 / E22 — kullanıcı onayı). Kanalın teknik hata raporu postalarının
 * {@code notification_logs} satırları: rapor gönderimi ({@value TechErrorReportSender#TRIGGER}), kendine test
 * ({@value TechErrorReportSender#TEST_TRIGGER}), onay postaları ({@value TechErrorApprovalService#TRIGGER}) ve hatırlatmalar
 * ({@link #APPROVAL_REMINDER_TRIGGER} / {@link #REMINDER_TRIGGER} — başka iş paketinin tetikleyicileri; eşleşen yoksa boş).
 *
 * <p><b>Bağ.</b> Satır kanala {@code notification_logs.tech_error_channel_id} ile bağlanır (rapor
 * {@code tech_error_report_id}); her teknik hata yolu satırı yazarken {@link #link} ile doldurur. Bu bağdan önce yazılmış
 * satırlar bağsızdır ve günlükte GÖRÜNMEZ (konu / takım adı güvenilir bir bağ değildi: aynı adlı iki kanal, değişen konu).
 *
 * <p><b>Maliyet.</b> Sabit sorgu sayısı: sayfa + toplam + kanalın rapor dönemleri (≤ 600 hafif satır) = 3.
 */
@Service
public class TechErrorSendLogService {

    /** Onay hatırlatması (başka iş paketi; bu dalda yazan yok — süzgeç yine de kapsar). */
    public static final String APPROVAL_REMINDER_TRIGGER = "TECH_ERROR_APPROVAL_REMINDER";
    /** Rapor hatırlatması (başka iş paketi; bu dalda yazan yok — süzgeç yine de kapsar). */
    public static final String REMINDER_TRIGGER = "TECH_ERROR_REMINDER";
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 25;

    /** Tür süzgeci → tetikleyiciler (sıra = arayüzdeki sıra; "all" hepsi). */
    static final Map<String, List<String>> KINDS;
    static {
        Map<String, List<String>> k = new LinkedHashMap<>();
        k.put("report", List.of(TechErrorReportSender.TRIGGER));
        k.put("test", List.of(TechErrorReportSender.TEST_TRIGGER));
        k.put("approval", List.of(TechErrorApprovalService.TRIGGER));
        k.put("reminder", List.of(APPROVAL_REMINDER_TRIGGER, REMINDER_TRIGGER));
        KINDS = java.util.Collections.unmodifiableMap(k);
    }
    static final List<String> ALL_TRIGGERS = KINDS.values().stream().flatMap(List::stream).toList();
    static final List<String> STATUS_CLASSES = List.of("sent", "queued", "skipped", "failed");

    private final NotificationLogRepository logs;
    private final TechErrorReportRepository reports;

    public TechErrorSendLogService(NotificationLogRepository logs, TechErrorReportRepository reports) {
        this.logs = logs;
        this.reports = reports;
    }

    /** Teknik hata satırını kanala / rapora bağlar (her {@code saveLog} yolu çağırır; null güvenli). */
    public static void link(NotificationLog n, Long channelId, Long reportId) {
        if (n == null) return;
        n.setTechErrorChannelId(channelId);
        n.setTechErrorReportId(reportId);
    }

    /** Günlük satırı. {@code to} / {@code cc} ham (sığdırılmış olabilir: "… +N"); sayılar {@link #listCount}. */
    public record Row(Long id, String at, String trigger, String kind, String role, String status, String statusText,
                      Long reportId, Integer year, Integer month, List<String> to, List<String> cc, int toCount, int ccCount) { }

    public record Page(List<Row> rows, long total, int page, int size, String kind, String status) { }

    /**
     * Kanalın günlüğü. {@code kind}: all | report | test | approval | reminder (bilinmeyen = all); {@code status}: all | sent |
     * queued | skipped | failed (bilinmeyen = all); {@code page} 0 tabanlı (negatif = 0); {@code size} 1..{@value #MAX_PAGE_SIZE}.
     */
    public Page page(Long channelId, String kind, String status, Integer page, Integer size) {
        String k = normalizeKind(kind);
        String s = normalizeStatus(status);
        int p = page == null || page < 0 ? 0 : page;
        int sz = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        List<String> triggers = "all".equals(k) ? ALL_TRIGGERS : KINDS.get(k);
        long total = logs.countTechErrorSendLog(channelId, triggers, s);
        List<Object[]> raw = total == 0 ? List.of() : logs.findTechErrorSendLog(channelId, triggers, s, PageRequest.of(p, sz));
        Map<Long, int[]> periods = new HashMap<>();
        if (!raw.isEmpty()) {
            for (TechErrorReportRepository.Row r : reports.findByChannelIdOrderByReportYearDescReportMonthDesc(channelId)) {
                if (r.getId() != null && r.getReportYear() != null && r.getReportMonth() != null) {
                    periods.put(r.getId(), new int[] { r.getReportYear(), r.getReportMonth() });
                }
            }
        }
        List<Row> rows = new ArrayList<>(raw.size());
        for (Object[] o : raw) {
            Long id = toLong(o[0]);
            String trigger = str(o[2]);
            String emailStatus = str(o[4]);
            Long reportId = toLong(o[7]);
            int[] period = reportId == null ? null : periods.get(reportId);
            String to = str(o[5]);
            String cc = str(o[6]);
            rows.add(new Row(id, isoUtc(str(o[1])), trigger, kindOf(trigger), roleOf(str(o[3])), statusClass(emailStatus), emailStatus,
                    reportId, period == null ? null : period[0], period == null ? null : period[1],
                    splitList(to), splitList(cc), listCount(to), listCount(cc)));
        }
        return new Page(rows, total, p, sz, k, s);
    }

    static String normalizeKind(String kind) {
        String k = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        return KINDS.containsKey(k) ? k : "all";
    }

    static String normalizeStatus(String status) {
        String s = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        return STATUS_CLASSES.contains(s) ? s : "all";
    }

    /** Tetikleyici → tür (bilinmeyen tetikleyici sorguya girmez; yine de güvenli). */
    static String kindOf(String trigger) {
        for (Map.Entry<String, List<String>> e : KINDS.entrySet()) if (e.getValue().contains(trigger)) return e.getKey();
        return "report";
    }

    /**
     * Rol: TEAM | EXECUTIVE | SELF | APPROVER | APPROVER_INFO | REQUESTER; onay postasının eski rol adı {@code PO} = APPROVER.
     * Bilinmeyen / boş → OTHER (arayüz ham değer göstermez).
     */
    static String roleOf(String role) {
        if (role == null) return "OTHER";
        String r = role.trim().toUpperCase(Locale.ROOT);
        return switch (r) {
            case "TEAM", "EXECUTIVE", "SELF", "APPROVER", "APPROVER_INFO", "REQUESTER" -> r;
            case "PO" -> "APPROVER";
            default -> "OTHER";
        };
    }

    /** Durum sınıfı — {@link TechErrorReportSender#classify} ile aynı kural (null / boş = failed). */
    public static String statusClass(String emailStatus) {
        if (emailStatus == null || emailStatus.isBlank()) return "failed";
        return TechErrorReportSender.classify(emailStatus).toLowerCase(Locale.ROOT);
    }

    /** Virgüllü liste → öğeler (sığdırma eki "… +N" son öğede kalır — adres yarım yazılmaz, gösterilir). */
    static List<String> splitList(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        for (String part : s.split(",")) {
            String v = part.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    /** Listedeki adres sayısı: öğeler + sığdırma ekindeki N ("a@x, b@y … +3" = 5). */
    static int listCount(String s) {
        List<String> parts = splitList(s);
        if (parts.isEmpty()) return 0;
        int n = parts.size();
        String last = parts.get(parts.size() - 1);
        int at = last.lastIndexOf(" … +");
        if (at >= 0) {
            String digits = last.substring(at + 4);
            boolean ok = !digits.isEmpty() && digits.length() <= 6;
            for (int i = 0; ok && i < digits.length(); i++) ok = Character.isDigit(digits.charAt(i));
            if (ok) n += Integer.parseInt(digits);
        }
        return n;
    }

    /** Günlüğün bölgesiz UTC zamanı ("yyyy-MM-dd'T'HH:mm:ss") → ISO an ("…Z"); okunamazsa ham değer. */
    static String isoUtc(String sentAt) {
        if (sentAt == null || sentAt.isBlank()) return null;
        try {
            return LocalDateTime.parse(sentAt.trim()).toInstant(ZoneOffset.UTC).toString();
        } catch (DateTimeParseException e) {
            return sentAt;
        }
    }

    private static Long toLong(Object o) {
        if (o instanceof Number n) return Long.valueOf(n.longValue());
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
