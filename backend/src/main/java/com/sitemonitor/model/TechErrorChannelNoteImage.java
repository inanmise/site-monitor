package com.sitemonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Kanal notuna yüklenen görsel (2026-11-20). Rapor görselleriyle aynı depolama: yalnız PNG / JPEG, tür / boyut / piksel
 * ölçüsü yüklemede baytlardan doğrulanır ({@code TechErrorImageValidator}); {@code @Lob} bilinçli olarak yok —
 * PostgreSQL'de BYTEA. Her yükleme YENİ satırdır (yerine yazma yok): not metni görseli kimliğiyle anar
 * ({@code te-note-img:<id>}).
 *
 * <p>{@code linked}: kayıtlı not metni bu görseli anıyor mu. Yüklemede {@code false}; not her kaydedildiğinde kanalın
 * bütün satırları için toplu UPDATE ile yeniden hesaplanır (bayt okunmaz) ve yalnız bayrak DEĞİŞİNCE
 * {@code link_changed_at} damgalanır. Bağlı olmayan görsel 30 gün sonra saklama kuralıyla silinir (kaydedilmemiş
 * taslaktaki yükleme ve yakın tarihli bir sürüme dönüş bu süre içinde görselini bulur). İki sütun yalnız eklemede ve toplu
 * UPDATE ile yazılır ({@code updatable = false}) — bir varlık kaydı bayrağı geri saramaz.
 */
@Entity
@Table(name = "tech_error_channel_note_images",
        indexes = @Index(name = "idx_tecni_channel", columnList = "channel_id"))
@Data
@NoArgsConstructor
public class TechErrorChannelNoteImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "content_type", nullable = false, length = 20)
    private String contentType;

    @Column(name = "file_name", length = 200)
    private String fileName;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Uzunluk H2 içindir (varsayılan VARBINARY(255)); PostgreSQL'de tür yine BYTEA. Sınır yüklemede 5 MB. */
    @JsonIgnore
    @lombok.ToString.Exclude                                   // ≤ 5 MB bayt toString'e (günlük / hata iletisi) girmez
    @Column(nullable = false, length = 10 * 1024 * 1024)
    private byte[] data;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    @JsonIgnore
    @Column(name = "uploaded_by_id")
    private Long uploadedById;

    @Column(nullable = false, updatable = false)
    private boolean linked = false;

    @Column(name = "link_changed_at", updatable = false)
    private Instant linkChangedAt;
}
