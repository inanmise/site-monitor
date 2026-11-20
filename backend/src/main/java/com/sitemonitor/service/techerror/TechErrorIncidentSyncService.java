package com.sitemonitor.service.techerror;

import com.sitemonitor.config.GlobalExceptionHandler.CodedNotFoundException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.model.TechErrorReportIncident;
import com.sitemonitor.repository.IncidentRecordRepository;
import com.sitemonitor.repository.TechErrorReportIncidentRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.service.IncidentRecordChangedEvent;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Issue;
import com.sitemonitor.service.techerror.TechErrorValues.Values;
import com.sitemonitor.util.Msg;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import static com.sitemonitor.service.techerror.TechErrorIncidentLinks.*;

/**
 * Anomali konusu ↔ olay kaydı CANLI bağının senkronu (2026-11-20). Kurallar sözleşmede ({@code olay-ice-alma-api.md}):
 *
 * <ul>
 *   <li>Her senkron raporun SATIR KİLİDİNİ ilk iş olarak alır ({@link TechErrorReportRepository#lockRow}); olay satırları
 *       kilitlenmez. Bir rapor = bir işlem ({@link TransactionTemplate}, REQUIRES_NEW).</li>
 *   <li>Önce sabitleme tespiti (kullanıcının değiştirdiği grup) + konusu kalmamış bağın silinmesi; sonra olay yeniden okunur,
 *       eşlenir, sabitlenmemiş ve eşlenen izi şu ankinden farklı her grup yazılır (konunun katı kuralı reddederse yazılmaz,
 *       {@code value_refused}); değerler değer kaydının yoluyla hesaplanır ({@link TechErrorCalc}), sürüm artar (editördeki
 *       eski kayıt 409 alır), içerik sayacı artar, TEK geçmiş olayı {@code INCIDENT_SYNC}.</li>
 *   <li>Dondurulmuş rapor ({@link TechErrorIncidentLinks#frozen}) kendiliğinden değişmez; yalnız açık "yenile" yazar.</li>
 *   <li>Olay güncellenince ({@link IncidentRecordChangedEvent}, işlem işlendikten SONRA) bağlı en çok
 *       {@link #MAX_REPORTS_PER_INCIDENT} rapor arka planda (tek iş parçacığı, sınırlı kuyruk, olay başına birleştirilir)
 *       senkronlanır; hata uyarıdır (dakikada en çok bir), olay kaydını hiçbir koşulda bozmaz.</li>
 * </ul>
 */
@Slf4j
@Service
public class TechErrorIncidentSyncService {

    /** Olay başına senkronlanan en çok rapor (en yeni raporlar). */
    public static final int MAX_REPORTS_PER_INCIDENT = 20;
    /** "Kullanıldığı raporlar" listesinin en çok satırı. */
    public static final int MAX_USAGE_ROWS = 100;
    /** Senkronun geçmişteki yapan adı (kişi yok). */
    public static final String ACTOR = "SiteMonitor";
    /** Arka plan kuyruğunun sınırı (olay kimliği; aynı olay kuyrukta bir kez). */
    static final int QUEUE_CAPACITY = 200;
    private static final long WARN_EVERY_MS = 60_000L;

    public enum Mode { AUTO, CATCH_UP, REFRESH }

    private final TechErrorReportService reports;
    private final TechErrorReportRepository reportRepo;
    private final TechErrorReportIncidentRepository links;
    private final TechErrorIncidentLinkHooks hooks;
    private final IncidentRecordRepository incidents;
    private final PlatformTransactionManager txManager;

    /** Yetişmenin kaynak izi karşılaştırmasında bir sorguda okunan en çok olay (tam metinleriyle; işlem dışında okunur). */
    static final int SOURCE_CHUNK = 20;

    private volatile boolean stopping;
    /** Kapanış başladıktan sonra kuyruktan çıkıp koşmayan olay senkronları (kapanışta sayı olarak uyarılır). */
    private final java.util.concurrent.atomic.AtomicInteger droppedAtShutdown = new java.util.concurrent.atomic.AtomicInteger();
    private final AtomicLong lastWarn = new AtomicLong();
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();
    private volatile Executor executor;

    public TechErrorIncidentSyncService(TechErrorReportService reports, TechErrorReportRepository reportRepo,
                                        TechErrorReportIncidentRepository links, TechErrorIncidentLinkHooks hooks,
                                        IncidentRecordRepository incidents, PlatformTransactionManager txManager) {
        this.reports = reports;
        this.reportRepo = reportRepo;
        this.links = links;
        this.hooks = hooks;
        this.incidents = incidents;
        this.txManager = txManager;
    }

    /** Testler arka plan yürütücüsünü değiştirir (ör. aynı iş parçacığı). */
    void useExecutor(Executor e) {
        this.executor = e;
    }

    // ── Olay güncellendi (işlem işlendikten sonra) ───────────────────────────────────────────────────

    /** Olay kaydı güncellendi: bağlı raporlar arka planda senkronlanır (aynı olay kuyrukta bir kez). Hata yutulur. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onIncidentRecordChanged(IncidentRecordChangedEvent e) {
        if (e == null || e.incidentId() == null || stopping) return;
        Long id = e.incidentId();
        if (!pending.add(id)) return;                                // zaten kuyrukta — tek senkron yeter (en son değeri okur)
        try {
            executor().execute(() -> {
                pending.remove(id);
                if (stopping) {                                          // kapanış başladı: koşmaz, kapanışta SAYISI yazılır
                    droppedAtShutdown.incrementAndGet();
                    return;
                }
                onIncidentChanged(id);
            });
        } catch (RejectedExecutionException ex) {
            pending.remove(id);
            warn("Olay kaydı #" + id + " senkron kuyruğu dolu; bağlı raporlar açılınca güncellenir: " + ex);
        }
    }

    /**
     * Olayın bağlı olduğu (en yeni {@link #MAX_REPORTS_PER_INCIDENT}) raporları senkronlar — her rapor KENDİ işleminde.
     * Dondurulmuş rapor atlanır. Hata uyarıdır (sonraki rapora geçilir); kapanış başladıysa döngü durur.
     */
    public void onIncidentChanged(Long incidentId) {
        if (incidentId == null || stopping) return;
        List<TechErrorReportIncident> ls;
        try {
            ls = links.findByIncident(incidentId, PageRequest.of(0, MAX_REPORTS_PER_INCIDENT));
        } catch (RuntimeException e) {
            warn("Olay kaydı #" + incidentId + " bağları okunamadı: " + e);
            return;
        }
        for (TechErrorReportIncident l : ls) {
            if (stopping) return;
            try {
                inTx(() -> sync(l.getReportId(), Mode.AUTO, incidentId, null, null, null, false, null, null, null));
            } catch (RuntimeException e) {
                warn("Olay kaydı #" + incidentId + " teknik hata raporu #" + l.getReportId() + " ile senkronlanamadı: " + e);
            }
        }
    }

    // ── Yetişme ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * Yetişme: dondurulmamış raporun BAYAT bağlarını senkronlar (tek işlem). Bağ yoksa, rapor dondurulmuşsa ya da bayat bağ
     * yoksa KİLİT ALINMAZ (inceleme 2026-11-20: donuk rapor her çıktıda kilitleniyordu). Yazılan bağ sayısını döner.
     */
    public int catchUp(Long reportId) {
        if (reportId == null) return 0;
        if (links.findByReportIdOrderByIdAsc(reportId).isEmpty()) return 0;
        return catchUp(reportRepo.findById(reportId).orElse(null));
    }

    /** {@link #catchUp(Long)} — raporu çağıran zaten okudu (çıktı uçları): dondurulmuşluk bu okunmuş satırdan, kilitsiz. */
    public int catchUp(TechErrorReport r) {
        if (r == null || r.getId() == null) return 0;
        List<TechErrorReportIncident> ls = links.findByReportIdOrderByIdAsc(r.getId());
        if (ls.isEmpty()) return 0;
        if (TechErrorIncidentLinks.frozen(r, () -> approvalFlow(r.getChannelId()), reports.now())) return 0;
        if (!anyStale(r, ls)) return 0;
        Long reportId = r.getId();
        Outcome o = inTx(() -> sync(reportId, Mode.CATCH_UP, null, null, null, null, false, null, null, null));
        return o == null ? 0 : o.written();
    }

    /** {@link #catchUp} — en iyi çaba (PDF / önizleme / onay / gönderimden önce): hata uyarıdır, çıktıyı engellemez. */
    public int catchUpQuietly(Long reportId) {
        try {
            return catchUp(reportId);
        } catch (RuntimeException e) {
            warn("Teknik hata raporu #" + reportId + " olay bağları güncellenemedi (çıktı eski değerlerle sürüyor): " + e);
            return 0;
        }
    }

    /** {@link #catchUpQuietly(Long)} — okunmuş raporla. */
    public int catchUpQuietly(TechErrorReport r) {
        try {
            return catchUp(r);
        } catch (RuntimeException e) {
            warn("Teknik hata raporu #" + (r == null ? null : r.getId())
                    + " olay bağları güncellenemedi (çıktı eski değerlerle sürüyor): " + e);
            return 0;
        }
    }

    /**
     * Kilitsiz ön denetim: bayat bağ var mı. Bayat = olayın {@code updated_at}'i bağdakinden farklı YA DA olayın eşleme
     * kaynağının izi ({@link TechErrorIncidentLinks#sourceFp}) bağdakinden farklı (aynı saniyedeki ikinci düzenleme; izi
     * olmayan eski bağda yalnız {@code updated_at}). Silinmiş olay ve raporun takımında OLMAYAN olay sayılmaz (o bağ hiç
     * yazılmaz). Kaynak izi için olaylar {@link #SOURCE_CHUNK}'lık gruplarla, işlem dışında okunur; ilk bayatta durur.
     */
    boolean anyStale(TechErrorReport r, List<TechErrorReportIncident> ls) {
        if (ls.isEmpty()) return false;
        Map<Long, IncidentRecordRepository.LinkState> states = new HashMap<>();
        for (var s : incidents.findLinkStates(incidentIds(ls))) states.put(s.getId(), s);
        List<TechErrorReportIncident> bySource = new ArrayList<>();
        for (TechErrorReportIncident l : ls) {
            IncidentRecordRepository.LinkState s = states.get(l.getIncidentId());
            if (s == null) continue;                                             // silinmiş olay: yazılacak bir şey yok
            if (!Objects.equals(s.getTeamId(), r.getTeamId())) continue;         // başka takımın olayı: bağ hiç yazılmaz
            if (!Objects.equals(s.getUpdatedAt(), l.getIncidentUpdatedAt())) return true;
            if (l.getSourceFp() != null) bySource.add(l);
        }
        for (int from = 0; from < bySource.size(); from += SOURCE_CHUNK) {
            List<TechErrorReportIncident> chunk = bySource.subList(from, Math.min(bySource.size(), from + SOURCE_CHUNK));
            Map<Long, IncidentRecord> incs = new HashMap<>();
            for (IncidentRecord e : incidents.findAllById(incidentIds(chunk))) incs.put(e.getId(), e);
            for (TechErrorReportIncident l : chunk) {
                IncidentRecord e = incs.get(l.getIncidentId());
                if (e == null || !Objects.equals(e.getTeamId(), r.getTeamId())) continue;
                if (!l.getSourceFp().equals(sourceFp(TechErrorIncidentMapper.Source.of(e)))) return true;
            }
        }
        return false;
    }

    /** Kanalın onay akışı var mı (yönetici alıcısı tanımlı); kanal okunamazsa temkinli: var. */
    private boolean approvalFlow(Long channelId) {
        try {
            return TechErrorApprovalService.approvalRequired(reports.channel(channelId));
        } catch (RuntimeException e) {
            return true;
        }
    }

    // ── Açık yenileme ve bağı koparma ────────────────────────────────────────────────────────────────

    /**
     * Bir bağı açıkça yeniler: {@code unpin} grupları (ya da {@code all}) sabitlemeden çıkarılır, bağ DONDURULMUŞ raporda bile
     * senkronlanır. Sürüm denetimi (eskiyse 409). Bağ yoksa 404 {@code INCIDENT_LINK_NOT_FOUND}.
     */
    public Outcome refresh(Long reportId, Long expectedVersion, String sectionId, String issueId, Object unpinSpec, Long by,
                           BiPredicate<Long, Long> canRead) {
        Set<String> unpin = new LinkedHashSet<>();
        boolean all = parseUnpin(unpinSpec, unpin);
        return inTx(() -> sync(reportId, Mode.REFRESH, null, sectionId, issueId, unpin, all, expectedVersion, by, canRead));
    }

    /** {@code unpin}: {@code "all"} ya da grup listesi; başka her şey 400 {@code unpin}. Dönüş: hepsi mi. */
    static boolean parseUnpin(Object spec, Set<String> out) {
        if (spec == null) return false;
        if (spec instanceof String s) {
            if ("all".equals(s.trim())) return true;
            throw badUnpin();
        }
        if (!(spec instanceof List<?> list) || list.size() > GROUPS.size() * 2) throw badUnpin();
        for (Object o : list) {
            if (!(o instanceof String g) || !GROUPS.contains(g)) throw badUnpin();
            out.add(g);
        }
        return false;
    }

    private static FieldValidationException badUnpin() {
        return new FieldValidationException("unpin", Msg.t(
                "Geri döndürülecek alanlar okunamadı: \"all\" ya da alan grupları (topic, description, date, time, errors, outage, impact) gönderin.",
                "The fields to restore couldn't be read: send \"all\" or a list of field groups (topic, description, date, time, errors, outage, impact)."));
    }

    /**
     * Bağı koparır: konu sıradan bir konu olarak kalır; içerik değişmediği için içerik sayacı ARTMAZ, sürüm artmaz. Geçmiş
     * olayı {@code INCIDENT_UNLINK}. Koparılan bağın olay kimliğini döner; bağ yoksa 404.
     */
    public Long unlink(Long reportId, String sectionId, String issueId, Long by) {
        return inTx(() -> {
            if (reportRepo.lockRow(reportId).isEmpty()) throw TechErrorReportService.reportNotFound();
            TechErrorReport r = reports.report(reportId);
            TechErrorReportIncident link = null;
            for (TechErrorReportIncident l : links.findByReportIdOrderByIdAsc(reportId)) {
                if (l.getSectionId().equals(sectionId) && l.getIssueId().equals(issueId)) link = l;
            }
            if (link == null) throw linkNotFound();
            links.deleteByIds(List.of(link.getId()));
            Template t = TechErrorTemplate.fromStored(r.getTemplateJson());
            Values v = TechErrorValues.fromStored(r.getValuesJson(), t);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("incident_id", link.getIncidentId());
            d.put("section_id", sectionId);
            d.put("issue_id", issueId);
            Issue i = TechErrorIncidentWriter.find(v, sectionId, issueId);
            if (i != null && i.topic() != null && !i.topic().isBlank()) d.put("topic", i.topic());
            reports.history().record(r, EV_UNLINK, by, null, null, d, reports.now());
            return link.getIncidentId();
        });
    }

    static CodedNotFoundException incidentHidden() {
        return new CodedNotFoundException("INCIDENT_NOT_FOUND", Msg.t(
                "Olay kaydı bulunamadı ya da görme yetkiniz yok; bu konu yenilenemez. Raporu yenileyin.",
                "The incident record wasn't found or you can't see it; this topic can't be refreshed. Reload the report."));
    }

    static CodedNotFoundException linkNotFound() {
        return new CodedNotFoundException("INCIDENT_LINK_NOT_FOUND", Msg.t(
                "Bu konunun olay kaydı bağı bulunamadı; bağ koparılmış ya da konu silinmiş olabilir. Raporu yenileyin.",
                "This topic's incident record link wasn't found; it may have been unlinked or the topic deleted. Reload the report."));
    }

    // ── Senkron (işlem İÇİNDE çağrılır) ───────────────────────────────────────────────────────────────

    /** Senkron sonucu: yazılan bağ sayısı, rapor dondurulmuş muydu, rapor bulundu mu. */
    public record Outcome(int written, boolean frozen, boolean found) {
        static final Outcome MISSING = new Outcome(0, false, false);
    }

    private record LinkWrite(TechErrorReportIncident link, Set<String> written, LinkedHashSet<String> pinned,
                             LinkedHashSet<String> notes, String incidentUpdatedAt, String sourceFp, Set<String> unpinned) { }

    /**
     * Raporun satır kilidi altında senkron. {@code AUTO}: yalnız {@code onlyIncident}'in bayat bağı; {@code CATCH_UP}: tüm bayat
     * bağlar; {@code REFRESH}: {@code sectionId}/{@code issueId} bağı (bayat olmasa da, dondurulmuş raporda da).
     */
    Outcome sync(Long reportId, Mode mode, Long onlyIncident, String sectionId, String issueId, Set<String> unpin,
                 boolean unpinAll, Long expectedVersion, Long by, BiPredicate<Long, Long> canRead) {
        if (reportRepo.lockRow(reportId).isEmpty()) {
            if (mode == Mode.REFRESH) throw TechErrorReportService.reportNotFound();
            return Outcome.MISSING;
        }
        TechErrorReport r = reportRepo.findById(reportId).orElse(null);
        if (r == null) {
            if (mode == Mode.REFRESH) throw TechErrorReportService.reportNotFound();
            return Outcome.MISSING;
        }
        if (mode == Mode.REFRESH) {
            if (expectedVersion == null) {
                throw new FieldValidationException("version", Msg.t(
                        "Raporun okunan sürümü gönderilmedi. Sayfayı yenileyip tekrar deneyin.",
                        "The report version you read wasn't sent. Reload the page and try again."));
            }
            if (!expectedVersion.equals(r.getVersion())) throw new OptimisticLockingFailureException("tech_error_report version");
        }
        Template t = TechErrorTemplate.fromStored(r.getTemplateJson());
        YearMonth period = YearMonth.of(r.getReportYear(), r.getReportMonth());
        Values v = TechErrorValues.fromStored(r.getValuesJson(), t);
        List<TechErrorReportIncident> kept = hooks.reconcile(links.findByReportIdOrderByIdAsc(reportId), t, v);
        Instant now = reports.now();
        Long channelId = r.getChannelId();
        boolean frozen = TechErrorIncidentLinks.frozen(r, () -> approvalFlow(channelId), now);

        List<TechErrorReportIncident> targets = new ArrayList<>();
        for (TechErrorReportIncident l : kept) {
            switch (mode) {
                case AUTO -> { if (l.getIncidentId().equals(onlyIncident)) targets.add(l); }
                case CATCH_UP -> targets.add(l);
                case REFRESH -> { if (l.getSectionId().equals(sectionId) && l.getIssueId().equals(issueId)) targets.add(l); }
            }
        }
        if (mode == Mode.REFRESH && targets.isEmpty()) throw linkNotFound();
        if (mode != Mode.REFRESH && frozen) return new Outcome(0, true, true);
        if (targets.isEmpty()) return new Outcome(0, frozen, true);

        Map<Long, IncidentRecord> incs = new HashMap<>();
        for (IncidentRecord e : incidents.findAllById(incidentIds(targets))) incs.put(e.getId(), e);
        if (mode == Mode.REFRESH) {
            // Açık yenileme olayın değerlerini bakanın eliyle yazar: olay var, HÂLÂ raporun takımında ve bakan okuyabilmeli —
            // silinmiş, başka takıma geçmiş ve görülemeyen olay AYNI yanıtı alır (404 INCIDENT_NOT_FOUND)
            IncidentRecord e = incs.get(targets.get(0).getIncidentId());
            if (e == null || !Objects.equals(e.getTeamId(), r.getTeamId())
                    || (canRead != null && !canRead.test(e.getTeamId(), e.getCreatedByTeamId()))) throw incidentHidden();
        }
        Map<String, List<Issue>> working = TechErrorIncidentWriter.mutableIssues(v);
        List<LinkWrite> writes = new ArrayList<>();
        for (TechErrorReportIncident link : targets) {
            IncidentRecord inc = incs.get(link.getIncidentId());
            if (inc == null) continue;                                       // olay silinmiş: bağ "deleted" okunur
            // Olay artık raporun takımında değil (transfer, olayın takımı değişti, rapor başka takıma taşındı): HİÇBİR kipte
            // yazılmaz — başka takımın düzenlemesi bu takımın raporuna akmaz; bağ "hidden" okunur, yetişme onu bayat saymaz
            if (!Objects.equals(inc.getTeamId(), r.getTeamId())) continue;
            // Yetişme yalnız BAYAT bağı işler: olayın updated_at'i ya da eşleme kaynağının izi değişti (updated_at SANİYE
            // çözünürlüklü — aynı saniyedeki ikinci düzenleme yalnız kaynak izinden görünür). Olayla gelen senkron (AUTO)
            // bayat görünmese de işler. Yazım yine yalnız izi değişen gruba yapılır.
            String srcFp = sourceFp(TechErrorIncidentMapper.Source.of(inc));
            boolean stale = !Objects.equals(inc.getUpdatedAt(), link.getIncidentUpdatedAt())
                    || (link.getSourceFp() != null && !link.getSourceFp().equals(srcFp));
            if (mode == Mode.CATCH_UP && !stale) continue;
            Section s = t.section(link.getSectionId());
            List<Issue> list = working.get(link.getSectionId());
            int idx = indexOf(list, link.getIssueId());
            if (s == null || idx < 0) continue;                              // reconcile silerdi; savunma
            LinkedHashSet<String> pinned = parseCsv(link.getPinned(), GROUPS);
            Set<String> unpinned = new LinkedHashSet<>();
            if (mode == Mode.REFRESH) {
                for (String g : GROUPS) {
                    if ((unpinAll || unpin.contains(g)) && pinned.remove(g)) unpinned.add(g);
                }
            }
            Issue cur = list.get(idx);
            TechErrorIncidentMapper.Result m = TechErrorIncidentMapper.map(TechErrorIncidentMapper.Source.of(inc), s, t, period,
                    parseCsv(link.getParts(), PARTS), cur.id());
            LinkedHashSet<String> notes = new LinkedHashSet<>(m.notes());
            Set<String> want = new LinkedHashSet<>();
            for (String g : GROUPS) {
                if (pinned.contains(g)) continue;
                if (!fingerprint(g, m.issue()).equals(fingerprint(g, cur))) want.add(g);
            }
            // Olay ayın dışına taşındı ve konuda tarih var: tarih (ve saati) korunur — boş tarih yazılmaz, sıkıştırılmaz
            if (m.dateOutsideMonth() && cur.date() != null && want.contains(G_DATE)) {
                want.remove(G_DATE);
                want.remove(G_TIME);
                notes.add(N_VALUE_REFUSED);
            }
            TechErrorIncidentWriter.Applied a = TechErrorIncidentWriter.apply(t, s, period, cur, m.issue(), want);
            if (!a.refused().isEmpty()) notes.add(N_VALUE_REFUSED);
            list.set(idx, a.issue());
            writes.add(new LinkWrite(link, new LinkedHashSet<>(a.written()), pinned, notes, inc.getUpdatedAt(), srcFp, unpinned));
        }

        Values after = v;
        int written = 0;
        boolean anyWritten = writes.stream().anyMatch(w -> !w.written().isEmpty());
        if (anyWritten) {
            after = compute(r, t, v, working, writes);
            String json = TechErrorValues.toJson(after);
            if (json.length() > TechErrorValues.MAX_JSON_CHARS) {
                // Rapor içeriği sınırı aşar: önce yazılan açıklamalar geri alınır, yine aşarsa hiçbir şey yazılmaz
                revertGroup(writes, working, v, G_DESCRIPTION);
                after = compute(r, t, v, working, writes);
                json = TechErrorValues.toJson(after);
                if (json.length() > TechErrorValues.MAX_JSON_CHARS) {
                    for (LinkWrite w : writes) {
                        if (w.written().isEmpty()) continue;
                        revertGroup(List.of(w), working, v, null);
                    }
                    after = v;
                    json = r.getValuesJson();
                }
            }
            if (!Objects.equals(json, r.getValuesJson())) {
                r.setValuesJson(json);
                // "Son düzenleyen" bir KİŞİNİN son düzenlemesidir (inceleme 2026-11-20): kendiliğinden senkron (AUTO /
                // CATCH_UP, kişi yok) updated_at / updated_by_id'e dokunmaz — "X, <senkron saati>'nde düzenledi" yanlış olurdu.
                // Değişikliğin işareti içerik sayacı (touchContent) + content_changed_at; geçmişte yapan "SiteMonitor".
                if (by != null) {
                    r.setUpdatedAt(now);
                    r.setUpdatedById(by);
                }
                r.setContentChangedAt(now);
                TechErrorReport saved = reportRepo.saveAndFlush(r);
                reportRepo.touchContent(saved.getId(), now);
                TechErrorChannel ch = reports.channel(saved.getChannelId());
                Map<String, Object> detail = reports.history().changesDetail(saved, t, v, after, ch.getName());
                List<Long> ids = new ArrayList<>();
                List<String> unpinnedAll = new ArrayList<>();
                for (LinkWrite w : writes) {
                    if (w.written().isEmpty()) continue;
                    ids.add(w.link().getIncidentId());
                    unpinnedAll.addAll(w.unpinned());
                }
                detail.put("incident_ids", ids);
                if (ids.size() == 1) detail.put("incident_id", ids.get(0));
                if (mode == Mode.REFRESH) detail.put("refresh", true);
                if (!unpinnedAll.isEmpty()) detail.put("unpinned", unpinnedAll);
                reports.history().record(saved, EV_SYNC, by, by == null ? ACTOR : null, saved.getVersion(), detail, now);
                reports.analyticsChanged();
                for (LinkWrite w : writes) if (!w.written().isEmpty()) written++;
            }
        }

        // Bağ satırları: sabitlenmemiş HER grubun izi saklı değere eşitlenir (yazılan, geri dönen ve aynı kalan); olay zamanı
        List<TechErrorReportIncident> save = new ArrayList<>();
        for (LinkWrite w : writes) {
            TechErrorReportIncident l = w.link();
            Issue stored = TechErrorIncidentWriter.find(after, l.getSectionId(), l.getIssueId());
            Map<String, String> fp = parseFp(l.getFieldsFp());
            for (String g : GROUPS) {
                if (w.pinned().contains(g)) continue;
                fp.put(g, fingerprint(g, stored));
            }
            l.setFieldsFp(fpJson(fp));
            l.setPinned(csv(w.pinned(), GROUPS));
            l.setIncidentUpdatedAt(w.incidentUpdatedAt());
            l.setSourceFp(w.sourceFp());
            l.setSyncedAt(now);
            l.setSyncNotes(notesCsv(w.notes()));
            save.add(l);
        }
        if (!save.isEmpty()) links.saveAll(save);
        return new Outcome(written, frozen, true);
    }

    /** Konu listeleri + düşen kısaltmalar → değer kaydının hesabı ({@link TechErrorCalc}, geçen ayın raporuyla). */
    private Values compute(TechErrorReport r, Template t, Values v, Map<String, List<Issue>> working, List<LinkWrite> writes) {
        List<String> dropped = new ArrayList<>();
        for (LinkWrite w : writes) {
            dropped.addAll(TechErrorIncidentWriter.unitKeysFor(w.link().getSectionId(), w.link().getIssueId(), w.written()));
        }
        Values merged = TechErrorIncidentWriter.withIssues(v, working, dropped);
        return TechErrorCalc.apply(t, merged, reports.previousFields(r, t));
    }

    /** Yazılan grubu ({@code group}; null = hepsi) geri alır: konu o grupta eski değerine döner, {@code value_refused}. */
    private static void revertGroup(List<LinkWrite> writes, Map<String, List<Issue>> working, Values v, String group) {
        for (LinkWrite w : writes) {
            Set<String> undo = new LinkedHashSet<>();
            for (String g : w.written()) if (group == null || group.equals(g)) undo.add(g);
            if (undo.isEmpty()) continue;
            List<Issue> list = working.get(w.link().getSectionId());
            int idx = indexOf(list, w.link().getIssueId());
            Issue old = TechErrorIncidentWriter.find(v, w.link().getSectionId(), w.link().getIssueId());
            if (idx < 0 || old == null) continue;
            Issue now = list.get(idx);
            for (String g : undo) now = withGroup(now, g, old, null);
            // Başlık: konu geri alınırsa eski başlık da döner
            if (undo.contains(G_TOPIC)) {
                now = new Issue(now.id(), old.topic(), old.title(), old.titleAuto(), now.description(), now.customers(), now.errors(),
                        now.date(), now.start(), now.end(), now.endNextDay(), now.outage(), now.outageId(), now.impact(),
                        now.captions(), now.share());
            }
            list.set(idx, now);
            w.written().removeAll(undo);
            w.notes().add(N_VALUE_REFUSED);
        }
    }

    // ── Rapor ekranı: bağ durumları ──────────────────────────────────────────────────────────────────

    /**
     * Raporun {@code incident_links} listesi — bağlı olayların TEK izdüşüm sorgusuyla (TEXT yok). {@code canRead(teamId,
     * createdByTeamId)}: bakan olay kaydını okuyabilir mi (okuyamıyorsa durum {@code hidden}, başlık / durum / tarih null).
     */
    public List<Map<String, Object>> linksDto(TechErrorReport r, Instant now, BiPredicate<Long, Long> canRead) {
        List<TechErrorReportIncident> ls = links.findByReportIdOrderByIdAsc(r.getId());
        List<Map<String, Object>> out = new ArrayList<>();
        if (ls.isEmpty()) return out;
        Map<Long, IncidentRecordRepository.LinkState> states = new HashMap<>();
        for (var s : incidents.findLinkStates(incidentIds(ls))) states.put(s.getId(), s);
        Long channelId = r.getChannelId();
        boolean frozen = TechErrorIncidentLinks.frozen(r, () -> approvalFlow(channelId), now);
        for (TechErrorReportIncident l : ls) {
            IncidentRecordRepository.LinkState s = states.get(l.getIncidentId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("section_id", l.getSectionId());
            m.put("issue_id", l.getIssueId());
            m.put("incident_id", l.getIncidentId());
            String state;
            // Başka takıma geçmiş olay (transfer / takım değişikliği / rapor taşındı) da "hidden": bağ artık izlemez
            boolean readable = s != null && Objects.equals(s.getTeamId(), r.getTeamId())
                    && canRead.test(s.getTeamId(), s.getCreatedByTeamId());
            if (s == null) state = S_DELETED;
            else if (!readable) state = S_HIDDEN;
            else if (!Objects.equals(s.getUpdatedAt(), l.getIncidentUpdatedAt())) state = frozen ? S_FROZEN_STALE : S_STALE;
            else state = S_OK;
            m.put("state", state);
            m.put("title", readable ? s.getTitle() : null);
            m.put("status", readable ? s.getStatus() : null);
            m.put("occurred_at", readable ? s.getOccurredAt() : null);
            m.put("pinned", new ArrayList<>(parseCsv(l.getPinned(), GROUPS)));
            m.put("notes", new ArrayList<>(parseCsv(l.getSyncNotes(), NOTE_CODES)));
            m.put("synced_at", l.getSyncedAt() == null ? null : l.getSyncedAt().toString());
            m.put("incident_updated_at", l.getIncidentUpdatedAt());
            out.add(m);
        }
        return out;
    }

    /** "Kullanıldığı raporlar"da konu adı için bir sorguda okunan en çok rapor (şablon + değer metniyle). */
    static final int USAGE_CONTENT_CHUNK = 20;

    /**
     * Olayın kullanıldığı raporlar (en çok {@link #MAX_USAGE_ROWS}); yalnız bakanın OKUYABİLDİĞİ raporlar
     * ({@code canReadReport(teamId)}). Olayın kendisi okunabilir olmalı (çağıran denetler). Raporlar METİNSİZ durum satırı
     * izdüşümüyle tek sorguda okunur ve önce süzülür (inceleme 2026-11-20: okunamayan raporun içeriği yüklenmez); konu adı
     * yalnız okunabilen raporların şablon + değer metninden, {@link #USAGE_CONTENT_CHUNK}'lık gruplarla.
     * {@code state}: {@code ok} | {@code stale} | {@code frozen_stale} | {@code hidden} (olay artık raporun takımında değil).
     */
    public List<Map<String, Object>> usage(IncidentRecord inc, Predicate<Long> canReadReport, java.util.function.Function<Long, String> teamName) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<TechErrorReportIncident> ls = links.findByIncident(inc.getId(), PageRequest.of(0, MAX_USAGE_ROWS));
        if (ls.isEmpty()) return out;
        Set<Long> reportIds = new LinkedHashSet<>();
        for (TechErrorReportIncident l : ls) reportIds.add(l.getReportId());
        Map<Long, TechErrorReportRepository.Row> rows = new HashMap<>();
        for (TechErrorReportRepository.Row row : reportRepo.findByIdIn(reportIds)) rows.put(row.getId(), row);
        List<TechErrorReportIncident> readable = new ArrayList<>();
        for (TechErrorReportIncident l : ls) {
            TechErrorReportRepository.Row row = rows.get(l.getReportId());
            if (row != null && canReadReport.test(row.getTeamId())) readable.add(l);
        }
        if (readable.isEmpty()) return out;
        Map<Long, String> topics = usageTopics(readable);
        Instant now = reports.now();
        Map<Long, TechErrorChannel> chans = new HashMap<>();
        for (TechErrorReportIncident l : readable) {
            TechErrorReportRepository.Row row = rows.get(l.getReportId());
            Long chId = row.getChannelId();
            if (!chans.containsKey(chId)) {
                TechErrorChannel found;
                try {
                    found = reports.channel(chId);
                } catch (RuntimeException e) {
                    found = null;
                }
                chans.put(chId, found);
            }
            TechErrorChannel ch = chans.get(chId);
            boolean frozen = TechErrorIncidentLinks.frozen(row, () -> ch == null || TechErrorApprovalService.approvalRequired(ch), now);
            String state = !Objects.equals(inc.getTeamId(), row.getTeamId()) ? S_HIDDEN
                    : !Objects.equals(inc.getUpdatedAt(), l.getIncidentUpdatedAt()) ? (frozen ? S_FROZEN_STALE : S_STALE) : S_OK;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("report_id", row.getId());
            m.put("channel_id", chId);
            m.put("channel_name", ch == null ? null : ch.getName());
            m.put("team_name", teamName.apply(row.getTeamId()));
            m.put("year", row.getReportYear());
            m.put("month", row.getReportMonth());
            m.put("status", TechErrorReportService.reportStatus(row, now));
            m.put("topic", topics.get(l.getId()));
            m.put("state", state);
            out.add(m);
        }
        return out;
    }

    /** Bağ kimliği → konunun adı (yalnız verilen — okunabilen — bağların raporları; gruplar halinde, metinler hemen bırakılır). */
    private Map<Long, String> usageTopics(List<TechErrorReportIncident> readable) {
        Map<Long, String> topics = new HashMap<>();
        Map<Long, List<TechErrorReportIncident>> byReport = new LinkedHashMap<>();
        for (TechErrorReportIncident l : readable) byReport.computeIfAbsent(l.getReportId(), k -> new ArrayList<>()).add(l);
        List<Long> ids = new ArrayList<>(byReport.keySet());
        for (int from = 0; from < ids.size(); from += USAGE_CONTENT_CHUNK) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + USAGE_CONTENT_CHUNK));
            for (TechErrorReportRepository.ContentRow c : reportRepo.findContentByIdIn(chunk)) {
                Template t = TechErrorTemplate.fromStored(c.getTemplateJson());
                Values v = TechErrorValues.fromStored(c.getValuesJson(), t);
                for (TechErrorReportIncident l : byReport.getOrDefault(c.getId(), List.of())) {
                    Issue i = TechErrorIncidentWriter.find(v, l.getSectionId(), l.getIssueId());
                    if (i != null) topics.put(l.getId(), i.topic());
                }
            }
        }
        return topics;
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────────

    static List<Long> incidentIds(List<TechErrorReportIncident> ls) {
        Set<Long> ids = new LinkedHashSet<>();
        for (TechErrorReportIncident l : ls) ids.add(l.getIncidentId());
        return new ArrayList<>(ids);
    }

    static int indexOf(List<Issue> list, String issueId) {
        if (list == null) return -1;
        for (int k = 0; k < list.size(); k++) if (list.get(k).id().equals(issueId)) return k;
        return -1;
    }

    /** Not kodları CSV'si, sütun sınırına (300) sığdırılır — sığmayan kod düşer (kodlar kısa, liste sınırlı). */
    static String notesCsv(Collection<String> notes) {
        String csv = csv(notes, NOTE_CODES);
        if (csv == null || csv.length() <= 300) return csv;
        int cut = csv.lastIndexOf(',', 300);
        return cut > 0 ? csv.substring(0, cut) : null;
    }

    private <T> T inTx(java.util.function.Supplier<T> work) {
        if (txManager == null) return work.get();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(s -> work.get());
    }

    private Executor executor() {
        Executor e = executor;
        if (e != null) return e;
        synchronized (this) {
            if (executor == null) {
                ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(QUEUE_CAPACITY), r -> {
                            Thread th = new Thread(r, "te-incident-sync");
                            th.setDaemon(true);
                            return th;
                        });
                ex.allowCoreThreadTimeOut(true);
                executor = ex;
            }
            return executor;
        }
    }

    /** Kapanış başladı: kuyruğa yeni iş alınmaz, süren döngü sonraki rapordan önce durur (bekleme yok, yalnız bayrak). */
    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        stopping = true;
    }

    /**
     * Kuyrukta kalan işler başlamaz; süren işlem kesilir (geri alınır — bağ bayat kalır, yetişme tamamlar: kaynak izi aynı
     * saniyedeki düzenlemeyi de yakalar). Bekleme yok. Düşen iş varsa SAYISI bir kez uyarılır (sessiz kayıp yok).
     */
    @PreDestroy
    public void destroy() {
        int dropped = stopAndCountDropped();
        if (dropped > 0) {
            log.warn("Kapanış: {} olay kaydı senkronu kuyrukta kaldı ve koşmadı — bağlı raporlar açılınca ya da çıktı alınırken"
                    + " yetişir", dropped);
        }
    }

    /** Durdurur; kapanışta koşmayan (kuyrukta kalan + kapanış başladıktan sonra çıkan) senkron sayısını döner. */
    int stopAndCountDropped() {
        stopping = true;
        Executor e = executor;
        int dropped = droppedAtShutdown.getAndSet(0);
        if (e instanceof ExecutorService es) dropped += es.shutdownNow().size();
        return dropped;
    }

    private void warn(String msg) {
        long now = System.currentTimeMillis();
        long last = lastWarn.get();
        if (now - last >= WARN_EVERY_MS && lastWarn.compareAndSet(last, now)) log.warn(msg);
        else log.debug(msg);
    }
}
