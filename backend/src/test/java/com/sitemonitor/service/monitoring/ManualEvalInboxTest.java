package com.sitemonitor.service.monitoring;

import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Çok pod R1-B (2026-10-21, MP-14) — elle kontrol gelen kutusunun sahiplenme sözleşmesi (H2, PostgreSQL kipi; gerçek SQL).
 * İki "pod" aynı anda sahiplenirse her satır TEK pod'a düşer; kirası dolan satır başka pod'a geçer ve eski sahibin geç
 * gelen {@link ManualEvalInbox#take} çağrısı satırı işleyemez (tam bir kez).
 */
class ManualEvalInboxTest {

    MultiPodTestSupport mp;
    ManualEvalInbox a;
    ManualEvalInbox b;

    @BeforeEach
    void setUp() {
        mp = MultiPodTestSupport.create();
        mp.jdbc().execute(ManualEvalInbox.DDL);
        mp.jdbc().execute(ManualEvalInbox.INDEX_DDL);
        a = new ManualEvalInbox(mp.jdbc());
        b = new ManualEvalInbox(mp.jdbc());
        a.setClock(mp.clockSupplier());
        b.setClock(mp.clockSupplier());
    }

    @AfterEach
    void tearDown() {
        mp.close();
    }

    int rows() {
        Integer n = mp.jdbc().queryForObject("SELECT count(*) FROM monitor_eval_inbox", Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("enqueue → claim → take: satır sahiplenilir, alınınca silinir; ham sonuç aynen döner")
    void enqueueClaimTake() {
        assertThat(a.enqueue(ManualEvalInbox.TYPE_HTTP, 1L, true, Map.of("ok", true, "response_ms", 12L), "pod2")).isTrue();
        List<ManualEvalInbox.Row> got = a.claim("pod1", 25);
        assertThat(got).singleElement().satisfies(r -> {
            assertThat(r.type()).isEqualTo(ManualEvalInbox.TYPE_HTTP);
            assertThat(r.monitorId()).isEqualTo(1L);
            assertThat(r.manual()).isTrue();
            assertThat(r.checkedMs()).isEqualTo(MultiPodTestSupport.T0);
            assertThat(r.sourcePod()).isEqualTo("pod2");
            assertThat(ManualEvalInbox.parse(r.resultJson())).containsEntry("ok", true).containsEntry("response_ms", 12);
        });
        assertThat(b.claim("pod2", 25)).as("kira sürerken ikinci sahiplenme yok").isEmpty();
        assertThat(a.take(got.get(0))).isTrue();
        assertThat(a.take(got.get(0))).as("ikinci alma yok").isFalse();
        assertThat(rows()).isZero();
    }

    @Test
    @DisplayName("İki pod 60 satırı AYNI ANDA sahiplenir → her satır tam bir pod'a; take her satırda tek kez true")
    void racingClaims_disjoint_exactlyOnce() {
        for (int i = 0; i < 60; i++) a.enqueue(ManualEvalInbox.TYPE_PORT, i, true, Map.of("open", true), "pod3");
        Callable<List<ManualEvalInbox.Row>> ca = () -> drainAll(a, "pod1");
        Callable<List<ManualEvalInbox.Row>> cb = () -> drainAll(b, "pod2");
        List<List<ManualEvalInbox.Row>> got = mp.race(ca, cb);
        Set<Long> ids = new HashSet<>();
        int taken = 0;
        for (List<ManualEvalInbox.Row> l : got) {
            for (ManualEvalInbox.Row r : l) {
                assertThat(ids.add(r.id())).as("satır %d iki pod'a düştü", r.id()).isTrue();
                taken++;
            }
        }
        assertThat(taken).isEqualTo(60);
        assertThat(rows()).isZero();
    }

    private static List<ManualEvalInbox.Row> drainAll(ManualEvalInbox inbox, String owner) {
        List<ManualEvalInbox.Row> out = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            List<ManualEvalInbox.Row> batch = inbox.claim(owner, 7);
            if (batch.isEmpty()) break;
            for (ManualEvalInbox.Row r : batch) if (inbox.take(r)) out.add(r);
        }
        return out;
    }

    @Test
    @DisplayName("Kirası dolan satır başka pod'a geçer; eski sahibin geç gelen take'i satırı işleyemez")
    void expiredLeaseReclaimed_oldOwnerCannotTake() {
        a.enqueue(ManualEvalInbox.TYPE_DNS, 5L, true, Map.of("success", false), "pod2");
        ManualEvalInbox.Row old = a.claim("pod1", 25).get(0);
        mp.advance(ManualEvalInbox.LEASE_MS + 1);
        ManualEvalInbox.Row fresh = b.claim("pod2", 25).get(0);
        assertThat(fresh.id()).isEqualTo(old.id());
        assertThat(a.take(old)).isFalse();
        assertThat(b.take(fresh)).isTrue();
    }

    @Test
    @DisplayName("Tablo yoksa enqueue false döner (fırlatmaz); claim boş liste")
    void missingTableIsTolerated() {
        mp.jdbc().execute("DROP TABLE monitor_eval_inbox");
        assertThat(a.enqueue(ManualEvalInbox.TYPE_HTTP, 1L, true, Map.of("ok", true), "pod2")).isFalse();
        assertThat(a.claim("pod1", 25)).isEmpty();
    }

    @Test
    @DisplayName("Çok büyük sonuç iletilmez (DB koruması) — false")
    void oversizedResultRejected() {
        String big = "x".repeat(ManualEvalInbox.MAX_JSON_CHARS + 10);
        assertThat(a.enqueue(ManualEvalInbox.TYPE_PAGE, 1L, true, Map.of("body", big), "pod2")).isFalse();
        assertThat(rows()).isZero();
    }
}
