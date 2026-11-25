package com.sitemonitor.service;

import com.sitemonitor.model.IncidentImage;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.IncidentImageRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * "Hızlı Giriş" (2026-11-20) — olay e-postasının görsel planı: tam çözünürlükte saklanan görsel ≤ 1000 px kopyayla gömülür,
 * toplam bütçe ({@code mail-inline-mb}) aşılırsa sığmayan görsel gömülmez (e-postada not olur); yalnız bu olayın görseli ve
 * önizlemede isteyenin taslağı; GIF çözülmeden gömülür ya da atlanır; WebP gömülmez. Önizleme gönderimle AYNI planı kullanır.
 */
class IncidentMailImagePlanTest {

    EmailNotificationService emailService;
    TeamRepository teamRepo;
    AppUserRepository userRepo;
    IncidentImageRepository imageRepo;
    AppSettingsService appSettings;
    NotificationGroupService groups;
    IncidentNotificationService service;

    @BeforeEach
    void setUp() {
        emailService = mock(EmailNotificationService.class);
        teamRepo = mock(TeamRepository.class);
        userRepo = mock(AppUserRepository.class);
        imageRepo = mock(IncidentImageRepository.class);
        appSettings = mock(AppSettingsService.class);
        groups = mock(NotificationGroupService.class);
        service = new IncidentNotificationService(emailService, teamRepo, userRepo, imageRepo, appSettings, groups);
        when(appSettings.getString(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
    }

    /** Düz desenli küçük PNG (iyi sıkışır). */
    static byte[] png(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, ((x / 8) % 2 == 0) ? 0x2266AA : 0xFFFFFF);
        return write(img, "png");
    }

    /** Rastgele gürültü (sıkışmaz) — bütçe testleri için. */
    static byte[] noisePng(int w, int h, long seed) {
        Random r = new Random(seed);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, r.nextInt(0xFFFFFF));
        return write(img, "png");
    }

    static byte[] write(BufferedImage img, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(img, format, out)) throw new IllegalStateException("yazıcı yok: " + format);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private IncidentImage image(long id, Long incidentId, String createdBy, byte[] data) {
        IncidentImage i = new IncidentImage();
        i.setId(id);
        i.setIncidentId(incidentId);
        i.setCreatedBy(createdBy);
        i.setContentType("image/png");
        i.setData(data);
        i.setSizeBytes((long) data.length);
        when(imageRepo.findById(id)).thenReturn(Optional.of(i));
        return i;
    }

    private static Map<String, Object> dto(Long id, String description) {
        Map<String, Object> m = new HashMap<>();
        if (id != null) m.put("id", id);
        m.put("title", "Ödeme");
        m.put("description", description);
        return m;
    }

    @Test
    @DisplayName("büyük görsel ≤ 1000 px kopyaya küçültülür; kutuya sığan PNG olduğu gibi (aynı baytlar) gömülür")
    void resizesLargeKeepsSmall() throws Exception {
        image(1, 9L, "sre1", png(2400, 1200));
        byte[] small = png(40, 30);
        image(2, 9L, "sre1", small);
        IncidentNotificationService.MailImagePlan plan = service.mailImagePlan(
                dto(9L, "![a](/api/incidents/images/1)\n![b](/api/incidents/images/2)"), null);
        assertThat(plan.embedded()).containsExactly(1L, 2L);
        assertThat(plan.inline()).extracting(EmailNotificationService.InlineImage::cid).containsExactly("incimg1", "incimg2");
        BufferedImage reduced = ImageIO.read(new ByteArrayInputStream(plan.inline().get(0).data()));
        assertThat(reduced.getWidth()).isEqualTo(1000);
        assertThat(reduced.getHeight()).isEqualTo(500);
        assertThat(plan.inline().get(1).data()).isSameAs(small);
    }

    @Test
    @DisplayName("toplam bütçe: sığmayan görsel gömülmez (sıradaki küçük olan yine sığabilir)")
    void budgetSkipsWhatDoesNotFit() {
        ReflectionTestUtils.setField(service, "mailInlineMb", 1);
        byte[] big = noisePng(560, 560, 1);      // ≈ 940 KB, gürültü sıkışmaz
        assertThat(big.length).isBetween(600_000, 1_048_576);
        image(1, 9L, "u", big);
        image(2, 9L, "u", noisePng(560, 560, 2));
        image(3, 9L, "u", png(20, 20));
        IncidentNotificationService.MailImagePlan plan = service.mailImagePlan(dto(9L,
                "![a](/api/incidents/images/1) ![b](/api/incidents/images/2) ![c](/api/incidents/images/3)"), null);
        assertThat(plan.embedded()).containsExactly(1L, 3L);
        long total = plan.inline().stream().mapToLong(i -> i.data().length).sum();
        assertThat(total).isLessThanOrEqualTo(1024L * 1024L);
    }

    @Test
    @DisplayName("yalnız bu olayın görseli; taslak yalnız önizlemede ve yalnız isteyenin (ad büyük/küçük harf duyarsız)")
    void eligibility() {
        image(5, 1L, "sre1", png(20, 20));        // bu olay
        image(6, 2L, "sre1", png(20, 20));        // başka olay
        image(7, null, "SRE1", png(20, 20));      // sre1'in taslağı
        image(8, null, "sre2", png(20, 20));      // başkasının taslağı
        String md = "![a](/api/incidents/images/5) ![b](/api/incidents/images/6) ![c](/api/incidents/images/7) ![d](/api/incidents/images/8)";
        assertThat(service.mailImagePlan(dto(1L, md), null).embedded()).containsExactly(5L);
        assertThat(service.mailImagePlan(dto(1L, md), "sre1").embedded()).containsExactly(5L, 7L);
        assertThat(service.mailImagePlan(dto(null, md), "sre1").embedded()).containsExactly(7L);
    }

    @Test
    @DisplayName("GIF çözülmez: kutuya sığan olduğu gibi (image/gif) gömülür, büyüğü atlanır; WebP gömülmez")
    void gifAndWebp() {
        BufferedImage g = new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB);
        byte[] gif = write(g, "gif");
        image(1, 9L, "u", gif);
        BufferedImage wide = new BufferedImage(1200, 10, BufferedImage.TYPE_INT_RGB);
        image(2, 9L, "u", write(wide, "gif"));
        ByteBuffer b = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(22).put("WEBP".getBytes(StandardCharsets.US_ASCII));
        b.put("VP8L".getBytes(StandardCharsets.US_ASCII)).putInt(10).put((byte) 0x2F).putInt((99) | (49 << 14));
        image(3, 9L, "u", b.array());
        IncidentNotificationService.MailImagePlan plan = service.mailImagePlan(dto(9L,
                "![a](/api/incidents/images/1) ![b](/api/incidents/images/2) ![c](/api/incidents/images/3)"), null);
        assertThat(plan.embedded()).containsExactly(1L);
        assertThat(plan.inline().get(0).contentType()).isEqualTo("image/gif");
        assertThat(plan.inline().get(0).data()).isSameAs(gif);
    }

    @Test
    @DisplayName("bütçe 0 ya da görsel referansı yok → boş plan, depo hiç okunmaz")
    void emptyPlans() {
        ReflectionTestUtils.setField(service, "mailInlineMb", 0);
        assertThat(service.mailImagePlan(dto(9L, "![a](/api/incidents/images/1)"), null).embedded()).isEmpty();
        ReflectionTestUtils.setField(service, "mailInlineMb", 6);
        assertThat(service.mailImagePlan(dto(9L, "metin, görsel yok"), null).inline()).isEmpty();
        assertThat(service.mailImagePlan(null, null).inline()).isEmpty();
        verifyNoInteractions(imageRepo);
    }

    @Test
    @DisplayName("önizleme gönderimle AYNI planı kurucuya verir (gömülen kimlik kümesi)")
    void previewUsesSamePlan() {
        image(5, 9L, "sre1", png(20, 20));
        when(emailService.buildIncidentNotificationHtml(anyMap(), any(), anyString(), anyString(), anyBoolean(), any()))
                .thenReturn("<html/>");
        service.previewHtml(dto(9L, "![a](/api/incidents/images/5) ![b](/api/incidents/images/77)"), "NEW", "sre1");
        verify(emailService).buildIncidentNotificationHtml(anyMap(), any(), eq("NEW"), anyString(), eq(false), eq(Set.of(5L)));
    }
}
