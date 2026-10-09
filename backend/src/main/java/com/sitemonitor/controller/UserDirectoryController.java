package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.service.userref.UserPublicIds;
import com.sitemonitor.service.userref.UserRef;
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

    /** Opak kullanıcı kimliği (2026-10-08). Bean'siz dilim testinde null → eski sayısal ayrıştırma. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UserPublicIds userPublicIds;

    /** Tüm kullanıcılar: [{id, username, display_name}] — frontend username→{id,display_name} haritası. */
    @GetMapping("/directory")
    public ResponseEntity<Map<String, Object>> directory() {
        // Tek projeksiyon sorgusu (2026-10-09): fotoğraf / takım koleksiyonu yüklenmez
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (Object[] r : userRepo.findDirectoryRows()) {
            if (r == null || r.length < 6 || r[1] == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", UserRef.of(r[0] instanceof Number n ? Long.valueOf(n.longValue()) : null));   // global olmayana opak (UserRefWire)
            m.put("username", r[1]);
            m.put("display_name", displayName((String) r[2], (String) r[3], (String) r[4]));
            m.put("email", r[5]);   // alıcı/eskalasyon kontağı eşleştirmesi (e-posta zaten o ekranlarda görünür)
            list.add(m);
        }
        return ResponseEntity.ok(Map.of("success", true, "data", list));
    }

    /** Kullanıcının AD fotoğrafı (JPEG) — yoksa 404. Authenticated erişim (admin gerekmez). */
    @GetMapping("/{id}/photo")
    public ResponseEntity<byte[]> photo(@PathVariable("id") String idRef, jakarta.servlet.http.HttpSession session) {
        // Opak kimlik (2026-10-08): sıralı sayıyla fotoğraf taraması kapalı — sayı yalnız global admin'den.
        Long id = UserPublicIds.resolve(userPublicIds, idRef, session);
        if (id == null) return ResponseEntity.notFound().build();
        return userRepo.findById(id)
                .map(u -> AuthController.photoResponse(u.getPhotoBase64()))
                .orElse(ResponseEntity.notFound().build());
    }

    /** display_name → yoksa "Ad Soyad" → yoksa null (frontend username'e düşer). */
    private static String displayName(String display, String first, String last) {
        if (display != null && !display.isBlank()) return display;
        String full = ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
        return full.isEmpty() ? null : full;
    }
}
