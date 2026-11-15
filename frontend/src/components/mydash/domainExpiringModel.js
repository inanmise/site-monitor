/**
 * Kişisel Panom "Yakında bitecek alan adları" kutusu (2026-11-15, C2) — SAF özet. Kaynak mevcut uç
 * `GET /api/monitoring/domain` (kapsam sunucuda — kullanıcının görebildiği alan adı izlemeleri). Yeni uç YOK.
 *
 * <p>Kalan gün CANLI (`domainCardModel.liveDays`, A1 — kartla aynı gün). Satır tonu izlemenin KENDİ eşiklerinden (sunucu
 * kuralı: gün ≤ kritik → kritik, ≤ max(uyarı, kritik) → uyarı; eşik yoksa kritik 7 / uyarı 30). Ton: dolmuş ya da kritik
 * varsa kırmızı, uyarı eşiğinde varsa turuncu; YEŞİL yalnız etkin izlemelerin HEPSİNİN bitişi biliniyor ve hiçbiri eşikte
 * değilken; bitişi bilinmeyen varsa ya da hiç izleme yoksa gri (karar verilemez — asla yeşil değil).
 */
import { liveDays } from '../domain/domainCardModel.js'

const COLLATOR = new Intl.Collator('tr', { sensitivity: 'base', numeric: true })
export const DEFAULT_CRITICAL_DAYS = 7
export const DEFAULT_WARNING_DAYS = 30

const num = (v) => {
  if (v == null || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/** Tek satırın durumu: expired | critical | warning | ok | unknown (bitiş yok). */
export function domainRowState(days, warningDays, criticalDays) {
  if (days == null) return 'unknown'
  const crit = num(criticalDays) > 0 ? num(criticalDays) : DEFAULT_CRITICAL_DAYS
  const warn = num(warningDays) > 0 ? num(warningDays) : DEFAULT_WARNING_DAYS
  if (days < 0) return 'expired'
  if (days <= crit) return 'critical'
  if (days <= Math.max(warn, crit)) return 'warning'
  return 'ok'
}

/**
 * → `{ hidden, active, known, unknown, expired, critical, warning, ok, rows, more, tone }`. `rows` en çok `limit` satır,
 * en az gün önce (dolmuş en başta): `{ id, name, domain, days, state, team }`. Sayılar görüntü sınırından BAĞIMSIZ.
 */
export function domainExpiring(data, limit = 5, now = Date.now()) {
  const empty = { active: 0, known: 0, unknown: 0, expired: 0, critical: 0, warning: 0, ok: 0, rows: [], more: 0, tone: 'muted' }
  if (data && typeof data === 'object' && !Array.isArray(data) && data.visible === false) return { hidden: true, ...empty }
  const list = (Array.isArray(data) ? data : []).filter((m) => m && typeof m === 'object' && m.active !== false)
  const out = { ...empty, active: list.length }
  const known = []
  for (const m of list) {
    const days = liveDays(m, now)
    const state = domainRowState(days, m.warning_days, m.critical_days)
    out[state] += 1
    if (state === 'unknown') continue
    known.push({ id: m.id, name: m.name || m.domain || null, domain: m.domain || null, days, state, team: m.team_name || null })
  }
  out.known = known.length
  known.sort((a, b) => a.days - b.days || COLLATOR.compare(String(a.domain || ''), String(b.domain || '')))
  const cap = Math.max(0, limit | 0)
  out.rows = known.slice(0, cap)
  out.more = Math.max(0, known.length - cap)
  out.tone = out.expired + out.critical > 0 ? 'danger'
    : out.warning > 0 ? 'warning'
    : out.active > 0 && out.unknown === 0 ? 'success'
    : 'muted'
  return { hidden: false, ...out }
}
