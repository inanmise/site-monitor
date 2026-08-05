package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Çalışma anında düzenlenebilir tek bir uygulama config override'ı (key/value).
 * Genel Ayarlar sayfasından set edilir; yalnız değiştirilmiş key'ler satır olarak tutulur.
 * Override yoksa efektif değer application.properties/@Value varsayılanıdır.
 *
 * `key`/`value` SQL'de rezerve kelime olduğundan kolonlar setting_key/setting_value.
 */
@Entity
@Table(
    name = "app_settings",
    uniqueConstraints = @UniqueConstraint(name = "ux_app_settings_key", columnNames = "setting_key")
)
@Data
@NoArgsConstructor
public class AppSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "setting_key", nullable = false, length = 150)
    private String settingKey;

    @Column(name = "setting_value", columnDefinition = "TEXT")
    private String value;

    private String updatedAt;
    private String updatedBy;

    public AppSetting(String settingKey, String value, String updatedAt, String updatedBy) {
        this.settingKey = settingKey;
        this.value = value;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }
}
