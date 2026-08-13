package com.sitemonitor.repository;

import com.sitemonitor.model.ScriptedDraft;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScriptedDraftRepository extends JpaRepository<ScriptedDraft, Long> {

    /** Taslak kullanıcı başına tekildir (unique index: owner + monitor_key). */
    Optional<ScriptedDraft> findByOwnerAndMonitorKey(String owner, String monitorKey);

    /** Kullanıcının tüm taslakları — "devam et" şeridi için, en yeni üstte. */
    List<ScriptedDraft> findByOwnerOrderByUpdatedAtDesc(String owner);

    void deleteByOwnerAndMonitorKey(String owner, String monitorKey);

    /** Monitör silinince ona ait taslaklar da düşsün (öksüz kalmasın). */
    void deleteByMonitorId(Long monitorId);
}
