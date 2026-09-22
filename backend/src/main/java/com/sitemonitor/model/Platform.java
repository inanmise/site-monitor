package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sitenin koştuğu platform kataloğu (2026-09-22, kullanıcı isteği): IIS, OpenShift, Kubernetes, Linux… Ayarlar →
 * Platformlar'dan yönetilir; envanter kaydı {@code certificate_inventory.platform} alanında bu kataloğun {@code code}
 * değerini taşır. Kod ÜST-HARF slug (IIS, OPENSHIFT, K8S_PROD…), değişmez; ad/açıklama serbestçe düzenlenir.
 * Kullanımda olan platform silinmez, pasife alınır (envanter kayıtları bozulmasın; seçicide görünmez).
 */
@Entity
@Table(name = "platforms", indexes = { @Index(name = "idx_platform_code", columnList = "code", unique = true) })
@Data
@NoArgsConstructor
public class Platform {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Üst-harf slug, tekil (A-Z0-9_ ; 2–20). Envanterdeki referans anahtarı. */
    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(length = 300)
    private String description;

    @Column(nullable = false)
    private Boolean active = true;

    /** Seçicideki sıra (küçük önce); eşitse ada göre. */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 100;

    @Column(name = "created_at", length = 30)
    private String createdAt;

    @Column(name = "updated_at", length = 30)
    private String updatedAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;
}
