package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kurum geneli tatil / özel gün (2026-10-31). Europe/Istanbul takvim günü; "tatillerde çalışma" seçili izlemeler bu
 * günlerde çalışmaz. Global admin yönetir (Ayarlar → Platform → Tatil takvimi); ilk açılışta resmî tatiller tohumlanır.
 */
@Entity
@Table(name = "holiday_days")
@Data
@NoArgsConstructor
public class HolidayDay {

    /** {@code yyyy-MM-dd}. */
    @Id
    @Column(length = 10)
    private String day;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "created_at", length = 30)
    private String createdAt;

    @Column(name = "created_by_name")
    private String createdByName;
}
