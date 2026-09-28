package com.sitemonitor.service.report;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.service.mail.CertInventoryMail;
import com.sitemonitor.service.mail.CertInventoryMail.Breakdown;
import com.sitemonitor.service.mail.CertInventoryMail.Buckets;
import com.sitemonitor.service.mail.CertInventoryMail.Cert;
import com.sitemonitor.service.mail.CertInventoryMail.Finding;
import com.sitemonitor.service.mail.CertInventoryMail.FindingGroup;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aylık envanter raporunun ÖZETİ — e-posta gövdesi ({@link CertInventoryMail}) ve PDF eki ({@link InventoryPdfWriter})
 * AYNI özetten beslenir, iki yerdeki sayılar birbirini tutar.
 *
 * <p><b>Maliyet.</b> Tek geçiş, O(n): envanter satırları × bellekteki son kontrol haritası ({@code getAllLatest},
 * önbellekli) + zaten yüklenmiş takım adları. Yeni sorgu YOK (tek pod, yüzlerce–binlerce kayıt; N+1 yasak).
 *
 * <p><b>Kapsam.</b> Sayaçlar silinmemiş kayıtlar (aktif + pasif); kalan süre, hata, kırılım ve listeler YALNIZ aktif
 * kayıtlar (pasif kayıt izlenmez). Kalan gün son kontrolün {@code daysRemaining}'idir (uygulamanın gösterdiği değer);
 * bitiş tarihi UTC saklanır, kurum saatine (Europe/Istanbul) çevrilip "gg.aa.yyyy" yazılır.
 */
final class CertInventorySummary {

    private CertInventorySummary() { }

    static final String SCOPE = "Tüm kurum envanteri";
    static final String NO_TEAM = "Takım atanmamış";

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    static CertInventoryMail.Report of(String monthLabel, List<CertificateInventory> rows, Map<Long, String> teams,
                                       Map<String, CertificateDto> latest, InventoryHygieneService.Result hygiene) {
        Map<Long, String> teamNames = teams == null ? Map.of() : teams;
        Map<String, CertificateDto> lat = latest == null ? Map.of() : latest;
        int active = 0, passive = 0, errors = 0;
        int expired = 0, d7 = 0, d14 = 0, d30 = 0, d90 = 0, over = 0, unknown = 0;
        List<Cert> upcoming = new ArrayList<>();
        List<Cert> later = new ArrayList<>();
        Map<String, int[]> byTeam = new HashMap<>();
        Map<String, int[]> byIssuer = new HashMap<>();
        Set<Long> owners = new LinkedHashSet<>();

        for (CertificateInventory r : rows) {
            if (r.getTeamId() != null) owners.add(r.getTeamId());
            if (r.getUgTeamId() != null) owners.add(r.getUgTeamId());
            if (Boolean.FALSE.equals(r.getActive())) { passive++; continue; }
            active++;
            CertificateDto d = r.getDomain() == null ? null : lat.get(r.getDomain());
            boolean err = d != null && "error".equalsIgnoreCase(d.getStatus());
            if (err) errors++;
            Integer days = d == null ? null : d.getDaysRemaining();
            if (days == null) unknown++;
            else if (days < 0) expired++;
            else if (days <= 7) d7++;
            else if (days <= 14) d14++;
            else if (days <= 30) d30++;
            else if (days <= 90) d90++;
            else over++;
            boolean urgent = days != null && days <= 30;

            String team = r.getTeamId() == null ? null : teamNames.getOrDefault(r.getTeamId(), "Takım #" + r.getTeamId());
            count(byTeam, team == null ? NO_TEAM : team, urgent, err);
            String issuer = issuerOf(d);
            if (issuer != null) count(byIssuer, issuer, urgent, err);

            if (days != null && r.getDomain() != null) {
                Cert c = new Cert(r.getDomain(), team, r.getTier(), days, istDate(d.getNotAfter()), issuer, statusText(d));
                (days <= 30 ? upcoming : later).add(c);
            }
        }

        Comparator<Cert> byDays = Comparator.comparing(Cert::daysLeft).thenComparing(Cert::domain);
        upcoming.sort(byDays);
        later.sort(byDays);

        List<FindingGroup> groups = new ArrayList<>();
        int findingTotal = 0;
        if (hygiene != null) {
            for (InventoryHygieneService.Group g : hygiene.groups()) {
                List<Finding> f = new ArrayList<>(g.samples().size());
                for (InventoryHygieneService.Finding x : g.samples()) f.add(new Finding(x.domain(), x.detail()));
                groups.add(new FindingGroup(g.key(), g.title(), g.total(), f));
            }
            findingTotal = hygiene.totalFindings();
        }

        return new CertInventoryMail.Report(monthLabel, SCOPE, owners.size(), active, passive, rows.size(),
                new Buckets(expired, d7, d14, d30, d90, over, unknown), errors, upcoming, later, groups, findingTotal,
                breakdowns(byTeam, true), breakdowns(byIssuer, false), List.of(), null, null);
    }

    private static void count(Map<String, int[]> m, String key, boolean urgent, boolean err) {
        int[] c = m.computeIfAbsent(key, k -> new int[3]);
        c[0]++;
        if (urgent) c[1]++;
        if (err) c[2]++;
    }

    /**
     * Takım kırılımı ACİLİYETE göre (acil ↓, hata ↓, toplam ↓, ad ↑) — okur önce iş bekleyen takımı görsün;
     * sağlayıcı kırılımı PAYA göre (toplam ↓, ad ↑).
     */
    private static List<Breakdown> breakdowns(Map<String, int[]> m, boolean urgencyFirst) {
        List<Breakdown> out = new ArrayList<>(m.size());
        m.forEach((k, v) -> out.add(new Breakdown(k, v[0], v[1], v[2])));
        Comparator<Breakdown> cmp = urgencyFirst
                ? Comparator.comparingInt(Breakdown::urgent).reversed()
                    .thenComparing(Comparator.comparingInt(Breakdown::errors).reversed())
                    .thenComparing(Comparator.comparingInt(Breakdown::total).reversed())
                : Comparator.comparingInt(Breakdown::total).reversed();
        out.sort(cmp.thenComparing(Breakdown::label));
        return out;
    }

    /** Sertifikayı veren kuruluş: O= (issuer) varsa o, yoksa CN; ikisi de yoksa null (kırılıma girmez). */
    static String issuerOf(CertificateDto d) {
        if (d == null) return null;
        if (d.getIssuer() != null && !d.getIssuer().isBlank()) return d.getIssuer().trim();
        if (d.getIssuerCn() != null && !d.getIssuerCn().isBlank()) return d.getIssuerCn().trim();
        return null;
    }

    /**
     * UTC ISO bitiş damgası ({@code yyyy-MM-dd'T'HH:mm:ss}, isteğe bağlı Z/ofset) → kurum günü "gg.aa.yyyy".
     * Eski rapor UTC dizesini dilimliyordu: 21:00 UTC sonrası biten sertifika bir gün ERKEN görünüyordu.
     * Ayrıştırılamayan girdide ilk 10 karakter dilimlenir (rapor biçim hatası yüzünden düşmez).
     */
    static String istDate(String iso) {
        if (iso == null || iso.isBlank()) return null;
        String s = iso.trim();
        try {
            if (s.endsWith("Z") || s.matches(".*T.*[+-][0-9][0-9]:[0-9][0-9]$")) {
                return OffsetDateTime.parse(s).atZoneSameInstant(IST).format(DAY);
            }
            return LocalDateTime.parse(s).atZone(ZoneOffset.UTC).withZoneSameInstant(IST).format(DAY);
        } catch (Exception e) {
            return s.length() < 10 ? null : s.substring(8, 10) + "." + s.substring(5, 7) + "." + s.substring(0, 4);
        }
    }

    /** Kısa Türkçe durum — önceki rapor tablosunun "Durum" sütunuyla aynı öncelik. */
    static String statusText(CertificateDto d) {
        if (d == null) return "kontrol edilmemiş";
        if ("error".equalsIgnoreCase(d.getStatus())) return "hata";
        if (d.getDaysRemaining() != null && d.getDaysRemaining() < 0) return "süresi dolmuş";
        if ("REVOKED".equalsIgnoreCase(d.getRevocationStatus())) return "iptal";
        if ("BROKEN".equalsIgnoreCase(d.getChainStatus())) return "zincir kırık";
        if ("INCOMPLETE".equalsIgnoreCase(d.getDeploymentStatus())) return "dağıtım eksik";
        return "geçerli";
    }
}
