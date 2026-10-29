package com.sitemonitor.service.checks;

import java.util.List;

/**
 * Tamamlanmış kontrol sonuçlarını DEĞERLENDİRİR (2026-10-29, çok pod Sürüm 3-3 P0, tasarım R3-3 §4.2) — paket B
 * ({@code SchedulerService}) sağlar, paket C'nin boşaltıcısı ({@code CheckResultDrainer}) çağırır.
 *
 * <p><b>Çağıranın garantileri (C):</b> yalnız tarama lideri, bir {@code MonitoringOutageService.evalBatchTicket()} bileti
 * İÇİNDE, {@code isSweepLeaderNow()} yeniden denetlendikten sonra; dalga {@code id} sırasında ve her (tür, izleme) için en
 * çok BİR sonuç taşır (aynı izlemenin ikinci sonucu sonraki dalgaya kalır); bayat ({@code result-stale-ms}) satırlar
 * dalgaya girmeden atılmıştır.
 *
 * <p><b>Uygulayıcının sözleşmesi (B, K-3):</b> türe göre toplu {@code findAllById}; aktif + hedef parmak izi
 * ({@link CheckJobRunner#KEY_TARGET}) + tür anahtarı + envanter denetimi; {@link CheckJobRunner#carriesResult} false olan
 * sonuç atlanır; kalanlar klasik 2. aşamanın AYNI kurucularıyla ({@code portSweepItem}, {@code httpSlowSweepItem}, …)
 * kaleme dönüşür ve klasik sırada (DOWN önce SLOW) {@code handleSweepResults(tür, liste)} (zamanlanmış) / DNS için dalga
 * başına TEK {@code handleDnsSweep(...)} çağrılır. Kalemlerin {@code recheck} kapanışları değerlendiricinin yüklediği
 * varlık üzerine kurulur (yeniden kontroller liderde koşar). Fırlatmamalıdır (tür başına yakalar); C yine de savunma
 * amaçlı yakalar.
 */
public interface CheckResultApplier {

    /**
     * Bir dalgayı değerlendirir.
     *
     * @param wave id sırasında, (tür, izleme) başına ≤ 1 sonuç (boş / null → hiçbir şey yapılmaz)
     */
    void apply(List<CheckResultRow> wave);
}
