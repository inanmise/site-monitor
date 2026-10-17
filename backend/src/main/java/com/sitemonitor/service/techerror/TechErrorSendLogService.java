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
 * <p><b>Maliyet.</b> Sabit sorgu sayısı: sayfa + toplam + kanalın rapor dönemleri (≤ 600 hafif satır) = 3. Sorgu kısmi
 * indeksle ({@code idx_nl_te_channel} = {@code (tech_error_channel_id, id) WHERE tech_error_channel_id IS NOT NULL},
 * {@code DeferredIndexBuilder.CATALOG}) daralır; sıra {@code id DESC} indeksin geriye taranmasıdır.
 *
 * <p><b>Ham durum metni istemciye dönmez (inceleme 2026-10-18).</b> {@code notification_logs.email_status} posta sunucusunun
 * iletisini taşır ("FAILED: " + istisna iletisi — sunucu adı, port, kurum içi ayrıntı) ve Türkçedir; okuyan her kullanıcıya
 * yalnız sabit bir SEBEP kodu ({@link #reasonOf}, {@link #REASON_CODES}) döner, arayüz metni dilinde yazar.
 *
 * <p><b>Sığdırılmış alıcı listesi (inceleme 2026-10-18).</b> {@code recipient_email} / {@code cc} VARCHAR(255): uzun liste
 * {@code NotificationLog.fitList} ile " … +N" ekiyle kısalır. Sunucu eki ayırır ({@link #splitFitted}): liste yalnız tam
 * adresler, {@code more} = sığmayan adres sayısı — arayüz "+N adres daha" der, "tam yazıldı" demez.
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

    /**
     * Arayüzün gösterdiği sebep kodları (sıra = {@code sendModel.js SENDLOG_REASON_KEYS}; test iki listeyi karşılaştırır).
     * Gönderilmeyenler {@link TechErrorHistoryService#sendReason} ile aynı kodlar; ek olarak QUEUED (posta sunucusu yeniden
     * deniyor), SMTP_FAILED (posta sunucusu kabul etmedi / ulaşılamadı), INTERNAL_ERROR (uygulama içinde yarıda kaldı),
     * SKIPPED_OTHER (bilinmeyen atlama), UNKNOWN (sonuç kaydı yok). SENT → kod yok (null).
     */
    public static final List<String> REASON_CODES = List.of("QUEUED", "MAIL_OFF", "INACTIVE_RECIPIENTS", "NO_RECIPIENT", "PDF_BUSY",
            "PDF_TOO_LARGE", "APPROVAL_STALE", "SMTP_FAILED", "INTERNAL_ERROR", "SKIPPED_OTHER", "UNKNOWN");
    /** "FAILED: " + istisna sınıfının kısa adı (uygulama içi hata; posta sunucusunun iletisi değil). Girdi ≤ 255 karakter. */
    private static final java.util.regex.Pattern INTERNAL_FAILURE =
            java.util.regex.Pattern.compile("FAILED: [A-Za-z_$][A-Za-z0-9_$]{0,118}(?:Exception|Error)");

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

    /**
     * Günlük satırı. {@code reason} sabit sebep kodu ({@link #reasonOf}; ham durum metni YOK). {@code to} / {@code cc} yalnız
     * tam adresler (sığdırma eki ayrılmış — {@link #splitFitted}), {@code toMore} / {@code ccMore} sığmayan adres sayısı;
     * {@code toCount} / {@code ccCount} = liste + sığmayan.
     */
    public record Row(Long id, String at, String trigger, String kind, String role, String status, String reason,
                      Long reportId, Integer year, Integer month, List<String> to, List<String> cc, int toCount, int ccCount,
                      int toMore, int ccMore) { }

    public record Page(List<Row> rows, long total, int page, int size, String kind, String status) { }

    /**
     * Kanalın günlüğü. {@code kind}: all | report | test | approval | reminder (bilinmeyen = all); {@code status}: all | sent |
     * queued | skipped | failed (bilinmeyen = all); {@code page} 0 tabanlı (negatif = 0; son sayfadan büyükse SON sayfa —
     * {@link #clampPage}: sınırsız sayfa ofset taşmasıyla 500 veriyordu, inceleme 2026-10-18; yanıttaki {@code page} gerçek
     * sayfadır); {@code size} 1..{@value #MAX_PAGE_SIZE}.
     */
    public Page page(Long channelId, String kind, String status, Integer page, Integer size) {
        String k = normalizeKind(kind);
        String s = normalizeStatus(status);
        int sz = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        List<String> triggers = "all".equals(k) ? ALL_TRIGGERS : KINDS.get(k);
        long total = logs.countTechErrorSendLog(channelId, triggers, s);
        int p = clampPage(page, total, sz);
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
            Fitted to = splitFitted(str(o[5]));
            Fitted cc = splitFitted(str(o[6]));
            rows.add(new Row(id, isoUtc(str(o[1])), trigger, kindOf(trigger), roleOf(str(o[3])), statusClass(emailStatus), reasonOf(emailStatus),
                    reportId, period == null ? null : period[0], period == null ? null : period[1],
                    to.items(), cc.items(), to.count(), cc.count(), to.more(), cc.more()));
        }
        return new Page(rows, total, p, sz, k, s);
    }

    /** 0 tabanlı sayfa: null / negatif → 0; son sayfadan büyük → son sayfa (toplam 0 → 0). Taşmasız (long aritmetiği). */
    static int clampPage(Integer page, long total, int size) {
        int p = page == null || page < 0 ? 0 : page;
        long last = total <= 0 || size <= 0 ? 0 : (total - 1) / size;
        return (int) Math.min(p, Math.min(last, Integer.MAX_VALUE));
    }

    /**
     * Durum metninden sabit sebep kodu (inceleme 2026-10-18 — ham metin posta sunucusunun iletisini, sunucu adını / portunu
     * taşır ve istemciye dönmez). SENT → null; QUEUED_RETRY… → QUEUED; gönderilmeyenler {@link TechErrorHistoryService#sendReason};
     * bilinmeyen SKIPPED → SKIPPED_OTHER; "FAILED: gönderim tamamlanamadı" / "FAILED: sonuç yok" / "FAILED: &lt;Sınıf&gt;Exception"
     * → INTERNAL_ERROR; diğer FAILED (posta sunucusunun iletisi) → SMTP_FAILED; boş → UNKNOWN.
     */
    public static String reasonOf(String emailStatus) {
        if (emailStatus == null || emailStatus.isBlank()) return "UNKNOWN";
        String s = emailStatus.trim();
        if ("SENT".equals(s)) return null;
        if (s.startsWith("QUEUED_RETRY")) return "QUEUED";
        String known = TechErrorHistoryService.sendReason(s);
        if (known != null) return known;
        if (s.startsWith("SKIPPED")) return "SKIPPED_OTHER";
        if (s.startsWith("FAILED: gönderim tamamlanamadı") || s.startsWith("FAILED: sonuç yok")
                || (s.length() <= 140 && INTERNAL_FAILURE.matcher(s).matches())) return "INTERNAL_ERROR";
        return "SMTP_FAILED";
    }

    /** Sığdırılmış liste: tam adresler + sığmayan sayısı; {@code count} = ikisinin toplamı. */
    public record Fitted(List<String> items, int more, int count) {
        static final Fitted EMPTY = new Fitted(List.of(), 0, 0);
    }

    /**
     * {@code NotificationLog.fitList}'in yazdığı biçimi ayırır: "a@x, b@y … +3" → [a@x, b@y] + 3. İlk adres bile sığmadıysa
     * değer düz {@code fit} ile "…" ile biter: yarım adres GÖSTERİLMEZ, en az 1 sığmayan sayılır. Ek yoksa liste aynen.
     */
    static Fitted splitFitted(String s) {
        if (s == null || s.isBlank()) return Fitted.EMPTY;
        String body = s;
        int more = 0;
        int at = s.lastIndexOf(" … +");
        if (at >= 0 && NotificationLog.fitted(s) && !s.endsWith("…")) {
            String digits = s.substring(at + 4);
            more = digits.length() <= 6 ? Integer.parseInt(digits) : Integer.MAX_VALUE / 2;
            body = s.substring(0, at);
        } else if (s.endsWith("…")) {
            // Düz kesilmiş (ilk adres 255'e sığmadı): son öğe yarım — gösterilmez
            int comma = s.lastIndexOf(',');
            body = comma < 0 ? "" : s.substring(0, comma);
            more = 1;
        }
        List<String> items = splitList(body);
        return new Fitted(items, more, items.size() + more);
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

    /** Virgüllü liste → öğeler (ek ayırmaz — {@link #splitFitted} ayırır). */
    static List<String> splitList(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        for (String part : s.split(",")) {
            String v = part.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    /** Listedeki adres sayısı: tam adresler + sığmayanlar ("a@x, b@y … +3" = 5). */
    static int listCount(String s) {
        return splitFitted(s).count();
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
