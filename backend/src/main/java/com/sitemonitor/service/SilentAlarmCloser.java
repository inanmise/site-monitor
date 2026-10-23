package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertEventSilentCloseRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Alarmın KOŞULLU sessiz kapanışı (2026-10-23, çok pod R2a · D2, MP-44).
 *
 * <p><b>Neden.</b> {@code EscalationService}'in sessiz kapanış yolları okunan alarm varlığını bütünüyle kaydediyordu: iki
 * pod aynı kapanışı koşunca ikincisi birincinin kapattığı alarmı yeniden "kapatıp" {@code resolved_by}'ı eziyor, okuma ile
 * kayıt arasında yazılmış onay / yeniden uyarı damgası / elle çözüm geri alınıyordu. Burada kapanış tek bir koşullu UPDATE'tir
 * ({@link AlertEventSilentCloseRepository#closeSilentlyIfOpen} — yalnız kapanış kolonları, yalnız hâlâ AÇIK satır); çözüm
 * push'u YALNIZ satırı gerçekten kapatan çağıranda kuyruğa girer. Davranış eskisiyle aynı: e-posta yok, {@code
 * resolved_silently = true} (fırtına çözümü bunu "kurtarıldı" saymaz), açılış push'u gittiyse çözüm push'u gider, 7/24 yok.
 *
 * <p><b>Kullananlar.</b> Bildirimi kapatılan türlerin uzlaştırması ({@code MonitoringOutageService.closeAlarmsOfDisabledTypes})
 * ve envanter içe aktarmasıyla pasife alınan kaydın kapanışı ({@code InventoryImportService}). R2b · F'den (2026-10-23) beri
 * {@code EscalationService}'in dört kapanış yolu da: sessiz kapanış ({@code resolveOpenAlertsSilently} /
 * {@code resolveOpenAlertsQuietlyRecovered} — {@link #closeCopyIfOpen}, push / 7-24 çağıranın), envanter silme /
 * pasifleştirme ({@code closeAlertsOnInventoryDelete|Deactivate}) ve koşullu kapanışı (CAS) kazanmış elle / otomatik
 * çözümün ardından bütün varlık kaydı yerine yalnız not ({@link #writeResolvedNote}).
 *
 * <p>Okunan varlık DEĞİŞTİRİLMEZ (çağıranın işleminde yönetiliyor olabilir — kirli kontrol bütün satırı yazardı); push için
 * kopyası kullanılır.
 */
@Slf4j
@Component
public class SilentAlarmCloser {

    /** Bildirimi kapatılan türün alarmlarının kapanış nedeni — eski metinle aynı. */
    public static final String BY_TYPE_DISABLED = "Sistem (bu türün alarm bildirimleri kapatıldı)";
    /** Envanter pasifleştirmesinin kapanış nedeni — {@code EscalationService.closeAlertsOnDeactivate} ile aynı. */
    public static final String BY_INVENTORY_DEACTIVATE = "inventory_deactivate";

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AlertEventSilentCloseRepository closeRepo;
    private final AlertEventRepository alertEventRepo;
    private final CertificateInventoryRepository inventoryRepo;
    /** Döngü yok — çağrı anında çözülür; yoksa (testler) push atlanır. */
    private final ObjectProvider<UserPushService> userPushService;

    public SilentAlarmCloser(AlertEventSilentCloseRepository closeRepo, AlertEventRepository alertEventRepo,
                             CertificateInventoryRepository inventoryRepo, ObjectProvider<UserPushService> userPushService) {
        this.closeRepo = closeRepo;
        this.alertEventRepo = alertEventRepo;
        this.inventoryRepo = inventoryRepo;
        this.userPushService = userPushService;
    }

    /**
     * Alarmı yalnız hâlâ açıksa sessizce kapatır. {@code true} = bu çağrı kapattı (çözüm push'u kuyruğa girdi); {@code false} =
     * başka yol zaten kapatmıştı ya da yazım düştü (push yok, satıra dokunulmadı).
     */
    public boolean closeIfOpen(AlertEvent event, String resolvedBy) {
        AlertEvent closed = closeCopyIfOpen(event, resolvedBy, true);
        if (closed == null) return false;
        enqueueResolvePushQuietly(closed);
        log.info("✅ Alarm sessizce kapatıldı ({}, mail yok): {} [{}]", closed.getResolvedBy(), event.getDomain(),
                event.getAlertType());
        return true;
    }

    /**
     * Yalnız KOŞULLU YAZIM (2026-10-23, çok pod R2b · F) — push / 7-24 YOK, onları çağıran kendi kanalından gönderir
     * ({@code EscalationService}'in sessiz kapanışları bugünkü push / NOC yolunu korur). Dönüş: bu çağrı kapattıysa okunan
     * varlığın kapanmış KOPYASI (kapanış kolonları dolu; okunan varlık değiştirilmez), başka yol zaten kapatmışsa {@code null}.
     *
     * @param markSilenced {@code true} = {@code resolved_silently = true} (susturma: silme / duraklatma / tür kapalı);
     *                     {@code false} = işarete DOKUNULMAZ (bakımda gerçekten düzelen alarm — O-c1)
     * @throws RuntimeException yazım düşerse (WARN yazılır, çağıran eski yoldaki gibi istisnayı görür)
     */
    public AlertEvent closeCopyIfOpen(AlertEvent event, String resolvedBy, boolean markSilenced) {
        if (event == null || event.getId() == null) return null;
        String by = resolvedBy != null && !resolvedBy.isBlank() ? resolvedBy : "Sistem (izleme silindi)";
        String at = ISO.format(Instant.now());
        int n;
        try {
            n = markSilenced ? closeRepo.closeSilentlyIfOpen(event.getId(), at, by)
                    : closeRepo.closeRecoveredIfOpen(event.getId(), at, by);
        } catch (RuntimeException e) {
            log.warn("Sessiz kapanış yazılamadı: {} [{}] #{} — {}", event.getDomain(), event.getAlertType(), event.getId(),
                    e.getMessage());
            throw e;
        }
        if (n == 0) {
            log.debug("Sessiz kapanış atlandı: {} [{}] #{} başka yolda zaten kapanmış", event.getDomain(),
                    event.getAlertType(), event.getId());
            return null;
        }
        AlertEvent closed = new AlertEvent();
        BeanUtils.copyProperties(event, closed);
        closed.setResolved(true);
        closed.setResolvedAt(at);
        closed.setResolvedBy(by);
        if (markSilenced) closed.setResolvedSilently(true);
        return closed;
    }

    /**
     * Koşullu kapanışı (CAS) KAZANMIŞ elle çözümün notu (2026-10-23, çok pod R2b · F) — bütün varlık kaydı yerine yalnız not
     * kolonu, yalnız bu kapanışın satırına ({@code resolved_at} + {@code resolved_by} eşleşir). {@code true} = yazıldı.
     */
    public boolean writeResolvedNote(Long id, String at, String by, String note) {
        if (id == null || note == null || note.isBlank()) return false;
        return closeRepo.writeResolvedNote(id, at, by, note) == 1;
    }

    /**
     * İçe aktarmayla pasife alınan envanter kaydının açık alarmları — {@code EscalationService.closeAlertsOnDeactivate} ile
     * aynı küme ({@code closableByInventory}: başka takımın bağımsız izlemesinin olayı kapanmaz), koşullu yazımla.
     *
     * @return bu çağrının kapattığı alarm sayısı
     */
    public int closeOnInventoryDeactivate(String domain) {
        if (domain == null) return 0;
        int closed = 0;
        for (AlertEvent event : alertEventRepo.findByDomainAndResolvedFalse(domain)) {
            if (!EscalationService.closableByInventory(event, "domain pasife alındı")) continue;
            if (closeIfOpen(event, BY_INVENTORY_DEACTIVATE)) closed++;
        }
        return closed;
    }

    private void enqueueResolvePushQuietly(AlertEvent event) {
        try {
            UserPushService push = userPushService == null ? null : userPushService.getIfAvailable();
            if (push != null) push.enqueueResolve(event, context(event), teamFallback(event));
        } catch (Exception e) {
            log.debug("Sessiz kapanış çözüm push'u atlandı: {}", e.toString());
        }
    }

    /** {@code EscalationService.resolveTeamFallback} ile aynı: olay damgası, yoksa envanterin SY takımı. */
    private Long teamFallback(AlertEvent event) {
        if (event.getTeamId() != null) return event.getTeamId();
        if (event.getDomain() == null || inventoryRepo == null) return null;
        try {
            return inventoryRepo.findByDomain(event.getDomain()).map(CertificateInventory::getTeamId).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> context(AlertEvent event) {
        String json = event.getContextJson();
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }
}
