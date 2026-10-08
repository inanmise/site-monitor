import { useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { BellRing, History, LayoutGrid, SearchCheck, ShieldCheck, UserCog, Users, X } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useServerPagination } from '../../hooks/useServerPagination.js'
import UserLdapCompare from './UserLdapCompare.jsx'
import UserDetailHeader from './userdetail/UserDetailHeader.jsx'
import OverviewTab from './userdetail/OverviewTab.jsx'
import TeamsTab from './userdetail/TeamsTab.jsx'
import NotificationsTab from './userdetail/NotificationsTab.jsx'
import PermissionsTab from './userdetail/PermissionsTab.jsx'
import ChangesTab from './userdetail/ChangesTab.jsx'
import { SectionCard } from './userdetail/parts.jsx'
import { useSection, unwrap } from './userdetail/useSection.js'
import { effectivePermissions, fullNameOf, teamIdsOf } from './userdetail/userDetailModel.js'
import { Sheet, SheetClose, SheetContent, SheetDescription, SheetHeader } from '@/components/shadcn/sheet'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { sameUser } from '../../utils/userRef'

// Kaydırma kilidi sayaçlı (ModalShell / IssueDetailSheet ile aynı sözleşme): iç içe pencerede erken açılmaz.
let scrollLocks = 0
let savedOverflow = ''

/**
 * Kullanıcı Detayı (2026-09-27 yeniden tasarım — kullanıcı isteği: "shadcn ile zengin, profesyonel, mobil duyarlı").
 *
 * <p><b>Neden sağdan Sheet (ModalShell değil).</b> Bu pencere bir liste satırının İNCELEYİCİSİ: kullanıcı listeyi
 * bağlamında tutar, tam yükseklik sekmeli içeriğe yer açar ve telefonda doğal olarak TAM EKRAN olur. Kardeş ayrıntı
 * pencereleri (Sorun Bildirimi, Olay) aynı deseni kullanır → uygulamada tek "ayrıntı" dili. `modal={false}` + kendi
 * scrim'i + sayaçlı kaydırma kilidi: içinden açılan takım üyeleri penceresi (TeamBadge → ModalShell), onay diyaloğu
 * (AD'den yeniden eşitle) ve ipucu/menü portal'ları tıklanabilir kalır. Kapatma yolları: scrim, X, Escape.
 *
 * <p>Yerleşim: profil başlığı (avatar · ad · kullanıcı adı/e-posta kopyala · rol/durum/kilit rozetleri · son giriş ·
 * Düzenle / AD denetimi) → yapışkan sekmeler (sayılı): Genel Bakış · Takımlar · Bildirimler · Yetkiler · Değişiklikler ·
 * Dizin (AD). Her bölüm kendi iskeleti ve hata + yeniden dene yüzeyiyle yüklenir (eski panel hataları yutuyordu).
 *
 * <p>Props sözleşmesi korunur: `{ user, teams, isAdmin, onClose, onEdit, onChanged }`; ek (isteğe bağlı) `onUnlock` =
 * UserManager'ın MEVCUT kilit açma işleyicileri `{ perm, role, org, team }` (yeni uç yok). Kapılar eskisiyle aynı:
 * yetki / push / geçmiş / AD ayakları yalnız `isAdmin`'e yüklenir; AD karşılaştırması yalnız LDAP hesapta (sunucu
 * ayrıca global yönetici ister).
 *
 * <p><b>`stacked` (2026-09-30):</b> pencere bir ModalShell'in (takım üyeleri penceresi, Üyeler sekmesi) ÜSTÜNE açılıyorsa
 * katman 2100/2101'e çıkar (ModalShell 2000; onay diyaloğu 9500, menü 9600, toast 9700 yine üstte) ve scrim tıklanabilir
 * kalır. Escape yalnız EN ÜSTTEKİ katmanı kapatır (Radix DismissableLayer yığını: alttaki ModalShell'in
 * `onEscapeKeyDown`'u çağrılmaz) — gate: `TeamManager.test.jsx` "üst üste açılan detay". Bu kipte içeriden açılan bir
 * TeamBadge penceresi (2000) bu kabuğun ALTINDA kalır; takım penceresinden gelen kullanıcı için takım zaten açıktır.
 *
 * <p><b>`initialTab` (2026-10-02, isteğe bağlı):</b> açılış sekmesi — kullanıcı düzenleyicisindeki "AD ile karşılaştır"
 * pencereyi doğrudan Dizin sekmesinde açar. Sekme bu kullanıcıda yoksa (ör. yerel hesapta Dizin) Genel Bakış.
 */
export default function UserDetailPanel({ user, teams = [], isAdmin, globalAdmin = false, onClose, onEdit, onChanged, onUnlock, stacked = false, initialTab = 'overview' }) {
  const t = useT()
  const showDirectory = !!isAdmin && user.auth_source === 'LDAP'
  const [tab, setTab] = useState(() => {
    if (initialTab === 'directory') return showDirectory ? 'directory' : 'overview'
    if ((initialTab === 'permissions' || initialTab === 'changes') && !isAdmin) return 'overview'
    return initialTab || 'overview'
  })
  const bodyRef = useRef(null)
  const name = fullNameOf(user)
  const teamIds = useMemo(() => teamIdsOf(user), [user])
  const teamMap = useMemo(() => Object.fromEntries((teams || []).map((x) => [Number(x.id), x.name])), [teams])

  // ── Bölümler: her biri kendi durumunu taşır (yükleniyor / hata + yeniden dene / veri) ──
  // Üyelik + KAYNAK izi sunucudan TAZE okunur (yeniden eşitlemeden sonra `user` prop'u bayat kalır).
  const membership = useSection(`m:${user.id}`, async () => {
    const r = unwrap(await api.admin.userTeamMembership(user.id), t('ud.errTeams'))
    if (!Array.isArray(r.data?.memberships)) throw new Error(t('ud.errTeams'))
    return r.data
  })
  const contacts = useSection(`c:${user.id}`, async () => {
    const r = unwrap(await api.admin.getContacts(), t('ud.errContacts'))
    return (r.data || []).filter((c) => sameUser(c.user_id, user.id))
  })
  const matrix = useSection(`p:${user.id}`, async () => {
    const r = unwrap(await api.admin.getPermissionMatrix(), t('ud.errPerms'))
    return { catalog: r.catalog || [], grants: r.grants || [] }
  }, !!isAdmin)
  const push = useSection(`u:${user.id}:${user.team_id}`, async () => {
    const r = unwrap(await api.admin.userPush.explain(user.team_id, 'HIGH'), t('ud.errPush'))
    return (r.members || r.data?.members || []).find((x) => x.username === user.username) || null
  }, !!isAdmin && user.team_id != null && !!api.admin.userPush?.explain)
  // Değişiklik geçmişi: standart sunucu sayfalaması (pencere ön ayarı: 10 / [10, 25, 50], sıkı çubuk).
  const sp = useServerPagination({ listKey: 'user-detail-history', preset: 'modal', resetDeps: [user.id], apiBase: 0 })
  const history = useSection(`h:${user.id}:${sp.apiPage}:${sp.pageSize}`, async () => (
    sp.bind(unwrap(await api.admin.history('USER', user.id, { page: sp.apiPage, size: sp.pageSize }), t('ud.errChanges')))
  ), !!isAdmin)

  const perms = useMemo(() => effectivePermissions(matrix.data, user.system_role), [matrix.data, user.system_role])

  // Odak iadesi: kapanışta tetikleyiciye (satır / kart).
  useEffect(() => {
    const previous = document.activeElement
    return () => { if (previous && typeof previous.focus === 'function' && document.contains(previous)) previous.focus() }
  }, [])
  // Arka plan kaydırma kilidi (sayaçlı).
  useEffect(() => {
    if (scrollLocks === 0) { savedOverflow = document.body.style.overflow; document.body.style.overflow = 'hidden' }
    scrollLocks += 1
    return () => { scrollLocks -= 1; if (scrollLocks === 0) document.body.style.overflow = savedOverflow }
  }, [])

  const openTab = (next) => {
    setTab(next)
    if (bodyRef.current) bodyRef.current.scrollTop = 0
  }
  const afterMutation = () => { history.reload(); onChanged?.() }

  const tabs = [
    { key: 'overview', label: t('ud.tabOverview'), icon: LayoutGrid },
    { key: 'teams', label: t('ud.tabTeams'), icon: Users,
      count: membership.data ? membership.data.memberships.length : (membership.loading ? null : teamIds.length) },
    { key: 'notifications', label: t('ud.tabNotifications'), icon: BellRing, count: contacts.data ? contacts.data.length : null },
    isAdmin && { key: 'permissions', label: t('ud.tabPermissions'), icon: ShieldCheck, count: perms ? perms.granted : null },
    isAdmin && { key: 'changes', label: t('ud.tabChanges'), icon: History, count: history.data ? Number(history.data.total ?? 0) : null },
    showDirectory && { key: 'directory', label: t('ud.tabDirectory'), icon: SearchCheck },
  ].filter(Boolean)

  const primaryName = user.team_id != null
    ? (membership.data?.memberships?.find((m) => Number(m.team_id) === Number(user.team_id))?.team_name || teamMap[Number(user.team_id)])
    : null

  return (
    <Sheet open modal={false} onOpenChange={(next) => { if (!next) onClose?.() }}>
      {/* Katman: shadcn Sheet'in z-50'si yüzen yardım düğmesinin (900) altında kalır; ModalShell (2000) ve onay diyalogları
          (--z-dialog) yine üstte açılsın diye 1000/1001 (Olay / Sorun ayrıntısıyla aynı). data-slot="dialog-overlay":
          rowAccessibleNames kapısı bu örtüyü pasif tıklama sayar — kapatma ayrıca X ve Escape ile. */}
      {createPortal(
        <div data-slot="dialog-overlay" aria-hidden="true"
          className={cn('fixed inset-0 bg-black/50 animate-in fade-in-0 motion-reduce:animate-none',
            stacked ? 'z-[2100] pointer-events-auto' : 'z-[1000]')}
          onClick={(e) => { if (e.target === e.currentTarget) onClose?.() }} />,
        document.body,
      )}
      <SheetContent side="right" showCloseButton={false} data-testid="user-detail" data-slot="user-detail" data-user-id={user.id}
        data-stacked={stacked ? 'true' : undefined}
        aria-modal="true" onInteractOutside={(e) => e.preventDefault()} onCloseAutoFocus={(e) => e.preventDefault()}
        className={cn('flex h-[100dvh] w-full flex-col gap-0 p-0 sm:w-[min(56rem,calc(100vw-2rem))] sm:max-w-none',
          stacked ? 'z-[2101]' : 'z-[1001]')}>
        <SheetHeader className="shrink-0 gap-3 px-4 pt-3 pb-4 text-left sm:px-6">
          <div className="flex items-center justify-between gap-2">
            <span className="inline-flex items-center gap-1.5 text-[11px] font-semibold tracking-wider text-muted-foreground uppercase">
              <UserCog aria-hidden="true" className="size-3.5" />{t('usr.viewTitle')}
            </span>
            <SheetClose asChild>
              <Button type="button" variant="ghost" size="icon" className="-mr-2 size-10 shrink-0 text-muted-foreground sm:size-9" aria-label={t('app.close')}>
                <X aria-hidden="true" />
              </Button>
            </SheetClose>
          </div>
          <SheetDescription className="sr-only">{t('ud.description', name)}</SheetDescription>
          <UserDetailHeader user={user} name={name} onEdit={onEdit}
            onOpenDirectory={showDirectory ? () => openTab('directory') : undefined} />
        </SheetHeader>

        <Tabs value={tab} onValueChange={openTab} className="flex min-h-0 flex-1 flex-col gap-0">
          <div className="shrink-0 border-y bg-background">
            {/* Telefonda sekmeler yatay kayar; kenarlar yumuşak solar (kaydırılabilir olduğu görünsün), hedefler 44 px */}
            <TabsList variant="line" aria-label={t('ud.tabsLabel')}
              className="w-full justify-start gap-1 overflow-x-auto rounded-none px-3 py-0 group-data-[orientation=horizontal]/tabs:h-auto [scrollbar-width:none] sm:px-5 max-sm:[mask-image:linear-gradient(90deg,transparent,#000_14px,#000_calc(100%-14px),transparent)]">
              {tabs.map(({ key, label, icon: Icon, count }) => (
                <TabsTrigger key={key} value={key} data-tab-key={key} className="h-11 flex-none gap-1.5 px-2.5 sm:h-10">
                  <Icon aria-hidden="true" />{label}
                  {count != null && (
                    <Badge variant="secondary" data-slot="ud-tab-count" className="h-5 min-w-5 rounded-full px-1.5 text-[11px] tabular-nums">{count}</Badge>
                  )}
                </TabsTrigger>
              ))}
            </TabsList>
          </div>

          <div ref={bodyRef} data-slot="ud-body" className="min-h-0 flex-1 overflow-y-auto bg-muted/30 px-4 py-4 pb-[max(env(safe-area-inset-bottom),1rem)] sm:px-6">
            <TabsContent value="overview" className="mt-0">
              <OverviewTab user={user} teamName={primaryName} onUnlock={onUnlock} onUnlocked={afterMutation} globalAdmin={globalAdmin} />
            </TabsContent>
            <TabsContent value="teams" className="mt-0">
              <TeamsTab user={user} section={membership} teamIds={teamIds} teamMap={teamMap} />
            </TabsContent>
            <TabsContent value="notifications" className="mt-0">
              <NotificationsTab user={user} isAdmin={!!isAdmin} push={push} contacts={contacts} teamMap={teamMap} onChanged={afterMutation} />
            </TabsContent>
            {isAdmin && (
              <TabsContent value="permissions" className="mt-0">
                <PermissionsTab role={user.system_role} section={matrix} perms={perms} />
              </TabsContent>
            )}
            {isAdmin && (
              <TabsContent value="changes" className="mt-0">
                <ChangesTab section={history} pagination={sp.bar} />
              </TabsContent>
            )}
            {showDirectory && (
              <TabsContent value="directory" className="mt-0">
                <SectionCard icon={SearchCheck} title={t('mship.checkTitle')} description={t('ud.dirIntro')}>
                  <UserLdapCompare user={user} globalAdmin={globalAdmin} onFieldUnlocked={afterMutation}
                    onResynced={() => { membership.reload(); afterMutation() }} />
                </SectionCard>
              </TabsContent>
            )}
          </div>
        </Tabs>
      </SheetContent>
    </Sheet>
  )
}
