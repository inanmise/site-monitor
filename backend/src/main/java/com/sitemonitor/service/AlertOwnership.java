package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.CertificateInventoryRepository;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Alarmın SAHİBİ takımları — alarm üzerinde EYLEM (sahiplen / çöz / tekrar bildir, alıcı önizlemesi, toplu işlem, olay
 * yorumu, olayı yönetme) kapılarının tek kuralı (2026-10-09, kullanıcı onayı).
 *
 * <p><b>Neden.</b> Kapılar alarmın takımını "damga + alan adı → envanterin SY ve UG takımı" diye çözüyordu — alarm
 * türüne bakmadan. A takımı {@code api.example.com}'un sertifikasını envanterde tutuyor, B takımı aynı host'ta bağımsız
 * bir Ping izliyor: A, B'nin Ping alarmını çözebiliyor (B'ye yanlış "çözüldü" postası gidiyordu) ve B'nin alıcılarını
 * "Tekrar Bildir" önizlemesinde görebiliyordu. Oysa bildirim yönlendirmesi bağımsız izlemenin takımını YALNIZ olaydan
 * alır (CLAUDE.md "Standalone vs inventory routing").
 *
 * <p><b>Kural — yönlendirmeyle AYNI yüklem.</b> {@link EscalationService#isStandaloneEvent} (tür listesi ya da bağlam
 * anlık görüntüsündeki {@code team_id} / {@code standalone} işareti; çözüm, tekrar bildir ve eskalasyon adımı yolları
 * envanteri bununla atlar):
 * <ul>
 *   <li>bağımsız izleme alarmı (Keyword, Ping, HTTP, Sayfa, Sayfa Hızı, Sentetik, Domain, süre-bitişi; kullanıcı eklediği
 *       Port/DNS) → YALNIZ olayın damgalı takımı ({@code teamId}); envanter OKUNMAZ, damgasızsa sahibi yoktur;</li>
 *   <li>envanter gibi yönlenen alarm (sertifika, ACCESSIBILITY, envanter TÜREVİ Port/DNS — bağlam bilerek damgasız,
 *       {@code SchedulerService.alarmTeamOf}) → damga + envanterin SY ve UG takımı (eski kural, değişmedi).</li>
 * </ul>
 *
 * <p>OKUMA kapsamı (Alarm Geçmişi listesi, tekil alarm, bildirim günlüğü, olay listesi) bu sınıfı KULLANMAZ: liste
 * sorguları ({@code AlertEventRepository.ALERT_LIST_FIND}, {@code INCIDENTS_FILTER}) envanter kuralını JPQL'de taşır;
 * bağımsızlık işareti bağlam JSON'unda durduğundan sorguya güvenle taşınamaz ve listede görünen satır açılabilmeli.
 * Sorgu atmaz (envanter satırını çağıran verir) ya da en çok bir envanter okuması yapar.
 */
public final class AlertOwnership {

    private AlertOwnership() {}

    /** Alarm envanter gibi mi yönlenir (true) yoksa bağımsız izleme alarmı mı (false) — yönlendirmeyle aynı yüklem. */
    public static boolean routesLikeInventory(AlertEvent ev) {
        return ev != null && !EscalationService.isStandaloneEvent(ev);
    }

    /**
     * Sahip takımlar — envanter satırı ÇAĞIRANDAN (toplu yüklenmiş; yoksa null). Bağımsız alarmda envanter yok sayılır.
     *
     * @param invTeamId   alan adının envanter SY takımı (yoksa null)
     * @param invUgTeamId alan adının envanter UG takımı (yoksa null)
     */
    public static Set<Long> ownerTeamIds(AlertEvent ev, Long invTeamId, Long invUgTeamId) {
        Set<Long> ids = new LinkedHashSet<>();
        if (ev == null) return ids;
        if (ev.getTeamId() != null) ids.add(ev.getTeamId());
        if (!routesLikeInventory(ev)) return ids;
        if (invTeamId != null) ids.add(invTeamId);
        if (invUgTeamId != null) ids.add(invUgTeamId);
        return ids;
    }

    /** Sahip takımlar — envanteri yalnız envanter gibi yönlenen alarmda okur (bağımsız alarmda sorgu yok). */
    public static Set<Long> ownerTeamIds(AlertEvent ev, CertificateInventoryRepository inventoryRepo) {
        if (ev == null) return new LinkedHashSet<>();
        if (inventoryRepo == null || ev.getDomain() == null || !routesLikeInventory(ev)) {
            return ownerTeamIds(ev, null, null);
        }
        CertificateInventory inv = inventoryRepo.findByDomain(ev.getDomain()).orElse(null);
        return ownerTeamIds(ev, inv == null ? null : inv.getTeamId(), inv == null ? null : inv.getUgTeamId());
    }

    /** Sahip takımlardan biri verilen kapsamda mı ({@code scope} null/boş → hayır). */
    public static boolean ownedByAny(Set<Long> owners, Collection<Long> scope) {
        if (owners == null || owners.isEmpty() || scope == null || scope.isEmpty()) return false;
        for (Long t : owners) if (t != null && scope.contains(t)) return true;
        return false;
    }
}
