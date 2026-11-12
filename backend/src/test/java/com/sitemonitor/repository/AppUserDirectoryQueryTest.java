package com.sitemonitor.repository;

import com.sitemonitor.model.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kullanıcılar dizini (2026-11-12) — {@code findDirectory}'nin YENİ süzgeçlerinin GERÇEK JPQL'i H2 üstünde: hesap durumu,
 * kaynak (LOCAL/LDAP), iletişim (telefon/e-posta/OTP), 7/24 (NOC), çevrimiçi, tarih aralığı (gün önekli), alan-bazlı arama,
 * sıralama (NULLS LAST). Controller testleri depoyu mock'lar; asıl sorgu davranışı burada doğrulanır.
 * {@code NOT_SUPPORTED}: @DataJpaTest'in test başına işlemi kapalı — sorgu üretimdeki gibi işlemsiz koşar.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AppUserDirectoryQueryTest {

    private static final String NOW = "2026-11-12T12:00:00";
    private static final String ONLINE_THRESHOLD = "2026-11-12T11:58:00";

    @Autowired AppUserRepository repo;

    @BeforeEach
    @AfterEach
    void clean() { repo.deleteAll(); }

    /** Tüm alanları ayarlanabilir kullanıcı; varsayılan aktif + LOCAL. */
    private AppUser u(String name) {
        AppUser a = new AppUser();
        a.setUsername(name);
        a.setDisplayName(name + " Ad");
        a.setSystemRole("USER");
        a.setActive(true);
        a.setAuthSource("LOCAL");
        return a;
    }

    private void save(AppUser a, Long primary, Long... extras) {
        a.setTeamId(primary);
        LinkedHashSet<Long> teams = new LinkedHashSet<>();
        if (primary != null) teams.add(primary);
        teams.addAll(List.of(extras));
        a.setTeamIds(teams);
        repo.save(a);
    }

    /** findDirectory — ilgisiz süzgeçler nötr; yalnız verilenler etkin. */
    private List<String> run(String q, String field, String status, String source,
                             int phoneMode, int emailMode, boolean otp,
                             int nocMode, List<Long> nocTeams, int onlineMode,
                             String lf, String lt, String cf, String ct, Sort sort) {
        return repo.findDirectory(q, field == null ? "all" : field, null, null, null,
                        false, List.of(-1L), status, source, phoneMode, emailMode, otp,
                        nocMode, nocTeams == null ? List.of(-1L) : nocTeams, onlineMode, ONLINE_THRESHOLD,
                        lf, lt, cf, ct, null, false, NOW,
                        PageRequest.of(0, 50, sort == null ? Sort.by("username") : sort))
                .getContent().stream().map(AppUser::getUsername).toList();
    }

    @Test
    @DisplayName("hesap durumu: active / passive / locked (kalıcı ya da ileri tarihli kilit) / mustchange")
    void status() {
        save(u("AKTIF"), 1L);
        AppUser pasif = u("PASIF"); pasif.setActive(false); save(pasif, 1L);
        AppUser perm = u("KALICI"); perm.setPermanentLock(true); save(perm, 1L);
        AppUser tempFuture = u("GECICI_ILERI"); tempFuture.setLockoutUntil("2026-11-12T13:00:00"); save(tempFuture, 1L); // > NOW
        AppUser tempPast = u("GECICI_GECMIS"); tempPast.setLockoutUntil("2026-11-12T11:00:00"); save(tempPast, 1L);     // < NOW → kilitli değil
        AppUser mc = u("PAROLA"); mc.setMustChangePassword(true); save(mc, 1L);

        assertThat(run(null, null, "active", null, 0, 0, false, 0, null, 0, null, null, null, null, null))
                .containsExactlyInAnyOrder("AKTIF", "KALICI", "GECICI_ILERI", "GECICI_GECMIS", "PAROLA");
        assertThat(run(null, null, "passive", null, 0, 0, false, 0, null, 0, null, null, null, null, null))
                .containsExactly("PASIF");
        assertThat(run(null, null, "locked", null, 0, 0, false, 0, null, 0, null, null, null, null, null))
                .containsExactlyInAnyOrder("KALICI", "GECICI_ILERI");
        assertThat(run(null, null, "mustchange", null, 0, 0, false, 0, null, 0, null, null, null, null, null))
                .containsExactly("PAROLA");
    }

    @Test
    @DisplayName("kaynak: LDAP / LOCAL (authSource null = LOCAL)")
    void source() {
        AppUser ldap = u("AD"); ldap.setAuthSource("LDAP"); save(ldap, 1L);
        save(u("YEREL"), 1L);                                   // LOCAL
        AppUser nullSrc = u("ESKI"); nullSrc.setAuthSource(null); save(nullSrc, 1L);   // null = LOCAL

        assertThat(run(null, null, null, "LDAP", 0, 0, false, 0, null, 0, null, null, null, null, null))
                .containsExactly("AD");
        assertThat(run(null, null, null, "LOCAL", 0, 0, false, 0, null, 0, null, null, null, null, null))
                .containsExactlyInAnyOrder("YEREL", "ESKI");
    }

    @Test
    @DisplayName("iletişim: telefon/e-posta var(1)/yok(2); boşluk = yok; OTP uygun = aktif + kilitsiz + iletişim var")
    void contactAndOtp() {
        AppUser tel = u("TELEFONLU"); tel.setPhone("5551112233"); tel.setEmail(null); save(tel, 1L);
        AppUser mail = u("EPOSTALI"); mail.setPhone(" "); mail.setEmail("a@b.com"); save(mail, 1L);   // telefon boşluk = yok
        AppUser bos = u("BOS"); bos.setPhone(null); bos.setEmail(null); save(bos, 1L);
        AppUser kilitli = u("KILITLI_TEL"); kilitli.setPhone("5559998877"); kilitli.setPermanentLock(true); save(kilitli, 1L);

        assertThat(run(null, null, null, null, 1, 0, false, 0, null, 0, null, null, null, null, null))
                .containsExactlyInAnyOrder("TELEFONLU", "KILITLI_TEL");
        assertThat(run(null, null, null, null, 2, 0, false, 0, null, 0, null, null, null, null, null))
                .containsExactlyInAnyOrder("EPOSTALI", "BOS");
        assertThat(run(null, null, null, null, 0, 1, false, 0, null, 0, null, null, null, null, null))
                .containsExactly("EPOSTALI");
        // OTP uygun: iletişim var VE aktif VE kilitsiz → TELEFONLU + EPOSTALI (KILITLI_TEL kalıcı kilit nedeniyle hariç)
        assertThat(run(null, null, null, null, 0, 0, true, 0, null, 0, null, null, null, null, null))
                .containsExactlyInAnyOrder("TELEFONLU", "EPOSTALI");
    }

    @Test
    @DisplayName("7/24 (NOC): operatör takımına birincil ya da ek üyelik; kip 1 = üye, kip 2 = değil (pasif üye hariç)")
    void noc() {
        save(u("BIRINCIL7"), 7L);
        save(u("EK7"), 9L, 7L);
        save(u("DISARIDA"), 9L);
        AppUser pasif7 = u("PASIF7"); pasif7.setActive(false); save(pasif7, 7L);   // NOC = aktif üye → hariç

        assertThat(run(null, null, null, null, 0, 0, false, 1, List.of(7L), 0, null, null, null, null, null))
                .containsExactlyInAnyOrder("BIRINCIL7", "EK7");
        assertThat(run(null, null, null, null, 0, 0, false, 2, List.of(7L), 0, null, null, null, null, null))
                .containsExactlyInAnyOrder("DISARIDA", "PASIF7");
    }

    @Test
    @DisplayName("çevrimiçi: canlı oturum + eşikten taze lastSeenAt; TERMINATED / bayat / oturumsuz = çevrimdışı")
    void online() {
        AppUser on = u("CEVRIMICI"); on.setActiveSessionId("sess-1"); on.setLastSeenAt("2026-11-12T11:59:30"); save(on, 1L);
        AppUser term = u("SONLANMIS"); term.setActiveSessionId("TERMINATED:x"); term.setLastSeenAt("2026-11-12T11:59:30"); save(term, 1L);
        AppUser stale = u("BAYAT"); stale.setActiveSessionId("sess-2"); stale.setLastSeenAt("2026-11-12T10:00:00"); save(stale, 1L); // < eşik
        AppUser none = u("OTURUMSUZ"); none.setActiveSessionId(null); save(none, 1L);

        assertThat(run(null, null, null, null, 0, 0, false, 0, null, 1, null, null, null, null, null))
                .containsExactly("CEVRIMICI");
        assertThat(run(null, null, null, null, 0, 0, false, 0, null, 2, null, null, null, null, null))
                .containsExactlyInAnyOrder("SONLANMIS", "BAYAT", "OTURUMSUZ");
    }

    @Test
    @DisplayName("tarih aralığı: son giriş + oluşturulma GÜN önekli (zaman biçiminden bağımsız); from/to dahil")
    void dateRange() {
        AppUser a = u("OCAK"); a.setLastLoginAt("2026-01-15T08:00:00"); a.setCreatedAt("2025-12-01T00:00:00Z"); save(a, 1L);
        AppUser b = u("HAZIRAN"); b.setLastLoginAt("2026-06-15T08:00:00"); b.setCreatedAt("2026-06-10 09:30:00"); save(b, 1L); // boşluklu biçim
        AppUser c = u("HIC"); c.setLastLoginAt(null); c.setCreatedAt("2026-06-20T00:00:00"); save(c, 1L);

        // son giriş 2026-02-01 .. 2026-12-31 → yalnız HAZIRAN (OCAK eski, HIC null)
        assertThat(run(null, null, null, null, 0, 0, false, 0, null, 0, "2026-02-01", "2026-12-31", null, null, null))
                .containsExactly("HAZIRAN");
        // oluşturulma 2026-06-01 .. 2026-06-30 → HAZIRAN (boşluklu) + HIC; OCAK (2025) hariç
        assertThat(run(null, null, null, null, 0, 0, false, 0, null, 0, null, null, "2026-06-01", "2026-06-30", null))
                .containsExactlyInAnyOrder("HAZIRAN", "HIC");
    }

    @Test
    @DisplayName("alan-bazlı arama: email yalnız e-postada, phone yalnız telefonda, employee yalnız sicilde eşleşir")
    void fieldScoped() {
        AppUser a = u("alpha"); a.setEmail("match@corp.com"); a.setPhone("5550001111"); a.setEmployeeId("E100"); save(a, 1L);
        AppUser b = u("match"); b.setEmail("beta@corp.com"); b.setPhone("5559990000"); b.setEmployeeId("E200"); save(b, 1L);

        // email alanı: "match" yalnız a'nın e-postasında geçer (b'nin kullanıcı adı "match" ama alan email)
        assertThat(run("%match%", "email", null, null, 0, 0, false, 0, null, 0, null, null, null, null, null))
                .containsExactly("alpha");
        // username alanı: yalnız b
        assertThat(run("%match%", "username", null, null, 0, 0, false, 0, null, 0, null, null, null, null, null))
                .containsExactly("match");
        // phone alanı: 5550 yalnız a
        assertThat(run("%5550%", "phone", null, null, 0, 0, false, 0, null, 0, null, null, null, null, null))
                .containsExactly("alpha");
        // employee alanı: e200 yalnız b (küçük harf — LOWER)
        assertThat(run("%e200%", "employee", null, null, 0, 0, false, 0, null, 0, null, null, null, null, null))
                .containsExactly("match");
        // all alanı: "match" hem a'nın e-postasında hem b'nin kullanıcı adında → ikisi de
        assertThat(run("%match%", "all", null, null, 0, 0, false, 0, null, 0, null, null, null, null, null))
                .containsExactlyInAnyOrder("alpha", "match");
    }

    @Test
    @DisplayName("sıralama: lastLogin DESC NULLS LAST (hiç girmemiş sonda); username ASC/DESC")
    void sorting() {
        AppUser a = u("aaa"); a.setLastLoginAt("2026-03-01T00:00:00"); save(a, 1L);
        AppUser b = u("bbb"); b.setLastLoginAt("2026-09-01T00:00:00"); save(b, 1L);
        AppUser c = u("ccc"); c.setLastLoginAt(null); save(c, 1L);   // hiç girmemiş → DESC'te sonda

        assertThat(run(null, null, null, null, 0, 0, false, 0, null, 0, null, null, null, null,
                Sort.by(new Sort.Order(Sort.Direction.DESC, "lastLoginAt").nullsLast(), Sort.Order.asc("id"))))
                .containsExactly("bbb", "aaa", "ccc");
        assertThat(run(null, null, null, null, 0, 0, false, 0, null, 0, null, null, null, null, Sort.by("username")))
                .containsExactly("aaa", "bbb", "ccc");
        assertThat(run(null, null, null, null, 0, 0, false, 0, null, 0, null, null, null, null,
                Sort.by(Sort.Direction.DESC, "username")))
                .containsExactly("ccc", "bbb", "aaa");
    }
}
