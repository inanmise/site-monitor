// İzleme türü sayfalarının VARSAYILAN kart sırası (2026-10-01, kullanıcı kararı) — dokuz türün tek kaynağı.
//
//   1) Sorunlu kartlar önce: kırmızı rozet ("down"), sonra sarı ("warn": yavaş / bozulmuş / uyarı / doğrulama yok);
//      duraklatılmış izleme sorunlu sayılmaz.
//   2) Grup adı olanlar: grup adı A→Z, aynı gruptakiler art arda, grup içinde ad A→Z.
//   3) Grup adı olmayanlar: ad A→Z.
//   (Eşitlikte kimlik — sayfalar arası kararlı sıra.)
//
// "Sorunlu" her sayfanın KARTTA gösterdiği durumla birebir: aşağıdaki TIER eşlemeleri sayfaların `statusKey`
// fonksiyonlarının aynası (HttpMonitorPage / KeywordMonitorPage: up|unknown dışı → down; Ping: down; Port: closed;
// DNS: açık alarm; Alan Adı: domainCardModel.statusKey — CRITICAL → down, WARNING → warn; Sayfa: DOWN / DEGRADED;
// Sayfa Hızı: DOWN / SLOW; Sentetik: FAIL|ERROR|TIMEOUT / NO_CHECKS). Bir sayfanın statusKey'i değişirse burası da
// değişir — `monitorSort.test.js` her türün eşlemesini pinler.

export const TIER_DOWN = 0
export const TIER_WARN = 1
export const TIER_REST = 2

const httpLike = (m) => (m.status === 'up' || m.status === 'unknown' ? TIER_REST : TIER_DOWN)

export const STATUS_TIER = {
  http: httpLike,
  keyword: httpLike,
  ping: (m) => (m.status === 'down' ? TIER_DOWN : TIER_REST),
  port: (m) => (m.status === 'closed' ? TIER_DOWN : TIER_REST),
  dns: (m) => (m.active_alarm ? TIER_DOWN : TIER_REST),
  domain: (m) => (m.status === 'CRITICAL' ? TIER_DOWN : m.status === 'WARNING' ? TIER_WARN : TIER_REST),
  page: (m) => (m.status === 'DOWN' ? TIER_DOWN : m.status === 'DEGRADED' ? TIER_WARN : TIER_REST),
  pagespeed: (m) => (m.status === 'DOWN' ? TIER_DOWN : m.status === 'SLOW' ? TIER_WARN : TIER_REST),
  scripted: (m) => (m.status === 'FAIL' || m.status === 'ERROR' || m.status === 'TIMEOUT' ? TIER_DOWN
    : m.status === 'NO_CHECKS' ? TIER_WARN : TIER_REST),
}

/** Sıralama katmanı — duraklatılmış izleme (active === false) her türde sorunlu sayılmaz. */
export function tierOf(m, type) {
  if (!m || m.active === false) return TIER_REST
  const fn = STATUS_TIER[type]
  return fn ? fn(m) : TIER_REST
}

// Türkçe harf düzeni, büyük/küçük harf duyarsız, sayılar doğal sırada ("web-2" < "web-10").
const COLLATOR = new Intl.Collator('tr', { sensitivity: 'base', numeric: true })

const groupOf = (m) => String(m?.group_name ?? '').trim()
/** Kartın başlığı: ad; yoksa hedef (alan adı / host / URL). */
export const nameOf = (m) => String(m?.name || m?.domain || m?.host || m?.url || '').trim()

export function compareMonitorsDefault(a, b, type) {
  const ta = tierOf(a, type), tb = tierOf(b, type)
  if (ta !== tb) return ta - tb
  const ga = groupOf(a), gb = groupOf(b)
  if (!!ga !== !!gb) return ga ? -1 : 1           // grup adı olanlar önce
  if (ga && gb) {
    const g = COLLATOR.compare(ga, gb)
    if (g !== 0) return g
  }
  const n = COLLATOR.compare(nameOf(a), nameOf(b))
  if (n !== 0) return n
  return (Number(a?.id) || 0) - (Number(b?.id) || 0)
}

/** Varsayılan sırayla YENİ dizi döner (girdiyi değiştirmez). */
export function sortMonitorsDefault(list, type) {
  return [...(list || [])].sort((a, b) => compareMonitorsDefault(a, b, type))
}
