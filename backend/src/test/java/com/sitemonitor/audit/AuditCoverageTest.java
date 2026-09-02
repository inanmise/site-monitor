package com.sitemonitor.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI: durum değiştiren her HTTP ucu denetim kaydı yazmalı.
 *
 * <p><b>Neden var.</b> 2026-09 denetiminde 175 yazma ucundan 18'inin hiç denetim yazmadığı
 * bulundu — en keskin örnek: alarm yeniden-bildiriminin TOPLU hâli denetleniyordu
 * ({@code ALERT_BULK_RENOTIFY}), TEKİL hâli hiç. Bu boşluklar sessizce oluştu çünkü onları
 * yakalayan hiçbir şey yoktu; kapı olmadan aynı şekilde yeniden oluşurlar.
 * ({@code RetentionCoverageTest}'in tablolar için yaptığının denetim uçları karşılığı.)
 *
 * <p><b>Neden kaynak taraması.</b> Reflection uçları sayabilir ama metot GÖVDESİNİ göremez —
 * "bu uç denetim yazıyor mu" sorusu ancak gövdeye bakılarak cevaplanır. Bu yüzden numaralandırma
 * da doğrulama da kaynaktan yapılır; kimlik olarak {@code Sınıf#metot} kullanılır (satır
 * numaraları taşınınca kırılmasın).
 *
 * <p><b>Yanlış pozitifler üç mekanizmayla elenir</b> (sırayla): (1) aynı sınıftaki yardımcıya
 * delegasyon, (2) denetim yazan SERVİS metotlarına delegasyon — liste taranarak bulunur, elle
 * tutulmaz, (3) gerekçeli {@link #EXEMPT} muafiyeti.
 */
class AuditCoverageTest {

    private static final Path CONTROLLERS = Path.of("src", "main", "java", "com", "sitemonitor", "controller");
    private static final Path SERVICES    = Path.of("src", "main", "java", "com", "sitemonitor", "service");

    /** Durum değiştiren eşlemeler. {@code @GetMapping} bilerek yok: okuma denetlenmez. */
    private static final Pattern WRITE_MAPPING = Pattern.compile(
            "@(?:PostMapping|PutMapping|PatchMapping|DeleteMapping)\\b"
          + "|@RequestMapping\\([^)]*method\\s*=\\s*\\{?[^)}]*(?:POST|PUT|PATCH|DELETE)");

    /** Metot imzası (annotation'lardan sonraki ilk public/private metot satırı). */
    // MULTILINE ŞART: `^` olmadan imza satırı dosyanın herhangi bir yerinde değil yalnız BAŞINDA
    // aranır ve tarama sessizce boş döner (ilk yazımda tam olarak bu oldu — kapı "ihlal yok" diyordu).
    private static final Pattern METHOD_SIG = Pattern.compile(
            "^\\s*(?:public|private|protected)\\s+[\\w<>,\\[\\]\\.\\? ]+\\s+(\\w+)\\s*\\(",
            Pattern.MULTILINE);

    private static final Pattern AUDIT_CALL = Pattern.compile("auditService\\.record\\w*\\(");

    /**
     * Denetim yazmayan yazma uçları — her biri GEREKÇESİYLE.
     *
     * <p>Kurallar {@code RetentionCoverageTest} ile aynı: gerekçesiz muafiyet yasak, ve artık var
     * olmayan bir metodu işaret eden muafiyet testi kırar (liste yalnız KÜÇÜLÜR).
     */
    private static final Map<String, String> EXEMPT = new LinkedHashMap<>();
    static {
        EXEMPT.put("MonitoringController#saveScriptedDraft",
                "Otomatik kaydetme: 30 sn'de bir çağrılıyor (sekme kapanırken sendBeacon ile de). "
              + "Her çağrıyı denetlemek defteri gürültüye boğardı; taslak ÇALIŞTIRILMAZ ve kayda "
              + "dönüştüğünde MONITOR_CREATE zaten yazılır. Silme (deleteScriptedDraft) denetleniyor.");
        EXEMPT.put("IncidentController#previewNotification",
                "Yalnız HTML önizler: kaydetmez, göndermez, dış bağlantı açmaz — durum değişmiyor.");
        EXEMPT.put("SystemController#triggerHeartbeat",
                "Yönetici tetikli sistem canlılık kaydı: sistem sağlık zaman çizelgesine tek satır "
              + "yazar, kalıcı bir yapılandırma/veri değişikliği yapmaz. Komşusu scheduler-lock "
              + "release DENETLENİR çünkü o başkasının kilidini düşürür.");
        EXEMPT.put("WeeklyReportController#lock",
                "Düzenleme kilidi alma — geçici, kendiliğinden düşen bir işaret. (force=true ile "
              + "başkasının kilidini gasp etmek denetlenmeli: ayrı iş olarak not edildi.)");
        EXEMPT.put("WeeklyReportController#unlock",
                "Kilit bırakma — kalıcı durum değişikliği değil.");
        EXEMPT.put("AuditController#replayFallback",
                "Kendisi AUDIT_REPLAY yazar ama auditService üzerinden değil replay servisinden "
              + "(Faz 5'te eklenecek; şimdilik uç yok).");
    }

    /** Bir kaynak dosyadaki metot adı → gövde. */
    private static Map<String, String> methodBodies(String src) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = METHOD_SIG.matcher(src);
        while (m.find()) {
            String name = m.group(1);
            int brace = src.indexOf('{', m.end());
            if (brace < 0) continue;
            int depth = 0;
            int i = brace;
            for (; i < src.length(); i++) {
                char c = src.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') { depth--; if (depth == 0) break; }
            }
            if (i < src.length()) out.put(name, src.substring(brace, Math.min(i + 1, src.length())));
        }
        return out;
    }

    /** Yazma eşlemesi taşıyan metotların adları (annotation'ı izleyen ilk imza). */
    private static List<String> writeEndpointMethods(String src) {
        List<String> names = new ArrayList<>();
        Matcher ann = WRITE_MAPPING.matcher(src);
        while (ann.find()) {
            Matcher sig = METHOD_SIG.matcher(src);
            if (sig.find(ann.end())) names.add(sig.group(1));
        }
        return names;
    }

    /** Gövdesinde denetim yazan SERVİS metotlarının adları — elle liste değil, taranarak bulunur. */
    private static Set<String> auditingCollaborators() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(SERVICES)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                if (!AUDIT_CALL.matcher(src).find()) continue;
                for (Map.Entry<String, String> e : methodBodies(src).entrySet()) {
                    if (AUDIT_CALL.matcher(e.getValue()).find()) names.add(e.getKey());
                }
            }
        }
        return names;
    }

    /** Denetim yazıyor mu: doğrudan, sınıf-içi delegasyonla ya da denetim yazan bir servis çağrısıyla. */
    private static boolean writesAudit(String body, Map<String, String> siblings,
                                       Set<String> collaborators, int depth) {
        if (body == null || depth > 3) return false;
        if (AUDIT_CALL.matcher(body).find()) return true;

        // Denetim yazan bir servis metoduna delegasyon (ör. renameGroup → MonitoringGroupService.rename)
        for (String c : collaborators) {
            if (body.contains("." + c + "(")) return true;
        }
        // Aynı sınıftaki yardımcıya delegasyon (ör. pause/resume → toggle)
        for (Map.Entry<String, String> s : siblings.entrySet()) {
            if (body.contains(s.getKey() + "(") && !s.getValue().equals(body)
                    && writesAudit(s.getValue(), siblings, collaborators, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("KAPI: durum değiştiren her uç denetim yazar (ya da GEREKÇELİ muaftır)")
    void everyWriteEndpointIsAudited() throws IOException {
        Set<String> collaborators = auditingCollaborators();
        List<String> missing = new ArrayList<>();
        List<String> seen = new ArrayList<>();

        try (Stream<Path> files = Files.walk(CONTROLLERS)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String cls = p.getFileName().toString().replace(".java", "");
                String src = Files.readString(p, StandardCharsets.UTF_8);
                Map<String, String> bodies = methodBodies(src);

                for (String method : writeEndpointMethods(src)) {
                    String id = cls + "#" + method;
                    seen.add(id);
                    if (EXEMPT.containsKey(id)) continue;
                    if (!writesAudit(bodies.get(method), bodies, collaborators, 0)) missing.add(id);
                }
            }
        }

        assertThat(seen).as("hiç yazma ucu bulunamadı — tarama deseni bozulmuş olabilir").isNotEmpty();
        assertThat(missing)
                .as("Denetim YAZMAYAN yazma uçları:%n  %s%n%nauditService.record… ekleyin ya da "
                  + "AuditCoverageTest.EXEMPT'e GEREKÇESİYLE yazın.", String.join("\n  ", missing))
                .isEmpty();
    }

    @Test
    @DisplayName("muafiyet listesi yalnız KÜÇÜLÜR: var olmayan bir ucu işaret eden muafiyet kırar")
    void exemptionsPointAtRealEndpoints() throws IOException {
        Set<String> seen = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(CONTROLLERS)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String cls = p.getFileName().toString().replace(".java", "");
                for (String m : writeEndpointMethods(Files.readString(p, StandardCharsets.UTF_8))) {
                    seen.add(cls + "#" + m);
                }
            }
        }

        List<String> stale = EXEMPT.keySet().stream()
                .filter(k -> !seen.contains(k))
                // Henüz yazılmamış uçlar için ön-muafiyet: gerekçesinde açıkça belirtilir.
                .filter(k -> !EXEMPT.get(k).contains("şimdilik uç yok"))
                .sorted().toList();

        assertThat(stale)
                .as("Bu muafiyetler artık var olmayan uçları işaret ediyor — silin:%n%s", stale)
                .isEmpty();
    }

    /** {@code recordAction(...)} çağrısının argümanlarını kabaca ayıklar (parantez dengeli). */
    private static final Pattern RECORD_ACTION_CALL = Pattern.compile("auditService\\.recordAction\\(");

    /**
     * Detay argümanı olarak kabul edilen biçimler. Ham {@code null} ve boş {@code "{}"} yasak:
     * ikisi de "bir şey oldu ama ne olduğu bilinmiyor" demek.
     */
    private static final Pattern DETAIL_OK = Pattern.compile(
            "AuditDetail\\.|AuditDiff\\.|^\"\\{|^\\w+$|^[\\w.]+\\(\\)$|\\.toString\\(\\)$|getName\\(\\)");

    /** Detayı {@code null} kalabilecek olaylar — her biri GEREKÇELİ. */
    private static final Map<String, String> DETAIL_NULL_OK = Map.of(
            "SELF_PASSWORD_CHANGE",
            "Parola değişikliğinin yazılabilecek bir ayrıntısı YOK: ne eski ne yeni değer denetime "
          + "girebilir, kim/ne zaman bilgisi zaten satırın kendisinde.");

    @Test
    @DisplayName("KAPI: hiçbir denetim çağrısı detayı boş bırakmaz (null / \"{}\" yasak)")
    void noEmptyAuditDetails() throws IOException {
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java", "com", "sitemonitor"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                Matcher m = RECORD_ACTION_CALL.matcher(src);
                while (m.find()) {
                    List<String> args = splitArgs(src, m.end());
                    // 6 argümanlı overload: (tip, session, resType, resId, detail, changes)
                    // 7 argümanlı overload: (tip, session, request, resType, resId, detail, changes)
                    // 6 argümanlı request'li: (tip, session, request, resType, resId, detail)
                    int detailIdx = args.size() >= 7 ? 5 : (args.size() == 6 && args.get(2).contains("request") ? 5 : 4);
                    if (args.size() <= detailIdx) continue;
                    String detail = args.get(detailIdx).trim();
                    String type = args.get(0).trim().replace("\"", "");

                    if (DETAIL_NULL_OK.containsKey(type)) continue;
                    if ("null".equals(detail) || "\"{}\"".equals(detail)) {
                        offenders.add(p.getFileName() + " → " + type + " (detail=" + detail + ")");
                    }
                }
            }
        }

        assertThat(offenders)
                .as("Denetim ayrıntısı BOŞ bırakılan çağrılar — 'bir şey oldu ama ne olduğu "
                  + "bilinmiyor' demektir. AuditDetail.of(...) ile ne olduğunu yazın:%n  %s",
                    String.join("\n  ", offenders))
                .isEmpty();
    }

    /** Bir çağrının argümanlarını virgülden böler; iç içe parantez/string sayılır. */
    private static List<String> splitArgs(String src, int from) {
        List<String> args = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        boolean inStr = false;
        for (int i = from; i < src.length(); i++) {
            char c = src.charAt(i);
            if (inStr) {
                cur.append(c);
                if (c == '"' && src.charAt(i - 1) != '\\') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; cur.append(c); continue; }
            if (c == '(' || c == '[') depth++;
            if (c == ')' && depth == 0) { args.add(cur.toString()); break; }
            if (c == ')' || c == ']') depth--;
            if (c == ',' && depth == 0) { args.add(cur.toString()); cur.setLength(0); continue; }
            cur.append(c);
        }
        return args;
    }

    @Test
    @DisplayName("her muafiyetin GEREKÇESİ var (boş/kısa gerekçe kabul edilmez)")
    void everyExemptionHasReason() {
        assertThat(EXEMPT).allSatisfy((id, reason) ->
                assertThat(reason).as("%s muafiyeti gerekçesiz", id).isNotNull().hasSizeGreaterThan(30));
    }
}
