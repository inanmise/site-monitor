import { useState, useEffect, useCallback } from 'react'
import { api, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { Loader2, RefreshCw, ShieldCheck, ShieldOff, Server, Globe, Building2, CalendarClock } from 'lucide-react'

/** Bitiş tarihi — insan-okur ("6 Ağustos 2026 15:37"), tr-TR; date-only ("2029-10-26") ve datetime güvenli. */
function fmtDateHuman(iso) {
  if (!iso) return '—'
  const s = String(iso).length <= 10 ? iso + 'T00:00:00Z' : (iso.endsWith('Z') || iso.includes('+') ? iso : iso + 'Z')
  const d = new Date(s)
  if (isNaN(d.getTime())) return String(iso).substring(0, 10)
  return d.toLocaleString('tr-TR', { year: 'numeric', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
function daysColor(d) {
  if (d == null) return 'var(--text-muted, #64748b)'
  if (d < 0 || d <= 7) return '#C0392B'
  if (d <= 30) return '#D68910'
  return '#1E8449'
}
const eppKey = (c) => String(c || '').replace(/\s+/g, '').toLowerCase()

function csv(v) {
  if (Array.isArray(v)) return v
  if (v == null || v === '') return []
  return String(v).split(',').map(s => s.trim()).filter(Boolean)
}

/** Domain Kaydı sekmesi — anlık RDAP/WHOIS (live) + son kontrol geçmişi. */
export default function DomainRegistrationTab({ monitor }) {
  const t = useT()
  const id = monitor?.id
  const [reg, setReg] = useState(null)
  const [loading, setLoading] = useState(true)
  const [stale, setStale] = useState(false)          // live başarısız → DB'deki son bilgi gösteriliyor
  const [err, setErr] = useState(null)

  const load = useCallback(async (live = true) => {
    if (!id) return
    setLoading(true); setErr(null); setStale(false)
    try {
      const res = await api.monitoring.getDomainRegistration(id, { live })
      if (res?.success) { setReg(res.data) }
      else if (live) {
        // live başarısız → DB'deki son bilgiye düş
        const fb = await api.monitoring.getDomainRegistration(id)
        if (fb?.success) { setReg(fb.data); setStale(true) } else setErr(res?.error || t('dreg.error'))
      } else setErr(res?.error || t('dreg.error'))
    } catch {
      try {
        const fb = await api.monitoring.getDomainRegistration(id)
        if (fb?.success) { setReg(fb.data); setStale(true) } else setErr(t('dreg.error'))
      } catch { setErr(t('dreg.error')) }
    }
    setLoading(false)
  }, [id, t])

  useEffect(() => { load(true) }, [load])

  if (loading && !reg) return <div className="upt-modal-loading"><Loader2 size={16} className="spin" /> {t('dreg.loading')}</div>
  if (err && !reg) return <div className="alert-msg alert-msg--err">{err}</div>
  if (!reg) return null

  const d = reg
  const ips = csv(d.resolved_ips), hosts = csv(d.hostnames), ns = csv(d.nameservers), epp = csv(d.status_codes)

  return (
    <div className="dreg">
      <div className="dreg-toolbar">
        {stale && <span className="dreg-stale">{t('dreg.stale').replace('{0}', formatDateSec(d.checked_at))}</span>}
        <button className="btn btn-sm btn-secondary" style={{ marginLeft: 'auto' }} onClick={() => load(true)} disabled={loading}>
          {loading ? <Loader2 size={13} className="spin" /> : <RefreshCw size={13} />}{t('dreg.refresh')}
        </button>
      </div>

      {/* Registrar */}
      <div className="dreg-section-hdr"><Building2 size={15} /> {t('dreg.registrar')}</div>
      <dl className="dreg-dl">
        <dt>{t('dreg.registrarName')}</dt><dd>{d.registrar || '—'}</dd>
        <dt>{t('dreg.ianaId')}</dt><dd>{d.registrar_iana_id || '—'}</dd>
        <dt>{t('dreg.source')}</dt><dd>{d.source || '—'}</dd>
      </dl>

      {/* Important Dates */}
      <div className="dreg-section-hdr"><CalendarClock size={15} /> {t('dreg.dates')}</div>
      <dl className="dreg-dl">
        <dt>{t('dreg.expiry')}</dt>
        <dd>
          <span style={{ color: daysColor(d.days_remaining), fontWeight: 700 }}>{d.days_remaining ?? '—'}</span> {t('dreg.daysLeft')}
          <span className="dreg-date">· {fmtDateHuman(d.expiry_date)}</span>
        </dd>
        <dt>{t('dreg.created')}</dt><dd>{fmtDateHuman(d.registration_date)}</dd>
        <dt>{t('dreg.updated')}</dt><dd>{fmtDateHuman(d.last_changed)}</dd>
      </dl>

      {/* Nameservers */}
      <div className="dreg-section-hdr"><Server size={15} /> {t('dreg.nameservers')}</div>
      {ns.length ? <ul className="dreg-list">{ns.map(n => <li key={n}>{n}</li>)}</ul> : <div className="dreg-empty">—</div>}

      {/* IP + Hostname */}
      <div className="dreg-section-hdr"><Globe size={15} /> {t('dreg.ips')}</div>
      {ips.length ? (
        <table className="dreg-iptable"><tbody>
          {ips.map((ip, i) => <tr key={ip}><td className="dreg-ip">{ip}</td><td className="dreg-host">{hosts[i] || '—'}</td></tr>)}
        </tbody></table>
      ) : <div className="dreg-empty">—</div>}

      {/* Domain Status (EPP) */}
      <div className="dreg-section-hdr">{t('dreg.eppStatus')}</div>
      {epp.length ? (
        <div className="dreg-epp">
          {epp.map(c => {
            const k = 'epp.' + eppKey(c)
            const desc = t(k)
            return <span key={c} className="dreg-epp-pill" title={desc !== k ? desc : c}>{c}</span>
          })}
        </div>
      ) : <div className="dreg-empty">—</div>}

      {/* DNSSEC */}
      <div className="dreg-section-hdr">DNSSEC</div>
      <div className="dreg-dnssec">
        {d.dnssec === 'signed' ? <><ShieldCheck size={15} className="dreg-ok" /> {t('dreg.dnssecSigned')}</>
          : d.dnssec === 'unsigned' ? <><ShieldOff size={15} className="dreg-muted" /> {t('dreg.dnssecUnsigned')}</>
          : <span className="dreg-empty">{t('dreg.dnssecUnknown')}</span>}
      </div>
      {/* Kontrol Geçmişi buradan kaldırıldı — "Kontrol" sekmesindeki geçmiş tablosuyla aynıydı (tekrar). */}
    </div>
  )
}
