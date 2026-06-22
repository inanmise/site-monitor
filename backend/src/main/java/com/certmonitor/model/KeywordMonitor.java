package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Serbest-form anahtar kelime izleme monitörü: bir URL'nin HTTP yanıt gövdesinde
 * {@code keyword} aranır (case-insensitive). {@code alertCondition}:
 * NOT_CONTAINS → kelime bulunmazsa alarm; CONTAINS → kelime bulunursa alarm.
 * Envantere bağlı değildir; takım {@code teamId} ile açıkça atanır (alarm yönlendirmesi).
 */
@Entity
@Table(name = "keyword_monitors")
@Data
@NoArgsConstructor
public class KeywordMonitor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String url;

    @Column(nullable = false)
    private String keyword;

    /** "NOT_CONTAINS" (kelime yoksa alarm) | "CONTAINS" (kelime varsa alarm). */
    @Column(name = "alert_condition", nullable = false)
    private String alertCondition = "NOT_CONTAINS";

    /** Sorumlu takım — alarm yönlendirmesi (envanterden bağımsız). */
    @Column(name = "team_id")
    private Long teamId;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "interval_seconds")
    private Integer intervalSeconds = 60;

    @Column(name = "timeout_ms")
    private Integer timeoutMs = 10000;

    @Column(name = "created_at")
    private String createdAt;

    @Column(name = "updated_at")
    private String updatedAt;
}
