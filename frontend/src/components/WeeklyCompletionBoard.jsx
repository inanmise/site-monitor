import { useEffect, useState } from 'react'
import { ChevronDown, LayoutGrid, Printer, Download } from 'lucide-react'
import { api } from '../api/client'
import { useT } from '../i18n/index.jsx'
import TeamBadge from './ui/TeamBadge.jsx'
import { buildYearSummaryCsv, buildYearSummaryHtml } from './weekly/weeklyModel.js'
import { Button } from '@/components/shadcn/button'

/**
 * Haftalık rapor takım tamamlama panosu (2026-09-12, zenginleştirme #21): takım × hafta ısı haritası.
 * Hücre rengi durum (girilmedi / taslak / onay bekliyor / onaylandı / reddedildi); en eksik takım üstte;
 * hücreye tıklayınca liste o takım + haftaya süzülür. Yalnız global admin / AUDIT için veri gelir
 * (diğerlerinde sunucu boş döner → pano çizilmez).
 */
const STATUS_CLASS = { MISSING: 'missing', DRAFT: 'draft', PENDING_APPROVAL: 'pending', APPROVED: 'approved', REJECTED: 'rejected' }

export default function WeeklyCompletionBoard({ year, onPick }) {
  const t = useT()
  const [data, setData] = useState(null)
  // Varsayılan KAPALI (kullanıcı kararı 2026-09-13): pano listeyi aşağı itiyordu; açan kişinin tercihi bu tarayıcıda kalır.
  const [open, setOpen] = useState(() => { try { return localStorage.getItem('wr-completion-open') === 'true' } catch { return false } })

  useEffect(() => {
    let alive = true
    ;(async () => {
      try { const r = await api.weeklyReports.completion(year); if (alive && r?.success && r.data) setData(r.data) }
      catch { /* pano süs */ }
    })()
    return () => { alive = false }
  }, [year])

  if (!data || !(data.teams || []).length) return null
  const weeks = Array.from({ length: data.weeks || 0 }, (_, i) => i + 1)
  const toggle = () => setOpen((o) => { try { localStorage.setItem('wr-completion-open', String(!o)) } catch { /* yoksay */ } return !o })

  // Yönetici yıl özeti (2026-09-13, ikinci tur): matris CSV + yazdırılabilir sayfa (tarayıcı "PDF olarak kaydet").
  function downloadCsv() {
    const csv = buildYearSummaryCsv(data, t)
    try {
      const blob = new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8' }); const url = URL.createObjectURL(blob)
      const a = document.createElement('a'); a.href = url; a.download = `haftalik-yil-ozeti-${data.year}.csv`; document.body.appendChild(a); a.click(); a.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch { /* jsdom */ }
  }
  function printSummary() {
    // Gizli iframe: uygulama CSS'inden bağımsız, A4 yatay tek sayfa; yazdırma bitince kaldırılır.
    const html = buildYearSummaryHtml(data, t)
    try {
      const f = document.createElement('iframe')
      f.setAttribute('aria-hidden', 'true'); f.setAttribute('title', 'print'); f.className = 'wrc-print-frame'
      document.body.appendChild(f)
      const d = f.contentDocument; d.open(); d.write(html); d.close()
      const w = f.contentWindow
      const done = () => setTimeout(() => { try { f.remove() } catch { /* yoksay */ } }, 1500)
      w.onafterprint = done
      setTimeout(() => { try { w.focus(); w.print() } catch { done() } }, 60)
    } catch { /* jsdom */ }
  }

  return (
    <section className="wrc" aria-label={t('wrc.title')}>
      <button type="button" className="wrc-head" aria-expanded={open} onClick={toggle}>
        <LayoutGrid size={16} aria-hidden="true" />
        <span className="wrc-title">{t('wrc.title', data.year)}</span>
        <span className={`wrc-summary${data.total_missing > 0 ? ' is-warn' : ' is-ok'}`}>
          {data.total_missing > 0 ? t('wrc.missing', data.total_missing) : t('wrc.allDone')}
        </span>
        <ChevronDown size={16} className={`wrc-chevron${open ? ' is-open' : ''}`} aria-hidden="true" />
      </button>
      {open && (
        <div className="wrc-body">
          <div className="wrc-scroll">
            <table className="wrc-grid">
              <thead>
                <tr>
                  <th className="wrc-team-th">{t('wrc.team')}</th>
                  {weeks.map((w) => <th key={w} className={`wrc-week-th${w === data.current_week ? ' is-current' : ''}`}>{w}</th>)}
                  <th className="wrc-sum-th">{t('wrc.sum')}</th>
                </tr>
              </thead>
              <tbody>
                {data.teams.map((tm) => (
                  <tr key={tm.team_id}>
                    <td className="wrc-team-td"><TeamBadge teamId={tm.team_id} teamName={tm.team_name} />{!tm.reminder && <span className="wrc-noremind" title={t('wrc.noReminder')}>⏸</span>}</td>
                    {(tm.cells || []).map((c) => (
                      <td key={c.week} className="wrc-cell-td">
                        <button type="button" className={`wrc-cell wrc-cell--${STATUS_CLASS[c.status] || 'missing'}${c.week === data.current_week ? ' is-current' : ''}`}
                          title={`${tm.team_name} · ${t('wrc.week', c.week)} · ${t(`wrc.status.${c.status}`)}`}
                          aria-label={`${tm.team_name} ${t('wrc.week', c.week)} ${t(`wrc.status.${c.status}`)}`}
                          onClick={() => onPick?.(tm.team_id, c.week, c.report_id)} />
                      </td>
                    ))}
                    <td className="wrc-sum-td"><b className="is-ok">{tm.approved}</b> / <b className={tm.missing > 0 ? 'is-warn' : ''}>{tm.missing}</b></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="wrc-legend">
            {Object.entries(STATUS_CLASS).map(([k, cls]) => (
              <span key={k} className="wrc-legend-item"><span className={`wrc-cell wrc-cell--${cls} wrc-cell--legend`} /> {t(`wrc.status.${k}`)}</span>
            ))}
            <span className="wrc-legend-item wrc-legend-note">{t('wrc.legendSum')}</span>
          </div>
          <div className="wrc-tools">
            <span className="wrc-tools-label">{t('wr.yearSummary')}:</span>
            <Button type="button" variant="secondary" size="sm" onClick={printSummary} title={t('wr.yearSummaryPrintTitle')}>
              <Printer size={13} /> {t('wr.yearSummaryPrint')}
            </Button>
            <Button type="button" variant="secondary" size="sm" onClick={downloadCsv} title={t('wr.yearSummaryCsvTitle')}>
              <Download size={13} /> CSV
            </Button>
          </div>
        </div>
      )}
    </section>
  )
}
