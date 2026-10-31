package com.sitemonitor.service;

import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.model.ScriptedTemplate;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import com.sitemonitor.repository.ScriptedTemplateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Sentetik betiklerde dosya okuma RAPORU (2026-10-01, onaylı öneri 1: "önce raporla, sorun yoksa engelle").
 *
 * <p>{@code site.monitor.scripted.file-read-policy} hangi mevcut betikleri etkiliyor: açılışta tek satırlık özet (WARN:
 * dosya okuyan betik var / INFO: yok) ve {@code GET /api/admin/scripted/file-read-report}. Yalnız okur; hiçbir betiği
 * değiştirmez ya da durdurmaz.
 *
 * <p>2026-10-31 (k6 inceleme A1): varsayılan politika BLOCK — açılış özeti "bu betikler koşturulmuyor" der. Şablonlar da
 * taranır (bir şablondan kurulan izleme BLOCK'ta kaydedilemez; şablon sahibi önceden görsün).
 */
@Slf4j
@Service
public class ScriptedFileReadAudit {

    private final ScriptedMonitorRepository scriptedMonitorRepo;
    private final ScriptedTemplateRepository templateRepo;   // null: dilimli test bağlamı (şablon taranmaz)
    private final AppSettingsService appSettings;

    @Autowired
    public ScriptedFileReadAudit(ScriptedMonitorRepository scriptedMonitorRepo,
                                 ScriptedTemplateRepository templateRepo, AppSettingsService appSettings) {
        this.scriptedMonitorRepo = scriptedMonitorRepo;
        this.templateRepo = templateRepo;
        this.appSettings = appSettings;
    }

    /** Şablonsuz kurucu (eski çağıranlar / testler). */
    public ScriptedFileReadAudit(ScriptedMonitorRepository scriptedMonitorRepo, AppSettingsService appSettings) {
        this(scriptedMonitorRepo, null, appSettings);
    }

    /** Dosya okuyan bir betik: kimlik, ad, takım, etkin mi ve bulunan yapıların kısa adları (içerik değil). */
    public record Finding(Long id, String name, Long teamId, boolean active, List<String> hits) {}

    /** Tüm sentetik izlemeleri tarar (pasifler dahil — BLOCK'ta yeniden etkinleştirildiklerinde koşmazlar). */
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

    /** Etkin (çöpte olmayan) şablonlar — aynı kural. Depo yoksa boş. */
    public List<Finding> scanTemplates() {
        List<Finding> out = new ArrayList<>();
        if (templateRepo == null) return out;
        for (ScriptedTemplate t : templateRepo.findAllActive()) {
            List<String> hits = ScriptedCheckerService.scanFileReads(t.getScript());
            if (!hits.isEmpty()) {
                out.add(new Finding(t.getId(), t.getName(), t.getTeamId(), !Boolean.FALSE.equals(t.getActive()), hits));
            }
        }
        return out;
    }

    public String policy() {
        return appSettings.getString(ScriptedCheckerService.FILE_READ_POLICY_KEY,
                ScriptedCheckerService.FILE_READ_POLICY_DEFAULT).toUpperCase(java.util.Locale.ROOT);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logOnStartup() {
        try {
            List<Finding> f = scan();
            List<Finding> t = scanTemplates();
            String policy = policy();
            if (f.isEmpty() && t.isEmpty()) {
                log.info("Sentetik betik dosya okuma taraması: dosya okuyan betik yok (politika {})", policy);
            } else if ("BLOCK".equals(policy)) {
                log.warn("⚠ {} sentetik betik dosya okuyor — politika BLOCK, bu betikler koşturulmuyor{}: {}", f.size(),
                        t.isEmpty() ? "" : " (ayrıca " + t.size() + " şablon)",
                        f.stream().limit(20).map(x -> "#" + x.id() + " " + x.name() + " " + x.hits()).toList());
            } else {
                log.warn("⚠ {} sentetik betik dosya okuyor (politika {}) — BLOCK'a geçmeden önce düzeltin{}: {}", f.size(), policy,
                        t.isEmpty() ? "" : " (ayrıca " + t.size() + " şablon)",
                        f.stream().limit(20).map(x -> "#" + x.id() + " " + x.name() + " " + x.hits()).toList());
            }
        } catch (Exception e) {
            log.debug("Sentetik betik dosya okuma taraması yapılamadı: {}", e.toString());
        }
    }
}
