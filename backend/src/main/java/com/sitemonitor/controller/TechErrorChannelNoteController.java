package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.CodedForbiddenException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorChannelNote;
import com.sitemonitor.model.TechErrorChannelNoteImage;
import com.sitemonitor.model.TechErrorChannelNoteVersion;
import com.sitemonitor.repository.TechErrorChannelNoteImageRepository;
import com.sitemonitor.repository.TechErrorChannelNoteVersionRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.techerror.TechErrorChannelNoteService;
import com.sitemonitor.service.techerror.TechErrorImageValidator;
import com.sitemonitor.service.techerror.TechErrorNoteRefs;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * KANAL NOTLARI uçları (2026-11-20, kullanıcı isteği: her teknik hata raporu kanalına "bu kanalın verisini nereden / nasıl
 * alıyorum" notu). Kanal başına tek Markdown belgesi + görseller + son 20 kaydın sürümü.
 *
 * <p><b>Kapsam</b> (tek kaynak — {@link #canReadNotes} / {@link #canEditNotes}; kanalın takımı her istekte kanal
 * satırından okunur): notu kanalın TAKIMI düzenler (üyeler + yöneticiler, kendi kapsamı — {@code canWriteOwn}); okuma =
 * takımın kendi kapsamı YA DA düzenleme (EDIT / DELETE) düzeyinde erişim paylaşımı. Kanalı yalnız görüntüleme / çalıştırma
 * paylaşımıyla gören notları ALAMAZ: 403 {@code CHANNEL_NOTES_NOT_SHARED}. Kanal yok ya da hiç görünmüyor: 404
 * {@code CHANNEL_NOT_FOUND} (kodlu — istemci eski pod'un kodsuz 404'ünden ayırır). 7/24 operatörlüğü hiçbir şeyi
 * genişletmez (modül kuralı). Rol izni: okuma {@code tech_error_reports.read/view}, yazma
 * {@code tech_error_reports.crud/edit}; modül görünürlüğü (S6) uygulanır.
 *
 * <p>Not metni ve görsel baytı günlüğe / denetim ayrıntısına YAZILMAZ (yalnız sayılar); istek / yanıt gövdesi
 * {@code RequestLoggingFilter.BODY_NEVER_LOGGED} listesindedir. Kullanıcı kimliği verilmez — yalnız ad.
 */
@Slf4j
@RestController
@RequestMapping("/api/tech-error-reports")
public class TechErrorChannelNoteController {

    /** Kişi başına dakikadaki en çok görsel yüklemesi (pod belleği — yük sınırı). */
    static final int UPLOADS_PER_MINUTE = 30;
    private static final long UPLOAD_WINDOW_MS = 60_000L;

    private final TechErrorChannelNoteService notes;
    private final PermissionService permissions;
    private final AuditService auditService;

    /** Kullanıcı → son yükleme zamanları (ms); sınırlı (1 000 anahtarda süresi geçenler atılır, 10 000'de sıfırlanır). */
    private final Map<String, Deque<Long>> uploadCalls = new HashMap<>();
    /** Saat (testlerde ileri sarılır). */
    LongSupplier uploadClock = System::currentTimeMillis;

    public TechErrorChannelNoteController(TechErrorChannelNoteService notes, PermissionService permissions,
                                          AuditService auditService) {
        this.notes = notes;
        this.permissions = permissions;
        this.auditService = auditService;
    }

    // ── Not ──────────────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/channel-notes/{channelId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long channelId, HttpSession session) {
        requireRead(session);
        TechErrorChannel ch = readableChannel(channelId, session);
        return ok(noteDto(ch, notes.read(ch.getId()), session));
    }

    @PutMapping("/channel-notes/{channelId}")
    public ResponseEntity<Map<String, Object>> save(@PathVariable Long channelId,
                                                    @RequestBody(required = false) Map<String, Object> body,
                                                    HttpSession session, HttpServletRequest request) {
        requireWrite(session);
        TechErrorChannel ch = editableChannel(channelId, session);
        Map<String, Object> b = body == null ? Map.of() : body;
        long version = versionOf(b.get("version"));
        String text = bodyOf(b.get("body"));
        TechErrorChannelNoteService.SaveResult res;
        try {
            res = notes.save(ch.getId(), version, text, userId(session));
        } catch (CodedConflictException e) {
            // Ayrıntısız çakışma = eşzamanlı İLK kaydı tekil kısıt kaybetti (servis bozuk işlemde okumaz): güncel durum
            // burada, işlemin DIŞINDA yeni bir okumayla eklenir
            if ("CONCURRENT_MODIFICATION".equals(e.getCode()) && e.getExtra() == null) throw currentConflict(ch.getId());
            throw e;
        } catch (OptimisticLockingFailureException e) {
            // Kanal kilidini atlayan bir yazım sürümü değiştirdi (@Version): aynı 409, güncel durumla
            throw currentConflict(ch.getId());
        }
        TechErrorChannelNoteService.View view = res.view();
        if (res.changed()) {
            // Denetim: yalnız sayılar — not metni ASLA
            auditService.recordAction("TECH_ERROR_CHANNEL_NOTE_SAVE", session, request, "TECH_ERROR_CHANNEL",
                    String.valueOf(ch.getId()),
                    AuditDetail.of("channel_id", ch.getId(), "channel", ch.getName(), "team_id", ch.getTeamId(),
                            "version", view.version(), "char_count", view.body().length(),
                            "image_count", res.linkedImages()));
        }
        Map<String, Object> data = noteDto(ch, view, session);
        data.put("changed", res.changed());
        return ok(data);
    }

    // ── Görseller ────────────────────────────────────────────────────────────────────────────────────

    @PostMapping("/channel-notes/{channelId}/images")
    public ResponseEntity<Map<String, Object>> uploadImage(@PathVariable Long channelId,
                                                           @RequestParam("file") MultipartFile file,
                                                           HttpSession session, HttpServletRequest request) throws IOException {
        requireWrite(session);
        TechErrorChannel ch = editableChannel(channelId, session);
        if (file == null || file.isEmpty()) throw new FieldValidationException("file", Msg.t(
                "Dosya seçilmedi ya da boş.", "No file was chosen, or it's empty."));
        if (file.getSize() > TechErrorImageValidator.MAX_BYTES) {
            throw new FieldValidationException("file", Msg.t(
                    "Görsel en çok 5 MB olabilir. Ekran görüntüsünü küçültüp ya da JPEG olarak kaydedip yeniden yükleyin.",
                    "An image can be at most 5 MB. Make the screenshot smaller or save it as JPEG, then upload it again."));
        }
        // Kişi başı yük sınırı kapsam denetiminden SONRA (görülemeyen kanal hak yemez); reddedilen istek sayılmaz
        Long retry = reserveUpload(userKey(session));
        if (retry != null) {
            return rateLimited(Msg.t(
                    "Görsel yükleme sınırına ulaşıldı: kişi başına dakikada en çok " + UPLOADS_PER_MINUTE + " görsel yüklenir. " + retry + " saniye sonra yeniden deneyin; bu deneme sayılmadı.",
                    "Image upload limit reached: at most " + UPLOADS_PER_MINUTE + " images per person per minute. Try again in " + retry + " seconds; this attempt wasn't counted."),
                    retry);
        }
        TechErrorChannelNoteImage img = notes.upload(ch.getId(), file.getBytes(), file.getOriginalFilename(), userId(session));
        // Denetim: kimlik + boyut — dosya baytı / adı YOK
        auditService.recordAction("TECH_ERROR_CHANNEL_NOTE_IMAGE_ADD", session, request, "TECH_ERROR_CHANNEL",
                String.valueOf(ch.getId()),
                AuditDetail.of("channel_id", ch.getId(), "channel", ch.getName(), "team_id", ch.getTeamId(),
                        "image_id", img.getId(), "size_bytes", img.getSizeBytes(),
                        "width", img.getWidth(), "height", img.getHeight(), "content_type", img.getContentType()));
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("id", img.getId());
        image.put("file_name", img.getFileName());
        image.put("content_type", img.getContentType());
        image.put("width", img.getWidth());
        image.put("height", img.getHeight());
        image.put("size_bytes", img.getSizeBytes());
        image.put("uploaded_at", iso(img.getUploadedAt()));
        image.put("linked", img.isLinked());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("image", image);
        data.put("ref", TechErrorNoteRefs.PREFIX + img.getId());
        return ok(data);
    }

    /**
     * Görselin baytları. Kapsam görselin KANALINDAN türetilir ve bayt yüklenmeden ÖNCE denetlenir; okunamayan ya da olmayan
     * görsel aynı 404'ü alır (varlık sızmaz). Tür yalnız saklanan PNG / JPEG olarak sunulur ({@code nosniff}).
     */
    @GetMapping("/channel-notes/images/{imageId}")
    public ResponseEntity<byte[]> image(@PathVariable Long imageId, HttpSession session) {
        requireRead(session);
        TechErrorChannelNoteImageRepository.Meta meta = notes.imageMeta(imageId).orElse(null);
        if (meta == null) throw TechErrorChannelNoteService.imageNotFound();
        Long teamId = notes.channelTeamId(meta.getChannelId()).orElse(null);
        if (teamId == null || !canReadNotes(session, teamId)) throw TechErrorChannelNoteService.imageNotFound();
        TechErrorChannelNoteImage img = notes.imageWithBytes(imageId).orElse(null);
        if (img == null || img.getData() == null || !meta.getChannelId().equals(img.getChannelId())) {
            throw TechErrorChannelNoteService.imageNotFound();
        }
        String name = img.getFileName() == null || img.getFileName().isBlank() ? "gorsel-" + img.getId() : img.getFileName();
        return ResponseEntity.ok()
                .contentType(servedType(img.getContentType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(name, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(img.getData());
    }

    @DeleteMapping("/channel-notes/{channelId}/images/{imageId}")
    public ResponseEntity<Map<String, Object>> deleteImage(@PathVariable Long channelId, @PathVariable Long imageId,
                                                           HttpSession session, HttpServletRequest request) {
        requireWrite(session);
        TechErrorChannel ch = editableChannel(channelId, session);
        TechErrorChannelNoteImageRepository.Meta meta = notes.deleteImage(ch.getId(), imageId);
        auditService.recordAction("TECH_ERROR_CHANNEL_NOTE_IMAGE_DELETE", session, request, "TECH_ERROR_CHANNEL",
                String.valueOf(ch.getId()),
                AuditDetail.of("channel_id", ch.getId(), "channel", ch.getName(), "team_id", ch.getTeamId(),
                        "image_id", meta.getId(), "size_bytes", meta.getSizeBytes()));
        return ok(new LinkedHashMap<>(Map.of("deleted", true)));
    }

    // ── Sürümler ─────────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/channel-notes/{channelId}/versions")
    public ResponseEntity<Map<String, Object>> versions(@PathVariable Long channelId, HttpSession session) {
        requireRead(session);
        TechErrorChannel ch = readableChannel(channelId, session);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TechErrorChannelNoteVersionRepository.Meta m : notes.versions(ch.getId())) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("version", m.getVersion());
            r.put("saved_at", iso(m.getSavedAt()));
            r.put("saved_by_name", m.getSavedByName());
            r.put("char_count", m.getCharCount());
            r.put("image_count", m.getImageCount());
            rows.add(r);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("versions", rows);
        data.put("max_versions", TechErrorChannelNoteService.MAX_VERSIONS);
        return ok(data);
    }

    @GetMapping("/channel-notes/{channelId}/versions/{version}")
    public ResponseEntity<Map<String, Object>> version(@PathVariable Long channelId, @PathVariable long version,
                                                       HttpSession session) {
        requireRead(session);
        TechErrorChannel ch = readableChannel(channelId, session);
        TechErrorChannelNoteVersion v = notes.version(ch.getId(), version)
                .orElseThrow(TechErrorChannelNoteService::versionNotFound);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", v.getVersion());
        data.put("body", v.getBodyMd() == null ? "" : v.getBodyMd());
        data.put("saved_at", iso(v.getSavedAt()));
        data.put("saved_by_name", v.getSavedByName());
        data.put("char_count", v.getCharCount());
        data.put("image_count", v.getImageCount());
        return ok(data);
    }

    // ── Kapsam (tek kaynak) ──────────────────────────────────────────────────────────────────────────

    /**
     * Notları OKUYABİLİR mi — yalnız KAPSAM (rol izni + modül görünürlüğü çağıranın {@code requireRead}'inde): kanalın
     * takımı okunabiliyor VE (takım yalnız paylaşımla görülmüyor YA DA paylaşım düzenleme düzeyinde YA DA takımın kendi
     * yazma kapsamı — "düzenleyebilen okur" güvencesi; üyeliği olan birinin ayrıca görüntüleme paylaşımı da olsa okur).
     * Yalnız görüntüleme / çalıştırma paylaşımıyla gören okuyamaz.
     */
    static boolean canReadNotes(HttpSession session, Long teamId) {
        if (teamId == null || !TechErrorReportController.canRead(session, teamId)) return false;
        return !SessionScope.viewableOnlyByGrant(session, teamId)
                || SessionScope.grantedEdit(session, teamId)
                || TechErrorReportController.canWriteOwn(session, teamId);
    }

    /**
     * Notu DÜZENLEYEBİLİR mi — yalnız KAPSAM (rol izni {@code tech_error_reports.crud/edit} + modül görünürlüğü çağıranın
     * {@code requireWrite}'ında): takımın KENDİ yazma kapsamı (global yönetici, takımı yöneten ya da üye). Erişim
     * paylaşımı (EDIT / DELETE dahil) notu okutur, düzenletmez.
     */
    static boolean canEditNotes(HttpSession session, Long teamId) {
        return TechErrorReportController.canWriteOwn(session, teamId);
    }

    /**
     * Rapor ekranı / rapor listesi için {@code channel_notes} bloğu (eklemeli anahtar). Okunamıyorsa {@code {readable:false}}
     * — sorgu YOK. Okunabiliyorsa TEK hafif sorgu (metin / bayt okunmaz); servis yoksa ya da okuma düşerse
     * {@code {readable:true, unavailable:true}} — asla "not yok" denmez.
     */
    static Map<String, Object> summaryBlock(TechErrorChannelNoteService notes, HttpSession session, TechErrorChannel ch) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (ch == null || !canReadNotes(session, ch.getTeamId())) {
            m.put("readable", false);
            return m;
        }
        m.put("readable", true);
        TechErrorChannelNoteService.Summary s = null;
        if (notes != null) {
            try {
                s = notes.summary(ch.getId());
            } catch (RuntimeException e) {
                log.debug("Kanal notu özeti okunamadı (kanal {}): {}", ch.getId(), e.toString());
            }
        }
        if (s == null) {
            m.put("unavailable", true);
            return m;
        }
        m.put("exists", s.exists());
        m.put("empty", s.empty());
        m.put("updated_at", iso(s.updatedAt()));
        m.put("updated_by_name", s.updatedByName());
        m.put("image_count", s.imageCount());
        return m;
    }

    /** Kanal: yok / hiç görünmüyor → 404 {@code CHANNEL_NOT_FOUND}; görünüyor ama notlar paylaşılmıyor → 403. */
    private TechErrorChannel readableChannel(Long channelId, HttpSession session) {
        TechErrorChannel ch = notes.channel(channelId).orElse(null);
        if (ch == null || !TechErrorReportController.canRead(session, ch.getTeamId())) {
            throw TechErrorChannelNoteService.channelNotFound();
        }
        if (!canReadNotes(session, ch.getTeamId())) {
            throw new CodedForbiddenException("CHANNEL_NOTES_NOT_SHARED", Msg.t(
                    "Bu kanalı görüntüleme paylaşımıyla görüyorsunuz; kanal notları yalnız kanalın takımına ve düzenleme düzeyinde paylaşımı olanlara açıktır. Notlara ihtiyacınız varsa kanalın takımından düzenleme düzeyinde erişim paylaşımı isteyin.",
                    "You see this channel through a view share; channel notes are open only to the channel's team and to people with an edit-level share. If you need the notes, ask the channel's team for an edit-level access share."));
        }
        return ch;
    }

    private TechErrorChannel editableChannel(Long channelId, HttpSession session) {
        TechErrorChannel ch = readableChannel(channelId, session);
        if (!canEditNotes(session, ch.getTeamId())) {
            throw new SecurityException(Msg.t(
                    "Kanal notunu yalnız kanalın takımının üyeleri ve yöneticileri düzenleyebilir; erişim paylaşımı notu okutur, düzenletmez.",
                    "Only the members and managers of the channel's team can edit the channel note; an access share lets you read the note, not edit it."));
        }
        return ch;
    }

    private void requireRead(HttpSession session) {
        if (!permissions.allows(session, TechErrorReportController.READ, "view")) {
            throw new SecurityException(TechErrorReportController.READ + " yetkisi gerekli");
        }
        TechErrorReportController.requireModuleVisible(session);
    }

    private void requireWrite(HttpSession session) {
        if (!permissions.allows(session, TechErrorReportController.CRUD, "edit")) {
            throw new SecurityException(TechErrorReportController.CRUD + " yetkisi gerekli");
        }
        TechErrorReportController.requireModuleVisible(session);
    }

    // ── DTO ──────────────────────────────────────────────────────────────────────────────────────────

    private Map<String, Object> noteDto(TechErrorChannel ch, TechErrorChannelNoteService.View view, HttpSession session) {
        TechErrorChannelNote n = view.note();
        String body = view.body();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("channel_id", ch.getId());
        m.put("exists", view.exists());
        m.put("version", view.version());
        m.put("body", body);
        m.put("char_count", body.length());
        m.put("updated_at", n == null ? null : iso(n.getUpdatedAt()));
        m.put("updated_by_name", n == null ? null : n.getUpdatedByName());
        m.put("created_at", n == null ? null : iso(n.getCreatedAt()));
        List<Map<String, Object>> images = new ArrayList<>();
        for (TechErrorChannelNoteImageRepository.Meta i : view.images()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", i.getId());
            r.put("file_name", i.getFileName());
            r.put("content_type", i.getContentType());
            r.put("width", i.getWidth());
            r.put("height", i.getHeight());
            r.put("size_bytes", i.getSizeBytes());
            r.put("uploaded_at", iso(i.getUploadedAt()));
            r.put("linked", Boolean.TRUE.equals(i.getLinked()));
            images.add(r);
        }
        m.put("images", images);
        m.put("image_bytes", view.imageBytes());
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("max_chars", TechErrorChannelNoteService.MAX_CHARS);
        limits.put("max_images", TechErrorChannelNoteService.MAX_IMAGES);
        limits.put("max_image_bytes", TechErrorImageValidator.MAX_BYTES);
        limits.put("max_total_bytes", TechErrorChannelNoteService.MAX_TOTAL_BYTES);
        limits.put("unlinked_keep_days", notes.unlinkedKeepDays());
        limits.put("max_versions", TechErrorChannelNoteService.MAX_VERSIONS);
        m.put("limits", limits);
        m.put("can_edit", permissions.allows(session, TechErrorReportController.CRUD, "edit")
                && canEditNotes(session, ch.getTeamId()));
        m.put("shared_access", SessionScope.viewableOnlyByGrant(session, ch.getTeamId()));
        return m;
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────────

    /** Sürüm: 0 ya da pozitif TAM sayı (not yokken 0). Ondalık / metin / eksik → 400 {@code version}. */
    private static long versionOf(Object o) {
        if (o instanceof Integer || o instanceof Long || o instanceof Short || o instanceof Byte) {
            long v = ((Number) o).longValue();
            if (v >= 0) return v;
        }
        throw new FieldValidationException("version", Msg.t(
                "Notun sürümü gönderilmedi ya da geçersiz. Sayfayı yenileyip notu yeniden açın, sonra tekrar kaydedin.",
                "The note's version wasn't sent or isn't valid. Reload the page, open the note again and then save."));
    }

    /** Gövde METİN olmalı (boş metin geçerli); uzunluk / normalleştirme serviste ({@code normalizeBody}). */
    private static String bodyOf(Object o) {
        if (o instanceof String s) return s;
        throw new FieldValidationException("body", Msg.t(
                "Not metni gönderilmedi ya da metin değil. Sayfayı yenileyip tekrar deneyin.",
                "The note text wasn't sent or isn't text. Reload the page and try again."));
    }

    /**
     * 409 {@code CONCURRENT_MODIFICATION} + güncel durum (sürüm, son kaydeden, zaman) — işlemin DIŞINDA tek hafif okuma.
     * Okuma da düşerse ek alansız aynı 409 (istemci notu yeniden yükler).
     */
    private CodedConflictException currentConflict(Long channelId) {
        try {
            TechErrorChannelNoteService.Summary s = notes.summary(channelId);
            return TechErrorChannelNoteService.conflict(Long.valueOf(s.version()), s.updatedByName(), s.updatedAt());
        } catch (RuntimeException e) {
            log.debug("Kanal notu çakışmasında güncel durum okunamadı (kanal {}): {}", channelId, e.toString());
            return TechErrorChannelNoteService.conflict(null, null, null);
        }
    }

    /** Sınır içindeyse yüklemeyi kaydeder ve {@code null} döner; doluysa en eski yüklemenin pencereden düşmesine kalan saniye (≥ 1). */
    Long reserveUpload(String key) {
        long now = uploadClock.getAsLong();
        synchronized (uploadCalls) {
            if (uploadCalls.size() > 1_000) {
                uploadCalls.values().removeIf(q -> {
                    while (!q.isEmpty() && now - q.peekFirst() >= UPLOAD_WINDOW_MS) q.pollFirst();
                    return q.isEmpty();
                });
                if (uploadCalls.size() > 10_000) uploadCalls.clear();
            }
            Deque<Long> q = uploadCalls.computeIfAbsent(key, k -> new ArrayDeque<>());
            while (!q.isEmpty() && now - q.peekFirst() >= UPLOAD_WINDOW_MS) q.pollFirst();
            if (q.size() >= UPLOADS_PER_MINUTE) {
                long ms = q.peekFirst() + UPLOAD_WINDOW_MS - now;
                return Math.max(1L, (ms + 999) / 1000);
            }
            q.addLast(now);
            return null;
        }
    }

    /** Yalnız saklanan iki tür sunulur; beklenmeyen bir değer tarayıcıya "çalıştırılabilir" tür olarak gitmez. */
    private static MediaType servedType(String stored) {
        if ("image/png".equals(stored)) return MediaType.IMAGE_PNG;
        if ("image/jpeg".equals(stored)) return MediaType.IMAGE_JPEG;
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private static String userKey(HttpSession session) {
        Long uid = userId(session);
        if (uid != null) return "u:" + uid;
        Object u = session.getAttribute("username");
        return u != null ? "n:" + u : "s:" + session.getId();
    }

    private static Long userId(HttpSession session) {
        Object v = session.getAttribute("userId");
        if (v instanceof Number n) return Long.valueOf(n.longValue());
        try { return v == null ? null : Long.valueOf(String.valueOf(v)); } catch (NumberFormatException e) { return null; }
    }

    private static String iso(Instant i) {
        return i == null ? null : i.toString();
    }

    private static ResponseEntity<Map<String, Object>> rateLimited(String message, long retryAfter) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", "RATE_LIMITED");
        body.put("error", message);
        String rid = MDC.get("requestId");
        if (rid != null) body.put("request_id", rid);
        body.put("retry_after_seconds", retryAfter);
        return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter)).body(body);
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }
}
