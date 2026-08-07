package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Genel "sorun bildirimi" kaydı — DB ledger'ı. Üç kaynaktan beslenir ({@code source}):
 * LOGIN (login ekranı, oturumsuz), CLIENT_ERROR (ErrorBoundary otomatik çökme bildirimi),
 * USER_REPORT (oturum içi kullanıcı-tetiklemeli bildirim; otomatik bağlam {@code autoContextJson}'da).
 * Mevcut e-posta akışına EK olarak kalıcıdır; yalnız yetkili adminler görüntüler/çözer.
 * Tüm zaman damgaları ISO-8601 UTC String (proje konvansiyonu; IncidentRecord/AuditLog ile aynı).
 * Ekran görüntüleri ayrı {@link LoginIssueReportImage} satırlarında (base64 TEXT).
 */
@Entity
@Table(
    name = "login_issue_reports",
    indexes = {
        @Index(name = "idx_lir_reported_at", columnList = "reportedAt"),
        @Index(name = "idx_lir_status",      columnList = "status"),
        @Index(name = "idx_lir_username",    columnList = "username")
    }
)
@Data
@NoArgsConstructor
public class LoginIssueReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ISO-8601 UTC — bildirimin geldiği an. */
    @Column(nullable = false, length = 30)
    private String reportedAt;

    /** Bildiren kullanıcı adı (login modalında zorunlu). */
    @Column(length = 100)
    private String username;

    /** Bildiren kişinin e-posta adresi (zorunlu) — referans no + çözüm bildirimi buraya gider. */
    @Column(length = 255)
    private String reporterEmail;

    /** Kullanıcının ekranda gördüğü hata mesajı — opsiyonel. */
    @Column(columnDefinition = "TEXT")
    private String errorText;

    /** Kullanıcının açıklaması — zorunlu. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(length = 50)
    private String ipAddress;

    @Column(columnDefinition = "TEXT")
    private String userAgent;

    /** OPEN | IN_PROGRESS | RESOLVED (String, JPA enum DEĞİL). */
    @Column(nullable = false, length = 20)
    private String status;

    /** Eklenen ekran görüntüsü adedi (liste ekranında resim yüklemeden göstermek için). */
    @Column(nullable = false)
    private Integer imageCount = 0;

    private String resolvedBy;
    private String resolvedAt;

    /** Çözüm notu — RESOLVED'a çekilirken zorunlu. */
    @Column(columnDefinition = "TEXT")
    private String resolutionNote;

    private String updatedAt;

    /** Bildirim kaynağı: LOGIN | CLIENT_ERROR | USER_REPORT. Eski satırlar patch ile LOGIN'e çekilir. */
    @Column(length = 20)
    private String source = "LOGIN";

    /** Kullanıcının önem algısı (USER_REPORT): BLOCKER | ANNOYANCE | SUGGESTION — opsiyonel. */
    @Column(length = 30)
    private String category;

    /** Bildirim anındaki uygulama sürümü (VERSION) — otomatik bağlam. */
    @Column(length = 30)
    private String appVersion;

    /** Ekran boyutu ("1920x1080") — otomatik bağlam. */
    @Column(length = 20)
    private String screenSize;

    /** Bildirim anındaki aktif sekme anahtarı (?tab=...) — otomatik bağlam. */
    @Column(length = 50)
    private String tabKey;

    /** Otomatik toplanan bağlamın tamamı (JSON; maskelenmiş) — tema/dil/UA/son başarısız API çağrıları vb. */
    @Column(columnDefinition = "TEXT")
    private String autoContextJson;

    /** ErrorBoundary otomatik kaydının referansı (LIR-...) — kullanıcı aynı çökmeye bağlam eklediğinde bağ kurar. */
    @Column(length = 30)
    private String linkedReference;
}
