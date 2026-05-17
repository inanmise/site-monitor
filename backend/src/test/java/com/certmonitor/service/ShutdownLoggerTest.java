package com.certmonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest
@ActiveProfiles("test")
class ShutdownLoggerTest {

    @Autowired
    ConfigurableApplicationContext ctx;

    @Autowired
    ShutdownLogger shutdownLogger;

    @Test
    @DisplayName("onContextClosed logs shutdown info without throwing")
    void onContextClosed_doesNotThrow() {
        // Simulates what happens when Spring fires ContextClosedEvent (SIGTERM on Linux)
        assertThatCode(() -> shutdownLogger.onContextClosed())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ShutdownLogger initialises and registers hooks without error")
    void init_doesNotThrow() {
        // If @PostConstruct had failed, Spring would not have started at all
        assertThatCode(() -> shutdownLogger.init())
                .doesNotThrowAnyException();
    }
}
