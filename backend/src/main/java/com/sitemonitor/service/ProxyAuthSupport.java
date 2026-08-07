package com.sitemonitor.service;

import org.slf4j.Logger;

import java.net.Authenticator;
import java.net.PasswordAuthentication;

/**
 * Kimlik doğrulamalı DMZ proxy desteği — {@code java.net.http.HttpClient} tabanlı dış istemciler
 * ({@link RdapDomainClient}, {@link TrWebWhoisClient}) için ortak yardımcı. user doluysa yalnız PROXY
 * isteklerine kimlik üreten bir {@link Authenticator} döner; çağıran bunu SADECE proxied client'ın
 * builder'ına verir. Bugünkü prod proxy anonim olduğundan davranış değişmez; proxy bir gün kimlik
 * istediğinde bu istemciler 407 ile <i>sessizce</i> ölmesin diye eklendi (2026-08 dersinin devamı —
 * CertificateChecker/ChainValidation elle {@code Proxy-Authorization} yazarken bu ikisi hiç yazmıyordu).
 */
final class ProxyAuthSupport {

    private ProxyAuthSupport() {}

    /** JDK, HTTPS CONNECT tünelinde Basic'i varsayılan kapatır (değer yokken "Basic" kabul edilir) —
     *  bu kaldırılmazsa authenticator tanımlı olsa bile tünel 407'de kalır. */
    static final String TUNNELING_PROP = "jdk.http.auth.tunneling.disabledSchemes";

    /**
     * user doluysa PROXY-tipli isteklere {@code user/pass} döndüren Authenticator; değilse {@code null}.
     * user boş + pass dolu tutarsızlığı WARN'lanır (kimlik GÖNDERİLMEZ — sessiz yutulmasın).
     * user dolu + pass boş normaldir (boş parolalı proxy olabilir) — yalnız DEBUG.
     */
    static Authenticator proxyAuthenticatorOrNull(String user, String pass, Logger log, String clientName) {
        boolean hasUser = user != null && !user.isBlank();
        boolean hasPass = pass != null && !pass.isBlank();
        if (!hasUser) {
            if (hasPass) {
                log.warn("{}: HTTP_PROXY_PASS dolu ama HTTP_PROXY_USER boş — proxy kimliği GÖNDERİLMEYECEK "
                        + "(tutarsız yapılandırma; ikisini birlikte doldurun).", clientName);
            }
            return null;
        }
        if (!hasPass) log.debug("{}: proxy kimliği boş parolayla kuruldu (HTTP_PROXY_PASS boş).", clientName);
        enableBasicForConnectTunnel(log, clientName);
        final String u = user;
        final char[] pw = (pass == null ? "" : pass).toCharArray();
        return new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                return getRequestorType() == RequestorType.PROXY
                        ? new PasswordAuthentication(u, pw.clone()) : null;
            }
        };
    }

    /** Property'yi yalnız operatör HİÇ set etmemişse boşaltır — bilinçli bir kısıtlamayı ezmeyiz. */
    private static synchronized void enableBasicForConnectTunnel(Logger log, String clientName) {
        if (System.getProperty(TUNNELING_PROP) != null) return;
        System.setProperty(TUNNELING_PROP, "");
        log.info("{}: kimlik doğrulamalı proxy için {}=\"\" ayarlandı (JDK varsayılanı CONNECT tünelinde Basic'i kapatır).",
                clientName, TUNNELING_PROP);
    }
}
