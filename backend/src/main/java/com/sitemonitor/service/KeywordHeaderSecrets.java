package com.sitemonitor.service;

import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.repository.KeywordMonitorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keyword izlemesinin özel HTTP başlıklarının ŞİFRELİ saklanması + tek seferlik göç.
 *
 * <p><b>Neden.</b> {@code keyword_monitors.custom_headers} düz metin saklanıyordu ve liste API'si
 * değeri AYNEN döndürüyordu; uç yalnız {@code monitoring.read} + takım görünürlüğü istiyor. Alana
 * pratikte {@code Authorization: Bearer …} / {@code X-Api-Key: …} yazılıyor, yani takımın (ve
 * müdürün görüş alanındaki) herkes anahtarı düz okuyabiliyordu — ayrıca değer DB yedeklerinde,
 * değişiklik geçmişi anlık görüntülerinde ve SQL Playground çıktılarında şifresiz duruyordu.
 * Kardeşi {@code PageSpeedMonitor} bu alanı 2026'dan beri {@code customHeadersEnc} ile şifreli
 * tutuyor, yazımı global-admin kapılı ve gösterimi yalnız {@code has_custom_headers} boolean'ı;
 * keyword tarafı o süpürmede atlanmış.
 *
 * <p><b>Göç.</b> Açılışta bir kez: düz kolonu dolu olan her satır şifrelenip
 * {@code custom_headers_enc}'e yazılır ve düz kolon NULL'lanır. İdempotent — ikinci koşuda
 * eşleşen satır kalmaz. Şifreleme düşerse satır ATLANIR (düz değer korunur) ve log uyarı yazar:
 * yarım göç, çalışan bir izlemenin başlıklarını kaybetmekten iyidir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordHeaderSecrets {

    private final KeywordMonitorRepository keywordRepo;
    private final SecretCipher secretCipher;

    /**
     * İzlemenin ETKİN başlık metni (düz).
     *
     * <p>Göç tamamlanana kadar iki kaynak birden okunur: önce şifreli kolon, yoksa eski düz kolon.
     * Böylece göçten ÖNCE koşan bir kontrol de doğru başlıkları gönderir (sessiz davranış
     * değişikliği olmaz).
     */
    public String effectiveHeaders(KeywordMonitor m) {
        if (m == null) return null;
        String enc = m.getCustomHeadersEnc();
        if (enc != null && !enc.isBlank()) {
            try {
                return secretCipher.decrypt(enc);
            } catch (Exception e) {
                log.warn("Keyword özel başlıkları çözülemedi (izleme {}): {}", m.getId(), e.toString());
                return null;
            }
        }
        @SuppressWarnings("deprecation")
        String legacy = m.getCustomHeaders();
        return legacy;
    }

    /** Kullanıcıya gösterilecek başlık ADLARI (değerler ASLA dönmez). */
    public List<String> headerNames(KeywordMonitor m) {
        String raw = effectiveHeaders(m);
        if (raw == null || raw.isBlank()) return List.of();
        Map<String, String> parsed = parseHeaders(raw);
        return List.copyOf(parsed.keySet());
    }

    /** İzlemenin başlıkları var mı (değeri açığa çıkarmadan). */
    public boolean hasHeaders(KeywordMonitor m) {
        if (m == null) return false;
        if (m.getCustomHeadersEnc() != null && !m.getCustomHeadersEnc().isBlank()) return true;
        @SuppressWarnings("deprecation")
        String legacy = m.getCustomHeaders();
        return legacy != null && !legacy.isBlank();
    }

    /** Düz metni şifreleyip alana yazar; boş/null ise alanı temizler. */
    @SuppressWarnings("deprecation")
    public void store(KeywordMonitor m, String plaintext) {
        if (m == null) return;
        String raw = plaintext == null ? null : plaintext.trim();
        m.setCustomHeadersEnc(raw == null || raw.isEmpty() ? null : secretCipher.encrypt(raw));
        m.setCustomHeaders(null);   // eski düz kopya bırakılmaz
    }

    /** "Name: Value" satırları → sıralı harita. Bozuk satır sessizce atlanır. */
    static Map<String, String> parseHeaders(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) return out;
        for (String line : raw.split("\r?\n")) {
            int i = line.indexOf(':');
            if (i <= 0) continue;
            String name = line.substring(0, i).trim();
            if (!name.isEmpty()) out.put(name, line.substring(i + 1).trim());
        }
        return out;
    }

    /** Tek seferlik göç — açılışta, şema yamalarından SONRA. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(200)
    @Transactional
    public void migratePlaintextHeaders() {
        try {
            List<KeywordMonitor> all = keywordRepo.findAll();
            List<KeywordMonitor> changed = new ArrayList<>();
            for (KeywordMonitor m : all) {
                @SuppressWarnings("deprecation")
                String legacy = m.getCustomHeaders();
                if (legacy == null || legacy.isBlank()) continue;
                try {
                    m.setCustomHeadersEnc(secretCipher.encrypt(legacy));
                    m.setCustomHeaders(null);
                    changed.add(m);
                } catch (Exception e) {
                    // Satırı ATLA: düz değer kalsın, izleme çalışmaya devam etsin.
                    log.warn("Keyword başlık göçü atlandı (izleme {}): {}", m.getId(), e.toString());
                }
            }
            if (!changed.isEmpty()) {
                keywordRepo.saveAll(changed);
                log.info("🔐 Keyword özel başlıkları şifrelendi — {} izleme göç etti", changed.size());
            }
        } catch (Exception e) {
            // Göç, açılışı ASLA düşürmez: bir sonraki açılış yeniden dener.
            log.warn("Keyword başlık göçü yapılamadı (sonraki açılışta yeniden denenecek): {}", e.toString());
        }
    }
}
