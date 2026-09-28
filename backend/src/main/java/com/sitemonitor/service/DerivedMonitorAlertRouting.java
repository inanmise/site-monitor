package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Geçiş (2026-09-28, O2): bu sürümden ÖNCE açılmış envanter TÜREVİ Port/DNS alarmlarını yeni yönlendirmeye alır.
 *
 * <p><b>Neden.</b> ≤20.89.0'da türev satır takımını (envanterden KOPYA) alarm bağlamına {@code team_id} olarak
 * damgalıyordu; bu damga olayın bağlam anlık görüntüsünde ({@code context_json}) duruyor. Yükseltmeden sonra açık kalan
 * böyle bir olay "bağımsız izleme" sayılıyor ({@code EscalationService.isStandaloneEvent}): UG takımı ve UG'nin kişileri
 * eklenmiyor; kopya envanterden sapmışsa (içe aktarma eşitlemiyordu) yeniden uyarı / eskalasyon / çözüm / tekrar
 * bildir ESKİ takıma gidiyor. DNS_CHANGED kendiliğinden kapanmadığı için bu süresiz olabiliyordu.
 *
 * <p><b>Ne yapar.</b> Açık PORT_ ve DNS_ önekli olaylardan bağlamında {@code team_id} olup {@code standalone} işareti
 * OLMAYAN ve bağlamdaki {@code monitor_id} satırı gerçekten türev ({@code standalone != true}) olanlarda: bağlamdan
 * {@code team_id} çıkarılır, olayın takım damgası canlı envanter SY takımına çekilir (envanter yoksa damga korunur).
 * Böylece olay yeni açılmış türev alarm gibi yönlenir (SY + UG + her takımın kendi kişileri).
 *
 * <p><b>İkinci iş (D4).</b> Tersine, bağlamında ne {@code team_id} ne {@code standalone} işareti olan ESKİ bir olay
 * gerçekte BAĞIMSIZ satıra aitse ({@code team_id} anlık görüntüye 2026-09-23'te, işaret 2026-09-28'de girdi) bağlama
 * {@code standalone: true} (+ satırın takımı) yazılır. Böylece UG kararı yalnız işarete dayanabilir; eski "damga ≠
 * envanter" sezgisi (SY aktarımından sonra sertifika alarmının UG'sini çözümde düşürüyordu) kaldırıldı.
 *
 * <p><b>Güvenlik.</b> İdempotent ve kendiliğinden sınırlı: işlenen türev olayın bağlamında artık {@code team_id},
 * işlenen bağımsız olayınkinde {@code standalone} işareti vardır; bir sonraki açılışta seçilmez, nişan tablosu gerekmez.
 * Zaten doğru olan (türev + damgasız, bağımsız + damgalı), satırı bulunamayan, {@code monitor_id}'si olmayan, bağlamı
 * okunamayan olaya DOKUNULMAZ. Hata açılışı durdurmaz (WARN). Kapalı olaylar (tarih kaydı) okunmaz bile.
 * Kapı: {@code DerivedMonitorAlertRoutingTest}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DerivedMonitorAlertRouting {

    private final AlertEventRepository alertEventRepo;
    private final PortMonitorRepository portMonitorRepo;
    private final DnsMonitorRepository dnsMonitorRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final ObjectMapper objectMapper;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            int n = migrateOpenDerivedAlerts();
            if (n > 0) log.info("Port/DNS açık alarm geçişi: {} olay yeni yönlendirmeye alındı (türev → SY + UG; eski bağımsız → işaret)", n);
        } catch (Exception e) {
            log.warn("Türev Port/DNS açık alarm geçişi atlandı (sonraki açılışta yeniden denenecek): {}", e.getMessage());
        }
    }

    /** @return yeni yönlendirmeye alınan (türev) ya da işaretlenen (eski bağımsız) olay sayısı */
    @SuppressWarnings("unchecked")
    public int migrateOpenDerivedAlerts() {
        int n = 0;
        for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
            if (Boolean.TRUE.equals(e.getResolved())) continue;
            String type = e.getAlertType();
            boolean port = type != null && type.startsWith("PORT_");
            boolean dns = type != null && type.startsWith("DNS_");
            if (!port && !dns) continue;
            String json = e.getContextJson();
            if (json == null || json.isBlank()) continue;
            Map<String, Object> ctx;
            try {
                ctx = objectMapper.readValue(json, Map.class);
            } catch (Exception ex) {
                continue;   // bozuk bağlam: dokunma
            }
            if (ctx == null || Boolean.TRUE.equals(ctx.get("standalone"))) continue;   // zaten işaretli
            boolean stamped = ctx.get("team_id") instanceof Number;
            Long monitorId = ctx.get("monitor_id") instanceof Number mid ? mid.longValue() : null;
            if (monitorId == null) continue;   // hangi satır olduğu bilinmiyor: dokunma
            Optional<Long[]> row = port   // [bağımsız mı (1/0), satırın takımı]
                    ? portMonitorRepo.findById(monitorId).map(m -> new Long[]{Boolean.TRUE.equals(m.getStandalone()) ? 1L : 0L, m.getTeamId()})
                    : dnsMonitorRepo.findById(monitorId).map(m -> new Long[]{Boolean.TRUE.equals(m.getStandalone()) ? 1L : 0L, m.getTeamId()});
            if (row.isEmpty()) continue;   // satır yok: dokunma
            boolean standaloneRow = row.get()[0] == 1L;
            Map<String, Object> next = new LinkedHashMap<>(ctx);
            if (!standaloneRow && stamped) {
                // O2: türev satırın eski kopya damgası → envanter yönlendirmesi
                next.remove("team_id");
                Long invTeam = inventoryRepo.findByDomain(e.getDomain()).map(CertificateInventory::getTeamId).orElse(null);
                if (invTeam != null) e.setTeamId(invTeam);
            } else if (standaloneRow && !stamped) {
                // D4: işaretsiz eski bağımsız olay → açık işaret (+ satırın takımı)
                next.put("standalone", true);
                if (row.get()[1] != null) next.put("team_id", row.get()[1]);
                if (e.getTeamId() == null && row.get()[1] != null) e.setTeamId(row.get()[1]);
            } else {
                continue;   // türev + damgasız (zaten doğru) ya da bağımsız + damgalı (zaten doğru)
            }
            try {
                e.setContextJson(objectMapper.writeValueAsString(next));
            } catch (Exception ex) {
                continue;
            }
            alertEventRepo.save(e);
            n++;
        }
        return n;
    }
}
