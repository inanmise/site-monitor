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
 * <p><b>Hata.</b> Bu sınıf istisnayı YUTMAZ: çağıran ({@code SchedulerService}) yakalar ve WARN yazar. 2026-10-21 (çok pod
 * R1-A, MP-11): yalnız tablo YOKSA ({@code BadSqlGrammarException}) eski bellek içi davranışa düşülür; geçici hatada güvenli
 * taraf — tur atlanır, izleme vadesi yalnız doğrulanmış liderin güvenli penceresinde yerelde karar verilir, takipçi asla
 * kendini lider saymaz.
 *
 * <p><b>İki aşamalı tur (2026-10-21, MP-15):</b> {@link #claimRun} turu koşu kirasıyla sahiplenir, {@link #complete} başarılı
 * koşuyu ızgara vadesine taşır; yarıda kalan tur kira dolunca yeniden koşar.
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

    // ── İki aşamalı tur (2026-10-21, çok pod R1-A, MP-15) ────────────────────────────────────────────────────────────

    /** Koşu tamamlandı: satır hâlâ bu koşunun bıraktığı değerdeyse (ve bu sahipteyse) sıradaki ızgara vadesine ilerler. */
    static final String SQL_COMPLETE = "UPDATE monitor_check_schedule SET next_due_at = ? "
            + "WHERE monitor_key = ? AND next_due_at = ? AND claimed_by = ?";

    /**
     * Turun KOŞU sahiplenmesi (iki aşamanın ilki): vadesi gelmiş satır {@code şimdi + runLeaseMs}'e ilerletilir — tur
     * yarıda kalırsa (pod öldü, liderlik kaybedildi, istisna) satır runLease sonunda yeniden vadesi gelmiş olur ve başka
     * (ya da aynı) pod turu yeniden koşar. Başarıyla biten tur {@link #complete} ile ızgaradaki asıl vadeye ilerler.
     * Eşzamanlılık {@link #claim} ile aynı (koşullu UPDATE / ilk görüşte ON CONFLICT DO NOTHING).
     *
     * @param runLeaseMs koşu kirası — turun nominal aralığından BÜYÜK olmamalı (yoksa {@link #isDue} "aralık kısaltıldı"
     *                   sanar)
     * @param prevSink   kazanılan sahiplenmede satırın ÖNCEKİ vadesi buraya yazılır (ilk görüşte {@code nowMs}); ızgara bu
     *                   değerden hesaplanır — {@code null} olabilir
     * @return sahiplenme sonucu; {@code nextDueAt} = koşu kirasının değeri ({@link #complete}'in beklediği değer)
     */
    public Claim claimRun(String key, long nowMs, long nominalMs, Long knownNextDue, long runLeaseMs, long[] prevSink) {
        long lease = nowMs + Math.max(1L, runLeaseMs);
        return claim(key, nowMs, nominalMs, knownNextDue, prev -> {
            if (prevSink != null && prevSink.length > 0) prevSink[0] = prev;
            return lease;
        });
    }

    /**
     * Koşunun tamamlanması (iki aşamanın ikincisi): satır hâlâ {@code leaseValue}'da ve bu sahipteyse {@code nextDueAt}'e
     * ilerletilir. 0 satır → başka pod bu arada turu yeniden sahiplendi (koşu kirası dolmuştu) ya da satır silindi — tur
     * yine de koşmuş olur, satıra dokunulmaz.
     *
     * @return satır ilerletildi mi
     */
    public boolean complete(String key, long leaseValue, long nextDueAt) {
        return jdbc.update(SQL_COMPLETE, nextDueAt, key, leaseValue, owner) == 1;
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

    // ── Salt okunur satır (2026-10-23, çok pod R3-1 3e) ───────────────────────────────────────────────────────────

    static final String SQL_ROW_READ = "SELECT next_due_at, claimed_by, claimed_at FROM monitor_check_schedule "
            + "WHERE monitor_key = ?";

    /**
     * Bir satırın anlık hâli: vade, son sahiplenen pod ve sahiplenme anı (epoch ms, sahiplenenin saati). Yalnız TANI ve
     * yerleşim kanıtı içindir ("bu turu son kim, ne zaman aldı" — {@code SchedulerService.leaderYieldsRound}); karar yine
     * {@link #claim} / {@link #claimRun}'ın koşullu UPDATE'idir.
     *
     * @param claimedBy {@code claimed_by} (null olabilir)
     * @param claimedAt {@code claimed_at} (null = bilinmiyor)
     */
    public record Row(String key, long nextDueAt, String claimedBy, Long claimedAt) {}

    /**
     * Anahtarın satırını TEK birincil anahtar okumasıyla döner (R3-1 3e — yan iş yerleşimi); satır yoksa null. Yazmaz.
     *
     * @throws org.springframework.dao.DataAccessException veritabanı hatasında (çağıran "kanıt yok" sayar)
     */
    public Row row(String key) {
        List<Row> rows = jdbc.query(SQL_ROW_READ, (rs, i) -> {
            long claimedAt = rs.getLong("claimed_at");
            Long at = rs.wasNull() ? null : claimedAt;
            return new Row(key, rs.getLong("next_due_at"), rs.getString("claimed_by"), at);
        }, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── Tur tamamlanma damgası (2026-10-23, çok pod R3-1 FX-SIDE #4) ──────────────────────────────────────────────

    /**
     * Tamamlanma damgası anahtarının son eki: {@code round:<ad>:done} — KALICI biçim. {@value #ROUND_PREFIX} önekiyle yaşar,
     * böylece {@link #prune} (ve 20.167.0 pod'larının gece budaması) silmez. Satır: {@code claimed_by} = turu SON normal bitiren
     * pod, {@code claimed_at} = bitiş anı (o pod'un saati), {@code next_due_at} = aynı an (vade DEĞİL — hiçbir tur bu satırı
     * sahiplenmez). Liderin "takipçi bu turu gerçekten koşturuyor" kanıtı yalnız bu satırdır (sahiplenme değil).
     */
    public static final String DONE_SUFFIX = ":done";

    /** Bir tur anahtarının ({@code round:<ad>}) tamamlanma damgası anahtarı. */
    public static String doneKey(String roundKey) {
        return roundKey + DONE_SUFFIX;
    }

    /** Daha yeni bir damgayı ezmez (iki pod'un yarışında en yeni bitiş kalır). */
    static final String SQL_DONE_UPDATE = "UPDATE monitor_check_schedule SET next_due_at = ?, claimed_by = ?, claimed_at = ? "
            + "WHERE monitor_key = ? AND (claimed_at IS NULL OR claimed_at <= ?)";

    /**
     * Turun bu pod'da NORMAL bittiğini damgalar ({@link #doneKey}): satır varsa ve damgası bundan eski değilse güncellenir,
     * yoksa eklenir ({@code ON CONFLICT DO NOTHING} — eşzamanlı ekleme kaybeden tarafta hata değil). Yalnız tanı / yerleşim
     * kanıtıdır; hiçbir kararın kilidi değildir.
     *
     * @return satır yazıldı mı (false = daha yeni bir damga var)
     * @throws org.springframework.dao.DataAccessException veritabanı hatasında (çağıran yutar — kanıt yoksa lider bırakmaz)
     */
    public boolean markDone(String roundKey, long nowMs) {
        String key = doneKey(roundKey);
        if (jdbc.update(SQL_DONE_UPDATE, nowMs, owner, nowMs, key, nowMs) == 1) return true;
        return jdbc.update(SQL_INSERT, key, nowMs, owner, nowMs) == 1;
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
