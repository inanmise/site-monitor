package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

/**
 * Nöbet rutini devralma (2026-11-26): bir günün ({@code DAY}, anahtar = tarih) ya da bir devir döneminin ({@code WEEK},
 * anahtar = dönem anahtarı) rutin sorumlusu. YALNIZ rutini etkiler — alarm nöbet yönlendirmesi (çizelge, yer değiştirme)
 * DEĞİŞMEZ.
 */
@Entity
@DynamicUpdate
@Table(name = "oncall_routine_assignments",
        uniqueConstraints = @UniqueConstraint(name = "uk_ora_team_scope", columnNames = {"team_id", "scope", "scope_key"}))
@Data
@NoArgsConstructor
public class OnCallRoutineAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    /** DAY | WEEK. */
    @Column(length = 8, nullable = false)
    private String scope;

    @Column(name = "scope_key", length = 10, nullable = false)
    private String scopeKey;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "user_name", length = 200)
    private String userName;

    @Column(length = 200)
    private String note;

    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "created_by_name", length = 200)
    private String createdByName;

    @Column(name = "created_at", length = 30)
    private String createdAt;
}
