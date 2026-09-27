package com.sitemonitor.repository;

import com.sitemonitor.model.NocCallLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface NocCallLogRepository extends JpaRepository<NocCallLog, Long> {

    /** Uyarının kayıtları — en yeni arama önce (aynı anda girilenlerde son eklenen önce). */
    List<NocCallLog> findByAlertIdOrderByContactedAtDescIdDesc(Long alertId);

    /**
     * Uyarı listesi özeti — sayfadaki uyarılar için TEK sorgu (N+1 yok): her uyarının EN SON araması
     * ({@code contacted_at}, eşitlikte büyük id) ve o uyarının toplam arama sayısı.
     * Satır: {@code [alertId, contactedName, outcome, contactedAt, count]}.
     *
     * <p>{@code contacted_at} sabit genişlikte UTC ISO olduğundan sözlüksel karşılaştırma zaman sırasıdır.
     */
    @Query("SELECT c.alertId, c.contactedName, c.outcome, c.contactedAt, "
         + "       (SELECT COUNT(d) FROM NocCallLog d WHERE d.alertId = c.alertId) "
         + "FROM NocCallLog c "
         + "WHERE c.alertId IN :ids "
         + "  AND NOT EXISTS (SELECT 1 FROM NocCallLog n WHERE n.alertId = c.alertId "
         + "                   AND (n.contactedAt > c.contactedAt "
         + "                        OR (n.contactedAt = c.contactedAt AND n.id > c.id)))")
    List<Object[]> summarizeByAlertIds(@Param("ids") Collection<Long> ids);
}
