import { ChevronRight, CheckCircle2, AlertTriangle, Lock } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { ProgressBar } from '../../ui/Progress.jsx'
import HelpTip from '../../ui/HelpTip.jsx'

/** Hızlı seçim çipleri — en sık kullanılan saklama pencereleri. */
const QUICK_DAYS = [30, 90, 180, 365, 730]

export const fmtBytes = (n) => {
  if (n == null) return '—'
  const u = ['B', 'KB', 'MB', 'GB', 'TB']
  let v = Number(n), i = 0
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++ }
  return `${v < 10 && i > 0 ? v.toFixed(1) : Math.round(v)} ${u[i]}`
}
export const fmtNum = (n) => {
  if (n == null) return '—'
  const x = Number(n)
  if (x >= 1_000_000) return (x / 1_000_000).toFixed(1).replace(/\.0$/, '') + 'M'
  if (x >= 1_000) return (x / 1_000).toFixed(1).replace(/\.0$/, '') + 'K'
  return String(x)
}
export const fmtDay = (iso) =>
  (iso ? String(iso).slice(0, 10).split('-').reverse().join('.') : '—')

/**
 * Tek saklama politikası satırı: solda kimlik, ortada süre kontrolü, sağda ölçüm kümesi.
 * Satır açılınca silme kuralı, taban/varsayılan, son temizlik ve uyum onayı görünür.
 *
 * Tablo yerine CSS-grid: 8 kolonlu sabit tablo dar ekranda sıkışıyordu ve başlıkları
 * "sıralanabilir" gibi görünüyordu (yanlış affordance).
 */
export default function PolicyRow({
  policy, value, original, maxRows, expanded, onToggle, onChange, approval, onApprove, disabled,
}) {
  const t = useT()
  const p = policy
  const changed = p.configurable && String(value) !== String(original)
  const shortened = changed && Number(value) < Number(original)
  const pct = maxRows > 0 ? Math.max(1, Math.round((Number(p.rows) || 0) / maxRows * 100)) : 0
  const needsApproval = p.data_class === 'PERSONAL' || p.data_class === 'SECURITY_AUDIT'

  return (
    <div className={`ret-row${changed ? ' ret-row--changed' : ''}${expanded ? ' ret-row--open' : ''}`}>
      <div className="ret-row-main">
        <button type="button" className="ret-row-toggle" onClick={onToggle}
          aria-expanded={expanded} aria-label={p.table}>
          <ChevronRight size={15} className={expanded ? 'ret-chev-open' : ''} />
        </button>

        {/* Kimlik */}
        <div className="ret-row-id">
          <div className="ret-row-name">
            {p.table}
            {/* Ayarı olmayan (ORPHAN/EXTERNAL) politikada helpKey boştur → HelpTip hiç çizilmez. */}
            <HelpTip helpKey={p.setting_key ? 'help.set.' + p.setting_key : ''} label={p.table} />
            {changed && (
              <span className={`ret-delta${shortened ? ' ret-delta--down' : ''}`}>
                {original} → {value}
              </span>
            )}
          </div>
          <div className="ret-row-why" title={p.rationale}>{p.rationale}</div>
        </div>

        {/* Süre kontrolü */}
        <div className="ret-row-ctl">
          {p.configurable ? (
            <>
              <div className="ret-chips">
                {QUICK_DAYS.map(d => (
                  <button key={d} type="button" disabled={disabled || d < p.min_days}
                    title={d < p.min_days ? t('ret.minHint', p.min_days) : undefined}
                    className={`ret-chip${String(value) === String(d) ? ' is-active' : ''}`}
                    onClick={() => onChange(String(d))}>{d}</button>
                ))}
              </div>
              <div className="ret-num-wrap">
                <input type="number" className="ret-num" min={p.zero_means_never ? 0 : p.min_days}
                  value={value} disabled={disabled}
                  onChange={e => onChange(e.target.value)} />
                <span className="ret-num-unit">{t('ret.daysShort')}</span>
              </div>
            </>
          ) : (
            <span className={`ret-mode-chip ret-mode-chip--${p.mode.toLowerCase()}`}>
              {p.mode === 'EXTERNAL' && <Lock size={11} />}
              {t(`ret.mode.${p.mode}`)}
            </span>
          )}
        </div>

        {/* Ölçüm kümesi */}
        <div className="ret-metrics">
          <div className="ret-metric">
            <span className="ret-metric-val">{fmtNum(p.rows)}</span>
            <span className="ret-metric-lbl">{t('ret.colRows')}</span>
          </div>
          <div className="ret-metric">
            <span className="ret-metric-val">{fmtBytes(p.bytes)}</span>
            <span className="ret-metric-lbl">{t('ret.colSize')}</span>
          </div>
          <div className="ret-metric ret-metric--span">
            <span className="ret-metric-val ret-metric-range">{fmtDay(p.oldest_at)} → {fmtDay(p.newest_at)}</span>
            <span className="ret-metric-lbl">{t('ret.rangeLbl')}</span>
          </div>
          <div className={`ret-metric${p.purgeable > 0 ? ' ret-metric--danger' : ''}`}>
            <span className="ret-metric-val">{p.deletes ? fmtNum(p.purgeable) : '—'}</span>
            <span className="ret-metric-lbl">{t('ret.colPurgeable')}</span>
          </div>
          {/* decorative: aynı oran zaten satır/boyut metinleri olarak görünüyor. */}
          <ProgressBar value={pct} max={100} size="sm" decorative className="ret-bar" />
        </div>
      </div>

      {expanded && (
        <div className="ret-row-detail">
          <dl className="ret-dl">
            <dt>{t('ret.detailRule')}</dt>
            <dd className="ret-rule-sql">{p.rule || t(`ret.mode.${p.mode}`)}</dd>
            <dt>{t('ret.detailColumn')}</dt>
            <dd>{p.time_column || '—'}</dd>
            <dt>{t('ret.detailFloor')}</dt>
            <dd>{p.configurable ? t('ret.detailFloorVal', p.min_days, p.default_days) : '—'}</dd>
            <dt>{t('ret.detailKey')}</dt>
            <dd className="ret-rule-sql">{p.setting_key || '—'}</dd>
          </dl>

          {needsApproval && (
            <button type="button" className={`ret-approve${approval ? ' is-approved' : ''}`}
              onClick={onApprove}>
              {approval
                ? <><CheckCircle2 size={13} />{t('ret.approvedBy', approval.by, fmtDay(approval.at))}
                    {approval.note ? <span className="ret-approve-note">· {approval.note}</span> : null}</>
                : <><AlertTriangle size={13} />{t('ret.approvePending')}</>}
            </button>
          )}
        </div>
      )}
    </div>
  )
}
