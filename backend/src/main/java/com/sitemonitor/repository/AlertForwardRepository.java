package com.sitemonitor.repository;

import com.sitemonitor.model.AlertForward;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** Alarm iletimleri (2026-11-05) — küçük tablo; yönlendirme anlık görüntüsü tümünü okur ({@code AlertForwardService}). */
public interface AlertForwardRepository extends JpaRepository<AlertForward, Long> {

    List<AlertForward> findAllByOrderByIdAsc();

    List<AlertForward> findByOwnerTeamIdInOrderByIdAsc(Collection<Long> ownerTeamIds);

    List<AlertForward> findByRecipientTeamIdInOrderByIdAsc(Collection<Long> recipientTeamIds);

    long countByOwnerTeamId(Long ownerTeamId);
}
