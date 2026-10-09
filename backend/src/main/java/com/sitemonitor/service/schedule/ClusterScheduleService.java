package com.sitemonitor.service.schedule;

import com.sitemonitor.service.SchedulerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.LongUnaryOperator;

/**
 * Küme geneli zamanlama kararı (2026-10-09): "bu izleme şimdi yoklanmalı mı?" ve "bu süpürme turu şimdi koşmalı mı?"
 * sorularının cevabı artık pod'un belleğinde değil, VERİTABANINDA verilir.
 *
 * <p><b>Neden.</b> Her pod kendi bellek içi vade haritasını ({@code SchedulerService.lastMonitorCheckAt}) tutuyordu.
 * Süpürme kilitleri ({@code scheduler_lock}) yalnız ÇAKIŞAN koşuları engelliyordu: A pod'u turu bitirip kilidi
 * bırakınca B pod'u kilidi alıyor ve kendi haritasına göre her izlemeyi "vadesi gelmiş" sayıyordu. 2 pod'da her
 * port/ping/DNS/keyword/http/sayfa/senaryo/sayfa hızı/alan adı izlemesi aralık başına İKİ KEZ yoklanıyor (çift dış
 * yük, çift geçmiş satırı, RDAP 429 riski), saatlik erişilebilirlik turu saatte iki kez koşuyordu. Yuvarlanan
 * dağıtımda (eski ve yeni pod üst üste) aynısı tek pod'lu kurulumda da oluyordu.
 *
 * <p><b>Model.</b> {@value #TABLE} tablosunda anahtar başına TEK satır: {@code monitor_key} ("port:12", tur için
 * "round:uptime"), {@code next_due_at} (epoch ms — sıradaki vade), {@code claimed_by} / {@code claimed_at} (son
 * sahiplenen pod ve anı; yalnız tanı içindir, karar vermez). Vadesi gelen satırı ilerletmek KOŞULLU bir UPDATE'tir
 * ({@code WHERE next_due_at = <okunan değer>}, karşılaştır-ve-değiştir): aynı değeri okuyan iki pod'dan yalnız biri
 * 1 satır günceller, diğeri 0 alır → aralık başına tam bir yoklama. PostgreSQL READ COMMITTED'da ikinci UPDATE
 * birincinin commit'ini bekler ve koşulu YENİ satır üzerinde yeniden değerlendirir; ilk görüş {@code INSERT … ON
 * CONFLICT DO NOTHING} ile yine tek kazanana düşer (H2 PostgreSQL kipinde de aynı sözdizimi çalışır).
 *
 * <p><b>Zaman.</b> Vadeler pod saatinden (epoch ms) yazılır; pod'lar NTP'li olduğundan aradaki kayma
 * {@link #SKEW_SLACK_MS}'in çok altındadır. Saklanan vade "şimdi + nominal aralık + pay"dan daha ilerideyse (aralık
 * kısaltıldı ya da cron değişti) satır hemen vadesi gelmiş sayılır — bellek içi haritada bunu yeniden başlatma
 * düzeltiyordu, kalıcı tabloda düzeltecek başka bir şey yok.
 *
 * <p><b>Tarama liderliği.</b> Aynı tabloda "lease:sweep-leader" satırı bir KİRA tutar ({@link #acquireLease}): izleme
 * taramaları (alarm hattı {@code MonitoringOutageService}'i besleyen her şey) yalnız kiracı pod'da koşar, çünkü o
 * servisin teyit/kurtarma sayaçları ve kardeş gözlemleri pod belleğindedir — yoklamalar pod'lara bölünseydi her pod
 * resmin yalnız bir parçasını görürdü. Vade kayıtları kirayla birlikte kalır: devir ve çakışma anında da izleme aralık
 * başına bir kez yoklanır, zamanlama yeniden başlatmada korunur.
 *
 * <p><b>Hata.</b> Bu sınıf istisnayı YUTMAZ: çağıran ({@code SchedulerService}) yakalar, WARN yazar ve o çağrı için
 * eski bellek içi davranışa düşer — izleme hiçbir koşulda durmaz.
 *
 * <p>Tablo ham DDL ile kurulur ({@link #DDL}, {@code SchedulerService.applySchemaPatches}); entity yok
 * ({@code scheduler_lock} ile aynı desen). Silinen izlemelerin satırlarını gece temizliği {@link #prune} ile atar.
 */
@Slf4j
@Service
public class ClusterScheduleService {

    public static final String TABLE = "monitor_check_schedule";

    /** Ham DDL — açılış yaması ve H2 testleri AYNI tanımı kullanır (tek kaynak). */
    public static final String DDL = "CREATE TABLE IF NOT EXISTS monitor_check_schedule ("
            + "monitor_key VARCHAR(100) NOT NULL PRIMARY KEY, "
            + "next_due_at BIGINT NOT NULL, "
            + "claimed_by VARCHAR(200), "
            + "claimed_at BIGINT)";

    /** Süpürme turu anahtarlarının öneki — izleme anahtarlarıyla aynı tabloda yaşar, budamaya girmez. */
    public static final String ROUND_PREFIX = "round:";

    /** Kira (lease) anahtarlarının öneki — "lease:sweep-leader"; budamaya girmez. Satırda {@code claimed_by} kiracı,
     *  {@code next_due_at} kiranın bitişi (epoch ms). */
    public static final String LEASE_PREFIX = "lease:";

    /** Pod saatleri arasındaki kabul edilen kayma; vade bundan fazla "ileride" ise aralık değişmiş sayılır. */
    public static final long SKEW_SLACK_MS = 120_000L;

    static final String SQL_SELECT = "SELECT next_due_at FROM monitor_check_schedule WHERE monitor_key = ?";
    static final String SQL_CAS = "UPDATE monitor_check_schedule SET next_due_at = ?, claimed_by = ?, claimed_at = ? "
            + "WHERE monitor_key = ? AND next_due_at = ?";
    static final String SQL_INSERT = "INSERT INTO monitor_check_schedule(monitor_key, next_due_at, claimed_by, claimed_at) "
            + "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING";
    static final String SQL_ALL_KEYS = "SELECT monitor_key FROM monitor_check_schedule";
    static final String SQL_DELETE = "DELETE FROM monitor_check_schedule WHERE monitor_key = ?";
    /** Kira al/yenile: satır bende ya da süresi dolmuş → bana geçer (PostgreSQL ikinci UPDATE'te koşulu yeniden değerlendirir). */
    static final String SQL_LEASE_TAKE = "UPDATE monitor_check_schedule SET next_due_at = ?, claimed_by = ?, claimed_at = ? "
            + "WHERE monitor_key = ? AND (claimed_by = ? OR next_due_at < ?)";
    static final String SQL_LEASE_RELEASE = "DELETE FROM monitor_check_schedule WHERE monitor_key = ? AND claimed_by = ?";
    static final String SQL_LEASE_CLEAR_HOST = "DELETE FROM monitor_check_schedule WHERE monitor_key = ? "
            + "AND claimed_by LIKE ? AND claimed_by <> ?";
    static final String SQL_LEASE_READ = "SELECT claimed_by, next_due_at FROM monitor_check_schedule WHERE monitor_key = ?";

    private static final int PRUNE_CHUNK = 500;

    /**
     * Sahiplenme sonucu.
     *
     * @param due       true → bu pod sahiplendi, şimdi yoklamalı; false → vadesi gelmedi ya da başka pod aldı
     * @param nextDueAt satırın bilinen son vadesi (önbellek için); bilinmiyorsa null (yarış kaybedildi)
     */
    public record Claim(boolean due, Long nextDueAt) {}

    private final JdbcTemplate jdbc;
    private final String owner;

    @Autowired
    public ClusterScheduleService(JdbcTemplate jdbc) {
        this(jdbc, SchedulerService.instanceId());
    }

    /** Testler: aynı veritabanını paylaşan iki "pod"u farklı sahip kimlikleriyle canlandırır. */
    public ClusterScheduleService(JdbcTemplate jdbc, String owner) {
        this.jdbc = jdbc;
        this.owner = owner;
    }

    /** Bu örneğin {@code claimed_by} değeri. */
    public String owner() {
        return owner;
    }

    /**
     * Saklanan vade şimdi koşulmalı mı: vade geldi ya da (aralık kısaltıldığı için) bir nominal aralık + kayma
     * payından daha ileride kaldı.
     */
    public static boolean isDue(long nextDueAt, long nowMs, long nominalMs) {
        if (nextDueAt <= nowMs) return true;
        return nextDueAt - nowMs > Math.max(0L, nominalMs) + SKEW_SLACK_MS;
    }

    /**
     * Anahtarı bu pod adına atomik olarak sahiplenmeyi dener.
     *
     * @param key          "type:id" ya da "round:&lt;ad&gt;"
     * @param nowMs        çağıranın saati
     * @param nominalMs    anahtarın nominal aralığı (kısaltılmış aralık tespiti — {@link #isDue})
     * @param knownNextDue bu pod'un bildiği son vade (önbellek); biliniyorsa önce TEK sorguyla CAS denenir
     * @param advance      vadesi gelen değerden yeni vadeyi üretir; ilk görüşte {@code nowMs} ile çağrılır
     * @return sahiplenme sonucu
     * @throws org.springframework.dao.DataAccessException veritabanı hatasında (çağıran eski davranışa düşer)
     */
    public Claim claim(String key, long nowMs, long nominalMs, Long knownNextDue, LongUnaryOperator advance) {
        // 1) Kararlı durum (tek pod): bildiğimiz değer hâlâ satırdaysa tek UPDATE yeter.
        if (knownNextDue != null && isDue(knownNextDue, nowMs, nominalMs)) {
            long next = advance.applyAsLong(knownNextDue);
            if (jdbc.update(SQL_CAS, next, owner, nowMs, key, knownNextDue) == 1) return new Claim(true, next);
        }
        // 2) Satırın güncel değerini oku (başka pod ilerletmiş olabilir ya da satır hiç yok).
        List<Long> rows = jdbc.queryForList(SQL_SELECT, Long.class, key);
        if (rows.isEmpty() || rows.get(0) == null) {
            long next = advance.applyAsLong(nowMs);
            if (jdbc.update(SQL_INSERT, key, next, owner, nowMs) == 1) return new Claim(true, next);
            List<Long> again = jdbc.queryForList(SQL_SELECT, Long.class, key);   // eşzamanlı başka pod ekledi
            return new Claim(false, again.isEmpty() ? null : again.get(0));
        }
        long current = rows.get(0);
        if (!isDue(current, nowMs, nominalMs)) return new Claim(false, current);
        long next = advance.applyAsLong(current);
        if (jdbc.update(SQL_CAS, next, owner, nowMs, key, current) == 1) return new Claim(true, next);
        return new Claim(false, null);   // yarışı başka pod kazandı — değeri bir sonraki turda okunur
    }

    // ── Kira (lease) — tarama liderliği ───────────────────────────────────────────────────────────────────────────

    /** Bir kiranın anlık durumu (System Health). */
    public record Lease(String owner, long untilMs) {}

    /**
     * Kirayı bu pod adına alır ya da yeniler: satır yoksa eklenir; varsa yalnız sahibi bensem ya da süresi dolmuşsa
     * bana geçer. İki pod aynı anda süresi dolmuş kiraya uzanırsa yalnız biri 1 satır günceller.
     *
     * @return true → kira bende ({@code nowMs + ttlMs}'e kadar); false → başka pod'da ve süresi dolmamış
     * @throws org.springframework.dao.DataAccessException veritabanı hatasında (çağıran yerel koşuya düşer)
     */
    public boolean acquireLease(String name, long nowMs, long ttlMs) {
        String key = LEASE_PREFIX + name;
        long until = nowMs + Math.max(1L, ttlMs);
        if (jdbc.update(SQL_LEASE_TAKE, until, owner, nowMs, key, owner, nowMs) == 1) return true;
        return jdbc.update(SQL_INSERT, key, until, owner, nowMs) == 1;
    }

    /** Kirayı bırakır (yalnız bendeyse) — zarif kapanışta devir beklemesin. @return satır silindi mi */
    public boolean releaseLease(String name) {
        return jdbc.update(SQL_LEASE_RELEASE, LEASE_PREFIX + name, owner) > 0;
    }

    /**
     * Aynı makinedeki ÖNCEKİ örneğin (çöküp {@code @PreDestroy} koşturamamış) kirasını siler — bir host'ta aynı anda
     * tek uygulama koşar ({@code SchedulerService.clearStaleLocksForThisHost} ile aynı varsayım). Böylece tek pod'un
     * kaba yeniden başlatması taramaları kira süresi boyunca durdurmaz.
     */
    public int clearLeaseOfPreviousInstance(String name, String hostOwnerPrefix) {
        return jdbc.update(SQL_LEASE_CLEAR_HOST, LEASE_PREFIX + name, hostOwnerPrefix + "%", owner);
    }

    /** Kiranın anlık sahibi ve bitişi; satır yoksa null. */
    public Lease lease(String name) {
        List<Lease> rows = jdbc.query(SQL_LEASE_READ,
                (rs, i) -> new Lease(rs.getString("claimed_by"), rs.getLong("next_due_at")), LEASE_PREFIX + name);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Canlı izleme kümesinde olmayan anahtarların satırlarını siler (silinen izlemeler birikmesin). Tur
     * ({@value #ROUND_PREFIX}) ve kira ({@value #LEASE_PREFIX}) satırlarına dokunmaz. Gece temizliğinden, tek pod'da
     * çağrılır.
     *
     * @return silinen satır sayısı
     */
    public int prune(Set<String> liveMonitorKeys) {
        List<String> keys = jdbc.queryForList(SQL_ALL_KEYS, String.class);
        List<Object[]> stale = new ArrayList<>();
        for (String k : keys) {
            if (k == null || k.startsWith(ROUND_PREFIX) || k.startsWith(LEASE_PREFIX) || liveMonitorKeys.contains(k)) continue;
            stale.add(new Object[]{k});
        }
        int deleted = 0;
        for (int i = 0; i < stale.size(); i += PRUNE_CHUNK) {
            for (int n : jdbc.batchUpdate(SQL_DELETE, stale.subList(i, Math.min(stale.size(), i + PRUNE_CHUNK)))) {
                if (n > 0) deleted += n;
            }
        }
        if (deleted > 0) log.debug("Küme zamanlama tablosu: {} silinmiş izleme satırı budandı", deleted);
        return deleted;
    }
}
