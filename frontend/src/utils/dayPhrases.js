/**
 * Gün sayılı ifadeler — İngilizcede tekil/çoğul ("1 day" / "N days"; Ek 3/1, 2026-09-28). Eskiden `t('inv.expiredAgo', 1)`
 * "Expired 1 days ago" yazıyordu. Proje deseni: ayrı `<anahtar>One` anahtarı (certh.expiredAgoOne, cdp.daysLeftOne);
 * Türkçede iki biçim aynıdır. `n` pozitif tam sayı (geçmiş için çağıran mutlak değeri verir).
 */
export const expiredAgoText = (t, n) => (n === 1 ? t('inv.expiredAgoOne') : t('inv.expiredAgo', n))
export const daysAgoText = (t, n) => (n === 1 ? t('inv.det.daysAgoOne') : t('inv.det.daysAgo', n))
export const expiresInText = (t, n) => (n === 1 ? t('inv.expiresInOne') : t('inv.expiresIn', n))
export const daysShortText = (t, n) => (n === 1 ? t('inv.daysShortOne') : t('inv.daysShort', n))
