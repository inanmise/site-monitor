import { describe, it, expect } from 'vitest'
import { toApiTime, startOfLocalDay, startOfLastNDays } from '../utils/apiTime.js'

/**
 * Sunucuya gönderilen zaman sınırlarının SÖZLEŞMESİ.
 *
 * <p>2026-08-23 hatası: İzleme Değişiklikleri konsolu aralığı yerel saat bileşenleriyle
 * dizeye çeviriyordu. Backend UTC sakladığı için Türkiye'de (UTC+3) "Bugün" penceresi üç saat
 * geç başlıyor, gecenin ilk üç saatindeki değişiklikler listeden SESSİZCE düşüyordu. Ekran
 * çalışıyor, hata vermiyor, sadece eksik gösteriyordu — bu yüzden ancak bir sözleşme testi
 * yakalayabilir.
 */
describe('apiTime — zaman sınırı sözleşmesi', () => {
  it('toApiTime UTC üretir: yerel saatle DEĞİL, gerçek anla', () => {
    const d = new Date('2026-08-23T00:00:00+03:00')      // İstanbul gece yarısı
    expect(toApiTime(d)).toBe('2026-08-22T21:00:00')     // aynı an, UTC
  })

  it('biçim saniye hassasiyetinde ve EK TAŞIMAZ (sunucu ISO metin karşılaştırıyor)', () => {
    expect(toApiTime(new Date('2026-08-23T10:20:30.456Z'))).toBe('2026-08-23T10:20:30')
  })

  it('geçersiz girdi boş dize döner — "Invalid Date" dizesi sorguya sızmaz', () => {
    expect(toApiTime(null)).toBe('')
    expect(toApiTime(new Date('olmayan-tarih'))).toBe('')
    expect(toApiTime('2026-08-23')).toBe('')
  })

  it('startOfLocalDay KULLANICININ gününü verir (UTC gününü değil)', () => {
    const noon = new Date(2026, 7, 23, 12, 34, 56, 789)
    const start = startOfLocalDay(noon)
    expect([start.getFullYear(), start.getMonth(), start.getDate()]).toEqual([2026, 7, 23])
    expect([start.getHours(), start.getMinutes(), start.getSeconds(), start.getMilliseconds()])
      .toEqual([0, 0, 0, 0])
    // Girdi MUTASYONA uğramaz — çağıran kendi Date'ini kaybetmemeli.
    expect(noon.getHours()).toBe(12)
  })

  it('"son N gün" BUGÜNÜ DÂHİL sayar: 7 gün = bugün + önceki 6', () => {
    const now = new Date(2026, 7, 23, 15, 0, 0)
    const start = startOfLastNDays(7, now)
    expect([start.getFullYear(), start.getMonth(), start.getDate()]).toEqual([2026, 7, 17])
    expect(start.getHours()).toBe(0)
  })

  it('ay ve yıl sınırını doğru geçer', () => {
    const start = startOfLastNDays(30, new Date(2026, 0, 5, 9, 0, 0))   // 5 Ocak 2026
    expect([start.getFullYear(), start.getMonth(), start.getDate()]).toEqual([2025, 11, 7])
  })

  it('bozuk gün sayısı en az 1 güne çekilir (sonsuz/negatif pencere olmaz)', () => {
    const now = new Date(2026, 7, 23, 9, 0, 0)
    for (const bad of [0, -5, NaN, undefined, 'abc']) {
      const start = startOfLastNDays(bad, now)
      expect(start.getDate()).toBe(23)
    }
  })

  it('sınır → gönderim → geri okuma turu ANI korur', () => {
    // Konsolun yaptığı tam zincir: yerel gün başı → UTC dize → (sunucu) → gösterim için Date.
    const localMidnight = startOfLocalDay(new Date(2026, 7, 23, 18, 0, 0))
    const sent = toApiTime(localMidnight)
    const readBack = new Date(sent + 'Z')                // formatDateSec'in toUtc'si ile aynı kural
    expect(readBack.getTime()).toBe(localMidnight.getTime())
  })
})
