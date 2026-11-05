/**
 * Veritabanı Sorgu İzleme — LİSTE ODAK GÖRÜNÜMLERİ modeli (saf; 2026-11-05, dbq2 C3).
 *
 * <p>İki istemci görünümü, ikisi de URL'de (sayfa durumu paramları — sekme değişince App temizler, kayıtlı görünüme girer):
 *  - "Şu an ihlalde" hızlı süzgeci `dq=breach` (izleme sayfalarının hızlı süzgeç anahtarı): son çalıştırması eşik aşımı
 *    (BREACH) ya da hata (FAIL) olan VEYA açık alarmı olan etkin izlemeler. Duraklatılmış izleme sayılmaz.
 *  - Bağlantıya göre gruplu liste `sort=conn`: aynı bağlantıyı kullanan izlemeler bir başlık altında; başlıkta bağlantı adı,
 *    veritabanı türü ve durum özeti. Gruplar en kötü durumdan iyiye, sonra ada göre (Türkçe sıralama); bağlantısı
 *    okunamayan / silinmiş izlemeler kendi grubunda, aynı durumdakilerin sonunda.
 * Satır verisi okunmaz — yalnız liste satırının durum alanları.
 */

export const FOCUS_BREACH = 'breach'
export const GROUP_BY_CONNECTION = 'conn'
/** Bağlantısı olmayan (okunamayan / silinmiş) izlemelerin grup anahtarı. */
export const NO_CONNECTION_KEY = 'none'

/** URL değeri → odak süzgeci (tanınmayan → null; sayfa yalnız bilinen değeri yazar). */
export function focusFromUrl(v) { return v === FOCUS_BREACH ? FOCUS_BREACH : null }
/** URL değeri → gruplama (tanınmayan → null). */
export function groupingFromUrl(v) { return v === GROUP_BY_CONNECTION ? GROUP_BY_CONNECTION : null }

/** "Şu an ihlalde": etkin ∧ (eşik aşımı ∨ hata ∨ açık alarm). Bilinmeyen / değerlendirilemeyen durum ihlal DEĞİLDİR. */
export function inBreachNow(m) {
  if (!m || m.active === false) return false
  return m.status === 'BREACH' || m.status === 'FAIL' || !!m.active_alarm
}

/** Bağlantı grubu anahtarı: `p:<profil kimliği>`; bağlantı okunamıyor / silinmiş → {@link NO_CONNECTION_KEY}. */
export function connectionKey(m) {
  if (!m || m.profile_missing || m.profile_id == null || !m.profile) return NO_CONNECTION_KEY
  return `p:${m.profile_id}`
}

/**
 * İzlemenin grup içindeki durum sınıfı: paused | fail | breach | slow | unknown | ok. Yavaş sorgu (OK + `slow`) ayrı bir
 * uyarı sınıfıdır ("temiz" sayılmaz); hiç çalışmamış / değerlendirilemeyen → unknown (asla ok).
 */
export function rowState(m) {
  if (m?.active === false) return 'paused'
  if (m?.status === 'FAIL') return 'fail'
  if (m?.status === 'BREACH') return 'breach'
  if (m?.status === 'OK') return m.slow ? 'slow' : 'ok'
  return 'unknown'
}

/** Grubun en kötü durumunun sırası (küçük = önce). */
const WORST_RANK = { fail: 0, breach: 1, slow: 2, unknown: 3, ok: 4, paused: 5 }
const COLLATOR = new Intl.Collator('tr', { sensitivity: 'base', numeric: true })

/**
 * İzlemeler → bağlantı grupları. Her grup: `{ key, profileId, name, dbType, teamId, monitors, counts, worst }`;
 * `counts` = { total, active, fail, breach, slow, unknown, ok, paused, alarm, alarmOnly } — `alarm` açık alarmı olan etkin
 * izlemeler, `alarmOnly` bunlardan son çalıştırması hata / ihlal OLMAYANLAR (alarmı henüz kapanmamış). Grup içindeki sıra
 * GİRİŞ sırasıdır (çağıran varsayılan kart sırasını verir).
 */
export function groupByConnection(list) {
  const groups = new Map()
  for (const m of Array.isArray(list) ? list : []) {
    if (!m) continue
    const key = connectionKey(m)
    let g = groups.get(key)
    if (!g) {
      const p = key === NO_CONNECTION_KEY ? null : m.profile
      g = {
        key, profileId: p ? m.profile_id : null, name: p?.name || null, dbType: p?.db_type || null, teamId: m.team_id ?? null,
        monitors: [], counts: { total: 0, active: 0, fail: 0, breach: 0, slow: 0, unknown: 0, ok: 0, paused: 0, alarm: 0, alarmOnly: 0 },
      }
      groups.set(key, g)
    }
    g.monitors.push(m)
    const s = rowState(m)
    g.counts.total += 1
    g.counts[s] += 1
    if (s !== 'paused') g.counts.active += 1
    if (m.active !== false && m.active_alarm) {
      g.counts.alarm += 1
      if (s !== 'fail' && s !== 'breach') g.counts.alarmOnly += 1
    }
  }
  const out = [...groups.values()].map((g) => ({ ...g, worst: worstOf(g.counts) }))
  return out.sort((a, b) => WORST_RANK[a.worst] - WORST_RANK[b.worst]
    || (a.key === NO_CONNECTION_KEY) - (b.key === NO_CONNECTION_KEY)
    || COLLATOR.compare(String(a.name || ''), String(b.name || ''))
    || String(a.key).localeCompare(String(b.key)))
}

/** Sayaçlardan en kötü durum (açık alarm, durumu OK olsa da ihlal sayılır). */
function worstOf(c) {
  if (c.fail > 0) return 'fail'
  if (c.breach > 0 || c.alarm > 0) return 'breach'
  if (c.slow > 0) return 'slow'
  if (c.unknown > 0) return 'unknown'
  if (c.ok > 0) return 'ok'
  return 'paused'
}

/** Grupların izlemelerini grup sırasıyla düz listeye açar (sayfalama bu sırayla — grup sayfa sınırında bölünebilir). */
export function flattenGroups(groups) {
  return (Array.isArray(groups) ? groups : []).flatMap((g) => g.monitors)
}

/**
 * Sayfadaki izlemeler → başlıklı bölümler: `[{ group, items, continued }]`. `continued` = grubun ilk izlemesi önceki
 * sayfada kaldı (başlık "devam" der). `byKey` tüm grupların kimlik dizini (başlık özeti TÜM listeden sayılır).
 */
export function pageSections(pageItems, groups) {
  const byKey = new Map((Array.isArray(groups) ? groups : []).map((g) => [g.key, g]))
  const out = []
  for (const m of Array.isArray(pageItems) ? pageItems : []) {
    const key = connectionKey(m)
    const last = out[out.length - 1]
    if (last && last.group.key === key) { last.items.push(m); continue }
    const group = byKey.get(key) || { key, name: null, dbType: null, monitors: [m], counts: { total: 1 }, worst: rowState(m) }
    out.push({ group, items: [m], continued: group.monitors[0] !== m })
  }
  return out
}

/** Takım listesinden bağlantı sağlığı dizini: profil kimliği → sağlık durumu (ok | failing | untested | unused | unknown). */
export const HEALTH_STATES = Object.freeze(['ok', 'failing', 'untested', 'unused'])
export function healthIndex(rows) {
  const out = new Map()
  for (const p of Array.isArray(rows) ? rows : []) {
    if (!p || p.id == null) continue
    const st = p.health?.state
    out.set(String(p.id), HEALTH_STATES.includes(st) ? st : 'unknown')
  }
  return out
}

/** Grupların ait olduğu takımlar (bağlantı sağlığı takım listesinden okunur) — en çok `max` (istek sayısı sınırlı). */
export function groupTeams(groups, max = 10) {
  const out = []
  for (const g of Array.isArray(groups) ? groups : []) {
    if (g.key === NO_CONNECTION_KEY || g.teamId == null) continue
    const k = String(g.teamId)
    if (!out.includes(k)) out.push(k)
    if (out.length >= max) break
  }
  return out
}
