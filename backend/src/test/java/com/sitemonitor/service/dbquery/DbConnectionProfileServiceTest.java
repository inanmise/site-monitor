package com.sitemonitor.service.dbquery;

import com.sitemonitor.controller.DbQueryApiException;
import com.sitemonitor.model.DbConnectionProfile;
import com.sitemonitor.repository.DbConnectionProfileRepository;
import com.sitemonitor.repository.DbQueryMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.SecretCipher;
import com.sitemonitor.service.SsrfGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Kayıtlı veritabanı bağlantısı servisi GERÇEK H2 + gerçek işlem üstünde (inceleme 2026-10-31):
 * <ul>
 *   <li><b>D4</b> — düzenleme okunduğu andaki varlığı kaydederken sağlık kolonlarını ve {@code password_enc}'i EZMEZ: araya giren
 *       bir izleme çalıştırmasının sağlık izi ({@code recordUseOk} / {@code recordTest}) ve anahtar döndürmenin yeniden
 *       şifrelediği parola korunur. Araya girme, servisin okumasından SONRA çağrılan {@code findByProfileId} sahtesinde aynı
 *       işlem içinde gerçek SQL ile yapılır (başka pod / iş parçacığı yazımının eşi).</li>
 *   <li><b>D3</b> — bağlantı başka takıma taşınınca kayıtlı parola yeniden istenir.</li>
 * </ul>
 * {@code NOT_SUPPORTED}: test işlemi servisin kendi işlemini ve kaydın gerçek yazımını gizlerdi.
 */
@DataJpaTest
@Import(DbConnectionProfileService.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DbConnectionProfileServiceTest {

    @Autowired DbConnectionProfileService service;
    @Autowired DbConnectionProfileRepository repo;
    @Autowired DataSource dataSource;
    @MockitoBean DbQueryMonitorRepository monitorRepo;
    @MockitoBean TeamRepository teamRepo;
    @MockitoBean SecretCipher cipher;
    @MockitoBean SsrfGuard ssrf;

    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        repo.deleteAll();
        when(teamRepo.existsById(anyLong())).thenReturn(true);
        when(cipher.encrypt(anyString())).thenAnswer(i -> "enc:v1:YENI-" + i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        repo.deleteAll();
    }

    private static MockHttpSession admin() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "admin");
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    private DbConnectionProfile saved(long team, String password) {
        DbConnectionProfile p = new DbConnectionProfile();
        p.setTeamId(team); p.setName("Sipariş DB " + team); p.setDbType("POSTGRESQL");
        p.setHost("db-orders.internal"); p.setPort(5432); p.setDatabaseName("orders"); p.setUsername("ro_monitor");
        p.setSslMode("prefer"); p.setConnectTimeoutMs(10_000);
        p.setPasswordEnc(password);
        return repo.save(p);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT description, password_enc, last_ok_at, last_used_at, last_duration_ms, last_test_at, "
                + "last_test_ok, team_id FROM db_connection_profiles WHERE id = ?", id);
    }

    @Test
    @DisplayName("D4: düzenleme sırasında araya giren sağlık yazımı ve yeniden şifreleme EZİLMEZ; yalnız değişen kolon (açıklama) yazılır")
    void concurrentHealthAndReencryptionSurviveEdit() {
        DbConnectionProfile p = saved(1L, "enc:v1:ESKI-ANAHTAR");
        long id = p.getId();
        // Servis kaydı okuduktan SONRA (aynı işlemde) başka pod'un yazımları: izleme çalıştırması + bağlantı denemesi + anahtar döndürme
        when(monitorRepo.findByProfileId(id)).thenAnswer(i -> {
            jdbc.update("UPDATE db_connection_profiles SET last_used_at = ?, last_ok_at = ?, last_duration_ms = ? WHERE id = ?",
                    "2026-10-31T10:00:00", "2026-10-31T10:00:00", 42, id);
            jdbc.update("UPDATE db_connection_profiles SET last_test_at = ?, last_test_ok = ? WHERE id = ?",
                    "2026-10-31T10:00:01", true, id);
            jdbc.update("UPDATE db_connection_profiles SET password_enc = ? WHERE id = ? AND password_enc = ?",
                    "enc:v1:YENI-ANAHTARLA-SIFRELI", id, "enc:v1:ESKI-ANAHTAR");
            return List.of();
        });
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", "Salt okunur izleme hesabı");
        service.update(id, body, admin(), "admin", "Yönetici");

        Map<String, Object> r = row(id);
        assertThat(r.get("DESCRIPTION")).isEqualTo("Salt okunur izleme hesabı");
        assertThat(r.get("PASSWORD_ENC")).as("yeniden şifrelenmiş parola eski metinle ezilmez").isEqualTo("enc:v1:YENI-ANAHTARLA-SIFRELI");
        assertThat(r.get("LAST_OK_AT")).as("izleme çalıştırmasının sağlık izi ezilmez").isEqualTo("2026-10-31T10:00:00");
        assertThat(r.get("LAST_USED_AT")).isEqualTo("2026-10-31T10:00:00");
        assertThat(((Number) r.get("LAST_DURATION_MS")).intValue()).isEqualTo(42);
        assertThat(r.get("LAST_TEST_AT")).isEqualTo("2026-10-31T10:00:01");
        assertThat(r.get("LAST_TEST_OK")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("D4: bayat (ayrık) varlığın kaydı da sağlık kolonlarını / parolayı yazmaz (varlık eşlemesi: updatable = false)")
    void staleEntitySaveNeverWritesHealthOrPassword() {
        DbConnectionProfile p = saved(1L, "enc:v1:ESKI");
        DbConnectionProfile stale = repo.findById(p.getId()).orElseThrow();
        repo.recordUseOk(p.getId(), "2026-10-31T11:00:00", 7);
        repo.recordTest(p.getId(), "2026-10-31T11:00:02", false, 900, "Bağlantı reddedildi", null);
        jdbc.update("UPDATE db_connection_profiles SET password_enc = ? WHERE id = ?", "enc:v1:DONDURULMUS", p.getId());
        stale.setDescription("bayat kayıt");
        repo.save(stale);

        Map<String, Object> r = row(p.getId());
        assertThat(r.get("DESCRIPTION")).isEqualTo("bayat kayıt");
        assertThat(r.get("PASSWORD_ENC")).isEqualTo("enc:v1:DONDURULMUS");
        assertThat(r.get("LAST_OK_AT")).isEqualTo("2026-10-31T11:00:00");
        assertThat(r.get("LAST_TEST_AT")).isEqualTo("2026-10-31T11:00:02");
        assertThat(r.get("LAST_TEST_OK")).isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("D4: parola GERÇEKTEN değişince hedefli yazımla kaydedilir; temizleme NULL yazar; boş parola mevcut değeri korur")
    void passwordChangeIsWrittenTargeted() {
        DbConnectionProfile p = saved(1L, "enc:v1:ESKI");
        long id = p.getId();
        when(monitorRepo.findByProfileId(id)).thenReturn(List.of());

        Map<String, Object> keep = new LinkedHashMap<>();
        keep.put("description", "değişti");
        keep.put("password", "");
        service.update(id, keep, admin(), "admin", "Yönetici");
        assertThat(row(id).get("PASSWORD_ENC")).isEqualTo("enc:v1:ESKI");

        Map<String, Object> change = new LinkedHashMap<>();
        change.put("password", "s3cret");
        DbConnectionProfileService.Updated u = service.update(id, change, admin(), "admin", "Yönetici");
        assertThat(row(id).get("PASSWORD_ENC")).isEqualTo("enc:v1:YENI-s3cret");
        assertThat(u.saved().getPasswordEnc()).as("yanıt password_set doğru olsun").isEqualTo("enc:v1:YENI-s3cret");

        Map<String, Object> clear = new LinkedHashMap<>();
        clear.put("password_clear", true);
        service.update(id, clear, admin(), "admin", "Yönetici");
        assertThat(row(id).get("PASSWORD_ENC")).isNull();
    }

    @Test
    @DisplayName("D3: kullanılmayan bağlantı başka takıma taşınırken kayıtlı parola yeniden istenir (400 password); yeni parolayla taşınır")
    void teamMoveRequiresPasswordAgain() {
        DbConnectionProfile p = saved(1L, "enc:v1:A-TAKIMININ-PAROLASI");
        long id = p.getId();
        when(monitorRepo.findByProfileId(id)).thenReturn(List.of());

        Map<String, Object> move = new LinkedHashMap<>();
        move.put("team_id", 2L);
        assertThatThrownBy(() -> service.update(id, move, admin(), "admin", "Yönetici"))
                .isInstanceOfSatisfying(DbQueryApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.fields()).containsKey("password");
                    assertThat(e.getMessage()).contains("başka bir takıma taşınıyor");
                });
        assertThat(((Number) row(id).get("TEAM_ID")).longValue()).as("taşınmadı").isEqualTo(1L);

        move.put("password", "b-takimi");
        service.update(id, move, admin(), "admin", "Yönetici");
        Map<String, Object> r = row(id);
        assertThat(((Number) r.get("TEAM_ID")).longValue()).isEqualTo(2L);
        assertThat(r.get("PASSWORD_ENC")).isEqualTo("enc:v1:YENI-b-takimi");

        // Parolasız bağlantı taşınırken kural tetiklenmez; parola siliniyorsa da (kayıtlı parola gitmez)
        DbConnectionProfile free = saved(3L, null);
        when(monitorRepo.findByProfileId(free.getId())).thenReturn(List.of());
        Map<String, Object> m2 = new LinkedHashMap<>();
        m2.put("team_id", 4L);
        service.update(free.getId(), m2, admin(), "admin", "Yönetici");
        assertThat(((Number) row(free.getId()).get("TEAM_ID")).longValue()).isEqualTo(4L);

        DbConnectionProfile withPw = saved(5L, "enc:v1:X");
        when(monitorRepo.findByProfileId(eq(withPw.getId()))).thenReturn(List.of());
        Map<String, Object> m3 = new LinkedHashMap<>();
        m3.put("team_id", 6L);
        m3.put("password_clear", true);
        service.update(withPw.getId(), m3, admin(), "admin", "Yönetici");
        assertThat(row(withPw.getId()).get("PASSWORD_ENC")).isNull();
        assertThat(((Number) row(withPw.getId()).get("TEAM_ID")).longValue()).isEqualTo(6L);
    }
}
