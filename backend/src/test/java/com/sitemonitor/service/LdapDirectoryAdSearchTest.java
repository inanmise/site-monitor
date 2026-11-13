package com.sitemonitor.service;

import com.sitemonitor.model.LdapSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AD'den kullanıcı ekleme — dizin tarafı (2026-11-13 hata taraması). Gerçek LDAP'a gidilmez: süzgeç saf, erişilemezlik
 * yalnız yerel, hemen kapanan bir soketle sınanır.
 * <ul>
 *   <li>Arama süzgeci kendi sabit kullanıcı koşulunu taşır (posta kişisi / bilgisayar / grup dışı); giriş filtresindeki
 *       {@code {{username}}} şablonu düşse de koşul kalır.</li>
 *   <li>{@link LdapDirectoryService#lookupUser} erişilemezlikte fırlatır; {@link LdapDirectoryService#findUser} (giriş /
 *       eşitleme yolu) eskisi gibi boş döner.</li>
 * </ul>
 */
class LdapDirectoryAdSearchTest {

    private final LdapDirectoryService pure = new LdapDirectoryService(null);

    private static LdapSettings settings(String base, String userAttr) {
        LdapSettings s = new LdapSettings();
        s.setUserSearchFilter(base);
        s.setUserAttribute(userAttr);
        return s;
    }

    @Test
    @DisplayName("Arama süzgeci (AD): temel süzgeç + (sAMAccountName=*) + (sAMAccountType=805306368) + kısmi VEYA")
    void searchFilter_adUserAccountClause() {
        assertThat(pure.buildSearchFilter(settings("(objectclass=person)", "sAMAccountName"), "ayşe"))
                .isEqualTo("(&(objectclass=person)(sAMAccountName=*)(sAMAccountType=805306368)"
                        + "(|(sAMAccountName=*ayşe*)(displayName=*ayşe*)(mail=*ayşe*)(cn=*ayşe*)))");
    }

    @Test
    @DisplayName("Giriş filtresi {{username}} şablonuysa temel süzgeç düşer, sabit kullanıcı koşulu KALIR")
    void searchFilter_templateBaseKeepsUserClause() {
        assertThat(pure.buildSearchFilter(settings("(&(objectClass=user)(sAMAccountName={{username}}))", null), "60146"))
                .isEqualTo("(&(sAMAccountName=*)(sAMAccountType=805306368)"
                        + "(|(sAMAccountName=*60146*)(displayName=*60146*)(mail=*60146*)(cn=*60146*)))");
    }

    @Test
    @DisplayName("AD dışı kullanıcı özniteliği (uid): yalnız (uid=*) — sAMAccountType koşulu eklenmez")
    void searchFilter_nonAdAttribute() {
        assertThat(pure.buildSearchFilter(settings(null, "uid"), "ali"))
                .isEqualTo("(&(uid=*)(|(uid=*ali*)(displayName=*ali*)(mail=*ali*)(cn=*ali*)))");
    }

    @Test
    @DisplayName("Arama değeri kaçışlanır (filtre injection yok)")
    void searchFilter_escapesValue() {
        String f = pure.buildSearchFilter(settings("(objectclass=person)", "sAMAccountName"), "*)(objectClass=*");
        assertThat(f).contains("(sAMAccountName=*\\2a\\29\\28objectClass=\\2a*)").doesNotContain("*)(objectClass=*)");
    }

    @Test
    @DisplayName("Kullanıcı hesabı adı: boş / null / '$' ile biten değil")
    void userAccountName() {
        assertThat(LdapDirectoryService.isUserAccountName("MP60146")).isTrue();
        assertThat(LdapDirectoryService.isUserAccountName(null)).isFalse();
        assertThat(LdapDirectoryService.isUserAccountName("  ")).isFalse();
        assertThat(LdapDirectoryService.isUserAccountName("PC01$")).isFalse();
        assertThat(LdapDirectoryService.isUserAccountName("PC01$ ")).isFalse();
    }

    @Test
    @DisplayName("Normal kullanıcı hesabı: sAMAccountType 805306368; bilgisayar / kişi / grup reddedilir; öznitelik yoksa ad kuralı")
    void userAccount() {
        assertThat(LdapDirectoryService.isUserAccount(Map.of("sAMAccountType", "805306368",
                "objectClass", List.of("top", "person", "organizationalPerson", "user")), "MP1")).isTrue();
        assertThat(LdapDirectoryService.isUserAccount(Map.of("sAMAccountType", List.of("805306369")), "SRV01")).isFalse();
        assertThat(LdapDirectoryService.isUserAccount(Map.of("objectClass", List.of("top", "person", "contact")), "KISI")).isFalse();
        assertThat(LdapDirectoryService.isUserAccount(Map.of("objectClass", "group"), "GRP")).isFalse();
        assertThat(LdapDirectoryService.isUserAccount(Map.of("objectclass", List.of("Computer")), "PC")).isFalse();
        assertThat(LdapDirectoryService.isUserAccount(Map.of(), "MP1")).isTrue();       // AD dışı dizin: yalnız ad kuralı
        assertThat(LdapDirectoryService.isUserAccount(null, "MP1")).isTrue();
        assertThat(LdapDirectoryService.isUserAccount(Map.of(), "PC01$")).isFalse();
    }

    @Test
    @DisplayName("Yapılandırma eksik: lookupUser fırlatır (AD_UNAVAILABLE), findUser eskisi gibi boş")
    void lookupUser_missingConfigThrows() {
        LdapSettingsService ss = mock(LdapSettingsService.class);
        when(ss.getOrDefaults()).thenReturn(new LdapSettings());   // host / base DN yok
        LdapDirectoryService svc = new LdapDirectoryService(ss);
        assertThatThrownBy(() -> svc.lookupUser("MP1"))
                .isInstanceOf(LdapDirectoryService.DirectoryUnavailableException.class);
        assertThat(svc.findUser("MP1")).isEmpty();
    }

    @Test
    @DisplayName("AD erişilemez (yerel soket hemen kapanır): lookupUser ve searchUsers fırlatır; findUser giriş yolu için boş döner")
    void unreachableDirectory() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket ignored = server.accept()) { /* bağlantıyı hemen kapat */ }
                    catch (IOException e) { return; }
                }
            }, "fake-ldap-closer");
            acceptor.setDaemon(true);
            acceptor.start();

            LdapSettings s = new LdapSettings();
            s.setEnabled(true);
            s.setHost(InetAddress.getLoopbackAddress().getHostAddress());
            s.setPort(server.getLocalPort());
            s.setUseLdaps(false);
            s.setStartTls(false);
            s.setBaseDn("dc=example,dc=test");
            LdapSettingsService ss = mock(LdapSettingsService.class);
            when(ss.getOrDefaults()).thenReturn(s);
            LdapDirectoryService svc = new LdapDirectoryService(ss);

            assertTimeoutPreemptively(Duration.ofSeconds(40), () -> {
                assertThatThrownBy(() -> svc.lookupUser("MP1"))
                        .isInstanceOf(LdapDirectoryService.DirectoryUnavailableException.class);
                assertThatThrownBy(() -> svc.searchUsers("ay", 10))
                        .isInstanceOf(LdapDirectoryService.DirectoryUnavailableException.class);
                assertThat(svc.findUser("MP1")).isEmpty();   // giriş / eşitleme davranışı DEĞİŞMEDİ
            });
        }
    }
}
