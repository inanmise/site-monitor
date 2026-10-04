package com.sitemonitor.service.noc;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.NocDelivery;
import com.sitemonitor.repository.NocDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Bu alarm 7/24 ekibine gitti mi, ne zaman?" — alarm listeleri için TOPLU okuma (2026-10-04). Kaynak
 * {@code noc_deliveries}'in açılış satırı (anahtar {@code alert:<id>:OPEN}) — {@link NocDelivery} javadoc'undaki TEK
 * cevap; fırtına e-postasına giren üyeler de {@code SENT_VIA_STORM} ile aynı satırı taşır. Sayfa başına TEK sorgu
 * (500'lük parçalar); satır başına sorgu YOK. Sorgu düşerse alanlar boş kalır, liste yine döner.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NocAlertFacts {

    static final int CHUNK = 500;

    private final NocDeliveryRepository deliveries;

    /** Açılış teslimi "gitmiş" sayılır mı — {@code NocNotificationService.sent} ile AYNI kural. */
    public static boolean sent(String status) {
        return status != null && (status.startsWith("SENT") || status.startsWith("QUEUED_RETRY"));
    }

    /** Fırtına e-postasıyla mı gitti. */
    public static boolean viaStorm(String status) {
        return NocNotificationService.VIA_STORM.equals(status);
    }

    /** İletim anı: teslimin bittiği an (yoksa sahiplenme anı). */
    public static String sentAt(NocDelivery d) {
        if (d == null) return null;
        return d.getUpdatedAt() != null ? d.getUpdatedAt() : d.getCreatedAt();
    }

    static String openKey(Long alertId) {
        return "alert:" + alertId + ":" + NocDelivery.OPEN;
    }

    /** Alarm kimliği → 7/24 AÇILIŞ teslim satırı (gitmiş olsun olmasın). Sorgu düşerse boş harita. */
    public Map<Long, NocDelivery> openDeliveries(Collection<Long> alertIds) {
        Map<Long, NocDelivery> out = new HashMap<>();
        if (alertIds == null || alertIds.isEmpty()) return out;
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(alertIds));
        ids.removeIf(java.util.Objects::isNull);
        try {
            for (int i = 0; i < ids.size(); i += CHUNK) {
                List<String> keys = new ArrayList<>();
                for (Long id : ids.subList(i, Math.min(ids.size(), i + CHUNK))) keys.add(openKey(id));
                for (NocDelivery d : deliveries.findByDedupeKeyIn(keys)) {
                    if (d != null && d.getAlertEventId() != null) out.put(d.getAlertEventId(), d);
                }
            }
        } catch (Exception e) {
            log.debug("7/24 teslim özeti alınamadı: {}", e.toString());
            return new HashMap<>();
        }
        return out;
    }

    /** Listeyi {@code noc_sent_at} / {@code noc_via_storm} ile süsler (tek toplu sorgu). */
    public void decorate(List<AlertEvent> alerts) {
        if (alerts == null || alerts.isEmpty()) return;
        Set<Long> ids = new LinkedHashSet<>();
        for (AlertEvent a : alerts) if (a != null && a.getId() != null) ids.add(a.getId());
        Map<Long, NocDelivery> byAlert = openDeliveries(ids);
        for (AlertEvent a : alerts) {
            if (a == null || a.getId() == null) continue;
            NocDelivery d = byAlert.get(a.getId());
            if (d != null && sent(d.getStatus())) {
                a.setNocSentAt(sentAt(d));
                a.setNocViaStorm(viaStorm(d.getStatus()));
            } else {
                a.setNocSentAt(null);
                a.setNocViaStorm(null);
            }
        }
    }
}
