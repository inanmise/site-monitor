package com.sitemonitor.service.ldap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Saf eşleme kuralı (2026-10-11): DN / CN eşleşmesi, alt dize YOK, ilk kural kazanır, varsayılan normalize. */
class LdapRoleMappingTest {

    static final String G1 = "CN=SiteMonitor Admins,OU=Groups,DC=example,DC=com";
    static final String G2 = "CN=SM-Denetim,OU=Groups,DC=example,DC=com";

    @Test
    @DisplayName("tam DN: büyük/küçük harf ve virgül çevresi boşluk yok sayılır")
    void dnMatch_normalized() {
        var rules = List.of(new LdapRoleMapping.Rule("cn=sitemonitor admins, ou=Groups ,dc=example,dc=com", "ADMIN"));
        assertThat(LdapRoleMapping.firstMatch(rules, List.of(G1))).isNotNull();
        assertThat(LdapRoleMapping.firstMatch(rules, List.of(G2))).isNull();
    }

    @Test
    @DisplayName("grup ADI (CN) eşleşir, 'CN=' önekiyle de; ALT DİZE eşleşmez ('Admins' ≠ 'SiteMonitor Admins')")
    void cnMatch_exactOnly() {
        assertThat(LdapRoleMapping.firstMatch(List.of(new LdapRoleMapping.Rule("sitemonitor admins", "ADMIN")), List.of(G1))).isNotNull();
        assertThat(LdapRoleMapping.firstMatch(List.of(new LdapRoleMapping.Rule("CN=SiteMonitor Admins", "ADMIN")), List.of(G1))).isNotNull();
        assertThat(LdapRoleMapping.firstMatch(List.of(new LdapRoleMapping.Rule("Admins", "ADMIN")), List.of(G1))).isNull();
        assertThat(LdapRoleMapping.firstMatch(List.of(new LdapRoleMapping.Rule("SiteMonitor", "ADMIN")), List.of(G1))).isNull();
    }

    @Test
    @DisplayName("ilk eşleşen kural kazanır — sıra yöneticinin elinde")
    void firstRuleWins() {
        var rules = List.of(new LdapRoleMapping.Rule("SM-Denetim", "AUDIT"), new LdapRoleMapping.Rule("SiteMonitor Admins", "ADMIN"));
        assertThat(LdapRoleMapping.decide(rules, null, List.of(G1, G2)).role()).isEqualTo("AUDIT");
    }

    @Test
    @DisplayName("eşleşme yoksa: boş / bilinmeyen varsayılan = DERIVED; geçerli rol = FALLBACK")
    void fallback() {
        var rules = List.of(new LdapRoleMapping.Rule("Yok", "ADMIN"));
        assertThat(LdapRoleMapping.decide(rules, null, List.of(G1)).source()).isEqualTo(LdapRoleMapping.Source.DERIVED);
        assertThat(LdapRoleMapping.decide(rules, "bogus", List.of(G1)).source()).isEqualTo(LdapRoleMapping.Source.DERIVED);
        var fb = LdapRoleMapping.decide(rules, "team_admin", List.of(G1));
        assertThat(fb.source()).isEqualTo(LdapRoleMapping.Source.FALLBACK);
        assertThat(fb.role()).isEqualTo("TEAM_ADMIN");
        assertThat(LdapRoleMapping.decide(List.of(), "USER", List.of()).role()).isEqualTo("USER");
    }

    @Test
    @DisplayName("ilk RDN CN değeri kaçışlarıyla çözülür; CN değilse null")
    void firstCn_escapes() {
        assertThat(LdapRoleMapping.firstCn("CN=Doe\\, John,OU=Users,DC=x")).isEqualTo("Doe, John");
        assertThat(LdapRoleMapping.firstCn("OU=Users,DC=x")).isNull();
        assertThat(LdapRoleMapping.normalizeDn("CN=A\\, B , OU=X")).isEqualTo("cn=a\\, b,ou=x");
    }

    @Test
    @DisplayName("rulesOf: boş grup / geçersiz rol atlanır, rol büyük harfe çevrilir, sıra korunur")
    void rulesOf_filters() {
        var rules = LdapRoleMapping.rulesOf(List.of(
                Map.of("group", "A", "role", "audit"),
                Map.of("group", " ", "role", "ADMIN"),
                Map.of("group", "B", "role", "ROOT"),
                Map.of("group", "C", "role", "USER")));
        assertThat(rules).extracting(LdapRoleMapping.Rule::group).containsExactly("A", "C");
        assertThat(rules.get(0).role()).isEqualTo("AUDIT");
    }

    @Test
    @DisplayName("rol sırası: ADMIN > AUDIT > TEAM_ADMIN > USER")
    void rank() {
        assertThat(LdapRoleMapping.rank("ADMIN")).isGreaterThan(LdapRoleMapping.rank("AUDIT"));
        assertThat(LdapRoleMapping.rank("AUDIT")).isGreaterThan(LdapRoleMapping.rank("TEAM_ADMIN"));
        assertThat(LdapRoleMapping.rank("TEAM_ADMIN")).isGreaterThan(LdapRoleMapping.rank("USER"));
    }
}
