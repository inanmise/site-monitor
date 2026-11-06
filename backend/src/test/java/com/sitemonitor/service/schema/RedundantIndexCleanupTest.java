package com.sitemonitor.service.schema;

import com.sitemonitor.service.schedule.SideJob;
import com.sitemonitor.service.schema.IndexCatalog.IndexFacts;
import com.sitemonitor.service.schema.RedundantIndexCleanup.Decision;
import com.sitemonitor.service.schema.RedundantIndexCleanup.Outcome;
import com.sitemonitor.service.schema.RedundantIndexCleanup.Target;
import com.sitemonitor.service.schema.RedundantIndexCleanup.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.sitemonitor.service.schema.FakeIndexCatalog.idx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gereksiz indeks temizliği (2026-11-06) — sözleşme. Pinlenen: doğrulama kuralları (kapsayan yok / kısmi / geçersiz / farklı
 * sütun-opclass-collation-sıra / benzersiz aday → düşürmez; birebir kopya ve birincil anahtar kapsar; kapsayan başka bir hedef
 * olamaz), kapı (eski sürüm canlı / okunamaz / geçmiş kapalı), kilit, nişan (yalnız iş bitince, kilit içinde), deneme sınırı
 * (bekleme turları sayılmaz), kapanış, yan iş koşucusu.
 */
class RedundantIndexCleanupTest {

    private static final Target PORT = new Target("idx_portc_monitor_id", "port_checks", List.of("monitor_id"));
    private static final Set<String> NAMES = RedundantIndexCleanup.TARGETS.stream().map(Target::index).collect(Collectors.toSet());

    private static IndexFacts copy(IndexFacts f, String am, boolean unique, boolean primary, boolean exclusion, boolean con,
                                   boolean valid, boolean ready, boolean live, boolean partial, boolean exprs,
                                   List<String> ops, List<String> coll, List<String> opt) {
        return new IndexFacts(f.name(), f.qualifiedName(), f.table(), am, unique, primary, exclusion, con, valid, ready, live,
                partial, exprs, f.keyCount(), f.columns(), ops, coll, opt, f.bytes());
    }

    private static IndexFacts cand() { return idx("port_checks", "idx_portc_monitor_id", List.of("monitor_id")); }

    private static IndexFacts cover() { return idx("port_checks", "idx_portc_monitor_checked", List.of("monitor_id", "checked_at")); }

    private static Decision decide(IndexFacts... indexes) {
        return RedundantIndexCleanup.decide(PORT, List.of(indexes), false, NAMES);
    }

    // ── 1. Doğrulama kuralları (saf) ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Aday yok → ABSENT; geçerli bileşik kapsayan → DROP (kapsayan adıyla)")
    void absentAndDrop() {
        assertThat(decide(cover()).verdict()).isEqualTo(Verdict.ABSENT);
        Decision d = decide(cand(), cover());
        assertThat(d.verdict()).isEqualTo(Verdict.DROP);
        assertThat(d.covering().name()).isEqualTo("idx_portc_monitor_checked");
    }

    @Test
    @DisplayName("Kapsayan yok / kısmi / ifade / btree değil / farklı öncü sütun → NO_COVER (düşürmez)")
    void noCoveringIndex() {
        IndexFacts c = cover();
        assertThat(decide(cand()).verdict()).isEqualTo(Verdict.NO_COVER);
        assertThat(decide(cand(), copy(c, "btree", false, false, false, false, true, true, true, true, false,
                c.opclasses(), c.collations(), c.options())).verdict()).as("kısmi").isEqualTo(Verdict.NO_COVER);
        assertThat(decide(cand(), copy(c, "btree", false, false, false, false, true, true, true, false, true,
                c.opclasses(), c.collations(), c.options())).verdict()).as("ifade").isEqualTo(Verdict.NO_COVER);
        assertThat(decide(cand(), copy(c, "hash", false, false, false, false, true, true, true, false, false,
                c.opclasses(), c.collations(), c.options())).verdict()).as("hash").isEqualTo(Verdict.NO_COVER);
        IndexFacts reversed = idx("port_checks", "idx_x", List.of("checked_at", "monitor_id"));
        assertThat(decide(cand(), reversed).verdict()).as("sütun sırası farklı").isEqualTo(Verdict.NO_COVER);
        IndexFacts other = idx("port_checks", "idx_portc_checked_at", List.of("checked_at"));
        assertThat(decide(cand(), other).verdict()).as("başka sütun").isEqualTo(Verdict.NO_COVER);
    }

    @Test
    @DisplayName("Kapsayan geçersiz / hazır değil / canlı değil → NO_COVER, gerekçe 'henüz geçerli değil'")
    void coveringNotValid() {
        IndexFacts c = cover();
        for (boolean[] f : new boolean[][] { {false, true, true}, {true, false, true}, {true, true, false} }) {
            Decision d = decide(cand(), copy(c, "btree", false, false, false, false, f[0], f[1], f[2], false, false,
                    c.opclasses(), c.collations(), c.options()));
            assertThat(d.verdict()).isEqualTo(Verdict.NO_COVER);
            assertThat(d.reason()).contains("idx_portc_monitor_checked").contains("henüz");
        }
    }

    @Test
    @DisplayName("Öncü sütunun opclass'ı / collation'ı / sıralama seçeneği farklı → kapsamaz")
    void opclassCollationOptionMustMatch() {
        IndexFacts c = cover();
        List<String> ops = new ArrayList<>(c.opclasses()); ops.set(0, "9999");
        List<String> coll = new ArrayList<>(c.collations()); coll.set(0, "950");
        List<String> opt = new ArrayList<>(c.options()); opt.set(0, "3");   // DESC NULLS FIRST
        for (IndexFacts bad : List.of(
                copy(c, "btree", false, false, false, false, true, true, true, false, false, ops, c.collations(), c.options()),
                copy(c, "btree", false, false, false, false, true, true, true, false, false, c.opclasses(), coll, c.options()),
                copy(c, "btree", false, false, false, false, true, true, true, false, false, c.opclasses(), c.collations(), opt))) {
            assertThat(decide(cand(), bad).verdict()).isEqualTo(Verdict.NO_COVER);
        }
        // İkinci (adayda olmayan) sütunun seçeneği farklı olabilir
        List<String> opt2 = new ArrayList<>(c.options()); opt2.set(1, "3");
        assertThat(decide(cand(), copy(c, "btree", false, false, false, false, true, true, true, false, false,
                c.opclasses(), c.collations(), opt2)).verdict()).isEqualTo(Verdict.DROP);
    }

    @Test
    @DisplayName("Aday bizim tanımımız değil (benzersiz / birincil / dışlama / kısıt / kısmi / ifade / INCLUDE / farklı sütun / btree değil) → NOT_OURS")
    void candidateNotOurs() {
        IndexFacts a = cand();
        List<IndexFacts> bad = List.of(
                copy(a, "btree", true, false, false, false, true, true, true, false, false, a.opclasses(), a.collations(), a.options()),
                copy(a, "btree", true, true, false, true, true, true, true, false, false, a.opclasses(), a.collations(), a.options()),
                copy(a, "btree", false, false, true, true, true, true, true, false, false, a.opclasses(), a.collations(), a.options()),
                copy(a, "btree", false, false, false, true, true, true, true, false, false, a.opclasses(), a.collations(), a.options()),
                copy(a, "btree", false, false, false, false, true, true, true, true, false, a.opclasses(), a.collations(), a.options()),
                copy(a, "btree", false, false, false, false, true, true, true, false, true, a.opclasses(), a.collations(), a.options()),
                copy(a, "brin", false, false, false, false, true, true, true, false, false, a.opclasses(), a.collations(), a.options()),
                idx("port_checks", "idx_portc_monitor_id", List.of("monitor_id", "checked_at"), 1),   // INCLUDE (checked_at)
                idx("port_checks", "idx_portc_monitor_id", List.of("checked_at")));
        for (IndexFacts b : bad) {
            Decision d = decide(b, cover());
            assertThat(d.verdict()).as("%s", b).isEqualTo(Verdict.NOT_OURS);
            assertThat(d.reason()).isNotBlank();
        }
    }

    @Test
    @DisplayName("Tabloda kurulum sürüyor → BUSY; geçersiz kalmış aday (yarıda kalmış düşürme) geçerli kapsayanla → DROP")
    void busyAndInvalidCandidate() {
        assertThat(RedundantIndexCleanup.decide(PORT, List.of(cand(), cover()), true, NAMES).verdict()).isEqualTo(Verdict.BUSY);
        IndexFacts a = cand();
        IndexFacts invalid = copy(a, "btree", false, false, false, false, false, true, true, false, false,
                a.opclasses(), a.collations(), a.options());
        assertThat(decide(invalid, cover()).verdict()).isEqualTo(Verdict.DROP);
    }

    @Test
    @DisplayName("Birebir kopya (idx_pri_check = idx_pri_check_id) ve birincil anahtar (idx_mch_type_key_hour) kapsar")
    void exactDuplicatesAndPrimaryKey() {
        Target pri = RedundantIndexCleanup.TARGETS.get(0);
        assertThat(pri.index()).isEqualTo("idx_pri_check");
        Decision d = RedundantIndexCleanup.decide(pri, List.of(
                idx("page_resource_issues", "idx_pri_check", List.of("check_id")),
                idx("page_resource_issues", "idx_pri_check_id", List.of("check_id"))), false, NAMES);
        assertThat(d.verdict()).isEqualTo(Verdict.DROP);
        assertThat(d.covering().name()).isEqualTo("idx_pri_check_id");

        Target mch = RedundantIndexCleanup.TARGETS.get(1);
        List<String> cols = List.of("monitor_type", "monitor_key", "hour_bucket");
        IndexFacts pk = idx("monitor_check_hourly", "monitor_check_hourly_pkey", cols);
        pk = copy(pk, "btree", true, true, false, true, true, true, true, false, false, pk.opclasses(), pk.collations(), pk.options());
        Decision m = RedundantIndexCleanup.decide(mch, List.of(idx("monitor_check_hourly", "idx_mch_type_key_hour", cols), pk),
                false, NAMES);
        assertThat(m.verdict()).isEqualTo(Verdict.DROP);
        assertThat(m.covering().name()).isEqualTo("monitor_check_hourly_pkey");
    }

    @Test
    @DisplayName("Kapsayan başka bir HEDEF olamaz: idx_act_type yalnız idx_act_type_mon ile kapsanıyorsa düşürülmez")
    void coveringMustNotBeAnotherTarget() {
        Target type = RedundantIndexCleanup.TARGETS.get(2);
        IndexFacts a = idx("activity_log", "idx_act_type", List.of("monitor_type"));
        IndexFacts mon = idx("activity_log", "idx_act_type_mon", List.of("monitor_type", "monitor_id"));
        assertThat(RedundantIndexCleanup.decide(type, List.of(a, mon), false, NAMES).verdict()).isEqualTo(Verdict.NO_COVER);
        IndexFacts time = idx("activity_log", "idx_act_type_mon_time", List.of("monitor_type", "monitor_id", "activity_time"));
        Decision d = RedundantIndexCleanup.decide(type, List.of(a, mon, time), false, NAMES);
        assertThat(d.verdict()).isEqualTo(Verdict.DROP);
        assertThat(d.covering().name()).isEqualTo("idx_act_type_mon_time");
    }

    @Test
    @DisplayName("Hedef listesi: 15 indeks, her biri kaynakta artık bildirilmiyor (entity @Index / patch CREATE INDEX)")
    void targetsAreNoLongerDeclared() throws Exception {
        assertThat(RedundantIndexCleanup.TARGETS).hasSize(15);
        assertThat(NAMES).hasSize(15);
        StringBuilder src = new StringBuilder();
        try (var files = Files.walk(Path.of("src/main/java/com/sitemonitor"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.startsWith("//") || t.startsWith("*")) continue;
                    src.append(line).append('\n');
                }
            }
        }
        String all = src.toString();
        for (String n : NAMES) {
            assertThat(all).as("%s hâlâ bildiriliyor", n)
                    .doesNotContainPattern("@Index\\(name\\s*=\\s*\"" + n + "\"")
                    .doesNotContainPattern("(?i)CREATE\\s+INDEX\\s+(?:CONCURRENTLY\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?" + n + "\\s");
        }
    }

    // ── 2. Akış ─────────────────────────────────────────────────────────────────────────────────────────────────────

    private static final class Rig {
        final FakeIndexCatalog db = new FakeIndexCatalog();
        Boolean pg = true;
        Supplier<List<Map<String, String>>> versions = List::of;
        final AtomicBoolean schedulerStopping = new AtomicBoolean();
        final AtomicInteger opens = new AtomicInteger();
        boolean openFails;
        boolean offloadAccepts;
        final List<SideJob> offloaded = new ArrayList<>();
        boolean enabled = true;
        List<Target> targets = List.of(PORT);

        RedundantIndexCleanup build() {
            return new RedundantIndexCleanup(targets, "test-marker", () -> pg, () -> {
                opens.incrementAndGet();
                if (openFails) throw new SQLException("bağlanılamadı", "08001");
                db.closed = false;
                return db;
            }, () -> versions.get(), schedulerStopping::get, job -> {
                offloaded.add(job);
                return offloadAccepts;
            }, enabled, 30_000, 600_000);
        }
    }

    @Test
    @DisplayName("Tam koşu: doğrulanan aday CONCURRENTLY + nitelikli adla, lock_timeout ile düşer; nişan kilit içinde yazılır; oturum kapanır")
    void fullRunDropsAndWritesMarker() {
        Rig r = new Rig();
        r.db.put(cand()).put(cover());
        RedundantIndexCleanup c = r.build();
        assertThat(c.runOnce()).isEqualTo(Outcome.DONE);
        assertThat(r.db.executed).containsExactly(
                "DROP INDEX CONCURRENTLY IF EXISTS public.idx_portc_monitor_id | lock=30000 | stmt=300000");
        assertThat(r.db.markers).containsEntry("test-marker", 1);
        assertThat(r.db.held).as("kilitler bırakıldı").isEmpty();
        assertThat(r.db.closed).isTrue();
        assertThat(r.db.indexes("port_checks")).extracting(IndexFacts::name).containsExactly("idx_portc_monitor_checked");
    }

    @Test
    @DisplayName("Kapsayan yoksa: düşürülmez, nişan YAZILMAZ, tur tamamlanamadı sayılır; 24 turdan sonra pod bırakır")
    void noCoverBlocksMarkerAndCountsAttempts() {
        Rig r = new Rig();
        r.db.put(cand());
        RedundantIndexCleanup c = r.build();
        assertThat(c.runOnce()).isEqualTo(Outcome.INCOMPLETE);
        assertThat(r.db.executed).isEmpty();
        assertThat(r.db.markers).isEmpty();
        assertThat(c.pendingReasons()).containsKey("idx_portc_monitor_id");
        for (int i = 0; i < RedundantIndexCleanup.MAX_ATTEMPTS; i++) c.runAndCount();
        assertThat(c.failures()).isEqualTo(RedundantIndexCleanup.MAX_ATTEMPTS);
        int opens = r.opens.get();
        c.scheduled();
        assertThat(r.opens.get()).as("sınırdan sonra tur başlamaz").isEqualTo(opens);
        assertThat(r.offloaded).isEmpty();
    }

    @Test
    @DisplayName("Bizim tanımımız olmayan aday düşürülmez ama nişanı engellemez")
    void notOursDoesNotBlockMarker() {
        Rig r = new Rig();
        IndexFacts a = cand();
        r.db.put(copy(a, "btree", true, false, false, false, true, true, true, false, false, a.opclasses(), a.collations(), a.options()))
                .put(cover());
        RedundantIndexCleanup c = r.build();
        assertThat(c.runOnce()).isEqualTo(Outcome.DONE);
        assertThat(r.db.executed).isEmpty();
        assertThat(r.db.markers).containsEntry("test-marker", 0);
    }

    @Test
    @DisplayName("Kilit beklemesi aşılırsa (55P03) nişan yazılmaz; sonraki tur düşürür ve biter")
    void lockTimeoutRetriesNextRound() {
        Rig r = new Rig();
        r.db.put(cand()).put(cover());
        r.db.failState = ddl -> "55P03";
        RedundantIndexCleanup c = r.build();
        c.runAndCount();
        assertThat(c.failures()).isEqualTo(1);
        assertThat(c.finished()).isFalse();
        assertThat(r.db.markers).isEmpty();
        r.db.failState = ddl -> null;
        c.runAndCount();
        assertThat(c.finished()).isTrue();
        assertThat(r.db.markers).containsKey("test-marker");
    }

    @Test
    @DisplayName("Nişan zaten varsa hiçbir şey düşürülmez; bu pod biter")
    void markerPresent() {
        Rig r = new Rig();
        r.db.put(cand()).put(cover());
        r.db.markers.put("test-marker", 3);
        RedundantIndexCleanup c = r.build();
        c.runAndCount();
        assertThat(c.finished()).isTrue();
        assertThat(r.db.executed).isEmpty();
    }

    @Test
    @DisplayName("Kapı: eski sürüm canlı / pod sürümleri okunamaz → bekler (sayılmaz); geçmiş kapalı ya da servis yok (boş liste) → koşar")
    void versionGate() {
        Rig r = new Rig();
        r.db.put(cand()).put(cover());
        r.versions = () -> List.of(Map.of("pod", "pod-a", "version", "20.186.2"));
        RedundantIndexCleanup c = r.build();
        c.runAndCount();
        assertThat(c.failures()).isZero();
        assertThat(r.opens.get()).as("kapı oturumdan önce").isZero();
        r.versions = () -> null;
        c.runAndCount();
        assertThat(c.failures()).isZero();
        assertThat(r.opens.get()).isZero();
        r.versions = () -> { throw new IllegalStateException("okunamadı"); };
        assertThat(c.runOnce()).isEqualTo(Outcome.WAITING);
        r.versions = List::of;   // DeploymentHistoryService.liveOtherVersions() geçmiş kapalıyken List.of() döner
        c.runAndCount();
        assertThat(c.finished()).isTrue();
        assertThat(r.db.executed).hasSize(1);
    }

    @Test
    @DisplayName("Tür okunamaz → bekler (asla 'PostgreSQL değil → bitti'); PostgreSQL değil (H2) → bu pod'da biter, oturum açılmaz")
    void dialect() {
        Rig r = new Rig();
        r.pg = null;
        RedundantIndexCleanup c = r.build();
        c.runAndCount();
        assertThat(c.finished()).isFalse();
        assertThat(c.failures()).isZero();
        r.pg = false;
        c.runAndCount();
        assertThat(c.finished()).isTrue();
        assertThat(r.opens.get()).isZero();
        assertThat(r.db.markers).isEmpty();
    }

    @Test
    @DisplayName("SMIDXBLD başka oturumda ya da başka pod yama koşuyor → bekler (sayılmaz), hiçbir şey düşmez")
    void locksBusy() {
        Rig r = new Rig();
        r.db.put(cand()).put(cover());
        r.db.heldElsewhere.add(DeferredIndexBuilder.LOCK_KEY);
        RedundantIndexCleanup c = r.build();
        c.runAndCount();
        assertThat(c.failures()).isZero();
        assertThat(r.db.executed).isEmpty();
        assertThat(r.db.closed).isTrue();
        r.db.heldElsewhere.clear();
        r.db.heldElsewhere.add(SchemaPatchRunner.LOCK_KEY);
        assertThat(c.runOnce()).isEqualTo(Outcome.WAITING);
        assertThat(r.db.held).as("SMIDXBLD bırakıldı").isEmpty();
        assertThat(r.db.executed).isEmpty();
    }

    @Test
    @DisplayName("Oturum açılamaz / nişan okunamaz → hata sayılır, dışarı sızmaz")
    void failuresCount() {
        Rig r = new Rig();
        r.openFails = true;
        RedundantIndexCleanup c = r.build();
        c.runAndCount();
        assertThat(c.failures()).isEqualTo(1);
        r.openFails = false;
        r.db.markerReadFails = true;
        c.runAndCount();
        assertThat(c.failures()).isEqualTo(2);
        assertThat(r.db.held).isEmpty();
    }

    @Test
    @DisplayName("Kapanış: ContextClosedEvent sonrası yeni tur yok; süren düşürme cin yardımcıda iptal edilir, kalan hedeflere geçilmez")
    void shutdown() throws Exception {
        Rig r = new Rig();
        Target dns = new Target("idx_dnsr_monitor_id", "dns_records", List.of("monitor_id"));
        r.targets = List.of(PORT, dns);
        r.db.put(cand()).put(cover())
                .put(idx("dns_records", "idx_dnsr_monitor_id", List.of("monitor_id")))
                .put(idx("dns_records", "idx_dnsr_monitor_checked", List.of("monitor_id", "checked_at")));
        AtomicReference<RedundantIndexCleanup> self = new AtomicReference<>();
        r.db.beforeExecute = ddl -> self.get().onContextClosed(null);
        RedundantIndexCleanup c = r.build();
        self.set(c);
        assertThat(c.runOnce()).isEqualTo(Outcome.WAITING);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (r.db.cancels.get() == 0 && System.nanoTime() < until) Thread.sleep(10);
        assertThat(r.db.cancels.get()).as("süren deyim iptal edildi").isEqualTo(1);
        assertThat(r.db.ddls()).containsExactly("DROP INDEX CONCURRENTLY IF EXISTS public.idx_portc_monitor_id");
        assertThat(r.db.markers).isEmpty();
        int opens = r.opens.get();
        c.scheduled();
        assertThat(c.runOnce()).isEqualTo(Outcome.WAITING);
        assertThat(r.opens.get()).isEqualTo(opens);
    }

    @Test
    @DisplayName("Zamanlayıcı kapanıyorsa (sideJobsStopping) tur başlamaz")
    void schedulerStopping() {
        Rig r = new Rig();
        r.schedulerStopping.set(true);
        assertThat(r.build().runOnce()).isEqualTo(Outcome.WAITING);
        assertThat(r.opens.get()).isZero();
    }

    @Test
    @DisplayName("Yan iş: koşucu üstlenirse gövde satır içinde koşmaz (sabit gecikmeli iş, ad kalıcı); üstlenmezse koşar; kapalıyken hiçbir şey")
    void sideJob() {
        Rig r = new Rig();
        r.db.put(cand()).put(cover());
        r.offloadAccepts = true;
        RedundantIndexCleanup c = r.build();
        c.scheduled();
        assertThat(r.offloaded).singleElement().satisfies(j -> {
            assertThat(j.name()).isEqualTo("redundant-index-cleanup");
            assertThat(j.heavy()).isTrue();
            assertThat(j.fixedDelayMs()).isEqualTo(600_000L);
        });
        assertThat(r.opens.get()).isZero();
        r.offloaded.get(0).body().run();
        assertThat(c.finished()).isTrue();

        Rig inline = new Rig();
        inline.db.put(cand()).put(cover());
        RedundantIndexCleanup c2 = inline.build();
        c2.scheduled();
        assertThat(c2.finished()).isTrue();

        Rig off = new Rig();
        off.enabled = false;
        off.build().scheduled();
        assertThat(off.offloaded).isEmpty();
        assertThat(off.opens.get()).isZero();
    }

    @Test
    @DisplayName("Üretim hedef listesinin tamamı: yalnız doğrulananlar düşer; kapsayanı silinmiş aday kalır ve nişanı engeller")
    void productionTargetsOnRealisticCatalog() {
        Rig r = new Rig();
        r.targets = RedundantIndexCleanup.TARGETS;
        Map<String, List<String>> covers = Map.ofEntries(
                Map.entry("idx_pri_check_id", List.of("check_id")),
                Map.entry("idx_act_type_mon_time", List.of("monitor_type", "monitor_id", "activity_time")),
                Map.entry("idx_pc_monitor_checked", List.of("monitor_id", "checked_at")),
                Map.entry("idx_dnsr_monitor_checked", List.of("monitor_id", "checked_at")),
                Map.entry("idx_kwr_monitor_checked", List.of("monitor_id", "checked_at")),
                Map.entry("idx_pingc_monitor_checked", List.of("monitor_id", "checked_at")),
                Map.entry("idx_hc_monitor_checked", List.of("monitor_id", "checked_at")),
                Map.entry("idx_dc_monitor_checked", List.of("monitor_id", "checked_at")),
                Map.entry("idx_sc_monitor_checked", List.of("monitor_id", "checked_at")),
                Map.entry("idx_pri_monitor_checked", List.of("monitor_id", "checked_at")),
                Map.entry("idx_cc_domain_ts", List.of("domain", "checked_at")),
                Map.entry("idx_uc_domain_port_checked", List.of("domain", "port", "checked_at")));
        Map<String, String> coverTable = Map.ofEntries(
                Map.entry("idx_pri_check_id", "page_resource_issues"), Map.entry("idx_act_type_mon_time", "activity_log"),
                Map.entry("idx_pc_monitor_checked", "page_checks"), Map.entry("idx_dnsr_monitor_checked", "dns_records"),
                Map.entry("idx_kwr_monitor_checked", "keyword_results"), Map.entry("idx_pingc_monitor_checked", "ping_checks"),
                Map.entry("idx_hc_monitor_checked", "http_checks"), Map.entry("idx_dc_monitor_checked", "domain_checks"),
                Map.entry("idx_sc_monitor_checked", "scripted_checks"), Map.entry("idx_pri_monitor_checked", "page_resource_issues"),
                Map.entry("idx_cc_domain_ts", "certificate_checks"), Map.entry("idx_uc_domain_port_checked", "uptime_checks"));
        covers.forEach((n, cols) -> r.db.put(idx(coverTable.get(n), n, cols)));
        IndexFacts pk = idx("monitor_check_hourly", "monitor_check_hourly_pkey", List.of("monitor_type", "monitor_key", "hour_bucket"));
        r.db.put(copy(pk, "btree", true, true, false, true, true, true, true, false, false, pk.opclasses(), pk.collations(), pk.options()));
        for (Target t : RedundantIndexCleanup.TARGETS) r.db.put(idx(t.table(), t.index(), t.columns()));
        // port_checks'te kapsayan YOK (silinmiş) → idx_portc_monitor_id kalmalı
        RedundantIndexCleanup c = r.build();
        assertThat(c.runOnce()).isEqualTo(Outcome.INCOMPLETE);
        assertThat(r.db.ddls()).hasSize(14).noneMatch(d -> d.endsWith("idx_portc_monitor_id"));
        assertThat(r.db.indexes("port_checks")).extracting(IndexFacts::name).containsExactly("idx_portc_monitor_id");
        assertThat(r.db.markers).isEmpty();
        // kapsayan geri gelince (ör. açılış yaması) sonraki tur biter
        r.db.put(idx("port_checks", "idx_portc_monitor_checked", List.of("monitor_id", "checked_at")));
        assertThat(c.runOnce()).isEqualTo(Outcome.DONE);
        assertThat(r.db.markers).containsEntry("test-marker", 1);
        assertThat(r.db.tables.get("activity_log")).extracting(IndexFacts::name).containsExactly("idx_act_type_mon_time");
        assertThat(r.db.tables.get("monitor_check_hourly")).extracting(IndexFacts::name).containsExactly("monitor_check_hourly_pkey");
    }

    // ── 3. İnceleme düzeltmeleri (2026-11-06) ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Bekçi: düşürme başka bir oturumu bekletti → tur burada biter (WAITING, deneme sayılmaz, nişan yok); sonraki tur düşürür")
    void blockingOthersYieldsAndRetries() {
        Rig r = new Rig();
        Target second = new Target("idx_pc_monitor_id", "page_checks", List.of("monitor_id"));
        r.targets = List.of(PORT, second);
        r.db.put(cand()).put(cover())
                .put(idx("page_checks", "idx_pc_monitor_id", List.of("monitor_id")))
                .put(idx("page_checks", "idx_pc_monitor_checked", List.of("monitor_id", "checked_at")));
        AtomicBoolean block = new AtomicBoolean(true);
        r.db.failState = ddl -> block.get() && ddl.endsWith("idx_portc_monitor_id") ? FakeIndexCatalog.BLOCKING : null;
        RedundantIndexCleanup c = r.build();
        c.runAndCount();
        assertThat(c.failures()).as("bekleme sayılmaz").isZero();
        assertThat(c.finished()).isFalse();
        assertThat(r.db.ddls()).as("ikinci hedefe geçilmez").containsExactly("DROP INDEX CONCURRENTLY IF EXISTS public.idx_portc_monitor_id");
        assertThat(r.db.markers).isEmpty();
        assertThat(r.db.held).as("kilit bırakıldı").isEmpty();
        assertThat(r.db.closed).isTrue();
        block.set(false);
        c.runAndCount();
        assertThat(c.finished()).isTrue();
        assertThat(r.db.markers).containsEntry("test-marker", 2);
    }

    @Test
    @DisplayName("Zamanlama programla: aralık 0 / negatif / küçük → 60 sn, ilk gecikme negatif → 0 (açılış düşmez); kapalıyken kayıt yok")
    void schedulingIsClamped() {
        RedundantIndexCleanup zero = new RedundantIndexCleanup(List.of(PORT), "m", () -> true, () -> new FakeIndexCatalog(),
                List::of, () -> false, job -> false, true, 30_000, 0, -5);
        ScheduledTaskRegistrar reg = new ScheduledTaskRegistrar();
        zero.configureTasks(reg);
        assertThat(reg.getFixedDelayTaskList()).singleElement().satisfies(t -> {
            assertThat(t.getIntervalDuration()).isEqualTo(Duration.ofMillis(RedundantIndexCleanup.MIN_INTERVAL_MS));
            assertThat(t.getInitialDelayDuration()).isEqualTo(Duration.ZERO);
        });

        RedundantIndexCleanup neg = new RedundantIndexCleanup(List.of(PORT), "m", () -> true, () -> new FakeIndexCatalog(),
                List::of, () -> false, job -> false, true, 30_000, -1, 1_000);
        ScheduledTaskRegistrar reg2 = new ScheduledTaskRegistrar();
        neg.configureTasks(reg2);
        IntervalTask t2 = reg2.getFixedDelayTaskList().get(0);
        assertThat(t2.getIntervalDuration()).isEqualTo(Duration.ofMillis(60_000));
        assertThat(t2.getInitialDelayDuration()).isEqualTo(Duration.ofMillis(1_000));

        RedundantIndexCleanup normal = new RedundantIndexCleanup(List.of(PORT), "m", () -> true, () -> new FakeIndexCatalog(),
                List::of, () -> false, job -> false, true, 30_000, 600_000);
        ScheduledTaskRegistrar reg3 = new ScheduledTaskRegistrar();
        normal.configureTasks(reg3);
        assertThat(reg3.getFixedDelayTaskList().get(0).getIntervalDuration()).isEqualTo(Duration.ofMinutes(10));
        assertThat(reg3.getFixedDelayTaskList().get(0).getInitialDelayDuration()).isEqualTo(Duration.ofMinutes(3));

        RedundantIndexCleanup off = new RedundantIndexCleanup(List.of(PORT), "m", () -> true, () -> new FakeIndexCatalog(),
                List::of, () -> false, job -> false, false, 30_000, 0, -5);
        ScheduledTaskRegistrar reg4 = new ScheduledTaskRegistrar();
        off.configureTasks(reg4);
        assertThat(reg4.getFixedDelayTaskList()).isEmpty();
    }
}
