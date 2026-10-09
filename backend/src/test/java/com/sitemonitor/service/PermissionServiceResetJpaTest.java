package com.sitemonitor.service;

import com.sitemonitor.repository.PermissionGrantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * "Varsayılana dön" (2026-10-09): dolu tabloda {@link PermissionService#seedDefaults()} gerçek JPA + benzersiz kısıtla
 * ({@code uk_perm_role_resource_action}) koşar. Eskiden {@code deleteAll()} kaldırmaları kuyrukta beklerken IDENTITY
 * kimlikli {@code save()} INSERT'i anında attığından kısıt ihlali → 409 dönüyordu.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PermissionServiceResetJpaTest {

    @Autowired PermissionGrantRepository repo;

    @Test
    @DisplayName("dolu tabloda seedDefaults kısıt ihlali vermez; satır sayısı katalogla aynı kalır")
    void seedDefaults_onPopulatedTable_noUniqueViolation() {
        PermissionService svc = new PermissionService(repo);
        svc.seedDefaultsIfEmpty();
        long seeded = repo.count();
        assertThat(seeded).isPositive();

        assertThatCode(svc::seedDefaults).doesNotThrowAnyException();
        repo.flush();
        assertThat(repo.count()).isEqualTo(seeded);
        assertThat(svc.allows("ADMIN", "inventory.list", "view")).isTrue();
    }
}
