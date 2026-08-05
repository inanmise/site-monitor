package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link UserDirectoryController} — username→{ad-soyad, e-posta} dizini + AD foto ucu. Controller saf
 * POJO olarak (mock repo ile) çağrılır; web-slice/güvenlik bağlamı gerekmez. display_name fallback
 * merdiveni (displayName → ad+soyad → null) directory() üzerinden doğrulanır.
 */
@ExtendWith(MockitoExtension.class)
class UserDirectoryControllerTest {

    @Mock AppUserRepository userRepo;

    UserDirectoryController controller;

    @BeforeEach
    void setUp() {
        controller = new UserDirectoryController(userRepo);
    }

    private static AppUser user(Long id, String username, String displayName, String first, String last, String email) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername(username);
        u.setDisplayName(displayName);
        u.setFirstName(first);
        u.setLastName(last);
        u.setEmail(email);
        return u;
    }

    @Test
    @DisplayName("directory: username'i null olan kullanıcıyı ELER, id/username/display_name/email eşler")
    @SuppressWarnings("unchecked")
    void directory_filtersNullUsername_andMapsFields() {
        when(userRepo.findAll()).thenReturn(List.of(
                user(1L, "aylin", "Aylin Y.", null, null, "aylin@akbank.com"),
                user(2L, null, "Ghost", "G", "H", "ghost@akbank.com")   // username null → elenmeli
        ));

        ResponseEntity<Map<String, Object>> resp = controller.directory();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> data = (List<Map<String, Object>>) resp.getBody().get("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0)).containsEntry("id", 1L)
                .containsEntry("username", "aylin")
                .containsEntry("display_name", "Aylin Y.")
                .containsEntry("email", "aylin@akbank.com");
    }

    @Test
    @DisplayName("directory: display_name yoksa 'Ad Soyad'a düşer; o da yoksa null (frontend username'e düşer)")
    @SuppressWarnings("unchecked")
    void directory_displayNameFallbackLadder() {
        when(userRepo.findAll()).thenReturn(List.of(
                user(1L, "explicit", "Açık İsim", "X", "Y", null),   // displayName kazanır
                user(2L, "nameparts", null, "Mehmet", "Demir", null), // ad+soyad
                user(3L, "bare", null, null, null, null)              // hiçbiri → null
        ));

        List<Map<String, Object>> data = (List<Map<String, Object>>) controller.directory().getBody().get("data");

        assertThat(data).hasSize(3);
        assertThat(data.get(0).get("display_name")).isEqualTo("Açık İsim");
        assertThat(data.get(1).get("display_name")).isEqualTo("Mehmet Demir");
        assertThat(data.get(2).get("display_name")).isNull();
    }

    @Test
    @DisplayName("photo: bilinmeyen id → 404")
    void photo_unknownId_returns404() {
        when(userRepo.findById(999L)).thenReturn(Optional.empty());

        ResponseEntity<byte[]> resp = controller.photo(999L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
