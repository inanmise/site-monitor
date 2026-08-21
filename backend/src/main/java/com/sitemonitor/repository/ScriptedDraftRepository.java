package com.sitemonitor.repository;

import com.sitemonitor.model.ScriptedDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface ScriptedDraftRepository extends JpaRepository<ScriptedDraft, Long> {

    /** Taslak kullanıcı başına tekildir (unique index: owner + monitor_key). */
    Optional<ScriptedDraft> findByOwnerAndMonitorKey(String owner, String monitorKey);

    /** Kullanıcının tüm taslakları — "devam et" şeridi için, en yeni üstte. */
    List<ScriptedDraft> findByOwnerOrderByUpdatedAtDesc(String owner);

    /**
     * Silinen satır sayısı — 0 gelirse çağıran "silindi" DEMEMELİ.
     *
     * <p>{@code @Transactional} ŞART: türetilmiş silme sorgusu {@code SimpleJpaRepository}'nin
     * hazır metotlarından DEĞİL, bu yüzden Spring Data ona kendiliğinden tx sarmıyor. Uygulamada
     * {@code open-in-view=false} ve çağıran {@code MonitoringController} de transactional değil;
     * annotation olmadan çağrı {@code TransactionRequiredException} ile düşüyordu. Sonuç kullanıcıya
     * "taslak silindi" diye görünen ama silinmeyen taslaktı (bkz. {@code RememberMeTokenRepository}
     * — aynı desen).
     */
    @Transactional
    int deleteByOwnerAndMonitorKey(String owner, String monitorKey);

    /** Monitör silinince ona ait taslaklar da düşsün (öksüz kalmasın). @Transactional yukarıdaki sebeple. */
    @Transactional
    int deleteByMonitorId(Long monitorId);
}
