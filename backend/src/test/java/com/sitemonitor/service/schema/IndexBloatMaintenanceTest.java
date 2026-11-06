package com.sitemonitor.service.schema;

import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.retention.RetentionCatalog;
import com.sitemonitor.service.retention.RetentionPolicy;
import com.sitemonitor.service.schedule.SideJob;
import com.sitemonitor.service.schema.IndexCatalog.ColumnStat;
import com.sitemonitor.service.schema.IndexCatalog.IndexStats;
import com.sitemonitor.service.schema.IndexCatalog.Leftover;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Şişmiş indeks bakımı (2026-11-06) — sözleşme. Pinlenen: kalıntılar önce temizlenir (kurulum süren tabloya dokunulmaz),
 * şişmişler en büyük fazladan başlayarak CONCURRENTLY yeniden kurulur, her yeniden kurma lock_timeout + kalan bütçe kadar
 * statement_timeout ile, başarısızlıkta o tablonun kalıntısı hemen temizlenir, süre bütçesi / kapanış / başka pod'un yaması
 * yeni indekse geçişi durdurur, SMIDXBLD sınırlı beklenir, küme kilidi + tetik turu altında koşar, izinli tablolar saklama
 * kuralı olan tablolardır.
 */
class IndexBloatMaintenanceTest {

    private static final long MB = 1024L * 1024;

    private static IndexStats bloated(String name, String table, long bytes) {
        return new IndexStats(name, "public." + name, table, 15_000, 15_000, bytes, 8192, 90,
                List.of(new ColumnStat("monitor_id", 8.0, 0.0), new ColumnStat("checked_at", 20.0, 0.0)));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SchedulerService> provider(SchedulerService s) {
        ObjectProvider<SchedulerService> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(s);
        return p;
    }

    private static final class Rig {
        final FakeIndexCatalog db = new FakeIndexCatalog();
        final AtomicLong now = new AtomicLong(1_000_000L);
        final AtomicInteger opens = new AtomicInteger();
        final AtomicBoolean schedulerStopping = new AtomicBoolean();
        final AtomicInteger sleeps = new AtomicInteger();
        Boolean pg = true;
        SchedulerService scheduler;
        long budgetMs = 45 * 60_000L;
        int maxIndexes = 6;
        boolean enabled = true;

        IndexBloatMaintenance build() {
            return new IndexBloatMaintenance(List.of("page_checks", "page_resource_issues"), () -> pg, () -> {
                opens.incrementAndGet();
                db.closed = false;
                return db;
            }, provider(scheduler), enabled, 30_000, maxIndexes, 4096 * MB, budgetMs, 64 * MB, now::get, ms -> {
                sleeps.incrementAndGet();
                now.addAndGet(ms);
            }, schedulerStopping::get);
        }
    }

    @Test
    @DisplayName("Kalıntılar önce düşer (kurulum süren tabloya dokunulmaz), sonra şişmişler en büyük fazladan CONCURRENTLY yeniden kurulur")
    void leftoversFirstThenRebuildByExcess() {
        Rig r = new Rig();
        r.db.leftovers.add(new Leftover("idx_pc_monitor_checked_ccnew", "public.idx_pc_monitor_checked_ccnew", "page_checks", 5 * MB, false));
        r.db.leftovers.add(new Leftover("idx_pri_checked_at_ccold", "public.idx_pri_checked_at_ccold", "page_resource_issues", MB, true));
        r.db.stats.add(bloated("idx_pc_monitor_checked", "page_checks", 85 * MB));
        r.db.stats.add(bloated("idx_pri_monitor_checked", "page_resource_issues", 127 * MB));
        r.db.stats.add(bloated("idx_pc_small", "page_checks", 30 * MB));   // < beklenen + 64 MB → dokunulmaz
        r.db.stats.add(bloated("idx_other", "port_checks", 900 * MB));      // izinli tablo değil → hiç okunmaz
        r.db.bytesAfter.put("public.idx_pri_monitor_checked", 3 * MB);
        r.db.bytesAfter.put("public.idx_pc_monitor_checked", 2 * MB);
        IndexBloatMaintenance.Report rep = r.build().maintain();
        assertThat(r.db.ddls()).containsExactly(
                "DROP INDEX CONCURRENTLY IF EXISTS public.idx_pc_monitor_checked_ccnew",
                "REINDEX INDEX CONCURRENTLY public.idx_pri_monitor_checked",
                "REINDEX INDEX CONCURRENTLY public.idx_pc_monitor_checked");
        assertThat(r.db.executed.get(1)).endsWith("| lock=30000 | stmt=" + 45 * 60_000L);
        assertThat(rep.locked()).isTrue();
        assertThat(rep.leftoversDropped()).isEqualTo(1);
        assertThat(rep.rebuilt()).containsExactly("idx_pri_monitor_checked", "idx_pc_monitor_checked");
        assertThat(rep.bytesBefore()).isEqualTo(212 * MB);
        assertThat(rep.bytesAfter()).isEqualTo(5 * MB);
        assertThat(r.db.held).as("SMIDXBLD bırakıldı").isEmpty();
        assertThat(r.db.closed).isTrue();
    }

    @Test
    @DisplayName("Yeniden kurma düşerse (55P03): başarısız sayılır, o tablonun _ccnew / _ccold kalıntısı hemen temizlenir, sıradakine geçilir")
    void failureCleansThatTable() {
        Rig r = new Rig();
        r.db.stats.add(bloated("idx_pri_monitor_checked", "page_resource_issues", 127 * MB));
        r.db.stats.add(bloated("idx_pc_monitor_checked", "page_checks", 85 * MB));
        r.db.failState = ddl -> {
            if (ddl.equals("REINDEX INDEX CONCURRENTLY public.idx_pri_monitor_checked")) {
                r.db.leftovers.add(new Leftover("idx_pri_monitor_checked_ccnew", "public.idx_pri_monitor_checked_ccnew",
                        "page_resource_issues", MB, false));
                return "55P03";
            }
            return null;
        };
        IndexBloatMaintenance.Report rep = r.build().maintain();
        assertThat(rep.failed()).containsExactly("idx_pri_monitor_checked");
        assertThat(rep.rebuilt()).containsExactly("idx_pc_monitor_checked");
        assertThat(r.db.leftoverQueries).containsExactly(List.of("page_checks", "page_resource_issues"), List.of("page_resource_issues"));
        assertThat(r.db.ddls()).containsExactly(
                "REINDEX INDEX CONCURRENTLY public.idx_pri_monitor_checked",
                "DROP INDEX CONCURRENTLY IF EXISTS public.idx_pri_monitor_checked_ccnew",
                "REINDEX INDEX CONCURRENTLY public.idx_pc_monitor_checked");
        assertThat(rep.leftoversDropped()).isEqualTo(1);
    }

    @Test
    @DisplayName("Süre bütçesi: kalan < 60 sn ise yeni yeniden kurmaya başlanmaz; statement_timeout = kalan bütçe")
    void timeBudget() {
        Rig r = new Rig();
        r.budgetMs = 10 * 60_000L;
        r.db.stats.add(bloated("a", "page_checks", 300 * MB));
        r.db.stats.add(bloated("b", "page_checks", 200 * MB));
        r.db.stats.add(bloated("c", "page_checks", 100 * MB));
        r.db.beforeExecute = ddl -> r.now.addAndGet(ddl.endsWith(".a") ? 9 * 60_000L + 30_000 : 0);
        IndexBloatMaintenance.Report rep = r.build().maintain();
        assertThat(r.db.ddls()).containsExactly("REINDEX INDEX CONCURRENTLY public.a");
        assertThat(r.db.executed.get(0)).endsWith("stmt=" + 10 * 60_000L);
        assertThat(rep.deferred()).isEqualTo(2);
    }

    @Test
    @DisplayName("İki yeniden kurma arasında SMIDXBLD kısa süre bırakılır; başka oturum (açılan pod'un ertelenmiş kurucusu) aldıysa bu gece biter")
    void yieldsIndexLockBetweenRebuilds() {
        Rig r = new Rig();
        r.db.stats.add(bloated("a", "page_checks", 300 * MB));
        r.db.stats.add(bloated("b", "page_checks", 200 * MB));
        r.db.beforeExecute = ddl -> {
            if (ddl.endsWith(".a")) r.db.heldElsewhere.add(DeferredIndexBuilder.LOCK_KEY);   // bırakıldığı anda kapılır
        };
        IndexBloatMaintenance.Report rep = r.build().maintain();
        assertThat(r.db.ddls()).containsExactly("REINDEX INDEX CONCURRENTLY public.a");
        assertThat(rep.stoppedEarly()).isTrue();
        assertThat(r.sleeps.get()).as("bir bırakma beklemesi").isEqualTo(1);
        assertThat(r.db.held).as("kilit bizde değil, ikinci kez bırakılmadı").isEmpty();

        Rig ok = new Rig();
        ok.db.stats.add(bloated("a", "page_checks", 300 * MB));
        ok.db.stats.add(bloated("b", "page_checks", 200 * MB));
        assertThat(ok.build().maintain().rebuilt()).containsExactly("a", "b");
        assertThat(ok.sleeps.get()).isEqualTo(1);
        assertThat(ok.db.held).isEmpty();
    }

    @Test
    @DisplayName("Adet tavanı: fazlası sonraki geceye")
    void countCap() {
        Rig r = new Rig();
        r.maxIndexes = 1;
        r.db.stats.add(bloated("a", "page_checks", 300 * MB));
        r.db.stats.add(bloated("b", "page_checks", 200 * MB));
        IndexBloatMaintenance.Report rep = r.build().maintain();
        assertThat(rep.rebuilt()).containsExactly("a");
        assertThat(rep.deferred()).isEqualTo(1);
    }

    @Test
    @DisplayName("Kapanış: süren yeniden kurma cin yardımcıda iptal edilir; yeni indekse geçilmez, kalıntı temizliğine girişilmez")
    void shutdown() throws Exception {
        Rig r = new Rig();
        r.db.stats.add(bloated("a", "page_checks", 300 * MB));
        r.db.stats.add(bloated("b", "page_checks", 200 * MB));
        AtomicReference<IndexBloatMaintenance> self = new AtomicReference<>();
        r.db.beforeExecute = ddl -> self.get().onContextClosed(null);
        r.db.failState = ddl -> "57014";   // iptal → "canceling statement due to user request"
        IndexBloatMaintenance m = r.build();
        self.set(m);
        IndexBloatMaintenance.Report rep = m.maintain();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (r.db.cancels.get() == 0 && System.nanoTime() < until) Thread.sleep(10);
        assertThat(r.db.cancels.get()).isEqualTo(1);
        assertThat(r.db.ddls()).containsExactly("REINDEX INDEX CONCURRENTLY public.a");
        assertThat(rep.stoppedEarly()).isTrue();
        assertThat(r.db.leftoverQueries).as("yalnız açılıştaki tarama").hasSize(1);
        int opens = r.opens.get();
        m.scheduled();
        assertThat(r.opens.get()).isEqualTo(opens);
    }

    @Test
    @DisplayName("Zamanlayıcı kapanıyorsa (sideJobsStopping) yeni yeniden kurma yok")
    void schedulerStopping() {
        Rig r = new Rig();
        r.db.stats.add(bloated("a", "page_checks", 300 * MB));
        r.schedulerStopping.set(true);
        IndexBloatMaintenance.Report rep = r.build().maintain();
        assertThat(r.db.executed).isEmpty();
        assertThat(rep.stoppedEarly()).isTrue();
    }

    @Test
    @DisplayName("Başka pod şema yamalarını koşuyor → yeni indekse başlanmaz")
    void patchLockBusy() {
        Rig r = new Rig();
        r.db.stats.add(bloated("a", "page_checks", 300 * MB));
        r.db.heldElsewhere.add(SchemaPatchRunner.LOCK_KEY);
        IndexBloatMaintenance.Report rep = r.build().maintain();
        assertThat(r.db.executed).isEmpty();
        assertThat(rep.stoppedEarly()).isTrue();
        assertThat(r.db.held).isEmpty();
    }

    @Test
    @DisplayName("SMIDXBLD doluysa en çok 5 dk beklenir (sınırlı deneme), sonra bu gece atlanır; hiçbir DDL yok")
    void indexLockBusy() {
        Rig r = new Rig();
        r.db.stats.add(bloated("a", "page_checks", 300 * MB));
        r.db.heldElsewhere.add(DeferredIndexBuilder.LOCK_KEY);
        IndexBloatMaintenance.Report rep = r.build().maintain();
        assertThat(rep.locked()).isFalse();
        assertThat(r.db.executed).isEmpty();
        assertThat(r.sleeps.get()).isBetween(1, (int) (IndexBloatMaintenance.LOCK_WAIT_MS / IndexBloatMaintenance.LOCK_POLL_MS) + 1);
        assertThat(r.db.closed).isTrue();
    }

    @Test
    @DisplayName("Oturum açılamazsa dışarı sızmaz")
    void openFails() {
        Rig r = new Rig();
        IndexBloatMaintenance m = new IndexBloatMaintenance(List.of("page_checks"), () -> true,
                () -> { throw new SQLException("bağlanılamadı", "08001"); }, provider(null), true, 30_000, 6, 4096 * MB,
                45 * 60_000L, 64 * MB, r.now::get, ms -> { }, () -> false);
        assertThat(m.maintain().locked()).isFalse();
    }

    @Test
    @DisplayName("Zamanlanmış koşu: küme kilidi + tetik başına öğe korumalı tur; tur başka pod'daysa bakım koşmaz; PG değil / okunamaz → kilit bile alınmaz")
    void scheduledRun() {
        SchedulerService s = mock(SchedulerService.class);
        when(s.callWithSchedulerLock(eq("index-bloat-maintenance"), any(Supplier.class)))
                .thenAnswer(inv -> Optional.ofNullable(((Supplier<?>) inv.getArgument(1)).get()));
        Rig r = new Rig();
        r.scheduler = s;
        r.db.stats.add(bloated("a", "page_checks", 300 * MB));
        when(s.claimItemProtectedCronRound(eq("index-bloat-maintenance"), anyString(), eq("Europe/Istanbul"))).thenReturn(false);
        IndexBloatMaintenance m = r.build();
        m.scheduled();   // Mockito zamanlayıcısı offloadSideJob → false: gövde satır içinde
        assertThat(r.opens.get()).as("tur başka pod'da").isZero();
        when(s.claimItemProtectedCronRound(eq("index-bloat-maintenance"), anyString(), eq("Europe/Istanbul"))).thenReturn(true);
        m.scheduled();
        assertThat(r.db.ddls()).containsExactly("REINDEX INDEX CONCURRENTLY public.a");
        verify(s, times(2)).claimItemProtectedCronRound(eq("index-bloat-maintenance"), eq(IndexBloatMaintenance.CRON), eq("Europe/Istanbul"));

        SchedulerService s2 = mock(SchedulerService.class);
        Rig notPg = new Rig();
        notPg.scheduler = s2;
        notPg.pg = false;
        notPg.build().scheduled();
        notPg.pg = null;
        notPg.build().scheduled();
        verify(s2, never()).callWithSchedulerLock(anyString(), any(Supplier.class));
    }

    @Test
    @DisplayName("Yan iş: koşucu üstlenirse gövde satır içinde koşmaz (cron işi, ad = tur adı, uzun iş); kapalı / zamanlayıcı yok → hiçbir şey")
    void sideJob() {
        SchedulerService s = mock(SchedulerService.class);
        AtomicReference<SideJob> job = new AtomicReference<>();
        when(s.offloadSideJob(any(SideJob.class))).thenAnswer(inv -> { job.set(inv.getArgument(0)); return true; });
        Rig r = new Rig();
        r.scheduler = s;
        r.build().scheduled();
        assertThat(job.get()).isNotNull();
        assertThat(job.get().name()).isEqualTo("index-bloat-maintenance");
        assertThat(job.get().cronRound()).isEqualTo("index-bloat-maintenance");
        assertThat(job.get().heavy()).isTrue();
        assertThat(job.get().cron()).isEqualTo(IndexBloatMaintenance.CRON);
        assertThat(job.get().zone()).isEqualTo("Europe/Istanbul");
        verify(s, never()).callWithSchedulerLock(anyString(), any(Supplier.class));

        Rig off = new Rig();
        SchedulerService s3 = mock(SchedulerService.class);
        off.scheduler = s3;
        off.enabled = false;
        off.build().scheduled();
        verify(s3, never()).offloadSideJob(any());
        Rig none = new Rig();
        none.build().scheduled();
        assertThat(none.opens.get()).isZero();
    }

    @Test
    @DisplayName("İzinli tabloların her biri saklama motorunun YAŞA göre sildiği bir tablo (RetentionCatalog zaman kolonu)")
    void allowedTablesHaveAgeRetention() {
        for (String t : IndexBloatMaintenance.ALLOWED_TABLES) {
            List<RetentionPolicy> p = RetentionCatalog.byTable(t);
            assertThat(p).as("%s RetentionCatalog'da yok", t).isNotEmpty();
            assertThat(p).as("%s yaşa göre silinmiyor", t).anyMatch(x -> x.timeColumn() != null);
        }
        assertThat(IndexBloatMaintenance.ALLOWED_TABLES).contains("page_checks", "page_resource_issues")
                .doesNotContain("audit_log", "app_users", "alert_events").doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Zaman aşımı türleri günlükte adlandırılır")
    void timeoutKind() {
        assertThat(IndexBloatMaintenance.timeoutKind(new SQLException("x", "55P03"))).contains("lock_timeout");
        assertThat(IndexBloatMaintenance.timeoutKind(new SQLException("x", "57014"))).contains("bütçe");
        assertThat(IndexBloatMaintenance.timeoutKind(new SQLException("x", "42P01"))).isEmpty();
    }
}
