package com.sitemonitor.service.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code cache_bus} tablosu (2026-10-22, çok pod R2-A, MP-30) — açılışta, İDEMPOTENT ({@code AlarmStateSchemaPatch} deseni;
 * paylaşılan {@code SchedulerService.applySchemaPatches}'e dokunulmaz, çok pod planı G4). Entity YOK: tabloyu yalnız
 * {@link CacheBus} ham SQL ile okur / yazar ({@code monitor_check_schedule} gibi). {@code monitor_check_schedule}'a önek
 * ile eklenmedi: 20.165.x pod'unun {@code ClusterScheduleService.prune}'u {@code round:} / {@code lease:} dışındaki her
 * anahtarı yuvarlanan dağıtımda silerdi.
 *
 * <p>Yama düşerse açılış durmaz: {@link CacheBus} tabloyu bulamadığında sessizce devre dışıdır (önbellekler TTL ile) ve
 * sonraki açılışta yeniden denenir. Veri yolu iş parçacığından ({@link CacheBus#onReady}, en son sırada) ÖNCE koşar.
 * Kapı: {@code CacheBusSchemaPatchTest}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheBusSchemaPatch {

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE + 100)
    public void onReady() {
        try {
            apply();
        } catch (Exception e) {
            log.warn("Önbellek yolu şema yaması uygulanamadı (sonraki açılışta yeniden denenecek; önbellekler TTL ile tazelenir): {}",
                    e.getMessage());
        }
    }

    /** @return bu koşuda tablo kuruldu mu (false = zaten vardı). */
    public boolean apply() {
        if (tableExists()) return false;
        jdbc.execute(CacheBus.DDL);
        log.info("Önbellek yolu şema yaması: {} tablosu kuruldu", CacheBus.TABLE);
        return true;
    }

    boolean tableExists() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
              + "WHERE lower(table_schema)='public' AND lower(table_name)=lower(?)",
                Integer.class, CacheBus.TABLE);
        return n != null && n > 0;
    }
}
