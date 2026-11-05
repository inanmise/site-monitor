package com.sitemonitor.service;

import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.NotificationGroupRepository;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.repository.PageSpeedMonitorRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.forward.AlertForwardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Takım yaşam döngüsünde alarm iletimi (2026-11-05): etki önizlemesi sahibi olduğu / gelen iletimleri BİLGİ olarak sayar
 * (silmeyi ENGELLEMEZ — {@code empty} değişmez); taşıma sahibi olduğu iletimleri hedefe geçirir, gelenleri siler; kullanıcı /
 * takım silme kancaları servisi çağırır. Servis yokken (eski kurulum / dilimli test) bölüm atlanır.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeamAdminAlertForwardTest {

    @Mock TeamRepository teamRepo;
    @Mock AppUserRepository userRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock NotificationGroupRepository groupRepo;
    @Mock AlertEventRepository alertEventRepo;
    @Mock HttpMonitorRepository httpRepo;
    @Mock PortMonitorRepository portRepo;
    @Mock PingMonitorRepository pingRepo;
    @Mock DnsMonitorRepository dnsRepo;
    @Mock KeywordMonitorRepository keywordRepo;
    @Mock PageMonitorRepository pageRepo;
    @Mock PageSpeedMonitorRepository pageSpeedRepo;
    @Mock ScriptedMonitorRepository scriptedRepo;
    @Mock DomainMonitorRepository domainRepo;
    @Mock AlertForwardService alertForwards;
    @InjectMocks TeamAdminService service;

    @BeforeEach
    void setUp() {
        Team t = new Team();
        t.setId(9L);
        t.setName("B");
        when(teamRepo.findById(9L)).thenReturn(Optional.of(t));
    }

    @Test
    @DisplayName("etki: iletim sayıları bilgi olarak döner ama boş takımın silinmesini engellemez")
    void impactCountsButDoesNotBlock() {
        service.setAlertForwards(alertForwards);
        when(alertForwards.impactOf(7L)).thenReturn(Map.of("owned", 3L, "incoming", 1L));
        Map<String, Object> impact = service.impact(7L);
        assertThat(impact.get("alert_forwards")).isEqualTo(Map.of("owned", 3L, "incoming", 1L));
        assertThat(impact.get("empty")).as("iletim silmeyi engellemez").isEqualTo(true);
    }

    @Test
    @DisplayName("etki: iletim sayılamazsa bölüm yok, önizleme yine döner; servis yokken bölüm yok")
    void impactReadFailure() {
        service.setAlertForwards(alertForwards);
        when(alertForwards.impactOf(7L)).thenThrow(new RuntimeException("db"));
        assertThat(service.impact(7L)).doesNotContainKey("alert_forwards").containsEntry("empty", true);
        service.setAlertForwards(null);
        assertThat(service.impact(7L)).doesNotContainKey("alert_forwards");
    }

    @Test
    @DisplayName("taşıma: sahibi olduğu iletimler hedefe, gelenler silinir — sayılar sonuçta")
    void moveAll() {
        service.setAlertForwards(alertForwards);
        when(alertForwards.moveTeam(7L, 9L)).thenReturn(new AlertForwardService.Moved(2, 1));
        Map<String, Integer> moved = service.moveAll(7L, 9L);
        verify(alertForwards).moveTeam(7L, 9L);
        assertThat(moved).containsEntry("alert_forwards", 2).containsEntry("alert_forwards_removed", 1);
        service.setAlertForwards(null);
        assertThat(service.moveAll(7L, 9L)).doesNotContainKey("alert_forwards");
    }
}
