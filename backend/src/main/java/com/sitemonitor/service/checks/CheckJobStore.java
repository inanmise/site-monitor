package com.sitemonitor.service.checks;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Kontrol işi kuyruğu {@code monitor_check_jobs} (2026-10-29, çok pod Sürüm 3-3 P0 sözleşmesi, tasarım R3-3 §3.1 / §3.2;
 * paket A uygular: {@code JdbcCheckJobStore}). Yaşam döngüsü: PENDING (gönderildi) → CLAIMED (işçi sahiplendi) → DONE
 * (sonuç yazıldı) → satır değerlendiricinin {@link #take} çağrısıyla SİLİNİR. Bir izlemenin aynı anda en çok bir AÇIK
 * (PENDING / CLAIMED) işi olur (PostgreSQL {@code ux_mcj_open} kısmi tekil dizini; H2: {@code NOT EXISTS}).
 *
 * <p><b>Zaman.</b> Tüm ms değerleri {@link DbClock} ile (veritabanı-çapalı) — çağıranlar zaman vermez.
 *
 * <p><b>Hatalar.</b> Veritabanı hatası {@code org.springframework.dao.DataAccessException} (RuntimeException) olarak fırlar;
 * çağıran yakalar (gönderme: başarısızlık sayılır — gönderen sayar ve türü klasiğe döndürebilir; sahiplenme / tamamlama /
 * alma: sonraki tıkta yeniden — satırlar yerinde kalır). Tablo henüz yoksa (ilk açılışın ilk saniyeleri) sahiplenme / alma /
 * tamamlama / bırakma / biçme / özet boş / 0 döner; <b>gönderme fırlatır</b>
 * ({@code DataAccessResourceFailureException}) — gönderen başarısızlık saysın. Hiçbir yöntem {@code result_json}'u günlüğe
 * yazmaz.
 *
 * <p><b>Kapı.</b> Bu arayüzün HİÇBİR yöntemi ana anahtar kapalıyken çağrılmaz (K-1): çağıranlar önce
 * {@link DistributedChecks#anyDistributed()} (bellek) okur.
 */
public interface CheckJobStore {

    String STATE_PENDING = "PENDING";
    String STATE_CLAIMED = "CLAIMED";
    String STATE_DONE = "DONE";

    /** {@link #claim} ölçüt: "oluşturma anından bağımsız" (normal sahiplenme). */
    long ANY_AGE = Long.MAX_VALUE;

    /**
     * Gönderme (yalnız tarama lideri; tür başına tık başına bir deyim — PostgreSQL çok satırlı {@code VALUES … ON CONFLICT DO
     * NOTHING} 200'lük parçalarla, H2 satır başına {@code INSERT … SELECT … WHERE NOT EXISTS}).
     *
     * @param type       {@link CheckJobTypes} adı
     * @param monitorIds vadesi gelmiş izlemeler (klasik kapılardan ve {@code checkDue} CAS'ından geçmiş); boş → 0, deyim yok
     * @param leaseMs    işin kirası ({@link CheckJobTypes#leaseMs})
     * @param dispatcher gönderen liderin örnek kimliği ({@code SchedulerService.instanceId()})
     * @return eklenen satır sayısı (açık işi olan izleme sayılmaz — dönem açık işe katılır)
     */
    int dispatch(String type, Collection<Long> monitorIds, long leaseMs, String dispatcher);

    /**
     * Gönderme yarıda düştü (R3-3 son inceleme F1): parçalar ayrı ayrı yazılır (200'lük, kendiliğinden işlem) — önceki
     * parçalar YAZILDI ({@link #committed()}), düşen ve sonraki parçalar yazılmadı. Gönderen yalnız yazılmayanları bekletir; yazılan
     * kimlikleri yeniden sunmak, o işler bitip alınmışsa (açık iş kalmadığı için çakışma yok) aynı dönemde ikinci kontrol
     * olurdu. Sonucu bilinmeyen tek parça (yazıldı ama istemci hata gördü) kabul edilen kalıntıdır.
     */
    final class PartialDispatchException extends org.springframework.dao.DataAccessResourceFailureException {
        private final transient List<Long> committed;

        public PartialDispatchException(String msg, Throwable cause, List<Long> committed) {
            super(msg, cause);
            this.committed = committed == null ? List.of() : List.copyOf(committed);
        }

        /** Yazılmış (işlemi tamamlanmış) parçaların izleme kimlikleri. */
        public List<Long> committed() {
            return committed;
        }
    }

    /**
     * Sahiplenme (her pod; PostgreSQL {@code UPDATE … WHERE id IN (SELECT … ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED)
     * RETURNING}, H2 aday SELECT + satır başına CAS). Adaylar: türü {@code types} içinde, PENDING ya da kirası dolmuş CLAIMED,
     * {@code attempts < MAX_ATTEMPTS}, {@code created_ms < createdBeforeMs}; en eski {@code id} önce, en çok {@code limit}.
     * Tek belirteç tüm sahiplenmeye yazılır; {@code attempts + 1}; {@code lease_until_ms = şimdi + lease_ms}.
     *
     * @param types           bu pod'un sahiplenebildiği türler (yetenek süzülmüş; boş → boş liste, deyim yok)
     * @param limit           en çok kaç iş (≤ 0 → boş liste, deyim yok)
     * @param owner           bu pod'un örnek kimliği ({@code SchedulerService.instanceId()})
     * @param createdBeforeMs yalnız bundan ÖNCE oluşturulanlar ({@link #ANY_AGE} = hepsi; liderin "çal" sahiplenmesi için
     *                        {@code now − steal-after-ms})
     * @return sahiplenilen işler {@code id} sırasında (RETURNING sırası garanti değil — uygulayıcı sıralar)
     */
    List<CheckJobRow> claim(Collection<String> types, int limit, String owner, long createdBeforeMs);

    /** {@link #claim(Collection, int, String, long)} — yaş ölçütsüz ({@link #ANY_AGE}). */
    default List<CheckJobRow> claim(Collection<String> types, int limit, String owner) {
        return claim(types, limit, owner, ANY_AGE);
    }

    /**
     * Tamamlama sonucu.
     *
     * @param id          iş kimliği
     * @param claimToken  sahiplenmenin belirteci ({@link CheckJobRow#claimToken()})
     * @param resultJson  {@link CheckResultCodec#encode} çıktısı
     * @param truncated   kodlayıcı kırptı mı
     * @param worker      tamamlayan pod'un örnek kimliği
     */
    record Completion(long id, String claimToken, String resultJson, boolean truncated, String worker) {
        @Override
        public String toString() {
            return "Completion[id=" + id + ", worker=" + worker + ", truncated=" + truncated + "]";
        }
    }

    /**
     * Toplu tamamlama (işçi, ~200 ms'de bir JDBC toplu deyimi): {@code UPDATE … SET state = 'DONE', result_json, …,
     * lease_until_ms = NULL WHERE id = ? AND state = 'CLAIMED' AND claim_token = ?}. 0 satır = kira kaybedildi (başka işçi
     * yeniden aldı) — sonuç düşer ({@code late}); kontrolün geçmiş satırı zaten yazıldı (kabul edilen kalıntı R-4).
     *
     * @return {@code completions} ile AYNI sırada, her biri için yazıldı mı (sürücü {@code SUCCESS_NO_INFO} derse true)
     */
    boolean[] complete(List<Completion> completions);

    /**
     * Kapanışta bırakma (işçi, {@code ContextClosedEvent} sonrası yardımcı iş parçacığında, beklemesiz): BAŞLAMAMIŞ sahiplenilmiş
     * işleri PENDING'e döndürür ({@code attempts − 1}, belirteç / kira temizlenir) — koşullar {@code claimed_by = owner AND
     * state = 'CLAIMED' AND id IN (…)}.
     *
     * @return bırakılan satır sayısı
     */
    int release(String owner, Collection<Long> ids);

    /**
     * Alma (yalnız değerlendirici = kira sahibi): DONE satırları {@code id} sırasında, en çok {@code limit}, kira-bağlı
     * {@code DELETE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED) AND EXISTS (SELECT 1 FROM monitor_check_schedule WHERE
     * monitor_key = 'lease:sweep-leader' AND claimed_by = ?) RETURNING …} — kirayı kaybetmiş pod hiçbir şey almaz. Satır
     * silinir: tam bir kez değerlendirilir.
     *
     * @param limit      en çok kaç satır (≤ 0 → boş)
     * @param leaseOwner tarama lideri kirasındaki sahip dizesi ({@code ClusterScheduleService.owner()})
     * @return alınan sonuçlar {@code id} artan sırada
     */
    List<CheckResultRow> take(int limit, String leaseOwner);

    /**
     * Biçme (lider, her gönderme tıkında): kirası dolmuş VE {@code attempts >= MAX_ATTEMPTS} CLAIMED satırları siler
     * (en çok {@code limit}) — iki kez sahiplenilip tamamlanmamış iş düşer ({@code lost}; çağıran hız sınırlı WARN yazar:
     * {@code Kontrol işi {n} kez sahiplenildi, tamamlanmadı — düşürüldü (tür, izleme)}). Ayrıca bitişi 2 ×
     * {@code result-stale-ms}'ten eski DONE satırları siler (en çok {@code limit}; dönen listede DEĞİL — boş liste "hiçbir şey
     * biçilmedi" demek değildir).
     *
     * @return silinen işler (yalnız {@code id}, {@code type}, {@code monitorId}, {@code attempts}, {@code createdMs} dolu)
     */
    List<CheckJobRow> reap(int limit);

    /**
     * Kuyruk durumu (yalnız anahtar açıkken; tek gruplu deyim {@code SELECT state, job_type, COUNT(*), MIN(created_ms),
     * MIN(finished_ms) … GROUP BY state, job_type} + kirası dolmuş sahiplenme sayısı). System Health / Küme kartı / ölçümler
     * (paket D) okur.
     */
    QueueStats stats();

    /**
     * Gruplu satır.
     *
     * @param state          PENDING / CLAIMED / DONE
     * @param type           tür
     * @param count          satır sayısı
     * @param minCreatedMs   en eski oluşturma (DB saati, ms; satır yoksa null)
     * @param minFinishedMs  en eski bitiş (yalnız DONE; diğerlerinde null)
     */
    record QueueStat(String state, String type, long count, Long minCreatedMs, Long minFinishedMs) {}

    /**
     * Kuyruk özeti.
     *
     * @param nowMs                 okuma anı (DB saati, ms)
     * @param rows                  gruplu satırlar (≤ 24)
     * @param oldestPendingAgeMs    en eski PENDING işin yaşı (oluşturmadan; yoksa null)
     * @param oldestDoneAgeMs       en eski DONE sonucun bekleme yaşı (bitişten; yoksa null)
     * @param expiredClaims         kirası dolmuş CLAIMED satır sayısı
     */
    record QueueStats(long nowMs, List<QueueStat> rows, Long oldestPendingAgeMs, Long oldestDoneAgeMs, long expiredClaims) {
        public QueueStats {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }

        /** Durum + tür sayısı (yoksa 0). */
        public long count(String state, String type) {
            long n = 0;
            for (QueueStat r : rows) if (r.state().equals(state) && r.type().equals(type)) n += r.count();
            return n;
        }

        /** Durumun toplam sayısı (tüm türler). */
        public long count(String state) {
            long n = 0;
            for (QueueStat r : rows) if (r.state().equals(state)) n += r.count();
            return n;
        }

        /** Satırı olan türler. */
        public Set<String> types() {
            Set<String> t = new java.util.TreeSet<>();
            for (QueueStat r : rows) t.add(r.type());
            return t;
        }
    }
}
