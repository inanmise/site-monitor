package com.sitemonitor.dto;

/**
 * Tüm Sertifikalar tablosunun sayfalı sorgusu (2026-09-13 zenginleştirme).
 *
 * <p>Sekiz parametreli imza büyümeyi kaldırmıyordu; süzgeçler tek nesnede taşınır. Tüm alanlar
 * isteğe bağlı: boş dize / null = süzgeç yok.
 *
 * <ul>
 *   <li>{@code filterStatus} — {@code alert_level} DİLİYLE: expired / critical / high / warning /
 *       valid / error (+ eski {@code expiring7} takma adı). Eskiden süzgeç kendi merdivenini
 *       kuruyordu; satır "Süresi doldu" derken süzgeç onu "Kritik"e sayıyordu.</li>
 *   <li>{@code filterWindow} — vade penceresi: {@code expired} | {@code 7} | {@code 30} | {@code 60} | {@code 90}
 *       (0 ≤ kalan gün ≤ N).</li>
 *   <li>{@code filterTeam} — takım kimliği ya da {@code __none__} (takımsız).</li>
 *   <li>{@code filterInsecure} — yalnız {@code security_flags} dolu olanlar.</li>
 *   <li>{@code filterTier} — envanter kritiklik kademesi (1–4).</li>
 *   <li>{@code filterPort} — {@code nonstd} (443 dışı) ya da tam port.</li>
 *   <li>{@code filterFp} — aynı parmak izini paylaşan satırlar (SAN/wildcard grubu).</li>
 * </ul>
 */
public record CertListQuery(int page, int perPage, String sortBy, String sortDir,
                            String filterDomain, String filterIssuer, String filterStatus,
                            String filterTeam, String filterWindow, boolean filterInsecure,
                            Integer filterTier, String filterPort, String filterFp) {

    public CertListQuery {
        page = Math.max(1, page);
        perPage = Math.min(Math.max(1, perPage), 5000);
        sortBy = nz(sortBy, "domain");
        sortDir = nz(sortDir, "asc");
        filterDomain = nz(filterDomain, "");
        filterIssuer = nz(filterIssuer, "");
        filterStatus = nz(filterStatus, "");
        filterTeam = nz(filterTeam, "");
        filterWindow = nz(filterWindow, "");
        filterPort = nz(filterPort, "");
        filterFp = nz(filterFp, "");
    }

    /** Eski sekiz parametreli çağrı biçimi (mevcut testler + basit kullanım). */
    public static CertListQuery of(int page, int perPage, String sortBy, String sortDir,
                                   String filterDomain, String filterIssuer, String filterStatus) {
        return new CertListQuery(page, perPage, sortBy, sortDir, filterDomain, filterIssuer, filterStatus,
                "", "", false, null, "", "");
    }

    private static String nz(String s, String def) { return s == null || s.isBlank() ? def : s.trim(); }
}
