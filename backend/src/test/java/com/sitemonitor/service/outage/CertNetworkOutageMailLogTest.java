package com.sitemonitor.service.outage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Yönetici ağ kesintisi postalarının izi (2026-10-21, çok pod — inceleme S-4 / F8): {@link CertNetworkOutageService} her
 * posta kararını {@code notification_logs}'a postanın KONUSUYLA yazar; e-posta servisi 421 kuyruğunun sonucunu satıra konu +
 * alıcıyla geri yazar ({@code EmailNotificationService.retryRowFor}). Konular iki yerde aynı kalmalı — biri değişirse geri
 * yazım satırı bulamaz, kuyruk sonucu iz bırakmaz.
 */
class CertNetworkOutageMailLogTest {

    @Test
    @DisplayName("İz satırının konusu e-posta servisinin gönderdiği konuyla birebir aynı (açılış + çözüm)")
    void subjectsMatchEmailService() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/sitemonitor/service/EmailNotificationService.java"),
                StandardCharsets.UTF_8);
        assertThat(src).contains("\"" + CertNetworkOutageService.ALERT_SUBJECT + "\"");
        assertThat(src).contains("\"" + CertNetworkOutageService.RESOLVED_SUBJECT + "\"");
    }

    @Test
    @DisplayName("Kesin sonuç kuralı: SENT / SKIPPED kesin; FAILED değil; QUEUED_RETRY yalnız günlük satırıyla (onRun içinde) kesin")
    void finalStatuses() {
        assertThat(CertNetworkOutageService.isFinal("SENT")).isTrue();
        assertThat(CertNetworkOutageService.isFinal("SKIPPED_DISABLED")).isTrue();
        assertThat(CertNetworkOutageService.isFinal(CertNetworkOutageService.MAIL_NO_ADDRESS)).isTrue();
        assertThat(CertNetworkOutageService.isFinal("QUEUED_RETRY: 421")).isTrue();
        assertThat(CertNetworkOutageService.isFinal("FAILED: smtp")).isFalse();
        assertThat(CertNetworkOutageService.isFinal(null)).isFalse();
    }
}
