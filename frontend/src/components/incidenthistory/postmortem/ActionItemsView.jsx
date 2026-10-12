import { useCallback, useEffect, useRef, useState } from 'react'
import { AlertOctagon, CalendarX2, ListChecks, RotateCcw, Search, UserRound } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { useUrlQuerySync, readUrlParam } from '../../../hooks/useUrlQuerySync.js'
import StatusBlock from '../../ui/StatusBlock.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { LoadingBlock, Spinner } from '../../ui/Progress.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { cn } from '@/lib/utils'
import { ActionRow } from './ActionItemParts.jsx'

const PAGE = 50
const STATUS_VALUES = ['open', 'done', 'all']
const TOUCH = 'pointer-coarse:h-10'

/**
 * Olaylar arası "Aksiyonlar" görünümü (2026-10-11) — Olay & Hata Geçmişi'nde `ih_view=actions`. Görüş kapsamındaki
 * olayların aksiyonları + size atananlar (başka takımın olayından gelse de). Süzgeçler: durum (açık / biten / tümü),
 * yalnız bana atananlar, yalnız gecikmişler, arama (aksiyon / olay / sahip); URL `ih_astatus`, `ih_amine`, `ih_aover`,
 * `ih_aq`. Sayaç kutucukları tıklanınca süzer. Size atanan aksiyonun durumunu burada değiştirebilirsiniz; diğer
 * düzenlemeler olayın postmortem penceresinde. Sıra: son tarihi yakın olan üstte, tarihsizler sonda.
 */
export default function ActionItemsView({ onOpenIncident }) {
  const t = useT()
  const toast = useToast()
  const [status, setStatus] = useState(() => (STATUS_VALUES.includes(readUrlParam('ih_astatus')) ? readUrlParam('ih_astatus') : 'open'))
  const [mine, setMine] = useState(() => readUrlParam('ih_amine') === '1')
  const [overdue, setOverdue] = useState(() => readUrlParam('ih_aover') === '1')
  const [q, setQ] = useState(() => readUrlParam('ih_aq', '') || '')
  const [qTerm, setQTerm] = useState(() => (readUrlParam('ih_aq', '') || '').trim())
  const [data, setData] = useState(null)
  const [items, setItems] = useState([])
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)
  const [busyId, setBusyId] = useState(null)
  const seq = useRef(0)

  useUrlQuerySync({ ih_astatus: status === 'open' ? null : status, ih_amine: mine ? '1' : null, ih_aover: overdue ? '1' : null, ih_aq: qTerm || null })
  useEffect(() => {
    const id = setTimeout(() => setQTerm(q.trim()), 300)
    return () => clearTimeout(id)
  }, [q])

  const load = useCallback(async (page = 0) => {
    const my = ++seq.current
    setLoading(true)
    let res = null
    try { res = await api.incidents.actions({ status, mine, overdue, q: qTerm, page, size: PAGE }) } catch (e) { res = { success: false, error: e?.message } } finally {
      if (my === seq.current) setLoading(false)
    }
    if (my !== seq.current) return
    if (res?.success && res.data) {
      setError(null)
      setData(res.data)
      setItems((prev) => (page === 0 ? res.data.items || [] : [...prev, ...(res.data.items || [])]))
    } else setError(res?.error || t('pm.listError'))
  }, [status, mine, overdue, qTerm, t])

  useEffect(() => { load(0) }, [load])

  async function changeStatus(a, next) {
    if (next === a.status) return
    setBusyId(a.id)
    let res = null
    try { res = await api.incidents.updateAction(a.id, { status: next }) } catch (e) { res = { success: false, error: e?.message } } finally { setBusyId(null) }
    if (res?.success && res.data) {
      setItems((list) => list.map((x) => (x.id === a.id ? { ...x, ...res.data } : x)))
      toast.success(t('pm.statusSaved', t('pm.astatus.' + next)))
    } else toast.error(res?.error || t('pm.actionError'))
  }

  const totals = data?.totals || {}
  const total = data?.total ?? 0
  const today = new Date().toLocaleDateString('sv-SE', { timeZone: 'Europe/Istanbul' })
  const hasFilter = status !== 'open' || mine || overdue || !!qTerm
  const kpi = (key, value, Icon, active, onClick, tone) => (
    <Button type="button" variant="outline" onClick={onClick} aria-pressed={active} data-slot="pm-kpi" data-kind={key}
      className={cn('h-auto min-w-0 flex-1 justify-start gap-3 rounded-xl bg-card px-4 py-3 text-left font-normal whitespace-normal',
        active && 'border-primary ring-1 ring-primary')}>
      <span className={cn('grid size-9 shrink-0 place-items-center rounded-lg', tone)} aria-hidden="true"><Icon className="size-4" /></span>
      <span className="flex min-w-0 flex-col">
        <span className="text-xl font-semibold tabular-nums leading-tight">{value ?? 0}</span>
        <span className="text-xs text-muted-foreground">{t('pm.kpi.' + key)}</span>
      </span>
    </Button>
  )

  return (
    <div data-slot="pm-actions-view" className="flex min-w-0 flex-col gap-4">
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
        {kpi('open', totals.open, ListChecks, status === 'open' && !mine && !overdue, () => { setStatus('open'); setMine(false); setOverdue(false) }, 'bg-primary/10 text-primary')}
        {kpi('overdue', totals.overdue, CalendarX2, overdue, () => { setStatus('open'); setOverdue((v) => !v) }, 'bg-destructive/10 text-destructive')}
        {kpi('mine', totals.mine, UserRound, mine, () => { setStatus('open'); setMine((v) => !v) }, 'bg-amber-500/15 text-amber-700 dark:text-amber-300')}
      </div>

      <div className="flex flex-wrap items-center gap-2" data-slot="pm-actions-toolbar">
        <SegmentedControl value={status} onChange={setStatus} ariaLabel={t('pm.filterStatus')}
          options={STATUS_VALUES.map((v) => ({ value: v, label: t('pm.filter.' + v) }))} />
        <Button type="button" variant={mine ? 'default' : 'outline'} size="sm" aria-pressed={mine} onClick={() => setMine((v) => !v)} className={TOUCH} data-slot="pm-filter-mine">
          <UserRound aria-hidden="true" />{t('pm.filter.mine')}
        </Button>
        <Button type="button" variant={overdue ? 'default' : 'outline'} size="sm" aria-pressed={overdue} onClick={() => setOverdue((v) => !v)} className={TOUCH} data-slot="pm-filter-overdue">
          <CalendarX2 aria-hidden="true" />{t('pm.filter.overdue')}
        </Button>
        <InputGroup className="min-w-[14rem] flex-1 pointer-coarse:h-11 sm:max-w-sm">
          <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
          <InputGroupInput type="search" className="pointer-coarse:h-10" value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('pm.searchPh')} aria-label={t('pm.searchPh')}
            onKeyDown={(e) => { if (e.key === 'Enter') setQTerm(q.trim()) }} data-page-search />
        </InputGroup>
        {loading && data && <Spinner size={14} label={t('pm.loading')} />}
        <span className="ml-auto text-sm text-muted-foreground" aria-live="polite" data-slot="pm-actions-count">{data ? t('pm.resultCount', total) : ''}</span>
      </div>

      {error && data && (
        <AlertBanner tone="danger" role="alert" title={t('pm.listError')} className="mb-0"
          actions={<Button type="button" variant="outline" size="sm" onClick={() => load(0)}><RotateCcw aria-hidden="true" />{t('pm.retry')}</Button>}>{error}</AlertBanner>
      )}

      {!data && !error && <LoadingBlock label={t('pm.loading')} />}
      {!data && error && (
        <StatusBlock tone="danger" icon={AlertOctagon} title={t('pm.listError')} description={error} className="rounded-xl border py-12"
          actions={<Button type="button" variant="outline" onClick={() => load(0)}><RotateCcw aria-hidden="true" />{t('pm.retry')}</Button>} />
      )}
      {data && items.length === 0 && (
        <StatusBlock icon={ListChecks} title={hasFilter ? t('pm.listEmptyFiltered') : t('pm.listEmpty')}
          description={hasFilter ? undefined : t('pm.listEmptyHint')} className="rounded-xl border border-dashed py-12" />
      )}
      {items.length > 0 && (
        <ul className={cn('m-0 flex list-none flex-col gap-2 p-0 transition-opacity', loading && 'opacity-60')} data-slot="pm-actions-list">
          {items.map((a) => (
            <ActionRow key={a.id} action={a} today={today} canWrite={false} busy={busyId === a.id} onStatus={changeStatus}
              incident={{ title: a.incident_title, team: a.incident_team_name }}
              onOpenIncident={onOpenIncident && a.can_open !== false ? () => onOpenIncident(a.incident_id) : undefined} />
          ))}
        </ul>
      )}
      {data && items.length < total && (
        <Button type="button" variant="outline" onClick={() => load(Math.floor(items.length / PAGE))} disabled={loading} className={cn('self-center', TOUCH)}
          data-slot="pm-actions-more">
          {loading && <Spinner size={14} inline decorative />}{t('pm.loadMore', total - items.length)}
        </Button>
      )}
    </div>
  )
}
