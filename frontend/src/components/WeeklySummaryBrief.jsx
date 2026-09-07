import CircularGauge from './ui/CircularGauge.jsx'
import { ShieldAlert, CalendarClock } from 'lucide-react'

const BAND_COLOR = {
  green: 'var(--ok, #16a34a)',
  amber: 'var(--severity-warn, #d97706)',
  red: 'var(--danger, #dc3545)',
}

/** Backend snake_case (spring.jackson SNAKE_CASE) → normalize; camelCase fallback güvenlik için. */
function normItem(a) {
  return {
    name: a.name,
    type: a.type,
    tier: a.tier,
    daysLeft: a.days_left ?? a.daysLeft,
    hasOpenAlarm: a.has_open_alarm ?? a.hasOpenAlarm,
  }
}

function ActionList({ items, t }) {
  if (!items || items.length === 0) return <div className="wr-sum-empty">{t('wr.sumNoRecords')}</div>
  return (
    <div className="wr-sum-list">
      {items.map((raw, i) => {
        const a = normItem(raw)
        return (
          <div key={`${a.name}#${i}`} className={`wr-sum-row wr-sum-tier${a.tier ?? 0}`}>
            <span className="wr-sum-name" title={a.name}>{a.name}</span>
            <span className="wr-sum-type">
              {t(a.type === 'domain' ? 'wr.sumTypeDomain' : 'wr.sumTypeCert')}
            </span>
            <span className="wr-sum-days">{a.daysLeft != null ? t('wr.sumDaysLeft', a.daysLeft) : '—'}</span>
            {a.hasOpenAlarm && <span className="wr-sum-alarm" title={t('wr.sumHasAlarm')} aria-label={t('wr.sumHasAlarm')}>●</span>}
          </div>
        )
      })}
    </div>
  )
}

/**
 * Executive özet — 01 · Özet bloğu. Sol: yönetici paragrafı; sağ: sağlık skoru (dairesel gösterge + delta);
 * altında Aksiyon Gerektirenler kolonu + (kayıt varsa) Önümüzdeki 30 Gün kolonu — lookahead boşsa o kolon
 * hiç render edilmez (kullanıcı isteği). `kpis.summary` yoksa (eski/veri-yok) hiç render etmez.
 */
export default function WeeklySummaryBrief({ kpis, t, lang }) {
  const s = kpis?.summary
  if (!s) return null
  const score = s.score || {}
  const scoreVal = score.value ?? 0
  const band = score.band || 'red'
  const color = BAND_COLOR[band] || BAND_COLOR.red
  const delta = score.delta
  const managerText = s.manager_text ?? s.managerText ?? {}
  const paragraph = managerText[lang === 'en' ? 'en' : 'tr'] || managerText.tr || ''
  const lookahead = s.lookahead ?? s.lookahead14

  return (
    <div className="wr-sum">
      <div className="wr-sum-hdr">
        <div className="wr-sum-para">{paragraph}</div>
        <div className="wr-sum-score">
          <div className="wr-sum-gauge">
            <CircularGauge value={scoreVal} color={color} />
            <div className="wr-sum-score-num" style={{ color }}>{scoreVal}</div>
          </div>
          {delta != null && delta !== 0 && (
            <div className={`wr-sum-delta ${delta > 0 ? 'wr-sum-delta--up' : 'wr-sum-delta--down'}`}>
              {delta > 0 ? '▲' : '▼'} {Math.abs(delta)}
            </div>
          )}
          <div className="wr-sum-score-lbl">{t('wr.sumScore')}</div>
        </div>
      </div>
      <div className="wr-sum-cols">
        <div className="wr-sum-col">
          <div className="wr-sum-col-hdr"><ShieldAlert size={14} /> {t('wr.sumActions')}</div>
          <ActionList items={s.actions} t={t} />
        </div>
        {lookahead && lookahead.length > 0 && (
          <div className="wr-sum-col">
            <div className="wr-sum-col-hdr"><CalendarClock size={14} /> {t('wr.sumLookahead')}</div>
            <ActionList items={lookahead} t={t} />
          </div>
        )}
      </div>
    </div>
  )
}
