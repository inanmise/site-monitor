package com.sitemonitor.controller;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Webhook başlıklarında SIR koruması — maskeli değer "değiştirmedim" demektir.
 *
 * <p>Kusur (5. tur denetimi, bulgu 4): sunucu sır başlıklarını {@code *****} olarak döndürüyor.
 * Kullanıcı kayıtlı bir {@code Authorization} başlığının "sır" kutusunu <b>kaldırıp</b>
 * kaydettiğinde istemci {@code {value:"*****", secret:false}} gönderiyor; kod {@code else}
 * dalına düşüp {@code stored = "*****"} yazıyordu. Şifreli token <b>geri alınamaz biçimde
 * siliniyor</b>, sonraki her push {@code Authorization: *****} ile gidip FAILED oluyordu.
 *
 * <p>Karar bayraktan BAĞIMSIZ olmalı: maskeli değer geldiyse kayıtlı değer korunur.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserPushHeaderSecretTest {

    @Mock AppSettingsService appSettings;
    @Mock SecretCipher secretCipher;
    @Mock com.sitemonitor.service.UserPushService userPushService;
    @Mock com.sitemonitor.repository.UserPushDeliveryRepository deliveryRepo;
    @Mock com.sitemonitor.repository.UserPushScopeRepository scopeRepo;
    @Mock com.sitemonitor.service.AuditService auditService;

    private UserPushController controller;

    /** Kayıtlı hâl: bir sır (şifreli) + bir düz başlık. */
    private static final String STORED =
            "[{\"name\":\"Authorization\",\"value\":\"ENC(gercek-token)\",\"secret\":true},"
          + " {\"name\":\"X-Env\",\"value\":\"prod\",\"secret\":false}]";

    @BeforeEach
    void setUp() {
        controller = new UserPushController(appSettings, userPushService, deliveryRepo,
                scopeRepo, secretCipher, auditService);
        when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getString("site.monitor.userpush.headers", "[]")).thenReturn(STORED);
        when(secretCipher.encrypt(anyString())).thenAnswer(i -> "ENC(" + i.getArgument(0) + ")");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> encrypt(Object incoming) throws Exception {
        String json = (String) ReflectionTestUtils.invokeMethod(controller, "encryptHeaders", incoming);
        return (List<Map<String, Object>>) new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, List.class);
    }

    private static Map<String, Object> row(String name, String value, boolean secret) {
        return Map.of("name", name, "value", value, "secret", secret);
    }

    @Test
    @DisplayName("'Sır' kutusu KALDIRILIRSA kayıtlı token korunur — '*****' asla kalıcılaşmaz")
    void unchekingSecret_doesNotDestroyToken() throws Exception {
        var out = encrypt(List.of(row("Authorization", "*****", false)));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).get("value"))
                .as("maskeli değer düz metin olarak kaydedilirse token geri alınamaz biçimde silinir")
                .isEqualTo("ENC(gercek-token)");
        assertThat(out.get(0).get("secret"))
                .as("değer şifreli kaldıysa sır bayrağı da korunmalı — aksi halde sır açığa çıkardı")
                .isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("Maskeli değer 'sır' bayrağı AÇIKKEN de korunur (mevcut davranış)")
    void maskedWithSecretOn_keepsToken() throws Exception {
        var out = encrypt(List.of(row("Authorization", "*****", true)));
        assertThat(out.get(0).get("value")).isEqualTo("ENC(gercek-token)");
    }

    @Test
    @DisplayName("GERÇEKTEN yeni bir değer girilirse şifrelenerek yazılır")
    void newSecretValue_isEncrypted() throws Exception {
        var out = encrypt(List.of(row("Authorization", "yeni-token", true)));
        assertThat(out.get(0).get("value")).isEqualTo("ENC(yeni-token)");
    }

    @Test
    @DisplayName("Sır OLMAYAN başlık düz metin olarak yazılmaya devam eder")
    void plainHeader_staysPlain() throws Exception {
        var out = encrypt(List.of(row("X-Env", "test", false)));
        assertThat(out.get(0).get("value")).isEqualTo("test");
        assertThat(out.get(0).get("secret")).isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("Sır bayrağı AÇIK ama değer BOŞ ise kayıtlı değer korunur (mevcut davranış)")
    void blankSecret_keepsStored() throws Exception {
        var out = encrypt(List.of(row("Authorization", "", true)));
        assertThat(out.get(0).get("value")).isEqualTo("ENC(gercek-token)");
    }
}
