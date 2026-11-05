import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  CalendarClock, CircleAlert, CircleCheck, CirclePause, Forward, Inbox, Lock, PenLine, Play, Plus, RefreshCw, Trash2,
  Mails, BellRing, Layers, ShieldOff,
} from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { useDialog } from '../../ui/Dialog.jsx'
import { useToast } from '../../ui/Toast.jsx'
import KebabMenu from '../../ui/KebabMenu.jsx'
import TeamBadge from '../../ui/TeamBadge.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import MonitorStatsBar from '../../MonitorStatsBar.jsx'
import { ToolbarSearch } from '../ListToolbar.jsx'
import { useElementWidthState } from '../../../hooks/useElementWidth.js'
import { useUrlQuerySync, readUrlParam } from '../../../hooks/useUrlQuerySync.js'
import { errorInfoOf } from '../../../utils/errorMessages.js'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import AlertForwardDialog from './AlertForwardDialog.jsx'
import { AttentionHint, Expiry, Flow, Meta, Note, Recipient, ScopeChips, StateBadge } from './ForwardParts.jsx'
import {
  RECIPIENT_FILTERS, SCOPE_FILTERS, STATE_FILTERS, filterForwards, normalizeForward, normalizeLimits, ownerTeamOptions,
  parseNewRef, payloadOf, recipientSummary, resultNotes, scopeSummary, sortForwards, summarize,
} from './alertForwardModel.js'

/** Kap bu genişliğin altındaysa kartlar (tablo dar kapta okunmaz). */
export const FORWARD_TABLE_MIN_PX = 860

/** URL değeri bilinen listede değilse boş (eski / elle yazılmış bağlantı süzgeci bozmasın). */
const known = (list, v) => (v && list.includes(v) ? v : '')

/**
 * Yönetim → Bildirim & Alarmlar → "Alarm iletimi" (2026-11-05, kullanıcı isteği: "bir takım istediği bir izleme tipi için
 * veya belirli bir izleme için oluşacak alarmların başka bir takıma da gitmesi için başka takımı veya o takımdan belirli
 * üyeleri ek olarak ekleyebilmeli").
 *
 * <p>Başlık kartı (Yenile, Yeni iletim — yalnız yönetebildiği bir takım varsa), "Bize iletilenler" (salt okunur; boşsa
 * çizilmez), özet kutuları (Toplam / Etkin / Duraklatıldı / Süresi doldu / Dikkat — tıklanınca süzer), arama + süzgeçler
 * (URL `g_fq`, `g_fteam`, `g_fscope`, `g_frcpt`, `g_fstate`), kap genişliğine göre tablo ↔ kartlar; satırda Düzenle /
 * Duraklat–Sürdür / Kaldır (yalnız sunucunun `can_edit` dediği satırda). Derin bağ `g_fnew` ("<tür>:<id>" ya da
 * "cert:<alanadı>", sahip takım `g_fteam`): pencere "Seçilen izlemeler" kapsamıyla ve o izleme seçili açılır; okunduktan
 * sonra URL'den silinir. Okunamayan liste "okunamadı" + yeniden dene — asla "iletim yok"; izin yoksa (403) "erişim yok".
 */
export default function AlertForwardManager() {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [state, setState] = useState({ status: 'loading', items: [], incoming: [], teams: [], canGrant: false,
    limits: normalizeLimits(null), message: null })
  const [dialog, setDialog] = useState(null)   // { forward } | { forward: null, preset }
  const [busyId, setBusyId] = useState(null)
  const [q, setQ] = useState(() => readUrlParam('g_fq', ''))
  const [team, setTeam] = useState(() => readUrlParam('g_fteam', ''))
  const [scope, setScope] = useState(() => known(SCOPE_FILTERS, readUrlParam('g_fscope', '')))
  const [rcpt, setRcpt] = useState(() => known(RECIPIENT_FILTERS, readUrlParam('g_frcpt', '')))
  const [stateFilter, setStateFilter] = useState(() => known(STATE_FILTERS, readUrlParam('g_fstate', '')))
  useUrlQuerySync({ g_fq: q || null, g_fteam: team || null, g_fscope: scope || null, g_frcpt: rcpt || null, g_fstate: stateFilter || null })
  // Derin bağ (izleme penceresindeki "Bu izleme için iletim ekle"): mount'ta okunur (saf okuma — StrictMode çift çizimi
  // bağı kaybetmez), URL'den etkide silinir (yenilemede pencere tekrar açılmaz); pencere liste gelince bir kez açılır
  const [deep] = useState(() => {
    const raw = readUrlParam('g_fnew', null)
    return { raw, ref: parseNewRef(raw), team: readUrlParam('g_fteam', null) }
  })
  const deepUsed = useRef(false)
  useEffect(() => {
    if (deep.raw == null) return
    try {
      const url = new URL(window.location.href)
      if (!url.searchParams.has('g_fnew')) return
      url.searchParams.delete('g_fnew')
      const qs = url.searchParams.toString()
      window.history.replaceState(window.history.state, '', url.pathname + (qs ? `?${qs}` : '') + url.hash)
    } catch { /* en iyi çaba */ }
  }, [deep])
  const [width, setListEl] = useElementWidthState()
  const seq = useRef(0)

  const load = useCallback(async () => {
    const my = ++seq.current
    setState((s) => ({ ...s, status: s.status === 'ready' ? 'refreshing' : 'loading' }))
    let res = null
    let thrown = null
    try { res = await api.alertForwards.list() } catch (e) { thrown = e }
    if (my !== seq.current) return
    const status = errorInfoOf(res)?.status
    if (res && !res.success && status === 403) {
      setState((s) => ({ ...s, status: 'forbidden', items: [], incoming: [], teams: [], message: null }))
      return
    }
    if (res?.success) {
      const d = payloadOf(res) || {}
      setState({
        status: 'ready',
        items: (Array.isArray(d.items) ? d.items : []).map(normalizeForward),
        incoming: (Array.isArray(d.incoming) ? d.incoming : []).map(normalizeForward),
        teams: Array.isArray(d.manageable_teams) ? d.manageable_teams.filter((x) => x && x.id != null) : [],
        canGrant: d.can_grant === true,
        limits: normalizeLimits(d.limits),
        message: null,
      })
      return
    }
    // Okuma hatası: önceki liste (varsa) korunur ama ekran "okunamadı" der — boş liste "iletim yok" sanılmasın
    setState((s) => ({ ...s, status: s.status === 'refreshing' ? 'stale' : 'error',
      message: res?.error || thrown?.message || null }))
  }, [])

  useEffect(() => { load() }, [load])

  // Derin bağ: liste geldikten sonra bir kez — sahip takım yönetilebiliyorsa pencere açılır, yoksa nedeni söylenir
  const [deepLinkProblem, setDeepLinkProblem] = useState(null)
  useEffect(() => {
    const d = deep
    if (deepUsed.current || d.raw == null || state.status !== 'ready') return
    deepUsed.current = true
    if (!d.ref) { setDeepLinkProblem('fwd.deepLink.invalid'); return }
    const teams = state.teams
    const owner = d.team != null ? teams.find((x) => String(x.id) === String(d.team))
      : (teams.length === 1 ? teams[0] : null)
    if (!owner) {
      setDeepLinkProblem(teams.length ? 'fwd.deepLink.notManaged' : 'fwd.deepLink.noManage')
      // Görmediği (ve yönetmediği) takımla süzülü boş liste "iletim yok" sanılmasın: süzgeç bırakılır
      if (d.team != null && !state.items.some((f) => String(f.ownerTeamId) === String(d.team))) setTeam('')
      return
    }
    setDialog({ forward: null, preset: { ownerTeamId: owner.id, monitorRef: d.ref } })
  }, [deep, state.status, state.teams, state.items])

  const items = state.items
  const incoming = state.incoming
  // KPI'lar arama / takım / kapsam / alıcı süzgeciyle daralır (durum KPI'nın kendi süzgecidir)
  const sum = useMemo(() => summarize(filterForwards(items, { q, team, scope, rcpt }, t)), [items, q, team, scope, rcpt, t])
  const filtered = useMemo(() => sortForwards(filterForwards(items, { q, team, scope, rcpt, state: stateFilter }, t)),
    [items, q, team, scope, rcpt, stateFilter, t])
  const owners = useMemo(() => ownerTeamOptions(items, state.teams), [items, state.teams])
  const canAdd = state.teams.length > 0
  const anyFilter = !!(q || team || scope || rcpt || stateFilter)
  const clearFilters = () => { setQ(''); setTeam(''); setScope(''); setRcpt(''); setStateFilter('') }
  const loading = state.status === 'loading'

  const kpis = [
    { key: 'total', Icon: Forward, cls: 'total', label: t('fwd.kpi.total'), value: loading ? '…' : sum.total },
    { key: 'active', Icon: CircleCheck, cls: 'valid', label: t('fwd.kpi.active'), value: loading ? '…' : sum.active, hint: t('fwd.kpi.activeHint') },
    { key: 'paused', Icon: CirclePause, cls: 'paused', label: t('fwd.kpi.paused'), value: loading ? '…' : sum.paused },
    { key: 'expired', Icon: CalendarClock, cls: 'paused', label: t('fwd.kpi.expired'), value: loading ? '…' : sum.expired },
    { key: 'attention', Icon: CircleAlert, cls: 'warning', label: t('fwd.kpi.attention'), value: loading ? '…' : sum.attention, hint: t('fwd.kpi.attentionHint') },
  ]
  const onKpi = (key) => {
    if (key === 'total') { setStateFilter(''); return }
    setStateFilter((v) => (v === key ? '' : key))
  }

  // Sahip takım süzgeci yönetilen bir takımsa pencere o takımla açılır (kimlik takım kaydından — URL metni değil)
  const openNew = () => {
    const owner = team ? state.teams.find((x) => String(x.id) === String(team)) : null
    setDialog({ forward: null, preset: { ownerTeamId: owner ? owner.id : null } })
  }

  async function remove(f) {
    const ok = await showConfirm({
      title: t('fwd.remove.title'),
      message: t('fwd.remove.body', f.ownerTeamName || '—', recipientSummary(f, t), scopeSummary(f, t)),
      confirmText: t('fwd.remove.ok'), cancelText: t('dlg.cancel'), variant: 'danger',
    })
    if (!ok) return
    setBusyId(f.id)
    try {
      const res = await api.alertForwards.remove(f.id)
      if (res?.success) { toast.success(t('fwd.toast.removed')); load() } else {
        toast.error(res?.error || t('fwd.err.generic'), { details: res })
        if (errorInfoOf(res)?.status === 404) load()   // zaten yok (başka sekme / yönetici kaldırdı)
      }
    } catch (e) {
      toast.error(e?.message || t('fwd.err.generic'), { details: e })
    } finally {
      setBusyId(null)
    }
  }

  async function togglePause(f) {
    const enabled = f.state === 'paused'
    setBusyId(f.id)
    try {
      const body = { enabled }
      if (f.version != null) body.version = f.version
      const res = await api.alertForwards.update(f.id, body)
      if (res?.success) {
        toast.success(t(enabled ? 'fwd.toast.resumed' : 'fwd.toast.paused'))
        load()
      } else {
        toast.error(res?.error || t('fwd.err.generic'), { details: res })
        const st = errorInfoOf(res)?.status
        if (st === 404 || st === 409) load()   // kaldırılmış / başka biri değiştirmiş → güncel liste
      }
    } catch (e) {
      toast.error(e?.message || t('fwd.err.generic'), { details: e })
    } finally {
      setBusyId(null)
    }
  }

  function onSaved(res, isNew) {
    setDialog(null)
    const d = res && typeof res === 'object' ? res : {}
    const notes = resultNotes(d, t)
    toast.success([t(isNew ? 'fwd.toast.created' : 'fwd.toast.updated'), ...notes.ok].join(' '))
    for (const w of notes.warn) toast.info(w)
    load()
  }

  const wide = width === 0 || width >= FORWARD_TABLE_MIN_PX
  const showList = state.status === 'ready' || state.status === 'refreshing' || state.status === 'stale'

  return (
    <div data-slot="alert-forwards" className="flex min-w-0 flex-col gap-4">
      {/* Kap sorgusu (@container): yan menü açık tablette içerik ~430 px — görünüm alanı değil kap genişliği belirler */}
      <Card className="@container min-w-0 gap-3 px-4 py-4 shadow-xs">
        <div className="flex min-w-0 flex-col gap-3 @lg:flex-row @lg:items-start @lg:justify-between">
          <div className="flex min-w-0 items-start gap-3">
            <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary" aria-hidden="true">
              <Forward className="size-5" />
            </span>
            <div className="flex min-w-0 flex-col gap-1">
              <h3 className="m-0 text-base font-semibold">{t('fwd.title')}</h3>
              <p className="m-0 text-sm text-muted-foreground">{t('fwd.desc')}</p>
            </div>
          </div>
          <div className="flex shrink-0 flex-wrap gap-2 [&>*]:flex-1 @lg:[&>*]:flex-none">
            <Button type="button" variant="outline" onClick={load} disabled={loading || state.status === 'refreshing'}
              className="min-h-10 gap-1.5" aria-label={t('fwd.refresh')} data-slot="fwd-refresh">
              <RefreshCw aria-hidden="true" className={cn(state.status === 'refreshing' && 'animate-spin motion-reduce:animate-none')} />
              <span className="@lg:hidden">{t('fwd.refresh')}</span>
            </Button>
            {canAdd && (
              <Button type="button" onClick={openNew} className="min-h-10 gap-1.5" data-slot="fwd-new">
                <Plus aria-hidden="true" />{t('fwd.new')}
              </Button>
            )}
          </div>
        </div>
      </Card>

      {deepLinkProblem && (
        <div data-slot="fwd-deeplink-problem" className="min-w-0">
          <AlertBanner tone="warning" className="mb-0" onDismiss={() => setDeepLinkProblem(null)} dismissLabel={t('app.close')}>
            {t(deepLinkProblem)}
          </AlertBanner>
        </div>
      )}

      {incoming.length > 0 && <IncomingCard rows={incoming} t={t} />}

      {state.status === 'stale' && (
        <div data-slot="fwd-stale" className="min-w-0">
          <AlertBanner tone="danger" className="mb-0"
            actions={<Button type="button" variant="secondary" size="sm" className="pointer-coarse:h-10" onClick={load}>{t('fwd.retry')}</Button>}>
            {t('fwd.refreshFailed')}
          </AlertBanner>
        </div>
      )}

      {loading ? (
        <ListSkeleton t={t} />
      ) : state.status === 'forbidden' ? (
        <StatusBlock icon={ShieldOff} title={t('fwd.forbidden.title')} description={t('fwd.forbidden.body')} />
      ) : state.status === 'error' ? (
        <StatusBlock tone="danger" icon={Forward} title={t('fwd.loadFailed')} description={state.message || t('fwd.loadFailedHint')}
          actions={<Button type="button" variant="outline" className="min-h-10 gap-1.5" onClick={load}><RefreshCw aria-hidden="true" />{t('fwd.retry')}</Button>} />
      ) : showList && items.length === 0 ? (
        <EmptyState t={t} canAdd={canAdd} onAdd={openNew} />
      ) : showList ? (
        <>
          <MonitorStatsBar dense items={kpis} activeFilter={stateFilter || null} onStatClick={onKpi} className="mb-0" />
          <div className="flex min-w-0 flex-wrap items-center gap-2" data-slot="fwd-toolbar">
            <ToolbarSearch value={q} onChange={setQ} placeholder={t('fwd.search')} ariaLabel={t('fwd.search')} clearLabel={t('app.clear')}
              className="h-10" />
            {(owners.length > 1 || team) && (
              // Seçenekler uzun takım adları: yerel seçici en uzun seçenek kadar genişlerdi — telefonda tam genişlik, geniş ekranda tavanlı
              <NativeSelect value={team} onChange={(e) => setTeam(e.target.value)} aria-label={t('fwd.f.ownerFilter')}
                className="h-10 w-full min-w-0 max-w-full sm:w-72" data-slot="fwd-filter-team">
                <NativeSelectOption value="">{t('fwd.filter.allTeams')}</NativeSelectOption>
                {owners.map(([id, name]) => <NativeSelectOption key={id} value={id}>{name}</NativeSelectOption>)}
              </NativeSelect>
            )}
            <NativeSelect value={scope} onChange={(e) => setScope(e.target.value)} aria-label={t('fwd.f.scopeFilter')} className="h-10 min-w-0 max-w-full"
              data-slot="fwd-filter-scope">
              <NativeSelectOption value="">{t('fwd.filter.allScopes')}</NativeSelectOption>
              {SCOPE_FILTERS.map((s) => <NativeSelectOption key={s} value={s}>{t(`fwd.scopeFilter.${s}`)}</NativeSelectOption>)}
            </NativeSelect>
            <NativeSelect value={rcpt} onChange={(e) => setRcpt(e.target.value)} aria-label={t('fwd.f.recipientFilter')} className="h-10 min-w-0 max-w-full"
              data-slot="fwd-filter-rcpt">
              <NativeSelectOption value="">{t('fwd.filter.allRecipients')}</NativeSelectOption>
              {RECIPIENT_FILTERS.map((s) => <NativeSelectOption key={s} value={s}>{t(`fwd.rcptFilter.${s}`)}</NativeSelectOption>)}
            </NativeSelect>
            <NativeSelect value={stateFilter} onChange={(e) => setStateFilter(e.target.value)} aria-label={t('fwd.f.stateFilter')} className="h-10 min-w-0 max-w-full"
              data-slot="fwd-filter-state">
              <NativeSelectOption value="">{t('fwd.filter.allStates')}</NativeSelectOption>
              {STATE_FILTERS.map((s) => <NativeSelectOption key={s} value={s}>{t(`fwd.stateFilter.${s}`)}</NativeSelectOption>)}
            </NativeSelect>
            {anyFilter && <Button type="button" variant="ghost" className="min-h-10" onClick={clearFilters} data-slot="fwd-clear">{t('fwd.filter.clear')}</Button>}
            <span className="ml-auto text-sm text-muted-foreground tabular-nums" aria-live="polite" data-slot="fwd-count">
              {t('fwd.count', filtered.length, items.length)}
            </span>
          </div>
          <div ref={setListEl} className="@container min-w-0" data-slot="fwd-list" data-layout={wide ? 'table' : 'cards'}>
            {filtered.length === 0 ? (
              <StatusBlock icon={Forward} title={t('fwd.noMatch')}
                actions={<Button type="button" variant="outline" className="min-h-10" onClick={clearFilters}>{t('fwd.filter.clear')}</Button>} />
            ) : wide ? (
              <ForwardTable rows={filtered} t={t} busyId={busyId} onEdit={(f) => setDialog({ forward: f })} onToggle={togglePause} onRemove={remove} />
            ) : (
              <ul className="m-0 grid list-none grid-cols-1 gap-3 p-0 @xl:grid-cols-2">
                {filtered.map((f) => (
                  <li key={f.id} className="min-w-0">
                    <ForwardCard f={f} t={t} busy={busyId === f.id}
                      onEdit={() => setDialog({ forward: f })} onToggle={() => togglePause(f)} onRemove={() => remove(f)} />
                  </li>
                ))}
              </ul>
            )}
          </div>
        </>
      ) : null}

      {dialog && (
        <AlertForwardDialog open forward={dialog.forward} preset={dialog.preset || null} teams={state.teams}
          canGrant={state.canGrant} limits={state.limits}
          onClose={() => setDialog(null)} onSaved={(res) => onSaved(res, !dialog.forward)} onStale={load}
          onGone={() => { setDialog(null); load() }} />
      )}
    </div>
  )
}

function Actions({ f, t, busy, onEdit, onToggle, onRemove }) {
  if (!f.canEdit) {
    return (
      <span className="inline-flex items-center gap-1 text-xs text-muted-foreground" data-slot="fwd-readonly">
        <Lock aria-hidden="true" className="size-3.5" />{t('fwd.readOnly')}
      </span>
    )
  }
  const paused = f.state === 'paused'
  return (
    <KebabMenu variant="labeled" label={t('fwd.actions')} rowLabel={`${f.ownerTeamName || ''} → ${f.recipientTeamName || ''}`} items={[
      { label: t('fwd.edit'), icon: <PenLine />, onClick: () => onEdit(f), disabled: busy },
      { label: t(paused ? 'fwd.resume' : 'fwd.pause'), icon: paused ? <Play /> : <CirclePause />, onClick: () => onToggle(f), disabled: busy },
      { label: t('fwd.remove.ok'), icon: <Trash2 />, onClick: () => onRemove(f), danger: true, disabled: busy },
    ]} />
  )
}

function ForwardTable({ rows, t, busyId, onEdit, onToggle, onRemove }) {
  return (
    <div className="min-w-0 overflow-x-auto rounded-lg border">
      <Table className="table-fixed" data-slot="fwd-table">
        <TableHeader>
          <TableRow>
            <TableHead className="w-[17%] whitespace-normal">{t('fwd.col.owner')}</TableHead>
            <TableHead className="w-[25%] whitespace-normal">{t('fwd.col.recipient')}</TableHead>
            <TableHead className="w-[24%] whitespace-normal">{t('fwd.col.scope')}</TableHead>
            <TableHead className="w-[17%] whitespace-normal">{t('fwd.col.state')}</TableHead>
            <TableHead className="w-[10rem] text-right whitespace-normal">{t('fwd.actions')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((f) => (
            <TableRow key={f.id} data-slot="fwd-row" data-state={f.state} data-id={f.id} className="align-top">
              <TableCell className="whitespace-normal">
                <span className="flex min-w-0 flex-col items-start gap-1">
                  <TeamBadge teamId={f.ownerTeamId} teamName={f.ownerTeamName || t('fwd.unknownTeam')} wrap static />
                  <Meta f={f} t={t} />
                </span>
              </TableCell>
              <TableCell className="whitespace-normal">
                <Recipient f={f} t={t} />
                <div className="mt-1"><Note f={f} /></div>
              </TableCell>
              <TableCell className="whitespace-normal"><ScopeChips f={f} t={t} /></TableCell>
              <TableCell className="whitespace-normal">
                <span className="flex min-w-0 flex-col items-start gap-1.5">
                  <StateBadge f={f} t={t} />
                  <Expiry f={f} t={t} />
                  <AttentionHint f={f} t={t} />
                </span>
              </TableCell>
              <TableCell className="text-right">
                <Actions f={f} t={t} busy={busyId === f.id} onEdit={onEdit} onToggle={onToggle} onRemove={onRemove} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

function ForwardCard({ f, t, busy, onEdit, onToggle, onRemove }) {
  return (
    <Card data-slot="fwd-row" data-state={f.state} data-id={f.id} className="min-w-0 gap-3 px-4 py-3 shadow-xs">
      <div className="flex min-w-0 flex-wrap items-start justify-between gap-2">
        <StateBadge f={f} t={t} />
        <Expiry f={f} t={t} />
      </div>
      <Flow f={f} t={t} />
      <div className="flex min-w-0 flex-col gap-1">
        <span className="text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">{t('fwd.col.scope')}</span>
        <ScopeChips f={f} t={t} />
      </div>
      <AttentionHint f={f} t={t} />
      <Note f={f} />
      <div className="flex min-w-0 flex-wrap items-end justify-between gap-2">
        <Meta f={f} t={t} />
        <Actions f={f} t={t} busy={busy} onEdit={() => onEdit(f)} onToggle={() => onToggle(f)} onRemove={() => onRemove(f)} />
      </div>
    </Card>
  )
}

/** "Bize iletilenler": başka takımların bize (üye olduğumuz / yönettiğimiz takıma ya da bize) ilettikleri — salt okunur. */
function IncomingCard({ rows, t }) {
  const sorted = sortForwards(rows)
  return (
    <Card data-slot="fwd-incoming" className="min-w-0 gap-2 px-4 py-3 shadow-xs">
      <h4 className="m-0 flex items-center gap-2 text-sm font-semibold"><Inbox aria-hidden="true" className="size-4" />{t('fwd.incoming.title')}</h4>
      <p className="m-0 text-xs text-muted-foreground">{t('fwd.incoming.desc')}</p>
      <ul className="m-0 flex list-none flex-col divide-y p-0">
        {sorted.map((f) => (
          <li key={f.id} data-slot="fwd-incoming-row" data-state={f.state} className="flex min-w-0 flex-col gap-2 py-3 first:pt-1 last:pb-1">
            <div className="flex min-w-0 flex-wrap items-start justify-between gap-2">
              <Flow f={f} t={t} />
              <StateBadge f={f} t={t} />
            </div>
            <ScopeChips f={f} t={t} />
            <Note f={f} />
          </li>
        ))}
      </ul>
    </Card>
  )
}

/** İlk iletim yokken: ne işe yaradığını üç adımda anlatan, eylem çağrılı boş durum. */
function EmptyState({ t, canAdd, onAdd }) {
  const steps = [
    { Icon: Layers, title: t('fwd.empty.step1'), body: t('fwd.empty.step1Body') },
    { Icon: Mails, title: t('fwd.empty.step2'), body: t('fwd.empty.step2Body') },
    { Icon: BellRing, title: t('fwd.empty.step3'), body: t('fwd.empty.step3Body') },
  ]
  return (
    <Card data-slot="fwd-empty" className="min-w-0 items-center gap-4 px-4 py-8 text-center shadow-xs">
      <span className="flex size-12 items-center justify-center rounded-full bg-primary/10 text-primary" aria-hidden="true">
        <Forward className="size-6" />
      </span>
      <div className="flex max-w-xl flex-col gap-1">
        <h4 className="m-0 text-base font-semibold">{t(canAdd ? 'fwd.empty.title' : 'fwd.empty.noManage')}</h4>
        <p className="m-0 text-sm text-muted-foreground">{t(canAdd ? 'fwd.empty.body' : 'fwd.empty.noManageBody')}</p>
      </div>
      <ol className="m-0 grid w-full max-w-3xl list-none grid-cols-1 gap-3 p-0 text-left sm:grid-cols-3">
        {steps.map((s, i) => (
          <li key={s.title} className="flex min-w-0 items-start gap-2 rounded-lg border bg-muted/30 px-3 py-2.5">
            <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-background text-xs font-semibold tabular-nums" aria-hidden="true">{i + 1}</span>
            <span className="flex min-w-0 flex-col gap-0.5">
              <span className="flex items-center gap-1.5 text-sm font-semibold"><s.Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />{s.title}</span>
              <span className="text-xs text-muted-foreground">{s.body}</span>
            </span>
          </li>
        ))}
      </ol>
      {canAdd && (
        <Button type="button" className="min-h-10 gap-1.5" onClick={onAdd} data-slot="fwd-empty-add">
          <Plus aria-hidden="true" />{t('fwd.empty.cta')}
        </Button>
      )}
    </Card>
  )
}

function ListSkeleton({ t }) {
  return (
    <div className="flex min-w-0 flex-col gap-3" data-slot="fwd-skeleton" role="status" aria-label={t('fwd.loading')}>
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-5">
        {[0, 1, 2, 3, 4].map((i) => <Skeleton key={i} className="h-14" />)}
      </div>
      <Skeleton className="h-10 w-full max-w-md" />
      {[0, 1, 2].map((i) => <Skeleton key={i} className="h-24 w-full" />)}
    </div>
  )
}
