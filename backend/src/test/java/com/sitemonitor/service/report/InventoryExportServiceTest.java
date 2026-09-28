package com.sitemonitor.service.report;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.CertificateInventoryOps;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aylık rapor eklerinin (CSV + PDF) sunucu tarafı üretimi.
 * PDF gerçekten açılıp metni okunur — "bayt üretildi" demek yetmez, Türkçe glyph'lerin
 * gömülü fontla doğru yazıldığı kanıtlanmalı (standart PDF fontları ş/ğ/İ taşımaz).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryExportServiceTest {

    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock TeamRepository teamRepo;

    InventoryExportService service;

    @BeforeEach
    void setUp() {
        service = new InventoryExportService(inventoryRepo, teamRepo);
    }

    private CertificateInventory row(String domain, Integer tier, boolean active) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain);
        i.setPort(443);
        i.setTier(tier);
        i.setActive(active);
        i.setTeamId(7L);
        i.setPurchasedBy("Tarık Karakılıç");
        i.setNetscaler(true);
        i.setWafEnabled(true);
        i.setInUse(true);
        i.setChangeDescription("1. IISAdmins PFX'i alır.\n2. Netscaler ve WAF'ta güncellenir.");
        return i;
    }

    private final Map<Long, String> teams = Map.of(7L, "SY-Takım A");

    @Test
    @DisplayName("CSV: BOM + CRLF, 13 bayrak kolonu, Evet/Hayır ve tırnak kaçışı")
    void csvShape() {
        String csv = new String(service.csv(List.of(row("www.example.com", 1, true)), teams), StandardCharsets.UTF_8);

        assertThat(csv).startsWith("﻿");                       // Excel'in UTF-8 açması için BOM
        assertThat(csv).contains("\r\n");
        String header = csv.split("\r\n")[0];
        for (var f : CertificateInventoryOps.ALL) {
            assertThat(header).as("bayrak kolonu: %s", f.label()).contains(f.label());
        }
        // v20.8.1 öncesi frontend export'un kaçırdığı bayrak — burada da olmalı.
        assertThat(header).contains("Proxy Üzerinden Kontrol Et");

        String dataRow = csv.split("\r\n")[1];
        assertThat(dataRow).contains("www.example.com").contains("SY-Takım A").contains("Aktif");
        assertThat(dataRow).contains("Evet").contains("Hayır");
    }

    @Test
    @DisplayName("CSV: virgül/tırnak/satır sonu içeren hücreler kaçırılır")
    void csvEscaping() {
        assertThat(InventoryExportService.csvEscape("a,b")).isEqualTo("\"a,b\"");
        assertThat(InventoryExportService.csvEscape("de\"mo")).isEqualTo("\"de\"\"mo\"");
        assertThat(InventoryExportService.csvEscape("bir\niki")).isEqualTo("\"bir\niki\"");
        assertThat(InventoryExportService.csvEscape("sade")).isEqualTo("sade");
        assertThat(InventoryExportService.csvEscape("=1+1")).isEqualTo("'=1+1");   // formül nötrlemesi (Csv.cell)
        assertThat(InventoryExportService.csvEscape("-cmd")).isEqualTo("'-cmd");
        assertThat(InventoryExportService.csvEscape(null)).isEmpty();
    }

    @Test
    @DisplayName("CSV: pasif kayıt 'Pasif', tier yoksa 'Sınıflandırılmamış'")
    void csvStatusAndTier() {
        String csv = new String(service.csv(List.of(row("pasif.example.com", null, false)), teams),
                StandardCharsets.UTF_8);
        assertThat(csv).contains("Pasif").contains("Sınıflandırılmamış");
    }

    @Test
    @DisplayName("PDF: ekrandaki detay düzeni — bölüm başlıkları, alanlar, Türkçe glyph'ler")
    void pdfMatchesScreenLayout() throws Exception {
        byte[] pdf = service.pdf(List.of(
                row("www.example.com", 1, true),
                row("internetsubesi.example.com", 2, true)), teams);

        assertThat(pdf).isNotEmpty();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(1);
            String text = new PDFTextStripper().getText(doc);

            // Ekrandaki PDF ile aynı bölümler (exportInventory.js)
            assertThat(text).as("PDF metni: <%s>", text)
                    .contains("Sertifika Envanteri")
                    .contains("TEMEL BİLGİLER")
                    .contains("OPERASYONEL BİLGİLER")
                    .contains("DEĞİŞİKLİK AÇIKLAMASI");
            assertThat(text).contains("www.example.com").contains("internetsubesi.example.com");
            // ✓ Roboto'da yok → encodable() "+" ile değiştirir; önemli olan Evet/Hayır ayrımı.
            assertThat(text).contains("Netscaler").contains("Evet").contains("Hayır");
            assertThat(text).contains("Kritiklik Seviyesi (Tier)").contains("Satın Alan Kişi/Ekip");
            assertThat(text).contains("Sayfa 1 / ");

            // Gömülü Roboto olmadan yazılamayan karakterler: ç, ı, ş, İ. Font gömülmeseydi
            // "Takım" → "Takim", "Bankacılık" → "Bankacilik" olurdu; birebir eşleşme kanıttır.
            // (Bölüm başlıkları büyük harfe çevrilir, bu yüzden gövde metinleri üzerinden bakılır.)
            assertThat(text).as("Türkçe glyph kaybı — PDF metni: <%s>", text)
                    .contains("Takım")
                    .contains("SY-Takım A");
        }
    }

    @Test
    @DisplayName("PDF: 13 operasyonel bayrağın TAMAMI basılır (ekranla aynı)")
    void pdfListsAllOperationalFlags() throws Exception {
        byte[] pdf = service.pdf(List.of(row("flags.example.com", 1, true)), teams);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
            for (var f : CertificateInventoryOps.ALL) {
                // Uzun etiketler sütuna sığmayınca "…" ile kırpılır; ilk 12 karakter yeterli kanıt.
                String head = f.label().length() > 12 ? f.label().substring(0, 12) : f.label();
                assertThat(text).as("PDF'te eksik bayrak: %s", f.label()).contains(head);
            }
        }
    }

    @Test
    @DisplayName("PDF: çok kayıtta sayfalanır ve her sayfada başlık/altlık olur")
    void pdfPaginates() throws Exception {
        List<CertificateInventory> many = new java.util.ArrayList<>();
        for (int i = 0; i < 120; i++) many.add(row("host" + i + ".example.com", 3, true));

        byte[] pdf = service.pdf(many, teams);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("host0.example.com").contains("host119.example.com");
            assertThat(text).contains("1 / " + doc.getNumberOfPages());
        }
    }

    @Test
    @DisplayName("PDF (aylık rapor eki, 2026-09-28): gövdeyle AYNI özet — başlık/hüküm, KPI, dağılım, 90 gün tablosu ve hijyen TAM liste; kayıt bazında detay YOK")
    void pdfWithSummary() throws Exception {
        CertInventorySamples.Large l = CertInventorySamples.largeData();
        var summary = CertInventorySamples.large();
        byte[] pdf = service.pdf(l.rows, l.teams, summary);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            // Kullanıcı kararı 2026-09-28: ek YALNIZ özet — 426 kayıtlık envanter eskiden ~146 sayfaydı
            assertThat(doc.getNumberOfPages()).isBetween(2, 12);
            assertThat(text).as("PDF özeti").contains("Sertifika Envanteri").contains("Eylül 2026")
                    .contains("412 aktif sertifika").contains("36 tanesi 30 gün içinde bitiyor")
                    .contains("Toplam kayıt").contains("30 gün içinde").contains("7 gün içinde").contains("Hijyen bulgusu")
                    .contains("AKSİYON GEREKLİ").contains("Kalan süreye göre dağılım").contains("90 gün içinde bitenler")
                    .contains("Envanter hijyeni").contains("Önerilen:");
            // e-postanın KIRPTIĞI satırlar ekte TAM — ÖZET bölümünde aranır (alan adları detay bloklarında da geçer,
            // tüm metinde aramak kırpılmış bir özeti yakalamazdı): 30 gün penceresinin 38'incisi, 31–90 günün sonuncusu,
            // hijyen "sorumlu ekip" grubunun 14'üncüsü.
            String summaryText = text;
            String hygiene = summaryText.substring(summaryText.indexOf("Envanter hijyeni"));
            assertThat(summaryText).contains("svc28.example.com").contains("app61.example.com");
            assertThat(hygiene).contains("host011.example.com").contains("host014.example.com");
            // en acil üstte
            assertThat(text.indexOf("legacy.example.com")).isLessThan(text.indexOf("odeme.example.com"));
            assertThat(text.indexOf("odeme.example.com")).isLessThan(text.indexOf("portal.example.com"));
            // kayıt bazındaki detay blokları ekte YOK (ekrandaki "Dışa Aktar → PDF"te var — ayrı test)
            assertThat(text).doesNotContain("Envanter Detayı").doesNotContain("TEMEL BİLGİLER").doesNotContain("OPERASYONEL BİLGİLER");
            // alt bilgi: her sayfada rapor + ay ve sayfa numarası
            assertThat(text).contains("Sayfa 1 / " + doc.getNumberOfPages());
            PDFTextStripper page2 = new PDFTextStripper();
            page2.setStartPage(2);
            page2.setEndPage(2);
            String p2 = page2.getText(doc);
            assertThat(p2).contains("Eylül 2026").contains("Sayfa 2 / ");
        }
    }

    @Test
    @DisplayName("PDF özeti: kayıtsız envanterde tek sayfa (detay bölümü açılmaz), belge düşmez")
    void pdfSummaryWithoutRows() throws Exception {
        byte[] pdf = service.pdf(List.of(), Map.of(), CertInventorySamples.empty());
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            assertThat(new PDFTextStripper().getText(doc)).contains("Envanterde eksik, hatalı veya güncel olmayan kayıt bulunmadı.")
                    .doesNotContain("Envanter Detayı");
        }
    }

    @Test
    @DisplayName("Boş envanterde bile geçerli PDF üretilir (rapor düşmez)")
    void pdfWithNoRows() throws Exception {
        byte[] pdf = service.pdf(List.of(), Map.of());
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Dosya adları yıl-ay damgalı")
    void fileNames() {
        var names = InventoryExportService.fileNames(2026, 8);
        assertThat(names.get("csv")).isEqualTo("sertifika-envanteri-2026-08.csv");
        assertThat(names.get("pdf")).isEqualTo("sertifika-envanteri-2026-08.pdf");
    }

    @Test
    @DisplayName("Takım adları yalnız atıf yapılan kimlikler için yüklenir")
    void teamNamesLoadsOnlyReferenced() {
        Team t = new Team();
        t.setId(7L);
        t.setName("SY-Takım A");
        org.mockito.Mockito.when(teamRepo.findAllById(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(t));

        assertThat(service.teamNames(List.of(row("a.example.com", 1, true))))
                .containsEntry(7L, "SY-Takım A");
        assertThat(service.teamNames(List.of())).isEmpty();
    }
}
