import { vi, afterAll } from 'vitest'

/**
 * Alan adı kalan gün testleri için sabit "bugün" (2026-11-15, inceleme LOW 2). Kalan gün İSTANBUL takvimiyle sayılır
 * (`domainCardModel.domainDaysUntil` = sunucunun `DomainCheckerService.daysUntil`'i); fikstürler ise YEREL tarihten
 * (`todayKey()`, `new Date().getDate()`) kurulur. CI (UTC) 21:00–24:00 arasında yerel tarih İstanbul'un bir gün gerisindedir —
 * gün sayısını doğrulayan testler gece titrerdi.
 *
 * Saat YALNIZ `Date` sahtelenerek (zamanlayıcılar gerçek; `shouldAdvanceTime` — zaman akar) yerel tarihin 09:00 UTC'sine
 * alınır: o an yerel tarih, UTC tarihi ve İstanbul tarihi UTC−9 … UTC+14 dilimlerinin hepsinde AYNI gündür. Test dosyasında
 * modül gövdesinden ÖNCE çağrılmalı (fikstürler bu saatle kurulsun):
 *
 * <pre>await vi.hoisted(async () => (await import('./helpers/orgNoonClock.js')).freezeOrgNoon())</pre>
 *
 * Dosyanın kendi testi `vi.useRealTimers()` çağırırsa ardından {@link refreezeOrgNoon} çağırır.
 */
let frozenAt = null

export function orgNoonMs(now = new Date()) {
  return Date.UTC(now.getFullYear(), now.getMonth(), now.getDate(), 9, 0, 0)
}

export function freezeOrgNoon() {
  frozenAt = orgNoonMs()
  vi.useFakeTimers({ toFake: ['Date'], shouldAdvanceTime: true })
  vi.setSystemTime(frozenAt)
  afterAll(() => vi.useRealTimers())
}

/** Dosyanın bir testi gerçek saate döndüyse sabit güne geri al (fikstürler o güne göre kuruldu). */
export function refreezeOrgNoon() {
  vi.useFakeTimers({ toFake: ['Date'], shouldAdvanceTime: true })
  vi.setSystemTime(frozenAt ?? orgNoonMs())
}
