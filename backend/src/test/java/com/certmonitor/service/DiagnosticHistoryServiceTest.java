package com.certmonitor.service;

import com.certmonitor.model.DiagnosticRun;
import com.certmonitor.repository.DiagnosticRunRepository;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DiagnosticHistoryServiceTest {

    @Mock DiagnosticRunRepository repo;
    private DiagnosticHistoryService service;

    @BeforeEach
    void setUp() {
        service = new DiagnosticHistoryService(repo, new ObjectMapper());
    }

    @Test
    @DisplayName("record: sonucu JSON'a çevirip kim/nereden/sonuç ile kaydeder")
    void record_serializesAndSaves() {
        service.record("www.akbank.com", 443, "OPENSSL",
                "admin", 1L, 2L, "10.0.0.5", true, "Zayıf protokol yok",
                Map.of("available", true, "version", "OpenSSL 3.0"));

        ArgumentCaptor<DiagnosticRun> cap = ArgumentCaptor.forClass(DiagnosticRun.class);
        verify(repo).save(cap.capture());
        DiagnosticRun d = cap.getValue();
        assertThat(d.getDomain()).isEqualTo("www.akbank.com");
        assertThat(d.getRunType()).isEqualTo("OPENSSL");
        assertThat(d.getExecutedBy()).isEqualTo("admin");
        assertThat(d.getSourceIp()).isEqualTo("10.0.0.5");
        assertThat(d.getSuccess()).isTrue();
        assertThat(d.getResultJson()).contains("\"available\":true").contains("OpenSSL 3.0");
        assertThat(d.getExecutedAt()).isNotBlank();
    }

    @Test
    @DisplayName("record: serialize hatası akışı bozmaz (yutar)")
    void record_swallowsErrors() {
        // Jackson döngüsel referansta patlar — kayıt atlanmalı, exception fırlamamalı
        Object cyclic = new Object() {
            @SuppressWarnings("unused")
            public Object self() { return this; }
        };
        assertThatCode(() -> service.record("d", 443, "OPENSSL", "a", 1L, 2L, "ip", false, "s", cyclic))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("history: domain bazında son kayıtlar; get: tek kayıt")
    void historyAndGet() {
        DiagnosticRun d = new DiagnosticRun();
        d.setId(7L); d.setDomain("x.com");
        when(repo.findTop100ByDomainOrderByIdDesc("x.com")).thenReturn(List.of(d));
        when(repo.findById(7L)).thenReturn(Optional.of(d));

        assertThat(service.history("x.com")).hasSize(1);
        assertThat(service.get(7L).getId()).isEqualTo(7L);
        assertThatThrownBy(() -> service.get(99L))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }
}
