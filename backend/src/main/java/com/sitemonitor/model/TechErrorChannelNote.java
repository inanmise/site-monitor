package com.sitemonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Kanal NOTU (2026-11-20, kullanıcı isteği: "bu kanalın verisini nereden / nasıl alıyorum"): teknik hata raporu kanalı
 * başına TEK zengin metin belgesi (Markdown, başlıklar belgenin içinde). Kanal satırına sütun olarak eklenmedi: genel
 * bakış her kanalın tam varlığını okur — 60 000 karakterlik metin her liste okumasını şişirirdi.
 *
 * <p>Takım kopyası YOK: takım her istekte kanal satırından türetilir (takım taşıma bu tabloya dokunmaz). Sürüm 1'den
 * başlar ({@code 0} = "henüz not yok" — istemci ilk kayıtta 0 gönderir); her gerçek değişiklikte artar. Yazım kanal satırı
 * kilidi altında yapılır ({@code TechErrorChannelNoteService}); {@code @Version} kilidi atlayan bir yazıma karşı ek güvence.
 * Metin hiçbir günlüğe / denetim ayrıntısına yazılmaz. Kullanıcı kimlikleri yanıta yazılmaz — yalnız o anki ad.
 */
@Entity
@Table(name = "tech_error_channel_notes",
        uniqueConstraints = @UniqueConstraint(name = "uk_tech_error_channel_note_channel", columnNames = {"channel_id"}))
@Data
@NoArgsConstructor
@org.hibernate.annotations.DynamicUpdate
public class TechErrorChannelNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    /** Markdown (GFM), LF satır sonu, ≤ 60 000 UTF-16 birimi; yalnız boşluktan oluşan gövde boş metin olarak saklanır. */
    @JsonIgnore
    @lombok.ToString.Exclude                                   // not metni hiçbir günlüğe / hata iletisine düşmez
    @Column(name = "body_md", columnDefinition = "TEXT", nullable = false)
    private String bodyMd;

    /**
     * İlk kayıtta servis 1 yazar ({@code setVersion(1)}), Hibernate dolu başlangıç sürümünü olduğu gibi saklar. İlkel tür
     * BİLİNÇLİ: Spring Data yeni kaydı kimlikten tanır (sarmalayıcı türde "sürüm dolu = eski kayıt" sayıp {@code merge}
     * ederdi). Alan başlatıcısı (= 1) BİLİNÇLİ olarak yok: Hibernate "kaydedilmemiş sürüm" değerini boş örnekten okur —
     * 1 olsaydı sürümü 1 olan kayıtlı bir satırı kopuk varlıkta yeni sanardı.
     */
    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at")
    private Instant createdAt;

    @JsonIgnore
    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "created_by_name", length = 200)
    private String createdByName;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @JsonIgnore
    @Column(name = "updated_by_id")
    private Long updatedById;

    @Column(name = "updated_by_name", length = 200)
    private String updatedByName;
}
