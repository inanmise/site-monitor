import { BellOff, Clock, Lock, Pencil, SearchCheck, TriangleAlert } from 'lucide-react'
import { formatDateSec } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import CopyButton from '../../ui/CopyButton.jsx'
import HintPopover from '../../ui/HintPopover.jsx'
import { adSoyadInitials, avatarStyleFor } from '../../ui/TeamMemberCards.jsx'
import ToneBadge, { OrgRoleBadge, SystemRoleBadge } from '../ToneBadge.jsx'
import { locksOf, signInState, DORMANT_DAYS } from './userDetailModel.js'
import { SheetTitle } from '@/components/shadcn/sheet'
import { Avatar, AvatarFallback, AvatarImage } from '@/components/shadcn/avatar'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/** Küçük rozet tetiği: görünür boyut aynı, dokunma alanı görünmez ::after ile ~40 px'e genişler (telefon). */
const HIT = "relative after:absolute after:inset-x-0 after:-inset-y-2.5 after:content-['']"

/** Kilit → rozet metni + dokun-gör açıklaması (i18n anahtarları literal: i18n-used-keys kapısı). */
export function lockText(t, key) {
  if (key === 'perm') return { label: t('usr.permLocked'), hint: t('ud.lockHint.perm') }
  if (key === 'role') return { label: t('ud.lock.role'), hint: t('ud.lockHint.role') }
  if (key === 'org') return { label: t('ud.lock.org'), hint: t('ud.lockHint.org') }
  return { label: t('ud.lock.team'), hint: t('ud.lockHint.team') }
}

/** Kullanıcı görseli: AD fotoğrafı (/api/users/{id}/photo — oturum açmış herkese açık), yoksa AD baş harfleri.
 *  Renk kimliği üye kartları ve kullanıcı menüsüyle aynı (TeamMemberCards.avatarStyleFor). */
export function UserAvatar({ user, className }) {
  return (
    <Avatar data-slot="ud-avatar" className={cn('shrink-0 shadow-sm ring-2 ring-background', className)}>
      <AvatarImage src={`/api/users/${user.id}/photo`} alt="" className="object-cover" />
      <AvatarFallback className="font-semibold" style={avatarStyleFor(user.username || user.display_name || String(user.id))}>
        {adSoyadInitials(user)}
      </AvatarFallback>
    </Avatar>
  )
}

/** Hesap durumu rozeti (Kullanıcılar listesiyle aynı: noktalı hap). */
export function AccountBadge({ active }) {
  const t = useT()
  return (
    <Badge variant="outline" data-account={active ? 'active' : 'inactive'}
      className={cn('gap-1.5 rounded-full font-medium', active ? 'border-success/30 bg-success/10 text-success' : 'text-muted-foreground')}>
      <span aria-hidden="true" className={cn('size-1.5 rounded-full', active ? 'bg-success' : 'bg-muted-foreground/60')} />
      {active ? t('usr.active') : t('usr.inactive')}
    </Badge>
  )
}

/**
 * Profil başlığı — büyük avatar · ad soyad (pencere başlığı) · kullanıcı adı (mono, kopyala) · e-posta (mailto, kopyala)
 * · rol / org. rol / hesap durumu / hesap kaynağı / kilit rozetleri (dokununca açıklama) · son giriş satırı
 * (göreli + tam zaman, "hiç girmedi" / "90+ gündür giriş yok" uyarısı) · eylemler (Düzenle, AD denetimi).
 * Telefonda sıkı: e-posta ve son giriş Genel Bakış'ta; eylemler başlığın altında eşit genişlikte.
 */
export default function UserDetailHeader({ user, name, onEdit, onOpenDirectory }) {
  const t = useT()
  const sign = signInState(user)
  const locks = locksOf(user)
  const copyProps = { variant: 'ghost', buttonSize: 'icon-sm', copiedLabel: t('ud.copied'), className: 'text-muted-foreground max-sm:size-10' }
  // Tek varyant çizilir (jsdom medya sorgusu uygulamaz — iki kopya testlerde iki düğme olurdu): telefonda ızgara DOM
  // sırasıyla akar (kimlik → rozetler → son giriş → eylemler), sm+'da eylemler kimliğin sağına yerleşir.
  return (
    <div data-slot="ud-header" className="grid min-w-0 grid-cols-1 gap-3 sm:grid-cols-[minmax(0,1fr)_auto] sm:gap-x-4">
      <div className="flex min-w-0 items-center gap-3 sm:gap-4">
        <UserAvatar user={user} className="size-12 text-base sm:size-16 sm:text-xl" />
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <SheetTitle className="text-lg leading-tight font-semibold [overflow-wrap:anywhere] sm:text-xl">{name}</SheetTitle>
          <div className="flex min-w-0 flex-wrap items-center gap-x-1 text-sm text-muted-foreground">
            <span data-slot="ud-username" className="font-mono break-all">{user.username}</span>
            <CopyButton value={user.username} label={t('ud.copyUsername', user.username)} {...copyProps} />
            {user.email && (
              <span className="hidden min-w-0 items-center gap-x-1 sm:inline-flex">
                <span aria-hidden="true" className="mr-1">·</span>
                <a href={`mailto:${user.email}`} data-slot="ud-email" className="min-w-0 truncate text-foreground underline-offset-4 hover:underline">{user.email}</a>
                <CopyButton value={user.email} label={t('ud.copyEmail', user.email)} {...copyProps} />
              </span>
            )}
          </div>
        </div>
      </div>

      <div data-slot="ud-badges" className="flex flex-wrap items-center gap-1.5 sm:col-span-2">
        <SystemRoleBadge role={user.system_role} />
        {user.org_role && <OrgRoleBadge role={user.org_role}>{t('usr.orgRoleVal.' + user.org_role)}</OrgRoleBadge>}
        <AccountBadge active={!!user.active} />
        <Badge variant="outline" data-auth={user.auth_source === 'LDAP' ? 'ldap' : 'local'} className="font-normal text-muted-foreground">
          {user.auth_source === 'LDAP' ? t('ud.authLdap') : t('usr.authLocal')}
        </Badge>
        {locks.map(({ key, severe }) => {
          const { label, hint } = lockText(t, key)
          return (
            <HintPopover key={key} content={hint} aria-label={t('ud.aboutLock', label)} triggerClassName={HIT}>
              <ToneBadge tone={severe ? 'danger' : 'warning'} data-lock={key} className="gap-1">
                <Lock aria-hidden="true" className="size-3" />{label}
              </ToneBadge>
            </HintPopover>
          )
        })}
        {user.push_opt_out && (
          <HintPopover content={t('usr.pushOptOutTitle')} aria-label={t('ud.aboutLock', t('ud.pushOff'))} triggerClassName={HIT}>
            <ToneBadge tone="muted" data-flag="push-off" className="gap-1"><BellOff aria-hidden="true" className="size-3" />{t('ud.pushOff')}</ToneBadge>
          </HintPopover>
        )}
      </div>

      <div data-slot="ud-signin" className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-sm text-muted-foreground sm:col-span-2">
        <Clock aria-hidden="true" className="size-4 shrink-0" />
        {sign.never ? (
          <ToneBadge tone="warning" data-login="never" className="gap-1"><TriangleAlert aria-hidden="true" className="size-3" />{t('ud.neverSignedIn')}</ToneBadge>
        ) : (
          <>
            <span>{t('ud.lastSignIn')}:</span>
            <strong className="font-semibold text-foreground">{sign.rel ? t(`uact.rel.${sign.rel.unit}`, sign.rel.n) : formatDateSec(user.last_login_at)}</strong>
            <span className="hidden tabular-nums sm:inline">· {formatDateSec(user.last_login_at)}{user.last_login_method ? ` · ${user.last_login_method}` : ''}</span>
            {sign.dormant && (
              <ToneBadge tone="warning" data-login="dormant" className="gap-1"><TriangleAlert aria-hidden="true" className="size-3" />{t('ud.dormant', DORMANT_DAYS)}</ToneBadge>
            )}
          </>
        )}
      </div>

      {(onEdit || onOpenDirectory) && (
        <div data-slot="ud-actions" className="flex w-full gap-2 sm:col-start-2 sm:row-start-1 sm:w-auto sm:self-start">
          {onOpenDirectory && (
            <Button type="button" variant="outline" className="h-10 flex-1 sm:h-9 sm:flex-none" onClick={onOpenDirectory}>
              <SearchCheck aria-hidden="true" />{t('ud.adCheck')}
            </Button>
          )}
          {onEdit && (
            <Button type="button" className="h-10 flex-1 sm:h-9 sm:flex-none" onClick={onEdit}>
              <Pencil aria-hidden="true" />{t('usr.edit')}
            </Button>
          )}
        </div>
      )}
    </div>
  )
}
