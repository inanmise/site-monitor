package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Hedefe (ping=host, keyword=url) bağlı TEK rehber bloğu (markdown): "bu alarm gelince ne yapılır".
 * Keyword/ping izleme detay modalındaki "Rehber & Notlar" sekmesinde gösterilir. Hedef bazlı olduğu
 * için monitör silinip yeniden kurulsa da kalır; aynı hedefi izleyen monitörler paylaşır.
 */
@Entity
@Table(name = "monitor_guide",
       uniqueConstraints = @UniqueConstraint(name = "uq_monitor_guide_target",
               columnNames = {"monitor_type", "target"}))
@Data
@NoArgsConstructor
public class MonitorGuide {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** KEYWORD | PING */
    @Column(name = "monitor_type", nullable = false, length = 20)
    private String monitorType;

    /** ping = host, keyword = url */
    @Column(nullable = false, length = 500)
    private String target;

    @Column(columnDefinition = "TEXT")
    private String guide;

    @Column(name = "updated_at")
    private String updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;
}
