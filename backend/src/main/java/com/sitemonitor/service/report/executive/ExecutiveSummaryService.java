package com.sitemonitor.service.report.executive;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.ExecutiveSummaryReport;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.ExecutiveSummaryReportRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.util.Msg;
import com.sitemonitor.util.TtlMemo;
import lombok.extern.slf4j.Slf4j;
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
 * <h2>Bellek</h2>
 * Ay başına bellek ({@link TtlMemo}): biten ay {@value #PAST_TTL_MS} ms (veri değişmez; anlık bölümler için yine de
 * süreli), devam eden ay {@value #CURRENT_TTL_MS} ms. {@code fresh} belleği ay başına en fazla {@value #FRESH_MIN_MS} ms'de
 * bir atlar. Gönderilmiş bir ay (SENT/PARTIAL) varsayılan olarak GÖNDERİLEN içerikten gösterilir ({@code source=snapshot})
 * — ekrandaki sayılar postadakilerle aynı; {@code live=true} canlı yeniden hesaplar.
 */
@Slf4j
@Service
public class ExecutiveSummaryService {

    static final long PAST_TTL_MS = 6L * 60 * 60 * 1000;
    static final long CURRENT_TTL_MS = 10L * 60 * 1000;
    static final long FRESH_MIN_MS = 30_000;
    /** Seçilebilen en eski ay (bugünden geriye). */
    public static final int MAX_MONTHS_BACK = 24;

    static final String INVENTORY_SQL = "SELECT domain, team_id, ug_team_id, tier, group_name, renewal_planned_at "
            + "FROM certificate_inventory WHERE active = true AND deleted_at IS NULL";

    private final List<ExecutiveSummarySection> sections;
    private final ExecutiveSummarySettings settings;
    private final CertificateService certificateService;
    private final TeamRepository teamRepo;
    private final JdbcTemplate jdbc;
    private final ExecutiveSummaryReportRepository reportRepo;

    private final TtlMemo<ExecutiveSummary> memo = new TtlMemo<>(40);
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

    /**
     * Ekranın girişi. Gönderilmiş ay → kayıt (snapshot) ({@code live=false}); aksi bellekli canlı hesap.
     */
    public ExecutiveSummary get(YearMonth month, boolean live, boolean fresh) {
        if (!live && month.isBefore(currentMonth())) {
            ExecutiveSummary snap = snapshot(month);
            if (snap != null) return snap;
        }
        String key = month.toString();
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
        return memo.get(key, ttl, doFresh, () -> compute(month));
    }

    /** Gönderilmiş ayın kaydı (yoksa / okunamazsa null). */
    public ExecutiveSummary snapshot(YearMonth month) {
        try {
            ExecutiveSummaryReport r = reportRepo.findByReportYearAndReportMonth(month.getYear(), month.getMonthValue())
                    .orElse(null);
            if (r == null || r.getSummaryJson() == null || r.getSummaryJson().isBlank()) return null;
            if (!ExecutiveSummaryReport.SENT.equals(r.getStatus()) && !ExecutiveSummaryReport.PARTIAL.equals(r.getStatus())) {
                return null;
            }
            return JSON.readValue(r.getSummaryJson(), ExecutiveSummary.class).withSource(ExecutiveSummary.SOURCE_SNAPSHOT);
        } catch (Exception e) {
            log.warn("Yönetici özeti kaydı okunamadı ({}): {}", month, e.toString());
            return null;
        }
    }

    /** Belleksiz canlı hesap — gönderim ve test bunu kullanır. */
    public ExecutiveSummary compute(YearMonth month) {
        Instant now = now();
        ExecutiveSummaryContext ctx = new ExecutiveSummaryContext(month, now, settings.availabilityTarget(),
                settings.renewalTargetDays(), this::loadLatest, this::loadInventory, this::loadTeamNames);
        List<SectionResult> results = new ArrayList<>();
        for (ExecutiveSummarySection s : sections) {
            long t0 = System.nanoTime();
            try {
                SectionResult r = s.compute(ctx);
                results.add(r == null ? SectionResult.failed(s.key(), s.order(), s.title()) : r);
            } catch (Exception e) {
                log.warn("Yönetici özeti bölümü hesaplanamadı ({} / {}): {}", s.key(), month, e.toString(), e);
                results.add(SectionResult.failed(s.key(), s.order(), s.title()));
            }
            log.debug("Yönetici özeti bölümü {} ({}): {} ms", s.key(), month, (System.nanoTime() - t0) / 1_000_000);
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
                results, st);
    }

    /** Seçilebilir aylar: bu ay + son 12 ay; gönderim durumuyla (tek sorgu). */
    public List<Map<String, Object>> months() {
        Map<String, ExecutiveSummaryReport> sent = new HashMap<>();
        try {
            for (ExecutiveSummaryReport r : reportRepo.findAllByOrderByReportYearDescReportMonthDesc(PageRequest.of(0, 24))) {
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
            ExecutiveSummaryReport r = sent.get(m.toString());
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
