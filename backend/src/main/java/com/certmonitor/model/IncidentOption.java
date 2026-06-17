package com.certmonitor.model;

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
    uniqueConstraints = @UniqueConstraint(name = "uk_inc_opt_type_value", columnNames = {"type", "opt_value"}),
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

    public IncidentOption(String type, String value, String createdBy, String createdAt) {
        this.type = type;
        this.value = value;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }
}
