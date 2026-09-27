package com.sitemonitor.service;

import com.sitemonitor.model.UserTeamSource;
import com.sitemonitor.repository.UserTeamSourceRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Üyelik kaynak izi için bellek-içi sahte depo — servis testleri gerçek {@link TeamMembershipSourceService}
 * mantığını (yaz / oku / sil) mock'lanmış repository üzerinden koşsun diye.
 */
final class TeamSourceFakes {

    private TeamSourceFakes() {}

    /** (userId:teamId) → iz. Testler doğrudan okuyup yazabilir. */
    static final class Store {
        final Map<String, UserTeamSource> rows = new LinkedHashMap<>();
        final UserTeamSourceRepository repo = mock(UserTeamSourceRepository.class);
        final TeamMembershipSourceService service = new TeamMembershipSourceService(repo);

        Store() {
            when(repo.save(any(UserTeamSource.class))).thenAnswer(inv -> {
                UserTeamSource s = inv.getArgument(0);
                rows.put(key(s.getUserId(), s.getTeamId()), s);
                return s;
            });
            when(repo.findByUserId(anyLong())).thenAnswer(inv -> {
                Long uid = inv.getArgument(0);
                List<UserTeamSource> out = new ArrayList<>();
                for (UserTeamSource s : rows.values()) if (uid.equals(s.getUserId())) out.add(s);
                return out;
            });
            when(repo.findByUserIdIn(anyCollection())).thenAnswer(inv -> {
                Collection<Long> ids = inv.getArgument(0);
                List<UserTeamSource> out = new ArrayList<>();
                for (UserTeamSource s : rows.values()) if (ids.contains(s.getUserId())) out.add(s);
                return out;
            });
            doAnswer(inv -> {
                Long uid = inv.getArgument(0);
                Collection<Long> teams = inv.getArgument(1);
                for (Long t : teams) rows.remove(key(uid, t));
                return null;
            }).when(repo).deleteByUserIdAndTeamIdIn(anyLong(), anyCollection());
            doAnswer(inv -> {
                Long uid = inv.getArgument(0);
                rows.values().removeIf(s -> uid.equals(s.getUserId()));
                return null;
            }).when(repo).deleteByUserId(anyLong());
        }

        void put(long userId, long teamId, String source, String detail) {
            rows.put(key(userId, teamId), new UserTeamSource(userId, teamId, source, detail, "2026-01-01T00:00:00", "test"));
        }

        UserTeamSource get(long userId, long teamId) {
            return rows.get(key(userId, teamId));
        }

        private static String key(Long u, Long t) {
            return u + ":" + t;
        }
    }
}
