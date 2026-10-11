package com.sitemonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Nöbet yer değiştirmesi (2026-10-11): {@code [startsAt, endsAt)} aralığında takımın nöbetçisi rotasyon yerine
 * {@code userId}. Çakışırsa en son eklenen geçerli. Bitişinden 180 gün sonra servis kaydederken budanır.
 */
@Entity
@Table(name = "oncall_overrides", indexes = @Index(name = "idx_oncall_ovr_team", columnList = "team_id, ends_at"))
@Data
@NoArgsConstructor
public class OnCallOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** UTC ISO (yyyy-MM-ddTHH:mm:ss). */
    @Column(name = "starts_at", length = 19, nullable = false)
    private String startsAt;

    @Column(name = "ends_at", length = 19, nullable = false)
    private String endsAt;

    @Column(length = 200)
    private String note;

    @Column(name = "created_at", length = 19)
    private String createdAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;
}
