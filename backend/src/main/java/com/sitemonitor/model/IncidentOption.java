package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Olay modülü için yönetilen seçenek listesi (kanal / domain). Incident sayfasından
 * yeni değer eklenebilir; (type, value) tekildir. Dropdown'lar buradan beslenir.
 * Sabit enum DEĞİL — kullanıcı genişletebilir (banka kanalları zamanla değişir).
 */
@Entity
@Table(
    name = "incident_options",
    // (type, opt_value) tekil kısıtı KALDIRILDI — seçenekler artık takıma özel (team_id); aynı değer
    // farklı takımlarda bulunabilir. Tekrarlar uygulama düzeyinde (ensureOption kapsam kontrolü) önlenir.
    // Mevcut DB'lerde eski kısıt SchedulerService.applySchemaPatches içinde DROP edilir.
    indexes = @Index(name = "idx_inc_opt_type", columnList = "type")
)
@Data
@NoArgsConstructor
public class IncidentOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** CHANNEL | DOMAIN */
    @Column(nullable = false, length = 20)
    private String type;

    /** Kolon adı opt_value — `value` H2/SQL'de rezerve kelime (portabilite). */
    @Column(name = "opt_value", nullable = false, length = 150)
    private String value;

    private String createdBy;
    private String createdAt;

    /** Seçeneğin ait olduğu takım. null = GLOBAL (tohumlanan varsayılanlar + admin'in eklediği) →
     *  herkese görünür. Dolu ise yalnız o takıma (+ global'lere) görünür ve yalnız o takım silebilir. */
    @Column(name = "team_id")
    private Long teamId;

    public IncidentOption(String type, String value, String createdBy, String createdAt) {
        this(type, value, createdBy, createdAt, null);
    }

    public IncidentOption(String type, String value, String createdBy, String createdAt, Long teamId) {
        this.type = type;
        this.value = value;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.teamId = teamId;
    }
}
