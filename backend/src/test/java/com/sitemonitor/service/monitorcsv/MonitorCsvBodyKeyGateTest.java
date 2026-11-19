package com.sitemonitor.service.monitorcsv;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI (2026-11-19): CSV sütunlarının gövde anahtarları izleme uçlarında GERÇEKTEN okunuyor mu. İçe aktarım satırı
 * MonitoringController'ın create / update metotlarına verir; denetleyicinin okumadığı bir anahtar sessizce yok sayılırdı ve
 * önizleme "güncellenecek" deyip hiçbir şey değişmezdi. Kaynak taranır: anahtar denetleyicide (ya da zamanlama anahtarlarında
 * {@code ScriptedSchedule}'de) tırnaklı metin olarak geçmeli. Türlerin sütun listesi de sabit sözleşmedir (başlık adları kalıcı).
 */
class MonitorCsvBodyKeyGateTest {

    private static String read(String rel) throws Exception {
        return Files.readString(Path.of("src", "main", "java", "com", "sitemonitor").resolve(rel), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("her sütunun gövde anahtarı izleme uçlarında okunur")
    void everyBodyKeyIsReadByTheController() throws Exception {
        String controller = read("controller/MonitoringController.java");
        String schedule = read("service/ScriptedSchedule.java");
        String noc = read("service/noc/NocMonitorService.java");
        List<String> missing = new ArrayList<>();
        for (String type : MonitorCsvSpec.TYPES) {
            MonitorCsvSpec.TypeSpec spec = MonitorCsvSpec.of(type);
            assertThat(spec).as(type).isNotNull();
            for (MonitorCsvSpec.Column c : spec.columns()) {
                if (c.bodyKey() == null) continue;
                String lit = "\"" + c.bodyKey() + "\"";
                if (!controller.contains(lit) && !schedule.contains(lit) && !noc.contains(lit)) missing.add(type + "." + c.header() + " → " + c.bodyKey());
            }
        }
        assertThat(missing).as("denetleyicide okunmayan gövde anahtarları").isEmpty();
    }

    @Test
    @DisplayName("türler ve başlıklar: on tür, her birinde name + team ilk iki sütun, başlıklar snake_case ve tekil, sır sütunu yok")
    void headersContract() {
        assertThat(MonitorCsvSpec.TYPES).containsExactly("http", "keyword", "page", "pagespeed", "ping", "port", "dns", "domain",
                "scripted", "dbquery");
        for (String type : MonitorCsvSpec.TYPES) {
            List<String> h = MonitorCsvSpec.of(type).headers();
            assertThat(h.subList(0, 2)).as(type).containsExactly("name", "team");
            assertThat(new HashSet<>(h)).as(type + " tekil").hasSize(h.size());
            for (MonitorCsvSpec.Column c : MonitorCsvSpec.of(type).columns()) {
                String x = c.header();
                assertThat(x).as(type).matches("[a-z][a-z0-9_]*");
                // Yalnız-bilgi sütunları (…_set) "kaynakta vardı" bayrağıdır; yazılabilir sütunların hiçbiri sır taşımaz
                if (c.info()) {
                    assertThat(x).as(type + " bilgi sütunu").endsWith("_set");
                    assertThat(c.bodyKey()).isNull();
                } else {
                    assertThat(x).as(type + " sır sütunu").doesNotContain("pass").doesNotContain("secret")
                            .isNotEqualTo("custom_headers");
                }
            }
            Set<String> req = MonitorCsvSpec.of(type).createRequired();
            for (String r : req) assertThat(h).as(type + " zorunlu " + r).contains(r);
        }
    }
}
