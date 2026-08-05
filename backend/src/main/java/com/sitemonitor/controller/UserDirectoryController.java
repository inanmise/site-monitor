package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hafif kullanıcı DİZİNİ — UI'da username → ad-soyad + avatar çözümü için. /api/** olduğundan
 * AuthInterceptor zaten oturum açmış kullanıcıyı zorunlu kılar (admin gerekmez); proje genelinde
 * her yerde sadece username yerine "ad-soyad + resim" gösterebilmek için tüm sayfalar bu dizinden
 * beslenir. Yalnız id/username/display_name + foto döner (rol/e-posta gibi hassas alan YOK).
 *
 * Foto: kurumsal iç araç bağlamında meslektaş AD fotoğrafı/adı hassas değildir; admin'e özel
 * /api/admin/users/{id}/photo ucu DOKUNULMADAN, burada authenticated kullanıcılara ayrı bir uç verilir.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserDirectoryController {

    private final AppUserRepository userRepo;

    /** Tüm kullanıcılar: [{id, username, display_name}] — frontend username→{id,display_name} haritası. */
    @GetMapping("/directory")
    public ResponseEntity<Map<String, Object>> directory() {
        List<Map<String, Object>> list = userRepo.findAll().stream()
                .filter(u -> u.getUsername() != null)
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", u.getId());
                    m.put("username", u.getUsername());
                    m.put("display_name", displayName(u));
                    m.put("email", u.getEmail());   // alıcı/eskalasyon kontağı eşleştirmesi (e-posta zaten o ekranlarda görünür)
                    return m;
                })
                .toList();
        return ResponseEntity.ok(Map.of("success", true, "data", list));
    }

    /** Kullanıcının AD fotoğrafı (JPEG) — yoksa 404. Authenticated erişim (admin gerekmez). */
    @GetMapping("/{id}/photo")
    public ResponseEntity<byte[]> photo(@PathVariable Long id) {
        return userRepo.findById(id)
                .map(u -> AuthController.photoResponse(u.getPhotoBase64()))
                .orElse(ResponseEntity.notFound().build());
    }

    /** display_name → yoksa "Ad Soyad" → yoksa null (frontend username'e düşer). */
    private static String displayName(AppUser u) {
        if (u.getDisplayName() != null && !u.getDisplayName().isBlank()) return u.getDisplayName();
        String full = ((u.getFirstName() != null ? u.getFirstName() : "") + " "
                     + (u.getLastName() != null ? u.getLastName() : "")).trim();
        return full.isEmpty() ? null : full;
    }
}
