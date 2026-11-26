package com.sitemonitor.controller;

import com.sitemonitor.model.IncidentImage;
import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.IncidentAlertLinkService;
import com.sitemonitor.service.IncidentNotificationService;
import com.sitemonitor.service.IncidentService;
import com.sitemonitor.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import com.sitemonitor.service.incident.IncidentExportRows;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SRE Olay & Hata Geçmişi REST API'si — manuel ledger CRUD + filtreli arama + trend.
 * İzin: görüntüleme = incidents.view, yazma/silme = incidents.manage (katalog grant'leri,
 * Permission Matrix'ten yönetilir; defaults SchedulerService.seedMissingDefaults ile backfill).
 * AuthInterceptor /api/** için oturum zorunluluğunu zaten uygular. Mevcut akışlardan izole.
 */
@Slf4j
@RestController
@RequestMapping("/api/incidents")
@RequiredArgsConstructor
public class IncidentController {

    private final IncidentService service;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final IncidentNotificationService notificationService;
    /** Alarmdan açılan kayıt (2026-10-01): alert_event_id doğrulaması + alarm detayının bağlı-kayıt sorgusu. */
    private final IncidentAlertLinkService alertLinks;
    /** Postmortem + aksiyon rozetleri ve olay silinince temizlik (2026-10-11) — dilim testlerinde bean yoksa atlanır. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.incident.IncidentPostmortemService postmortems;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Özet listede ({@code fields=summary}) satıra konmayan dört markdown alanı (2026-11-20). */
    static final List<String> MARKDOWN_FIELDS = List.of("rca_summary", "description", "resolution_steps", "business_impact");

    /**
     * Süzgeçli liste. Süzgeç parametreleri ({@link #filterFrom}): {@code q} (başlık, alan, kök neden, açıklama, çözüm, iş
     * etkisi, etiket, üç kod, etkilenen uygulama / sistem / servis, sorun türü; {@code #123} / {@code 123} kayıt kimliği),
     * {@code severity}, {@code category}, {@code status}, {@code service} / {@code channel} (alt dize — eski), {@code since},
     * {@code until}, {@code team_id}, {@code sla_breached}, {@code open}; 2026-11-26 ("Bul ve Raporla"): {@code service_exact},
     * {@code channel_exact}, {@code problem_type}, {@code tag} (CSV alanında TAM öğe), {@code error_code}, {@code created_by}
     * (tam), {@code postmortem} (none | draft | in_review | published), {@code sort} ({@link IncidentService#SORTS}). Arama
     * varken satır {@code match_field} taşır (eşleşen ilk alan). Yanıt {@code ext_filters=true}: bu sürüm yeni süzgeçleri
     * uygular (eski pod bilmez — arayüz uyarır).
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String fields,
            @RequestParam(required = false) String sort,
            @RequestParam Map<String, String> params,
            HttpSession session) {
        requireView(session);
        int sz = Math.max(1, Math.min(size, 200));
        IncidentService.ListFilter f = filterFrom(params, session);
        Page<IncidentRecord> result = this.service.list(f, incidentViewScope(session),
                PageRequest.of(Math.max(0, page), sz), sort);
        // Özet satırlar (2026-11-20, "Hızlı Giriş"): fields=summary → dört markdown metni (≤ 20 000 karakter her biri) satırda
        // yok + markdown_omitted=true; ayrıntı / düzenleme kaydı GET /{id} ile taze okur. Parametre yok = bugünkü tam satır.
        boolean summary = "summary".equalsIgnoreCase(fields == null ? "" : fields.trim());
        String q = f.q();
        List<Map<String, Object>> rows = result.getContent().stream().map(e -> {
            Map<String, Object> d = markWritable(session, e, dto(e));
            if (q != null && !q.isBlank()) d.put("match_field", IncidentService.matchField(e, q));
            if (summary) {
                for (String k : MARKDOWN_FIELDS) d.remove(k);
                d.put("markdown_omitted", true);
            }
            return d;
        }).toList();
        addPostmortemSummaries(rows);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", rows);
        body.put("total", result.getTotalElements());
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("sort", IncidentService.sortKey(sort));
        body.put("ext_filters", true);
        return ok(body);
    }

    /**
     * Eğilim + özet — listenin AYNI süzgeç parametreleriyle (2026-11-26). Tarih aralığı dışında süzgeç yoksa sayılar bugünküyle
     * aynı ({@code filtered=false}); varsa süzgece göre ({@code filtered=true}).
     */
    /**
     * Eğilimler. {@code view=daily} (inceleme 2026-11-26): yalnız günlük seri (grafik) — özet, kırılımlar, MTTD / ortancalar
     * hesaplanmaz. Yanıt kökünde {@code ext_filters: true}: bu sürüm gelişmiş süzgeçleri eğilimde de uygular (eski pod
     * göndermez → arayüz "sayılar süzülmüyor" der).
     */
    @GetMapping("/trends")
    public ResponseEntity<Map<String, Object>> trends(@RequestParam Map<String, String> params, HttpSession session) {
        requireView(session);
        boolean dailyOnly = "daily".equals(params == null ? null : params.get("view"));
        IncidentService.ListFilter f = filterFrom(params, session);
        List<Long> scope = incidentViewScope(session);
        return ok(Map.of("data", dailyOnly ? service.trends(f, scope, true) : service.trends(f, scope), "ext_filters", true));
    }

    /**
     * Sorgu parametreleri → ham süzgeç (2026-11-26). {@code team_id} sayı, {@code sla_breached} / {@code open} doğru-yanlış
     * olmalı (okunamayan değer alan adlı 400); metinler serviste doğrulanır ({@link IncidentService#toQuery}).
     */
    static IncidentService.ListFilter filterFrom(Map<String, String> p, HttpSession session) {
        Map<String, String> m = p == null ? Map.of() : p;
        String cb = m.get("created_by");
        if (cb != null && CREATED_BY_ME.equalsIgnoreCase(cb.trim())) {
            Object u = session == null ? null : session.getAttribute("username");
            if (u != null) {
                Map<String, String> copy = new java.util.HashMap<>(m);
                copy.put("created_by", String.valueOf(u));
                m = copy;
            }
        }
        return filterFrom(m);
    }

    /** {@code created_by=@me} → oturumun kullanıcı adı (arayüzün "Benim açtıklarım" süzgeci; kullanıcı adları "@" ile başlamaz). */
    static final String CREATED_BY_ME = "@me";

    static IncidentService.ListFilter filterFrom(Map<String, String> p) {
        Map<String, String> m = p == null ? Map.of() : p;
        return new IncidentService.ListFilter(
                m.get("q"), m.get("severity"), m.get("category"), m.get("status"), m.get("service"), m.get("channel"),
                m.get("since"), m.get("until"), longParam(m, "team_id"), boolParam(m, "sla_breached"), boolParam(m, "open"),
                m.get("service_exact"), m.get("channel_exact"), m.get("problem_type"), m.get("error_code"), m.get("tag"),
                m.get("created_by"), m.get("postmortem"));
    }

    private static Long longParam(Map<String, String> m, String key) {
        String v = m.get(key);
        if (v == null || v.isBlank()) return null;
        try {
            return Long.valueOf(v.trim());
        } catch (NumberFormatException e) {
            throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException(key, com.sitemonitor.util.Msg.t(
                    "Takım süzgeci okunamadı (sayı olmalı). Takım seçimini temizleyip yeniden seçin.",
                    "The team filter could not be read (it must be a number). Clear the team choice and pick it again."));
        }
    }

    private static Boolean boolParam(Map<String, String> m, String key) {
        String v = m.get(key);
        if (v == null || v.isBlank()) return null;
        String s = v.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on")) return Boolean.TRUE;
        if (s.equals("false") || s.equals("0") || s.equals("no") || s.equals("off")) return Boolean.FALSE;
        throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException(key, com.sitemonitor.util.Msg.t(
                "Süzgeç değeri okunamadı (true ya da false olmalı). Süzgeçleri temizleyip yeniden deneyin.",
                "The filter value could not be read (it must be true or false). Clear the filters and try again."));
    }

    // ── Dışa aktarım (2026-11-26, "Bul ve Raporla") ──────────────────────────────────────────────────────────────────
    /** Dışa aktarım satır tavanı. */
    static final int EXPORT_MAX_ROWS = 10_000;
    /** Okuma parçası — kayıtlar (dört markdown metniyle) parça parça okunur, bellek parça başına sınırlı. */
    static final int EXPORT_CHUNK = 200;
    /** Kişi başına dakikada dışa aktarım (pod belleği — yük sınırı). */
    static final int EXPORT_PER_MINUTE = 6;
    /** Dışa aktarımdaki düz metinlerin (dört markdown alanı) toplam karakter bütçesi — aşınca kalan satırlarda metin boş. */
    static final long EXPORT_TEXT_BUDGET = 4_000_000L;
    private final Map<String, java.util.Deque<Long>> exportCalls = new java.util.HashMap<>();
    private final java.util.Set<String> exportRunning = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * Pod başına aynı anda hazırlanan dışa aktarım (inceleme 2026-11-26): her biri en çok 10 000 kayıt + 4 M karakter metin
     * okur ve akıtır — N kişinin aynı anda başlattığı dışa aktarım podu boğmasın. Dolu → 429 {@code EXPORT_BUSY} +
     * {@code Retry-After} (kişinin dakikalık hakkı iade edilir); bekleme yok (Tomcat iş parçacığı tutulmaz).
     */
    static final int EXPORT_POD_SLOTS = 2;
    /** Pod dolu yanıtının önerdiği bekleme (sn). */
    static final long EXPORT_POD_RETRY_SECONDS = 10L;
    final java.util.concurrent.Semaphore exportPodSlots = new java.util.concurrent.Semaphore(EXPORT_POD_SLOTS);

    /** Kullanıcı adı → görünen ad (dışa aktarımın "kaydeden" sütunları) — dilim testlerinde bean yoksa kullanıcı adı yazılır. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.repository.AppUserRepository userRepo;

    /**
     * Liste dışa aktarımı (2026-11-26) — LİSTEYLE AYNI süzgeç + sıralama + kapsam, en çok {@value #EXPORT_MAX_ROWS} kayıt
     * ({@code X-Export-Truncated}, {@code X-Export-Max-Rows}). {@code format=csv} (varsayılan; BOM, formül koruması, İstanbul
     * saati) | {@code json} (istemci Excel'i kurar). Kişiler yalnız görünen ad; markdown düz metin (görsel yok), alan başına
     * {@value IncidentExportRows#MAX_TEXT_CHARS} karakter, toplam {@value #EXPORT_TEXT_BUDGET} ({@code X-Export-Text-Truncated}).
     * Kişi başına aynı anda tek + dakikada {@value #EXPORT_PER_MINUTE} (429 {@code EXPORT_BUSY} / {@code RATE_LIMITED}).
     * Pod başına aynı anda en çok {@value #EXPORT_POD_SLOTS} (dolu → 429 {@code EXPORT_BUSY}, dakikalık hak iade edilir).
     * Denetim {@code INCIDENT_EXPORT} (süzgeç + satır sayısı). Kapı {@code incidents.view}.
     */
    @GetMapping("/export")
    public ResponseEntity<?> export(@RequestParam(required = false) String format,
                                    @RequestParam(required = false) String sort,
                                    @RequestParam Map<String, String> params,
                                    HttpSession session, HttpServletRequest request,
                                    jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        requireView(session);
        String fmt = format == null || format.isBlank() ? "csv" : format.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"csv".equals(fmt) && !"json".equals(fmt))
            throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("format",
                    com.sitemonitor.util.Msg.t("Biçim csv ya da json olmalı.", "The format must be csv or json."));
        IncidentService.ListFilter f = filterFrom(params, session);
        IncidentService.toQuery(f, null);   // okunamayan süzgeç → alan adlı 400, hak düşülmeden
        String who = exportKey(session);
        boolean podSlot = false;
        if (!exportRunning.add(who)) {
            return tooMany("EXPORT_BUSY", 5L, com.sitemonitor.util.Msg.t(
                    "Önceki dışa aktarımınız hâlâ hazırlanıyor; kişi başına aynı anda tek dışa aktarım yapılabilir. Bitince yeniden deneyin.",
                    "Your previous export is still being prepared; one export per person can run at a time. Try again when it has finished."));
        }
        try {
            Long retry = reserveExport(who);
            if (retry != null) {
                return tooMany("RATE_LIMITED", retry, com.sitemonitor.util.Msg.t(
                        "Dışa aktarım sınırına ulaşıldı: kişi başına dakikada en çok " + EXPORT_PER_MINUTE + " dışa aktarım. "
                                + retry + " saniye sonra yeniden deneyin.",
                        "Export limit reached: at most " + EXPORT_PER_MINUTE + " exports per person per minute. Try again in "
                                + retry + " seconds."));
            }
            if (!exportPodSlots.tryAcquire()) {
                refundExport(who);   // hiçbir şey okunmadı — kişinin dakikalık hakkı düşmez
                return tooMany("EXPORT_BUSY", EXPORT_POD_RETRY_SECONDS, com.sitemonitor.util.Msg.t(
                        "Sunucuda şu anda başka dışa aktarımlar hazırlanıyor; " + EXPORT_POD_RETRY_SECONDS
                                + " saniye sonra yeniden deneyin.",
                        "Other exports are being prepared on the server right now; try again in " + EXPORT_POD_RETRY_SECONDS
                                + " seconds."));
            }
            podSlot = true;
            java.util.function.Function<String, String> names = displayNames();
            List<Long> scope = incidentViewScope(session);
            // Kimlikler tek dar sorguyla (sıralı, tavan + 1), kayıtlar 200'lük kimlik parçalarıyla birincil anahtardan — ofsetli
            // sayfalamanın büyüyen OFFSET'i ve her dolu sayfadaki yeniden sayım yok; arada silinen kayıt yalnız atlanır.
            IncidentService.ExportIds plan = service.exportIds(f, scope, sort, EXPORT_MAX_ROWS);
            List<Long> ids = plan.ids();
            long total = plan.total();
            List<Map<String, Object>> rows = new java.util.ArrayList<>(ids.size());
            long textUsed = 0;
            boolean textTruncated = false;
            int clipped = 0;
            for (int k = 0; k < ids.size(); k += EXPORT_CHUNK) {
                List<IncidentRecord> chunk = service.loadOrdered(ids.subList(k, Math.min(ids.size(), k + EXPORT_CHUNK)));
                Map<Long, String> pm = postmortemStates(chunk);
                for (IncidentRecord e : chunk) {
                    boolean withText = textUsed < EXPORT_TEXT_BUDGET;
                    if (!withText) textTruncated = true;
                    IncidentExportRows.Row r = IncidentExportRows.row(e, names, pm.get(e.getId()), withText);
                    for (String key : MARKDOWN_FIELDS) {
                        Object v = r.values().get(key);
                        if (v instanceof String s) textUsed += s.length();
                    }
                    clipped += r.textClipped();
                    rows.add(r.values());
                }
            }
            boolean truncated = total > ids.size();
            try {
                auditService.recordAction("INCIDENT_EXPORT", session, request, "INCIDENT", "export",
                        com.sitemonitor.service.AuditDetail.of("format", fmt, "rows", rows.size(), "total", total,
                                "truncated", truncated, "q", f.q(), "severity", f.severity(), "status", f.status(),
                                "category", f.category(), "channel", f.channelExact() != null ? f.channelExact() : f.channel(),
                                "service", f.serviceExact() != null ? f.serviceExact() : f.service(), "team_id", f.teamId(),
                                "since", f.since(), "until", f.until(), "problem_type", f.problemType(),
                                "error_code", f.errorCode(), "tag", f.tag(), "created_by", f.createdBy(),
                                "postmortem", f.postmortem(), "sort", IncidentService.sortKey(sort)));
            } catch (Exception e) {
                log.debug("INCIDENT_EXPORT denetimi yazılamadı: {}", e.toString());
            }
            // Gövde DOĞRUDAN yanıt akışına yazılır (2026-11-26, teknik hata dışa aktarımı deseni): ResponseEntity ile dönseydi
            // global olmayan görüntüleyicide UserRefResponseAdvice tüm gövdeyi önce tek bir metne çevirirdi (10 000 satır + 4 M
            // karakter metinle onlarca MB). Tavsiye burada uygulanmaz ve GEREKMEZ: sütun / anahtar adlarının hiçbiri
            // UserRefWire.KEYS'te değil (kişiler yalnız görünen ad — IncidentFindReportControllerTest.jsonCarriesNoUserRefKeys).
            // Kişinin "süren dışa aktarım" işareti gövde TÜMÜYLE yazılana dek kalır (finally).
            response.setStatus(200);
            response.setHeader(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader("X-Export-Truncated", String.valueOf(truncated));
            response.setHeader("X-Export-Max-Rows", String.valueOf(EXPORT_MAX_ROWS));
            response.setHeader("X-Export-Text-Truncated", String.valueOf(textTruncated));
            if ("json".equals(fmt)) {
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                // AUTO_CLOSE_CONTENT kapalı: yazma yarıda kesilirse eksik diziler / nesneler KAPATILMAZ — istemci geçersiz JSON
                // görür; yarım satırlı ama "success: true" görünen geçerli bir gövde (eksik Excel) oluşmaz
                try (tools.jackson.core.JsonGenerator gen = jsonMapper().writer()
                        .without(tools.jackson.core.StreamWriteFeature.AUTO_CLOSE_CONTENT)
                        .createGenerator(org.springframework.util.StreamUtils.nonClosing(response.getOutputStream()),
                                tools.jackson.core.JsonEncoding.UTF8)) {
                    writeExportJson(gen, rows, total, truncated, textTruncated, clipped);
                }
                return null;
            }
            response.setContentType("text/csv;charset=UTF-8");
            response.setHeader(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                    org.springframework.http.ContentDisposition.attachment()
                            .filename("olay-gecmisi-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
                                    .withZone(java.time.ZoneId.of("Europe/Istanbul")).format(Instant.now()) + ".csv")
                            .build().toString());
            java.io.Writer w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(response.getOutputStream(),
                    java.nio.charset.StandardCharsets.UTF_8), 16 * 1024);
            writeExportCsv(w, rows);
            w.flush();
            return null;
        } finally {
            if (podSlot) exportPodSlots.release();   // gövde TÜMÜYLE yazıldıktan sonra (kişi işaretiyle aynı yerde)
            exportRunning.remove(who);
        }
    }

    /** Kullanıcı adı → görünen ad (tek hafif okuma; okunamazsa kullanıcı adı yazılır). */
    private java.util.function.Function<String, String> displayNames() {
        if (userRepo == null) return u -> null;
        try {
            Map<String, String> m = new java.util.HashMap<>();
            for (Object[] r : userRepo.findDirectoryRows()) {
                if (r[1] instanceof String u && r[2] instanceof String n && !n.isBlank())
                    m.put(u.toUpperCase(java.util.Locale.ROOT), n);
            }
            return u -> u == null ? null : m.get(u.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            log.debug("Dışa aktarım: görünen adlar okunamadı: {}", e.toString());
            return u -> null;
        }
    }

    /** Sayfanın postmortem durumları (bean yoksa / okunamazsa boş — sütun boş kalır). */
    private Map<Long, String> postmortemStates(List<IncidentRecord> page) {
        Map<Long, String> out = new java.util.HashMap<>();
        if (postmortems == null || page.isEmpty()) return out;
        try {
            Map<Long, Map<String, Object>> sums = postmortems.summaries(page.stream().map(IncidentRecord::getId).toList());
            sums.forEach((id, s) -> {
                if (s.get("postmortem_status") instanceof String st) out.put(id, st);
            });
        } catch (Exception ex) {
            log.debug("Dışa aktarım: postmortem durumları okunamadı: {}", ex.toString());
        }
        return out;
    }

    private static String exportKey(HttpSession session) {
        Object u = session == null ? null : session.getAttribute("username");
        return u != null ? "u:" + u : "s:" + (session == null ? "-" : session.getId());
    }

    /** Dakikalık hakkı geri verir (pod dolu — hiçbir şey okunmadı): kişinin son kaydı silinir. */
    private void refundExport(String who) {
        synchronized (exportCalls) {
            java.util.Deque<Long> q = exportCalls.get(who);
            if (q != null && !q.isEmpty()) q.pollLast();
        }
    }

    /** Dakikalık hak — dolu ise kalan saniye, yoksa null (hak düşülür). */
    private Long reserveExport(String who) {
        long now = System.currentTimeMillis();
        synchronized (exportCalls) {
            if (exportCalls.size() > 1000) exportCalls.clear();
            java.util.Deque<Long> q = exportCalls.computeIfAbsent(who, k -> new java.util.ArrayDeque<>());
            while (!q.isEmpty() && now - q.peekFirst() >= 60_000L) q.pollFirst();
            if (q.size() >= EXPORT_PER_MINUTE) return Math.max(1L, (60_000L - (now - q.peekFirst()) + 999) / 1000);
            q.addLast(now);
            return null;
        }
    }

    /** Uygulamanın ObjectMapper'ı (ileti dönüştürücüsüyle aynı değer serileştirmesi); dilim testinde yoksa varsayılan. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private tools.jackson.databind.ObjectMapper objectMapper;

    private tools.jackson.databind.ObjectMapper jsonMapper() {
        return objectMapper != null ? objectMapper : tools.jackson.databind.json.JsonMapper.builder().build();
    }

    /**
     * JSON dışa aktarım gövdesi satır satır: {@code {success, data:{columns, rows, row_count, total, max_rows, truncated,
     * text_truncated, text_clipped}}} — akışa geçmeden önceki {@code ResponseEntity} haritasıyla aynı anahtarlar, aynı sıra,
     * aynı değer serileştirmesi (test eski haritayla bayt bayt karşılaştırır). Tüm gövde bellekte ikinci kez kurulmaz.
     */
    static void writeExportJson(tools.jackson.core.JsonGenerator gen, List<Map<String, Object>> rows, long total,
                                boolean truncated, boolean textTruncated, int clipped) {
        gen.writeStartObject();
        gen.writeBooleanProperty("success", true);
        gen.writeName("data");
        gen.writeStartObject();
        gen.writePOJOProperty("columns", IncidentExportRows.COLUMNS);
        gen.writeName("rows");
        gen.writeStartArray();
        for (Map<String, Object> r : rows) gen.writePOJO(r);
        gen.writeEndArray();
        gen.writeNumberProperty("row_count", rows.size());
        gen.writeNumberProperty("total", total);
        gen.writeNumberProperty("max_rows", EXPORT_MAX_ROWS);
        gen.writeBooleanProperty("truncated", truncated);
        gen.writeBooleanProperty("text_truncated", textTruncated);
        gen.writeNumberProperty("text_clipped", clipped);
        gen.writeEndObject();
        gen.writeEndObject();
    }

    /** CSV dışa aktarım gövdesi: BOM + başlık satırı ({@code Csv.row}, formül koruması) + satırlar. */
    static void writeExportCsv(java.io.Writer w, List<Map<String, Object>> rows) throws java.io.IOException {
        w.write('\uFEFF');
        w.write(com.sitemonitor.util.Csv.row(IncidentExportRows.COLUMNS.toArray()));
        for (Map<String, Object> r : rows) w.write(IncidentExportRows.csvRow(r));
    }

    private static ResponseEntity<Map<String, Object>> tooMany(String code, long retry, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("error", message);
        body.put("retry_after_seconds", retry);
        String rid = org.slf4j.MDC.get("requestId");
        if (rid != null) body.put("request_id", rid);
        return ResponseEntity.status(429).header(org.springframework.http.HttpHeaders.RETRY_AFTER, String.valueOf(retry)).body(body);
    }

    // ── Takımlar (süzgeç seçicisi + seçenek yönetimi, 2026-11-26) ────────────────────────────────────────────────────

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.repository.TeamRepository teamRepo;

    /**
     * Süzgeçte seçilebilecek takımlar = kullanıcının olay kayıtlarını OKUYABİLDİĞİ takımlar (liste kapsamı); her satır
     * {@code can_manage_options}: bu takımın seçeneklerini yeniden adlandırabilir / birleştirebilir mi (incidents.manage +
     * takım yönetim kapsamı). Takım adına göre sıralı.
     */
    @GetMapping("/teams")
    public ResponseEntity<Map<String, Object>> teams(HttpSession session) {
        requireView(session);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (teamRepo != null) {
            List<Long> scope = incidentViewScope(session);
            List<com.sitemonitor.model.Team> list = scope == null ? teamRepo.findAll()
                    : (scope.isEmpty() ? List.of() : teamRepo.findAllById(scope));
            boolean manage = permissionService.allows(session, "incidents.manage", "edit");
            for (com.sitemonitor.model.Team t : list) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", t.getId());
                m.put("name", t.getName());
                m.put("active", !Boolean.FALSE.equals(t.getActive()));
                m.put("can_manage_options", manage && SessionScope.canManage(session, t.getId()));
                out.add(m);
            }
            java.text.Collator col = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("tr"));
            out.sort((a, b) -> col.compare(String.valueOf(a.get("name")), String.valueOf(b.get("name"))));
        }
        return ok(Map.of("data", out));
    }

    // ── Seçenek yönetimi: kullanım + yeniden adlandır / birleştir (2026-11-26) ───────────────────────────────────────

    /** Seçenek yönetim kapısı: incidents.manage (düzenle) + takım yönetim kapsamı. */
    private void requireOptionManage(HttpSession session, Long teamId) {
        requireManage(session);
        if (teamId == null || !SessionScope.canManage(session, teamId))
            throw new SecurityException("Bu takımın seçeneklerini yönetme yetkiniz yok");
    }

    /**
     * Seçenek uçlarının takım kimliği (inceleme 2026-11-26): eksik / sayı olmayan / sıfır-altı değer KAPSAM denetiminden ÖNCE
     * alan adlı 400 — eskiden 403 "yetkiniz yok" dönüyordu (yanlış neden).
     */
    static Long optionTeamId(Object raw) {
        Long id = null;
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            id = ((Number) raw).longValue();
        } else if (raw instanceof Number n) {
            // Double / Float / BigInteger / BigDecimal: yalnız tam ve long'a sığan değer (NaN / sonsuz / taşan → hata)
            try {
                java.math.BigDecimal bd = new java.math.BigDecimal(n.toString());
                if (bd.stripTrailingZeros().scale() <= 0) id = bd.longValueExact();
            } catch (NumberFormatException | ArithmeticException e) {
                id = null;
            }
        } else if (raw != null) {
            String v = raw.toString().trim();
            if (v.matches("\\d{1,18}")) id = Long.valueOf(v);
        }
        if (id == null || id <= 0) {
            throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("team_id", com.sitemonitor.util.Msg.t(
                    "Takım seçin.", "Choose a team."));
        }
        return id;
    }

    /** Takımın bir seçenek türündeki değerleri + kayıt sayıları (yeniden adlandırma / birleştirme penceresi). */
    @GetMapping("/options/usage")
    public ResponseEntity<Map<String, Object>> optionUsage(@RequestParam(required = false) String type,
                                                           @RequestParam(name = "team_id", required = false) String teamParam,
                                                           HttpSession session) {
        requireManage(session);
        Long teamId = optionTeamId(teamParam);
        requireOptionManage(session, teamId);
        IncidentService.UsageReport report = service.optionUsage(type, teamId);
        List<Map<String, Object>> values = new java.util.ArrayList<>();
        for (IncidentService.OptionUsage u : report.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", u.value());
            m.put("records", u.records());
            m.put("team_option", u.teamOption());
            m.put("global_option", u.globalOption());
            values.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", type == null ? null : type.trim().toUpperCase(java.util.Locale.ROOT));
        data.put("team_id", teamId);
        data.put("values", values);
        data.put("truncated", report.truncated());
        data.put("rows_read", report.rowsRead());
        data.put("max_rows", IncidentService.OPTION_USAGE_MAX_ROWS);
        return ok(Map.of("data", data));
    }

    /**
     * Yeniden adlandır / birleştir (2026-11-26): gövde {@code {type, team_id, from: [..] | "..", to}}. Takımın kayıtlarında
     * kaynak değerlerin TAM öğeleri hedefle değişir (tek işlem); her kayıt sürümü artırır. Denetim
     * {@code INCIDENT_OPTIONS_RENAME} (tek kaynak, hedef yeni) ya da {@code INCIDENT_OPTIONS_MERGE}.
     */
    @PutMapping("/options")
    public ResponseEntity<Map<String, Object>> renameOptions(@RequestBody Map<String, Object> body,
                                                             HttpSession session, HttpServletRequest request) {
        requireManage(session);
        Long teamId = optionTeamId(body == null ? null : body.get("team_id"));
        requireOptionManage(session, teamId);
        String type = body.get("type") == null ? null : String.valueOf(body.get("type"));
        List<String> from = new java.util.ArrayList<>();
        Object raw = body.get("from");
        if (raw instanceof List<?> l) {
            for (Object o : l) if (o != null) from.add(String.valueOf(o));
        } else if (raw != null) {
            from.add(String.valueOf(raw));
        }
        String to = body.get("to") == null ? null : String.valueOf(body.get("to"));
        IncidentService.RenameResult r = service.renameOptions(type, teamId, from, to, (String) session.getAttribute("username"));
        try {
            auditService.recordAction(r.merge() ? "INCIDENT_OPTIONS_MERGE" : "INCIDENT_OPTIONS_RENAME", session, request,
                    "INCIDENT_OPTION", r.type(),
                    com.sitemonitor.service.AuditDetail.of("team_id", r.teamId(), "from", String.join(", ", r.perValue().keySet()),
                            "to", r.to(), "records", r.records(), "options_removed", r.optionsRemoved()));
        } catch (Exception e) {
            log.debug("Seçenek yeniden adlandırma denetimi yazılamadı: {}", e.toString());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", r.type());
        data.put("team_id", r.teamId());
        data.put("to", r.to());
        data.put("mode", r.merge() ? "merge" : "rename");
        data.put("records", r.records());
        data.put("per_value", r.perValue());
        data.put("record_ids", r.recordIds());
        data.put("options_removed", r.optionsRemoved());
        data.put("global_kept", r.globalKept());
        return ok(Map.of("data", data));
    }

    // ── Yönetilen seçenekler (kanal / domain) — creatable dropdown beslemesi ──────────────────

    @GetMapping("/options")
    public ResponseEntity<Map<String, Object>> options(@RequestParam String type, HttpSession session) {
        requireView(session);
        // Seçenekler takıma özel: kullanıcı global + kendi takımını görür; admin tümünü.
        return ok(Map.of("data", service.listOptions(type, longAttr(session, "teamId"),
                SessionScope.isGlobalAdmin(session))));
    }

    @PostMapping("/options")
    public ResponseEntity<Map<String, Object>> addOption(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireManage(session);
        String type  = body.get("type")  == null ? null : String.valueOf(body.get("type"));
        String value = body.get("value") == null ? null : String.valueOf(body.get("value"));
        String saved = service.addOption(type, value, (String) session.getAttribute("username"),
                longAttr(session, "teamId"));   // seçenek ekleyenin takımına yazılır (admin → global)
        auditService.recordAction("INCIDENT_OPTION_ADD", session, request,
                "INCIDENT_OPTION", type, "{\"value\":\"" + safe(saved) + "\"}");
        return ok(Map.of("data", saved));
    }

    @DeleteMapping("/options")
    public ResponseEntity<Map<String, Object>> deleteOption(
            @RequestParam String type, @RequestParam String value,
            HttpSession session, HttpServletRequest request) {
        requireManage(session);
        // Kullanıcı yalnız kendi takımının seçeneğini silebilir; admin her şeyi.
        service.removeOption(type, value, longAttr(session, "teamId"),
                SessionScope.isGlobalAdmin(session));
        auditService.recordAction("INCIDENT_OPTION_DELETE", session, request,
                "INCIDENT_OPTION", type, "{\"value\":\"" + safe(value) + "\"}");
        return ok(Map.of("message", "Deleted"));
    }

    /**
     * Bir alarmdan açılmış olay kayıtları (2026-10-01) — alarm detayının "Olay kaydı #N" bağlantıları. Yalnız alarm
     * DETAYI çağırır (liste satırı başına istek yok). Kapı listeyle aynı: incidents.view + olay kaydı takım kapsamı.
     */
    @GetMapping("/by-alert/{alertEventId}")
    public ResponseEntity<Map<String, Object>> byAlert(@PathVariable Long alertEventId, HttpSession session) {
        requireView(session);
        return ok(Map.of("data", alertLinks.linked(alertEventId, incidentViewScope(session))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id, HttpSession session) {
        requireView(session);
        IncidentRecord e = service.get(id);
        requireIncidentRead(session, e);   // takım kapsamı (IDOR engeli)
        // Postmortem rozetleri ayrıntıda da (2026-10-12): bağlantıyla açılan olay kartı "postmortem yok" diyordu.
        Map<String, Object> d = markWritable(session, e, dto(e));
        addPostmortemSummaries(List.of(d));
        return ok(Map.of("data", d));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireManage(session);
        // Hedef takım kapsamı (2026-09-28, A2): gövdedeki team_id doğrulanmadan kayda yazılıyordu. incidents.manage
        // USER'da açık → kullanıcı Takım B'nin team_id'siyle sahte olay açıp (send_notification ile) B'nin
        // ekibine/müdürüne mail attırabiliyor, B'nin SLA/ledger'ını kirletebiliyordu. Kural /transfer ile AYNI.
        if (body.containsKey("team_id")) requireTransferTarget(session, longVal(body.get("team_id")));
        // Alarmdan açılan kayıt: alarm var mı (400) ve kullanıcı onu görebiliyor mu (403) — kayıttan ÖNCE.
        alertLinks.validate(session, body, null);
        IncidentRecord e = service.create(body,
                (String) session.getAttribute("username"),
                longAttr(session, "userId"), longAttr(session, "teamId"));
        auditService.recordAction("INCIDENT_CREATE", session, request,
                "INCIDENT", String.valueOf(e.getId()),
                "{\"severity\":\"" + e.getSeverity() + "\",\"category\":\"" + e.getCategory() + "\""
                        + (e.getAlertEventId() != null ? ",\"alert_event_id\":" + e.getAlertEventId() : "") + "}");
        Map<String, Object> created = dto(e);
        // Mail YALNIZ kullanıcı "Mail gönder"i seçtiyse gider (varsayılan: kayıtta mail yok).
        if (Boolean.TRUE.equals(body.get("send_notification")))
            notificationService.notifyIncident(created, "NEW"); // takım + müdür executive bildirim (async)
        return ok(Map.of("data", created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id, @RequestBody Map<String, Object> body,
            HttpSession session, HttpServletRequest request) {
        requireManage(session);
        IncidentRecord cur = service.get(id);
        requireIncidentWrite(session, cur);   // takım kapsamı (IDOR engeli)
        // Takım DEĞİŞİYORSA hedef de kapsamda olmalı (A2) — PUT, /transfer'in atladığı kapıdan geçen ikinci yoldu.
        // Aynı team_id'yi geri göndermek (eski istemci her kayıtta tüm alanları yollar) taşıma değildir → kapıya takılmaz.
        // Sürüm çakışması (2026-11-20) ve alan doğrulaması servistedir (IncidentService.update → 409 / alan adlı 400).
        if (body.containsKey("team_id") && !java.util.Objects.equals(longVal(body.get("team_id")), cur.getTeamId()))
            requireTransferTarget(session, longVal(body.get("team_id")));
        // Kaynak alarm DEĞİŞİYORSA yeni alarm doğrulanır (aynı değeri geri göndermek kapıya takılmaz).
        alertLinks.validate(session, body, cur.getAlertEventId());
        String prevStatus = cur.getStatus(); // RESOLVED'e GEÇİŞ tespiti için
        IncidentRecord e = service.update(id, body, (String) session.getAttribute("username"));
        auditService.recordAction("INCIDENT_UPDATE", session, request,
                "INCIDENT", id.toString(),
                "{\"severity\":\"" + e.getSeverity() + "\",\"status\":\"" + e.getStatus() + "\"}");
        Map<String, Object> updated = dto(e);
        boolean justResolved = "RESOLVED".equals(e.getStatus()) && !"RESOLVED".equals(prevStatus);
        // Mail YALNIZ kullanıcı "Mail gönder"i seçtiyse gider (varsayılan: kayıtta mail yok).
        if (Boolean.TRUE.equals(body.get("send_notification")))
            notificationService.notifyIncident(updated, justResolved ? "RESOLVED" : "UPDATED"); // async bildirim
        return ok(Map.of("data", updated));
    }

    /** Gidecek bildirim mailinin ÖNİZLEME HTML'i — kaydetmez/göndermez (modal'daki "Mail Önizle"). */
    @PostMapping("/preview-notification")
    public ResponseEntity<Map<String, Object>> previewNotification(
            @RequestBody Map<String, Object> body, HttpSession session) {
        requireManage(session);
        String kind = body.get("kind") instanceof String k ? k : "UPDATED";
        // Görsel planı (2026-11-20) bu olayın görsellerini okur: gövdedeki kimlik ancak okunabilen bir kayıtsa kullanılır
        // (başkasının kaydının görselleri önizlemeyle sınanamaz). Silinmiş / görülemeyen kayıt → kimliksiz (yeni kayıt) önizleme.
        Map<String, Object> dto = body;
        Long id = longVal(body.get("id"));
        if (id != null) {
            boolean readable;
            try {
                IncidentRecord cur = service.get(id);
                readable = SessionScope.isGlobalViewer(session) || canReadIncident(session, cur.getTeamId(), cur.getCreatedByTeamId());
            } catch (java.util.NoSuchElementException e) {
                readable = false;
            }
            if (!readable) {
                dto = new LinkedHashMap<>(body);
                dto.remove("id");
            }
        }
        return ok(Map.of("html", notificationService.previewHtml(dto, kind, (String) session.getAttribute("username"))));
    }

    @PostMapping("/transfer")
    public ResponseEntity<Map<String, Object>> transfer(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireManage(session);
        List<Long> ids = new java.util.ArrayList<>();
        if (body.get("ids") instanceof List<?> raw) {
            for (Object o : raw) {
                try { ids.add(Long.valueOf(String.valueOf(o))); } catch (Exception ignored) {}
            }
        }
        Long teamId = longVal(body.get("team_id"));
        String teamName = body.get("team_name") == null ? null : String.valueOf(body.get("team_name"));
        // Takım kapsamı (IDOR engeli) — kardeş uçların (update/delete/uploadImage) deseni burada EKSİKTİ:
        // yalnız incidents.manage isteniyordu ve o yetki USER'a açık, yani bir kullanıcı BAŞKA takımın
        // olayını kendi takımına (ya da kendi olayını yabancı bir takıma) taşıyabiliyordu.
        // KISMİ BAŞARI YOK: ekran toplu seçimle çalışıyor; yetkisiz TEK kayıt varsa hiçbiri taşınmaz —
        // yarım transfer kullanıcıya "hepsi taşındı" izlenimi verirdi.
        for (Long id : ids) {
            try {
                requireIncidentWrite(session, service.get(id));
            } catch (java.util.NoSuchElementException ignore) {
                /* kayıt yok → transfer da atlayacak (findAllById); doğrulama dışı bırakılır */
            }
        }
        requireTransferTarget(session, teamId);
        int n = service.transfer(ids, teamId, teamName, (String) session.getAttribute("username"));
        auditService.recordAction("INCIDENT_TRANSFER", session, request,
                "INCIDENT", String.valueOf(teamId),
                "{\"count\":" + n + ",\"team\":\"" + safe(teamName) + "\"}");
        return ok(Map.of("data", Map.of("transferred", n)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        requireDelete(session);
        requireIncidentWrite(session, service.get(id));   // takım kapsamı (IDOR engeli)
        IncidentRecord e = service.delete(id);
        if (postmortems != null) {
            try { postmortems.deleteForIncident(id); } catch (Exception ex) { log.warn("Olay #{} postmortem/aksiyon temizliği başarısız (saklama öksüz kuralı siler): {}", id, ex.getMessage()); }
        }
        auditService.recordAction("INCIDENT_DELETE", session, request,
                "INCIDENT", id.toString(),
                "{\"title\":\"" + safe(e.getTitle()) + "\"}");
        return ok(Map.of("message", "Deleted"));
    }

    // ── Görseller (markdown alanlarına gömülür) ───────────────────────────────

    @PostMapping("/{id}/images")
    public ResponseEntity<Map<String, Object>> uploadImage(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "caption", required = false) String caption,
            HttpSession session, HttpServletRequest request) {
        requireManage(session);
        requireIncidentWrite(session, service.get(id));   // takım kapsamı (IDOR engeli)
        IncidentImage img = service.storeImage(id, caption, file, (String) session.getAttribute("username"));
        auditService.recordAction("INCIDENT_IMAGE_ADD", session, request,
                "INCIDENT", id.toString(),
                "{\"image_id\":" + img.getId() + ",\"size\":" + img.getSizeBytes() + "}");
        return ok(Map.of("data", Map.of(
                "id", img.getId(), "size_bytes", img.getSizeBytes(), "content_type", img.getContentType())));
    }

    /** Taslak yükleme — olay henüz kaydedilmeden (create modu). incidentId yok; kaydedince
     *  IncidentService.linkImages markdown'daki id'leri yeni olaya bağlar. */
    @PostMapping("/images")
    public ResponseEntity<Map<String, Object>> uploadDraftImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "caption", required = false) String caption,
            HttpSession session, HttpServletRequest request) {
        requireManage(session);
        IncidentImage img = service.storeImage(null, caption, file, (String) session.getAttribute("username"));
        auditService.recordAction("INCIDENT_IMAGE_ADD", session, request,
                "INCIDENT", "draft",
                "{\"image_id\":" + img.getId() + ",\"size\":" + img.getSizeBytes() + "}");
        return ok(Map.of("data", Map.of(
                "id", img.getId(), "size_bytes", img.getSizeBytes(), "content_type", img.getContentType())));
    }

    @GetMapping("/images/{imageId}")
    public ResponseEntity<byte[]> serveImage(@PathVariable Long imageId, HttpSession session) {
        requireView(session);
        IncidentImage img = service.getImage(imageId);
        if (img.getIncidentId() != null) requireIncidentRead(session, service.get(img.getIncidentId())); // takım kapsamı
        // Taslak (henüz olaya bağlanmamış) görsel YALNIZ yükleyene / global görücüye açık (prod kapısı 2026-09-25, Y-1):
        // takım kapısı taslakta hiç işlemiyordu, id taramasıyla başkasının taslağı okunabiliyordu.
        else if (!SessionScope.isGlobalViewer(session)
                && !String.valueOf(session.getAttribute("username")).equalsIgnoreCase(img.getCreatedBy()))
            throw new java.util.NoSuchElementException("Görsel bulunamadı");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(img.getContentType()))
                .header("Cache-Control", "private, max-age=3600")
                .body(img.getData());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void requireView(HttpSession session) {
        if (!permissionService.allows(session, "incidents.view", "view"))
            throw new SecurityException("incidents.view yetkisi gerekli");
    }

    private void requireManage(HttpSession session) {
        if (!permissionService.allows(session, "incidents.manage", "edit"))
            throw new SecurityException("incidents.manage yetkisi gerekli");
    }

    // ── Takım kapsamı (IDOR engeli): olaylar takıma-gizli. Olay SINIRI = takım ÜYELİĞİ (viewTeamIds);
    //    global viewer/admin (AUDIT dahil) tümünü görür. Eylem yetkisini (view/manage/delete) ayrıca
    //    requireView/requireManage/requireDelete kontrol eder — bu yüzden YAZMA da AYNI üyelik sınırını
    //    kullanır (USER'ın manageTeamIds'i boştur ama kendi takımının olayını düzenleyebilmeli). ──
    private List<Long> incidentViewScope(HttpSession session) {
        // OKUMA kapsamı: 7/24 izleme ekibi operatörü (2026-10-04) tüm takımların olay kayıtlarını okur (liste, eğilim,
        // alarm bağlantıları). Yazma kapıları (requireIncidentWrite / requireTransferTarget) bunu KULLANMAZ.
        return SessionScope.seesAllMonitoring(session) ? null : SessionScope.viewTeamIdsWithGrants(session);   // + paylaşım (2026-10-12)
    }
    private void requireIncidentRead(HttpSession session, IncidentRecord e) {
        if (SessionScope.isGlobalViewer(session)) return;   // kayda dokunmadan (eski sıra korunur)
        if (canReadIncident(session, e.getTeamId(), e.getCreatedByTeamId())) return;
        throw new SecurityException("Bu olay kaydı sizin takım(lar)ınıza ait değil");
    }
    /**
     * Olay kaydı OKUMA kuralı — TEK kaynak (bu denetleyici ve Durum Sayfası, 2026-10-01): global görüntüleyici hepsini;
     * diğerleri kaydın takımı YA DA giren takım görüş kapsamındaysa ({@code findFiltered}'ın {@code :scope} koşuluyla
     * aynı). {@code incidents.view} izni ayrıca çağıranda aranır.
     */
    public static boolean canReadIncident(HttpSession session, Long teamId, Long createdByTeamId) {
        // 7/24 izleme ekibi operatörü (2026-10-04) her takımın kaydını OKUR; yazma sınırı inOwnIncidentScope'ta kalır.
        if (SessionScope.isNocOperator(session)) return true;
        if (inOwnIncidentScope(session, teamId, createdByTeamId)) return true;
        // Erişim paylaşımı (2026-10-12): paylaşan takımın olay kaydını OKUR (yazma sınırı inOwnIncidentScope'ta kalır)
        return (teamId != null && SessionScope.canViewWithGrants(session, teamId))
                || (createdByTeamId != null && SessionScope.canViewWithGrants(session, createdByTeamId));
    }
    /** KENDİ takım sınırı (eski okuma = yazma sınırı) — global görüntüleyici hepsi; diğerleri kaydın / girenin takımı. */
    static boolean inOwnIncidentScope(HttpSession session, Long teamId, Long createdByTeamId) {
        if (SessionScope.isGlobalViewer(session)) return true;
        List<Long> v = SessionScope.viewTeamIds(session);
        return v != null && ((teamId != null && v.contains(teamId)) || (createdByTeamId != null && v.contains(createdByTeamId)));
    }
    /**
     * Yazma sınırı = KENDİ takım üyeliği (eski okuma sınırı). Eylem yetkisini requireManage/requireDelete kontrol eder.
     * 7/24 operatörünün okuma genişlemesi (2026-10-04) BURAYA girmez: başka takımın kaydını okur, düzenleyemez/silemez.
     */
    private void requireIncidentWrite(HttpSession session, IncidentRecord e) {
        if (SessionScope.isGlobalViewer(session)) return;   // kayda dokunmadan (eski sıra korunur)
        if (inOwnIncidentScope(session, e.getTeamId(), e.getCreatedByTeamId())) return;
        throw new SecurityException("Bu olay kaydı sizin takım(lar)ınıza ait değil");
    }

    /**
     * Transfer HEDEFİ de kapsam içinde olmalı — kaynak kaydı doğrulamak tek başına yetmez.
     *
     * <p>Aksi halde kullanıcı kendi takımının olayını yabancı bir takıma taşıyıp kaydı KENDİ görüş
     * alanından çıkarabilirdi: geri alması mümkün olmayan tek yönlü bir veri kaybı (artık okuma
     * yetkisi de yok). Sınır, {@link #requireIncidentRead} ile aynı üyelik sınırıdır.
     */
    private void requireTransferTarget(HttpSession session, Long teamId) {
        if (SessionScope.isGlobalViewer(session)) return;
        List<Long> v = SessionScope.viewTeamIds(session);
        if (v != null && v.contains(teamId)) return;
        throw new SecurityException("Hedef takım sizin takım(lar)ınız arasında değil");
    }

    /** Silme yalnız TEAM_ADMIN/ADMIN (incidents.delete/execute); USER gir/düzenle yapar, silemez. */
    private void requireDelete(HttpSession session) {
        if (!permissionService.allows(session, "incidents.delete", "execute"))
            throw new SecurityException("incidents.delete yetkisi gerekli");
    }

    /**
     * Liste rozetleri (2026-10-11): postmortem durumu + açık / gecikmiş / biten aksiyon sayısı — sayfa başına iki gruplu
     * sorgu (satır başına sorgu yok). Hata listeyi bozmaz (rozetsiz döner).
     */
    private void addPostmortemSummaries(List<Map<String, Object>> rows) {
        if (postmortems == null || rows.isEmpty()) return;
        try {
            List<Long> ids = rows.stream().map(r -> (Long) r.get("id")).toList();
            Map<Long, Map<String, Object>> sums = postmortems.summaries(ids);
            for (Map<String, Object> r : rows) {
                Map<String, Object> s = sums.get((Long) r.get("id"));
                if (s != null) r.putAll(s);
                // Özet OKUNDU: alan yoksa gerçekten "rapor / aksiyon yok" (istemci okunamadığında yanlış "yok" demesin)
                r.put("pm_summary", true);
            }
        } catch (Exception ex) {
            log.warn("Olay listesi postmortem rozetleri okunamadı: {}", ex.getMessage());
        }
    }

    /**
     * Okunabilen ama YAZILAMAYAN kayıt (7/24 operatörünün ya da takım erişim paylaşımının açtığı, 2026-10-12): satıra
     * {@code can_edit=false} — arayüz Düzenle / Sil / toplu işlemi çizmez (sunucu inOwnIncidentScope ile zaten reddeder).
     * Kendi kapsamındaki satırda alan YAZILMAZ (yanıt bugünküyle aynı).
     */
    private static Map<String, Object> markWritable(HttpSession session, IncidentRecord e, Map<String, Object> d) {
        if (!inOwnIncidentScope(session, e.getTeamId(), e.getCreatedByTeamId())) d.put("can_edit", false);
        return d;
    }

    private Map<String, Object> dto(IncidentRecord e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("title", e.getTitle());
        m.put("occurred_at", e.getOccurredAt());
        m.put("detected_at", e.getDetectedAt());
        m.put("resolved_at", e.getResolvedAt());
        m.put("severity", e.getSeverity());
        m.put("status", e.getStatus());
        m.put("category", e.getCategory());
        m.put("error_code", e.getErrorCode());
        m.put("function_code", e.getFunctionCode());
        m.put("channel_code", e.getChannelCode());
        m.put("service", e.getService());
        m.put("channel", e.getChannel());
        m.put("team_id", e.getTeamId());
        m.put("team_name", e.getTeamName());
        m.put("alert_event_id", e.getAlertEventId());
        m.put("rca_summary", e.getRcaSummary());
        m.put("description", e.getDescription());
        m.put("resolution_steps", e.getResolutionSteps());
        m.put("business_impact", e.getBusinessImpact());
        m.put("affected_services", e.getAffectedServices());
        m.put("problem_types", e.getProblemTypes());
        m.put("affected_app", e.getAffectedApp());
        m.put("affected_systems", e.getAffectedSystems());
        m.put("affected_customers", e.getAffectedCustomers());
        m.put("affected_transactions", e.getAffectedTransactions());
        // Teknik hata raporu konusuna içe alınacak üç alan (2026-11-20) — anahtar HEP vardır; değer yoksa null (bilinmiyor).
        m.put("tech_error_count", e.getTechErrorCount());
        m.put("outage_related", e.getOutageRelated());
        m.put("outage_id", e.getOutageId());
        m.put("sla_breached", e.getSlaBreached());
        m.put("error_budget_burn_pct", e.getErrorBudgetBurnPct());
        m.put("duration_minutes", e.getDurationMinutes());
        m.put("runbook_url", e.getRunbookUrl());
        m.put("tags", e.getTags());
        m.put("created_by", e.getCreatedBy());
        m.put("created_at", e.getCreatedAt());
        m.put("updated_by", e.getUpdatedBy());
        m.put("updated_at", e.getUpdatedAt());
        // İyimser kilit sürümü (2026-11-20, "Sağlam Kayıt"): düzenleme formu bunu PUT'ta geri gönderir; farklıysa 409.
        m.put("version", e.getVersion() == null ? 0L : e.getVersion());
        return m;
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> response = new LinkedHashMap<>(body);
        response.put("success", true);
        response.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.ok(response);
    }

    private static Long longVal(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.valueOf(v.toString().trim()); } catch (Exception e) { return null; }
    }

    private Long longAttr(HttpSession session, String key) {
        Object v = session.getAttribute(key);
        if (v instanceof Long l) return l;
        if (v != null) { try { return Long.valueOf(v.toString()); } catch (Exception ignored) {} }
        return null;
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\"", "'").replace("\\", "");
    }
}
