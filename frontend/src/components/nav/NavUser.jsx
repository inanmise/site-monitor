import { useRef, useState } from 'react'
import {
  ChevronsUpDown, Users, MonitorSmartphone, Settings, Bug, Lock, Compass, Sun, Moon, Languages, LogOut, BookOpenText,
  Clock, ShieldAlert, PanelLeftClose, PanelLeftOpen, Keyboard,
} from 'lucide-react'
import { useT, useLanguage } from '../../i18n/index.jsx'
import { useTheme } from '../../i18n/theme.jsx'
import { formatDate } from '../../api/client'
import { adSoyadInitials, avatarStyleFor } from '../ui/TeamMemberCards.jsx'
import { SystemRoleBadge } from '../admin/ToneBadge.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { SidebarMenu, SidebarMenuButton, SidebarMenuItem, useSidebar } from '@/components/shadcn/sidebar'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem, DropdownMenuLabel, DropdownMenuRadioGroup,
  DropdownMenuRadioItem, DropdownMenuSeparator, DropdownMenuShortcut, DropdownMenuSub, DropdownMenuSubContent,
  DropdownMenuSubTrigger, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'
import { Avatar, AvatarFallback, AvatarImage } from '@/components/shadcn/avatar'
import { Button } from '@/components/shadcn/button'
import { Separator } from '@/components/shadcn/separator'
import { cn } from '@/lib/utils'

/** Sistem rolü → rozet metni (USER rozetsiz). `t()` çağrıları literal (i18n-used-keys kapısı). */
function roleLabel(t, role) {
  if (role === 'ADMIN') return t('nav.roleAdmin')
  if (role === 'TEAM_ADMIN') return t('nav.roleTeamAdmin')
  if (role === 'AUDIT') return t('nav.roleAudit')
  return null
}

/** Dil adları kendi dillerinde yazılır (çevrilmez). */
const LANGS = [{ value: 'tr', label: 'Türkçe' }, { value: 'en', label: 'English' }]

/** Kullanıcının fotoğrafı (/api/me/photo) ya da AD baş harfleri; renk kimliği üye kartlarıyla aynı (TeamMemberCards). */
function UserAvatar({ username, profile, className }) {
  const [err, setErr] = useState(false)
  const m = { username, display_name: profile?.display_name, first_name: profile?.first_name, last_name: profile?.last_name }
  return (
    <Avatar className={cn('rounded-lg', className)}>
      {!err && <AvatarImage src="/api/me/photo" alt="" className="object-cover" onError={() => setErr(true)} />}
      <AvatarFallback className="rounded-lg font-semibold" style={avatarStyleFor(username)}>{adSoyadInitials(m)}</AvatarFallback>
    </Avatar>
  )
}

/** Menü satırı ikonu: kenar çubuğuyla aynı "kutucuk" dili (küçük yuvarlak zemin). */
function IconTile({ children, className }) {
  return (
    <span aria-hidden="true" data-slot="user-menu-icon"
      className={cn('flex size-7 shrink-0 items-center justify-center rounded-md bg-muted text-foreground/80 [&>svg]:size-4', className)}>
      {children}
    </span>
  )
}

// ── Masaüstü menü parçaları (modül düzeyinde: yeniden çizimde kimlik sabit, odak kaybolmaz) ──
function Item({ icon, children, shortcut, className, ...rest }) {
  return (
    <DropdownMenuItem className={cn('h-9 gap-2.5 rounded-md px-1.5', className)} {...rest}>
      <IconTile>{icon}</IconTile>
      <span className="min-w-0 flex-1 truncate">{children}</span>
      {shortcut && <DropdownMenuShortcut className="mr-1 shrink-0">{shortcut}</DropdownMenuShortcut>}
    </DropdownMenuItem>
  )
}
function GroupLabel({ children }) {
  return <DropdownMenuLabel className="px-2 pt-1.5 pb-0.5 text-[10px] font-semibold tracking-wider text-muted-foreground uppercase">{children}</DropdownMenuLabel>
}
/**
 * Radyo seçeneği: etkin olan noktayla işaretli; seçim menüyü KAPATMAZ (değişiklik anında görülsün).
 * `busy`: seçilen dilin sözlüğü iniyor (İngilizce ayrı chunk, 2026-10-02 öneri 22) — satır sonunda kısa meşgul göstergesi.
 */
function Radio({ value, children, busy = false, busyLabel }) {
  return (
    <DropdownMenuRadioItem value={value} className="h-9 rounded-md" onSelect={(e) => e.preventDefault()}
      aria-busy={busy || undefined}>
      {children}
      {busy && <span className="ml-auto flex items-center"><Spinner size={14} label={busyLabel} /></span>}
    </DropdownMenuRadioItem>
  )
}
// ── Telefon sayfası parçaları ──
function Row({ icon, children, onSelect, className, ...rest }) {
  return (
    <Button type="button" variant="ghost" onClick={onSelect}
      className={cn('h-11 w-full justify-start gap-3 rounded-lg px-2 text-[15px] font-normal', className)} {...rest}>
      <IconTile className="size-8">{icon}</IconTile>
      <span className="min-w-0 flex-1 truncate text-left">{children}</span>
    </Button>
  )
}
function Caption({ children }) {
  return <div className="px-2 pt-2 pb-1 text-[10px] font-semibold tracking-wider text-muted-foreground uppercase">{children}</div>
}

/**
 * Kullanıcı menüsü (2026-09-27 yeniden tasarım — kullanıcı isteği: "profesyonel, okunaklı, mobil duyarlı").
 *
 * Tetik: alt bilgideki kullanıcı kartı (avatar + ad soyad + rol rozeti + takım). Masaüstü/tablet: shadcn DropdownMenu
 * (ikon kipinde de sağa açılır). Telefon: başparmağa yakın ALT Sheet (tutamaç, 40 px+ satırlar, güvenli alan dolgusu);
 * bir eylem seçilince önce bu sayfa kapanır, gezinme Nav'ın `go`'suyla çekmeceyi de kapatır.
 *
 * İçerik: başlık kartı ("Oturum açan" · büyük avatar · ad soyad · e-posta/kullanıcı adı · rol + takım rozeti ·
 * son giriş satırı — veri yalnız App'in /me yükünden, ek istek YOK) → Hesap (takımlarım, cihaz geçmişi, Ayarlar [ADMIN],
 * parola) → Yardım ve destek (kılavuz, ürün turu, [masaüstü] klavye kısayolları, sorun bildir) → Tercihler (Tema ve Dil
 * alt menüde radyo — etkin olan işaretli; kenar çubuğu Ctrl+B) → en altta Çıkış (yıkıcı). Sol şerit YOK.
 * Klavye kısayolları (öneri 24) yalnız masaüstü menüsünde — telefon sayfası dokunmatik içindir.
 */
export default function NavUser({
  username, profile = null, teamName, myTeams = [], systemRole, loginInfo, isMobile,
  onGo, onOpenTeams, onReportIssue, onChangePassword, onStartTour, onShowShortcuts, onLogout,
}) {
  const t = useT()
  // Kısayol listesi menüden açılınca Radix menünün odak iadesi (kapanış animasyonu sonunda tetiğe) pencerenin
  // odağını çalmasın — MonitorPageHeader / KebabMenu kalıbı; diğer öğelerin davranışı değişmez.
  const openedDialog = useRef(false)
  // langPending: seçilen dilin sözlüğü iniyor (İngilizce ayrı chunk) — dil denetiminde kısa meşgul göstergesi
  const { lang, toggle: toggleLang, pending: langPending } = useLanguage()
  const { theme, toggle: toggleTheme } = useTheme()
  const { state: sidebarState, toggleSidebar } = useSidebar()
  const [sheetOpen, setSheetOpen] = useState(false)
  const role = roleLabel(t, systemRole)
  const isAdmin = systemRole === 'ADMIN'
  // Çok takımlı kullanıcı (2026-09-18): tetikte "N takım", menüde "Dahil olduğum takımlar" → üye penceresi.
  const multiTeam = Array.isArray(myTeams) && myTeams.length > 1
  const primaryTeam = (Array.isArray(myTeams) && (myTeams.find((tm) => tm.name === teamName) || myTeams[0])) || null
  const fullName = (profile?.display_name || [profile?.first_name, profile?.last_name].filter(Boolean).join(' ') || username || '').trim() || username
  const secondary = profile?.email || (fullName !== username ? username : null)
  const prevLogin = loginInfo?.prev_login_at ? formatDate(loginInfo.prev_login_at) : null
  const failed = Number(loginInfo?.failed_before_login ?? 0)
  const collapsed = sidebarState === 'collapsed'

  const setTheme = (v) => { if (v && v !== theme) toggleTheme() }
  const setLang = (v) => { if (v && v !== lang) toggleLang() }
  /** Telefon sayfasından eylem: önce sayfa kapanır, sonra eylem (gezinme çekmeceyi Nav'da kapatır). */
  const pick = (fn) => () => { setSheetOpen(false); fn?.() }

  const teamLine = multiTeam
    ? <span className="flex min-w-0 items-center gap-1 truncate text-xs text-muted-foreground" title={myTeams.map((tm) => tm.name).join(', ')}><Users className="size-3 shrink-0" aria-hidden="true" /> {t('nav.teamsCount', myTeams.length)}</span>
    : teamName ? <span className="truncate text-xs text-muted-foreground">{teamName}</span> : null

  // ── Başlık kartı (iki yüzey de aynı) ──
  const header = (
    <div data-slot="user-menu-header" className="flex flex-col gap-2.5 px-3 py-3 text-left">
      <span className="text-[10px] font-semibold tracking-wider text-muted-foreground uppercase">{t('nav.signedInAs')}</span>
      <div className="flex items-center gap-3">
        <UserAvatar username={username} profile={profile} className="size-11 text-sm" />
        <div className="grid min-w-0 flex-1 leading-tight">
          <span className="truncate text-[15px] font-semibold text-foreground">{fullName}</span>
          {secondary && <span className="truncate text-xs text-muted-foreground">{secondary}</span>}
        </div>
      </div>
      {(role || teamName) && (
        <div className="flex flex-wrap items-center gap-1.5">
          {role && <SystemRoleBadge role={systemRole} className="h-5 px-1.5 text-[10px]">{role}</SystemRoleBadge>}
          {teamName && <TeamBadge teamId={primaryTeam?.id} teamName={teamName} size={12} />}
        </div>
      )}
      {prevLogin && (
        <div className="flex flex-col gap-0.5 text-xs text-muted-foreground">
          <span className="flex items-center gap-1.5"><Clock className="size-3.5 shrink-0" aria-hidden="true" />{t('nav.lastSignIn')}: <span className="font-medium text-foreground tabular-nums">{prevLogin}</span></span>
          {failed > 0 && <span className="flex items-center gap-1.5 font-semibold text-warning"><ShieldAlert className="size-3.5 shrink-0" aria-hidden="true" />{t('lastLogin.failedShort', failed)}</span>}
        </div>
      )}
    </div>
  )

  // ── Tetik: alt bilgideki kullanıcı kartı ──
  const trigger = (
    <SidebarMenuButton size="lg" data-tour="nav-user" data-slot="user-menu-trigger" tooltip={t('nav.userSettings')}
      aria-label={t('nav.userMenu')}
      onClick={isMobile ? () => setSheetOpen(true) : undefined}
      aria-haspopup={isMobile ? 'dialog' : undefined} aria-expanded={isMobile ? sheetOpen : undefined}
      className={cn('rounded-lg border border-sidebar-border/80 bg-background/60 hover:bg-sidebar-accent',
        'dark:bg-sidebar-accent/40 dark:hover:bg-sidebar-accent',
        'data-[state=open]:bg-sidebar-accent data-[state=open]:text-sidebar-accent-foreground',
        'group-data-[collapsible=icon]:border-0 group-data-[collapsible=icon]:bg-transparent')}>
      <UserAvatar username={username} profile={profile} className="size-8 text-xs" />
      <span className="grid min-w-0 flex-1 text-left leading-tight">
        <span className="flex min-w-0 items-center gap-1.5">
          <span className="truncate text-sm font-medium text-sidebar-accent-foreground">{fullName}</span>
          {role && <SystemRoleBadge role={systemRole} className="h-4 shrink-0 px-1.5 text-[10px]">{role}</SystemRoleBadge>}
        </span>
        {teamLine}
      </span>
      <ChevronsUpDown className="ml-auto size-4 text-sidebar-foreground/60" aria-hidden="true" />
    </SidebarMenuButton>
  )

  // ── Telefon: alt Sheet ──
  if (isMobile) {
    return (
      <SidebarMenu>
        <SidebarMenuItem>
          {trigger}
          <Sheet open={sheetOpen} onOpenChange={setSheetOpen}>
            <SheetContent side="bottom" data-slot="user-menu-sheet" showCloseButton={false}
              className="z-(--z-menu) max-h-[88dvh] gap-0 overflow-y-auto rounded-t-2xl p-0 pb-[max(env(safe-area-inset-bottom),0.5rem)]">
              {/* Tutamaç: sayfanın alttan açıldığını söyler; kapatma dış tıklama / Esc / eylem seçimi */}
              <div aria-hidden="true" className="mx-auto mt-2 h-1.5 w-10 shrink-0 rounded-full bg-muted-foreground/30" />
              <SheetHeader className="gap-0 p-0">
                <SheetTitle className="sr-only">{t('nav.userMenu')}</SheetTitle>
                <SheetDescription className="sr-only">{t('nav.userSettings')}</SheetDescription>
                {header}
              </SheetHeader>
              <Separator />
              <div className="flex flex-col px-2 pb-1">
                <div data-slot="user-menu-group" data-group="account">
                  <Caption>{t('nav.menuAccount')}</Caption>
                  {multiTeam && <Row icon={<Users />} onSelect={pick(onOpenTeams)}>{t('nav.myTeams', myTeams.length)}</Row>}
                  <Row icon={<MonitorSmartphone />} onSelect={pick(() => onGo('myactivity'))}>{t('dev.navLink')}</Row>
                  {isAdmin && <Row icon={<Settings />} onSelect={pick(() => onGo('settings'))}>{t('nav.settings')}</Row>}
                  <Row icon={<Lock />} onSelect={pick(onChangePassword)}>{t('nav.changePassword')}</Row>
                </div>
                <div data-slot="user-menu-group" data-group="help">
                  <Caption>{t('nav.menuHelp')}</Caption>
                  <Row icon={<BookOpenText />} onSelect={pick(() => onGo('help'))}>{t('nav.userGuide')}</Row>
                  <Row icon={<Compass />} onSelect={pick(onStartTour)}>{t('tour.restart')}</Row>
                  <Row icon={<Bug />} onSelect={pick(onReportIssue)}>{t('nav.reportIssue')}</Row>
                </div>
                <div data-slot="user-menu-group" data-group="preferences">
                  <Caption>{t('nav.menuPreferences')}</Caption>
                  {/* Tema / Dil: etkin seçenek görünür (ToggleGroup, SegmentedControl); değişim anında uygulanır, sayfa açık kalır */}
                  <div className="flex min-h-11 items-center gap-3 px-2 py-1">
                    <IconTile className="size-8">{theme === 'dark' ? <Moon /> : <Sun />}</IconTile>
                    <span className="min-w-0 flex-1 text-[15px]">{t('nav.theme')}</span>
                    <SegmentedControl ariaLabel={t('nav.theme')} value={theme} onChange={setTheme}
                      options={[{ value: 'light', label: t('nav.themeLight'), icon: Sun }, { value: 'dark', label: t('nav.themeDark'), icon: Moon }]} />
                  </div>
                  <div className="flex min-h-11 items-center gap-3 px-2 py-1">
                    <IconTile className="size-8"><Languages /></IconTile>
                    <span className="flex min-w-0 flex-1 items-center gap-2 text-[15px]">
                      {t('nav.language')}
                      {langPending && <Spinner size={14} label={t('nav.langLoading')} />}
                    </span>
                    <SegmentedControl ariaLabel={t('nav.language')} value={lang} onChange={setLang} options={LANGS} />
                  </div>
                </div>
                <Separator className="my-1" />
                <Row icon={<LogOut />} onSelect={pick(onLogout)} data-slot="user-menu-logout" data-variant="destructive"
                  className="text-destructive hover:bg-destructive/10 hover:text-destructive [&_[data-slot=user-menu-icon]]:bg-destructive/10 [&_[data-slot=user-menu-icon]]:text-destructive">
                  {t('nav.logout')}
                </Row>
              </div>
            </SheetContent>
          </Sheet>
        </SidebarMenuItem>
      </SidebarMenu>
    )
  }

  // ── Masaüstü / tablet: DropdownMenu (ikon kipinde de sağa açılır) ──
  return (
    <SidebarMenu>
      <SidebarMenuItem>
        <DropdownMenu modal={false}>
          <DropdownMenuTrigger asChild>{trigger}</DropdownMenuTrigger>
          <DropdownMenuContent side="right" align="end" sideOffset={8} collisionPadding={8}
            onCloseAutoFocus={(e) => { if (openedDialog.current) { openedDialog.current = false; e.preventDefault() } }}
            className="z-(--z-menu) flex max-h-[calc(100vh-1rem)] w-72 flex-col overflow-y-auto rounded-xl p-0 shadow-lg">
            <DropdownMenuLabel className="p-0 font-normal">{header}</DropdownMenuLabel>
            <DropdownMenuSeparator className="my-0" />
            <div className="flex flex-col p-1">
              <DropdownMenuGroup data-slot="user-menu-group" data-group="account">
                <GroupLabel>{t('nav.menuAccount')}</GroupLabel>
                {multiTeam && <Item icon={<Users />} onSelect={onOpenTeams}>{t('nav.myTeams', myTeams.length)}</Item>}
                {/* Son giriş satırının doğal devamı: tüm cihaz geçmişi (Etkinliklerim) */}
                <Item icon={<MonitorSmartphone />} onSelect={() => onGo('myactivity')}>{t('dev.navLink')}</Item>
                {/* Ayarlar: rol ADMIN — global admin VE kapsamlı müdür (2026-09-10 ürün kararı; sır yüzeyleri AdminSettings
                    içinde kilitli, backend GLOBAL_ONLY/requireNotScopedAdmin uygular). */}
                {isAdmin && <Item icon={<Settings />} onSelect={() => onGo('settings')}>{t('nav.settings')}</Item>}
                <Item icon={<Lock />} onSelect={onChangePassword}>{t('nav.changePassword')}</Item>
              </DropdownMenuGroup>
              <DropdownMenuSeparator />
              <DropdownMenuGroup data-slot="user-menu-group" data-group="help">
                <GroupLabel>{t('nav.menuHelp')}</GroupLabel>
                <Item icon={<BookOpenText />} onSelect={() => onGo('help')}>{t('nav.userGuide')}</Item>
                {/* Ürün turu (2026-09-13): kapatan kullanıcı istediğinde yeniden bulabilsin */}
                <Item icon={<Compass />} onSelect={onStartTour}>{t('tour.restart')}</Item>
                {/* Klavye kısayolları listesi (öneri 24) — `?` ile de açılır */}
                {onShowShortcuts && (
                  <Item icon={<Keyboard />} data-slot="user-menu-shortcuts"
                    onSelect={() => { openedDialog.current = true; onShowShortcuts() }}>{t('shortcuts.title')}</Item>
                )}
                {/* Kalıcı "Sorun Bildir" — çökme OLMAYAN sorunlar (yanlış veri, yavaşlık, görsel bozukluk) */}
                <Item icon={<Bug />} onSelect={onReportIssue}>{t('nav.reportIssue')}</Item>
              </DropdownMenuGroup>
              <DropdownMenuSeparator />
              <DropdownMenuGroup data-slot="user-menu-group" data-group="preferences">
                <GroupLabel>{t('nav.menuPreferences')}</GroupLabel>
                {/* Tema / Dil: alt menüde radyo — etkin olan işaretli, geçerli değer satırda görünür */}
                <DropdownMenuSub>
                  <DropdownMenuSubTrigger className="h-9 gap-2.5 rounded-md px-1.5" data-slot="user-menu-theme">
                    <IconTile>{theme === 'dark' ? <Moon /> : <Sun />}</IconTile>
                    <span className="min-w-0 flex-1">{t('nav.theme')}</span>
                    <span className="mr-1 text-xs text-muted-foreground">{theme === 'dark' ? t('nav.themeDark') : t('nav.themeLight')}</span>
                  </DropdownMenuSubTrigger>
                  <DropdownMenuSubContent className="z-(--z-menu) min-w-40" sideOffset={6}>
                    <DropdownMenuRadioGroup value={theme} onValueChange={setTheme}>
                      <Radio value="light">{t('nav.themeLight')}</Radio>
                      <Radio value="dark">{t('nav.themeDark')}</Radio>
                    </DropdownMenuRadioGroup>
                  </DropdownMenuSubContent>
                </DropdownMenuSub>
                <DropdownMenuSub>
                  <DropdownMenuSubTrigger className="h-9 gap-2.5 rounded-md px-1.5" data-slot="user-menu-language">
                    <IconTile><Languages /></IconTile>
                    <span className="min-w-0 flex-1">{t('nav.language')}</span>
                    <span className="mr-1 text-xs text-muted-foreground">{LANGS.find((l) => l.value === lang)?.label}</span>
                  </DropdownMenuSubTrigger>
                  <DropdownMenuSubContent className="z-(--z-menu) min-w-40" sideOffset={6}>
                    <DropdownMenuRadioGroup value={lang} onValueChange={setLang}>
                      {LANGS.map((l) => (
                        <Radio key={l.value} value={l.value} busy={langPending === l.value} busyLabel={t('nav.langLoading')}>{l.label}</Radio>
                      ))}
                    </DropdownMenuRadioGroup>
                  </DropdownMenuSubContent>
                </DropdownMenuSub>
                {/* Gerçek kısayol: Ctrl/⌘+B (sidebar.jsx) — uydurma kısayol yok */}
                <Item icon={collapsed ? <PanelLeftOpen /> : <PanelLeftClose />} onSelect={toggleSidebar} shortcut="Ctrl B">
                  {collapsed ? t('nav.expandSidebar') : t('nav.collapseSidebar')}
                </Item>
              </DropdownMenuGroup>
              <DropdownMenuSeparator />
              <Item icon={<LogOut />} onSelect={onLogout} variant="destructive" data-slot="user-menu-logout"
                className="[&_[data-slot=user-menu-icon]]:bg-destructive/10 [&_[data-slot=user-menu-icon]]:text-destructive">
                {t('nav.logout')}
              </Item>
            </div>
          </DropdownMenuContent>
        </DropdownMenu>
      </SidebarMenuItem>
    </SidebarMenu>
  )
}
