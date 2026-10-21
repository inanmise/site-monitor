package com.sitemonitor.service;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.repository.AppSettingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kullanıcı adı ön eki ayarlarının kayıt kuralları (2026-10-21) — hangi denetleyiciden gelirse gelsin
 * {@link AppSettingsService#save}: biçim (ön ek / muaf ad), açıkken boş liste yasak, kaydeden yönetici KENDİNİ
 * kilitleyemez ({@code LOCKOUT_SELF}), anahtarlar GLOBAL_ONLY (müdür 403). Hata alan adıyla döner.
 */
@DataJpaTest
@Import(AppSettingsService.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "site.monitor.username=admin",
        "site.monitor.executor.core-size=20",
        "site.monitor.executor.max-size=50",
        "site.monitor.executor.queue-capacity=5000",
})
class AppSettingsUsernamePrefixTest {

    static final String EN = "site.monitor.login.username-prefix.enabled";
    static final String AL = "site.monitor.login.username-prefix.allowed";
    static final String EX = "site.monitor.login.username-prefix.exempt";

    @Autowired AppSettingsService service;
    @Autowired AppSettingRepository repo;

    /** Servisin bellek önbelleği bağlam boyunca yaşar (DB geri alınsa da) — her test kapalı kuralla başlar. */
    @org.junit.jupiter.api.BeforeEach
    void clean() {
        RequestContextHolder.resetRequestAttributes();
        service.save(values(EN, "", AL, "", EX, "", "site.monitor.login.otp.max-attempts", ""), "test");
    }

    @AfterEach
    void reset() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void bindSession(String username, boolean scoped) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", username);
        s.setAttribute("systemRole", "ADMIN");
        if (scoped) s.setAttribute("viewTeamIds", new java.util.ArrayList<>(List.of(3L)));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setSession(s);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    /** Kayıtlı değer (temizlik satırı değeri null bırakır). */
    private String storedEnabled() {
        return repo.findBySettingKey(EN).map(com.sitemonitor.model.AppSetting::getValue).orElse(null);
    }

    private static Map<String, Object> values(Object... kv) {
        Map<String, Object> inner = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) inner.put((String) kv[i], kv[i + 1]);
        return Map.of("values", inner);
    }

    @Test
    @DisplayName("N… yöneticisi kuralı N ile açar; değerler okunur")
    void saveAllowed() {
        bindSession("N100", false);
        service.save(values(EN, "true", AL, "N", EX, "OPS"), "N100");
        assertThat(service.getBoolean(EN, false)).isTrue();
        assertThat(service.getString(AL, "")).isEqualTo("N");
    }

    @Test
    @DisplayName("açıkken boş liste → 400 alan username_prefix_allowed (VALIDATION_FAILED); satır yazılmaz")
    void emptyListRejected() {
        bindSession("N100", false);
        assertThatThrownBy(() -> service.save(values(EN, "true"), "N100"))
                .isInstanceOfSatisfying(GlobalExceptionHandler.FieldValidationException.class, e -> {
                    assertThat(e.getFields()).containsKey("username_prefix_allowed");
                    assertThat(e.getCode()).isNull();
                });
        assertThat(storedEnabled()).as("satır yazılmaz").isNull();
    }

    @Test
    @DisplayName("kaydeden MP… yöneticisi kendini kilitleyemez → LOCKOUT_SELF; muaf listeye eklenince kaydedilir")
    void lockoutSelf() {
        bindSession("MP200", false);
        assertThatThrownBy(() -> service.save(values(EN, "true", AL, "N"), "MP200"))
                .isInstanceOfSatisfying(GlobalExceptionHandler.FieldValidationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("LOCKOUT_SELF");
                    assertThat(e.getFields()).containsKey("username_prefix_allowed");
                    assertThat(e.getMessage()).contains("MP200");
                });
        assertThat(storedEnabled()).as("satır yazılmaz").isNull();
        service.save(values(EN, "true", AL, "N", EX, "MP200"), "MP200");
        assertThat(service.getBoolean(EN, false)).isTrue();
    }

    @Test
    @DisplayName("bootstrap yönetici her zaman muaf — kendi adı N değilken de açabilir")
    void bootstrapAdminMaySave() {
        bindSession("admin", false);
        service.save(values(EN, "true", AL, "N"), "admin");
        assertThat(service.getBoolean(EN, false)).isTrue();
    }

    @Test
    @DisplayName("geçersiz ön ek / muaf ad biçimi → 400 alan adıyla (hangi uçtan yazılırsa yazılsın)")
    void formatValidated() {
        bindSession("admin", false);
        assertThatThrownBy(() -> service.save(values(AL, "N, Ç1"), "admin"))
                .isInstanceOfSatisfying(GlobalExceptionHandler.FieldValidationException.class,
                        e -> assertThat(e.getFields()).containsKey("username_prefix_allowed"));
        assertThatThrownBy(() -> service.save(values(EX, "iki kelime"), "admin"))
                .isInstanceOfSatisfying(GlobalExceptionHandler.FieldValidationException.class,
                        e -> assertThat(e.getFields()).containsKey("username_prefix_exempt"));
    }

    @Test
    @DisplayName("kural açıkken listeyi daraltan sonraki kayıt da denetlenir (yalnız ön eki değiştirmek kendini kilitlemesin)")
    void laterNarrowingChecked() {
        bindSession("N100", false);
        service.save(values(EN, "true", AL, "N"), "N100");
        assertThatThrownBy(() -> service.save(values(AL, "MP"), "N100"))
                .isInstanceOfSatisfying(GlobalExceptionHandler.FieldValidationException.class,
                        e -> assertThat(e.getCode()).isEqualTo("LOCKOUT_SELF"));
        assertThat(service.getString(AL, "")).isEqualTo("N");
    }

    @Test
    @DisplayName("ilgisiz ayar kaydı ön ek kuralını denetlemez (depodaki değer başka kaydı kilitlemesin)")
    void unrelatedSaveNotChecked() {
        bindSession("N100", false);
        service.save(values(EN, "true", AL, "N"), "N100");
        bindSession("MP200", false);   // kural onu reddederdi — ama ön ek anahtarına dokunmuyor
        service.save(values("site.monitor.login.otp.max-attempts", "4"), "MP200");
        assertThat(service.getInt("site.monitor.login.otp.max-attempts", 0)).isEqualTo(4);
    }

    @Test
    @DisplayName("kapsamlı müdür (ADMIN + viewTeamIds) GLOBAL_ONLY ön ek anahtarlarını yazamaz → 403")
    void scopedAdminRejected() {
        bindSession("N300", true);
        assertThatThrownBy(() -> service.save(values(EN, "true", AL, "N"), "N300"))
                .isInstanceOf(SecurityException.class);
        assertThat(storedEnabled()).as("satır yazılmaz").isNull();
    }
}
