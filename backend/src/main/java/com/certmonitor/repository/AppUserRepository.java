package com.certmonitor.repository;

import com.certmonitor.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
    Optional<AppUser> findByUsernameAndActiveTrue(String username);
    List<AppUser> findByTeamIdOrderByUsernameAsc(Long teamId);
    List<AppUser> findAllByOrderByUsernameAsc();
    boolean existsByUsername(String username);
}
