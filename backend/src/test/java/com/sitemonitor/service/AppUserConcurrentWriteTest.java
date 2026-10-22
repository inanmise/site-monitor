package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.PasswordHistoryRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code app_users} yazımları eşzamanlı değişikliği GERİ ALMAZ (2026-10-23, çok pod Sürüm 2a — MP-51). GERÇEK H2 (PostgreSQL
 * kipi), işlem dışında ({@code NOT_SUPPORTED}) — üretimdeki gibi çağıran uç kullanıcıyı işlem DIŞINDA okur (ayrık nesne).
 *
 * <p>Her yol için aynı senaryo: kullanıcı okunur → BAŞKA bir istek (başka pod) hesabı pasife alır ({@code active=false} +
 * {@code TERMINATED:} nöbetçisi, işlenmiş) → yol kendi yazımını yapar → pasifleştirme DURUR. Eskiden yollar ayrık nesneyi
 * {@code save()} ile birleştiriyordu (merge satırın tüm alanlarını eski değerlerle yazar; {@code @DynamicUpdate} de
 * korumaz — {@link #control_detachedMergeRevertsDeactivation} bunu kanıtlar) ve pasif hesap yeniden aktifleşiyordu.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:appuserconcurrent;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver"})
class AppUserConcurrentWriteTest {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Autowired AppUserRepository realRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired CertificateInventoryRepository inventoryRepo;
    @Autowired EscalationContactRepository contactRepo;
    @Autowired PasswordHistoryRepository passwordHistoryRepo;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager txManager;

    JdbcTemplate jdbc;
    Long id;
    /** {@code findByUsername} SONRASI bir kez koşturulacak "başka pod" yazımı (null = yok). */
    final AtomicReference<Runnable> afterRead = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM app_user_teams");
        realRepo.deleteAll();
        AppUser u = new AppUser();
        u.setUsername("ALICE");
        u.setDisplayName("Alice");
        u.setEmail(null);
        u.setActive(true);
        u.setSystemRole("USER");
        u.setActiveSessionId("SID-LIVE");
        u.setCreatedAt("2026-10-23T08:00:00");
        u.setUpdatedAt("2026-10-23T08:00:00");
        id = realRepo.save(u).getId();
        afterRead.set(null);
    }

    /** NOT_SUPPORTED satırları işler — önbellekli @DataJpaTest bağlamını paylaşan sonraki testler temiz başlasın. */
    @AfterEach
    void cleanUpCommittedRows() {
        jdbc.update("DELETE FROM app_user_teams");
        realRepo.deleteAll();
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────────────────────────────────────────

    /** Gerçek depoyu saran vekil: {@code findByUsername}'dan HEMEN sonra {@link #afterRead}'i bir kez koşturur. */
    private AppUserRepository hookedRepo() {
        return (AppUserRepository) Proxy.newProxyInstance(AppUserRepository.class.getClassLoader(),
                new Class<?>[] { AppUserRepository.class }, (proxy, m, args) -> {
                    Object r;
                    try {
                        r = m.invoke(realRepo, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if ("findByUsername".equals(m.getName())) {
                        Runnable hook = afterRead.getAndSet(null);
                        if (hook != null) hook.run();
                    }
                    return r;
                });
    }

    private UserService service(AppUserRepository repo) {
        UserService s = new UserService(repo, teamRepo, inventoryRepo, contactRepo, passwordHistoryRepo);
        ReflectionTestUtils.setField(s, "stampDedupeSeconds", 30L);
        ReflectionTestUtils.setField(s, "lockoutDurationsSecs", java.util.List.of(30L, 120L, 600L, 1800L));
        ReflectionTestUtils.setField(s, "lockoutFailuresNeeded", java.util.List.of(5, 3, 2, 1));
        return s;
    }

    /** "Başka pod": hesabı pasife alır — UserService.updateUser'ın yazdığı iki alan, ayrı işlemde, işlenmiş. */
    private void deactivateElsewhere() {
        new TransactionTemplate(txManager, requiresNew()).executeWithoutResult(st ->
                jdbc.update("UPDATE app_users SET active = false, active_session_id = 'TERMINATED:other-pod' WHERE id = ?", id));
    }

    private static TransactionDefinition requiresNew() {
        org.springframework.transaction.support.DefaultTransactionDefinition d =
                new org.springframework.transaction.support.DefaultTransactionDefinition();
        d.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return d;
    }

    private AppUser detached() {
        return realRepo.findByUsername("ALICE").orElseThrow();   // işlem dışı → ayrık nesne (üretimdeki çağıran uçlar gibi)
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT * FROM app_users WHERE id = ?", id);
    }

    private void assertDeactivationSurvived() {
        Map<String, Object> r = row();
        assertThat(r.get("active")).as("pasifleştirme geri alınmamalı").isEqualTo(Boolean.FALSE);
        assertThat((String) r.get("active_session_id")).as("TERMINATED nöbetçisi geri alınmamalı").startsWith("TERMINATED:");
    }

    // ── Kontrol: tehlikenin kendisi ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("KONTROL: ayrık nesnenin save() ile birleştirilmesi eşzamanlı pasifleştirmeyi GERİ ALIR (@DynamicUpdate'e rağmen) — düzeltmelerin sebebi")
    void control_detachedMergeRevertsDeactivation() {
        AppUser u = detached();
        deactivateElsewhere();
        u.setPushOptOut(true);
        realRepo.save(u);   // 20.165.1'in savePushOptOut / TourStateService / IssueReportController / AdminController yolu
        assertThat(row().get("active")).isEqualTo(Boolean.TRUE);
    }

    // ── Kişinin kendi tercihleri ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("savePushOptOut (AuthController /me/push-opt-out): yalnız push_opt_out yazılır, pasifleştirme durur")
    void savePushOptOut_keepsDeactivation() {
        AppUser u = detached();
        deactivateElsewhere();
        u.setPushOptOut(true);
        service(realRepo).savePushOptOut(u);
        assertDeactivationSurvived();
        assertThat(row().get("push_opt_out")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("savePushQuietHours: yalnız sessiz saat sütunları, pasifleştirme durur")
    void savePushQuietHours_keepsDeactivation() {
        AppUser u = detached();
        deactivateElsewhere();
        AppUser out = service(realRepo).savePushQuietHours(u, new QuietHours.Config("22:00", "07:00", "1,2,3", "CRITICAL"));
        assertDeactivationSurvived();
        Map<String, Object> r = row();
        assertThat(r.get("push_quiet_start")).isEqualTo("22:00");
        assertThat(r.get("push_quiet_end")).isEqualTo("07:00");
        assertThat(r.get("push_quiet_days")).isEqualTo("1,2,3");
        assertThat(r.get("push_quiet_min_level")).isEqualTo("CRITICAL");
        assertThat(out.getPushQuietStart()).isEqualTo("22:00");   // yanıt bu nesneyi okur
    }

    @Test
    @DisplayName("savePushPreferences (MyPushController): yalnız tercih sütunları, pasifleştirme durur")
    void savePushPreferences_keepsDeactivation() {
        AppUser u = detached();
        deactivateElsewhere();
        service(realRepo).savePushPreferences(u, "HIGH", "cert,http", "en");
        assertDeactivationSurvived();
        Map<String, Object> r = row();
        assertThat(r.get("push_min_level")).isEqualTo("HIGH");
        assertThat(r.get("push_families")).isEqualTo("cert,http");
        assertThat(r.get("push_lang")).isEqualTo("en");
    }

    @Test
    @DisplayName("savePushSnooze (MyPushController + AdminController susturma kaldırma): yalnız susturma sütunları, pasifleştirme durur")
    void savePushSnooze_keepsDeactivation() {
        AppUser u = detached();
        deactivateElsewhere();
        service(realRepo).savePushSnooze(u, "2026-10-23T12:00:00", false);
        assertDeactivationSurvived();
        assertThat(row().get("push_snooze_until")).isEqualTo("2026-10-23T12:00:00");
        assertThat(row().get("push_snooze_critical")).isEqualTo(Boolean.FALSE);

        AppUser again = detached();
        service(realRepo).savePushSnooze(again, null, null);   // yönetici kaldırması: kritik bayrağına dokunmaz
        assertDeactivationSurvived();
        assertThat(row().get("push_snooze_until")).isNull();
        assertThat(row().get("push_snooze_critical")).isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("TourStateService.apply (her tur adımı + sıfırlama): yalnız tour_state, pasifleştirme durur")
    void tourState_keepsDeactivation() {
        TourStateService tours = new TourStateService(realRepo);
        AppUser u = detached();
        deactivateElsewhere();
        tours.apply(u, Map.of("status", "started", "version", 2), false);
        assertDeactivationSurvived();
        assertThat((String) row().get("tour_state")).contains("\"status\":\"started\"");

        AppUser again = detached();
        tours.apply(again, Map.of(), true);
        assertDeactivationSurvived();
        assertThat(row().get("tour_state")).isNull();
    }

    @Test
    @DisplayName("IssueReportController 'profile kaydet': yalnız e-posta ve YALNIZ hâlâ boşsa; pasifleştirme durur")
    void issueReportEmail_keepsDeactivationAndNeverOverwrites() {
        AppUser u = detached();
        deactivateElsewhere();
        assertThat(realRepo.updateEmailIfBlank(u.getId(), "yeni@example.com")).isEqualTo(1);
        assertDeactivationSurvived();
        assertThat(row().get("email")).isEqualTo("yeni@example.com");
        // Bu arada başka sekme bir adres yazdıysa ezilmez
        assertThat(realRepo.updateEmailIfBlank(u.getId(), "baska@example.com")).isZero();
        assertThat(row().get("email")).isEqualTo("yeni@example.com");
    }

    // ── Yönetici profil düzenlemesi ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AdminController PUT /users/{id} profil alanları: TAZE satıra yazılır — araya giren yeni oturum kimliği ve pasifleştirme durur")
    void adminProfileFields_freshRow() {
        UserService svc = service(realRepo);
        AppUser fromUpdate = detached();   // updateUser'ın döndürdüğü (işlemi bitmiş) nesne yerine geçer
        // Araya: kullanıcı başka pod'da yeniden girdi (yeni oturum) ve ardından pasife alındı
        jdbc.update("UPDATE app_users SET active_session_id = 'SID-NEW' WHERE id = ?", id);
        AppUser saved = new TransactionTemplate(txManager).execute(st ->
                svc.applyProfileFieldsAndSave(fromUpdate.getId(), new HashMap<>(Map.of("title", "Kıdemli Uzman")), true));
        assertThat(row().get("active_session_id")).isEqualTo("SID-NEW");
        assertThat(row().get("title")).isEqualTo("Kıdemli Uzman");
        assertThat(saved.getTitle()).isEqualTo("Kıdemli Uzman");

        deactivateElsewhere();
        new TransactionTemplate(txManager).execute(st ->
                svc.applyProfileFieldsAndSave(fromUpdate.getId(), new HashMap<>(Map.of("department", "Kanallar")), true));
        assertDeactivationSurvived();
        assertThat(row().get("department")).isEqualTo("Kanallar");
    }

    // ── Giriş yolu ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("checkLockout (kilit dolduğu an, giriş yolu): yalnız lockout_until temizlenir — araya giren pasifleştirme durur")
    void checkLockout_keepsDeactivation() {
        jdbc.update("UPDATE app_users SET lockout_until = ? WHERE id = ?", ISO.format(Instant.now().minusSeconds(5)), id);
        afterRead.set(this::deactivateElsewhere);
        UserService.LockoutStatus st = service(hookedRepo()).checkLockout("ALICE");
        assertThat(st.isBlocked()).isFalse();
        assertDeactivationSurvived();
        assertThat(row().get("lockout_until")).isNull();
    }

    @Test
    @DisplayName("checkLockout: süresi dolmuş kilit okunduktan SONRA başka pod yeni kilit yazdıysa temizleme ona dokunmaz (koşullu)")
    void checkLockout_doesNotWipeNewerLockout() {
        jdbc.update("UPDATE app_users SET lockout_until = ? WHERE id = ?", ISO.format(Instant.now().minusSeconds(5)), id);
        String newer = ISO.format(Instant.now().plusSeconds(120));
        afterRead.set(() -> jdbc.update("UPDATE app_users SET lockout_until = ? WHERE id = ?", newer, id));
        service(hookedRepo()).checkLockout("ALICE");
        assertThat(row().get("lockout_until")).isEqualTo(newer);
    }

    @Test
    @DisplayName("recordSuccessfulLogin (yönetilen varlık, kendi işleminde): @DynamicUpdate → okumadan sonraki pasifleştirme (active=false) durur")
    void recordSuccessfulLogin_dynamicUpdateKeepsActiveFlag() {
        UserService svc = service(hookedRepo());
        afterRead.set(this::deactivateElsewhere);
        new TransactionTemplate(txManager).executeWithoutResult(st ->
                svc.recordSuccessfulLogin("ALICE", "SID-LOGIN", "10.0.0.1", UserService.LoginMethod.PASSWORD));
        Map<String, Object> r = row();
        assertThat(r.get("active")).as("tam satır yazımı active=true'yu geri getirirdi").isEqualTo(Boolean.FALSE);
        assertThat(r.get("last_login_ip")).isEqualTo("10.0.0.1");   // girişin kendi sütunları yazıldı
    }

    // ── Çevrimiçi göstergesi / 409 onayı: ping debounce'u pod'lar arası ────────────────────────────────────────

    @Test
    @DisplayName("Ping debounce SQL'de: pod A 'ayrıldım' ile damgayı siler → pod B'nin (yerel kaydı taze) sonraki ping'i YAZAR; canlı oturum hemen görünür")
    void presence_markLeftOnA_pingOnBReRegisters() {
        UserService podA = service(realRepo);
        UserService podB = service(realRepo);
        ReflectionTestUtils.setField(podA, "touchDebounceMs", 60_000L);
        ReflectionTestUtils.setField(podB, "touchDebounceMs", 60_000L);
        ReflectionTestUtils.setField(podA, "activeWindowSeconds", 120L);
        ReflectionTestUtils.setField(podB, "activeWindowSeconds", 120L);
        TransactionTemplate tx = new TransactionTemplate(txManager);

        // Eski damga → B yazar (yerel kaydı artık taze)
        jdbc.update("UPDATE app_users SET last_seen_at = ? WHERE id = ?", ISO.format(Instant.now().minusSeconds(90)), id);
        tx.executeWithoutResult(st -> podB.touchActiveSession("ALICE", "SID-LIVE"));
        assertThat(podB.hasLiveSession(detached())).isTrue();

        // Son sekme kapandı: beacon pod A'ya düştü
        podA.markLeft("ALICE", "SID-LIVE");
        assertThat(row().get("last_seen_at")).isNull();
        assertThat(podB.hasLiveSession(detached())).isFalse();

        // Sayfa yenilendi, ping pod B'ye düştü — 20.165.1'de B'nin 60 sn'lik yerel kaydı yazımı atlıyordu
        tx.executeWithoutResult(st -> podB.touchActiveSession("ALICE", "SID-LIVE"));
        assertThat(row().get("last_seen_at")).isNotNull();
        assertThat(podA.hasLiveSession(detached())).as("başka yerden giriş 409 onayı alır").isTrue();

        // Taze damga: hemen ardından gelen ping (her iki pod) yazmaz — debounce satırdan
        String stamp = (String) row().get("last_seen_at");
        jdbc.update("UPDATE app_users SET last_seen_at = ? WHERE id = ?", stamp, id);
        assertThat(realRepo.touchLastSeenIfStale("ALICE", "SID-LIVE", "2099-01-01T00:00:00",
                ISO.format(Instant.now().minusSeconds(45)))).isZero();
        assertThat(row().get("last_seen_at")).isEqualTo(stamp);
    }

    @Test
    @DisplayName("Ping: işaret NULL (açılış temizliği) → yeniden sahiplenme yine çalışır; başka işaret (süpersede) varken çalınmaz")
    void presence_adoptStillWorks() {
        UserService pod = service(realRepo);
        ReflectionTestUtils.setField(pod, "touchDebounceMs", 60_000L);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        jdbc.update("UPDATE app_users SET active_session_id = NULL, last_seen_at = NULL WHERE id = ?", id);
        tx.executeWithoutResult(st -> pod.touchActiveSession("ALICE", "SID-LIVE"));
        assertThat(row().get("active_session_id")).isEqualTo("SID-LIVE");
        assertThat(row().get("last_seen_at")).isNotNull();

        jdbc.update("UPDATE app_users SET active_session_id = 'SID-NEWER' WHERE id = ?", id);
        UserService other = service(realRepo);
        ReflectionTestUtils.setField(other, "touchDebounceMs", 60_000L);
        tx.executeWithoutResult(st -> other.touchActiveSession("ALICE", "SID-LIVE"));
        assertThat(row().get("active_session_id")).isEqualTo("SID-NEWER");
    }
}
