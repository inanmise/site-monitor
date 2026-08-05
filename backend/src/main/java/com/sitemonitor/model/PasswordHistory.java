package com.sitemonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Archive of a user's outgoing password hash, kept so that
 * UserService.changePassword can reject reuse of the most recent N
 * passwords (see site.monitor.password.history-count).
 */
@Entity
@Table(name = "password_history",
       indexes = { @Index(name = "idx_pwhist_user_at", columnList = "user_id, created_at") })
@Data
@NoArgsConstructor
public class PasswordHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "password_hash", nullable = false, columnDefinition = "TEXT")
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
