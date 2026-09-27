package com.sitemonitor.model;

/**
 * 7/24 İzleme Ekibi (NOC) bildirimi taşıyan izleme (2026-09-27): dokuz izleme türü + sertifika envanteri.
 *
 * <p>İki alan, ikisi de NULLABLE (dolu tabloya NOT NULL kolon sessizce eklenmez — proje tuzağı):
 * <ul>
 *   <li>{@code noc_notify} — null = KAPALI (varsayılan kapalı; kullanıcı açıkça açar).</li>
 *   <li>{@code noc_group_ids} — virgüllü NOC grup kimlikleri; null/boş = varsayılan gruplar
 *       (hiç varsayılan yoksa tüm aktif gruplar). Çözümleme {@code NocGroupService.resolveTargets}.</li>
 * </ul>
 * Ortak arayüz, türden bağımsız aç/kapa, toplu işlem ve grup silmede kimlik temizliğinin TEK kod yolundan
 * geçmesi içindir (tür başına kopya, bir türün sessizce atlanması demekti).
 */
public interface NocTarget {

    Long getId();

    Boolean getNocNotify();

    void setNocNotify(Boolean nocNotify);

    /** Virgüllü grup kimlikleri (ham kolon değeri); null = varsayılan gruplar. */
    String getNocGroupIds();

    void setNocGroupIds(String nocGroupIds);
}
