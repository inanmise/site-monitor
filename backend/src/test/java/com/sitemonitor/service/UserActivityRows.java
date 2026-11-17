package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.lenient;

/**
 * Kullanıcı etkinliği testleri (2026-11-17 perf): servis kullanıcıları tam varlık ({@code findAll}) yerine dar satırdan
 * ({@code findActivityRows} + {@code findAllTeamMembershipPairs}) okur. Mevcut testlerin {@code findAll} fikstürleri aynen
 * geçerli kalsın diye dar satırlar o fikstürün o anki cevabından türetilir (sonradan yeniden tanımlanan stub da izlenir).
 */
public final class UserActivityRows {

    private UserActivityRows() { }

    public static void mirrorFindAll(AppUserRepository repo) {
        lenient().when(repo.findActivityRows()).thenAnswer(i -> rowsOf(repo.findAll()));
        lenient().when(repo.findAllTeamMembershipPairs()).thenAnswer(i -> pairsOf(repo.findAll()));
    }

    /** {@code AppUserRepository#findActivityRows} sütun sırası. */
    public static List<Object[]> rowsOf(List<AppUser> users) {
        List<Object[]> out = new ArrayList<>();
        if (users == null) return out;
        for (AppUser u : users) {
            out.add(new Object[] {
                    u.getId(), u.getUsername(), u.getDisplayName(), u.getFirstName(), u.getLastName(), u.getEmail(),
                    u.getActive(), u.getTeamId(), u.getSystemRole(), u.getOrgRole(), u.getAuthSource(), u.getCreatedAt(),
                    u.getLastLoginAt(), u.getLastLoginIp(), u.getLastLoginMethod(), u.getLastSeenAt(), u.getPrevLoginAt(),
                    u.getPrevLoginIp(), u.getFailedBeforeLogin(), u.getFailedSinceLogin(), u.getLastFailedLoginAt(),
                    u.getLastFailedLoginIp(), u.getLastFailedLoginReason(), u.getPermanentLock(), u.getDepartment(),
                    u.getEmployeeId(), u.getTitle(), u.getTourState(), u.getActiveSessionId(),
                    u.getPhotoBase64() != null && !u.getPhotoBase64().isBlank() });
        }
        return out;
    }

    public static List<Object[]> pairsOf(List<AppUser> users) {
        List<Object[]> out = new ArrayList<>();
        if (users == null) return out;
        for (AppUser u : users) if (u.getTeamIds() != null) for (Long t : u.getTeamIds()) out.add(new Object[] { u.getId(), t });
        return out;
    }
}
