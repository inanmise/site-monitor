package com.sitemonitor.service;

import com.sitemonitor.model.LdapSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.PartialResultException;
import javax.naming.SizeLimitExceededException;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.Control;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.PagedResultsControl;
import javax.naming.ldap.PagedResultsResponseControl;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LDAP rol eşlemesi önizlemesinin dizin okuması (2026-10-11): grup kuralı (tam DN ya da CN) → DOĞRUDAN üyeler.
 * Bağlam taklit edilir; sayfalama çerezi, CN → DN çözümü, büyük harfe çevirme, AD referansı (PartialResult) ve
 * sunucu tavanı (SizeLimitExceeded → truncated) gerçek JNDI sınıflarıyla sınanır.
 */
class LdapGroupMembersTest {

    private static final String BASE = "DC=ornek,DC=local";
    private static final String GROUP_DN = "CN=Uygulama Yoneticileri,OU=Gruplar,DC=ornek,DC=local";

    private static LdapSettings settings() {
        LdapSettings s = new LdapSettings();
        s.setHost("127.0.0.1");
        s.setBaseDn(BASE);
        return s;
    }

    private static LdapDirectoryService service(LdapSettings s) {
        LdapSettingsService ss = mock(LdapSettingsService.class);
        when(ss.getOrDefaults()).thenReturn(s);
        return new LdapDirectoryService(ss);
    }

    @Test
    @DisplayName("yapılandırma eksikse anlaşılır hata; kural yoksa dizine hiç bağlanmadan boş sonuç")
    void validatesBeforeConnecting() {
        LdapSettings noHost = settings();
        noHost.setHost(" ");
        assertThatThrownBy(() -> service(noHost).membersOfGroups(List.of("x"))).isInstanceOf(IllegalArgumentException.class);
        LdapSettings noBase = settings();
        noBase.setBaseDn(null);
        assertThatThrownBy(() -> service(noBase).membersOfGroups(List.of("x"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(service(settings()).membersOfGroups(List.of())).isEmpty();
        assertThat(service(settings()).membersOfGroups(null)).isEmpty();
    }

    @Test
    @DisplayName("dizine ulaşılamazsa IllegalStateException (kök neden metniyle)")
    void unreachableDirectory() {
        LdapSettings s = settings();
        s.setUseLdaps(false);
        s.setPort(1);   // yerel kapalı kapı: bağlantı anında reddedilir
        assertThatThrownBy(() -> service(s).membersOfGroups(List.of(GROUP_DN))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("tam DN kuralı: sayfalar çerezle izlenir, adlar büyük harf ve tekil, boş öznitelik atlanır, istek denetimi temizlenir")
    void dnSpecPagesThroughMembers() throws Exception {
        LdapContext ctx = mock(LdapContext.class);
        String filter = "(memberOf=" + GROUP_DN + ")";
        when(ctx.search(eq(BASE), eq(filter), any(SearchControls.class)))
                .thenReturn(results(user("sAMAccountName", "ali.veli"), user("sAMAccountName", " "), user("sAMAccountName", "Ayse.Kaya")))
                .thenReturn(results(user("sAMAccountName", "ALI.VELI"), user("sAMAccountName", "can.demir")));
        when(ctx.getResponseControls())
                .thenReturn(new Control[]{pagedResponse(new byte[]{1, 2})})
                .thenReturn(new Control[]{pagedResponse(new byte[0])});

        var out = service(settings()).membersOn(ctx, settings(), List.of(" " + GROUP_DN + " "));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).spec()).isEqualTo(GROUP_DN);
        assertThat(out.get(0).groupDns()).containsExactly(GROUP_DN);
        assertThat(out.get(0).usernames()).containsExactly("ALI.VELI", "AYSE.KAYA", "CAN.DEMIR");
        assertThat(out.get(0).truncated()).isFalse();
        verify(ctx, times(2)).search(eq(BASE), eq(filter), any(SearchControls.class));
        verify(ctx, times(2)).setRequestControls(any(Control[].class));
        verify(ctx, atLeastOnce()).setRequestControls(isNull());
    }

    @Test
    @DisplayName("grup adı (CN, \"cn=\" önekiyle de): grup arama tabanından BAĞIMSIZ tüm dizinde DN'lere çözülür (girişle aynı), üyeler birleşir")
    void cnSpecResolvesGroups() throws Exception {
        LdapSettings s = settings();
        s.setGroupSearchBase("OU=Gruplar," + BASE);
        s.setUserAttribute("uid");
        LdapContext ctx = mock(LdapContext.class);
        String dn1 = "CN=Operasyon,OU=Gruplar," + BASE;
        String dn2 = "CN=Operasyon,OU=Eski," + BASE;
        when(ctx.search(eq(BASE), eq("(&(objectClass=group)(cn=Operasyon \\28NOC\\29))"), any(SearchControls.class)))
                .thenReturn(results(group(dn1), group(dn2)));
        when(ctx.search(eq(BASE), eq("(memberOf=" + dn1 + ")"), any(SearchControls.class))).thenReturn(results(user("uid", "a")));
        when(ctx.search(eq(BASE), eq("(memberOf=" + dn2 + ")"), any(SearchControls.class))).thenReturn(results(user("uid", "b"), user("cn", "yok")));

        var out = service(s).membersOn(ctx, s, List.of("cn=Operasyon (NOC)", ""));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).groupDns()).containsExactly(dn1, dn2);
        assertThat(out.get(0).usernames()).containsExactly("A", "B");
        assertThat(out.get(1).groupDns()).isEmpty();
        assertThat(out.get(1).usernames()).isEmpty();
    }

    @Test
    @DisplayName("2026-10-12: base DN kullanıcı OU'su olsa da CN grubu dizin KÖKÜNDE (DC= son eki) aranır; üyeler kullanıcı tabanında")
    void cnSearchedAtNamingContext() throws Exception {
        assertThat(LdapDirectoryService.namingContextOf("OU=Users,OU=Merkez,DC=ornek,DC=local")).isEqualTo("DC=ornek,DC=local");
        assertThat(LdapDirectoryService.namingContextOf("dc=ornek, dc=local")).isEqualTo("dc=ornek,dc=local");
        assertThat(LdapDirectoryService.namingContextOf("O=Kurum")).isEqualTo("O=Kurum");
        LdapSettings s = settings();
        s.setBaseDn("OU=Users," + BASE);
        s.setGroupSearchBase("OU=ScrumGroups," + BASE);
        LdapContext ctx = mock(LdapContext.class);
        String dn = "CN=CertAdmins,OU=Security," + BASE;
        when(ctx.search(eq(BASE), eq("(&(objectClass=group)(cn=CertAdmins))"), any(SearchControls.class))).thenReturn(results(group(dn)));
        when(ctx.search(eq("OU=Users," + BASE), eq("(memberOf=" + dn + ")"), any(SearchControls.class)))
                .thenReturn(results(user("sAMAccountName", "ali")));
        var out = service(s).membersOn(ctx, s, List.of("CertAdmins"));
        assertThat(out.get(0).groupDns()).containsExactly(dn);
        assertThat(out.get(0).usernames()).containsExactly("ALI");
    }

    @Test
    @DisplayName("AD referansı bulunanları korur; sunucu tavanı ya da CN aramasında tavan → kısmi sonuç işaretlenir")
    void partialAndSizeLimit() throws Exception {
        LdapContext ctx = mock(LdapContext.class);
        when(ctx.search(eq(BASE), eq("(memberOf=" + GROUP_DN + ")"), any(SearchControls.class)))
                .thenReturn(results(user("sAMAccountName", "x"), new PartialResultException("referans"), user("sAMAccountName", "okunmaz")));
        String other = "CN=Buyuk,OU=Gruplar," + BASE;
        when(ctx.search(eq(BASE), eq("(memberOf=" + other + ")"), any(SearchControls.class)))
                .thenThrow(new SizeLimitExceededException("tavan"));
        when(ctx.search(eq(BASE), eq("(&(objectClass=group)(cn=Kayip))"), any(SearchControls.class)))
                .thenReturn(results(new SizeLimitExceededException("tavan")));

        var out = service(settings()).membersOn(ctx, settings(), List.of(GROUP_DN, other, "Kayip"));

        assertThat(out.get(0).usernames()).containsExactly("X");
        assertThat(out.get(0).truncated()).isFalse();
        assertThat(out.get(1).truncated()).isTrue();
        assertThat(out.get(2).groupDns()).isEmpty();
        // iki grup okuması (DN + tavanlı DN); çözülemeyen CN üye araması yapmaz — her okuma sonunda istek denetimi temizlenir
        verify(ctx, times(2)).setRequestControls(isNull());
        verify(ctx, never()).search(eq(BASE), eq("(memberOf=)"), any(SearchControls.class));
    }

    // ── yardımcılar ──

    private static SearchResult user(String attr, String value) {
        return new SearchResult("cn=" + value, null, new BasicAttributes(attr, value, true));
    }

    private static SearchResult group(String dn) {
        SearchResult r = new SearchResult(dn, null, new BasicAttributes(true));
        r.setNameInNamespace(dn);
        return r;
    }

    /** BER: SEQUENCE { size INTEGER 0, cookie OCTET STRING } — gerçek yanıt denetimi. */
    private static PagedResultsResponseControl pagedResponse(byte[] cookie) throws java.io.IOException {
        byte[] ber = new byte[7 + cookie.length];
        ber[0] = 0x30;
        ber[1] = (byte) (5 + cookie.length);
        ber[2] = 0x02;
        ber[3] = 0x01;
        ber[4] = 0x00;
        ber[5] = 0x04;
        ber[6] = (byte) cookie.length;
        System.arraycopy(cookie, 0, ber, 7, cookie.length);
        return new PagedResultsResponseControl(PagedResultsControl.OID, false, ber);
    }

    /** Sıradaki öğe bir istisnaysa {@code hasMore} onu fırlatır (JNDI'nin referans / tavan davranışı). */
    private static NamingEnumeration<SearchResult> results(Object... items) {
        List<Object> list = new ArrayList<>(List.of(items));
        Iterator<Object> it = list.iterator();
        return new NamingEnumeration<>() {
            private Object next;
            @Override public boolean hasMore() throws NamingException {
                if (next == null && it.hasNext()) next = it.next();
                if (next instanceof NamingException ne) { next = null; throw ne; }
                return next != null;
            }
            @Override public SearchResult next() throws NamingException {
                if (!hasMore()) throw new java.util.NoSuchElementException();
                SearchResult r = (SearchResult) next;
                next = null;
                return r;
            }
            @Override public boolean hasMoreElements() {
                try { return hasMore(); } catch (NamingException e) { return false; }
            }
            @Override public SearchResult nextElement() {
                try { return next(); } catch (NamingException e) { throw new IllegalStateException(e); }
            }
            @Override public void close() { }
        };
    }
}
