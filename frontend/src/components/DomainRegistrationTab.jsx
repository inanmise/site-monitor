import { useState, useEffect, useCallback } from 'react'
import { dateLocale } from '../i18n/dateLocale.js'
import { api, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { RefreshCw, ShieldCheck, ShieldOff, ShieldAlert, ListX, History,
  Server, Globe, Building2, CalendarClock, BellRing } from 'lucide-react'
import { Spinner, LoadingBlock } from './ui/Progress.jsx'

/** Bitiş tarihi — insan-okur ("6 Ağustos 2026 15:37"), tr-TR; date-only ("2029-10-26") ve datetime güvenli. */
function fmtDateHuman(iso) {
  if (!iso) return '—'
  const s = String(iso).length <= 10 ? iso + 'T00:00:00Z' : (iso.endsWith('Z') || iso.includes('+') ? iso : iso + 'Z')
  const d = new Date(s)
  if (isNaN(d.getTime())) return String(iso).substring(0, 10)
  return d.toLocaleString(dateLocale(), { year: 'numeric', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
function daysColor(d) {
  if (d == null) return 'var(--text-muted, #71717a)'
  if (d < 0 || d <= 7) return '#C0392B'
  if (d <= 30) return '#D68910'
  return '#1E8449'
}
import { eppKey, eppLabel } from '../utils/domainEpp.js'
import { Button } from '@/components/shadcn/button'

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
  const [rem, setRem] = useState(null)   // hatırlatmalar (2026-09-22, E): { thresholds, items }

  const load = useCallback(async (live = true) => {
    if (!id) return
    setLoading(true); setErr(null); setStale(false)
    try {
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
    } finally {
      setLoading(false)
    }
  }, [id, t])

  useEffect(() => { load(true) }, [load])
  useEffect(() => {
    let alive = true
    api.monitoring.getDomainReminders?.(monitor.id)?.then(r => { if (alive && r?.success) setRem(r.data) }).catch(() => {})
    return () => { alive = false }
  }, [monitor.id])

  if (loading && !reg) return <LoadingBlock label={t('dreg.loading')} className="upt-modal-loading" size={16} />
  if (err && !reg) return <div className="alert-msg alert-msg--err">{err}</div>
  if (!reg) return null

  const d = reg
  const ips = csv(d.resolved_ips), hosts = csv(d.hostnames), ns = csv(d.nameservers), epp = csv(d.status_codes)

  return (
    <div className="dreg">
      <div className="dreg-toolbar">
        {stale && <span className="dreg-stale">{t('dreg.stale').replace('{0}', formatDateSec(d.checked_at))}</span>}
        <Button variant="secondary" size="sm" style={{ marginLeft: 'auto' }} onClick={() => load(true)} disabled={loading}>
          {loading ? <Spinner size={13} inline decorative /> : <RefreshCw size={13} />}{t('dreg.refresh')}
        </Button>
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
      {ns.length ? <ul className="dreg-list">{ns.map(n => <li key={n}>{n}</li>)}</ul> : <div className="dreg-empty">— <span className="dreg-why">{d.source === 'NONE' || !d.source ? t('dreg.whyNoData') : t('dreg.whyNoNs')}</span></div>}

      {/* IP + Hostname */}
      <div className="dreg-section-hdr"><Globe size={15} /> {t('dreg.ips')}</div>
      {ips.length ? (
        <table className="dreg-iptable"><tbody>
          {ips.map((ip, i) => <tr key={ip}><td className="dreg-ip">{ip}</td><td className="dreg-host">{hosts[i] || <span className="dreg-why">{t('dreg.whyNoPtr')}</span>}</td></tr>)}
        </tbody></table>
      ) : <div className="dreg-empty">— <span className="dreg-why">{t('dreg.whyNoIp', d.domain || '')}</span></div>}

      {/* Domain Status (EPP) */}
      <div className="dreg-section-hdr">{t('dreg.eppStatus')}</div>
      {epp.length ? (
        <div className="dreg-epp">
          {epp.map(c => {
            const k = 'epp.' + eppKey(c)
            const desc = t(k)
            return <span key={c} className="dreg-epp-pill" title={desc !== k ? desc : c}>{eppLabel(c)}</span>
          })}
        </div>
      ) : <div className="dreg-empty">— <span className="dreg-why">{d.source === 'WHOIS' ? t('dreg.whyNoEppWhois') : t('dreg.whyNoData')}</span></div>}

      {/* DNSSEC */}
      <div className="dreg-section-hdr">DNSSEC</div>
      <div className="dreg-dnssec">
        {d.dnssec === 'signed' ? <><ShieldCheck size={15} className="dreg-ok" /> {t('dreg.dnssecSigned')}</>
          : d.dnssec === 'unsigned' ? <><ShieldOff size={15} className="dreg-muted" /> {t('dreg.dnssecUnsigned')}</>
          : <span className="dreg-empty">{t('dreg.dnssecUnknown')} <span className="dreg-why">{d.source === 'WHOIS' ? t('dreg.whyDnssecWhois') : t('dreg.whyNoData')}</span></span>}
      </div>
      {/* ── Koruma durumu ────────────────────────────────────────────────────────
          Üç sinyal de ÜÇ/DÖRT durumlu: "doğrulanamadı" ayrı bir cevaptır, "sorun yok"
          değil. Kilit yalnız RDAP'ta doğrulanabiliyor, kara liste sorgusu kurumsal ağdan
          reddedilebiliyor — ikisini de "temiz" saymak korumanın çalıştığı yanılsaması olurdu. */}
      <div className="dreg-section-hdr"><ShieldCheck size={15} /> {t('dreg.protection')}</div>
      <div className="dreg-protect">
        <span className="dreg-protect-lbl">{t('dom.transferLock')}</span>
        <LockBadge value={d.transfer_lock} t={t} />

        <span className="dreg-protect-lbl">{t('dom.blacklist')}</span>
        <BlacklistBadge status={d.blacklist_status} detail={d.blacklist_detail} t={t} noIp={ips.length === 0} />
        {d.transfer_lock === 'UNKNOWN' && <span className="dreg-why dreg-why--row">{d.source === 'WHOIS' ? t('dreg.whyLockWhois') : t('dreg.whyNoData')}</span>}

        {d.change_detail && (<>
          <span className="dreg-protect-lbl"><History size={13} /> {t('dom.lastChange')}</span>
          <span className="dreg-protect-change">{d.change_detail}</span>
        </>)}
      </div>

      {/* Hatırlatmalar (2026-09-22, E): eşik takvimi + gönderilenler. Eskiden eşik alanı ölü idi (form + rehber "mail gider" diyordu, gitmiyordu). */}
      <div className="dreg-section-hdr"><BellRing size={15} /> {t('dreg.reminders')}</div>
      {rem && (<>
        <div className="dreg-rem-thresholds">
          {(rem.thresholds || []).map(th => {
            const hit = (rem.items || []).find(x => x.threshold_days === th && x.expiry_date === d.expiry_date)
            const cls = hit ? (hit.status === 'SENT' ? 'sent' : hit.status === 'COVERED' ? 'covered' : 'skipped') : (d.days_remaining != null && d.days_remaining <= th ? 'due' : 'pending')
            return <span key={th} className={`dreg-rem-chip dreg-rem-chip--${cls}`} title={hit ? `${t('dreg.remStatus_' + hit.status)} · ${formatDateSec(hit.sent_at)}` : t('dreg.remPending')}>{th} {t('card.daysUnit')}</span>
          })}
        </div>
        <div className="dreg-rem-legend">{t('dreg.remLegend')}</div>
        {(rem.items || []).length > 0 ? (
          <ul className="dreg-list dreg-rem-list">
            {rem.items.slice(0, 10).map(x => (
              <li key={x.id}><span className={`dreg-rem-dot dreg-rem-dot--${x.status === 'SENT' ? 'sent' : x.status === 'COVERED' ? 'covered' : 'skipped'}`} />
                {formatDateSec(x.sent_at)} · {t('dreg.remRow', x.threshold_days, x.days_remaining ?? '—')} · {t('dreg.remStatus_' + x.status)}
                {x.recipients ? <span className="dreg-why"> → {x.recipients}</span> : null}
                {x.push_queued != null ? <span className="dreg-why"> · push {x.push_queued}</span> : null}</li>
            ))}
          </ul>
        ) : <div className="dreg-empty">{t('dreg.remNone')}</div>}
      </>)}

      {/* Kontrol Geçmişi buradan kaldırıldı — "Kontrol" sekmesindeki geçmiş tablosuyla aynıydı (tekrar). */}
    </div>
  )
}

/**
 * Transfer kilidi rozeti. DÖRT durum: registry+registrar / yalnız registry / yalnız registrar /
 * kilit yok / doğrulanamadı.
 *
 * <p>Registry kilidi (serverTransferProhibited) registrar kilidinden daha güçlüdür: registrar
 * hesabı ele geçse bile transfer engellenir. İkisini tek kovaya koymak bu farkı görünmez yapardı.
 */
function LockBadge({ value, t }) {
  const v = String(value || 'UNKNOWN').toUpperCase()
  if (v === 'BOTH')   return <span className="dreg-badge dreg-badge--ok"><ShieldCheck size={13} /> {t('dom.lockBoth')}</span>
  if (v === 'SERVER') return <span className="dreg-badge dreg-badge--ok"><ShieldCheck size={13} /> {t('dom.lockServer')}</span>
  if (v === 'CLIENT') return <span className="dreg-badge dreg-badge--ok"><ShieldCheck size={13} /> {t('dom.lockClient')}</span>
  if (v === 'NONE')   return <span className="dreg-badge dreg-badge--bad"><ShieldAlert size={13} /> {t('dom.lockNone')}</span>
  return <span className="dreg-badge dreg-badge--unknown"><ShieldOff size={13} /> {t('dom.lockUnknown')}</span>
}

/** Kara liste rozeti. LISTED'de kanıt (hangi liste) ipucunda taşınır — rakam tek başına iş görmez. */
function BlacklistBadge({ status, detail, t, noIp = false }) {
  const v = String(status || 'UNKNOWN').toUpperCase()
  if (v === 'CLEAN')   return <span className="dreg-badge dreg-badge--ok"><ShieldCheck size={13} /> {t('dom.blClean')}</span>
  if (v === 'SKIPPED') return <span className="dreg-badge dreg-badge--muted">{t('dom.blSkipped')}</span>
  if (v === 'LISTED') {
    const lists = String(detail || '').split(';').map(x => x.split('=')[0].trim()).filter(Boolean)
    return (
      <span className="dreg-badge dreg-badge--bad" title={detail || undefined}>
        <ListX size={13} /> {t('dom.blListed').replace('{n}', lists.length || '?')}
      </span>
    )
  }
  // Neden doğrulanamadı: IP yoksa sorgulanacak şey yok (apex A kaydı olmayan alan adı — www için bakılmaz); IP varsa DNSBL sorgusu cevapsız (kurumsal ağ) (2026-09-22, K)
  return <span className="dreg-badge dreg-badge--unknown" title={noIp ? t('dreg.whyBlNoIp') : t('dreg.whyBlNoAnswer')}><ShieldOff size={13} /> {t('dom.blUnknown')} <span className="dreg-why">{noIp ? t('dreg.whyBlNoIp') : t('dreg.whyBlNoAnswer')}</span></span>
}
