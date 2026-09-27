import { GitFork, Lock, UserRoundCheck, Users } from 'lucide-react'
import { formatDateSec } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import TeamBadge from '../../ui/TeamBadge.jsx'
import { MembershipSourceBadge } from '../MembershipSource.jsx'
import { SectionCard, SectionEmpty, SectionError, SectionSkeleton } from './parts.jsx'
import { Badge } from '@/components/shadcn/badge'

/**
 * Takımlar — üyelik başına kart: takım rozeti (üye listesini açar) · birincil · üyelik KAYNAĞI (AD grubu / AD company /
 * elle / takım taşıması / kaynak kaydı yok) · kanıt (grup CN'i, company değeri — dokunmatikte de görünür, ipucuna
 * bırakılmaz) · son yazan · zaman. Veri sunucudan TAZE (`/admin/users/{id}/team-membership`); yüklenemezse hata +
 * yeniden dene ve kullanıcı kaydındaki takımlar yedek olarak listelenir (eski davranış).
 *
 * <p><b>Takım müdürü üye DEĞİLDİR</b> (prod hatası 2026-09-26): bağlı olduğu yönetici ayrı "Yönetim bağı" kartında,
 * üye listesine ve sayısına asla girmez.
 */
export default function TeamsTab({ user, section, teamIds, teamMap }) {
  const t = useT()
  const nameOf = (id, name) => name || teamMap[Number(id)] || `#${id}`
  const data = section.data
  const rows = Array.isArray(data?.memberships) ? data.memberships : null

  return (
    <div data-slot="ud-teams" className="flex min-w-0 flex-col gap-3">
      {section.status === 'error' && <SectionError title={t('ud.errTeams')} error={section.error} onRetry={section.reload} />}
      {(data?.team_locked || (!data && user.team_locked)) && (
        <AlertBanner tone="info" icon={Lock} className="mb-0">{t('mship.locked')}</AlertBanner>
      )}

      <SectionCard icon={Users} title={t('usr.detailTeams')} count={rows ? rows.length : teamIds.length} bodyClassName="@container">
        {section.loading && !rows ? <SectionSkeleton rows={Math.max(2, Math.min(teamIds.length, 4))} /> : rows ? (
          rows.length === 0 ? <SectionEmpty icon={Users} title={t('usr.detailNoTeam')} /> : (
            <ul data-testid="user-team-sources" className="m-0 grid list-none grid-cols-1 gap-2.5 p-0 @2xl:grid-cols-2">
              {rows.map((m) => {
                const written = [m.updated_by, m.updated_at ? formatDateSec(m.updated_at) : null].filter(Boolean).join(' · ')
                return (
                  <li key={m.team_id} data-team-id={m.team_id} data-slot="ud-membership"
                    className="flex min-w-0 flex-col gap-2 rounded-lg border bg-card px-3 py-2.5">
                    <div className="flex min-w-0 flex-wrap items-center gap-1.5">
                      <TeamBadge teamId={m.team_id} teamName={nameOf(m.team_id, m.team_name)} size={13} />
                      {m.primary && <Badge variant="secondary" data-slot="ud-primary" className="font-semibold">{t('ud.primary')}</Badge>}
                      <MembershipSourceBadge source={m.source} detail={m.detail} />
                    </div>
                    {(m.detail || written || m.legacy_primary_only) && (
                      <dl className="m-0 grid grid-cols-[auto_minmax(0,1fr)] gap-x-2 gap-y-0.5 text-xs">
                        {m.detail && <><dt className="text-muted-foreground">{t('ud.sourceEvidence')}</dt><dd className="m-0 min-w-0 font-mono [overflow-wrap:anywhere]">{m.detail}</dd></>}
                        {written && <><dt className="text-muted-foreground">{t('ud.sourceWritten')}</dt><dd className="m-0 min-w-0 tabular-nums [overflow-wrap:anywhere]">{written}</dd></>}
                        {m.legacy_primary_only && <><dt className="text-muted-foreground">{t('ud.note')}</dt><dd className="m-0 min-w-0">{t('mship.legacyPrimary')}</dd></>}
                      </dl>
                    )}
                  </li>
                )
              })}
            </ul>
          )
        ) : (
          // Yedek: üyelik izi yüklenemedi → kullanıcı kaydındaki takımlar (kaynaksız)
          teamIds.length === 0 ? <SectionEmpty icon={Users} title={t('usr.detailNoTeam')} /> : (
            <div className="flex flex-col gap-2">
              <p className="m-0 text-xs text-muted-foreground">{t('ud.membershipFallback')}</p>
              <div className="flex flex-wrap gap-2">
                {teamIds.map((id) => (
                  <span key={id} className="inline-flex items-center gap-1.5">
                    <TeamBadge teamId={id} teamName={nameOf(id)} size={12} />
                    {Number(user.team_id) === id && <Badge variant="secondary">{t('ud.primary')}</Badge>}
                  </span>
                ))}
              </div>
            </div>
          )
        )}
      </SectionCard>

      <SectionCard icon={GitFork} title={t('ud.reportsTo')} description={t('ud.reportsToHint')}>
        {user.manager_sicil ? (
          <div data-slot="ud-line-manager" className="flex min-w-0 items-center gap-2 text-sm">
            <UserRoundCheck aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
            <span className="text-muted-foreground">{t('ud.lineManager')}:</span>
            <span className="font-mono font-semibold">{user.manager_sicil}</span>
          </div>
        ) : <p className="m-0 text-sm text-muted-foreground">{t('ud.noLineManager')}</p>}
      </SectionCard>
    </div>
  )
}
