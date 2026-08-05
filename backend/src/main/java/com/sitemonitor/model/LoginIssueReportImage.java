package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@link LoginIssueReport}'a ekli ekran görüntüsü — base64 TEXT (prefix'siz).
 * Düz {@code reportId} FK (proje deseni: @ManyToOne ilişki kullanılmaz).
 * Yalnız detay endpoint'i döndürür; liste hafif kalsın diye resimler taşınmaz.
 */
@Entity
@Table(
    name = "login_issue_report_images",
    indexes = { @Index(name = "idx_lir_img_report", columnList = "reportId") }
)
@Data
@NoArgsConstructor
public class LoginIssueReportImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long reportId;

    /** image/png | image/jpeg */
    @Column(nullable = false, length = 30)
    private String contentType;

    /** Ham base64 (data-URL prefix'i olmadan). */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String dataBase64;
}
