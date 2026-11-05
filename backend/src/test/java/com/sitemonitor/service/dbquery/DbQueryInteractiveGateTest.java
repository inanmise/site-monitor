package com.sitemonitor.service.dbquery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Etkileşimli sorgu denetimi kapısı (dbq2 inceleme O3): hız sınırı, beklemeyen eşzamanlılık, zamanlanmışa kalan izin. */
class DbQueryInteractiveGateTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);

    @Test
    @DisplayName("hız: kullanıcı başına dakikada 60 denetim / 20 düzeltme (ayrı sayaç); aşınca RATE_LIMITED + bekleme; pencere kayınca açılır; "
            + "reddedilen istek sayılmaz")
    void rateLimit() {
        DbQueryInteractiveGate g = new DbQueryInteractiveGate(4, now::get);
        for (int i = 0; i < DbQueryInteractiveGate.FIX_PER_MINUTE; i++)
            g.tryEnter("ayse", DbQueryInteractiveGate.Kind.FIX).ticket().close();
        DbQueryInteractiveGate.Decision d = g.tryEnter("ayse", DbQueryInteractiveGate.Kind.FIX);
        assertThat(d.admitted()).isFalse();
        assertThat(d.refusedCode()).isEqualTo(DbQueryInteractiveGate.CODE_RATE_LIMITED);
        assertThat(d.retryAfterSeconds()).isEqualTo(60);
        assertThat(g.tryEnter("ayse", DbQueryInteractiveGate.Kind.CHECK).admitted()).isTrue();   // ayrı sayaç
        assertThat(g.tryEnter("mehmet", DbQueryInteractiveGate.Kind.FIX).admitted()).isTrue();   // kullanıcı başına
        now.addAndGet(30_000);
        assertThat(g.tryEnter("ayse", DbQueryInteractiveGate.Kind.FIX).retryAfterSeconds()).isEqualTo(30);
        now.addAndGet(30_000);
        assertThat(g.tryEnter("ayse", DbQueryInteractiveGate.Kind.FIX).admitted()).isTrue();

        DbQueryInteractiveGate c = new DbQueryInteractiveGate(4, now::get);
        for (int i = 0; i < DbQueryInteractiveGate.CHECK_PER_MINUTE; i++) {
            DbQueryInteractiveGate.Decision ok = c.tryEnter("ayse", DbQueryInteractiveGate.Kind.CHECK);
            assertThat(ok.admitted()).isTrue();
            ok.ticket().close();
        }
        assertThat(c.tryEnter("ayse", DbQueryInteractiveGate.Kind.CHECK).refusedCode()).isEqualTo(DbQueryInteractiveGate.CODE_RATE_LIMITED);
    }

    @Test
    @DisplayName("eşzamanlılık: kapı dolu → beklemeden DBQUERY_BUSY (sayılmaz); bilet kapanınca açılır; iki kez kapanan bilet izni bir kez bırakır")
    void busyWithoutWaiting() {
        DbQueryInteractiveGate g = new DbQueryInteractiveGate(2, now::get);
        var a = g.tryEnter("a", DbQueryInteractiveGate.Kind.CHECK).ticket();
        var b = g.tryEnter("b", DbQueryInteractiveGate.Kind.CHECK).ticket();
        long t0 = System.nanoTime();
        DbQueryInteractiveGate.Decision d = g.tryEnter("c", DbQueryInteractiveGate.Kind.CHECK);
        assertThat(System.nanoTime() - t0).isLessThan(500_000_000L);
        assertThat(d.refusedCode()).isEqualTo(DbQueryInteractiveGate.CODE_BUSY);
        assertThat(d.retryAfterSeconds()).isEqualTo(DbQueryInteractiveGate.BUSY_RETRY_SECONDS);
        a.close();
        a.close();
        assertThat(g.tryEnter("c", DbQueryInteractiveGate.Kind.CHECK).admitted()).isTrue();
        assertThat(g.tryEnter("d", DbQueryInteractiveGate.Kind.CHECK).admitted()).isFalse();   // fazladan izin yok
        b.close();
        // reddedilen (BUSY) istek dakikalık sayaca yazılmadı: "c" 60 hakkını yine kullanabilir
        DbQueryInteractiveGate h = new DbQueryInteractiveGate(1, now::get);
        var hold = h.tryEnter("x", DbQueryInteractiveGate.Kind.FIX).ticket();
        for (int i = 0; i < 30; i++) assertThat(h.tryEnter("y", DbQueryInteractiveGate.Kind.FIX).refusedCode()).isEqualTo("DBQUERY_BUSY");
        hold.close();
        assertThat(h.tryEnter("y", DbQueryInteractiveGate.Kind.FIX).admitted()).isTrue();
    }

    @Test
    @DisplayName("zamanlanmış çalıştırma asla etkileşimli istek yüzünden DB_BUSY olmaz: kapı = ayrıştırma izni − 1; tüm etkileşimli "
            + "biletler birer ayrıştırma iznini tutarken (yavaş ayrıştırma) gerçek denetim son izni alır, zaman aşımı yok")
    void scheduledRunKeepsAParsePermit() {
        Semaphore parse = (Semaphore) ReflectionTestUtils.getField(DbQuerySqlGuard.class, "PARSE_PERMITS");
        int total = parse.availablePermits();
        assertThat(total).isEqualTo(DbQueryInteractiveGate.guardParsePermits());   // aynı formül
        DbQueryInteractiveGate g = new DbQueryInteractiveGate(DbQueryInteractiveGate.defaultCapacity(), now::get);
        assertThat(g.capacity()).isEqualTo(total - 1).isLessThan(total);
        List<DbQueryInteractiveGate.Ticket> tickets = new ArrayList<>();
        int held = 0;
        try {
            for (int i = 0; i < total + 5; i++) {
                DbQueryInteractiveGate.Decision d = g.tryEnter("kullanici" + i, DbQueryInteractiveGate.Kind.FIX);
                if (!d.admitted()) {
                    assertThat(d.refusedCode()).isEqualTo(DbQueryInteractiveGate.CODE_BUSY);
                    continue;
                }
                tickets.add(d.ticket());
                assertThat(parse.tryAcquire()).isTrue();   // bu etkileşimli istek ayrıştırıyor (uzun sürüyor)
                held++;
            }
            assertThat(held).isEqualTo(total - 1);
            // zamanlanmış çalıştırmanın denetimi (önbellekte olmayan sorgu) — son izni alır
            DbQuerySqlGuard.Result r = DbQuerySqlGuard.check("SELECT id FROM planli_" + System.nanoTime(), DbType.POSTGRESQL);
            assertThat(DbQuerySqlGuard.isTimeout(r)).isFalse();
            assertThat(r.ok()).isTrue();
        } finally {
            parse.release(held);
            tickets.forEach(DbQueryInteractiveGate.Ticket::close);
        }
    }
}
