package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MANUEL "ÇALIŞTIR" BAĞLANTI KAPISI.
 *
 * <p>Manuel çalıştırma eskiden tek kontrol yapıp bırakıyordu: ekranda "hata" görünüyor ama alarm
 * hiç açılmıyordu — iki farklı gerçek. Artık dokuz izleme türünde de zamanlayıcıyla AYNI
 * değerlendirme hattına giriyor.
 *
 * <p><b>Asıl risk kopyalamaydı:</b> her türün alarm bağlamı (ctx) sweep döngülerinin içinde
 * kuruluyordu. Manuel yol kendi ctx'ini kursaydı bir anahtar — {@code team_id} ya da
 * {@code notification_group_id} — eksik kalabilir ve alarm SESSİZCE yanlış takıma/gruba giderdi.
 * Bu yüzden her tür için ctx kurulumu TEK bir metoda çıkarıldı ve iki yol onu paylaşıyor.
 *
 * <p>Bu kapı, yeni bir izleme türü eklendiğinde ya da bir tür manuel yoldan koparıldığında
 * kırmızıya döner. (Sertifika türü kapsam dışı: onun manuel akışı ayrı bir serviste.)
 */
class ManualEvaluationWiringTest {

    /** Metot gövdesinin sonunu bulmak için satır başı — kaynak dosyalar LF ile saklanıyor. */
    private static final String NEWLINE = String.valueOf((char) 10);

    private static final Path SCHEDULER = Path.of(
            "src/main/java/com/sitemonitor/service/SchedulerService.java");
    private static final Path CONTROLLER = Path.of(
            "src/main/java/com/sitemonitor/controller/MonitoringController.java");

    /** Manuel değerlendirme girişi olması BEKLENEN türler (tetik ucu olan her izleme türü). */
    private static final List<String> ENTRIES = List.of(
            "evaluateHttpNow", "evaluatePortNow", "evaluatePingNow", "evaluateKeywordNow",
            "evaluateDnsNow", "evaluatePageNow", "evaluatePageSpeedNow", "evaluateScriptedNow",
            "evaluateDomainAlarmsNow");   // Domain'in girişi daha önceden vardı

    private static String read(Path p) throws Exception {
        return Files.readString(p);
    }

    @Test
    @DisplayName("her türün manuel değerlendirme girişi TANIMLI")
    void allEntriesDefined() throws Exception {
        String src = read(SCHEDULER);
        List<String> missing = new ArrayList<>();
        for (String e : ENTRIES) {
            if (!Pattern.compile("public void " + e + "\\(").matcher(src).find()) missing.add(e);
        }
        assertThat(missing).as("SchedulerService'te tanımsız manuel giriş").isEmpty();
    }

    @Test
    @DisplayName("her tetik ucu manuel değerlendirmeyi ÇAĞIRIR — yoksa alarm hiç açılmaz")
    void allEntriesCalledFromController() throws Exception {
        String src = read(CONTROLLER);
        String sched = read(SCHEDULER);
        List<String> missing = new ArrayList<>();
        for (String e : ENTRIES) {
            if (src.contains("schedulerService." + e + "(")) continue;
            // Senaryo DOLAYLI bağlanır: uç triggerScriptedCheckAsync'i çağırır, değerlendirme
            // koşumun KENDİ içinde yapılır. Bu bilinçli — ayrı bir evaluate* çağrısı ikinci bir k6
            // koşumu başlatıyordu (tek tıkla iki süreç, iki permit). Zincir yine uçtan uca doğrulanır.
            if ("evaluateScriptedNow".equals(e)
                    && src.contains("schedulerService.triggerScriptedCheckAsync(")
                    && sched.contains("evaluateScriptedNow(m, r)")) continue;
            missing.add(e);
        }
        assertThat(missing).as("tetik ucundan çağrılmayan manuel giriş").isEmpty();
    }

    @Test
    @DisplayName("manuel girişler ASENKRON — doğrulama 3×30 sn sürer, istek bekleyemez")
    void entriesAreAsync() throws Exception {
        String src = read(SCHEDULER);
        List<String> sync = new ArrayList<>();
        for (String e : ENTRIES) {
            if ("evaluateDomainAlarmsNow".equals(e)) continue;   // eşik değerlendirmesi, anlık
            Matcher m = Pattern.compile("(@Async\\([^)]*\\)\\s*)?public void " + e + "\\(").matcher(src);
            if (!m.find() || m.group(1) == null) sync.add(e);
        }
        assertThat(sync).as("@Async olmayan manuel giriş — HTTP isteği doğrulama boyunca bloklanır").isEmpty();
    }

    @Test
    @DisplayName("manuel girişler ctx'i KOPYALAMAZ — paylaşılan kurucuyu çağırır")
    void entriesReuseSharedBuilders() throws Exception {
        String src = read(SCHEDULER);
        // Her manuel giriş gövdesinde ya paylaşılan *SweepItem kurucusu ya da add*SweepItems
        // çağrısı olmalı. Kendi `new SweepItem(` çağrısını kuran bir giriş, ctx'i kopyalıyor
        // demektir — bir anahtar eksik kalırsa alarm sessizce yanlış takıma gider.
        List<String> offenders = new ArrayList<>();
        for (String e : ENTRIES) {
            int at = src.indexOf("public void " + e + "(");
            if (at < 0) continue;
            String body = src.substring(at, Math.min(src.length(), at + 1200));
            int end = body.indexOf("\n    }");
            if (end > 0) body = body.substring(0, end);
            boolean reuses = body.contains("SweepItem(m, ") || body.contains("SweepItems(m, ")
                    || body.contains("dnsFailureSweepItem(") || body.contains("addDomainSweepItems(");
            if (body.contains("new MonitoringOutageService.SweepItem(") || !reuses) offenders.add(e);
        }
        assertThat(offenders).as("ctx'i kopyalayan manuel giriş").isEmpty();
    }

    // ── Denetim 5. tur, bulgu 2: TEK tık → TEK kontrol ────────────────────────
    //
    // Uç kontrolü koşup satırı kaydediyor, ardından evaluate*Now recheck*'i ÇAĞIRIYORDU; o da
    // kendi kontrolünü koşup KENDİ satırını yazıyordu. Tek tıkla hedefe iki istek, geçmişe iki
    // satır ve uptime yüzdesinin manuel kontrolü çift sayması. Sayfa izlemede ikinci koşum
    // SITE_CRAWL modunda TAM SITE TARAMASI oluyordu.

    @Test
    @DisplayName("KAPI: hiçbir evaluate*Now metodu recheck* çağırmaz (ikinci kontrol yasak)")
    void manualEntriesDoNotRecheck() throws Exception {
        String src = read(SCHEDULER);
        List<String> offenders = new ArrayList<>();
        for (String e : ENTRIES) {
            if ("evaluateDomainAlarmsNow".equals(e)) continue;   // zaten sonucu parametre alıyor
            int start = src.indexOf("public void " + e + "(");
            if (start < 0) continue;
            int end = src.indexOf(NEWLINE + "    }", start);
            String body = end > start ? src.substring(start, end) : src.substring(start);
            if (Pattern.compile("recheck[A-Z][A-Za-z]*[(]").matcher(body).find()) offenders.add(e);
        }
        assertThat(offenders)
                .as("manuel değerlendirme İKİNCİ bir kontrol koşuyor — hedefe çift istek, geçmişe çift satır")
                .isEmpty();
    }

    @Test
    @DisplayName("KAPI: her manuel giriş kontrol SONUCUNU parametre olarak alır")
    void manualEntriesTakeResultParameter() throws Exception {
        String src = read(SCHEDULER);
        List<String> offenders = new ArrayList<>();
        for (String e : ENTRIES) {
            Matcher m = Pattern.compile("public void " + e + "[(]([^)]*)[)]").matcher(src);
            if (!m.find()) continue;
            if (!m.group(1).contains("Map<String, Object>")) offenders.add(e + " -> (" + m.group(1) + ")");
        }
        assertThat(offenders)
                .as("sonucu parametre almayan giriş kendi kontrolünü koşmak zorunda kalır")
                .isEmpty();
    }
}
