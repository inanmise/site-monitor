package com.sitemonitor.repository;

import com.sitemonitor.model.NocDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface NocDeliveryRepository extends JpaRepository<NocDelivery, Long> {

    Optional<NocDelivery> findByDedupeKey(String dedupeKey);

    List<NocDelivery> findByDedupeKeyIn(Collection<String> keys);

    /** Fırtınanın EN SON güncelleme e-postası — güncellemeler arası en az aralık (fırtına başına ~5 dk). */
    Optional<NocDelivery> findTopByStormIdAndPhaseOrderByIdDesc(Long stormId, String phase);

    /**
     * VAR OLAN teslim satırının yeniden sahiplenmesi (2026-10-21, çok pod — N-10): satır OKUNDUĞU durumdaysa
     * ({@code status} + {@code updated_at} aynı) "gönderiliyor"a çekilir — tek yazar kazanır. Eskiden okunan varlık koşulsuz
     * kaydediliyordu ({@code @Version} yok): iki pod aynı FAILED / bayat SENDING satırını aynı anda yeniden deneyip 7/24
     * postasını iki kez gönderebiliyordu. NULL alanlar boş metin olarak karşılaştırılır (sayaçtan önceki satırlar). Deneme
     * sayacı: kayıtlı satırın NULL'u bir deneme sayılır ({@code NocNotificationService.attemptsOf} ile aynı).
     */
    @Modifying
    @Transactional
    @Query("UPDATE NocDelivery d SET d.status = 'SENDING', d.attempts = COALESCE(d.attempts, 1) + 1, d.updatedAt = :now "
         + "WHERE d.id = :id AND COALESCE(d.status, '') = :seenStatus AND COALESCE(d.updatedAt, '') = :seenAt")
    int reclaim(@Param("id") Long id, @Param("seenStatus") String seenStatus, @Param("seenAt") String seenAt,
                @Param("now") String now);

    /**
     * Sonucu bekleyen SMTP 421 kuyruk teslimleri (2026-10-21, çok pod R1 / MP-06): {@code status} hâlâ {@code QUEUED_RETRY…}
     * olan, {@code created_at}'i verilen andan yeni satırlar, en eski önce, en çok 200 ({@code idx_noc_delivery_created}).
     * {@code NocQueuedRetryReconciler} okur.
     */
    List<NocDelivery> findTop200ByStatusStartingWithAndCreatedAtGreaterThanEqualOrderByIdAsc(String statusPrefix, String since);

    /**
     * Kuyruktaki teslimin KESİN sonucunu yazar (2026-10-21, çok pod R1 / MP-06) — {@link #reclaim} ile AYNI koşul: satır
     * okunduğu durumdaysa ({@code status} + {@code updated_at}) tek yazar kazanır; arada yeniden sahiplenilmiş / sonuçlanmış
     * satıra dokunulmaz. Deneme sayacı değişmez (yeni deneme değil, mevcut denemenin sonucu).
     */
    @Modifying
    @Transactional
    @Query("UPDATE NocDelivery d SET d.status = :status, d.updatedAt = :now "
         + "WHERE d.id = :id AND COALESCE(d.status, '') = :seenStatus AND COALESCE(d.updatedAt, '') = :seenAt")
    int finishQueuedRetry(@Param("id") Long id, @Param("seenStatus") String seenStatus, @Param("seenAt") String seenAt,
                          @Param("status") String status, @Param("now") String now);
}
