package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Müdür sicili → uygulama kullanıcısı (manager_id bağı). Tek kural, iki yazar: LDAP provizyonu
 * ({@code LdapProvisioningService}) ve yöneticinin elle girdiği müdür sicili ({@code UserService.applyProfileFields}).
 *
 * <p><b>Neden ayrı (prod hatası 2026-09-26, "müdür verisi karışık").</b> Eski bağ
 * {@code findByEmployeeId(sicil)} idi: (a) aynı sicili taşıyan iki satırda istisna fırlatıyordu
 * (girişte yutulup "hatalı parola" gibi görünür), (b) boşluk/harf farkında bağ sessizce kopuyordu.
 *
 * <p>Seçim — yanlış kişiye bağlamaktansa BAĞLAMAMAK:
 * <ol>
 *   <li>Kişinin kendisi aday değildir.</li>
 *   <li>Aktif hesaplar pasiflerden önce gelir.</li>
 *   <li>En üst kademede tek aday varsa o; birden çoksa LDAP kaynaklı olan; hâlâ birden çoksa
 *       <b>belirsiz</b> → boş döner ve uyarı loglanır (sicili ortak iki gerçek kişi arasında tahmin yok).</li>
 * </ol>
 * Baştaki sıfırlar normalize EDİLMEZ ("0100001" ≠ "100001") — iki ayrı sicil olabilir.
 */
@Slf4j
public final class ManagerLookup {

    private ManagerLookup() {}

    public static Optional<AppUser> resolve(AppUserRepository repo, String sicil, Long selfId) {
        if (repo == null || sicil == null || sicil.isBlank()) return Optional.empty();
        List<AppUser> all = repo.findAllByEmployeeIdNormalized(sicil.trim());
        return pick(all, selfId, sicil);
    }

    static Optional<AppUser> pick(List<AppUser> all, Long selfId, String sicilForLog) {
        if (all == null || all.isEmpty()) return Optional.empty();
        List<AppUser> cands = new ArrayList<>();
        for (AppUser a : all) {
            if (a == null || a.getId() == null) continue;
            if (selfId != null && selfId.equals(a.getId())) continue;
            cands.add(a);
        }
        if (cands.isEmpty()) return Optional.empty();
        List<AppUser> tier = cands.stream().filter(a -> Boolean.TRUE.equals(a.getActive())).toList();
        if (tier.isEmpty()) tier = cands;
        if (tier.size() == 1) return Optional.of(tier.get(0));
        List<AppUser> ldap = tier.stream().filter(a -> "LDAP".equalsIgnoreCase(a.getAuthSource())).toList();
        if (ldap.size() == 1) return Optional.of(ldap.get(0));
        log.warn("Müdür sicili belirsiz: '{}' {} kullanıcıda kayıtlı (id'ler {}) — manager_id BAĞLANMADI. "
                        + "Yinelenen sicili Yönetim → Kullanıcılar'dan düzeltin.",
                sicilForLog, tier.size(), tier.stream().map(AppUser::getId).toList());
        return Optional.empty();
    }
}
