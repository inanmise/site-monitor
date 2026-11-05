package com.sitemonitor.service.forward;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Alarm iletiminin push ayağı (2026-11-05). Uygulama {@code UserPushService}; e-posta yolları (EscalationService,
 * StormService) bu arayüzü çağırır — alan {@code @Autowired(required = false)} (null = push iletimi yok).
 */
public interface ForwardPushSink {

    /**
     * Tek alarmın iletim push'u — sahip takımın push'undan SONRA çağrılır ({@code sendCombinedAlert}). {@code mailTrigger}
     * INITIAL | ESCALATION | DAILY_REALERT | MANUAL (sahip takımın push'uyla aynı eşleme). Sahip takımın kanal kararları
     * ({@code ctx}: push_disabled, quiet_deferred …) aynen uygulanır; aynı kişi aynı aşamada iki push almaz.
     */
    void enqueueForwards(Long alertEventId, String mailTrigger, List<ForwardTarget> targets,
                         Map<String, Object> ctx, Set<String> excludeUsernames);

    /**
     * Fırtına bildirimi (varsayılan kipte üyelerin push'u fırtınaya devredilir): alıcı takıma, kendisine iletilen üye
     * sayısıyla tek toplu push. {@code stormTrigger} INITIAL | DAILY_REALERT | RESOLVE.
     */
    void enqueueForwardStormNotice(Long stormId, String stormTrigger, ForwardTarget target, int memberCount,
                                   String level, String ownerTeamName);
}
