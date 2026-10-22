package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MP-46 (2026-10-23, çok pod R2a · D2) — sayfa kullanımı iki pod'dan aynı (gün, kullanıcı, sekme) satırına yazılır:
 * ilk yazım yarışı tur iptal etmez, her anahtar KENDİ yazımından hemen sonra bellekten düşülür (önceden yazılmış anahtarlar
 * sonraki turda ikinci kez sayılmaz), toplam = iki pod'un ping toplamı.
 */
class PageUsageMultiPodTest {

    private static final String DDL = "CREATE TABLE IF NOT EXISTS page_usage_daily(day VARCHAR(10) NOT NULL, "
            + "username VARCHAR(120) NOT NULL, tab VARCHAR(40) NOT NULL, pings BIGINT DEFAULT 0, first_seen VARCHAR(40), "
            + "last_seen VARCHAR(40), PRIMARY KEY (day, username, tab))";

    @Test
    @DisplayName("İlk satır yarışı (INSERT tekil ihlal) turu iptal etmez: anahtar yeniden toplanır, tampon ilk turda boşalır")
    void insertRace_retriesAndDrainsPerKey() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AtomicInteger contestedUpdates = new AtomicInteger();
        // Mockito açık (varargs) argümanlar: [sql, a1, a2, …]
        when(jdbc.update(startsWith("UPDATE page_usage_daily"), any(Object[].class))).thenAnswer(i -> {
            Object[] args = i.getArguments();
            if ("contested".equals(args[args.length - 1])) return contestedUpdates.getAndIncrement() == 0 ? 0 : 1;
            return 0;
        });
        when(jdbc.update(startsWith("INSERT INTO page_usage_daily"), any(Object[].class))).thenAnswer(i -> {
            Object[] args = i.getArguments();
            if ("contested".equals(args[3])) throw new DuplicateKeyException("pk_page_usage_daily");
            return 1;
        });
        PageUsageService s = new PageUsageService(jdbc);
        for (String tab : new String[]{"overview", "contested", "domains", "alerthistory", "monitoring"}) s.record("ali", tab);

        s.flush();

        assertThat(s.buffered()).as("eski kod turu iptal edip yazılmış anahtarları bellekte bırakıyordu").isZero();
        assertThat(contestedUpdates.get()).as("ihlalden sonra yeniden UPDATE").isEqualTo(2);
        clearInvocations(jdbc);
        s.flush();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("Yazım düşerse tur orada durur: önceki anahtarlar düşülmüş, kalanlar sonraki tura (çift sayım yok)")
    void writeFailure_keepsOnlyUnwrittenKeys() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AtomicBoolean down = new AtomicBoolean(false);
        AtomicInteger writes = new AtomicInteger();
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(i -> {
            if (down.get()) throw new IllegalStateException("db down");
            if (writes.incrementAndGet() == 2) down.set(true);   // ilk anahtar yazıldı, ikincide veritabanı düştü
            return 1;
        });
        PageUsageService s = new PageUsageService(jdbc);
        s.record("ali", "overview");
        s.record("ali", "domains");
        s.record("ali", "monitoring");

        s.flush();
        int left = s.buffered();
        assertThat(left).as("yazılan anahtar düşüldü, kalanlar bekliyor").isBetween(1, 2);
        down.set(false);
        writes.set(-100);
        s.flush();
        assertThat(s.buffered()).isZero();
    }

    @Test
    @DisplayName("İki pod gerçek H2'de aynı satıra aynı anda ilk kez yazar → toplam ping = iki pod'un toplamı")
    void twoPodsSameRow_countsAddUp() {
        // H2'de DAY anahtar sözcük: bu tablo için NON_KEYWORDS (üretimde PostgreSQL)
        org.springframework.jdbc.datasource.DriverManagerDataSource ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:pud" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=DAY");
        JdbcTemplate shared = new JdbcTemplate(ds);
        try (MultiPodTestSupport mp = MultiPodTestSupport.create()) {
            shared.execute(DDL);
            CyclicBarrier atInsert = new CyclicBarrier(2);
            PageUsageService a = new PageUsageService(barrierJdbc(ds, atInsert));
            PageUsageService b = new PageUsageService(barrierJdbc(ds, atInsert));
            for (int i = 0; i < 3; i++) a.record("ali", "overview");
            for (int i = 0; i < 2; i++) a.record("ali", "contested");
            for (int i = 0; i < 5; i++) b.record("ali", "contested");

            mp.race(() -> { a.flush(); return 1; }, () -> { b.flush(); return 1; });
            a.flush();
            b.flush();

            assertThat(shared.queryForObject("SELECT pings FROM page_usage_daily WHERE tab = 'contested'", Long.class)).isEqualTo(7L);
            assertThat(shared.queryForObject("SELECT pings FROM page_usage_daily WHERE tab = 'overview'", Long.class)).isEqualTo(3L);
            assertThat(a.buffered()).isZero();
            assertThat(b.buffered()).isZero();
        } finally {
            shared.execute("SHUTDOWN");
        }
    }

    /** İlk "contested" INSERT'ünde iki pod'u buluşturan JdbcTemplate (ikisi de UPDATE'te 0 satır gördükten sonra ekler). */
    private static JdbcTemplate barrierJdbc(DataSource ds, CyclicBarrier barrier) {
        AtomicBoolean armed = new AtomicBoolean(true);
        return new JdbcTemplate(ds) {
            @Override
            public int update(String sql, Object... args) {
                if (sql.startsWith("INSERT") && args.length > 2 && "contested".equals(args[2]) && armed.compareAndSet(true, false)) {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
                return super.update(sql, args);
            }
        };
    }
}
