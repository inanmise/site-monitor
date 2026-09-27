package com.sitemonitor.service;

import com.sitemonitor.controller.SessionScope;
import com.sitemonitor.model.CertificateInventory;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Envanter ORG GENELİ okuma kapısı (2026-09-26, kullanıcı kararı): "Sitedeki her kullanıcı Domain
 * Envanteri'ni ve sertifika envanterini görebilmeli; hangi alan adının hangi takıma kayıtlı olduğunu
 * herkes bilmeli — ama başka takıma kayıtlı alan adına kimse müdahale edemez."
 *
 * <p><b>Yalnız OKUMA genişler.</b> Oturumdaki {@code viewTeamIds} DEĞİŞMEZ; genişleme yalnız bu
 * sınıfı açıkça soran uçlarda olur (Envanter listesi / by-domain, Tüm Sertifikalar listesi + CSV,
 * sertifika detay penceresinin okuma uçları, Durum İzleme özeti + geçmişi). Yazma kapıları
 * ({@code requireInventoryWriter}, {@code requireTeamScopedAdmin}, {@code canManage}, anlık kontrol
 * için özgün {@code canView}) bu sınıfı HİÇ çağırmaz — dolayısıyla ayar açıkken de başka takımın
 * kaydına yazma yolu açılmaz. Pano, izleme sayfaları, alarmlar, olaylar ve bildirimler takım
 * kapsamlı kalır.
 *
 * <p>Ayar {@code site.monitor.inventory.visible-to-all} canlı okunur (Genel Ayarlar, yalnız global
 * yönetici değiştirir — {@code AppSettingsCatalog.GLOBAL_ONLY}). Kapalıyken {@code scope=all}
 * isteği sessizce {@code mine}'a düşer: arayüz eski sürümle konuşsa da görünürlük bugünküyle aynıdır.
 *
 * <p>Maliyet: istek başına bir ayar okuması (bellek içi harita) + bir izin sorgusu (önbellek) —
 * satır başına hiçbir şey sorulmaz.
 */
@Service
@RequiredArgsConstructor
public class InventoryVisibility {

    /** Ayar anahtarı — {@code AppSettingsCatalog} + i18n etiket/yardım metinleriyle aynı. */
    public static final String SETTING_KEY = "site.monitor.inventory.visible-to-all";
    /** Liste uçlarının {@code scope} değerleri. Bilinmeyen/boş değer {@link #SCOPE_MINE} sayılır. */
    public static final String SCOPE_ALL = "all";
    public static final String SCOPE_MINE = "mine";

    private final AppSettingsService appSettings;
    private final PermissionService permissionService;

    /** Ayar açık mı — her çağrıda canlı okunur (kaydedince yeniden başlatma gerekmez). Varsayılan AÇIK. */
    public boolean enabled() {
        return appSettings.getBoolean("site.monitor.inventory.visible-to-all", true);
    }

    /**
     * Bu oturum org geneli okuyabilir mi: ayar açık VE rolün {@code inventory.list/view} izni var.
     * İzin şartı, matristen envanter okuması kapatılmış bir rolün bu yoldan envanter görmesini engeller.
     */
    public boolean orgWideReader(HttpSession session) {
        if (session == null || !enabled()) return false;
        return permissionService.allows(session, "inventory.list", "view");
    }

    /** {@code scope} parametresi org geneli okumaya dönüşüyor mu? {@code all} değilse ya da kapı kapalıysa false. */
    public boolean wantsAll(HttpSession session, String scope) {
        return scope != null && SCOPE_ALL.equalsIgnoreCase(scope.trim()) && orgWideReader(session);
    }

    /** Yanıtta dönen etkin kapsam — arayüz anahtarı, istenenin değil GERÇEKTE uygulananın durumunu çizer. */
    public String effectiveScope(HttpSession session, String scope) {
        return wantsAll(session, scope) ? SCOPE_ALL : SCOPE_MINE;
    }

    /**
     * Kayıt KENDİ görüş kapsamı (SY/UG takımı) DIŞINDA olsa da okunabilir mi? Silinmiş kayıt org geneli
     * okunmaz — çöp kutusu kendi takımının ve global görüntüleyicinin işidir.
     */
    public boolean readableOrgWide(HttpSession session, CertificateInventory inv) {
        return inv != null && inv.getDeletedAt() == null && orgWideReader(session);
    }

    /**
     * Tek kayıt okuma kararı: kendi görüş kapsamı (SY ya da UG takımı) VEYA org geneli okuma.
     * Yazma kapılarında KULLANILMAZ.
     */
    public boolean canRead(HttpSession session, CertificateInventory inv) {
        if (inv == null) return false;
        if (SessionScope.canView(session, inv.getTeamId())) return true;
        if (inv.getUgTeamId() != null && SessionScope.canView(session, inv.getUgTeamId())) return true;
        return readableOrgWide(session, inv);
    }
}
