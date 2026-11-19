package com.sitemonitor.service.monitorcsv;

import jakarta.servlet.http.HttpSession;

import java.util.Map;

/**
 * İçe aktarımın GERÇEK yazma yolu (2026-11-19): bir satırın oluşturma / güncelleme gövdesini izleme uçlarına verir.
 * Üretim uygulaması {@link MonitoringControllerCsvWriter} — MonitoringController'ın create / update metotlarını aynı oturumla
 * çağırır, böylece her doğrulama, takım kapsamı, paylaşım yönlendirme koruması, değişiklik geçmişi, {@code MONITOR_CREATE |
 * UPDATE} denetimi ve alarm kuralı (duraklatma alarmı kapatır, URL değişimi tabanı temizler …) birebir uygulanır.
 */
public interface MonitorCsvWriter {

    /**
     * Yazma sonucu.
     *
     * @param ok      uç başarılı döndü
     * @param id      oluşturulan / güncellenen izlemenin kimliği (biliniyorsa)
     * @param message başarısızlıkta kullanıcıya gösterilecek güvenli metin
     * @param field   başarısızlığın alanı (biliniyorsa — gövde anahtarı)
     */
    record Result(boolean ok, Long id, String message, String field) {
        public static Result success(Long id) { return new Result(true, id, null, null); }
        public static Result failure(String message, String field) { return new Result(false, null, message, field); }
    }

    Result create(String type, Map<String, Object> body, HttpSession session);

    Result update(String type, Long id, Map<String, Object> body, HttpSession session);
}
