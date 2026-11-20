package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Teknik hata raporu anomali konusu ↔ olay kaydı CANLI bağı (2026-11-20, kullanıcı kararı: "olay kaydından içe al, olay
 * değişince konu da kendiliğinden güncellensin; elle değiştirdiğim alan artık izlemesin"). Bir satır = bir konunun bir
 * olay kaydına bağı. Tablo adı ve sütunlar KALICI.
 *
 * <ul>
 *   <li>{@code fields_fp}: alan grubu → senkronun / içe almanın SON YAZDIĞI değerin parmak izi (JSON; hiç yazılmamış
 *       grubun anahtarı yok). Konunun saklı değeri bu izden farklılaşırsa kullanıcı değiştirmiştir → grup "sabitlenir".</li>
 *   <li>{@code pinned}: sabitlenmiş grupların CSV'si (grup listesinin sırasıyla); açıkça "olaydaki değere dön" denene dek kalır.</li>
 *   <li>{@code parts}: içe alırken seçilen açıklama parçaları (CSV).</li>
 * </ul>
 * Satırlar YALNIZ raporun satır kilidi altında okunur ve yazılır ({@code TechErrorReportRepository.lockRow} ilk iş) —
 * eski bir varlık eşzamanlı değişikliğin üstüne kaydedilmez.
 */
@Entity
@Table(name = "tech_error_report_incidents",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_teri_report_topic", columnNames = {"report_id", "section_id", "issue_id"}),
                @UniqueConstraint(name = "uk_teri_report_incident", columnNames = {"report_id", "incident_id"})
        },
        indexes = {
                @Index(name = "idx_teri_incident", columnList = "incident_id"),
                @Index(name = "idx_teri_channel", columnList = "channel_id")
        })
@Data
@NoArgsConstructor
public class TechErrorReportIncident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "section_id", nullable = false, length = 40)
    private String sectionId;

    @Column(name = "issue_id", nullable = false, length = 40)
    private String issueId;

    @Column(name = "incident_id", nullable = false)
    private Long incidentId;

    /** Grup → son yazılan değerin parmak izi (JSON nesnesi). */
    @Column(name = "fields_fp", columnDefinition = "TEXT")
    private String fieldsFp;

    /** Sabitlenmiş gruplar (CSV, grup listesinin sırasıyla). */
    @Column(name = "pinned", length = 120)
    private String pinned;

    /** İçe alırken seçilen açıklama parçaları (CSV). */
    @Column(name = "parts", length = 60)
    private String parts;

    /** Olayın son senkron / içe alma anındaki {@code updated_at} değeri. */
    @Column(name = "incident_updated_at", length = 30)
    private String incidentUpdatedAt;

    /**
     * Olayın eşleme kaynağının izi ({@code TechErrorIncidentLinks.sourceFp}: başlık, zamanlar, sayılar, seçili açıklama
     * parçaları) son senkron / içe alma anında. Yetişmenin "bayat mı" denetimi bununla da karşılaştırır — aynı saniyedeki
     * ikinci düzenleme {@code incident_updated_at}'i değiştirmez. Null = eski satır (yalnız {@code incident_updated_at}).
     */
    @Column(name = "source_fp", length = 16)
    private String sourceFp;

    /** Son senkronun not kodları (CSV). */
    @Column(name = "sync_notes", length = 300)
    private String syncNotes;

    @Column(name = "imported_at")
    private Instant importedAt;

    @Column(name = "imported_by_name", length = 200)
    private String importedByName;

    @Column(name = "synced_at")
    private Instant syncedAt;
}
