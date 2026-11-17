package com.sitemonitor.service.oncall;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * Nöbet servisi testleri (2026-11-17 perf): servis kişileri tam varlık ({@code findAllById}) yerine dar satırdan
 * ({@code findOnCallPersonRows}) okur. Mevcut testlerin {@code findAllById} fikstürleri aynen geçerli kalsın diye dar sorgu
 * o fikstürün o anki cevabından türetilir (sonradan yeniden tanımlanan {@code doAnswer} de izlenir).
 */
final class OnCallUserRows {

    private OnCallUserRows() { }

    static void mirrorFindAllById(AppUserRepository users) {
        lenient().when(users.findOnCallPersonRows(any())).thenAnswer(i -> {
            Collection<Long> ids = i.getArgument(0);
            return rowsOf(users.findAllById(ids));
        });
    }

    static List<Object[]> rowsOf(Iterable<AppUser> list) {
        List<Object[]> out = new ArrayList<>();
        if (list == null) return out;
        for (AppUser u : list) out.add(new Object[] { u.getId(), u.getUsername(), u.getDisplayName(), u.getEmail(), u.getActive() });
        return out;
    }
}
