import { useMemo, useState } from 'react'
import {
  ArrowRight, BellRing, CalendarClock, ChevronDown, CircleCheck, ClipboardCopy, ClipboardList, Database, Download, EyeOff, LayoutGrid,
  ListTodo, Plus, RefreshCw, RotateCcw, Sparkles, UserPlus, UserRound, Users, Wrench,
} from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { applyTabView, navigateTo } from '../../utils/navigate.js'
import { dateLocale } from '../../i18n/dateLocale.js'
import ToneBadge from '../admin/ToneBadge.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import { PHONE_FULLSCREEN } from '../ui/modalClasses.js'
import { bandOf, bandTone, linkFor } from '../dataquality/dataQualityModel.js'
import { GAP_CATEGORIES, SNOOZE_MS, SNOOZE_WEEK_MS, groupByTeam } from './gapsModel.js'
import { CONTACT_CODES, SELF_FIX_CODES } from './gapsProfile.js'
import { PUSH_PREFS_LINK, extraGapLink, extraItemLink, fixKindOf } from './gapsLinks.js'
import { gapItemLine, gapTextKeys, tn } from './gapsText.js'
import { Button } from '@/components/shadcn/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/shadcn/dialog'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { cn } from '@/lib/utils'

const WRAP = '[overflow-wrap:anywhere]'
const TOUCH = 'pointer-coarse:min-h-10'
const CATEGORY_ICON = Object.freeze({ profile: UserRound, team: Users, monitors: LayoutGrid, inventory: Database, tasks: ListTodo })
const SEVERITY_TONE = Object.freeze({ HIGH: 'danger', MEDIUM: 'warning', LOW: 'muted' })
const FIX_ICON = Object.freeze({ contact: UserPlus, group: Users, oncall: CalendarClock })
/** "Sonraki takımlar" düğmesinin bir seferde denetlediği takım sayısı. */
const NEXT_BATCH = 10

/** Şu an açık sekme (adres çubuğundan). */
function currentTab() {
  try { return new URLSearchParams(window.location.search).get('tab') } catch { return null }
}

/**
 * Eksikler penceresi (2026-11-15; zenginleştirme aynı gün): beş kategori (Profil, Takım, İzlemeler, Envanter ve veri kalitesi,
 * Bekleyen işler) ya da takıma göre görünüm; her eksik için neden önemli olduğu, düzeltme yerine giden düğme, yetkili
 * yöneticiye pencereden düzeltme ve "bu eksiği gizle". Üstte özet, Yeni / düzeldi notu, yenileme ve dışa aktarma; altta
 * "Daha sonra hatırlat" (yarın / 1 hafta), "Bir daha gösterme" (yeni eksik çıkana dek), "Kapat".
 */
export default function GapsDialog({
  status, gaps, diff = null, at = null, moreTeams = 0, restTeams = [], loadingMore = false, maxTeams = 10, dq = 'yes',
  globalAdmin = false, username, ask = null, canFix = () => false,
  onClose, onSnooze, onDismiss, onRetry, onRefresh, onLoadMore, onHide, onUnhide, onFix,
}) {
  const t = useT()
  const toast = useToast()
  const ready = status === 'ready' && gaps
  const firstWithGaps = useMemo(() => (ready ? GAP_CATEGORIES.find((c) => gaps.byCategory[c].length > 0) : null), [ready, gaps])
  const [tab, setTab] = useState(null)
  const [view, setView] = useState('category')
  const [exporting, setExporting] = useState(false)
  const active = tab || firstWithGaps || 'profile'
  const newKeys = diff?.newKeys instanceof Set ? diff.newKeys : new Set()
  const high = ready ? GAP_CATEGORIES.reduce((n, c) => n + gaps.byCategory[c].filter((g) => g.severity === 'HIGH').length, 0) : 0
  const newCount = ready ? GAP_CATEGORIES.reduce((n, c) => n + gaps.byCategory[c].filter((g) => newKeys.has(g.key)).length, 0) : 0

  const go = (link) => {
    if (!link) return
    onClose()
    // Hedef sekme zaten açıksa sayfa parametrelerini yalnız bağlanırken okur (Yönetim, Veri Kalitesi): sekme YENİDEN
    // bağlanır (kayıtlı görünüm yolu); başka sekmeye geçişte olağan gezinme
    if (currentTab() === link.tab) applyTabView(link.tab, link.params)
    else navigateTo(link.tab, link.params)
  }
  const doExport = async (kind) => {
    if (!ready || exporting) return
    setExporting(true)
    try {
      const mod = await import('./gapsExport.js')
      const ok = kind === 'copy' ? await mod.copyGaps(gaps, t, { at, ask }) : mod.downloadGapsXlsx(gaps, t, { at })
      if (kind === 'copy') toast[ok ? 'success' : 'error'](t(ok ? 'gaps.export.copied' : 'gaps.export.copyFailed'))
      else if (!ok) toast.error(t('gaps.export.downloadFailed'))
    } catch {
      toast.error(t('gaps.export.failed'))
    } finally {
      setExporting(false)
    }
  }
  const none = ready && gaps.total === 0 && gaps.failedTeams.length === 0 && gaps.failedChecks.length === 0
    && !(gaps.unsupportedChecks?.length > 0) && !gaps.profileFailed
    && gaps.profileKnown && moreTeams === 0 && dq === 'yes'
  const itemProps = { t, onGo: go, newKeys, globalAdmin, username, ask, canFix, onHide, onFix }

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose() }}>
      <DialogContent data-slot="gaps-dialog" data-status={status}
        className={cn('flex max-h-[92dvh] flex-col gap-3 overflow-hidden sm:max-w-3xl', PHONE_FULLSCREEN)}>
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2"><ClipboardList aria-hidden="true" className="size-5" />{t('gaps.title')}</DialogTitle>
          <DialogDescription className={WRAP}>{t('gaps.desc')}</DialogDescription>
        </DialogHeader>
        <div className="min-h-0 flex-1 overflow-y-auto" data-slot="gaps-body">
          {!ready ? (
            <LoadingBlock label={t('gaps.loading')} className="py-10" />
          ) : (
            <div className="flex min-w-0 flex-col gap-3">
              <SummaryBar gaps={gaps} high={high} newCount={newCount} diff={diff} at={at} view={view} setView={setView}
                exporting={exporting} onExport={doExport} onRefresh={onRefresh} t={t} />
              {/* "Tamamlanacak bir şey yok" YALNIZ her şey okunduysa: profil alanları vardı, hiçbir takım / denetim okunmadan
                  kalmadı, denetlenmeyen takım yok ve veri kalitesi yetkisi biliniyor (bilinmeyen ≠ eksik yok) */}
              {none && (
                <div data-slot="gaps-none">
                  <AlertBanner tone="success" className="mb-0" icon={CircleCheck}>
                    {gaps.hiddenList.length > 0 ? tn(t, 'gaps.noneHidden', gaps.hiddenList.length) : t('gaps.none')}
                  </AlertBanner>
                </div>
              )}
              {gaps.failedTeams.length > 0 && (
                <div data-slot="gaps-failed">
                  <AlertBanner tone="warning" className="mb-0"
                    actions={<Button type="button" variant="outline" size="sm" className={TOUCH} onClick={onRetry}><RefreshCw aria-hidden="true" />{t('gaps.retry')}</Button>}>
                    {t('gaps.failedTeams', gaps.failedTeams.length)}
                  </AlertBanner>
                </div>
              )}
              {gaps.failedChecks.length > 0 && (
                <div data-slot="gaps-failed-checks">
                  <AlertBanner tone="warning" className="mb-0"
                    actions={<Button type="button" variant="outline" size="sm" className={TOUCH} onClick={onRetry}><RefreshCw aria-hidden="true" />{t('gaps.retry')}</Button>}>
                    {t('gaps.failedChecks', checkList(gaps.failedChecks, t))}
                  </AlertBanner>
                </div>
              )}
              {gaps.deniedChecks.length > 0 && (
                <p data-slot="gaps-denied" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>
                  {t('gaps.deniedChecks', gaps.deniedChecks.map((c) => t(`gaps.check.${c}`)).join(', '))}
                </p>
              )}
              {gaps.unsupportedChecks?.length > 0 && (
                <p data-slot="gaps-unsupported" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>
                  {t('gaps.unsupportedChecks', gaps.unsupportedChecks.map((c) => t(`gaps.check.${c}`)).join(', '))}
                </p>
              )}
              {moreTeams > 0 && (
                <MoreTeams moreTeams={moreTeams} restTeams={restTeams} loadingMore={loadingMore} maxTeams={maxTeams}
                  onLoadMore={onLoadMore} t={t} />
              )}
              {view === 'team' ? (
                <TeamView gaps={gaps} dq={dq} itemProps={itemProps} t={t} />
              ) : (
                <Tabs value={active} onValueChange={setTab} className="min-w-0">
                  <TabsList className="grid h-auto w-full grid-cols-2 gap-1 sm:grid-cols-3 lg:grid-cols-5 group-data-[orientation=horizontal]/tabs:h-auto" data-slot="gaps-tabs">
                    {GAP_CATEGORIES.map((c) => {
                      const Icon = CATEGORY_ICON[c]
                      const n = gaps.byCategory[c].length
                      return (
                        <TabsTrigger key={c} value={c} data-slot="gaps-tab" data-category={c} data-count={n}
                          className={cn('h-auto min-h-9 gap-1.5 whitespace-normal py-1.5 text-xs', TOUCH)}>
                          <Icon aria-hidden="true" className="size-3.5 shrink-0" />
                          <span className={WRAP}>{t(`gaps.cat.${c}`)}</span>
                          {n > 0 && <ToneBadge tone="warning" className="h-5 shrink-0 px-1.5 text-[11px]">{n}</ToneBadge>}
                        </TabsTrigger>
                      )
                    })}
                  </TabsList>
                  {GAP_CATEGORIES.map((c) => (
                    <TabsContent key={c} value={c} className="mt-3 min-w-0" data-slot="gaps-panel" data-category={c}>
                      <CategoryPanel category={c} list={gaps.byCategory[c]} dq={dq} gaps={gaps} itemProps={itemProps} t={t} />
                    </TabsContent>
                  ))}
                </Tabs>
              )}
              {gaps.hiddenList.length > 0 && <HiddenList list={gaps.hiddenList} onUnhide={onUnhide} t={t} />}
            </div>
          )}
        </div>
        <DialogFooter className="flex-col-reverse gap-2 sm:flex-row sm:justify-between">
          <Button type="button" variant="ghost" className={TOUCH} onClick={onClose} data-slot="gaps-close">{t('gaps.close')}</Button>
          <div className="flex flex-col-reverse gap-2 sm:flex-row">
            <Button type="button" variant="outline" className={TOUCH} onClick={onDismiss} disabled={!ready} title={t('gaps.dismissHint')}
              data-slot="gaps-dismiss">{t('gaps.dismiss')}</Button>
            <div className="flex gap-px" data-slot="gaps-snooze-group">
              <Button type="button" className={cn('flex-1 rounded-r-none', TOUCH)} onClick={() => onSnooze(SNOOZE_MS)} title={t('gaps.snoozeHint')}
                data-slot="gaps-snooze">
                <BellRing aria-hidden="true" />{t('gaps.snooze')}
              </Button>
              <DropdownMenu modal={false}>
                <DropdownMenuTrigger asChild>
                  <Button type="button" size="icon" className={cn('rounded-l-none', TOUCH, 'pointer-coarse:size-10')}
                    aria-label={t('gaps.snoozeChoose')} data-slot="gaps-snooze-more">
                    <ChevronDown aria-hidden="true" />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" className="z-[2100]">
                  <DropdownMenuItem className={TOUCH} onSelect={() => onSnooze(SNOOZE_MS)} data-slot="gaps-snooze-day">{t('gaps.snoozeDay')}</DropdownMenuItem>
                  <DropdownMenuItem className={TOUCH} onSelect={() => onSnooze(SNOOZE_WEEK_MS)} data-slot="gaps-snooze-week">{t('gaps.snoozeWeek')}</DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </div>
          </div>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/** Son denetim saati (İstanbul, SS:dd). */
function clockOf(ms) {
  try {
    return new Intl.DateTimeFormat(dateLocale(), { hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Istanbul' }).format(new Date(ms))
  } catch {
    return ''
  }
}

/** Okunamayan denetimler kısa listesi: "Nöbet çizelgesi (Takım A), Alarm iletimi". */
function checkList(list, t) {
  const seen = new Set()
  const out = []
  for (const f of list) {
    const label = f.teamId != null ? t('gaps.checkOnTeam', t(`gaps.check.${f.check}`), f.teamName || t('gaps.teamUnnamed')) : t(`gaps.check.${f.check}`)
    if (seen.has(label)) continue
    seen.add(label)
    out.push(label)
  }
  return out.join(', ')
}

function SummaryBar({ gaps, high, newCount, diff, at, view, setView, exporting, onExport, onRefresh, t }) {
  return (
    <div className="flex min-w-0 flex-col gap-2 rounded-lg border bg-muted/30 px-3 py-2.5" data-slot="gaps-summary">
      <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1.5">
        <span className={cn('text-sm font-semibold text-foreground', WRAP)} data-slot="gaps-total" data-total={gaps.total}>
          {tn(t, 'gaps.summary.total', gaps.total)}
        </span>
        {high > 0 && <ToneBadge tone="danger" className="h-5 px-1.5 text-[11px]" data-slot="gaps-high">{t('gaps.summary.high', high)}</ToneBadge>}
        {newCount > 0 && (
          <ToneBadge tone="info" className="h-5 gap-1 px-1.5 text-[11px]" data-slot="gaps-new-count">
            <Sparkles aria-hidden="true" className="size-3" />{t('gaps.summary.new', newCount)}
          </ToneBadge>
        )}
        {diff?.fixed > 0 && (
          <span className={cn('text-xs text-success', WRAP)} data-slot="gaps-fixed">{tn(t, 'gaps.summary.fixed', diff.fixed)}</span>
        )}
        {at && <span className="text-xs text-muted-foreground tabular-nums" data-slot="gaps-at">{t('gaps.summary.at', clockOf(at))}</span>}
      </div>
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <SegmentedControl value={view} onChange={setView} ariaLabel={t('gaps.view.label')}
          itemClassName={TOUCH}
          options={[{ value: 'category', label: t('gaps.view.category') }, { value: 'team', label: t('gaps.view.team') }]} />
        <div className="ms-auto flex flex-wrap items-center gap-2">
          <Button type="button" variant="outline" size="sm" className={TOUCH} onClick={onRefresh} data-slot="gaps-refresh">
            <RefreshCw aria-hidden="true" />{t('gaps.refresh')}
          </Button>
          <DropdownMenu modal={false}>
            <DropdownMenuTrigger asChild>
              <Button type="button" variant="outline" size="sm" className={TOUCH} disabled={exporting} data-slot="gaps-export">
                <Download aria-hidden="true" />{t('gaps.export.label')}<ChevronDown aria-hidden="true" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="z-[2100]">
              <DropdownMenuItem className={TOUCH} onSelect={() => onExport('copy')} data-slot="gaps-export-copy">
                <ClipboardCopy aria-hidden="true" />{t('gaps.export.copy')}
              </DropdownMenuItem>
              <DropdownMenuItem className={TOUCH} onSelect={() => onExport('xlsx')} data-slot="gaps-export-xlsx">
                <Download aria-hidden="true" />{t('gaps.export.xlsx')}
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </div>
    </div>
  )
}

function MoreTeams({ moreTeams, restTeams, loadingMore, maxTeams, onLoadMore, t }) {
  const next = restTeams.slice(0, NEXT_BATCH)
  return (
    <div className="flex min-w-0 flex-col gap-2 rounded-lg border px-3 py-2.5" data-slot="gaps-more-teams">
      <p className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t('gaps.moreTeams', maxTeams, moreTeams)}</p>
      {restTeams.length > 0 && (
        <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-center">
          <Button type="button" variant="outline" size="sm" className={cn('self-start', TOUCH)} disabled={loadingMore}
            onClick={() => onLoadMore(next.map((r) => r.id))} data-slot="gaps-more-teams-load">
            <Plus aria-hidden="true" />{tn(t, 'gaps.moreTeamsLoad', next.length)}
          </Button>
          <div className="min-w-0 flex-1 sm:max-w-xs" data-slot="gaps-team-pick">
            <SearchableSelect wrapLabels value="" disabled={loadingMore} searchThreshold={4}
              ariaLabel={t('gaps.teamPick')} placeholder={t('gaps.teamPick')}
              onChange={(v) => { if (v) onLoadMore([v]) }}
              options={restTeams.map((r) => ({ value: String(r.id), label: r.name || t('gaps.teamUnnamed') }))} />
          </div>
        </div>
      )}
      {loadingMore && <p className="m-0 text-xs text-muted-foreground" role="status">{t('gaps.moreTeamsLoading')}</p>}
    </div>
  )
}

function CategoryPanel({ category, list, dq, gaps, itemProps, t }) {
  if (category === 'profile' && gaps.profileFailed) {
    return <p data-slot="gaps-profile-failed" className={cn('m-0 text-sm text-muted-foreground', WRAP)}>{t('gaps.profileFailed')}</p>
  }
  const dqCategory = category === 'team' || category === 'monitors' || category === 'inventory'
  if (dqCategory && category !== 'team' && dq === 'unknown') {
    return <p data-slot="gaps-perm-unknown" className={cn('m-0 text-sm text-muted-foreground', WRAP)}>{t('gaps.permUnknown')}</p>
  }
  if (dqCategory && category !== 'team' && dq !== 'yes') {
    return <p data-slot="gaps-no-permission" className={cn('m-0 text-sm text-muted-foreground', WRAP)}>{t('gaps.noPermission')}</p>
  }
  return (
    <div className="flex min-w-0 flex-col gap-2">
      {category === 'team' && dq === 'unknown' && (
        <p data-slot="gaps-perm-unknown" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t('gaps.permUnknown')}</p>
      )}
      {category === 'team' && dq === 'no' && (
        <p data-slot="gaps-no-permission" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t('gaps.noPermission')}</p>
      )}
      {category === 'profile' && gaps.prefsFailed && (
        <p data-slot="gaps-prefs-failed" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t('gaps.prefsFailed')}</p>
      )}
      {list.length === 0 ? (
        <p data-slot="gaps-empty" className={cn('m-0 text-sm text-muted-foreground', WRAP)}>{t('gaps.emptyCategory')}</p>
      ) : (
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {list.map((g) => <GapItem key={g.key} g={g} {...itemProps} />)}
        </ul>
      )}
    </div>
  )
}

function TeamView({ gaps, dq, itemProps, t }) {
  const groups = groupByTeam(gaps)
  const profile = gaps.byCategory.profile
  return (
    <div className="flex min-w-0 flex-col gap-3" data-slot="gaps-team-view">
      {profile.length > 0 && (
        <section className="flex min-w-0 flex-col gap-2" data-slot="gaps-team-group" data-team="profile" aria-label={t('gaps.cat.profile')}>
          <h3 className="m-0 flex items-center gap-1.5 text-sm font-semibold"><UserRound aria-hidden="true" className="size-4" />{t('gaps.cat.profile')}</h3>
          <ul className="m-0 flex list-none flex-col gap-2 p-0">{profile.map((g) => <GapItem key={g.key} g={g} {...itemProps} />)}</ul>
        </section>
      )}
      {dq !== 'yes' && (
        <p data-slot={dq === 'unknown' ? 'gaps-perm-unknown' : 'gaps-no-permission'} className={cn('m-0 text-xs text-muted-foreground', WRAP)}>
          {t(dq === 'unknown' ? 'gaps.permUnknown' : 'gaps.noPermission')}
        </p>
      )}
      {groups.map((grp) => (
        <section key={grp.teamId ?? 'mine'} className="flex min-w-0 flex-col gap-2" data-slot="gaps-team-group" data-team={grp.teamId ?? 'mine'}
          aria-label={grp.teamId == null ? t('gaps.view.mine') : (grp.teamName || t('gaps.teamUnnamed'))}>
          <div className="flex min-w-0 flex-wrap items-center gap-2">
            {grp.teamId == null ? (
              <h3 className="m-0 flex items-center gap-1.5 text-sm font-semibold"><ListTodo aria-hidden="true" className="size-4" />{t('gaps.view.mine')}</h3>
            ) : (
              <h3 className="m-0 min-w-0 text-sm font-semibold"><TeamBadge teamId={grp.teamId} teamName={grp.teamName || t('gaps.teamUnnamed')} static wrap /></h3>
            )}
            {grp.score && <ScoreBadge score={grp.score} t={t} />}
            <span className="text-xs text-muted-foreground tabular-nums" data-slot="gaps-team-count">{tn(t, 'gaps.teamCount', grp.gaps.length)}</span>
          </div>
          {grp.gaps.length === 0 ? (
            <p className={cn('m-0 text-xs text-muted-foreground', WRAP)} data-slot="gaps-team-clean">{t('gaps.teamClean')}</p>
          ) : (
            <ul className="m-0 flex list-none flex-col gap-2 p-0">
              {grp.gaps.map((g) => <GapItem key={g.key} g={g} showCategory {...itemProps} />)}
            </ul>
          )}
        </section>
      ))}
      {groups.length === 0 && profile.length === 0 && (
        <p data-slot="gaps-empty" className={cn('m-0 text-sm text-muted-foreground', WRAP)}>{t('gaps.emptyCategory')}</p>
      )}
    </div>
  )
}

/** Takım veri kalitesi puanı: puan + bant (+ 7 günlük değişim). Puan yoksa bant "Puan yok" (gri, asla yeşil değil). */
function ScoreBadge({ score, t }) {
  const band = bandOf(score.band)
  const tone = score.score == null ? 'muted' : ({ success: 'success', info: 'info', warning: 'warning', danger: 'danger' }[bandTone(band)] || 'muted')
  return (
    <span className="flex flex-wrap items-center gap-1.5" data-slot="gaps-team-score" data-band={band}>
      <ToneBadge tone={tone} className="h-5 px-1.5 text-[11px] tabular-nums">
        {score.score == null ? t('dq.band.NO_DATA') : t('gaps.score', score.score, t(`dq.band.${band}`))}
      </ToneBadge>
      {score.delta != null && score.delta !== 0 && (
        <span className={cn('text-[11px] tabular-nums', score.delta > 0 ? 'text-success' : 'text-destructive')} data-slot="gaps-team-delta">
          {t('gaps.scoreDelta', score.delta > 0 ? `+${score.delta}` : String(score.delta))}
        </span>
      )}
    </span>
  )
}

function GapHead({ g, title, isNew, t }) {
  return (
    <div className="flex min-w-0 flex-wrap items-center gap-1.5">
      <ToneBadge tone={SEVERITY_TONE[g.severity] || 'muted'} className="h-5 shrink-0 px-1.5 text-[11px]" data-severity={g.severity}>
        {t(`dq.severity.${g.severity}`)}
      </ToneBadge>
      {isNew && (
        <ToneBadge tone="info" className="h-5 shrink-0 gap-1 px-1.5 text-[11px]" data-slot="gaps-new">
          <Sparkles aria-hidden="true" className="size-3" />{t('gaps.new')}
        </ToneBadge>
      )}
      <span className={cn('min-w-0 text-sm font-semibold text-foreground', WRAP)}>{title}</span>
    </div>
  )
}

/** Tek eksik — profil, veri kalitesi kuralı, ek takım eksiği ya da bekleyen iş. */
function GapItem({ g, t, onGo, newKeys, globalAdmin, username, ask, canFix, onHide, onFix, showCategory = false }) {
  const keys = gapTextKeys(g)
  const title = t(keys.title)
  const fixKind = g.teamId != null ? fixKindOf(g.code) : null
  const fixable = fixKind && canFix(g.teamId, fixKind)
  const FixIcon = fixKind ? FIX_ICON[fixKind] : Wrench
  return (
    <li data-slot="gaps-item" data-code={g.code} data-team={g.teamId ?? undefined} data-category={g.category}
      className="flex min-w-0 flex-col gap-1.5 rounded-lg border bg-card px-3 py-2.5">
      <GapHead g={g} title={title} isNew={newKeys.has(g.key)} t={t} />
      {(g.teamId != null || g.failing > 0 || g.points > 0 || showCategory) && g.category !== 'profile' && (
        <div className="flex min-w-0 flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
          {showCategory && <span data-slot="gaps-item-category">{t(`gaps.cat.${g.category}`)}</span>}
          {g.teamId != null && !showCategory && <TeamBadge teamId={g.teamId} teamName={g.teamName || t('gaps.teamUnnamed')} static wrap />}
          <span className="tabular-nums">{t(g.category === 'tasks' ? 'gaps.taskCount' : 'gaps.failing', g.failing)}</span>
          {g.points > 0 && (
            <span className="tabular-nums" data-slot="gaps-points" title={t('gaps.pointsHint')}>{tn(t, 'gaps.points', g.points)}</span>
          )}
        </div>
      )}
      {g.category === 'profile' && Array.isArray(g.reasons) && g.reasons.length > 0 && (
        <div className="flex min-w-0 flex-wrap gap-1" data-slot="gaps-reasons">
          {g.reasons.map((r) => <ToneBadge key={r} tone="muted" className="h-5 px-1.5 text-[11px]">{t(`gaps.reason.${r}`)}</ToneBadge>)}
        </div>
      )}
      <p className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t(keys.why)}</p>
      {g.category === 'profile' ? (
        <ProfileFix g={g} globalAdmin={globalAdmin} username={username} ask={ask} t={t} onGo={onGo} />
      ) : (
        <>
          <p className={cn('m-0 text-xs text-foreground', WRAP)}>{t(keys.fix)}</p>
          {g.items.length > 0 && <SubItems g={g} t={t} onGo={onGo} />}
          <GapLinks g={g} t={t} onGo={onGo} />
        </>
      )}
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        {fixable && (
          <Button type="button" variant="outline" size="sm" className={TOUCH} onClick={() => onFix(fixKind, { id: g.teamId, name: g.teamName })}
            data-slot="gaps-inline-fix" data-kind={fixKind}>
            <FixIcon aria-hidden="true" />{t(`gaps.inlineFix.${fixKind}`)}
          </Button>
        )}
        <Button type="button" variant="ghost" size="sm" className={cn('ms-auto text-muted-foreground', TOUCH)} onClick={() => onHide(g.key)}
          aria-label={t('gaps.hideFor', title)} data-slot="gaps-hide">
          <EyeOff aria-hidden="true" />{t('gaps.hide')}
        </Button>
      </div>
    </li>
  )
}

function ProfileFix({ g, globalAdmin, username, ask, t, onGo }) {
  if (SELF_FIX_CODES.includes(g.code)) {
    return (
      <>
        <p className={cn('m-0 text-xs text-foreground', WRAP)}>{t('gaps.profile.fixSelf')}</p>
        <Button type="button" variant="outline" size="sm" className={cn('self-start', TOUCH)} onClick={() => onGo(PUSH_PREFS_LINK)} data-slot="gaps-fix">
          {t('gaps.openPushPrefs')}<ArrowRight aria-hidden="true" />
        </Button>
      </>
    )
  }
  // Profil alanları (e-posta, telefon) yönetici / AD tarafında düzenlenir: global yönetici kendi kaydını açabilir
  const link = globalAdmin && username ? { tab: 'admin', params: { g_tab: 'users', g_q: username } } : null
  return (
    <>
      <p className={cn('m-0 text-xs text-foreground', WRAP)}>{t(link ? 'gaps.profile.fixAdmin' : 'gaps.profile.fixAsk')}</p>
      {!link && ask && CONTACT_CODES.includes(g.code) && (
        <p className={cn('m-0 text-xs text-foreground', WRAP)} data-slot="gaps-ask">
          {ask.teamName ? t('gaps.askLeader', ask.leaderName, ask.teamName) : t('gaps.askLeaderNoTeam', ask.leaderName)}
        </p>
      )}
      {link && (
        <Button type="button" variant="outline" size="sm" className={cn('self-start', TOUCH)} onClick={() => onGo(link)} data-slot="gaps-fix">
          {t('gaps.openUser')}<ArrowRight aria-hidden="true" />
        </Button>
      )}
    </>
  )
}

function SubItems({ g, t, onGo }) {
  return (
    <ul className="m-0 flex list-none flex-col gap-1 p-0" data-slot="gaps-subitems">
      {g.items.map((it, i) => {
        const link = g.dq ? linkFor(g.code, it) : extraItemLink(g, it)
        const line = gapItemLine(g, it, t)
        return (
          <li key={`${it.kind}-${it.id ?? it.name ?? ''}-${i}`} data-slot="gaps-subitem"
            className="flex min-w-0 flex-col gap-1 rounded-md bg-muted/40 px-2 py-1.5 sm:flex-row sm:items-center sm:justify-between">
            <span className="flex min-w-0 flex-col">
              <span className={cn('text-xs font-medium text-foreground', WRAP)}>{line.name}</span>
              {line.sub && <span className={cn('text-[11px] text-muted-foreground', WRAP)}>{line.sub}</span>}
            </span>
            {link && (
              <Button type="button" variant="ghost" size="sm" className={cn('shrink-0 self-start sm:self-auto', TOUCH)}
                onClick={() => onGo(link)} data-slot="gaps-fix">
                {t(it.can_edit === false ? 'gaps.view' : (g.category === 'tasks' ? 'gaps.open' : 'gaps.fix'))}<ArrowRight aria-hidden="true" />
              </Button>
            )}
          </li>
        )
      })}
    </ul>
  )
}

/** "N kayıt daha" / toplu bağlantı: veri kalitesi kuralı → Veri Kalitesi sayfası; ek eksik / iş → kendi sayfası. */
function GapLinks({ g, t, onGo }) {
  if (g.dq) {
    if (!(g.more > 0 || g.items.length === 0)) return null
    const dqLink = { tab: 'dataquality', params: { dq_team: String(g.teamId) } }
    return (
      <Button type="button" variant="link" size="sm" className={cn('h-auto self-start px-0', TOUCH)} onClick={() => onGo(dqLink)}
        data-slot="gaps-open-dq">
        {g.more > 0 ? t('gaps.moreItems', g.more) : t('gaps.openDq')}
      </Button>
    )
  }
  const link = extraGapLink(g)
  if (!link) return null
  if (!(g.more > 0 || g.items.length === 0 || g.code === 'TEAM_ONCALL_MEMBERS')) return null
  return (
    <Button type="button" variant="link" size="sm" className={cn('h-auto self-start px-0', TOUCH)} onClick={() => onGo(link)}
      data-slot="gaps-open-page">
      {g.more > 0 ? t('gaps.moreItemsPage', g.more) : t(`gaps.code.${g.code}.open`)}
    </Button>
  )
}

function HiddenList({ list, onUnhide, t }) {
  return (
    <Collapsible className="rounded-lg border" data-slot="gaps-hidden">
      <CollapsibleTrigger asChild>
        <Button type="button" variant="ghost" className={cn('h-auto w-full justify-start gap-2 px-3 py-2 text-sm whitespace-normal', TOUCH)}
          data-slot="gaps-hidden-toggle">
          <EyeOff aria-hidden="true" />{t('gaps.hiddenTitle', list.length)}
          <ChevronDown aria-hidden="true" className="ms-auto" />
        </Button>
      </CollapsibleTrigger>
      <CollapsibleContent>
        <ul className="m-0 flex list-none flex-col gap-1 border-t p-2">
          {list.map((g) => {
            const title = t(gapTextKeys(g).title)
            return (
              <li key={g.key} data-slot="gaps-hidden-item" data-code={g.code}
                className="flex min-w-0 flex-col gap-1 rounded-md px-2 py-1.5 sm:flex-row sm:items-center sm:justify-between">
                <span className="flex min-w-0 flex-col">
                  <span className={cn('text-xs font-medium text-foreground', WRAP)}>{title}</span>
                  {g.teamName && <span className={cn('text-[11px] text-muted-foreground', WRAP)}>{g.teamName}</span>}
                </span>
                <Button type="button" variant="outline" size="sm" className={cn('shrink-0 self-start sm:self-auto', TOUCH)}
                  onClick={() => onUnhide(g.key)} aria-label={t('gaps.unhideFor', title)} data-slot="gaps-unhide">
                  <RotateCcw aria-hidden="true" />{t('gaps.unhide')}
                </Button>
              </li>
            )
          })}
        </ul>
      </CollapsibleContent>
    </Collapsible>
  )
}
