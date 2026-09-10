package com.sitemonitor.service;

import com.sitemonitor.util.Msg;
import com.sitemonitor.model.LdapSettings;
import com.sitemonitor.model.SmtpSettings;
import com.sitemonitor.repository.LdapSettingsRepository;
import com.sitemonitor.repository.SmtpSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anahtar kurtarma/doğrulama aracı (yalnız admin). SITE_MONITOR_SECRET_KEY ile şifrelenmiş
 * alanları (SMTP/LDAP parolaları) VERİLEN aday anahtarla çözer ve sonucu döner. Yapılandırılmış
 * anahtarı kullanmaz; doğru anahtar verildiğinde plaintext'i, yanlışta "başarısız" döner.
 */
@Service
@RequiredArgsConstructor
public class SecretToolsService {

    private final SmtpSettingsRepository smtpRepo;
    private final LdapSettingsRepository ldapRepo;
    private final SecretCipher cipher;

    /** Gömülü DEV varsayılan anahtarı (gizli değil) — admin testi için. */
    public String devDefaultKey() {
        return cipher.devDefaultKey();
    }

    /** Gerçek bir SITE_MONITOR_SECRET_KEY ayarlı mı. */
    public boolean isSecretKeyConfigured() {
        return cipher.isKeyConfigured();
    }

    public List<Map<String, Object>> decryptWithKey(String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        smtpRepo.findById(SmtpSettings.SINGLETON_ID).ifPresent(s ->
                out.add(row(Msg.t("SMTP Parolası", "SMTP password"), "smtp_settings.password_enc", s.getPasswordEnc(), key)));
        ldapRepo.findById(LdapSettings.SINGLETON_ID).ifPresent(l ->
                out.add(row(Msg.t("LDAP Bind Parolası", "LDAP bind password"), "ldap_settings.bind_password_enc", l.getBindPasswordEnc(), key)));
        return out;
    }

    private Map<String, Object> row(String label, String column, String enc, String key) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("column", column);
        boolean present = enc != null && !enc.isBlank();
        m.put("present", present);
        String dec = present ? cipher.decryptWith(enc, key) : null;
        m.put("ok", dec != null);
        m.put("value", dec); // çözülen plaintext (yalnız doğru anahtarda) veya null
        return m;
    }
}
