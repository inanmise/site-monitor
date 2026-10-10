package com.sitemonitor.repository;

import com.sitemonitor.model.ExecutiveSummaryTeamSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Takım yönetici özeti ayarları (takım başına tek satır; satır yok = kapalı + varsayılan alıcı seçenekleri). */
public interface ExecutiveSummaryTeamSettingsRepository extends JpaRepository<ExecutiveSummaryTeamSettings, Long> {

    /** Gönderimi açık takımlar (zamanlanmış koşu ve telafi). */
    List<ExecutiveSummaryTeamSettings> findByEnabledTrueOrderByTeamIdAsc();

    /** Açık takım var mı (saatlik telafinin kilide dokunup dokunmayacağı — tek sayım). */
    boolean existsByEnabledTrue();
}
