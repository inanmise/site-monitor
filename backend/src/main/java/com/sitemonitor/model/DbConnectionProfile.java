package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kayıtlı veritabanı bağlantısı (2026-10-31) — TAKIM belgesidir: yalnız sahip takım görür ve kullanır (erişim paylaşımıyla
 * da açılmaz). İzleme düzenleyebilen takım üyesi ekler / düzenler / siler (kullanıcı kararı).
 *
 * <p>JDBC URL'i kullanıcıdan ALINMAZ; alanlardan kurulur (URL parametresi enjeksiyonu yok). Parola {@code SecretCipher}
 * ile şifreli saklanır, API'den asla dönmez (yalnız {@code password_set}). Sağlık alanlarını yalnız servis yazar
 * (hedefli koşullu UPDATE — bir varlık kaydı eş zamanlı bir düzenlemeyi ezmesin).
 *
 * <p><b>Varlık kaydı neyi YAZMAZ</b> (inceleme 2026-10-31, D4): sağlık kolonları ve {@code password_enc}
 * {@code updatable = false} — yalnız hedefli UPDATE'ler yazar ({@code recordTest} / {@code recordUseOk} /
 * {@code recordUseError} / {@code updatePasswordEnc}, anahtar döndürmenin koşullu yeniden şifrelemesi). Düzenleme
 * ({@code DbConnectionProfileService.update}) okunduğu andaki değerleri geri yazıp araya giren bir izleme çalıştırmasının
 * sağlık izini ya da yeni anahtarla şifrelenmiş parolayı (eski anahtarlı metinle) EZMİYOR. {@code @DynamicUpdate}: kalan
 * kolonlardan da yalnız değişenler yazılır.
 */
@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "db_connection_profiles",
       indexes = @Index(name = "idx_dbcp_team", columnList = "team_id"))
@Data
@NoArgsConstructor
public class DbConnectionProfile {

    public static final String ORACLE = "ORACLE", MSSQL = "MSSQL", POSTGRESQL = "POSTGRESQL";
    public static final String ORACLE_SERVICE = "SERVICE", ORACLE_SID = "SID";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    /** Takımda tekil ad (büyük-küçük harf duyarsız, ≤ 120). */
    @Column(nullable = false, length = 120)
    private String name;

    /** {@link #ORACLE} | {@link #MSSQL} | {@link #POSTGRESQL}. */
    @Column(name = "db_type", nullable = false, length = 12)
    private String dbType;

    @Column(nullable = false)
    private String host;

    @Column(nullable = false)
    private Integer port;

    /** PostgreSQL / MSSQL veritabanı adı ya da Oracle servis adı / SID. */
    @Column(name = "database_name", length = 128)
    private String databaseName;

    /** Yalnız Oracle: {@link #ORACLE_SERVICE} (varsayılan) | {@link #ORACLE_SID}. */
    @Column(name = "oracle_mode", length = 8)
    private String oracleMode;

    /** Yalnız MSSQL: adlandırılmış örnek (isteğe bağlı). */
    @Column(name = "mssql_instance", length = 64)
    private String mssqlInstance;

    @Column(nullable = false, length = 128)
    private String username;

    @Column(name = "password_enc", columnDefinition = "TEXT", updatable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String passwordEnc;

    /** Yalnız PostgreSQL: disable | prefer (varsayılan) | require | verify-full. */
    @Column(name = "ssl_mode", length = 16)
    private String sslMode;

    /** Yalnız MSSQL: bağlantıyı şifrele (varsayılan true). */
    @Column(name = "mssql_encrypt")
    private Boolean mssqlEncrypt;

    /** Yalnız MSSQL: sunucu sertifikasını doğrulamadan güven (varsayılan false). */
    @Column(name = "mssql_trust_server_certificate")
    private Boolean mssqlTrustServerCertificate;

    /** Bağlanma zaman aşımı (ms, 1000–30000; varsayılan 10000). */
    @Column(name = "connect_timeout_ms")
    private Integer connectTimeoutMs = 10000;

    @Column(length = 500)
    private String description;

    // ── Sağlık (yalnız servis yazar) ─────────────────────────────────────────

    @Column(name = "last_test_at", length = 30, updatable = false)
    private String lastTestAt;

    @Column(name = "last_test_ok", updatable = false)
    private Boolean lastTestOk;

    @Column(name = "last_test_ms", updatable = false)
    private Integer lastTestMs;

    /** Maskeli (≤ 500). */
    @Column(name = "last_test_error", length = 500, updatable = false)
    private String lastTestError;

    /** Son başarılı denemede sunucunun bildirdiği ürün / sürüm (DatabaseMetaData, ≤ 200). */
    @Column(name = "server_version", length = 200, updatable = false)
    private String serverVersion;

    /** İzlemelerin bu bağlantıyı son kullandığı an (başarılı ya da değil). */
    @Column(name = "last_used_at", length = 30, updatable = false)
    private String lastUsedAt;

    @Column(name = "last_ok_at", length = 30, updatable = false)
    private String lastOkAt;

    @Column(name = "last_error_at", length = 30, updatable = false)
    private String lastErrorAt;

    /** Maskeli (≤ 500). */
    @Column(name = "last_error", length = 500, updatable = false)
    private String lastError;

    @Column(name = "last_duration_ms", updatable = false)
    private Integer lastDurationMs;

    @Column(name = "created_at", length = 30)
    private String createdAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_by_name")
    private String createdByName;

    @Column(name = "updated_at", length = 30)
    private String updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "updated_by_name")
    private String updatedByName;
}
