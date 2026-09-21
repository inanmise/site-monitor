package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.CertificateInventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Locale;

/**
 * HTTP tabanlı izlemeler (HTTP/Website, Keyword, Sayfa Bütünlüğü) için kurumsal vekil (proxy) KARARI — tek nokta.
 *
 * <p>Neden var (2026-09-21, kullanıcı bildirimi): sertifika envanterinde "Proxy üzerinden kontrol et = Evet" seçilen
 * bir alan adının HTTP izlemesi yine pod'dan DOĞRUDAN çıkıyordu; aynı alan adı için iki farklı yol, iki farklı
 * cevap. Sertifika kontrolü {@link ProxySettings#useFor(String, boolean)} ile karar verir; burası aynı kuralı
 * izleme başına üç kipli tercihe bağlar:
 * <ul>
 *   <li>{@code AUTO} (varsayılan, null da AUTO): <b>envanterle aynı</b> — URL'nin alan adı envanterde kayıtlıysa ve
 *       {@code use_proxy=true} ise vekil; değilse doğrudan (bugünkü davranış korunur — envanterde işaretli olmayan
 *       hiçbir izleme yol değiştirmez).</li>
 *   <li>{@code ON}: vekil (vekil yapılandırılmışsa ve hedef NO_PROXY'de değilse — sertifika kontrolüyle aynı).</li>
 *   <li>{@code OFF}: her zaman doğrudan.</li>
 * </ul>
 * Karar {@link Decision} olarak döner; kaynak ({@code monitor} | {@code inventory} | {@code none}) ve NO_PROXY
 * baypası da yazılır ki arayüz "AUTO seçtim ama neden doğrudan?" sorusunu cevaplayabilsin.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyPolicyService {

    public static final String AUTO = "AUTO", ON = "ON", OFF = "OFF";

    private final ProxySettings proxySettings;
    private final CertificateInventoryRepository inventoryRepo;

    /**
     * @param viaProxy   nihai karar
     * @param source     kararın kaynağı: {@code monitor} (ON/OFF), {@code inventory} (AUTO + envanter kaydı), {@code none}
     * @param wanted     vekil İSTENDİ mi (yapılandırma/NO_PROXY yüzünden yine de doğrudan çıkabilir)
     * @param bypassed   istendi ama NO_PROXY listesi ya da vekil tanımsızlığı yüzünden doğrudan
     */
    public record Decision(boolean viaProxy, String source, boolean wanted, boolean bypassed) {
        public String via() { return viaProxy ? "proxy" : "direct"; }
        public static Decision direct(String source) { return new Decision(false, source, false, false); }
    }

    /** {@code AUTO|ON|OFF} dışındaki her girdi (null dâhil) AUTO'ya düşer — sentetik izlemeyle aynı kural. */
    public static String normalizeMode(Object raw) {
        String v = raw == null ? "" : raw.toString().trim().toUpperCase(Locale.ROOT);
        return (ON.equals(v) || OFF.equals(v)) ? v : AUTO;
    }

    /** URL'den alan adı; şemasız/bozuk girdide null. */
    public static String hostOf(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            String h = URI.create(url.trim()).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    /** Karar — URL ve izlemenin tercihi. */
    public Decision decide(String url, String mode) {
        return decideForHost(hostOf(url), mode);
    }

    public Decision decideForHost(String host, String mode) {
        String m = normalizeMode(mode);
        if (OFF.equals(m)) return Decision.direct("monitor");
        boolean wanted;
        String source;
        if (ON.equals(m)) {
            wanted = true; source = "monitor";
        } else {
            CertificateInventory inv = inventoryFor(host);
            if (inv == null) return Decision.direct("none");
            wanted = Boolean.TRUE.equals(inv.getUseProxy());
            source = "inventory";
            if (!wanted) return new Decision(false, source, false, false);
        }
        boolean via = host != null && proxySettings.useFor(host, true);
        return new Decision(via, source, true, !via);
    }

    /** Envanter kaydı: tam eşleşme; yoksa {@code www.} öneki atılıp/eklenip bir kez daha (aynı site, iki yazım). */
    private CertificateInventory inventoryFor(String host) {
        if (host == null || inventoryRepo == null) return null;
        try {
            CertificateInventory inv = inventoryRepo.findByDomain(host).orElse(null);
            if (inv != null) return inv;
            String alt = host.startsWith("www.") ? host.substring(4) : "www." + host;
            return inventoryRepo.findByDomain(alt).orElse(null);
        } catch (Exception e) {
            log.debug("Vekil kararı: envanter okunamadı ({}): {}", host, e.getMessage());
            return null;
        }
    }
}
