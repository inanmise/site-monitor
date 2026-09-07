package com.sitemonitor.repository;

import com.sitemonitor.model.CertificateInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code findDomainsForTeams} entegrasyon testleri (H2).
 *
 * <p>Neden mock'la yetinilmiyor: bu sorgunun ASIL sözleşmesi
 * {@code teamId IN :ids <b>OR</b> ugTeamId IN :ids} — yani ikincil (UG) takımı üzerinden gelen
 * görünürlük. Mock'lanmış bir depo bunu kanıtlayamaz; türetilmiş
 * {@code findByTeamIdIn...} metotları da UG'yi kapsamadığı için bu sorgu elle yazıldı.
 * Kural {@code CertificateController.requireViewableDomain} ile birebir aynı olmak ZORUNDA:
 * tekil kapının kabul ettiği bir domain, liste kapısında düşerse rozet sessizce kaybolur;
 * reddettiğini liste verirse izolasyon sızar.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class CertificateInventoryRepositoryTest {

    @Autowired CertificateInventoryRepository repo;

    private CertificateInventory inv(String domain, Long teamId, Long ugTeamId) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain);
        i.setPort(443);
        i.setActive(true);
        i.setTeamId(teamId);
        i.setUgTeamId(ugTeamId);
        return i;
    }

    @Test
    @DisplayName("findDomainsForTeams: birincil takım kapsamdaysa domain döner")
    void primaryTeamInScope_isReturned() {
        repo.save(inv("birincil.example.com", 5L, null));
        repo.save(inv("yabanci.example.com", 9L, null));

        assertThat(repo.findDomainsForTeams(List.of(5L)))
                .containsExactly("birincil.example.com");
    }

    @Test
    @DisplayName("findDomainsForTeams: YALNIZ UG takımı kapsamdaysa domain YİNE döner (asıl sözleşme)")
    void secondaryUgTeamInScope_isReturned() {
        // Birincil takım (9) kapsam DIŞI, ikincil takım (5) kapsam içi: uygulama geliştirici
        // ekibi kendi devrettiği sertifikayı görebilmeli — requireViewableDomain de böyle diyor.
        repo.save(inv("ug.example.com", 9L, 5L));

        assertThat(repo.findDomainsForTeams(List.of(5L)))
                .containsExactly("ug.example.com");
    }

    @Test
    @DisplayName("findDomainsForTeams: kapsam dışı takımın domaini DÖNMEZ")
    void foreignTeam_isNotReturned() {
        repo.save(inv("yabanci.example.com", 9L, 7L));

        assertThat(repo.findDomainsForTeams(List.of(5L))).isEmpty();
    }

    @Test
    @DisplayName("findDomainsForTeams: her iki takım da kapsamdaysa domain BİR KEZ döner (mükerrer yok)")
    void bothTeamsInScope_returnsSingleRow() {
        // OR satır düzeyinde değerlendirilir, join değil — iki koşul da tutsa bile tek satır.
        // Mükerrer dönseydi rozet kümesi şişer, `count` alanı da yanlış raporlanırdı.
        repo.save(inv("ikisi.example.com", 5L, 6L));

        assertThat(repo.findDomainsForTeams(List.of(5L, 6L)))
                .containsExactly("ikisi.example.com");
    }

    @Test
    @DisplayName("findDomainsForTeams: takımsız (teamId=null) domain hiçbir kapsamda GÖRÜNMEZ")
    void unassignedDomain_isNeverReturned() {
        // `null IN (...)` false'tur. Takıma bağlanamayan bir adı kapsamlı kullanıcıya vermek,
        // tekil kapının reddettiği şeyi liste üzerinden sızdırmak olurdu.
        repo.save(inv("sahipsiz.example.com", null, null));

        assertThat(repo.findDomainsForTeams(List.of(5L))).isEmpty();
    }
}
