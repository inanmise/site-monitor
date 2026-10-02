package com.sitemonitor.repository;

import com.sitemonitor.model.UserBulkOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Toplu kullanıcı işlemi kayıtları ({@code user_bulk_operations}, 2026-10-02). Satırlar silinmez (RetentionCatalog
 * {@code user-bulk-operations}, BOUNDED); türetilmiş silme yöntemi YOK.
 */
public interface UserBulkOperationRepository extends JpaRepository<UserBulkOperation, Long> {

    /** Son N işlem (yeni → eski) — sihirbazdaki "Son toplu işlemler" listesi. */
    List<UserBulkOperation> findTop20ByOrderByIdDesc();

    /** Verilen işlemden SONRA başlamış, geri alınmamış aynı türden işlemler — geri alma kuralının ikinci ayağı. */
    List<UserBulkOperation> findByKindAndIdGreaterThanAndUndoneAtIsNull(String kind, Long id);

    /**
     * Geri almayı ATOMİK olarak sahiplenir: yalnız bitmiş ({@code DONE}) ve henüz geri alınmamış satır işaretlenir.
     * 0 → zaten geri alınmış / sürüyor (çağıran 409 döner); iki yönetici aynı anda bassa da yalnız biri kazanır.
     */
    @Modifying
    @Transactional
    @Query("UPDATE UserBulkOperation o SET o.undoneAt = :at, o.undoneBy = :by, o.undoneById = :byId "
         + "WHERE o.id = :id AND o.undoneAt IS NULL AND o.status = 'DONE'")
    int markUndone(@Param("id") Long id, @Param("at") String at, @Param("by") String by, @Param("byId") Long byId);

    /** Geri alma sonuç sayıları (sahiplenmeden sonra, ayrı küçük yazım). */
    @Modifying
    @Transactional
    @Query("UPDATE UserBulkOperation o SET o.undoOkCount = :ok, o.undoSkippedCount = :skipped, o.undoFailedCount = :failed "
         + "WHERE o.id = :id")
    int recordUndoResult(@Param("id") Long id, @Param("ok") int ok, @Param("skipped") int skipped, @Param("failed") int failed);
}
