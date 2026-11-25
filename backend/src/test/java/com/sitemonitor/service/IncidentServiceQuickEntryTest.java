package com.sitemonitor.service;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.IncidentImage;
import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.repository.IncidentImageRepository;
import com.sitemonitor.repository.IncidentRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "Hızlı Giriş" paketi (2026-11-20) — sunucu kuralları, H2'de gerçek servis + depo ile: runbook bağlantısı (yalnız http(s),
 * yalnız DEĞİŞEN değer denetlenir), görsel türünün BAYTLARDAN belirlenmesi ve kayıt başına görsel toplamı (olaya bağlı
 * görseller / kullanıcının son 24 saatteki taslakları). H2'de görsel kolonu kısa olduğundan testler küçük PNG kullanır.
 */
@DataJpaTest
@Import(IncidentService.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class IncidentServiceQuickEntryTest {

    @Autowired IncidentService service;
    @Autowired IncidentRecordRepository repo;
    @Autowired IncidentImageRepository images;

    private static Map<String, Object> base(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", "Ödeme gecikmesi");
        m.put("occurred_at", "2026-11-18T10:00:00");
        m.put("severity", "HIGH");
        m.put("status", "OPEN");
        m.put("category", "APPLICATION");
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static byte[] tinyPng(int rgb) throws Exception {
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, rgb);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private IncidentService target() {
        return AopTestUtils.getTargetObject(service);
    }

    // ── Runbook bağlantısı ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("runbook: saf kural — http(s) + sunucu adı; boşluk, başka şema, sunucusuz adres reddedilir")
    void runbookRule() {
        for (String ok : new String[]{"https://wiki.example.com/runbook/odeme", "http://wiki/x", "HTTPS://Wiki.Example.com",
                "https://wiki_internal:8443/a?b=c#d", "https://confluence.example.com/display/SRE/Ödeme+Runbook"}) {
            assertThat(IncidentService.runbookUrlProblem(ok)).as(ok).isFalse();
        }
        for (String bad : new String[]{"javascript:alert(1)", "data:text/html,x", "file:///etc/passwd", "ftp://x/y",
                "wiki.example.com/runbook", "https://", "https:///path", "https://a b/c", "https://x/\ty", "//wiki/x",
                "https://wiki/x<y>", "https://wiki/a\u00a0b", "https://wiki/x\u2007y", "https://wiki/x\u202fy",
                "https://wiki/x#a#b"}) {
            assertThat(IncidentService.runbookUrlProblem(bad)).as(bad).isTrue();
        }
    }

    @Test
    @DisplayName("runbook: yeni kayıtta geçersiz adres alan adlı 400; geçerlisi kırpılarak yazılır; boş → null")
    void runbookOnCreate() {
        assertThatThrownBy(() -> service.create(base("runbook_url", "javascript:alert(1)"), "sre1", 1L, 1L))
                .isInstanceOfSatisfying(FieldValidationException.class, ex -> {
                    assertThat(ex.getFields()).containsOnlyKeys("runbook_url");
                    assertThat(ex.getMessage()).contains("https://").hasSizeGreaterThan(40);
                });
        IncidentRecord ok = service.create(base("runbook_url", "  https://wiki.example.com/rb  "), "sre1", 1L, 1L);
        assertThat(ok.getRunbookUrl()).isEqualTo("https://wiki.example.com/rb");
        IncidentRecord none = service.create(base("runbook_url", "   "), "sre1", 1L, 1L);
        assertThat(none.getRunbookUrl()).isNull();
    }

    @Test
    @DisplayName("runbook: yalnız DEĞİŞEN değer denetlenir — kayıtlı eski (kurala uymayan) değer başka alanın düzenlemesini kilitlemez")
    void runbookOnlyChangedValidated() {
        IncidentRecord r = service.create(base(), "sre1", 1L, 1L);
        // Eski sürümün yazdığı (http olmayan) değer veritabanında
        r.setRunbookUrl("wiki/runbook");
        r = repo.saveAndFlush(r);
        Long id = r.getId();
        IncidentRecord edited = service.update(id, body("runbook_url", "wiki/runbook", "title", "Yeni başlık"), "sre1");
        assertThat(edited.getTitle()).isEqualTo("Yeni başlık");
        assertThat(edited.getRunbookUrl()).isEqualTo("wiki/runbook");
        assertThatThrownBy(() -> service.update(id, body("runbook_url", "wiki/baska"), "sre1"))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        ex -> assertThat(ex.getFields()).containsOnlyKeys("runbook_url"));
        IncidentRecord cleared = service.update(id, body("runbook_url", null), "sre1");
        assertThat(cleared.getRunbookUrl()).isNull();
    }

    @Test
    @DisplayName("runbook: 500 karakteri aşan adres uzunluk hatası (kolon sınırı), biçim hatası değil")
    void runbookTooLong() {
        String longUrl = "https://wiki.example.com/" + "a".repeat(600);
        assertThatThrownBy(() -> service.create(base("runbook_url", longUrl), "sre1", 1L, 1L))
                .isInstanceOfSatisfying(FieldValidationException.class, ex -> assertThat(ex.getMessage()).contains("500"));
    }

    // ── Görseller ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("görsel: tür BAYTLARDAN — PNG baytı 'image/jpeg' başlığıyla gelse de image/png saklanır; SVG 'image/png' başlığıyla reddedilir")
    void imageTypeFromBytes() throws Exception {
        byte[] png = tinyPng(0x112233);
        IncidentImage img = service.storeImage(null, "  ekran  ", new MockMultipartFile("file", "a.jpg", "image/jpeg", png), "sre1");
        assertThat(img.getContentType()).isEqualTo("image/png");
        assertThat(img.getCaption()).isEqualTo("ekran");
        assertThat(img.getSizeBytes()).isEqualTo((long) png.length);
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.storeImage(null, null, new MockMultipartFile("file", "a.png", "image/png", svg), "sre1"))
                .isInstanceOfSatisfying(FieldValidationException.class, ex -> assertThat(ex.getFields()).containsOnlyKeys("file"));
        assertThatThrownBy(() -> service.storeImage(null, null, new MockMultipartFile("file", "a.png", "image/png", new byte[0]), "sre1"))
                .isInstanceOf(FieldValidationException.class);
    }

    @Test
    @DisplayName("görsel toplamı: olaya bağlı görseller + yeni dosya sınırı aşamaz → 400 INCIDENT_IMAGE_TOTAL_LIMIT (alan 'file')")
    void perRecordTotalCap() throws Exception {
        IncidentRecord r = service.create(base(), "sre1", 1L, 1L);
        byte[] a = tinyPng(0x010203);
        ReflectionTestUtils.setField(target(), "imageTotalMaxBytes", 2L * a.length);
        service.storeImage(r.getId(), null, new MockMultipartFile("file", "a.png", "image/png", a), "sre1");
        service.storeImage(r.getId(), null, new MockMultipartFile("file", "b.png", "image/png", a), "sre1");
        assertThat(images.sumSizeByIncident(r.getId())).isEqualTo(2L * a.length);
        assertThatThrownBy(() -> service.storeImage(r.getId(), null, new MockMultipartFile("file", "c.png", "image/png", a), "sre1"))
                .isInstanceOfSatisfying(FieldValidationException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INCIDENT_IMAGE_TOTAL_LIMIT");
                    assertThat(ex.getFields()).containsOnlyKeys("file");
                    assertThat(ex.getMessage()).contains("MB");
                });
        // Başka kaydın görselleri bu kaydın sınırına sayılmaz
        IncidentRecord other = service.create(base(), "sre1", 1L, 1L);
        service.storeImage(other.getId(), null, new MockMultipartFile("file", "d.png", "image/png", a), "sre1");
    }

    private static String ago(long seconds) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC).format(Instant.now().minusSeconds(seconds));
    }

    private void age(Long imageId, long seconds) {
        IncidentImage i = images.findById(imageId).orElseThrow();
        i.setCreatedAt(ago(seconds));
        images.save(i);
        images.flush();
    }

    @Test
    @DisplayName("görsel toplamı: metinde GEÇEN bağlı görsel (eski de olsa) sayılır; metinden çıkarılmış ve 24 saati geçmiş kullanılmamış görsel sayılmaz")
    void perRecordTotalCountsOnlyUsedOrRecent() throws Exception {
        IncidentRecord r = service.create(base(), "sre1", 1L, 1L);
        byte[] a = tinyPng(0x0C0D0E);
        ReflectionTestUtils.setField(target(), "imageTotalMaxBytes", 2L * a.length);
        IncidentImage used = service.storeImage(r.getId(), null, new MockMultipartFile("file", "a.png", "image/png", a), "sre1");
        IncidentImage dropped = service.storeImage(r.getId(), null, new MockMultipartFile("file", "b.png", "image/png", a), "sre1");
        // ilki metinde (eski), ikincisi metinden çıkarılmış (eski) → yalnız ilki sayılır
        service.update(r.getId(), body("description", "Grafik ![g](/api/incidents/images/" + used.getId() + ")"), "sre1");
        age(used.getId(), 3 * 24 * 3600);
        age(dropped.getId(), 25 * 3600);
        IncidentImage third = service.storeImage(r.getId(), null, new MockMultipartFile("file", "c.png", "image/png", a), "sre1");
        // şimdi: kullanılan (eski) + üçüncü (24 saat içinde, henüz metinde değil) = sınır → dördüncü reddedilir
        assertThatThrownBy(() -> service.storeImage(r.getId(), null, new MockMultipartFile("file", "d.png", "image/png", a), "sre1"))
                .isInstanceOfSatisfying(FieldValidationException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INCIDENT_IMAGE_TOTAL_LIMIT");
                    assertThat(ex.getMessage()).contains("24");
                });
        // kullanılmayan üçüncü 24 saati geçince sayılmaz → yükleme yine yapılabilir
        age(third.getId(), 25 * 3600);
        service.storeImage(r.getId(), null, new MockMultipartFile("file", "e.png", "image/png", a), "sre1");
    }

    @Test
    @DisplayName("görsel toplamı: metin çıkarılıp kaydedilince eski görsel artık sayılmaz (kayıt kalıcı kilitlenmez)")
    void removedFromTextStopsCounting() throws Exception {
        IncidentRecord r = service.create(base(), "sre1", 1L, 1L);
        byte[] a = tinyPng(0x0E0F10);
        ReflectionTestUtils.setField(target(), "imageTotalMaxBytes", 1L * a.length);
        IncidentImage i1 = service.storeImage(r.getId(), null, new MockMultipartFile("file", "a.png", "image/png", a), "sre1");
        service.update(r.getId(), body("description", "![a](/api/incidents/images/" + i1.getId() + ")"), "sre1");
        age(i1.getId(), 2 * 24 * 3600);
        assertThatThrownBy(() -> service.storeImage(r.getId(), null, new MockMultipartFile("file", "b.png", "image/png", a), "sre1"))
                .isInstanceOf(FieldValidationException.class);
        service.update(r.getId(), body("description", "metin"), "sre1");
        service.storeImage(r.getId(), null, new MockMultipartFile("file", "b.png", "image/png", a), "sre1");
    }

    @Test
    @DisplayName("görsel yükleme: olay satırı toplam denetiminden ÖNCE kilitlenir (eşzamanlı iki yükleme birlikte sınırı aşamaz); kayıt yoksa 404, kilit sorgusu yine çalışır")
    void uploadLocksTheIncidentRowFirst() throws Exception {
        IncidentRecordRepository recRepo = org.mockito.Mockito.mock(IncidentRecordRepository.class);
        IncidentImageRepository imgRepo = org.mockito.Mockito.mock(IncidentImageRepository.class);
        IncidentService svc = new IncidentService(recRepo, imgRepo, null);
        ReflectionTestUtils.setField(svc, "imageMaxBytes", 5L * 1024 * 1024);   // elle kurulan nesnede @Value yok
        IncidentRecord rec = new IncidentRecord();
        rec.setId(7L);
        rec.setDescription("![a](/api/incidents/images/5)");
        org.mockito.Mockito.when(recRepo.findById(7L)).thenReturn(java.util.Optional.of(rec));
        org.mockito.Mockito.when(imgRepo.sumCountedForIncident(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.anyCollection(), org.mockito.ArgumentMatchers.anyString())).thenReturn(0L);
        org.mockito.Mockito.when(imgRepo.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));
        svc.storeImage(7L, null, new MockMultipartFile("file", "a.png", "image/png", tinyPng(0x123456)), "sre1");
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(recRepo, imgRepo);
        order.verify(recRepo).lockRow(7L);
        order.verify(imgRepo).sumCountedForIncident(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.argThat(ids -> ids.contains(5L)), org.mockito.ArgumentMatchers.anyString());
        order.verify(imgRepo).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("taslak toplamı: kullanıcının son 24 saatteki BAĞLANMAMIŞ taslakları sayılır (ad büyük/küçük harf duyarsız); eskisi ve başkasınınki sayılmaz")
    void draftTotalCap() throws Exception {
        byte[] a = tinyPng(0x0A0B0C);
        ReflectionTestUtils.setField(target(), "imageTotalMaxBytes", 2L * a.length);
        service.storeImage(null, null, new MockMultipartFile("file", "a.png", "image/png", a), "sre1");
        service.storeImage(null, null, new MockMultipartFile("file", "b.png", "image/png", a), "SRE1");
        assertThatThrownBy(() -> service.storeImage(null, null, new MockMultipartFile("file", "c.png", "image/png", a), "sre1"))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("INCIDENT_IMAGE_TOTAL_LIMIT"));
        // Başka kullanıcının taslağı ayrı sayılır
        service.storeImage(null, null, new MockMultipartFile("file", "x.png", "image/png", a), "sre2");
        // 24 saatten eski taslaklar sayılmaz
        String old = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)
                .format(Instant.now().minusSeconds(25 * 3600));
        for (IncidentImage i : images.findAll()) {
            if ("sre1".equalsIgnoreCase(i.getCreatedBy())) { i.setCreatedAt(old); images.save(i); }
        }
        images.flush();
        assertThat(images.sumDraftSizeByCreator("SRE1", DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                .withZone(ZoneOffset.UTC).format(Instant.now().minusSeconds(24 * 3600)))).isZero();
        service.storeImage(null, null, new MockMultipartFile("file", "d.png", "image/png", a), "sre1");
    }
}
