import { useState, useEffect, useCallback, useMemo } from 'react'
import { api, formatDate, formatDateSec, formatDateOnly } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import {
  AlertTriangle, ShieldAlert, ShieldCheck, RefreshCw, Download, ChevronDown, Bell, BellRing,
  ClipboardCheck, ClipboardX, ScanSearch, Lock, Link2, Users, TrendingUp, CalendarClock, ListChecks, BarChart3,
} from 'lucide-react'
import { LoadingBlock, Spinner, ProgressBar } from '../ui/Progress.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'

/**
 * Zayıf Algoritma Raporu — zengin sürüm (2026-09-12, kullanıcı: "sayfa çok uzun süredir boş; neyi
 * gösteriyor, ne bekliyor, ne yok — 10 zenginleştirmenin tamamı").
 *
 * Bölümler (backend `WeakAlgorithmReportService` sözleşmesi):
 *  1 tarama özeti · 2 kural kataloğu · 3 dağılım · 4 2030 görünümü · 5 TLS/şifre bulguları ·
 *  6 zincir/güven bulguları · 7 takım kırılımı · 8 30 günlük trend · 9 eylemler (kontrol et /
 *  bildir / istisna) · 10 CSV dışa aktarma (haftalık e-posta satırı backend'de).
 *
 * Boş rapor ARTIK boş sayfa değil: "N alan tarandı, kurallar şunlar, hiçbiri eşleşmedi" kanıtıdır.
 */

const RULE_ORDER = ['sig.md5', 'sig.sha1', 'key.rsa1024', 'key.rsa2048', 'key.ec192', 'key.ec256',
  'tls.legacy', 'cipher.weak', 'cipher.cbc', 'pfs.none', 'chain.broken', 'trust.untrusted',
  'revocation.revoked', 'revocation.nourl', 'intermediate.expiring']

const SEV_CLASS = { CRITICAL: 'wa-sev-critical', HIGH: 'wa-sev-high', MEDIUM: 'wa-sev-medium' }

function SeverityBadge({ severity }) {
  return <span className={`wa-severity ${SEV_CLASS[severity] || 'wa-sev-medium'}`}>{severity}</span>
}

function StatusBadge({ status }) {
  const t = useT()
  if (!status) return <span className="wa-status-unknown">—</span>
  const cls = status === 'error' ? 'wa-status-error'
            : status === 'ok' || status === 'valid' ? 'wa-status-ok'
            : 'wa-status-warn'
  return <span className={`wa-status ${cls}`} title={t('wa.statusTip')}>{status.toUpperCase()}</span>
}

/** Katlanır bölüm — başlık düğmesi aria-expanded; sayaç rozeti başlıkta. */
function Section({ id, icon: Icon, title, count, tone, open, onToggle, children, hint }) {
  return (
    <section className={`wa-sec${open ? ' is-open' : ''}`} data-sec={id}>
      <button type="button" className="wa-sec-head" aria-expanded={open} aria-controls={`wa-sec-${id}`} onClick={onToggle}>
        <ChevronDown size={16} className={`wa-sec-chevron${open ? ' is-open' : ''}`} aria-hidden="true" />
        {Icon && <Icon size={16} aria-hidden="true" />}
        <span className="wa-sec-title">{title}</span>
        {count != null && <span className={`wa-sec-count${tone ? ` wa-sec-count--${tone}` : ''}`}>{count}</span>}
        {hint && <span className="wa-sec-hint">{hint}</span>}
      </button>
      {open && <div className="wa-sec-body" id={`wa-sec-${id}`}>{children}</div>}
    </section>
  )
}

function Kpi({ label, value, sub, tone }) {
  return (
    <div className={`wa-kpi${tone ? ` wa-kpi--${tone}` : ''}`}>
      <span className="wa-kpi-label">{label}</span>
      <span className="wa-kpi-value">{value}</span>
      {sub && <span className="wa-kpi-sub">{sub}</span>}
    </div>
  )
}

/** Yatay çubuk listesi (dağılım) — en büyük değer %100. */
function Bars({ items, title }) {
  const t = useT()
  const max = Math.max(1, ...items.map(i => i.count))
  return (
    <div className="wa-bars">
      <div className="wa-bars-title">{title}</div>
      {items.length === 0 && <div className="wa-muted">{t('wa.noData')}</div>}
      {items.map(i => (
        <div key={i.label} className="wa-bar-row" title={`${i.label}: ${i.count}`}>
          <span className="wa-bar-label wa-mono">{i.label}</span>
          <ProgressBar value={i.count} max={max} size="sm" decorative className="wa-bar-fill" />
          <b className="wa-bar-count">{i.count}</b>
        </div>
      ))}
    </div>
  )
}

/** 30 günlük sütun grafiği — SVG, kütüphanesiz (jsdom'da da çizilir). */
function TrendChart({ series }) {
  const t = useT()
  const max = Math.max(1, ...series.map(s => s.weak))
  const W = 600, H = 90, pad = 4
  const bw = (W - pad * 2) / Math.max(1, series.length)
  return (
    <svg className="wa-trend" viewBox={`0 0 ${W} ${H}`} role="img" aria-label={t('wa.trendAria')} preserveAspectRatio="none">
      {series.map((s, i) => {
        const h = Math.round((H - pad * 2) * s.weak / max)
        return (
          <g key={s.day}>
            <title>{`${s.day}: ${s.weak}`}</title>
            <rect x={pad + i * bw + 1} y={H - pad - h} width={Math.max(1, bw - 2)} height={h}
              className={s.weak > 0 ? 'wa-trend-bar wa-trend-bar--weak' : 'wa-trend-bar'} />
          </g>
        )
      })}
      <line x1={pad} y1={H - pad} x2={W - pad} y2={H - pad} className="wa-trend-axis" />
    </svg>
  )
}

function ExceptionModal({ domain, existing, onClose, onSaved }) {
  const t = useT()
  const toast = useToast()
  const [reason, setReason] = useState(existing?.reason || '')
  const [until, setUntil] = useState(existing?.until || '')
  const [saving, setSaving] = useState(false)

  async function save() {
    setSaving(true)
    try {
      const r = await api.admin.setWeakAlgorithmException(domain, { reason, until })
      if (r?.success) { toast.success(t('wa.exceptionSaved', domain)); onSaved(); onClose() }
      else toast.error(r?.error || t('wa.exceptionError'))
    } catch { toast.error(t('wa.exceptionError')) }
    finally { setSaving(false) }
  }

  return (
    <ModalShell open onClose={onClose} title={t('wa.exceptionTitle', domain)} icon={ClipboardCheck} size="sm" busy={saving}
      footer={<>
        <button type="button" className="btn btn-secondary" onClick={onClose} disabled={saving}>{t('app.cancel')}</button>
        <button type="button" className="btn btn-primary" onClick={save} disabled={saving || !until}>
          {saving ? <Spinner size={14} inline decorative /> : <ClipboardCheck size={14} />} {t('wa.exceptionSave')}
        </button>
      </>}>
      <p className="wa-banner-info">{t('wa.exceptionHelp')}</p>
      <label className="wa-field">
        {t('wa.exceptionUntil')}
        <input type="date" className="input" value={until} onChange={e => setUntil(e.target.value)} required />
      </label>
      <label className="wa-field">
        {t('wa.exceptionReason')}
        <textarea className="input" rows={3} value={reason} onChange={e => setReason(e.target.value)} placeholder={t('wa.exceptionReasonPh')} />
      </label>
    </ModalShell>
  )
}

export default function WeakAlgorithmReport() {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const { canEdit } = usePermissions()
  const canManage = canEdit('weak_algo.manage')

  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState({})          // domain → 'check' | 'notify'
  const [exModal, setExModal] = useState(null)  // { domain, existing }
  const [open, setOpen] = useState({ rules: false, dist: false, outlook: false, tls: true, chain: true, teams: false, trend: false, cert: true, exceptions: false })

  const toggle = (k) => setOpen(o => ({ ...o, [k]: !o[k] }))

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const r = await api.admin.getWeakAlgorithms()
      if (r?.success) { setData(r); setError(null) }
      else setError(r?.error || t('wa.loadError'))
    } catch { setError(t('wa.loadError')) }
    finally { setLoading(false) }
  }, [t])

  useEffect(() => { load() }, [load])

  const ruleLabel = (k) => t(`wa.rule.${k}`)

  async function checkNow(domain) {
    setBusy(b => ({ ...b, [domain]: 'check' }))
    try {
      const r = await api.refreshCertificateHealth(domain)
      if (r?.success) { toast.success(t('wa.checkDone', domain)); load() }
      else toast.error(r?.error || t('wa.checkError'))
    } catch { toast.error(t('wa.checkError')) }
    finally { setBusy(b => { const n = { ...b }; delete n[domain]; return n }) }
  }

  async function notify(row) {
    const ok = await showConfirm({
      title: t('wa.notifyTitle'),
      message: t('wa.notifyMsg', row.domain, row.team_name || '—'),
      confirmText: t('wa.notifyConfirm'),
      cancelText: t('app.cancel'),
    })
    if (!ok) return
    setBusy(b => ({ ...b, [row.domain]: 'notify' }))
    try {
      const r = await api.admin.notifyWeakAlgorithm(row.domain)
      if (r?.success) {
        const d = r.data || {}
        toast.success(t('wa.notifyDone', d.email_to || '—', d.push?.queued ?? 0))
      } else toast.error(r?.error || t('wa.notifyError'))
    } catch { toast.error(t('wa.notifyError')) }
    finally { setBusy(b => { const n = { ...b }; delete n[row.domain]; return n }) }
  }

  async function clearException(domain) {
    const ok = await showConfirm({
      title: t('wa.exceptionClearTitle'),
      message: t('wa.exceptionClearMsg', domain),
      confirmText: t('wa.exceptionClear'),
      cancelText: t('app.cancel'),
      variant: 'danger',
    })
    if (!ok) return
    const r = await api.admin.clearWeakAlgorithmException(domain)
    if (r?.success) { toast.success(t('wa.exceptionCleared', domain)); load() }
    else toast.error(r?.error || t('wa.exceptionError'))
  }

  function exportCsv() {
    window.open(api.admin.weakAlgorithmsExportUrl(), '_blank')
  }

  const rows = data?.data || []
  const tlsRows = data?.tls?.rows || []
  const chainRows = data?.chain?.rows || []
  const scan = data?.scan || {}
  const clean = rows.length === 0 && tlsRows.length === 0 && chainRows.length === 0
  const rulesSorted = useMemo(() => {
    const list = data?.rules || []
    return [...list].sort((a, b) => RULE_ORDER.indexOf(a.key) - RULE_ORDER.indexOf(b.key))
  }, [data])

  if (loading && !data) return <LoadingBlock label={t('wa.loading')} className="wa-loading" />
  if (error && !data) {
    return (
      <div className="wa-empty" role="alert">
        <ShieldAlert size={40} style={{ marginBottom: 12, opacity: .4 }} />
        <p>{error}</p>
        <button type="button" className="btn btn-secondary" onClick={load}><RefreshCw size={14} /> {t('wa.retry')}</button>
      </div>
    )
  }

  /** Eylem hücresi — kontrol et / bildir / istisna (yalnız manage yetkisinde). */
  const Actions = ({ row }) => (
    <div className="wa-actions">
      <button type="button" className="btn btn-sm btn-secondary" onClick={() => checkNow(row.domain)}
        disabled={!!busy[row.domain]} title={t('wa.actCheck')}>
        {busy[row.domain] === 'check' ? <Spinner size={13} inline decorative /> : <RefreshCw size={13} />} {t('wa.actCheck')}
      </button>
      {canManage && (
        <>
          <button type="button" className="btn btn-sm btn-secondary" onClick={() => notify(row)}
            disabled={!!busy[row.domain] || !row.team_id} title={row.team_id ? t('wa.actNotify') : t('wa.noTeam')}>
            {busy[row.domain] === 'notify' ? <Spinner size={13} inline decorative /> : <BellRing size={13} />} {t('wa.actNotify')}
          </button>
          {row.exception
            ? <button type="button" className="btn btn-sm btn-secondary" onClick={() => clearException(row.domain)} title={t('wa.exceptionClear')}>
                <ClipboardX size={13} /> {t('wa.exceptionClear')}
              </button>
            : <button type="button" className="btn btn-sm btn-secondary" onClick={() => setExModal({ domain: row.domain, existing: null })} title={t('wa.actException')}>
                <ClipboardCheck size={13} /> {t('wa.actException')}
              </button>}
        </>
      )}
    </div>
  )

  const ExceptionChip = ({ ex }) => ex ? (
    <span className={`wa-exception-chip${ex.expired ? ' is-expired' : ''}`} title={ex.reason || ''}>
      {ex.expired ? t('wa.exceptionExpired', formatDateOnly(ex.until)) : t('wa.exceptionUntilChip', formatDateOnly(ex.until))}
    </span>
  ) : null

  const TeamCell = ({ row }) => row.team_name
    ? <TeamBadge teamId={row.team_id} teamName={row.team_name} />
    : <span className="wa-muted">{t('wa.noTeam')}</span>

  return (
    <div className="wa-root">
      {/* ── Araç çubuğu ── */}
      <div className="wa-toolbar">
        <div className="wa-toolbar-meta">
          <ScanSearch size={16} aria-hidden="true" />
          <span>{t('wa.generatedAt', scan.generated_at ? formatDateSec(scan.generated_at) : '—')}</span>
          <span className="wa-muted">· {t('wa.latestCheck', scan.latest_checked_at ? formatDateSec(scan.latest_checked_at) : '—')}</span>
        </div>
        <div className="wa-toolbar-actions">
          <button type="button" className="btn btn-sm btn-secondary" onClick={load} disabled={loading}>
            {loading ? <Spinner size={13} inline decorative /> : <RefreshCw size={13} />} {t('wa.refresh')}
          </button>
          <button type="button" className="btn btn-sm btn-secondary" onClick={exportCsv}>
            <Download size={13} /> {t('wa.exportCsv')}
          </button>
        </div>
      </div>

      {/* ── 1. Tarama özeti ── */}
      <div className="wa-kpis" role="group" aria-label={t('wa.scanTitle')}>
        <Kpi label={t('wa.kpiActive')} value={scan.active_domains ?? 0} sub={t('wa.kpiActiveSub')} />
        <Kpi label={t('wa.kpiChecked24h')} value={scan.checked_24h ?? 0} sub={t('wa.kpiCheckedSub', scan.checked ?? 0)} />
        <Kpi label={t('wa.kpiUnchecked')} value={(scan.never_checked ?? 0) + (scan.error ?? 0)}
          sub={t('wa.kpiUncheckedSub', scan.never_checked ?? 0, scan.error ?? 0)} tone={(scan.never_checked ?? 0) + (scan.error ?? 0) > 0 ? 'warn' : undefined} />
        <Kpi label={t('wa.kpiWeak')} value={data?.total ?? 0} sub={t('wa.kpiWeakSub', data?.critical ?? 0, data?.high ?? 0)} tone={(data?.total ?? 0) > 0 ? 'bad' : 'ok'} />
        <Kpi label={t('wa.kpiTls')} value={data?.tls?.total ?? 0} tone={(data?.tls?.total ?? 0) > 0 ? 'bad' : 'ok'} />
        <Kpi label={t('wa.kpiChain')} value={data?.chain?.total ?? 0} tone={(data?.chain?.total ?? 0) > 0 ? 'bad' : 'ok'} />
        {/* Kayıtlı istisna sayısı = İstisnalar bölümüyle aynı (QA ISSUE-008); yalnız zayıf bulguya bağlı olanlar alt satırda */}
        <Kpi label={t('wa.kpiExcepted')} value={data?.exceptions?.length ?? 0} sub={(data?.excepted ?? 0) > 0 ? t('wa.kpiExceptedOnFindings', data.excepted) : t('wa.kpiExceptedSub')} />
      </div>

      {/* ── Hüküm bandı ── */}
      {clean ? (
        <div className="wa-banner wa-banner--clean">
          <div className="wa-banner-left"><ShieldCheck size={20} /><span>{t('wa.cleanTitle', scan.checked ?? 0)}</span></div>
          <span className="wa-banner-sub">{t('wa.cleanSub', rulesSorted.length)}</span>
        </div>
      ) : (
        <div className="wa-banner">
          <div className="wa-banner-left"><AlertTriangle size={20} /><span>{t('wa.totalWeak', rows.length)}</span></div>
          <div className="wa-banner-badges">
            {data.critical > 0 && <span className="wa-banner-badge critical">{t('wa.bannerCritical', data.critical)}</span>}
            {data.high > 0 && <span className="wa-banner-badge high">{t('wa.bannerHigh', data.high)}</span>}
            {tlsRows.length > 0 && <span className="wa-banner-badge tls">{t('wa.bannerTls', tlsRows.length)}</span>}
            {chainRows.length > 0 && <span className="wa-banner-badge chain">{t('wa.bannerChain', chainRows.length)}</span>}
          </div>
        </div>
      )}
      {!clean && <p className="wa-banner-info">{t('wa.bannerInfo')}</p>}

      {/* ── 9. Sertifika algoritması bulguları + eylemler ── */}
      <Section id="cert" icon={Lock} title={t('wa.secCert')} count={rows.length} tone={rows.length ? 'bad' : 'ok'} open={open.cert} onToggle={() => toggle('cert')}>
        {rows.length === 0 ? <p className="wa-muted">{t('wa.empty')}</p> : (
          <div className="wa-table-wrap">
            <table className="wa-table">
              <thead><tr>
                <th>{t('wa.colSeverity')}</th><th>{t('wa.colDomain')}</th><th>{t('wa.colOwner')}</th><th>{t('wa.colTeam')}</th>
                <th>{t('wa.colSigAlgo')}</th><th>{t('wa.colKeyAlgo')}</th><th>{t('wa.colWeakness')}</th><th>{t('wa.colExpiry')}</th>
                <th>{t('wa.colStatus')}</th><th>{t('wa.colActions')}</th>
              </tr></thead>
              <tbody>
                {rows.map(row => (
                  <tr key={row.domain} className={`wa-row wa-row-${(row.severity || '').toLowerCase()}${row.exception && !row.exception.expired ? ' wa-row--excepted' : ''}`}>
                    <td><SeverityBadge severity={row.severity} /></td>
                    <td className="wa-cell-domain">{row.domain}<ExceptionChip ex={row.exception} /></td>
                    <td>
                      {row.owner && <div>{row.owner}</div>}
                      {row.description && <div className="wa-sub">{row.description}</div>}
                      {!row.owner && !row.description && <span className="wa-muted">{t('wa.noOwner')}</span>}
                    </td>
                    <td><TeamCell row={row} />{row.team_email && <div className="wa-sub">{row.team_email}</div>}</td>
                    <td className="wa-mono">{row.signature_algorithm || '—'}</td>
                    <td className="wa-mono">{row.public_key_algorithm || '—'}{row.public_key_size && <span className="wa-sub">{row.public_key_size} bit</span>}</td>
                    <td>{(row.weaknesses || []).map((w, i) => <div key={i} className="wa-weakness-chip">{w}</div>)}</td>
                    <td>
                      <div>{row.not_after ? formatDate(row.not_after) : '—'}</div>
                      {row.days_remaining != null && (
                        <div className={`wa-sub ${row.days_remaining < 0 ? 'wa-expired' : row.days_remaining <= 30 ? 'wa-expiring' : ''}`}>
                          {row.days_remaining < 0 ? t('wa.daysPast', Math.abs(row.days_remaining)) : t('wa.daysLeft', row.days_remaining)}
                        </div>
                      )}
                    </td>
                    <td><StatusBadge status={row.status} /></td>
                    <td><Actions row={row} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Section>

      {/* ── 5. TLS / şifre bulguları ── */}
      <Section id="tls" icon={ShieldAlert} title={t('wa.secTls')} count={tlsRows.length} tone={tlsRows.length ? 'bad' : 'ok'} open={open.tls} onToggle={() => toggle('tls')} hint={t('wa.secTlsHint')}>
        {tlsRows.length === 0 ? <p className="wa-muted">{t('wa.tlsEmpty')}</p> : (
          <div className="wa-table-wrap">
            <table className="wa-table">
              <thead><tr>
                <th>{t('wa.colSeverity')}</th><th>{t('wa.colDomain')}</th><th>{t('wa.colTeam')}</th><th>{t('wa.colTls')}</th><th>{t('wa.colCipher')}</th><th>{t('wa.colFindings')}</th><th>{t('wa.colActions')}</th>
              </tr></thead>
              <tbody>
                {tlsRows.map(row => (
                  <tr key={row.domain} className={`wa-row wa-row-${(row.severity || '').toLowerCase()}`}>
                    <td><SeverityBadge severity={row.severity} /></td>
                    <td className="wa-cell-domain">{row.domain}<ExceptionChip ex={row.exception} /></td>
                    <td><TeamCell row={row} /></td>
                    <td className="wa-mono">{row.tls_version || '—'}</td>
                    <td className="wa-mono">{row.cipher_suite || '—'}</td>
                    <td>{(row.findings || []).map(k => <div key={k} className="wa-weakness-chip" title={t(`wa.ruleDesc.${k}`)}>{ruleLabel(k)}</div>)}</td>
                    <td><Actions row={row} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Section>

      {/* ── 6. Zincir / güven bulguları ── */}
      <Section id="chain" icon={Link2} title={t('wa.secChain')} count={chainRows.length} tone={chainRows.length ? 'bad' : 'ok'} open={open.chain} onToggle={() => toggle('chain')} hint={t('wa.secChainHint')}>
        {chainRows.length === 0 ? <p className="wa-muted">{t('wa.chainEmpty')}</p> : (
          <div className="wa-table-wrap">
            <table className="wa-table">
              <thead><tr>
                <th>{t('wa.colSeverity')}</th><th>{t('wa.colDomain')}</th><th>{t('wa.colTeam')}</th><th>{t('wa.colIssuer')}</th><th>{t('wa.colFindings')}</th><th>{t('wa.colIntermediate')}</th><th>{t('wa.colActions')}</th>
              </tr></thead>
              <tbody>
                {chainRows.map(row => (
                  <tr key={row.domain} className={`wa-row wa-row-${(row.severity || '').toLowerCase()}`}>
                    <td><SeverityBadge severity={row.severity} /></td>
                    <td className="wa-cell-domain">{row.domain}<ExceptionChip ex={row.exception} /></td>
                    <td><TeamCell row={row} /></td>
                    <td className="wa-mono">{row.issuer || '—'}</td>
                    <td>{(row.findings || []).map(k => <div key={k} className="wa-weakness-chip" title={t(`wa.ruleDesc.${k}`)}>{ruleLabel(k)}</div>)}</td>
                    <td>{row.intermediate_days != null ? t('wa.daysLeft', row.intermediate_days) : '—'}</td>
                    <td><Actions row={row} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Section>

      {/* ── 2. Kural kataloğu ── */}
      <Section id="rules" icon={ListChecks} title={t('wa.secRules')} count={rulesSorted.length} open={open.rules} onToggle={() => toggle('rules')} hint={t('wa.secRulesHint')}>
        <div className="wa-table-wrap">
          <table className="wa-table wa-table--rules">
            <thead><tr><th>{t('wa.colRule')}</th><th>{t('wa.colRuleDesc')}</th><th>{t('wa.colSeverity')}</th><th>{t('wa.colMatched')}</th></tr></thead>
            <tbody>
              {rulesSorted.map(r => (
                <tr key={r.key} className={r.matched > 0 ? 'wa-rule--hit' : ''}>
                  <td className="wa-cell-domain">{ruleLabel(r.key)}</td>
                  <td className="wa-sub">{t(`wa.ruleDesc.${r.key}`)}</td>
                  <td><SeverityBadge severity={r.severity} /></td>
                  <td><b className={r.matched > 0 ? 'wa-expired' : 'wa-ok'}>{r.matched}</b></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>

      {/* ── 3. Dağılım ── */}
      <Section id="dist" icon={BarChart3} title={t('wa.secDist')} open={open.dist} onToggle={() => toggle('dist')} hint={t('wa.secDistHint')}>
        <div className="wa-dist-grid">
          <Bars title={t('wa.distSig')} items={data?.distribution?.signature || []} />
          <Bars title={t('wa.distKey')} items={data?.distribution?.key || []} />
          <Bars title={t('wa.distTls')} items={data?.distribution?.tls || []} />
          <Bars title={t('wa.distCipher')} items={(data?.distribution?.cipher_tier || []).map(i => ({ ...i, label: t(`wa.tier.${i.label}`) }))} />
        </div>
      </Section>

      {/* ── 4. 2030 görünümü — risk + beklenen eylem (2026-09-12: "riskimizi bildirelim, bekleneni net aktaralım") ── */}
      <Section id="outlook" icon={CalendarClock} title={t('wa.secOutlook', data?.outlook?.year ?? 2030)} count={data?.outlook?.affected ?? 0}
        tone={(data?.outlook?.affected ?? 0) > 0 ? 'warn' : 'ok'} open={open.outlook} onToggle={() => toggle('outlook')}>
        {(() => {
          const ol = data?.outlook || {}
          const sm = ol.summary || {}
          const rowsO = ol.rows || []
          const sunset = ol.sunset ? formatDate(ol.sunset) : '31.12.2030'
          const years = Math.max(0, Math.round(((sm.days_to_sunset ?? 0) / 365.25) * 10) / 10)
          const actionLabel = (a) => t(`wa.outlookAction.${a || 'unknown'}`)
          return (
            <>
              {/* Uyarı bandı — bugün güvenli, yarın uyumsuz: risk sayılarla */}
              <div className={`wa-outlook-warn${rowsO.length ? '' : ' is-clear'}`} role="note">
                <div className="wa-outlook-warn-head">
                  {rowsO.length ? <AlertTriangle size={18} aria-hidden="true" /> : <ShieldCheck size={18} aria-hidden="true" />}
                  <b>{rowsO.length ? t('wa.outlookRiskTitle', rowsO.length, sm.pct_of_checked ?? 0, sunset) : t('wa.outlookEmpty')}</b>
                </div>
                <p>{t('wa.outlookWhat', ol.rsa_min_bits ?? 3072, sunset)}</p>
                <p>{t('wa.outlookWhy')}</p>
                {rowsO.length > 0 && (
                  <ul className="wa-outlook-risk">
                    <li>{t('wa.outlookRiskReissue', sm.reissue ?? 0)}</li>
                    <li>{t('wa.outlookRiskRenew', sm.renew ?? 0)}</li>
                    {(sm.unknown ?? 0) > 0 && <li>{t('wa.outlookRiskUnknown', sm.unknown)}</li>}
                    <li>{t('wa.outlookRiskTime', years, sm.days_to_sunset ?? 0)}</li>
                    {(sm.by_team || []).length > 0 && (
                      <li>{t('wa.outlookRiskTeams')} {sm.by_team.map(b => `${b.label} (${b.count})`).join(' · ')}</li>
                    )}
                  </ul>
                )}
              </div>

              {/* Ne bekleniyor — takımın yapacağı iş, adım adım */}
              <div className="wa-outlook-expect">
                <div className="wa-bars-title">{t('wa.outlookExpectTitle')}</div>
                <ol className="wa-outlook-steps">
                  <li>{t('wa.outlookStep1')}</li>
                  <li>{t('wa.outlookStep2')}</li>
                  <li>{t('wa.outlookStep3')}</li>
                  <li>{t('wa.outlookStep4')}</li>
                  <li>{t('wa.outlookStep5')}</li>
                </ol>
              </div>

              {rowsO.length > 0 && (
                <div className="wa-table-wrap">
                  <table className="wa-table">
                    <thead><tr>
                      <th>{t('wa.colAction')}</th><th>{t('wa.colDomain')}</th><th>{t('wa.colTeam')}</th><th>{t('wa.colKeyAlgo')}</th>
                      <th>{t('wa.colTarget')}</th><th>{t('wa.colExpiry')}</th><th>{t('wa.colRenewBy')}</th><th>{t('wa.colActions')}</th>
                    </tr></thead>
                    <tbody>
                      {rowsO.map(r => (
                        <tr key={r.domain} className={`wa-row wa-row-${r.action === 'reissue' ? 'high' : 'medium'}`}>
                          <td>
                            <span className={`wa-outlook-action wa-outlook-action--${r.action || 'unknown'}`}>{actionLabel(r.action)}</span>
                            <div className="wa-sub">{t(`wa.outlookActionHint.${r.action || 'unknown'}`)}</div>
                          </td>
                          <td className="wa-cell-domain">{r.domain}<ExceptionChip ex={r.exception} /></td>
                          <td><TeamCell row={r} /></td>
                          <td className="wa-mono">{r.public_key_algorithm} {r.public_key_size}<div className="wa-sub">{t('wa.outlookNow')}</div></td>
                          <td className="wa-mono">{r.target}<div className="wa-sub">{t('wa.outlookTargetHint')}</div></td>
                          <td>
                            <div>{r.not_after ? formatDate(r.not_after) : '—'}</div>
                            {r.days_remaining != null && <div className="wa-sub">{t('wa.daysLeft', r.days_remaining)}</div>}
                          </td>
                          <td className={r.action === 'reissue' ? 'wa-expiring' : ''}>{r.renewal_by ? formatDate(r.renewal_by) : '—'}</td>
                          <td><Actions row={r} /></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          )
        })()}
      </Section>

      {/* ── 7. Takım kırılımı ── */}
      <Section id="teams" icon={Users} title={t('wa.secTeams')} count={(data?.teams?.rows || []).length} open={open.teams} onToggle={() => toggle('teams')}>
        {data?.teams?.unowned?.total > 0 && (
          <p className={`wa-banner-info${data.teams.unowned.weak + data.teams.unowned.tls + data.teams.unowned.chain > 0 ? ' wa-expired' : ''}`}>
            {t('wa.unowned', data.teams.unowned.total, data.teams.unowned.weak + data.teams.unowned.tls + data.teams.unowned.chain)}
          </p>
        )}
        <div className="wa-table-wrap">
          <table className="wa-table">
            <thead><tr><th>{t('wa.colTeam')}</th><th>{t('wa.colTotal')}</th><th>{t('wa.colWeakCount')}</th><th>{t('wa.kpiTls')}</th><th>{t('wa.kpiChain')}</th><th>{t('wa.colRatio')}</th></tr></thead>
            <tbody>
              {(data?.teams?.rows || []).map(r => {
                const bad = r.weak + r.tls + r.chain
                return (
                  <tr key={r.team_id} className={bad > 0 ? 'wa-rule--hit' : ''}>
                    <td><TeamBadge teamId={r.team_id} teamName={r.team_name} /></td>
                    <td>{r.total}</td>
                    <td><b className={r.weak > 0 ? 'wa-expired' : 'wa-ok'}>{r.weak}</b></td>
                    <td><b className={r.tls > 0 ? 'wa-expired' : 'wa-ok'}>{r.tls}</b></td>
                    <td><b className={r.chain > 0 ? 'wa-expired' : 'wa-ok'}>{r.chain}</b></td>
                    <td className="wa-mono">{r.total ? `${Math.round(100 * (r.total - r.weak) / r.total)}%` : '—'}</td>
                  </tr>
                )
              })}
              {(data?.teams?.rows || []).length === 0 && <tr><td colSpan={6} className="wa-muted">{t('wa.noData')}</td></tr>}
            </tbody>
          </table>
        </div>
      </Section>

      {/* ── 8. Trend ── */}
      <Section id="trend" icon={TrendingUp} title={t('wa.secTrend', data?.trend?.days ?? 30)} open={open.trend} onToggle={() => toggle('trend')}
        count={(data?.trend?.detected || []).length + (data?.trend?.resolved || []).length}>
        <TrendChart series={data?.trend?.series || []} />
        <div className="wa-trend-lists">
          <div>
            <div className="wa-bars-title">{t('wa.trendDetected', (data?.trend?.detected || []).length)}</div>
            {(data?.trend?.detected || []).length === 0 && <div className="wa-muted">{t('wa.trendNone')}</div>}
            {(data?.trend?.detected || []).map(d => <div key={d.domain} className="wa-sub"><span className="wa-mono">{d.day}</span> · {d.domain}</div>)}
          </div>
          <div>
            <div className="wa-bars-title">{t('wa.trendResolved', (data?.trend?.resolved || []).length)}</div>
            {(data?.trend?.resolved || []).length === 0 && <div className="wa-muted">{t('wa.trendNone')}</div>}
            {(data?.trend?.resolved || []).map(d => <div key={d.domain} className="wa-sub"><span className="wa-mono">{d.day}</span> · {d.domain}</div>)}
          </div>
        </div>
      </Section>

      {/* ── İstisnalar ── */}
      <Section id="exceptions" icon={ClipboardCheck} title={t('wa.secExceptions')} count={(data?.exceptions || []).length} open={open.exceptions} onToggle={() => toggle('exceptions')} hint={t('wa.secExceptionsHint')}>
        {(data?.exceptions || []).length === 0 ? <p className="wa-muted">{t('wa.exceptionsEmpty')}</p> : (
          <div className="wa-table-wrap">
            <table className="wa-table">
              <thead><tr><th>{t('wa.colDomain')}</th><th>{t('wa.exceptionUntil')}</th><th>{t('wa.exceptionReason')}</th><th>{t('wa.colBy')}</th>{canManage && <th>{t('wa.colActions')}</th>}</tr></thead>
              <tbody>
                {data.exceptions.map(e => (
                  <tr key={e.domain} className={e.expired ? 'wa-rule--hit' : ''}>
                    <td className="wa-cell-domain">{e.domain}</td>
                    <td>{formatDateOnly(e.until)}{e.expired && <span className="wa-sub wa-expired">{t('wa.exceptionExpiredShort')}</span>}</td>
                    <td className="wa-sub">{e.reason || '—'}</td>
                    <td className="wa-sub">{e.created_by || '—'}{e.created_at && <div>{formatDateSec(e.created_at)}</div>}</td>
                    {canManage && (
                      <td className="wa-actions">
                        <button type="button" className="btn btn-sm btn-secondary" onClick={() => setExModal({ domain: e.domain, existing: e })}><ClipboardCheck size={13} /> {t('wa.exceptionEdit')}</button>
                        <button type="button" className="btn btn-sm btn-secondary" onClick={() => clearException(e.domain)}><ClipboardX size={13} /> {t('wa.exceptionClear')}</button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Section>

      <p className="wa-banner-info"><Bell size={12} aria-hidden="true" /> {t('wa.weeklyNote')}</p>

      {exModal && (
        <ExceptionModal domain={exModal.domain} existing={exModal.existing} onClose={() => setExModal(null)} onSaved={load} />
      )}
    </div>
  )
}
