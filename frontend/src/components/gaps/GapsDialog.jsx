import { useMemo, useState } from 'react'
import { ArrowRight, BellRing, CircleCheck, ClipboardList, Database, LayoutGrid, RefreshCw, UserRound, Users } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { navigateTo } from '../../utils/navigate.js'
import ToneBadge from '../admin/ToneBadge.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import { PHONE_FULLSCREEN } from '../ui/modalClasses.js'
import { factText, linkFor } from '../dataquality/dataQualityModel.js'
import { GAP_CATEGORIES, MAX_TEAMS } from './gapsModel.js'
import { Button } from '@/components/shadcn/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/shadcn/dialog'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { cn } from '@/lib/utils'

const WRAP = '[overflow-wrap:anywhere]'
const TOUCH = 'pointer-coarse:min-h-10'
const CATEGORY_ICON = Object.freeze({ profile: UserRound, team: Users, monitors: LayoutGrid, inventory: Database })
const SEVERITY_TONE = Object.freeze({ HIGH: 'danger', MEDIUM: 'warning', LOW: 'muted' })

/**
 * Eksikler penceresi (2026-11-15): dört kategori (Profil, Takım, İzlemeler, Envanter ve veri kalitesi), her eksik için
 * neden önemli olduğu ve düzeltme yerine giden düğme. Alt kısım: "Daha sonra hatırlat" (1 gün), "Bir daha gösterme" (yeni
 * eksik çıkana dek), "Kapat".
 */
export default function GapsDialog({ status, gaps, moreTeams = 0, dqAllowed = true, globalAdmin = false, username, onClose, onSnooze, onDismiss, onRetry }) {
  const t = useT()
  const ready = status === 'ready' && gaps
  const firstWithGaps = useMemo(() => (ready ? GAP_CATEGORIES.find((c) => gaps.byCategory[c].length > 0) : null), [ready, gaps])
  const [tab, setTab] = useState(null)
  const active = tab || firstWithGaps || 'profile'
  const go = (link) => {
    if (!link) return
    onClose()
    navigateTo(link.tab, link.params)
  }
  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose() }}>
      <DialogContent data-slot="gaps-dialog" data-status={status}
        className={cn('flex max-h-[92dvh] flex-col gap-3 overflow-hidden sm:max-w-2xl', PHONE_FULLSCREEN)}>
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2"><ClipboardList aria-hidden="true" className="size-5" />{t('gaps.title')}</DialogTitle>
          <DialogDescription className={WRAP}>{t('gaps.desc')}</DialogDescription>
        </DialogHeader>
        <div className="min-h-0 flex-1 overflow-y-auto" data-slot="gaps-body">
          {!ready ? (
            <LoadingBlock label={t('gaps.loading')} className="py-10" />
          ) : (
            <div className="flex min-w-0 flex-col gap-3">
              {gaps.total === 0 && gaps.failedTeams.length === 0 && !gaps.profileFailed && dqAllowed && (
                <div data-slot="gaps-none">
                  <AlertBanner tone="success" className="mb-0" icon={CircleCheck}>{t('gaps.none')}</AlertBanner>
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
              {moreTeams > 0 && (
                <p data-slot="gaps-more-teams" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>
                  {t('gaps.moreTeams', MAX_TEAMS, moreTeams)}
                </p>
              )}
              <Tabs value={active} onValueChange={setTab} className="min-w-0">
                <TabsList className="grid h-auto w-full grid-cols-2 gap-1 sm:grid-cols-4 group-data-[orientation=horizontal]/tabs:h-auto" data-slot="gaps-tabs">
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
                    <CategoryPanel category={c} list={gaps.byCategory[c]} dqAllowed={dqAllowed} profileFailed={gaps.profileFailed} globalAdmin={globalAdmin}
                      username={username} t={t} onGo={go} />
                  </TabsContent>
                ))}
              </Tabs>
            </div>
          )}
        </div>
        <DialogFooter className="flex-col-reverse gap-2 sm:flex-row sm:justify-between">
          <Button type="button" variant="ghost" className={TOUCH} onClick={onClose} data-slot="gaps-close">{t('gaps.close')}</Button>
          <div className="flex flex-col-reverse gap-2 sm:flex-row">
            <Button type="button" variant="outline" className={TOUCH} onClick={onDismiss} disabled={!ready} title={t('gaps.dismissHint')}
              data-slot="gaps-dismiss">{t('gaps.dismiss')}</Button>
            <Button type="button" className={TOUCH} onClick={onSnooze} title={t('gaps.snoozeHint')} data-slot="gaps-snooze">
              <BellRing aria-hidden="true" />{t('gaps.snooze')}
            </Button>
          </div>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function CategoryPanel({ category, list, dqAllowed, profileFailed, globalAdmin, username, t, onGo }) {
  if (category === 'profile' && profileFailed) {
    return <p data-slot="gaps-profile-failed" className={cn('m-0 text-sm text-muted-foreground', WRAP)}>{t('gaps.profileFailed')}</p>
  }
  if (category !== 'profile' && !dqAllowed) {
    return <p data-slot="gaps-no-permission" className={cn('m-0 text-sm text-muted-foreground', WRAP)}>{t('gaps.noPermission')}</p>
  }
  if (list.length === 0) {
    return <p data-slot="gaps-empty" className={cn('m-0 text-sm text-muted-foreground', WRAP)}>{t('gaps.emptyCategory')}</p>
  }
  return (
    <ul className="m-0 flex list-none flex-col gap-2 p-0">
      {list.map((g) => (category === 'profile'
        ? <ProfileGap key={g.key} g={g} globalAdmin={globalAdmin} username={username} t={t} onGo={onGo} />
        : <TeamGap key={g.key} g={g} t={t} onGo={onGo} />))}
    </ul>
  )
}

function GapHead({ g, title, t }) {
  return (
    <div className="flex min-w-0 flex-wrap items-center gap-1.5">
      <ToneBadge tone={SEVERITY_TONE[g.severity] || 'muted'} className="h-5 shrink-0 px-1.5 text-[11px]" data-severity={g.severity}>
        {t(`dq.severity.${g.severity}`)}
      </ToneBadge>
      <span className={cn('min-w-0 text-sm font-semibold text-foreground', WRAP)}>{title}</span>
    </div>
  )
}

function ProfileGap({ g, globalAdmin, username, t, onGo }) {
  // Profil alanları (e-posta, telefon) yönetici / AD tarafında düzenlenir: global yönetici kendi kaydını açabilir
  const link = globalAdmin && username ? { tab: 'admin', params: { g_tab: 'users', g_q: username } } : null
  return (
    <li data-slot="gaps-item" data-code={g.code} className="flex min-w-0 flex-col gap-1.5 rounded-lg border bg-card px-3 py-2.5">
      <GapHead g={g} title={t(`gaps.profile.${g.code}.title`)} t={t} />
      <p className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t(`gaps.profile.${g.code}.why`)}</p>
      <p className={cn('m-0 text-xs text-foreground', WRAP)}>{t(link ? 'gaps.profile.fixAdmin' : 'gaps.profile.fixAsk')}</p>
      {link && (
        <Button type="button" variant="outline" size="sm" className={cn('self-start', TOUCH)} onClick={() => onGo(link)} data-slot="gaps-fix">
          {t('gaps.openUser')}<ArrowRight aria-hidden="true" />
        </Button>
      )}
    </li>
  )
}

function TeamGap({ g, t, onGo }) {
  const dqLink = { tab: 'dataquality', params: { dq_team: String(g.teamId) } }
  return (
    <li data-slot="gaps-item" data-code={g.code} data-team={g.teamId} className="flex min-w-0 flex-col gap-1.5 rounded-lg border bg-card px-3 py-2.5">
      <GapHead g={g} title={t(`dq.rule.${g.code}.title`)} t={t} />
      <div className="flex min-w-0 flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
        <TeamBadge teamId={g.teamId} teamName={g.teamName || t('gaps.teamUnnamed')} static wrap />
        <span className="tabular-nums">{t('gaps.failing', g.failing)}</span>
      </div>
      <p className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t(`dq.rule.${g.code}.why`)}</p>
      <p className={cn('m-0 text-xs text-foreground', WRAP)}>{t(`dq.rule.${g.code}.fix`)}</p>
      {g.items.length > 0 && (
        <ul className="m-0 flex list-none flex-col gap-1 p-0" data-slot="gaps-subitems">
          {g.items.map((it, i) => {
            const link = linkFor(g.code, it)
            const ft = factText(g.code, it.facts)
            return (
              <li key={`${it.kind}-${it.id ?? it.name ?? i}`} data-slot="gaps-subitem"
                className="flex min-w-0 flex-col gap-1 rounded-md bg-muted/40 px-2 py-1.5 sm:flex-row sm:items-center sm:justify-between">
                <span className="flex min-w-0 flex-col">
                  <span className={cn('text-xs font-medium text-foreground', WRAP)}>{it.name || it.target || t('gaps.unnamed')}</span>
                  {ft && <span className={cn('text-[11px] text-muted-foreground', WRAP)}>{t(ft.key, ...ft.args)}</span>}
                </span>
                {link && (
                  <Button type="button" variant="ghost" size="sm" className={cn('shrink-0 self-start sm:self-auto', TOUCH)}
                    onClick={() => onGo(link)} data-slot="gaps-fix">
                    {t(it.can_edit === false ? 'gaps.view' : 'gaps.fix')}<ArrowRight aria-hidden="true" />
                  </Button>
                )}
              </li>
            )
          })}
        </ul>
      )}
      {(g.more > 0 || g.items.length === 0) && (
        <Button type="button" variant="link" size="sm" className={cn('h-auto self-start px-0', TOUCH)} onClick={() => onGo(dqLink)}
          data-slot="gaps-open-dq">
          {g.more > 0 ? t('gaps.moreItems', g.more) : t('gaps.openDq')}
        </Button>
      )}
    </li>
  )
}
