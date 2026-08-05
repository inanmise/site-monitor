package com.sitemonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Haftalık rapora gömülen görsel. Markdown'da
 * ![caption](/api/weekly-reports/images/{id}) olarak referans alınır;
 * onay mailinde inline CID attachment'a çevrilir.
 *
 * NOT: @Lob bilinçli olarak KULLANILMADI — Hibernate 6'da düz byte[]
 * PostgreSQL'de BYTEA'ya, H2 test DB'sinde VARBINARY'ye map'lenir
 * (@Lob PG'de oid'e map'lenip large-object yönetimi gerektirirdi).
 */
@Entity
@Table(name = "weekly_report_images",
    indexes = {
        @Index(name = "idx_wri_report", columnList = "report_id"),
        @Index(name = "idx_wri_team", columnList = "team_id")
    })
@Data
@NoArgsConstructor
public class WeeklyReportImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    /** Görselin ait olduğu takım — açık izolasyon/sorgu için (report→team ile aynı). */
    @Column(name = "team_id")
    private Long teamId;

    /** Görselin üst açıklaması — mailde ve markdown alt-text'inde kullanılır. */
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
