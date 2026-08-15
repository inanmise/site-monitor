import { useEffect, useRef, useState } from 'react'
import { RefreshCw, Check, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { ProgressBar, Spinner } from '../ui/Progress.jsx'

// Date → "HH:mm:ss.SSS"
const fmtClock = (d) => (d instanceof Date
  ? d.toTimeString().slice(0, 8) + '.' + String(d.getMilliseconds()).padStart(3, '0')
  : '')
// Geçen süre — <1 sn ise ms, değilse saniye (3 hane ms hassasiyeti).
const fmtDur = (ms) => (ms == null ? '' : ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(3)} s`)
// ISO/tarih → "GG.AA.YYYY" (saat gürültüsü tabloyu şişirmesin)
const fmtDay = (iso) => {
  if (!iso) return '—'
  const d = new Date(iso)
  return isNaN(d) ? '—' : d.toLocaleDateString('tr-TR', { day: '2-digit', month: '2-digit', year: 'numeric' })
}

/** Kalan gün → renk sınıfı (kart/rozet token'larıyla aynı eşikler: <0 dolmuş, <7 kritik, <15 yüksek, <30 uyarı). */
function daysClass(days) {
  if (days == null) return 'chk-d-unknown'
  if (days < 0) return 'chk-d-expired'
  if (days < 7) return 'chk-d-crit'
  if (days < 15) return 'chk-d-high'
  if (days < 30) return 'chk-d-warn'
  return 'chk-d-ok'
}

/** HTTP durum kodu → renk sınıfı (2xx/3xx iyi, 4xx uyarı, 5xx hata). */
function httpClass(code) {
  if (code == null) return 'chk-d-unknown'
  if (code < 400) return 'chk-d-ok'
  if (code < 500) return 'chk-d-warn'
  return 'chk-d-crit'
}

/**
 * "Şimdi Kontrol Et" akan ilerleme tablosu. Her satır tek bir kontrolün SONUCUNU taşır:
 * durum · alan adı · takım · tier · port · HTTP · kalan gün · bitiş · süre.
 *
 * Durum kendi kolonunda (eskiden alan adıyla aynı hücredeydi ve satırlar oynuyordu);
 * alan adı sola, sayısal kolonlar sağa yaslı monospace.
 */
export default function CheckRunModal({ run, certIndex, onClose, onCancel }) {
  const t = useT()
  const listRef = useRef(null)
  const [now, setNow] = useState(() => Date.now())

  // Yeni satır eklendikçe listeyi en alta kaydır (akış efekti).
  useEffect(() => {
    const el = listRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [run?.rows.length])

  // Geçen süre saniyede bir ilerlesin: koşum paralel olduğu için son yavaş kontrol beklenirken
  // yeni satır gelmiyor ve yalnız render'a bağlı bir sayaç donmuş görünürdü.
  const running = !!run && !run.done
  useEffect(() => {
    if (!running) return
    const id = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(id)
  }, [running])

  if (!run) return null

  const rows = run.rows
  const okCount = rows.filter(r => r.ok).length
  const failCount = rows.length - okCount
  // DUVAR SAATİ süresi. Satır sürelerinin toplamı DEĞİL: kontroller paralel koştuğu için o toplam
  // (ör. 8 × 6 sn) gerçekte geçen sürenin çok üstünde çıkar ve kullanıcıya yanlış bilgi verirdi.
  const elapsedMs = run.startedAt ? Math.max(0, (run.finishedAt ?? now) - run.startedAt) : null

  return (
    <div className="modal-overlay">
      <div className="modal-box chk-modal" onClick={e => e.stopPropagation()}>
        <div className="modal-icon-hdr modal-icon-hdr--check">
          <div className="modal-icon-hdr-badge"><RefreshCw size={20} /></div>
          <h3>{t('app.checkProgressTitle')}</h3>
          <span className="chk-count">{rows.length}/{run.total}</span>
        </div>

        {/* Belirli ilerleme: kaç domainin kontrol edildiği zaten biliniyor → yüzde gösterilebilir.
            Native <progress> rolü ve değeri kendisi taşır; başlıktaki x/y ile aynı sayıdan gelir. */}
        <div className="chk-progress">
          <ProgressBar value={rows.length} max={run.total} size="sm"
            label={t('app.checkProgressLabel', rows.length, run.total)} showValue />
        </div>

        <div className="chk-summary">
          <span className="chk-sum-item chk-sum-ok"><Check size={13} />{t('app.checkSummaryOk', okCount)}</span>
          <span className={`chk-sum-item${failCount ? ' chk-sum-fail' : ''}`}><X size={13} />{t('app.checkSummaryFail', failCount)}</span>
          <span className="chk-sum-item chk-sum-time">{t('app.checkSummaryTime', fmtDur(elapsedMs))}</span>
          {run.teamLabel && <span className="chk-sum-item chk-sum-team">{run.teamLabel}</span>}
        </div>

        <div className="chk-list" ref={listRef}>
          <table className="chk-table">
            <thead>
              <tr>
                <th className="chk-th-status" title={t('app.checkColStatus')}>&nbsp;</th>
                <th className="chk-th-domain">{t('app.checkColDomain')}</th>
                <th className="chk-th-team">{t('app.checkColTeam')}</th>
                <th>{t('app.checkColTier')}</th>
                <th>{t('app.checkColPort')}</th>
                <th>{t('app.checkColHttp')}</th>
                <th>{t('app.checkColDays')}</th>
                <th>{t('app.checkColExpiry')}</th>
                <th>{t('app.checkColStart')}</th>
                <th>{t('app.checkColDur')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => {
                const inv = certIndex?.[r.domain] || {}
                const d = r.data || {}
                const days = d.days_remaining ?? null
                const http = d.http_status ?? null
                const port = d.port ?? inv.port ?? null
                const tier = inv.tier ?? null
                return (
                  <tr key={i} className={r.ok ? '' : 'chk-row-err'}>
                    <td className="chk-td-status">
                      <span className={`chk-tick${r.ok ? '' : ' chk-tick-err'}`}>{r.ok ? '✓' : '✕'}</span>
                    </td>
                    <td className="chk-td-domain">
                      <span className="chk-domain-name">{r.domain}</span>
                      {!r.ok && (r.error || d.error) && (
                        <span className="chk-row-msg">{r.error || d.error}</span>
                      )}
                    </td>
                    <td className="chk-td-team">{inv.team_name || '—'}</td>
                    <td>{tier ? <span className={`chk-tier chk-tier-${tier}`}>T{tier}</span> : '—'}</td>
                    <td className="chk-mono">{port ?? '—'}</td>
                    <td className={`chk-mono ${httpClass(http)}`}>{http ?? '—'}</td>
                    <td className={`chk-mono chk-days ${daysClass(days)}`}>
                      {days == null ? '—' : t('app.checkDaysUnit', days)}
                    </td>
                    <td className="chk-mono">{fmtDay(d.not_after)}</td>
                    <td className="chk-mono">{fmtClock(r.start)}</td>
                    <td className="chk-mono"><b>{fmtDur(r.ms)}</b></td>
                  </tr>
                )
              })}
              {!run.done && (
                <tr><td className="chk-pending" colSpan={10}>
                  <Spinner size={14} inline decorative /> {t('app.checking')}
                </td></tr>
              )}
            </tbody>
          </table>
        </div>

        <div className="modal-actions">
          {!run.done && (
            <button className="btn btn-secondary" onClick={onCancel}>{t('app.checkCancel')}</button>
          )}
          <button className="btn btn-secondary" onClick={onClose}>{t('app.close')}</button>
        </div>
      </div>
    </div>
  )
}
