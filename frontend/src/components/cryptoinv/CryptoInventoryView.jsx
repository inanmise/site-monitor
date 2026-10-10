import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  AlertTriangle, Atom, CalendarClock, CircleHelp, Download, FileSpreadsheet, FileText, Hash, Hourglass, Layers, RefreshCw,
  ScanSearch, ShieldCheck, ShieldX, Sheet,
} from 'lucide-react'
import { api, formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { formatPercent } from '../../i18n/dateLocale.js'
import { readUrlParam, useUrlQuerySync } from '../../hooks/useUrlQuerySync.js'
import MonitorStatsBar from '../MonitorStatsBar.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { LoadingBlock, Spinner } from '../ui/Progress.jsx'
import { useToast } from '../ui/Toast.jsx'
import { Button } from '@/components/shadcn/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/shadcn/dropdown-menu'
import { cn } from '@/lib/utils'
import { KeyDistributionCard, PqcReadinessCard, SignatureCard } from './CryptoCharts.jsx'
import CryptoMigrationList from './CryptoMigrationList.jsx'
import CryptoRuleCard from './CryptoRuleCard.jsx'
import CryptoTeamsCard from './CryptoTeamsCard.jsx'
import {
  DEFAULT_FILTERS, DUE_DAYS, URL_KEYS, filterRows, filtersToUrl, isFiltered, readiness, sanitizeFilters, scopeText, sortRows,
  teamOptions,
} from './cryptoInventoryModel.js'

/** KPI kutucukları: anahtar → süzgeç yaması ve "etkin mi" yüklemi (tek tanım). */
const KPI_FILTERS = {
  vulnerable: { patch: { pqc: 'VULNERABLE' }, on: (f) => f.pqc === 'VULNERABLE' },
  broken: { patch: { category: 'BROKEN' }, on: (f) => f.category === 'BROKEN' },
  legacy: { patch: { category: 'LEGACY' }, on: (f) => f.category === 'LEGACY' },
  remnants: { patch: { remnant: true }, on: (f) => f.remnant },
  due: { patch: { due: true }, on: (f) => f.due },
  ready: { patch: { category: 'PQC_READY' }, on: (f) => f.category === 'PQC_READY' },
  unknown: { patch: { category: 'UNKNOWN' }, on: (f) => f.category === 'UNKNOWN' },
}

function readFilters() {
  const raw = {}
  for (const [k, key] of Object.entries(URL_KEYS)) raw[k] = readUrlParam(key, null)
  return sanitizeFilters(raw)
}

/**
 * Kripto envanteri ve kuantum sonrası (PQC) hazırlık görünümü (2026-10-10, kullanıcı isteği: "Anahtar algoritması ve
 * boyu dağılımını, SHA-1 kalıntılarını ve takım bazlı geçiş listesini gösterir. Düzenleyici raporlama için hazır olur.").
 * Zayıf Algoritma sayfasının ikinci sekmesidir (aynı izin, aynı kapsam, aynı veri).
 *
 * Bölümler: künye + dışa aktarım (XLSX / PDF / CSV, tembel modül) · hüküm bandı · KPI kutucukları (süzgeç) · PQC hazırlık
 * halkası · algoritma × boy · imza özeti + SHA-1/MD5 kalıntıları · takım bazlı geçiş özeti · geçiş listesi · yöntem.
 * Sınıflandırma ve puan SUNUCUDA (tek kaynak); burada yalnız süzme/sıralama. Süzgeçler URL'de (`ci_*`).
 */
export default function CryptoInventoryView() {
  const t = useT()
  const toast = useToast()
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(null)
  const [filters, setFilters] = useState(readFilters)
  const listAnchor = useRef(null)
  const seqRef = useRef(0)

  useUrlQuerySync(filtersToUrl(filters))

  const load = useCallback(async (fresh = false) => {
    const seq = ++seqRef.current
    setLoading(true)
    try {
      const r = await api.cryptoInventory.get(fresh)
      if (seq !== seqRef.current) return
      if (r?.success && r.data) { setData(r.data); setError(null) } else setError(r?.error || t('cinv.loadError'))
    } catch (e) {
      if (seq !== seqRef.current) return
      setError(e?.message || t('cinv.loadError'))
    } finally {
      if (seq === seqRef.current) setLoading(false)
    }
  }, [t])

  useEffect(() => { load(false) }, [load])

  const rows = useMemo(() => data?.rows || [], [data])
  const visible = useMemo(() => sortRows(filterRows(rows, filters), filters.sort), [rows, filters])
  const teamOpts = useMemo(() => teamOptions(rows), [rows])
  const teamName = useCallback((id) => teamOpts.find((o) => o.value === id)?.label || null, [teamOpts])
  const filtered = isFiltered(filters)

  const patch = useCallback((p) => setFilters((f) => ({ ...f, ...p })), [])
  const clear = useCallback(() => setFilters((f) => ({ ...DEFAULT_FILTERS, sort: f.sort })), [])
  const scrollToList = () => {
    try { listAnchor.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }) } catch { /* jsdom */ }
  }
  /** Grafik/KPI tıklaması: aynı değer seçiliyse kaldırır, değilse uygular ve listeye kaydırır. */
  const toggle = (key, value) => {
    const off = filters[key] === value
    patch({ [key]: off ? DEFAULT_FILTERS[key] : value })
    if (!off) scrollToList()
  }

  async function runExport(kind) {
    if (!data || busy) return
    setBusy(kind)
    try {
      const mod = await import('./cryptoInventoryExport.js')
      let n
      if (kind === 'pdf') n = await mod.exportCryptoPdf(data, visible, filters, t, teamName)
      else if (kind === 'xlsx') n = mod.exportCryptoXlsx(data, visible, filters, t, teamName)
      else n = mod.exportCryptoCsv(visible, t)
      toast.success(t('cinv.exportDone', n))
      // Düzenleyici iz (CRYPTO_INVENTORY_EXPORT) — en iyi çaba, indirmeyi engellemez
      Promise.resolve(api.cryptoInventory.auditExport({ format: kind, rows: n, filtered })).catch(() => { /* iz yazılamadı */ })
    } catch {
      toast.error(t('cinv.exportFailed'))
    } finally {
      setBusy(null)
    }
  }

  if (loading && !data) return <LoadingBlock label={t('cinv.loading')} />
  if (error && !data) {
    return (
      <StatusBlock tone="danger" icon={ShieldX} role="alert" title={t('cinv.loadErrorTitle')} description={error}
        actions={<Button type="button" variant="outline" onClick={() => load(true)} className="min-h-10"><RefreshCw aria-hidden="true" />{t('cinv.retry')}</Button>} />
    )
  }

  const s = data?.summary || {}
  const r = readiness(s)
  const rem = s.remnants || {}
  const byCat = s.by_category || {}
  const p1 = s.by_band?.P1 ?? 0
  const kpiActive = Object.keys(KPI_FILTERS).find((k) => KPI_FILTERS[k].on(filters)) || null
  const tiles = [
    { key: 'total', Icon: Layers, cls: 'total', value: r.total, label: t('cinv.kpi.total'), hint: t('cinv.kpiHint.total'),
      sub: t('cinv.kpi.totalSub', s.network ?? 0, s.manual ?? 0), tip: t('cinv.kpiTip.total'), onClick: clear },
    { key: 'vulnerable', Icon: Atom, cls: 'warning', value: r.vulnerable, label: t('cinv.kpi.vulnerable'), hint: t('cinv.kpiHint.vulnerable'),
      sub: formatPercent(r.vulnerablePct) },
    { key: 'broken', Icon: ShieldX, cls: 'critical', value: byCat.BROKEN ?? 0, label: t('cinv.kpi.broken'), hint: t('cinv.kpiHint.broken') },
    { key: 'legacy', Icon: CalendarClock, cls: 'high', value: byCat.LEGACY ?? 0, label: t('cinv.kpi.legacy'), hint: t('cinv.kpiHint.legacy') },
    { key: 'remnants', Icon: Hash, cls: 'weak', value: rem.affected ?? 0, label: t('cinv.kpi.remnants'), hint: t('cinv.kpiHint.remnants') },
    { key: 'due', Icon: Hourglass, cls: 'alert', value: s.vulnerable_expiring_90d ?? 0, label: t('cinv.kpi.due', DUE_DAYS), hint: t('cinv.kpiHint.due', DUE_DAYS) },
    { key: 'ready', Icon: ShieldCheck, cls: 'valid', value: r.ready, label: t('cinv.kpi.ready'), hint: t('cinv.kpiHint.ready') },
    { key: 'unknown', Icon: CircleHelp, cls: 'paused', value: byCat.UNKNOWN ?? 0, label: t('cinv.kpi.unknown'), hint: t('cinv.kpiHint.unknown') },
  ]
  const onKpi = (key) => {
    const k = KPI_FILTERS[key]
    if (!k) return
    if (k.on(filters)) patch(Object.fromEntries(Object.keys(k.patch).map((x) => [x, DEFAULT_FILTERS[x]])))
    else { patch(k.patch); scrollToList() }
  }

  return (
    <div data-testid="crypto-inventory" className="flex min-w-0 flex-col gap-4">
      {/* ── Künye + eylemler ── */}
      <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex min-w-0 flex-col gap-0.5 text-sm" data-slot="cinv-meta">
          <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5">
            <ScanSearch size={16} aria-hidden="true" className="shrink-0 text-muted-foreground" />
            <span>{t('cinv.generatedAt', data?.generated_at ? formatDateSec(data.generated_at) : '—')}</span>
            <span className="text-muted-foreground">· {t('cinv.dataAsOf', data?.data_as_of ? formatDateSec(data.data_as_of) : '—')}</span>
          </span>
          <span className="text-xs text-muted-foreground" data-slot="cinv-scope">{scopeText(data?.scope, t)}</span>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button type="button" variant="outline" size="sm" onClick={() => load(true)} disabled={loading} aria-busy={loading || undefined}
            className="min-h-10 sm:min-h-8">
            {loading ? <Spinner size={13} inline decorative /> : <RefreshCw aria-hidden="true" />}{t('cinv.refresh')}
          </Button>
          <DropdownMenu modal={false}>
            <DropdownMenuTrigger asChild>
              <Button type="button" variant="default" size="sm" disabled={!!busy || r.total === 0} aria-busy={!!busy || undefined}
                data-slot="cinv-export" className="min-h-10 sm:min-h-8">
                {busy ? <Spinner size={13} inline decorative /> : <Download aria-hidden="true" />}
                {busy ? t('cinv.exporting') : t('cinv.export')}
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="z-(--z-menu) min-w-56">
              <DropdownMenuItem onSelect={() => runExport('xlsx')} data-export="xlsx" className="min-h-10"><FileSpreadsheet aria-hidden="true" />{t('cinv.exportXlsx')}</DropdownMenuItem>
              <DropdownMenuItem onSelect={() => runExport('pdf')} data-export="pdf" className="min-h-10"><FileText aria-hidden="true" />{t('cinv.exportPdf')}</DropdownMenuItem>
              <DropdownMenuItem onSelect={() => runExport('csv')} data-export="csv" className="min-h-10"><Sheet aria-hidden="true" />{t('cinv.exportCsv')}</DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </div>

      {r.total === 0 ? (
        <StatusBlock tone="neutral" icon={Layers} title={t('cinv.emptyTitle')} description={t('cinv.emptyBody')} />
      ) : (
        <>
          {/* ── Hüküm bandı ── */}
          <AlertBanner tone={p1 > 0 ? 'warning' : 'info'} icon={p1 > 0 ? AlertTriangle : Atom} className="mb-0"
            title={t('cinv.verdictTitle', r.vulnerable, r.total, formatPercent(r.vulnerablePct))}>
            <span className="flex flex-col gap-1">
              <span>{p1 > 0 ? t('cinv.verdictP1', p1) : t('cinv.verdictNoP1')}</span>
              <span className="text-xs opacity-90">{t('cinv.verdictWhy')}</span>
            </span>
          </AlertBanner>

          {/* ── KPI kutucukları: her biri listenin süzgeci ── */}
          <div className="[&>[data-slot=stats-panel]]:mb-0" data-slot="cinv-kpis">
            <MonitorStatsBar items={tiles} activeFilter={kpiActive} onStatClick={onKpi} />
          </div>

          {/* ── Dağılım ── */}
          <div className="grid min-w-0 grid-cols-1 gap-4 lg:grid-cols-2 2xl:grid-cols-3">
            <PqcReadinessCard summary={s} active={filters.pqc} onPick={(k) => toggle('pqc', k)} />
            <KeyDistributionCard algorithms={data?.algorithms} active={filters.bucket} onPick={(b) => toggle('bucket', b)} />
            <SignatureCard signatures={data?.signatures} remnants={rem} activeHash={filters.hash} remnantOn={filters.remnant}
              onPickHash={(h) => toggle('hash', h)} onRemnants={() => toggle('remnant', true)} className="lg:col-span-2 2xl:col-span-1" />
          </div>

          {/* ── Takım bazlı geçiş özeti ── */}
          <CryptoTeamsCard teams={data?.teams} unowned={data?.unowned} activeTeam={filters.team} onPick={(id) => toggle('team', id)} />

          {/* ── Geçiş listesi ── */}
          <div ref={listAnchor} data-slot="cinv-list-anchor" className={cn('min-w-0 scroll-mt-4')}>
            <CryptoMigrationList rows={visible} total={r.total} filters={filters} onChange={patch} onClear={clear}
              teamOpts={teamOpts} filtered={filtered} />
          </div>

          <CryptoRuleCard rule={data?.rule} thresholds={data?.thresholds} />
        </>
      )}
    </div>
  )
}
