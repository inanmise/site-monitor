package com.sitemonitor.service.schema;

import java.sql.SQLException;

/**
 * İndeks DDL'i (REINDEX / DROP INDEX CONCURRENTLY) başka bir oturumu beklettiği için bekçi ({@link DdlBlockWatch}) tarafından
 * iptal edildi (2026-11-06, inceleme ORTA-2). SQLSTATE 57014 (iptal) korunur; çağıran bunu "başarısız" değil "yol verdi" diye
 * ele alır: bu koşu biter, kalıntı temizlenir. Günlükte {@link #reason()} yazılır (neden zincirinin kökü yalnız
 * "canceling statement due to user request" der).
 */
final class BlockingOthersException extends SQLException {

    private final String reason;

    BlockingOthersException(String reason, SQLException cause) {
        super("başka bir oturumu bekletiyordu, bırakıldı (" + reason + ")", "57014", cause);
        this.reason = reason;
    }

    /** Bekçinin gerekçesi (ör. "1 oturum bu DDL'i bekliyordu"). */
    String reason() {
        return reason;
    }
}
