package com.sitemonitor.service.report.executive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI (2026-10-10): yönetici özetinin arayüz metinleri DİNAMİK anahtarlardır ({@code t('exec.' + key + '.kpi.' + code)}),
 * frontend'in used-keys kapısı onları göremez. Bu test bölüm sağlayıcılarının KAYNAĞINI tarar — {@code KEY}, hüküm
 * ({@code verdict("KOD"}), gösterge ({@code new Kpi("kod"}), tablo ({@code new Table("kod"}), sütun
 * ({@code new Column("kod"}), not ({@code note("KOD"}) — ve TR + EN sözlükte karşılığını arar. Yeni bir bölüm (ör. TLS
 * notu) eklendiğinde kendiliğinden kapsanır: paketteki her {@code ExecutiveSummarySection} uygulaması taranır.
 */
class ExecutiveSummaryI18nGateTest {

    private static final Path I18N_DIR = Path.of("../frontend/src/i18n");
    private static final Path PKG = Path.of("src/main/java/com/sitemonitor/service/report/executive");

    private static final Pattern KEY = Pattern.compile("String KEY = \"([a-z0-9-]+)\"");
    private static final Pattern VERDICT = Pattern.compile("verdict\\(\\s*\"([A-Z0-9_]+)\"");
    private static final Pattern VERDICT_TERNARY = Pattern.compile("\"(TOTAL_UP|TOTAL_DOWN|TOTAL_SAME)\"");
    private static final Pattern KPI = Pattern.compile("new Kpi\\(\\s*\"([a-z0-9_]+)\"");
    private static final Pattern TABLE = Pattern.compile("new Table\\(\\s*\"([a-z0-9_]+)\"");
    private static final Pattern COLUMN = Pattern.compile("new Column\\(\\s*\"([a-z0-9_]+)\"");
    private static final Pattern NOTE = Pattern.compile("\\.note\\(\\s*\"([A-Z0-9_]+)\"");

    private static String dictionary(String file, String marker) throws IOException {
        String src = Files.readString(I18N_DIR.resolve(file), StandardCharsets.UTF_8);
        int start = src.indexOf(marker);
        assertThat(start).as(file + " sözlük başlangıcı").isGreaterThanOrEqualTo(0);
        return src.substring(start);
    }

    private static List<String> all(Pattern p, String src) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(src);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** Bölüm kaynaklarından beklenen anahtarlar. */
    static Set<String> expectedKeys() throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        int sections = 0;
        try (Stream<Path> files = Files.list(PKG)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                if (!src.contains("implements ExecutiveSummarySection")) continue;
                Matcher km = KEY.matcher(src);
                assertThat(km.find()).as(f + " KEY sabiti").isTrue();
                String key = km.group(1);
                sections++;
                keys.add("exec." + key + ".title");
                for (String v : all(VERDICT, src)) keys.add("exec." + key + ".verdict." + v);
                for (String v : all(VERDICT_TERNARY, src)) keys.add("exec." + key + ".verdict." + v);
                for (String k : all(KPI, src)) {
                    keys.add("exec." + key + ".kpi." + k);
                    keys.add("exec." + key + ".kpi." + k + ".hint");
                }
                for (String t : all(TABLE, src)) keys.add("exec." + key + ".table." + t);
                for (String c : all(COLUMN, src)) keys.add("exec.col." + c);
                for (String n : all(NOTE, src)) keys.add("exec." + key + ".note." + n);
            }
        }
        assertThat(sections).as("bölüm sağlayıcısı taranamadı (yol / desen değişmiş)").isGreaterThanOrEqualTo(4);
        for (String e : ExecFormat.ENUM_TR.keySet()) keys.add("exec.enum." + e);
        keys.add("exec.note.ERROR");        // SectionResult.failed — her bölümde ortak
        return keys;
    }

    @Test
    @DisplayName("SÖZLEŞME: her bölümün başlık/hüküm/gösterge/tablo/sütun/not anahtarı TR ve EN sözlükte var")
    void everyCodeHasTrAndEn() throws IOException {
        String tr = dictionary("tr.js", "export const TR = {");
        String en = dictionary("en.js", "export const EN = {");
        Set<String> expected = expectedKeys();
        assertThat(expected).as("vakum koruması").hasSizeGreaterThan(60);
        List<String> missing = new ArrayList<>();
        for (String k : expected) {
            if (!tr.contains("'" + k + "'")) missing.add("TR " + k);
            if (!en.contains("'" + k + "'")) missing.add("EN " + k);
        }
        assertThat(missing).as("eksik i18n anahtarları").isEmpty();
    }

    @Test
    @DisplayName("örnek özetin ürettiği her kod tarama kümesinde (dinamik kod kaçmasın) — kurum VE takım kapsamı örnekleri")
    void sampleCodesAreScanned() throws IOException {
        Set<String> expected = expectedKeys();
        List<String> unknown = new ArrayList<>();
        for (ExecutiveSummary s : List.of(ExecutiveSummarySamples.full(), ExecutiveSummarySamples.quiet(),
                ExecutiveSummarySamples.teamFull(), ExecutiveSummarySamples.teamQuiet())) {
            for (SectionResult sec : s.sections()) {
                for (SectionResult.Verdict v : sec.verdicts()) {
                    String k = "exec." + sec.key() + ".verdict." + v.code();
                    if (!expected.contains(k)) unknown.add(k);
                }
                for (SectionResult.Kpi kpi : sec.kpis()) {
                    String k = "exec." + sec.key() + ".kpi." + kpi.code();
                    if (!expected.contains(k)) unknown.add(k);
                }
                for (SectionResult.Note n : sec.notes()) {
                    String k = "exec." + sec.key() + ".note." + n.code();
                    if (!expected.contains(k)) unknown.add(k);
                }
                for (SectionResult.Table t : sec.tables()) {
                    String k = "exec." + sec.key() + ".table." + t.code();
                    if (!expected.contains(k)) unknown.add(k);
                    for (SectionResult.Column c : t.columns()) {
                        if (!expected.contains("exec.col." + c.code())) unknown.add("exec.col." + c.code());
                    }
                }
            }
        }
        assertThat(unknown).isEmpty();
    }

    @Test
    @DisplayName("takım kapsamı örnekleri gerçekten TAKIM kodlarını üretir (kurum kodları yerine) — kapsam alanı takım")
    void teamSamplesUseTeamCodes() {
        for (ExecutiveSummary s : List.of(ExecutiveSummarySamples.teamFull(), ExecutiveSummarySamples.teamQuiet())) {
            assertThat(s.scope().isTeam()).isTrue();
            assertThat(s.headlineKpis()).extracting(h -> h.section() + ":" + h.kpi().code())
                    .contains("availability:team_availability", "data-quality:team_score")
                    .doesNotContain("availability:org_availability", "data-quality:org_score");
            for (SectionResult sec : s.sections()) {
                assertThat(sec.verdicts()).extracting(SectionResult.Verdict::code)
                        .doesNotContain("ORG_MET", "ORG_MISSED", "TEAMS_BELOW", "TEAMS_ALL_MET", "SCORE", "POOR_TEAMS", "TOP_RULE");
                assertThat(sec.tables()).extracting(SectionResult.Table::code)
                        .doesNotContain("teams", "top_teams", "by_team", "lowest_teams");
                List<String> notes = sec.notes().stream().map(SectionResult.Note::code).toList();
                // "kurum geneli / kurum hedefi / kurum puanı" yazan notların takım karşılıkları kullanılır
                switch (sec.key()) {
                    case "availability" -> assertThat(notes).contains("TEAM_METHOD").doesNotContain("METHOD", "UNMAPPED");
                    case "crypto-readiness" -> assertThat(notes).contains("TEAM_ASOF").doesNotContain("ASOF");
                    case "data-quality" -> assertThat(notes).contains("TEAM_ASOF", "TEAM_METHOD").doesNotContain("ASOF", "METHOD");
                    default -> assertThat(notes).contains("TEAM_SCOPE");
                }
            }
        }
    }
}
