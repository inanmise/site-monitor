package com.sitemonitor.repository;

import com.sitemonitor.model.NocTeamCallEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

public interface NocTeamCallEntryRepository extends JpaRepository<NocTeamCallEntry, Long> {

    List<NocTeamCallEntry> findByTeamIdOrderByPositionAsc(Long teamId);

    List<NocTeamCallEntry> findByTeamIdInOrderByTeamIdAscPositionAsc(Collection<Long> teamIds);

    /**
     * Listeyi baştan yazarken eski satırlar. Türetilmiş silme Spring Data'da transaction'a SARILMAZ
     * (open-in-view kapalı) — kendi transaction'ını taşır; çağıran {@code NocCallListService#replace} de
     * {@code @Transactional}, iki adım (sil + yaz) tek işlemde döner.
     */
    @Transactional
    void deleteByTeamId(Long teamId);
}
