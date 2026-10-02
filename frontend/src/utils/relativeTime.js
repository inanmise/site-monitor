/**
 * Göreli zaman — ORTAK yardımcılar (2026-10-02, öneri 29). Kural: ekranda okunan metin DEĞİŞMEZ. Buraya yalnız birden
 * çok yerde kelimesi kelimesine aynı olan hesap taşındı; ayrıştırma (sunucu damgasının nasıl okunduğu) ve boş/bozuk
 * damga karşılığı her çağıranda farklı olabildiği için çağıran kendi kuralını uygular, sonra buradaki çekirdeği çağırır.
 * Kilit: `test/relativeTime.characterization.test.js` (taşıma öncesi gövdeler kâhin olarak; geniş girdi matrisi).
 *
 * Bilinçli olarak BURAYA TAŞINMAYANLAR (eşik/sözcük/anahtar farklı — taşımak metni değiştirirdi): nocCallModel.relTime
 * (45 sn, yuvarlama, "1 gün"), issuesModel.fmtRelative (aynı metin ama `myIssues.*` anahtarları), healthModel.relTime
 * (iki birimli, gelecek "sonra"), overviewModel.formatAge (10 sn), ActionNoteDialog.agoText, CertificateLiveStrip.ago
 * (kısa biçim, 48 sa), Intl tabanlılar (inboxModel, releaseModel, sqlUtils, incidentHistoryModel, certDetailsModel).
 */

/**
 * `act.rel.*` dağarcığı: "az önce" (< 60 sn) · "N dk önce" · "N sa önce" · "N gün önce". Her basamak aşağı yuvarlanır;
 * gelecekteki damga (saat kayması) "az önce" okunur. `thenMs` sonlu bir epoch ms olmalı (ayrıştırma çağıranda).
 */
export function agoText(thenMs, t, nowMs = Date.now()) {
  const sec = Math.floor(Math.max(0, nowMs - thenMs) / 1000)
  if (sec < 60) return t('act.rel.now')
  const min = Math.floor(sec / 60); if (min < 60) return t('act.rel.min', min)
  const hr = Math.floor(min / 60); if (hr < 24) return t('act.rel.hour', hr)
  return t('act.rel.day', Math.floor(hr / 24))
}

/**
 * Sunucu damgası → epoch ms. Sunucu UTC'yi bölgesiz gönderir: damgada 'Z'/'z' ya da sonda ±hh[:]mm yoksa 'Z' eklenir.
 * Boş → NaN; bozuk → NaN. (İzleme Değişiklikleri / Aktivite Logu / Son Giriş'in ortak okuma kuralı.)
 */
export function zonedMs(iso) {
  if (!iso) return NaN
  const s = /[zZ]|[+-]\d\d:?\d\d$/.test(iso) ? iso : iso + 'Z'
  return new Date(s).getTime()
}

/** `act.rel.*` metni; boş damga "—", çözülemeyen damga OLDUĞU GİBİ döner (İzleme Değişiklikleri, Aktivite Logu). */
export function relTimeOrRaw(iso, t, now = Date.now()) {
  const then = zonedMs(iso)
  if (Number.isNaN(then)) return iso || '—'
  return agoText(then, t, now)
}

/**
 * Parça biçimi `{ unit: 'sec'|'min'|'hour'|'day'|'month'|'year', n }` — çağıran `t('<önek>.rel.<unit>', n)` ile yazar
 * (Tüm Sertifikalar `tbl.rel.*`, Kullanıcı/Oturum `uact.rel.*`). Her basamak Math.round (zincirleme), "az önce" yok
 * (0 sn), ay = 30 gün, yıl = 12 ay; gelecek damga 0 sn.
 */
export function roundedAgoParts(thenMs, nowMs = Date.now()) {
  const s = Math.max(0, Math.round((nowMs - thenMs) / 1000))
  if (s < 60) return { unit: 'sec', n: s }
  const m = Math.round(s / 60); if (m < 60) return { unit: 'min', n: m }
  const h = Math.round(m / 60); if (h < 24) return { unit: 'hour', n: h }
  const d = Math.round(h / 24); if (d < 30) return { unit: 'day', n: d }
  const mo = Math.round(d / 30); if (mo < 12) return { unit: 'month', n: mo }
  return { unit: 'year', n: Math.round(mo / 12) }
}
