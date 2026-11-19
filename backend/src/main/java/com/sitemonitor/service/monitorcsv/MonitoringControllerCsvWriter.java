package com.sitemonitor.service.monitorcsv;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.controller.DbQueryApiException;
import com.sitemonitor.controller.MonitoringController;
import com.sitemonitor.util.ErrorTexts;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link MonitorCsvWriter}'ın üretim uygulaması (2026-11-19): satırın gövdesini MonitoringController'ın create / update
 * metotlarına verir — yani içe aktarılan her satır, kullanıcının formdan kaydettiği izlemeyle AYNI yoldan geçer. Denetleyici
 * {@link ObjectProvider} ile çözülür (bağlam kurulurken döngü yok; denetleyicinin kurucusu değişmez).
 *
 * <p>Sonuç eşlemesi: 2xx → başarı (kimlik {@code data.id}); başka durum → gövdedeki {@code error} metni; fırlatılan alan
 * hatası / güvenlik reddi / dbquery hatası kendi metniyle; beklenmeyen istisna yalnız güvenli metinle (sınıf adı ya da SQL
 * metni kullanıcıya gitmez — {@link ErrorTexts#safeMessage}).
 */
@Slf4j
@Component
public class MonitoringControllerCsvWriter implements MonitorCsvWriter {

    private final ObjectProvider<MonitoringController> controller;

    public MonitoringControllerCsvWriter(ObjectProvider<MonitoringController> controller) {
        this.controller = controller;
    }

    @Override
    public Result create(String type, Map<String, Object> body, HttpSession session) {
        MonitoringController c = controller.getObject();
        Map<String, Object> b = new LinkedHashMap<>(body);
        return call(() -> switch (type) {
            case "http" -> c.createHttp(b, session);
            case "keyword" -> c.createKeyword(b, session);
            case "page" -> c.createPage(b, session);
            case "pagespeed" -> c.createPageSpeed(b, session);
            case "ping" -> c.createPing(b, session);
            case "port" -> c.createPort(b, session);
            case "dns" -> c.createDns(b, session);
            case "domain" -> c.createDomain(b, session);
            case "scripted" -> c.createScripted(b, session);
            case "dbquery" -> c.createDbQuery(b, session);
            default -> throw new IllegalArgumentException("type");
        });
    }

    @Override
    public Result update(String type, Long id, Map<String, Object> body, HttpSession session) {
        MonitoringController c = controller.getObject();
        Map<String, Object> b = new LinkedHashMap<>(body);
        return call(() -> switch (type) {
            case "http" -> c.updateHttp(id, b, session);
            case "keyword" -> c.updateKeyword(id, b, session);
            case "page" -> c.updatePage(id, b, session);
            case "pagespeed" -> c.updatePageSpeed(id, b, session);
            case "ping" -> c.updatePing(id, b, session);
            case "port" -> c.updatePort(id, b, session);
            case "dns" -> c.updateDns(id, b, session);
            case "domain" -> c.updateDomain(id, b, session);
            case "scripted" -> c.updateScripted(id, b, session);
            case "dbquery" -> c.updateDbQuery(id, b, session);
            default -> throw new IllegalArgumentException("type");
        });
    }

    private interface Call {
        ResponseEntity<Map<String, Object>> run();
    }

    static Result call(Call call) {
        try {
            return fromResponse(call.run());
        } catch (GlobalExceptionHandler.FieldValidationException e) {
            String field = e.getFields() == null || e.getFields().isEmpty() ? null : e.getFields().keySet().iterator().next();
            return Result.failure(e.getMessage(), field);
        } catch (DbQueryApiException e) {
            String field = e.fields().isEmpty() ? null : e.fields().keySet().iterator().next();
            return Result.failure(e.getMessage(), field);
        } catch (SecurityException e) {
            return Result.failure(ErrorTexts.safeMessage(e, "Bu izleme için yetkiniz yok.", "You don't have permission for this monitor."), null);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.failure(ErrorTexts.safeMessage(e, "Satır kaydedilemedi — değerleri denetleyin.",
                    "The row couldn't be saved — check its values."), null);
        } catch (RuntimeException e) {
            log.warn("CSV içe aktarma satırı kaydedilemedi: {}", e.getClass().getSimpleName());
            return Result.failure(Msg.t("Satır kaydedilemedi: beklenmeyen bir hata oluştu. Değerleri denetleyip yeniden deneyin.",
                    "The row couldn't be saved: an unexpected error occurred. Check its values and try again."), null);
        }
    }

    @SuppressWarnings("unchecked")
    static Result fromResponse(ResponseEntity<Map<String, Object>> res) {
        if (res == null) return Result.failure(Msg.t("Satır kaydedilemedi (yanıt yok).", "The row couldn't be saved (no response)."), null);
        Map<String, Object> body = res.getBody();
        if (res.getStatusCode().is2xxSuccessful()) {
            Long id = null;
            String warning = null;
            if (body != null && body.get("data") instanceof Map<?, ?> d) {
                if (d.get("id") instanceof Number n) id = n.longValue();
                warning = warnings(d);
            }
            return Result.success(id, warning);
        }
        String msg = body == null ? null : body.get("error") == null ? null : String.valueOf(body.get("error"));
        String field = body == null ? null : body.get("field") == null ? null : String.valueOf(body.get("field"));
        if (msg == null || msg.isBlank()) {
            msg = res.getStatusCode().value() == 404
                    ? Msg.t("İzleme bulunamadı — silinmiş olabilir.", "Monitor not found — it may have been deleted.")
                    : Msg.t("Satır kaydedilemedi (HTTP " + res.getStatusCode().value() + ").",
                            "The row couldn't be saved (HTTP " + res.getStatusCode().value() + ").");
        }
        return Result.failure(msg, field);
    }

    /** En çok bu kadar uyarı metni birleştirilir; metin en çok {@link #WARNING_MAX_CHARS} karakter. */
    static final int WARNING_MAX_ITEMS = 3;
    static final int WARNING_MAX_CHARS = 600;

    /**
     * 2xx yanıtındaki uyarılar → tek okunur metin ya da null: k6 {@code warnings} (metin listesi), dbquery {@code warnings}
     * ({@code {code, message}} listesi) ve {@code plan_warning} (yalnız {@code verdict = WARN}). Satır "uygulandı" olsa da
     * kullanıcı uyarıyı görmeli (formdan kaydederken gördüğü gibi). Sınırlı: ≤ 3 madde, ≤ 600 karakter.
     */
    static String warnings(Map<?, ?> data) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (data.get("plan_warning") instanceof Map<?, ?> pw && "WARN".equals(String.valueOf(pw.get("verdict")))
                && pw.get("message") != null && !String.valueOf(pw.get("message")).isBlank()) {
            out.add(String.valueOf(pw.get("message")).trim());
        }
        if (data.get("warnings") instanceof java.util.Collection<?> list) {
            for (Object w : list) {
                if (out.size() >= WARNING_MAX_ITEMS) break;
                Object text = w instanceof Map<?, ?> m ? m.get("message") : w;
                if (text != null && !String.valueOf(text).isBlank()) out.add(String.valueOf(text).trim());
            }
        }
        if (out.isEmpty()) return null;
        String joined = String.join(" · ", out.subList(0, Math.min(out.size(), WARNING_MAX_ITEMS)));
        if (joined.length() <= WARNING_MAX_CHARS) return joined;
        int cut = WARNING_MAX_CHARS - 1;
        if (Character.isHighSurrogate(joined.charAt(cut - 1))) cut--;   // vekil çifti bölünmez
        return joined.substring(0, cut) + "…";
    }
}
