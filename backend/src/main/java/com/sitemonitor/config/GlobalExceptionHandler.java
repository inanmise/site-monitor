package com.sitemonitor.config;

import com.sitemonitor.service.AuditService;
import com.sitemonitor.util.ErrorTexts;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.lang.reflect.Method;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Denetleyicilerden kaçan istisnaların TEK JSON hata sözleşmesi (2026-10-08 genişletmesi, "hata mesajları çok açıklayıcı
 * olsun"):
 * <pre>{ "success": false, "code": "&lt;KARARLI_KOD&gt;", "error": "&lt;istek dilinde açıklayıcı metin&gt;", "request_id": "…" }</pre>
 * <ul>
 *   <li>{@code error} {@link Msg#t} ile istek dilinde ve NE oldu · NEDEN · NE yapmalı biçiminde. Servislerin kendi
 *       doğrulama/çakışma iletileri (IllegalArgument/IllegalState — projenin doğrulama kanalı) KORUNUR; yalnız teknik
 *       olanlar ({@link ErrorTexts#isTechnical}) açıklayıcı metne çevrilir, ham metin log'a gider.</li>
 *   <li>{@code code} kararlıdır; mevcut özel kodlar (DOMAIN_EXISTS, CodedConflictException kodları …) aynen.</li>
 *   <li>{@code request_id} = {@link CorrelationIdFilter} kimliği (yanıt başlığı {@code X-Request-Id} ile aynı); 500'lerde
 *       log satırı {@code [istek=…]} taşır, kullanıcı bu kimliği ilettiğinde hata satırı doğrudan bulunur.</li>
 *   <li>Beklenmeyen 500'ler istisna metnini ASLA sızdırmaz.</li>
 * </ul>
 * Denetim (audit) kayıtları ham Türkçe metni tutmaya devam eder.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    /** Opsiyonel — @WebMvcTest slice'ları AuditService bean'i sağlamayabilir; yoksa güvenlik olayı sessizce atlanır. */
    private final ObjectProvider<AuditService> auditServiceProvider;

    /** 413 iletisindeki sınır için yedek (istisna izin verilen boyutu taşımıyorsa). Birim testinde null. */
    @Value("${spring.servlet.multipart.max-file-size:}")
    private String configuredMaxFileSize;

    // ── Gövde ────────────────────────────────────────────────────────────────

    /** Ortak hata gövdesi: success:false, code, error, request_id (varsa). */
    static Map<String, Object> errorBody(String code, String error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        if (code != null) body.put("code", code);
        body.put("error", error);
        String rid = CorrelationIdFilter.current();
        if (rid != null) body.put("request_id", rid);
        return body;
    }

    private static ResponseEntity<Map<String, Object>> respond(int status, String code, String error) {
        return ResponseEntity.status(status).body(errorBody(code, error));
    }

    /** Log satırı eki — kullanıcının ilettiği istek kimliği bu satırı bulsun. */
    private static String rid() {
        String r = CorrelationIdFilter.current();
        return r == null ? "-" : r;
    }

    // ── 404 ──────────────────────────────────────────────────────────────────

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoSuchElementException e) {
        String raw = e.getMessage();
        String known = ErrorTexts.localizeKnown(raw);
        String msg;
        if (known != null) msg = known;
        else if (ErrorTexts.isTechnical(raw)) {
            // "No value present" (Optional.orElseThrow()) ya da mesajsız istisna — teknik içerik sızmasın
            if (raw != null && !raw.isBlank()) log.debug("Kayıt bulunamadı [istek={}]: {}", rid(), raw);
            msg = Msg.t("Aradığınız kayıt bulunamadı; silinmiş, taşınmış ya da adı değişmiş olabilir. Listeyi yenileyip tekrar deneyin.",
                    "The record you asked for wasn’t found; it may have been deleted, moved or renamed. Refresh the list and try again.");
        } else msg = raw;
        return respond(404, codeOr(raw, "NOT_FOUND"), msg);
    }

    // ── 409 ──────────────────────────────────────────────────────────────────

    /** Fired when e.g. trying to re-notify an already-resolved alert. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IllegalStateException e) {
        // Mükerrer alan adı (2026-09-28): çakışan kayıt YAPISAL alanla taşınır — arayüz sahibi takımı rozetle
        // çizer, kaydı açar, aktarım/geri yükleme/talep eylemlerini sunar; metni ayrıştırmak zorunda kalmaz.
        if (e instanceof DomainExistsException de) {
            Map<String, Object> body = errorBody(DomainExistsException.CODE, e.getMessage());
            body.put("existing", de.getExisting());
            return ResponseEntity.status(409).body(body);
        }
        // Kodlu çakışma (2026-10-06, manuel sertifika): arayüz iletiyi ayrıştırmaz, kodla dallanır; ek alanlar düz taşınır.
        if (e instanceof CodedConflictException ce) {
            Map<String, Object> body = errorBody(ce.getCode(), e.getMessage());
            if (ce.getExtra() != null) ce.getExtra().forEach(body::putIfAbsent);
            return ResponseEntity.status(409).body(body);
        }
        String raw = e.getMessage();
        if (ErrorTexts.isTechnical(raw)) {
            // Kütüphane kaynaklı IllegalStateException ("Duplicate key …", "Session is closed") bir ÇAKIŞMA değil, hatadır.
            return internalError(e);
        }
        return respond(409, codeOr(raw, "CONFLICT"), raw);
    }

    /** 409 + yapısal {@code code} (ve isteğe bağlı ek alanlar) — {@link #handleConflict}. */
    public static class CodedConflictException extends IllegalStateException {
        private final String code;
        private final Map<String, Object> extra;
        public CodedConflictException(String code, String message) {
            this(code, message, null);
        }
        public CodedConflictException(String code, String message, Map<String, Object> extra) {
            super(message);
            this.code = code;
            this.extra = extra;
        }
        public String getCode() { return code; }
        public Map<String, Object> getExtra() { return extra; }
    }

    /**
     * Alan adı envanterde zaten kayıtlı (ekle / yeniden adlandır) — 409 + {@code code=DOMAIN_EXISTS} +
     * {@code existing}. {@code existing} YALNIZ ad/kimlik/durum ve çağıranın eylem bayraklarını taşır
     * (beyaz liste, {@code AdminController.domainExists}); sorumlu kişi, not, açıklama gibi alanlar ASLA.
     * {@link IllegalStateException} alt sınıfı: onu yakalayan her eski yol aynı 409'u görmeye devam eder.
     */
    public static class DomainExistsException extends IllegalStateException {
        public static final String CODE = "DOMAIN_EXISTS";
        private final Map<String, Object> existing;
        public DomainExistsException(String message, Map<String, Object> existing) {
            super(message);
            this.existing = existing;
        }
        public Map<String, Object> getExisting() { return existing; }
    }

    /** DB kısıt ihlali — ham DB iletisi (tablo/kısıt adları) ASLA sızmaz; türüne göre açıklayıcı metin + ipucu. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        String em = e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : e.getMessage();
        String lower = em == null ? "" : em.toLowerCase(Locale.ROOT);
        log.warn("Veri kısıtı ihlali [istek={}]: {}", rid(), em);
        if (lower.contains("uk3jgfhgl0acmsnh3q4t1730tin")) {
            return respond(409, "DUPLICATE_RECORD", Msg.t(
                    "Bu alan adı envanterde zaten var. Var olan kaydı arayıp açın ya da farklı bir alan adı girin.",
                    "This domain is already in the inventory. Search for the existing record and open it, or enter a different domain."));
        }
        if (lower.contains("foreign key") || lower.contains("referential integrity")) {
            return respond(409, "REFERENCED_RECORD", Msg.t(
                    "Kayıt çakışması: bu kayıt başka kayıtlar tarafından kullanıldığı için işlem yapılamadı (ör. bağlı izlemeler, alarmlar ya da üyeler). Önce bağlı kayıtları kaldırın ya da taşıyın, sonra tekrar deneyin.",
                    "Record conflict: this record is still used by other records (for example linked monitors, alerts or members), so the action couldn’t be done. Remove or move the linked records first, then try again."));
        }
        if (lower.contains("not-null") || lower.contains("null value in column") || lower.contains("null not allowed")) {
            return respond(400, "MISSING_REQUIRED_FIELD", Msg.t(
                    "Zorunlu bir alan boş bırakıldığı için kayıt kaydedilemedi. Formdaki zorunlu alanları doldurup tekrar deneyin.",
                    "The record couldn’t be saved because a required field is empty. Fill in the required fields in the form and try again."));
        }
        if (lower.contains("value too long") || lower.contains("too long for")) {
            return respond(400, "VALUE_TOO_LONG", Msg.t(
                    "Girilen değerlerden biri izin verilen uzunluğu aşıyor. Uzun metinleri kısaltıp tekrar deneyin.",
                    "One of the values you entered is longer than allowed. Shorten the long texts and try again."));
        }
        if (lower.contains("check constraint")) {
            return respond(400, "INVALID_VALUE", Msg.t(
                    "Girilen değerlerden biri kabul edilen aralığın dışında. Değerleri kontrol edip tekrar deneyin.",
                    "One of the values you entered is outside the accepted range. Check the values and try again."));
        }
        return respond(409, "DUPLICATE_RECORD", Msg.t(
                "Kayıt çakışması: aynı bilgilere sahip bir kayıt zaten var (benzersiz olması gereken bir değer tekrarlanıyor). Değeri değiştirip tekrar deneyin ya da var olan kaydı açın.",
                "Record conflict: a record with the same details already exists (a value that must be unique is repeated). Change the value and try again, or open the existing record."));
    }

    // ── 400 ──────────────────────────────────────────────────────────────────

    /** Validation errors from controllers (e.g. blank domain) — projenin doğrulama kanalı: iletisi KORUNUR. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        // Alan bazlı doğrulama (2026-10-08): @Valid'in VALIDATION_FAILED + fields sözleşmesinin elle yazılan kardeşi.
        if (e instanceof FieldValidationException fe) {
            Map<String, Object> body = errorBody("VALIDATION_FAILED", e.getMessage());
            body.put("fields", fe.getFields());
            if (!fe.getFields().isEmpty()) body.put("field", fe.getFields().keySet().iterator().next());
            return ResponseEntity.status(400).body(body);
        }
        // Öneri YAPISAL alanla taşınır: arayüz metni ayrıştırmak zorunda kalırsa TR/EN
        // arasında ya da mesaj her düzenlendiğinde sessizce kırılır.
        if (e instanceof UnresolvableTargetException ue && ue.getSuggestion() != null) {
            Map<String, Object> body = errorBody("UNRESOLVABLE_TARGET", e.getMessage());
            body.put("unresolvable_host", ue.getHost());
            body.put("suggested_host", ue.getSuggestion());
            return ResponseEntity.status(400).body(body);
        }
        String raw = e.getMessage();
        if (e instanceof NumberFormatException) {
            log.debug("Sayı ayrıştırılamadı [istek={}]: {}", rid(), raw);
            return respond(400, "INVALID_NUMBER", Msg.t(
                    "Sayı beklenen bir alana geçersiz bir değer girildi. Değeri yalnız rakamlarla yazıp tekrar deneyin.",
                    "A field that expects a number got an invalid value. Enter the value using digits only and try again."));
        }
        if (raw != null && raw.startsWith("No enum constant ")) {
            log.debug("Geçersiz seçenek [istek={}]: {}", rid(), raw);
            return respond(400, "INVALID_OPTION", Msg.t(
                    "Seçilen değer geçerli seçeneklerden biri değil. Listeden bir seçenek seçip tekrar deneyin; sayfa eskiyse önce yenileyin.",
                    "The selected value isn’t one of the valid options. Pick an option from the list and try again; reload the page first if it’s out of date."));
        }
        if (ErrorTexts.isTechnical(raw)) {
            log.warn("Geçersiz istek (teknik ileti gizlendi) [istek={}]: {}", rid(), raw);
            return respond(400, "INVALID_REQUEST", Msg.t(
                    "İstek kabul edilmedi: gönderilen bilgilerden biri geçersiz. Formdaki alanları kontrol edip tekrar deneyin.",
                    "The request was not accepted: some of the information sent is invalid. Check the fields in the form and try again."));
        }
        return respond(400, codeOr(raw, "INVALID_REQUEST"), raw);
    }

    /**
     * Tanılama hedefi DNS'te çözülmedi; varsa çalışan bir alternatif ({@code www.<host>}) taşır.
     *
     * <p>Bir politika reddi DEĞİL — kullanıcı "izin verilmeyen hedef" mesajını aracın kendisini
     * engellediği sanmıştı; çözümü host adını düzeltmek.
     */
    public static class UnresolvableTargetException extends IllegalArgumentException {
        private final String host;
        private final String suggestion;
        public UnresolvableTargetException(String message, String host, String suggestion) {
            super(message);
            this.host = host;
            this.suggestion = suggestion;
        }
        public String getHost() { return host; }
        public String getSuggestion() { return suggestion; }
    }

    /**
     * Alan bazlı doğrulama hatası (2026-10-08, "doğrulanmadan alınan veri var mı? doğrulama ekle"): elle doğrulayan
     * denetleyici/servis yolları ({@code Map} gövdeli uçlar) bunu fırlatır → 400 {@code VALIDATION_FAILED} + {@code fields}
     * ({alan: ileti}) + {@code field} (ilk alan). İleti {@link Msg#t} ile istek dilindedir ve alan adını içerir: istemci
     * {@code fields}'i okumasa da tost/şerit tek başına anlaşılır. {@link IllegalArgumentException} alt sınıfı — onu
     * yakalayan eski yollar aynı 400'ü görmeye devam eder.
     */
    public static class FieldValidationException extends IllegalArgumentException {
        private final Map<String, String> fields;
        public FieldValidationException(String field, String message) {
            super(message);
            Map<String, String> f = new LinkedHashMap<>();
            f.put(field, message);
            this.fields = java.util.Collections.unmodifiableMap(f);
        }
        public Map<String, String> getFields() { return fields; }
    }

    /**
     * Gövde alanı beklenen türde değil (2026-10-08): {@code Map} gövdeli uçlardaki {@code (Number) body.get(..)} gibi
     * dönüşümler {@code {"intervalSeconds":"60"}} gövdesinde {@link ClassCastException} fırlatıp 500 üretiyordu.
     * İstemci hatasıdır → 400 {@code VALIDATION_FAILED}; istisna metni (sınıf adları) ASLA dönmez, log'a WARN ile gider
     * (sunucu içi gerçek bir tür hatası da böylece görünür kalır).
     */
    @ExceptionHandler(ClassCastException.class)
    public ResponseEntity<Map<String, Object>> handleClassCast(ClassCastException e) {
        log.warn("Gövde alanı beklenmeyen türde [istek={}]: {}", rid(), e.getMessage());
        return respond(400, "VALIDATION_FAILED", Msg.t(
                "Gönderilen alanlardan birinin türü yanlış (ör. sayı beklenen yerde metin ya da açık/kapalı beklenen yerde sayı). Formdaki değerleri kontrol edip tekrar deneyin; sorun sürerse sayfayı yenileyin.",
                "A field you sent has the wrong type (for example text where a number is expected, or a number where on/off is expected). Check the values in the form and try again; if it keeps happening, reload the page."));
    }

    /** @Valid başarısız oldu — body bind sırasında alan kuralı kırıldı. {@code fields} haritası alan altı iletiler için. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        return validationResponse(e.getBindingResult().getFieldErrors());
    }

    /** @ModelAttribute / form bağlama hatası (MethodArgumentNotValid'in üst sınıfı). */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Map<String, Object>> handleBind(BindException e) {
        return validationResponse(e.getBindingResult().getFieldErrors());
    }

    private static ResponseEntity<Map<String, Object>> validationResponse(List<FieldError> errors) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fe : errors) {
            fields.putIfAbsent(fe.getField(), fe.getDefaultMessage() != null && !ErrorTexts.isTechnical(fe.getDefaultMessage())
                    ? fe.getDefaultMessage() : Msg.t("geçersiz değer", "invalid value"));
        }
        List<String> parts = new ArrayList<>();
        int i = 0;
        for (Map.Entry<String, String> f : fields.entrySet()) {
            if (i++ == 3) { parts.add("+" + (fields.size() - 3)); break; }
            parts.add(f.getKey() + ": " + f.getValue());
        }
        String list = String.join("; ", parts);
        Map<String, Object> body = errorBody("VALIDATION_FAILED", Msg.t(
                "Formdaki bazı alanlar geçersiz (" + list + "). Alanların altındaki uyarıları düzeltip tekrar deneyin.",
                "Some fields in the form are invalid (" + list + "). Fix the fields marked below and try again."));
        body.put("fields", fields);
        return ResponseEntity.status(400).body(body);
    }

    /** @Validated metot parametresi (yol/sorgu değişkeni) kuralı kırıldı. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> handleMethodValidation(HandlerMethodValidationException e) {
        log.debug("Parametre doğrulaması [istek={}]: {}", rid(), e.getMessage());
        return respond(400, "VALIDATION_FAILED", Msg.t(
                "İstekteki bir değer kurallara uymuyor (ör. aralık dışı sayı ya da boş değer). Değerleri kontrol edip tekrar deneyin.",
                "A value in the request doesn’t meet the rules (for example a number out of range or an empty value). Check the values and try again."));
    }

    /** Bozuk JSON body. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMalformedJson(HttpMessageNotReadableException e) {
        log.debug("Okunamayan istek gövdesi [istek={}]: {}", rid(), e.getMessage());
        return respond(400, "MALFORMED_REQUEST", Msg.t(
                "Geçersiz istek formatı: gönderilen veri okunamadı ya da bir alanın türü yanlış (ör. sayı yerine metin). Sayfayı yenileyip tekrar deneyin; sorun sürerse sistem yöneticilerine bildirin.",
                "Invalid request format: the data sent couldn’t be read or a field has the wrong type (for example text instead of a number). Reload the page and try again; if it keeps happening, tell the system administrators."));
    }

    /** Query/form parametresi eksik. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException e) {
        String p = e.getParameterName();
        return respond(400, "MISSING_PARAMETER", Msg.t(
                "Eksik parametre: '" + p + "' değeri gönderilmedi. Formu ya da adresi kontrol edip tekrar deneyin; bağlantıdan geldiyseniz sayfayı yenileyin.",
                "Missing parameter: no value was sent for '" + p + "'. Check the form or address and try again; if you came from a link, reload the page."));
    }

    /** Yol/sorgu değişkeni yanlış türde ("abc" → Long, bilinmeyen enum değeri, bozuk tarih). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String name = e.getName();
        Object v = e.getValue();
        String shown = v == null ? "" : String.valueOf(v);
        if (shown.length() > 40) shown = shown.substring(0, 40) + "…";
        Class<?> type = e.getRequiredType();
        String[] expected = expectedType(type);
        return respond(400, "INVALID_PARAMETER", Msg.t(
                "'" + name + "' değeri geçersiz: '" + shown + "' beklenen biçimde değil (beklenen: " + expected[0] + "). Adresi ya da formu kontrol edip tekrar deneyin.",
                "The value of '" + name + "' is invalid: '" + shown + "' isn’t in the expected format (expected: " + expected[1] + "). Check the address or form and try again."));
    }

    private static String[] expectedType(Class<?> t) {
        if (t == null) return new String[]{"geçerli bir değer", "a valid value"};
        if (Number.class.isAssignableFrom(t) || t == int.class || t == long.class || t == short.class || t == double.class || t == float.class) {
            return new String[]{"sayı", "a number"};
        }
        if (t == Boolean.class || t == boolean.class) return new String[]{"true ya da false", "true or false"};
        if (t.isEnum()) return new String[]{"listedeki seçeneklerden biri", "one of the listed options"};
        if (Temporal.class.isAssignableFrom(t)) return new String[]{"tarih/saat (ör. 2026-10-08)", "a date/time (e.g. 2026-10-08)"};
        return new String[]{"geçerli bir değer", "a valid value"};
    }

    /** Eksik başlık / çerez / istek değişkeni (MissingRequestHeaderException …). */
    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<Map<String, Object>> handleBinding(ServletRequestBindingException e) {
        log.debug("İstek bağlama hatası [istek={}]: {}", rid(), e.getMessage());
        return respond(400, "INVALID_REQUEST", Msg.t(
                "İstek eksik ya da hatalı geldi (gerekli bir başlık ya da değer yok). Sayfayı yenileyip tekrar deneyin; sorun sürerse sistem yöneticilerine bildirin.",
                "The request arrived incomplete or malformed (a required header or value is missing). Reload the page and try again; if it keeps happening, tell the system administrators."));
    }

    /** Çok parçalı yüklemede dosya parçası yok. */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> handleMissingPart(MissingServletRequestPartException e) {
        String part = e.getRequestPartName();
        return respond(400, "MISSING_FILE", Msg.t(
                "Yüklenecek dosya bulunamadı ('" + part + "'). Bir dosya seçip tekrar deneyin.",
                "No file to upload was found ('" + part + "'). Choose a file and try again."));
    }

    // ── 413 / 415 / yükleme ──────────────────────────────────────────────────

    /** Yükleme sınırı aşıldı — sınır MB olarak hem metinde hem {@code max_mb} alanında. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        long limit = permittedBytes(e);
        if (limit <= 0) limit = parseSize(configuredMaxFileSize);
        log.info("Yükleme boyut sınırı aşıldı [istek={}]: {}", rid(), e.getMessage());
        if (limit > 0) {
            String mb = formatMb(limit);
            Map<String, Object> body = errorBody("PAYLOAD_TOO_LARGE", Msg.t(
                    "Dosya çok büyük: en fazla " + mb + " MB yükleyebilirsiniz. Dosyayı küçültüp ya da parçalara bölüp tekrar deneyin.",
                    "The file is too large: you can upload at most " + mb + " MB. Make the file smaller or split it into parts, then try again."));
            body.put("max_mb", Double.valueOf(limit / 1048576.0));
            return ResponseEntity.status(413).body(body);
        }
        return respond(413, "PAYLOAD_TOO_LARGE", Msg.t(
                "Dosya ya da gönderilen veri izin verilen boyutu aşıyor. Dosyayı küçültüp ya da parçalara bölüp tekrar deneyin.",
                "The file or data you sent is larger than allowed. Make the file smaller or split it into parts, then try again."));
    }

    /** Diğer çok parçalı yükleme hataları (yarıda kesilen yükleme, çok parçalı olmayan istek). */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, Object>> handleMultipart(MultipartException e) {
        log.info("Yükleme okunamadı [istek={}]: {}", rid(), e.getMessage());
        return respond(400, "UPLOAD_FAILED", Msg.t(
                "Dosya yüklemesi okunamadı; yükleme yarıda kesilmiş ya da bağlantı kopmuş olabilir. Dosyayı yeniden seçip tekrar yükleyin.",
                "The file upload couldn’t be read; it may have been interrupted or the connection dropped. Choose the file again and re-upload it."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaType(HttpMediaTypeNotSupportedException e) {
        log.debug("Desteklenmeyen içerik türü [istek={}]: {}", rid(), e.getMessage());
        return respond(415, "UNSUPPORTED_MEDIA_TYPE", Msg.t(
                "Gönderilen içerik türü bu işlemde desteklenmiyor. Sayfayı tamamen yenileyip (Ctrl+F5) tekrar deneyin; dosya yüklüyorsanız desteklenen bir biçim seçin.",
                "The content type you sent isn’t supported for this action. Fully reload the page (Ctrl+F5) and try again; if you’re uploading a file, choose a supported format."));
    }

    /** Tomcat'in SizeException'ı izin verilen boyutu taşır ({@code getPermittedSize}) — Tomcat'e derleme bağımlılığı olmadan. */
    static long permittedBytes(MaxUploadSizeExceededException e) {
        if (e.getMaxUploadSize() > 0) return e.getMaxUploadSize();
        int causeDepth = 0;   // neden zinciri tavanı: A→B→A döngüsü sonsuza dek dönmesin
        for (Throwable c = e.getCause(); c != null && c != c.getCause() && causeDepth++ < com.sitemonitor.util.CauseChain.MAX_DEPTH; c = c.getCause()) {
            try {
                Method m = c.getClass().getMethod("getPermittedSize");
                Object v = m.invoke(c);
                if (v instanceof Number n && n.longValue() > 0) return n.longValue();
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // bu halkada yok
            }
        }
        return -1;
    }

    static long parseSize(String s) {
        if (s == null || s.isBlank()) return -1;
        try {
            return DataSize.parse(s.trim()).toBytes();
        } catch (RuntimeException ex) {
            return -1;
        }
    }

    static String formatMb(long bytes) {
        double mb = bytes / 1048576.0;
        return mb == Math.rint(mb) ? String.valueOf((long) mb) : String.format(Locale.ROOT, "%.1f", mb);
    }

    // ── 403 ──────────────────────────────────────────────────────────────────

    /** Access denied — yetkisiz/oturumsuz/IDOR erişim denemesi. GÜVENLİK OLAYI olarak denetlenir (BLOCKED). */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(SecurityException e, HttpServletRequest request) {
        String msg = e.getMessage() != null ? e.getMessage() : "Erişim reddedildi";
        String lower = msg.toLowerCase();
        String eventType = (lower.contains("authentication") || lower.contains("kimlik") || lower.contains("oturum"))
                ? "AUTH_REQUIRED" : "ACCESS_DENIED";
        try {
            AuditService audit = auditServiceProvider.getIfAvailable();
            if (audit != null) {
                // Denetim HAM (sistem dili) metni tutar — kullanıcıya giden açıklayıcı metin değil.
                audit.recordSecurityEvent(eventType, request,
                        request != null ? request.getSession(false) : null,
                        "ENDPOINT",
                        request != null ? (request.getMethod() + " " + request.getRequestURI()) : null,
                        msg);
            }
        } catch (Exception ignored) { /* denetim yazımı isteği asla düşürmez */ }
        String shown = ErrorTexts.localizeKnown(msg);
        if (shown == null) {
            shown = ErrorTexts.isTechnical(e.getMessage())
                    ? Msg.t("Bu işlem için yetkiniz yok. Gerekiyorsa takım yöneticinizden ya da sistem yöneticisinden bu izni isteyin.",
                            "You don’t have permission for this action. If you need it, ask your team manager or a system administrator to grant it.")
                    : msg;
        }
        boolean auth = "AUTH_REQUIRED".equals(eventType) || lower.contains("authenticated");
        String code = ErrorTexts.isCodeLike(msg) ? msg.trim() : (auth ? "AUTH_REQUIRED" : "FORBIDDEN");
        return respond(403, code, shown);
    }

    // ── 405 / passthrough ────────────────────────────────────────────────────

    /** Eksik statik kaynak (favicon.ico, eşleşmeyen statik yol) — sessiz 404.
     *  Generic handler'a düşüp ERROR + tam stack basmasın (gereksiz log gürültüsü).
     *  (2026-10-08: {@link SpaNotFoundAdvice} bu istisnayı daha önce yakalar; bu işleyici yedek olarak kalır.) */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e) {
        log.debug("Statik kaynak bulunamadı: {}", e.getResourcePath());
        return ResponseEntity.status(404)
                .body(Map.of("success", false, "error", "Kaynak bulunamadı"));
    }

    /**
     * Yol var ama yöntem yok — 405 (2026-10-07). Kaldırılmış bir uca (ör. çöp kutusu {@code POST /inventory/purge-deleted};
     * yol {@code /inventory/{id}} kalıbına düşer) eski bir istemciden gelen istek jenerik 500 + ERROR yığını üretiyordu.
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotAllowed(
            org.springframework.web.HttpRequestMethodNotSupportedException e) {
        log.debug("Desteklenmeyen HTTP yöntemi: {}", e.getMessage());
        return respond(405, "METHOD_NOT_ALLOWED", Msg.t(
                "Bu işlem bu adreste desteklenmiyor; uygulama yeni bir sürüme güncellenmiş olabilir. Sayfayı tamamen yenileyip (Ctrl+F5) tekrar deneyin.",
                "This action isn’t supported at this address; the app may have been updated to a new version. Fully reload the page (Ctrl+F5) and try again."));
    }

    /** Controller'lardan elle fırlatılmış status hatası — gerekçe (reason) iletisi korunur; yoksa duruma göre metin. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException e) {
        int status = e.getStatusCode().value();
        String reason = e.getReason();
        String msg = reason != null && !ErrorTexts.isTechnical(reason) ? reason : statusText(status);
        return respond(status, codeOr(reason, "HTTP_" + status), msg);
    }

    // ── 500 / 503 ────────────────────────────────────────────────────────────

    /** Veritabanına erişilemiyor (bağlantı kurulamadı / havuz tükendi) ya da geçici DB hatası → 503 (geçici,
     *  tekrar denenebilir). DataAccessResourceFailureException = "Unable to acquire JDBC Connection" (postgres
     *  blip'i / havuz boş); CannotGetJdbcConnectionException onun alt-sınıfı → kapsanır. Bad SQL grammar gibi
     *  KALICI DAO hataları bu handler'a düşmez → {@link #handleDataAccess}. WARN (blipte ERROR yığını basmasın). */
    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class,
            org.springframework.transaction.CannotCreateTransactionException.class})
    public ResponseEntity<Map<String, Object>> handleDbUnavailable(Exception e) {
        log.warn("Veritabanına erişilemiyor (geçici) [istek={}]: {}", rid(), e.toString());
        return respond(503, "DB_UNAVAILABLE", Msg.t(
                "Veritabanına şu anda ulaşılamıyor; kısa bir kesinti ya da yoğunluk olabilir. Birkaç saniye sonra tekrar deneyin; sorun sürerse sistem yöneticilerine bildirin.",
                "The database can’t be reached right now; there may be a short outage or heavy load. Try again in a few seconds; if it keeps happening, tell the system administrators."));
    }

    /**
     * Eşzamanlı düzenleme (iyimser kilit): kayıt biz düzenlerken başkası tarafından değişti → 409. (Bu istisna
     * TransientDataAccessException altında; eskiden "veritabanına ulaşılamıyor" 503'ü alıyordu — yanlış neden.)
     */
    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(Exception e) {
        log.info("Eşzamanlı düzenleme çakışması [istek={}]: {}", rid(), e.toString());
        return respond(409, "CONCURRENT_MODIFICATION", Msg.t(
                "Kayıt siz düzenlerken başka biri tarafından değiştirildi; değişikliğiniz kaydedilmedi. Sayfayı yenileyip güncel hâli üzerinden tekrar deneyin.",
                "Someone else changed this record while you were editing it, so your change wasn’t saved. Reload the page and try again on the latest version."));
    }

    /** JPA "kayıt yok" (getReferenceById / tembel yükleme) → 404, 500 değil. */
    @ExceptionHandler({jakarta.persistence.EntityNotFoundException.class,
            org.springframework.orm.ObjectRetrievalFailureException.class})
    public ResponseEntity<Map<String, Object>> handleEntityNotFound(Exception e) {
        log.debug("Kayıt bulunamadı (JPA) [istek={}]: {}", rid(), e.toString());
        return respond(404, "NOT_FOUND", Msg.t(
                "Aradığınız kayıt bulunamadı; silinmiş, taşınmış ya da adı değişmiş olabilir. Listeyi yenileyip tekrar deneyin.",
                "The record you asked for wasn’t found; it may have been deleted, moved or renamed. Refresh the list and try again."));
    }

    /** Kalıcı veri erişim hatası (hatalı SQL, işlem dışı yazım …) — sunucu hatası; ileti sızmaz, log'a tam yığın. */
    @ExceptionHandler({DataAccessException.class, org.springframework.transaction.TransactionException.class,
            jakarta.persistence.PersistenceException.class})
    public ResponseEntity<Map<String, Object>> handleDataAccess(Exception e) {
        // Çevrilmemiş kısıt ihlali (SQLState 23xxx — ör. servis içi flush'ta Hibernate ConstraintViolationException)
        // bir sunucu hatası değil; kısıt işleyicisiyle aynı açıklayıcı yanıtı alır.
        int causeDepth = 0;   // neden zinciri tavanı: A→B→A döngüsü sonsuza dek dönmesin
        for (Throwable c = e; c != null && c != c.getCause() && causeDepth++ < com.sitemonitor.util.CauseChain.MAX_DEPTH; c = c.getCause()) {
            if (c instanceof java.sql.SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("23")) {
                return handleDataIntegrityViolation(new DataIntegrityViolationException(sql.getMessage(), e));
            }
        }
        log.error("Veri erişim hatası [istek={}]: {}", rid(), e.toString(), e);
        return respond(500, "DATA_ACCESS_ERROR", Msg.t(
                "Kayıt işlemi sunucuda tamamlanamadı; değişiklikleriniz kaydedilmemiş olabilir. Sayfayı yenileyip son durumu kontrol edin ve tekrar deneyin; sorun sürerse sistem yöneticilerine istek kimliğiyle (" + rid() + ") bildirin.",
                "The data operation couldn’t be completed on the server; your changes may not have been saved. Reload the page to check the current state and try again; if it keeps happening, tell the system administrators and include the request ID (" + rid() + ")."));
    }

    /** Uzun süren asenkron istek süre sınırını aştı. */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleAsyncTimeout(AsyncRequestTimeoutException e) {
        log.warn("Asenkron istek zaman aşımı [istek={}]", rid());
        return respond(503, "REQUEST_TIMEOUT", Msg.t(
                "İşlem zamanında tamamlanamadı; sunucu yoğun olabilir. İşlem arka planda sürüyor olabilir: bir dakika sonra sonucu kontrol edip gerekirse tekrar deneyin.",
                "The action didn’t finish in time; the server may be busy. It may still be running in the background: check the result in a minute and try again if needed."));
    }

    /** İstemci yanıt tamamlanmadan bağlantıyı kapattı/reset etti (sekme kapatma, proxy/LB reset, oturum devralma).
     *  Sunucu hatası DEĞİL — yanıt zaten yazılamaz; ERROR + 500 denemesi yalnızca gürültü (yanlış alarm). void →
     *  Spring hiçbir gövde yazmaz; iz DEBUG'da kalır. (Logdaki tam tip: flushBuffer sırasında Connection reset.) */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(AsyncRequestNotUsableException e) {
        log.debug("İstemci bağlantıyı erken kapattı (yanıt yazılamadı): {}", e.toString());
    }

    /**
     * Son çare: handler eşleşmeyen her şey burada yakalanır.
     * Stack trace UI'ye sızmasın — kullanıcıya açıklayıcı metin + istek kimliği, log'a tam hata (aynı kimlikle).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
        // Senkron istemci-kopması (ClientAbortException / broken pipe) async-wrapper olmadan buraya düşebilir →
        // ERROR/500 üretme (bağlantı ölü); DEBUG + gövde yazma (null = "handled, gövde yok").
        if (isClientAbort(e)) {
            log.debug("İstemci bağlantıyı erken kapattı (yanıt yazılamadı): {}", e.toString());
            return null;
        }
        // Spring'in kendi 4xx istisnaları (406, NoHandlerFound …) ErrorResponse taşır — 500 + ERROR yığını değil.
        if (e instanceof ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            int status = er.getStatusCode().value();
            log.debug("İstemci hatası {} [istek={}]: {}", status, rid(), e.toString());
            return respond(status, "HTTP_" + status, statusText(status));
        }
        return internalError(e);
    }

    private ResponseEntity<Map<String, Object>> internalError(Exception e) {
        String id = rid();
        log.error("Beklenmeyen sunucu hatası [istek={}]: {}", id, e.toString(), e);
        return respond(500, "INTERNAL_ERROR", Msg.t(
                "Sunucuda beklenmeyen bir hata oluştu ve işlem tamamlanamadı. Birkaç saniye sonra tekrar deneyin; sorun sürerse sistem yöneticilerine istek kimliğini (" + id + ") iletin.",
                "An unexpected error occurred on the server and the action wasn’t completed. Try again in a few seconds; if it keeps happening, give the system administrators the request ID (" + id + ")."));
    }

    /** Duruma göre açıklayıcı metin (gerekçesiz ResponseStatusException / Spring 4xx). İstemcideki eşlemeyle uyumlu. */
    static String statusText(int status) {
        return switch (status) {
            case 400 -> Msg.t("İstek kabul edilmedi: gönderilen bilgilerden biri eksik ya da geçersiz. Formdaki alanları kontrol edip tekrar deneyin.",
                    "The request was not accepted: some of the information sent is missing or invalid. Check the fields in the form and try again.");
            case 401 -> Msg.t("Oturumunuz bulunamadı ya da sona erdi. Sayfayı yenileyip yeniden giriş yapın.",
                    "Your session was not found or has ended. Reload the page and sign in again.");
            case 403 -> Msg.t("Bu işlem için yetkiniz yok. Gerekiyorsa takım yöneticinizden ya da sistem yöneticisinden bu izni isteyin.",
                    "You don’t have permission for this action. If you need it, ask your team manager or a system administrator to grant it.");
            case 404 -> Msg.t("Aradığınız kayıt ya da adres bulunamadı; silinmiş, taşınmış ya da adı değişmiş olabilir. Listeyi yenileyip tekrar deneyin.",
                    "The record or address you asked for wasn’t found; it may have been deleted, moved or renamed. Refresh the list and try again.");
            case 406 -> Msg.t("Sunucu yanıtı istenen biçimde üretemedi. Sayfayı yenileyip tekrar deneyin.",
                    "The server couldn’t produce the response in the requested format. Reload the page and try again.");
            case 409 -> Msg.t("İşlem bir çakışma yüzünden yapılamadı: kayıt bu arada değişmiş ya da aynı kayıt zaten var olabilir. Sayfayı yenileyip tekrar deneyin.",
                    "The action couldn’t be completed because of a conflict: the record may have changed in the meantime or already exist. Reload the page and try again.");
            case 410 -> Msg.t("Bu kayıt ya da bağlantı artık geçerli değil. Listeyi yenileyip güncel kayıt üzerinden devam edin.",
                    "This record or link is no longer valid. Refresh the list and continue from the current record.");
            case 413 -> Msg.t("Gönderilen dosya ya da veri izin verilen boyutu aşıyor. Dosyayı küçültüp tekrar deneyin.",
                    "The file or data you sent is larger than allowed. Make the file smaller and try again.");
            case 429 -> Msg.t("Kısa sürede çok fazla istek gönderildi. Bir dakika kadar bekleyip tekrar deneyin.",
                    "Too many requests were sent in a short time. Wait about a minute and try again.");
            case 503 -> Msg.t("Hizmet geçici olarak kullanılamıyor. Bir dakika sonra tekrar deneyin; sorun sürerse sistem yöneticilerine bildirin.",
                    "The service is temporarily unavailable. Try again in a minute; if it keeps happening, tell the system administrators.");
            default -> status >= 500
                    ? Msg.t("Sunucu isteği işleyemedi (HTTP " + status + "). Birkaç saniye sonra tekrar deneyin; sorun sürerse sistem yöneticilerine bildirin.",
                            "The server couldn’t process the request (HTTP " + status + "). Try again in a few seconds; if it keeps happening, tell the system administrators.")
                    : Msg.t("İstek sunucu tarafından reddedildi (HTTP " + status + "). Sayfayı yenileyip tekrar deneyin; sorun sürerse sistem yöneticilerine bildirin.",
                            "The server refused the request (HTTP " + status + "). Reload the page and try again; if it keeps happening, tell the system administrators.");
        };
    }

    /** BÜYÜK_HARF_KOD biçimli ileti kodun kendisidir (ön yüz onunla dallanır); değilse verilen kararlı kod. */
    private static String codeOr(String raw, String fallback) {
        return ErrorTexts.isCodeLike(raw) ? raw.trim() : fallback;
    }

    /** İstisna zincirinde istemci-kopması var mı — Tomcat'e import bağımlılığı olmadan (sınıf-adı + IOException
     *  mesajı ile). ClientAbortException / AsyncRequestNotUsableException / "broken pipe" / "connection reset". */
    static boolean isClientAbort(Throwable t) {
        int causeDepth = 0;   // neden zinciri tavanı: A→B→A döngüsü sonsuza dek dönmesin
        for (Throwable c = t; c != null && causeDepth++ < com.sitemonitor.util.CauseChain.MAX_DEPTH; c = c.getCause()) {
            String cn = c.getClass().getName();
            if (cn.equals("org.apache.catalina.connector.ClientAbortException")
                    || cn.equals("org.springframework.web.context.request.async.AsyncRequestNotUsableException")) {
                return true;
            }
            if (c instanceof java.io.IOException && c.getMessage() != null) {
                String m = c.getMessage().toLowerCase();
                if (m.contains("broken pipe") || m.contains("connection reset") || m.contains("reset by peer")) {
                    return true;
                }
            }
            if (c.getCause() == c) break;   // kendine-referanslı zincirde sonsuz döngü önle
        }
        return false;
    }
}
