package com.sitemonitor.service.forward;

import java.util.Collection;
import java.util.List;

/**
 * Alarm iletimi yönlendirmesi (2026-11-05) — gönderim yollarının (EscalationService, StormService, QuietDigestService,
 * AlarmFeedService) kullandığı TEK okuma arayüzü. Uygulama {@code AlertForwardService}; çağıranlar alanı
 * {@code @Autowired(required = false)} tutar (null = iletim yok, bugünkü davranış birebir).
 *
 * <p>İki yöntem de YALNIZ BELLEKTEN okur (anlık görüntü; veritabanı sorgusu yok) — alarm gönderim iş parçacığında ve 20 000
 * alarmlık akış kurulumunda çağrılabilir. Hiç iletim tanımlı değilse {@link #anyFor} false döner ve çağıran başka hiçbir şey
 * yapmaz.
 */
public interface AlertForwardRouting {

    /** Alarmın kimliği: sahip takımlar (SY / UG — gönderimdeki), alarm tipi, alarm anahtarı (domain) ve bağlamdaki izleme kimliği. */
    record AlertRef(Collection<Long> ownerTeamIds, String alertType, String domain, Long monitorId) { }

    /** Verilen sahip takımlardan herhangi birinin etkin, süresi dolmamış iletimi var mı (yalnız bellek). */
    boolean anyFor(Collection<Long> ownerTeamIds);

    /**
     * Alarmın iletileceği alıcı takımlar — alıcı takım başına bir öğe, {@code recipientTeamId} artan. Kapsam eşleşmesi:
     * ALL her alarm; TYPES alarm tipinin türü kümede (tür = {@code NocType.forAlertType}, SSL → "cert"); MONITORS (tür, kimlik)
     * kümede — kimlik bağlamdaki {@code monitorId}, cert için alan adı (envanter alan adı tekil). Sahip takımlardan biri olan
     * alıcı takım atlanır (alarmı zaten alıyor); etkin olmayan / süresi dolmuş iletim, pasif alıcı takım, adresi ve üyesi
     * kalmamış alıcı döndürülmez. Boş liste = iletim yok.
     */
    List<ForwardTarget> targetsFor(AlertRef ref);
}
