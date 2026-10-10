package com.sitemonitor.service.report.executive;

import com.sitemonitor.dto.CertificateDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.text.Collator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.sitemonitor.service.report.executive.SectionResult.*;

/**
 * (c) YAKLAŞAN SERTİFİKA BİTİŞLERİ — RAPOR ANI fotoğrafı (geçmiş bir ayda bile üretim anına göredir; ekranda/PDF'te
 * "… itibarıyla" yazılır). Kaynak: aktif envanterin son kontrol sonuçları ({@code CertificateService.getAllLatest},
 * bellekli) — yeni sorgu yok; plan tarihi paylaşılan envanter okumasından.
 *
 * <h2>Kovalar (ayrık, kalan TAM gün = bitiş − şimdi, aşağı yuvarlanır)</h2>
 * süresi dolmuş (&lt; 0) · 0–30 · 31–60 · 61–90 · 90 üstü · tarih yok. "30/60/90 gün içinde" kümülatiftir (süresi
 * dolmuşlar HARİÇ, ayrı gösterilir). Duraklatılmış (pasif) kayıtlar izlenmediği için dahil değildir; dosyadan yüklenen
 * (manuel) sertifikalar dahildir.
 *
 * <p><b>Takım kapsamı (2026-10-10):</b> bağlamın {@link ExecutiveSummaryContext#latestCerts()} /
 * {@link ExecutiveSummaryContext#inventory()} okumaları zaten takımın (SY ya da UG) kayıtlarıdır; "takımlara göre"
 * tablosu (tek satır olurdu) atlanır, kapsam notu düşülür.
 */
@Slf4j
@Component
public class CertificateExpirySection implements ExecutiveSummarySection {

    public static final String KEY = "expirations";
    static final int ORDER = 30;
    static final String TITLE = "Yaklaşan sertifika bitişleri";
    static final int LIST_LIMIT = 15;
    static final int TEAM_LIMIT = 10;

    @Override public String key() { return KEY; }
    @Override public int order() { return ORDER; }
    @Override public String title() { return TITLE; }

    /** Sertifika görünümü (hesap için gereken alanlar). */
    record Cert(String domain, Long teamId, String teamName, Integer tier, Integer days, String expiryDay,
                String plannedAt) { }

    @Override
    public SectionResult compute(ExecutiveSummaryContext ctx) {
        List<Cert> certs = certs(ctx);
        return evaluate(ctx, certs);
    }

    /** Son kontrol sonuçları + envanter planı → hesap görünümü. Kalan gün ŞİMDİYE göre yeniden hesaplanır. */
    static List<Cert> certs(ExecutiveSummaryContext ctx) {
        List<Cert> out = new ArrayList<>();
        Map<String, ExecutiveSummaryContext.InventoryRow> inv;
        try { inv = ctx.inventory(); } catch (Exception e) { inv = Map.of(); }
        if (inv == null) inv = Map.of();
        Map<Long, String> names;
        try { names = ctx.teamNames(); } catch (Exception e) { names = Map.of(); }
        if (names == null) names = Map.of();
        List<CertificateDto> latest;
        try { latest = ctx.latestCerts(); } catch (Exception e) { latest = List.of(); }
        if (latest == null) latest = List.of();
        for (CertificateDto d : latest) {
            if (d == null || d.getDomain() == null) continue;
            if (Boolean.TRUE.equals(d.getPaused())) continue;
            ExecutiveSummaryContext.InventoryRow row = inv.get(d.getDomain());
            Long teamId = d.getTeamId() != null ? d.getTeamId() : row == null ? null : row.teamId();
            Integer tier = d.getTier() != null ? d.getTier() : row == null ? null : row.tier();
            Instant na = parseUtc(d.getNotAfter());
            // İki dal da REFERANS (CLAUDE.md: int/Integer koşullusu null dalda NPE verir)
            Integer days = na != null ? Integer.valueOf(daysBetween(ctx.now(), na)) : d.getDaysRemaining();
            String expiryDay = na == null ? null : na.atZone(ExecutiveSummaryContext.IST).toLocalDate().toString();
            String teamName = d.getTeamName() != null ? d.getTeamName() : teamId == null ? null : names.get(teamId);
            out.add(new Cert(d.getDomain(), teamId, teamName, tier, days, expiryDay,
                    row == null ? null : row.renewalPlannedAt()));
        }
        return out;
    }

    /** Kalan TAM gün (aşağı yuvarlanır; süresi dolmuşta negatif). */
    static int daysBetween(Instant now, Instant notAfter) {
        long secs = notAfter.getEpochSecond() - now.getEpochSecond();
        return (int) Math.floorDiv(secs, 86_400L);
    }

    /** Kova: -1 süresi dolmuş, 0 → 0–30, 1 → 31–60, 2 → 61–90, 3 → 90 üstü, 4 → tarih yok. */
    static int bucket(Integer days) {
        if (days == null) return 4;
        if (days < 0) return -1;
        if (days <= 30) return 0;
        if (days <= 60) return 1;
        if (days <= 90) return 2;
        return 3;
    }

    private static final class Counts {
        int expired, d30, d60, d90, over, unknown, planned;
        void add(Integer days, boolean hasPlan) {
            switch (bucket(days)) {
                case -1 -> expired++;
                case 0 -> d30++;
                case 1 -> d60++;
                case 2 -> d90++;
                case 3 -> over++;
                default -> unknown++;
            }
            int b = bucket(days);
            if (hasPlan && b >= -1 && b <= 2) planned++;
        }
        int within30() { return d30; }
        int within60() { return d30 + d60; }
        int within90() { return d30 + d60 + d90; }
    }

    SectionResult evaluate(ExecutiveSummaryContext ctx, List<Cert> certs) {
        SectionResult.Builder b = SectionResult.builder(KEY, ORDER, TITLE).snapshot(ctx.nowIso()).headlineKpi("within30");
        Counts all = new Counts();
        Map<Integer, Counts> byTier = new java.util.TreeMap<>();
        Map<Long, Counts> byTeam = new LinkedHashMap<>();
        Map<Long, String> teamNames = new java.util.HashMap<>();
        int urgent = 0, urgentPlanned = 0;
        for (Cert c : certs) {
            boolean plan = c.plannedAt() != null && !c.plannedAt().isBlank();
            all.add(c.days(), plan);
            byTier.computeIfAbsent(c.tier() == null ? 0 : c.tier(), k -> new Counts()).add(c.days(), plan);
            byTeam.computeIfAbsent(c.teamId(), k -> new Counts()).add(c.days(), plan);
            if (c.teamId() != null && c.teamName() != null) teamNames.putIfAbsent(c.teamId(), c.teamName());
            int bk = bucket(c.days());
            if (bk == -1 || bk == 0) { urgent++; if (plan) urgentPlanned++; }
        }
        Double planCoverage = urgent == 0 ? null : Math.round(1000.0 * urgentPlanned / urgent) / 10.0;

        if (certs.isEmpty()) {
            b.status(NO_DATA).verdict("NO_CERTS", T_NEUTRAL, "İzlenen aktif sertifika yok.");
        } else {
            b.status(all.expired > 0 ? CRITICAL : all.within30() > 0 ? ATTENTION : OK);
            if (all.expired > 0) {
                b.verdict("EXPIRED", T_BAD, all.expired + " sertifikanın süresi dolmuş ve hâlâ izlemede.", all.expired);
            }
            if (all.within30() > 0) {
                b.verdict("WITHIN30", T_WARN, all.within30() + " sertifika 30 gün içinde bitiyor; acil "
                        + urgent + " kaydın " + urgentPlanned + " tanesinin yenileme planı var.",
                        all.within30(), urgent, urgentPlanned);
            } else if (all.expired == 0) {
                b.verdict("CLEAR30", T_OK, "Önümüzdeki 30 gün içinde süresi dolacak sertifika yok; 90 gün içinde "
                        + all.within90() + ".", all.within90());
            }
        }

        b.kpi(new Kpi("expired", "Süresi dolmuş", all.expired, "int", all.expired > 0 ? T_BAD : T_OK,
                "hâlâ izlemede", List.of(), null, null, null));
        b.kpi(new Kpi("within30", "30 gün içinde", all.within30(), "int", all.within30() > 0 ? T_WARN : T_OK,
                "bitecek sertifika", List.of(), null, null, null));
        b.kpi(new Kpi("within60", "60 gün içinde", all.within60(), "int", T_NEUTRAL, "bitecek sertifika", List.of(),
                null, null, null));
        b.kpi(new Kpi("within90", "90 gün içinde", all.within90(), "int", T_NEUTRAL, "bitecek sertifika", List.of(),
                null, null, null));
        b.kpi(new Kpi("plan_coverage", "Planlı (acil)", planCoverage, "pct",
                planCoverage == null ? T_NEUTRAL : planCoverage >= 80 ? T_OK : T_WARN,
                urgent + " acil kaydın " + urgentPlanned + " tanesi planlı", List.of(urgent, urgentPlanned), null, null, null));
        b.kpi(new Kpi("unknown", "Tarih yok", all.unknown, "int", all.unknown > 0 ? T_WARN : T_NEUTRAL,
                "bitiş tarihi okunamadı", List.of(), null, null, null));

        // ── Kritiklik seviyesine (tier) göre ──
        List<Map<String, Object>> tierRows = new ArrayList<>();
        for (Map.Entry<Integer, Counts> e : byTier.entrySet()) {
            Counts c = e.getValue();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("tier", e.getKey() == 0 ? null : e.getKey());
            r.put("expired", c.expired);
            r.put("d30", c.d30);
            r.put("d60", c.d60);
            r.put("d90", c.d90);
            tierRows.add(r);
        }
        b.table(new Table("by_tier", "Kritiklik seviyesine göre", List.of(
                new Column("tier", "Seviye", "tier"),
                new Column("expired", "Süresi dolmuş", "int"),
                new Column("d30", "0–30 gün", "int"),
                new Column("d60", "31–60 gün", "int"),
                new Column("d90", "61–90 gün", "int")),
                tierRows, tierRows.size(), "Kayıt yok."));

        // ── Takıma göre (en çok acil/yaklaşan önce) — takım kapsamında tek satır olacağından yok ──
        if (!ctx.teamScoped()) b.table(byTeamTable(byTeam, teamNames));

        // ── En yakın bitişler ──
        List<Cert> soon = new ArrayList<>();
        for (Cert c : certs) if (c.days() != null && c.days() <= 90) soon.add(c);
        soon.sort(Comparator.comparingInt((Cert c) -> c.days()).thenComparing(Cert::domain));
        List<Map<String, Object>> soonRows = new ArrayList<>();
        for (Cert c : soon.subList(0, Math.min(LIST_LIMIT, soon.size()))) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("domain", c.domain());
            r.put("team", c.teamName());
            r.put("team_id", c.teamId());
            r.put("tier", c.tier());
            r.put("days", c.days());
            r.put("expiry", c.expiryDay());
            r.put("planned_at", c.plannedAt());
            r.put("state", c.days() < 0 ? T_BAD : c.days() <= 30 ? T_WARN : T_NEUTRAL);
            soonRows.add(r);
        }
        b.table(new Table("soonest", "En yakın bitişler", List.of(
                new Column("domain", "Sertifika", "text"),
                new Column("team", "Takım", "team"),
                new Column("tier", "Seviye", "tier"),
                new Column("days", "Kalan", "days"),
                new Column("expiry", "Bitiş", "date"),
                new Column("planned_at", "Plan", "date"),
                new Column("state", "Durum", "status")),
                soonRows, soon.size(), "90 gün içinde biten sertifika yok."));
        b.note("ASOF", "Rapor anı fotoğrafı: " + ExecFormat.stamp(ctx.now()) + " itibarıyla aktif envanterin son kontrol "
                + "sonuçları. Duraklatılmış kayıtlar dahil değildir.", datetime(ctx.nowIso()));
        if (ctx.teamScoped()) {
            b.note("TEAM_SCOPE", "Takımın sorumlu (SY) ya da uygulama geliştirici (UG) olduğu aktif envanter kayıtları; "
                    + "Takım sütunu kaydın sorumlu takımıdır.");
        }
        b.data("expired", all.expired).data("within30", all.within30()).data("within60", all.within60())
                .data("within90", all.within90()).data("over90", all.over).data("unknown", all.unknown)
                .data("total", certs.size());
        return b.build();
    }

    /** Takımlara göre (en çok acil/yaklaşan önce) — yalnız kurum kapsamı. */
    private static Table byTeamTable(Map<Long, Counts> byTeam, Map<Long, String> teamNames) {
        Collator collator = Collator.getInstance(Locale.forLanguageTag("tr"));
        collator.setStrength(Collator.SECONDARY);
        List<Map.Entry<Long, Counts>> teams = new ArrayList<>(byTeam.entrySet());
        teams.removeIf(e -> e.getValue().expired + e.getValue().within90() == 0);
        teams.sort(Comparator.comparingInt((Map.Entry<Long, Counts> e) -> -(e.getValue().expired + e.getValue().d30))
                .thenComparingInt(e -> -e.getValue().within90())
                .thenComparing(e -> teamNames.getOrDefault(e.getKey(), "￿"), collator));
        List<Map<String, Object>> teamRows = new ArrayList<>();
        for (Map.Entry<Long, Counts> e : teams.subList(0, Math.min(TEAM_LIMIT, teams.size()))) {
            Counts c = e.getValue();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("team", e.getKey() == null ? null : teamNames.get(e.getKey()));
            r.put("team_id", e.getKey());
            r.put("expired", c.expired);
            r.put("upto30", c.within30());
            r.put("upto60", c.within60());
            r.put("upto90", c.within90());
            r.put("planned", c.planned);
            teamRows.add(r);
        }
        return new Table("by_team", "Takımlara göre", List.of(
                new Column("team", "Takım", "team"),
                new Column("expired", "Süresi dolmuş", "int"),
                new Column("upto30", "≤ 30 gün", "int"),
                new Column("upto60", "≤ 60 gün", "int"),
                new Column("upto90", "≤ 90 gün", "int"),
                new Column("planned", "Planlı", "int")),
                teamRows, teams.size(), "90 gün içinde biten sertifika yok.");
    }

    static String tierLabel(int tier) {
        return switch (tier) {
            case 1 -> "Seviye 1 · Müşteriye açık üretim";
            case 2 -> "Seviye 2 · İç üretim";
            case 3 -> "Seviye 3 · UAT / ön üretim";
            case 4 -> "Seviye 4 · Geliştirme";
            default -> "Seviye atanmamış";
        };
    }

    static Instant parseUtc(String ts) {
        if (ts == null || ts.isBlank()) return null;
        String s = ts.trim();
        try {
            if (s.matches("^\\d{4}-\\d{2}-\\d{2}$")) return LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC);
            if (s.endsWith("Z") || s.endsWith("z")) return Instant.parse(s.toUpperCase(Locale.ROOT));
            if (s.matches(".*[+-]\\d\\d:?\\d\\d$")) return OffsetDateTime.parse(s).toInstant();
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }
}
