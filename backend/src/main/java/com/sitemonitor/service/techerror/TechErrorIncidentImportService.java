package com.sitemonitor.service.techerror;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.model.TechErrorReportImage;
import com.sitemonitor.model.TechErrorReportIncident;
import com.sitemonitor.repository.IncidentImageRepository;
import com.sitemonitor.repository.IncidentRecordRepository;
import com.sitemonitor.repository.TechErrorReportImageRepository;
import com.sitemonitor.repository.TechErrorReportIncidentRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.service.keyword.KeywordFold;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Issue;
import com.sitemonitor.service.techerror.TechErrorValues.Values;
import com.sitemonitor.util.Msg;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BiPredicate;

import static com.sitemonitor.service.techerror.TechErrorIncidentLinks.*;

/**
 * Olay kaydını (Olay ve Hata Geçmişi) teknik hata raporunun anomali konusu olarak İÇE ALMA (2026-11-20): aday listesi,
 * önizleme (yazmaz) ve içe alma (HEP YA DA HİÇ, raporun satır kilidi altında tek işlem). İçe alınan her konu olaya CANLI
 * bağlanır ({@link TechErrorIncidentSyncService}). Kapsam (kim hangi raporu yazar, hangi olayı okur) uçta denetlenir;
 * olayın okunabilirliği buraya {@code canRead(teamId, createdByTeamId)} olarak verilir.
 *
 * <p>Sınırlar: içe almada en çok {@link #MAX_IMPORT} olay; aday sayfası ≤ {@link #MAX_PAGE_SIZE}, aday okuması
 * ≤ {@link #MAX_CANDIDATE_ROWS} satır; arama ≤ {@link #MAX_QUERY} karakter; olay başına en çok {@link #MAX_IMAGE_TRIES}
 * görsel denenir (en çok 2 kopyalanır), görsel bilgisi olay başına ≤ {@link #MAX_INCIDENT_IMAGES} satır okunur.
 */
@Slf4j
@Service
public class TechErrorIncidentImportService {

    public static final int MAX_IMPORT = 10;
    public static final int MAX_PAGE_SIZE = 50;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_QUERY = 200;
    static final int MAX_CANDIDATE_ROWS = 2_000;
    static final int MAX_IMAGE_TRIES = 8;
    static final int MAX_INCIDENT_IMAGES = 50;
    static final int MAX_COPIES = TechErrorTemplate.MAX_ISSUE_IMAGES;

    /** Görselin kopyalanmama nedenleri (KALICI). */
    public static final String R_FORMAT = "format";
    public static final String R_TOO_LARGE = "too_large";
    public static final String R_TOTAL = "total";
    public static final String R_UNREADABLE = "unreadable";

    private static final DateTimeFormatter UTC_TEXT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TechErrorReportService reports;
    private final TechErrorReportRepository reportRepo;
    private final TechErrorReportImageRepository images;
    private final TechErrorReportIncidentRepository links;
    private final TechErrorIncidentLinkHooks hooks;
    private final IncidentRecordRepository incidents;
    private final IncidentImageRepository incidentImages;
    private final PlatformTransactionManager txManager;

    public TechErrorIncidentImportService(TechErrorReportService reports, TechErrorReportRepository reportRepo,
                                          TechErrorReportImageRepository images, TechErrorReportIncidentRepository links,
                                          TechErrorIncidentLinkHooks hooks, IncidentRecordRepository incidents,
                                          IncidentImageRepository incidentImages, PlatformTransactionManager txManager) {
        this.reports = reports;
        this.reportRepo = reportRepo;
        this.images = images;
        this.links = links;
        this.hooks = hooks;
        this.incidents = incidents;
        this.incidentImages = incidentImages;
        this.txManager = txManager;
    }

    // ── Adaylar ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * Raporun takımının olay kayıtları: ayında başlamış olanlar; {@code overlap} ile ayrıca DAHA ÖNCE başlamış ve ay içinde /
     * sonra çözülmüş ya da hâlâ açık olanlar. Başlıkta Türkçe harf duyarsız arama; en yeni önce; okunamayan olay listelenmez.
     */
    public Map<String, Object> candidates(TechErrorReport r, String q, Integer page, Integer size, boolean overlap,
                                          BiPredicate<Long, Long> canRead) {
        String query = q == null ? "" : q.strip();
        if (query.length() > MAX_QUERY) {
            throw new FieldValidationException("q", Msg.t("Arama en çok 200 karakter olabilir. Kısaltıp yeniden deneyin.",
                    "The search can be at most 200 characters. Shorten it and try again."));
        }
        int sz = size == null ? DEFAULT_PAGE_SIZE : Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        int pg = page == null ? 0 : Math.max(0, Math.min(page, 10_000));
        YearMonth ym = YearMonth.of(r.getReportYear(), r.getReportMonth());
        // Sınırlar DAKİKA uzunluğunda (16 karakter, inceleme 2026-11-20): olay zamanı 19 ("…T21:00:00") ya da 16 ("…T21:00")
        // karakter saklanabilir; ay sınırı (Istanbul gece yarısı) hep tam dakikadır. "…T21:00" ≥ "…T21:00" ve "…T21:00:00" ≥
        // "…T21:00" (ön ek eşit, uzun olan büyük) — iki uzunluk da eşlemenin okuduğu gibi aynı aya düşer; 19 karakterlik sınır
        // 16 karakterlik "…T21:00"ı bir önceki aya atıyordu.
        String from = utcMinuteText(ym.atDay(1).atStartOfDay(TechErrorReportService.ZONE).toInstant());
        String to = utcMinuteText(ym.plusMonths(1).atDay(1).atStartOfDay(TechErrorReportService.ZONE).toInstant());
        List<IncidentRecordRepository.ImportCandidate> rows = incidents.findImportCandidates(r.getTeamId(), from, to, overlap,
                PageRequest.of(0, MAX_CANDIDATE_ROWS + 1));
        boolean truncated = rows.size() > MAX_CANDIDATE_ROWS;
        String needle = query.isEmpty() ? null : KeywordFold.fold(query);
        List<IncidentRecordRepository.ImportCandidate> hits = new ArrayList<>();
        for (int k = 0; k < rows.size() && k < MAX_CANDIDATE_ROWS; k++) {
            var c = rows.get(k);
            if (!canRead.test(c.getTeamId(), c.getCreatedByTeamId())) continue;
            if (needle != null && (c.getTitle() == null || !KeywordFold.fold(c.getTitle()).contains(needle))) continue;
            hits.add(c);
        }
        Set<Long> imported = new HashSet<>();
        for (TechErrorReportIncident l : links.findByReportIdOrderByIdAsc(r.getId())) imported.add(l.getIncidentId());
        List<Map<String, Object>> items = new ArrayList<>();
        int fromIdx = (int) Math.min((long) pg * sz, hits.size());
        for (int k = fromIdx; k < Math.min(hits.size(), fromIdx + sz); k++) {
            var c = hits.get(k);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("title", c.getTitle());
            m.put("occurred_at", c.getOccurredAt());
            m.put("resolved_at", c.getResolvedAt());
            m.put("status", c.getStatus());
            m.put("severity", c.getSeverity());
            m.put("channel", c.getChannel());
            m.put("tech_error_count", c.getTechErrorCount());
            m.put("outage_related", c.getOutageRelated());
            m.put("affected_customers", c.getAffectedCustomers());
            m.put("imported", imported.contains(c.getId()));
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", hits.size());
        out.put("page", pg);
        out.put("size", sz);
        out.put("period", Map.of("year", ym.getYear(), "month", ym.getMonthValue()));
        out.put("truncated", truncated);
        return out;
    }

    static String utcText(Instant i) {
        return UTC_TEXT.format(LocalDateTime.ofInstant(i, ZoneOffset.UTC));
    }

    /** UTC metin zamanının dakika uzunluğu ({@code yyyy-MM-ddTHH:mm}) — aday listesinin ay sınırları. */
    static String utcMinuteText(Instant i) {
        return utcText(i).substring(0, 16);
    }

    // ── İstek ────────────────────────────────────────────────────────────────────────────────────────

    /** İçe alma isteği (uçta okunmuş, biçimi doğrulanmış). */
    public record Request(String sectionId, List<Long> incidentIds, List<String> parts, boolean images) { }

    /** Önizleme / içe alma ortak doğrulaması: bölüm, olaylar (var + okunur + aynı takım), daha önce bağlanmamış, bölüm dolmamış. */
    private record Checked(Section section, List<IncidentRecord> incidents) { }

    private Checked check(TechErrorReport r, Template t, Values v, Request req, BiPredicate<Long, Long> canRead) {
        Section s = req.sectionId() == null ? null : t.section(req.sectionId());
        if (s == null || !"issues".equals(s.kind())) {
            throw new FieldValidationException("section_id", Msg.t(
                    "Olay kaydı yalnız raporun bir anomali bölümüne alınabilir; seçilen bölüm yok ya da anomali bölümü değil. Raporu yenileyin.",
                    "An incident record can only be imported into one of the report's anomaly sections; the chosen section doesn't exist or isn't an anomaly section. Reload the report."));
        }
        Map<Long, IncidentRecord> found = new HashMap<>();
        for (IncidentRecord e : incidents.findAllById(req.incidentIds())) found.put(e.getId(), e);
        List<IncidentRecord> list = new ArrayList<>();
        for (Long id : req.incidentIds()) {
            IncidentRecord e = found.get(id);
            if (e == null || !canRead.test(e.getTeamId(), e.getCreatedByTeamId())) throw incidentNotFound(id);
            if (!Objects.equals(e.getTeamId(), r.getTeamId())) {
                throw new FieldValidationException("INCIDENT_OTHER_TEAM", "incident_ids", Msg.t(
                        "Olay kaydı #" + id + " bu kanalın takımına ait değil; yalnız kanalın takımının olay kayıtları içe alınabilir.",
                        "Incident record #" + id + " doesn't belong to this channel's team; only the channel team's incident records can be imported."));
            }
            list.add(e);
        }
        Set<Long> linked = new HashSet<>();
        for (TechErrorReportIncident l : links.findByReportIdOrderByIdAsc(r.getId())) linked.add(l.getIncidentId());
        for (Long id : req.incidentIds()) {
            if (linked.contains(id)) {
                throw new CodedConflictException("INCIDENT_ALREADY_IMPORTED", Msg.t(
                        "Olay kaydı #" + id + " bu rapora zaten alınmış. Raporu yenileyin; aynı olay bir rapora bir kez alınır.",
                        "Incident record #" + id + " is already imported into this report. Reload the report; an incident can be imported into a report only once."),
                        Map.of("incident_id", id));
            }
        }
        int existing = v.issues().getOrDefault(s.id(), List.of()).size();
        if (existing + list.size() > TechErrorValues.MAX_ISSUES) {
            throw new FieldValidationException("SECTION_FULL", "incident_ids", Msg.t(
                    "Bölüm dolu: bir bölümde en çok 30 konu olabilir (şu an " + existing + "). Daha az olay seçin ya da gereksiz konuları silin.",
                    "The section is full: a section can have at most 30 topics (now " + existing + "). Choose fewer incidents or delete topics you don't need."));
        }
        return new Checked(s, list);
    }

    static FieldValidationException incidentNotFound(Long id) {
        return new FieldValidationException("INCIDENT_NOT_FOUND", "incident_ids", Msg.t(
                "Olay kaydı #" + id + " bulunamadı ya da görme yetkiniz yok. Listeyi yenileyip yeniden seçin.",
                "Incident record #" + id + " wasn't found or you can't see it. Refresh the list and choose again."));
    }

    // ── Önizleme ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Önizleme (YAZMAZ): her olay için doldurulacak alanlar, açıklama özeti (≤ 600), notlar, rapora girmeyecek mi, kaç görsel
     * kopyalanır. Görsel sayısı görselin TÜRÜ ve BOYUTUndan tahmindir (baytlar okunmaz); içe alma piksel sınırını da denetler.
     */
    public List<Map<String, Object>> preview(TechErrorReport r, Request req, BiPredicate<Long, Long> canRead) {
        Template t = TechErrorTemplate.fromStored(r.getTemplateJson());
        Values v = TechErrorValues.fromStored(r.getValuesJson(), t);
        Checked c = check(r, t, v, req, canRead);
        YearMonth period = YearMonth.of(r.getReportYear(), r.getReportMonth());
        long baseBytes = reportImageBytes(r, t, v);
        long[] used = { 0 };
        List<Map<String, Object>> out = new ArrayList<>();
        for (IncidentRecord e : c.incidents()) {
            TechErrorIncidentMapper.Result m = TechErrorIncidentMapper.map(TechErrorIncidentMapper.Source.of(e), c.section(), t, period,
                    req.parts(), "i-preview");
            Issue i = m.issue();
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("topic", i.topic());
            f.put("date", i.date());
            f.put("start", i.start());
            f.put("end", i.end());
            f.put("end_next_day", i.endNextDay());
            f.put("errors", i.errors());
            f.put("outage", i.outage());
            f.put("outage_id", i.outageId());
            f.put("impact", i.impact());
            f.put("customers", i.customers());
            ImagePlan plan = req.images() && c.section().opts().allowImages()
                    ? plan(e, req.parts(), baseBytes + used[0], false) : ImagePlan.NONE;
            for (Copy cp : plan.copies()) used[0] += cp.size();
            List<String> notes = new ArrayList<>(m.notes());
            if (!plan.skipped().isEmpty() && !notes.contains(N_IMAGE_SKIPPED)) notes.add(N_IMAGE_SKIPPED);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("incident_id", e.getId());
            p.put("title", e.getTitle());
            p.put("fields", f);
            p.put("description_preview", TechErrorIncidentMapper.preview(i.description()));
            p.put("notes", orderNotes(notes));
            p.put("hidden", m.hidden());
            p.put("images", imagesDto(plan, req.images(), c.section().opts().allowImages()));
            out.add(p);
        }
        return out;
    }

    private static Map<String, Object> imagesDto(ImagePlan plan, boolean asked, boolean allowed) {
        Map<String, Object> img = new LinkedHashMap<>();
        img.put("copy", plan.copies().size());
        List<Map<String, Object>> sk = new ArrayList<>();
        for (Skip s : plan.skipped()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reason", s.reason());
            m.put("image_id", s.imageId());
            sk.add(m);
        }
        img.put("skipped", sk);
        if (asked && !allowed) img.put("allowed", false);
        if (plan.remaining() > 0) img.put("remaining", plan.remaining());
        return img;
    }

    // ── İçe alma ─────────────────────────────────────────────────────────────────────────────────────

    /** İçe alma sonucu: kaydedilen rapor, içe alınanlar ({@code incident_id}, {@code issue_id}, {@code notes}), sayılar. */
    public record ImportOutcome(TechErrorReport report, List<Map<String, Object>> imported, int issuesAdded, int imagesAdded) { }

    /**
     * HEP YA DA HİÇ içe alma (tek işlem, raporun satır kilidi İLK iş): sürüm denetimi (eskiyse 409), doğrulama, her olay için
     * bölümün sonuna yeni konu (eşlenmiş değerler katı kuralla doğrulanır; reddedilen grup boş kalır, {@code value_refused}),
     * değer kaydının hesabı, görsellerin kopyası, tek {@code INCIDENT_IMPORT} geçmiş olayı ve bağ satırları.
     */
    public ImportOutcome importIncidents(Long reportId, Long expectedVersion, Request req, Long by, String byName,
                                         BiPredicate<Long, Long> canRead) {
        TransactionTemplate tx = txManager == null ? null : new TransactionTemplate(txManager);
        if (tx != null) tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            return tx == null ? doImport(reportId, expectedVersion, req, by, byName, canRead)
                    : tx.execute(s -> doImport(reportId, expectedVersion, req, by, byName, canRead));
        } catch (DataIntegrityViolationException e) {
            // Aynı olayın aynı rapora eşzamanlı ikinci içe alması (bağ tablosunun benzersiz kısıtı): kilit sırasından sonra
            // kalan tek yarış. Başka bir bütünlük hatası (sütun sığmadı, NOT NULL…) "zaten alındı" DEĞİLDİR — aynen geçer
            // (inceleme 2026-11-20)
            if (!isLinkUniqueViolation(e)) throw e;
            throw new CodedConflictException("INCIDENT_ALREADY_IMPORTED", Msg.t(
                    "Seçilen olay kayıtlarından biri bu rapora siz alırken başka biri tarafından alındı. Raporu yenileyin.",
                    "One of the chosen incident records was imported into this report by someone else at the same time. Reload the report."));
        }
    }

    /** Bağ tablosunun benzersiz kısıtları (adları KALICI, varlıktaki {@code @UniqueConstraint} adları). */
    static final List<String> LINK_UNIQUE_CONSTRAINTS = List.of("uk_teri_report_incident", "uk_teri_report_topic");

    /**
     * Bütünlük hatası bağ tablosunun benzersiz kısıtından mı: neden zincirinde Hibernate'in kısıt adı ya da sürücü iletisi
     * (H2 adı BÜYÜK harfle ve ekle yazar, PostgreSQL tırnak içinde) bu adlardan birini taşıyor mu — büyük / küçük harf duyarsız.
     */
    static boolean isLinkUniqueViolation(Throwable e) {
        int causeDepth = 0;
        for (Throwable c = e; c != null && causeDepth++ < com.sitemonitor.util.CauseChain.MAX_DEPTH; c = c.getCause()) {
            if (c instanceof org.hibernate.exception.ConstraintViolationException cve && namesLinkConstraint(cve.getConstraintName())) {
                return true;
            }
            if (namesLinkConstraint(c.getMessage())) return true;
            if (c == c.getCause()) break;
        }
        return false;
    }

    private static boolean namesLinkConstraint(String text) {
        if (text == null || text.isEmpty()) return false;
        String low = text.length() > 4096 ? text.substring(0, 4096).toLowerCase(Locale.ROOT) : text.toLowerCase(Locale.ROOT);
        for (String n : LINK_UNIQUE_CONSTRAINTS) if (low.contains(n)) return true;
        return false;
    }

    private ImportOutcome doImport(Long reportId, Long expectedVersion, Request req, Long by, String byName,
                                   BiPredicate<Long, Long> canRead) {
        if (reportRepo.lockRow(reportId).isEmpty()) throw TechErrorReportService.reportNotFound();
        TechErrorReport r = reports.report(reportId);
        if (expectedVersion == null) {
            throw new FieldValidationException("version", Msg.t(
                    "Raporun okunan sürümü gönderilmedi. Sayfayı yenileyip tekrar deneyin.",
                    "The report version you read wasn't sent. Reload the page and try again."));
        }
        if (!expectedVersion.equals(r.getVersion())) throw new OptimisticLockingFailureException("tech_error_report version");
        Template t = TechErrorTemplate.fromStored(r.getTemplateJson());
        YearMonth period = YearMonth.of(r.getReportYear(), r.getReportMonth());
        Values v = TechErrorValues.fromStored(r.getValuesJson(), t);
        // Var olan bağlar: kullanıcının değiştirdiği gruplar sabitlenir, konusu kalmayan bağ silinir (her yazımda)
        hooks.reconcile(links.findByReportIdOrderByIdAsc(reportId), t, v);
        Checked c = check(r, t, v, req, canRead);
        Section s = c.section();
        boolean allowImages = req.images() && s.opts().allowImages();

        Map<String, List<Issue>> working = TechErrorIncidentWriter.mutableIssues(v);
        List<Issue> list = working.computeIfAbsent(s.id(), k -> new ArrayList<>());
        Set<String> ids = new HashSet<>();
        for (List<Issue> l : working.values()) for (Issue i : l) ids.add(i.id());
        Instant now = reports.now();
        long base = reportImageBytes(r, t, v);
        long copied = 0;
        ReadBudget readBudget = new ReadBudget(readCapBytes);                  // tüm olaylar için ortak okuma sınırı
        List<Pending> pending = new ArrayList<>();
        for (IncidentRecord e : c.incidents()) {
            String iid = newIssueId(ids, now);
            ids.add(iid);
            TechErrorIncidentMapper.Result m = TechErrorIncidentMapper.map(TechErrorIncidentMapper.Source.of(e), s, t, period,
                    req.parts(), iid);
            Issue empty = new Issue(iid, "", "", true, "", null, null);
            TechErrorIncidentWriter.Applied a = TechErrorIncidentWriter.apply(t, s, period, empty, m.issue(), GROUPS);
            LinkedHashSet<String> notes = new LinkedHashSet<>(m.notes());
            if (!a.refused().isEmpty()) notes.add(N_VALUE_REFUSED);
            ImagePlan plan = allowImages ? plan(e, req.parts(), base + copied, true, readBudget) : ImagePlan.NONE;
            Map<String, String> captions = new LinkedHashMap<>();
            int n = 0;
            for (Copy cp : plan.copies()) {
                n++;
                copied += cp.size();
                if (cp.caption() != null && !cp.caption().isEmpty()) captions.put(String.valueOf(n), cp.caption());
            }
            if (!plan.skipped().isEmpty()) notes.add(N_IMAGE_SKIPPED);
            Issue topic = a.issue();
            if (!captions.isEmpty()) {
                topic = new Issue(topic.id(), topic.topic(), topic.title(), topic.titleAuto(), topic.description(), topic.customers(),
                        topic.errors(), topic.date(), topic.start(), topic.end(), topic.endNextDay(), topic.outage(), topic.outageId(),
                        topic.impact(), captions, topic.share());
            }
            list.add(topic);
            pending.add(new Pending(e, iid, plan, notes));
        }

        Values merged = TechErrorIncidentWriter.withIssues(v, working, List.of());
        Values after = TechErrorCalc.apply(t, merged, reports.previousFields(r, t));
        String json = TechErrorValues.toJson(after);
        if (json.length() > TechErrorValues.MAX_JSON_CHARS) {
            throw new FieldValidationException("REPORT_TOO_LARGE", "incident_ids", Msg.t(
                    "Seçilen olaylarla rapor içeriği sınırı (256 KB) aşıyor. Daha az olay seçin ya da açıklama parçalarını azaltın.",
                    "With the chosen incidents the report content exceeds its limit (256 KB). Choose fewer incidents or fewer description parts."));
        }
        r.setValuesJson(json);
        r.setUpdatedAt(now);
        r.setUpdatedById(by);
        r.setContentChangedAt(now);
        TechErrorReport saved = reportRepo.saveAndFlush(r);
        reportRepo.touchContent(saved.getId(), now);

        int imagesAdded = 0;
        List<Map<String, Object>> imported = new ArrayList<>();
        List<Map<String, Object>> imageRows = new ArrayList<>();
        List<TechErrorReportIncident> rows = new ArrayList<>();
        for (Pending p : pending) {
            int n = 0;
            for (Copy cp : p.plan().copies()) {
                n++;
                String slot = TechErrorTemplate.issueSlotId(s.id(), p.issueId(), n);
                TechErrorReportImage img = new TechErrorReportImage();
                img.setReportId(saved.getId());
                img.setTeamId(saved.getTeamId());
                img.setSlotId(slot);
                img.setContentType(cp.contentType());
                img.setFileName(TechErrorReportService.fileName("incident-" + p.incident().getId() + "-" + cp.imageId()
                        + ("image/png".equals(cp.contentType()) ? ".png" : ".jpg")));
                img.setWidth(cp.width());
                img.setHeight(cp.height());
                img.setSizeBytes(cp.data().length);
                img.setData(cp.data());
                img.setUploadedAt(now);
                img.setUploadedById(by);
                images.save(img);
                imagesAdded++;
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("slot", slot);
                im.put("incident_id", p.incident().getId());
                im.put("size_bytes", cp.data().length);
                imageRows.add(im);
            }
            Issue stored = TechErrorIncidentWriter.find(after, s.id(), p.issueId());
            Map<String, String> fp = new LinkedHashMap<>();
            for (String g : GROUPS) fp.put(g, fingerprint(g, stored));
            TechErrorReportIncident l = new TechErrorReportIncident();
            l.setReportId(saved.getId());
            l.setChannelId(saved.getChannelId());
            l.setSectionId(s.id());
            l.setIssueId(p.issueId());
            l.setIncidentId(p.incident().getId());
            l.setFieldsFp(fpJson(fp));
            l.setPinned(null);
            l.setParts(csv(req.parts() == null ? DEFAULT_PARTS : req.parts(), PARTS));
            l.setIncidentUpdatedAt(p.incident().getUpdatedAt());
            l.setSourceFp(sourceFp(TechErrorIncidentMapper.Source.of(p.incident())));
            l.setSyncNotes(TechErrorIncidentSyncService.notesCsv(p.notes()));
            l.setImportedAt(now);
            l.setImportedByName(byName == null ? null : (byName.length() > 200 ? byName.substring(0, 200) : byName));
            l.setSyncedAt(now);
            rows.add(l);
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("incident_id", p.incident().getId());
            im.put("issue_id", p.issueId());
            im.put("notes", orderNotes(p.notes()));
            imported.add(im);
        }
        links.saveAllAndFlush(rows);
        if (imagesAdded > 0) images.flush();

        TechErrorChannel ch = reports.channel(saved.getChannelId());
        Map<String, Object> detail = reports.history().changesDetail(saved, t, v, after, ch.getName());
        List<Long> incIds = new ArrayList<>();
        for (Pending p : pending) incIds.add(p.incident().getId());
        detail.put("incident_ids", incIds);
        detail.put("section_id", s.id());
        detail.put("issues_added", pending.size());
        if (imagesAdded > 0) {
            detail.put("images_added", imagesAdded);
            detail.put("images", imageRows);
        }
        reports.history().record(saved, EV_IMPORT, by, null, saved.getVersion(), detail, now);
        reports.analyticsChanged();
        return new ImportOutcome(saved, imported, pending.size(), imagesAdded);
    }

    private record Pending(IncidentRecord incident, String issueId, ImagePlan plan, LinkedHashSet<String> notes) { }

    /** İstemcinin {@code newIssue} biçimi: {@code i-<zaman (36 tabanı)><4 rastgele>} — rapordaki kimliklerle çakışmaz. */
    static String newIssueId(Set<String> taken, Instant now) {
        String base = "i-" + Long.toString(now.toEpochMilli(), 36);
        for (int attempt = 0; attempt < 50; attempt++) {
            StringBuilder b = new StringBuilder(base);
            for (int k = 0; k < 4; k++) b.append(Character.forDigit(RANDOM.nextInt(36), 36));
            String id = b.toString();
            if (!taken.contains(id)) return id;
        }
        // Rastgele 50 denemede çakıştı (pratikte olmaz): sıra numarası
        for (int k = 0; ; k++) {
            String id = base + "x" + k;
            if (!taken.contains(id)) return id;
            if (k > 10_000) throw new IllegalStateException("issue id space exhausted");
        }
    }

    private static List<String> orderNotes(Collection<String> notes) {
        List<String> out = new ArrayList<>();
        for (String n : NOTE_CODES) if (notes.contains(n)) out.add(n);
        return out;
    }

    // ── Görseller ────────────────────────────────────────────────────────────────────────────────────

    record Copy(long imageId, String caption, String contentType, int width, int height, long size, byte[] data) { }

    record Skip(long imageId, String reason) { }

    record ImagePlan(List<Copy> copies, List<Skip> skipped, int remaining) {
        static final ImagePlan NONE = new ImagePlan(List.of(), List.of(), 0);
    }

    /**
     * Bir içe almada veritabanından OKUNAN görsel baytlarının sınırı (inceleme 2026-11-20): raporun görsel toplamının
     * ({@link TechErrorReportService#MAX_REPORT_IMAGE_BYTES}) iki katı — reddedilen (biçim / boyut) görseller de sayılır.
     * Aşacak görsel okunmadan {@code total} nedeniyle atlanır.
     */
    static final long MAX_IMAGE_READ_BYTES = 2L * TechErrorReportService.MAX_REPORT_IMAGE_BYTES;

    /** İçe almanın okuma bütçesi (tüm olaylar için ortak). */
    static final class ReadBudget {
        final long cap;
        long read;

        ReadBudget(long cap) {
            this.cap = cap;
        }

        boolean fits(long bytes) {
            return read + Math.max(0L, bytes) <= cap;
        }
    }

    /** Testler okuma sınırını küçültür. */
    long readCapBytes = MAX_IMAGE_READ_BYTES;

    /** Raporun ŞU ANKİ görsellerinin toplamı (sahipsiz konu görseli sayılmaz — yüklemenin kuralı). */
    private long reportImageBytes(TechErrorReport r, Template t, Values v) {
        long total = 0;
        for (var m : images.findByReportIdOrderByIdAsc(r.getId())) {
            if (!TechErrorReportService.orphanIssueSlot(t, v, m.getSlotId())) total += m.getSizeBytes();
        }
        return total;
    }

    /**
     * Olayın kopyalanacak görselleri: seçilen açıklama parçalarında GEÇTİĞİ sıra, sonra kalanlar (kimlik sırası). İlk iki geçerli
     * görsel kopyalanır; geçemeyen (en çok {@link #MAX_IMAGE_TRIES} deneme) nedeniyle raporlanır. {@code load}: baytlar okunup
     * {@link TechErrorImageValidator} kuralıyla denetlenir (içe alma); değilse yalnız tür + boyut (önizleme).
     */
    ImagePlan plan(IncidentRecord e, Collection<String> parts, long reportBytes, boolean load) {
        return plan(e, parts, reportBytes, load, new ReadBudget(readCapBytes));
    }

    /**
     * {@link #plan(IncidentRecord, Collection, long, boolean)} — içe almanın ORTAK okuma bütçesiyle. Baytlar yönetilen varlık
     * olarak DEĞİL izdüşümle okunur ({@link IncidentImageRepository#findDataOfIncident}): reddedilen görselin baytları
     * kalıcılık bağlamında kalmaz, bir sonraki görsele geçince bırakılır — aynı anda en çok BİR reddedilecek görselin baytı
     * bellektedir (kopyalananlar raporun 40 MB toplamıyla sınırlı).
     */
    ImagePlan plan(IncidentRecord e, Collection<String> parts, long reportBytes, boolean load, ReadBudget budget) {
        Set<String> chosen = parts == null ? new HashSet<>(DEFAULT_PARTS) : new HashSet<>(parts);
        List<IncidentImageRepository.Meta> metas = incidentImages.findMetaByIncident(e.getId(), PageRequest.of(0, MAX_INCIDENT_IMAGES));
        Map<Long, IncidentImageRepository.Meta> byId = new LinkedHashMap<>();
        for (var m : metas) byId.put(m.getId(), m);
        LinkedHashMap<Long, String> order = new LinkedHashMap<>();               // kimlik → alt metin
        for (String p : PARTS) {
            if (!chosen.contains(p)) continue;
            String md = switch (p) {
                case P_RCA -> e.getRcaSummary();
                case P_IMPACT -> e.getBusinessImpact();
                case P_RESOLUTION -> e.getResolutionSteps();
                default -> e.getDescription();
            };
            for (TechErrorIncidentText.ImageRef ref : TechErrorIncidentText.imageRefs(md)) order.putIfAbsent(ref.id(), ref.alt());
        }
        for (Long id : byId.keySet()) order.putIfAbsent(id, null);
        List<Copy> copies = new ArrayList<>();
        List<Skip> skipped = new ArrayList<>();
        long used = reportBytes;
        int tries = 0;
        int considered = 0;
        for (var en : order.entrySet()) {
            if (copies.size() >= MAX_COPIES || tries >= MAX_IMAGE_TRIES) break;
            considered++;
            tries++;
            long id = en.getKey();
            IncidentImageRepository.Meta m = byId.get(id);
            if (m == null) {                                                     // başka olayın / taslağın görseli: kopyalanmaz
                skipped.add(new Skip(id, R_UNREADABLE));
                continue;
            }
            long size = m.getSizeBytes() == null ? 0L : m.getSizeBytes();
            String ct = m.getContentType() == null ? "" : m.getContentType().toLowerCase(Locale.ROOT);
            if (size > TechErrorImageValidator.MAX_BYTES) {
                skipped.add(new Skip(id, R_TOO_LARGE));
                continue;
            }
            if (!"image/png".equals(ct) && !"image/jpeg".equals(ct)) {
                skipped.add(new Skip(id, R_FORMAT));
                continue;
            }
            if (used + size > TechErrorReportService.MAX_REPORT_IMAGE_BYTES) {
                skipped.add(new Skip(id, R_TOTAL));
                continue;
            }
            String caption = caption(m.getCaption(), en.getValue());
            if (!load) {
                copies.add(new Copy(id, caption, ct, 0, 0, size, null));
                used += size;
                continue;
            }
            if (!budget.fits(size)) {                                            // içe almanın okuma bütçesi doldu: okunmaz
                skipped.add(new Skip(id, R_TOTAL));
                continue;
            }
            List<IncidentImageRepository.ImageBytes> found = incidentImages.findDataOfIncident(id, e.getId());
            byte[] data = found.isEmpty() ? null : found.get(0).getData();
            if (data != null) budget.read += data.length;
            if (data == null || data.length == 0) {
                skipped.add(new Skip(id, R_UNREADABLE));
                continue;
            }
            String reason = reasonOf(data);
            if (reason != null) {
                skipped.add(new Skip(id, reason));
                continue;
            }
            if (used + data.length > TechErrorReportService.MAX_REPORT_IMAGE_BYTES) {
                skipped.add(new Skip(id, R_TOTAL));
                continue;
            }
            TechErrorImageValidator.Checked ck;
            try {
                ck = TechErrorImageValidator.check(data);
            } catch (RuntimeException ex) {
                skipped.add(new Skip(id, R_UNREADABLE));
                continue;
            }
            copies.add(new Copy(id, caption, ck.contentType(), ck.width(), ck.height(), data.length, data));
            used += data.length;
        }
        return new ImagePlan(List.copyOf(copies), List.copyOf(skipped), Math.max(0, order.size() - considered));
    }

    /** Baytlardan ret nedeni (geçerliyse null): tür (PNG / JPEG), boyut, piksel sınırları. */
    static String reasonOf(byte[] data) {
        if (data.length > TechErrorImageValidator.MAX_BYTES) return R_TOO_LARGE;
        String type = TechErrorImageValidator.sniff(data);
        if (type == null) return R_FORMAT;
        int[] size = TechErrorImageValidator.dimensions(data, "image/png".equals(type) ? "png" : "jpeg");
        if (size == null) return R_UNREADABLE;
        if (size[0] < TechErrorImageValidator.MIN_SIDE || size[1] < TechErrorImageValidator.MIN_SIDE) return R_FORMAT;
        if (size[0] > TechErrorImageValidator.MAX_SIDE || size[1] > TechErrorImageValidator.MAX_SIDE
                || (long) size[0] * size[1] > TechErrorImageValidator.MAX_PIXELS) return R_TOO_LARGE;
        return null;
    }

    /** Konu görselinin açıklaması: olay görselinin adı, yoksa markdown alt metni; tek satır, ≤ 200. */
    static String caption(String stored, String alt) {
        String c = TechErrorTemplate.line(stored);
        if (c.isEmpty()) c = TechErrorTemplate.line(alt);
        if (c.length() > TechErrorValues.MAX_CAPTION) c = c.substring(0, TechErrorValues.MAX_CAPTION).strip();
        return c.isEmpty() ? null : c;
    }
}
