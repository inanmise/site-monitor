package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Bir bildirim grubunun NEREDE KULLANILDIĞI ve referansların TOPLU TAŞINMASI.
 *
 * <p><b>Neden gerekli:</b> grup silmek, onu kullanan izlemelerin alarm yönlendirmesini sessizce
 * değiştirirdi. Silen kişi hangi izlemeleri etkilediğini görmeden karar veremez. Bu yüzden silme
 * artık kullanımda olan grupta ENGELLENİR ve kullanıcıya önce "nerede kullanılıyor" gösterilir,
 * sonra tek hamlede başka bir gruba taşıma imkânı verilir.
 *
 * <p><b>Neden ayrı servis:</b> on repository bağımlılığı gerekiyor.
 * {@link NotificationGroupService} çözümleme ve CRUD'un sıcak yolu — alarm gönderiminde her
 * seferinde kuruluyor; bu on bağımlılığı oraya taşımak o sınıfı gereksiz yere ağırlaştırırdı.
 *
 * <p><b>Kapsam kararı — alarm damgaları SAYILMAZ.</b> {@code alert_events.notification_group_id}
 * geçmiş bir olayın "o an hangi gruba gitti" damgasıdır, yapılandırma değil. Kapalı bir alarmın
 * damgası yüzünden grup silinemeseydi, grup listesi zamanla asla temizlenemezdi. Damgalı grup
 * silinirse gönderim zaten zincirin kalanına düşer (bkz. {@code NotificationGroupService}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationGroupUsageService {

    private final DnsMonitorRepository dnsRepo;
    private final DomainMonitorRepository domainRepo;
    private final HttpMonitorRepository httpRepo;
    private final KeywordMonitorRepository keywordRepo;
    private final PageMonitorRepository pageRepo;
    private final PageSpeedMonitorRepository pageSpeedRepo;
    private final PingMonitorRepository pingRepo;
    private final PortMonitorRepository portRepo;
    private final ScriptedMonitorRepository scriptedRepo;
    private final CertificateInventoryRepository inventoryRepo;

    /** Arayüzde gösterilecek tek referans. {@code type} makine anahtarıdır; etiketi frontend çevirir. */
    public record Ref(String type, Long id, String name) { }

    /**
     * @param total  toplam referans sayısı
     * @param byType tür → adet (özet satırı için)
     * @param items  örnek referanslar — {@link #MAX_ITEMS} ile sınırlı; {@code total} gerçek sayıdır
     */
    public record Usage(int total, Map<String, Integer> byType, List<Ref> items) {
        public boolean inUse() { return total > 0; }
    }

    /** Modal listesi tavanı — 500 izlemeyi ekrana basmak ne okunur ne gerekli; sayaç gerçeği söyler. */
    public static final int MAX_ITEMS = 50;

    /** Tür anahtarı → o türün referanslarını okuyan sorgu. Sıra arayüzdeki gösterim sırasıdır. */
    private Map<String, Function<Long, List<Ref>>> sources() {
        Map<String, Function<Long, List<Ref>>> m = new LinkedHashMap<>();
        m.put("http",      id -> refs("http",      httpRepo.findByNotificationGroupId(id),      x -> x.getId(), x -> x.getName()));
        m.put("keyword",   id -> refs("keyword",   keywordRepo.findByNotificationGroupId(id),   x -> x.getId(), x -> x.getName()));
        m.put("ping",      id -> refs("ping",      pingRepo.findByNotificationGroupId(id),      x -> x.getId(), x -> x.getName()));
        m.put("port",      id -> refs("port",      portRepo.findByNotificationGroupId(id),      x -> x.getId(), x -> x.getName()));
        m.put("dns",       id -> refs("dns",       dnsRepo.findByNotificationGroupId(id),       x -> x.getId(), x -> x.getName()));
        m.put("domain",    id -> refs("domain",    domainRepo.findByNotificationGroupId(id),    x -> x.getId(), x -> x.getName()));
        m.put("page",      id -> refs("page",      pageRepo.findByNotificationGroupId(id),      x -> x.getId(), x -> x.getName()));
        m.put("pagespeed", id -> refs("pagespeed", pageSpeedRepo.findByNotificationGroupId(id), x -> x.getId(), x -> x.getName()));
        m.put("scripted",  id -> refs("scripted",  scriptedRepo.findByNotificationGroupId(id),  x -> x.getId(), x -> x.getName()));
        m.put("inventory", id -> refs("inventory", inventoryRepo.findByNotificationGroupId(id),
                CertificateInventory::getId, CertificateInventory::getDomain));
        return m;
    }

    private static <T> List<Ref> refs(String type, List<T> rows,
                                      Function<T, Long> id, Function<T, String> name) {
        List<Ref> out = new ArrayList<>(rows.size());
        for (T r : rows) out.add(new Ref(type, id.apply(r), name.apply(r)));
        return out;
    }

    /** Grubun tüm referansları — silme kapısı ve "nerede kullanılıyor" modalı bunu kullanır. */
    public Usage usage(Long groupId) {
        if (groupId == null) return new Usage(0, Map.of(), List.of());
        Map<String, Integer> byType = new LinkedHashMap<>();
        List<Ref> items = new ArrayList<>();
        int total = 0;
        for (var e : sources().entrySet()) {
            List<Ref> found = e.getValue().apply(groupId);
            if (found.isEmpty()) continue;
            byType.put(e.getKey(), found.size());
            total += found.size();
            for (Ref r : found) {
                if (items.size() < MAX_ITEMS) items.add(r);
            }
        }
        return new Usage(total, byType, items);
    }

    /**
     * Grubun TÜM referanslarını başka bir gruba (ya da {@code null} = takım varsayılanı) taşır.
     *
     * <p>Tek işlem: yarım kalan bir taşıma, izlemelerin bir kısmını eski gruba bırakıp diğerlerini
     * yeni gruba alırdı ve kullanıcı bunu ancak alarm gelince fark ederdi.
     *
     * <p>Sahiplik/aktiflik doğrulaması ÇAĞIRANDA yapılır (controller): burada yalnız taşıma var.
     *
     * @return tür → taşınan adet
     */
    @Transactional
    public Map<String, Integer> reassign(Long fromGroupId, Long toGroupId) {
        Map<String, Integer> moved = new LinkedHashMap<>();
        moved.put("http",      move(httpRepo.findByNotificationGroupId(fromGroupId),      toGroupId, httpRepo::saveAll,      (x, v) -> x.setNotificationGroupId(v)));
        moved.put("keyword",   move(keywordRepo.findByNotificationGroupId(fromGroupId),   toGroupId, keywordRepo::saveAll,   (x, v) -> x.setNotificationGroupId(v)));
        moved.put("ping",      move(pingRepo.findByNotificationGroupId(fromGroupId),      toGroupId, pingRepo::saveAll,      (x, v) -> x.setNotificationGroupId(v)));
        moved.put("port",      move(portRepo.findByNotificationGroupId(fromGroupId),      toGroupId, portRepo::saveAll,      (x, v) -> x.setNotificationGroupId(v)));
        moved.put("dns",       move(dnsRepo.findByNotificationGroupId(fromGroupId),       toGroupId, dnsRepo::saveAll,       (x, v) -> x.setNotificationGroupId(v)));
        moved.put("domain",    move(domainRepo.findByNotificationGroupId(fromGroupId),    toGroupId, domainRepo::saveAll,    (x, v) -> x.setNotificationGroupId(v)));
        moved.put("page",      move(pageRepo.findByNotificationGroupId(fromGroupId),      toGroupId, pageRepo::saveAll,      (x, v) -> x.setNotificationGroupId(v)));
        moved.put("pagespeed", move(pageSpeedRepo.findByNotificationGroupId(fromGroupId), toGroupId, pageSpeedRepo::saveAll, (x, v) -> x.setNotificationGroupId(v)));
        moved.put("scripted",  move(scriptedRepo.findByNotificationGroupId(fromGroupId),  toGroupId, scriptedRepo::saveAll,  (x, v) -> x.setNotificationGroupId(v)));
        moved.put("inventory", move(inventoryRepo.findByNotificationGroupId(fromGroupId), toGroupId, inventoryRepo::saveAll, (x, v) -> x.setNotificationGroupId(v)));
        moved.values().removeIf(v -> v == 0);
        log.info("Bildirim grubu {} referansları {} grubuna taşındı: {}", fromGroupId, toGroupId, moved);
        return moved;
    }

    /** Tek türün taşınması — boş listede depoya HİÇ gitmez. */
    private static <T> int move(List<T> rows, Long target,
                                java.util.function.Consumer<List<T>> saveAll,
                                java.util.function.BiConsumer<T, Long> setter) {
        if (rows.isEmpty()) return 0;
        for (T r : rows) setter.accept(r, target);
        saveAll.accept(rows);
        return rows.size();
    }
}
