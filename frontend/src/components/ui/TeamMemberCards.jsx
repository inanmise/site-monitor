import { useT } from '../../i18n/index.jsx'
import { Avatar, AvatarFallback, AvatarImage } from '@/components/shadcn/avatar'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { OrgRoleBadge, SystemRoleBadge } from '../admin/ToneBadge.jsx'

/**
 * Takım üyesi kartları — takım-üyeleri modalında (her yüzeyden) çizilir. YALNIZ GERÇEK ÜYELER.
 *
 * <p><b>Prod hatası (2026-09-26):</b> eskiden {@code usersById} verilince üyelerden yukarı 2 kademe yönetim zinciri
 * yürünüp müdürler (ve onların müdürleri) üye ızgarasına "Müdür" rozetiyle EKLENİYORDU — takımın üyesi olmayan kişi
 * takımın içinde görünüyor, "N üye" sayacıyla kart sayısı tutmuyordu. Zincir yürüyüşü kaldırıldı; takım müdürü üye
 * listesine değil yönetim ekranındaki Takım Müdürü sütununa aittir (`utils/teamManager.js`). Üyenin müdürü kartında
 * yalnız ALAN olarak ("Müdür: …") görünür.
 *
 * <p>Sıralama: MANAGER → PO → diğer; sonra company_level büyükten küçüğe (tr, sayısal); sonra ad (tr).
 * shadcn Card + Avatar + Badge; yönetici düzenleyebiliyorsa ad gerçek bir düğmedir ve kartın tamamını kaplar
 * (MonitorCard'daki gerilmiş düğme deseni). Telefonda tek sütun.
 */
function computeInitials(name) {
  if (!name) return '?'
  const parts = String(name).trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase()
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
}

const AVATAR_PALETTE = [
  'linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%)',
  'linear-gradient(135deg, #0ea5e9 0%, #06b6d4 100%)',
  'linear-gradient(135deg, #10b981 0%, #14b8a6 100%)',
  'linear-gradient(135deg, #f59e0b 0%, #ef4444 100%)',
  'linear-gradient(135deg, #ec4899 0%, #a855f7 100%)',
]
export function avatarStyleFor(seed) {   // dışa açık (2026-09-27): kullanıcı menüsü aynı renk kimliğini kullanır
  const s = String(seed || '')
  let hash = 0
  for (let i = 0; i < s.length; i++) hash = ((hash << 5) - hash + s.charCodeAt(i)) | 0
  return { background: AVATAR_PALETTE[Math.abs(hash) % AVATAR_PALETTE.length], color: '#fff' }
}

/** Ad + Soyad baş harfleri (AD'den); yoksa display_name'e düşer. Türkçe-uyumlu büyütme. */
export function adSoyadInitials(m) {
  const fn = (m.first_name || '').trim()
  const ln = (m.last_name || '').trim()
  if (fn || ln) {
    const ii = ((fn[0] || '') + (ln[0] || '')).toLocaleUpperCase('tr-TR')
    if (ii) return ii
  }
  return computeInitials(m.display_name || m.username)
}

/** Üye avatarı: LDAP fotoğrafı; yüklenemezse baş harf rozeti (shadcn Avatar kendi düşüşünü yönetir).
 *  /api/users/{id}/photo oturum açmış herkese açık (admin ucu ACCESS_DENIED denetim satırı üretirdi). */
export function MemberAvatar({ m }) {
  return (
    <Avatar className="size-11 shrink-0">
      <AvatarImage src={`/api/users/${m.id}/photo`} alt="" className="object-cover" />
      <AvatarFallback className="text-sm font-semibold" style={avatarStyleFor(m.username || m.display_name || String(m.id))}>
        {adSoyadInitials(m)}
      </AvatarFallback>
    </Avatar>
  )
}

/** Yalnız üyeler, sıralı. (Eski `buildMemberCards(members, usersById)` zincir yürüyüşü KALDIRILDI — bkz. dosya başı.) */
export function sortMembers(members) {
  const rankOf = (m) => (m.org_role === 'MANAGER' ? 0 : (m.org_role === 'PO' ? 1 : 2))
  return [...members].sort((a, b) => {
    if (rankOf(a) !== rankOf(b)) return rankOf(a) - rankOf(b)
    const la = (a.company_level || '').toLowerCase()
    const lb = (b.company_level || '').toLowerCase()
    if (la !== lb) return lb.localeCompare(la, 'tr', { numeric: true })
    return (a.display_name || a.username || '').localeCompare(b.display_name || b.username || '', 'tr')
  })
}

export default function TeamMemberCards({ members = [], leaderId, canManage = false, onSelect, managerLabelFor }) {
  const t = useT()
  if (!members.length) return <p className="text-sm text-muted-foreground">{t('team.noMembers')}</p>
  const mgrLabel = (m) => (managerLabelFor ? managerLabelFor(m) : (m.manager_display_name || null))
  const field = (label, value, extra) => value ? (
    <>
      <dt className="text-muted-foreground">{label}:</dt>
      <dd className="min-w-0 break-words" {...extra}>{value}</dd>
    </>
  ) : null
  // list-none: preflight yok — <ul> madde işaretini kendisi çizer (sayfalama çubuğu "•" hatasıyla aynı sınıf)
  return (
    <ul data-slot="team-member-cards" className="m-0 grid list-none grid-cols-1 gap-3 p-0 sm:grid-cols-2">
      {sortMembers(members).map((m) => {
        const name = m.display_name || m.username
        const isLeader = leaderId != null && Number(leaderId) === Number(m.id)
        return (
          <li key={m.id} className="min-w-0">
            <Card data-slot="team-member-card" data-clickable={canManage ? 'true' : undefined}
              className="relative h-full gap-0 p-3.5 shadow-none transition-colors has-[[data-slot=team-member-open]:hover]:bg-accent/40 motion-reduce:transition-none">
              <div className="flex min-w-0 items-start gap-3">
                <MemberAvatar m={m} />
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-1.5">
                    {canManage ? (
                      // Gerilmiş düğme: ad gerçek bir düğme, ::after ile kartın tamamını tıklanabilir yapar
                      <Button type="button" variant="link" data-slot="team-member-open" title={t('usr.editTitle')}
                        onClick={() => onSelect?.(m)}
                        className="h-auto min-w-0 p-0 text-left text-[15px] font-semibold whitespace-normal text-foreground after:absolute after:inset-0 after:rounded-xl after:content-['']">
                        <span data-slot="team-member-name" className="break-words">{name}</span>
                      </Button>
                    ) : (
                      <strong data-slot="team-member-name" className="min-w-0 text-[15px] break-words">{name}</strong>
                    )}
                    {isLeader && <Badge variant="warning" data-slot="team-member-leader">{t('team.leaderBadge')}</Badge>}
                  </div>
                  <dl className="mt-1.5 grid grid-cols-[auto_minmax(0,1fr)] gap-x-2 gap-y-0.5 text-[12.5px]">
                    {field(t('usr.colUsername'), m.username, { className: 'min-w-0 font-mono break-all' })}
                    {field(t('usr.colEmployeeId'), m.employee_id)}
                    {field(t('usr.colEmail'), m.email, { title: m.email, className: 'min-w-0 break-all' })}
                    {m.system_role && field(t('usr.colRole'), <SystemRoleBadge role={m.system_role} />)}
                    {m.org_role && field(t('usr.colOrgRole'), <OrgRoleBadge role={m.org_role}>{t('usr.orgRoleVal.' + m.org_role)}</OrgRoleBadge>)}
                    {field(t('usr.colTitle'), m.title)}
                    {field(t('usr.colPhone'), m.phone)}
                    {field(t('usr.colDept'), m.department)}
                    {field(t('usr.colMudurluk'), m.mudurluk_name)}
                    {field(t('team.memberManager'), mgrLabel(m))}
                  </dl>
                </div>
              </div>
            </Card>
          </li>
        )
      })}
    </ul>
  )
}
