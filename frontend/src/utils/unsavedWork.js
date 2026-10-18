import { useEffect } from 'react'

/**
 * KAYDEDİLMEMİŞ İŞ KAYDI (2026-10-18, Görsel panelinin "Ana pencerede aç"ı): kaydedilmemiş değişiklik tutan ekranlar
 * (rapor düzenleyicisi, kanal şablonu, izin matrisi, giriş anomalisi ayarları — `beforeunload` uyarısı olanlar) açıkken
 * bunu buraya bildirir. Başka bir pencereden gelen "şu raporu aç" isteği uygulama içinde sayfa değiştirmeden önce sorar:
 * kayıt varsa istek yerine getirilmez (kullanıcıya "önce kaydedin / kapatın" denir) — taslak sessizce kaybolmaz.
 *
 * <p>Uygulama içi olağan gezinme (kenar çubuğu) bu kaydı okumaz; davranışı değişmez.
 */
const holders = new Set()
let seq = 0

/** Kaydedilmemiş iş tutan bir ekran açık mı? */
export function hasUnsavedWork() {
  return holders.size > 0
}

/** Ekran `active` iken kaydedilmemiş iş tutar; kapanınca / değişiklik kalmayınca kayıt silinir. */
export function useUnsavedWork(active) {
  useEffect(() => {
    if (!active) return undefined
    const token = ++seq
    holders.add(token)
    return () => { holders.delete(token) }
  }, [active])
}

/** Testler: kaydı boşaltır. */
export function resetUnsavedWorkForTests() {
  holders.clear()
}
