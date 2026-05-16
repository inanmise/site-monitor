package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "certificate_inventory")
@Data
@NoArgsConstructor
public class CertificateInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String domain;

    @Column(nullable = false)
    private Integer port = 443;

    private String description;
    private String owner;

    @Column(columnDefinition = "TEXT")
    private String tags;

    @Column(nullable = false)
    private Boolean active = true;

    /** SHA-256 hex of the expected (newly renewed) cert — used for DEPLOYMENT_INCOMPLETE detection */
    private String expectedFingerprint;
    private String expectedSubject;

    private String createdAt;
    private String updatedAt;
}
