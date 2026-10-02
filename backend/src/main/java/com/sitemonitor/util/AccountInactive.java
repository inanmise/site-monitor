package com.sitemonitor.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pasif hesap sinyalinin TEK tanımı (2026-10-02, kullanıcı kararı: pasif kullanıcı hiçbir yoldan giriş yapamaz).
 *
 * <p>İki yerde aynı gövde döner ve istemci ({@code api/client.js}) bunu {@code code} alanından tanır:
 * <ul>
 *   <li>Giriş ucu ({@code POST /api/login}) — kimlik bilgisi DOĞRULANDIKTAN sonra hesap pasifse <b>403</b>
 *       (yanlış parolada bugünkü genel 401 değişmez: kullanıcı adı numaralandırması yok).</li>
 *   <li>{@code AuthInterceptor} — canlı oturumu ya da remember-me çerezi olan pasif hesabın her /api isteği <b>401</b>
 *       (istemci "oturum süresi doldu" yönlendirmesi yerine bloklayan "Hesabınız pasife alındı" penceresini açar).</li>
 * </ul>
 * Metin arayüz dilinde ({@link Msg#t}); {@code error_code} giriş ucunun mevcut sözleşmesiyle (TEMP_PASSWORD_EXPIRED,
 * ACTIVE_SESSION_EXISTS) aynı adla ikizlenir.
 */
public final class AccountInactive {

    private AccountInactive() {}

    /** Makine kodu — istemci ve denetim nedeni aynı adı kullanır. */
    public static final String CODE = "ACCOUNT_INACTIVE";

    /** Kullanıcıya gösterilen metin (TR / EN). */
    public static String message() {
        return Msg.t("Hesabınız pasif durumda; giriş yapılamaz. Erişim için yöneticinize başvurun.",
                "Your account is inactive; sign-in is not allowed. Contact your administrator.");
    }

    /** Yanıt gövdesi: {@code {success:false, code, error_code, error}}. */
    public static Map<String, Object> body() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("code", CODE);
        m.put("error_code", CODE);
        m.put("error", message());
        return m;
    }
}
