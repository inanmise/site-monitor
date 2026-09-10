package com.sitemonitor.service;

import java.util.Set;

/**
 * {@link AppSettingsService} override haritası değişince yayınlanır (ekrandan kayıt ya da
 * başka pod'un yazdığını periyodik tazelemede fark etme). Tüketiciler okuma-anı getter'larla
 * zaten canlı; bu olay OKUMAYAN, kendine "uygulanması" gereken bileşenler içindir
 * (örn. executor havuz/kuyruk boyutu, log seviyesi gibi durum taşıyanlar).
 *
 * @param changedKeys değişen anahtarlar; periyodik tazelemede tam fark bilinmiyorsa boş küme
 *                    ("hepsi değişmiş olabilir" — dinleyici kendi anahtarını yeniden okur).
 * @param source      {@code "save"} (ekran) ya da {@code "refresh"} (DB'den tazeleme)
 */
public record AppSettingsChangedEvent(Set<String> changedKeys, String source) {

    public boolean touches(String key) {
        return changedKeys.isEmpty() || changedKeys.contains(key);
    }
}
