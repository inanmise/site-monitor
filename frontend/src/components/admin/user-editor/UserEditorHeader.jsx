import { BellOff, CalendarPlus, Clock, KeyRound, Lock, ShieldAlert, UserPlus, UserX } from 'lucide-react'
import { formatDateSec } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import ToneBadge, { OrgRoleBadge, SystemRoleBadge } from '../ToneBadge.jsx'
import { AccountBadge, UserAvatar } from '../userdetail/UserDetailHeader.jsx'
import { DORMANT_DAYS, fullNameOf, signInState } from '../userdetail/userDetailModel.js'
import { Badge } from '@/components/shadcn/badge'

/**
 * Düzenleyici başlık özeti (2026-10-02, kullanıcı isteği) — kimin düzenlendiği ilk bakışta: fotoğraf (AD; yoksa baş
 * harfler — `UserAvatar`, kullanıcı detayıyla aynı renk kimliği), ad + kullanıcı adı / e-posta, KAYITLI durumun rozetleri
 * (sistem rolü, org. rolü, Aktif / Pasif — pasif takım listelerindeki gibi yıkıcı rozet —, hesap kaynağı, tek ADMIN,
 * kalıcı / süreli giriş kilidi, şifre değişimi bekliyor, kilitli AD alanı sayısı, push kapalı) ve son giriş / oluşturulma.
 * Formdaki kaydedilmemiş değerler burada GÖSTERİLMEZ (değişiklik özeti altlıkta) — başlık "şu an ne kayıtlı" der.
 * Yeni kullanıcıda: ikon + canlı ad önizlemesi + zorunlu alan notu.
 */
export default function UserEditorHeader({ mode, user, form, guards, lock = null, lockedFieldCount = 0 }) {
  const t = useT()
  if (mode === 'add') {
    const preview = (form?.display_name || '').trim() || (form?.username || '').trim()
    return (
      <div data-slot="ued-header" data-mode="add" className="flex min-w-0 items-center gap-3 rounded-xl border bg-muted/40 p-3 sm:p-4">
        <span aria-hidden="true" className="grid size-12 shrink-0 place-items-center rounded-full bg-primary/10 text-primary">
          <UserPlus className="size-5" />
        </span>
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <span className="text-base leading-tight font-semibold [overflow-wrap:anywhere]">{preview || t('ued.newUser')}</span>
          <span className="text-xs text-muted-foreground">{t('ued.newUserHint')}</span>
        </div>
      </div>
    )
  }
  if (!user) return null
  const name = fullNameOf(user)
  const sign = signInState(user)
  return (
    <div data-slot="ued-header" data-mode={mode} className="flex min-w-0 items-start gap-3 rounded-xl border bg-muted/40 p-3 sm:gap-4 sm:p-4">
      <UserAvatar user={user} className="size-11 text-base sm:size-14 sm:text-lg" />
      <div className="flex min-w-0 flex-1 flex-col gap-1.5">
        <div className="flex min-w-0 flex-col gap-0.5">
          <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
            <span data-slot="ued-name" className="text-base leading-tight font-semibold [overflow-wrap:anywhere] sm:text-lg">{name}</span>
            {guards?.self && <Badge variant="secondary" data-flag="self">{t('ued.you')}</Badge>}
          </span>
          <span className="flex min-w-0 flex-wrap items-center gap-x-1.5 text-xs text-muted-foreground">
            <span data-slot="ued-username" className="font-mono break-all">{user.username}</span>
            {user.email && <><span aria-hidden="true">·</span><span className="min-w-0 break-all">{user.email}</span></>}
          </span>
        </div>
        <div data-slot="ued-badges" className="flex flex-wrap items-center gap-1">
          <SystemRoleBadge role={user.system_role} />
          {user.org_role && <OrgRoleBadge role={user.org_role}>{t('usr.orgRoleVal.' + user.org_role)}</OrgRoleBadge>}
          {user.active === false ? (
            <Badge variant="destructive" data-account="inactive" className="gap-1"><UserX aria-hidden="true" />{t('usr.inactive')}</Badge>
          ) : <AccountBadge active />}
          <Badge variant="outline" data-auth={user.auth_source === 'LDAP' ? 'ldap' : 'local'} className="font-normal text-muted-foreground">
            {user.auth_source === 'LDAP' ? t('ud.authLdap') : t('usr.authLocal')}
          </Badge>
          {guards?.lastAdmin && <ToneBadge tone="danger" data-flag="last-admin" title={t('usr.lastAdminTitle')}>{t('usr.lastAdminBadge')}</ToneBadge>}
          {lock?.kind === 'perm' && (
            <ToneBadge tone="danger" data-lock="perm" className="gap-1"><Lock aria-hidden="true" className="size-3" />{t('usr.permLocked')}</ToneBadge>
          )}
          {lock?.kind === 'temp' && (
            <ToneBadge tone="warning" data-lock="temp" className="gap-1"><ShieldAlert aria-hidden="true" className="size-3" />{t('ued.lockedTemp')}</ToneBadge>
          )}
          {user.must_change_password && (
            <ToneBadge tone="warning" data-flag="must-change" className="gap-1"><KeyRound aria-hidden="true" className="size-3" />{t('ued.mustChangeBadge')}</ToneBadge>
          )}
          {lockedFieldCount > 0 && (
            <ToneBadge tone="warning" data-lock="fields" data-count={lockedFieldCount} className="gap-1">
              <Lock aria-hidden="true" className="size-3" />{t('ud.lock.fields', lockedFieldCount)}
            </ToneBadge>
          )}
          {user.push_opt_out && (
            <ToneBadge tone="muted" data-flag="push-off" className="gap-1"><BellOff aria-hidden="true" className="size-3" />{t('ud.pushOff')}</ToneBadge>
          )}
        </div>
        <div data-slot="ued-meta" className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
          <span className="inline-flex min-w-0 flex-wrap items-center gap-1">
            <Clock aria-hidden="true" className="size-3.5 shrink-0" />
            {sign.never ? (
              <span data-login="never">{t('ud.neverSignedIn')}</span>
            ) : (
              <>
                <span>{t('ud.lastSignIn')}:</span>
                <span className="font-medium text-foreground" title={formatDateSec(user.last_login_at)}>
                  {sign.rel ? t(`uact.rel.${sign.rel.unit}`, sign.rel.n) : formatDateSec(user.last_login_at)}
                </span>
                {sign.dormant && <ToneBadge tone="warning" data-login="dormant">{t('ud.dormant', DORMANT_DAYS)}</ToneBadge>}
              </>
            )}
          </span>
          {/* Oluşturulma telefonda gizli: başlık kısa kalsın, sekmeler ilk ekranda görünsün */}
          {user.created_at && (
            <span className="hidden items-center gap-1 sm:inline-flex">
              <CalendarPlus aria-hidden="true" className="size-3.5 shrink-0" />
              {t('ud.created')}: <span className="tabular-nums">{formatDateSec(user.created_at)}</span>
            </span>
          )}
        </div>
      </div>
    </div>
  )
}
