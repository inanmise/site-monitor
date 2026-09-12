import { useMemo, useState } from 'react'
import { ShieldCheck, AlertTriangle, ChevronDown, Sparkles } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'

/**
 * Hijyen bandı (2026-09-12, #2): e-posta raporunun analizi sayfada — sayaçlar tıklanınca ilgili
 * süzgeç uygulanır (hygiene kodu). #12 çakışma sezgisi aynı bandın altında (yalnız öneri).
 *
 * @param data   /admin/inventory/hygiene yanıtı {total, scanned, groups:[{key,total,findings:[{domain,codes}]}]}
 * @param active seçili hijyen kodu (filters.hygiene)
 */
export const HYGIENE_CODES = ['no_team', 'no_tier', 'no_contacts', 'never_checked', 'stale', 'error', 'expired', 'revoked', 'chain', 'deployment', 'weak']

/** domain → Set(codes) — süzgeç bunu okur. */
export function hygieneIndex(data) {
  const idx = {}
  for (const g of data?.groups || []) for (const f of g.findings || []) {
    if (!idx[f.domain]) idx[f.domain] = new Set()
    for (const c of f.codes || []) idx[f.domain].add(c)
  }
  return idx
}

export default function InventoryHygieneBand({ data, overlaps, active, onSelect, onOpenDomain }) {
  const t = useT()
  const [open, setOpen] = useState(() => { try { return localStorage.getItem('inv-hygiene-open') !== 'false' } catch { return true } })
  const counts = useMemo(() => {
    const c = {}
    for (const g of data?.groups || []) for (const f of g.findings || []) for (const code of f.codes || []) c[code] = (c[code] || 0) + 1
    return c
  }, [data])
  if (!data) return null
  const total = data.total || 0
  const toggle = () => setOpen((o) => { try { localStorage.setItem('inv-hygiene-open', String(!o)) } catch { /* yoksay */ } return !o })
  const codes = HYGIENE_CODES.filter((k) => counts[k] > 0)
  const tone = total === 0 ? 'ok' : (counts.expired || counts.revoked || counts.error) ? 'bad' : 'warn'

  return (
    <section className={`invhy invhy--${tone}`} aria-label={t('inv.hyTitle')}>
      <button type="button" className="invhy-head" aria-expanded={open} onClick={toggle}>
        {total === 0 ? <ShieldCheck size={16} aria-hidden="true" /> : <AlertTriangle size={16} aria-hidden="true" />}
        <span className="invhy-title">{t('inv.hyTitle')}</span>
        <span className="invhy-sum">{total === 0 ? t('inv.hyClean', data.scanned ?? 0) : t('inv.hySummary', total, data.scanned ?? 0)}</span>
        <ChevronDown size={16} className={`invhy-chev${open ? ' is-open' : ''}`} aria-hidden="true" />
      </button>
      {open && total > 0 && (
        <div className="invhy-body">
          <div className="invhy-chips">
            {codes.map((k) => (
              <button key={k} type="button" className={`invhy-chip invhy-chip--${k}${active === k ? ' is-on' : ''}`}
                onClick={() => onSelect(active === k ? '' : k)} aria-pressed={active === k} title={t('inv.hyShow')}>
                <b>{counts[k]}</b> {t(`inv.hy.${k}`)}
              </button>
            ))}
            {active && <button type="button" className="btn btn-sm btn-secondary" onClick={() => onSelect('')}>{t('inv.filterClear')}</button>}
          </div>
          <p className="invhy-hint">{t('inv.hyHint')}</p>
        </div>
      )}
      {open && overlaps?.total > 0 && (
        <div className="invhy-overlap">
          <div className="invhy-overlap-title"><Sparkles size={13} aria-hidden="true" /> {t('inv.hyOverlapTitle', overlaps.total)}</div>
          <ul className="invhy-overlap-list">
            {overlaps.wildcard.slice(0, 5).map((o) => <li key={'w' + o.domain}>{t('inv.hyOverlapWildcard', o.domain, o.by)} <button type="button" className="today-link" onClick={() => onOpenDomain?.(o.domain)}>{o.domain}</button></li>)}
            {overlaps.www.slice(0, 5).map((o) => <li key={'x' + o.domain}>{t('inv.hyOverlapWww', o.domain, o.by)}</li>)}
            {overlaps.port.slice(0, 5).map((o) => <li key={'p' + o.domain}>{t('inv.hyOverlapPort', o.domain, o.by)}</li>)}
            {overlaps.total > 15 && <li className="invhy-more">+{overlaps.total - 15}</li>}
          </ul>
        </div>
      )}
    </section>
  )
}
