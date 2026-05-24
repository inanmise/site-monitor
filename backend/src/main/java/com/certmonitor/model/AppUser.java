package com.certmonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "app_users",
    indexes = {
        @Index(name = "idx_user_username", columnList = "username"),
        @Index(name = "idx_user_team",     columnList = "team_id")
    })
@Data
@NoArgsConstructor
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @JsonIgnore
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    private String displayName;
    private String email;

    @Column(name = "employee_id", length = 50)
    private String employeeId;

    /** ADMIN — full access to all teams; USER — restricted to own team */
    @Column(nullable = false)
    private String systemRole = "USER";

    /** Organizational role: PO | TECH | MANAGER | CLEVEL | null (regular member) */
    @Column(name = "org_role")
    private String orgRole;

    @Column(name = "team_id")
    private Long teamId;

    @Column(nullable = false)
    private Boolean active = true;

    /** ISO-UTC timestamp until which this account is temporarily locked. */
    @Column(name = "lockout_until", length = 30)
    private String lockoutUntil;

    /** How many times progressive lockout has been applied (drives escalation). */
    @Column(name = "failed_block_count")
    private Integer failedBlockCount = 0;

    /** Permanent lock set after max escalation — only admin can clear. */
    @Column(name = "permanent_lock")
    private Boolean permanentLock = false;

    /** ISO-UTC timestamp when the last progressive lockout was applied (used to reset the failure-count window). */
    @Column(name = "last_lockout_at", length = 30)
    private String lastLockoutAt;

    private String createdAt;
    private String updatedAt;
}
