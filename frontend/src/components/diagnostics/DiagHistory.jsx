import { useEffect, useMemo, useState } from 'react'
import { History, ArrowLeft, GitCompare } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import ToneBadge from '../admin/ToneBadge.jsx'
import DiffTable from '../admin/audit/DiffTable.jsx'
import DomainExpiryTrace from '../DomainExpiryTrace.jsx'
import { TH, TD, DataTable, KV_GRID } from '../admin/HealthUi.jsx'
import { Button } from '@/components/shadcn/button'
import { TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { Spinner } from '../ui/Progress.jsx'
import { cn } from '@/lib/utils'
import { buildSteps, buildVerdict, diffRuns } from './diagModel.js'
import { ShowField, PRE, Running, Section } from './DiagParts.jsx'
import { CombosTable, HstsResult, NetworkResult, OpensslResult, TlsClientInfo } from './DiagAdvanced.jsx'
import DiagVerdict from './DiagVerdict.jsx'

/** Kayıt türü → etiket anahtarı (DOMAIN_EXPIRY / PROXY_CA_CHAIN eskiden boş matris çiziyordu). */
const TYPE_KEY = {
  CONNECTION: 'inv.histTypeConnection', OPENSSL: 'inv.histTypeOpenssl', NETWORK: 'inv.histTypeNetwork',
  HSTS: 'inv.histTypeHsts', DOMAIN_EXPIRY: 'diag.histTypeDomainExpiry', PROXY_CA_CHAIN: 'diag.histTypeProxyCa',
}

function parseResult(json) {
  try { return JSON.parse(json) } catch { return null }
}

/**
 * Tanılama geçmişi — pencerenin İÇİNDE (iç içe ikinci pencere yerine; 2026-09-26). Liste (tablo; telefonda
 * düşük öncelikli sütunlar gizli) → kayıt ayrıntısı (aynı çizimler: bağlantı koşusu için hüküm + matris,
 * openssl/ağ/HSTS/alan adı için kendi gövdeleri) → bağlantı koşusunda "öncekiyle karşılaştır" (DiffTable).
 * Yetki kapısı (diagnostics.history) ÇAĞIRANDA; burada yalnız veri.
 *
 * @param {string} domain
 * @param {boolean} active  bölüm açık mı (ilk açılışta yüklenir)
 */
export default function DiagHistory({ domain, active }) {
  const t = useT()
  const toast = useToast()
  const [state, setState] = useState({ loading: false, items: null, error: null })
  const [detail, setDetail] = useState(null)       // { row, result }
  const [compare, setCompare] = useState(null)     // { loading, prev, rows }

  useEffect(() => {
    if (!active || state.items || state.loading) return
    let alive = true
    setState({ loading: true, items: null, error: null })
    api.admin.diagHistory(domain).then((res) => {
      if (!alive) return
      setState(res?.success ? { loading: false, items: res.data ?? [], error: null } : { loading: false, items: null, error: res?.error || t('inv.diagError') })
    }).catch(() => { if (alive) setState({ loading: false, items: null, error: t('inv.diagError') }) })
    return () => { alive = false }
  }, [active, domain]) // eslint-disable-line react-hooks/exhaustive-deps

  const typeLabel = (rt) => t(TYPE_KEY[rt] || 'inv.histTypeConnection')

  async function openDetail(row) {
    setCompare(null)
    const res = await api.admin.diagHistoryDetail(row.id)
    if (res?.success) setDetail({ row: { ...row, ...res.data }, result: parseResult(res.data?.result_json) })
    else toast.error(res?.error || t('inv.diagError'))
  }

  /** Aynı türdeki (CONNECTION) bir önceki kayıt — liste id'ye göre azalan sıralı. */
  const previousRow = useMemo(() => {
    if (!detail || detail.row.run_type !== 'CONNECTION' || !state.items) return null
    const idx = state.items.findIndex((h) => h.id === detail.row.id)
    return state.items.slice(idx + 1).find((h) => h.run_type === 'CONNECTION') || null
  }, [detail, state.items])

  async function runCompare() {
    if (!previousRow) return
    setCompare({ loading: true })
    const res = await api.admin.diagHistoryDetail(previousRow.id)
    if (!res?.success) { setCompare(null); toast.error(res?.error || t('inv.diagError')); return }
    const prev = parseResult(res.data?.result_json)
    setCompare({ loading: false, prev: { ...previousRow, ...res.data }, rows: diffRuns(detail.result, prev, t) })
  }

  if (!active) return null

  return (
    <div data-slot="diag-history" className="flex min-w-0 flex-col gap-3">
      {state.loading && <Running label={t('inv.diagRunning')} />}
      {state.error && <AlertBanner tone="danger">{state.error}</AlertBanner>}

      {state.items && !detail && (
        state.items.length ? (
          <DataTable testId="diag-history">
            <TableHeader><TableRow>
              <TableHead className={TH}>{t('inv.histColType')}</TableHead>
              <TableHead className={TH}>{t('inv.histColWhen')}</TableHead>
              <TableHead className={cn(TH, 'hidden md:table-cell')}>{t('inv.histColWho')}</TableHead>
              <TableHead className={cn(TH, 'hidden lg:table-cell')}>{t('inv.histColSource')}</TableHead>
              <TableHead className={TH}>{t('inv.histColResult')}</TableHead>
              <TableHead className={TH}><span className="sr-only">{t('inv.histView')}</span></TableHead>
            </TableRow></TableHeader>
            <TableBody>
              {state.items.map((h) => (
                <TableRow key={h.id} data-run-type={h.run_type}>
                  <TableCell className={cn(TD, 'font-medium')}>{typeLabel(h.run_type)}</TableCell>
                  <TableCell className={cn(TD, 'whitespace-nowrap')}>
                    {formatDate(h.executed_at)}
                    <div className="text-xs text-muted-foreground md:hidden">{h.executed_by}</div>
                  </TableCell>
                  <TableCell className={cn(TD, 'hidden md:table-cell')}>{h.executed_by}</TableCell>
                  <TableCell className={cn(TD, 'hidden font-mono text-xs lg:table-cell')}>{h.source_ip || '—'}</TableCell>
                  <TableCell className={TD}>
                    <ToneBadge tone={h.success ? 'success' : 'danger'}>{h.success ? t('inv.diagOk') : t('inv.diagError')}</ToneBadge>
                    {h.summary && <span className="ml-2 text-xs text-muted-foreground">{h.summary}</span>}
                  </TableCell>
                  <TableCell className={cn(TD, 'text-right')}>
                    <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => openDetail(h)}
                      aria-label={t('a11y.rowAction', t('inv.histView'), `${typeLabel(h.run_type)} · ${formatDate(h.executed_at)}`)}>
                      {t('inv.histView')}
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </DataTable>
        ) : (
          <StatusBlock tone="neutral" icon={History} title={t('inv.histEmpty')} className="py-6" />
        )
      )}

      {detail && (
        <div data-slot="diag-history-detail" className="flex min-w-0 flex-col gap-3">
          <div className="flex flex-wrap items-center gap-2">
            <Button type="button" variant="secondary" size="sm" onClick={() => { setDetail(null); setCompare(null) }}>
              <ArrowLeft aria-hidden="true" /> {t('diag.back')}
            </Button>
            <ToneBadge tone="info">{typeLabel(detail.row.run_type)}</ToneBadge>
            {detail.row.run_type === 'CONNECTION' && (
              <Button type="button" variant="outline" size="sm" onClick={runCompare} disabled={!previousRow || compare?.loading}
                aria-busy={compare?.loading || undefined} title={!previousRow ? t('diag.compareNone') : undefined}>
                {compare?.loading ? <Spinner size={14} inline decorative /> : <GitCompare aria-hidden="true" />} {t('diag.compare')}
              </Button>
            )}
          </div>
          <div className={KV_GRID}>
            <ShowField label={t('inv.histColWho')} value={detail.row.executed_by} />
            <ShowField label={t('inv.histColWhen')} value={formatDate(detail.row.executed_at)} />
            <ShowField label={t('inv.histColSource')} mono value={detail.row.source_ip || '—'} />
            <ShowField label={t('inv.histColResult')} value={detail.row.summary || (detail.row.success ? t('inv.diagOk') : t('inv.diagError'))} />
          </div>

          {compare && !compare.loading && (
            <div data-slot="diag-compare" className="flex min-w-0 flex-col gap-2">
              <Section>{t('diag.compareTitle', formatDate(compare.prev.executed_at))}</Section>
              {compare.rows.length
                ? <DiffTable rows={compare.rows} fieldLabel={t('diag.compareField')} fromLabel={t('diag.comparePrev')} toLabel={t('diag.compareNow')} />
                : <AlertBanner tone="success" className="mb-0">{t('diag.compareSame')}</AlertBanner>}
            </div>
          )}

          <HistoryBody row={detail.row} result={detail.result} />
        </div>
      )}
    </div>
  )
}

/** Kayıt türüne göre gövde — canlı koşuyla aynı çizimler. */
function HistoryBody({ row, result }) {
  const t = useT()
  if (result == null) return <AlertBanner tone="warning" className="mb-0">{t('diag.histNoResult')}</AlertBanner>
  switch (row.run_type) {
    case 'OPENSSL': return <OpensslResult data={result} />
    case 'NETWORK': return <NetworkResult data={result} />
    case 'HSTS': return <HstsResult data={result} />
    case 'DOMAIN_EXPIRY': return <DomainExpiryTrace data={result} />
    case 'PROXY_CA_CHAIN': return <pre className={PRE}>{JSON.stringify(result, null, 2)}</pre>
    default: return <ConnectionHistory result={result} row={row} />
  }
}

function ConnectionHistory({ result, row }) {
  const t = useT()
  const steps = useMemo(() => buildSteps(result), [result])
  const verdict = useMemo(() => buildVerdict(result, steps, t), [result, steps, t])
  return (
    <>
      <DiagVerdict verdict={verdict} />
      <Section>{t('inv.diagMatrix')}</Section>
      <CombosTable combos={result?.combos} detailed item={{ domain: row.domain, port: row.port }} proxyAddress={result?.proxy_address} />
      {result?.tls_client && (
        <>
          <Section>{t('inv.diagTlsClient')}</Section>
          <TlsClientInfo tc={result.tls_client} />
        </>
      )}
    </>
  )
}
