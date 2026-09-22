package com.sitemonitor.service.report;

import com.sitemonitor.util.Csv;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.CertificateInventoryOps;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Sertifika envanterinin SUNUCU TARAFINDA CSV ve PDF üretimi — aylık rapor e-postasının ekleri.
 *
 * <p>Ekrandaki "Dışa Aktar" jsPDF ile tarayıcıda üretiliyor; zamanlanmış mailde tarayıcı yok,
 * bu yüzden ikinci bir üretici gerekti. Kolon listesi ve 13 operasyonel bayrak
 * {@link CertificateInventoryOps#ALL}'dan gelir — yani frontend'in eskiden yaptığı gibi bir
 * bayrağı kaçırmak mümkün değil (entity↔katalog bağı reflection bekçisiyle kilitli).
 *
 * <p>PDF için Apache PDFBox kullanılır (Apache-2.0). PDFBox'ta tablo API'si olmadığından
 * düzen {@link PdfTable} ile elle çizilir; Türkçe glyph'ler için Roboto TTF gömülür
 * (standart PDF fontları ş/ğ/İ taşımaz).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryExportService {

    private final CertificateInventoryRepository inventoryRepo;
    private final TeamRepository teamRepo;

    /** Rapor kapsamı: silinmemiş tüm kayıtlar (aktif + pasif) — ekrandaki dışa aktarımla aynı. */
    public List<CertificateInventory> reportRows() {
        return inventoryRepo.findByDeletedAtIsNullOrderByDomainAsc();
    }

    /** Envanterdeki takım kimliklerini ada çevirir (yalnız atıf yapılanlar yüklenir). */
    public Map<Long, String> teamNames(List<CertificateInventory> rows) {
        var ids = rows.stream().map(CertificateInventory::getTeamId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return teamRepo.findAllById(ids).stream()
                .filter(t -> t.getId() != null && t.getName() != null)
                .collect(Collectors.toMap(Team::getId, Team::getName, (a, b) -> a));
    }

    // ── CSV ──────────────────────────────────────────────────────────────────

    /**
     * Excel-uyumlu CSV: UTF-8 BOM + CRLF (Türkçe karakterler ve satır sonları için).
     * Kolon sırası ekrandaki dışa aktarımla aynı tutulur.
     */
    public byte[] csv(List<CertificateInventory> rows, Map<Long, String> teams) {
        // Kolon adları ekrandaki dışa aktarımın TR etiketleriyle BİREBİR
        // (frontend/src/utils/exportInventory.js → i18n inv.* anahtarları).
        List<String> header = new java.util.ArrayList<>(List.of(
                "Domain", "Port", "Takım", "Kritiklik Seviyesi (Tier)", "Satın Alan Kişi/Ekip", "Platform", "Platform Ayrıntısı", "Aktif"));
        CertificateInventoryOps.ALL.forEach(f -> header.add(f.label()));
        header.addAll(List.of("Değişiklik Açıklaması",
                "Beklenen Parmak İzi — SHA-256 hex (dağıtım uyumsuzluğu tespiti için)",
                "Beklenen Subject", "Oluşturulma", "Güncelleme"));

        StringBuilder sb = new StringBuilder(4096);
        sb.append('﻿');                       // BOM — Excel'in UTF-8'i doğru açması için
        sb.append(header.stream().map(InventoryExportService::csvEscape).collect(Collectors.joining(",")));
        sb.append("\r\n");

        for (CertificateInventory r : rows) {
            List<String> cells = new java.util.ArrayList<>();
            cells.add(nz(r.getDomain()));
            cells.add(r.getPort() == null ? "443" : String.valueOf(r.getPort()));
            cells.add(r.getTeamId() == null ? "" : nz(teams.get(r.getTeamId())));
            cells.add(tierLabel(r.getTier()));
            cells.add(nz(r.getPurchasedBy()));
            cells.add(nz(r.getPlatform()));
            cells.add(nz(r.getPlatformDetail()));
            cells.add(Boolean.FALSE.equals(r.getActive()) ? "Pasif" : "Aktif");
            for (var f : CertificateInventoryOps.ALL) {
                cells.add(Boolean.TRUE.equals(f.getter().apply(r)) ? "Evet" : "Hayır");
            }
            cells.add(oneLine(r.getChangeDescription()));
            cells.add(nz(r.getExpectedFingerprint()));
            cells.add(nz(r.getExpectedSubject()));
            cells.add(nz(r.getCreatedAt()));
            cells.add(nz(r.getUpdatedAt()));
            sb.append(cells.stream().map(InventoryExportService::csvEscape).collect(Collectors.joining(",")));
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Ortak kural: tırnaklama + formül nötrlemesi (CWE-1236) — {@link Csv#cell}. Yerel kopya 2026-09-11 kaldırıldı. */
    static String csvEscape(String s) { return Csv.cell(s); }

    // ── PDF ──────────────────────────────────────────────────────────────────

    /**
     * PDF — EKRANDAKİ "Dışa Aktar → PDF" ile aynı domain-başına detay düzeni
     * ({@link InventoryPdfWriter}). Kullanıcı aynı belgeyi iki yoldan alabildiği için
     * (ekran + aylık mail eki) çıktıların farklı görünmemesi gerekir.
     */
    public byte[] pdf(List<CertificateInventory> rows, Map<Long, String> teams) {
        try (InventoryPdfWriter w = new InventoryPdfWriter()) {
            return w.write(rows, teams);
        } catch (Exception e) {
            log.error("Envanter PDF üretimi başarısız: {}", e.getMessage(), e);
            return new byte[0];        // ek olmadan gönderim sürsün — rapor tamamen düşmesin
        }
    }

    // ── ortak ────────────────────────────────────────────────────────────────

    /** Ekrandaki tier etiketiyle aynı: "T1 — Müşteri Yüzü Üretim". */
    static String tierLabel(Integer tier) {
        if (tier == null) return "Sınıflandırılmamış";
        return switch (tier) {
            case 1 -> "T1 — Müşteri Yüzü Üretim";
            case 2 -> "T2 — İç Üretim";
            case 3 -> "T3 — UAT/Pre-Prod";
            case 4 -> "T4 — Dev/Sandbox";
            default -> "T" + tier;
        };
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** Markdown açıklamayı tek satıra indirger (CSV hücresi çok satırlı olmasın). */
    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /** Dosya adı damgası — YYYY-MM. */
    public static Map<String, String> fileNames(int year, int month) {
        String stamp = String.format("%04d-%02d", year, month);
        Map<String, String> m = new LinkedHashMap<>();
        m.put("csv", "sertifika-envanteri-" + stamp + ".csv");
        m.put("pdf", "sertifika-envanteri-" + stamp + ".pdf");
        return m;
    }

    /** Boş çıktı kontrolü — ByteArrayOutputStream tabanlı üreticilerde tek yerde. */
    static byte[] toBytes(ByteArrayOutputStream out) {
        return out == null ? new byte[0] : out.toByteArray();
    }
}
