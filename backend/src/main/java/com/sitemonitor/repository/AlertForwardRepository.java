package com.sitemonitor.repository;

import com.sitemonitor.model.AlertForward;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/** Alarm iletimleri (2026-11-05) — küçük tablo; yönlendirme anlık görüntüsü tümünü okur ({@code AlertForwardService}). */
public interface AlertForwardRepository extends JpaRepository<AlertForward, Long> {

    List<AlertForward> findAllByOrderByIdAsc();

    List<AlertForward> findByOwnerTeamIdInOrderByIdAsc(Collection<Long> ownerTeamIds);

    List<AlertForward> findByRecipientTeamIdInOrderByIdAsc(Collection<Long> recipientTeamIds);

    long countByOwnerTeamId(Long ownerTeamId);

    long countByRecipientTeamId(Long recipientTeamId);

    /** Seçilen üyelere giden iletimler (kullanıcı silinince üye listesinden çıkarmak için — küçük küme). */
    List<AlertForward> findByRecipientKindOrderByIdAsc(String recipientKind);

    /**
     * Sahip takımın iletim yazımlarını SIRALAR (kilit): takım satırı işlem boyunca kilitlenir — aynı takıma iki eşzamanlı
     * "yeni iletim" sırayla çalışır (takım başına tavan ve "aynı iletim zaten var" kesin). Takım yoksa boş liste. Çağıranın
     * işleminde çalışır (servis {@code @Transactional}).
     */
    @Query(value = "SELECT id FROM teams WHERE id = :teamId FOR UPDATE", nativeQuery = true)
    List<Long> lockTeamRow(@Param("teamId") Long teamId);

    /**
     * Seçilen üyelerin iletim için gereken alanları — fotoğraf kolonu YÜKLENMEZ. Üyelik başına bir satır:
     * {@code [Long id, String email, Boolean active, Long primaryTeamId, Long membershipTeamId (null olabilir),
     * String displayName, String username]}.
     */
    @Query("SELECT u.id, u.email, u.active, u.teamId, tid, u.displayName, u.username "
         + "FROM AppUser u LEFT JOIN u.teamIds tid WHERE u.id IN :ids")
    List<Object[]> findMemberRows(@Param("ids") Collection<Long> ids);

    /** Envanter kimliği → alan adı (cert kapsam öğelerinin alarm eşleşmesi alan adıyladır). {@code [Long id, String domain]}. */
    @Query("SELECT c.id, c.domain FROM CertificateInventory c WHERE c.id IN :ids")
    List<Object[]> findInventoryDomains(@Param("ids") Collection<Long> ids);

    /** Takım silinince: sahibi ya da alıcısı o takım olan iletimler. */
    @Modifying
    @Transactional
    @Query("DELETE FROM AlertForward f WHERE f.ownerTeamId = :teamId OR f.recipientTeamId = :teamId")
    int deleteByTeam(@Param("teamId") Long teamId);

    /** Takım taşıma: alıcısı {@code from} olan (gelen) iletimler silinir. */
    @Modifying
    @Transactional
    @Query("DELETE FROM AlertForward f WHERE f.recipientTeamId = :teamId")
    int deleteIncoming(@Param("teamId") Long teamId);

    /** Takım taşıma: sahibi {@code from}, alıcısı {@code to} olan iletim taşınınca kendi kendine iletim olurdu — silinir. */
    @Modifying
    @Transactional
    @Query("DELETE FROM AlertForward f WHERE f.ownerTeamId = :owner AND f.recipientTeamId = :recipient")
    int deleteByOwnerAndRecipient(@Param("owner") Long owner, @Param("recipient") Long recipient);

    /** Takım taşıma: sahibi {@code from} olan iletimler {@code to}'ya geçer (sürüm artar — açık düzenleme penceresi 409 alır). */
    @Modifying
    @Transactional
    @Query("UPDATE AlertForward f SET f.ownerTeamId = :to, f.version = COALESCE(f.version, 0) + 1, f.updatedAt = :at "
         + "WHERE f.ownerTeamId = :from")
    int moveOwner(@Param("from") Long from, @Param("to") Long to, @Param("at") String at);
}
