package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.techerror.TechErrorExportService;
import com.sitemonitor.service.techerror.TechErrorReportService;
import com.sitemonitor.util.Csv;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.MDC;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Kanal teknik hata raporu DEĞER dışa aktarımı (E23, 2026-10-18 — kullanıcı onayı: "gönderilmiş rapor değerleri, BI
 * için; kapsam + kişi maskesi kuralları"). Salt okunur, oturumla (her {@code /api} ucu gibi — API anahtarı yok):
 * {@code GET /api/tech-error-reports/export?team=<id|all>&channel=<id>&year=<yyyy>&month=<1-12>&status=sent|all&format=json|csv}.
 *
 * <p><b>Kapı = modülün okuma kapısı.</b> {@code tech_error_reports.read/view} + modül görünürlüğü (S6, 403); takım ve kanal
 * okuma kapsamından ({@link TechErrorReportController#canRead}: görüş / yönetim / üyelik + erişim paylaşımı VIEW ve üstü).
 * Okunamayan takım ya da kanal 404 (varlığı sızmaz). Kişi verisi YOK (servis izdüşümü onu okumaz bile) — paylaşımla
 * okuyan da aynı satırları görür.
 *
 * <p>{@code year} 2020…bu yıl (İstanbul), tek kanal ({@code channel}) dışında zorunlu; {@code month} yıl ister. Tavan
 * {@link TechErrorExportService#MAX_REPORTS} rapor → 400 {@code EXPORT_TOO_LARGE}. Kullanıcı başına dakikada
 * {@link #EXPORT_PER_MINUTE} istek (429 {@code RATE_LIMITED} + {@code Retry-After}); pod genelinde aynı anda en çok
 * {@link #EXPORT_CONCURRENCY} dışa aktarım (dolu → 429 {@code EXPORT_BUSY} + {@code Retry-After}, kişinin hakkını yemez).
 * Okumadan önce kuyruktaki gönderimler sonuçlandırılır ({@code reconcileQueued} — diğer okuma uçları gibi). Her başarılı
 * dışa aktarım {@code TECH_ERROR_REPORT_EXPORT} denetim satırı yazar (süzgeç + rapor / satır sayısı). CSV doğrudan yanıt
 * akışına yazılır.
 *
 * <p>Ayrı denetleyici (aynı taban yol): rapor uçlarının büyük denetleyicisine yalnız bir kapsam yardımcısı açıldı.
 */
@RestController
@RequestMapping("/api/tech-error-reports")
public class TechErrorReportExportController {

    /** En küçük dışa aktarılabilir yıl (modül 2026'da açıldı; geniş bir alt sınır). */
    static final int MIN_YEAR = 2020;
    /** Kullanıcı başına dakikadaki en çok dışa aktarım (şablon önizleme PDF'inin sınırıyla aynı desen). */
    static final int EXPORT_PER_MINUTE = 6;
    private static final long EXPORT_WINDOW_MS = 60_000L;
    /** Pod genelinde aynı anda en çok bu kadar dışa aktarım (her biri en çok 2 × MAX_REPORTS raporun JSON'unu okur). */
    static final int EXPORT_CONCURRENCY = 2;
    /** İzin için kısa bekleyiş (PDF izni deseni; dışa aktarım PDF'ten ucuz — daha kısa). */
    static final long EXPORT_WAIT_MS = 5_000L;
    /** Dolu yanıtında önerilen bekleme ({@code Retry-After}). */
    static final long EXPORT_BUSY_RETRY_SECONDS = 10L;

    private final TechErrorReportService service;
    private final TechErrorExportService exporter;
    private final TeamRepository teams;
    private final PermissionService permissions;
    private final AuditService auditService;

    /** Kullanıcı → son istek zamanları (ms); pod başına, sınırlı (1 000 anahtarda süresi geçenler atılır, 10 000'de sıfırlanır). */
    private final Map<String, Deque<Long>> exportCalls = new HashMap<>();
    /** Saat (testlerde ileri sarılır). */
    java.util.function.LongSupplier exportClock = System::currentTimeMillis;
    /** Eşzamanlılık izinleri (adil; testler doldurur). */
    final java.util.concurrent.Semaphore exportSlots = new java.util.concurrent.Semaphore(EXPORT_CONCURRENCY, true);
    /** İzin bekleme süresi (ms; testlerde kısaltılır). */
    long exportWaitMs = EXPORT_WAIT_MS;

    public TechErrorReportExportController(TechErrorReportService service, TechErrorExportService exporter,
                                           TeamRepository teams, PermissionService permissions, AuditService auditService) {
        this.service = service;
        this.exporter = exporter;
        this.teams = teams;
        this.permissions = permissions;
        this.auditService = auditService;
    }

    @GetMapping("/export")
    public ResponseEntity<?> export(@RequestParam(value = "team", required = false) String team,
                                    @RequestParam(value = "channel", required = false) String channel,
                                    @RequestParam(value = "year", required = false) String year,
                                    @RequestParam(value = "month", required = false) String month,
                                    @RequestParam(value = "status", required = false) String status,
                                    @RequestParam(value = "format", required = false) String format,
                                    HttpSession session, HttpServletRequest request,
                                    HttpServletResponse response) throws java.io.IOException {
        if (!permissions.allows(session, TechErrorReportController.READ, "view")) {
            throw new SecurityException(TechErrorReportController.READ + " yetkisi gerekli");
        }
        TechErrorReportController.requireModuleVisible(session);

        // ── Parametreler (geçersiz istek hakkı yemez) ──
        Long teamId = team == null || team.isBlank() || "all".equalsIgnoreCase(team.trim()) ? null
                : idOrFail(team, "team", Msg.t("Takım kimliği geçersiz. Listeden bir takım seçin ya da tüm takımlar için team=all gönderin.",
                        "The team id is invalid. Pick a team from the list, or send team=all for every team."));
        Long channelId = channel == null || channel.isBlank() ? null
                : idOrFail(channel, "channel", Msg.t("Kanal kimliği geçersiz. Kanal listesini yenileyip yeniden seçin.",
                        "The channel id is invalid. Reload the channel list and pick it again."));
        int currentYear = LocalDate.ofInstant(service.now(), TechErrorReportService.ZONE).getYear();
        Integer y = year == null || year.isBlank() ? null : intOrNull(year);
        if (year != null && !year.isBlank() && (y == null || y < MIN_YEAR || y > currentYear)) {
            throw new FieldValidationException("year", Msg.t(
                    "Yıl " + MIN_YEAR + " ile " + currentYear + " arasında olmalı (ör. year=" + currentYear + ").",
                    "The year must be between " + MIN_YEAR + " and " + currentYear + " (e.g. year=" + currentYear + ")."));
        }
        if (y == null && channelId == null) {
            throw new FieldValidationException("year", Msg.t(
                    "Yıl gerekli (ör. year=" + currentYear + "). Tüm yılları yalnız tek bir kanal için (channel=<kimlik>) dışa aktarabilirsiniz.",
                    "A year is required (e.g. year=" + currentYear + "). You can export every year only for a single channel (channel=<id>)."));
        }
        Integer m = month == null || month.isBlank() ? null : intOrNull(month);
        if (month != null && !month.isBlank() && (m == null || m < 1 || m > 12)) {
            throw new FieldValidationException("month", Msg.t("Ay 1 ile 12 arasında olmalı (ör. month=9) ya da boş bırakılmalı.",
                    "The month must be between 1 and 12 (e.g. month=9), or left out."));
        }
        if (m != null && y == null) {
            throw new FieldValidationException("year", Msg.t("Ay seçildiğinde yıl da gerekir (ör. year=" + currentYear + "&month=" + m + ").",
                    "When you pick a month you also need a year (e.g. year=" + currentYear + "&month=" + m + ")."));
        }
        String st = status == null || status.isBlank() ? "sent" : status.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"sent".equals(st) && !"all".equals(st)) {
            throw new FieldValidationException("status", Msg.t("Durum süzgeci sent (yalnız gönderilmiş raporlar) ya da all (taslaklar dahil) olmalı.",
                    "The status filter must be sent (only reports that were sent) or all (drafts included)."));
        }
        String fmt = format == null || format.isBlank() ? "json" : format.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"json".equals(fmt) && !"csv".equals(fmt)) {
            throw new FieldValidationException("format", Msg.t("Biçim json ya da csv olmalı.", "The format must be json or csv."));
        }

        // ── Kapsam (sabit sorgu: kanal(lar) + takım adları; raporlar serviste tek sorgu) ──
        List<TechErrorChannel> chs = new ArrayList<>();
        Map<Long, String> teamNames = new HashMap<>();
        if (channelId != null) {
            TechErrorChannel ch;
            try {
                ch = service.channel(channelId);
            } catch (NoSuchElementException e) {
                throw notFoundChannel();
            }
            if (!TechErrorReportController.canRead(session, ch.getTeamId())
                    || (teamId != null && !teamId.equals(ch.getTeamId()))) throw notFoundChannel();
            Team t = teams.findById(ch.getTeamId()).orElseThrow(TechErrorReportExportController::notFoundChannel);
            teamNames.put(t.getId(), t.getName());
            chs.add(ch);
        } else if (teamId != null) {
            if (!TechErrorReportController.canRead(session, teamId)) throw notFoundTeam();
            Team t = teams.findById(teamId).orElseThrow(TechErrorReportExportController::notFoundTeam);
            teamNames.put(t.getId(), t.getName());
            chs.addAll(service.channels(Set.of(teamId)));
        } else {
            Set<Long> visible = TechErrorReportController.visibleTeamIds(session);       // null = kurum geneli görüntüleyici
            List<TechErrorChannel> all = service.channels(visible);
            Set<Long> ids = new LinkedHashSet<>();
            for (TechErrorChannel ch : all) if (TechErrorReportController.canRead(session, ch.getTeamId())) ids.add(ch.getTeamId());
            for (Team t : ids.isEmpty() ? List.<Team>of() : teams.findAllById(ids)) teamNames.put(t.getId(), t.getName());
            // Takımı silinmiş (yetim temizliğini bekleyen) kanal dışarıda kalır — genel bakış listesiyle aynı
            for (TechErrorChannel ch : all) if (teamNames.containsKey(ch.getTeamId())) chs.add(ch);
        }

        String rateKey = rateKey(session);
        Long retryAfter = reserveExport(rateKey);
        if (retryAfter != null) {
            Map<String, Object> body = errorBody("RATE_LIMITED", Msg.t(
                    "Dışa aktarım sınırına ulaşıldı: kişi başına dakikada en çok " + EXPORT_PER_MINUTE + " dışa aktarım. " + retryAfter + " saniye sonra yeniden deneyin.",
                    "Export limit reached: at most " + EXPORT_PER_MINUTE + " exports per person per minute. Try again in " + retryAfter + " seconds."));
            body.put("retry_after_seconds", retryAfter);
            return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter)).body(body);
        }
        // Pod genelinde en çok EXPORT_CONCURRENCY dışa aktarım aynı anda (bellek: her biri en çok 2 × MAX_REPORTS rapor
        // okur). Dolu → 429 EXPORT_BUSY; reddedilen istek kişinin dakikalık hakkını YEMEZ (ayrılan hak geri verilir).
        if (!acquireExportSlot()) {
            cancelReservation(rateKey);
            long wait = Math.max(1L, EXPORT_BUSY_RETRY_SECONDS);
            Map<String, Object> body = errorBody("EXPORT_BUSY", Msg.t(
                    "Şu anda başka dışa aktarımlar hazırlanıyor (aynı anda en çok " + EXPORT_CONCURRENCY + "). " + wait + " saniye sonra yeniden deneyin; bu deneme dakikalık hakkınızdan düşülmedi.",
                    "Other exports are being prepared right now (at most " + EXPORT_CONCURRENCY + " at a time). Try again in " + wait + " seconds; this attempt did not count against your per-minute limit."));
            body.put("retry_after_seconds", wait);
            return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER, String.valueOf(wait)).body(body);
        }
        try {
            // Kuyruğa alınmış gönderimler sonuçlandıysa durum güncel (diğer okuma uçları gibi: genel bakış, liste, rapor)
            List<Long> channelIds = chs.stream().map(TechErrorChannel::getId).toList();
            if (!channelIds.isEmpty()) service.reconcileQueued(channelIds);

            TechErrorExportService.Result res;
            try {
                res = exporter.export(chs, teamNames, new TechErrorExportService.Filter(y, m, "sent".equals(st)), service.now());
            } catch (TechErrorExportService.TooLargeException e) {
                Map<String, Object> body = errorBody("EXPORT_TOO_LARGE", Msg.t(
                        "Seçilen süzgeçle " + TechErrorExportService.MAX_REPORTS + " rapordan fazlası çıkıyor. Bir ay, bir takım ya da tek bir kanal seçerek daraltıp yeniden deneyin.",
                        "This filter matches more than " + TechErrorExportService.MAX_REPORTS + " reports. Narrow it to one month, one team or a single channel and try again."));
                body.put("max_reports", TechErrorExportService.MAX_REPORTS);
                return ResponseEntity.status(400).body(body);
            }

            auditService.recordAction("TECH_ERROR_REPORT_EXPORT", session, request,
                    channelId != null ? "TECH_ERROR_CHANNEL" : "TEAM",
                    channelId != null ? String.valueOf(channelId) : teamId != null ? String.valueOf(teamId) : "all",
                    AuditDetail.of("team", teamId != null ? teamId : (channelId != null ? null : "all"), "channel_id", channelId,
                            "year", y, "month", m, "status", st, "format", fmt,
                            "reports", res.reports().size(), "rows", res.rows().size()));

            if ("csv".equals(fmt)) {
                // Doğrudan servlet çıkışına akıtılır (AdminController.exportAlerts ile aynı gerekçe): tüm CSV bellekte bir
                // metin + bayt dizisi olarak ikinci kez kurulmaz. Akış başladıktan sonra hata gövdesi yazılamaz — veri o an
                // hazırdır, yalnız yazma (istemci kopması) kalır; izin finally'de bırakılır.
                response.setStatus(200);
                response.setContentType("text/csv;charset=UTF-8");
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName(y, m)).build().toString());
                java.io.Writer w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(response.getOutputStream(),
                        StandardCharsets.UTF_8), 16 * 1024);
                writeCsv(w, res.rows());
                w.flush();
                return null;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("columns", TechErrorExportService.COLUMNS);
            data.put("rows", res.rows());
            data.put("notes", notes("sent".equals(st)));
            data.put("reports", reportRows(res.reports()));
            data.put("report_count", res.reports().size());
            data.put("row_count", res.rows().size());
            data.put("max_reports", TechErrorExportService.MAX_REPORTS);
            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("team", teamId != null ? teamId : (channelId != null ? null : "all"));
            filter.put("channel", channelId);
            filter.put("year", y);
            filter.put("month", m);
            filter.put("status", st);
            data.put("filter", filter);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("data", data);
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
        } finally {
            exportSlots.release();
        }
    }

    /** Eşzamanlılık izni: kısa bekleyişle (PDF izni deseni); kesilirse izin alınmamış sayılır. */
    private boolean acquireExportSlot() {
        try {
            return exportSlots.tryAcquire(exportWaitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ── CSV ──────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Düz ondalık sayı (ör. {@code -1.25}, {@code 12000}) — BI araçları (Power BI, Qlik, pandas) bunu sayı olarak okur; formül
     * olamaz. Virgülle ayrılmış, nokta ondalıklı bu CSV BI içindir; Excel kullanıcıları kanal görünümündeki "Excel'e aktar"
     * düğmesini (.xlsx, gerçek sayı hücreleri) kullanır — Türkçe bölge ayarlı Excel bu CSV'yi tek sütun açabilir.
     */
    private static final Pattern PLAIN_NUMBER = Pattern.compile("-?[0-9]{1,20}(\\.[0-9]{1,6})?");

    /**
     * RFC 4180, UTF-8 BOM, CRLF. Metin hücreleri projenin ortak kuralından geçer ({@link Csv#cell}: tırnaklama + formül
     * nötrleme — {@code = + - @} ile başlayan metin başına tek tırnak alır). Sunucunun ÜRETTİĞİ sayılar ({@link BigDecimal} /
     * {@link Long}) düz ondalık deseninde olduğu doğrulanarak OLDUĞU GİBİ yazılır: negatif bir yüzde ({@code -1.5}) BI için
     * sayı kalmalı, {@code '-1.5} metnine dönmemeli — bu desende formül olamaz. Mantıksal değer {@code true} / {@code false}.
     */
    static String csv(List<List<Object>> rows) {
        java.io.StringWriter sw = new java.io.StringWriter();
        try {
            writeCsv(sw, rows);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);       // StringWriter yazmada hata vermez
        }
        return sw.toString();
    }

    /** {@link #csv} ile aynı biçim, doğrudan bir yazıcıya (satırlar üretildikçe; tüm dosya bellekte kurulmaz). */
    static void writeCsv(java.io.Writer w, List<List<Object>> rows) throws java.io.IOException {
        w.write(0xFEFF);                                   // UTF-8 BOM (mevcut CSV dışa aktarımlarıyla aynı)
        w.write(Csv.row(TechErrorExportService.COLUMNS.toArray()));
        StringBuilder sb = new StringBuilder(256);
        for (List<Object> r : rows) {
            sb.setLength(0);
            for (int i = 0; i < r.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(csvValue(r.get(i)));
            }
            sb.append("\r\n");
            w.write(sb.toString());
        }
    }

    static String csvValue(Object v) {
        if (v == null) return "";
        if (v instanceof BigDecimal b) {
            String s = b.toPlainString();
            return PLAIN_NUMBER.matcher(s).matches() ? s : Csv.cell(s);
        }
        if (v instanceof Long || v instanceof Integer) return String.valueOf(v);
        if (v instanceof Boolean b) return b ? "true" : "false";
        return Csv.cell(String.valueOf(v));
    }

    /** {@code teknik-hata-raporlari-<yıl>[-<ay>].csv}; tek kanalın tüm yılları {@code teknik-hata-raporlari-tum-yillar.csv}. */
    static String fileName(Integer year, Integer month) {
        if (year == null) return "teknik-hata-raporlari-tum-yillar.csv";
        return "teknik-hata-raporlari-" + year + (month == null ? "" : "-" + String.format("%02d", month)) + ".csv";
    }

    // ── JSON yardımcıları ────────────────────────────────────────────────────────────────────────────

    private static List<Map<String, Object>> reportRows(List<TechErrorExportService.ReportMeta> metas) {
        List<Map<String, Object>> out = new ArrayList<>(metas.size());
        for (TechErrorExportService.ReportMeta r : metas) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("team_id", r.teamId());
            m.put("team_name", r.teamName());
            m.put("channel_id", r.channelId());
            m.put("channel_name", r.channelName());
            m.put("year", r.year());
            m.put("month", r.month());
            m.put("report_id", r.reportId());
            m.put("report_status", r.status());
            m.put("sent_at", r.sentAt() == null ? null : r.sentAt().toString());
            m.put("changed_after_send", r.changedAfterSend());
            m.put("approval_state", r.approvalState());
            m.put("field_count", r.fieldCount());
            out.add(m);
        }
        return out;
    }

    /** Okuyucuya (BI) açıklamalar — istek dilinde. */
    static List<String> notes(boolean sentOnly) {
        List<String> out = new ArrayList<>();
        out.add(Msg.t(
                "Değerler raporların GÜNCEL hâlidir. Gönderildikten sonra değişen raporlar changed_after_send = true ile işaretlidir; o raporlarda değer, gönderilen PDF'tekinden farklı olabilir.",
                "Values are the reports' CURRENT values. Reports changed after they were sent carry changed_after_send = true; their values may differ from the PDF that was sent."));
        out.add(sentOnly
                ? Msg.t("Yalnız en az bir kez gönderilmiş raporlar listelenir; taslaklar için status=all kullanın.",
                        "Only reports that were sent at least once are listed; use status=all to include drafts.")
                : Msg.t("Taslaklar dahil tüm raporlar listelenir; gönderim durumu report_status ve sent_at sütunlarındadır.",
                        "Every report is listed, drafts included; the send state is in the report_status and sent_at columns."));
        out.add(Msg.t(
                "Her rapor kendi kopyalanmış şablonuyla okunur: alan adı ve türü o aya aittir. Aylar arasında karşılaştırırken field_id ile field_type'ı birlikte kullanın.",
                "Each report is read with its own copied template: the field label and type belong to that month. When comparing months, use field_id together with field_type."));
        out.add(Msg.t(
                "Hesaplanan oranlar PDF'teki gibi bu dışa aktarım anında CANLI hesaplanır (raporun girilen adetlerinden; önceki aya göre değişim aynı kanalın bir önceki takvim ayının raporundan) ve computed = true taşır. computed = false olan bir yüzde ya da evet/hayır alanı elle girilmiştir: formül kullanılamadığında (ör. kanalın ilk raporu, geçen ayın değeri yok) girilen değer kalır.",
                "Calculated rates are computed LIVE at export time, like the PDF (from the counts entered in the report; the change versus last month from the same channel's report for the previous calendar month) and carry computed = true. A percent or yes/no field with computed = false was entered by hand: when the formula can't be used (e.g. the channel's first report, no value for last month) the entered value is kept."));
        out.add(Msg.t(
                "exempt = true: alan o ay muaftır (bağlı evet/hayır sorusu Hayır yanıtlandı) — değer boştur, sıfır değildir.",
                "exempt = true: the field is exempt that month (its yes/no question was answered No) — the value is empty, not zero."));
        out.add(Msg.t(
                "approval_state yalnız onay akışı olan kanallarda doludur ve kanalın BUGÜNKÜ yönetici alıcısı listesine bakar (ekrandaki gibi): liste sonradan boşaltılan kanalın eski raporlarında da boştur, sonradan eklenen kanalın eski raporları none görünür.",
                "approval_state is filled only for channels with an approval flow and follows the channel's CURRENT executive recipient list (as on screen): it is empty for older reports of a channel whose list was cleared later, and older reports of a channel that got a list later show none."));
        out.add(Msg.t(
                "Kişi bilgisi (alıcılar, gönderen, onaylayan, düzenleyen) bu dışa aktarımda yer almaz.",
                "No personal data (recipients, sender, approver, editor) is included in this export."));
        return out;
    }

    // ── Hız sınırı ───────────────────────────────────────────────────────────────────────────────────

    /** Sınır içindeyse isteği kaydeder ve {@code null} döner; doluysa en eski isteğin pencereden düşmesine kalan saniye (≥ 1). */
    Long reserveExport(String key) {
        long now = exportClock.getAsLong();
        synchronized (exportCalls) {
            if (exportCalls.size() > 1_000) {
                exportCalls.values().removeIf(q -> {
                    while (!q.isEmpty() && now - q.peekFirst() >= EXPORT_WINDOW_MS) q.pollFirst();
                    return q.isEmpty();
                });
                if (exportCalls.size() > 10_000) exportCalls.clear();
            }
            Deque<Long> q = exportCalls.computeIfAbsent(key, k -> new ArrayDeque<>());
            while (!q.isEmpty() && now - q.peekFirst() >= EXPORT_WINDOW_MS) q.pollFirst();
            if (q.size() >= EXPORT_PER_MINUTE) {
                long ms = q.peekFirst() + EXPORT_WINDOW_MS - now;
                return Math.max(1L, (ms + 999) / 1000);
            }
            q.addLast(now);
            return null;
        }
    }

    /**
     * Az önce ayrılan hakkı geri verir (dolu → 429 EXPORT_BUSY: deneme sayılmaz). Kuyruğun EN YENİ kaydı silinir — aynı
     * kişinin eşzamanlı başka bir isteği araya girdiyse onunki silinir; sayı aynı kalır (ikisi de pencerede).
     */
    void cancelReservation(String key) {
        synchronized (exportCalls) {
            Deque<Long> q = exportCalls.get(key);
            if (q != null && !q.isEmpty()) q.pollLast();
        }
    }

    private static String rateKey(HttpSession session) {
        Object v = session.getAttribute("userId");
        if (v != null) return "u:" + v;
        Object u = session.getAttribute("username");
        return u != null ? "n:" + u : "s:" + session.getId();
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────────

    private static Long idOrFail(String raw, String field, String message) {
        try {
            long v = Long.parseLong(raw.trim());
            if (v > 0) return v;
        } catch (NumberFormatException ignored) { /* aşağıda */ }
        throw new FieldValidationException(field, message);
    }

    private static Integer intOrNull(String raw) {
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static NoSuchElementException notFoundChannel() {
        return new NoSuchElementException(Msg.t(
                "Kanal bulunamadı; silinmiş olabilir ya da görme yetkiniz yok. Listeyi yenileyin.",
                "The channel wasn't found; it may have been deleted or you can't see it. Refresh the list."));
    }

    private static NoSuchElementException notFoundTeam() {
        return new NoSuchElementException(Msg.t(
                "Takım bulunamadı; silinmiş olabilir ya da bu takımın raporlarını görme yetkiniz yok. Listeden bir takım seçin.",
                "The team wasn't found; it may have been deleted, or you can't see this team's reports. Pick a team from the list."));
    }

    private static Map<String, Object> errorBody(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("error", message);
        String rid = MDC.get("requestId");
        if (rid != null) body.put("request_id", rid);
        return body;
    }
}
