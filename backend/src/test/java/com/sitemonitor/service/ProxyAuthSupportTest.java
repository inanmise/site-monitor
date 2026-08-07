package com.sitemonitor.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.Authenticator;
import java.net.Authenticator.RequestorType;
import java.net.PasswordAuthentication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kimlik doğrulamalı proxy yardımcısı (2026-08 prod log analizi bulgusu): RDAP/.tr web-whois istemcileri
 * auth'lu proxy'de 407 ile SESSİZCE ölmesin. Tunneling property testleri global JVM state'e dokunduğu için
 * her test öncesi/sonrası kaydedilip geri yüklenir.
 */
class ProxyAuthSupportTest {

    private static final String PROP = ProxyAuthSupport.TUNNELING_PROP;

    private String origProp;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        origProp = System.getProperty(PROP);
        System.clearProperty(PROP);
        logger = (Logger) LoggerFactory.getLogger("test.ProxyAuthSupport");
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        if (origProp == null) System.clearProperty(PROP); else System.setProperty(PROP, origProp);
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("user+pass dolu → yalnız PROXY isteklerine kimlik döner; CONNECT-tünel Basic engeli kaldırılır")
    void userAndPass_proxyOnlyCredentials_andTunnelingEnabled() {
        Authenticator auth = ProxyAuthSupport.proxyAuthenticatorOrNull("svc-mon", "pw-123", logger, "TEST");
        assertThat(auth).isNotNull();

        PasswordAuthentication proxy = Authenticator.requestPasswordAuthentication(
                auth, "proxy.local", null, 8080, "http", "auth", "basic", null, RequestorType.PROXY);
        assertThat(proxy).isNotNull();
        assertThat(proxy.getUserName()).isEqualTo("svc-mon");
        assertThat(new String(proxy.getPassword())).isEqualTo("pw-123");

        // Hedef sunucu (SERVER) kimlik İSTEYEMEZ — proxy kimliği origin'e sızmaz.
        PasswordAuthentication server = Authenticator.requestPasswordAuthentication(
                auth, "evil.example", null, 443, "https", "auth", "basic", null, RequestorType.SERVER);
        assertThat(server).isNull();

        // JDK'nın CONNECT tünelinde Basic'i kapatan varsayılanı kaldırıldı (property önceden ayarsızdı).
        assertThat(System.getProperty(PROP)).isEmpty();
    }

    @Test
    @DisplayName("user boş + pass dolu → null + WARN (sessiz yutulmaz); tunneling property'ye dokunulmaz")
    void blankUserWithPass_returnsNullAndWarns() {
        Authenticator auth = ProxyAuthSupport.proxyAuthenticatorOrNull("", "pw-123", logger, "TEST");
        assertThat(auth).isNull();
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(e.getFormattedMessage()).contains("HTTP_PROXY_USER boş");
        });
        assertThat(System.getProperty(PROP)).isNull();
    }

    @Test
    @DisplayName("ikisi de boş/null → null, uyarı yok (anonim proxy — bugünkü prod)")
    void bothBlank_returnsNullSilently() {
        assertThat(ProxyAuthSupport.proxyAuthenticatorOrNull(null, null, logger, "TEST")).isNull();
        assertThat(ProxyAuthSupport.proxyAuthenticatorOrNull("", "", logger, "TEST")).isNull();
        assertThat(appender.list).noneMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN);
        assertThat(System.getProperty(PROP)).isNull();
    }

    @Test
    @DisplayName("user dolu + pass boş → boş parolalı kimlik (geçerli); operatörün set ettiği property EZİLMEZ")
    void userWithoutPass_emptyPassword_andOperatorPropertyRespected() {
        System.setProperty(PROP, "Basic");   // operatör bilinçli kısıtlamış
        Authenticator auth = ProxyAuthSupport.proxyAuthenticatorOrNull("svc-mon", "", logger, "TEST");
        assertThat(auth).isNotNull();
        PasswordAuthentication pa = Authenticator.requestPasswordAuthentication(
                auth, "proxy.local", null, 8080, "http", "auth", "basic", null, RequestorType.PROXY);
        assertThat(pa.getUserName()).isEqualTo("svc-mon");
        assertThat(pa.getPassword()).isEmpty();
        assertThat(System.getProperty(PROP)).isEqualTo("Basic");   // dokunulmadı
    }
}
