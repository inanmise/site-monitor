package com.sitemonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Teknik hata raporunun DEĞİŞİKLİK GEÇMİŞİ (2026-10-14, kullanıcı isteği: "kim, ne zaman, hangi alanı neyden neye
 * değiştirdi"). Her kayıt, görsel ekleme / değiştirme / silme, şablon uygulama, oluşturma ve gönderim bir satır. Olay,
 * değişikliği yazan işlemin İÇİNDE eklenir (değişiklik geri alınırsa olay da). Ayrıntı ({@code detail_json}) yazıldığı
 * andaki biçimiyle saklanır (alan adı, eski → yeni değer şablonun dilinde) — şablon sonradan değişse de geçmiş okunur.
 *
 * <p>Rapor başına en çok {@code TechErrorHistoryService.MAX_EVENTS_PER_REPORT} satır (eskiler budanır); rapor
 * silinince aynı işlemde silinir, yetim satır saklama kuralıyla temizlenir. {@code actor_name} yazıldığı andaki ad
 * (kullanıcı sonradan silinse de okunur); dışarı yalnız ad verilir, kullanıcı kimliği verilmez.
 */
@Entity
@Table(name = "tech_error_report_events",
        indexes = @Index(name = "idx_tere_report", columnList = "report_id, id"))
@Data
@NoArgsConstructor
public class TechErrorReportEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "event_at", nullable = false)
    private Instant eventAt;

    /** Değişikliği yapan; {@code null} = sistem (ör. kuyruktaki gönderimin sonucu). Dışarı verilmez. */
    @JsonIgnore
    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_name", length = 200)
    private String actorName;

    /** CREATE | VALUES | IMAGE_ADD | IMAGE_REPLACE | IMAGE_DELETE | TEMPLATE_REFRESH | SEND | SEND_RESULT | APPROVAL_REQUEST | APPROVAL */
    @Column(nullable = false, length = 30)
    private String kind;

    /** Değişiklikten sonraki rapor sürümü (değer kaydı / şablon uygulama); diğerlerinde boş. */
    @Column(name = "report_version")
    private Long reportVersion;

    @JsonIgnore
    @Column(name = "detail_json", columnDefinition = "TEXT")
    private String detailJson;
}
