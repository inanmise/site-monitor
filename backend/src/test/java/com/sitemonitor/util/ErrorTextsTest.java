package com.sitemonitor.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.*;

/** Kullanıcıya dönen hata metni bekçisi (2026-10-08): teknik ileti tespiti + bilinen kısa iletilerin iki dilli açıklaması. */
class ErrorTextsTest {

    @AfterEach
    void reset() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void lang(String l) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(Msg.HEADER, l);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "java.lang.NullPointerException",
            "Cannot invoke \"String.length()\" because \"s\" is null",
            "org.springframework.dao.InvalidDataAccessApiUsageException: x",
            "could not execute statement; SQL [n/a]",
            "For input string: \"abc\"",
            "No enum constant com.sitemonitor.model.Tier.FIVE",
            "No value present",
            "Index 3 out of bounds for length 2",
            "at com.sitemonitor.X.run(X.java:12)",
            "IllegalStateException: boom",
            "duplicate key value violates unique constraint \"uk_x\"",
    })
    @DisplayName("teknik iletiler yakalanır")
    void technical(String msg) {
        assertTrue(ErrorTexts.isTechnical(msg), msg);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Bu alan adı zaten kayıtlı",
            "net.ornek.com.tr envanterde zaten var",
            "LDAP unreachable",
            "Invalid admin password",
            "Alarm not found: #999",
            "Eşik 1 ile 100 arasında olmalı",
    })
    @DisplayName("kullanıcı iletileri (alan adları dahil) teknik sayılmaz")
    void notTechnical(String msg) {
        assertFalse(ErrorTexts.isTechnical(msg), msg);
    }

    @Test
    @DisplayName("boş/null ileti gösterilemez sayılır; safeMessage yedeğe düşer")
    void blankAndSafe() {
        assertTrue(ErrorTexts.isTechnical(null));
        assertTrue(ErrorTexts.isTechnical("  "));
        assertEquals("Kaydedilemedi, tekrar deneyin",
                ErrorTexts.safeMessage(new RuntimeException("java.lang.X"), "Kaydedilemedi, tekrar deneyin", "Couldn’t save, try again"));
        assertEquals("Takım adı boş olamaz",
                ErrorTexts.safeMessage(new IllegalArgumentException("Takım adı boş olamaz"), "x", "y"));
        lang("en");
        assertEquals("Couldn’t save, try again",
                ErrorTexts.safeMessage(new RuntimeException((String) null), "Kaydedilemedi, tekrar deneyin", "Couldn’t save, try again"));
    }

    @Test
    @DisplayName("kod biçimli ileti")
    void codeLike() {
        assertTrue(ErrorTexts.isCodeLike("VERSION_CONFLICT"));
        assertFalse(ErrorTexts.isCodeLike("Version conflict"));
        assertFalse(ErrorTexts.isCodeLike(null));
    }

    @Test
    @DisplayName("bilinen iletiler TR/EN açıklanır; kimlik ve izin adı korunur; bilinmeyen → null")
    void localizeKnown() {
        assertTrue(ErrorTexts.localizeKnown("Admin access required").startsWith("Bu işlem yalnız sistem yöneticilerine açık"));
        assertEquals("Takım bulunamadı (#12); silinmiş ya da taşınmış olabilir. Listeyi yenileyip tekrar deneyin.",
                ErrorTexts.localizeKnown("Team not found: 12"));
        assertEquals("Kullanıcı bulunamadı (alice); silinmiş ya da taşınmış olabilir. Listeyi yenileyip tekrar deneyin.",
                ErrorTexts.localizeKnown("User not found: alice"));
        assertTrue(ErrorTexts.localizeKnown("incidents.view yetkisi gerekli").contains("incidents.view"));
        assertNull(ErrorTexts.localizeKnown("Invalid admin password"));
        assertNull(ErrorTexts.localizeKnown("Takım adı boş olamaz"));
        assertNull(ErrorTexts.localizeKnown("User not found: "));
        assertNull(ErrorTexts.localizeKnown(null));

        lang("en");
        assertEquals("Only system administrators can do this. If you need it, ask a system administrator to do it for you or to grant you access.",
                ErrorTexts.localizeKnown("Global admin required"));
        assertEquals("Alert not found (#7); it may have been deleted or moved. Refresh the list and try again.",
                ErrorTexts.localizeKnown("Alert not found: 7"));
        assertTrue(ErrorTexts.localizeKnown("Bu işlem için yetkiniz yok: alerts.edit").contains("it needs alerts.edit"));
    }
}
