import { vi } from 'vitest'
import { act } from '@testing-library/react'

/** jsdom'un kare döngüsü ~16,7 ms'de bir döner; bir adım bir kareyi kesin kapsar. */
const FRAME_MS = 17
/** Döngünün boşa düşüp kendini kapatması için verilen en çok kare (sınırlı — sonsuz döngü yok). */
const MAX_FRAMES = 10

/**
 * Sahte saatten gerçek saate güvenli dönüş (2026-11-16, jsdom 31 + Vitest 5).
 *
 * jsdom 31 odak düzeltmesini (odaklı öğe DOM'dan kalkınca odağı gövdeye taşıma) ve requestAnimationFrame karelerini tek
 * bir `setInterval` döngüsüyle çalıştırır; döngünün kimliği pencerenin kapanımında saklanır ve yalnız döngü BOŞTAYKEN
 * (bekleyen kare / odak düzeltmesi yokken) `null` olur. Döngü sahte saat açıkken kurulursa `vi.useRealTimers()` sahte
 * zamanlayıcıyı yok eder ama kimlik `null` olmaz: aynı test dosyasında bundan sonra ne animasyon karesi ne odak
 * düzeltmesi çalışır (LoginOtp "hata odak" testi rAF ile verilen odağı hiç görmedi).
 *
 * Gerçek saate dönmeden önce sahte saat birkaç kare ilerletilir (her adım `act` içinde, React etkileri de boşalır): döngü
 * işini bitirir ve kendini kapatır. Sahte saat açık değilse yalnız `vi.useRealTimers()`.
 */
export function restoreRealTimers() {
  if (vi.isFakeTimers()) {
    for (let i = 0; i < MAX_FRAMES; i++) act(() => { vi.advanceTimersByTime(FRAME_MS) })
  }
  vi.useRealTimers()
}
