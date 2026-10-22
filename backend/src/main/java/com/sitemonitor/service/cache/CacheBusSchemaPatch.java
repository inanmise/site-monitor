package com.sitemonitor.service.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code cache_bus} tablosu (2026-10-22, çok pod R2-A, MP-30) — açılışta, İDEMPOTENT ({@code AlarmStateSchemaPatch} deseni;
 * paylaşılan {@code SchedulerService.applySchemaPatches}'e dokunulmaz, çok pod planı G4). Entity YOK: tabloyu yalnız
 * {@link CacheBus} ham SQL ile okur / yazar ({@code monitor_check_schedule} gibi). {@code monitor_check_schedule}'a önek
 * ile eklenmedi: 20.165.x pod'unun {@code ClusterScheduleService.prune}'u {@code round:} / {@code lease:} dışındaki her
 * anahtarı yuvarlanan dağıtımda silerdi.
 *
 * <p><b>Varlık denetimi</b> tablonun kendisine nitelemesiz bir okumadır ({@code SELECT 1 FROM cache_bus WHERE 1 = 0}) —
 * veri yolunun kendi sorgularıyla AYNI şema çözümü ({@code search_path} / geçerli şema; {@code public} varsayılmaz).
 * <b>İki pod aynı anda açılırsa</b> (PostgreSQL {@code CREATE TABLE IF NOT EXISTS} yarışında ikincisi {@code pg_type}
 * yinelenen anahtar hatası alır) hata sonrası tablo yeniden denetlenir: varsa öteki pod kurmuştur — yalnız DEBUG, sahte WARN
 * yok. Ortak {@code SMSCHEMA} kilidi bilerek KULLANILMAZ: öteki pod tüm yamalarını koşarken bu tek tablo açılışı 60 sn'ye
 * kadar bekletirdi; yarış zaten zararsız.
 *
 * <p>Yama düşerse açılış durmaz: {@link CacheBus} tabloyu bulamadığında sessizce devre dışıdır (önbellekler TTL ile) ve
 * sonraki açılışta yeniden denenir. Veri yolu iş parçacığından ({@link CacheBus#onReady}, en son sırada) ÖNCE koşar ve
 * tablo hazır olunca veri yoluna bildirir ({@link CacheBus#tableReady()}). Kapı: {@code CacheBusSchemaPatchTest}.
 */
@Slf4j
@Component
public class CacheBusSchemaPatch {

    private final JdbcTemplate jdbc;
    private final @Nullable ObjectProvider<CacheBus> bus;

    @Autowired
    public CacheBusSchemaPatch(JdbcTemplate jdbc, ObjectProvider<CacheBus> bus) {
        this.jdbc = jdbc;
        this.bus = bus;
    }

    /** Test kurulumu (veri yolu yok). */
    public CacheBusSchemaPatch(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

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

    /** @return bu koşuda tablo kuruldu mu (false = zaten vardı ya da öteki pod aynı anda kurdu). */
    public boolean apply() {
        boolean created = false;
        if (!tableExists()) {
            try {
                jdbc.execute(CacheBus.DDL);
                created = true;
                log.info("Önbellek yolu şema yaması: {} tablosu kuruldu", CacheBus.TABLE);
            } catch (RuntimeException e) {
                if (!tableExists()) throw e;
                log.debug("Önbellek yolu tablosu öteki pod tarafından aynı anda kuruldu: {}", e.getMessage());
            }
        }
        CacheBus b = bus == null ? null : bus.getIfAvailable();
        if (b != null) b.tableReady();
        return created;
    }

    /** Veri yolunun kendi sorgularıyla aynı şema çözümü — tablo okunabiliyor mu. */
    boolean tableExists() {
        try {
            jdbc.queryForList("SELECT 1 FROM " + CacheBus.TABLE + " WHERE 1 = 0");
            return true;
        } catch (BadSqlGrammarException missing) {
            return false;
        }
    }
}
