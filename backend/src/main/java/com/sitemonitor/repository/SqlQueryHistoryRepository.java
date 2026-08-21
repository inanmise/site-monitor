package com.sitemonitor.repository;

import com.sitemonitor.model.SqlQueryHistory;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SqlQueryHistoryRepository extends JpaRepository<SqlQueryHistory, Long> {

    List<SqlQueryHistory> findTop50ByOrderByExecutedAtDesc();

    List<SqlQueryHistory> findTop50ByExecutedByOrderByExecutedAtDesc(String executedBy);

    /**
     * DB analitiği — pencere içindeki sorgu geçmişi (Java'da kullanıcı/SQL/zaman kümelemesi için).
     *
     * <p>TAVANLI ve EN YENİ-ÖNCE (2026-08-20 bellek denetimi). Eskiden LIMIT'siz ve ASC idi:
     * 30 günlük pencere tek bir List'e yükleniyordu, oysa sql-history retention varsayılanı
     * 365 GÜN — yani pencereyi retention sınırlamıyordu. Yoğun bir kurulumda tüm satırlar
     * (+ 9 ayrı toplayıcının ürettiği türev yapılar + @Cacheable payload'ı) aynı anda canlı olurdu.
     * Tavana takılırsa EN YENİ kayıtlar korunur; çağıran listeyi ASC'ye çevirir.
     */
    List<SqlQueryHistory> findByExecutedAtGreaterThanEqualOrderByExecutedAtDesc(String since, Limit limit);
}
