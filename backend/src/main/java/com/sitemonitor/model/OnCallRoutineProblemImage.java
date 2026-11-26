package com.sitemonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Nöbet rutini sorununa eklenen görsel (2026-11-26) — {@code TechErrorReportImage} deseni: yalnız PNG / JPEG, tür / boyut /
 * piksel yüklemede baytlardan doğrulanır ({@code TechErrorImageValidator}); sorun başına en çok 4, takım başına en çok 200 MB.
 * {@code @Lob} bilinçli olarak yok (PostgreSQL'de BYTEA).
 */
@Entity
@Table(name = "oncall_routine_problem_images",
        indexes = @Index(name = "idx_orpi_problem", columnList = "problem_id"))
@Data
@NoArgsConstructor
public class OnCallRoutineProblemImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "problem_id", nullable = false)
    private Long problemId;

    @Column(name = "content_type", length = 20, nullable = false)
    private String contentType;

    @Column(name = "file_name", length = 200)
    private String fileName;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(nullable = false)
    private Integer width;

    @Column(nullable = false)
    private Integer height;

    @JsonIgnore
    @Column(nullable = false, length = 10 * 1024 * 1024)
    private byte[] data;

    @Column(name = "created_by_name", length = 200)
    private String createdByName;

    @Column(name = "created_at", length = 30)
    private String createdAt;
}
