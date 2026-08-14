package com.sitemonitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * k6 özetindeki İSTEK FAZLARI — "nerede takıldı?" sorusunun tek ölçülebilir cevabı.
 *
 * <p>Saha vakası (2026-08): bir monitör 288 koşumun 288'inde {@code request timeout} verdi ve
 * DNS mi, TCP mi, TLS mi, yanıt bekleme mi olduğu HİÇBİR ekrandan cevaplanamadı — çünkü k6 bu
 * metrikleri her koşumda üretiyor, {@code parseSummary} ise yalnız {@code http_req_duration} ve
 * {@code iteration_duration} okuyup gerisini atıyordu. Teşhis dört sürüm boyunca tahmine kaldı.
 *
 * <p>Bu testler "üretilen veri okunuyor" sözleşmesini pinler; kırılırsa teşhis yine körleşir.
 */
class ScriptedPhaseMetricsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Gerçek k6 0.49 {@code --summary-export} biçimi (trend metrikleri avg/p(95), sayaçlar count). */
    private static final String FULL_SUMMARY = """
        {"metrics":{
          "http_req_duration":{"avg":123.4,"p(95)":200.0},
          "iteration_duration":{"avg":456.7},
          "http_req_blocked":{"avg":1.2},
          "http_req_connecting":{"avg":4.18},
          "http_req_tls_handshaking":{"avg":18.9},
          "http_req_sending":{"avg":0.4},
          "http_req_waiting":{"avg":98.0},
          "http_req_receiving":{"avg":2.1},
          "http_req_failed":{"passes":1,"fails":0},
          "data_sent":{"count":381},
          "data_received":{"count":99}
        }}""";

    @Test
    @DisplayName("Tüm fazlar okunur ve ms'e yuvarlanır")
    void parsesAllPhases() {
        var s = ScriptedCheckerService.parseSummary(FULL_SUMMARY, mapper);

        assertThat(s.blockedMs).isEqualTo(1L);
        assertThat(s.connectingMs).isEqualTo(4L);
        assertThat(s.tlsMs).isEqualTo(19L);
        assertThat(s.sendingMs).isZero();
        assertThat(s.waitingMs).isEqualTo(98L);
        assertThat(s.receivingMs).isEqualTo(2L);
        assertThat(s.dataSent).isEqualTo(381L);
        assertThat(s.dataReceived).isEqualTo(99L);
        assertThat(s.httpReqFailed).isEqualTo(1);
    }

    @Test
    @DisplayName("TCP bağlanıp TLS düşen koşum: girilmeyen fazlar 0 gelir (k6'nın GERÇEK biçimi)")
    void stalledRunReportsZerosNotAbsence() {
        // `k6 run https://example.com:80` ile ölçüldü (v0.49): connecting pozitif, gerisi 0,
        // metriklerin hiçbiri EKSİLMİYOR. "Yok = girilmedi" varsayımı bu yüzden yanlıştır;
        // takılma noktası son SIFIR-OLMAYAN fazdan türetilir.
        String json = """
            {"metrics":{
              "http_req_blocked":{"avg":0.0},"http_req_connecting":{"avg":40.8358},
              "http_req_tls_handshaking":{"avg":0.0},"http_req_sending":{"avg":0.0},
              "http_req_waiting":{"avg":0.0},"http_req_receiving":{"avg":0.0},
              "data_sent":{"count":281},"data_received":{"count":316}}}""";

        var s = ScriptedCheckerService.parseSummary(json, mapper);

        assertThat(s.connectingMs).isEqualTo(41L);
        assertThat(s.tlsMs).isZero();          // null DEĞİL — k6 sıfır basar
        assertThat(s.waitingMs).isZero();
        assertThat(s.dataReceived).isEqualTo(316L);
    }

    @Test
    @DisplayName("Metrik gerçekten yoksa null kalır (eksik/kısmi özet) — 0'a düşürülmez")
    void absentMetricStaysNull() {
        // Bu, "faza girilmedi" değil "özet eksik" hâlidir; ikisini ayırmak zorundayız çünkü
        // frontend faz alanı HİÇ gelmeyen (düzeltme öncesi) satırlarda paneli çizmiyor.
        var s = ScriptedCheckerService.parseSummary(
                "{\"metrics\":{\"http_req_blocked\":{\"avg\":1.0}}}", mapper);

        assertThat(s.blockedMs).isEqualTo(1L);
        assertThat(s.tlsMs).isNull();
        assertThat(s.dataSent).isNull();
    }

    @Test
    @DisplayName("Bozuk/boş özet: faz alanları null, istisna YOK")
    void tolerantToBrokenSummary() {
        assertThat(ScriptedCheckerService.parseSummary("{bozuk", mapper).connectingMs).isNull();
        assertThat(ScriptedCheckerService.parseSummary("{}", mapper).dataSent).isNull();
        assertThat(ScriptedCheckerService.parseSummary(null, mapper).tlsMs).isNull();
    }

    @Test
    @DisplayName("Fazlar sonuca taşınır — DB'ye ve ekrana giden yol buradan geçiyor")
    void phasesReachResult() {
        var s = ScriptedCheckerService.parseSummary(FULL_SUMMARY, mapper);
        var r = new ProcessProbe.Result("out", 0, false);

        var result = ScriptedCheckerService.buildResult("PASS", 500L, r, s, "out", null, false);

        assertThat(result.phases().connectingMs()).isEqualTo(4L);
        assertThat(result.phases().dataReceived()).isEqualTo(99L);
    }

    @Test
    @DisplayName("Süreç hiç koşamadıysa faz nesnesi EMPTY'dir (null değil — çağıran NPE almasın)")
    void emptyPhasesWhenProcessNeverRan() {
        assertThat(ScriptedCheckerService.Phases.EMPTY.connectingMs()).isNull();
        assertThat(ScriptedCheckerService.Phases.EMPTY.dataSent()).isNull();
    }

    @Test
    @DisplayName("group() İÇİNDEKİ check'ler de toplanır — alarm e-postasındaki liste boş kalmasın")
    void collectsChecksInsideGroups() {
        // k6, group('...') içindeki check'leri root_group.groups.<ad>.checks altına koyar ve
        // root_group.checks BOŞ kalır. Eskiden yalnız kök okunuyordu: projenin kendi "Kritik iş
        // akışı" şablonu (üç adımın her biri bir group) ile kurulan monitörde adım düştüğünde
        // durum FAIL oluyor ama "Başarısız Check'ler" listesi boş gidiyordu.
        String json = """
            {"metrics":{"checks":{"passes":2,"fails":1}},
             "root_group":{"checks":{},"groups":{
               "1) ana sayfa":{"checks":{"200 döndü":{"passes":1,"fails":0}},"groups":{}},
               "2) arama":{"checks":{"sonuç var":{"passes":1,"fails":0}},"groups":{
                  "2a) detay":{"checks":{"fiyat görünüyor":{"passes":0,"fails":1}},"groups":{}}}}}}}""";

        var s = ScriptedCheckerService.parseSummary(json, mapper);

        assertThat(s.checksJson).isNotNull();
        // Grup adı öne eklenir: aynı check adı farklı gruplarda tekrarlanabiliyor.
        assertThat(s.checksJson).contains("1) ana sayfa › 200 döndü")
                                .contains("2) arama › sonuç var")
                                .contains("2) arama › 2a) detay › fiyat görünüyor");
        assertThat(s.checksJson).contains("\"passed\":false");   // düşen adım işaretli
    }

    @Test
    @DisplayName("Grupsuz (düz) check'ler eskisi gibi çalışır — regresyon")
    void collectsPlainRootChecks() {
        String json = """
            {"metrics":{"checks":{"passes":1,"fails":0}},
             "root_group":{"checks":{"status 200":{"passes":1,"fails":0}}}}""";

        var s = ScriptedCheckerService.parseSummary(json, mapper);

        assertThat(s.checksJson).contains("status 200").doesNotContain("›");
    }
}
