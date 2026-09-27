package com.sitemonitor.controller;

import com.sitemonitor.model.NocTarget;
import com.sitemonitor.service.noc.NocGroupIds;
import com.sitemonitor.service.noc.NocMonitorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 7/24 ALAN ZİNCİRİ KAPISI — yeni bir alan on türün HER halkasına bağlanmalı; biri atlanırsa derleme ve diğer testler
 * yeşil kalır ama o türde form değeri sessizce kaybolur (proje tuzağı: "9'un 8'i bağlandı").
 *
 * <p>Halkalar: varlık ({@link NocTarget}) → oluşturma ve güncelleme bağlaması ({@code applyNoc}) → yanıt
 * ({@code enrich*} — ayrıca {@link MonitorResponseFieldsTest}) → geçmiş/denetim alan listeleri → envanter ekle/düzenle
 * (gövdede gelmeyen alan korunur) → kopyala (yanıt biçimi aynen oluşturma gövdesine döner).
 */
class NocFieldChainTest {

    private static final Path MC = Path.of("src", "main", "java", "com", "sitemonitor", "controller", "MonitoringController.java");
    private static final Path AC = Path.of("src", "main", "java", "com", "sitemonitor", "controller", "AdminController.java");

    /** Dokuz izleme türü (envanter AdminController'da). */
    private static final List<String> TYPES = List.of("Ping", "Http", "Keyword", "Page", "PageSpeed", "Scripted", "Domain", "Port", "Dns");

    private static String read(Path p) throws Exception {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** {@code public ResponseEntity<...> name(} ile başlayan metodun gövdesi (bir sonraki uç imzasına kadar). */
    private static String endpointBody(String src, String method) {
        Matcher m = Pattern.compile("ResponseEntity<Map<String, Object>> " + method + "\\s*\\(").matcher(src);
        if (!m.find()) return null;
        Matcher next = Pattern.compile("\\n    @(Get|Post|Put|Delete)Mapping").matcher(src);
        int end = next.find(m.end()) ? next.start() : src.length();
        return src.substring(m.end(), end);
    }

    @Test
    @DisplayName("on varlık da NocTarget (noc_notify + noc_group_ids kolonları)")
    void entitiesImplementNocTarget() throws Exception {
        for (String cls : List.of("PingMonitor", "HttpMonitor", "KeywordMonitor", "PageMonitor", "PageSpeedMonitor",
                "ScriptedMonitor", "DomainMonitor", "PortMonitor", "DnsMonitor", "CertificateInventory")) {
            Class<?> c = Class.forName("com.sitemonitor.model." + cls);
            assertThat(NocTarget.class.isAssignableFrom(c)).as(cls).isTrue();
            String src = read(Path.of("src", "main", "java", "com", "sitemonitor", "model", cls + ".java"));
            assertThat(src).as(cls).contains("name = \"noc_notify\"").contains("name = \"noc_group_ids\"");
        }
    }

    @Test
    @DisplayName("dokuz türün oluşturma VE güncelleme uçları 7/24 alanlarını bağlar (applyNoc)")
    void createAndUpdateBind() throws Exception {
        String src = read(MC);
        List<String> missing = new ArrayList<>();
        for (String t : TYPES) {
            for (String verb : List.of("create", "update")) {
                String body = endpointBody(src, verb + t);
                if (body == null) { missing.add(verb + t + " (metot yok)"); continue; }
                if (!body.contains("applyNoc(body, m)")) missing.add(verb + t);
            }
        }
        assertThat(missing).as("7/24 alanını bağlamayan uç(lar)").isEmpty();
    }

    @Test
    @DisplayName("geçmiş/denetim alan listeleri 7/24 alanlarını taşır (yalnız 7/24 değişen düzenleme de iz bırakır)")
    void historyFieldLists() throws Exception {
        String mc = read(MC), ac = read(AC);
        for (String arr : List.of("MON_FIELDS", "SCRIPTED_FIELDS", "PAGESPEED_FIELDS")) {
            String body = arrayBody(mc, arr);
            assertThat(body).as(arr).contains("\"nocNotify\"").contains("\"nocGroupIds\"");
        }
        assertThat(arrayBody(ac, "INVENTORY_FIELDS")).contains("\"nocNotify\"").contains("\"nocGroupIds\"");
    }

    private static String arrayBody(String src, String name) {
        Matcher m = Pattern.compile("String\\[\\]\\s+" + name + "\\s*=\\s*\\{([\\s\\S]*?)\\}").matcher(src);
        assertThat(m.find()).as(name + " bulunamadı").isTrue();
        return m.group(1);
    }

    @Test
    @DisplayName("envanter: ekle grup kimliklerini temizler; düzenle YALNIZ gövdede gelen alanı yazar")
    void inventoryBinding() throws Exception {
        String ac = read(AC);
        assertThat(endpointBody(ac, "addInventory")).contains("sanitizeGroupIds(item.getNocGroupIds())");
        String upd = endpointBody(ac, "updateInventory");
        assertThat(upd).contains("item.isNocNotifySupplied()").contains("item.isNocGroupIdsSupplied()");
    }

    /** Kopyala (duplicate): arayüz GET yanıtını oluşturma gövdesine çevirir — değerler aynen geri dönmeli. */
    @Test
    @DisplayName("kopyala gidiş-dönüş: yanıt biçimi (noc_notify, noc_group_ids) → oluşturma gövdesi → aynı değerler")
    void duplicateRoundTrip() {
        com.sitemonitor.model.PingMonitor src = new com.sitemonitor.model.PingMonitor();
        src.setNocNotify(true);
        src.setNocGroupIds("7,3");
        // enrich* yanıtı:
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("noc_notify", Boolean.TRUE.equals(src.getNocNotify()));
        response.put("noc_group_ids", NocGroupIds.parse(src.getNocGroupIds()));
        // arayüzün kopya gövdesi (camelCase):
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nocNotify", response.get("noc_notify"));
        body.put("nocGroupIds", response.get("noc_group_ids"));
        com.sitemonitor.model.PingMonitor copy = new com.sitemonitor.model.PingMonitor();
        NocMonitorService.applyFromBody(copy, body, null);
        assertThat(copy.getNocNotify()).isTrue();
        assertThat(copy.getNocGroupIds()).isEqualTo("7,3");

        // Gövdede alan YOKSA mevcut değer korunur (alanı bilmeyen istemci sıfırlamaz).
        NocMonitorService.applyFromBody(copy, Map.of("name", "x"), null);
        assertThat(copy.getNocNotify()).isTrue();
        assertThat(copy.getNocGroupIds()).isEqualTo("7,3");
        // Açıkça boş liste → varsayılan gruplar (null); nocNotify null → kapalı.
        Map<String, Object> clear = new LinkedHashMap<>();
        clear.put("nocNotify", null);
        clear.put("nocGroupIds", List.of());
        NocMonitorService.applyFromBody(copy, clear, null);
        assertThat(copy.getNocNotify()).isFalse();
        assertThat(copy.getNocGroupIds()).isNull();
    }

    /**
     * 7/24 aç/kapa izni, izlemenin KENDİ güncelleme ucunun matris izniyle AYNI olmalı (yayın öncesi inceleme:
     * SENTETİK uç {@code monitoring.scripted} isterken NocType {@code monitoring.crud} diyordu — USER kendi ekleyemediği
     * sentetik izlemede 7/24'ü açıp kapatabiliyordu).
     */
    @Test
    @DisplayName("her türün 7/24 izni = o türün güncelleme ucunun matris izni (SSL: envanter düzenleme)")
    void togglePermissionMatchesUpdateEndpoint() throws Exception {
        String mc = read(MC);
        Map<com.sitemonitor.service.noc.NocType, String> endpoint = Map.of(
                com.sitemonitor.service.noc.NocType.PING, "updatePing", com.sitemonitor.service.noc.NocType.HTTP, "updateHttp",
                com.sitemonitor.service.noc.NocType.KEYWORD, "updateKeyword", com.sitemonitor.service.noc.NocType.PAGE, "updatePage",
                com.sitemonitor.service.noc.NocType.PAGESPEED, "updatePageSpeed", com.sitemonitor.service.noc.NocType.SCRIPTED, "updateScripted",
                com.sitemonitor.service.noc.NocType.DOMAIN, "updateDomain", com.sitemonitor.service.noc.NocType.PORT, "updatePort",
                com.sitemonitor.service.noc.NocType.DNS, "updateDns");
        List<String> wrong = new ArrayList<>();
        for (var e : endpoint.entrySet()) {
            String body = endpointBody(mc, e.getValue());
            assertThat(body).as(e.getValue()).isNotNull();
            String expected = "permissionService.require(session, \"" + e.getKey().permission + "\", \"edit\")";
            if (!body.contains(expected)) wrong.add(e.getKey() + " → " + e.getKey().permission);
        }
        assertThat(wrong).as("7/24 izni izlemenin güncelleme ucuyla ayrışmış").isEmpty();
        assertThat(endpointBody(read(AC), "updateInventory"))
                .contains("requirePerm(session, \"" + com.sitemonitor.service.noc.NocType.SSL.permission + "\", \"edit\")");
    }
}
