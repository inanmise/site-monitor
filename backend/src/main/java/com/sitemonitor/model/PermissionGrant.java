package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "permission_grants",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_perm_role_resource_action",
        columnNames = {"role", "resource_key", "action"}),
    indexes = @Index(name = "idx_perm_role", columnList = "role"))
@Data
@NoArgsConstructor
public class PermissionGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String role;

    @Column(name = "resource_key", nullable = false, length = 64)
    private String resourceKey;

    @Column(nullable = false, length = 16)
    private String action;

    @Column(nullable = false)
    private Boolean allowed;

    @Column(name = "updated_at")
    private String updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;
}
