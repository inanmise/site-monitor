import { useId, useState } from 'react'
import { Building2, Eye, EyeOff, IdCard, Lock, LockOpen, SearchCheck, ShieldCheck, Star, UserRound, X } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import Field from '../../ui/Field.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import MultiTeamSelect from '../../ui/MultiTeamSelect.jsx'
import TeamBadge from '../../ui/TeamBadge.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { FORM_LOCK_KEY, ORG_ROLES, makePrimary, predictedPrimary, roleOptionsFor } from './userEditorModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { Switch } from '@/components/shadcn/switch'
import {
  Field as ShField, FieldContent, FieldDescription, FieldLabel, FieldLegend, FieldSet, FieldTitle,
} from '@/components/shadcn/field'
import { cn } from '@/lib/utils'

/**
 * Kullanıcı düzenleyicinin form sekmeleri (2026-10-02, kullanıcı isteği) — Hesap · Takımlar · Profil. Her sekme `ed`
 * (düzenleyici durumu + eylemleri, `UserEditor` kurar) okur; alan hataları `ui/Field` altında (useFormErrors),
 * `data-field` kancasıyla ilk hatalı alana odak. Telefonda tek sütun, `sm`'den itibaren iki sütun.
 */

const GRID = 'grid grid-cols-1 items-start gap-x-4 sm:grid-cols-2'
const LEGEND = 'mb-2 flex items-center gap-1.5 text-[13px] font-semibold text-foreground'
/** Hint satırındaki küçük eylem düğmesi: masaüstünde sıkı, dokunmatikte 40 px hedef. */
const HINT_BTN = 'h-7 gap-1 px-2 text-xs pointer-coarse:h-10 max-sm:h-10'

/** İpucu satırları — birden çok bilgi (kilit + koruma) tek `FieldDescription` içinde alt alta. */
function hintStack(items) {
  const list = items.filter(Boolean)
  if (list.length === 0) return undefined
  if (list.length === 1) return list[0]
  return <span className="flex flex-col gap-1">{list.map((x, i) => <span key={i} className="block">{x}</span>)}</span>
}

/**
 * Kayıtlı bir AD istisnası (rol / org. rol / takım kilidi): rozet + anlamı + (yetki varsa) "AD'ye geri ver".
 * Kilit açma ANINDA uygulanır (mevcut uçlar), kayıt düğmesini beklemez.
 */
function LockNote({ ed, kind, label, hint, releaseLabel, fieldLabel }) {
  const t = useT()
  const canRelease = ed.canManage && ed.editable
  const busy = ed.busy === kind
  return (
    <span data-slot="ued-lock-note" data-kind={kind} className="inline-flex flex-wrap items-center gap-1.5">
      <Badge variant="warning" data-lock={kind} className="gap-1 px-1.5 py-0 text-[11px]"><Lock aria-hidden="true" className="size-3" />{label}</Badge>
      <span>{hint}</span>
      {canRelease && (
        <Button type="button" variant="outline" size="xs" className={HINT_BTN} disabled={ed.busy != null}
          aria-busy={busy || undefined} aria-label={t('a11y.rowAction', fieldLabel, releaseLabel)} onClick={() => ed.releaseLock(kind)}>
          {busy ? <Spinner decorative size={12} /> : <LockOpen aria-hidden="true" />}{releaseLabel}
        </Button>
      )}
    </span>
  )
}

/**
 * AD alanı ipucu (LDAP hesabı): kilitsizse "AD'den gelir — düzenlersen kilitlenir", kilitliyse "Kilitli" rozeti +
 * "elle düzenlendi, AD ezmez" (+ global yöneticiye "AD'ye geri ver" — `POST /admin/users/{id}/field-unlock`).
 * Eski formdaki `adHint` ile aynı metin ve kancalar (`data-lock="field"` + `data-field`).
 */
function adHint(ed, t, formKey) {
  const lockKey = FORM_LOCK_KEY[formKey]
  if (!ed.ldap || !lockKey) return undefined
  if (!ed.lockedKeys.has(lockKey)) return t('usr.fieldFromAdHint')
  const busy = ed.busy === `field:${lockKey}`
  return (
    <span className="inline-flex flex-wrap items-center gap-1.5">
      <Badge variant="warning" data-lock="field" data-field={lockKey} className="gap-1 px-1.5 py-0 text-[11px]">
        <Lock aria-hidden="true" className="size-3" />{t('usr.fieldLockedBadge')}
      </Badge>
      {t('usr.fieldLockedHint')}
      {ed.globalAdmin && ed.editable && (
        <Button type="button" variant="outline" size="xs" className={HINT_BTN} disabled={ed.busy != null} aria-busy={busy || undefined}
          data-slot="ued-field-unlock" data-field={lockKey}
          aria-label={t('a11y.rowAction', t(labelKeyOf(formKey)), t('usr.fieldUnlock'))} onClick={() => ed.releaseField(lockKey)}>
          {busy ? <Spinner decorative size={12} /> : <LockOpen aria-hidden="true" />}{t('usr.fieldUnlock')}
        </Button>
      )}
    </span>
  )
}

const LABEL_KEY = {
  display_name: 'usr.formDisplay', email: 'usr.formEmail', employee_id: 'usr.formEmployeeId', first_name: 'usr.formFirstName',
  last_name: 'usr.formLastName', title: 'usr.colTitle', phone: 'usr.colPhone', department: 'usr.colDept',
  company_level: 'usr.formCompanyLevel', mudurluk_name: 'usr.colMudurluk', manager_sicil: 'usr.colManager',
}
const labelKeyOf = (k) => LABEL_KEY[k] || k

/** Düz metin alanı (AD ipucuyla). */
function TextField({ ed, k, type = 'text', inputMode, autoComplete }) {
  const t = useT()
  const locked = ed.ldap && ed.lockedKeys.has(FORM_LOCK_KEY[k])
  return (
    <Field label={t(labelKeyOf(k))} hint={adHint(ed, t, k)} hintTone={locked ? 'warn' : undefined} {...ed.fe.fieldProps(k)}>
      {({ id, describedBy, invalid }) => (
        <Input id={id} type={type} inputMode={inputMode} autoComplete={autoComplete} aria-describedby={describedBy} aria-invalid={invalid}
          value={ed.form[k] ?? ''} disabled={!ed.editable} onChange={(e) => ed.set(k, e.target.value)} />
      )}
    </Field>
  )
}

/** Hesap sekmesi — kimlik (kullanıcı adı, parola, e-posta, sicil), erişim (sistem / org. rolü), hesap durumu (aktiflik). */
export function AccountSection({ ed }) {
  const t = useT()
  const [showPwd, setShowPwd] = useState(false)
  const activeId = useId()
  const { form, guards, isAdd, editable, user } = ed
  const roleLocked = !isAdd && !!user?.role_locked && !ed.released.has('role')
  const orgLocked = !isAdd && !!user?.org_role_locked && !ed.released.has('org')
  const roleHint = hintStack([
    guards.self && t('usr.selfRoleLocked'),
    !guards.self && guards.lastAdmin && t('usr.lastAdminRoleLocked'),
    roleLocked && <LockNote ed={ed} kind="role" label={t('ud.lock.role')} hint={t('ud.lockHint.role')} releaseLabel={t('usr.roleUnlock')} fieldLabel={t('usr.formRole')} />,
  ])
  const orgHint = orgLocked
    ? <LockNote ed={ed} kind="org" label={t('ud.lock.org')} hint={t('ud.lockHint.org')} releaseLabel={t('usr.orgRoleUnlock')} fieldLabel={t('usr.orgRole')} />
    : undefined
  const activeLocked = !editable || guards.locked
  const wasActive = user?.active !== false
  return (
    <div data-slot="ued-account" className="flex min-w-0 flex-col gap-4">
      <FieldSet className="min-w-0 gap-0">
        <FieldLegend variant="label" className={LEGEND}><IdCard aria-hidden="true" className="size-4 text-muted-foreground" />{t('ued.secIdentity')}</FieldLegend>
        <div className={GRID}>
          <Field label={t('usr.formUsername')} required={isAdd} hint={isAdd ? t('ued.usernameHint') : t('ued.usernameFixed')} {...ed.fe.fieldProps('username')}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} value={form.username} disabled={!isAdd}
                autoFocus={isAdd} autoComplete="off" autoCapitalize="characters" spellCheck={false}
                onChange={(e) => ed.set('username', e.target.value)} />
            )}
          </Field>
          {isAdd && (
            <Field label={t('usr.formPassword')} required hint={t('ued.passwordHint')} {...ed.fe.fieldProps('password')}>
              {({ id, describedBy, invalid }) => (
                <InputGroup className="max-sm:h-10">
                  <InputGroupInput id={id} type={showPwd ? 'text' : 'password'} aria-describedby={describedBy} aria-invalid={invalid}
                    value={form.password} autoComplete="new-password" onChange={(e) => ed.set('password', e.target.value)} />
                  <InputGroupAddon align="inline-end">
                    <InputGroupButton size="icon-sm" className="max-sm:size-9" aria-pressed={showPwd}
                      aria-label={showPwd ? t('ued.passwordHide') : t('ued.passwordShow')} onClick={() => setShowPwd((v) => !v)}>
                      {showPwd ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}
                    </InputGroupButton>
                  </InputGroupAddon>
                </InputGroup>
              )}
            </Field>
          )}
          <Field label={t('usr.formEmail')} required={editable} hint={adHint(ed, t, 'email')}
            hintTone={ed.ldap && ed.lockedKeys.has('email') ? 'warn' : undefined} {...ed.fe.fieldProps('email')}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} type="email" inputMode="email" autoComplete="off" aria-describedby={describedBy} aria-invalid={invalid}
                value={form.email} disabled={!editable} onChange={(e) => ed.set('email', e.target.value)} />
            )}
          </Field>
          <TextField ed={ed} k="employee_id" autoComplete="off" />
        </div>
      </FieldSet>

      <FieldSet className="min-w-0 gap-0">
        <FieldLegend variant="label" className={LEGEND}><ShieldCheck aria-hidden="true" className="size-4 text-muted-foreground" />{t('ued.secAccess')}</FieldLegend>
        <div className={GRID}>
          <Field label={t('usr.formRole')} hint={roleHint} hintTone={guards.locked ? 'warn' : undefined}>
            {({ id }) => (
              <SearchableSelect id={id} value={form.system_role} disabled={!editable || guards.locked}
                onChange={(v) => ed.set('system_role', v)}
                options={roleOptionsFor(ed.viewerRole, form.system_role).map((r) => ({ value: r, label: r }))} />
            )}
          </Field>
          <Field label={t('usr.orgRole')} hint={orgHint}>
            {({ id }) => (
              <SearchableSelect id={id} value={form.org_role} disabled={!editable} onChange={(v) => ed.set('org_role', v)}
                options={[{ value: '', label: t('usr.orgRoleNone') }, ...ORG_ROLES.map((r) => ({ value: r, label: t('usr.orgRoleVal.' + r) }))]} />
            )}
          </Field>
        </div>
      </FieldSet>

      {!isAdd && (
        <FieldSet className="min-w-0 gap-2">
          <FieldLegend variant="label" className={LEGEND}><UserRound aria-hidden="true" className="size-4 text-muted-foreground" />{t('ued.secStatus')}</FieldLegend>
          {/* Seçim kartı: kartın tamamı dokunma hedefi; anahtarın adı yalnız başlık ("Aktif"), açıklama ayrıca bağlı. */}
          <FieldLabel htmlFor={activeId} data-slot="ued-active-card" className={cn('w-full', activeLocked && 'cursor-not-allowed')}>
            <ShField orientation="horizontal" data-disabled={activeLocked || undefined}>
              <FieldContent>
                <FieldTitle id={`${activeId}-t`}>{t('usr.formActive')}</FieldTitle>
                <FieldDescription id={`${activeId}-d`} className="text-xs">{form.active ? t('ued.activeOn') : t('ued.activeOff')}</FieldDescription>
              </FieldContent>
              <Switch id={activeId} checked={!!form.active} disabled={activeLocked} aria-labelledby={`${activeId}-t`}
                aria-describedby={`${activeId}-d`} onCheckedChange={(v) => ed.set('active', !!v)} />
            </ShField>
          </FieldLabel>
          {guards.self && <span data-slot="ued-active-guard" className="text-xs text-warning">{t('usr.selfActiveLocked')}</span>}
          {!guards.self && guards.lastAdmin && <span data-slot="ued-active-guard" className="text-xs text-warning">{t('usr.lastAdminActiveLocked')}</span>}
          {/* Aktiflik değişiminin sonucu kaydetmeden ÖNCE görünür (2026-10-02, kullanıcı kararı — pasif hesap) */}
          {editable && wasActive && !form.active && (
            <AlertBanner tone="warning" className="mb-0">{t('usr.deactivateEffects')}</AlertBanner>
          )}
          {editable && !wasActive && form.active && (
            <AlertBanner tone="info" className="mb-0">{t('usr.activateEffects')}</AlertBanner>
          )}
        </FieldSet>
      )}
    </div>
  )
}

/**
 * Takımlar sekmesi — çoklu seçim + seçili takımların listesi (birincil rozeti, çıkar). Takım yöneticisi yalnız kendi
 * takımını görür (salt-okunur; kayıtta da kendi takımına sabitlenir). ADMIN dışındaki rollerde en az bir takım zorunlu.
 *
 * <p>Birincil takım (kablo sözleşmesi korunur, `team_id = team_ids[0]`): YENİ kullanıcıda sunucu kümenin ilk elemanını
 * birincil yapar → "Birincil yap" takımı listenin başına alır. Düzenlemede sunucu mevcut birincili kümede kaldıkça
 * korur; liste kaydedince birincil olacak takımı ÖNGÖRÜR (yanlış vaat yok).
 */
export function TeamsSection({ ed }) {
  const t = useT()
  const { form, isAdd, editable, user, teams, teamMap } = ed
  const teamLocked = !isAdd && !!user?.team_locked && !ed.released.has('team')
  const roleAdmin = form.system_role === 'ADMIN'
  const selected = form.team_ids || []
  const primary = predictedPrimary(selected, { mode: ed.mode, currentPrimary: user?.team_id })
  const err = ed.fe.fieldProps('teams')
  const ownName = ed.ownTeamId != null ? (teamMap[ed.ownTeamId] || `#${ed.ownTeamId}`) : null

  if (ed.isTeamAdmin) {
    return (
      <div data-slot="ued-teams" data-scope="team-admin" className="flex min-w-0 flex-col gap-3">
        <Field label={t('usr.teamsLabel')} required={!roleAdmin} hint={t('ued.teamAdminOwnTeam')} {...err}>
          {({ id }) => (
            <SearchableSelect id={id} value={ed.ownTeamId ?? ''} onChange={() => {}} disabled
              options={[{ value: ed.ownTeamId ?? '', label: ownName ?? t('usr.noTeam') }]} />
          )}
        </Field>
        {teamLocked && <LockNote ed={ed} kind="team" label={t('ud.lock.team')} hint={t('ud.lockHint.team')} releaseLabel={t('usr.teamUnlock')} fieldLabel={t('usr.teamsLabel')} />}
      </div>
    )
  }

  const liveHint = !err.error && !roleAdmin && selected.length === 0 && editable ? t('usr.teamsRequired') : undefined
  return (
    <div data-slot="ued-teams" className="flex min-w-0 flex-col gap-3">
      <Field label={t('usr.teamsLabel')} required={editable && !roleAdmin} hintTone="warn"
        hint={liveHint || (roleAdmin && selected.length === 0 ? t('ued.adminNoTeam') : undefined)} {...err}>
        {({ id }) => (
          <MultiTeamSelect id={id} value={selected} disabled={!editable} searchThreshold={2} placeholder={t('usr.teamsPlaceholder')}
            onChange={(ids) => ed.set('team_ids', ids.map(Number))}
            options={(teams || []).map((team) => ({ value: team.id, label: team.name }))} />
        )}
      </Field>
      {teamLocked && (
        <p className="m-0 text-xs text-muted-foreground">
          <LockNote ed={ed} kind="team" label={t('ud.lock.team')} hint={t('ud.lockHint.team')} releaseLabel={t('usr.teamUnlock')} fieldLabel={t('usr.teamsLabel')} />
        </p>
      )}
      <section aria-label={t('ued.teamsSelected')} data-slot="ued-team-list" className="flex min-w-0 flex-col gap-2">
        <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
          <span className="text-[13px] font-semibold">{t('ued.teamsSelected')}
            <Badge variant="secondary" className="ml-2 h-5 min-w-5 rounded-full px-1.5 align-middle tabular-nums">{selected.length}</Badge>
          </span>
          {primary != null && !isAdd && (
            <span data-slot="ued-primary-after" className="text-xs text-muted-foreground">{t('ued.primaryAfterSave', teamMap[primary] || `#${primary}`)}</span>
          )}
        </div>
        {selected.length === 0 ? (
          <p className="m-0 rounded-lg border border-dashed px-3 py-4 text-center text-sm text-muted-foreground">{t('ued.teamsEmpty')}</p>
        ) : (
          <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
            {selected.map((id) => {
              const name = teamMap[id] || `#${id}`
              const isPrimary = String(id) === String(primary)
              return (
                <li key={id} data-slot="ued-team-row" data-team-id={id} data-primary={isPrimary ? 'true' : undefined}
                  className="flex min-w-0 flex-wrap items-center gap-2 rounded-lg border bg-card px-3 py-2">
                  <span className="flex min-w-0 flex-1 flex-wrap items-center gap-1.5">
                    <TeamBadge teamId={id} teamName={name} size={12} static />
                    {isPrimary && <Badge variant="secondary" data-slot="ued-primary" className="gap-1"><Star aria-hidden="true" className="size-3" />{t('ued.primary')}</Badge>}
                  </span>
                  {editable && (
                    <span className="flex shrink-0 items-center gap-1">
                      {isAdd && !isPrimary && (
                        <Button type="button" variant="ghost" size="sm" className="h-8 pointer-coarse:h-10 max-sm:h-10"
                          aria-label={t('a11y.rowAction', name, t('ued.makePrimary'))}
                          onClick={() => ed.set('team_ids', makePrimary(selected, id))}>
                          <Star aria-hidden="true" />{t('ued.makePrimary')}
                        </Button>
                      )}
                      <Button type="button" variant="ghost" size="icon-sm" className="text-muted-foreground pointer-coarse:size-10 max-sm:size-10"
                        aria-label={t('a11y.rowAction', name, t('ued.removeTeam'))}
                        onClick={() => ed.set('team_ids', selected.filter((x) => String(x) !== String(id)))}>
                        <X aria-hidden="true" />
                      </Button>
                    </span>
                  )}
                </li>
              )
            })}
          </ul>
        )}
        <p className="m-0 text-xs leading-relaxed text-muted-foreground">{isAdd ? t('ued.primaryAddHint') : t('ued.primaryEditHint')}</p>
      </section>
    </div>
  )
}

/** Profil sekmesi — AD'den eşlenen alanlar (LDAP hesabında ipucu + alan kilidi), "AD ile karşılaştır" girişi. */
export function ProfileSection({ ed }) {
  const t = useT()
  return (
    <div data-slot="ued-profile" className="flex min-w-0 flex-col gap-4">
      {ed.ldap ? (
        <AlertBanner tone="info" className="mb-0" icon={SearchCheck}
          actions={ed.onOpenDirectory && (
            <Button type="button" variant="outline" size="sm" className="h-10 sm:h-8" data-slot="ued-ad-compare" onClick={ed.onOpenDirectory}>
              <SearchCheck aria-hidden="true" />{t('ued.adCompare')}
            </Button>
          )}>
          {t('ued.profileIntroLdap')}
        </AlertBanner>
      ) : (
        <p className="m-0 text-xs text-muted-foreground">{ed.isAdd ? t('ued.profileIntroNew') : t('ued.profileIntroLocal')}</p>
      )}
      <FieldSet className="min-w-0 gap-0">
        <FieldLegend variant="label" className={LEGEND}><UserRound aria-hidden="true" className="size-4 text-muted-foreground" />{t('ued.secPerson')}</FieldLegend>
        <div className={GRID}>
          <div className="sm:col-span-2"><TextField ed={ed} k="display_name" autoComplete="off" /></div>
          <TextField ed={ed} k="first_name" autoComplete="off" />
          <TextField ed={ed} k="last_name" autoComplete="off" />
          <TextField ed={ed} k="phone" type="tel" inputMode="tel" autoComplete="off" />
        </div>
      </FieldSet>
      <FieldSet className="min-w-0 gap-0">
        <FieldLegend variant="label" className={LEGEND}><Building2 aria-hidden="true" className="size-4 text-muted-foreground" />{t('ued.secOrg')}</FieldLegend>
        <div className={GRID}>
          <TextField ed={ed} k="title" autoComplete="off" />
          <TextField ed={ed} k="department" autoComplete="off" />
          <TextField ed={ed} k="company_level" autoComplete="off" />
          <TextField ed={ed} k="manager_sicil" autoComplete="off" />
          <div className="sm:col-span-2"><TextField ed={ed} k="mudurluk_name" autoComplete="off" /></div>
        </div>
      </FieldSet>
    </div>
  )
}
