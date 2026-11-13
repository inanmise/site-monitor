package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;

/**
 * SQL Playground kayıtlı sorgusu (2026-11-13, D1). Sahibine aittir; {@code shared} ise playground'u kullanan diğer
 * yöneticiler okuyabilir (yalnız sahibi düzenler / siler). Başlık sahibi içinde benzersizdir ({@code title_key} = Türkçe
 * küçük harfe katlanmış başlık). {@code params_json} yalnız parametre TÜRLERİNİ taşır — değer asla saklanmaz.
 */
@Entity
@Table(name = "sql_saved_queries",
       uniqueConstraints = @UniqueConstraint(name = "uq_ssq_owner_title", columnNames = {"owner_username", "title_key"}),
       indexes = {
           @Index(name = "idx_ssq_owner_updated", columnList = "owner_username,updated_at"),
           @Index(name = "idx_ssq_shared_updated", columnList = "shared,updated_at")
       })
@DynamicUpdate
@Getter @Setter @NoArgsConstructor
public class SqlSavedQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_username", nullable = false, length = 100)
    private String ownerUsername;

    /** Kaydeden kişinin görünen adı (anlık görüntü — kullanıcı silinse de paylaşılan kayıtta kalır). */
    @Column(name = "owner_name", length = 200)
    private String ownerName;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "title_key", nullable = false, length = 120)
    private String titleKey;

    @Column(name = "description", length = 1000)
    private String description;

    /** Etiketler ",a,b," biçiminde (sınırlayıcılı — tam eşleşme araması için), en çok 10 × 30. */
    @Column(name = "tags", length = 400)
    private String tags;

    @Column(name = "sql_text", nullable = false, columnDefinition = "TEXT")
    private String sqlText;

    /** Son kullanılan parametre türleri: {"ad":"text"} — değer YOK. */
    @Column(name = "params_json", columnDefinition = "TEXT")
    private String paramsJson;

    @Column(name = "shared", nullable = false)
    private Boolean shared = false;

    @Column(name = "created_at", nullable = false, length = 30)
    private String createdAt;

    @Column(name = "updated_at", nullable = false, length = 30)
    private String updatedAt;

    @Version
    @Column(name = "version")
    private Long version;
}
