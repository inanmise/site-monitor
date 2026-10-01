import { useState } from 'react'
import { Building2, Contact, Lock, LockOpen, UserRound } from 'lucide-react'
import { api, formatDateSec } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { useToast } from '../../ui/Toast.jsx'
import CopyButton from '../../ui/CopyButton.jsx'
import TeamBadge from '../../ui/TeamBadge.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { AccountBadge, lockText } from './UserDetailHeader.jsx'
import { Dash, Fact, SectionCard } from './parts.jsx'
import { DORMANT_DAYS, FIELD_LABEL_KEYS, locksOf, signInState } from './userDetailModel.js'
import { Button } from '@/components/shadcn/button'

/** Kilit → UserManager'daki mevcut kilit açma işleyicisinin etiketi (yeni uç YOK; işleyiciler prop'la gelir). */
function unlockLabel(t, key) {
  if (key === 'perm') return t('usr.unlock')
  if (key === 'role') return t('usr.roleUnlock')
  if (key === 'org') return t('usr.orgRoleUnlock')
  return t('usr.teamUnlock')
}

/**
 * Genel Bakış — dört kart: Hesap ve oturum · Kuruluş · İletişim · Kilitler ve AD istisnaları. Kartlar kap genişliğine
 * göre iki sütun (`@container`, tablet ve masaüstü), telefonda tek sütun. Yalnız kullanıcı satırının taşıdığı alanlar
 * (sunucunun döndürmediği alan UYDURULMAZ); boş isteğe bağlı alanlar gizlenir, temel alanlar "—" gösterir.
 *
 * <p>LDAP alan kilitleri (2026-09-30): `locked_field_keys`'teki her alan "Kilitler" kartında kendi satırıyla listelenir
 * (`data-lock="field"` + `data-field`); "AD'ye geri ver" YALNIZ global yöneticide (`globalAdmin`) — sunucu da 403 verir.
 */
export default function OverviewTab({ user, teamName, onUnlock, onUnlocked, globalAdmin = false }) {
  const t = useT()
  const toast = useToast()
  const sign = signInState(user)
  const locks = locksOf(user)
  const [busy, setBusy] = useState(null)
  const copyProps = { variant: 'ghost', buttonSize: 'icon-xs', copiedLabel: t('ud.copied'), className: 'text-muted-foreground max-sm:size-10' }
  const failed = Number(user.failed_before_login ?? 0)
  const org = [
    user.title && <Fact key="title" label={t('usr.colTitle')}>{user.title}</Fact>,
    user.department && <Fact key="dept" label={t('usr.colDept')}>{user.department}</Fact>,
    user.mudurluk_name && <Fact key="mud" label={t('usr.colMudurluk')} wide>{user.mudurluk_name}</Fact>,
    user.company_level && <Fact key="lvl" label={t('usr.formCompanyLevel')}>{user.company_level}</Fact>,
    user.manager_sicil && <Fact key="mgr" label={t('ud.lineManager')}><span className="font-mono">{user.manager_sicil}</span></Fact>,
  ].filter(Boolean)

  async function unlock(key) {
    const fn = onUnlock?.[key]
    if (!fn || busy) return
    setBusy(key)
    try { await fn(user.id); onUnlocked?.() } finally { setBusy(null) }
  }

  /** LDAP alan kilidi: sunucu ucu doğrudan (UserManager işleyicisi yok); başarıda satır + geçmiş tazelenir. */
  async function unlockField(field) {
    if (busy) return
    setBusy(`field:${field}`)
    try {
      const r = await api.admin.unlockUserField(user.id, field)
      if (r?.success) { toast.success(t('usr.fieldUnlocked')); onUnlocked?.() }
      else toast.error(r?.error || 'Error')
    } catch (e) {
      toast.error(e?.message || 'Error')
    } finally {
      setBusy(null)
    }
  }

  // İki sütun yalnız kap yeterince genişken (≥ 48rem: masaüstü yan panel); iki bağımsız sütun → kısa kart uzun kartın
  // yanında boşluk bırakmaz. Telefonda / tablette tek sütun: Hesap → Kilitler (eylemli) → İletişim → Kuruluş.
  return (
    <div data-slot="ud-overview" className="@container">
      <div className="grid grid-cols-1 items-start gap-3 @3xl:grid-cols-2">
        <div className="flex min-w-0 flex-col gap-3">
          <SectionCard icon={UserRound} title={t('ud.secAccount')}>
            <dl className="m-0 grid grid-cols-1 gap-x-4 gap-y-3 @xs:grid-cols-2">
              <Fact label={t('ud.status')}><AccountBadge active={!!user.active} /></Fact>
              <Fact label={t('usr.authSourceTitle')}>{user.auth_source === 'LDAP' ? t('ud.authLdap') : t('usr.authLocal')}</Fact>
              <Fact label={t('ud.lastSignIn')} wide>
                {sign.never ? <ToneBadge tone="warning" data-login="never">{t('ud.neverSignedIn')}</ToneBadge> : (
                  <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
                    {sign.rel && <strong className="font-semibold">{t(`uact.rel.${sign.rel.unit}`, sign.rel.n)}</strong>}
                    <span className="text-muted-foreground tabular-nums">{formatDateSec(user.last_login_at)}{user.last_login_method ? ` · ${user.last_login_method}` : ''}</span>
                    {sign.dormant && <ToneBadge tone="warning" data-login="dormant">{t('ud.dormant', DORMANT_DAYS)}</ToneBadge>}
                  </span>
                )}
              </Fact>
              {user.prev_login_at && (
                <Fact label={t('ud.prevSignIn')} wide>
                  <span className="tabular-nums">{formatDateSec(user.prev_login_at)}{user.prev_login_method ? ` · ${user.prev_login_method}` : ''}</span>
                </Fact>
              )}
              {failed > 0 && (
                <Fact label={t('ud.failedBefore')} wide><ToneBadge tone="warning" className="tabular-nums">{failed}</ToneBadge></Fact>
              )}
              <Fact label={t('ud.created')}><span className="tabular-nums">{user.created_at ? formatDateSec(user.created_at) : <Dash />}</span></Fact>
              <Fact label={t('ud.updated')}><span className="tabular-nums">{user.updated_at ? formatDateSec(user.updated_at) : <Dash />}</span></Fact>
            </dl>
          </SectionCard>

          <SectionCard icon={Lock} title={t('ud.secLocks')} count={locks.length || null}>
            {locks.length === 0 ? (
              <p data-slot="ud-no-locks" className="m-0 text-sm text-muted-foreground">{t('ud.noLocks')}</p>
            ) : (
              <ul data-slot="ud-locks" className="m-0 flex list-none flex-col gap-2.5 p-0">
                {locks.map(({ key, field, severe }) => {
                  const isField = !!field
                  const { label, hint } = isField
                    ? { label: t('ud.lock.field', t(FIELD_LABEL_KEYS[field])), hint: t('ud.lockHint.field') }
                    : lockText(t, key)
                  const can = isField ? !!globalAdmin : !!onUnlock?.[key]
                  return (
                    <li key={key} data-lock={isField ? 'field' : key} data-field={field || undefined}
                      className="flex min-w-0 flex-col gap-2 rounded-lg border px-3 py-2.5">
                      <div className="flex min-w-0 flex-1 flex-col gap-1">
                        <ToneBadge tone={severe ? 'danger' : 'warning'} className="gap-1 self-start"><Lock aria-hidden="true" className="size-3" />{label}</ToneBadge>
                        <span className="text-xs leading-relaxed text-muted-foreground">{hint}</span>
                      </div>
                      {can && (
                        <Button type="button" variant="outline" size="sm" className="h-10 self-start sm:h-8" disabled={busy != null}
                          aria-busy={busy === key || undefined} onClick={() => (isField ? unlockField(field) : unlock(key))}>
                          {busy === key ? <Spinner decorative size={14} /> : <LockOpen aria-hidden="true" />}
                          {isField ? t('usr.fieldUnlock') : unlockLabel(t, key)}
                        </Button>
                      )}
                    </li>
                  )
                })}
              </ul>
            )}
          </SectionCard>
        </div>
        <div className="flex min-w-0 flex-col gap-3">
          <SectionCard icon={Contact} title={t('ud.secContact')}>
            <dl className="m-0 grid grid-cols-1 gap-x-4 gap-y-3 @xs:grid-cols-2">
              <Fact label={t('usr.colEmail')} wide>
                {user.email ? (
                  <span className="inline-flex min-w-0 max-w-full items-center gap-1">
                    <a href={`mailto:${user.email}`} className="min-w-0 break-all text-primary underline-offset-4 hover:underline max-sm:inline-flex max-sm:min-h-10 max-sm:items-center">{user.email}</a>
                    <CopyButton value={user.email} label={t('ud.copyEmail', user.email)} {...copyProps} />
                  </span>
                ) : <Dash />}
              </Fact>
              <Fact label={t('usr.colEmployeeId')}>
                {user.employee_id ? (
                  <span className="inline-flex items-center gap-1">
                    <span className="font-mono">{user.employee_id}</span>
                    <CopyButton value={user.employee_id} label={t('ud.copyEmployeeId', user.employee_id)} {...copyProps} />
                  </span>
                ) : <Dash />}
              </Fact>
              {user.phone && (
                <Fact label={t('usr.colPhone')}>
                  <a href={`tel:${String(user.phone).replace(/\s+/g, '')}`} className="text-primary tabular-nums underline-offset-4 hover:underline max-sm:inline-flex max-sm:min-h-10 max-sm:items-center">{user.phone}</a>
                </Fact>
              )}
            </dl>
          </SectionCard>

          <SectionCard icon={Building2} title={t('ud.secOrg')}>
            <dl className="m-0 grid grid-cols-1 gap-x-4 gap-y-3 @xs:grid-cols-2">
              <Fact label={t('ud.primaryTeam')} wide>
                {user.team_id != null ? <TeamBadge teamId={user.team_id} teamName={teamName || `#${user.team_id}`} size={12} /> : <Dash />}
              </Fact>
              {org}
            </dl>
            {org.length === 0 && <p className="m-0 text-xs text-muted-foreground">{t('ud.orgEmpty')}</p>}
          </SectionCard>
        </div>
      </div>
    </div>
  )
}
