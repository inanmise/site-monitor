/**
 * Veritabanı Analitiği — SAF türetimler (React yok). Panel ve alt parçaları AYNI hesaptan beslenir; ton/eşik kararı
 * burada verilir, bileşenler yalnız çizer. Tel biçimi `DbAnalyticsService.getOverview` (snake_case).
 *
 * "Bilinmiyor" ile "sorun yok" AYRI: okunamayan bir kaynak (ör. `db_stats: null`, `connections.states: null`, eski
 * sunucuda hiç olmayan alan) `unknown` tonuna düşer ve ekranda açıkça "Bilinmiyor" yazar — yeşil/sıfır DEĞİL.
 */
import { HEALTH_THRESHOLDS } from '../health/healthModel.js'
import { dateLocale, formatPercent } from '../../../i18n/dateLocale.js'

/** Ortalama/azami sorgu süresi eşikleri — Sistem Sağlığı `HEALTH_THRESHOLDS.dbMs` ile AYNI kaynak. */
export const DB_MS = HEALTH_THRESHOLDS.dbMs
/** Sunucu bağlantı doluluğu (% max_connections). */
export const CONN_PCT = { warn: 70, crit: 90 }
/** Önbellek isabeti (%): OLTP'de ≥ 99 beklenir. */
export const CACHE_PCT = { good: 99, watch: 90 }
/** Ölü satır oranı (%) — küçük tablolar gürültü üretmesin diye en az `minRows` ölü satır şartı. */
export const DEAD = { warn: 10, crit: 20, minRows: 1000 }
/** Geri alınan işlem oranı (%) üstü dikkat. */
export const ROLLBACK_PCT = 5
/** Sıralı tarama ağırlıklı tablo uyarısı: indeks payı bunun altında + yeterince büyük ve okunan tablo. */
export const SEQ_HEAVY = { idxPct: 50, minRows: 10_000, minScans: 1000 }

const isNum = (v) => v !== null && v !== undefined && v !== '' && Number.isFinite(Number(v))

/** Yerel binlik ayraçlı sayı; boş → '—'. */
export function num(v) {
  return isNum(v) ? Number(v).toLocaleString(dateLocale()) : '—'
}

/** Tek ondalıklı YEREL sayı ("99,5" / "99.5") — şablonun kendi % işareti olan yerler için; boş → '—'. */
export function dec(v) {
  return isNum(v) ? Number(v).toLocaleString(dateLocale(), { maximumFractionDigits: 1 }) : '—'
}

/**
 * Yüzde, YEREL ondalıkla (TR "%99,5" / EN "99.5%") — HTTP ekranlarının `fmtPct`'iyle aynı kural (2026-09-28c ek-7):
 * `formatPercent(99.5)` sayıyı olduğu gibi yazıyor, TR'de "%99.5" çıkıyordu. Boş → '—'.
 */
export function pct(v) {
  return isNum(v) ? formatPercent(dec(v)) : '—'
}

/** Bayt → okunur birim (1024 tabanı, pg_size_pretty ile aynı birim adları); boş → '—'. */
export function fmtBytes(b) {
  if (!isNum(b)) return '—'
  const units = ['bytes', 'kB', 'MB', 'GB', 'TB']
  let v = Math.max(0, Number(b)), i = 0
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return `${v.toLocaleString(dateLocale(), { maximumFractionDigits: i === 0 || v >= 100 ? 0 : 1 })} ${units[i]}`
}

/** Tek satıra indirgenmiş kısa önizleme (erişilebilir adlarda satırı ayırt eder). */
export function snippet(s, n = 48) {
  const x = String(s || '').replace(/\s+/g, ' ').trim()
  return x.length > n ? x.slice(0, n) + '…' : x
}

/** null/boş en sonda; sayılar sayısal, metinler yerel sırayla. */
export function compare(a, b) {
  const an = a == null || a === '', bn = b == null || b === ''
  if (an || bn) return an === bn ? 0 : an ? 1 : -1
  if (typeof a === 'number' && typeof b === 'number') return a - b
  if (isNum(a) && isNum(b) && typeof a !== 'boolean' && typeof b !== 'boolean') return Number(a) - Number(b)
  return String(a).localeCompare(String(b), undefined, { numeric: true })
}

/** Arama (küçük harf, `keys` alanlarında) + sıralama. Saf: girdi dizisini değiştirmez. */
export function filterSort(rows, { q = '', keys = [], sort = null, columns = [] } = {}) {
  const n = String(q || '').trim().toLocaleLowerCase(dateLocale())
  const filtered = n ? rows.filter((r) => keys.some((k) => String(r[k] ?? '').toLocaleLowerCase(dateLocale()).includes(n))) : rows
  if (!sort) return filtered
  const col = columns.find((c) => c.key === sort.key)
  const val = col?.sortValue || ((r) => r[sort.key])
  const dir = sort.dir === 'asc' ? 1 : -1
  return [...filtered].sort((a, b) => {
    const va = val(a), vb = val(b)
    const aEmpty = va == null || va === '', bEmpty = vb == null || vb === ''
    if (aEmpty || bEmpty) return aEmpty === bEmpty ? 0 : aEmpty ? 1 : -1   // boşlar yön ne olursa olsun EN SONDA
    return compare(va, vb) * dir
  })
}

// ── Tonlar (ToneBadge dili: success | warning | danger | muted) ─────────────────────────────────────────────

export const msTone = (ms) => (!isNum(ms) ? 'muted' : ms >= DB_MS.crit ? 'danger' : ms >= DB_MS.warn ? 'warning' : 'success')
export const connTone = (pct) => (pct == null ? 'muted' : pct >= CONN_PCT.crit ? 'danger' : pct >= CONN_PCT.warn ? 'warning' : 'success')
export const cacheTone = (pct) => (!isNum(pct) ? 'muted' : pct >= CACHE_PCT.good ? 'success' : pct >= CACHE_PCT.watch ? 'warning' : 'danger')
export const successTone = (pct) => (pct >= 99 ? 'success' : pct >= 95 ? 'warning' : 'danger')
/** Yanıt süresi: negatif = ölçülemedi (bağlantı düştü) → danger. */
export const respTone = (ms) => (!isNum(ms) ? 'muted' : ms < 0 ? 'danger' : ms >= 200 ? 'danger' : ms >= 50 ? 'warning' : 'success')
export function deadTone(row) {
  const pct = row?.dead_pct
  if (!isNum(pct)) return 'muted'
  if (Number(row.dead_rows) < DEAD.minRows) return 'success'
  return pct >= DEAD.crit ? 'danger' : pct >= DEAD.warn ? 'warning' : 'success'
}
/** Bakım (VACUUM) önerilen tablo: ölü oran eşiği aşıldı VE ölü satır sayısı anlamlı. */
export const needsVacuum = (row) => isNum(row?.dead_pct) && Number(row.dead_pct) >= DEAD.crit && Number(row.dead_rows) >= DEAD.minRows
/** Sıralı tarama ağırlıklı büyük tablo. */
export const seqHeavy = (row) => isNum(row?.idx_scan_pct) && Number(row.idx_scan_pct) < SEQ_HEAVY.idxPct
  && Number(row.row_count) >= SEQ_HEAVY.minRows && Number(row.seq_scan) >= SEQ_HEAVY.minScans

// ── Tablolar ──────────────────────────────────────────────────────────────────────────────────────────────

/**
 * `table_sizes` (tüm tablolar) + `top_tables` (okuma/yazma — eski sunucuda yalnız ilk 10) birleşimi. Yeni sunucu
 * okuma/yazmayı zaten her satırda taşır; eski sunucuda kullanım listesinden tamamlanır, iki listeden birinde olup
 * diğerinde olmayan tablo KAYBOLMAZ.
 */
export function buildTables(d) {
  const use = new Map((d?.top_tables || []).map((r) => [r.table_name, r]))
  const rows = (d?.table_sizes || []).map((r) => {
    const u = use.get(r.table_name)
    const total = Number(r.total_size_bytes), table = Number(r.table_size_bytes), index = Number(r.index_size_bytes)
    const other = isNum(r.total_size_bytes) && isNum(r.table_size_bytes) && isNum(r.index_size_bytes)
      ? Math.max(0, total - table - index) : null
    return {
      ...r,
      reads: r.reads ?? u?.reads ?? null,
      writes: r.writes ?? u?.writes ?? null,
      other_size_bytes: other,
    }
  })
  for (const [name, u] of use) {
    if (!rows.some((r) => r.table_name === name)) rows.push({ table_name: name, row_count: u.row_count, reads: u.reads, writes: u.writes })
  }
  return rows
}

/** Genel ölü satır oranı + bakım önerilen tablo sayısı; tablolarda ölü satır alanı yoksa (eski sunucu) null. */
export function deadSummary(tables) {
  const known = tables.filter((r) => isNum(r.dead_rows))
  if (!known.length) return null
  const dead = known.reduce((s, r) => s + Number(r.dead_rows), 0)
  const live = known.reduce((s, r) => s + (Number(r.row_count) || 0), 0)
  return {
    pct: dead + live > 0 ? Math.round((dead * 1000) / (dead + live)) / 10 : null,
    dead,
    vacuum: known.filter(needsVacuum).length,
  }
}

// ── Grafik ────────────────────────────────────────────────────────────────────────────────────────────────

/** Sunucu kovası (UTC ISO, Z'siz) → Date. */
export function parseTs(ts) {
  const s = String(ts ?? '')
  return new Date(s + (s.endsWith('Z') ? '' : 'Z'))
}

/**
 * Seri → grafik noktaları. Sorgu olmayan kovada ortalama süre `null` (çizgi KOPAR) — "0 ms" bir ölçüm değildir.
 * `gran`: 'hour' (1 günlük pencere) | 'day'.
 */
export function buildChart(series, gran) {
  const loc = dateLocale()
  const p2 = (n) => String(n).padStart(2, '0')
  const points = (series || []).map((b) => {
    const dt = parseTs(b.ts)
    const ok = !Number.isNaN(dt.getTime())
    const count = Number(b.count) || 0
    const failed = Math.min(count, Number(b.failed) || 0)
    const avg = count > 0 && isNum(b.avg_ms) ? Number(b.avg_ms) : null
    return {
      ts: b.ts,
      label: !ok ? String(b.ts) : gran === 'hour' ? `${p2(dt.getHours())}:00` : `${p2(dt.getDate())}.${p2(dt.getMonth() + 1)}`,
      full: !ok ? String(b.ts) : dt.toLocaleString(loc, gran === 'hour'
        ? { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }
        : { weekday: 'short', day: '2-digit', month: '2-digit' }),
      count, failed, ok: count - failed, avg,
    }
  })
  const total = points.reduce((s, p) => s + p.count, 0)
  const failed = points.reduce((s, p) => s + p.failed, 0)
  const peak = points.reduce((best, p) => (p.count > (best?.count ?? 0) ? p : best), null)
  const avgs = points.map((p) => p.avg).filter((v) => v != null)
  return {
    points,
    total,
    failed,
    errPct: total > 0 ? Math.round((failed * 1000) / total) / 10 : null,
    peak,
    maxAvg: avgs.length ? Math.max(...avgs) : 0,
    withData: points.filter((p) => p.count > 0).length,
    empty: total === 0,
  }
}

// ── Bağlantılar ───────────────────────────────────────────────────────────────────────────────────────────

/** pg_stat_activity durumu → sabit sıra + i18n anahtarı + renk sınıfı (durum anlamı taşıyanlar durum renginde). */
export const STATE_META = {
  active: { order: 0, label: 'dba.stateActive', dot: 'bg-chart-1' },
  idle: { order: 1, label: 'dba.stateIdle', dot: 'bg-muted-foreground/45' },
  'idle in transaction': { order: 2, label: 'dba.stateIdleTx', dot: 'bg-amber-500' },
  'idle in transaction (aborted)': { order: 3, label: 'dba.stateIdleTxAborted', dot: 'bg-destructive' },
  other: { order: 4, label: 'dba.stateOther', dot: 'bg-chart-5' },
  unknown: { order: 5, label: 'dba.stateUnknown', dot: 'bg-muted-foreground/20' },
}

/** `connections.states` → sabit sıralı segmentler; null = bilinmiyor (eski sunucu ya da okunamadı). */
export function connStates(conn) {
  const list = conn?.states
  if (!Array.isArray(list)) return null
  const merged = new Map()
  for (const s of list) {
    const key = STATE_META[s?.state] ? s.state : 'other'
    merged.set(key, (merged.get(key) || 0) + (Number(s?.count) || 0))
  }
  const total = [...merged.values()].reduce((a, b) => a + b, 0)
  return [...merged.entries()]
    .filter(([, n]) => n > 0)
    .map(([state, count]) => ({ state, count, pct: total > 0 ? (count * 100) / total : 0, ...STATE_META[state] }))
    .sort((a, b) => a.order - b.order)
}

/** Bağlantı özeti: doluluk + tonu + uyarı sinyalleri (bilinmeyen alan null kalır). */
export function connModel(d) {
  const conn = d?.connections || {}
  const sum = d?.summary || {}
  const active = conn.active ?? sum.active_connections ?? null
  const max = conn.max ?? null
  const pct = isNum(max) && Number(max) > 0 && isNum(active) ? Math.round((Number(active) * 100) / Number(max)) : null
  return {
    active, max, pct, tone: connTone(pct),
    resp: conn.response_ms ?? null,
    states: connStates(conn),
    idleInTx: conn.idle_in_tx ?? null,
    longQueries: conn.long_queries ?? null,
    lockWaits: conn.lock_waits ?? null,
    longestQuery: conn.longest_query_s ?? null,
    longestXact: conn.longest_xact_s ?? null,
    longThreshold: conn.long_threshold_s ?? 60,
  }
}

// ── Başarısız sorgular ────────────────────────────────────────────────────────────────────────────────────

/** Hata iletisinden kaba sınıf (rozet) — metin uydurulmaz, yalnız sınıflanır; tam ileti ayrıntıda. */
export function errorKind(msg) {
  const m = String(msg || '')
  if (!m) return null
  if (/timeout|canceling statement|zaman aşımı/i.test(m)) return 'timeout'
  if (/read-only transaction|salt okunur/i.test(m)) return 'readonly'
  if (/permission denied|not allowed|forbidden|yetki|izin verilmiyor|yasak/i.test(m)) return 'denied'
  if (/syntax error|sözdizimi/i.test(m)) return 'syntax'
  if (/does not exist|bulunamad|unknown column|undefined/i.test(m)) return 'missing'
  return 'other'
}

/** "SQLSTATE: 57014" / "SQL state [42P01]" → kod; yoksa null. */
export function sqlState(msg) {
  const m = /SQL\s*STATE\W{0,3}([0-9A-Z]{5})\b/i.exec(String(msg || ''))
  return m ? m[1].toUpperCase() : null
}
