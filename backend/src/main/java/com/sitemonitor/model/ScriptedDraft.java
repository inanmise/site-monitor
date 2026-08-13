package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kullanıcının k6 script düzenleme formundaki KAYDEDİLMEMİŞ hâli (otomatik taslak).
 *
 * <p>Neden var: kullanıcı script yazarken başka sayfaya geçtiğinde ya da sekmeyi kapattığında
 * yazdıkları tamamen kayboluyordu; düzenleme modalında hiçbir taslak/terk koruması yoktu.
 *
 * <p>Taslak KULLANICI BAŞINA tutulur: aynı monitörü iki kişi düzenlerken birbirlerinin taslağını
 * ezmemeli. Anahtar {@code owner + monitorKey} (unique index) — {@code monitorKey} mevcut monitörde
 * id'nin metni, hiç kaydedilmemiş yeni monitörde {@code "new"}. Neden nullable id yerine metin:
 * PostgreSQL unique index'te NULL'ları BİRBİRİNDEN FARKLI sayar, yani {@code monitor_id IS NULL}
 * satırları çoğalırdı.
 */
@Entity
@Table(
    name = "scripted_drafts",
    indexes = {
        @Index(name = "idx_sdraft_owner",   columnList = "owner"),
        @Index(name = "idx_sdraft_updated", columnList = "updatedAt")
    }
)
@Data
@NoArgsConstructor
public class ScriptedDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Canonical (BÜYÜK harf) kullanıcı adı — app_users.username ile aynı biçim. */
    @Column(nullable = false, length = 100)
    private String owner;

    /** Monitör id'sinin metni ya da hiç kaydedilmemiş yeni monitör için {@code "new"}. */
    @Column(nullable = false, length = 40)
    private String monitorKey;

    /** Gösterim/kolaylık için; {@code monitorKey="new"} iken null. */
    private Long monitorId;

    /** Formun tamamı (ad, script, env, ayarlar) JSON olarak — kısmi/eksik olabilir. */
    @Column(columnDefinition = "TEXT")
    private String formJson;

    /** Taslakta yazılı monitör adı — "devam et" şeridinde göstermek için (form içinden kopya). */
    private String monitorName;

    @Column(nullable = false)
    private String updatedAt;
}
