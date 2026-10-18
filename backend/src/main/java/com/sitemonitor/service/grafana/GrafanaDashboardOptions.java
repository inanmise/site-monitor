package com.sitemonitor.service.grafana;

import java.util.List;

/**
 * Grafana panosu üretiminin girdileri (2026-10-18) — {@link GrafanaDashboardSettings#current} kayıtlı ayarlardan kurar,
 * testler elle kurar. Hepsi doğrulanmış değerdir ({@link GrafanaDashboardSettings#validateAll}).
 *
 * @param title        pano başlığı (boş → dile göre varsayılan)
 * @param uid          Grafana pano kimliği (içe aktarmada aynı uid = aynı pano, üzerine yazılır)
 * @param linkBaseUrl  SiteMonitor'e dönen bağlantıların kökü (sonunda {@code /} yok; boş → bağlantı göreli olmaz, dışarıda
 *                     kalır — üretici o zaman bağlantı yazmaz)
 * @param selector     her sorguya eklenen Prometheus etiket eşleyicisi ({@code namespace="site-monitor-prod"}); boş olabilir
 * @param timeFrom     varsayılan zaman aralığının başı ({@code now-24h})
 * @param refresh      otomatik yenileme ({@code 1m})
 * @param language     pano metinlerinin dili ({@code tr} | {@code en})
 * @param sections     açık bölümler ({@link GrafanaDashboardGenerator#SECTIONS} sırasıyla)
 * @param perMonitor   izleme / sertifika / alarm başına paneller (aktarıcının {@code per-monitor} serilerini okur)
 * @param defaultTeam  takım süzgecinin varsayılan değeri (takım adı; boş = Tümü)
 * @param topN         "en yavaş / en az kalan" panellerinin varsayılan satır sayısı
 * @param appVersion   SiteMonitor sürümü (açıklamaya yazılır; içerik özetine girmez)
 */
public record GrafanaDashboardOptions(String title, String uid, String linkBaseUrl, String selector, String timeFrom,
                                      String refresh, String language, List<String> sections, boolean perMonitor,
                                      String defaultTeam, int topN, String appVersion) {

    public GrafanaDashboardOptions {
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    public boolean english() {
        return "en".equals(language);
    }

    public boolean has(String section) {
        return sections.contains(section);
    }
}
