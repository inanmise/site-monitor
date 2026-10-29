package com.sitemonitor.service.checks;

import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kuyruk çağıranın işlemine KATILMAZ (2026-10-29, çok pod Sürüm 3-3 FX-QUEUE, inceleme QW-L3): {@link JdbcCheckJobStore}'un
 * Spring bean'i vekildir (CGLIB, Boot'un varsayılanı gibi) ve sınıf düzeyi {@code NOT_SUPPORTED} her yöntemi çağıranın işleminin
 * DIŞINDA koşturur — işlem içindeki çağrı (ör. salt okunur System Health) dış bağlantının commit edilmemiş satırını GÖRMEZ, kendi
 * yazımı dış işlem geri alınsa da kalır. Düzeltme olmadan kuyruk dış bağlantıya katılır: bu test düşer.
 */
class CheckJobStoreTransactionTest {

    private static final long T0 = 1_800_000_000_000L;

    final MultiPodTestSupport mp = MultiPodTestSupport.create();
    final TestDbClock clock = new TestDbClock(T0);
    AnnotationConfigApplicationContext ctx;

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TxConfig {
    }

    @AfterEach
    void close() {
        if (ctx != null) ctx.close();
        mp.close();
    }

    private CheckJobStore proxiedStore(PlatformTransactionManager txm) {
        ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(mp.dataSource()));
        ctx.registerBean(DbClock.class, () -> clock);
        ctx.registerBean("transactionManager", PlatformTransactionManager.class, () -> txm);
        ctx.register(TxConfig.class, JdbcCheckJobStore.class);
        ctx.refresh();
        return ctx.getBean(CheckJobStore.class);
    }

    @Test
    @DisplayName("İşlem içinden çağrı: kuyruk kendi bağlantısında koşar — dış işlemin commit edilmemiş satırını görmez, yazımı geri alınmaz")
    void storeNeverJoinsTheCallersTransaction() {
        new CheckJobSchemaPatch(mp.jdbc()).apply();
        DataSourceTransactionManager txm = new DataSourceTransactionManager(mp.dataSource());
        CheckJobStore store = proxiedStore(txm);
        assertThat(AopUtils.isAopProxy(store)).as("bean vekil — @Transactional uygulanır").isTrue();
        assertThat(store).isInstanceOf(JdbcCheckJobStore.class);

        JdbcTemplate outer = new JdbcTemplate(mp.dataSource());
        TransactionTemplate tt = new TransactionTemplate(txm);
        tt.executeWithoutResult(status -> {
            outer.update("INSERT INTO monitor_check_jobs (job_type, monitor_id, state, lease_ms, created_ms, dispatcher, attempts) "
                    + "VALUES ('port', 99, 'PENDING', 150000, ?, 'dış', 0)", T0);
            assertThat(store.stats().count(CheckJobStore.STATE_PENDING))
                    .as("dış işlemin commit edilmemiş satırı görünmez (kendi bağlantısı)").isZero();
            assertThat(store.dispatch(CheckJobTypes.PORT, List.of(1L), 150_000, "l")).isEqualTo(1);
            assertThat(store.claim(Set.of(CheckJobTypes.HTTP), 1, "w")).as("üç bağımsız değişkenli varsayılan da vekilden").isEmpty();
            status.setRollbackOnly();
        });
        List<Long> left = mp.jdbc().queryForList("SELECT monitor_id FROM monitor_check_jobs", Long.class);
        assertThat(left).as("dış işlem geri alındı, kuyruğun yazımı kaldı").containsExactly(1L);
    }

    @Test
    @DisplayName("Bean'in pod ayarı: result-stale-ms isteğe bağlı DistributedChecks'ten okunur (yoksa varsayılan)")
    void distributedChecksIsOptional() {
        new CheckJobSchemaPatch(mp.jdbc()).apply();
        CheckJobStore store = proxiedStore(new DataSourceTransactionManager(mp.dataSource()));
        assertThat(store.reap(5)).isEmpty();
    }
}
