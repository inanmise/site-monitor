package com.sitemonitor.service;

import com.sitemonitor.service.forward.AlertForwardRouting;
import com.sitemonitor.service.forward.ForwardPushSink;
import com.sitemonitor.service.forward.ForwardTarget;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Alarm iletimi testlerinin sahte yönlendirmesi ve push ayağı (BE1 / BE3 servislerine bağımlı olmadan — sözleşme §8).
 * Yönlendirme: sahip takım → hedefler; {@code targetsFor} sahiplerden biri olan alıcıyı atlar (sözleşme §2).
 */
final class ForwardFakes {

    private ForwardFakes() { }

    static ForwardTarget target(long rt, String name, List<String> owners, List<String> emails) {
        return new ForwardTarget(rt, name, List.of(), owners, List.of(rt * 10), emails, List.of(), true);
    }

    /** Sahte yönlendirme: {@code byOwner} sahip takım → o takımın hedefleri; çağrılar kaydedilir. */
    static final class Routing implements AlertForwardRouting {
        final Map<Long, List<ForwardTarget>> byOwner;
        /** İsteğe bağlı kapsam süzgeci (alarm × hedef); null = her alarm her hedefe. */
        BiPredicate<AlertRef, ForwardTarget> filter;
        final List<AlertRef> refs = new ArrayList<>();
        final List<Collection<Long>> anyCalls = new ArrayList<>();

        Routing(Map<Long, List<ForwardTarget>> byOwner) {
            this.byOwner = byOwner;
        }

        @Override
        public boolean anyFor(Collection<Long> ownerTeamIds) {
            anyCalls.add(List.copyOf(ownerTeamIds));
            for (Long o : ownerTeamIds) if (byOwner.containsKey(o)) return true;
            return false;
        }

        @Override
        public List<ForwardTarget> targetsFor(AlertRef ref) {
            refs.add(ref);
            List<ForwardTarget> out = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            for (Long o : ref.ownerTeamIds()) {
                for (ForwardTarget t : byOwner.getOrDefault(o, List.of())) {
                    if (ref.ownerTeamIds().contains(t.recipientTeamId())) continue;
                    if (filter != null && !filter.test(ref, t)) continue;
                    if (seen.add(t.recipientTeamId())) out.add(t);
                }
            }
            out.sort((a, b) -> Long.compare(a.recipientTeamId(), b.recipientTeamId()));
            return out;
        }
    }

    /** Sahte push ayağı — çağrıları kaydeder. */
    static final class Push implements ForwardPushSink {
        record Call(Long alertEventId, String trigger, List<ForwardTarget> targets, Map<String, Object> ctx,
                    Set<String> excludeUsernames) { }

        record StormCall(Long stormId, String trigger, ForwardTarget target, int memberCount, String level, String owner,
                         Long ownerTeamId) { }

        final List<Call> calls = new ArrayList<>();
        final List<StormCall> stormCalls = new ArrayList<>();
        /** {@code forwardStormRecipientTeams} yanıtı (fırtına → önceki iletim push alıcı takımları). */
        final Map<Long, Set<Long>> priorStormTeams = new java.util.HashMap<>();

        @Override
        public void enqueueForwards(Long alertEventId, String mailTrigger, List<ForwardTarget> targets,
                                    Map<String, Object> ctx, Set<String> excludeUsernames) {
            calls.add(new Call(alertEventId, mailTrigger, List.copyOf(targets), ctx, excludeUsernames));
        }

        @Override
        public void enqueueForwardStormNotice(Long stormId, String stormTrigger, ForwardTarget target, int memberCount,
                                              String level, String ownerTeamName, Long ownerTeamId) {
            stormCalls.add(new StormCall(stormId, stormTrigger, target, memberCount, level, ownerTeamName, ownerTeamId));
        }

        @Override
        public Set<Long> forwardStormRecipientTeams(Long stormId) {
            return priorStormTeams.getOrDefault(stormId, Set.of());
        }
    }
}
