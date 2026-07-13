package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * İzleme grubu registry'si — TAKIM + İZLEME TÜRÜ bazlı kanonik grup adı kaydı. Grup adları (team_id, type, name)
 * üçlüsünde case-insensitive BENZERSİZDİR: aynı ad ("deneme") DNS ve Ping türlerinde AYRI gruplardır. İzleme kayıtları
 * gruba (team_id + type + group_name) üzerinden denormalize bağlanır; bu tablo benzersizlik + listeleme + rename kaynağı.
 * {@code type} ∈ {cert, http, ping, port, dns, keyword, domain}. {@code nameLower} = name.toLowerCase(ROOT).
 */
@Entity
@Table(name = "monitoring_groups",
       uniqueConstraints = @UniqueConstraint(name = "ux_mon_groups_team_type_lname", columnNames = {"team_id", "type", "name_lower"}))
@Data
@NoArgsConstructor
public class MonitoringGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    /** İzleme türü — cert/http/ping/port/dns/keyword/domain. Aynı ad farklı türlerde ayrı gruptur. */
    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String name;

    /** Benzersizlik için lowercase kopya (case-insensitive UNIQUE). */
    @Column(name = "name_lower", nullable = false)
    private String nameLower;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at")
    private String createdAt;
}
