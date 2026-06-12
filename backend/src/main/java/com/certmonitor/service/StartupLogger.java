package com.certmonitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Uygulama hazır olduğunda React (Vite) statik arayüzünün pod içinde sunulabilir
 * durumda olup olmadığını loglar.
 *
 * <p>Frontend ayrı bir süreç DEĞİLDİR: Vite build çıktısı (frontend/dist) Docker
 * imajında /app/frontend/dist'e kopyalanır ve aynı Spring Boot JVM'i tarafından
 * servis edilir ({@code spring.web.resources.static-locations}). Bu yüzden "frontend
 * ayağa kalktı mı" sorusu = dist/index.html mevcut ve okunabilir mi sorusudur.
 * Operatör pod logundan tek bakışta UI'ın sunulabilir olup olmadığını görebilsin diye.
 *
 * <p>{@link ShutdownLogger} ile simetriktir.
 */
@Slf4j
@Component
public class StartupLogger {

    @Value("${spring.web.resources.static-locations:}")
    private String staticLocations;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            logFrontendStatus();
        } catch (Exception e) {
            // Log kontrolü asla başlatmayı bozmamalı
            log.warn("[FRONTEND] Statik arayüz durumu kontrol edilemedi: {}", e.getMessage());
        }
    }

    private void logFrontendStatus() {
        String cwd = System.getProperty("user.dir", "?");
        List<String> locs = parseLocations(staticLocations);
        log.info("[FRONTEND] Statik arayüz aranıyor — CWD={}, konumlar={}", cwd, locs);

        // 1) Dosya sistemindeki konumlar (Docker: file:./frontend/dist/)
        Optional<File> fileIndex = findFileIndex(staticLocations);
        if (fileIndex.isPresent()) {
            File index = fileIndex.get();
            int files = countFiles(index.getParentFile().toPath());
            log.info("[FRONTEND] ✅ Statik arayüz HAZIR — index.html bulundu: {} ({} byte), dist'te {} dosya. UI sunulabilir.",
                    safeAbs(index), index.length(), files);
            return;
        }

        // 2) Jar'a gömülü classpath:/static/index.html (yedek dağıtım biçimi)
        if (getClass().getResource("/static/index.html") != null) {
            log.info("[FRONTEND] ✅ Statik arayüz HAZIR — index.html classpath:/static/ içinde (jar'a gömülü). UI sunulabilir.");
            return;
        }

        // 3) Hiçbir yerde yok — UI sunulamaz
        log.warn("[FRONTEND] ⚠ Statik arayüz BULUNAMADI — index.html hiçbir konumda yok "
                + "(CWD={}, konumlar={}). Backend/API çalışır ancak UI istekleri 404 dönebilir. "
                + "Docker imajında frontend/dist kopyalandı mı / build başarılı mı kontrol edin.",
                cwd, locs);
    }

    // ── Test edilebilir saf yardımcılar ─────────────────────────────────────────

    /** Virgülle ayrılmış static-locations değerini temiz listeye çevirir. */
    static List<String> parseLocations(String staticLocations) {
        List<String> locs = new ArrayList<>();
        if (staticLocations == null) return locs;
        for (String raw : staticLocations.split(",")) {
            String loc = raw.trim();
            if (!loc.isEmpty()) locs.add(loc);
        }
        return locs;
    }

    /** İlk {@code file:} konumunda index.html varsa onu döner (classpath konumları atlanır). */
    static Optional<File> findFileIndex(String staticLocations) {
        for (String loc : parseLocations(staticLocations)) {
            if (!loc.startsWith("file:")) continue;
            File index = new File(loc.substring("file:".length()), "index.html");
            if (index.isFile()) return Optional.of(index);
        }
        return Optional.empty();
    }

    private static String safeAbs(File f) {
        try { return f.getAbsolutePath(); } catch (Exception e) { return f.getPath(); }
    }

    /** Dizindeki normal dosya sayısı (özyinelemeli); okunamazsa -1. */
    private static int countFiles(Path dir) {
        try (var s = Files.walk(dir)) {
            return (int) s.filter(Files::isRegularFile).count();
        } catch (Exception e) {
            return -1;
        }
    }
}
