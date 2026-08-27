package com.sitemonitor.repository;

import com.sitemonitor.model.UserPushScope;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserPushScopeRepository extends JpaRepository<UserPushScope, Long> {
    List<UserPushScope> findByScopeType(String scopeType);
    Optional<UserPushScope> findByScopeTypeAndScopeKey(String scopeType, String scopeKey);
}
