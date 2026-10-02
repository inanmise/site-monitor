package com.sitemonitor.service;

import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Sentetik betiklerde dosya okuma RAPORU (2026-10-01, onaylı öneri 1: "önce raporla, sorun yoksa engelle").
 *
 * <p>{@code site.monitor.scripted.file-read-policy} BLOCK'a çevrilmeden önce hangi mevcut betiklerin etkileneceğini
 * gösterir: açılışta tek satırlık özet (WARN: dosya okuyan betik var / INFO: yok) ve
 * {@code GET /api/admin/scripted/file-read-report}. Yalnız okur; hiçbir betiği değiştirmez ya da durdurmaz.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptedFileReadAudit {

    private final ScriptedMonitorRepository scriptedMonitorRepo;
    private final AppSettingsService appSettings;

    /** Dosya okuyan bir betik: kimlik, ad, takım, etkin mi ve bulunan yapıların kısa adları (içerik değil). */
    public record Finding(Long id, String name, Long teamId, boolean active, List<String> hits) {}

    /** Tüm sentetik izlemeleri tarar (pasifler dahil — BLOCK'a geçilirse yeniden etkinleştirildiklerinde koşmazlar). */
    public List<Finding> scan() {
        List<Finding> out = new ArrayList<>();
        for (ScriptedMonitor m : scriptedMonitorRepo.findAll()) {
            List<String> hits = ScriptedCheckerService.scanFileReads(m.getScript());
            if (!hits.isEmpty()) {
                out.add(new Finding(m.getId(), m.getName(), m.getTeamId(), !Boolean.FALSE.equals(m.getActive()), hits));
            }
        }
        return out;
    }

    public String policy() {
        return appSettings.getString(ScriptedCheckerService.FILE_READ_POLICY_KEY, "REPORT").toUpperCase(java.util.Locale.ROOT);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logOnStartup() {
        try {
            List<Finding> f = scan();
            if (f.isEmpty()) {
                log.info("Sentetik betik dosya okuma taraması: dosya okuyan betik yok (politika {})", policy());
            } else {
                log.warn("⚠ {} sentetik betik dosya okuyor (politika {}) — BLOCK'a geçmeden önce düzeltin: {}", f.size(), policy(),
                        f.stream().limit(20).map(x -> "#" + x.id() + " " + x.name() + " " + x.hits()).toList());
            }
        } catch (Exception e) {
            log.debug("Sentetik betik dosya okuma taraması yapılamadı: {}", e.toString());
        }
    }
}
