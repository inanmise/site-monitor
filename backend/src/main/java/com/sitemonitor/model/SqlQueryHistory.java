package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "sql_query_history",
    indexes = {
        @Index(name = "idx_sqh_executed_at", columnList = "executedAt"),
        @Index(name = "idx_sqh_executed_by", columnList = "executedBy")
    }
)
@Data
@NoArgsConstructor
public class SqlQueryHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String executedBy;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String sqlText;

    private Integer rowCount;

    private Long durationMs;

    @Column(nullable = false)
    private Boolean success;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false, length = 30)
    private String executedAt;
}
