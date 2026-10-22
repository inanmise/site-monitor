package com.sitemonitor.repository;

import com.sitemonitor.model.LoginIssueReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sorun bildirimi süzgeç sorgusunun ETKİ ayağı (2026-09-28) GERÇEK veritabanında (H2): controller/servis testleri
 * depoyu mock'ladığı için JPQL'in {@code CONCAT(',', r.impacts, ',') LIKE :impact} parçası başka hiçbir yerde
 * koşmaz. Sözleşme: TAM kod eşleşmesi (virgülle sınırlı), eski (null) kayıtlar süzgeç yokken listede kalır.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class LoginIssueReportRepositoryTest {

    @Autowired LoginIssueReportRepository repo;

    private LoginIssueReport report(String at, String impacts) {
        LoginIssueReport r = new LoginIssueReport();
        r.setReportedAt(at);
        r.setMessage("m " + at);
        r.setStatus("OPEN");
        r.setSource("USER_REPORT");
        r.setImpacts(impacts);
        return repo.save(r);
    }

    private List<String> ids(String impactLike) {
        return repo.findFiltered(null, null, null, impactLike, null, null, null, PageRequest.of(0, 50))
                .getContent().stream().map(LoginIssueReport::getReportedAt).toList();
    }

    @Test
    @DisplayName("impact süzgeci: ',KOD,' deseniyle kümenin başı/ortası/sonu eşleşir; başka kod ve null kayıt eşleşmez; süzgeçsiz hepsi")
    void impactFilter_exactCodeWithinCsv() {
        report("2026-09-28T01:00:00", "LOGIN,SLOW");
        report("2026-09-28T02:00:00", "SLOW");
        report("2026-09-28T03:00:00", "PAGE_NOT_LOADING,SAVE_ERROR,OTHER");
        report("2026-09-28T04:00:00", null);   // özellik öncesi kayıt

        assertThat(ids("%,SLOW,%")).containsExactly("2026-09-28T02:00:00", "2026-09-28T01:00:00");
        assertThat(ids("%,LOGIN,%")).containsExactly("2026-09-28T01:00:00");
        assertThat(ids("%,OTHER,%")).containsExactly("2026-09-28T03:00:00");
        assertThat(ids("%,MOBILE,%")).isEmpty();
        assertThat(ids(null)).hasSize(4);
    }

    @Test
    @DisplayName("AUTH-13 (2026-10-22): 'Bildirimlerim' (findMine / countMineByStatus) yalnız oturum içi kaynakları sayar — giriş sayfası (LOGIN) kaydı sahibinin listesine girmez")
    void mineExcludesLoginSource() {
        for (String[] row : new String[][] { {"USER_REPORT", "2026-10-22T01:00:00"}, {"CLIENT_ERROR", "2026-10-22T02:00:00"},
                {"LOGIN", "2026-10-22T03:00:00"} }) {
            LoginIssueReport r = new LoginIssueReport();
            r.setReportedAt(row[1]);
            r.setMessage("m");
            r.setStatus("OPEN");
            r.setSource(row[0]);
            r.setUsername("N12345");
            repo.save(r);
        }
        assertThat(repo.findMine("n12345", null, PageRequest.of(0, 50)).getContent().stream()
                .map(LoginIssueReport::getSource).toList()).containsExactly("CLIENT_ERROR", "USER_REPORT");
        assertThat(repo.countMineByStatus("n12345")).singleElement()
                .satisfies(r -> assertThat(((Number) r[1]).longValue()).isEqualTo(2L));
    }
}
