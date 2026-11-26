package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.PartialResultException;
import javax.naming.SizeLimitExceededException;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.SearchResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bağımsız inceleme (H paketi, 2026-11-26): AD hesap durumu araması (beni-hatırla / kodla giriş) parola girişiyle AYNI kaydı
 * kullanır — giriş filtresi, ilk eşleşme ({@code LdapDirectoryService.loginEntry}). Eskiden iki kayıt "belirsiz" sayılıyor,
 * parola girişi çalışan hesap 24 saat sonra beni-hatırla / kodla girişte reddediliyordu.
 */
class LdapLoginEntryRuleTest {

    /** Sahte arama sonucu: verilen kayıtları döner; {@code failAt} sırasındaki {@code hasMore} istisna fırlatır. */
    private static NamingEnumeration<SearchResult> results(List<SearchResult> hits, int failAt, NamingException ex) {
        return new NamingEnumeration<>() {
            int i;
            int calls;

            @Override public boolean hasMore() throws NamingException {
                if (calls++ == failAt) throw ex;
                return i < hits.size();
            }
            @Override public SearchResult next() { return hits.get(i++); }
            @Override public void close() { }
            @Override public boolean hasMoreElements() { return i < hits.size(); }
            @Override public SearchResult nextElement() { return hits.get(i++); }
        };
    }

    private static SearchResult entry(String dn, String uac) {
        BasicAttributes a = new BasicAttributes(true);
        if (uac != null) a.put("userAccountControl", uac);
        SearchResult r = new SearchResult(dn, null, a);
        r.setNameInNamespace(dn);
        return r;
    }

    @Test
    @DisplayName("ilk kayıt kuralı: iki eşleşmede İLKİ (parola girişinin bind ettiği); boş / referans öncesi kayıt yok → bulunamadı")
    void firstResultRule() throws Exception {
        SearchResult a = entry("CN=Ali,OU=Personel,DC=x", "512");
        SearchResult b = entry("CN=Ali,OU=Eski,DC=x", "514");
        assertThat(LdapDirectoryService.firstResult(results(List.of(a, b), -1, null))).isSameAs(a);
        // Sayım sınırı (2) ikinci okumada aşılırdı — ilk kayıt yine döner (yalnız bir kez sorulur)
        assertThat(LdapDirectoryService.firstResult(results(List.of(a), 1, new SizeLimitExceededException()))).isSameAs(a);
        assertThat(LdapDirectoryService.firstResult(results(List.of(), -1, null))).isNull();
        assertThat(LdapDirectoryService.firstResult(results(List.of(a), 0, new PartialResultException()))).isNull();
    }

    @Test
    @DisplayName("hesap durumu = ilk kaydın userAccountControl'ü; kayıt yok → NOT_FOUND; öznitelik yok → FOUND, etkin sayılır")
    void accountLookupOfEntry() throws Exception {
        var disabled = LdapDirectoryService.accountLookupOf(entry("CN=A", "514"));
        assertThat(disabled.presence()).isEqualTo(LdapDirectoryService.AccountPresence.FOUND);
        assertThat(disabled.disabled()).isTrue();
        var active = LdapDirectoryService.accountLookupOf(entry("CN=B", "512"));
        assertThat(active.disabled()).isFalse();
        assertThat(active.userAccountControl()).isEqualTo("512");
        var noAttr = LdapDirectoryService.accountLookupOf(entry("CN=C", null));
        assertThat(noAttr.presence()).isEqualTo(LdapDirectoryService.AccountPresence.FOUND);
        assertThat(noAttr.userAccountControl()).isNull();
        assertThat(LdapDirectoryService.accountLookupOf(null).presence())
                .isEqualTo(LdapDirectoryService.AccountPresence.NOT_FOUND);
    }

    @Test
    @DisplayName("kapı: parola girişi ve hesap durumu araması TEK yardımcıdan (loginEntry) geçer — biri ayrılırsa kural ayrışır")
    void bothPathsUseLoginEntry() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/sitemonitor/service/LdapDirectoryService.java"),
                StandardCharsets.UTF_8);
        for (String sig : List.of("public Optional<LdapUser> authenticate(", "public AccountLookup lookupAccountState(")) {
            int at = src.indexOf(sig);
            assertThat(at).as(sig).isPositive();
            int end = src.indexOf("\n    }\n", at + sig.length());   // yöntemin kapanışı (4 boşluk girinti)
            assertThat(end).as(sig + " kapanışı").isPositive();
            String body = src.substring(at, end);
            assertThat(body).as(sig).contains("loginEntry(").doesNotContain("uniqueHit(").doesNotContain("setCountLimit(");
        }
        assertThat(src).doesNotContain("AMBIGUOUS");
    }
}
