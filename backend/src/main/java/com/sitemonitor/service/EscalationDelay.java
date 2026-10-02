package com.sitemonitor.service;

import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.util.Msg;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Zamana bağlı eskalasyon adımının (2026-10-01, opt-in) SAF kuralları — kim "gecikmeli", anlık bildirim listesinden kim
 * düşer, gecikme değeri nasıl doğrulanır.
 *
 * <p><b>Ürün güvencesi.</b> "Adım tanımlanmadıkça kimseye yeni bildirim gitmez." Hiçbir kişide gecikme yokken
 * {@link #anyDelayed} false döner ve anlık yolların filtresi listeyi AYNI NESNE olarak geri verir — ek sorgu, ek alıcı
 * ya da eksik alıcı yoktur (kapı: {@code EscalationStepRegressionTest}).
 *
 * <p><b>Gecikmeli kişi</b> ({@code delay_minutes} 1–1440): alarmın ANLIK bildirimlerine (ilk bildirim, seviye artışı,
 * günlük hatırlatma, açılış telafisi, çözüm, elle yeniden gönderim, fırtına postası) girmez. Alarm açık ve onaysız olarak
 * gecikme kadar beklerse {@code EscalationStepService} ona BİR adım gönderir; o andan sonra (ya da kişi alarmı gecikme
 * tanımlanmadan önce zaten almışsa) o alarmın normal alıcısıdır ("döngüde").
 */
public final class EscalationDelay {

    private EscalationDelay() {}

    /** Gecikme alt / üst sınırı (dakika) — form ve sunucu aynı aralığı kullanır. */
    public static final int MIN_MINUTES = 1;
    public static final int MAX_MINUTES = 1440;

    public static boolean isDelayed(EscalationContact c) {
        return c != null && c.getDelayMinutes() != null && c.getDelayMinutes() > 0;
    }

    public static boolean anyDelayed(Collection<EscalationContact> contacts) {
        if (contacts == null) return false;
        for (EscalationContact c : contacts) if (isDelayed(c)) return true;
        return false;
    }

    /** Gecikmeli kişileri çıkarır; gecikmeli kişi yoksa AYNI liste nesnesi döner (bugünkü yol birebir). */
    public static List<EscalationContact> immediateOnly(List<EscalationContact> contacts) {
        return withoutPending(contacts, List.of());
    }

    /**
     * Gecikmeli kişilerden YALNIZ "döngüde" olanları ({@code notifiedContactIds}: adımı gitmiş / alarmı zaten almış)
     * tutar; gecikmesiz kişiler aynen kalır, sıra korunur. Gecikmeli kişi yoksa AYNI liste nesnesi döner.
     */
    public static List<EscalationContact> withoutPending(List<EscalationContact> contacts,
                                                         Collection<Long> notifiedContactIds) {
        if (!anyDelayed(contacts)) return contacts;
        List<EscalationContact> out = new ArrayList<>(contacts.size());
        for (EscalationContact c : contacts) {
            if (!isDelayed(c) || (c.getId() != null && notifiedContactIds != null && notifiedContactIds.contains(c.getId())))
                out.add(c);
        }
        return out;
    }

    /**
     * Yönetim formundan gelen ham değer → gecikme. Boş / null / 0 → null (anlık, bugünkü davranış); 1–1440 arası tam sayı
     * → değer; diğer her şey {@link IllegalArgumentException} (400, kullanıcının dilinde).
     */
    public static Integer parse(Object raw) {
        if (raw == null) return null;
        String s = raw.toString().trim();
        if (s.isEmpty()) return null;
        int v;
        try {
            if (raw instanceof Number n) {
                double d = n.doubleValue();
                if (d != Math.rint(d)) throw new NumberFormatException();
                v = (int) d;
            } else {
                v = Integer.parseInt(s);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(Msg.t(
                    "Gecikme tam sayı (dakika) olmalı.",
                    "Delay must be a whole number of minutes."));
        }
        if (v == 0) return null;
        if (v < MIN_MINUTES || v > MAX_MINUTES) {
            throw new IllegalArgumentException(Msg.t(
                    "Gecikme " + MIN_MINUTES + "–" + MAX_MINUTES + " dakika arasında olmalı (boş = alarm açılınca hemen).",
                    "Delay must be between " + MIN_MINUTES + " and " + MAX_MINUTES + " minutes (empty = immediately when the alert opens)."));
        }
        return v;
    }
}
