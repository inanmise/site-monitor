import { useEffect, useState } from 'react'
import { Users, Mail, ShieldAlert } from 'lucide-react'
import ModalShell from './ModalShell.jsx'
import TeamMemberCards, { adSoyadInitials } from './TeamMemberCards.jsx'
import { LoadingBlock } from './Progress.jsx'
import AlertBanner from './AlertBanner.jsx'
import StatusBlock from './StatusBlock.jsx'
import CopyButton from './CopyButton.jsx'
import { OrgRoleBadge } from '../admin/ToneBadge.jsx'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { Avatar, AvatarFallback } from '@/components/shadcn/avatar'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { cn } from '@/lib/utils'

/** Eskalasyon asgari seviyesi: dolgulu rozet tonu (admin/EscalationContacts ile aynı), etiket anahtarı, sıralama (kritik önce). */
const LEVEL_CLS = { WARNING: 'bg-amber-500 text-white', HIGH: 'bg-orange-600 text-white', CRITICAL: 'bg-red-700 text-white' }
const LEVEL_KEY = { WARNING: 'ec.level.warning', HIGH: 'ec.level.high', CRITICAL: 'ec.level.critical' }
const LEVEL_RANK = { WARNING: 1, HIGH: 2, CRITICAL: 3 }
const initialsOf = (name) => adSoyadInitials({ display_name: name || '' })

/**
 * Takım üyeleri modalı — takım adı tıklanınca her yüzeyden açılır. Varsayılan yükleyici
 * kurum-geneli /api/teams/{id}/members (beyaz-listeli projeksiyon); yönetim ekranı (TeamManager)
 * kendi kapsamlı yükleyicisini ve canManage/onEditUser'ı verir.
 *
 * Üst şerit: lider rozeti, takım e-postası (mailto), üye sayısı. İki sekme: Üyeler / Eskalasyon
 * kişileri (o takımın aktif eskalasyon kontağı — "kime gider?" sorusu tek ekranda).
 * Çizim shadcn Badge (şerit) + Tabs (`line`) + Table (eskalasyon; telefonda yatay kayar).
 */
export default function TeamMembersModal({ team, open, onClose, canManage = false, onEditUser, loadMembers, managerLabelFor, refreshKey = 0 }) {
  const t = useT()
  const [state, setState] = useState({ loading: true, data: null, error: null })
  const [tab, setTab] = useState('members')

  useEffect(() => {
    if (!open || !team?.id) return
    let alive = true
    setState({ loading: true, data: null, error: null })
    const loader = loadMembers || ((id) => api.teams.members(id))
    Promise.resolve()
      .then(() => loader(team.id))
      .then(res => {
        if (!alive) return
        if (res?.success) setState({ loading: false, data: res.data, error: null })
        else setState({ loading: false, data: null, error: res?.error || t('team.membersLoadError') })
      })
      .catch(e => { if (alive) setState({ loading: false, data: null, error: e?.message || t('team.membersLoadError') }) })
    return () => { alive = false }
  }, [open, team?.id, loadMembers, refreshKey, t])

  if (!open || !team) return null
  const info = state.data?.team || team
  const members = state.data?.members || []
  const contacts = state.data?.escalation_contacts || []
  const leaderName = info.leader_display_name
    || members.find(m => info.leader_id != null && Number(m.id) === Number(info.leader_id))?.display_name
    || null

  return (
    <ModalShell open={open} onClose={onClose} title={t('team.membersModalTitle', team.name || info.name || '')} icon={Users} size="lg" scrollBody>
      <div className="mb-2.5 flex flex-wrap items-center gap-2">
        {leaderName && (
          <Badge variant="warning" data-slot="team-leader" className="max-w-full gap-1.5 rounded-full px-2.5 py-[3px] font-normal whitespace-normal">
            <Users aria-hidden="true" /> {t('team.leaderBadge')}: <strong className="font-semibold">{leaderName}</strong>
          </Badge>
        )}
        {info.email && (
          <Badge asChild variant="outline" className="max-w-full gap-1.5 rounded-full bg-muted/50 px-2.5 py-[3px] font-normal">
            <a href={`mailto:${info.email}`}><Mail aria-hidden="true" /> <span className="truncate">{info.email}</span></a>
          </Badge>
        )}
        {!state.loading && (
          <Badge variant="outline" data-slot="team-member-count" className="rounded-full bg-muted/50 px-2.5 py-[3px] font-semibold">
            {t('team.membersCount', members.length)}
          </Badge>
        )}
      </div>
      <Tabs value={tab} onValueChange={setTab} className="gap-3">
        <TabsList variant="line" className="w-full justify-start overflow-x-auto border-b">
          <TabsTrigger value="members" className="flex-none"><Users aria-hidden="true" /> {t('team.tabMembers')}</TabsTrigger>
          <TabsTrigger value="escalation" className="flex-none">
            <ShieldAlert aria-hidden="true" /> {t('team.tabEscalation')}{contacts.length ? ` (${contacts.length})` : ''}
          </TabsTrigger>
        </TabsList>
        {state.error && <AlertBanner tone="danger">{state.error}</AlertBanner>}
        <TabsContent value="members">
          {state.loading ? <LoadingBlock label={t('team.loadingMembers')} size={16} /> : (
            <TeamMemberCards members={members} leaderId={info.leader_id}
              canManage={canManage} onSelect={onEditUser} managerLabelFor={managerLabelFor} />
          )}
        </TabsContent>
        <TabsContent value="escalation">
          {state.loading ? <LoadingBlock label={t('team.loadingMembers')} size={16} />
            : contacts.length === 0 ? <StatusBlock tone="neutral" icon={ShieldAlert} title={t('team.noEscalation')} /> : (
              // Eskalasyon kişileri — shadcn kartlar (2026-09-26, kullanıcı: eski tablo "çok kötü durumda"): baş harf avatarı,
              // ad + kurumsal rol rozeti, asgari seviye DOLGULU rozet + düz cümle, e-posta bağlantısı + kopyala. Telefonda tek sütun.
              <div className="flex flex-col gap-3">
                <p className="text-sm text-muted-foreground">{t('team.escIntro')}</p>
                <ul data-slot="team-escalation-list" className="m-0 grid list-none grid-cols-1 gap-3 p-0 sm:grid-cols-2">
                  {[...contacts].sort((a, b) => (LEVEL_RANK[b.min_alert_level] ?? 0) - (LEVEL_RANK[a.min_alert_level] ?? 0)).map(c => {
                    const level = c.min_alert_level || 'WARNING'
                    const levelLabel = t(LEVEL_KEY[level] || 'ec.level.warning')
                    return (
                      <li key={c.id} className="min-w-0">
                        <Card data-slot="team-escalation-card" data-level={level} className="h-full gap-0 p-3.5 shadow-none">
                          <div className="flex min-w-0 items-start gap-3">
                            <Avatar className="size-10 shrink-0">
                              <AvatarFallback className="bg-primary/10 text-sm font-semibold text-primary">{initialsOf(c.name)}</AvatarFallback>
                            </Avatar>
                            <div className="min-w-0 flex-1">
                              <div className="flex flex-wrap items-center gap-1.5">
                                <strong data-slot="team-escalation-name" className="min-w-0 text-[15px] break-words">{c.name || '—'}</strong>
                                {c.role && <OrgRoleBadge role={c.role}>{t('usr.orgRoleVal.' + c.role)}</OrgRoleBadge>}
                              </div>
                              <div className="mt-1.5 flex flex-wrap items-center gap-1.5 text-[13px]">
                                <Badge data-slot="team-escalation-level" className={cn('font-bold', LEVEL_CLS[level] || 'bg-muted-foreground text-white')}>
                                  {levelLabel}
                                </Badge>
                                <span className="text-muted-foreground">{t('team.escFrom', levelLabel)}</span>
                              </div>
                              {c.email && (
                                <div className="mt-2 flex min-w-0 items-center gap-1.5 text-sm">
                                  <Mail aria-hidden="true" className="size-3.5 shrink-0 text-muted-foreground" />
                                  <a className="min-w-0 truncate text-primary hover:underline" href={`mailto:${c.email}`} title={c.email}>{c.email}</a>
                                  <CopyButton value={c.email} label={t('team.escCopyEmail', c.name || c.email)} buttonSize="icon-xs" variant="ghost" />
                                </div>
                              )}
                            </div>
                          </div>
                        </Card>
                      </li>
                    )
                  })}
                </ul>
              </div>
            )}
        </TabsContent>
      </Tabs>
    </ModalShell>
  )
}
