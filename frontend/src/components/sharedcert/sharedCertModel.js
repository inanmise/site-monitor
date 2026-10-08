/**
 * Paylaşılan sertifika penceresi — saf model (2026-10-08, kullanıcı: "paylaşılan sertifika penceresinde takım bazlı, grup
 * bazlı görebilmeliyim; PDF ve Excel olarak dışa alabilmeliyim"). React yok: pencere, PDF ve Excel AYNI gruplamayı ve
 * AYNI satır içeriğini buradan okur — ekranda gördüğünüz düzen dosyaya da aynen çıkar.
 */

/** Görünümler: düz liste · takıma göre · gruba göre (envanterdeki grup adı). */
export const SHARED_VIEWS = Object.freeze(['list', 'team', 'group'])

const collator = new Intl.Collator('tr', { sensitivity: 'base', numeric: true })

const fieldOf = (by) => (by === 'team' ? 'team_name' : by === 'group' ? 'group_name' : null)

/**
 * Eşleri bölümlere ayırır: `[{ key, label, rows, none }]`. Ad A→Z (Türkçe, sayısal), atanmamışlar ("Takımsız" /
 * "Grupsuz") EN SONDA; bölüm içinde sunucunun sırası (alan adı) korunur. `list` → tek, başlıksız bölüm.
 */
export function groupPeers(peers, by, { noneLabel = '—' } = {}) {
  const rows = Array.isArray(peers) ? peers : []
  const field = fieldOf(by)
  if (!field) return [{ key: '__all__', label: null, rows, none: false }]
  const map = new Map()
  for (const p of rows) {
    const name = String(p?.[field] ?? '').trim()
    const key = name ? `n:${name.toLocaleLowerCase('tr')}` : '__none__'
    if (!map.has(key)) map.set(key, { key, label: name || noneLabel, rows: [], none: !name })
    map.get(key).rows.push(p)
  }
  return [...map.values()].sort((a, b) => (Number(a.none) - Number(b.none)) || collator.compare(a.label, b.label))
}

/** Kalan gün metni — pencerenin "Kalan / Bitiş" sütunuyla aynı dil. */
export function daysText(p, t) {
  const d = p?.days_remaining
  if (d === null || d === undefined || !Number.isFinite(Number(d))) return '—'
  return Number(d) < 0 ? t('shc.expiredAgo', Math.abs(Number(d))) : t('shc.daysLeft', Number(d))
}

/** Satır notu: bu kartın alanı / envanterde olmayan alan. */
export function noteText(p, t) {
  const notes = []
  if (p?.self) notes.push(t('shc.thisDomain'))
  if (p && p.in_inventory === false) notes.push(t('shc.notInInventory'))
  return notes.join(' · ')
}

/**
 * Dışa aktarma tablosu: sütun başlıkları + bölümler (her satır hücre dizisi). `fmtDate` / `fmtDateSec` çağırandan
 * (istemci modülünü mock'layan testler kendi biçimleyicisini verir).
 */
export function exportTable(data, by, t, { fmtDate = (v) => v ?? '', fmtDateSec = (v) => v ?? '' } = {}) {
  const noneLabel = by === 'team' ? t('shc.noTeam') : t('shc.noGroup')
  const columns = [
    t('shc.colDomain'), t('shc.colTeam'), t('shc.colGroup'), t('shc.colPlatform'), t('shc.colTier'),
    t('shc.colDays'), t('shc.colDaysNum'), t('shc.expiry'), t('shc.colChecked'), t('shc.colNote'),
  ]
  const toRow = (p) => [
    p.domain + (p.port && p.port !== 443 ? `:${p.port}` : ''),
    p.team_name || '',
    p.group_name || '',
    p.platform ? p.platform + (p.platform_detail ? ` · ${p.platform_detail}` : '') : '',
    p.tier ? `T${p.tier}` : '',
    daysText(p, t),
    Number.isFinite(Number(p.days_remaining)) && p.days_remaining !== null && p.days_remaining !== undefined ? Number(p.days_remaining) : null,
    p.not_after ? fmtDate(p.not_after) : '',
    p.checked_at ? fmtDateSec(p.checked_at) : '',
    noteText(p, t),
  ]
  const sections = groupPeers(data?.peers, by, { noneLabel }).map((s) => ({ ...s, cells: s.rows.map(toRow) }))
  return { columns, sections }
}

/** Sertifikanın kendisi (anahtar → değer satırları) — PDF üst bloğu ve Excel "Sertifika" sayfası. */
export function certInfoRows(data, t, { fmtDate = (v) => v ?? '' } = {}) {
  const d = data || {}
  const days = d.days_remaining
  return [
    [t('shc.colDomain'), d.domain || ''],
    [t('shc.subject'), d.subject || ''],
    [t('shc.issuer'), d.issuer || ''],
    [t('shc.expiry'), d.not_after ? `${fmtDate(d.not_after)}${days !== null && days !== undefined ? ` · ${daysText(d, t)}` : ''}` : ''],
    [t('shc.fingerprint'), d.fingerprint || ''],
    [t('shc.san', Array.isArray(d.san) ? d.san.length : 0), Array.isArray(d.san) ? d.san.join(', ') : ''],
    [t('shc.peersTitle', Array.isArray(d.peers) ? d.peers.length : 0), d.hidden > 0 ? t('shc.hidden', d.hidden) : ''],
  ]
}

/** Dosya adı: önek + güvenli alan adı + tarih (YYYY-MM-DD). */
export function exportFileName(prefix, domain, ext, now = new Date()) {
  const safe = String(domain || 'cert').toLowerCase().replace(/[^a-z0-9.-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 80) || 'cert'
  const pad = (n) => String(n).padStart(2, '0')
  return `${prefix}-${safe}-${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}.${ext}`
}
