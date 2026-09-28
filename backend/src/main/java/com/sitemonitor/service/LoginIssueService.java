package com.sitemonitor.service;

import com.sitemonitor.model.IssueReportComment;
import com.sitemonitor.model.LoginIssueReport;
import com.sitemonitor.model.LoginIssueReportImage;
import com.sitemonitor.repository.IssueReportCommentRepository;
import com.sitemonitor.repository.LoginIssueMailLogRepository;
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
    /** Kalıcı silmede giden maillerin SAKLANAN kopyası da gider (konu + alıcı + tam HTML gövde). */
    private final LoginIssueMailLogRepository mailLogRepo;
    /** Konuşma dizisi + durum geçişi satırları (2026-09-26) — rapor durum makinesiyle AYNI transaction'da yazılır. */
    private final IssueReportCommentRepository commentRepo;

    /** Yorum ekleme sonucu: yazılan satır + bildirenin yorumu ÇÖZÜLMÜŞ raporu yeniden açtı mı. */
    public record CommentResult(IssueReportComment comment, boolean reopened) {}

    /** Ayrıştırılmış görsel — data-URL prefix'i çıkarılmış ham base64. */
    public record ParsedImage(String contentType, String base64) {}

    /**
     * Kaynak + otomatik bağlam meta'sı — LOGIN akışı için {@link #META_LOGIN} yeterli. {@code impacts} /
     * {@code impactOther} (2026-09-28): oturum içi bildirimin çoklu etki seçimi ({@link #normalizeImpacts} çıktısı).
     */
    public record ReportMeta(String source, String category, String appVersion, String screenSize,
                             String tabKey, String autoContextJson, String linkedReference,
                             String impacts, String impactOther) {
        /** Etkisiz imza — LOGIN / CLIENT_ERROR / otomatik kayıtlar (eski çağıranlar değişmez). */
        public ReportMeta(String source, String category, String appVersion, String screenSize,
                          String tabKey, String autoContextJson, String linkedReference) {
            this(source, category, appVersion, screenSize, tabKey, autoContextJson, linkedReference, null, null);
        }
    }
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
            r.setImpacts(blankToNull(meta.impacts()));
            r.setImpactOther(blankToNull(meta.impactOther()));
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

    /**
     * Raporu KALICI siler — rapor + resimleri + mail gunlugu.
     *
     * <p>Uc tablo BIRLIKTE silinir: baglar duz {@code reportId} FK'sidir (proje deseni,
     * @ManyToOne yok), yani yalniz raporu silmek otekileri OKSUZ birakir -- hicbir ekranda
     * gorunmeyen ama sonsuza dek buyuyen satirlar.
     *
     * <p>Geri alinamaz; cagiran yetkiyi ve onayi kendisi saglar.
     *
     * @return silinen rapor (kayit yoksa {@code null}) -- denetim kaydi icin
     */
    @Transactional
    public LoginIssueReport purge(Long id) {
        LoginIssueReport r = reportRepo.findById(id).orElse(null);
        if (r == null) return null;
        imageRepo.deleteByReportId(id);
        mailLogRepo.deleteByReportId(id);
        commentRepo.deleteByReportId(id);
        reportRepo.delete(r);
        return r;
    }

    // ── "Bildirimlerim" — kullanıcının kendi kayıtları (2026-09-26) ─────────────────────────

    /** Kullanıcı adı normalizasyonu: eşleşme büyük/küçük harf duyarsız (AD kullanıcı adı yazımı değişebilir). */
    private static String norm(String username) {
        return username == null ? "" : username.strip().toLowerCase(java.util.Locale.ROOT);
    }

    /** Rapor bu kullanıcıya mı ait? (username eşleşmesi; boş kullanıcı adı hiçbir şeye sahip değildir) */
    public static boolean ownedBy(LoginIssueReport r, String username) {
        String u = norm(username);
        return r != null && !u.isEmpty() && u.equals(norm(r.getUsername()));
    }

    /** Kullanıcının kendi raporları — tüm kaynaklar, en yeni önce; durum süzgeci opsiyonel. */
    @Transactional(readOnly = true)
    public Page<LoginIssueReport> listMine(String username, String status, int page, int size) {
        String st = (status != null && STATUSES.contains(status)) ? status : null;
        Pageable pageable = PageRequest.of(Math.max(0, page), clampSize(size));
        return reportRepo.findMine(norm(username), st, pageable);
    }

    /** Kullanıcının kendi raporlarının durum sayaçları (eksik durumlar 0). */
    @Transactional(readOnly = true)
    public Map<String, Long> countsMine(String username) {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put(OPEN, 0L); out.put(IN_PROGRESS, 0L); out.put(RESOLVED, 0L);
        for (Object[] row : reportRepo.countMineByStatus(norm(username))) {
            String st = (String) row[0];
            if (st != null && out.containsKey(st)) out.put(st, ((Number) row[1]).longValue());
        }
        return out;
    }

    /**
     * Kullanıcının KENDİ raporu — başkasınınki için boş döner (controller 404 verir: 403 varlığı sızdırır).
     */
    @Transactional(readOnly = true)
    public Optional<LoginIssueReport> getMine(Long id, String username) {
        return reportRepo.findById(id).filter(r -> ownedBy(r, username));
    }

    /** Bildiren raporu açtı — okunmamış-yanıt göstergesi sıfırlanır. */
    @Transactional
    public void markSeenByReporter(Long id) {
        reportRepo.findById(id).ifPresent(r -> { r.setReporterSeenAt(nowIso()); reportRepo.save(r); });
    }

    // ── Konuşma dizisi ────────────────────────────────────────────────────────────────────────

    /** Bir raporun TÜM satırları (yorum + durum geçişi; iç notlar DÂHİL) — yalnız yönetici ucu. */
    @Transactional(readOnly = true)
    public List<IssueReportComment> comments(Long reportId) {
        return commentRepo.findByReportIdOrderByIdAsc(reportId);
    }

    /** Bildirenin görebileceği satırlar: iç notlar SÜZÜLÜR. Kullanıcı uçlarının tek kaynağı. */
    @Transactional(readOnly = true)
    public List<IssueReportComment> publicComments(Long reportId) {
        return commentRepo.findByReportIdOrderByIdAsc(reportId).stream().filter(c -> !c.isInternal()).toList();
    }

    /** Liste rozeti: rapor → herkese açık yorum sayısı. */
    @Transactional(readOnly = true)
    public Map<Long, Long> publicCommentCounts(java.util.Collection<Long> ids) {
        Map<Long, Long> out = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) return out;
        for (Object[] row : commentRepo.countPublicByReportIds(ids)) {
            out.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return out;
    }

    /**
     * Yorum ekle. Gövde 1–{@value IssueReportComment#MAX_BODY} karakter (aksi hâlde IllegalArgumentException →
     * controller 400). {@code byReporter=true} ve rapor RESOLVED ise rapor OTOMATİK yeniden açılır
     * (→ IN_PROGRESS; çözüm sahipliği temizlenir, çözüm notu geçmiş olarak KALIR) ve bir STATUS satırı düşer.
     * İç not ({@code internal}) yalnız yönetici yazar ve bildirenin göstergesine dokunmaz.
     */
    @Transactional
    public CommentResult addComment(Long reportId, String author, String authorRole, boolean internal,
                                    boolean byReporter, String body) {
        String text = body == null ? "" : body.strip();
        if (text.isEmpty()) throw new IllegalArgumentException("Yorum boş olamaz");
        if (text.length() > IssueReportComment.MAX_BODY)
            throw new IllegalArgumentException("Yorum en fazla " + IssueReportComment.MAX_BODY + " karakter olabilir");
        LoginIssueReport r = reportRepo.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException("Kayıt bulunamadı: " + reportId));
        String now = nowIso();
        IssueReportComment c = new IssueReportComment();
        c.setReportId(reportId);
        c.setKind(IssueReportComment.KIND_COMMENT);
        c.setAuthorUsername(trimTo(author, 100));
        c.setAuthorRole(trimTo(authorRole, 20));
        c.setInternal(!byReporter && internal);   // bildiren iç not yazamaz
        c.setByReporter(byReporter);
        c.setBody(text);
        c.setCreatedAt(now);
        IssueReportComment saved = commentRepo.save(c);

        boolean reopened = false;
        if (byReporter && RESOLVED.equals(r.getStatus())) {
            // Çözülmüş rapora bildirenin yazması = "sorun bitmedi": IN_PROGRESS'e döner, çözüm notu geçmiş olarak kalır.
            r.setStatus(IN_PROGRESS);
            r.setResolvedBy(null);
            r.setResolvedAt(null);
            statusRow(reportId, IN_PROGRESS, author, authorRole, true, now);
            reopened = true;
        }
        r.setLastActivityAt(now);
        if (!byReporter && !saved.isInternal()) r.setLastAdminActivityAt(now);
        r.setUpdatedAt(now);
        reportRepo.save(r);
        return new CommentResult(saved, reopened);
    }

    private void statusRow(Long reportId, String status, String author, String authorRole, boolean byReporter, String now) {
        IssueReportComment s = new IssueReportComment();
        s.setReportId(reportId);
        s.setKind(IssueReportComment.KIND_STATUS);
        s.setAuthorUsername(trimTo(author, 100));
        s.setAuthorRole(trimTo(authorRole, 20));
        s.setInternal(false);
        s.setByReporter(byReporter);
        s.setBody(status);
        s.setCreatedAt(now);
        commentRepo.save(s);
    }

    /** Geçerli kaynaklar — dışarıdan gelen filtre değeri bu kümede değilse yok sayılır. */
    public static final Set<String> SOURCES = Set.of("LOGIN", "CLIENT_ERROR", "USER_REPORT");
    /**
     * Kullanıcı önem algısı seçenekleri (USER_REPORT) + talep türü {@code DOMAIN_TRANSFER} (2026-09-28): envanterde
     * başka ekipte kayıtlı alan adının aktarım talebi — mükerrer kayıt 409'undaki "Aktarım talebi oluştur" bu türle
     * gönderir; Sorun Bildirimleri ekranı bu türe göre süzülebilir.
     */
    public static final Set<String> CATEGORIES = Set.of("BLOCKER", "ANNOYANCE", "SUGGESTION", "DOMAIN_TRANSFER");

    /**
     * "Ne yaşıyorsunuz?" etki kodları (2026-09-28) — KANONİK sıra (en sık görülenden). Önemden ayrı ve çoklu seçilir;
     * arayüz etiketleri {@code issue.impact.<KOD>}, e-posta etiketleri {@code EmailNotificationService.labelForImpact}.
     */
    public static final List<String> IMPACTS = List.of("LOGIN", "PAGE_NOT_LOADING", "SLOW", "WRONG_DATA", "SAVE_ERROR",
            "NO_ALERTS", "FALSE_ALERTS", "ACCESS", "MOBILE", "REPORT_EXPORT", "FEATURE_REQUEST", "OTHER");
    /** "Diğer" serbest metninin üst sınırı. */
    public static final int IMPACT_OTHER_MAX = 200;

    /**
     * İstemcinin etki listesini doğrular ve saklama biçimine çevirir: yalnız {@link #IMPACTS} kodları (bilinmeyen kod
     * → IllegalArgumentException = 400), tekilleştirilmiş, KANONİK sırada, virgülle birleşik. Yok / boş → null.
     */
    public static String normalizeImpacts(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof java.util.Collection<?> list) || list.size() > IMPACTS.size() * 2) {
            throw new IllegalArgumentException("Geçersiz etki değeri");
        }
        Set<String> chosen = new java.util.HashSet<>();
        for (Object o : list) {
            String code = o == null ? "" : o.toString().trim().toUpperCase(java.util.Locale.ROOT);
            if (!IMPACTS.contains(code)) throw new IllegalArgumentException("Geçersiz etki değeri");
            chosen.add(code);
        }
        if (chosen.isEmpty()) return null;
        return String.join(",", IMPACTS.stream().filter(chosen::contains).toList());
    }

    /**
     * "Diğer" metni — YALNIZ etkilerde OTHER varsa saklanır. Kontrol ve biçim (görünmez / yön değiştiren) karakterleri
     * boşluğa çevrilir, ardışık boşluk sadeleşir; {@link #IMPACT_OTHER_MAX} aşılırsa 400.
     */
    public static String sanitizeImpactOther(Object raw, String impactsCsv) {
        if (raw == null || !impactList(impactsCsv).contains("OTHER")) return null;
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toString().toCharArray()) {
            sb.append(Character.isISOControl(c) || Character.getType(c) == Character.FORMAT ? ' ' : c);
        }
        String s = sb.toString().trim().replaceAll(" {2,}", " ");
        if (s.isEmpty()) return null;
        if (s.length() > IMPACT_OTHER_MAX) throw new IllegalArgumentException("Alan uzunluk sınırı aşıldı");
        return s;
    }

    /** Saklanan CSV → kod listesi (yanıtlar için; bilinmeyen kalıntı kod düşer); null → boş liste. */
    public static List<String> impactList(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return java.util.Arrays.stream(csv.split(",")).map(String::trim).filter(IMPACTS::contains).toList();
    }

    @Transactional(readOnly = true)
    public Page<LoginIssueReport> list(String status, String source, String category,
                                       String q, String since, String until, int page, int size) {
        return list(status, source, category, null, q, since, until, page, size);
    }

    /** Etki süzgeçli liste (2026-09-28): {@code impact} tek kod — kaydın etki kümesinde olan (geçersiz kod → yok sayılır). */
    @Transactional(readOnly = true)
    public Page<LoginIssueReport> list(String status, String source, String category, String impact,
                                       String q, String since, String until, int page, int size) {
        // NOT: Set.of(...).contains(null) NPE atar → önce null kontrolü (status yoksa "tümü").
        String st = (status != null && STATUSES.contains(status)) ? status : null;
        String src = (source != null && SOURCES.contains(source)) ? source : null;
        String cat = (category != null && CATEGORIES.contains(category)) ? category : null;
        // q → "%küçükharf%" (message + errorText + username LIKE); boş → null (filtre kapalı). IncidentService deseni.
        String like = (q != null && !q.isBlank()) ? "%" + q.trim().toLowerCase() + "%" : null;
        Pageable pageable = PageRequest.of(Math.max(0, page), clampSize(size));
        // Etki CSV'sinde TAM kod eşleşmesi: iki uca virgül eklenmiş kümede ",KOD," aranır (SLOW ≠ SLOWNESS).
        String imp = (impact != null && IMPACTS.contains(impact)) ? "%," + impact + ",%" : null;
        return reportRepo.findFiltered(st, src, cat, imp, like, blankToNull(since), blankToNull(until), pageable);
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
        return updateStatus(id, newStatus, resolutionNote, actor, "ADMIN");
    }

    /**
     * Durum güncelle (2026-09-26 imzası: aktörün rolüyle). Durum GERÇEKTEN değiştiyse zaman çizelgesine bir
     * STATUS satırı düşer ve bildirenin okunmamış göstergesi ({@code lastAdminActivityAt}) damgalanır —
     * aynı durumu tekrar kaydetmek (yalnız not güncelleme) satır üretmez.
     */
    @Transactional
    public LoginIssueReport updateStatus(Long id, String newStatus, String resolutionNote, String actor, String actorRole) {
        if (newStatus == null || !STATUSES.contains(newStatus)) throw new IllegalArgumentException("Geçersiz durum: " + newStatus);
        LoginIssueReport r = reportRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Kayıt bulunamadı: " + id));
        boolean changed = !newStatus.equals(r.getStatus());
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
        String now = nowIso();
        r.setStatus(newStatus);
        r.setUpdatedAt(now);
        if (changed) {
            statusRow(id, newStatus, actor, actorRole, false, now);
            r.setLastActivityAt(now);
            r.setLastAdminActivityAt(now);
        }
        return reportRepo.save(r);
    }

    /** Durum geçişi gerçekten oldu mu? (controller bildirimleri yalnız gerçek geçişte gönderir) */
    public static boolean statusChanged(String before, String after) {
        return after != null && !after.equals(before);
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
