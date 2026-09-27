package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 7/24 İzleme Ekibi (NOC) bildirim grubu (2026-09-27) — GLOBAL (takıma bağlı DEĞİL) adlandırılmış e-posta listesi.
 *
 * <p>Takım {@link NotificationGroup} yapısından bilerek AYRI: o, bir takımın kendi nöbet düzenidir; bu ise kurumun
 * 7/24 ekranlardan izleyen ekibinin adres(ler)idir. Yalnız global yönetici yazar. Bir izleme kendi grup kimliklerini
 * seçmezse {@code is_default} gruplara, hiç varsayılan yoksa TÜM aktif gruplara gider
 * ({@code NocGroupService.resolveTargets}).
 *
 * <p>Tablo YENİ olduğundan kolonlar ddl-auto ile kısıtlarıyla birlikte doğar; ad benzersizliği yine de uygulama
 * katmanında (harf duyarsız) doğrulanır — {@code LOWER(name)} tekilliği ddl-auto'nun ifade edemediği bir kısıttır.
 */
@Entity
@Table(name = "noc_notification_groups")
@Getter @Setter @NoArgsConstructor
public class NocNotificationGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    /** Normalize virgüllü adres listesi (1..50, harf duyarsız tekil). BOŞ grup kaydedilemez. */
    @Column(columnDefinition = "TEXT")
    private String emails;

    /** Pasif grup çözümlemede YOK SAYILIR; izlemelerdeki seçim korunur. */
    @Column(nullable = false)
    private Boolean active = true;

    /** Varsayılan grup — kendi grubu seçilmemiş izlemeler buna gider. Birden çok varsayılan olabilir. */
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    @Column(name = "created_at", length = 30)
    private String createdAt;

    @Column(name = "updated_at", length = 30)
    private String updatedAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "updated_by_name", length = 200)
    private String updatedByName;
}
