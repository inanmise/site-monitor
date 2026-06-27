package com.certmonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Olay (incident) kaydına gömülen görsel. Markdown'da
 * ![caption](/api/incidents/images/{id}) olarak referans alınır.
 *
 * NOT: @Lob bilinçli KULLANILMADI — düz byte[] PostgreSQL'de BYTEA'ya,
 * H2 testte VARBINARY'ye map'lenir (WeeklyReportImage ile aynı yaklaşım).
 */
@Entity
@Table(name = "incident_images",
    indexes = { @Index(name = "idx_inci_img_incident", columnList = "incident_id") })
@Data
@NoArgsConstructor
public class IncidentImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Taslak yükleme: yeni olay henüz KAYDEDİLMEDEN görsel yüklenir (incidentId=null);
    // olay kaydedilince IncidentService.linkImages markdown referanslarından olaya bağlar.
    // Bu yüzden nullable OLMALI (eski NOT NULL kısıtı taslakları reddediyordu).
    @Column(name = "incident_id")
    private Long incidentId;

    @Column(length = 300)
    private String caption;

    @Column(name = "content_type", length = 100, nullable = false)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @JsonIgnore
    @Column(nullable = false)
    private byte[] data;

    private String createdBy;
    private String createdAt;
}
