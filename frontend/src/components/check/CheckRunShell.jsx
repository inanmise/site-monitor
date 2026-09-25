import { useEffect, useRef, useState } from 'react'
import { dateLocale } from '../../i18n/dateLocale.js'
import { RefreshCw, Check, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { ProgressBar, Spinner } from '../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'

// Date → "HH:mm:ss.SSS"
export const fmtClock = (d) => (d instanceof Date
  ? d.toTimeString().slice(0, 8) + '.' + String(d.getMilliseconds()).padStart(3, '0')
  : '')
// Geçen süre — <1 sn ise ms, değilse saniye (3 hane ms hassasiyeti).
export const fmtDur = (ms) => (ms == null ? '' : ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(3)} s`)
// ISO/tarih → "GG.AA.YYYY" (saat gürültüsü tabloyu şişirmesin)
export const fmtDay = (iso) => {
  if (!iso) return '—'
  const d = new Date(iso)
  return isNaN(d) ? '—' : d.toLocaleDateString(dateLocale(), { day: '2-digit', month: '2-digit', year: 'numeric' })
}

/** Kalan gün → renk sınıfı (kart/rozet token'larıyla aynı eşikler: <0 dolmuş, <7 kritik, <15 yüksek, <30 uyarı). */
export function daysClass(days) {
  if (days == null) return 'chk-d-unknown'
  if (days < 0) return 'chk-d-expired'
  if (days < 7) return 'chk-d-crit'
  if (days < 15) return 'chk-d-high'
  if (days < 30) return 'chk-d-warn'
  return 'chk-d-ok'
}

/** HTTP durum kodu → renk sınıfı (2xx/3xx iyi, 4xx uyarı, 5xx hata). */
export function httpClass(code) {
  if (code == null) return 'chk-d-unknown'
  if (code < 400) return 'chk-d-ok'
  if (code < 500) return 'chk-d-warn'
  return 'chk-d-crit'
}

/**
 * Kolonun hücre sınıfı. Varsayılan `chk-mono` (sayısal kolonlar); boş dize verilirse sınıf
 * HİÇ yazılmaz — `class=""` bırakmak sertifika tablosunun mevcut DOM'unu değiştirirdi.
 */
function tdClassOf(c, row) {
  const cls = typeof c.tdClassName === 'function' ? c.tdClassName(row) : c.tdClassName
  if (cls === undefined) return 'chk-mono'
  return cls || undefined
}

/**
 * "Şimdi Kontrol Et" akan ilerleme tablosunun İSKELETİ: ilerleme çubuğu, özet şeridi, akış
 * kaydırması, duvar saati ve Durdur/Kapat eylemleri.
 *
 * <p><b>Neden ayrı bileşen:</b> aynı koşum yüzeyi iki yerde gerekiyor — sertifika panosunda
 * (alan adı · tier · kalan gün) ve dokuz izleme sayfasında (izleme · hedef · yanıt süresi).
 * Ortak olan tabloların KOLONLARI değil, çevresindeki her şey: satırlar geldikçe en alta kayan
 * liste, paralel koşumda donmayan saniye sayacı, kaç/kaç ilerleme ve "uçuştakiler bitsin"
 * anlamındaki Durdur. Bunları ikinci kez yazmak, iki kopyanın zamanla ayrışması demekti.
 *
 * <p>Kolonlar çağırandan gelir; iskelet yalnız durum tik'ini, ad hücresini, başlangıç saatini
 * ve süreyi kendisi çizer — çünkü satır sözleşmesinin ({@code ok/error/start/ms}) sahibi odur.
 *
 * @param {Object|null} run  {rows, total, done, teamLabel, startedAt, finishedAt}; null → hiçbir şey çizilmez
 * @param {string} nameHeader  ilk veri kolonunun başlığı
 * @param {Function} nameOf  (row) => string — ilk veri kolonunun metni
 * @param {Function} [errorOf]  (row) => string|null — varsayılan: row.error || row.data?.error
 * @param {Array} columns  [{key, label, thClassName?, tdClassName?: string|(row)=>string, render: (row)=>node}]
 */
export default function CheckRunShell({ run, nameHeader, nameOf, errorOf, columns = [], title, onClose, onCancel }) {
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
  const rowError = errorOf || ((r) => r.error || r.data?.error)

  return (
    <div className="modal-overlay">
      <div className="modal-box chk-modal" onClick={e => e.stopPropagation()}>
        <div className="modal-icon-hdr modal-icon-hdr--check">
          <div className="modal-icon-hdr-badge"><RefreshCw size={20} /></div>
          <h3>{title || t('app.checkProgressTitle')}</h3>
          <span className="chk-count">{rows.length}/{run.total}</span>
        </div>

        {/* Belirli ilerleme: kaç kontrolün bittiği zaten biliniyor → yüzde gösterilebilir.
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
                <th className="chk-th-domain">{nameHeader}</th>
                {columns.map(c => <th key={c.key} className={c.thClassName}>{c.label}</th>)}
                <th>{t('app.checkColStart')}</th>
                <th>{t('app.checkColDur')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => {
                const msg = r.ok ? null : rowError(r)
                return (
                  <tr key={i} className={r.ok ? '' : 'chk-row-err'}>
                    <td className="chk-td-status">
                      <span className={`chk-tick${r.ok ? '' : ' chk-tick-err'}`}>{r.ok ? '✓' : '✕'}</span>
                    </td>
                    <td className="chk-td-domain">
                      <span className="chk-domain-name">{nameOf(r)}</span>
                      {msg && <span className="chk-row-msg">{msg}</span>}
                    </td>
                    {columns.map(c => (
                      <td key={c.key} className={tdClassOf(c, r)}>{c.render(r)}</td>
                    ))}
                    <td className="chk-mono">{fmtClock(r.start)}</td>
                    <td className="chk-mono"><b>{fmtDur(r.ms)}</b></td>
                  </tr>
                )
              })}
              {!run.done && (
                <tr><td className="chk-pending" colSpan={columns.length + 4}>
                  <Spinner size={14} inline decorative /> {t('app.checking')}
                </td></tr>
              )}
            </tbody>
          </table>
        </div>

        <div className="modal-actions">
          {!run.done && (
            <Button variant="secondary" onClick={onCancel}>{t('app.checkCancel')}</Button>
          )}
          <Button variant="secondary" onClick={onClose}>{t('app.close')}</Button>
        </div>
      </div>
    </div>
  )
}
