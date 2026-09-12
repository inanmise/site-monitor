import { useEffect, useState } from 'react'
import { api, formatDateOnly } from '../api/client'
import { useT } from '../i18n/index.jsx'
import DiagnosticsModal from './admin/DiagnosticsModal.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import MonthCalendar from './ui/MonthCalendar.jsx'
import { buildIcs, downloadIcs } from '../utils/ics.js'
import { CalendarDays, List, Download } from 'lucide-react'

const PRIORITY_COLOR = { critical: '#dc3545', warning: '#fd7e14', info: '#0d6efd' }

export default function RenewalAdvice({ onSelectDomain }) {
  const t = useT()
  const [advice, setAdvice] = useState([])
  const [loading, setLoading] = useState(true)
  const [diag, setDiag] = useState(null)   // { domain, port } → DiagnosticsModal
  const [loadError, setLoadError] = useState(null)
  // Takvim görünümü (2026-09-12, #8): aynı haftaya yığılan yenilemeler görünsün; tercih saklanır.
  const [view, setView] = useState(() => { try { return localStorage.getItem('renewal-view') === 'calendar' ? 'calendar' : 'list' } catch { return 'list' } })
  const switchView = (v) => { setView(v); try { localStorage.setItem('renewal-view', v) } catch { /* yoksay */ } }
  const calEvents = advice.filter(a => a.not_after).map(a => ({
    date: String(a.not_after).slice(0, 10), label: a.domain, title: `${a.domain} · ${a.message}`,
    tone: a.priority === 'critical' ? 'bad' : a.priority === 'warning' ? 'warn' : 'info', onClick: () => onSelectDomain?.(a.domain),
  }))
  function exportIcs() {
    downloadIcs('sertifika-yenilemeleri.ics', buildIcs(advice.filter(a => a.not_after).map(a => ({
      uid: `cert-${a.domain}-${String(a.not_after).slice(0, 10)}`, date: a.not_after, summary: `${t('renewal.icsPrefix')} ${a.domain}`, description: `${a.message}\n${a.action || ''}`,
    })), { calName: t('renewal.icsCal') }))
  }

  // .catch YOKTU: request() ag hatasinda {success:false} DONDURMEZ, throw eder ve burada
  // timeoutMs de verilmiyor (varsayilan 0 = timeout yok). Promise reject olunca setLoading(false)
  // HIC calismiyor, bu sekmede spinner SONSUZA KADAR donuyordu — hata mesaji da yoktu ve
  // sekme degistirip geri gelmeden duzelmiyordu.
  useEffect(() => {
    api.getRenewalAdvice()
      .then((res) => {
        if (res?.success) { setAdvice(res.data); setLoadError(null) }
        else setLoadError(res?.error || 'load failed')
      })
      .catch((e) => setLoadError(e?.message || 'network error'))
      .finally(() => setLoading(false))
  }, [])

  const PRIORITY_LABEL = {
    critical: t('renewal.critical'),
    warning:  t('renewal.warning'),
    info:     t('renewal.info'),
  }

  if (loading) return <LoadingBlock label={t('renewal.loading')} fullWidth />
  // Hata bandi "her sey yolunda" bos durumunun ONUNDE: aksi halde yukleme hatasi
  // "yenilenecek sertifika yok" gibi okunurdu.
  if (loadError && advice.length === 0)
    return (
      <AlertBanner tone="danger" title={t('mon.loadError')} role="alert">
        {String(loadError)}
      </AlertBanner>
    )
  if (advice.length === 0)
    return <LoadingBlock label={t('renewal.allGood')} fullWidth />

  return (
    <div className="renewal-container">
      <div className="renewal-toolbar">
        <div className="seg" role="group" aria-label={t('renewal.viewLabel')}>
          <button type="button" className={`btn btn-sm ${view === 'list' ? 'btn-primary' : 'btn-secondary'}`} onClick={() => switchView('list')} aria-pressed={view === 'list'}><List size={13} /> {t('renewal.viewList')}</button>
          <button type="button" className={`btn btn-sm ${view === 'calendar' ? 'btn-primary' : 'btn-secondary'}`} onClick={() => switchView('calendar')} aria-pressed={view === 'calendar'}><CalendarDays size={13} /> {t('renewal.viewCalendar')}</button>
        </div>
        <button type="button" className="btn btn-sm btn-secondary" onClick={exportIcs} disabled={!calEvents.length} title={t('renewal.icsTip')}><Download size={13} /> {t('renewal.ics')}</button>
      </div>
      {view === 'calendar' && <MonthCalendar events={calEvents} ariaLabel={t('renewal.viewCalendar')} />}
      {view === 'list' && advice.map((item, i) => {
        const color = PRIORITY_COLOR[item.priority] || '#6c757d'
        const label = PRIORITY_LABEL[item.priority] || item.priority
        const days = item.days_remaining
        const daysAbs = days !== null && days !== undefined ? Math.abs(days) : null
        const daysLabel = days === null || days === undefined
          ? t('renewal.unknown')
          : days < 0 ? t('renewal.daysAgo') : t('renewal.daysLeft')

        return (
          <div key={i} className="renewal-card" style={{ borderLeftColor: color, cursor: 'pointer' }}
            onClick={() => onSelectDomain?.(item.domain)}
            title={t('renewal.openDetail')}>

            <div className="renewal-card-body">
              <div className="renewal-card-header">
                <span className="renewal-badge" style={{ background: color }}>{label}</span>
                <strong className="renewal-domain">{item.domain}</strong>
              </div>
              <div className="renewal-message">{item.message}</div>
              <div className="renewal-action">
                <strong>{t('renewal.action')}</strong> <code>{item.action}</code>
              </div>
              {/* Bağlantı sorunu (ulaşılamayan sertifika) → derin tanılama linki */}
              {item.code === 'UNREACHABLE' && (
                <button type="button" className="renewal-diagnose-link"
                  onClick={(e) => { e.stopPropagation(); setDiag({ domain: item.domain, port: 443 }) }}>
                  🔍 {t('renewal.diagnose')}
                </button>
              )}
            </div>

            <div
              className="renewal-expiry-stamp"
              style={{
                background: `linear-gradient(150deg, ${color}18 0%, ${color}38 100%)`,
                borderLeftColor: `${color}50`,
              }}
            >
              <span className="expiry-days-num" style={{ color }}>
                {daysAbs !== null ? daysAbs : '?'}
              </span>
              <span className="expiry-days-lbl" style={{ color }}>
                {daysLabel}
              </span>
              <div className="expiry-divider" style={{ background: `${color}40` }} />
              <span className="expiry-date-caption">{t('renewal.expiry')}</span>
              <span className="expiry-date-val">
                {formatDateOnly(item.not_after)}
              </span>
            </div>

          </div>
        )
      })}

      {/* Tanılama modalı (envanter ile ortak) — backend izlenen domainlere açık */}
      {diag && (
        <DiagnosticsModal domain={diag.domain} port={diag.port} onClose={() => setDiag(null)} />
      )}
    </div>
  )
}
