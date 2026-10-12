package com.sitemonitor.repository;

import com.sitemonitor.model.TlsGradeChange;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** TLS notu değişim günlüğü (2026-10-10). Yazma yalnız {@code saveAll}; silme gece temizliğinde (RetentionCatalog). */
public interface TlsGradeChangeRepository extends JpaRepository<TlsGradeChange, Long> {

    /** Bir kaydın son değişimleri (sertifika penceresi not geçmişi). */
    List<TlsGradeChange> findTop20ByInventoryIdOrderByChangedAtDescIdDesc(Long inventoryId);

    /** Pencere içindeki değişimler, yeniden eskiye — "son düşüşler" (kapsam süzgeci çağıranda). */
    @Query("SELECT c FROM TlsGradeChange c WHERE c.changedAt >= :since AND c.direction = :direction "
            + "ORDER BY c.changedAt DESC, c.id DESC")
    List<TlsGradeChange> findRecent(@Param("since") String since, @Param("direction") String direction, Pageable page);

    /**
     * {@link #findRecent} — yalnız SY ya da UG takımı {@code teams} içinde olanlar (2026-10-12: kapsam süzgeci SQL'de;
     * eskiden kurum geneli ilk 500 satır okunup bellekte süzülüyordu, kalabalık bir ayda takımın düşüşleri hiç görünmezdi).
     */
    @Query("SELECT c FROM TlsGradeChange c WHERE c.changedAt >= :since AND c.direction = :direction "
            + "AND (c.teamId IN :teams OR c.ugTeamId IN :teams) ORDER BY c.changedAt DESC, c.id DESC")
    List<TlsGradeChange> findRecentForTeams(@Param("since") String since, @Param("direction") String direction,
                                            @Param("teams") java.util.Collection<Long> teams, Pageable page);

    /**
     * Yarı açık pencere {@code [from, to)} içindeki değişimler, yeniden eskiye — aylık yönetici özeti (ay sınırları
     * İstanbul takvimi, UTC damga). Sayfa sınırı çağıranda (sorgu sınırsız olmasın).
     */
    @Query("SELECT c FROM TlsGradeChange c WHERE c.changedAt >= :from AND c.changedAt < :to AND c.direction = :direction "
            + "ORDER BY c.changedAt DESC, c.id DESC")
    List<TlsGradeChange> findBetween(@Param("from") String from, @Param("to") String to,
                                     @Param("direction") String direction, Pageable page);

    /** Bildirimi bekleyen düşüşler (eski önce; çağıran sayfa sınırı verir). */
    List<TlsGradeChange> findByNotifyStateOrderByIdAsc(String notifyState, Pageable page);

    /**
     * Koşullu durum geçişi — iki pod / iki tur aynı düşüşü iki kez bildirmesin (talep). Yalnız {@code from} durumundaki
     * satırlar {@code to}'ya geçer; dönen sayı talep edilen satır sayısıdır.
     */
    @Modifying
    @Transactional
    @Query("UPDATE TlsGradeChange c SET c.notifyState = :to WHERE c.id IN :ids AND c.notifyState = :from")
    int transitionNotifyState(@Param("ids") java.util.Collection<Long> ids, @Param("from") String from, @Param("to") String to);

    /** Bildirim sonucu (SENT / "SKIPPED: …") + zamanı. */
    @Modifying
    @Transactional
    @Query("UPDATE TlsGradeChange c SET c.notifyState = :state, c.notifiedAt = :at WHERE c.id IN :ids")
    int finishNotify(@Param("ids") java.util.Collection<Long> ids, @Param("state") String state, @Param("at") String at);

    /** Koşullu sonuç: yalnız {@code expected} durumundaki satırlar (yarıda kalan SENDING süpürmesi, 2026-10-12). */
    @Modifying
    @Transactional
    @Query("UPDATE TlsGradeChange c SET c.notifyState = :state, c.notifiedAt = :at WHERE c.id IN :ids AND c.notifyState = :expected")
    int finishNotifyIfState(@Param("ids") java.util.Collection<Long> ids, @Param("expected") String expected,
                            @Param("state") String state, @Param("at") String at);

    /** {@link #findBetween} penceresindeki TAM sayı (liste sınırlıyken de doğru toplam). */
    @Query("SELECT COUNT(c) FROM TlsGradeChange c WHERE c.changedAt >= :from AND c.changedAt < :to AND c.direction = :direction")
    long countBetween(@Param("from") String from, @Param("to") String to, @Param("direction") String direction);
}
