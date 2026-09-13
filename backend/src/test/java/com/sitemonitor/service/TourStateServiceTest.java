package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/** Ürün turu durumu (2026-09-13): birleştirme kuralları, kalıcı "bir daha gösterme", erteleme tavanı, özet. */
@ExtendWith(MockitoExtension.class)
class TourStateServiceTest {

    @Mock AppUserRepository userRepo;

    private TourStateService svc() { return new TourStateService(userRepo); }

    private static AppUser user(String state) { AppUser u = new AppUser(); u.setUsername("u"); u.setActive(true); u.setTourState(state); return u; }

    @Test
    @DisplayName("parse: boş/bozuk → null; geçerli JSON → harita")
    void parse() {
        assertThat(TourStateService.parse(null)).isNull();
        assertThat(TourStateService.parse("   ")).isNull();
        assertThat(TourStateService.parse("{bozuk")).isNull();
        assertThat(TourStateService.parse("[1,2]")).isNull();
        assertThat(TourStateService.parse("{\"status\":\"completed\",\"version\":2}")).containsEntry("status", "completed").containsEntry("version", 2);
    }

    @Test
    @DisplayName("apply: yama birleşir, updated_at damgalanır, kaydedilir; bilinmeyen status → 400 (IllegalArgument)")
    void applyMergesAndSaves() {
        AppUser u = user(null);
        Map<String, Object> out = svc().apply(u, Map.of("status", "started", "version", 1, "last_step", "sidebar"), false);
        assertThat(out).containsEntry("status", "started").containsEntry("version", 1).containsEntry("last_step", "sidebar").containsKey("updated_at");
        assertThat(u.getTourState()).contains("\"status\":\"started\"");
        verify(userRepo).save(u);
        assertThatThrownBy(() -> svc().apply(u, Map.of("status", "bogus"), false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("dismissed KALICI: started/snoozed onu ezmez; completed ya da yeniden dismissed yazabilir; reset siler")
    void dismissedIsSticky() {
        AppUser u = user("{\"status\":\"dismissed\",\"version\":1}");
        assertThat(svc().apply(u, Map.of("status", "started"), false)).containsEntry("status", "dismissed");
        assertThat(svc().apply(u, Map.of("status", "snoozed"), false)).containsEntry("status", "dismissed");
        assertThat(svc().apply(u, Map.of("status", "completed"), false)).containsEntry("status", "completed");
        assertThat(svc().apply(u, Map.of(), true)).isNull();
        assertThat(u.getTourState()).isNull();
    }

    @Test
    @DisplayName("snoozed sayacı artar ve SNOOZE_MAX'ta durur; seen_page tekilleşir ve yalnız güvenli anahtar kabul eder")
    void snoozeAndSeenPages() {
        AppUser u = user(null);
        for (int i = 0; i < TourStateService.SNOOZE_MAX + 2; i++) svc().apply(u, Map.of("status", "snoozed"), false);
        assertThat(TourStateService.parse(u.getTourState())).containsEntry("snoozed", TourStateService.SNOOZE_MAX);
        svc().apply(u, Map.of("seen_page", "all"), false);
        svc().apply(u, Map.of("seen_page", "all"), false);
        svc().apply(u, Map.of("seen_page", "<script>"), false);
        @SuppressWarnings("unchecked") List<String> pages = (List<String>) TourStateService.parse(u.getTourState()).get("seen_pages");
        assertThat(pages).containsExactly("all");
    }

    @Test
    @DisplayName("checklist birleşir (var olan anahtarlar korunur), yalnız güvenli anahtar + boolean")
    void checklistMerges() {
        AppUser u = user("{\"checklist\":{\"tour\":true}}");
        Map<String, Object> out = svc().apply(u, Map.of("checklist", Map.of("card", true, "bad key", true, "x", "evet")), false);
        @SuppressWarnings("unchecked") Map<String, Object> cl = (Map<String, Object>) out.get("checklist");
        assertThat(cl).containsEntry("tour", true).containsEntry("card", true).containsEntry("x", false).doesNotContainKey("bad key");
        assertThat(svc().apply(u, Map.of("checklist_hidden", true), false)).containsEntry("checklist_hidden", true);
    }

    @Test
    @DisplayName("summarize: aktif kullanıcılarda tamamladı / kapattı / bekliyor / hiç; pasif kullanıcı sayılmaz")
    void summarize() {
        AppUser inactive = user("{\"status\":\"completed\"}"); inactive.setActive(false);
        Map<String, Integer> m = TourStateService.summarize(List.of(
                user("{\"status\":\"completed\"}"), user("{\"status\":\"dismissed\"}"), user("{\"status\":\"snoozed\"}"),
                user(null), user("{bozuk"), inactive));
        assertThat(m).containsEntry("completed", 1).containsEntry("dismissed", 1).containsEntry("pending", 1).containsEntry("none", 2);
    }

    @Test
    @DisplayName("apply: userRepo.save her yazımda çağrılır (kalıcılık kanıtı)")
    void savesOnEveryWrite() {
        AppUser u = user(null);
        svc().apply(u, Map.of("version", 3), false);
        verify(userRepo).save(any(AppUser.class));
    }
}
