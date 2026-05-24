package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "remember_me_tokens",
       indexes = @Index(name = "idx_rmt_token", columnList = "token", unique = true))
@Getter @Setter @NoArgsConstructor
public class RememberMeToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(nullable = false, length = 128)
    private String username;

    @Column(name = "expires_at", nullable = false)
    private long expiresAt; // Unix epoch seconds
}
