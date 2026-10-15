package com.sitemonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Kanal şablonunun SÜRÜM GEÇMİŞİ (2026-10-14): her şablon kaydı (ve kanal açılışı) o sürümün tam kopyasını yazar.
 * Kanalın şablon sütunu yalnız son hâli tutar; buradan eski bir sürüm okunur, önceki sürümle farkı gösterilir ve
 * istenirse düzenleyiciye yüklenir (kaydedince YENİ sürüm olur — geçmiş değişmez).
 *
 * <p>Kanal × sürüm tekil. Bu özellikten önce açılmış kanalda ilk kayıtta önceki (o anki) sürüm de taban olarak yazılır.
 * Kanal başına en çok {@code TechErrorHistoryService.MAX_TEMPLATE_VERSIONS} satır (eskiler budanır); kanal silinince
 * aynı işlemde silinir.
 */
@Entity
@Table(name = "tech_error_template_versions",
        uniqueConstraints = @UniqueConstraint(name = "uk_tech_error_template_version", columnNames = {"channel_id", "version"}))
@Data
@NoArgsConstructor
public class TechErrorTemplateVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private int version;

    @JsonIgnore
    @Column(name = "template_json", columnDefinition = "TEXT", nullable = false)
    private String templateJson;

    @Column(name = "saved_at")
    private Instant savedAt;

    @JsonIgnore
    @Column(name = "saved_by_id")
    private Long savedById;

    @Column(name = "saved_by_name", length = 200)
    private String savedByName;

    @Column(name = "section_count", nullable = false)
    private int sectionCount;

    @Column(name = "field_count", nullable = false)
    private int fieldCount;

    @Column(name = "slot_count", nullable = false)
    private int slotCount;
}
