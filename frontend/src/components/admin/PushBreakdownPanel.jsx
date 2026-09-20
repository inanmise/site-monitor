import { useState } from 'react'
import { ChevronDown, ChevronRight, Filter } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { formatDate } from '../../api/client'

/**
 * Gönderim logu kırılım paneli (2026-09-21, kullanıcı bildirimi: "kartları daha iyi sunalım, rakamlara tıklayınca liste
 * gelsin"). Her satır: ad hücresi + oran çubuğu (gönderildi / başarısız / diğer) + üç tıklanır sayı (Toplam → yalnız o
 * boyut; Gönderildi → boyut + SENT; Başarısız → boyut + FAILED) + son hata. Tıklama üstteki süzgeci uygular ve ana tabloya
 * kaydırır; aynı seçime ikinci tıklama süzgeci kaldırır. Boyut süzülemiyorsa (takımsız satır) sayı yalnız durumu süzer.
 *
 * @param rows        [{ total, sent, failed, last_failed_at, ... }]
 * @param keyOf       satır anahtarı
 * @param label       (row) → ad hücresi (JSX)
 * @param dim         (row) → boyut süzgeç yaması ({ teamId: '5' } gibi) ya da null (süzülemez)
 * @param isDimActive (row) → boyut süzgeci bu satırda mı
 * @param status      etkin durum süzgeci ('' | 'SENT' | 'FAILED' | …)
 * @param onFilter    (patch) → süzgeci uygula (durum dâhil) ve tabloya kaydır
 */
export default function PushBreakdownPanel({ title, rows = [], keyOf, label, dim, isDimActive, status = '', onFilter, defaultOpen = true }) {
  const t = useT()
  const [open, setOpen] = useState(defaultOpen)
  const max = Math.max(1, ...rows.map((r) => Number(r.total) || 0))
  const activeRows = rows.filter((r) => isDimActive?.(r)).length
  const apply = (r, st) => {
    const d = dim?.(r)
    const dimOn = isDimActive?.(r)
    const same = dimOn && (status || '') === (st || '')
    // aynı seçime ikinci tıklama → o boyut + durum süzgeci kalkar
    const patch = { status: same ? '' : (st || '') }
    if (d) for (const k of Object.keys(d)) patch[k] = same ? '' : d[k]
    onFilter?.(patch)
  }
  const num = (r, st, val, cls) => {
    const on = isDimActive?.(r) && (status || '') === (st || '')
    return (
      <button type="button" className={`pbp-num${cls ? ' ' + cls : ''}${on ? ' is-on' : ''}`} onClick={() => apply(r, st)}
        title={st ? t('pl.bdFilterStatus', st === 'SENT' ? t('health.statusSent') : t('health.statusFailed')) : t('pl.bdFilterAll')}>
        <b>{val ?? 0}</b><span>{st === 'SENT' ? t('health.statusSent') : st === 'FAILED' ? t('health.statusFailed') : t('sml.kpiTotal')}</span>
      </button>
    )
  }

  return (
    <section className={`sml-card pbp${open ? '' : ' is-collapsed'}`} data-testid="pbp">
      <button type="button" className="pbp-head" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
        {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        <span className="pbp-title">{title}</span>
        <span className="pbp-count">{rows.length}</span>
        {activeRows > 0 && <span className="pbp-active"><Filter size={11} /> {t('pl.bdActive')}</span>}
        <span className="pbp-hint">{t('pl.bdHint')}</span>
      </button>
      {open && (rows.length === 0 ? <div className="sml-empty">{t('sml.noData')}</div> : (
        <ul className="pbp-list">
          {rows.map((r) => {
            const total = Number(r.total) || 0, sent = Number(r.sent) || 0, failed = Number(r.failed) || 0
            const other = Math.max(0, total - sent - failed)
            // genişlikler CSS özel değişkeniyle (--w): çok parçalı çubuk ProgressBar'a sığmaz; progress-guard kapısı inline width istemez
            const w = (n) => `${total ? (n / total) * 100 : 0}%`
            return (
              <li key={keyOf(r)} className={`pbp-row${isDimActive?.(r) ? ' is-active' : ''}`}>
                <div className="pbp-name">{label(r)}</div>
                <div className="pbp-bar" title={`${t('health.statusSent')} ${sent} · ${t('health.statusFailed')} ${failed}${other ? ` · ${t('pl.bdOther')} ${other}` : ''}`} style={{ '--w': `${(total / max) * 100}%` }}>
                  <span className="pbp-seg pbp-seg--sent" style={{ '--w': w(sent) }} />
                  <span className="pbp-seg pbp-seg--failed" style={{ '--w': w(failed) }} />
                  <span className="pbp-seg pbp-seg--other" style={{ '--w': w(other) }} />
                </div>
                <div className="pbp-nums">
                  {num(r, '', total)}
                  {num(r, 'SENT', sent, 'pbp-num--ok')}
                  {num(r, 'FAILED', failed, failed > 0 ? 'pbp-num--bad' : '')}
                </div>
                <div className="pbp-last" title={r.last_failed_at ? formatDate(r.last_failed_at) : ''}>
                  <span className="pbp-k">{t('sml.lastFailed')}</span>{r.last_failed_at ? formatDate(r.last_failed_at) : '—'}
                </div>
              </li>
            )
          })}
        </ul>
      ))}
    </section>
  )
}
