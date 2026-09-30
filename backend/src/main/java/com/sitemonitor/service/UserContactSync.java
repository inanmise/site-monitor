package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.repository.EscalationContactRepository;

/**
 * {@code user_id} bağlı eskalasyon kişilerinin ad/e-postasını kullanıcı satırından tazeler — TEK kaynak.
 *
 * <p>Eskiden yalnız yönetici düzenlemesi ({@code UserService.updateUser}) senkronluyordu; LDAP girişi AD'den yeni
 * e-postayı {@code app_users}'a yazıyor ama kişiyi eski adreste bırakıyordu (kod denetimi 2026-09-29, A1-O2) —
 * alarm/eskalasyon/haftalık rapor maili ölü adrese gidiyor, ekranda kişi adıyla göründüğü için fark edilmiyordu.
 * İki yol da bu yardımcıyı çağırır.
 */
final class UserContactSync {

    private UserContactSync() {}

    /** Kullanıcının kişilerini (ad → görünen ad ya da kullanıcı adı, e-posta) günceller; değişen kayıt sayısı. */
    static int sync(EscalationContactRepository contactRepo, AppUser u) {
        if (contactRepo == null || u == null || u.getId() == null) return 0;
        String name = u.getDisplayName() != null && !u.getDisplayName().isBlank() ? u.getDisplayName() : u.getUsername();
        int n = 0;
        for (EscalationContact c : contactRepo.findByUserId(u.getId())) {
            boolean changed = !java.util.Objects.equals(c.getName(), name) || !java.util.Objects.equals(c.getEmail(), u.getEmail());
            if (!changed) continue;
            c.setName(name);
            c.setEmail(u.getEmail());
            contactRepo.save(c);
            n++;
        }
        return n;
    }
}
