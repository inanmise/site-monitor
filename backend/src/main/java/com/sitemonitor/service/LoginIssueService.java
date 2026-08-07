package com.sitemonitor.service;

import com.sitemonitor.model.LoginIssueReport;
import com.sitemonitor.model.LoginIssueReportImage;
import com.sitemonitor.repository.LoginIssueReportImageRepository;
import com.sitemonitor.repository.LoginIssueReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Login "sorun bildir" kayıtlarının kalıcılığı + durum akışı (OPEN → IN_PROGRESS → RESOLVED,
 * ve yanlış-kapatma geri alma RESOLVED → OPEN). Public akış {@code save} çağırır; admin ekranı
 * list/detail/counts/updateStatus kullanır. İş kuralları burada (test edilebilir).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginIssueService {

    public static final String OPEN = "OPEN";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String RESOLVED = "RESOLVED";
    private static final Set<String> STATUSES = Set.of(OPEN, IN_PROGRESS, RESOLVED);
    private static final int MAX_NOTE = 2000;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final LoginIssueReportRepository reportRepo;
    private final LoginIssueReportImageRepository imageRepo;

    /** Ayrıştırılmış görsel — data-URL prefix'i çıkarılmış ham base64. */
    public record ParsedImage(String contentType, String base64) {}

    /** Kaynak + otomatik bağlam meta'sı — LOGIN akışı için {@link #META_LOGIN} yeterli. */
    public record ReportMeta(String source, String category, String appVersion, String screenSize,
                             String tabKey, String autoContextJson, String linkedReference) {}
    public static final ReportMeta META_LOGIN = new ReportMeta("LOGIN", null, null, null, null, null, null);

    /** Public "sorun bildir" akışından kalıcı kayıt (resimlerle) — kaynak LOGIN. Geriye dönük imza. */
    @Transactional
    public LoginIssueReport save(String username, String reporterEmail, String errorText, String message,
                                 List<ParsedImage> images, String ip, String userAgent, String reportedAt) {
        return save(username, reporterEmail, errorText, message, images, ip, userAgent, reportedAt, META_LOGIN);
    }

    /** Genel kayıt — tüm kaynaklar (LOGIN / CLIENT_ERROR / USER_REPORT). Kaydedilen entity döner (refCode için). */
    @Transactional
    public LoginIssueReport save(String username, String reporterEmail, String errorText, String message,
                                 List<ParsedImage> images, String ip, String userAgent, String reportedAt,
                                 ReportMeta meta) {
        LoginIssueReport r = new LoginIssueReport();
        r.setReportedAt(reportedAt != null && !reportedAt.isBlank() ? reportedAt : nowIso());
        r.setUsername(trimTo(username, 100));
        r.setReporterEmail(trimTo(reporterEmail, 255));
        r.setErrorText(blankToNull(errorText));
        r.setMessage(message);
        r.setIpAddress(trimTo(ip, 50));
        r.setUserAgent(blankToNull(userAgent));
        r.setStatus(OPEN);
        r.setImageCount(images != null ? images.size() : 0);
        if (meta != null) {
            r.setSource(meta.source() != null ? meta.source() : "LOGIN");
            r.setCategory(blankToNull(meta.category()));
            r.setAppVersion(trimTo(meta.appVersion(), 30));
            r.setScreenSize(trimTo(meta.screenSize(), 20));
            r.setTabKey(trimTo(meta.tabKey(), 50));
            r.setAutoContextJson(blankToNull(meta.autoContextJson()));
            r.setLinkedReference(trimTo(meta.linkedReference(), 30));
        }
        LoginIssueReport saved = reportRepo.save(r);
        if (images != null) {
            for (ParsedImage img : images) {
                LoginIssueReportImage e = new LoginIssueReportImage();
                e.setReportId(saved.getId());
                e.setContentType(img.contentType());
                e.setDataBase64(img.base64());
                imageRepo.save(e);
            }
        }
        return saved;
    }

    /** Geçerli kaynaklar — dışarıdan gelen filtre değeri bu kümede değilse yok sayılır. */
    public static final Set<String> SOURCES = Set.of("LOGIN", "CLIENT_ERROR", "USER_REPORT");
    /** Kullanıcı önem algısı seçenekleri (USER_REPORT). */
    public static final Set<String> CATEGORIES = Set.of("BLOCKER", "ANNOYANCE", "SUGGESTION");

    @Transactional(readOnly = true)
    public Page<LoginIssueReport> list(String status, String source, String category,
                                       String q, String since, String until, int page, int size) {
        // NOT: Set.of(...).contains(null) NPE atar → önce null kontrolü (status yoksa "tümü").
        String st = (status != null && STATUSES.contains(status)) ? status : null;
        String src = (source != null && SOURCES.contains(source)) ? source : null;
        String cat = (category != null && CATEGORIES.contains(category)) ? category : null;
        // q → "%küçükharf%" (message + errorText + username LIKE); boş → null (filtre kapalı). IncidentService deseni.
        String like = (q != null && !q.isBlank()) ? "%" + q.trim().toLowerCase() + "%" : null;
        Pageable pageable = PageRequest.of(Math.max(0, page), clampSize(size));
        return reportRepo.findFiltered(st, src, cat, like, blankToNull(since), blankToNull(until), pageable);
    }

    @Transactional(readOnly = true)
    public Optional<LoginIssueReport> get(Long id) { return reportRepo.findById(id); }

    @Transactional(readOnly = true)
    public List<LoginIssueReportImage> images(Long reportId) {
        return imageRepo.findByReportIdOrderByIdAsc(reportId);
    }

    /** Durum sayaçları (OPEN/IN_PROGRESS/RESOLVED; eksik durumlar 0) — opsiyonel tarih aralığında.
     *  Aralık verilmezse (null) tüm-zaman → liste penceresiyle uyumlu (kart/satır sayısı tutarlı). */
    @Transactional(readOnly = true)
    public Map<String, Long> counts(String since, String until) {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put(OPEN, 0L); out.put(IN_PROGRESS, 0L); out.put(RESOLVED, 0L);
        for (Object[] row : reportRepo.countByStatus(blankToNull(since), blankToNull(until))) {
            String st = (String) row[0];
            if (st != null && out.containsKey(st)) out.put(st, ((Number) row[1]).longValue());
        }
        return out;
    }

    /**
     * Durum güncelle. RESOLVED'a çekerken {@code resolutionNote} zorunlu (aksi halde
     * IllegalArgumentException → controller 400). OPEN/IN_PROGRESS'e dönünce çözüm alanları temizlenir.
     */
    @Transactional
    public LoginIssueReport updateStatus(Long id, String newStatus, String resolutionNote, String actor) {
        if (!STATUSES.contains(newStatus)) throw new IllegalArgumentException("Geçersiz durum: " + newStatus);
        LoginIssueReport r = reportRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Kayıt bulunamadı: " + id));
        if (RESOLVED.equals(newStatus)) {
            if (resolutionNote == null || resolutionNote.isBlank())
                throw new IllegalArgumentException("Çözüm notu zorunludur");
            r.setResolutionNote(trimTo(resolutionNote, MAX_NOTE));
            r.setResolvedBy(actor);
            r.setResolvedAt(nowIso());
        } else {
            // OPEN / IN_PROGRESS — çözüm SAHİPLİĞİNİ (resolvedBy/At) temizle, ama notu kalıcı çalışma notu
            // olarak KORU: yeni (boş olmayan) not sağlanmışsa güncelle, sağlanmamışsa mevcut notu bırak.
            // Böylece "İşleme Al"da yazılan not kaybolmaz ve yeniden açmada eski not korunur.
            r.setResolvedBy(null);
            r.setResolvedAt(null);
            if (resolutionNote != null && !resolutionNote.isBlank()) {
                r.setResolutionNote(trimTo(resolutionNote, MAX_NOTE));
            }
        }
        r.setStatus(newStatus);
        r.setUpdatedAt(nowIso());
        return reportRepo.save(r);
    }

    /** Admin gösterim referans kodu: {@code LIR-<yıl>-<6 hane id>}. */
    public static String refCode(LoginIssueReport r) {
        String year = (r.getReportedAt() != null && r.getReportedAt().length() >= 4)
                ? r.getReportedAt().substring(0, 4) : "0000";
        return String.format("LIR-%s-%06d", year, r.getId() != null ? r.getId() : 0L);
    }

    private static String nowIso() { return ISO.format(Instant.now()); }
    private static int clampSize(int size) { return size <= 0 ? 20 : Math.min(size, 200); }
    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s; }
    private static String trimTo(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
