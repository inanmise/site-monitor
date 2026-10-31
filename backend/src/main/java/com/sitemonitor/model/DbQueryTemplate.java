package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Takım sorgu şablonu (2026-10-31): sık kullanılan sorgu + kural kümesini adıyla saklar; yeni izleme "şablondan başla" ile
 * doldurulur. Takım belgesidir (yalnız sahip takım görür; paylaşımla açılmaz). Takımda ≤ 50, ad takımda tekil.
 * Şablonun sorgusu da kayıtta {@code DbQuerySqlGuard} denetiminden geçer.
 */
@Entity
@Table(name = "dbquery_templates",
       indexes = @Index(name = "idx_dbqt_team", columnList = "team_id"))
@Data
@NoArgsConstructor
public class DbQueryTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(nullable = false, length = 120)
    private String name;

    /** Hangi veritabanı türü için yazıldığı (ORACLE | MSSQL | POSTGRESQL); null = türden bağımsız. */
    @Column(name = "db_type", length = 12)
    private String dbType;

    @Column(length = 500)
    private String description;

    @Column(name = "sql_text", columnDefinition = "TEXT")
    private String sqlText;

    @Column(columnDefinition = "TEXT")
    private String purpose;

    @Column(name = "rules_json", columnDefinition = "TEXT")
    private String rulesJson;

    @Column(name = "rule_logic", length = 8)
    private String ruleLogic;

    @Column(name = "created_at", length = 30)
    private String createdAt;

    @Column(name = "created_by_name")
    private String createdByName;

    @Column(name = "updated_at", length = 30)
    private String updatedAt;

    @Column(name = "updated_by_name")
    private String updatedByName;
}
