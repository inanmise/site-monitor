import { useId } from 'react'
import { ExternalLink, Info, PhoneOff, ShieldAlert, UserX } from 'lucide-react'
import AlertBanner from './AlertBanner.jsx'
import StatusBlock from './StatusBlock.jsx'
import { EmailLine, PersonAvatar } from './TeamMemberCards.jsx'
import { OrgRoleBadge } from '../admin/ToneBadge.jsx'
import { groupContactsByLevel } from './teamMembersModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * Takım penceresinin İKİNCİL sekmeleri (2026-09-28 yeniden tasarım):
 *  - {@link EscalationPanel}: takımın aktif eskalasyon kişileri, asgari alarm seviyesine göre GRUPLU (kritik önce).
 *  - {@link CallListPanel}: 7/24 arama listesi (salt okunur, sıralı). Telefon numarası ASLA çizilmez — sunucu da
 *    döndürmez (`has_phone` yalnız bayrak); AD'de telefonu olmayan kişi uyarıyla işaretlenir.
 * Satır içi e-posta/avatar üye listesiyle aynı parçalardan (`TeamMemberCards` → EmailLine, PersonAvatar).
 */

/** Seviye rozeti — dolgulu ton admin/EscalationContacts ile aynı (kalıcı renk kimliği). */
const LEVEL_CLS = { WARNING: 'bg-amber-500 text-white', HIGH: 'bg-orange-600 text-white', CRITICAL: 'bg-red-700 text-white' }
const LEVEL_KEY = { WARNING: 'ec.level.warning', HIGH: 'ec.level.high', CRITICAL: 'ec.level.critical' }

export function LevelBadge({ level, t }) {
  return (
    <Badge data-slot="team-escalation-level" data-level={level} className={cn('font-bold tracking-wide', LEVEL_CLS[level] || 'bg-muted-foreground text-white')}>
      {t(LEVEL_KEY[level] || 'ec.level.warning')}
    </Badge>
  )
}

function EscalationGroup({ group, t, seedFor }) {
  const headId = useId()
  return (
    <section aria-labelledby={headId} data-slot="team-escalation-group" data-level={group.level} className="flex min-w-0 flex-col gap-2">
      <h3 id={headId} className="m-0 flex flex-wrap items-center gap-2 text-sm font-semibold">
        <LevelBadge level={group.level} t={t} />
        <span>{t('team.escScope.' + group.level)}</span>
        <span className="font-normal text-muted-foreground tabular-nums">({group.items.length})</span>
      </h3>
      <ul data-slot="team-escalation-list" className="m-0 grid list-none grid-cols-1 gap-2 p-0 md:grid-cols-2">
        {group.items.map((c) => {
          const name = c.name || c.email || '—'
          return (
            <li key={c.id ?? c.email} data-slot="team-escalation-card" data-level={group.level}
              className="flex min-w-0 items-start gap-3 rounded-lg border bg-card px-3 py-2.5">
              <PersonAvatar name={name} seed={seedFor(c)} className="mt-0.5" />
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-1.5">
                  <span data-slot="team-escalation-name" className="min-w-0 font-medium break-words">{name}</span>
                  {c.role && <OrgRoleBadge role={c.role}>{t('usr.orgRoleVal.' + c.role)}</OrgRoleBadge>}
                </div>
                <EmailLine email={c.email} personName={name} t={t} />
              </div>
            </li>
          )
        })}
      </ul>
    </section>
  )
}

/**
 * @param contacts  `/api/teams/{id}/members` → `escalation_contacts` ({ id, name, email, role, min_alert_level })
 * @param seedFor   kişi → avatar tohumu (üyeyse üyenin tohumu: aynı kişi her sekmede aynı renk)
 */
export function EscalationPanel({ contacts = [], t, seedFor = (c) => c.email || c.name }) {
  const groups = groupContactsByLevel(contacts)
  return (
    <div data-slot="team-escalation" className="flex min-w-0 flex-col gap-4">
      <div className="flex gap-3 rounded-lg border bg-muted/40 px-3 py-2.5 text-sm">
        <Info aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-primary" />
        <div className="min-w-0">
          <p className="m-0 font-medium">{t('team.escWhatTitle')}</p>
          <p className="m-0 text-muted-foreground">{t('team.escWhat')} {t('team.escIntro')}</p>
        </div>
      </div>
      {groups.length === 0
        ? <StatusBlock tone="neutral" icon={ShieldAlert} title={t('team.noEscalation')} className="rounded-lg border border-dashed py-8" />
        : groups.map((g) => <EscalationGroup key={g.level} group={g} t={t} seedFor={seedFor} />)}
    </div>
  )
}

/**
 * 7/24 arama listesi — salt okunur. Düzenleme 7/24 Kapsamı sayfasında (yetkiyi o sayfa ve sunucu belirler; bu
 * pencerenin elinde oturum rolü yok → bağlantı herkese, kimin düzenleyebileceği metinle söylenir).
 *
 * @param rows  `GET /api/noc/teams/{id}/call-list` → [{ user_id, display_name, title, has_phone, is_member }]
 */
export function CallListPanel({ rows = [], t, teamName, seedFor = (r) => r.display_name, onOpenCoverage }) {
  const noPhone = rows.filter((r) => r.has_phone === false)
  return (
    <div data-slot="team-call-list-panel" className="flex min-w-0 flex-col gap-3">
      <p className="m-0 text-sm text-muted-foreground">{t('team.clIntro')}</p>
      {noPhone.length > 0 && (
        <AlertBanner tone="warning" title={t('noc.clNoPhoneTitle', noPhone.map((r) => r.display_name).join(', '))}>
          {t('noc.clNoPhoneBody')}
        </AlertBanner>
      )}
      {rows.length === 0 ? (
        <StatusBlock icon={PhoneOff} title={t('noc.clEmptyTitle', teamName)} description={t('noc.clEmpty')}
          className="rounded-lg border border-dashed py-8" />
      ) : (
        // role="list": list-none Safari/VoiceOver'da liste anlamını düşürür; sıra numarası görsel, anlam <ol>'dan.
        <ol role="list" data-slot="team-call-list" aria-label={t('noc.clListLabel', teamName)}
          className="m-0 list-none divide-y overflow-hidden rounded-lg border bg-card p-0">
          {rows.map((r, i) => (
            <li key={r.user_id ?? i} data-slot="team-call-item" data-user={r.user_id}
              className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1.5 px-3 py-2.5">
              <span aria-hidden="true" className="grid size-7 shrink-0 place-items-center rounded-full bg-primary/10 text-xs font-semibold text-primary tabular-nums">
                {i + 1}
              </span>
              <PersonAvatar name={r.display_name} seed={seedFor(r)} photoId={r.user_id ?? null} />
              <div className="min-w-0 flex-1 basis-40">
                <div className="font-medium break-words">{r.display_name || '—'}</div>
                {r.title && <div className="truncate text-sm text-muted-foreground" title={r.title}>{r.title}</div>}
              </div>
              {(r.has_phone === false || r.is_member === false) && (
                <div className="flex flex-wrap gap-1 max-sm:basis-full max-sm:pl-[5.5rem]">
                  {r.has_phone === false && (
                    <Badge variant="warning" data-slot="team-call-nophone"><PhoneOff aria-hidden="true" /> {t('noc.clNoPhone')}</Badge>
                  )}
                  {r.is_member === false && (
                    <Badge variant="outline" data-slot="team-call-notmember"><UserX aria-hidden="true" /> {t('noc.clNotMember')}</Badge>
                  )}
                </div>
              )}
            </li>
          ))}
        </ol>
      )}
      <div className="flex flex-col items-start gap-2 rounded-lg border border-dashed px-3 py-2.5 sm:flex-row sm:items-center sm:justify-between">
        <p className="m-0 text-xs text-muted-foreground">{t('noc.clReadOnly')}</p>
        <Button type="button" variant="outline" size="sm" data-slot="team-call-open" onClick={onOpenCoverage}
          className="shrink-0 max-sm:h-10 max-sm:w-full pointer-coarse:h-10">
          <ExternalLink aria-hidden="true" /> {t('team.clOpen')}
        </Button>
      </div>
    </div>
  )
}
