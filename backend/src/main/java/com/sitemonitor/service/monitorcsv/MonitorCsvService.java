package com.sitemonitor.service.monitorcsv;

import com.sitemonitor.controller.SessionScope;
import com.sitemonitor.model.DbConnectionProfile;
import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.model.MonitorAlertPrefs;
import com.sitemonitor.model.NocNotificationGroup;
import com.sitemonitor.model.NotificationGroup;
import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.DbConnectionProfileRepository;
import com.sitemonitor.repository.DbQueryMonitorRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.NocNotificationGroupRepository;
import com.sitemonitor.repository.NotificationGroupRepository;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.repository.PageSpeedMonitorRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.PingCheckerService;
import com.sitemonitor.service.dbquery.DbQuerySqlGuard;
import com.sitemonitor.service.dbquery.DbType;
import com.sitemonitor.service.monitorcsv.MonitorCsvSpec.Column;
import com.sitemonitor.service.monitorcsv.MonitorCsvSpec.Kind;
import com.sitemonitor.service.monitorcsv.MonitorCsvSpec.TypeSpec;
import com.sitemonitor.util.MonitorUrls;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;

import java.text.Collator;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * İzleme CSV dışa / içe aktarımı (2026-11-19, kullanıcı isteği — UAT'tan prod'a taşıma ve toplu kurulum).
 *
 * <p><b>Dışa aktarım:</b> türün oturumun GÖRDÜĞÜ izlemeleri ({@link SessionScope#canViewMonitoring}, türün liste ucuyla aynı
 * görünürlük; Port / DNS'te yalnız bağımsız satırlar — envanterden türeyen satırları envanter yönetir) sabit sütunlarla
 * ({@link MonitorCsvSpec}). Takım / bildirim grubu / 7/24 grupları / dbquery bağlantısı ADLA yazılır (UAT ile prod kimlikleri
 * farklı). Sır yazılmaz.
 *
 * <p><b>İçe aktarım:</b> çakışma anahtarı = AYNI TAKIM + AYNI AD (büyük / küçük harf duyarsız) → mevcut izleme GÜNCELLENİR,
 * yoksa OLUŞTURULUR. Takımda o adla birden fazla izleme → satır hatası (tahmin yok). dbquery adları tüm takımlarda tekildir:
 * başka takımda aynı ad → satır hatası. Boş hücre = "değiştirme" (yeni kayıtta varsayılan). Önizleme (dry run) HİÇBİR ŞEY
 * yazmaz: ucuz denetimleri yapar (zorunlu sütun, biçim, ad çözümü, takım yetkisi, URL, aralık, dbquery SQL güvenlik
 * denetimi) ve satır başına "oluşturulacak / güncellenecek (değişen alanlar) / değişiklik yok / hata" der. Gerçek koşum
 * satırları sırayla izleme uçlarından ({@link MonitorCsvWriter}) geçirir; güncelleme gövdesi YALNIZ farklı alanları taşır.
 * Önizlemenin kabul ettiği satır gerçek koşumda yine reddedilebilir (ör. mükerrer URL) — satır sonucu bunu söyler.
 */
@Service
public class MonitorCsvService {

    /** Dışa aktarımın satır sınırı. */
    public static final int EXPORT_MAX_ROWS = 5000;
    /** Gerçek koşumun zaman bütçesi — dolunca kalan satırlar "atlandı (süre)" ile döner. */
    public static final long IMPORT_BUDGET_MS = 10 * 60_000L;

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    private static final Set<String> DNS_RECORD_TYPES = Set.of("A", "AAAA", "CNAME", "MX", "TXT", "NS");

    private final Map<String, JpaRepository<?, Long>> repos = new HashMap<>();
    private final TeamRepository teamRepo;
    private final NotificationGroupRepository notificationGroupRepo;
    private final NocNotificationGroupRepository nocGroupRepo;
    private final DbConnectionProfileRepository connectionRepo;
    private final MonitorCsvWriter writer;
    private final LongSupplier clock;

    @Autowired
    public MonitorCsvService(HttpMonitorRepository http, KeywordMonitorRepository keyword, PageMonitorRepository page,
                             PageSpeedMonitorRepository pageSpeed, PingMonitorRepository ping, PortMonitorRepository port,
                             DnsMonitorRepository dns, DomainMonitorRepository domain, ScriptedMonitorRepository scripted,
                             DbQueryMonitorRepository dbQuery, TeamRepository teamRepo,
                             NotificationGroupRepository notificationGroupRepo, NocNotificationGroupRepository nocGroupRepo,
                             DbConnectionProfileRepository connectionRepo, MonitorCsvWriter writer) {
        this(http, keyword, page, pageSpeed, ping, port, dns, domain, scripted, dbQuery, teamRepo, notificationGroupRepo,
                nocGroupRepo, connectionRepo, writer, System::currentTimeMillis);
    }

    MonitorCsvService(HttpMonitorRepository http, KeywordMonitorRepository keyword, PageMonitorRepository page,
                      PageSpeedMonitorRepository pageSpeed, PingMonitorRepository ping, PortMonitorRepository port,
                      DnsMonitorRepository dns, DomainMonitorRepository domain, ScriptedMonitorRepository scripted,
                      DbQueryMonitorRepository dbQuery, TeamRepository teamRepo,
                      NotificationGroupRepository notificationGroupRepo, NocNotificationGroupRepository nocGroupRepo,
                      DbConnectionProfileRepository connectionRepo, MonitorCsvWriter writer, LongSupplier clock) {
        repos.put("http", http);
        repos.put("keyword", keyword);
        repos.put("page", page);
        repos.put("pagespeed", pageSpeed);
        repos.put("ping", ping);
        repos.put("port", port);
        repos.put("dns", dns);
        repos.put("domain", domain);
        repos.put("scripted", scripted);
        repos.put("dbquery", dbQuery);
        this.teamRepo = teamRepo;
        this.notificationGroupRepo = notificationGroupRepo;
        this.nocGroupRepo = nocGroupRepo;
        this.connectionRepo = connectionRepo;
        this.writer = writer;
        this.clock = clock;
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    //  İstek düzeyi hata
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /** Dosyanın tamamını reddeden hata (400 {@code VALIDATION_FAILED}, alan {@code csv}). {@code column} varsa adı. */
    public static final class CsvRequestException extends RuntimeException {
        private final String reason;
        private final String column;
        private final int line;

        public CsvRequestException(String reason, String column, int line, String message) {
            super(message);
            this.reason = reason;
            this.column = column;
            this.line = line;
        }

        public String reason() { return reason; }
        public String column() { return column; }
        public int line() { return line; }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    //  Dışa aktarım
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /** Dışa aktarım sonucu: dosya adı, UTF-8 BOM'lu CSV metni, satır sayısı, sınırda kesildi mi. */
    public record Export(String fileName, String csv, int rows, boolean truncated) { }

    public Export export(String type, HttpSession session, Long teamFilter, Set<Long> idFilter) {
        TypeSpec spec = MonitorCsvSpec.of(type);
        if (spec == null) throw new IllegalArgumentException("type");
        Map<Long, String> teamNames = teamNames();
        List<Object> list = new ArrayList<>();
        for (Object e : load(type)) {
            Long teamId = teamOf(e);
            if (!SessionScope.canViewMonitoring(session, teamId)) continue;
            if (teamFilter != null && !teamFilter.equals(teamId)) continue;
            if (idFilter != null && !idFilter.contains(idOf(e))) continue;
            list.add(e);
        }
        Collator col = Collator.getInstance(Locale.forLanguageTag("tr"));
        col.setStrength(Collator.SECONDARY);
        list.sort(Comparator.<Object, String>comparing(e -> Objects.toString(teamNames.get(teamOf(e)), ""), col)
                .thenComparing(e -> Objects.toString(nameOf(e), ""), col)
                .thenComparing(e -> idOf(e) == null ? 0L : idOf(e)));
        boolean truncated = list.size() > EXPORT_MAX_ROWS;
        if (truncated) list = list.subList(0, EXPORT_MAX_ROWS);

        Map<Long, String> groupNames = new HashMap<>();
        Map<Long, String> nocNames = new HashMap<>();
        Map<Long, String> connNames = new HashMap<>();
        if (!list.isEmpty()) for (NotificationGroup g : notificationGroupRepo.findAll()) groupNames.put(g.getId(), g.getName());
        for (NocNotificationGroup g : nocGroupRepo.findAllByOrderByNameAsc()) nocNames.put(g.getId(), g.getName());
        if ("dbquery".equals(type) && connectionRepo != null) {
            for (DbConnectionProfile p : connectionRepo.findAllByOrderByNameAsc()) connNames.put(p.getId(), p.getName());
        }

        StringBuilder sb = new StringBuilder("﻿");
        sb.append(MonitorCsvCodec.row(spec.headers()));
        for (Object e : list) {
            List<String> cells = new ArrayList<>(spec.columns().size());
            for (Column c : spec.columns()) {
                Object v = safeCurrent(c, e);
                String text;
                if (MonitorCsvSpec.H_TEAM.equals(c.header())) text = Objects.toString(teamNames.get(teamOf(e)), "");
                else if (c.kind() == Kind.NOTIFY_GROUP) text = v instanceof Number n ? Objects.toString(groupNames.get(n.longValue()), "") : "";
                else if (c.kind() == Kind.CONNECTION) text = v instanceof Number n ? Objects.toString(connNames.get(n.longValue()), "") : "";
                else if (c.kind() == Kind.NOC_GROUPS) {
                    List<String> names = new ArrayList<>();
                    if (v instanceof Collection<?> ids) for (Object id : ids) {
                        String nm = id instanceof Number n ? nocNames.get(n.longValue()) : null;
                        if (nm != null) names.add(nm);
                    }
                    text = String.join("; ", names);
                } else text = MonitorCsvCodec.text(c.kind(), v);
                cells.add(text);
            }
            sb.append(MonitorCsvCodec.row(cells));
        }
        String day = LocalDate.now(IST).format(DateTimeFormatter.BASIC_ISO_DATE);
        return new Export(type + "-izlemeler-" + day + ".csv", sb.toString(), list.size(), truncated);
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    //  İçe aktarım
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /** Satır eylemi (kalıcı — arayüz {@code mcsv.action.<…>}). */
    public static final String A_CREATE = "create", A_UPDATE = "update", A_UNCHANGED = "unchanged", A_ERROR = "error";
    /** Gerçek koşum durumu (kalıcı — arayüz {@code mcsv.status.<…>}). */
    public static final String S_DONE = "done", S_SKIPPED = "skipped", S_FAILED = "failed";

    /**
     * Satır sonucu.
     *
     * @param row     dosyadaki satır (fiziksel satır numarası, başlık 1)
     * @param name    satırdaki ad
     * @param team    satırdaki takım adı
     * @param action  create | update | unchanged | error
     * @param changed güncellemede değişen başlıklar (oluşturmada boş)
     * @param secrets içe aktarımdan sonra arayüzde girilmesi gereken sırlar ({@code basic_auth_pass}, {@code custom_headers},
     *                {@code env:<AD>})
     * @param status  gerçek koşumda done | skipped | failed (önizlemede null)
     * @param message hata / atlama metni (Msg.t)
     * @param field   hatanın sütunu (başlık adı; bilinmiyorsa null)
     */
    public record RowResult(int row, String name, String team, String action, List<String> changed, List<String> secrets,
                            String status, String message, String field) {
        RowResult withStatus(String s, String msg, String f) {
            return new RowResult(row, name, team, action, changed, secrets, s, msg, f);
        }
    }

    /** İçe aktarım sonucu. {@code counts}: create / update / unchanged / error (+ gerçek koşumda done / skipped / failed). */
    public record ImportResult(String type, boolean dryRun, List<RowResult> rows, Map<String, Integer> counts,
                               List<String> columns, boolean budgetExceeded) { }

    /** Önizleme / gerçek koşum için çözülmüş satır planı. */
    private record Plan(RowResult result, Long existingId, Map<String, Object> body, Map<String, String> bodyKeyToHeader) { }

    public ImportResult importCsv(String type, String text, boolean dryRun, HttpSession session) {
        TypeSpec spec = MonitorCsvSpec.of(type);
        if (spec == null) throw new IllegalArgumentException("type");
        MonitorCsvParser.Parsed parsed;
        try {
            parsed = MonitorCsvParser.parse(text);
        } catch (MonitorCsvParser.CsvFormatException e) {
            throw new CsvRequestException(e.reason(), null, e.line(), parseMessage(e.reason(), e.line()));
        }
        List<String> header = readHeader(spec, parsed.header());
        Context ctx = context(spec, session);
        List<Plan> plans = new ArrayList<>(parsed.rows().size());
        Set<String> seen = new HashSet<>();
        for (MonitorCsvParser.Record rec : parsed.rows()) plans.add(plan(spec, header, rec, ctx, seen, session));

        List<RowResult> out = new ArrayList<>(plans.size());
        boolean budgetExceeded = false;
        if (dryRun) {
            for (Plan p : plans) out.add(p.result());
        } else {
            long start = clock.getAsLong();
            String note = Msg.t("CSV ile içe aktarıldı", "Imported from CSV");
            for (Plan p : plans) {
                RowResult r = p.result();
                if (A_ERROR.equals(r.action())) { out.add(r.withStatus(S_FAILED, r.message(), r.field())); continue; }
                if (A_UNCHANGED.equals(r.action())) {
                    out.add(r.withStatus(S_SKIPPED, Msg.t("Değişiklik yok — kayıt olduğu gibi bırakıldı.",
                            "No changes — the record was left as it is."), null));
                    continue;
                }
                if (clock.getAsLong() - start > IMPORT_BUDGET_MS) {
                    budgetExceeded = true;
                    out.add(r.withStatus(S_SKIPPED, Msg.t("Atlandı: içe aktarmanın süre sınırı (10 dk) doldu. Dosyayı kalan satırlarla yeniden içe aktarın.",
                            "Skipped: the import's time limit (10 min) ran out. Import the file again with the remaining rows."), null));
                    continue;
                }
                Map<String, Object> body = new LinkedHashMap<>(p.body());
                body.put("changeNote", note);
                if ("scripted".equals(type) && (body.containsKey("script") || body.containsKey("env"))) body.put("versionNote", note);
                MonitorCsvWriter.Result w = A_CREATE.equals(r.action())
                        ? writer.create(type, body, session)
                        : writer.update(type, p.existingId(), body, session);
                if (w.ok()) out.add(r.withStatus(S_DONE, null, null));
                else out.add(r.withStatus(S_FAILED, w.message(), w.field() == null ? null
                        : p.bodyKeyToHeader().getOrDefault(w.field(), w.field())));
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String k : List.of(A_CREATE, A_UPDATE, A_UNCHANGED, A_ERROR)) counts.put(k, 0);
        if (!dryRun) for (String k : List.of(S_DONE, S_SKIPPED, S_FAILED)) counts.put(k, 0);
        for (RowResult r : out) {
            counts.merge(r.action(), 1, Integer::sum);
            if (r.status() != null) counts.merge(r.status(), 1, Integer::sum);
        }
        return new ImportResult(type, dryRun, out, counts, header, budgetExceeded);
    }

    /** Başlık: bilinmeyen / yinelenen sütun ya da eksik anahtar sütun → istek hatası. */
    private static List<String> readHeader(TypeSpec spec, MonitorCsvParser.Record rec) {
        List<String> header = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : rec.cells()) {
            String h = MonitorCsvCodec.unguard(raw == null ? "" : raw).trim().toLowerCase(Locale.ROOT);
            if (h.isEmpty()) {
                header.add("");
                continue;
            }
            if (spec.column(h) == null) {
                throw new CsvRequestException("unknown_column", h, rec.line(), Msg.t(
                        "Bilinmeyen sütun: \"" + clip(h) + "\". Bu türün dışa aktarılan dosyasındaki sütun adlarını kullanın.",
                        "Unknown column: \"" + clip(h) + "\". Use the column names of this type's exported file."));
            }
            if (!seen.add(h)) {
                throw new CsvRequestException("duplicate_column", h, rec.line(), Msg.t(
                        "Sütun iki kez geçiyor: \"" + h + "\". Her sütun başlıkta bir kez yer almalı.",
                        "The column appears twice: \"" + h + "\". Each column must appear once in the header."));
            }
            header.add(h);
        }
        for (String required : List.of(MonitorCsvSpec.H_NAME, MonitorCsvSpec.H_TEAM)) {
            if (!seen.contains(required)) {
                throw new CsvRequestException("missing_column", required, rec.line(), Msg.t(
                        "Zorunlu sütun eksik: \"" + required + "\". Satırlar takım ve ada göre eşleştirildiği için bu sütun gerekli.",
                        "A required column is missing: \"" + required + "\". Rows are matched by team and name, so this column is needed."));
            }
        }
        return header;
    }

    private static String clip(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    static String parseMessage(String reason, int line) {
        return switch (reason) {
            case MonitorCsvParser.E_EMPTY -> Msg.t("Dosya boş — en az başlık satırı ve bir izleme satırı olmalı.",
                    "The file is empty — it needs at least a header row and one monitor row.");
            case MonitorCsvParser.E_TOO_LARGE -> Msg.t("Dosya çok büyük (en çok 5 MB). Dosyayı bölüp parça parça içe aktarın.",
                    "The file is too large (at most 5 MB). Split it and import the parts one by one.");
            case MonitorCsvParser.E_TOO_MANY_ROWS -> Msg.t("Dosyada çok fazla satır var (en çok " + MonitorCsvParser.MAX_ROWS
                    + "). Dosyayı bölüp parça parça içe aktarın.", "The file has too many rows (at most " + MonitorCsvParser.MAX_ROWS
                    + "). Split it and import the parts one by one.");
            case MonitorCsvParser.E_TOO_MANY_COLUMNS -> Msg.t("Satır " + line + ": çok fazla sütun var (en çok "
                    + MonitorCsvParser.MAX_COLUMNS + "). Ayırıcıyı ve tırnakları denetleyin.", "Line " + line
                    + ": too many columns (at most " + MonitorCsvParser.MAX_COLUMNS + "). Check the separators and quotes.");
            case MonitorCsvParser.E_CELL_TOO_LONG -> Msg.t("Satır " + line + ": bir hücre çok uzun (en çok 64 KB). Kapanmamış bir tırnak olabilir.",
                    "Line " + line + ": a cell is too long (at most 64 KB). There may be an unclosed quote.");
            case MonitorCsvParser.E_UNCLOSED_QUOTE -> Msg.t("Satır " + line + ": tırnak kapatılmamış. Hücredeki tırnakları denetleyin (tırnak içinde tırnak \"\" diye yazılır).",
                    "Line " + line + ": a quote isn't closed. Check the quotes in the cell (a quote inside quotes is written \"\").");
            case MonitorCsvParser.E_BAD_QUOTE -> Msg.t("Satır " + line + ": kapanan tırnaktan sonra beklenmeyen karakter. Hücreyi baştan sona tırnakla sarın.",
                    "Line " + line + ": an unexpected character follows a closing quote. Wrap the whole cell in quotes.");
            default -> Msg.t("Dosya okunamadı — geçerli bir CSV dosyası seçin.", "The file couldn't be read — choose a valid CSV file.");
        };
    }

    // ── Bağlam (çözümleme haritaları) ─────────────────────────────────────────────────────────────────────────────

    private static final class Context {
        final Map<String, List<Long>> teamsByName = new HashMap<>();   // küçük harf ad → kimlikler
        final Map<String, Long> teamsExact = new HashMap<>();
        final Map<String, List<Object>> existing = new HashMap<>();     // teamId + ad → kayıtlar
        final Map<String, Long> globalNames = new HashMap<>();          // dbquery: küçük harf ad → takım
        final Map<Long, Map<String, List<Long>>> groups = new HashMap<>();
        final Map<String, List<Long>> nocGroups = new HashMap<>();
        final Map<Long, Map<String, List<DbConnectionProfile>>> connections = new HashMap<>();
        final Map<Long, DbConnectionProfile> connectionsById = new HashMap<>();
    }

    private Context context(TypeSpec spec, HttpSession session) {
        Context ctx = new Context();
        for (Team t : teamRepo.findAll()) {
            if (t.getName() == null) continue;
            ctx.teamsExact.put(t.getName().trim(), t.getId());
            ctx.teamsByName.computeIfAbsent(lower(t.getName()), k -> new ArrayList<>()).add(t.getId());
        }
        for (Object e : load(spec.type())) {
            String n = nameOf(e);
            if (n == null) continue;
            ctx.existing.computeIfAbsent(key(teamOf(e), n), k -> new ArrayList<>()).add(e);
            if (spec.nameUniqueGlobal()) ctx.globalNames.putIfAbsent(lower(n), teamOf(e));
        }
        if (spec.column("notification_group") != null) {
            for (NotificationGroup g : notificationGroupRepo.findAll()) {
                if (g.getTeamId() == null || g.getName() == null || !Boolean.TRUE.equals(g.getActive())) continue;
                ctx.groups.computeIfAbsent(g.getTeamId(), k -> new HashMap<>())
                        .computeIfAbsent(lower(g.getName()), k -> new ArrayList<>()).add(g.getId());
            }
        }
        for (NocNotificationGroup g : nocGroupRepo.findAllByOrderByNameAsc()) {
            if (g.getName() == null || Boolean.FALSE.equals(g.getActive())) continue;
            ctx.nocGroups.computeIfAbsent(lower(g.getName()), k -> new ArrayList<>()).add(g.getId());
        }
        if ("dbquery".equals(spec.type()) && connectionRepo != null) {
            for (DbConnectionProfile p : connectionRepo.findAllByOrderByNameAsc()) {
                ctx.connectionsById.put(p.getId(), p);
                if (p.getTeamId() == null || p.getName() == null) continue;
                ctx.connections.computeIfAbsent(p.getTeamId(), k -> new HashMap<>())
                        .computeIfAbsent(lower(p.getName()), k -> new ArrayList<>()).add(p);
            }
        }
        return ctx;
    }

    // ── Satır planı ───────────────────────────────────────────────────────────────────────────────────────────────

    private Plan plan(TypeSpec spec, List<String> header, MonitorCsvParser.Record rec, Context ctx, Set<String> seen,
                      HttpSession session) {
        Map<String, String> cells = new LinkedHashMap<>();
        boolean extra = false;
        for (int i = 0; i < rec.cells().size(); i++) {
            String raw = MonitorCsvCodec.unguard(rec.cells().get(i));
            if (i >= header.size() || header.get(i).isEmpty()) {
                if (raw != null && !raw.isBlank()) extra = true;
                continue;
            }
            cells.put(header.get(i), raw == null ? "" : raw);
        }
        String name = Objects.toString(cells.get(MonitorCsvSpec.H_NAME), "").trim();
        String teamName = Objects.toString(cells.get(MonitorCsvSpec.H_TEAM), "").trim();
        Map<String, String> keyToHeader = new HashMap<>();
        for (Column c : spec.columns()) if (c.bodyKey() != null) keyToHeader.put(c.bodyKey(), c.header());
        RowBuilder rb = new RowBuilder(rec.line(), name, teamName, keyToHeader);

        if (extra) return rb.error(null, Msg.t("Satırda başlıkta olmayan fazladan hücre var. Ayırıcıları ve tırnakları denetleyin.",
                "The row has extra cells that aren't in the header. Check the separators and quotes."));
        if (name.isEmpty()) return rb.error(MonitorCsvSpec.H_NAME, Msg.t("Ad boş — her satırın bir adı olmalı (eşleştirme takım + ad ile yapılır).",
                "The name is empty — each row needs a name (rows are matched by team and name)."));
        if (name.length() > 300) return rb.error(MonitorCsvSpec.H_NAME, Msg.t("Ad çok uzun (en çok 300 karakter).",
                "The name is too long (at most 300 characters)."));
        if (teamName.isEmpty()) return rb.error(MonitorCsvSpec.H_TEAM, Msg.t("Takım boş — izlemenin takımını adıyla yazın.",
                "The team is empty — write the monitor's team by name."));
        Long teamId = resolveTeam(ctx, teamName);
        if (teamId == null) {
            List<Long> ids = ctx.teamsByName.get(lower(teamName));
            return rb.error(MonitorCsvSpec.H_TEAM, ids != null && ids.size() > 1
                    ? Msg.t("Takım adı birden fazla takımla eşleşiyor: \"" + clip(teamName) + "\". Takım adını tam yazın.",
                            "The team name matches more than one team: \"" + clip(teamName) + "\". Write the exact team name.")
                    : Msg.t("Takım bulunamadı: \"" + clip(teamName) + "\". Hedef ortamda aynı adla bir takım olmalı.",
                            "Team not found: \"" + clip(teamName) + "\". A team with the same name must exist in the target environment."));
        }
        if (!SessionScope.canOperateTeam(session, teamId)) {
            return rb.error(MonitorCsvSpec.H_TEAM, Msg.t("Bu takımın izlemelerini düzenleme yetkiniz yok: \"" + clip(teamName) + "\".",
                    "You aren't allowed to edit this team's monitors: \"" + clip(teamName) + "\"."));
        }
        String k = key(teamId, name);
        if (!seen.add(k)) {
            return rb.error(MonitorCsvSpec.H_NAME, Msg.t("Bu takım ve ad dosyada daha önce geçti — her izleme dosyada bir kez yer almalı.",
                    "This team and name already appeared in the file — each monitor must appear once."));
        }
        List<Object> matches = ctx.existing.getOrDefault(k, List.of());
        if (matches.size() > 1) {
            return rb.error(MonitorCsvSpec.H_NAME, Msg.t("Takımda bu adla " + matches.size() + " izleme var; hangisinin güncelleneceği belli değil. Önce adları ayırın.",
                    "The team has " + matches.size() + " monitors with this name; it isn't clear which one to update. Make the names distinct first."));
        }
        Object existing = matches.isEmpty() ? null : matches.get(0);
        if (existing == null && spec.nameUniqueGlobal() && ctx.globalNames.containsKey(lower(name))) {
            return rb.error(MonitorCsvSpec.H_NAME, Msg.t("Bu ad başka bir takımın veritabanı sorgu izlemesinde kullanılıyor; bu türde adlar tüm takımlarda tekildir.",
                    "This name is used by another team's database query monitor; names of this type are unique across all teams."));
        }

        // Hücreler → gövde değerleri (boş hücre = değiştirme)
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Boolean> info = new HashMap<>();
        for (Column c : spec.columns()) {
            String h = c.header();
            if (MonitorCsvSpec.H_NAME.equals(h) || MonitorCsvSpec.H_TEAM.equals(h)) continue;
            String raw = cells.get(h);
            if (raw == null || raw.isBlank()) continue;
            Object v;
            try {
                v = MonitorCsvCodec.parse(c.kind(), raw);
            } catch (MonitorCsvCodec.CellException e) {
                return rb.error(h, cellMessage(e.code(), h));
            }
            if (c.info()) {
                info.put(h, (Boolean) v);
                continue;
            }
            if (c.kind() == Kind.INT && ((Integer) v) < 0) {
                return rb.error(h, Msg.t("\"" + h + "\" negatif olamaz.", "\"" + h + "\" can't be negative."));
            }
            if (c.kind() == Kind.NOTIFY_GROUP) {
                List<Long> ids = ctx.groups.getOrDefault(teamId, Map.of()).get(lower((String) v));
                if (ids == null || ids.isEmpty()) return rb.error(h, Msg.t("Bildirim grubu bu takımda bulunamadı: \"" + clip((String) v)
                        + "\". Grubu hedef takımda aynı adla oluşturun ya da hücreyi boşaltın.", "Notification group not found in this team: \""
                        + clip((String) v) + "\". Create the group in the target team with the same name or empty the cell."));
                if (ids.size() > 1) return rb.error(h, Msg.t("Takımda bu adla birden fazla bildirim grubu var: \"" + clip((String) v) + "\".",
                        "The team has more than one notification group with this name: \"" + clip((String) v) + "\"."));
                v = ids.get(0);
            } else if (c.kind() == Kind.NOC_GROUPS) {
                List<Long> ids = new ArrayList<>();
                for (Object nm : (List<?>) v) {
                    List<Long> found = ctx.nocGroups.get(lower(nm.toString()));
                    if (found == null || found.isEmpty()) return rb.error(h, Msg.t("7/24 grubu bulunamadı: \"" + clip(nm.toString())
                            + "\". Hedef ortamda aynı adla etkin bir 7/24 grubu olmalı.", "24/7 group not found: \"" + clip(nm.toString())
                            + "\". An active 24/7 group with the same name must exist in the target environment."));
                    if (found.size() > 1) return rb.error(h, Msg.t("Bu adla birden fazla 7/24 grubu var: \"" + clip(nm.toString()) + "\".",
                            "There is more than one 24/7 group with this name: \"" + clip(nm.toString()) + "\"."));
                    if (!ids.contains(found.get(0))) ids.add(found.get(0));
                }
                v = ids;
            } else if (c.kind() == Kind.CONNECTION) {
                List<DbConnectionProfile> found = ctx.connections.getOrDefault(teamId, Map.of()).get(lower((String) v));
                if (found == null || found.isEmpty()) return rb.error(h, Msg.t("Veritabanı bağlantısı bu takımda bulunamadı: \""
                        + clip((String) v) + "\". Bağlantıyı hedef takımda aynı adla oluşturun.", "Database connection not found in this team: \""
                        + clip((String) v) + "\". Create the connection in the target team with the same name."));
                if (found.size() > 1) return rb.error(h, Msg.t("Takımda bu adla birden fazla bağlantı var: \"" + clip((String) v) + "\".",
                        "The team has more than one connection with this name: \"" + clip((String) v) + "\"."));
                v = found.get(0).getId();
            }
            values.put(h, v);
        }

        // Alarm seviyesi: sessizce düşen değer yerine satır hatası
        if (values.get("alert_level") instanceof String lvl && !MonitorAlertPrefs.LEVELS.contains(lvl.toUpperCase(Locale.ROOT))) {
            return rb.error("alert_level", Msg.t("Alarm seviyesi WARNING, HIGH ya da CRITICAL olmalı.",
                    "The alert level must be WARNING, HIGH or CRITICAL."));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        List<String> changed = new ArrayList<>();
        String action;
        if (existing == null) {
            List<String> missing = new ArrayList<>();
            for (String req : spec.createRequired()) {
                if (MonitorCsvSpec.H_NAME.equals(req) || MonitorCsvSpec.H_TEAM.equals(req)) continue;
                if (!values.containsKey(req)) missing.add(req);
            }
            if (!missing.isEmpty()) {
                return rb.error(missing.get(0), Msg.t("Yeni izleme için zorunlu sütunlar boş: " + String.join(", ", missing) + ".",
                        "Required columns are empty for a new monitor: " + String.join(", ", missing) + "."));
            }
            body.put("name", name);
            body.put("teamId", teamId);
            for (Column c : spec.columns()) {
                if (c.info() || !values.containsKey(c.header())) continue;
                body.put(c.bodyKey(), values.get(c.header()));
            }
            action = A_CREATE;
        } else {
            for (Column c : spec.columns()) {
                if (c.info() || !values.containsKey(c.header())) continue;
                Object cur = safeCurrent(c, existing);
                String a = MonitorCsvCodec.canon(c.kind(), cur, c.nullAs(), c.upper());
                String b = MonitorCsvCodec.canon(c.kind(), values.get(c.header()), c.nullAs(), c.upper());
                if (!a.equals(b)) {
                    changed.add(c.header());
                    body.put(c.bodyKey(), values.get(c.header()));
                }
            }
            if (!name.equals(nameOf(existing))) {
                changed.add(0, MonitorCsvSpec.H_NAME);
                body.put("name", name);
            }
            action = changed.isEmpty() ? A_UNCHANGED : A_UPDATE;
        }

        String typeErr = typeCheck(spec, values, existing, teamId, session, rb);
        if (typeErr != null) return rb.error(rb.errField, typeErr);

        List<String> secrets = secrets(spec.type(), info, values, existing);
        return new Plan(new RowResult(rec.line(), name, teamName, action, List.copyOf(changed), secrets, null, null, null),
                existing == null ? null : idOf(existing), body, keyToHeader);
    }

    /** Türe özgü ucuz denetimler — hata metni (rb.errField alanı yazar) ya da null. */
    private String typeCheck(TypeSpec spec, Map<String, Object> values, Object existing, Long teamId, HttpSession session,
                             RowBuilder rb) {
        if (values.get("url") instanceof String url) {
            String u = MonitorUrls.normalize(url);
            if (!MonitorUrls.isCheckable(u)) {
                rb.errField = "url";
                return Msg.t("Geçersiz URL: geçerli bir host yok (örn. https://example.com).", "Invalid URL: there is no valid host (e.g. https://example.com).");
            }
        }
        Object iv = values.containsKey("interval_seconds") ? values.get("interval_seconds") : null;
        if (iv instanceof Integer i && i == 0) {
            rb.errField = "interval_seconds";
            return Msg.t("Kontrol aralığı 0 olamaz — saniye cinsinden pozitif bir değer yazın.", "The check interval can't be 0 — write a positive value in seconds.");
        }
        switch (spec.type()) {
            case "ping" -> {
                if (values.get("host") instanceof String host) {
                    String cur = existing == null ? null : Objects.toString(MonitorCsvSpec.prop("host").apply(existing), null);
                    if (!host.equals(cur) && (PingCheckerService.startsWithDash(host) || !PingCheckerService.isValidHost(host))) {
                        rb.errField = "host";
                        return Msg.t("Host geçersiz — yalnız ana bilgisayar adı ya da IP adresi yazın (https://, yol, port olmadan).",
                                "The host is invalid — write only a host name or an IP address (without https://, a path or a port).");
                    }
                }
            }
            case "port" -> {
                if (values.get("port") instanceof Integer p && (p < 1 || p > 65535)) {
                    rb.errField = "port";
                    return Msg.t("Port 1–65535 aralığında olmalı.", "The port must be between 1 and 65535.");
                }
            }
            case "dns" -> {
                if (values.get("record_type") instanceof String rt && !DNS_RECORD_TYPES.contains(rt.toUpperCase(Locale.ROOT))) {
                    rb.errField = "record_type";
                    return Msg.t("Kayıt tipi A, AAAA, CNAME, MX, TXT ya da NS olmalı.", "The record type must be A, AAAA, CNAME, MX, TXT or NS.");
                }
            }
            case "dbquery" -> {
                if (existing == null && !SessionScope.canOperateTeamOwn(session, teamId)) {
                    rb.errField = MonitorCsvSpec.H_TEAM;
                    return Msg.t("Paylaşımla erişilen takımda veritabanı sorgu izlemesi oluşturulamaz — bağlantılar takımın kendi belgesidir.",
                            "A database query monitor can't be created in a team you reach through access sharing — connections belong to the team itself.");
                }
                Long connId = values.get("connection") instanceof Long l ? l
                        : existing instanceof DbQueryMonitor m ? m.getProfileId() : null;
                String sql = values.get("sql_text") instanceof String s ? s : null;
                boolean connChanged = values.containsKey("connection");
                if ((sql != null || connChanged) && connId != null) {
                    if (sql == null && existing instanceof DbQueryMonitor m) sql = m.getSqlText();
                    DbConnectionProfile prof = null;
                    if (connectionRepo != null) prof = connectionRepo.findById(connId).orElse(null);
                    if (sql != null && !sql.isBlank()) {
                        DbType t = prof == null ? null : DbType.parse(prof.getDbType());
                        DbQuerySqlGuard.Result g = DbQuerySqlGuard.check(sql, t == null ? DbType.POSTGRESQL : t);
                        if (g != null && !g.ok()) {
                            String first = g.violations() == null || g.violations().isEmpty() ? null : g.violations().get(0).message();
                            rb.errField = "sql_text";
                            return Msg.t("Sorgu güvenlik denetiminden geçmedi" + (first == null ? "." : ": " + first),
                                    "The query didn't pass the safety check" + (first == null ? "." : ": " + first));
                        }
                    }
                }
            }
            default -> { }
        }
        return null;
    }

    /** Satırın, içe aktarımdan sonra arayüzde girilmesi gereken sırları. */
    static List<String> secrets(String type, Map<String, Boolean> info, Map<String, Object> values, Object existing) {
        List<String> out = new ArrayList<>();
        switch (type) {
            case "http", "keyword", "pagespeed" -> {
                String user = values.get("basic_auth_user") instanceof String s ? s
                        : existing == null ? null : Objects.toString(MonitorCsvSpec.prop("basicAuthUser").apply(existing), null);
                boolean hasPass = existing != null && notBlank(MonitorCsvSpec.prop("basicAuthPassEnc").apply(existing));
                if (notBlank(user) && !hasPass) out.add(MonitorCsvSpec.SECRET_BASIC_AUTH_PASS);
                boolean hadHeaders = Boolean.TRUE.equals(info.get(MonitorCsvSpec.H_CUSTOM_HEADERS_SET));
                boolean hasHeaders = existing != null && (notBlank(MonitorCsvSpec.prop("customHeadersEnc").apply(existing))
                        || ("keyword".equals(type) && notBlank(MonitorCsvSpec.prop("customHeaders").apply(existing))));
                if (hadHeaders && !hasHeaders) out.add(MonitorCsvSpec.SECRET_CUSTOM_HEADERS);
            }
            case "scripted" -> {
                if (values.get("env") instanceof List<?> env) {
                    Set<String> stored = existing instanceof ScriptedMonitor m ? MonitorCsvSpec.storedSecretNames(m.getEnvJson()) : Set.of();
                    for (Object o : env) {
                        if (o instanceof Map<?, ?> e && Boolean.TRUE.equals(e.get("secret"))) {
                            String nm = String.valueOf(e.get("name"));
                            if (!stored.contains(nm)) out.add(MonitorCsvSpec.SECRET_ENV_PREFIX + nm);
                        }
                    }
                }
            }
            default -> { }
        }
        return List.copyOf(out);
    }

    private static boolean notBlank(Object o) {
        return o != null && !o.toString().isBlank();
    }

    static String cellMessage(String code, String header) {
        return switch (code) {
            case MonitorCsvCodec.C_NOT_INT -> Msg.t("\"" + header + "\" bir tam sayı olmalı.", "\"" + header + "\" must be a whole number.");
            case MonitorCsvCodec.C_NOT_BOOL -> Msg.t("\"" + header + "\" true ya da false olmalı (evet / hayır da kabul edilir).",
                    "\"" + header + "\" must be true or false (yes / no is accepted too).");
            case MonitorCsvCodec.C_NOT_LIST -> Msg.t("\"" + header + "\" bir JSON liste olmalı (ör. [{\"name\":\"A\",\"value\":\"1\",\"secret\":false}]).",
                    "\"" + header + "\" must be a JSON list (e.g. [{\"name\":\"A\",\"value\":\"1\",\"secret\":false}]).");
            default -> Msg.t("\"" + header + "\" geçerli bir JSON değil. Dışa aktarılan dosyadaki biçimi koruyun.",
                    "\"" + header + "\" isn't valid JSON. Keep the format of the exported file.");
        };
    }

    /** Satır hatası kurucusu. */
    private static final class RowBuilder {
        final int line;
        final String name;
        final String team;
        final Map<String, String> keyToHeader;
        String errField;

        RowBuilder(int line, String name, String team, Map<String, String> keyToHeader) {
            this.line = line;
            this.name = name;
            this.team = team;
            this.keyToHeader = keyToHeader;
        }

        Plan error(String field, String message) {
            return new Plan(new RowResult(line, name, team, A_ERROR, List.of(), List.of(), null, message, field), null, Map.of(),
                    keyToHeader);
        }
    }

    // ── Kayıt erişimi ─────────────────────────────────────────────────────────────────────────────────────────────

    /** Türün satırları — Port / DNS'te yalnız bağımsız ve silinmemiş satırlar. */
    List<Object> load(String type) {
        JpaRepository<?, Long> repo = repos.get(type);
        if (repo == null) return List.of();
        List<Object> out = new ArrayList<>();
        for (Object e : repo.findAll()) {
            if ("port".equals(type) || "dns".equals(type)) {
                var w = PropertyAccessorFactory.forBeanPropertyAccess(e);
                if (!Boolean.TRUE.equals(w.getPropertyValue("standalone"))) continue;
                if (w.getPropertyValue("deletedAt") != null) continue;
            }
            out.add(e);
        }
        return out;
    }

    private Map<Long, String> teamNames() {
        Map<Long, String> m = new HashMap<>();
        for (Team t : teamRepo.findAll()) m.put(t.getId(), t.getName());
        return m;
    }

    private static Long resolveTeam(Context ctx, String name) {
        Long exact = ctx.teamsExact.get(name);
        if (exact != null) return exact;
        List<Long> ids = ctx.teamsByName.get(lower(name));
        return ids != null && ids.size() == 1 ? ids.get(0) : null;
    }

    static Object safeCurrent(Column c, Object e) {
        try {
            return c.current().apply(e);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    static Long teamOf(Object e) {
        Object v = PropertyAccessorFactory.forBeanPropertyAccess(e).getPropertyValue("teamId");
        return v instanceof Number n ? n.longValue() : null;
    }

    static Long idOf(Object e) {
        Object v = PropertyAccessorFactory.forBeanPropertyAccess(e).getPropertyValue("id");
        return v instanceof Number n ? n.longValue() : null;
    }

    static String nameOf(Object e) {
        Object v = PropertyAccessorFactory.forBeanPropertyAccess(e).getPropertyValue("name");
        return v == null ? null : v.toString();
    }

    static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String key(Long teamId, String name) {
        return teamId + "\u0000" + lower(name);
    }

    /** Dışa aktarımın {@code ids} süzgeci: virgülle ayrılmış kimlikler (≤ {@link #EXPORT_MAX_ROWS}); boş → null (süzgeç yok). */
    public static Set<Long> parseIds(String csv) {
        if (csv == null || csv.isBlank()) return null;
        Set<Long> out = new LinkedHashSet<>();
        int start = 0;
        for (int i = 0; i <= csv.length() && out.size() <= EXPORT_MAX_ROWS; i++) {
            if (i == csv.length() || csv.charAt(i) == ',') {
                String part = csv.substring(start, i).trim();
                if (!part.isEmpty()) {
                    try {
                        out.add(Long.valueOf(part));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("ids");
                    }
                }
                start = i + 1;
            }
        }
        return out;
    }
}
