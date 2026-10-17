import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowDown, ArrowUp, CheckCircle2, CircleAlert, CirclePause, FileClock, FileText, FileWarning, LayoutTemplate, MailWarning, Plus,
  Radio, RefreshCw, Search, X,
} from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import PageHeader from '../ui/PageHeader.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import MonitorStatsBar from '../MonitorStatsBar.jsx'
import { usePagination } from '../../hooks/usePagination.js'
import { readUrlParam, useUrlQuerySync } from '../../hooks/useUrlQuerySync.js'
import { useElementWidth } from '../../hooks/useElementWidth.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent, CardFooter, CardHeader } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { cn } from '@/lib/utils'
import { errorInfoOf } from '../../utils/errorMessages.js'
import ChannelDialog from './ChannelDialog.jsx'
import { StatusBadge, periodLabel } from './TechErrorParts.jsx'
import {
  CHANNEL_SORTS, CHANNEL_TABLE_MIN_PX, TE_PAGE, TE_PS, TE_Q, TE_SORT, TE_STATUS, applyReorderPlan, channelCounts, filterChannels,
  fullOrderPlan, inverseReorderPlan, moveInTeam, normalizeChannelSort, normalizeChannelStatus, reorderPlan, sortChannels,
} from './channelListModel.js'

const ALL = '__all__'

/**
 * Toplu sıra ucu YOK mu (eski pod, kademeli dağıtım): 405 ya da kodu `TEAM_NOT_FOUND` olmayan 404 (SpaNotFoundAdvice
 * `NOT_FOUND`). Yeni sunucunun "takım görülemiyor" 404'ü `TEAM_NOT_FOUND` taşır — o bir hatadır, eski yola düşülmez.
 */
function reorderEndpointMissing(res) {
  const info = errorInfoOf(res)
  return info?.status === 405 || (info?.status === 404 && info?.code !== 'TEAM_NOT_FOUND')
}

/** Satır taşınabilir mi: kanalı kendi kapsamında düzenleyebilen (paylaşımla gören değil). */
const movable = (ch) => !!ch?.can_edit && !ch?.shared_access

/**
 * Kanallar görünümü (yeniden tasarım 2026-10-17, 8. sürüm — kullanıcı isteği: "sayfalama, shadcn ile profesyonel UI,
 * mweb"): tıklanabilir dönem özeti kartları (süzgeç), araç çubuğu (arama — Türkçe katlama, takım, sıralama, temizle),
 * kap genişliğine göre TABLO (≥ 960 px) ya da KART, sayfalama (`usePagination`, URL `te_page` / `te_ps`). Süzgeçler
 * URL'de (`te_q` / `te_st` / `te_sort`): bağlantı paylaşılır, kanaldan dönünce liste aynı kalır. Kart / satır eylemleri
 * satırın sunucu bayraklarından (`can_edit` / `can_delete`) çizilir; yazamayan kullanıcı yalnız açar. Veri ve uçlar
 * değişmedi (`GET overview`).
 *
 * Kanal sırası (R14, 2026-10-17): sıralama "Sıra" iken satır / kart menüsü "Yukarı taşı" / "Aşağı taşı" alır — yalnız
 * kanalı düzenleyebilen (`can_edit`, kendi kapsamı; paylaşımla görenlerde yok). Taşıma AYNI takımın komşusuyla yer
 * değiştirir (`moveInTeam`); takımın tüm dizilişi TEK istekle gider (`reorderChannels` → sunucu 1…n yazar, tek denetim
 * satırı); eski pod (uç yok) → kanal başına PUT (`reorderPlan`). Liste iyimser güncellenir, sürmekte olan yüklemeler düşer
 * (sıra numarası), meşgul durumu yeniden yükleme bitene dek sürer; düşerse eski sıra geri gelir + bildirim (kısmen
 * kaydedildiyse söylenir), sunucu gerçeği yeniden okunur. Süzgeç / arama açıkken taşıma kapalı (komşu gizli bir kanal
 * olabilirdi — hiçbir şey görünür biçimde değişmezdi); takım süzgeci sunucuda süzdüğü için takımın tüm kanalları
 * listededir, taşımayı kapatmaz. Taşıma sonucu `aria-live` satırında söylenir; kanal başka sayfaya geçtiyse sayfa izler.
 */
export default function ChannelsView({ team, onTeamChange, onOpenChannel, onOpenReport }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [state, setState] = useState({ loading: true, error: null, status: null, data: null })
  const [dialog, setDialog] = useState(null)   // { mode: 'create' | 'edit', channel? }
  // Meşgul durumu SAHİPLİ (inceleme 2026-10-17): bir eylem (rapor açma, pasife alma, silme, taşıma) sürerken diğeri başlamaz;
  // bitiren yalnız KENDİ anahtarını temizler — taşımanın meşgul durumunu başka bir eylemin `finally`si silemez.
  const busyRef = useRef(null)
  const [busy, setBusyState] = useState(null)
  const claimBusy = useCallback((key) => {
    if (busyRef.current) return false
    busyRef.current = key
    setBusyState(key)
    return true
  }, [])
  const releaseBusy = useCallback((key) => {
    if (busyRef.current !== key) return
    busyRef.current = null
    setBusyState(null)
  }, [])
  const [orderStatus, setOrderStatus] = useState('')
  const followRef = useRef(null)   // taşınan kanal: yeni sayfasına geçilir
  const [q, setQ] = useState(() => readUrlParam(TE_Q, ''))
  const [status, setStatus] = useState(() => normalizeChannelStatus(readUrlParam(TE_STATUS, 'all')))
  const [sort, setSort] = useState(() => normalizeChannelSort(readUrlParam(TE_SORT, 'name')))
  const [listRef, listWidth] = useElementWidth()
  const seq = useRef(0)

  useUrlQuerySync({
    [TE_Q]: q.trim() ? q : null,
    [TE_STATUS]: status === 'all' ? null : status,
    [TE_SORT]: sort === 'name' ? null : sort,
  })

  const load = useCallback(async () => {
    const my = ++seq.current
    setState((s) => ({ ...s, loading: true }))
    try {
      const res = await api.techErrors.overview(team)
      if (my !== seq.current) return
      if (res?.success && res.data) setState({ loading: false, error: null, status: null, data: res.data })
      else setState((s) => ({ ...s, loading: false, error: res?.error || t('te.loadError'), status: res?.status ?? null }))
    } catch (e) {
      if (my !== seq.current) return
      setState((s) => ({ ...s, loading: false, error: e?.message || t('te.loadError'), status: 0 }))
    }
  }, [team, t])
  useEffect(() => { load() }, [load])

  const data = state.data
  // Paylaşılan bağlantıdaki takım bu kullanıcıya görünmüyorsa (ya da silindiyse) süzgeç kalkar: süzgeç kutusu tek
  // takımda çizilmediği için kullanıcı boş sayfada mahsur kalıyordu (inceleme 2026-10-13)
  useEffect(() => {
    if (!team || !data?.teams) return
    if (!data.teams.some((tm) => String(tm.id) === String(team))) onTeamChange(null)
  }, [team, data, onTeamChange])
  const channels = useMemo(() => data?.channels || [], [data])
  const period = data?.period
  const teams = useMemo(() => data?.teams || [], [data])
  const teamOptions = useMemo(() => [{ value: ALL, label: t('te.allTeams') },
    ...teams.map((tm) => ({ value: String(tm.id), label: tm.name || `#${tm.id}` }))], [teams, t])
  const noEmail = teams.filter((tm) => tm.can_edit && !tm.has_email && channels.some((c) => c.team_id === tm.id))
  const counts = useMemo(() => channelCounts(channels), [channels])
  const visible = useMemo(() => sortChannels(filterChannels(channels, { q, status }), sort), [channels, q, status, sort])
  // Sıra kipinde komşuluk TÜM listede (süzgeçsiz) hesaplanır; süzgeç / arama açıkken taşıma zaten kapalıdır (orderLocked)
  const orderedAll = useMemo(() => (sort === 'order' ? sortChannels(channels, 'order') : null), [channels, sort])
  const pager = usePagination(visible, {
    listKey: 'te-channels', preset: 'page', resetDeps: [q, status, sort, team], url: { pageKey: TE_PAGE, sizeKey: TE_PS },
  })
  const cardsLayout = listWidth > 0 && listWidth < CHANNEL_TABLE_MIN_PX
  const filtersActive = Boolean(q.trim()) || status !== 'all'
  // Süzgeç / arama açıkken sıra değiştirilmez: komşu, süzgeçle GİZLENMİŞ bir kanal olabilir — sıra değişir ama listede
  // görünür hiçbir şey değişmezdi (inceleme 2026-10-17). Takım süzgeci sunucuda süzer: takımın tüm kanalları listede.
  const orderLocked = filtersActive
  const anyMovable = useMemo(() => visible.some(movable), [visible])
  const moving = typeof busy === 'string' && busy.startsWith('m')
  const pLabel = periodLabel(period, t)

  // Taşınan kanal başka sayfaya geçtiyse sayfa onu izler (ve menü düğmesi odağı alır — eski sayfadaki tetik söküldü)
  useEffect(() => {
    const id = followRef.current
    if (id == null) return
    followRef.current = null
    const idx = visible.findIndex((c) => c.id === id)
    if (idx < 0) return
    const page = Math.floor(idx / pager.pageSize) + 1
    if (page === pager.page) return
    pager.setPage(page)
    setTimeout(() => {
      const a = document.activeElement
      if (a && a !== document.body) return
      document.querySelector(`[data-channel="${id}"] [aria-haspopup="menu"]`)?.focus()
    }, 0)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible])

  function clearFilters() {
    setQ('')
    setStatus('all')
  }

  async function startReport(ch) {
    if (!period) return
    if (ch.period_report?.id) { onOpenReport(ch.period_report.id, ch.id); return }
    const key = `r${ch.id}`
    if (!claimBusy(key)) return
    try {
      const res = await api.techErrors.createReport(ch.id, period.year, period.month)
      if (res?.success && res.data?.id) onOpenReport(res.data.id, ch.id)
      else if (res?.code === 'REPORT_EXISTS' && res?.report_id) onOpenReport(res.report_id, ch.id)
      else toast.error(res?.error || t('te.createReportError'), { details: res?.errorInfo })
    } catch (e) {
      toast.error(e?.message || t('te.createReportError'))
    } finally {
      releaseBusy(key)
    }
  }

  async function toggleActive(ch) {
    const key = `a${ch.id}`
    if (!claimBusy(key)) return
    try {
      const res = await api.techErrors.updateChannel(ch.id, { active: !ch.active })
      if (res?.success) {
        toast.success(ch.active ? t('te.channelPaused') : t('te.channelResumed'))
        load()
      } else toast.error(res?.error || t('te.saveError'), { details: res?.errorInfo })
    } catch (e) {
      toast.error(e?.message || t('te.saveError'))
    } finally {
      releaseBusy(key)
    }
  }

  async function remove(ch) {
    if (busyRef.current) return
    const ok = await showConfirm({
      title: t('te.deleteChannelTitle'),
      message: t('te.deleteChannelBody', ch.name, ch.report_count || 0),
      variant: 'danger',
      confirmText: t('te.deleteChannelConfirm'),
      cancelText: t('te.cancel'),
    })
    if (!ok) return
    const key = `d${ch.id}`
    if (!claimBusy(key)) return      // onay penceresi açıkken başka bir eylem başladıysa silme bekler değil, vazgeçer
    try {
      const res = await api.techErrors.deleteChannel(ch.id)
      if (res?.success) {
        toast.success(t('te.channelDeleted', ch.name))
        load()
      } else toast.error(res?.error || t('te.deleteError'), { details: res?.errorInfo })
    } catch (e) {
      toast.error(e?.message || t('te.deleteError'))
    } finally {
      releaseBusy(key)
    }
  }

  /** Takımın taşınmış dizilişi (null = taşınamaz: takımın ilk / son kanalı ya da süzgeç açık). */
  function nextTeamFor(ch, dir) {
    if (!orderedAll || orderLocked) return null
    return moveInTeam(orderedAll, ch.team_id, orderedAll.indexOf(ch), dir)
  }

  async function move(ch, dir) {
    const nextTeam = nextTeamFor(ch, dir)
    if (!nextTeam) return
    const key = `m${ch.id}`
    if (!claimBusy(key)) return
    // Sürmekte olan yüklemeler (önceki taşıma ya da Yenile) düşer: geç dönen eski liste iyimser sırayı geri almasın
    seq.current++
    const before = channels
    const ids = nextTeam.map((c) => c.id)
    const full = fullOrderPlan(nextTeam)
    const fallback = reorderPlan(orderedAll, ch.team_id, orderedAll.indexOf(ch), dir)
    followRef.current = ch.id
    setOrderStatus('')
    setState((s) => (s.data ? { ...s, loading: false, data: { ...s.data, channels: applyReorderPlan(s.data.channels, full) } } : s))
    let done = 0          // eski yolda kaydedilen PUT sayısı (toplu uç atomiktir — kısmen kayıt yalnız eski yolda olur)
    try {
      const res = await api.techErrors.reorderChannels(ch.team_id, ids)
      if (!res?.success && reorderEndpointMissing(res)) {
        // Eski pod (kademeli dağıtım): kanal başına PUT; biri düşerse kalanlar gitmez
        for (const p of fallback) {
          const r = await api.techErrors.updateChannel(p.id, { sort_order: p.sort_order })
          if (!r?.success) {
            const err = new Error(r?.error || t('te.saveError'))
            err.errorInfo = r?.errorInfo
            throw err
          }
          done++
        }
      } else if (!res?.success) {
        const err = new Error(res?.error || t('te.saveError'))
        err.errorInfo = res?.errorInfo
        throw err
      }
      await load()
      setOrderStatus(t('te.order.moved', ch.name, ids.indexOf(ch.id) + 1))
    } catch (e) {
      // Hiçbir şey kaydedilmediyse iyimser sıra geri alınır; kısmen kaydedildiyse sunucunun gerçeği gösterilir (yeniden okuma)
      const partial = done > 0
      if (!partial) {
        followRef.current = ch.id          // kanal eski yerine dönünce sayfa da onu izler
        const undo = inverseReorderPlan(before, full)
        setState((s) => (s.data ? { ...s, data: { ...s.data, channels: applyReorderPlan(s.data.channels, undo) } } : s))
      }
      await load()
      const msg = e?.message || t('te.saveError')
      toast.error(partial ? `${msg} ${t('te.order.partial')}` : msg, { details: e?.errorInfo })
    } finally {
      releaseBusy(key)
    }
  }

  const rowActions = (ch) => ({
    onOpen: () => onOpenChannel(ch.id),
    onTemplate: () => onOpenChannel(ch.id, 'template'),
    onReport: () => startReport(ch),
    onEdit: () => setDialog({ mode: 'edit', channel: ch }),
    onToggle: () => toggleActive(ch),
    onDelete: () => remove(ch),
    anyBusy: !!busy,
    orderMode: sort === 'order',
    // Bir eylem sürerken (taşıma, pasife alma, silme) ya da süzgeç açıkken taşıma öğeleri devre dışı
    canMoveUp: !busy && nextTeamFor(ch, -1) != null,
    canMoveDown: !busy && nextTeamFor(ch, 1) != null,
    onMoveUp: () => move(ch, -1),
    onMoveDown: () => move(ch, 1),
  })

  const header = (
    <PageHeader icon={FileWarning} title={t('te.title')} description={t('te.subtitle')}
      actions={(
        <>
          <Button type="button" variant="outline" onClick={load} disabled={state.loading || moving} data-slot="te-refresh" className="pointer-coarse:min-h-10">
            <RefreshCw aria-hidden="true" className={cn(state.loading && 'animate-spin motion-reduce:animate-none')} />
            {t('te.refresh')}
          </Button>
          {data?.can_create && (
            <Button type="button" onClick={() => setDialog({ mode: 'create' })} data-slot="te-new-channel" className="pointer-coarse:min-h-10">
              <Plus aria-hidden="true" /> {t('te.newChannel')}
            </Button>
          )}
        </>
      )} />
  )

  if (state.loading && !data) {
    return (
      <div className="flex flex-col gap-4">
        {header}
        <div role="status" aria-live="polite" className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
          <span className="sr-only">{t('te.loading')}</span>
          {Array.from({ length: 3 }, (_, i) => <Skeleton key={i} className="h-44 w-full rounded-xl motion-reduce:animate-none" />)}
        </div>
      </div>
    )
  }
  if (state.error && !data) {
    return (
      <div className="flex flex-col gap-4">
        {header}
        <StatusBlock tone={state.status === 403 ? 'neutral' : 'danger'} icon={CircleAlert}
          title={state.status === 403 ? t('te.forbiddenTitle') : t('te.loadErrorTitle')}
          description={state.status === 403 ? t('te.forbiddenBody') : state.error}
          actions={state.status === 403 ? null : <Button type="button" variant="outline" onClick={load}>{t('te.retry')}</Button>} />
      </div>
    )
  }

  const kpiItems = [
    { key: 'all', Icon: Radio, label: t('te.kpi.channels'), value: counts.channels, cls: 'total', hint: t('te.kpi.hint.channels') },
    { key: 'sent', Icon: CheckCircle2, label: t('te.kpi.sent', pLabel), value: counts.sent, cls: 'valid', hint: t('te.kpi.hint.sent') },
    { key: 'open', Icon: FileClock, label: t('te.kpi.open'), value: counts.open, cls: 'warning', hint: t('te.kpi.hint.open') },
    { key: 'missing', Icon: FileWarning, label: t('te.kpi.missing'), value: counts.missing, cls: 'alert', hint: t('te.kpi.hint.missing') },
    { key: 'inactive', Icon: CirclePause, label: t('te.kpi.inactive'), value: counts.inactive, cls: 'paused', hint: t('te.kpi.hint.inactive') },
  ]

  return (
    <div className="flex flex-col gap-4" data-slot="te-channels">
      {header}
      {/* Taşıma sonucu (ekran okuyucu): hep bağlı canlı bölge — içerik değişince okunur */}
      <p role="status" aria-live="polite" className="sr-only" data-slot="te-order-status">{orderStatus}</p>

      {state.error && (
        <AlertBanner tone="danger" title={t('te.refreshFailed')}>{state.error}</AlertBanner>
      )}

      {noEmail.length > 0 && (
        <AlertBanner tone="warning" icon={MailWarning} title={t('te.noEmailTitle')}>
          {t('te.noEmailBody', noEmail.map((tm) => tm.name).join(', '))}
        </AlertBanner>
      )}

      {channels.length > 0 && (
        <div data-slot="te-kpis">
          <MonitorStatsBar items={kpiItems} activeFilter={status} className="mb-0"
            onStatClick={(key) => setStatus(key === status ? 'all' : key)} />
        </div>
      )}

      {channels.length > 0 && (
        <Card data-slot="te-toolbar" className="py-3">
          <CardContent className="flex flex-col gap-3 px-3 sm:px-4">
            <div className="flex flex-wrap items-end gap-3">
              <InputGroup className="w-full sm:w-72 pointer-coarse:h-10">
                <InputGroupInput type="search" value={q} onChange={(e) => setQ(e.target.value)} data-page-search=""
                  placeholder={t('te.search.channels')} aria-label={t('te.search.channels')} data-slot="te-search"
                  className="pointer-coarse:h-10" />
                <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
              </InputGroup>
              {teams.length > 1 && (
                <div className="flex min-w-0 flex-col gap-1.5 sm:w-72">
                  <span id="te-team-filter" className="text-xs font-medium text-muted-foreground">{t('te.teamFilter')}</span>
                  <SearchableSelect ariaLabelledBy="te-team-filter" value={team || ALL} options={teamOptions} wrapLabels
                    onChange={(v) => onTeamChange(v === ALL ? null : String(v))} />
                </div>
              )}
              <div className="flex min-w-0 flex-col gap-1.5">
                <label htmlFor="te-sort" className="text-xs font-medium text-muted-foreground">{t('te.sort.label')}</label>
                <NativeSelect id="te-sort" value={sort} onChange={(e) => setSort(normalizeChannelSort(e.target.value))}
                  className="pointer-coarse:h-10" data-slot="te-sort" aria-describedby={sort === 'order' && anyMovable ? 'te-sort-hint' : undefined}>
                  {CHANNEL_SORTS.map((s) => <NativeSelectOption key={s} value={s}>{t(`te.sort.${s}`)}</NativeSelectOption>)}
                </NativeSelect>
                {/* İpucu yalnız görünür listede taşınabilir satır varken; süzgeç açıksa neden taşınamadığını söyler */}
                {sort === 'order' && anyMovable && (
                  <p id="te-sort-hint" data-slot="te-sort-hint" data-locked={orderLocked ? 'true' : 'false'}
                    className="m-0 max-w-72 text-xs text-muted-foreground [overflow-wrap:anywhere]">
                    {orderLocked ? t('te.sort.orderLocked') : t('te.sort.orderHint')}
                  </p>
                )}
              </div>
              {filtersActive && (
                <Button type="button" variant="ghost" onClick={clearFilters} className="pointer-coarse:min-h-10" data-slot="te-clear-filters">
                  <X aria-hidden="true" /> {t('te.filters.clear')}
                </Button>
              )}
            </div>
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted-foreground">
              <span data-slot="te-count">{t('te.filters.count', visible.length, counts.total)}</span>
              {period && <span data-slot="te-period">{t('te.periodNote', pLabel)}</span>}
            </div>
          </CardContent>
        </Card>
      )}

      {channels.length === 0 ? (
        <>
          {period && <p className="text-sm text-muted-foreground" data-slot="te-period">{t('te.periodNote', pLabel)}</p>}
          <StatusBlock tone="neutral" icon={Radio} title={t('te.emptyTitle')}
            description={data?.can_create ? t('te.emptyBodyCreate') : t('te.emptyBody')}
            actions={data?.can_create ? (
              <Button type="button" onClick={() => setDialog({ mode: 'create' })} className="pointer-coarse:min-h-10"><Plus aria-hidden="true" /> {t('te.newChannel')}</Button>
            ) : null} />
        </>
      ) : (
        <div ref={listRef} className="flex flex-col gap-3" data-slot="te-channel-list" data-layout={cardsLayout ? 'cards' : 'table'}>
          {visible.length === 0 ? (
            <StatusBlock tone="neutral" icon={Search} title={t('te.noMatchTitle')} description={t('te.noMatchBody')}
              actions={<Button type="button" variant="outline" onClick={clearFilters}><X aria-hidden="true" /> {t('te.filters.clear')}</Button>} />
          ) : cardsLayout ? (
            <div className="grid gap-3 sm:grid-cols-2">
              {pager.pageItems.map((ch) => (
                <ChannelCard key={ch.id} ch={ch} period={period} busy={busy} {...rowActions(ch)} />
              ))}
            </div>
          ) : (
            <div className="overflow-hidden rounded-xl border" data-slot="te-channel-table">
              <Table className="table-fixed">
                <TableHeader>
                  <TableRow>
                    <TableHead>{t('te.col.channel')}</TableHead>
                    <TableHead className="w-56">{t('te.col.periodReport', pLabel)}</TableHead>
                    <TableHead className="w-28 text-right">{t('te.col.reports')}</TableHead>
                    <TableHead className="w-72 text-right">{t('te.col.actions')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {pager.pageItems.map((ch) => (
                    <ChannelRow key={ch.id} ch={ch} period={period} busy={busy} {...rowActions(ch)} />
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
          {(pager.totalPages > 1 || visible.length > pager.pageSize) && <PaginationBar {...pager} />}
        </div>
      )}

      {dialog && (
        <ChannelDialog mode={dialog.mode} channel={dialog.channel} teams={teams} channels={data?.channels || []}
          defaultTeam={team}
          onClose={() => setDialog(null)}
          onSaved={(saved) => {
            setDialog(null)
            if (dialog.mode === 'create' && saved?.id) onOpenChannel(saved.id, 'template')
            else load()
          }} />
      )}
    </div>
  )
}

/**
 * Menü öğeleri (tablo satırı = etiketli "İşlemler" menüsü; kart = ikonlu menü): yalnız yazabilenin eylemleri. Sıra kipinde
 * (`orderMode`) düzenleyebilen — paylaşımla gören DEĞİL — "Yukarı / Aşağı taşı" alır; takımın ucunda devre dışı (görünür,
 * seçilemez); erişilebilir ad kanal adını taşır ("Yukarı taşı: <kanal>").
 */
function menuItems(t, ch, { onOpen, onEdit, onToggle, onDelete, anyBusy, orderMode, canMoveUp, canMoveDown, onMoveUp, onMoveDown }, withOpen) {
  const items = []
  if (withOpen) items.push({ label: t('te.allReports'), onClick: onOpen })
  if (ch.can_edit) {
    items.push({ label: t('te.editChannel'), onClick: onEdit })
    // Bir eylem sürerken (taşıma, rapor açma, silme) pasife alma / silme seçilemez — eylemler üst üste binmez
    items.push({ label: ch.active ? t('te.pauseChannel') : t('te.resumeChannel'), onClick: onToggle, disabled: !!anyBusy })
  }
  if (orderMode && movable(ch)) {
    items.push({ label: t('te.order.moveUp'), ariaLabel: t('te.order.moveUpOf', ch.name), icon: <ArrowUp aria-hidden="true" />,
      onClick: onMoveUp, disabled: !canMoveUp })
    items.push({ label: t('te.order.moveDown'), ariaLabel: t('te.order.moveDownOf', ch.name), icon: <ArrowDown aria-hidden="true" />,
      onClick: onMoveDown, disabled: !canMoveDown })
  }
  if (ch.can_delete) items.push({ label: t('te.deleteChannel'), onClick: onDelete, danger: true, disabled: !!anyBusy })
  return items
}

/** Rapor içeriğini yazabilir mi: `can_report` (9. sürüm: paylaşım EDIT de); eski sunucuda `can_edit`. */
export function canReport(ch) {
  return ch?.can_report != null ? !!ch.can_report : !!ch?.can_edit
}

function ChannelChips({ ch }) {
  const t = useT()
  if (ch.active && !ch.approval_required && !ch.shared_access) return null
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {!ch.active && <Badge variant="outline">{t('te.inactive')}</Badge>}
      {ch.shared_access && (
        <Badge variant="outline" className="rounded-md overflow-visible whitespace-normal" data-slot="te-shared" title={t('te.sharedAccessHint')}>
          {t('te.sharedAccess')}
        </Badge>
      )}
      {ch.approval_required && (
        <Badge variant="secondary" className="rounded-md overflow-visible whitespace-normal" data-slot="te-channel-approval">
          {t('te.approvalChip', ch.executive_count || 0)}
        </Badge>
      )}
    </div>
  )
}

function PeriodReportButton({ ch, period, busy, onReport, size = 'sm' }) {
  const t = useT()
  const pr = ch.period_report
  const canStart = canReport(ch) && ch.active
  if (!pr?.id && !canStart) return null
  return (
    <Button type="button" size={size} variant={pr?.id ? 'outline' : 'default'} onClick={onReport} disabled={busy === `r${ch.id}`}
      data-slot="te-period-report" className="pointer-coarse:min-h-10 whitespace-normal text-left h-auto min-h-8">
      <FileText aria-hidden="true" />
      {pr?.id ? t('te.openPeriodReport', periodLabel(period, t)) : t('te.startPeriodReport', periodLabel(period, t))}
    </Button>
  )
}

/**
 * Kanal adı (kanalı açan bağlantı) + takım rozeti. Dokunmatikte aralık 12 px (`pointer-coarse:gap-3`, inceleme 2026-10-17):
 * rozetin `::after` vuruş alanı dikeyde 10 px taşar (TEAM_BADGE_TOUCH) — 6 px'lik aralıkta ad düğmesinin üstüne biniyordu.
 * Yalnız paylaşımla görülen satırda rozet DÜZ metindir (tıklanmaz): üye penceresi sahip takımın kişilerini gösterirdi
 * (kişi verisi paylaşımla görünmez).
 */
function ChannelNameAndTeam({ ch, onOpen, className }) {
  return (
    <div className={cn('flex flex-col gap-1.5 pointer-coarse:gap-3', className)}>
      <Button type="button" variant="link" onClick={onOpen} data-slot="te-channel-name"
        className="h-auto justify-start p-0 text-left text-base font-semibold leading-snug whitespace-normal text-foreground [overflow-wrap:anywhere] pointer-coarse:min-h-10">
        {ch.name}
      </Button>
      <div className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
        <TeamBadge teamId={ch.team_id} teamName={ch.team_name} wrap static={!!ch.shared_access} />
      </div>
    </div>
  )
}

function ChannelRow({ ch, period, busy, onOpen, onTemplate, onReport, ...actions }) {
  const t = useT()
  const pr = ch.period_report
  const items = menuItems(t, ch, { onOpen, ...actions }, true)
  return (
    <TableRow data-slot="te-channel-row" data-channel={ch.id} data-active={ch.active ? 'true' : 'false'}
      className={cn(!ch.active && 'bg-muted/30')}>
      <TableCell className="whitespace-normal align-top">
        <div className="flex flex-col gap-1.5">
          <ChannelNameAndTeam ch={ch} onOpen={onOpen} />
          {ch.description && <p className="m-0 text-sm text-muted-foreground [overflow-wrap:anywhere]">{ch.description}</p>}
          <ChannelChips ch={ch} />
        </div>
      </TableCell>
      <TableCell className="whitespace-normal align-top">
        <div className="flex flex-col items-start gap-2">
          <StatusBadge status={pr?.status || null} sendState={pr?.last_send_status} />
          <PeriodReportButton ch={ch} period={period} busy={busy} onReport={onReport} />
        </div>
      </TableCell>
      <TableCell className="whitespace-normal align-top text-right">
        <div className="text-base font-semibold tabular-nums">{ch.report_count || 0}</div>
        <div className="text-xs text-muted-foreground">{t('te.templateMeta', ch.section_count || 0, ch.slot_count || 0)}</div>
      </TableCell>
      <TableCell className="align-top text-right">
        <div className="flex flex-wrap justify-end gap-2">
          <Button type="button" size="sm" variant="outline" onClick={onTemplate} data-slot="te-open-template"
            className="pointer-coarse:min-h-10">
            <LayoutTemplate aria-hidden="true" /> {t('te.openTemplate')}
          </Button>
          <KebabMenu variant="labeled" items={items} label={t('te.col.actions')} rowLabel={ch.name} />
        </div>
      </TableCell>
    </TableRow>
  )
}

function ChannelCard({ ch, period, busy, onOpen, onTemplate, onReport, ...actions }) {
  const t = useT()
  const pr = ch.period_report
  const items = menuItems(t, ch, { onOpen, ...actions }, false)
  return (
    <Card data-slot="te-channel-card" data-channel={ch.id} data-active={ch.active ? 'true' : 'false'}
      className={cn('gap-3', !ch.active && 'border-dashed')}>
      <CardHeader className="gap-2">
        <div className="flex items-start justify-between gap-2">
          <ChannelNameAndTeam ch={ch} onOpen={onOpen} className="min-w-0" />
          {items.length > 0 && <KebabMenu items={items} label={t('te.channelActions')} rowLabel={ch.name} placement="right" />}
        </div>
        <div className="flex flex-wrap items-center gap-1.5">
          <ChannelChips ch={ch} />
          <StatusBadge status={pr?.status || null} sendState={pr?.last_send_status} prefix={periodLabel(period, t)} />
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-2 text-sm">
        {ch.description && <p className="text-muted-foreground [overflow-wrap:anywhere]">{ch.description}</p>}
        <p className="text-xs text-muted-foreground">
          {t('te.channelMeta', ch.report_count || 0, ch.section_count || 0, ch.slot_count || 0)}
        </p>
      </CardContent>
      <CardFooter className="mt-auto flex flex-wrap gap-2">
        <PeriodReportButton ch={ch} period={period} busy={busy} onReport={onReport} />
        <Button type="button" size="sm" variant="outline" onClick={onTemplate} data-slot="te-open-template"
          className="pointer-coarse:min-h-10">
          <LayoutTemplate aria-hidden="true" /> {t('te.openTemplate')}
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onOpen} className="pointer-coarse:min-h-10">
          {t('te.allReports')}
        </Button>
      </CardFooter>
    </Card>
  )
}
