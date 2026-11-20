package com.sitemonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Kanal notunun SÜRÜM GEÇMİŞİ (2026-11-20): her gerçek kayıt o sürümün tam metnini yazar; kanal başına en yeni 20 sürüm
 * tutulur (eskiler aynı işlemde budanır). Eski bir sürüm düzenleyiciye KAYDEDİLMEMİŞ taslak olarak yüklenir —
 * kaydedilince yeni sürüm olur, geçmiş değişmez. Kanal × sürüm tekil (tekil kısıtın indeksi kanal sorgularını da karşılar);
 * kanal silinince aynı işlemde silinir. Kaydedenin yalnız o anki ADI tutulur (kullanıcı kimliği yok).
 */
@Entity
@Table(name = "tech_error_channel_note_versions",
        uniqueConstraints = @UniqueConstraint(name = "uk_tech_error_channel_note_version", columnNames = {"channel_id", "version"}))
@Data
@NoArgsConstructor
public class TechErrorChannelNoteVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(nullable = false)
    private long version;

    @JsonIgnore
    @lombok.ToString.Exclude                                   // not metni hiçbir günlüğe / hata iletisine düşmez
    @Column(name = "body_md", columnDefinition = "TEXT", nullable = false)
    private String bodyMd;

    @Column(name = "saved_at")
    private Instant savedAt;

    @Column(name = "saved_by_name", length = 200)
    private String savedByName;

    @Column(name = "char_count", nullable = false)
    private int charCount;

    @Column(name = "image_count", nullable = false)
    private int imageCount;
}
