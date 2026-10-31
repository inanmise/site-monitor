package com.sitemonitor.service.dbquery;

import com.sitemonitor.model.HolidayDay;
import com.sitemonitor.repository.HolidayDayRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * Tatil / özel gün takvimi okuması (2026-10-31): zamanlayıcı ve zamanlama doğrulaması her vadede çağırır → 60 sn bellek
 * özeti; okuma hatası önceki özeti korur (ilk okumada hata = boş küme — "tatil yok" sayılır, izleme çalışır).
 * Yönetim (kayıt, tohum, önbellek veri yolu) API paketinde genişletilir; {@link #days()} ve {@link #invalidate()} sözleşmedir.
 */
@Service
public class HolidayCalendarService {

    private static final Logger log = LoggerFactory.getLogger(HolidayCalendarService.class);
    static final long CACHE_MS = 60_000L;

    private final HolidayDayRepository repo;
    private volatile Set<LocalDate> snapshot = Set.of();
    private volatile long loadedAtMs = -1;

    public HolidayCalendarService(HolidayDayRepository repo) {
        this.repo = repo;
    }

    public Set<LocalDate> days() {
        long now = System.currentTimeMillis();
        if (loadedAtMs < 0 || now - loadedAtMs > CACHE_MS) {
            try {
                Set<LocalDate> out = new HashSet<>();
                for (HolidayDay d : repo.findAll()) {
                    try {
                        out.add(LocalDate.parse(d.getDay()));
                    } catch (RuntimeException ignored) {
                        // bozuk satır atlanır
                    }
                }
                snapshot = Set.copyOf(out);
            } catch (RuntimeException e) {
                log.warn("Tatil takvimi okunamadı — önceki özet kullanılıyor: {}", e.getClass().getSimpleName());
            }
            loadedAtMs = now;
        }
        return snapshot;
    }

    /** Takvim değişti — bir sonraki okuma yeniden yükler. */
    public void invalidate() {
        loadedAtMs = -1;
    }
}
