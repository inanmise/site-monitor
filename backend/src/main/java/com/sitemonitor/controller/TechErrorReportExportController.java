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
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.ObjectMapper;

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
 * {@link #EXPORT_PER_MINUTE} istek (429 {@code RATE_LIMITED} + {@code Retry-After}). Kişi başına aynı anda TEK dışa
 * aktarım (bekleyen ya da süren — yanıt, CSV ya da JSON, yazılıp bitene kadar): ikinci istek BEKLEMEDEN 429 {@code EXPORT_BUSY}. Pod
 * genelinde aynı anda en çok {@link #EXPORT_CONCURRENCY} dışa aktarım HAZIRLANIR (izin yalnız okuma + hesap süresince
 * tutulur; {@link TechErrorExportService#export} döner dönmez — yanıt yazılmadan önce — bırakılır, yavaş bir istemci izni
 * tutamaz); dolu → en çok {@link #EXPORT_WAIT_MS} beklenir, sonra 429 {@code EXPORT_BUSY} + {@code Retry-After}. Meşgul
 * reddi dakikalık hakkı yemez ama ayrı, küçük bir bütçeden düşer: kişi başına dakikada {@link #EXPORT_BUSY_PER_MINUTE}
 * meşgul reddinden sonra istekler beklemeden 429 {@code RATE_LIMITED} (Retry-After'ı yok sayan döngü sınırlanır).
 * Okumadan önce kuyruktaki gönderimler sonuçlandırılır ({@code reconcileQueued} — diğer okuma uçları gibi). Her başarılı
 * dışa aktarım {@code TECH_ERROR_REPORT_EXPORT} denetim satırı yazar (süzgeç + rapor / satır sayısı). CSV de JSON da
 * doğrudan yanıt akışına yazılır (JSON: {@link #writeJson}; gövde bellekte tek bir metne çevrilmez).
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
    /**
     * Kişi başına dakikadaki en çok meşgul reddi ({@code EXPORT_BUSY}); aşan kişi pencere boşalana dek beklemeden
     * {@code RATE_LIMITED} alır — meşgul reddi dakikalık hakkı iade ettiği için Retry-After'ı yok sayan bir döngü başka
     * türlü hiç sınırlanmazdı.
     */
    static final int EXPORT_BUSY_PER_MINUTE = 12;

    private final TechErrorReportService service;
    private final TechErrorExportService exporter;
    private final TeamRepository teams;
    private final PermissionService permissions;
    private final AuditService auditService;
    /** Uygulamanın ObjectMapper'ı: JSON gövdesi ileti dönüştürücüsüyle aynı değer serileştirmesiyle akıtılır. */
    private final ObjectMapper json;

    /** Kullanıcı → son istek zamanları (ms); pod başına, sınırlı (1 000 anahtarda süresi geçenler atılır, 10 000'de sıfırlanır). */
    private final Map<String, Deque<Long>> exportCalls = new HashMap<>();
    /** Kullanıcı → son meşgul reddi zamanları (ms); {@link #exportCalls} ile aynı sınırlar. */
    final Map<String, Deque<Long>> busyCalls = new HashMap<>();
    /**
     * Şu an dışa aktarımı bekleyen ya da süren kullanıcılar (kişi başına tek). Her giriş isteğin {@code finally}'sinde
     * silinir: boyutu eşzamanlı istek sayısıyla (Tomcat iş parçacıkları) sınırlıdır.
     */
    final Set<String> running = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Saat (testlerde ileri sarılır). */
    java.util.function.LongSupplier exportClock = System::currentTimeMillis;
    /** Eşzamanlılık izinleri (adil; testler doldurur). */
    final java.util.concurrent.Semaphore exportSlots = new java.util.concurrent.Semaphore(EXPORT_CONCURRENCY, true);
    /** İzin bekleme süresi (ms; testlerde kısaltılır). */
    long exportWaitMs = EXPORT_WAIT_MS;

    public TechErrorReportExportController(TechErrorReportService service, TechErrorExportService exporter,
                                           TeamRepository teams, PermissionService permissions, AuditService auditService,
                                           ObjectMapper json) {
        this.service = service;
        this.exporter = exporter;
        this.teams = teams;
        this.permissions = permissions;
        this.auditService = auditService;
        this.json = json;
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
        // Meşgul bütçesi tükendiyse BEKLEMEDEN reddedilir (dakikalık hak ayrılmaz)
        Long busyRetry = busyRetryAfter(rateKey);
        if (busyRetry != null) {
            Map<String, Object> body = errorBody("RATE_LIMITED", Msg.t(
                    "Dışa aktarım meşgulken çok sık denendi: kişi başına dakikada en çok " + EXPORT_BUSY_PER_MINUTE + " deneme reddedilir, sonrası sınırlanır. " + busyRetry + " saniye sonra yeniden deneyin.",
                    "Too many attempts while the export was busy: at most " + EXPORT_BUSY_PER_MINUTE + " refused attempts per person per minute, after that requests are limited. Try again in " + busyRetry + " seconds."));
            body.put("retry_after_seconds", busyRetry);
            return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER, String.valueOf(busyRetry)).body(body);
        }
        Long retryAfter = reserveExport(rateKey);
        if (retryAfter != null) {
            Map<String, Object> body = errorBody("RATE_LIMITED", Msg.t(
                    "Dışa aktarım sınırına ulaşıldı: kişi başına dakikada en çok " + EXPORT_PER_MINUTE + " dışa aktarım. " + retryAfter + " saniye sonra yeniden deneyin.",
                    "Export limit reached: at most " + EXPORT_PER_MINUTE + " exports per person per minute. Try again in " + retryAfter + " seconds."));
            body.put("retry_after_seconds", retryAfter);
            return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter)).body(body);
        }
        // Kişi başına TEK dışa aktarım (bekleyen ya da süren): ikinci istek beklemeden reddedilir — kimse birden çok
        // Tomcat iş parçacığını izin kuyruğunda tutamaz. Reddedilen istek dakikalık hakkı yemez (meşgul bütçesinden düşer).
        if (!running.add(rateKey)) {
            cancelReservation(rateKey);
            noteBusy(rateKey);
            long wait = Math.max(1L, EXPORT_BUSY_RETRY_SECONDS);
            Map<String, Object> body = errorBody("EXPORT_BUSY", Msg.t(
                    "Önceki dışa aktarımınız hâlâ hazırlanıyor ya da indiriliyor; kişi başına aynı anda tek dışa aktarım yapılabilir. O bitince (yaklaşık " + wait + " saniye sonra) yeniden deneyin; bu deneme dakikalık hakkınızdan düşülmedi.",
                    "Your previous export is still being prepared or downloaded; one export per person can run at a time. Try again when it has finished (in about " + wait + " seconds); this attempt did not count against your per-minute limit."));
            body.put("retry_after_seconds", wait);
            return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER, String.valueOf(wait)).body(body);
        }
        try {
            // Pod genelinde en çok EXPORT_CONCURRENCY dışa aktarım aynı anda HAZIRLANIR (bellek: her biri en çok 2 ×
            // MAX_REPORTS rapor okur). Dolu → 429 EXPORT_BUSY; reddedilen istek kişinin dakikalık hakkını YEMEZ.
            if (!acquireExportSlot()) {
                cancelReservation(rateKey);
                noteBusy(rateKey);
                long wait = Math.max(1L, EXPORT_BUSY_RETRY_SECONDS);
                Map<String, Object> body = errorBody("EXPORT_BUSY", Msg.t(
                        "Şu anda başka dışa aktarımlar hazırlanıyor (aynı anda en çok " + EXPORT_CONCURRENCY + "). " + wait + " saniye sonra yeniden deneyin; bu deneme dakikalık hakkınızdan düşülmedi.",
                        "Other exports are being prepared right now (at most " + EXPORT_CONCURRENCY + " at a time). Try again in " + wait + " seconds; this attempt did not count against your per-minute limit."));
                body.put("retry_after_seconds", wait);
                return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER, String.valueOf(wait)).body(body);
            }
            TechErrorExportService.Result res;
            try {
                // Kuyruğa alınmış gönderimler sonuçlandıysa durum güncel (diğer okuma uçları gibi: genel bakış, liste, rapor)
                List<Long> channelIds = chs.stream().map(TechErrorChannel::getId).toList();
                if (!channelIds.isEmpty()) service.reconcileQueued(channelIds);
                res = exporter.export(chs, teamNames, new TechErrorExportService.Filter(y, m, "sent".equals(st)), service.now());
            } catch (TechErrorExportService.TooLargeException e) {
                Map<String, Object> body = errorBody("EXPORT_TOO_LARGE", Msg.t(
                        "Seçilen süzgeçle " + TechErrorExportService.MAX_REPORTS + " rapordan fazlası çıkıyor. Bir ay, bir takım ya da tek bir kanal seçerek daraltıp yeniden deneyin.",
                        "This filter matches more than " + TechErrorExportService.MAX_REPORTS + " reports. Narrow it to one month, one team or a single channel and try again."));
                body.put("max_reports", TechErrorExportService.MAX_REPORTS);
                return ResponseEntity.status(400).body(body);
            } finally {
                // Veri artık bellekte: ağır kısım (okuma + canlı hesap) bitti. İzin YANIT YAZILMADAN bırakılır — yavaş ya da
                // duraklamış bir istemci (CSV akışı dakikalar sürebilir) pod'un iki izninden birini tutamaz.
                exportSlots.release();
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
                // hazırdır, yalnız yazma (istemci kopması) kalır. Pod izni zaten bırakıldı; kişinin "süren dışa aktarım"
                // işareti yazma bitene dek kalır (bir kişi aynı anda yalnız bir sonucu bellekte tutar).
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
            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("team", teamId != null ? teamId : (channelId != null ? null : "all"));
            filter.put("channel", channelId);
            filter.put("year", y);
            filter.put("month", m);
            filter.put("status", st);
            // JSON de doğrudan yanıt akışına yazılır (inceleme 2026-10-18): gövde ResponseEntity ile dönseydi global olmayan
            // görüntüleyicide UserRefResponseAdvice (UserRefWire.toOpaqueFast) tüm gövdeyi ÖNCE tek bir metne çevirirdi —
            // 5 000 raporluk tavanda ~100–150 MB; birkaç eşzamanlı çekiş 1 GiB'lik pod'u düşürebilirdi. Tavsiye yalnız ileti
            // dönüştürücüsünden geçen gövdeye uygulanır; burada uygulanmaz ve GEREKMEZ: bu yükte kullanıcı kimliği yoktur
            // (servis izdüşümü kişi sütunu okumaz; sütun / anahtar adlarının hiçbiri UserRefWire.KEYS'te değil —
            // TechErrorReportExportControllerTest.jsonCarriesNoUserRefKeys bunu kilitler), yani çeviri bir şey değiştirmezdi.
            // Kişinin "süren dışa aktarım" işareti CSV'deki gibi yazma bitene dek kalır (finally); pod izni zaten bırakıldı.
            response.setStatus(200);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            // AUTO_CLOSE_CONTENT kapalı: yazma yarıda kesilirse kapanışta eksik diziler / nesneler KAPATILMAZ — istemci
            // geçersiz JSON görür; yarım satırlı ama "success: true" görünen geçerli bir gövde (eksik Excel) oluşmaz
            try (JsonGenerator gen = json.writer().without(StreamWriteFeature.AUTO_CLOSE_CONTENT)
                    .createGenerator(StreamUtils.nonClosing(response.getOutputStream()), JsonEncoding.UTF8)) {
                writeJson(gen, res, notes("sent".equals(st)), filter);
            }
            return null;
        } finally {
            running.remove(rateKey);
        }
    }

    /**
     * JSON gövdesi, satır satır üretici ile: {@code {success, data:{columns, rows, notes, reports, report_count, row_count,
     * max_reports, filter}}} — eski {@code ResponseEntity} gövdesiyle aynı anahtarlar, aynı sıra, aynı değer serileştirmesi
     * (uygulamanın ObjectMapper'ı; testte eski haritayla bayt bayt karşılaştırılır). Satırlar tek tek yazılır: tüm gövde
     * bellekte ikinci kez (metin / ağaç olarak) kurulmaz.
     */
    static void writeJson(JsonGenerator gen, TechErrorExportService.Result res, List<String> notes, Map<String, Object> filter) {
        gen.writeStartObject();
        gen.writeBooleanProperty("success", true);
        gen.writeName("data");
        gen.writeStartObject();
        gen.writePOJOProperty("columns", TechErrorExportService.COLUMNS);
        gen.writeName("rows");
        gen.writeStartArray();
        for (List<Object> r : res.rows()) gen.writePOJO(r);
        gen.writeEndArray();
        gen.writePOJOProperty("notes", notes);
        gen.writeName("reports");
        gen.writeStartArray();
        for (TechErrorExportService.ReportMeta r : res.reports()) gen.writePOJO(reportRow(r));
        gen.writeEndArray();
        gen.writeNumberProperty("report_count", res.reports().size());
        gen.writeNumberProperty("row_count", res.rows().size());
        gen.writeNumberProperty("max_reports", TechErrorExportService.MAX_REPORTS);
        gen.writePOJOProperty("filter", filter);
        gen.writeEndObject();
        gen.writeEndObject();
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

    /** Rapor künyesi satırı ({@code reports[]}) — kişi verisi yok; anahtarlar {@code UserRefWire.KEYS} dışında. */
    static Map<String, Object> reportRow(TechErrorExportService.ReportMeta r) {
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
        return m;
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
                "Hesaplanan oranlar PDF'teki gibi bu dışa aktarım anında CANLI hesaplanır (raporun girilen adetlerinden; önceki aya göre değişim aynı kanalın bir önceki takvim ayının raporundan) ve computed = true taşır. computed = false tek başına \"elle girildi\" demek değildir: formülü olan alanda nedeni calc_status söyler — MISSING / ZERO / NEGATIVE / EQUAL / RANGE: bu ay sonuç yok (ör. bir adet boş, bölen sıfır; değer boştur, elle girilmemiştir); MANUAL / BAD_REF: formül kullanılamıyor (ör. kanalın ilk raporunda geçen ay yok, formülün alanı şablondan çıkmış) ve elle girilen değer kalır. Formülü olmayan alanda calc_status boştur; değer her zaman girilen değerdir.",
                "Calculated rates are computed LIVE at export time, like the PDF (from the counts entered in the report; the change versus last month from the same channel's report for the previous calendar month) and carry computed = true. computed = false alone does not mean \"entered by hand\": for a field with a formula, calc_status tells why — MISSING / ZERO / NEGATIVE / EQUAL / RANGE: no result this month (e.g. a count is empty, the base is zero; the value is empty, nothing was typed); MANUAL / BAD_REF: the formula can't be used (e.g. the channel's first report has no last month, or a field the formula uses was removed) and the value entered by hand is kept. Fields without a formula have an empty calc_status; their value is always the entered one."));
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
            prune(exportCalls, now);
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

    /**
     * Meşgul bütçesi tükendiyse (pencerede {@link #EXPORT_BUSY_PER_MINUTE} red) en eski reddin düşmesine kalan saniye
     * (≥ 1), değilse {@code null}. Kayıt yazmaz.
     */
    Long busyRetryAfter(String key) {
        long now = exportClock.getAsLong();
        synchronized (busyCalls) {
            Deque<Long> q = busyCalls.get(key);
            if (q == null) return null;
            while (!q.isEmpty() && now - q.peekFirst() >= EXPORT_WINDOW_MS) q.pollFirst();
            if (q.isEmpty()) {
                busyCalls.remove(key);
                return null;
            }
            if (q.size() < EXPORT_BUSY_PER_MINUTE) return null;
            long ms = q.peekFirst() + EXPORT_WINDOW_MS - now;
            return Math.max(1L, (ms + 999) / 1000);
        }
    }

    /** Meşgul reddini kaydeder (bütçe {@link #busyRetryAfter} ile okunur); harita {@link #exportCalls} gibi sınırlı. */
    void noteBusy(String key) {
        long now = exportClock.getAsLong();
        synchronized (busyCalls) {
            prune(busyCalls, now);
            Deque<Long> q = busyCalls.computeIfAbsent(key, k -> new ArrayDeque<>());
            while (!q.isEmpty() && now - q.peekFirst() >= EXPORT_WINDOW_MS) q.pollFirst();
            q.addLast(now);
            while (q.size() > EXPORT_BUSY_PER_MINUTE) q.pollFirst();          // pencere başına en çok bütçe kadar kayıt
        }
    }

    /** 1 000 anahtarı aşan haritada süresi geçen kayıtları atar; yine de 10 000'i aşarsa sıfırlar (çağıran kilitli). */
    private static void prune(Map<String, Deque<Long>> calls, long now) {
        if (calls.size() <= 1_000) return;
        calls.values().removeIf(q -> {
            while (!q.isEmpty() && now - q.peekFirst() >= EXPORT_WINDOW_MS) q.pollFirst();
            return q.isEmpty();
        });
        if (calls.size() > 10_000) calls.clear();
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
