package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Takımın 7/24 arama listesinin bir satırı (2026-09-27): NOC bir sorun görünce takımdan KİMİ, hangi SIRAYLA arar.
 *
 * <p>Telefon burada SAKLANMAZ — AD'den gelen {@code AppUser.phone} gönderim anında CANLI okunur (elle yazılmış,
 * eskiyen numara yok). Telefon yalnız NOC e-postasına girer; arayüze asla dönmez ({@code has_phone} yeter).
 * Liste yalnız takım ÜYELERİNDEN kurulur (yazma anında doğrulanır).
 */
@Entity
@Table(name = "noc_team_call_list",
       uniqueConstraints = @UniqueConstraint(name = "ux_noc_call_team_user", columnNames = {"team_id", "user_id"}),
       indexes = @Index(name = "idx_noc_call_team", columnList = "team_id,position"))
@Getter @Setter @NoArgsConstructor
public class NocTeamCallEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 0'dan başlayan arama sırası. */
    @Column(nullable = false)
    private Integer position;

    @Column(name = "updated_at", length = 30)
    private String updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}
