package com.sitemonitor.service.watchdog;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.it.MultiPodTestSupport.Pod;
import com.sitemonitor.model.WatchdogState;
import com.sitemonitor.repository.WatchdogStateRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.mail.WatchdogMail;
import com.sitemonitor.service.schedule.ClusterLockService;
import com.sitemonitor.service.schedule.ClusterScheduleService;
import com.sitemonitor.service.watchdog.WatchdogTransition.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Kendi kendini izleme N pod'da (2026-10-21, çok pod R1-A, MP-09): veritabanı çökükken "veritabanı çöktü" bildirimini
 * yalnız doğrulanmış tarama lideri gönderir (eskiden HER pod: N e-posta / push / webhook), takipçi yalnız lider ölmüş
 * görünüyorsa ve bekleme süresinden sonra; DB dönünce TEK "düzeldi" — iki pod duyurmuş olsa bile önce koşullu damga.
 * Pod'lar gerçek {@code SchedulerService} (kira, kilit — {@link MultiPodTestSupport}), durum tablosu ortak H2'de.
 */
class WatchdogMultiPodTest {

    private static final long SEC = 1_000L;
    private static final long MIN = 60 * SEC;

    static final String DDL = "CREATE TABLE IF NOT EXISTS watchdog_state(check_key VARCHAR(40) NOT NULL PRIMARY KEY, "
            + "status VARCHAR(12) NOT NULL, last_result VARCHAR(12), severity VARCHAR(12), since VARCHAR(19), "
            + "problem_streak INT, ok_streak INT, facts VARCHAR(4000), evaluated_at VARCHAR(19), notified_at VARCHAR(19), "
            + "notified_kind VARCHAR(20))";

    final MultiPodTestSupport mp = MultiPodTestSupport.create();
    final AtomicBoolean dbDown = new AtomicBoolean();
    final Map<WatchdogCheck, Result> results = new ConcurrentHashMap<>();
    /** Gönderilen bildirimler: "sahip:TÜR". */
    final List<String> sent = new CopyOnWriteArrayList<>();
    WatchdogStateRepository repo;
    WatchdogProbes probes;
    static final JdbcTemplate DOWN = Mockito.mock(JdbcTemplate.class, inv -> {
        throw new DataAccessResourceFailureException("veritabanına ulaşılamıyor");
    });

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mp.jdbc().execute(DDL);
        repo = mock(WatchdogStateRepository.class);
        when(repo.findAll()).thenAnswer(i -> {
            if (dbDown.get()) throw new DataAccessResourceFailureException("veritabanına ulaşılamıyor");
            return readAll();
        });
        when(repo.saveAll(any())).thenAnswer(i -> {
            if (dbDown.get()) throw new DataAccessResourceFailureException("veritabanına ulaşılamıyor");
            writeAll((Collection<WatchdogState>) i.getArgument(0));
            return null;
        });
        for (WatchdogCheck c : WatchdogCheck.values()) results.put(c, Result.OK);
        probes = mock(WatchdogProbes.class);
        when(probes.runAll(any())).thenAnswer(i -> {
            List<WatchdogProbes.Probe> out = new ArrayList<>();
            for (WatchdogCheck c : WatchdogCheck.values()) out.add(probe(c));
            return out;
        });
        when(probes.run(eq(WatchdogCheck.DATABASE), any())).thenAnswer(i -> probe(WatchdogCheck.DATABASE));
    }

    @AfterEach
    void close() {
        mp.close();
    }

    WatchdogProbes.Probe probe(WatchdogCheck c) {
        Result r = results.get(c);
        String sev = c == WatchdogCheck.DATABASE && r == Result.PROBLEM ? "CRITICAL" : c.severity().name();
        return new WatchdogProbes.Probe(c, r, sev, Map.of("status", r == Result.PROBLEM ? "DOWN" : "UP"));
    }

    List<WatchdogState> readAll() {
        return mp.jdbc().query("SELECT * FROM watchdog_state", (rs, n) -> {
            WatchdogState s = new WatchdogState();
            s.setCheckKey(rs.getString("check_key"));
            s.setStatus(rs.getString("status"));
            s.setLastResult(rs.getString("last_result"));
            s.setSeverity(rs.getString("severity"));
            s.setSince(rs.getString("since"));
            s.setProblemStreak(rs.getInt("problem_streak"));
            s.setOkStreak(rs.getInt("ok_streak"));
            s.setFacts(rs.getString("facts"));
            s.setEvaluatedAt(rs.getString("evaluated_at"));
            s.setNotifiedAt(rs.getString("notified_at"));
            s.setNotifiedKind(rs.getString("notified_kind"));
            return s;
        });
    }

    void writeAll(Collection<WatchdogState> states) {
        for (WatchdogState s : states) {
            mp.jdbc().update("DELETE FROM watchdog_state WHERE check_key = ?", s.getCheckKey());
            mp.jdbc().update("INSERT INTO watchdog_state(check_key, status, last_result, severity, since, problem_streak, "
                            + "ok_streak, facts, evaluated_at, notified_at, notified_kind) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    s.getCheckKey(), s.getStatus(), s.getLastResult(), s.getSeverity(), s.getSince(), s.getProblemStreak(),
                    s.getOkStreak(), s.getFacts(), s.getEvaluatedAt(), s.getNotifiedAt(), s.getNotifiedKind());
        }
    }

    /** Pod'un izleyicisi: kendi bildiricisi (gönderenin kim olduğu sayılsın), ortak durum tablosu ve olasılık ölçümü. */
    WatchdogService watchdog(String owner, Pod pod) {
        WatchdogNotifier notifier = mock(WatchdogNotifier.class);
        when(notifier.send(anyList(), anyBoolean())).thenAnswer(i -> {
            List<WatchdogMail.Item> items = i.getArgument(0);
            for (WatchdogMail.Item it : items) sent.add(owner + ":" + it.kind());
            return new WatchdogNotifier.Report(WatchdogNotifier.Outcome.DELIVERED, "SENT", "SKIPPED", "SKIPPED", 1);
        });
        WatchdogService w = new WatchdogService(repo, probes, notifier, mp.mock(AppSettingsService.class));
        if (pod != null) w.setScheduler(pod.scheduler());
        w.setJdbcTemplate(mp.jdbc());
        return w;
    }

    Instant now() {
        return Instant.ofEpochMilli(mp.now());
    }

    void renew(Pod p) {
        ReflectionTestUtils.invokeMethod(p.scheduler(), "renewSweepLeadership");
    }

    /** Pod'un veritabanı bağlantısı kesildi: kilit ve kira sorguları geçici hata verir. */
    void cut(Pod p) {
        ReflectionTestUtils.setField(p.scheduler(), "lockService", new ClusterLockService(DOWN, p.owner(), mp.clockSupplier(), false));
        ReflectionTestUtils.setField(p.scheduler(), "clusterSchedule", new ClusterScheduleService(DOWN, p.owner()));
    }

    void restore(Pod p) {
        ReflectionTestUtils.setField(p.scheduler(), "lockService", p.locks());
        ReflectionTestUtils.setField(p.scheduler(), "clusterSchedule", p.schedule());
    }

    long count(String kind) {
        return sent.stream().filter(s -> s.endsWith(":" + kind)).count();
    }

    @Test
    @DisplayName("MP-09: 3 pod, veritabanı 10 dk çökük → TEK 'veritabanı çöktü' (doğrulanmış lider); dönünce TEK 'düzeldi'")
    void threePods_dbDown_oneAlertOneRecover() {
        Pod p1 = mp.pod(1);
        Pod p2 = mp.pod(2);
        Pod p3 = mp.pod(3);
        assertThat(p1.scheduler().isSweepLeaderNow()).isTrue();
        renew(p2);
        renew(p3);
        WatchdogService w1 = watchdog(p1.owner(), p1);
        WatchdogService w2 = watchdog(p2.owner(), p2);
        WatchdogService w3 = watchdog(p3.owner(), p3);
        List<Pod> pods = List.of(p1, p2, p3);
        List<WatchdogService> dogs = List.of(w1, w2, w3);
        for (WatchdogService w : dogs) w.tick(now());           // sağlıklı tur: biri değerlendirir, diğerleri RECENT
        assertThat(readAll()).hasSize(WatchdogCheck.values().length);

        dbDown.set(true);
        results.put(WatchdogCheck.DATABASE, Result.PROBLEM);
        pods.forEach(this::cut);
        for (int m = 1; m <= 10; m++) {
            mp.advance(MIN);
            pods.forEach(this::renew);
            for (WatchdogService w : dogs) w.tick(now());
        }
        assertThat(count("ALERT")).as("HATA ÖNCESİ: 3 pod → 3 bildirim").isEqualTo(1);
        assertThat(sent).containsExactly(p1.owner() + ":ALERT");

        dbDown.set(false);
        results.put(WatchdogCheck.DATABASE, Result.OK);
        pods.forEach(this::restore);
        for (int m = 11; m <= 15; m++) {
            mp.advance(MIN);
            pods.forEach(this::renew);
            w2.tick(now());                                      // takipçi önce değerlendirsin (sıra fark etmez)
            w1.tick(now());
            w3.tick(now());
        }
        assertThat(count("ALERT")).isEqualTo(1);
        assertThat(count("RECOVER")).as("tek 'düzeldi'").isEqualTo(1);
        WatchdogState db = readAll().stream().filter(s -> "database".equals(s.getCheckKey())).findFirst().orElseThrow();
        assertThat(db.getStatus()).isEqualTo("OK");
        assertThat(db.getNotifiedKind()).isEqualTo("RECOVER");
    }

    @Test
    @DisplayName("MP-09 takipçi yedeği: lider öldü (kira bayatladı) ve veritabanı çöktü → takipçi bekleme süresinden SONRA bir kez duyurur")
    void deadLeader_followerAnnouncesAfterWait() {
        Pod p1 = mp.pod(1);
        Pod p2 = mp.pod(2);
        assertThat(p1.scheduler().isSweepLeaderNow()).isTrue();
        mp.advance(100 * SEC);                                    // p1 öldü (artık yenilemiyor)
        renew(p2);                                                // takipçi bayat kirayı gördü: kalan 80 sn < TTL/2
        WatchdogService w2 = watchdog(p2.owner(), p2);
        mp.advance(10 * SEC);

        dbDown.set(true);
        results.put(WatchdogCheck.DATABASE, Result.PROBLEM);
        cut(p2);
        for (int m = 1; m <= 4; m++) {                            // gözlemden 110..340 sn sonra: bekleme (180 + 2×60 sn) sürüyor
            mp.advance(MIN);
            renew(p2);
            w2.tick(now());
        }
        assertThat(sent).as("bekleme dolmadan duyurulmaz").isEmpty();
        for (int m = 5; m <= 10; m++) {
            mp.advance(MIN);
            renew(p2);
            w2.tick(now());
        }
        assertThat(sent).containsExactly(p2.owner() + ":ALERT");
    }

    @Test
    @DisplayName("MP-09: canlı lider varken takipçi taze kirayı görmüşse hiç duyurmaz (yarım saatlik kesintide bile)")
    void liveLeader_followerNeverAnnounces() {
        Pod p1 = mp.pod(1);
        Pod p2 = mp.pod(2);
        assertThat(p1.scheduler().isSweepLeaderNow()).isTrue();
        mp.advance(30 * SEC);
        renew(p2);
        WatchdogService w2 = watchdog(p2.owner(), p2);
        dbDown.set(true);
        results.put(WatchdogCheck.DATABASE, Result.PROBLEM);
        cut(p2);
        for (int m = 1; m <= 30; m++) {
            mp.advance(MIN);
            renew(p2);
            w2.tick(now());
        }
        assertThat(sent).isEmpty();
        assertThat(w2.followerMayAnnounce(now(), p2.scheduler().sweepLeaseObservation())).isFalse();
    }

    @Test
    @DisplayName("MP-09: iki pod (ör. eski sürüm) kesintiyi duyurmuşsa, DB dönünce 'düzeldi'yi koşullu damgayı kazanan TEK pod gönderir")
    void twoAnnouncers_singleRecoverViaStamp() {
        WatchdogService a = watchdog("eski-a", null);
        WatchdogService b = watchdog("eski-b", null);
        dbDown.set(true);
        results.put(WatchdogCheck.DATABASE, Result.PROBLEM);
        for (int m = 0; m < 2; m++) {
            a.evaluate(now(), false);
            b.evaluate(now(), false);
            mp.advance(MIN);
        }
        assertThat(count("ALERT")).isEqualTo(2);                 // küme bileşeni yok → ikisi de duyurdu (eski davranış)

        dbDown.set(false);
        results.put(WatchdogCheck.DATABASE, Result.OK);
        watchdog("yeni-x", null).evaluate(now(), false);          // başka pod sağlıklı satırı yazdı
        mp.advance(10 * SEC);
        Instant t = now();
        mp.race(() -> a.evaluate(t, false), () -> b.evaluate(t, false));
        assertThat(count("RECOVER")).as("HATA ÖNCESİ: iki 'düzeldi'").isEqualTo(1);
        WatchdogState db = readAll().stream().filter(s -> "database".equals(s.getCheckKey())).findFirst().orElseThrow();
        assertThat(db.getNotifiedKind()).isEqualTo("RECOVER");
        mp.advance(MIN);
        a.evaluate(now(), false);
        b.evaluate(now(), false);
        assertThat(count("RECOVER")).isEqualTo(1);
    }

    @Test
    @DisplayName("CORE-1: takipçinin kendi isSweepLeader() yoklaması (yenileyiciden 40–55 sn sonra) gözlem anını kaydırmaz → "
            + "canlı liderin taze kirası 'bayat' görünmez; 30 dk kesintide takipçi duyurmaz, yalnız lider bir kez")
    void followerOwnLeaseChecksDoNotFakeStaleLease() {
        Pod p1 = mp.pod(1);
        Pod p2 = mp.pod(2);
        assertThat(p1.scheduler().isSweepLeaderNow()).isTrue();     // t=0: kira 180 sn
        WatchdogService w1 = watchdog(p1.owner(), p1);
        WatchdogService w2 = watchdog(p2.owner(), p2);
        for (int offset = 40; offset <= 55; offset += 5) {
            renew(p1);                                               // canlı lider yeniler (bitiş = şimdi + 180 sn)
            mp.advance(50 * SEC);
            renew(p2);                                               // takipçinin yenileyicisi kirayı okur: kalan 130 sn
            mp.advance(offset * SEC);                                // ÖNCE: anı buraya kaydırıyordu → kalan 130 − offset < 90
            assertThat(p2.scheduler().isSweepLeaderNow()).isFalse(); // takipçinin kendi yoklaması (tarama, kutu, kapı)
            var obs = p2.scheduler().sweepLeaseObservation();
            assertThat(obs.leaseUntilMs() - obs.observedAtMs()).as("gözlem çifti birlikte (bitiş, okunduğu an)")
                    .isGreaterThanOrEqualTo(obs.ttlMs() / 2);
        }
        dbDown.set(true);
        results.put(WatchdogCheck.DATABASE, Result.PROBLEM);
        cut(p1);
        cut(p2);
        for (int m = 1; m <= 30; m++) {
            mp.advance(MIN);
            renew(p1);
            renew(p2);
            p2.scheduler().isSweepLeaderNow();
            w1.tick(now());
            w2.tick(now());
        }
        assertThat(sent).as("ÖNCE: takipçi 'bayat kira' sanıp ikinci kez duyuruyordu").containsExactly(p1.owner() + ":ALERT");
    }

    @Test
    @DisplayName("CORE-5: 'düzeldi' önce RECOVER_SENDING ile sahiplenilir, gönderim SIRASINDA satır 'gönderildi' demez; bitince RECOVER")
    void recoverIsClaimedThenFinalized() {
        WatchdogService a = watchdog("eski-a", null);
        dbDown.set(true);
        results.put(WatchdogCheck.DATABASE, Result.PROBLEM);
        a.evaluate(now(), false);
        mp.advance(MIN);
        a.evaluate(now(), false);                                    // kesintide duyurdu (bellek)
        dbDown.set(false);
        results.put(WatchdogCheck.DATABASE, Result.OK);
        watchdog("yeni-x", null).evaluate(now(), false);              // başka pod sağlıklı satırı yazdı
        List<String> kindDuringSend = new CopyOnWriteArrayList<>();
        WatchdogNotifier n = (WatchdogNotifier) ReflectionTestUtils.getField(a, "notifier");
        when(n.send(anyList(), anyBoolean())).thenAnswer(i -> {
            kindDuringSend.add(dbKind());
            return new WatchdogNotifier.Report(WatchdogNotifier.Outcome.DELIVERED, "SENT", "SKIPPED", "SKIPPED", 1);
        });
        mp.advance(10 * SEC);
        a.evaluate(now(), false);
        assertThat(kindDuringSend).containsExactly(WatchdogService.RECOVER_SENDING);
        assertThat(dbKind()).isEqualTo("RECOVER");
    }

    @Test
    @DisplayName("CORE-5: pod sahiplendikten sonra ÖLDÜ (RECOVER_SENDING kaldı) → bayatlayınca başka pod BİR KEZ yeniden gönderir; "
            + "iki pod yarışırsa tek gönderim; taze sahiplenmeye dokunulmaz; ikinci deneme de kalırsa RECOVER_UNKNOWN (bir daha yok)")
    void staleRecoverClaimIsRetriedOnceWithCap() {
        Instant t0 = now();
        healthyRows(t0.minusSeconds(30));
        setDb("RECOVER_SENDING", t0.minusSeconds(60));               // 1 dk önce sahiplenildi — taze
        WatchdogService a = watchdog("pod-a", null);
        WatchdogService b = watchdog("pod-b", null);
        a.evaluate(t0, false);
        assertThat(sent).as("taze sahiplenme gönderiliyor olabilir").isEmpty();

        setDb("RECOVER_SENDING", t0.minus(java.time.Duration.ofMinutes(10)));   // sahibi öldü
        mp.race(() -> a.evaluate(t0, false), () -> b.evaluate(t0, false));
        assertThat(count("RECOVER")).as("en az bir kez, tek pod").isEqualTo(1);
        assertThat(dbKind()).isEqualTo("RECOVER");
        a.evaluate(t0.plusSeconds(10), false);
        assertThat(count("RECOVER")).isEqualTo(1);

        setDb("RECOVER_RETRY", t0.minus(java.time.Duration.ofMinutes(10)));     // yeniden deneyen de öldü → tavan
        WatchdogNotifier na = (WatchdogNotifier) ReflectionTestUtils.getField(a, "notifier");
        WatchdogNotifier nb = (WatchdogNotifier) ReflectionTestUtils.getField(b, "notifier");
        mp.race(() -> a.evaluate(t0.plusSeconds(20), false), () -> b.evaluate(t0.plusSeconds(20), false));
        assertThat(count("RECOVER")).as("tavan: ikinci yeniden deneme yok").isEqualTo(1);
        assertThat(dbKind()).isEqualTo(WatchdogService.RECOVER_UNKNOWN);
        // R5: kararın kalıcı izi — notification_logs satırı TEK pod'dan, bir kez (SKIPPED: bildirim sağlığını bozmaz)
        org.mockito.ArgumentCaptor<String> status = org.mockito.ArgumentCaptor.forClass(String.class);
        int traces = org.mockito.Mockito.mockingDetails(na).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("recordDecision")).toList().size()
                + org.mockito.Mockito.mockingDetails(nb).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("recordDecision")).toList().size();
        assertThat(traces).isEqualTo(1);
        WatchdogNotifier traced = org.mockito.Mockito.mockingDetails(na).getInvocations().stream()
                .anyMatch(i -> i.getMethod().getName().equals("recordDecision")) ? na : nb;
        org.mockito.Mockito.verify(traced).recordDecision(org.mockito.ArgumentMatchers.contains("Veritabanı"), status.capture());
        assertThat(status.getValue()).startsWith("SKIPPED: düzelme bildirimi sonucu bilinmiyor");
        a.evaluate(t0.plusSeconds(30), false);
        b.evaluate(t0.plusSeconds(30), false);
        org.mockito.Mockito.verify(traced, org.mockito.Mockito.times(1)).recordDecision(anyString(), anyString());
    }

    /** Tüm kontroller sağlıklı (OK / OK) satırlarla, verilen değerlendirme anıyla. */
    void healthyRows(Instant evaluatedAt) {
        List<WatchdogState> rows = new ArrayList<>();
        for (WatchdogCheck c : WatchdogCheck.values()) {
            WatchdogState s = new WatchdogState();
            s.setCheckKey(c.key());
            s.setStatus("OK");
            s.setLastResult("OK");
            s.setEvaluatedAt(WatchdogTransition.ISO.format(evaluatedAt));
            rows.add(s);
        }
        writeAll(rows);
    }

    void setDb(String kind, Instant at) {
        mp.jdbc().update("UPDATE watchdog_state SET notified_kind = ?, notified_at = ? WHERE check_key = 'database'",
                kind, WatchdogTransition.ISO.format(at));
    }

    String dbKind() {
        return mp.jdbc().queryForObject("SELECT notified_kind FROM watchdog_state WHERE check_key = 'database'", String.class);
    }

    @Test
    @DisplayName("izleyici kilidi 2 dk ile alınır (ölen pod'un kilidi dış ucu 503'e düşürecek kadar beklemesin)")
    void watchdogLockTtl() {
        Pod p1 = mp.pod(1);
        WatchdogService w1 = watchdog(p1.owner(), p1);
        String[] until = new String[1];
        when(probes.runAll(any())).thenAnswer(i -> {
            until[0] = mp.jdbc().queryForObject("SELECT locked_until FROM scheduler_lock WHERE name = 'watchdog'", String.class);
            return List.of();
        });
        w1.tick(now());
        assertThat(until[0]).isEqualTo(WatchdogTransition.ISO.format(now().plus(WatchdogService.LOCK_TTL)));
    }
}
