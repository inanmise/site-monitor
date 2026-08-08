package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Kontrol Geçmişi v2 — tüm izleme türlerinin history endpoint'lerinin ortak motoru.
 * Tek tip sözleşme: {@code items, page, size, total, counts{total,fail}, range{from,to}, buckets, alerts}.
 * <ul>
 *   <li><b>500 literal'i yok:</b> server-side pagination (0-tabanlı page/size, size≤{@value #MAX_PAGE_SIZE});
 *       eski {@code days} paramı sugar olarak {@code from=now-days}'e çevrilir.</li>
 *   <li><b>checked_at String'dir</b> (sabit-genişlik ISO, sözlüksel=kronolojik) — from/to sorguya girmeden
 *       19 karaktere normalize edilir; yarım "2026-08-07T10" değerleri yanlış aralık üretirdi.</li>
 *   <li><b>Retention clamp:</b> from, saklama penceresinin gerisine inemez; efektif aralık {@code range}
 *       ile döner — UI kırpmayı kullanıcıya gösterir (sessiz kırpma yok).</li>
 *   <li><b>Alarm eşlemesi çıkarımsal:</b> aralıkla kesişen alert_events aynı yanıtta döner; satır-rozet
 *       eşlemesi client'ta (check kaydında alertEventId yok — bilinçli şema kararı).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckHistoryService {

    private final AlertEventRepository alertEventRepo;

    public static final int MAX_PAGE_SIZE = 200;
    private static final int CSV_PAGE = 5_000;
    /** Runaway CSV emniyeti: en fazla bu kadar satır akıtılır (180g × dakikalık ≈ 260k'nın üstü anomali). */
    private static final int CSV_MAX_ROWS = 500_000;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Ham istek parametreleri (controller @RequestParam'larından). */
    public record Query(String from, String to, Integer days, String status, int page, int size) {}

    /** Normalize + clamp edilmiş efektif sorgu. */
    public record Range(String from, String to, boolean fail, int page, int size) {}

    /** Tür-özel veri kaynağı — controller repo metot referanslarıyla kurar. */
    public interface Source<T> {
        Page<T> page(String from, String to, boolean fail, Pageable p);
        long total(String from, String to);
        long fail(String from, String to);
        List<Object[]> histogram(String from, String to, int prefixLen);
    }

    /** CSV kolonu: başlık + satırdan değer çıkaran fonksiyon. */
    public record CsvColumn<T>(String header, Function<T, Object> value) {}

    // ── Aralık çözümleme ─────────────────────────────────────────────────────

    public Range resolve(Query q, int retentionDays) {
        String now = ISO.format(Instant.now());
        String to = normalizeTo(q.to() != null && !q.to().isBlank() ? q.to() : now);
        if (to.compareTo(now) > 0) to = now;   // gelecek tarihli to → now (canlı yenileme kıyası bozulmasın)

        String from;
        if (q.from() != null && !q.from().isBlank()) {
            from = normalizeFrom(q.from());
        } else {
            int d = (q.days() != null && q.days() > 0) ? q.days() : 30;   // days sugar; hiçbiri yoksa 30g
            from = ISO.format(Instant.now().minus(d, ChronoUnit.DAYS));
        }
        String minFrom = ISO.format(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
        if (from.compareTo(minFrom) < 0) from = minFrom;   // retention clamp — range.from ile UI'a görünür
        if (from.compareTo(to) > 0) from = to;

        boolean fail = "fail".equalsIgnoreCase(q.status()) || "changed".equalsIgnoreCase(q.status());
        int page = Math.max(0, q.page());
        int size = Math.max(1, Math.min(q.size(), MAX_PAGE_SIZE));
        return new Range(from, to, fail, page, size);
    }

    /** Tarih/tarih-saat girdisini 19 karakterlik ISO'ya tamamlar (from ucu: günün/dakikanın başı). */
    static String normalizeFrom(String s) {
        if (s == null) return null;
        if (s.length() == 10) return s + "T00:00:00";
        if (s.length() == 16) return s + ":00";
        return s;
    }

    /** to ucu: günün/dakikanın sonu. */
    static String normalizeTo(String s) {
        if (s == null) return null;
        if (s.length() == 10) return s + "T23:59:59";
        if (s.length() == 16) return s + ":59";
        return s;
    }

    /** Yoğunluk kovası genişliği: ≤6 saat → dakika(16), ≤7 gün → saat(13), üstü → gün(10). */
    static int bucketPrefixLen(String from, String to) {
        try {
            Duration d = Duration.between(LocalDateTime.parse(from), LocalDateTime.parse(to));
            long hours = d.toHours();
            if (hours <= 6) return 16;
            if (hours <= 7 * 24) return 13;
            return 10;
        } catch (Exception e) {
            return 13;
        }
    }

    // ── Yanıt zarfı ──────────────────────────────────────────────────────────

    public Map<String, Object> execute(Source<?> src, Range r, String alertDomainKey, Set<String> alertTypes) {
        Pageable pageable = PageRequest.of(r.page(), r.size(), Sort.by(Sort.Direction.DESC, "checkedAt"));
        Page<?> page = src.page(r.from(), r.to(), r.fail(), pageable);

        long totalAll = src.total(r.from(), r.to());
        long totalFail = src.fail(r.from(), r.to());

        // Histogram degrade-edilebilir: patlarsa şerit kaybolur ama liste/sayaç/alarm YAŞAR.
        // 2026-08 test ortamı dersi: JPQL 42803 hatası tüm sekmeyi öldürmüştü — bir daha asla.
        int len = bucketPrefixLen(r.from(), r.to());
        List<Map<String, Object>> buckets = new ArrayList<>();
        try {
            for (Object[] row : src.histogram(r.from(), r.to(), len)) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("key", row[0]);
                b.put("total", row[1]);
                b.put("fail", row[2] == null ? 0L : row[2]);
                buckets.add(b);
            }
        } catch (Exception e) {
            log.warn("Kontrol Geçmişi yoğunluk histogramı başarısız — şerit boş dönecek: {}", e.getMessage());
            buckets.clear();
        }

        List<Map<String, Object>> alerts = new ArrayList<>();
        if (alertDomainKey != null && alertTypes != null && !alertTypes.isEmpty()) {
            for (AlertEvent e : alertEventRepo.findOverlappingForHistory(alertDomainKey, alertTypes, r.from(), r.to())) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("id", e.getId());
                a.put("alert_type", e.getAlertType());
                a.put("alert_level", e.getAlertLevel());
                a.put("message", e.getMessage());
                a.put("created_at", e.getCreatedAt());
                a.put("resolved", Boolean.TRUE.equals(e.getResolved()));
                a.put("resolved_at", e.getResolvedAt());
                alerts.add(a);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", page.getContent());
        out.put("page", r.page());
        out.put("size", r.size());
        out.put("total", page.getTotalElements());          // FİLTRELİ toplam — pagination bunun üstünden
        out.put("counts", Map.of("total", totalAll, "fail", totalFail));
        out.put("range", Map.of("from", r.from(), "to", r.to()));
        out.put("buckets", buckets);
        out.put("alerts", alerts);
        return out;
    }

    // ── CSV dışa aktarım ─────────────────────────────────────────────────────

    /** CSV'yi doğrudan servlet yanıtına akıtır ve yazımı bitirir — çağıran handler {@code null} döner
     *  (HttpEntityMethodProcessor null'u "yanıt tamamlandı" sayar). StreamingResponseBody kullanılamadı:
     *  {@code ResponseEntity<?>} (wildcard) dönüş tipinde Spring emitter handler'ı devreye girmiyor,
     *  lambda JSON converter'a düşüp 500 veriyordu. */
    public <T> void writeCsv(Source<T> src, Range r, String baseName, List<CsvColumn<T>> columns,
                             jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        String fname = baseName + "_" + r.from().substring(0, 10) + "_" + r.to().substring(0, 10) + ".csv";
        response.setStatus(200);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fname + "\"");
        Writer w = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
        w.write(0xFEFF);   // Excel'in UTF-8'i doğru açması için BOM (ActivityLog dışa aktarımıyla aynı)
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) w.write(',');
            w.write(csvEscape(columns.get(i).header()));
        }
        w.write("\r\n");
        int rows = 0;
        for (int p = 0; rows < CSV_MAX_ROWS; p++) {
            Pageable pageable = PageRequest.of(p, CSV_PAGE, Sort.by(Sort.Direction.DESC, "checkedAt"));
            List<T> chunk = src.page(r.from(), r.to(), r.fail(), pageable).getContent();
            if (chunk.isEmpty()) break;
            for (T row : chunk) {
                for (int i = 0; i < columns.size(); i++) {
                    if (i > 0) w.write(',');
                    Object v = columns.get(i).value().apply(row);
                    w.write(csvEscape(v == null ? "" : String.valueOf(v)));
                }
                w.write("\r\n");
                rows++;
            }
            if (chunk.size() < CSV_PAGE) break;
        }
        w.flush();
    }

    private static String csvEscape(String s) {
        if (s == null) return "";
        boolean needQuote = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") || s.contains(";");
        String v = s.replace("\"", "\"\"");
        return needQuote ? "\"" + v + "\"" : v;
    }
}
