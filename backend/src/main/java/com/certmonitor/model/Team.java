package com.certmonitor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "teams")
@Data
@NoArgsConstructor
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    @Column(nullable = false)
    private Boolean active = true;

    /** ID of the user who leads / owns this team (PO or team lead). Required. */
    @Column(name = "leader_id")
    private Long leaderId;

    private String createdAt;
    private String updatedAt;
}
