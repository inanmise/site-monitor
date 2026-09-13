import { ArrowUp, ArrowDown, Sparkles, Eye, EyeOff } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { STATUS_CHIPS, scoreBand, delta } from './weeklyModel.js'

/** Durum çipleri (facet sayaçlı) + "Onayımı bekleyenler" (2026-09-13). */
export function WeeklyStatusChips({ facets, value, onChange, mineCount, showMine }) {
  const t = useT()
  const label = (k) => t(k === 'PENDING_APPROVAL' ? 'wr.statusPending' : `wr.status${k.charAt(0) + k.slice(1).toLowerCase()}`)
  return (
    <div className="wr-chips" role="group" aria-label={t('wr.statusCol')} data-tour="wr-chips">
      <button type="button" className={`invtb-chip${!value ? ' is-on' : ''}`} aria-pressed={!value} onClick={() => onChange('')}>{t('wr.chipAll')} ({facets.all})</button>
      {showMine && (
        <button type="button" className={`invtb-chip wr-chip-mine${value === 'MINE' ? ' is-on' : ''}`} aria-pressed={value === 'MINE'} onClick={() => onChange(value === 'MINE' ? '' : 'MINE')}>
          {t('wr.chipMine')} ({mineCount})
        </button>
      )}
      {STATUS_CHIPS.map((k) => (
        <button key={k} type="button" className={`invtb-chip${value === k ? ' is-on' : ''}`} aria-pressed={value === k} onClick={() => onChange(value === k ? '' : k)}>
          {label(k)} ({facets[k] ?? 0})
        </button>
      ))}
    </div>
  )
}

/** Sıralanabilir başlık (aria-sort). */
export function SortTh({ col, label, sort, onSort, style }) {
  const [k, d] = String(sort || '').split('|')
  const on = k === col
  return (
    <th style={style} aria-sort={on ? (d === 'desc' ? 'descending' : 'ascending') : 'none'}>
      <button type="button" className="inv-th-btn" onClick={() => onSort(`${col}|${on && d === 'desc' ? 'asc' : 'desc'}`)}>
        {label} {on ? (d === 'desc' ? <ArrowDown size={11} /> : <ArrowUp size={11} />) : null}
      </button>
    </th>
  )
}

/** Skor rozeti (gönderim anı; null → —). */
export function ScoreBadge({ score, title }) {
  const band = scoreBand(score)
  if (band == null) return <span className="wr-score wr-score--none">—</span>
  return <span className={`wr-score wr-score--${band}`} title={title}>{score}</span>
}

/** Önceki haftaya göre fark rozeti; goodWhenDown: azalış iyi (olay/alarm sayıları). */
export function DeltaBadge({ cur, prev, goodWhenDown = true }) {
  const t = useT()
  const d = delta(cur, prev)
  if (d == null || d === 0) return null
  const good = goodWhenDown ? d < 0 : d > 0
  return <span className={`wr-delta wr-delta--${good ? 'good' : 'bad'}`} title={t('wr.deltaTitle', prev)}>{d > 0 ? '▲' : '▼'} {Math.abs(d)}</span>
}

/** Sistemden öneri rozeti: değer elle girilenden farklıysa "Uygula". */
export function SuggestBadge({ value, current, onApply, label }) {
  const t = useT()
  if (value == null) return null
  const same = Number(current) === Number(value)
  return (
    <span className={`wr-suggest${same ? ' is-same' : ''}`} title={label}>
      <Sparkles size={12} /> {t('wr.sugSystem')} {value}
      {!same && <button type="button" className="tour-link" onClick={() => onApply(Number(value))}>{t('wr.sugApply')}</button>}
    </span>
  )
}

/** "Geçen haftanın notu" — bölüm altında açılır-kapanır ham not (markdown metni). */
export function PrevNoteToggle({ open, onToggle, note, weekLabel }) {
  const t = useT()
  if (!note) return null
  return (
    <div className="wr-prev">
      <button type="button" className="tour-link" onClick={onToggle} aria-expanded={open}>
        {open ? <EyeOff size={12} /> : <Eye size={12} />} {open ? t('wr.prevHide') : t('wr.prevShow', weekLabel || '')}
      </button>
      {open && <pre className="wr-prev-note">{note}</pre>}
    </div>
  )
}
