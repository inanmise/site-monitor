package com.sitemonitor.repository;

import com.sitemonitor.model.AlertEvent;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sessiz alarm kapanışının KOŞULLU yazımı (2026-10-23, çok pod R2a · D2, MP-44). {@code SilentAlarmCloser} kullanır.
 *
 * <p>Eskiden sessiz kapanış okunan alarm varlığını bütünüyle kaydediyordu ({@code save(event)}; {@code AlertEvent}'te
 * {@code @Version} / {@code @DynamicUpdate} yok, yani TÜM kolonlar yeniden yazılır): okuma ile kayıt arasında başka bir yol
 * (başka pod, elle çözüm, onay, yeniden uyarı damgası) satırı değiştirdiyse o değişiklik geri alınıyor, zaten kapanmış alarm
 * yeniden "kapatılıp" {@code resolved_by} eziliyor ve çözüm push'u İKİNCİ kez kuyruğa giriyordu. Bu güncelleme yalnız kapanış
 * kolonlarını ve yalnız hâlâ AÇIK satırda yazar; 0 satır = başka yol kapattı (bu çağıran push göndermez).
 *
 * <p>Ayrı arayüz (aynı varlık): büyük {@code AlertEventRepository} başka paketlerin de değiştirdiği bir dosya.
 * {@code clearAutomatically} YOK — çağıranın işlemindeki (envanter içe aktarma) yönetilen varlıklar sökülmesin; okunmuş
 * alarm varlığı DEĞİŞTİRİLMEZ (kirli kontrol bütün satırı yeniden yazardı).
 */
public interface AlertEventSilentCloseRepository extends Repository<AlertEvent, Long> {

    @Transactional
    @Modifying
    @Query("UPDATE AlertEvent e SET e.resolved = true, e.resolvedAt = :at, e.resolvedBy = :by, e.resolvedSilently = true "
            + "WHERE e.id = :id AND e.resolved = false")
    int closeSilentlyIfOpen(@Param("id") Long id, @Param("at") String at, @Param("by") String by);

    /**
     * {@link #closeSilentlyIfOpen} ikizi — {@code resolved_silently}'ye DOKUNMAZ (2026-10-23, çok pod R2b · F). Bakım
     * penceresinde GERÇEKTEN düzelen alarmın e-postasız kapanışı ({@code EscalationService.resolveOpenAlertsQuietlyRecovered}):
     * alarm susturulmadı, kurtuldu — fırtına çözümü onu "kurtarıldı" saysın diye işaret yazılmaz (O-c1).
     */
    @Transactional
    @Modifying
    @Query("UPDATE AlertEvent e SET e.resolved = true, e.resolvedAt = :at, e.resolvedBy = :by "
            + "WHERE e.id = :id AND e.resolved = false")
    int closeRecoveredIfOpen(@Param("id") Long id, @Param("at") String at, @Param("by") String by);

    /**
     * Elle çözümün notu (2026-10-23, çok pod R2b · F) — yalnız BU kapanışın yazdığı satıra ({@code resolved_at} +
     * {@code resolved_by} eşleşir): koşullu kapanışı kazanan çağıran notu ayrıca yazar; bütün varlık kaydedilmez (okumadan
     * sonra yazılmış onay / damga ezilmesin).
     */
    @Transactional
    @Modifying
    @Query("UPDATE AlertEvent e SET e.resolvedNote = :note "
            + "WHERE e.id = :id AND e.resolved = true AND e.resolvedAt = :at AND e.resolvedBy = :by")
    int writeResolvedNote(@Param("id") Long id, @Param("at") String at, @Param("by") String by,
                          @Param("note") String note);
}
