package com.sitemonitor.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ALERTS-12 / DATA-2 (2026-10-22): {@code user_push_deliveries.dedupe_key} VARCHAR(60). TLS notu düşüşü ve zayıf algoritma
 * "takıma bildir" anahtarları 60'ı aşınca INSERT 22001 ile düşüyor, hata "aynı satır zaten var" sanılıp push SESSİZCE
 * kayboluyordu. GERÇEK servis + GERÇEK depo (H2 VARCHAR uzunluğunu PostgreSQL gibi uygular): satır yazılır, ikinci çağrı
 * aynı (sığdırılmış) anahtarı bulur, uzun görünen ad satırı düşürmez, tekil olmayan ihlal WARN olur.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserPushDedupeKeyFitTest {

    @Autowired UserPushDeliveryRepository repo;

    private UserPushService service;
    private UserPushRecipientResolver resolver;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    /** Gerçek ürün biçimi: 12 TLS notu düşüşü, beş haneli satır kimlikleri. */
    private static final String TLS_KEY = "tls-drop:7:" + String.join(",",
            "10001", "10002", "10003", "10004", "10005", "10006", "10007", "10008", "10009", "10010", "10011", "10012");
    /** Zayıf algoritma raporu: 40+ karakterli alan adı. */
    private static final String WEAK_KEY = "WEAK_ALGO:mobil-sube-api.bireysel.ornekbanka.com.tr:2026-10-22";

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getBoolean(anyString(), any(Boolean.class))).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getBoolean(eq("site.monitor.userpush.enabled"), any(Boolean.class))).thenReturn(true);
        when(settings.getString(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getInt(anyString(), any(Integer.class))).thenAnswer(inv -> inv.getArgument(1));
        resolver = mock(UserPushRecipientResolver.class);
        service = new UserPushService(settings, repo, null, resolver, null, null, null, null);
        service.beginShutdown();   // çıkış kutusu turu koşmasın — satırların durumu testin konusu
        logger = (Logger) LoggerFactory.getLogger(UserPushService.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    @Test
    @DisplayName("fitDedupeKey: 60'a sığan anahtar AYNEN; uzun anahtar ≤ 60, belirleyici, idempotent, önek korunur, farklı anahtar farklı sonuç")
    void fitRules() {
        assertThat(UserPushDelivery.fitDedupeKey(null)).isNull();
        assertThat(UserPushDelivery.fitDedupeKey("OPEN")).isEqualTo("OPEN");
        String sixty = "x".repeat(60);
        assertThat(UserPushDelivery.fitDedupeKey(sixty)).isSameAs(sixty);
        assertThat(TLS_KEY.length()).as("gerçek anahtar kolonu aşar").isGreaterThan(60);
        String fitted = UserPushDelivery.fitDedupeKey(TLS_KEY);
        assertThat(fitted).hasSizeLessThanOrEqualTo(UserPushDelivery.DEDUPE_KEY_MAX).startsWith("tls-drop:7:10001,");
        assertThat(UserPushDelivery.fitDedupeKey(TLS_KEY)).isEqualTo(fitted);
        assertThat(UserPushDelivery.fitDedupeKey(fitted)).as("idempotent").isEqualTo(fitted);
        assertThat(UserPushDelivery.fitDedupeKey(TLS_KEY + ",10013")).as("farklı düşüş kümesi farklı anahtar").isNotEqualTo(fitted);
        assertThat(UserPushDelivery.fitDedupeKey(WEAK_KEY)).isNotEqualTo(UserPushDelivery.fitDedupeKey(WEAK_KEY.replace("22", "23")));
        // vekil çift bölünmez
        String emoji = "a".repeat(42) + "😀" + "b".repeat(30);
        String e = UserPushDelivery.fitDedupeKey(emoji);
        assertThat(e).hasSizeLessThanOrEqualTo(60);
        assertThat(Character.isHighSurrogate(e.charAt(e.indexOf('#') - 1))).isFalse();
    }

    @Test
    @DisplayName("takım bildirimi (TLS düşüşü, 12 satır): uzun anahtarla PENDING satır YAZILIR; aynı çağrı ikinci kez satır yazmaz")
    void teamNoticeWithLongKeyIsQueuedOnce() {
        when(resolver.resolve(eq(7L), anyString())).thenReturn(List.of(
                new UserPushRecipientResolver.Recipient("N10001", "Ayşe Yılmaz", null)));

        Map<String, Object> first = service.enqueueTeamNoticeLocalized(7L, "TLS_GRADE_DROP", "CRITICAL", "CERT", "Ekip",
                new UserPushService.LocalizedText("12 sertifikanın TLS notu düştü", null), TLS_KEY, Set.of());
        Map<String, Object> second = service.enqueueTeamNoticeLocalized(7L, "TLS_GRADE_DROP", "CRITICAL", "CERT", "Ekip",
                new UserPushService.LocalizedText("12 sertifikanın TLS notu düştü", null), TLS_KEY, Set.of());

        assertThat(first.get("queued")).isEqualTo(1);
        assertThat(second.get("queued")).isEqualTo(0);
        assertThat(repo.findAll()).singleElement().satisfies(d -> {
            assertThat(d.getStatus()).isEqualTo("PENDING");
            assertThat(d.getDedupeKey()).isEqualTo(UserPushDelivery.fitDedupeKey(TLS_KEY));
            assertThat(repo.existsByDedupeKeyAndUsername(UserPushDelivery.fitDedupeKey(TLS_KEY), "N10001")).isTrue();
        });
    }

    @Test
    @DisplayName("doğrudan bildirim (zayıf algoritma, uzun alan adı) + uzun görünen ad: satır yazılır, ad 200'e sığdırılır")
    void directNoticeWithLongKeyAndLongNameIsQueued() {
        String longName = "Ad Soyad (Dijital Kanallar Direktörlüğü / Kıdemli Uygulama Geliştirme Uzmanı) ".repeat(3).trim();
        assertThat(longName.length()).isGreaterThan(200);
        Map<String, Object> out = service.enqueueDirectLocalized(
                List.of(new UserPushService.DirectRecipient("N20002", longName, false)), 7L, "WEAK_ALGO", "WARNING",
                "CERTIFICATE", "mobil-sube-api.bireysel.ornekbanka.com.tr", UserPushService.LocalizedText.of("Zayıf algoritma"),
                WEAK_KEY);
        Map<String, Object> again = service.enqueueDirectLocalized(
                List.of(new UserPushService.DirectRecipient("N20002", longName, false)), 7L, "WEAK_ALGO", "WARNING",
                "CERTIFICATE", "mobil-sube-api.bireysel.ornekbanka.com.tr", UserPushService.LocalizedText.of("Zayıf algoritma"),
                WEAK_KEY);

        assertThat(out.get("queued")).isEqualTo(1);
        assertThat(again.get("queued")).isEqualTo(0);
        assertThat(repo.findAll()).singleElement().satisfies(d -> {
            assertThat(d.getDedupeKey()).hasSizeLessThanOrEqualTo(60);
            assertThat(d.getDisplayName()).hasSizeLessThanOrEqualTo(200).endsWith("…");
        });
    }

    @Test
    @DisplayName("satır yazılamadı: tekil ihlal (23505) = 'zaten var' (WARN yok); başka bütünlük hatası (22001) WARN + kolon adı, sicil günlüğe yazılmaz")
    void nonUniqueViolationIsWarned() {
        UserPushDelivery d = new UserPushDelivery();
        d.setDedupeKey("OPEN");
        d.setTrigger("OPEN");
        d.setUsername("N30003");
        d.setStatus("PENDING_" + "X".repeat(40));   // kolon 40 — sığdırılmaz (kod)

        service.logRowWriteFailure("test", d, new org.springframework.dao.DataIntegrityViolationException("dup",
                new SQLException("unique", "23505")));
        assertThat(logs.list).noneMatch(ev -> ev.getLevel() == Level.WARN);

        service.logRowWriteFailure("test", d, new org.springframework.dao.DataIntegrityViolationException("too long",
                new SQLException("value too long for type character varying(40)", "22001")));
        assertThat(logs.list).filteredOn(ev -> ev.getLevel() == Level.WARN).singleElement().satisfies(ev -> {
            assertThat(ev.getFormattedMessage()).contains("YAZILAMADI").contains("status(").doesNotContain("N30003");
        });
        assertThat(UserPushService.isUniqueViolation(new org.springframework.dao.DuplicateKeyException("x"))).isTrue();
        assertThat(UserPushService.isUniqueViolation(new RuntimeException("x"))).isFalse();
    }

    @Test
    @DisplayName("L-P1: TLS notu düşüşünün 'CERT' türü kişisel 'cert' ailesine eşlenir (sertifika push'unu kapatan kişi bunu da almaz)")
    void certNoticeTypeMapsToCertFamily() {
        assertThat(UserPushService.noticeFamilies("CERT")).containsExactly("cert");
        assertThat(UserPushService.noticeFamilies("CERTIFICATE")).containsExactly("cert");
    }
}
