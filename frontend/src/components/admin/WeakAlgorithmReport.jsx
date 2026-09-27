import { useState, useEffect, useCallback, useMemo } from 'react'
import { api, formatDate, formatDateSec, formatDateOnly } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import {
  AlertTriangle, ShieldAlert, ShieldCheck, RefreshCw, Download, Bell, BellRing,
  ClipboardCheck, ClipboardX, ScanSearch, Lock, Link2, Users, TrendingUp, CalendarClock, ListChecks, BarChart3,
} from 'lucide-react'
import { LoadingBlock, Spinner, ProgressBar } from '../ui/Progress.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import Field from '../ui/Field.jsx'
import CollapsibleSection from '../ui/CollapsibleSection.jsx'
import { DateTimePopover } from '../ui/DatePickerParts.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import ToneBadge from './ToneBadge.jsx'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Textarea } from '@/components/shadcn/textarea'
import { Table as UiTable, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

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

/** Önem rozeti dolguları — AlertHistory / AlertThresholds ile aynı önem jetonları. */
const SEV_BG = {
  CRITICAL: 'bg-(--severity-critical) text-white',
  HIGH: 'bg-(--severity-high) text-white',
  MEDIUM: 'bg-(--severity-warn) text-white',
}

function SeverityBadge({ severity }) {
  return (
    <Badge data-severity={severity} className={cn('rounded-sm border-transparent px-1.5 text-[0.72em] font-bold tracking-wide', SEV_BG[severity] || SEV_BG.MEDIUM)}>
      {severity}
    </Badge>
  )
}

function StatusBadge({ status }) {
  const t = useT()
  if (!status) return <span className="text-muted-foreground">—</span>
  const tone = status === 'error' ? 'danger' : status === 'ok' || status === 'valid' ? 'success' : 'warning'
  return <ToneBadge tone={tone} title={t('wa.statusTip')} className="font-bold">{status.toUpperCase()}</ToneBadge>
}

/** Sayaç rozeti tonları (bölüm başlığı). */
const COUNT_TONE = { ok: 'success', bad: 'danger', warn: 'warning' }

/**
 * Katlanır bölüm — ortak ui/CollapsibleSection (shadcn Collapsible; tetik aria-expanded, içerik kapalıyken DOM'da
 * yok). Sayaç rozeti başlıkta; ipucu kapalıyken yanında, telefonda alt satırda SARAR (eski `.wa-sec-hint` dar
 * ekranda sağa taşıyordu — responsive kapısında `weakalgo@phone/tablet` bilinen taşmaydı).
 */
function Section({ id, icon, title, count, tone, open, onToggle, children, hint }) {
  return (
    <CollapsibleSection data-sec={id} open={open} onOpenChange={onToggle} icon={icon} hint={hint}
      label={<>{title}{count != null && (
        <ToneBadge tone={COUNT_TONE[tone] || 'muted'} className="ml-2 rounded-full align-middle font-bold tabular-nums">{count}</ToneBadge>
      )}</>}
      // shadcn Button `whitespace-nowrap` taşır → uzun başlık/ipucu telefonda sarmayıp taşıyordu; başlık span'ı
      // telefonda kalan genişliği alır (yoksa flex-wrap ikonu ve oku ayrı satırlara iterdi)
      triggerClassName="whitespace-normal [&>span:first-of-type]:min-w-0 [&>span:first-of-type]:flex-[1_1_0%] sm:[&>span:first-of-type]:flex-initial" contentClassName="pt-2.5">
      {children}
    </CollapsibleSection>
  )
}

/** Bulgu kural çipi (amber). */
function RuleChip({ children, title }) {
  return <ToneBadge tone="warning" title={title} className="mb-0.5 block w-fit max-w-full font-semibold whitespace-normal">{children}</ToneBadge>
}
function Sub({ className, children }) {
  return <div className={cn('text-xs text-muted-foreground', className)}>{children}</div>
}
function Num({ bad }) {
  return <b className={bad > 0 ? 'text-destructive' : 'text-success'}>{bad}</b>
}

/** Bulgu tablosu kabuğu — shadcn Table (kendi içinde yatay kayar), başlık hücreleri tek tip. */
function Grid({ head, children, testId }) {
  return (
    <div className="overflow-hidden rounded-lg border">
      <UiTable data-testid={testId} className="text-[0.86em]">
        <TableHeader className="bg-muted/50">
          <TableRow>{head.map((h, i) => <TableHead key={i} className="text-[0.9em] font-bold text-muted-foreground">{h}</TableHead>)}</TableRow>
        </TableHeader>
        <TableBody>{children}</TableBody>
      </UiTable>
    </div>
  )
}

const KPI_VAL = { ok: 'text-success', bad: 'text-destructive', warn: 'text-amber-600 dark:text-amber-400' }
function Kpi({ label, value, sub, tone }) {
  return (
    <div data-kpi="" data-tone={tone} className="flex min-w-0 flex-col gap-0.5 rounded-[10px] border bg-card px-3 py-2.5">
      <span data-kpi-label="" className="text-[0.74em] font-semibold tracking-wide text-muted-foreground uppercase">{label}</span>
      <span data-kpi-value="" className={cn('text-[1.5em] leading-tight font-extrabold tabular-nums', KPI_VAL[tone])}>{value}</span>
      {sub && <span data-kpi-sub="" className="text-[0.74em] text-muted-foreground">{sub}</span>}
    </div>
  )
}

/** Yatay çubuk listesi (dağılım) — en büyük değer %100. */
function Bars({ items, title }) {
  const t = useT()
  const max = Math.max(1, ...items.map(i => i.count))
  return (
    <div className="flex min-w-0 flex-col gap-1.5 rounded-[10px] border bg-card px-3 py-2.5">
      <div className="text-[0.8em] font-bold tracking-wide text-muted-foreground uppercase">{title}</div>
      {items.length === 0 && <div className="text-sm text-muted-foreground">{t('wa.noData')}</div>}
      {items.map(i => (
        <div key={i.label} className="grid grid-cols-[minmax(0,1fr)_minmax(60px,1fr)_auto] items-center gap-2 text-[0.84em]">
          <span className="truncate font-mono">{i.label}</span>
          <ProgressBar value={i.count} max={max} size="sm" decorative />
          <b className="tabular-nums">{i.count}</b>
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
    <svg className="block h-[90px] w-full" viewBox={`0 0 ${W} ${H}`} role="img" aria-label={t('wa.trendAria')} preserveAspectRatio="none">
      {series.map((s, i) => {
        const h = Math.round((H - pad * 2) * s.weak / max)
        return (
          <g key={s.day}>
            <title>{`${s.day}: ${s.weak}`}</title>
            <rect x={pad + i * bw + 1} y={H - pad - h} width={Math.max(1, bw - 2)} height={h} data-trend-bar={s.weak > 0 ? 'weak' : 'ok'}
              className={s.weak > 0 ? 'fill-destructive' : 'fill-muted'} />
          </g>
        )
      })}
      <line x1={pad} y1={H - pad} x2={W - pad} y2={H - pad} className="stroke-border" />
    </svg>
  )
}

/** yyyy-MM-dd ↔ yerel takvim günü (saat dilimi kayması yok — istisna bitişi bir GÜNDÜR). */
const pad2 = (n) => String(n).padStart(2, '0')
const parseDay = (v) => {
  const [y, m, d] = String(v || '').slice(0, 10).split('-').map(Number)
  return y && m && d ? new Date(y, m - 1, d) : null
}
const toDay = (d) => (d ? `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}` : '')

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
        <Button type="button" variant="secondary" onClick={onClose} disabled={saving}>{t('app.cancel')}</Button>
        <Button type="button" onClick={save} disabled={saving || !until} aria-busy={saving || undefined}>
          {saving ? <Spinner size={14} inline decorative /> : <ClipboardCheck size={14} />} {t('wa.exceptionSave')}
        </Button>
      </>}>
      <p className="mb-3 text-sm text-muted-foreground">{t('wa.exceptionHelp')}</p>
      {/* Bitiş günü (zorunlu): shadcn Date Picker deseni (Popover + Calendar); alan etiketi tetiğin İÇİNDE,
          dolayısıyla düğmenin erişilebilir adı "Geçerlilik sonu 31.12.2026" olur. */}
      <div className="mb-3.5">
        <DateTimePopover withTime={false} label={t('wa.exceptionUntil')} value={parseDay(until)}
          onChange={(d) => setUntil(toDay(d))} placeholder="—" />
      </div>
      <Field label={t('wa.exceptionReason')} className="mb-0">
        {({ id }) => (
          <Textarea id={id} rows={3} value={reason} onChange={e => setReason(e.target.value)} placeholder={t('wa.exceptionReasonPh')} />
        )}
      </Field>
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

  if (loading && !data) return <LoadingBlock label={t('wa.loading')} />
  if (error && !data) {
    return (
      <StatusBlock tone="danger" icon={ShieldAlert} role="alert" title={error}
        actions={<Button type="button" variant="secondary" onClick={load}><RefreshCw size={14} /> {t('wa.retry')}</Button>} />
    )
  }

  /** Eylem hücresi — kontrol et / bildir / istisna (yalnız manage yetkisinde). Düğme adları SATIRI (alanı) içerir. */
  // DİKKAT: bunlar bileşen DEĞİL, çağrılan işlevler — render içinde tanımlanan bir bileşen her çizimde yeni
  // tür olur ve React tüm alt ağacı söküp yeniden kurar (satır referansları, odak kaybolur).
  const actions = (row) => {
    const name = (label) => t('a11y.rowAction', label, row.domain)
    return (
      <div className="flex flex-wrap gap-1.5">
        <Button type="button" variant="secondary" size="sm" onClick={() => checkNow(row.domain)}
          disabled={!!busy[row.domain]} aria-busy={busy[row.domain] === 'check' || undefined} aria-label={name(t('wa.actCheck'))}>
          {busy[row.domain] === 'check' ? <Spinner size={13} inline decorative /> : <RefreshCw size={13} />} {t('wa.actCheck')}
        </Button>
        {canManage && (
          <>
            <Button type="button" variant="secondary" size="sm" onClick={() => notify(row)}
              disabled={!!busy[row.domain] || !row.team_id} aria-busy={busy[row.domain] === 'notify' || undefined}
              aria-label={name(row.team_id ? t('wa.actNotify') : `${t('wa.actNotify')} (${t('wa.noTeam')})`)}>
              {busy[row.domain] === 'notify' ? <Spinner size={13} inline decorative /> : <BellRing size={13} />} {t('wa.actNotify')}
            </Button>
            {row.exception
              ? <Button type="button" variant="secondary" size="sm" onClick={() => clearException(row.domain)} aria-label={name(t('wa.exceptionClear'))}>
                  <ClipboardX size={13} /> {t('wa.exceptionClear')}
                </Button>
              : <Button type="button" variant="secondary" size="sm" onClick={() => setExModal({ domain: row.domain, existing: null })} aria-label={name(t('wa.actException'))}>
                  <ClipboardCheck size={13} /> {t('wa.actException')}
                </Button>}
          </>
        )}
      </div>
    )
  }

  const exceptionChip = (ex) => ex ? (
    <ToneBadge tone={ex.expired ? 'danger' : 'info'} data-expired={ex.expired ? 'true' : undefined} title={ex.reason || undefined} className="ml-1.5 align-middle">
      {ex.expired ? t('wa.exceptionExpired', formatDateOnly(ex.until)) : t('wa.exceptionUntilChip', formatDateOnly(ex.until))}
    </ToneBadge>
  ) : null

  const teamCell = (row) => row.team_name
    ? <TeamBadge teamId={row.team_id} teamName={row.team_name} />
    : <span className="text-muted-foreground">{t('wa.noTeam')}</span>

  const TD = 'align-top whitespace-normal'
  const DOMAIN = 'min-w-[160px] align-top font-semibold break-all whitespace-normal'
  const MONO = 'min-w-[90px] align-top font-mono text-[0.92em] whitespace-normal break-words'
  const empty = (text) => <p className="text-sm text-muted-foreground">{text}</p>

  return (
    <div className="flex min-w-0 flex-col gap-4" data-testid="weak-algo">
      {/* ── Araç çubuğu ── */}
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5 text-sm">
          <ScanSearch size={16} aria-hidden="true" className="shrink-0 text-muted-foreground" />
          <span>{t('wa.generatedAt', scan.generated_at ? formatDateSec(scan.generated_at) : '—')}</span>
          <span className="text-muted-foreground">· {t('wa.latestCheck', scan.latest_checked_at ? formatDateSec(scan.latest_checked_at) : '—')}</span>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button type="button" variant="secondary" size="sm" onClick={load} disabled={loading} aria-busy={loading || undefined}>
            {loading ? <Spinner size={13} inline decorative /> : <RefreshCw size={13} />} {t('wa.refresh')}
          </Button>
          <Button type="button" variant="secondary" size="sm" onClick={exportCsv}>
            <Download size={13} /> {t('wa.exportCsv')}
          </Button>
        </div>
      </div>

      {/* ── 1. Tarama özeti ── telefonda 2'li ızgara */}
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-[repeat(auto-fit,minmax(150px,1fr))]" role="group" aria-label={t('wa.scanTitle')}>
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
        <AlertBanner tone="success" icon={ShieldCheck} title={t('wa.cleanTitle', scan.checked ?? 0)} className="mb-0">
          {t('wa.cleanSub', rulesSorted.length)}
        </AlertBanner>
      ) : (
        <AlertBanner tone="warning" icon={AlertTriangle} title={t('wa.totalWeak', rows.length)} className="mb-0">
          <span className="mt-1 flex flex-wrap gap-1.5">
            {data.critical > 0 && <Badge className={SEV_BG.CRITICAL}>{t('wa.bannerCritical', data.critical)}</Badge>}
            {data.high > 0 && <Badge className={SEV_BG.HIGH}>{t('wa.bannerHigh', data.high)}</Badge>}
            {tlsRows.length > 0 && <Badge className="bg-violet-600 text-white">{t('wa.bannerTls', tlsRows.length)}</Badge>}
            {chainRows.length > 0 && <Badge className="bg-sky-700 text-white">{t('wa.bannerChain', chainRows.length)}</Badge>}
          </span>
          <span className="mt-1.5 block text-xs opacity-90">{t('wa.bannerInfo')}</span>
        </AlertBanner>
      )}

      {/* ── 9. Sertifika algoritması bulguları + eylemler ── */}
      <Section id="cert" icon={Lock} title={t('wa.secCert')} count={rows.length} tone={rows.length ? 'bad' : 'ok'} open={open.cert} onToggle={() => toggle('cert')}>
        {rows.length === 0 ? empty(t('wa.empty')) : (
          <Grid testId="wa-cert" head={[t('wa.colSeverity'), t('wa.colDomain'), t('wa.colOwner'), t('wa.colTeam'), t('wa.colSigAlgo'), t('wa.colKeyAlgo'), t('wa.colWeakness'), t('wa.colExpiry'), t('wa.colStatus'), t('wa.colActions')]}>
            {rows.map(row => (
              <TableRow key={row.domain} data-severity={row.severity} data-excepted={row.exception && !row.exception.expired ? 'true' : undefined}
                className="data-[excepted]:opacity-70">
                <TableCell className={TD}><SeverityBadge severity={row.severity} /></TableCell>
                <TableCell className={DOMAIN}>{row.domain}{exceptionChip(row.exception)}</TableCell>
                <TableCell className={TD}>
                  {row.owner && <div>{row.owner}</div>}
                  {row.description && <Sub>{row.description}</Sub>}
                  {!row.owner && !row.description && <span className="text-muted-foreground">{t('wa.noOwner')}</span>}
                </TableCell>
                <TableCell className={TD}>{teamCell(row)}{row.team_email && <Sub className="break-all">{row.team_email}</Sub>}</TableCell>
                <TableCell className={MONO}>{row.signature_algorithm || '—'}</TableCell>
                <TableCell className={MONO}>{row.public_key_algorithm || '—'}{row.public_key_size && <Sub>{row.public_key_size} bit</Sub>}</TableCell>
                <TableCell className={TD}>{(row.weaknesses || []).map((w, i) => <RuleChip key={i}>{w}</RuleChip>)}</TableCell>
                <TableCell className={TD}>
                  <div>{row.not_after ? formatDate(row.not_after) : '—'}</div>
                  {row.days_remaining != null && (
                    <Sub className={row.days_remaining < 0 ? 'font-semibold text-destructive' : row.days_remaining <= 30 ? 'font-semibold text-amber-600 dark:text-amber-400' : ''}>
                      {row.days_remaining < 0 ? t('wa.daysPast', Math.abs(row.days_remaining)) : t('wa.daysLeft', row.days_remaining)}
                    </Sub>
                  )}
                </TableCell>
                <TableCell className={TD}><StatusBadge status={row.status} /></TableCell>
                <TableCell className={TD}>{actions(row)}</TableCell>
              </TableRow>
            ))}
          </Grid>
        )}
      </Section>

      {/* ── 5. TLS / şifre bulguları ── */}
      <Section id="tls" icon={ShieldAlert} title={t('wa.secTls')} count={tlsRows.length} tone={tlsRows.length ? 'bad' : 'ok'} open={open.tls} onToggle={() => toggle('tls')} hint={t('wa.secTlsHint')}>
        {tlsRows.length === 0 ? empty(t('wa.tlsEmpty')) : (
          <Grid testId="wa-tls" head={[t('wa.colSeverity'), t('wa.colDomain'), t('wa.colTeam'), t('wa.colTls'), t('wa.colCipher'), t('wa.colFindings'), t('wa.colActions')]}>
            {tlsRows.map(row => (
              <TableRow key={row.domain} data-severity={row.severity}>
                <TableCell className={TD}><SeverityBadge severity={row.severity} /></TableCell>
                <TableCell className={DOMAIN}>{row.domain}{exceptionChip(row.exception)}</TableCell>
                <TableCell className={TD}>{teamCell(row)}</TableCell>
                <TableCell className={MONO}>{row.tls_version || '—'}</TableCell>
                <TableCell className={MONO}>{row.cipher_suite || '—'}</TableCell>
                <TableCell className={TD}>{(row.findings || []).map(k => <RuleChip key={k} title={t(`wa.ruleDesc.${k}`)}>{ruleLabel(k)}</RuleChip>)}</TableCell>
                <TableCell className={TD}>{actions(row)}</TableCell>
              </TableRow>
            ))}
          </Grid>
        )}
      </Section>

      {/* ── 6. Zincir / güven bulguları ── */}
      <Section id="chain" icon={Link2} title={t('wa.secChain')} count={chainRows.length} tone={chainRows.length ? 'bad' : 'ok'} open={open.chain} onToggle={() => toggle('chain')} hint={t('wa.secChainHint')}>
        {chainRows.length === 0 ? empty(t('wa.chainEmpty')) : (
          <Grid testId="wa-chain" head={[t('wa.colSeverity'), t('wa.colDomain'), t('wa.colTeam'), t('wa.colIssuer'), t('wa.colFindings'), t('wa.colIntermediate'), t('wa.colActions')]}>
            {chainRows.map(row => (
              <TableRow key={row.domain} data-severity={row.severity}>
                <TableCell className={TD}><SeverityBadge severity={row.severity} /></TableCell>
                <TableCell className={DOMAIN}>{row.domain}{exceptionChip(row.exception)}</TableCell>
                <TableCell className={TD}>{teamCell(row)}</TableCell>
                <TableCell className={MONO}>{row.issuer || '—'}</TableCell>
                <TableCell className={TD}>{(row.findings || []).map(k => <RuleChip key={k} title={t(`wa.ruleDesc.${k}`)}>{ruleLabel(k)}</RuleChip>)}</TableCell>
                <TableCell className={TD}>{row.intermediate_days != null ? t('wa.daysLeft', row.intermediate_days) : '—'}</TableCell>
                <TableCell className={TD}>{actions(row)}</TableCell>
              </TableRow>
            ))}
          </Grid>
        )}
      </Section>

      {/* ── 2. Kural kataloğu ── */}
      <Section id="rules" icon={ListChecks} title={t('wa.secRules')} count={rulesSorted.length} open={open.rules} onToggle={() => toggle('rules')} hint={t('wa.secRulesHint')}>
        <Grid testId="wa-rules" head={[t('wa.colRule'), t('wa.colRuleDesc'), t('wa.colSeverity'), t('wa.colMatched')]}>
          {rulesSorted.map(r => (
            <TableRow key={r.key} data-hit={r.matched > 0 ? 'true' : undefined} className="data-[hit]:bg-destructive/5">
              <TableCell className={DOMAIN}>{ruleLabel(r.key)}</TableCell>
              <TableCell className={cn(TD, 'min-w-[220px] text-xs text-muted-foreground')}>{t(`wa.ruleDesc.${r.key}`)}</TableCell>
              <TableCell className={TD}><SeverityBadge severity={r.severity} /></TableCell>
              <TableCell className={TD}><Num bad={r.matched} /></TableCell>
            </TableRow>
          ))}
        </Grid>
      </Section>

      {/* ── 3. Dağılım ── */}
      <Section id="dist" icon={BarChart3} title={t('wa.secDist')} open={open.dist} onToggle={() => toggle('dist')} hint={t('wa.secDistHint')}>
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
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
          const ACTION_TONE = { reissue: 'danger', renew: 'warning' }
          return (
            <div className="flex flex-col gap-3">
              {/* Uyarı bandı — bugün güvenli, yarın uyumsuz: risk sayılarla (TÜM çerçeve; sol şerit YOK) */}
              <AlertBanner tone={rowsO.length ? 'warning' : 'success'} icon={rowsO.length ? AlertTriangle : ShieldCheck} className="mb-0"
                title={rowsO.length ? t('wa.outlookRiskTitle', rowsO.length, sm.pct_of_checked ?? 0, sunset) : t('wa.outlookEmpty')}>
                <span className="flex flex-col gap-1.5">
                  <span>{t('wa.outlookWhat', ol.rsa_min_bits ?? 3072, sunset)}</span>
                  <span>{t('wa.outlookWhy')}</span>
                  {rowsO.length > 0 && (
                    <ul className="list-disc pl-5">
                      <li>{t('wa.outlookRiskReissue', sm.reissue ?? 0)}</li>
                      <li>{t('wa.outlookRiskRenew', sm.renew ?? 0)}</li>
                      {(sm.unknown ?? 0) > 0 && <li>{t('wa.outlookRiskUnknown', sm.unknown)}</li>}
                      <li>{t('wa.outlookRiskTime', years, sm.days_to_sunset ?? 0)}</li>
                      {(sm.by_team || []).length > 0 && (
                        <li>{t('wa.outlookRiskTeams')} {sm.by_team.map(b => `${b.label} (${b.count})`).join(' · ')}</li>
                      )}
                    </ul>
                  )}
                </span>
              </AlertBanner>

              {/* Ne bekleniyor — takımın yapacağı iş, adım adım */}
              <div className="rounded-[10px] border bg-card px-3.5 py-3">
                <div className="mb-1.5 text-[0.8em] font-bold tracking-wide text-muted-foreground uppercase">{t('wa.outlookExpectTitle')}</div>
                <ol className="list-decimal space-y-1 pl-5 text-sm">
                  <li>{t('wa.outlookStep1')}</li>
                  <li>{t('wa.outlookStep2')}</li>
                  <li>{t('wa.outlookStep3')}</li>
                  <li>{t('wa.outlookStep4')}</li>
                  <li>{t('wa.outlookStep5')}</li>
                </ol>
              </div>

              {rowsO.length > 0 && (
                <Grid testId="wa-outlook" head={[t('wa.colAction'), t('wa.colDomain'), t('wa.colTeam'), t('wa.colKeyAlgo'), t('wa.colTarget'), t('wa.colExpiry'), t('wa.colRenewBy'), t('wa.colActions')]}>
                  {rowsO.map(r => (
                    <TableRow key={r.domain} data-action={r.action || 'unknown'}>
                      <TableCell className={cn(TD, 'min-w-[160px]')}>
                        <ToneBadge tone={ACTION_TONE[r.action] || 'muted'} className="font-bold">{actionLabel(r.action)}</ToneBadge>
                        <Sub>{t(`wa.outlookActionHint.${r.action || 'unknown'}`)}</Sub>
                      </TableCell>
                      <TableCell className={DOMAIN}>{r.domain}{exceptionChip(r.exception)}</TableCell>
                      <TableCell className={TD}>{teamCell(r)}</TableCell>
                      <TableCell className={MONO}>{r.public_key_algorithm} {r.public_key_size}<Sub>{t('wa.outlookNow')}</Sub></TableCell>
                      <TableCell className={MONO}>{r.target}<Sub>{t('wa.outlookTargetHint')}</Sub></TableCell>
                      <TableCell className={TD}>
                        <div>{r.not_after ? formatDate(r.not_after) : '—'}</div>
                        {r.days_remaining != null && <Sub>{t('wa.daysLeft', r.days_remaining)}</Sub>}
                      </TableCell>
                      <TableCell className={cn(TD, r.action === 'reissue' && 'font-semibold text-amber-600 dark:text-amber-400')}>{r.renewal_by ? formatDate(r.renewal_by) : '—'}</TableCell>
                      <TableCell className={TD}>{actions(r)}</TableCell>
                    </TableRow>
                  ))}
                </Grid>
              )}
            </div>
          )
        })()}
      </Section>

      {/* ── 7. Takım kırılımı ── */}
      <Section id="teams" icon={Users} title={t('wa.secTeams')} count={(data?.teams?.rows || []).length} open={open.teams} onToggle={() => toggle('teams')}>
        <div className="flex flex-col gap-2.5">
          {data?.teams?.unowned?.total > 0 && (
            <p className={cn('text-sm text-muted-foreground', data.teams.unowned.weak + data.teams.unowned.tls + data.teams.unowned.chain > 0 && 'font-semibold text-destructive')}>
              {t('wa.unowned', data.teams.unowned.total, data.teams.unowned.weak + data.teams.unowned.tls + data.teams.unowned.chain)}
            </p>
          )}
          <Grid testId="wa-teams" head={[t('wa.colTeam'), t('wa.colTotal'), t('wa.colWeakCount'), t('wa.kpiTls'), t('wa.kpiChain'), t('wa.colRatio')]}>
            {(data?.teams?.rows || []).map(r => {
              const bad = r.weak + r.tls + r.chain
              return (
                <TableRow key={r.team_id} data-hit={bad > 0 ? 'true' : undefined} className="data-[hit]:bg-destructive/5">
                  <TableCell className={TD}><TeamBadge teamId={r.team_id} teamName={r.team_name} /></TableCell>
                  <TableCell className="tabular-nums">{r.total}</TableCell>
                  <TableCell><Num bad={r.weak} /></TableCell>
                  <TableCell><Num bad={r.tls} /></TableCell>
                  <TableCell><Num bad={r.chain} /></TableCell>
                  <TableCell className="font-mono">{r.total ? `${Math.round(100 * (r.total - r.weak) / r.total)}%` : '—'}</TableCell>
                </TableRow>
              )
            })}
            {(data?.teams?.rows || []).length === 0 && (
              <TableRow><TableCell colSpan={6} className="text-muted-foreground">{t('wa.noData')}</TableCell></TableRow>
            )}
          </Grid>
        </div>
      </Section>

      {/* ── 8. Trend ── */}
      <Section id="trend" icon={TrendingUp} title={t('wa.secTrend', data?.trend?.days ?? 30)} open={open.trend} onToggle={() => toggle('trend')}
        count={(data?.trend?.detected || []).length + (data?.trend?.resolved || []).length}>
        <div className="flex flex-col gap-3">
          <div className="rounded-lg border bg-card p-2"><TrendChart series={data?.trend?.series || []} /></div>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {[['detected', t('wa.trendDetected', (data?.trend?.detected || []).length)], ['resolved', t('wa.trendResolved', (data?.trend?.resolved || []).length)]].map(([k, title]) => (
              <div key={k} className="min-w-0">
                <div className="mb-1 text-[0.8em] font-bold tracking-wide text-muted-foreground uppercase">{title}</div>
                {(data?.trend?.[k] || []).length === 0 && <div className="text-sm text-muted-foreground">{t('wa.trendNone')}</div>}
                {(data?.trend?.[k] || []).map(d => <Sub key={d.domain} className="break-all"><span className="font-mono">{d.day}</span> · {d.domain}</Sub>)}
              </div>
            ))}
          </div>
        </div>
      </Section>

      {/* ── İstisnalar ── */}
      <Section id="exceptions" icon={ClipboardCheck} title={t('wa.secExceptions')} count={(data?.exceptions || []).length} open={open.exceptions} onToggle={() => toggle('exceptions')} hint={t('wa.secExceptionsHint')}>
        {(data?.exceptions || []).length === 0 ? empty(t('wa.exceptionsEmpty')) : (
          <Grid testId="wa-exceptions" head={[t('wa.colDomain'), t('wa.exceptionUntil'), t('wa.exceptionReason'), t('wa.colBy'), ...(canManage ? [t('wa.colActions')] : [])]}>
            {data.exceptions.map(e => (
              <TableRow key={e.domain} data-hit={e.expired ? 'true' : undefined} className="data-[hit]:bg-destructive/5">
                <TableCell className={DOMAIN}>{e.domain}</TableCell>
                <TableCell className={TD}>{formatDateOnly(e.until)}{e.expired && <Sub className="font-semibold text-destructive">{t('wa.exceptionExpiredShort')}</Sub>}</TableCell>
                <TableCell className={cn(TD, 'min-w-[180px] text-xs text-muted-foreground')}>{e.reason || '—'}</TableCell>
                <TableCell className={cn(TD, 'text-xs text-muted-foreground')}>{e.created_by || '—'}{e.created_at && <div>{formatDateSec(e.created_at)}</div>}</TableCell>
                {canManage && (
                  <TableCell className={TD}>
                    <div className="flex flex-wrap gap-1.5">
                      <Button type="button" variant="secondary" size="sm" onClick={() => setExModal({ domain: e.domain, existing: e })}
                        aria-label={t('a11y.rowAction', t('wa.exceptionEdit'), e.domain)}><ClipboardCheck size={13} /> {t('wa.exceptionEdit')}</Button>
                      <Button type="button" variant="secondary" size="sm" onClick={() => clearException(e.domain)}
                        aria-label={t('a11y.rowAction', t('wa.exceptionClear'), e.domain)}><ClipboardX size={13} /> {t('wa.exceptionClear')}</Button>
                    </div>
                  </TableCell>
                )}
              </TableRow>
            ))}
          </Grid>
        )}
      </Section>

      <p className="flex items-center gap-1.5 text-xs text-muted-foreground"><Bell size={12} aria-hidden="true" className="shrink-0" /> {t('wa.weeklyNote')}</p>

      {exModal && (
        <ExceptionModal domain={exModal.domain} existing={exModal.existing} onClose={() => setExModal(null)} onSaved={load} />
      )}
    </div>
  )
}
