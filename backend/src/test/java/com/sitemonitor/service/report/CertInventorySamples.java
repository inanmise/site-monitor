package com.sitemonitor.service.report;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.service.mail.CertInventoryMail;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aylık envanter raporunun ÖRNEK verisi — e-posta galerisi ({@code EmailSamples}), içerik testleri
 * ({@code CertInventoryMailTest}) ve PDF testleri aynı kaynağı kullanır. Veri GERÇEK özetleyiciden
 * ({@link CertInventorySummary}) geçer: sayılar elle yazılmaz, envanter satırlarından türetilir.
 *
 * <p>Yer tutucu kimlikler ({@code example.com}, "Takım A"…). Tarihler yalnız GÖSTERİM içindir (kalan gün DTO'dan
 * gelir, hiçbir eşikle "şimdi"ye karşı karşılaştırılmaz) → sabit taban tarih zaman bombası değildir.
 * Zorlayıcı örnekler bilinçli: 80 karakterlik host, Türkçe karakter, HTML özel karakterli takım/hata metni.
 */
public final class CertInventorySamples {

    private CertInventorySamples() { }

    public static final String LONG_HOST = "very-long-subdomain-name-for-stress-testing.internal-services.region-a.example.com";
    /** Kaçış denemesi: takım adında HTML özel karakterleri. */
    public static final String NASTY_TEAM = "Takım L <Ödeme & Kart>";
    /** Kaçış denemesi: hata metninde etiket. */
    public static final String NASTY_ERROR = "<b>PKIX</b> path building failed";

    private static final String[] ISSUERS = { "DigiCert Inc", "Sectigo Limited", "GlobalSign nv-sa", "Let's Encrypt", "Kurumsal İç CA" };
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 25, 9, 0);

    /** Büyük envanter: 426 kayıt (412 aktif), 38 kayıt 30 gün penceresinde, 31 hijyen bulgusu, 12 takım, 5 CA. */
    public static final class Large {
        public final List<CertificateInventory> rows = new ArrayList<>();
        public final Map<Long, String> teams = new LinkedHashMap<>();
        public final Map<String, CertificateDto> latest = new LinkedHashMap<>();
        public final InventoryHygieneService.Result hygiene;

        Large() {
            for (int t = 1; t <= 11; t++) teams.put((long) t, "Takım " + (char) ('A' + t - 1));
            teams.put(12L, NASTY_TEAM);
            int n = 0;
            // süresi dolmuş (2) + 0–7 (3) + 8–14 (5) + 15–30 (28)
            add(LONG_HOST, -2, n++, null);
            add("legacy.example.com", -9, n++, null);
            add("odeme.example.com", 0, n++, null);
            add("www.example.com", 5, n++, null);
            add("api.example.com", 6, n++, null);
            String[] mid = { "portal", "mail", "vpn", "cdn", "sso" };
            int[] midDays = { 9, 10, 12, 13, 14 };
            for (int i = 0; i < mid.length; i++) add(mid[i] + ".example.com", midDays[i], n++, null);
            for (int i = 1; i <= 28; i++) add(String.format("svc%02d.example.com", i), 15 + (i * 15) / 28, n++, null);
            // 31–90 (61) + 90 üstü (306)
            for (int i = 1; i <= 61; i++) add(String.format("app%02d.example.com", i), 31 + (i * 59) / 61, n++, null);
            for (int i = 1; i <= 306; i++) add(String.format("host%03d.example.com", i), 91 + i, n++, null);
            // tarih yok: 4 okunamayan (hata) + 3 hiç kontrol edilmemiş
            for (int i = 1; i <= 4; i++) add("err" + i + ".example.com", null, n++, i == 1 ? NASTY_ERROR : "connect timed out");
            for (int i = 1; i <= 3; i++) add("new" + i + ".example.com", null, n++, "__never__");
            // pasif (14)
            for (int i = 1; i <= 14; i++) {
                CertificateInventory r = inv("old" + i + ".example.com", 1L + (i % 12), 3);
                r.setActive(false);
                rows.add(r);
            }
            hygiene = new InventoryHygieneService.Result(List.of(
                    group("missing", "Envanter bilgisi eksik", List.of(
                            f("svc03.example.com", "takım atanmamış"), f("svc07.example.com", "kritiklik (tier) atanmamış"),
                            f("app12.example.com", "takım atanmamış, kritiklik (tier) atanmamış"))),
                    group("contacts", "Sorumlu ekip bilgisi eksik", seq("host%03d.example.com", 14, "sorumlu ekip bilgisi girilmemiş")),
                    group("stale", "Kontrol edilmemiş veya güncel olmayan", List.of(
                            f("new1.example.com", "hiç kontrol edilmemiş — izleme sonucu yok"),
                            f("new2.example.com", "hiç kontrol edilmemiş — izleme sonucu yok"),
                            f("new3.example.com", "hiç kontrol edilmemiş — izleme sonucu yok"),
                            f("app05.example.com", "son kontrol 24.09 18:00 (eşik: 65 dk)"),
                            f("app06.example.com", "son kontrol 24.09 18:00 (eşik: 65 dk)"),
                            f("host010.example.com", "son kontrol 23.09 09:30 (eşik: 65 dk)"),
                            f("host011.example.com", "son kontrol 23.09 09:30 (eşik: 65 dk)"))),
                    group("error", "Kontrol hatası — erişilemeyen sertifika", List.of(
                            f("err1.example.com", NASTY_ERROR), f("err2.example.com", "connect timed out"),
                            f("err3.example.com", "connect timed out"), f("err4.example.com", "connect timed out"))),
                    group("health", "Sertifika sağlığı sorunları", List.of(
                            f(LONG_HOST, "SÜRESİ DOLMUŞ (2 gün önce)"), f("legacy.example.com", "SÜRESİ DOLMUŞ (9 gün önce), sertifika zinciri kırık"),
                            f("cdn.example.com", "dağıtım eksik")))),
                    31);
        }

        private void add(String domain, Integer days, int n, String error) {
            long team = n % 17 == 3 ? 0 : 1 + (n % 12);
            CertificateInventory r = inv(domain, team == 0 ? null : team, n % 9 == 4 ? null : 1 + (n % 4));
            if (n % 7 == 0) r.setUgTeamId(20L + (n % 2));
            rows.add(r);
            if ("__never__".equals(error)) return;
            CertificateDto d = new CertificateDto();
            d.setDomain(domain);
            if (error != null) {
                d.setStatus("error");
                d.setError(error);
            } else {
                d.setStatus("valid");
                d.setDaysRemaining(days);
                // Biri 22:30 UTC'de bitiyor → kurum gününe (UTC+3) çevrilince ertesi gün görünmeli.
                LocalDateTime end = BASE.plusDays(days).withHour("www.example.com".equals(domain) ? 22 : 9)
                        .withMinute("www.example.com".equals(domain) ? 30 : 0);
                d.setNotAfter(ISO.format(end));
                d.setIssuer(ISSUERS[n % ISSUERS.length]);
                if ("legacy.example.com".equals(domain)) d.setChainStatus("BROKEN");
                if ("cdn.example.com".equals(domain)) d.setDeploymentStatus("INCOMPLETE");
            }
            latest.put(domain, d);
        }
    }

    public static Large largeData() { return new Large(); }

    /** Büyük envanter özeti + geçen ay (418 kayıt, 35 bulgu) + iki ek. */
    public static CertInventoryMail.Report large() {
        Large l = largeData();
        return CertInventorySummary.of("Eylül 2026", l.rows, l.teams, l.latest, l.hygiene)
                .withPrevious(418, 35)
                .withAttachments(attachments(l.rows.size()));
    }

    /** Orta envanter: 30 gün penceresinde 6 kayıt (tavanın altında → "+N daha" yok), iki bulgu grubu, geçen ay YOK. */
    public static CertInventoryMail.Report medium() {
        List<CertificateInventory> rows = new ArrayList<>();
        Map<String, CertificateDto> latest = new LinkedHashMap<>();
        Map<Long, String> teams = Map.of(1L, "Takım A", 2L, "Takım B", 3L, "Takım C");
        int[] days = { 3, 11, 18, 22, 27, 30, 45, 60, 88, 120, 150, 200, 240, 300, 365 };
        for (int i = 0; i < days.length; i++) {
            String dom = i == 0 ? "www.example.com" : String.format("m%02d.example.com", i);
            rows.add(inv(dom, 1L + (i % 3), 1 + (i % 3)));
            latest.put(dom, dto(dom, days[i], ISSUERS[i % 3]));
        }
        InventoryHygieneService.Result h = new InventoryHygieneService.Result(List.of(
                group("missing", "Envanter bilgisi eksik", List.of(f("m04.example.com", "kritiklik (tier) atanmamış"))),
                group("contacts", "Sorumlu ekip bilgisi eksik", List.of(f("m07.example.com", "sorumlu ekip bilgisi girilmemiş"),
                        f("m08.example.com", "sorumlu ekip bilgisi girilmemiş")))), 3);
        return CertInventorySummary.of("Eylül 2026", rows, teams, latest, h).withAttachments(attachments(rows.size()));
    }

    /** Sakin ay: 3 sertifika, hepsi 90 günden uzak, bulgu yok; geçen ay aynı. */
    public static CertInventoryMail.Report quiet() {
        List<CertificateInventory> rows = new ArrayList<>();
        Map<String, CertificateDto> latest = new LinkedHashMap<>();
        String[] doms = { "www.example.com", "api.example.com", "portal.example.com" };
        int[] days = { 200, 143, 311 };
        for (int i = 0; i < doms.length; i++) {
            rows.add(inv(doms[i], 1L, 1));
            latest.put(doms[i], dto(doms[i], days[i], "DigiCert Inc"));
        }
        return CertInventorySummary.of("Eylül 2026", rows, Map.of(1L, "Takım A"), latest,
                        new InventoryHygieneService.Result(List.of(), 0))
                .withPrevious(3, 0)
                .withAttachments(attachments(rows.size()));
    }

    /** Aktif kaydı olmayan envanter (yalnız pasif) — ek yok. */
    public static CertInventoryMail.Report empty() {
        CertificateInventory a = inv("old1.example.com", 1L, 2);
        a.setActive(false);
        CertificateInventory b = inv("old2.example.com", 1L, 3);
        b.setActive(false);
        return CertInventorySummary.of("Eylül 2026", List.of(a, b), Map.of(1L, "Takım A"), Map.of(),
                new InventoryHygieneService.Result(List.of(), 0));
    }

    // ── yardımcılar ──────────────────────────────────────────────────────────

    public static List<CertInventoryMail.Attachment> attachments(int rows) {
        return List.of(
                new CertInventoryMail.Attachment("sertifika-envanteri-2026-09.csv",
                        "Envanterin tamamı — " + rows + " kayıt; takım, kritiklik, platform ve 13 operasyonel bayrak dahil. Excel'de doğrudan açılır."),
                new CertInventoryMail.Attachment("sertifika-envanteri-2026-09.pdf",
                        "Özet (KPI, durum dağılımı, 90 gün içinde bitenler, hijyen bulgularının tamamı) — " + rows + " kayıtlık envanterin özeti; kayıt bazında tam liste CSV ekinde."));
    }

    static CertificateInventory inv(String domain, Long teamId, Integer tier) {
        CertificateInventory r = new CertificateInventory();
        r.setDomain(domain);
        r.setPort(443);
        r.setTeamId(teamId);
        r.setTier(tier);
        r.setActive(true);
        return r;
    }

    static CertificateDto dto(String domain, int days, String issuer) {
        CertificateDto d = new CertificateDto();
        d.setDomain(domain);
        d.setStatus("valid");
        d.setDaysRemaining(days);
        d.setNotAfter(ISO.format(BASE.plusDays(days)));
        d.setIssuer(issuer);
        return d;
    }

    static InventoryHygieneService.Finding f(String domain, String detail) {
        return new InventoryHygieneService.Finding(domain, detail);
    }

    static InventoryHygieneService.Group group(String key, String title, List<InventoryHygieneService.Finding> all) {
        return new InventoryHygieneService.Group(key, title, all.size(), all);
    }

    static List<InventoryHygieneService.Finding> seq(String pattern, int count, String detail) {
        List<InventoryHygieneService.Finding> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) out.add(f(String.format(pattern, i), detail));
        return out;
    }
}
