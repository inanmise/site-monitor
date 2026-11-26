package com.sitemonitor.service;

import com.sitemonitor.model.AuditLog;
import com.sitemonitor.repository.AuditLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H paketi B6 + B7 (2026-11-26): denetim fallback satırı GERÇEK JSON'dur (tam kaçış, kayıpsız alanlar; satır sonu satırı
 * bölmez, sayaç doğru sayar) ve her pod kendi dosyasını veritabanı okunabilir olunca denetim tablosuna geri aktarır — zincir
 * kuralıyla, nişanla (tekrar yazım yok), bozuk satırı .failed dosyasına alarak. Bağımsız inceleme (aynı gün): satır yazılırken
 * HMAC ile İMZALANIR; geri aktarım yalnız imzası doğru, alanları geçerli (katalogdaki olay türü, bilinen sonuç, bilinen
 * anahtarlar, sütun sınırı) ve 60 günden genç satırı zincire yazar — imzasız (önceki sürümün v1 satırı dahil), imzası bozuk ya
 * da alanı geçersiz satır .failed'a gider ve bekleyen sayılır. Gerçek H2 + gerçek işlem yöneticisi; satırlar gerçekten COMMIT
 * olur, her test sonunda silinir.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:auditfallbackreplay;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuditFallbackReplayTest {

    private static final tools.jackson.databind.ObjectMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    private static final byte[] KEY = "test-audit-fallback-key-32-bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER_KEY = "another-audit-fallback-key-32byt".getBytes(StandardCharsets.UTF_8);

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    /** Olay anı: testin koştuğu andan 1 gün önce (UTC) — sabit tarih 60 günlük yaş sınırında zamanla patlardı. */
    private static final String EVENT_TIME = ISO.format(Instant.now().minus(Duration.ofDays(1)));

    @Autowired AuditLogRepository repo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbc;

    @TempDir Path tmp;

    private AuditService svc;
    private Path fallback;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
        jdbc.update("DELETE FROM schema_patch_markers WHERE patch_key LIKE 'audit-fallback:%'");
        fallback = tmp.resolve("logs").resolve("audit-fallback.jsonl");
        svc = pod(jdbc);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM schema_patch_markers WHERE patch_key LIKE 'audit-fallback:%'");
    }

    private AuditService pod(JdbcTemplate j) {
        return pod(j, repo);
    }

    private AuditService pod(JdbcTemplate j, AuditLogRepository r) {
        AuditService s = podNoKeys(j, r);
        s.setFallbackKeys(KEY, null);   // tüm "pod'lar" aynı SITE_MONITOR_SECRET_KEY alt anahtarını paylaşır
        return s;
    }

    private AuditService podNoKeys(JdbcTemplate j, AuditLogRepository r) {
        AuditService s = new AuditService(r, mock(GeoIpService.class), mock(NewDeviceNotifier.class),
                mock(ClientIpResolver.class), mock(AuditGeoEnricher.class));
        s.setChainLockSupport(j, txManager);
        ReflectionTestUtils.setField(s, "fallbackFile", fallback.toString());
        ReflectionTestUtils.setField(s, "fallbackSecondaryDir", tmp.resolve("second").toString());
        return s;
    }

    /** İmzalı fallback satırı — yazıcının ürettiğiyle aynı (sütun sığdırma + imza). */
    private String line(AuditLog e) {
        AuditService.fitColumns(e);
        return svc.fallbackLine(e);
    }

    private static AuditLog event(String type, String actor) {
        AuditLog e = new AuditLog();
        e.setEventTime(EVENT_TIME);
        e.setEventType(type);
        e.setActor(actor);
        e.setActorId(42L);
        e.setActorTeamId(7L);
        e.setActorRole("ADMIN");
        e.setIpAddress("10.1.2.3");
        e.setUserAgent("Mozilla/5.0");
        e.setResourceType("USER");
        e.setResourceId("9");
        e.setDetail("{\"username\":\"ali\",\"path\":\"C:\\\\temp\"}");
        e.setOutcome("SUCCESS");
        return e;
    }

    private void writeLines(Path p, String... lines) throws Exception {
        Files.createDirectories(p.getParent());
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String s) {
        return JSON.readValue(s, Map.class);
    }

    /** İmzasız gövdeyi (yeniden biçimlendirmeden) değiştirip imzalar — "anahtarı bilen ama alanı uydurulmuş" satır. */
    private String signedBody(Map<String, Object> m) {
        return svc.signFallbackBody(JSON.writeValueAsString(m));
    }

    private static Map<String, Object> bodyOf(AuditLog e) {
        return new LinkedHashMap<>(parse(AuditService.fallbackJson(e)));
    }

    private int auditRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class);
    }

    private List<String> failedLines() throws Exception {
        Path failed = AuditService.siblingWith(fallback, AuditService.FAILED_SUFFIX);
        return Files.exists(failed) ? Files.readAllLines(failed) : List.of();
    }

    // ── B6: gerçek JSON ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("B6: kullanıcının yazdığı satır sonu, ters bölü, tırnak ve denetim karakteri tek satır GEÇERLİ JSON'da kayıpsız taşınır; satır imzalıdır")
    void fallbackLine_isValidSingleLineJson() throws Exception {
        AuditLog e = event("LOGIN_FAILED", "kötü\nad\\\"x\u0001\r\u2028son");
        String line = line(e);
        assertThat(line).doesNotContain("\n").doesNotContain("\r");
        Map<String, Object> m = parse(line);
        assertThat(m.get("v")).isEqualTo(AuditService.FALLBACK_FORMAT);
        assertThat((String) m.get("fid")).matches("[0-9a-f-]{36}");
        assertThat(m.get("actor")).isEqualTo("kötü\nad\\\"x\u0001\r\u2028son");
        assertThat(m.get("actor_id")).isEqualTo(42);
        assertThat(m.get("actor_team_id")).isEqualTo(7);
        assertThat(m.get("ip_address")).isEqualTo("10.1.2.3");
        assertThat(m.get("detail")).isEqualTo("{\"username\":\"ali\",\"path\":\"C:\\\\temp\"}");
        assertThat(m.get("event_time")).isEqualTo(EVENT_TIME);
        assertThat((String) m.get("sig")).matches("[A-Za-z0-9_-]{43}");
        assertThat(svc.checkFallbackSignature(line).problem()).isNull();
        assertThat(parse(svc.checkFallbackSignature(line).body())).doesNotContainKey("sig").containsEntry("fid", m.get("fid"));

        // Sayaç: satır içindeki "\n" (kaçışlı) satırı bölmez → 1 kayıt
        writeLines(fallback, line);
        assertThat(svc.pendingFallbackAuditCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("B6: yedek kaçış yolu (jsonString) Jackson ile aynı sonucu verir — kontrol karakterleri, U+2028, ters bölü")
    void jsonString_escapesLikeJackson() {
        String raw = "a\"b\\c\nd\re\tf\u0000g\u001fh\u2028i\u2029j\u007fk";
        assertThat(JSON.readValue(AuditService.jsonString(raw), String.class)).isEqualTo(raw);
        assertThat(AuditService.jsonString(raw)).doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    @DisplayName("B6: eski (bölünmüş) satır dosyasında yalnız '{' ile başlayan satırlar sayılır — parça sahte kayıt değildir")
    void countLines_ignoresLegacyFragments() throws Exception {
        writeLines(fallback,
                "{\"event_time\":\"2026-11-01T10:00:00\",\"event_type\":\"LOGIN_FAILED\",\"actor\":\"x",
                "y\",\"resource_type\":\"\",\"resource_id\":\"\",\"outcome\":\"FAILURE\"}",
                "{\"event_time\":\"2026-11-01T10:01:00\",\"event_type\":\"LOGIN\",\"actor\":\"ali\",\"resource_type\":\"\",\"resource_id\":\"\",\"outcome\":\"SUCCESS\"}");
        assertThat(AuditService.countLines(fallback)).isEqualTo(2L);
    }

    // ── B7: geri aktarım ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("B7: imzalı satırlar zincire yazılır (zaman = aktarım anı, asıl zaman ayrıntıda); önceki sürümün imzasız satırı ve bozuk parça .failed'a; zincir sağlam, ikinci tur yazmaz")
    void replay_writesChainOnceAndParksUnverified() throws Exception {
        String v2a = line(event("USER_UPDATE", "admin"));
        String v2b = line(event("LOGIN", "ali"));
        String legacy = "{\"event_time\":\"" + EVENT_TIME + "\",\"event_type\":\"LOGOUT\",\"actor\":\"veli\","
                + "\"resource_type\":\"\",\"resource_id\":\"\",\"outcome\":\"SUCCESS\"}";
        writeLines(fallback, v2a, v2b, legacy, "parça\",\"outcome\":\"FAILURE\"}");
        svc.recordSystemEvent("SYSTEM_STARTUP", "SYSTEM", "app", "boot");   // zincirde önceden bir satır

        AuditService.ReplayResult r = svc.replayFallback();

        assertThat(r.replayed()).isEqualTo(2);
        assertThat(r.parked()).isEqualTo(2);
        assertThat(r.rejected()).as("imzasız eski satır + parça doğrulanamadı").isEqualTo(2);
        assertThat(r.dbUnavailable()).isFalse();
        assertThat(Files.exists(fallback)).isFalse();
        assertThat(Files.exists(AuditService.siblingWith(fallback, AuditService.REPLAY_SUFFIX))).isFalse();
        assertThat(failedLines()).hasSize(2).anyMatch(l -> l.contains("veli")).anyMatch(l -> l.contains("parça"));
        assertThat(svc.pendingFallbackAuditCount()).as("yalnız .failed satırları bekler").isEqualTo(2L);

        List<String> types = jdbc.queryForList("SELECT event_type FROM audit_log WHERE seq IS NOT NULL ORDER BY seq", String.class);
        assertThat(types).containsExactly("SYSTEM_STARTUP", "USER_UPDATE", "LOGIN");
        AuditLog replayed = repo.findById(jdbc.queryForObject(
                "SELECT id FROM audit_log WHERE event_type = 'USER_UPDATE'", Long.class)).orElseThrow();
        assertThat(replayed.getEventTime()).isNotEqualTo(EVENT_TIME);
        assertThat(replayed.getActorId()).isEqualTo(42L);
        assertThat(replayed.getIpAddress()).isEqualTo("10.1.2.3");
        Map<String, Object> d = parse(replayed.getDetail());
        assertThat(d).containsEntry("username", "ali").containsEntry("path", "C:\\temp")
                .containsEntry("replayed_from_fallback", true)
                .containsEntry("original_event_time", EVENT_TIME)
                .containsEntry("fallback_id", parse(v2a).get("fid"))
                .doesNotContainKey("fallback_legacy");
        assertThat(svc.verifyChain().ok()).as("aktarılan satırlar zincir kuralıyla yazıldı").isTrue();

        // İkinci tur: iş yok, tekrar yazım yok
        assertThat(svc.replayFallback().replayed()).isZero();
        assertThat(auditRows()).isEqualTo(3);
    }

    @Test
    @DisplayName("B7: önceki tur nişanı yazıp dosyayı silemeden durduysa (pod çöktü) aynı dosya yeniden okunur ama nişanlı satır TEKRAR yazılmaz / .failed'a TEKRAR eklenmez")
    void replay_markerPreventsDuplicateAfterCrash() throws Exception {
        String a = line(event("USER_UPDATE", "admin"));
        String b = line(event("LOGIN", "ali"));
        String garbage = "bozuk satır — JSON değil";
        Path replay = AuditService.siblingWith(fallback, AuditService.REPLAY_SUFFIX);
        writeLines(replay, a, b, garbage, garbage);   // aynı içerikli iki bozuk satır = iki ayrı kayıt
        AuditService.ReplayResult first = svc.replayFallback();
        assertThat(first.replayed()).isEqualTo(2);
        assertThat(first.parked()).isEqualTo(2);
        // Çökme benzetimi: aynı .replay içeriği geri gelir (silme hiç olmamış gibi)
        writeLines(replay, a, b, garbage, garbage);
        AuditService.ReplayResult again = svc.replayFallback();
        assertThat(again.replayed()).isZero();
        assertThat(again.parked()).isZero();
        assertThat(again.skipped()).isEqualTo(4);
        assertThat(auditRows()).isEqualTo(2);
        assertThat(failedLines()).hasSize(2);
        assertThat(Files.exists(replay)).isFalse();
    }

    @Test
    @DisplayName("B7: aktarım sırasında yazılan yeni düşüş ayrı dosyaya gider; .replay bitmeden birincil dosya taşınmaz, sonraki tur onu da aktarır")
    void replay_newWritesGoToFreshFile() throws Exception {
        Path replay = AuditService.siblingWith(fallback, AuditService.REPLAY_SUFFIX);
        writeLines(replay, line(event("LOGIN", "a")));
        writeLines(fallback, line(event("LOGOUT", "b")));
        assertThat(svc.pendingFallbackAuditCount()).isEqualTo(2L);

        assertThat(svc.replayFallback().replayed()).isEqualTo(1);   // önce .replay
        assertThat(Files.exists(fallback)).isTrue();
        assertThat(svc.pendingFallbackAuditCount()).isEqualTo(1L);
        assertThat(svc.replayFallback().replayed()).isEqualTo(1);   // sonra birincil dosya
        assertThat(svc.pendingFallbackAuditCount()).isZero();
        assertThat(jdbc.queryForList("SELECT event_type FROM audit_log ORDER BY seq", String.class))
                .containsExactly("LOGIN", "LOGOUT");
    }

    @Test
    @DisplayName("B7: veritabanı okunamıyorsa tur hiçbir şeye dokunmaz — dosya taşınmaz, bekleyen sayısı aynı")
    void replay_dbDown_keepsFile() throws Exception {
        writeLines(fallback, line(event("LOGIN", "ali")));
        JdbcTemplate broken = mock(JdbcTemplate.class);
        when(broken.queryForObject(eq("SELECT 1"), eq(Integer.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        AuditService down = pod(broken);
        AuditService.ReplayResult r = down.replayFallback();
        assertThat(r.dbUnavailable()).isTrue();
        assertThat(r.replayed()).isZero();
        assertThat(Files.exists(fallback)).isTrue();
        assertThat(Files.exists(AuditService.siblingWith(fallback, AuditService.REPLAY_SUFFIX))).isFalse();
        assertThat(down.pendingFallbackAuditCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("B7: sağlıklı veritabanında yazılamayan (doğrulanmış) satır 3 turda .failed'a alınır; ardındaki satır aktarılır")
    void replay_contentFailure_parkedAfterThreeAttempts() throws Exception {
        // Sütuna sığmayan değer artık sığdırılıyor (fitColumns) — kalıcı içerik hatasını depo düzeyinde benzetiriz.
        AuditLogRepository failing = mock(AuditLogRepository.class, org.mockito.AdditionalAnswers.delegatesTo(repo));
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("kalıcı içerik hatası"))
                .when(failing).save(org.mockito.ArgumentMatchers.argThat(
                        (AuditLog x) -> x != null && "USER_DELETE".equals(x.getEventType())));
        svc = pod(jdbc, failing);
        writeLines(fallback, line(event("USER_DELETE", "admin")), line(event("LOGIN", "ali")));

        AuditService.ReplayResult r1 = svc.replayFallback();
        assertThat(r1.replayed()).isZero();
        assertThat(r1.dbUnavailable()).isFalse();
        AuditService.ReplayResult r2 = svc.replayFallback();
        assertThat(r2.replayed()).isZero();
        AuditService.ReplayResult r3 = svc.replayFallback();
        assertThat(r3.parked()).isEqualTo(1);
        assertThat(r3.rejected()).as("doğrulanmış satır — red değil, yazım hatası").isZero();
        assertThat(r3.replayed()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT event_type FROM audit_log", String.class)).containsExactly("LOGIN");
        assertThat(failedLines()).hasSize(1);
        assertThat(svc.pendingFallbackAuditCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("B7: aşırı uzun satır okunurken kesilir (bellek sınırlı) ve .failed'a alınır; dosya yoksa veritabanına gidilmez")
    void replay_oversizedLineParked_andNoFileNoDb() throws Exception {
        writeLines(fallback, "{\"v\":2,\"fid\":\"abc\",\"event_type\":\"X\",\"detail\":\""
                + "y".repeat(AuditService.REPLAY_MAX_LINE_CHARS + 10) + "\"}");
        AuditService.ReplayResult r = svc.replayFallback();
        assertThat(r.parked()).isEqualTo(1);
        assertThat(r.rejected()).isEqualTo(1);
        assertThat(r.replayed()).isZero();
        String failedLine = failedLines().get(0);
        assertThat(failedLine.length()).isLessThan(9000);

        // Dosya yok → altyapıya hiç dokunulmaz (bozuk JdbcTemplate çağrılsa istisna verirdi)
        JdbcTemplate untouched = mock(JdbcTemplate.class);
        when(untouched.queryForObject(any(String.class), eq(Integer.class))).thenThrow(new IllegalStateException("çağrılmamalı"));
        Files.deleteIfExists(AuditService.siblingWith(fallback, AuditService.FAILED_SUFFIX));
        assertThat(pod(untouched).replayFallback()).isEqualTo(AuditService.ReplayResult.NONE);
    }

    // ── Bağımsız inceleme (2026-11-26): imza ve alan doğrulaması ─────────────────────────────

    @Test
    @DisplayName("sahte satır: doğru biçimli ama imzasız LOGIN_FAILED zincire YAZILMAZ, kilit sayacını beslemez; gerçek satırın fid'ini sahiplenemez")
    void forgedUnsignedLine_neverReachesChain() throws Exception {
        AuditLog real = event("LOGIN", "ali");
        AuditService.fitColumns(real);
        String realLine = svc.fallbackLine(real);
        Map<String, Object> forged = new LinkedHashMap<>(parse(realLine));
        forged.remove("sig");
        forged.put("event_type", "LOGIN_FAILED");
        forged.put("actor", "kurban");
        forged.put("outcome", "FAILURE");
        String forgedLine = JSON.writeValueAsString(forged);   // AYNI fid, imza yok
        writeLines(fallback, forgedLine, realLine);

        AuditService.ReplayResult r = svc.replayFallback();

        assertThat(r.replayed()).as("yalnız imzalı gerçek satır").isEqualTo(1);
        assertThat(r.rejected()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT event_type FROM audit_log", String.class)).containsExactly("LOGIN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE actor = 'kurban'", Integer.class)).isZero();
        assertThat(failedLines()).hasSize(1).first().asString().contains("kurban");
        assertThat(svc.pendingFallbackAuditCount()).isEqualTo(1L);
        // Sahte satır kendi beyan ettiği fid ile nişan alamaz: gerçek satırın nişanı "yazıldı" (1), sahteninki içerik özeti.
        assertThat(jdbc.queryForObject("SELECT rows_affected FROM schema_patch_markers WHERE patch_key = ?", Integer.class,
                AuditService.MARKER_PREFIX + parse(realLine).get("fid")))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class,
                AuditService.MARKER_PREFIX + "raw-" + AuditService.sha256(forgedLine) + "-0")).isEqualTo(1);
    }

    @Test
    @DisplayName("kurcalanmış imza: gövdede tek değişiklik, kısaltılmış / geçersiz karakterli imza, başka anahtarla imza → reddedilir")
    void tamperedSignature_rejected() throws Exception {
        String good = line(event("USER_UPDATE", "admin"));
        String bodyChanged = good.replace("\"actor\":\"admin\"", "\"actor\":\"root\"");
        assertThat(bodyChanged).isNotEqualTo(good);
        String sig = (String) parse(good).get("sig");
        String shortSig = good.replace(sig, sig.substring(1));
        String badChars = good.replace(sig, "!" + sig.substring(1));
        AuditService other = pod(jdbc);
        other.setFallbackKeys(OTHER_KEY, null);
        AuditLog e = event("USER_UPDATE", "admin");
        AuditService.fitColumns(e);
        String otherKeyLine = other.fallbackLine(e);

        assertThat(svc.checkFallbackSignature(good).problem()).isNull();
        assertThat(svc.checkFallbackSignature(bodyChanged).problem()).isEqualTo("bad_signature");
        assertThat(svc.checkFallbackSignature(shortSig).problem()).isEqualTo("bad_signature");
        assertThat(svc.checkFallbackSignature(badChars).problem()).isEqualTo("bad_signature");
        assertThat(svc.checkFallbackSignature(otherKeyLine).problem()).isEqualTo("bad_signature");
        assertThat(svc.checkFallbackSignature(AuditService.fallbackJson(e)).problem()).isEqualTo("unsigned");
        assertThat(svc.checkFallbackSignature("{}").problem()).isEqualTo("unsigned");
        assertThat(svc.checkFallbackSignature("").problem()).isEqualTo("unsigned");

        writeLines(fallback, bodyChanged, shortSig, badChars, otherKeyLine);
        AuditService.ReplayResult r = svc.replayFallback();
        assertThat(r.replayed()).isZero();
        assertThat(r.rejected()).isEqualTo(4);
        assertThat(auditRows()).isZero();
        assertThat(failedLines()).hasSize(4);
    }

    @Test
    @DisplayName("anahtar döndürme: önceki anahtarla imzalanmış satır kabul edilir, yeni satır güncel anahtarla imzalanır")
    void previousKeyAccepted_afterRotation() throws Exception {
        AuditService beforeRotation = pod(jdbc);
        beforeRotation.setFallbackKeys(OTHER_KEY, null);
        AuditLog e = event("LOGIN", "ali");
        AuditService.fitColumns(e);
        String oldLine = beforeRotation.fallbackLine(e);

        AuditService rotated = pod(jdbc);
        rotated.setFallbackKeys(KEY, OTHER_KEY);
        assertThat(rotated.checkFallbackSignature(oldLine).problem()).isNull();
        String newLine = rotated.fallbackLine(e);
        assertThat(svc.checkFallbackSignature(newLine).problem()).as("güncel anahtarla imzalı").isNull();
        assertThat(beforeRotation.checkFallbackSignature(newLine).problem()).isEqualTo("bad_signature");

        writeLines(fallback, oldLine);
        assertThat(rotated.replayFallback().replayed()).isEqualTo(1);
        assertThat(auditRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("anahtar SITE_MONITOR_SECRET_KEY alt anahtarından türetilir (önceki de); anahtar yoksa süreç başına rastgele — başka süreç doğrulayamaz")
    void keysComeFromSecretCipher() {
        SecretCipher cipher = mock(SecretCipher.class);
        when(cipher.hmacSubKey(AuditService.FALLBACK_SIG_PURPOSE)).thenReturn(KEY);
        when(cipher.previousHmacSubKey(AuditService.FALLBACK_SIG_PURPOSE)).thenReturn(OTHER_KEY);
        AuditService derived = podNoKeys(jdbc, repo);
        ReflectionTestUtils.setField(derived, "secretCipher", cipher);
        AuditLog e = event("LOGIN", "ali");
        AuditService.fitColumns(e);
        assertThat(svc.checkFallbackSignature(derived.fallbackLine(e)).problem()).isNull();
        AuditService old = pod(jdbc);
        old.setFallbackKeys(OTHER_KEY, null);
        assertThat(derived.checkFallbackSignature(old.fallbackLine(e)).problem()).as("önceki anahtar").isNull();

        SecretCipher none = mock(SecretCipher.class);   // anahtar ayarlı değil → null
        AuditService a = podNoKeys(jdbc, repo);
        AuditService b = podNoKeys(jdbc, repo);
        ReflectionTestUtils.setField(a, "secretCipher", none);
        ReflectionTestUtils.setField(b, "secretCipher", none);
        String fromA = a.fallbackLine(e);
        assertThat(a.checkFallbackSignature(fromA).problem()).isNull();
        assertThat(b.checkFallbackSignature(fromA).problem()).isEqualTo("bad_signature");
        assertThat(podNoKeys(jdbc, repo).checkFallbackSignature(fromA).problem())
                .as("SecretCipher fasulyesi yok").isEqualTo("bad_signature");
    }

    @Test
    @DisplayName("alan doğrulaması: bilinmeyen olay türü / sonuç / anahtar, yanlış sürüm, sütunu aşan alan, yanlış tip → kod; yazıcının kendi satırı geçer")
    void validateReplayFields_table() {
        Map<String, Object> ok = bodyOf(fitted(event("LOGIN_FAILED", "ali")));
        assertThat(AuditService.validateReplayFields(ok)).isNull();

        assertThat(AuditService.validateReplayFields(with(ok, "event_type", "NOT_A_REAL_EVENT"))).isEqualTo("event_type");
        assertThat(AuditService.validateReplayFields(with(ok, "event_type", null))).isEqualTo("event_type");
        assertThat(AuditService.validateReplayFields(with(ok, "outcome", "MAYBE"))).isEqualTo("outcome");
        assertThat(AuditService.validateReplayFields(with(ok, "outcome", null))).as("sonuç boş olabilir").isNull();
        assertThat(AuditService.validateReplayFields(with(ok, "seq_locked", true))).isEqualTo("unknown_field");
        assertThat(AuditService.validateReplayFields(with(ok, "row_hash", "x"))).isEqualTo("unknown_field");
        assertThat(AuditService.validateReplayFields(with(ok, "v", 1))).isEqualTo("version");
        assertThat(AuditService.validateReplayFields(without(ok, "v"))).isEqualTo("version");
        assertThat(AuditService.validateReplayFields(with(ok, "fid", "../../x"))).isEqualTo("fid");
        assertThat(AuditService.validateReplayFields(with(ok, "event_time", 5))).isEqualTo("event_time");
        assertThat(AuditService.validateReplayFields(with(ok, "actor_id", "42"))).isEqualTo("actor_id");
        assertThat(AuditService.validateReplayFields(with(ok, "actor", "a".repeat(101)))).isEqualTo("actor");
        assertThat(AuditService.validateReplayFields(with(ok, "actor", "a".repeat(100)))).isNull();
        assertThat(AuditService.validateReplayFields(with(ok, "resource_id", "r".repeat(201)))).isEqualTo("resource_id");
        assertThat(AuditService.validateReplayFields(with(ok, "user_agent", "u".repeat(AuditLog.USER_AGENT_MAX + 1))))
                .isEqualTo("user_agent");
        assertThat(AuditService.validateReplayFields(with(ok, "pod", "p".repeat(AuditService.FALLBACK_POD_MAX + 1))))
                .isEqualTo("pod");
        assertThat(AuditService.validateReplayFields(with(ok, "detail", Map.of("a", 1)))).isEqualTo("detail");

        // Tek kaynak: alan sınırı sütun uzunluğuyla aynı (yazıcı sığdırır → kendi satırımız asla reddedilmez)
        for (Map.Entry<String, Integer> cap : AuditService.REPLAY_STRING_CAPS.entrySet()) {
            if (AuditService.FIT_LENGTHS.containsKey(cap.getKey())) {
                assertThat(cap.getValue()).as(cap.getKey()).isEqualTo(AuditService.FIT_LENGTHS.get(cap.getKey()));
            }
        }
    }

    @Test
    @DisplayName("anahtarla imzalanmış ama geçersiz alanlı satır (bilinmeyen tür, bilinmeyen anahtar, sütunu aşan alan, eski sürüm, derin iç içe) zincire yazılmaz")
    void signedButInvalidFields_parked() throws Exception {
        Map<String, Object> base = bodyOf(fitted(event("USER_UPDATE", "admin")));
        String unknownType = signedBody(with(freshFid(base), "event_type", "NOT_A_REAL_EVENT"));
        String unknownField = signedBody(with(freshFid(base), "seq_locked", true));
        String oversizedField = signedBody(with(freshFid(base), "actor", "a".repeat(101)));
        String legacyVersion = signedBody(with(freshFid(base), "v", 1));
        String deep = svc.signFallbackBody(JSON.writeValueAsString(freshFid(base))
                .replaceFirst("\\}$", ",\"x\":{\"a\":{\"b\":{\"c\":1}}}}"));
        String valid = signedBody(freshFid(base));
        writeLines(fallback, unknownType, unknownField, oversizedField, legacyVersion, deep, valid);

        AuditService.ReplayResult r = svc.replayFallback();
        assertThat(r.replayed()).isEqualTo(1);
        assertThat(r.rejected()).isEqualTo(5);
        assertThat(auditRows()).isEqualTo(1);
        assertThat(failedLines()).hasSize(5);
        assertThat(svc.pendingFallbackAuditCount()).isEqualTo(5L);
    }

    @Test
    @DisplayName("eski satır (önceki sürümün imzasız v1 / v2 satırı) zincire yazılmaz — .failed'a gider, bekleyen sayılır, WARN")
    void legacyUnsignedLines_parked() throws Exception {
        String v1 = "{\"event_time\":\"" + EVENT_TIME + "\",\"event_type\":\"LOGIN_FAILED\",\"actor\":\"veli\","
                + "\"resource_type\":\"\",\"resource_id\":\"\",\"outcome\":\"FAILURE\"}";
        String v2Unsigned = AuditService.fallbackJson(fitted(event("LOGIN", "ali")));
        writeLines(fallback, v1, v2Unsigned);
        AuditService.ReplayResult r = svc.replayFallback();
        assertThat(r.replayed()).isZero();
        assertThat(r.rejected()).isEqualTo(2);
        assertThat(r.parked()).isEqualTo(2);
        assertThat(auditRows()).isZero();
        assertThat(failedLines()).hasSize(2);
        assertThat(svc.pendingFallbackAuditCount()).isEqualTo(2L);
        assertThat(Files.exists(fallback)).isFalse();
    }

    @Test
    @DisplayName("eski imzalı satır: asıl zamanı 60 günden eski (nişanlar 90 günde budanır) ya da okunamayan satır yeniden zincire giremez")
    void staleSignedLine_parked() throws Exception {
        AuditLog old = event("LOGIN", "ali");
        old.setEventTime(ISO.format(Instant.now().minus(Duration.ofDays(AuditService.REPLAY_LINE_MAX_AGE_DAYS + 10))));
        AuditLog unreadable = event("LOGOUT", "ali");
        unreadable.setEventTime("dün öğlen");
        AuditLog young = event("USER_UPDATE", "ali");
        young.setEventTime(ISO.format(Instant.now().minus(Duration.ofDays(AuditService.REPLAY_LINE_MAX_AGE_DAYS - 1))));
        writeLines(fallback, line(old), line(unreadable), line(young));
        AuditService.ReplayResult r = svc.replayFallback();
        assertThat(r.replayed()).isEqualTo(1);
        assertThat(r.rejected()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT event_type FROM audit_log", String.class)).containsExactly("USER_UPDATE");
    }

    @Test
    @DisplayName("ayrıştırıcı sınırları yazıcının meşru satırını reddetmez: satır sınırına yakın uzun ayrıntı aktarılır")
    void longDetailInsideLineLimit_replayed() throws Exception {
        AuditLog e = event("USER_UPDATE", "admin");
        AuditService.fitColumns(e);
        e.setDetail("d".repeat(AuditService.REPLAY_MAX_LINE_CHARS - 2000));   // satır sınırına yakın
        String big = svc.fallbackLine(e);
        assertThat(big.length()).isLessThan(AuditService.REPLAY_MAX_LINE_CHARS);
        writeLines(fallback, big);
        AuditService.ReplayResult r = svc.replayFallback();
        assertThat(r.replayed()).as("sınır içindeki uzun ayrıntı geçerli").isEqualTo(1);
        assertThat(r.rejected()).isZero();
    }

    // ── Hata avı (2026-11-26): sütun sınırı ───────────────────────────────────────────────

    @Test
    @DisplayName("yazıcı yolu sütuna sığmayan değeri SIĞDIRARAK imzalar (satır geçer); sığdırılmamış imzalı satır reddedilir")
    void replay_tooLongValue_fittedByWriter() throws Exception {
        AuditLog bad = event("USER_UPDATE", "admin");
        bad.setActorRole("X".repeat(40));          // actor_role VARCHAR(20)
        bad.setFailureReason("y".repeat(300));     // failure_reason VARCHAR(200)
        String unfitted = svc.fallbackLine(bad);   // yazıcıyı atlayan satır
        writeLines(fallback, line(bad), unfitted); // line() = yazıcı gibi önce sığdırır

        AuditService.ReplayResult r = svc.replayFallback();
        assertThat(r.replayed()).isEqualTo(1);
        assertThat(r.rejected()).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT actor_role, failure_reason FROM audit_log");
        assertThat((String) row.get("actor_role")).hasSize(20).endsWith("…");
        assertThat((String) row.get("failure_reason")).hasSize(200).endsWith("…");
        assertThat(svc.verifyChain().ok()).isTrue();
    }

    @Test
    @DisplayName("gerçek yazıcı yolu (writeFallback): sütunu aşan değer imzadan ÖNCE sığdırılır, satır doğrulanır ve aktarılır")
    void writerPath_fitsThenSigns_lineReplays() throws Exception {
        AuditLog bad = event("USER_UPDATE", "admin");
        bad.setActorRole("X".repeat(40));
        bad.setResourceId("r".repeat(300));
        ReflectionTestUtils.invokeMethod(svc, "writeFallback", bad, new RuntimeException("db yok"));
        assertThat(svc.checkFallbackSignature(Files.readAllLines(fallback).get(0)).problem()).isNull();

        AuditService.ReplayResult r = svc.replayFallback();
        assertThat(r.replayed()).isEqualTo(1);
        assertThat(r.rejected()).isZero();
        Map<String, Object> row = jdbc.queryForMap("SELECT actor_role, resource_id FROM audit_log");
        assertThat((String) row.get("actor_role")).hasSize(20).endsWith("…");
        assertThat((String) row.get("resource_id")).hasSize(200).endsWith("…");
    }

    @Test
    @DisplayName("ana yazım yolu da sığdırır: uzun kaynak kimliği satırı fallback dosyasına göndermez, zincir sağlam")
    void mainPath_tooLongValue_fittedNotFallback() {
        svc.recordSystemEvent("SYS_FIT_TEST", "USER", "r".repeat(300), "{}");
        assertThat(jdbc.queryForObject("SELECT LENGTH(resource_id) FROM audit_log WHERE event_type = 'SYS_FIT_TEST'",
                Integer.class)).isEqualTo(200);
        assertThat(Files.exists(fallback)).isFalse();
        assertThat(svc.verifyChain().ok()).isTrue();
    }

    @Test
    @DisplayName(".failed nişanı yarışta başkasınca yazıldıysa (yinelenen anahtar) tur veritabanı hatası saymaz, dosya biter")
    void park_markerRace_isNotADatabaseFailure() throws Exception {
        String garbage = "bozuk satır — JSON değil";
        String marker = AuditService.MARKER_PREFIX + "raw-" + AuditService.sha256(garbage) + "-0";
        jdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                marker, "2026-11-26T00:00:00", -1);
        // Yarış benzetimi: nişan okumaları "yok" der, INSERT yinelenen anahtara çarpar.
        JdbcTemplate racy = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
                if (sql.startsWith("SELECT patch_key FROM schema_patch_markers")) return List.of();
                return super.queryForList(sql, type, args);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T queryForObject(String sql, Class<T> type, Object... args) {
                if (sql.startsWith("SELECT COUNT(*) FROM schema_patch_markers")) return (T) Integer.valueOf(0);
                return super.queryForObject(sql, type, args);
            }
        };
        svc = pod(racy);
        writeLines(fallback, garbage);

        AuditService.ReplayResult r = svc.replayFallback();
        assertThat(r.dbUnavailable()).isFalse();
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(Files.exists(AuditService.siblingWith(fallback, AuditService.REPLAY_SUFFIX))).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class,
                marker)).isEqualTo(1);
    }

    @Test
    @DisplayName("kapı: sığdırma uzunlukları AuditLog @Column(length) ile birebir; fitColumns her alanı uygular")
    void fitLengthsMatchColumns() throws Exception {
        Map<String, Integer> fromEntity = new java.util.TreeMap<>();
        for (java.lang.reflect.Field f : AuditLog.class.getDeclaredFields()) {
            jakarta.persistence.Column c = f.getAnnotation(jakarta.persistence.Column.class);
            if (c == null || f.getType() != String.class || !c.columnDefinition().isEmpty()) continue;
            if (c.name().equals("row_hash") || c.name().equals("prev_hash")) continue;   // hesaplanan değerler
            fromEntity.put(c.name(), c.length());
        }
        assertThat(new java.util.TreeMap<>(AuditService.FIT_LENGTHS)).isEqualTo(fromEntity);

        AuditLog e = new AuditLog();
        for (java.lang.reflect.Field f : AuditLog.class.getDeclaredFields()) {
            jakarta.persistence.Column c = f.getAnnotation(jakarta.persistence.Column.class);
            if (c == null || !AuditService.FIT_LENGTHS.containsKey(c.name())) continue;
            f.setAccessible(true);
            f.set(e, "z".repeat(400));
        }
        AuditService.fitColumns(e);
        for (java.lang.reflect.Field f : AuditLog.class.getDeclaredFields()) {
            jakarta.persistence.Column c = f.getAnnotation(jakarta.persistence.Column.class);
            if (c == null || !AuditService.FIT_LENGTHS.containsKey(c.name())) continue;
            f.setAccessible(true);
            assertThat(((String) f.get(e)).length()).as(c.name()).isEqualTo(AuditService.FIT_LENGTHS.get(c.name()));
        }
        assertThat(AuditService.fitCol("ab😀cd", 4)).isEqualTo("ab…");   // vekil çift bölünmez
        assertThat(AuditService.fitCol("abc", 3)).isEqualTo("abc");
        assertThat(AuditService.fitCol(null, 3)).isNull();
    }

    // ── yardımcılar ──────────────────────────────────────────────────────────────────

    private static AuditLog fitted(AuditLog e) {
        AuditService.fitColumns(e);
        return e;
    }

    private static Map<String, Object> with(Map<String, Object> m, String k, Object v) {
        Map<String, Object> out = new LinkedHashMap<>(m);
        out.put(k, v);
        return out;
    }

    private static Map<String, Object> without(Map<String, Object> m, String k) {
        Map<String, Object> out = new LinkedHashMap<>(m);
        out.remove(k);
        return out;
    }

    /** Aynı gövdeyi birden çok satırda kullanırken her satıra kendi fid'i (yoksa ilk satırın nişanı diğerlerini atlatır). */
    private static Map<String, Object> freshFid(Map<String, Object> m) {
        return with(m, "fid", java.util.UUID.randomUUID().toString());
    }
}
