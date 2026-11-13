package com.sitemonitor.repository;

import com.sitemonitor.model.SqlSavedQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** SQL Playground kayıtlı sorguları (2026-11-13, D1). Yazma yalnız {@code save} / {@code delete} (Spring Data işlemi). */
public interface SqlSavedQueryRepository extends JpaRepository<SqlSavedQuery, Long> {

    long countByOwnerUsername(String ownerUsername);

    Optional<SqlSavedQuery> findByOwnerUsernameAndTitleKey(String ownerUsername, String titleKey);

    /**
     * Liste: {@code scope} 0 = benim, 1 = paylaşılan (sahibi kim olursa), 2 = benim + paylaşılan. {@code q} başlık /
     * açıklama / SQL'de (küçük harf, LIKE kaçışlı, {@code %} = hepsi); {@code tag} = {@code %,etiket,%} (boş → {@code %}).
     */
    @Query(value = "SELECT s FROM SqlSavedQuery s WHERE "
            + "((:scope = 0 AND s.ownerUsername = :user) OR (:scope = 1 AND s.shared = true) "
            + " OR (:scope = 2 AND (s.ownerUsername = :user OR s.shared = true))) "
            + "AND (LOWER(s.title) LIKE :q ESCAPE '!' OR LOWER(COALESCE(s.description, '')) LIKE :q ESCAPE '!' "
            + " OR LOWER(s.sqlText) LIKE :q ESCAPE '!') "
            + "AND LOWER(COALESCE(s.tags, '')) LIKE :tag ESCAPE '!'",
           countQuery = "SELECT COUNT(s) FROM SqlSavedQuery s WHERE "
            + "((:scope = 0 AND s.ownerUsername = :user) OR (:scope = 1 AND s.shared = true) "
            + " OR (:scope = 2 AND (s.ownerUsername = :user OR s.shared = true))) "
            + "AND (LOWER(s.title) LIKE :q ESCAPE '!' OR LOWER(COALESCE(s.description, '')) LIKE :q ESCAPE '!' "
            + " OR LOWER(s.sqlText) LIKE :q ESCAPE '!') "
            + "AND LOWER(COALESCE(s.tags, '')) LIKE :tag ESCAPE '!'")
    Page<SqlSavedQuery> search(@Param("scope") int scope, @Param("user") String user, @Param("q") String q,
                               @Param("tag") String tag, Pageable pageable);
}
