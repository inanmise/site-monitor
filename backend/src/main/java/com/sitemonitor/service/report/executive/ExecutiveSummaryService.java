package com.sitemonitor.service.report.executive;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.ExecutiveReportRow;
import com.sitemonitor.model.ExecutiveSummaryTeamReport;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.ExecutiveSummaryReportRepository;
import com.sitemonitor.repository.ExecutiveSummaryTeamReportRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.util.Msg;
import com.sitemonitor.util.TtlMemo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * AYLIK YÖNETİCİ ÖZETİ — hesap + bellek + gönderilmiş ayın kaydı (2026-10-10, kullanıcı isteği: "SLO uyumu, en gürültülü
 * alarmlar, yaklaşan sertifika bitişleri ve yenileme süresine uyum tek bir PDF ya da e-postada").
 *
 * <h2>Hesap</h2>
 * Spring'in bulduğu HER {@link ExecutiveSummarySection} {@link ExecutiveSummarySection#order()} sırasıyla çalışır; her
 * bölüm sabit sayıda toplu sorgu yapar, envanter + son sertifika durumu + takım adları bağlamda TEK kez okunur. Bir
 * bölümün hatası yalnız o bölümü "hesaplanamadı" yapar. Üst şerit: bölümlerin ilk hükmü + {@code headline_kpi}'si; genel
 * durum bölümlerin en kötüsü.
 *
 * <h2>Kapsam (2026-10-10, kullanıcı isteği: takıma özel özet)</h2>
 * {@code teamId == null} kurum geneli (bugünkü davranış), dolu = yalnız o takımın kayıtları
 * ({@link ExecutiveSummaryContext}). Takım kapsamı yetki DEĞİLDİR — kim hangi takımı görebilir kararı denetleyicidedir.
 * Aylık gönderim aynı ay için kurum + N takım hesaplar; {@link #newRunShared()} haritası bütün bağlamlara verilir ve
 * kurum geneli ham veri (envanter, son sertifikalar, ay sorguları) koşu başına bir kez okunur.
 *
 * <h2>Bellek</h2>
 * Ay × kapsam başına bellek ({@link TtlMemo}): biten ay {@value #PAST_TTL_MS} ms (veri değişmez; anlık bölümler için yine
 * de süreli), devam eden ay {@value #CURRENT_TTL_MS} ms. {@code fresh} belleği ay × kapsam başına en fazla
 * {@value #FRESH_MIN_MS} ms'de bir atlar. Gönderilmiş bir ay (SENT/PARTIAL) varsayılan olarak GÖNDERİLEN içerikten
 * gösterilir ({@code source=snapshot}) — ekrandaki sayılar postadakilerle aynı; {@code live=true} canlı yeniden hesaplar.
 */
@Slf4j
@Service
public class ExecutiveSummaryService {

    static final long PAST_TTL_MS = 6L * 60 * 60 * 1000;
    static final long CURRENT_TTL_MS = 10L * 60 * 1000;
    static final long FRESH_MIN_MS = 30_000;
    /** Seçilebilen en eski ay (bugünden geriye). */
    public static final int MAX_MONTHS_BACK = 24;
    /** Bellek kapasitesi: ay × kapsam (kurum + takımlar). */
    static final int MEMO_CAPACITY = 120;

    static final String INVENTORY_SQL = "SELECT domain, team_id, ug_team_id, tier, group_name, renewal_planned_at "
            + "FROM certificate_inventory WHERE active = true AND deleted_at IS NULL";

    private final List<ExecutiveSummarySection> sections;
    private final ExecutiveSummarySettings settings;
    private final CertificateService certificateService;
    private final TeamRepository teamRepo;
    private final JdbcTemplate jdbc;
    private final ExecutiveSummaryReportRepository reportRepo;

    /** Takım kayıtları (birim testlerinde yoksa takım ekranı yalnız canlı hesap gösterir). */
    @Autowired(required = false)
    private ExecutiveSummaryTeamReportRepository teamReportRepo;

    private final TtlMemo<ExecutiveSummary> memo = new TtlMemo<>(MEMO_CAPACITY);
    private final Map<String, Long> lastFresh = new ConcurrentHashMap<>();
    private Supplier<Instant> clock = Instant::now;

    static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public ExecutiveSummaryService(List<ExecutiveSummarySection> sections, ExecutiveSummarySettings settings,
                                   CertificateService certificateService, TeamRepository teamRepo, JdbcTemplate jdbc,
                                   ExecutiveSummaryReportRepository reportRepo) {
        List<ExecutiveSummarySection> sorted = new ArrayList<>(sections == null ? List.of() : sections);
        sorted.sort(Comparator.comparingInt(ExecutiveSummarySection::order).thenComparing(ExecutiveSummarySection::key));
        this.sections = List.copyOf(sorted);
        this.settings = settings;
        this.certificateService = certificateService;
        this.teamRepo = teamRepo;
        this.jdbc = jdbc;
        this.reportRepo = reportRepo;
    }

    /** Test kancası: saat. */
    void setClock(Supplier<Instant> clock) { this.clock = clock; }

    /** Test kancası: takım kayıtları. */
    void setTeamReportRepo(ExecutiveSummaryTeamReportRepository r) { this.teamReportRepo = r; }

    public Instant now() { return clock.get(); }

    public YearMonth currentMonth() { return YearMonth.from(now().atZone(ExecutiveSummaryContext.IST)); }

    /** Varsayılan ay: son TAMAMLANMIŞ ay (ayın 1'inde gönderilen raporun kapsadığı ay). */
    public YearMonth defaultMonth() { return currentMonth().minusMonths(1); }

    /**
     * {@code YYYY-MM} ayrıştırır; boşsa varsayılan ay. Gelecek ay ya da {@value #MAX_MONTHS_BACK} aydan eskisi 400
     * {@code VALIDATION_FAILED} ({@code field=month}).
     */
    public YearMonth parseMonth(String raw) {
        if (raw == null || raw.isBlank()) return defaultMonth();
        YearMonth m;
        try {
            m = YearMonth.parse(raw.trim());
        } catch (Exception e) {
            throw new FieldValidationException("month", Msg.t(
                    "Ay biçimi geçersiz. YYYY-AA biçiminde bir ay seçin (ör. 2026-09).",
                    "The month format is invalid. Choose a month in YYYY-MM format (for example 2026-09)."));
        }
        YearMonth cur = currentMonth();
        if (m.isAfter(cur) || m.isBefore(cur.minusMonths(MAX_MONTHS_BACK))) {
            throw new FieldValidationException("month", Msg.t(
                    "Seçilen ay aralık dışında. Bu ay ile son " + MAX_MONTHS_BACK + " ay arasından bir ay seçin.",
                    "The selected month is out of range. Choose this month or one of the last " + MAX_MONTHS_BACK + " months."));
        }
        return m;
    }

    /** Kurum geneli ekran girişi (eski imza). */
    public ExecutiveSummary get(YearMonth month, boolean live, boolean fresh) {
        return get(month, null, live, fresh);
    }

    /**
     * Ekranın girişi. Gönderilmiş ay → kayıt (snapshot) ({@code live=false}); aksi bellekli canlı hesap.
     *
     * @param teamId null = kurum geneli; dolu = takım özeti (yetkiyi çağıran denetler)
     */
    public ExecutiveSummary get(YearMonth month, Long teamId, boolean live, boolean fresh) {
        if (!live && month.isBefore(currentMonth())) {
            ExecutiveSummary snap = snapshot(month, teamId);
            if (snap != null) return snap;
        }
        String key = month + "|" + (teamId == null ? "org" : "team:" + teamId);
        boolean past = month.isBefore(currentMonth());
        long ttl = past ? PAST_TTL_MS : CURRENT_TTL_MS;
        boolean doFresh = false;
        if (fresh) {
            long nowMs = now().toEpochMilli();
            Long prev = lastFresh.get(key);
            if (prev == null || nowMs - prev >= FRESH_MIN_MS) {
                lastFresh.put(key, nowMs);
                doFresh = true;
            }
        }
        return memo.get(key, ttl, doFresh, () -> compute(month, teamId, null));
    }

    /** Gönderilmiş kurum ayının kaydı (eski imza). */
    public ExecutiveSummary snapshot(YearMonth month) {
        return snapshot(month, null);
    }

    /** Gönderilmiş ayın kaydı (yoksa / okunamazsa null). */
    public ExecutiveSummary snapshot(YearMonth month, Long teamId) {
        try {
            ExecutiveReportRow r = teamId == null
                    ? reportRepo.findByReportYearAndReportMonth(month.getYear(), month.getMonthValue()).orElse(null)
                    : teamReportRepo == null ? null
                    : teamReportRepo.findByTeamIdAndReportYearAndReportMonth(teamId, month.getYear(), month.getMonthValue())
                            .orElse(null);
            if (r == null || r.getSummaryJson() == null || r.getSummaryJson().isBlank()) return null;
            if (!ExecutiveReportRow.SENT.equals(r.getStatus()) && !ExecutiveReportRow.PARTIAL.equals(r.getStatus())) {
                return null;
            }
            return JSON.readValue(r.getSummaryJson(), ExecutiveSummary.class).withSource(ExecutiveSummary.SOURCE_SNAPSHOT);
        } catch (Exception e) {
            log.warn("Yönetici özeti kaydı okunamadı ({} / {}): {}", month, teamId == null ? "kurum" : "takım " + teamId,
                    e.toString());
            return null;
        }
    }

    /** Belleksiz canlı kurum hesabı (eski imza). */
    public ExecutiveSummary compute(YearMonth month) {
        return compute(month, null, null);
    }

    /** Koşu boyu paylaşılan bellek — aynı ayın kurum + takım hesaplarına aynı harita verilir. */
    public Map<String, Object> newRunShared() {
        return new ConcurrentHashMap<>();
    }

    /**
     * Belleksiz canlı hesap — gönderim ve test bunu kullanır.
     *
     * @param teamId null = kurum geneli
     * @param shared koşu boyu paylaşılan bellek ({@link #newRunShared()}); null = bu hesaba özel
     */
    public ExecutiveSummary compute(YearMonth month, Long teamId, Map<String, Object> shared) {
        Instant now = now();
        Map<String, Object> sh = shared == null ? newRunShared() : shared;
        Supplier<List<CertificateDto>> latest = () -> ExecutiveSummaryContext.sharedValue(sh, "base.latest", this::loadLatest);
        Supplier<Map<String, ExecutiveSummaryContext.InventoryRow>> inv =
                () -> ExecutiveSummaryContext.sharedValue(sh, "base.inventory", this::loadInventory);
        Supplier<Map<Long, String>> names = () -> ExecutiveSummaryContext.sharedValue(sh, "base.teamNames", this::loadTeamNames);
        String teamName = teamId == null ? null : names.get().get(teamId);
        ExecutiveSummaryContext ctx = new ExecutiveSummaryContext(month, now, settings.availabilityTarget(),
                settings.renewalTargetDays(), latest, inv, names, teamId, teamName, sh);
        List<SectionResult> results = new ArrayList<>();
        for (ExecutiveSummarySection s : sections) {
            long t0 = System.nanoTime();
            try {
                SectionResult r = s.compute(ctx);
                results.add(r == null ? SectionResult.failed(s.key(), s.order(), s.title()) : r);
            } catch (Exception e) {
                log.warn("Yönetici özeti bölümü hesaplanamadı ({} / {} / {}): {}", s.key(), month,
                        teamId == null ? "kurum" : "takım " + teamId, e.toString(), e);
                results.add(SectionResult.failed(s.key(), s.order(), s.title()));
            }
            log.debug("Yönetici özeti bölümü {} ({} / {}): {} ms", s.key(), month, teamId == null ? "kurum" : teamId,
                    (System.nanoTime() - t0) / 1_000_000);
        }
        return assemble(ctx, results);
    }

    /** Bölümlerden özet: üst şerit hükümleri + göstergeleri + genel durum. Saf (birim testleri sınar). */
    static ExecutiveSummary assemble(ExecutiveSummaryContext ctx, List<SectionResult> results) {
        List<SectionResult.Verdict> headline = new ArrayList<>();
        List<ExecutiveSummary.HeadlineKpi> kpis = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        for (SectionResult r : results) {
            statuses.add(r.status());
            if (!r.verdicts().isEmpty()) headline.add(r.verdicts().get(0));
            if (r.headlineKpi() != null) {
                for (SectionResult.Kpi k : r.kpis()) {
                    if (r.headlineKpi().equals(k.code())) { kpis.add(new ExecutiveSummary.HeadlineKpi(r.key(), k)); break; }
                }
            }
        }
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("availability_target", ctx.availabilityTarget());
        st.put("renewal_target_days", ctx.renewalTargetDays());
        st.put("timezone", ExecutiveSummaryContext.IST.getId());
        return new ExecutiveSummary(ctx.month().toString(), ExecFormat.monthLabel(ctx.month()), ctx.fromIso(), ctx.toIso(),
                ctx.complete(), ctx.nowIso(), ExecutiveSummary.SOURCE_LIVE, SectionResult.worst(statuses), headline, kpis,
                results, st, ctx.teamScoped()
                        ? ExecutiveSummary.Scope.team(ctx.scopeTeamId(), ctx.scopeTeamName()) : ExecutiveSummary.Scope.ORG);
    }

    /** Seçilebilir kurum ayları (eski imza). */
    public List<Map<String, Object>> months() {
        return months(null);
    }

    /** Seçilebilir aylar: bu ay + son 12 ay; kapsamın gönderim durumuyla (tek sorgu). */
    public List<Map<String, Object>> months(Long teamId) {
        Map<String, ExecutiveReportRow> sent = new HashMap<>();
        try {
            List<? extends ExecutiveReportRow> rows = teamId == null
                    ? reportRepo.findAllByOrderByReportYearDescReportMonthDesc(PageRequest.of(0, 24))
                    : teamReportRepo == null ? List.<ExecutiveSummaryTeamReport>of()
                    : teamReportRepo.findByTeamIdOrderByReportYearDescReportMonthDesc(teamId, PageRequest.of(0, 24));
            for (ExecutiveReportRow r : rows) {
                if (r.getReportYear() != null && r.getReportMonth() != null) {
                    sent.put(YearMonth.of(r.getReportYear(), r.getReportMonth()).toString(), r);
                }
            }
        } catch (Exception e) {
            log.debug("Yönetici özeti geçmişi okunamadı: {}", e.toString());
        }
        YearMonth cur = currentMonth();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i <= 12; i++) {
            YearMonth m = cur.minusMonths(i);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("month", m.toString());
            o.put("label", ExecFormat.monthLabel(m));
            o.put("current", i == 0);
            ExecutiveReportRow r = sent.get(m.toString());
            o.put("status", r == null ? null : r.getStatus());
            o.put("sent_at", r == null ? null : r.getSentAt());
            out.add(o);
        }
        return out;
    }

    /** Sağlayıcı anahtarları (sıralı) — tanı/ekran. */
    public List<String> sectionKeys() {
        return sections.stream().map(ExecutiveSummarySection::key).toList();
    }

    /** Takım adları (kimlik → ad; tek sorgu). Denetleyici kapsam seçicisi ve takım varlık denetimi için. */
    public Map<Long, String> teamNames() {
        return loadTeamNames();
    }

    // ── Paylaşılan tembel veri (bağlam başına TEK okuma) ──

    private List<CertificateDto> loadLatest() {
        try {
            return certificateService.getAllLatest();
        } catch (Exception e) {
            log.warn("Yönetici özeti: sertifika durumu okunamadı: {}", e.toString());
            return List.of();
        }
    }

    private Map<String, ExecutiveSummaryContext.InventoryRow> loadInventory() {
        Map<String, ExecutiveSummaryContext.InventoryRow> out = new LinkedHashMap<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(INVENTORY_SQL)) {
                Object d = r.get("domain");
                if (d == null) continue;
                out.put(String.valueOf(d), new ExecutiveSummaryContext.InventoryRow(String.valueOf(d),
                        asLong(r.get("team_id")), asLong(r.get("ug_team_id")),
                        r.get("tier") instanceof Number n ? Integer.valueOf(n.intValue()) : null,
                        r.get("group_name") == null ? null : String.valueOf(r.get("group_name")),
                        r.get("renewal_planned_at") == null ? null : String.valueOf(r.get("renewal_planned_at"))));
            }
        } catch (Exception e) {
            log.warn("Yönetici özeti: envanter okunamadı: {}", e.toString());
        }
        return out;
    }

    private Map<Long, String> loadTeamNames() {
        Map<Long, String> out = new HashMap<>();
        try {
            for (Team t : teamRepo.findAll()) if (t.getId() != null) out.put(t.getId(), t.getName());
        } catch (Exception e) {
            log.debug("Yönetici özeti: takım adları okunamadı: {}", e.toString());
        }
        return out;
    }

    private static Long asLong(Object v) {
        return v instanceof Number n ? Long.valueOf(n.longValue()) : null;
    }

    /** Özet → JSON (kayıt için). */
    public static String toJson(ExecutiveSummary s) {
        try {
            return JSON.writeValueAsString(s);
        } catch (Exception e) {
            log.warn("Yönetici özeti JSON'a çevrilemedi: {}", e.toString());
            return null;
        }
    }
}
