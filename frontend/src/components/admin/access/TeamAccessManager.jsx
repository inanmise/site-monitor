import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { CalendarClock, Info, PenLine, Plus, RefreshCw, Share2, Trash2, UserRound, UsersRound } from 'lucide-react'
import { api, formatDate, formatDateOnly } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { useDialog } from '../../ui/Dialog.jsx'
import { useToast } from '../../ui/Toast.jsx'
import KebabMenu from '../../ui/KebabMenu.jsx'
import TeamBadge from '../../ui/TeamBadge.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { LoadingBlock } from '../../ui/Progress.jsx'
import MonitorStatsBar from '../../MonitorStatsBar.jsx'
import { ToolbarSearch } from '../ListToolbar.jsx'
import { useElementWidthState } from '../../../hooks/useElementWidth.js'
import { useUrlQuerySync, readUrlParam } from '../../../hooks/useUrlQuerySync.js'
import { errorInfoOf } from '../../../utils/errorMessages.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import TeamAccessGrantDialog, { LEVEL_ICON } from './TeamAccessGrantDialog.jsx'
import { ACCESS_LEVELS, daysLeft, filterGrants, grantState, normalizeGrant, sortGrants, summarize } from './teamAccessModel.js'

/** Kap bu genişliğin altındaysa kartlar (tablo telefonda / dar kapta okunmaz). */
export const ACCESS_TABLE_MIN_PX = 860

/**
 * Yönetim → Organizasyon → "Erişim paylaşımı" (2026-10-12, kullanıcı isteği: "bir ekip kendi izlemelerini başka bir takıma
 * ya da kişiye açabilsin; takım yöneticisi yönetsin; profesyonel, shadcn, mobil uyumlu").
 *
 * <p>Üstte "Sizinle paylaşılan takımlar" (varsa — her kullanıcı görür), ardından yönettiği takımların paylaşımları:
 * özet kartları (toplam / takımlara / kişilere / 7 gün içinde bitecek / geçersiz — tıklanınca süzer), arama + süzgeçler
 * (URL `g_team`, `g_aq`, `g_atype`, `g_alevel`, `g_astate`), kap genişliğine göre tablo ↔ kartlar, satırda Düzenle / Kaldır
 * (yalnız sunucunun `can_edit` dediği satırda). Adlar sarar, kırpılmaz.
 */
export default function TeamAccessManager() {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [state, setState] = useState({ status: 'loading', grants: [], teams: [], message: null })
  const [shared, setShared] = useState([])
  // "Sizinle paylaşılanlar" okunamadı (2026-10-12): kart sessizce kaybolmaz — "yok" ile "okunamadı" ayrı söylenir
  const [sharedFailed, setSharedFailed] = useState(false)
  const [dialog, setDialog] = useState(null)   // { grant } | { grant: null }
  const [q, setQ] = useState(() => readUrlParam('g_aq', ''))
  const [team, setTeam] = useState(() => readUrlParam('g_team', ''))
  const [type, setType] = useState(() => readUrlParam('g_atype', ''))
  const [level, setLevel] = useState(() => readUrlParam('g_alevel', ''))
  const [stateFilter, setStateFilter] = useState(() => readUrlParam('g_astate', ''))
  useUrlQuerySync({ g_aq: q || null, g_team: team || null, g_atype: type || null, g_alevel: level || null, g_astate: stateFilter || null })
  const [width, setListEl] = useElementWidthState()
  const seq = useRef(0)

  const load = useCallback(async () => {
    const my = ++seq.current
    setState((s) => ({ ...s, status: s.status === 'ready' ? 'refreshing' : 'loading' }))
    const [list, mine] = await Promise.allSettled([api.teamAccess.list(), api.teamAccess.sharedWithMe()])
    if (my !== seq.current) return
    if (mine.status === 'fulfilled' && mine.value?.success) {
      setShared(Array.isArray(mine.value.data?.grants) ? mine.value.data.grants : [])
      setSharedFailed(false)
    } else {
      setSharedFailed(true)   // önceki liste (varsa) kalır
    }
    const res = list.status === 'fulfilled' ? list.value : null
    if (res && !res.success && res.status === 403) {
      // teams.sharing/view izni yok (ör. USER): hata değil — "Sizinle paylaşılanlar" kartı kalır, liste boş durumu
      setState({ status: 'ready', grants: [], teams: [], message: null })
      return
    }
    if (res?.success) {
      setState({ status: 'ready', grants: (res.data?.grants || []).map(normalizeGrant),
        teams: Array.isArray(res.data?.manageable_teams) ? res.data.manageable_teams : [], message: null })
    } else {
      setState((s) => ({ ...s, status: 'error', message: res?.error || (list.status === 'rejected' ? list.reason?.message : null) }))
    }
  }, [])

  useEffect(() => { load() }, [load])

  const grants = state.grants
  // KPI'lar arama / takım / düzey süzgeciyle daralır (tür ve durum KPI'nın kendi süzgecidir) — Takımlar menüsünden takımla
  // gelince sayılar o takımın paylaşımlarıdır
  const sum = useMemo(() => summarize(filterGrants(grants, { q, team, level })), [grants, q, team, level])
  const filtered = useMemo(() => sortGrants(filterGrants(grants, { q, team, type, level, state: stateFilter })),
    [grants, q, team, type, level, stateFilter])
  const ownerTeams = useMemo(() => {
    // Paylaşımı olan takımlar + yönetebildiği takımlar (Takımlar menüsünden `g_team` ile gelen, henüz paylaşımı olmayan takım
    // seçicide görünsün — yoksa süzgeç etkinken seçici "Tüm takımlar" gösterirdi)
    const m = new Map()
    for (const g of grants) if (g.ownerTeamId != null && !m.has(String(g.ownerTeamId))) m.set(String(g.ownerTeamId), g.ownerTeamName || String(g.ownerTeamId))
    for (const tm of state.teams) if (tm?.id != null && !m.has(String(tm.id))) m.set(String(tm.id), tm.name || String(tm.id))
    return [...m.entries()].sort((a, b) => a[1].localeCompare(b[1], 'tr'))
  }, [grants, state.teams])
  const canAdd = state.teams.length > 0
  const anyFilter = !!(q || team || type || level || stateFilter)
  const clearFilters = () => { setQ(''); setTeam(''); setType(''); setLevel(''); setStateFilter('') }

  const kpis = [
    { key: 'total', Icon: Share2, cls: 'total', label: t('access.kpi.total'), value: state.status === 'loading' ? '…' : sum.total },
    { key: 'TEAM', Icon: UsersRound, cls: 'valid', label: t('access.kpi.teams'), value: state.status === 'loading' ? '…' : sum.teams },
    { key: 'USER', Icon: UserRound, cls: 'valid', label: t('access.kpi.people'), value: state.status === 'loading' ? '…' : sum.people },
    { key: 'expiring', Icon: CalendarClock, cls: 'warning', label: t('access.kpi.expiring'), value: state.status === 'loading' ? '…' : sum.expiring, hint: t('access.kpi.expiringHint') },
    { key: 'inactive', Icon: Info, cls: 'paused', label: t('access.kpi.inactive'), value: state.status === 'loading' ? '…' : sum.inactive, hint: t('access.kpi.inactiveHint') },
  ]
  // Tür ve durum KPI'ları birbirini dışlar (tek kutu vurgulanır); Toplam yalnız ikisini temizler
  const kpiActive = stateFilter || type || null
  const onKpi = (key) => {
    if (key === 'total') { setType(''); setStateFilter(''); return }
    if (key === 'TEAM' || key === 'USER') { setStateFilter(''); setType((v) => (v === key ? '' : key)); return }
    setType('')
    setStateFilter((v) => (v === key ? '' : key))
  }

  async function remove(g) {
    const ok = await showConfirm({
      title: t('access.remove.title'),
      message: t('access.remove.body', g.granteeName || '—', g.ownerTeamName || '—'),
      confirmText: t('access.remove.ok'), cancelText: t('dlg.cancel'), variant: 'danger',
    })
    if (!ok) return
    try {
      const res = await api.teamAccess.remove(g.id)
      if (res?.success) { toast.success(t('access.toast.removed')); load() }
      else {
        toast.error(res?.error || t('access.err.generic'))
        // Paylaşım zaten yok (başka sekme / yönetici kaldırdı): satır listede kalmasın. Durum gövdede değil künyede.
        if (errorInfoOf(res)?.status === 404) load()
      }
    } catch (e) {
      toast.error(e?.message || t('access.err.generic'))
    }
  }

  const wide = width === 0 || width >= ACCESS_TABLE_MIN_PX

  return (
    <div data-slot="team-access" className="flex min-w-0 flex-col gap-4">
      <Card className="min-w-0 gap-3 px-4 py-4 shadow-xs">
        <div className="flex min-w-0 flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
          <div className="flex min-w-0 items-start gap-3">
            <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary" aria-hidden="true">
              <Share2 className="size-5" />
            </span>
            <div className="flex min-w-0 flex-col gap-1">
              <h3 className="m-0 text-base font-semibold">{t('access.title')}</h3>
              <p className="m-0 text-sm text-muted-foreground">{t('access.desc')}</p>
            </div>
          </div>
          <div className="flex shrink-0 flex-wrap gap-2 max-sm:[&>*]:flex-1">
            <Button type="button" variant="outline" onClick={load} disabled={state.status === 'loading'} className="min-h-10 gap-1.5"
              aria-label={t('access.refresh')}>
              <RefreshCw aria-hidden="true" className={cn(state.status === 'refreshing' && 'animate-spin motion-reduce:animate-none')} />
              <span className="sm:hidden">{t('access.refresh')}</span>
            </Button>
            {canAdd && (
              <Button type="button" onClick={() => setDialog({ grant: null })} className="min-h-10 gap-1.5" data-slot="access-new">
                <Plus aria-hidden="true" />{t('access.new')}
              </Button>
            )}
          </div>
        </div>
      </Card>

      {shared.length > 0 && <SharedWithMe rows={shared} t={t} />}
      {sharedFailed && (
        <div data-slot="access-shared-failed" className="min-w-0">
          <AlertBanner tone="warning" className="mb-0">{t('access.shared.loadFailed')}</AlertBanner>
        </div>
      )}

      {state.status === 'loading' ? (
        <LoadingBlock label={t('access.loading')} />
      ) : state.status === 'error' ? (
        <StatusBlock tone="danger" icon={Share2} title={t('access.loadFailed')} description={state.message || t('access.loadFailedHint')}
          actions={<Button type="button" variant="outline" className="min-h-10 gap-1.5" onClick={load}><RefreshCw aria-hidden="true" />{t('sslv.retry')}</Button>} />
      ) : grants.length === 0 ? (
        <StatusBlock icon={Share2} title={t(canAdd ? 'access.empty.title' : 'access.empty.noManage')}
          description={t(canAdd ? 'access.empty.body' : 'access.empty.noManageBody')}
          actions={canAdd ? <Button type="button" className="min-h-10 gap-1.5" onClick={() => setDialog({ grant: null })}><Plus aria-hidden="true" />{t('access.new')}</Button> : null} />
      ) : (
        <>
          <MonitorStatsBar dense items={kpis} activeFilter={kpiActive} onStatClick={onKpi} className="mb-0" />
          <div className="flex min-w-0 flex-wrap items-center gap-2" data-slot="access-toolbar">
            <ToolbarSearch value={q} onChange={setQ} placeholder={t('access.search')} ariaLabel={t('access.search')} clearLabel={t('app.clear')}
              className="h-10" />
            {(ownerTeams.length > 1 || team) && (
              <NativeSelect value={team} onChange={(e) => setTeam(e.target.value)} aria-label={t('access.f.owner')} className="h-10 min-w-0 max-w-full">
                <NativeSelectOption value="">{t('access.filter.allTeams')}</NativeSelectOption>
                {ownerTeams.map(([id, name]) => <NativeSelectOption key={id} value={id}>{name}</NativeSelectOption>)}
              </NativeSelect>
            )}
            <NativeSelect value={level} onChange={(e) => setLevel(e.target.value)} aria-label={t('access.f.level')} className="h-10 min-w-0 max-w-full">
              <NativeSelectOption value="">{t('access.filter.allLevels')}</NativeSelectOption>
              {ACCESS_LEVELS.map((lv) => <NativeSelectOption key={lv} value={lv}>{t(`access.level.${lv}`)}</NativeSelectOption>)}
            </NativeSelect>
            {anyFilter && <Button type="button" variant="ghost" className="min-h-10" onClick={clearFilters}>{t('access.filter.clear')}</Button>}
            <span className="ml-auto text-sm text-muted-foreground tabular-nums" aria-live="polite">{t('access.count', filtered.length, grants.length)}</span>
          </div>
          <div ref={setListEl} className="min-w-0" data-slot="access-list" data-layout={wide ? 'table' : 'cards'}>
            {filtered.length === 0 ? (
              <StatusBlock icon={Share2} title={t('access.noMatch')}
                actions={<Button type="button" variant="outline" className="min-h-10" onClick={clearFilters}>{t('access.filter.clear')}</Button>} />
            ) : wide ? (
              <GrantTable rows={filtered} t={t} onEdit={(g) => setDialog({ grant: g })} onRemove={remove} />
            ) : (
              <ul className="m-0 grid list-none grid-cols-1 gap-3 p-0 @container sm:grid-cols-2">
                {filtered.map((g) => <li key={g.id} className="min-w-0"><GrantCard g={g} t={t} onEdit={() => setDialog({ grant: g })} onRemove={() => remove(g)} /></li>)}
              </ul>
            )}
          </div>
        </>
      )}

      {dialog && (
        <TeamAccessGrantDialog open grant={dialog.grant} teams={state.teams}
          defaultOwnerTeamId={team && state.teams.some((tm) => String(tm.id) === String(team)) ? team : null}
          onClose={() => setDialog(null)} onSaved={() => { setDialog(null); load() }}
          onGone={() => { setDialog(null); load() }} />
      )}
    </div>
  )
}

/** Dar sütunda sarabilen rozet: hap köşeleri çok satırlı metnin uçlarını kırpmasın (rounded-md + overflow-visible). */
const WRAP_BADGE = 'h-auto rounded-md whitespace-normal overflow-visible text-left'

function LevelBadge({ level, t }) {
  const Icon = LEVEL_ICON[level] || Info
  return (
    <Badge variant={level === 'DELETE' ? 'warning' : 'secondary'} data-slot="access-level" data-level={level}
      className="h-auto gap-1 rounded-md font-semibold whitespace-normal overflow-visible">
      <Icon aria-hidden="true" className="size-3.5" />{level ? t(`access.level.${level}`) : '—'}
    </Badge>
  )
}

function Grantee({ g, t }) {
  const Icon = g.granteeType === 'USER' ? UserRound : UsersRound
  return (
    <span className="flex min-w-0 items-start gap-2" data-slot="access-grantee">
      <Icon aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
      <span className="flex min-w-0 flex-col gap-0.5">
        <span className="font-medium [overflow-wrap:anywhere]">{g.granteeName || t('access.unknownGrantee')}</span>
        <span className="text-xs text-muted-foreground">{t(g.granteeType === 'USER' ? 'access.f.person' : 'access.f.team')}</span>
      </span>
    </span>
  )
}

function Expiry({ g, t }) {
  const st = grantState(g)
  if (!g.expiresOn) {
    return st === 'inactive'
      ? <Badge variant="outline" data-slot="access-state" data-state="inactive" className={WRAP_BADGE}>{t('access.state.inactive')}</Badge>
      : <span className="text-sm text-muted-foreground" data-slot="access-expiry">{t('access.noExpiry')}</span>
  }
  const d = daysLeft(g)
  return (
    <span className="flex min-w-0 flex-col items-start gap-1" data-slot="access-expiry">
      <span className="text-sm tabular-nums">{formatDateOnly(g.expiresOn)}</span>
      {st === 'expired' && <Badge variant="destructive" data-slot="access-state" data-state="expired" className={WRAP_BADGE}>{t('access.state.expired')}</Badge>}
      {st === 'inactive' && <Badge variant="outline" data-slot="access-state" data-state="inactive" className={WRAP_BADGE}>{t('access.state.inactive')}</Badge>}
      {st === 'expiring' && (
        <Badge variant="warning" data-slot="access-state" data-state="expiring" className={cn(WRAP_BADGE, 'tabular-nums')}>
          {d <= 1 ? t('access.lastDay') : t('access.daysLeft', d)}
        </Badge>
      )}
    </span>
  )
}

function Actions({ g, t, onEdit, onRemove }) {
  if (!g.canEdit) return <span className="text-xs text-muted-foreground">{t('access.readOnly')}</span>
  return (
    <KebabMenu variant="labeled" label={t('access.actions')} rowLabel={`${g.ownerTeamName || ''} → ${g.granteeName || ''}`} items={[
      { label: t('access.edit'), icon: <PenLine />, onClick: () => onEdit(g) },
      { label: t('access.remove.ok'), icon: <Trash2 />, onClick: () => onRemove(g), danger: true },
    ]} />
  )
}

function Meta({ g, t }) {
  return (
    <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
      {t('access.addedBy', g.createdBy || '—', g.createdAt ? formatDate(g.createdAt) : '—')}
    </span>
  )
}

function GrantTable({ rows, t, onEdit, onRemove }) {
  return (
    <div className="min-w-0 overflow-x-auto rounded-lg border">
      <Table className="table-fixed" data-slot="access-table">
        <TableHeader>
          <TableRow>
            <TableHead className="w-[22%]">{t('access.col.owner')}</TableHead>
            <TableHead className="w-[26%]">{t('access.col.grantee')}</TableHead>
            <TableHead className="w-[16%]">{t('access.col.level')}</TableHead>
            <TableHead className="w-[14%]">{t('access.col.expires')}</TableHead>
            <TableHead className="w-[12%]">{t('access.col.added')}</TableHead>
            <TableHead className="w-[10rem] text-right">{t('access.actions')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((g) => (
            <TableRow key={g.id} data-slot="access-row" data-state={grantState(g)} className="align-top">
              <TableCell className="whitespace-normal"><TeamBadge teamId={g.ownerTeamId} teamName={g.ownerTeamName} wrap static /></TableCell>
              <TableCell className="whitespace-normal">
                <Grantee g={g} t={t} />
                {g.note && <p className="m-0 mt-1 text-xs text-muted-foreground [overflow-wrap:anywhere]">{g.note}</p>}
              </TableCell>
              <TableCell className="whitespace-normal"><LevelBadge level={g.level} t={t} /></TableCell>
              <TableCell className="whitespace-normal"><Expiry g={g} t={t} /></TableCell>
              <TableCell className="whitespace-normal"><Meta g={g} t={t} /></TableCell>
              <TableCell className="text-right"><Actions g={g} t={t} onEdit={onEdit} onRemove={onRemove} /></TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

function GrantCard({ g, t, onEdit, onRemove }) {
  return (
    <Card data-slot="access-row" data-state={grantState(g)} className="min-w-0 gap-3 px-4 py-3 shadow-xs">
      <div className="flex min-w-0 items-start justify-between gap-2">
        <TeamBadge teamId={g.ownerTeamId} teamName={g.ownerTeamName} wrap static />
        <LevelBadge level={g.level} t={t} />
      </div>
      <Grantee g={g} t={t} />
      {g.note && <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{g.note}</p>}
      <div className="flex min-w-0 flex-wrap items-end justify-between gap-2">
        <div className="flex min-w-0 flex-col gap-1">
          <span className="text-xs text-muted-foreground">{t('access.col.expires')}</span>
          <Expiry g={g} t={t} />
          <Meta g={g} t={t} />
        </div>
        <Actions g={g} t={t} onEdit={() => onEdit(g)} onRemove={() => onRemove(g)} />
      </div>
    </Card>
  )
}

function SharedWithMe({ rows, t }) {
  return (
    <Card data-slot="access-shared-with-me" className="min-w-0 gap-2 px-4 py-3 shadow-xs">
      <h4 className="m-0 flex items-center gap-2 text-sm font-semibold"><Share2 aria-hidden="true" className="size-4" />{t('access.shared.title')}</h4>
      <p className="m-0 text-xs text-muted-foreground">{t('access.shared.desc')}</p>
      <ul className="m-0 flex list-none flex-col divide-y p-0">
        {rows.map((r, i) => (
          <li key={`${r.owner_team_id}-${r.via}-${i}`} className="flex min-w-0 flex-col gap-1 py-2 sm:flex-row sm:items-center sm:justify-between">
            <span className="flex min-w-0 flex-col gap-0.5">
              <span className="font-medium [overflow-wrap:anywhere]">{r.owner_team_name || '—'}</span>
              <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
                {r.via === 'TEAM' ? t('access.shared.viaTeam', r.via_team_name || '—') : t('access.shared.viaYou')}
                {r.expires_on ? ` · ${t('access.shared.until', formatDateOnly(r.expires_on))}` : ''}
              </span>
            </span>
            <LevelBadge level={r.level} t={t} />
          </li>
        ))}
      </ul>
    </Card>
  )
}
