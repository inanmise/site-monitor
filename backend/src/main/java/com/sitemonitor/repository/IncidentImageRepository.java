package com.sitemonitor.repository;

import com.sitemonitor.model.IncidentImage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface IncidentImageRepository extends JpaRepository<IncidentImage, Long> {

    /** Görselin baytsız bilgisi (teknik hata raporuna içe alma, 2026-11-20). */
    interface Meta {
        Long getId();
        Long getIncidentId();
        String getCaption();
        String getContentType();
        Long getSizeBytes();
    }

    /** Olaya BAĞLI görsellerin baytsız listesi (kimlik sırasıyla); çağıran sayfa boyutuyla sınırlar. */
    @Query("""
            SELECT i.id AS id, i.incidentId AS incidentId, i.caption AS caption, i.contentType AS contentType,
                   i.sizeBytes AS sizeBytes
              FROM IncidentImage i
             WHERE i.incidentId = :incidentId
             ORDER BY i.id
            """)
    List<Meta> findMetaByIncident(@Param("incidentId") Long incidentId, Pageable page);

    /** Görselin baytları — YÖNETİLEN varlık değil (inceleme 2026-11-20): işlemin kalıcılık bağlamında tutulmaz, bırakılınca biter. */
    interface ImageBytes {
        Long getId();
        byte[] getData();
    }

    /** Bu olayın bu görselinin baytları (başka olayın görseli boş liste döner). */
    @Query("SELECT i.id AS id, i.data AS data FROM IncidentImage i WHERE i.id = :id AND i.incidentId = :incidentId")
    List<ImageBytes> findDataOfIncident(@Param("id") Long id, @Param("incidentId") Long incidentId);
}
