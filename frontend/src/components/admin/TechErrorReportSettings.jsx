import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  CheckCircle2, CircleAlert, ExternalLink, EyeOff, FileClock, FileWarning, Landmark, MailWarning, Radio, RefreshCw, Search, Users, X,
} from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import Field from '../ui/Field.jsx'
import MultiTeamSelect from '../ui/MultiTeamSelect.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import MonitorStatsBar from '../MonitorStatsBar.jsx'
import ToneBadge from './ToneBadge.jsx'
import { usePagination } from '../../hooks/usePagination.js'
import { useElementWidth } from '../../hooks/useElementWidth.js'
import { navigateTo } from '../../utils/navigate.js'
import { unwrap } from '../noc/nocModel.js'
import { SettingsSaveBar, SettingsSection } from './SettingsControls.jsx'
import { isDirty, normalizeIds, teamOptions } from './noc/operatorTeamsModel.js'
import { StatusBadge, periodLabel } from '../techerror/TechErrorParts.jsx'
import { TE_CHANNEL, TE_VIEW, VIEW_CHANNEL } from '../techerror/techErrorModel.js'
import {
  TES_FLAG_FILTERS, TES_PERIOD_FILTERS, TES_SORTS, TES_TABLE_MIN_PX, channelPeriodStatus, countChannels, filterChannels,
  filtersActive, hasExecutives, normalizeFilter, normalizeSort, searchChannels, sortChannels, teamEmailState, teamVisibleState, teamsOf,
} from './techErrorSettingsModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { NativeSelect, NativeSelectOptGroup, NativeSelectOption } from '@/components/shadcn/native-select'
import { cn } from '@/lib/utils'

const ALL = 'all'
const TOUCH = 'pointer-coarse:min-h-10'

/**
 * Ayarlar → Platform → Teknik hata raporu (2026-10-17, S6; yeniden tasarım aynı gün — kullanıcı isteği: "shadcn ile
 * yeniden tasarla, mweb duyarlı, mevcut işlevler korunsun").
 *
 * İşlevler (sunucu sözleşmesi DEĞİŞMEDİ — `GET/PUT /api/admin/tech-error-reports/settings`):
 *  - modülü gören takımlar (boş = herkes; GLOBAL_ONLY — kapsamlı müdür `read_only` ile salt okunur görür, kayıt çubuğu yok);
 *  - kanalların onay akışı özeti: yönetici alıcı sayısı, PO onaylayıcı sayısı, takım e-postası, modül görünürlüğü, dönem
 *    raporunun durumu + onay durumu. Yönetici alıcıları kanalın kendi iletişim kutusundan girilir; burası yalnız gösterir.
 *
 * Yeni: dönem KPI kartları (süzgeç), araç çubuğu (arama — Türkçe katlama, takım, durum, sıralama, temizle), kap genişliğine
 * göre TABLO (≥ {@link TES_TABLE_MIN_PX}) ya da KART, sayfalama (`panel` ön ayarı), "Kanalı aç" → kanal sayfası. Yükleme
 * iskeletle, okuma hatası tehlike bloğu + Tekrar dene (boş "kanal yok" durumu YALNIZ başarılı yanıtta). Saf mantık
 * `techErrorSettingsModel.js`. Test kancaları `tes-*`.
 */
export default function TechErrorReportSettings({ readOnly = false }) {
  const t = useT()
  const toast = useToast()
  const [settings, setSettings] = useState(null)
  const [draft, setDraft] = useState([])
  const [directory, setDirectory] = useState([])
  const [loadError, setLoadError] = useState(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [q, setQ] = useState('')
  const [team, setTeam] = useState(ALL)
  const [filter, setFilter] = useState(ALL)
  const [sort, setSort] = useState('name')
  const [listRef, listWidth] = useElementWidth()
  const alive = useRef(true)
  const loadSeq = useRef(0)

  const load = useCallback(async () => {
    const id = ++loadSeq.current
    setLoadError(null)
    setLoading(true)
    try {
      const [cfg, dir] = await Promise.all([
        api.admin.techErrorSettings(),
        Promise.resolve(api.teams?.directory ? api.teams.directory() : null).catch(() => null),
      ])
      if (!alive.current || id !== loadSeq.current) return
      const r = unwrap(cfg)
      if (!r.ok) { setLoadError(r.error || t('te.set.loadError')); return }
      const data = r.data || {}
      const ids = normalizeIds(data.team_ids)
      setSettings({ ...data, ids, readOnly: readOnly || data.read_only === true })
      setDraft(ids)
      const d = unwrap(dir)
      setDirectory(d.ok && Array.isArray(d.data) ? d.data : [])
    } catch (e) {
      if (alive.current && id === loadSeq.current) setLoadError(e?.message || t('te.set.loadError'))
    } finally {
      if (alive.current && id === loadSeq.current) setLoading(false)
    }
  }, [readOnly, t])
  useEffect(() => {
    alive.current = true
    load()
    return () => { alive.current = false }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const options = useMemo(() => teamOptions(directory, draft, t('noc.ot.inactiveTeam')), [directory, draft, t])
  const dirty = !!settings && isDirty(settings.ids, draft)
  const locked = !!settings?.readOnly

  async function save() {
    if (!settings || saving || locked) return
    setSaving(true)
    try {
      const r = unwrap(await api.admin.saveTechErrorSettings({ team_ids: normalizeIds(draft) }))
      if (!alive.current) return
      if (!r.ok) { toast.error(r.error || t('te.set.saveError'), { details: r.errorInfo }); return }
      const ids = normalizeIds(r.data?.team_ids ?? draft)
      setSettings((s) => ({ ...s, ids }))
      setDraft(ids)
      toast.success(t('te.set.saved'))
    } catch (e) {
      toast.error(e?.message || t('te.set.saveError'))
    } finally {
      if (alive.current) setSaving(false)
    }
  }

  const channels = useMemo(() => (Array.isArray(settings?.channels) ? settings.channels : []), [settings])
  const period = settings?.period
  const pLabel = periodLabel(period, t)
  const teams = useMemo(() => teamsOf(channels), [channels])
  const teamSelectOptions = useMemo(() => [{ value: ALL, label: t('te.allTeams') }, ...teams.map((tm) => ({ value: tm.id, label: tm.name }))], [teams, t])
  // KPI sayımları arama + takım süzgecinin ÜZERİNDEN (kart süzgecinin kendisi hariç) — kartlar "bu listede kaç tane" der
  const searched = useMemo(() => searchChannels(channels, { q, team }), [channels, q, team])
  const counts = useMemo(() => countChannels(searched), [searched])
  const visible = useMemo(() => sortChannels(filterChannels(channels, { q, team, filter }), sort), [channels, q, team, filter, sort])
  const pager = usePagination(visible, { listKey: 'tes-channels', preset: 'panel', resetDeps: [q, team, filter, sort] })
  const cardsLayout = listWidth > 0 && listWidth < TES_TABLE_MIN_PX
  const anyFilter = filtersActive({ q, team, filter })

  function clearFilters() {
    setQ('')
    setTeam(ALL)
    setFilter(ALL)
  }

  function openChannel(ch) {
    navigateTo('channelreports', { [TE_VIEW]: VIEW_CHANNEL, [TE_CHANNEL]: String(ch.id) })
  }

  const kpiItems = channels.length === 0 ? [] : [
    { key: ALL, Icon: Radio, label: t('te.set.kpi.total'), value: counts.total, cls: 'total', hint: t('te.set.kpi.hint.total') },
    { key: 'sent', Icon: CheckCircle2, label: t('te.kpi.sent', pLabel), value: counts.sent, cls: 'valid', hint: t('te.kpi.hint.sent') },
    { key: 'open', Icon: FileClock, label: t('te.kpi.open'), value: counts.open, cls: 'warning', hint: t('te.kpi.hint.open') },
    { key: 'missing', Icon: FileWarning, label: t('te.kpi.missing'), value: counts.missing, cls: 'alert', hint: t('te.kpi.hint.missing') },
    { key: 'exec', Icon: Landmark, label: t('te.set.kpi.exec'), value: counts.exec, cls: 'weak', hint: t('te.set.kpi.hint.exec') },
    { key: 'noEmail', Icon: MailWarning, label: t('te.set.kpi.noEmail'), value: counts.noEmail, cls: 'error', hint: t('te.set.kpi.hint.noEmail') },
    { key: 'hidden', Icon: EyeOff, label: t('te.set.kpi.hidden'), value: counts.hidden, cls: 'paused', hint: t('te.set.kpi.hint.hidden') },
  ]

  const refreshButton = settings && (
    <Button type="button" variant="outline" size="sm" onClick={load} disabled={loading} data-slot="tes-refresh" className={cn('h-9 sm:h-8', TOUCH)}>
      <RefreshCw aria-hidden="true" className={cn(loading && 'animate-spin motion-reduce:animate-none')} /> {t('te.refresh')}
    </Button>
  )

  return (
    <div data-slot="tes-root" data-testid="techerror-settings" className="flex min-w-0 w-full flex-col gap-5">
      {/* 1 — Modül görünürlüğü (ayar) */}
      <SettingsSection title={t('te.set.title')} description={t('te.set.desc')} contentClassName="flex flex-col gap-4">
        {loadError && !settings && (
          <StatusBlock tone="danger" role="alert" icon={CircleAlert} title={t('te.set.unreadableTitle')} description={String(loadError)}
            className="py-8"
            actions={<Button type="button" variant="outline" onClick={load} className={TOUCH} data-slot="tes-retry"><RefreshCw aria-hidden="true" /> {t('te.retry')}</Button>} />
        )}
        {!settings && !loadError && (
          <div role="status" aria-live="polite" className="flex flex-col gap-3" data-slot="tes-skeleton">
            <span className="sr-only">{t('settings.loading')}</span>
            <Skeleton className="h-4 w-40 motion-reduce:animate-none" />
            <Skeleton className="h-10 w-full motion-reduce:animate-none" />
            <Skeleton className="h-4 w-3/4 motion-reduce:animate-none" />
          </div>
        )}
        {settings && (
          <>
            {loadError && (
              <AlertBanner tone="danger" title={t('te.refreshFailed')} className="mb-0"
                actions={<Button type="button" variant="outline" size="sm" className={cn('h-9 sm:h-8', TOUCH)} onClick={load}><RefreshCw aria-hidden="true" />{t('te.retry')}</Button>}>
                {String(loadError)}
              </AlertBanner>
            )}
            {locked && (
              <div data-slot="tes-readonly">
                <AlertBanner tone="info" role="status" className="mb-0">{t('te.set.readOnly')}</AlertBanner>
              </div>
            )}
            <div data-touch-picker="" className="min-w-0">
              <Field label={t('te.set.visibility')} hint={t('te.set.visibilityHint')} name="team_ids" className="mb-0">
                {({ id }) => (
                  <MultiTeamSelect id={id} value={draft} options={options} disabled={locked} ariaLabel={t('te.set.visibility')}
                    placeholder={t('te.set.selectTeams')} onChange={(ids) => setDraft(normalizeIds(ids))} />
                )}
              </Field>
            </div>
            {draft.length === 0 && (
              <p className="m-0 flex items-start gap-2 text-sm text-muted-foreground" data-slot="tes-everyone">
                <Users aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
                <span>{t('te.set.everyone')}</span>
              </p>
            )}
            {!locked && (
              <SettingsSaveBar dirty={dirty} saving={saving} onSave={save} onDiscard={() => setDraft(settings.ids)} />
            )}
          </>
        )}
      </SettingsSection>

      {/* 2 — Kanallar ve onay akışı (özet) */}
      {settings && (
        <Card data-slot="tes-channels">
          <CardContent className="flex flex-col gap-4 px-4 sm:px-6">
            <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between sm:gap-4">
              <div className="min-w-0">
                <h4 id="tes-channels-title" className="m-0 flex items-center gap-2 text-base font-semibold">
                  <FileWarning aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" /> {t('te.set.channels')}
                </h4>
                <p className="m-0 mt-1 text-sm text-muted-foreground">{t('te.set.channelsHint')}</p>
              </div>
              <div className="flex shrink-0 flex-wrap items-center gap-2">{refreshButton}</div>
            </div>

            {kpiItems.length > 0 && (
              <div data-slot="tes-kpis">
                <MonitorStatsBar items={kpiItems} activeFilter={filter} className="mb-0"
                  onStatClick={(key) => setFilter(key === filter || key === ALL ? ALL : key)} />
              </div>
            )}

            {channels.length > 0 && (
              <Card data-slot="tes-toolbar" className="py-3 shadow-none">
                <CardContent className="flex flex-col gap-3 px-3 sm:px-4">
                  <div className="flex flex-wrap items-end gap-3">
                    <InputGroup className="w-full sm:w-72 pointer-coarse:h-10">
                      <InputGroupInput type="search" value={q} onChange={(e) => setQ(e.target.value)} data-page-search=""
                        placeholder={t('te.set.search')} aria-label={t('te.set.search')} data-slot="tes-search" className="pointer-coarse:h-10" />
                      <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
                    </InputGroup>
                    {teams.length > 1 && (
                      <div className="flex min-w-0 flex-col gap-1.5 sm:w-72" data-touch-picker="" data-slot="tes-team">
                        <span id="tes-team-label" className="text-xs font-medium text-muted-foreground">{t('te.teamFilter')}</span>
                        <SearchableSelect ariaLabelledBy="tes-team-label" value={team} options={teamSelectOptions} wrapLabels
                          onChange={(v) => setTeam(v == null || v === ALL ? ALL : String(v))} />
                      </div>
                    )}
                    <div className="flex min-w-0 flex-col gap-1.5">
                      <label htmlFor="tes-status" className="text-xs font-medium text-muted-foreground">{t('te.set.filterLabel')}</label>
                      <NativeSelect id="tes-status" value={filter} onChange={(e) => setFilter(normalizeFilter(e.target.value))}
                        className="pointer-coarse:h-10" data-slot="tes-status">
                        <NativeSelectOption value={ALL}>{t('te.set.filter.all')}</NativeSelectOption>
                        <NativeSelectOptGroup label={t('te.set.filterGroup.period')}>
                          {TES_PERIOD_FILTERS.map((f) => <NativeSelectOption key={f} value={f}>{t(`te.set.filter.${f}`)}</NativeSelectOption>)}
                        </NativeSelectOptGroup>
                        <NativeSelectOptGroup label={t('te.set.filterGroup.config')}>
                          {TES_FLAG_FILTERS.map((f) => <NativeSelectOption key={f} value={f}>{t(`te.set.filter.${f}`)}</NativeSelectOption>)}
                        </NativeSelectOptGroup>
                      </NativeSelect>
                    </div>
                    <div className="flex min-w-0 flex-col gap-1.5">
                      <label htmlFor="tes-sort" className="text-xs font-medium text-muted-foreground">{t('te.sort.label')}</label>
                      <NativeSelect id="tes-sort" value={sort} onChange={(e) => setSort(normalizeSort(e.target.value))}
                        className="pointer-coarse:h-10" data-slot="tes-sort">
                        {TES_SORTS.map((s) => <NativeSelectOption key={s} value={s}>{t(`te.sort.${s}`)}</NativeSelectOption>)}
                      </NativeSelect>
                    </div>
                    {anyFilter && (
                      <Button type="button" variant="ghost" onClick={clearFilters} className={TOUCH} data-slot="tes-clear">
                        <X aria-hidden="true" /> {t('te.filters.clear')}
                      </Button>
                    )}
                  </div>
                  <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted-foreground">
                    <span data-slot="tes-count">{t('te.filters.count', visible.length, channels.length)}</span>
                    {period && <span data-slot="tes-period">{t('te.periodNote', pLabel)}</span>}
                  </div>
                </CardContent>
              </Card>
            )}

            {channels.length === 0 ? (
              <div data-slot="tes-empty"><StatusBlock tone="neutral" icon={Radio} title={t('te.set.noChannels')} className="py-8" /></div>
            ) : (
              <div ref={listRef} className="flex min-w-0 flex-col gap-3" data-slot="tes-list" data-layout={cardsLayout ? 'cards' : 'table'}>
                {visible.length === 0 ? (
                  <StatusBlock tone="neutral" icon={Search} title={t('te.noMatchTitle')} description={t('te.noMatchBody')} className="py-8"
                    actions={<Button type="button" variant="outline" onClick={clearFilters} className={TOUCH}><X aria-hidden="true" /> {t('te.filters.clear')}</Button>} />
                ) : cardsLayout ? (
                  // Kart düzeni yalnız dar kapta (< TES_TABLE_MIN_PX) — tek sütun: 768 px tablette kap ≈ 350 px, iki sütun
                  // uzun takım adını 160 px'e sıkıştırıyordu (görünüm alanı değil KAP dar)
                  <div className="grid grid-cols-1 gap-3">
                    {pager.pageItems.map((ch) => <ChannelCard key={ch.id} ch={ch} period={period} onOpen={() => openChannel(ch)} />)}
                  </div>
                ) : (
                  <div className="overflow-hidden rounded-xl border" data-slot="tes-table">
                    {/* Dört sütun — Ayarlar içeriği dar (bkz. TES_TABLE_MIN_PX): kanal adı kanalı AÇAN bağlantı, takım durumu
                        (görünürlük + e-posta) ve onay akışı (yönetici + PO) rozetleri alt alta */}
                    <Table className="table-fixed">
                      <TableHeader>
                        <TableRow>
                          <TableHead className="whitespace-normal">{t('te.col.channel')}</TableHead>
                          <TableHead className="w-28 whitespace-normal">{t('te.set.col.teamState')}</TableHead>
                          <TableHead className="w-32 whitespace-normal">{t('te.set.col.flow')}</TableHead>
                          <TableHead className="w-36 whitespace-normal">{t('te.col.periodReport', pLabel)}</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {pager.pageItems.map((ch) => <ChannelRow key={ch.id} ch={ch} period={period} onOpen={() => openChannel(ch)} />)}
                      </TableBody>
                    </Table>
                  </div>
                )}
                {pager.totalPages > 1 && <PaginationBar {...pager} />}
              </div>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  )
}

const CHIP = 'rounded-md overflow-visible whitespace-normal [overflow-wrap:anywhere]'

/** Ortak satır / kart öznitelikleri: akış (onaylı / takım), dönem durumu, kanal kimliği. */
function rowAttrs(ch) {
  return {
    'data-channel': ch.id,
    'data-flow': hasExecutives(ch) ? 'approval' : 'team',
    'data-status': channelPeriodStatus(ch),
    'data-active': ch.active ? 'true' : 'false',
  }
}

/** Kanal adı = kanalı açan bağlantı (kanal listesi sayfasıyla aynı kalıp); altında takım rozeti (+ pasif çipi). */
function ChannelName({ ch, onOpen }) {
  const t = useT()
  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <Button type="button" variant="link" onClick={onOpen} data-slot="tes-open" aria-label={t('te.set.openChannelFor', ch.name)}
        className={cn('h-auto justify-start p-0 text-left text-base font-semibold leading-snug whitespace-normal text-foreground [overflow-wrap:anywhere]', TOUCH)}>
        <span data-slot="tes-channel-name" className="[overflow-wrap:anywhere]">{ch.name}</span>
      </Button>
      <div className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
        <TeamBadge teamId={ch.team_id} teamName={ch.team_name} static wrap />
        {!ch.active && <Badge variant="outline" className={CHIP}>{t('te.inactive')}</Badge>}
      </div>
    </div>
  )
}

/** Modül görünürlüğü — yalnız açık `false` "kapalı"; alan yoksa "bilinmiyor" (yeşil değil). */
function VisibilityBadge({ ch }) {
  const t = useT()
  const v = teamVisibleState(ch)
  if (v === true) return <ToneBadge tone="success" className={CHIP} data-slot="tes-visibility" data-state="visible">{t('te.set.visible')}</ToneBadge>
  if (v === false) return <ToneBadge tone="warning" className={CHIP} data-slot="tes-visibility" data-state="hidden">{t('te.set.teamHidden')}</ToneBadge>
  return <ToneBadge tone="muted" className={CHIP} data-slot="tes-visibility" data-state="unknown">{t('te.set.visibilityUnknown')}</ToneBadge>
}

/** Onay akışı: yönetici alıcı sayısı + PO onaylayıcı sayısı (metinler eski ekranla aynı). */
function FlowBadges({ ch }) {
  const t = useT()
  const exec = Number(ch.executive_count) || 0
  const po = Number(ch.approver_count) || 0
  return (
    <div className="flex flex-wrap gap-1.5" data-slot="tes-flow">
      <Badge variant={exec > 0 ? 'secondary' : 'outline'} className={CHIP} data-slot="tes-exec">
        {exec > 0 ? `${t('te.set.col.exec')}: ${exec}` : t('te.set.noFlow')}
      </Badge>
      <Badge variant={po > 0 ? 'secondary' : 'outline'} className={CHIP} data-slot="tes-approvers">
        {po > 0 ? `${t('te.set.col.approvers')}: ${po}` : t('te.set.noApprover')}
      </Badge>
    </div>
  )
}

/** Takım e-postası — `true` tanımlı, `false` eksik (rapor gönderilemez), bilinmiyor gri. */
function EmailBadge({ ch }) {
  const t = useT()
  const s = teamEmailState(ch)
  if (s === true) return <ToneBadge tone="success" className={CHIP} data-slot="tes-email" data-state="set">{t('te.set.emailSet')}</ToneBadge>
  if (s === false) return <ToneBadge tone="danger" className={CHIP} data-slot="tes-email" data-state="missing">{t('te.set.emailMissing')}</ToneBadge>
  return <ToneBadge tone="muted" className={CHIP} data-slot="tes-email" data-state="unknown">{t('te.set.emailUnknown')}</ToneBadge>
}

/** Dönem raporu durumu (+ onay durumu) — rapor yoksa "Dönem raporu yok" (yeşil değil). */
function ReportBadges({ ch, period, withPrefix = false }) {
  const t = useT()
  const pr = ch.period_report
  const appr = pr?.approval?.state
  return (
    <div className="flex flex-wrap gap-1.5" data-slot="tes-report">
      {pr ? (
        <StatusBadge status={pr.status || null} sendState={pr.last_send_status} prefix={withPrefix && period ? periodLabel(period, t) : undefined} />
      ) : (
        <Badge variant="outline" className={CHIP}>{t('te.set.noReport')}</Badge>
      )}
      {appr && appr !== 'none' && (
        <Badge variant="outline" className={CHIP} data-slot="tes-approval" data-state={appr}>{t(`te.appr.state.${appr}`)}</Badge>
      )}
    </div>
  )
}

/** Takım durumu: modül görünürlüğü + takım e-postası alt alta (tablo sütunu / kart satırı). */
function TeamStateBadges({ ch }) {
  return (
    <div className="flex flex-wrap gap-1.5" data-slot="tes-team-state">
      <VisibilityBadge ch={ch} />
      <EmailBadge ch={ch} />
    </div>
  )
}

function ChannelRow({ ch, period, onOpen }) {
  return (
    <TableRow data-slot="tes-channel" {...rowAttrs(ch)} className={cn(!ch.active && 'bg-muted/30')}>
      <TableCell className="whitespace-normal align-top"><ChannelName ch={ch} onOpen={onOpen} /></TableCell>
      <TableCell className="whitespace-normal align-top"><TeamStateBadges ch={ch} /></TableCell>
      <TableCell className="whitespace-normal align-top"><FlowBadges ch={ch} /></TableCell>
      <TableCell className="whitespace-normal align-top"><ReportBadges ch={ch} period={period} /></TableCell>
    </TableRow>
  )
}

function ChannelCard({ ch, period, onOpen }) {
  const t = useT()
  return (
    <Card data-slot="tes-channel" {...rowAttrs(ch)} className={cn('gap-3 py-4', !ch.active && 'border-dashed')}>
      <CardContent className="flex flex-col gap-3 px-4">
        <ChannelName ch={ch} onOpen={onOpen} />
        <dl className="m-0 grid grid-cols-1 gap-x-4 gap-y-2 text-sm">
          <dt className="text-xs font-medium text-muted-foreground">{t('te.set.col.teamState')}</dt>
          <dd className="m-0"><TeamStateBadges ch={ch} /></dd>
          <dt className="text-xs font-medium text-muted-foreground">{t('te.set.col.flow')}</dt>
          <dd className="m-0"><FlowBadges ch={ch} /></dd>
          <dt className="text-xs font-medium text-muted-foreground">{t('te.col.periodReport', periodLabel(period, t))}</dt>
          <dd className="m-0"><ReportBadges ch={ch} period={period} /></dd>
        </dl>
        <div className="flex flex-wrap gap-2">
          <Button type="button" size="sm" variant="outline" onClick={onOpen} data-slot="tes-open-btn" className={cn(TOUCH, 'whitespace-normal')}>
            <ExternalLink aria-hidden="true" /> {t('te.set.openChannel')}
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}
