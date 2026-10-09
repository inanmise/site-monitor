import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Contact, ShieldCheck, UserCog, UserPlus, UserRound, Users } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { useDialog } from '../../ui/Dialog.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import { PHONE_FULLSCREEN as PHONE_FULLSCREEN_BASE } from '../../ui/modalClasses.js'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { useFormErrors } from '../../../hooks/useFormErrors.js'
import { focusFormError } from '../../../utils/formErrors.js'
import { usePermissions } from '../../../contexts/PermissionsProvider.jsx'
import AdminAutoResetModal from '../AdminAutoResetModal.jsx'
import { TerminateModal } from '../useractivity/UactModals.jsx'
import UserEditorHeader from './UserEditorHeader.jsx'
import UserEditorFooter from './UserEditorFooter.jsx'
import UserEditorSecurity from './UserEditorSecurity.jsx'
import { AccountSection, ProfileSection, TeamsSection } from './UserEditorSections.jsx'
import {
  buildCreatePayload, buildPayload, diffChanges, editorGuards, errorCountsByTab, firstErrorTab, isAddFormTouched,
  lockoutState, toForm, emptyForm, validateForm,
} from './userEditorModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { cn } from '@/lib/utils'

/**
 * Pencere boyutu SABİT (IncidentFormModal deseni): ≥640 px'te yükseklik `min(760px, 100dvh-2rem)` — sekme değişince
 * kutu büyüyüp küçülmez, ortalı pencere zıplamaz; kaydırma çubuğu yeri hep ayrılı. Telefonda (< 640) TAM EKRAN,
 * altlık güvenli alanı (safe-area) gözetir. Alanlar telefonda ≥ 40 px dokunma hedefi.
 */
const FIXED = cn(
  'grid-cols-[minmax(0,1fr)] sm:h-[min(760px,calc(100dvh-2rem))] sm:w-full',
  '[&_[data-slot=modal-shell-body]]:[scrollbar-gutter:stable]',
)
const PHONE_FULLSCREEN = `${PHONE_FULLSCREEN_BASE} max-sm:[&_[data-slot=dialog-close]]:size-10`
const PHONE_TOUCH = 'max-sm:[&_[data-slot=input]]:h-10 max-sm:[&_[role=combobox]]:min-h-10'

const TAB_ICON = { account: UserRound, teams: Users, profile: Contact, security: ShieldCheck }
const TAB_LABEL = { account: 'ued.tabAccount', teams: 'ued.tabTeams', profile: 'ued.tabProfile', security: 'ued.tabSecurity' }

/**
 * KULLANICI DÜZENLEYİCİ — tek paylaşılan pencere (2026-10-02, kullanıcı isteği: "Kullanıcı Düzenle ekranını shadcn
 * ile yeniden, fonksiyonlarını koruyarak tasarlayalım; mweb responsive; kullanıcı deneyimi en üst seviyede").
 *
 * <p>Kullanıldığı yerler: Yönetim → Kullanıcılar (Ekle / satır menüsü "Düzenle" / kullanıcı detayındaki "Düzenle") ve
 * Yönetim → Takımlar → üye kartı kalemi (`UserEditModal` ince sarmalayıcısı). Kipler: `add` · `edit` · `view`
 * (salt-okunur; "Düzenle" → `onEdit` ya da yetki varsa aynı pencerede düzenleme).
 *
 * <p>Yerleşim: başlık özeti (fotoğraf, ad, kayıtlı durum rozetleri, son giriş) → yapışkan sekmeler (Hesap · Takımlar ·
 * Profil · Güvenlik; hatalı alanı olan sekmede kırmızı nokta, takım sayısı) → yapışkan altlık (hata sayısı, "N
 * değişiklik" özeti, İptal / Kaydet). Bütün sekmeler DOM'da kalır (`forceMount` + `hidden`): alan değerleri, AD
 * ipuçları ve `data-field` odak kancaları sekme değişse de yaşar.
 *
 * <p>KORUNAN her davranış (eski iki formun birleşimi): aynı uçlar ve aynı kayıt yükü (`userEditorModel.buildPayload`);
 * kendi hesabında ve sistemdeki TEK aktif ADMIN'de rol + aktiflik kilitli (açıklamalı); takım yöneticisi yalnız
 * USER/TEAM_ADMIN rolü verir ve kayıtta takım KENDİ takımına sabitlenir; ADMIN dışındaki rollerde takım zorunlu;
 * e-posta zorunlu + biçim denetimi; LDAP hesabında AD alanı ipucu / kilit rozeti (+ global yöneticiye "AD'ye geri
 * ver"); aktiflik değişiminin sonucu kaydetmeden önce görünür (pasifleştirme / yeniden aktifleştirme bandı); sunucu
 * hatası pencerede kalır, form kaybolmaz; başarıda "Kaydedildi" bildirimi; ürün turu sıfırlama; cihaz geçmişi (izinli,
 * tembel); geçici şifre gönderimi ve sonucu.
 *
 * <p>Zenginleştirmeler: değişiklik özeti (eski → yeni), kaydedilmemiş değişiklikle kapatmada onay (AlertDialog),
 * değişiklik yokken Kaydet kapalı, alan yanında hata + ilk hatalı alanın sekmesine geçip odaklanma, Ctrl/⌘+Enter ile
 * kaydet, parola göster/gizle, birincil takım (yeni kullanıcıda seçilir, düzenlemede kaydedince olacak birincil
 * gösterilir), rol / org. rol / takım AD kilitlerini yerinde açma, kilit açma (kalıcı / süreli), oturum sonlandırma
 * (global yönetici), "AD ile karşılaştır" girişi (kullanıcı detayının Dizin sekmesi).
 */
export default function UserEditor({
  mode: initialMode = 'edit', user = null, teams = [],
  viewerRole = 'ADMIN', globalAdmin = false, ownTeamId = null, currentUsername = null, activeAdminCount = null,
  onClose, onSaved, onChanged, onEdit, onOpenDirectory,
}) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const { canView } = usePermissions()
  const [mode, setMode] = useState(initialMode)
  const isAdd = mode === 'add'
  const isView = mode === 'view'
  const editable = !isView
  const [form, setForm] = useState(() => (isAdd ? emptyForm() : toForm(user)))
  const [tab, setTab] = useState('account')
  const [saving, setSaving] = useState(false)
  const [serverError, setServerError] = useState(null)
  const [busy, setBusy] = useState(null)
  const [released, setReleased] = useState(() => new Set())
  const [resetResult, setResetResult] = useState(null)
  const [autoResetOpen, setAutoResetOpen] = useState(false)
  const [terminateOpen, setTerminateOpen] = useState(false)
  const [devicesOpen, setDevicesOpen] = useState(false)
  const fe = useFormErrors(`${initialMode}:${user?.id ?? 'new'}`)
  const bodyRef = useRef(null)
  const tabBarRef = useRef(null)
  const focusPending = useRef(false)

  // Telefonda sekme çubuğu yatay kayar: etkin sekme (doğrulamanın açtığı dâhil) görünür alana kaydırılır.
  const tabMounted = useRef(false)
  useEffect(() => {
    if (!tabMounted.current) { tabMounted.current = true; return }
    const el = tabBarRef.current?.querySelector(`[data-tab-key="${tab}"]`)
    try { el?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' }) } catch { /* jsdom / eski tarayıcı */ }
  }, [tab])

  // Başka bir kullanıcıya geçilirse (aynı pencere örneği) form o kullanıcıdan yeniden kurulur. Aynı kullanıcının TAZE
  // satırı (kilit açma / AD eşitlemesi sonrası liste yenilenince) yazılanları SİLMEZ — taban (`baseline`) güncellenir.
  const userId = user?.id ?? null
  const mounted = useRef(false)
  useEffect(() => {
    if (!mounted.current) { mounted.current = true; return }
    if (initialMode === 'add') return
    setForm(toForm(user))
    setServerError(null)
    setReleased(new Set())
    setResetResult(null)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userId])

  const ctx = useMemo(() => ({ mode, viewerRole, ownTeamId }), [mode, viewerRole, ownTeamId])
  const baseline = useMemo(() => (isAdd ? emptyForm() : toForm(user)), [isAdd, user])
  const changes = useMemo(() => (isAdd || isView ? [] : diffChanges(baseline, form, ctx)), [isAdd, isView, baseline, form, ctx])
  const dirty = isAdd ? isAddFormTouched(form) : changes.length > 0
  const guards = useMemo(() => editorGuards(user, { mode, currentUsername, activeAdminCount }), [user, mode, currentUsername, activeAdminCount])
  const teamMap = useMemo(() => Object.fromEntries((teams || []).map((tm) => [tm.id, tm.name])), [teams])
  const ldap = !isAdd && user?.auth_source === 'LDAP'
  const lockedKeys = useMemo(() => new Set(ldap
    ? (user?.locked_field_keys || []).filter((k) => !released.has(`field:${k}`)) : []), [ldap, user, released])
  const lock = !isAdd && !released.has('perm') ? lockoutState(user) : null
  const canManage = viewerRole === 'ADMIN' || viewerRole === 'TEAM_ADMIN'
  const errorCounts = errorCountsByTab(fe.errors)
  const errorCount = Object.values(errorCounts).reduce((a, b) => a + b, 0)

  const feClear = fe.clear
  const set = useCallback((key, value) => {
    setForm((f) => (f[key] === value ? f : { ...f, [key]: value }))
    feClear(key === 'team_ids' ? 'teams' : key)
  }, [feClear])

  // Doğrulama sonrası: ilk hatalı alanın sekmesi açıldıktan SONRA odak (gizli sekmedeki alan odak alamaz).
  useEffect(() => {
    if (!focusPending.current) return
    focusPending.current = false
    focusFormError(fe.errors, bodyRef.current || undefined)
  }, [tab, fe.errors])

  async function save() {
    if (saving || isView) return
    const raw = validateForm(form, ctx)
    const keys = Object.keys(raw)
    if (keys.length > 0) {
      const map = Object.fromEntries(keys.map((k) => [k, t(raw[k].key, ...(raw[k].args || []))]))
      const first = firstErrorTab(map)
      if (first && first !== tab) setTab(first)
      focusPending.current = true
      fe.check(map)
      return
    }
    if (!isAdd && changes.length === 0) return
    fe.reset()
    setServerError(null)
    setSaving(true)
    try {
      const res = isAdd
        ? await api.admin.createUser(buildCreatePayload(form, ctx))
        : await api.admin.updateUser(user.id, buildPayload(form, ctx))
      if (res?.success) {
        toast.success(t('usr.saved'))
        onSaved?.(res.data ?? null)
        onClose?.()
      } else {
        setServerError(res?.error || 'Error')
      }
    } catch (e) {
      setServerError(e?.message || 'Error')
    } finally {
      setSaving(false)
    }
  }

  async function requestClose() {
    if (saving) return
    if (!isView && dirty) {
      const ok = await showConfirm({
        title: t('ued.discardTitle'),
        message: isAdd ? t('ued.discardMsgNew') : t('ued.discardMsg', changes.length),
        confirmText: t('ued.discardConfirm'),
        cancelText: t('ued.keepEditing'),
        variant: 'warning',
      })
      if (!ok) return
    }
    onClose?.()
  }

  /** "AD ile karşılaştır": düzenleyiciyi (onaylı) kapatıp kullanıcı detayının Dizin sekmesini açar. */
  async function openDirectory() {
    if (!onOpenDirectory || saving) return
    if (dirty) {
      const ok = await showConfirm({
        title: t('ued.discardTitle'), message: t('ued.discardMsg', changes.length),
        confirmText: t('ued.discardConfirm'), cancelText: t('ued.keepEditing'), variant: 'warning',
      })
      if (!ok) return
    }
    onOpenDirectory(user)
  }

  // ── Anında uygulanan eylemler (mevcut uçlar; kayıt düğmesini beklemez) ──
  const LOCK_CALL = {
    perm: [() => api.admin.unlockUser(user.id), () => t('usr.unlocked')],
    role: [() => api.admin.unlockUserRole(user.id), () => t('usr.roleUnlocked')],
    org: [() => api.admin.unlockUserOrgRole(user.id), () => t('usr.orgRoleUnlocked')],
    team: [() => api.admin.unlockUserTeams(user.id), () => t('usr.teamUnlocked')],
  }
  /** Tek seferde tek eylem (`busy`); `lenient`: yanıtta `success` yoksa da başarı say (eski tur sıfırlama sözleşmesi). */
  async function runAction(key, call, onOk, { lenient = false } = {}) {
    if (busy) return
    setBusy(key)
    try {
      const r = await call()
      if (lenient ? r?.success === false : !r?.success) toast.error(r?.error || (lenient ? t('usr.tourResetFailed') : 'Error'))
      else onOk(r)
    } catch (e) {
      toast.error(e?.message || 'Error')
    } finally {
      setBusy(null)
    }
  }
  const releaseLock = (kind) => {
    const [call, okText] = LOCK_CALL[kind] || []
    if (!call || !user) return
    runAction(kind, call, () => {
      toast.success(okText())
      setReleased((s) => new Set(s).add(kind))
      onChanged?.()
    })
  }
  const releaseField = (lockKey) => {
    if (!user) return
    runAction(`field:${lockKey}`, () => api.admin.unlockUserField(user.id, lockKey), () => {
      toast.success(t('usr.fieldUnlocked'))
      setReleased((s) => new Set(s).add(`field:${lockKey}`))
      onChanged?.()
    })
  }
  const resetTour = () => {
    if (!user) return
    runAction('tour', () => api.admin.resetUserTour(user.id), () => toast.success(t('usr.tourResetDone')), { lenient: true })
  }
  async function terminate(reason) {
    if (!user) return
    setBusy('terminate')
    try {
      const res = await api.admin.terminateUserSession(user.username, reason)
      if (res?.success) { toast.success(t('uact.terminated', user.username)); setTerminateOpen(false); onChanged?.() }
      else toast.error(res?.error || 'Error')
    } catch (e) {
      toast.error(e?.message || 'Error')
    } finally {
      setBusy(null)
    }
  }

  const startEdit = onEdit || (canManage ? () => setMode('edit') : null)
  const ed = {
    mode, isAdd, isView, editable, form, set, fe, user, guards, viewerRole, isTeamAdmin: viewerRole === 'TEAM_ADMIN',
    globalAdmin, ownTeamId, teams, teamMap, ldap, lockedKeys, released, busy,
    canManage, lock, releaseLock, releaseField, resetTour, resetResult,
    openAutoReset: () => setAutoResetOpen(true), openTerminate: () => setTerminateOpen(true),
    canTerminate: !isAdd && editable && globalAdmin && !guards.self && !!user?.username,
    canSeeDevices: !isAdd && !!user?.id && canView('audit_log.read'), devicesOpen, setDevicesOpen,
    onOpenDirectory: ldap && globalAdmin && onOpenDirectory ? openDirectory : null,
    changes, dirty, saving, errorCount, save, requestClose, startEdit,
  }

  const tabs = ['account', 'teams', 'profile', ...(isAdd ? [] : ['security'])]
  const teamCount = (viewerRole === 'TEAM_ADMIN' ? (ownTeamId != null ? 1 : 0) : (form.team_ids || []).length)
  const title = isAdd ? t('usr.addTitle') : isView ? t('usr.viewTitle') : t('usr.editTitle')

  return (
    <ModalShell open onClose={requestClose} size="lg" scrollBody busy={saving} bodyRef={bodyRef}
      icon={isAdd ? UserPlus : UserCog} title={title} className={cn(FIXED, PHONE_FULLSCREEN)}
      footer={<UserEditorFooter ed={ed} />}>
      <div data-slot="user-editor" data-mode={mode} data-user-id={user?.id ?? undefined} className={cn('flex min-w-0 flex-col gap-3', PHONE_TOUCH)}
        onKeyDown={(e) => { if ((e.ctrlKey || e.metaKey) && e.key === 'Enter' && editable) { e.preventDefault(); save() } }}>
        <UserEditorHeader mode={mode} user={user} form={form} guards={guards} lock={lock} lockedFieldCount={lockedKeys.size} />
        <Tabs value={tab} onValueChange={setTab} className="min-w-0 gap-0">
          {/* Sekme çubuğu (+ sunucu hatası) gövdenin üstünde yapışık; telefonda yatay kayar, hedefler 44 px. */}
          <div ref={tabBarRef} className="sticky top-0 z-10 -mx-1 flex flex-col gap-2 border-b bg-background px-1 pb-0">
            {/* Telefonda kenarlar yumuşak solar: sekmelerin kaydırılabildiği görünsün (kullanıcı detayıyla aynı dil). */}
            <TabsList variant="line" aria-label={t('ued.tabsLabel')}
              className="w-full justify-start gap-1 overflow-x-auto overflow-y-hidden rounded-none p-0 group-data-[orientation=horizontal]/tabs:h-auto [scrollbar-width:none] max-sm:px-2 max-sm:[mask-image:linear-gradient(90deg,transparent,#000_10px,#000_calc(100%-14px),transparent)]">
              {tabs.map((key) => {
                const Icon = TAB_ICON[key]
                const n = errorCounts[key] || 0
                return (
                  <TabsTrigger key={key} value={key} data-tab-key={key} data-invalid={n > 0 || undefined}
                    className="h-11 flex-none gap-1.5 px-2.5 sm:h-10">
                    <Icon aria-hidden="true" />{t(TAB_LABEL[key])}
                    {key === 'teams' && teamCount > 0 && (
                      <Badge variant="secondary" data-slot="ued-tab-count" className="h-5 min-w-5 rounded-full px-1.5 text-[11px] tabular-nums">{teamCount}</Badge>
                    )}
                    {n > 0 && (
                      <span data-slot="ued-tab-error" className="inline-flex items-center">
                        <span aria-hidden="true" className="size-2 rounded-full bg-destructive" />
                        <span className="sr-only">{t('ued.tabErrors', n)}</span>
                      </span>
                    )}
                  </TabsTrigger>
                )
              })}
            </TabsList>
          </div>
          {serverError && (
            <AlertBanner tone="danger" role="alert" className="mt-3 mb-0" title={t('ued.saveFailed')}
              onDismiss={() => setServerError(null)} dismissLabel={t('app.close')}>{serverError}</AlertBanner>
          )}
          {tabs.map((key) => (
            <TabsContent key={key} value={key} forceMount hidden={tab !== key} className="min-w-0 pt-4">
              {key === 'account' && <AccountSection ed={ed} />}
              {key === 'teams' && <TeamsSection ed={ed} />}
              {key === 'profile' && <ProfileSection ed={ed} />}
              {key === 'security' && <UserEditorSecurity ed={ed} />}
            </TabsContent>
          ))}
        </Tabs>
      </div>

      {autoResetOpen && user && (
        <AdminAutoResetModal targetUser={user} onClose={() => setAutoResetOpen(false)}
          onSuccess={(emailStatus) => { setResetResult(String(emailStatus || '').startsWith('SENT') ? 'sent' : 'failed'); onChanged?.() }} />
      )}
      {terminateOpen && user && (
        <TerminateModal target={user.username} busy={busy === 'terminate'} onClose={() => setTerminateOpen(false)} onConfirm={terminate} />
      )}
    </ModalShell>
  )
}
