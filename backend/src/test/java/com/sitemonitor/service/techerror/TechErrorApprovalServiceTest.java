package com.sitemonitor.service.techerror;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TechErrorApprovalToken;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TechErrorApprovalTokenRepository;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.repository.TechErrorReportEventRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PO onayı (2026-10-17, S6) — H2 üzerinde gerçek koşullu JPQL: istek / onay / red geçişleri, eskimiş istek, belirteçler
 * (özet, tek kullanım, yeni istekte eskiler ölür), onaylayıcı kümesi (aktif PO üye — birincil ya da ek üyelik), postalar
 * (onaylayıcı başına bağlantı, isteyene karar) ve onay sonrası yöneticilere gönderim.
 */
@DataJpaTest
@Import({TechErrorReportService.class, TechErrorHistoryService.class, TechErrorApprovalService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TechErrorApprovalServiceTest {

    @Autowired TechErrorReportService service;
    @Autowired TechErrorApprovalService approvals;
    @Autowired TechErrorChannelRepository channels;
    @Autowired TechErrorReportRepository reports;
    @Autowired TechErrorApprovalTokenRepository tokens;
    @Autowired TechErrorReportEventRepository events;
    @Autowired AppUserRepository users;
    @Autowired NotificationLogRepository logs;
    @Autowired com.sitemonitor.repository.TechErrorReportImageRepository images;
    @Autowired com.sitemonitor.repository.TechErrorTemplateVersionRepository versions;
    @Autowired TechErrorHistoryService history;

    @MockitoBean TechErrorReportSender sender;
    @MockitoBean EmailNotificationService email;
    @MockitoBean AppSettingsService appSettings;

    private static final Pattern LINK = Pattern.compile("approve-link\\?token=([A-Za-z0-9_-]+)");

    private Team team;
    private AppUser po1, po2, tech, passivePo;

    @BeforeEach
    void setUp() {
        tokens.deleteAll();
        events.deleteAll();
        versions.deleteAll();
        images.deleteAll();
        reports.deleteAll();
        channels.deleteAll();
        logs.deleteAll();
        users.deleteAll();
        service.useClock(Clock.fixed(Instant.parse("2026-10-13T09:00:00Z"), ZoneOffset.UTC));
        when(appSettings.getString(eq("site.monitor.app.base-url"), any())).thenReturn("https://sm.example/");
        when(email.sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), anyBoolean(), any())).thenReturn("SENT");
        when(sender.maxMailBytes()).thenReturn(15L * 1024 * 1024);
        team = new Team();
        team.setId(1L);
        team.setName("Dijital");
        team.setEmail("dijital@example.com");
        po1 = user("po1", "PO Bir", "po1@example.com", "PO", 1L, Set.of(1L), true);
        po2 = user("po2", "PO İki", "po2@example.com", "PO", 9L, Set.of(9L, 1L), true);   // ek üyelikle takımda
        tech = user("tech", "Teknik", "tech@example.com", "TECH", 1L, Set.of(1L), true);
        passivePo = user("po3", "Pasif PO", "po3@example.com", "PO", 1L, Set.of(1L), false);
        user("po-other", "Başka takım PO", "other@example.com", "PO", 2L, Set.of(2L), true);
    }

    private AppUser user(String name, String display, String mail, String role, Long teamId, Set<Long> teamIds, boolean active) {
        AppUser u = new AppUser();
        u.setUsername(name);
        u.setDisplayName(display);
        u.setEmail(mail);
        u.setOrgRole(role);
        u.setTeamId(teamId);
        u.setTeamIds(new LinkedHashSet<>(teamIds));
        u.setActive(active);
        return users.save(u);
    }

    private int seq = 0;

    private TechErrorChannel channelWithExecs(String execs) {
        TechErrorChannel ch = service.createChannel(1L, seq++ == 0 ? "Mobil" : "Mobil " + seq, null, TechErrorTemplate.defaults("tr"), tech.getId());
        if (execs != null) service.updateChannel(ch.getId(), Map.of("executive_emails", execs), tech.getId());
        return service.channel(ch.getId());
    }

    @Test
    @DisplayName("onaylayıcılar: aktif PO üyeler (birincil ya da ek üyelik); TECH, pasif PO ve başka takımın PO'su değil; akış yalnız yönetici alıcısı varken")
    void approversAndRequirement() {
        assertThat(approvals.approvers(1L)).extracting(TechErrorApprovalService.Approver::name).containsExactly("PO Bir", "PO İki");
        assertThat(approvals.isApprover(po1.getId(), 1L)).isTrue();
        assertThat(approvals.isApprover(po2.getId(), 1L)).isTrue();
        assertThat(approvals.isApprover(tech.getId(), 1L)).isFalse();
        assertThat(approvals.isApprover(passivePo.getId(), 1L)).isFalse();
        assertThat(approvals.isApprover(null, 1L)).isFalse();
        assertThat(TechErrorApprovalService.approvalRequired(channelWithExecs(null))).isFalse();
        TechErrorChannel ch = channelWithExecs("ceo@example.com; cto@example.com, ceo@example.com");
        assertThat(ch.getExecutiveEmails()).isEqualTo("ceo@example.com, cto@example.com");
        assertThat(TechErrorApprovalService.approvalRequired(ch)).isTrue();
    }

    @Test
    @DisplayName("kanal yönetici alıcıları: geçersiz adres 400 (alan), ≤ 20, tekilleşir ve normalize edilir; boş = akış yok")
    void executiveEmailsValidation() {
        TechErrorChannel ch = service.createChannel(1L, "Mobil", null, TechErrorTemplate.defaults("tr"), tech.getId());
        assertThatThrownBy(() -> service.updateChannel(ch.getId(), Map.of("executive_emails", "ceo@example.com, bozuk"), tech.getId()))
                .isInstanceOf(FieldValidationException.class);
        service.updateChannel(ch.getId(), Map.of("executive_emails", " ceo@example.com ;CTO@example.com, ceo@example.com "), tech.getId());
        assertThat(service.channel(ch.getId()).getExecutiveEmails()).isEqualTo("ceo@example.com, CTO@example.com");
        assertThat(TechErrorReportSender.executiveRecipients(service.channel(ch.getId()))).containsExactly("ceo@example.com", "CTO@example.com");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 21; i++) many.append("e").append(i).append("@example.com,");
        assertThatThrownBy(() -> service.updateChannel(ch.getId(), Map.of("executive_emails", many.toString()), tech.getId()))
                .isInstanceOf(FieldValidationException.class);
        service.updateChannel(ch.getId(), Map.of("executive_emails", ""), tech.getId());
        assertThat(service.channel(ch.getId()).getExecutiveEmails()).isNull();
        assertThat(TechErrorApprovalService.approvalRequired(service.channel(ch.getId()))).isFalse();
    }

    @Test
    @DisplayName("istek: alıcısız kanal 409 EXEC_RECIPIENTS_MISSING; PO'suz takım 409 NO_APPROVER; başarı → PENDING, rev bağlandı, PO başına özetli belirteç + bağlantılı posta + günlük, geçmiş olayı; aynı içerik için ikinci istek 409 APPROVAL_PENDING")
    void request() {
        TechErrorChannel plain = channelWithExecs(null);
        TechErrorReport r0 = service.createReport(plain, 2026, 9, tech.getId());
        assertThatThrownBy(() -> approvals.request(r0, plain, team, tech.getId()))
                .isInstanceOf(CodedConflictException.class).extracting(e -> ((CodedConflictException) e).getCode()).isEqualTo("EXEC_RECIPIENTS_MISSING");

        TechErrorChannel ch = channelWithExecs("ceo@example.com");
        TechErrorReport r = service.createReport(ch, 2026, 9, tech.getId());
        Team noPo = new Team();
        noPo.setId(2L);
        noPo.setName("Yalnız");
        users.findAll().forEach(u -> { if (u.getTeamId() == 2L) users.delete(u); });
        assertThatThrownBy(() -> approvals.request(r, ch, noPo, tech.getId()))
                .isInstanceOf(CodedConflictException.class).extracting(e -> ((CodedConflictException) e).getCode()).isEqualTo("NO_APPROVER");

        TechErrorApprovalService.RequestResult res = approvals.request(r, ch, team, tech.getId());
        assertThat(res.approvers()).hasSize(2);
        assertThat(res.mailed()).isEqualTo(2);
        assertThat(res.failed()).isZero();
        assertThat(res.mailProblem()).isNull();
        TechErrorReport after = service.report(r.getId());
        assertThat(after.getApprovalState()).isEqualTo("PENDING");
        assertThat(after.getApprovalRev()).isEqualTo(after.getContentRev());
        assertThat(after.getApprovalRequestedById()).isEqualTo(tech.getId());
        List<TechErrorApprovalToken> toks = tokens.findByReportIdOrderByIdAsc(r.getId());
        assertThat(toks).hasSize(2);
        assertThat(toks).allSatisfy(t -> {
            assertThat(t.getTokenHash()).hasSize(64);
            assertThat(t.getUsedAt()).isNull();
            assertThat(t.getExpiresAt()).isEqualTo(service.now().plus(TechErrorApprovalService.TOKEN_TTL));
        });
        // Postalar: PO başına bir posta, kendi bağlantısıyla, PDF ekli, "[Onay bekliyor]" konu; ham belirteç DB'de yok
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<EmailNotificationService.MailAttachment>> att = ArgumentCaptor.forClass(List.class);
        verify(email, times(2)).sendHtml(to.capture(), any(), subject.capture(), html.capture(), anyString(), any(), eq(false), att.capture());
        assertThat(to.getAllValues()).extracting(a -> a[0]).containsExactlyInAnyOrder("po1@example.com", "po2@example.com");
        assertThat(subject.getAllValues()).allMatch(s -> s.startsWith("[Onay bekliyor] Mobil"));
        assertThat(att.getAllValues()).allSatisfy(l -> assertThat(l.get(0).fileName()).endsWith(".pdf"));
        List<String> raws = html.getAllValues().stream().map(h -> {
            Matcher m = LINK.matcher(h);
            assertThat(m.find()).as("bağlantı").isTrue();
            return m.group(1);
        }).toList();
        assertThat(raws).hasSize(2).doesNotHaveDuplicates();
        for (String raw : raws) {
            assertThat(toks).extracting(TechErrorApprovalToken::getTokenHash).contains(TechErrorApprovalService.hash(raw))
                    .doesNotContain(raw);
            assertThat(approvals.status(raw).valid()).isTrue();
        }
        assertThat(html.getAllValues().get(0)).contains("data-slot=\"te-mail-band\"").contains("Onayınız bekleniyor");
        List<NotificationLog> rows = logs.findAll();
        assertThat(rows).hasSize(2).allMatch(n -> TechErrorApprovalService.TRIGGER.equals(n.getTrigger()) && "SENT".equals(n.getEmailStatus()));
        assertThat(events.findAll()).extracting(e -> e.getKind()).contains("APPROVAL_REQUEST");
        assertThatThrownBy(() -> approvals.request(service.report(r.getId()), ch, team, tech.getId()))
                .isInstanceOf(CodedConflictException.class).extracting(e -> ((CodedConflictException) e).getCode()).isEqualTo("APPROVAL_PENDING");
        // İçerik değişti → yeni istek: eski belirteçler ölür
        reports.touchContent(r.getId(), service.now());
        approvals.request(service.report(r.getId()), ch, team, tech.getId());
        for (String raw : raws) assertThat(approvals.status(raw).reason()).isEqualTo("not_found");
        assertThat(tokens.findByReportIdOrderByIdAsc(r.getId())).hasSize(2);
    }

    @Test
    @DisplayName("karar: PO olmayan 403; onay → APPROVED, belirteçler kapanır, yöneticilere EXECUTIVE gönderim, isteyene '[Onaylandı]' posta; değişmiş içerik 409 APPROVAL_STALE; bekleyen istek yokken 409")
    void approve() {
        TechErrorChannel ch = channelWithExecs("ceo@example.com");
        TechErrorReport r = service.createReport(ch, 2026, 9, tech.getId());
        assertThatThrownBy(() -> approvals.decide(r, ch, team, po1.getId(), true, null, "app"))
                .isInstanceOf(CodedConflictException.class).extracting(e -> ((CodedConflictException) e).getCode()).isEqualTo("APPROVAL_NOT_PENDING");
        approvals.request(r, ch, team, tech.getId());
        assertThatThrownBy(() -> approvals.decide(service.report(r.getId()), ch, team, tech.getId(), true, null, "app"))
                .isInstanceOf(SecurityException.class);
        when(sender.send(any(), any(), any(), any(), eq(TechErrorReportSender.Delivery.EXECUTIVE)))
                .thenReturn(new TechErrorReportSender.Result("SENT", "SENT", List.of("ceo@example.com"), 100, "Konu"));
        TechErrorApprovalService.Decision d = approvals.decide(service.report(r.getId()), ch, team, po2.getId(), true, "Uygun", "app");
        assertThat(d.state()).isEqualTo("APPROVED");
        assertThat(d.send().state()).isEqualTo("SENT");
        assertThat(d.sendError()).isNull();
        TechErrorReport after = service.report(r.getId());
        assertThat(after.getApprovalState()).isEqualTo("APPROVED");
        assertThat(after.getApprovalDecidedById()).isEqualTo(po2.getId());
        assertThat(after.getApprovalNote()).isEqualTo("Uygun");
        assertThat(tokens.findByReportIdOrderByIdAsc(r.getId())).allMatch(t -> t.getUsedAt() != null);
        verify(sender).send(any(), eq(ch), eq(team), eq(po2.getId()), eq(TechErrorReportSender.Delivery.EXECUTIVE));
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(email, times(3)).sendHtml(to.capture(), any(), subject.capture(), anyString(), any(), any(), eq(false), any());
        assertThat(subject.getAllValues().get(2)).startsWith("[Onaylandı] Mobil Kanalı");
        assertThat(to.getAllValues().get(2)[0]).isEqualTo("tech@example.com");
        assertThat(events.findAll()).extracting(e -> e.getKind()).contains("APPROVAL_REQUEST", "APPROVAL");
        // Eskimiş istek: içerik değişti → onaylanamaz
        TechErrorReport r2 = service.createReport(ch, 2026, 8, tech.getId());
        approvals.request(r2, ch, team, tech.getId());
        reports.touchContent(r2.getId(), service.now());
        assertThatThrownBy(() -> approvals.decide(service.report(r2.getId()), ch, team, po1.getId(), true, null, "app"))
                .isInstanceOf(CodedConflictException.class).extracting(e -> ((CodedConflictException) e).getCode()).isEqualTo("APPROVAL_STALE");
        assertThat(service.report(r2.getId()).getApprovalState()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("onay sonrası gönderim başarısız: onay KALIR (APPROVED), sendError kodu döner, isteyene 'henüz gönderilmedi' postası; red: not zorunlu, REJECTED + not, '[Reddedildi]' posta; değişmiş içerik yine reddedilebilir")
    void approveSendFailureAndReject() {
        TechErrorChannel ch = channelWithExecs("ceo@example.com");
        TechErrorReport r = service.createReport(ch, 2026, 9, tech.getId());
        approvals.request(r, ch, team, tech.getId());
        when(sender.send(any(), any(), any(), any(), eq(TechErrorReportSender.Delivery.EXECUTIVE)))
                .thenThrow(new CodedConflictException("PDF_TOO_LARGE", "büyük"));
        TechErrorApprovalService.Decision d = approvals.decide(service.report(r.getId()), ch, team, po1.getId(), true, null, "email_token");
        assertThat(d.state()).isEqualTo("APPROVED");
        assertThat(d.send()).isNull();
        assertThat(d.sendError()).isEqualTo("PDF_TOO_LARGE");
        assertThat(service.report(r.getId()).getApprovalState()).isEqualTo("APPROVED");
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email, times(3)).sendHtml(any(String[].class), any(), anyString(), html.capture(), any(), any(), eq(false), any());
        assertThat(html.getAllValues().get(2)).contains("Henüz gönderilmedi").contains("PDF_TOO_LARGE");

        TechErrorReport r2 = service.createReport(ch, 2026, 8, tech.getId());
        approvals.request(r2, ch, team, tech.getId());
        assertThatThrownBy(() -> approvals.decide(service.report(r2.getId()), ch, team, po1.getId(), false, "  ", "app"))
                .isInstanceOf(FieldValidationException.class);
        reports.touchContent(r2.getId(), service.now());        // değişmiş içerik de reddedilir
        TechErrorApprovalService.Decision rej = approvals.decide(service.report(r2.getId()), ch, team, po1.getId(), false, "Rakamlar eksik", "app");
        assertThat(rej.state()).isEqualTo("REJECTED");
        assertThat(rej.send()).isNull();
        TechErrorReport after = service.report(r2.getId());
        assertThat(after.getApprovalState()).isEqualTo("REJECTED");
        assertThat(after.getApprovalNote()).isEqualTo("Rakamlar eksik");
        verify(sender, never()).send(any(), any(), any(), any(), eq(TechErrorReportSender.Delivery.TEAM));
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(email, times(6)).sendHtml(any(String[].class), any(), subject.capture(), anyString(), any(), any(), eq(false), any());
        assertThat(subject.getAllValues().get(5)).startsWith("[Reddedildi] Mobil Kanalı");
        // Reddedilen rapor yeniden istenebilir
        approvals.request(service.report(r2.getId()), ch, team, tech.getId());
        assertThat(service.report(r2.getId()).getApprovalState()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("bağlantı: geçersiz / kullanılmış / süresi dolmuş / eskimiş / karar verilmiş / onaylayıcı pasif sebepleri; tek kullanım — ikinci tıklama 'used'; karar e-posta belirteciyle")
    void tokenFlow() {
        TechErrorChannel ch = channelWithExecs("ceo@example.com");
        TechErrorReport r = service.createReport(ch, 2026, 9, tech.getId());
        approvals.request(r, ch, team, tech.getId());
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(email, times(2)).sendHtml(to.capture(), any(), anyString(), html.capture(), any(), any(), eq(false), any());
        String rawPo1 = null, rawPo2 = null;
        for (int i = 0; i < 2; i++) {
            Matcher m = LINK.matcher(html.getAllValues().get(i));
            assertThat(m.find()).isTrue();
            if (to.getAllValues().get(i)[0].startsWith("po1")) rawPo1 = m.group(1); else rawPo2 = m.group(1);
        }
        assertThat(approvals.status("yok").reason()).isEqualTo("not_found");
        assertThat(approvals.status(null).reason()).isEqualTo("not_found");
        TechErrorApprovalService.TokenStatus ok = approvals.status(rawPo1);
        assertThat(ok.valid()).isTrue();
        assertThat(ok.user().getId()).isEqualTo(po1.getId());
        assertThat(ok.report().getId()).isEqualTo(r.getId());
        // Onaylayıcı pasifleşti → approver_inactive
        po2.setActive(false);
        users.save(po2);
        assertThat(approvals.status(rawPo2).reason()).isEqualTo("approver_inactive");
        po2.setActive(true);
        users.save(po2);
        // İçerik değişti → bağlantı geçerli ama eskimiş: onaylanamaz, reddedilebilir
        reports.touchContent(r.getId(), service.now());
        assertThat(approvals.status(rawPo1).valid()).isTrue();
        assertThat(approvals.status(rawPo1).stale()).isTrue();
        // Yeni istek → eski bağlantı silinir
        approvals.request(service.report(r.getId()), ch, team, tech.getId());
        assertThat(approvals.status(rawPo1).reason()).isEqualTo("not_found");
        ArgumentCaptor<String> html2 = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String[]> to2 = ArgumentCaptor.forClass(String[].class);
        verify(email, times(4)).sendHtml(to2.capture(), any(), anyString(), html2.capture(), any(), any(), eq(false), any());
        String fresh = null;
        for (int i = 2; i < 4; i++) {
            Matcher m = LINK.matcher(html2.getAllValues().get(i));
            assertThat(m.find()).isTrue();
            if (to2.getAllValues().get(i)[0].startsWith("po1")) fresh = m.group(1);
        }
        assertThat(fresh).isNotNull();
        when(sender.send(any(), any(), any(), any(), eq(TechErrorReportSender.Delivery.EXECUTIVE)))
                .thenReturn(new TechErrorReportSender.Result("SENT", "SENT", List.of("ceo@example.com"), 100, "Konu"));
        TechErrorApprovalService.Decision d = approvals.decideViaToken(fresh, team, true, null);
        assertThat(d.state()).isEqualTo("APPROVED");
        assertThat(service.report(r.getId()).getApprovalDecidedById()).isEqualTo(po1.getId());
        // İkinci tıklama: kullanıldı; diğer PO'nun bağlantısı da kapandı (not_pending ya da used)
        String finalFresh = fresh;
        assertThatThrownBy(() -> approvals.decideViaToken(finalFresh, team, true, null))
                .isInstanceOf(IllegalStateException.class).hasMessage("used");
        // Süresi dolmuş belirteç
        TechErrorReport r2 = service.createReport(ch, 2026, 8, tech.getId());
        approvals.request(r2, ch, team, tech.getId());
        service.useClock(Clock.fixed(Instant.parse("2026-10-13T09:00:00Z").plus(TechErrorApprovalService.TOKEN_TTL).plusSeconds(60), ZoneOffset.UTC));
        ArgumentCaptor<String> html3 = ArgumentCaptor.forClass(String.class);
        verify(email, times(7)).sendHtml(any(String[].class), any(), anyString(), html3.capture(), any(), any(), eq(false), any());
        Matcher m = LINK.matcher(html3.getAllValues().get(6));
        assertThat(m.find()).isTrue();
        assertThat(approvals.status(m.group(1)).reason()).isEqualTo("expired");
    }

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    @DisplayName("sayaçtan önceki satır (content_rev NULL, mevcut veri): istek ve onay çalışır (COALESCE) — inceleme 2026-10-17")
    void nullContentRevRows() {
        TechErrorChannel ch = channelWithExecs("ceo@example.com");
        TechErrorReport r = service.createReport(ch, 2026, 9, tech.getId());
        jdbc.update("update tech_error_reports set content_rev = null where id = ?", r.getId());
        assertThat(service.report(r.getId()).getContentRev()).isNull();
        approvals.request(service.report(r.getId()), ch, team, tech.getId());
        TechErrorReport after = service.report(r.getId());
        assertThat(after.getApprovalState()).isEqualTo("PENDING");
        assertThat(after.getApprovalRev()).isZero();
        when(sender.send(any(), any(), any(), any(), eq(TechErrorReportSender.Delivery.EXECUTIVE)))
                .thenReturn(new TechErrorReportSender.Result("SENT", "SENT", List.of("ceo@example.com"), 100, "Konu"));
        assertThat(approvals.decide(service.report(r.getId()), ch, team, po1.getId(), true, null, "app").state()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("yeniden istek: aynı içerik için 10 dk içinde 409 APPROVAL_PENDING; 10 dk sonra yeniden gönderilir (eski bağlantılar ölür, PO yeniden posta alır) — yarıda kalan istek böyle kurtarılır")
    void resendAfterQuietPeriod() {
        TechErrorChannel ch = channelWithExecs("ceo@example.com");
        TechErrorReport r = service.createReport(ch, 2026, 9, tech.getId());
        approvals.request(r, ch, team, tech.getId());
        List<TechErrorApprovalToken> first = tokens.findByReportIdOrderByIdAsc(r.getId());
        assertThatThrownBy(() -> approvals.request(service.report(r.getId()), ch, team, tech.getId()))
                .isInstanceOf(CodedConflictException.class).extracting(e -> ((CodedConflictException) e).getCode()).isEqualTo("APPROVAL_PENDING");
        service.useClock(Clock.fixed(Instant.parse("2026-10-13T09:00:00Z").plus(TechErrorApprovalService.RESEND_AFTER).plusSeconds(1), ZoneOffset.UTC));
        TechErrorApprovalService.RequestResult res = approvals.request(service.report(r.getId()), ch, team, tech.getId());
        assertThat(res.mailed()).isEqualTo(2);
        List<TechErrorApprovalToken> second = tokens.findByReportIdOrderByIdAsc(r.getId());
        assertThat(second).hasSize(2);
        assertThat(second).extracting(TechErrorApprovalToken::getId).doesNotContainAnyElementsOf(first.stream().map(TechErrorApprovalToken::getId).toList());
        assertThat(service.report(r.getId()).getApprovalState()).isEqualTo("PENDING");
        verify(email, times(4)).sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), eq(false), any());
    }

    @Test
    @DisplayName("PDF posta sınırına sığmıyor: onay postası eksiz gider ve bunu söyler; sebep PDF_TOO_LARGE; geçmiş olayı gerçek alıcıları ve sebebi taşır")
    void requestWhenPdfTooLarge() {
        when(sender.maxMailBytes()).thenReturn(1L);
        TechErrorChannel ch = channelWithExecs("ceo@example.com");
        TechErrorReport r = service.createReport(ch, 2026, 9, tech.getId());
        TechErrorApprovalService.RequestResult res = approvals.request(r, ch, team, tech.getId());
        assertThat(res.mailed()).isEqualTo(2);
        assertThat(res.mailProblem()).isEqualTo("PDF_TOO_LARGE");
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<EmailNotificationService.MailAttachment>> att = ArgumentCaptor.forClass(List.class);
        verify(email, times(2)).sendHtml(any(String[].class), any(), anyString(), html.capture(), any(), any(), eq(false), att.capture());
        assertThat(att.getAllValues()).allMatch(List::isEmpty);
        assertThat(html.getAllValues().get(0)).contains("PDF bu postanın boyut sınırına sığmadığından eklenmedi").doesNotContain("ekteki PDF&#39;tedir");
        var ev = events.findAll().stream().filter(e -> "APPROVAL_REQUEST".equals(e.getKind())).findFirst().orElseThrow();
        assertThat(ev.getDetailJson()).contains("PDF_TOO_LARGE").contains("po1@example.com");
    }

    @Test
    @DisplayName("bağlantı + eskimiş istek: onay reddedilir (belirteç TÜKETİLMEZ), red yine olur; uzun not belirteç tüketilmeden reddedilir; damga geri alma")
    void tokenStaleAndNoteRules() {
        TechErrorChannel ch = channelWithExecs("ceo@example.com");
        TechErrorReport r = service.createReport(ch, 2026, 9, tech.getId());
        approvals.request(r, ch, team, tech.getId());
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email, times(2)).sendHtml(any(String[].class), any(), anyString(), html.capture(), any(), any(), eq(false), any());
        Matcher m = LINK.matcher(html.getAllValues().get(0));
        assertThat(m.find()).isTrue();
        String raw = m.group(1);
        String longNote = "x".repeat(TechErrorApprovalService.MAX_NOTE + 1);
        assertThatThrownBy(() -> approvals.decideViaToken(raw, team, true, longNote)).isInstanceOf(FieldValidationException.class);
        assertThat(approvals.status(raw).valid()).as("uzun not belirteci tüketmez").isTrue();
        reports.touchContent(r.getId(), service.now());
        TechErrorApprovalService.TokenStatus st = approvals.status(raw);
        assertThat(st.valid()).isTrue();
        assertThat(st.stale()).isTrue();
        assertThatThrownBy(() -> approvals.decideViaToken(raw, team, true, null)).isInstanceOf(IllegalStateException.class).hasMessage("stale");
        assertThat(approvals.status(raw).valid()).as("eskimiş istekte onay denemesi belirteci tüketmez").isTrue();
        TechErrorApprovalService.Decision d = approvals.decideViaToken(raw, team, false, "Rakamlar değişti, yeniden bakın");
        assertThat(d.state()).isEqualTo("REJECTED");
        assertThat(approvals.status(raw).reason()).isEqualTo("used");
        // Damga geri alma: koşullu — yalnız aynı anla
        TechErrorApprovalToken t = tokens.findByReportIdOrderByIdAsc(r.getId()).get(0);
        assertThat(tokens.restoreUnused(t.getId(), Instant.EPOCH)).isZero();
        assertThat(tokens.restoreUnused(t.getId(), t.getUsedAt())).isEqualTo(1);
        assertThat(tokens.findById(t.getId()).orElseThrow().getUsedAt()).isNull();
    }

    @Test
    @DisplayName("PDF sırası dolu: istek yine PENDING kalır (uygulama içi onay mümkün), posta gitmez, sebep PDF_BUSY, günlükte SKIPPED")
    void requestWhenPdfBusy() {
        TechErrorChannel ch = channelWithExecs("ceo@example.com");
        TechErrorReport r = service.createReport(ch, 2026, 9, tech.getId());
        // İzin kuyruğunu doldurmak yerine: proxy'siz bir servis kopyasının renderPdf'i PdfBusyException fırlatır
        TechErrorReportService plain = new TechErrorReportService(channels, reports, images, users, history);
        plain.useClock(Clock.fixed(Instant.parse("2026-10-13T09:00:00Z"), ZoneOffset.UTC));
        TechErrorReportService spy = org.mockito.Mockito.spy(plain);
        org.mockito.Mockito.doThrow(new TechErrorReportService.PdfBusyException("meşgul")).when(spy).renderPdf(any());
        TechErrorApprovalService a = new TechErrorApprovalService(spy, reports, tokens, users, sender, email, logs, appSettings);
        TechErrorApprovalService.RequestResult res = a.request(r, ch, team, tech.getId());
        assertThat(res.mailed()).isZero();
        assertThat(res.failed()).isEqualTo(2);
        assertThat(res.mailProblem()).isEqualTo("PDF_BUSY");
        assertThat(service.report(r.getId()).getApprovalState()).isEqualTo("PENDING");
        verify(email, never()).sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), anyBoolean(), any());
        assertThat(logs.findAll()).hasSize(2).allMatch(n -> n.getEmailStatus().startsWith("SKIPPED"));
        var ev = events.findAll().stream().filter(e -> "APPROVAL_REQUEST".equals(e.getKind())).findFirst().orElseThrow();
        assertThat(ev.getDetailJson()).contains("PDF_BUSY").doesNotContain("po1@example.com");   // kimseye gitmedi
    }
}
