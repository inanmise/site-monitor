import { useId, useState, useCallback, useEffect, useMemo } from 'react'
import {
  Settings, Search, SlidersHorizontal, Palette, FolderTree, Layers, Mail, CalendarClock, CalendarDays, FileText,
  CloudLightning, BellRing, ShieldAlert, KeyRound, LockKeyhole, Stethoscope, Archive, Database, Headset,
} from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useUrlQuerySync, readUrlParam } from '../../hooks/useUrlQuerySync.js'
import { useIsMobile } from '../../hooks/use-mobile.js'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { NativeSelect, NativeSelectOptGroup, NativeSelectOption } from '@/components/shadcn/native-select'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { Badge } from '@/components/shadcn/badge'
import { Label } from '@/components/shadcn/label'
import { cn } from '@/lib/utils'
import SmtpSettings from './SmtpSettings'
import LdapSettings from './LdapSettings'
import GeneralSettings from './GeneralSettings'
import MonitorGroups from './MonitorGroups'
import PlatformSettings from './PlatformSettings.jsx'   // platform kataloğu (2026-09-22)
import SecretTools from './SecretTools'
import DatabaseInfo from './DatabaseInfo'
import WeeklyAvailabilitySettings from './WeeklyAvailabilitySettings'
import WeeklyReportAccessSettings from './WeeklyReportAccessSettings'
import ConfigHealthCard from './ConfigHealthCard.jsx'
import CertInventoryReportSettings from './CertInventoryReportSettings'
import StormSettings from './StormSettings'
import LoginAnomalySettings from './LoginAnomalySettings'
import DomainDiagnostics from './DomainDiagnostics'
import BrandingSettings from './BrandingSettings'
import RetentionSettings from './RetentionSettings'
import UserPushSettings from './UserPushSettings'
import NocSettings from './NocSettings.jsx'   // 7/24 İzleme Ekibi (2026-09-27)
import ToneBadge from './ToneBadge.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import PageHeader from '../ui/PageHeader.jsx'

/**
 * Ayarlar kabuğu (tam sayfa yeniden tasarım, 2026-09-27 — kullanıcı: "Ayarlar sayfası tüm sayfayı kullanmıyor;
 * shadcn ile zenginleştirilmiş, tam sayfa, mobil duyarlı, profesyonel").
 *
 * <p>Üç yerleşim, tek Tabs (Radix) semantiği:
 *  • ≥1024 px: solda yapışkan, GRUPLU bölüm gezintisi (Platform / Bildirimler / Güvenlik ve erişim / Veri ve bakım),
 *    ikonlu, bölüm arama kutulu; sağda içerik kalan genişliğin TAMAMINI kullanır (920/1100 px tavanı yok).
 *  • 768–1023 px (tablet): yatay kaydırılabilir `line` sekme şeridi; içerik tam genişlik.
 *  • <768 px (telefon): gruplu NativeSelect bölüm seçici (Radix Tabs yerine — 16 sekmelik dikey menü ekranı yerdi).
 * Bölüm yanındaki nokta: Yapılandırma sağlığı kartının (aynı veri, ek istek yok) o bölüme düşen uyarı/sorunu.
 */
const GROUPS = [
  { id: 'platform', labelKey: 'settings.grpPlatform', sections: [
    { id: 'general', labelKey: 'settings.navGeneral', icon: SlidersHorizontal },
    { id: 'branding', labelKey: 'settings.navBranding', icon: Palette },
    { id: 'monitorgroups', labelKey: 'settings.navMonitorGroups', icon: FolderTree },
    { id: 'platforms', labelKey: 'settings.navPlatforms', icon: Layers },
  ] },
  { id: 'notifications', labelKey: 'settings.grpNotifications', sections: [
    { id: 'smtp', labelKey: 'settings.navSmtp', icon: Mail },
    { id: 'weeklyavail', labelKey: 'settings.navWeeklyAvail', icon: CalendarClock },
    { id: 'weeklyreports', labelKey: 'settings.navWeeklyReports', icon: CalendarDays },
    { id: 'certinvreport', labelKey: 'settings.navCertInvReport', icon: FileText },
    { id: 'storm', labelKey: 'settings.navStorm', icon: CloudLightning },
    { id: 'userpush', labelKey: 'settings.navUserPush', icon: BellRing },
    // 7/24 İzleme Ekibi (NOC, 2026-09-27): kapsamlı müdür salt okunur görür (e-postalar gizli) — kilitli değil
    { id: 'noc', labelKey: 'settings.navNoc', icon: Headset },
  ] },
  { id: 'security', labelKey: 'settings.grpSecurity', sections: [
    { id: 'loginanomaly', labelKey: 'settings.navLoginAnomaly', icon: ShieldAlert },
    { id: 'ldap', labelKey: 'settings.navLdap', icon: KeyRound },
    { id: 'secrets', labelKey: 'settings.navSecrets', icon: LockKeyhole },
  ] },
  { id: 'data', labelKey: 'settings.grpData', sections: [
    { id: 'domaindiag', labelKey: 'settings.navDomainDiag', icon: Stethoscope },
    { id: 'retention', labelKey: 'settings.navRetention', icon: Archive },
    { id: 'database', labelKey: 'settings.navDatabase', icon: Database },
  ] },
]
export const SECTIONS = GROUPS.flatMap((g) => g.sections)

const DEFAULT_SECTION = 'general'
const SECTION_IDS = new Set(SECTIONS.map((s) => s.id))

/** ?sec= yalnız bilinen bölüm anahtarlarını kabul eder (App.jsx'teki VALID_TABS deseni). */
function initialSection() {
  const s = readUrlParam('sec')
  return s && SECTION_IDS.has(s) ? s : DEFAULT_SECTION
}

/**
 * Kapsamlı müdür (AD ADMIN, globalAdmin=false) için KİLİTLİ bölümler: kimlik bilgisi/sır taşıyan
 * dört yüzey. Backend aynı dördü requireNotScopedAdmin ile 403'ler; burada 403 dolu bir ekran yerine
 * "yalnız global yönetici" notu çizilir. Sekme listede kalır (var olduğu görülsün).
 */
// Modül görünürlüğü kurumsal bir karardır: kapsamlı müdür kendi takımına açamaz (2026-09-16).
const GLOBAL_ONLY_SECTIONS = new Set(['smtp', 'ldap', 'database', 'secrets', 'weeklyreports'])

/** Durum ağırlığı — bir bölüme birden çok kontrol düşerse en kötüsü gösterilir. */
const STATUS_RANK = { bad: 2, warn: 1 }
const DOT = { bad: 'bg-destructive', warn: 'bg-amber-500' }

/**
 * Medya sorgusu (yalnız yerleşim kipi için — görünüm farkı CSS ile). jsdom'da matchMedia her zaman `false`
 * döner → testler masaüstü (dikey, gruplu liste) kipini görür; tablet kipi testte matchMedia mock'uyla açılır.
 */
function useMediaQuery(query) {
  const [matches, setMatches] = useState(() => typeof window !== 'undefined' && !!window.matchMedia?.(query)?.matches)
  useEffect(() => {
    const mql = window.matchMedia?.(query)
    if (!mql) return undefined
    const onChange = () => setMatches(!!mql.matches)
    onChange()
    mql.addEventListener?.('change', onChange)
    return () => mql.removeEventListener?.('change', onChange)
  }, [query])
  return matches
}

/** Bölüm durum noktası (uyarı/sorun) — yalnız gerektiğinde çizilir; ekran okuyucuya metin. */
function StatusDot({ status, t }) {
  if (!DOT[status]) return null
  const label = status === 'bad' ? t('settings.navStatusBad') : t('settings.navStatusWarn')
  return (
    <span data-slot="settings-nav-status" data-status={status} title={label}
      className={cn('ml-auto size-2 shrink-0 rounded-full', DOT[status])}>
      <span className="sr-only"> — {label}</span>
    </span>
  )
}

export default function AdminSettings({ globalAdmin = true }) {
  const t = useT()
  const [active, setActive] = useState(initialSection)
  // Yapılandırma sağlığı "Aç" → bölüm + alan anahtarı (GeneralSettings kaydırır/odaklar; ISSUE-012)
  const [focusKey, setFocusKey] = useState(null)
  const openSection = useCallback((sec, key) => { setActive(sec); setFocusKey(key ? { key, nonce: Date.now() } : null) }, [])
  // Yapılandırma sağlığı verisi (kart yükler, kabuk yalnız okur — ek istek/poll YOK): gezintideki durum noktaları
  // ve başlıktaki sayaç çipleri buradan.
  const [health, setHealth] = useState(null)
  const [query, setQuery] = useState('')
  // Telefonda 16 bölümlük dikey menü yerine tek seçim kutusu; tablette yatay şerit (yapı farkı → kanca).
  const phone = useIsMobile()
  const tablet = useMediaQuery('(min-width: 768px) and (max-width: 1023px)')
  const desktop = !phone && !tablet
  const pickerId = useId()
  const searchId = useId()

  // Derin bağlantı: /?tab=settings&sec=ldap doğrudan LDAP bölümünü açar. Varsayılan bölümde
  // param silinir (URL temiz kalır); yazma replaceState ile — sekmelerin pushState'i bozulmaz.
  useUrlQuerySync({ sec: active === DEFAULT_SECTION ? null : active })

  /** Bölüm → en kötü yapılandırma durumu (bad/warn); kartın `checks[].tab` alanı bölüm kimliğidir. */
  const statusBySection = useMemo(() => {
    const out = {}
    for (const c of health?.checks || []) {
      if (!SECTION_IDS.has(c.tab) || !STATUS_RANK[c.status]) continue
      if ((STATUS_RANK[c.status] || 0) > (STATUS_RANK[out[c.tab]] || 0)) out[c.tab] = c.status
    }
    return out
  }, [health])

  /** Arama: bölüm adına göre süzer (büyük/küçük harf duyarsız, TR yerel); boş grup gizlenir. */
  const q = query.trim().toLocaleLowerCase('tr')
  const visibleGroups = useMemo(() => {
    if (!q) return GROUPS
    return GROUPS
      .map((g) => ({ ...g, sections: g.sections.filter((s) => t(s.labelKey).toLocaleLowerCase('tr').includes(q)) }))
      .filter((g) => g.sections.length > 0)
  }, [q, t])

  const trigger = (s, extra = '') => {
    const Icon = s.icon
    return (
      <TabsTrigger key={s.id} value={s.id} data-id={s.id} data-status={statusBySection[s.id]}
        className={cn('h-auto flex-none gap-2 rounded-md px-3 py-2 text-left text-[0.92em] font-normal text-foreground',
          'hover:bg-primary/10 hover:text-primary data-[state=active]:bg-primary/10 data-[state=active]:font-semibold data-[state=active]:text-primary',
          'group-data-[variant=default]/tabs-list:data-[state=active]:shadow-none dark:data-[state=active]:border-transparent dark:data-[state=active]:bg-primary/15 dark:data-[state=active]:text-primary',
          extra)}>
        <Icon aria-hidden="true" className="size-4 shrink-0 opacity-80" />
        <span className="min-w-0 flex-1 truncate">{t(s.labelKey)}</span>
        <StatusDot status={statusBySection[s.id]} t={t} />
      </TabsTrigger>
    )
  }

  /*
   * Klavye (shadcn Tabs / Radix, `activationMode="manual"`): oklar/Home/End yalnız ODAĞI taşır, seçimi
   * DEĞİŞTİRMEZ; Enter/Space seçer. Otomatik aktivasyon (odak = seçim) her panelin mount'ta API çağırmasına
   * yol açardı — 16 bölümü ok tuşuyla geçmek onlarca gereksiz istek üretirdi. Roving tabindex Radix'te:
   * gruba tek Tab ile girilir.
   */
  return (
    <div data-slot="settings-page" className="flex min-w-0 flex-col">
      <PageHeader icon={Settings} title={t('settings.pageTitle')} description={t('settings.pageDesc')}
        meta={<>
          <Badge variant="outline" className="font-normal text-muted-foreground">{t('settings.sectionCount', SECTIONS.length)}</Badge>
          {health?.bad > 0 && <ToneBadge tone="danger" className="rounded-full font-bold">{health.bad === 1 ? t('cfg.badOne') : t('cfg.bad', health.bad)}</ToneBadge>}
          {health?.warn > 0 && <ToneBadge tone="warning" className="rounded-full font-bold">{health.warn === 1 ? t('cfg.warnOne') : t('cfg.warn', health.warn)}</ToneBadge>}
        </>} />

      {/* Yapılandırma sağlığı — bölümlerin üstünde tek kart (2026-09-12, #25); yalnız global admin ucu */}
      {globalAdmin && <ConfigHealthCard onOpenSection={openSection} onData={setHealth} />}

      <Tabs value={active} onValueChange={setActive} orientation={desktop ? 'vertical' : 'horizontal'} activationMode="manual"
        // Telefon/tablet: dikey flex sütun → çocuklar GERİLİR (items-start seçiciyi içerik genişliğine büzüyordu);
        // masaüstü: iki sütunlu ızgara, üstten hizalı (gezinti yapışkan).
        className={cn('mt-1 min-w-0 gap-4',
          desktop ? 'grid grid-cols-[240px_minmax(0,1fr)] items-start gap-6 xl:grid-cols-[264px_minmax(0,1fr)]' : 'items-stretch')}>
        {phone ? (
          <div className="flex flex-col gap-1.5 [&>[data-slot=native-select-wrapper]]:w-full" data-slot="settings-picker">
            <Label htmlFor={pickerId} className="text-xs font-semibold text-muted-foreground">{t('settings.navHeader')}</Label>
            <NativeSelect id={pickerId} value={active} onChange={(e) => setActive(e.target.value)} className="h-10 w-full">
              {GROUPS.map((g) => (
                <NativeSelectOptGroup key={g.id} label={t(g.labelKey)}>
                  {g.sections.map((s) => <NativeSelectOption key={s.id} value={s.id}>{t(s.labelKey)}</NativeSelectOption>)}
                </NativeSelectOptGroup>
              ))}
            </NativeSelect>
          </div>
        ) : tablet ? (
          // Tablet: yatay şerit — kendi kabında kayar (sayfa düzeyinde yatay taşma yok)
          <div data-slot="settings-rail" className="w-full min-w-0 overflow-x-auto border-b border-border">
            <TabsList variant="line" aria-label={t('settings.navHeader')} className="h-auto w-max min-w-full justify-start gap-1 p-0 pb-1">
              {SECTIONS.map((s) => trigger(s, 'whitespace-nowrap'))}
            </TabsList>
          </div>
        ) : (
          <aside data-slot="settings-nav"
            className="sticky top-3 flex max-h-[calc(100dvh-1.5rem)] min-w-0 flex-col gap-2 overflow-y-auto rounded-xl border bg-card p-2.5">
            <InputGroup className="h-9 shrink-0">
              <InputGroupInput id={searchId} type="search" value={query} onChange={(e) => setQuery(e.target.value)}
                placeholder={t('settings.navSearch')} aria-label={t('settings.navSearch')} autoComplete="off" />
              <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
            </InputGroup>
            <TabsList aria-label={t('settings.navHeader')} className="w-full items-stretch gap-0.5 bg-transparent p-0">
              {visibleGroups.map((g) => (
                <div key={g.id} role="presentation" data-slot="settings-nav-group" data-group={g.id} className="flex flex-col gap-0.5">
                  <div aria-hidden="true" className="px-2.5 pt-2.5 pb-1 text-[0.68em] font-bold tracking-widest text-muted-foreground/80 uppercase">
                    {t(g.labelKey)}
                  </div>
                  {g.sections.map((s) => trigger(s, 'w-full justify-start whitespace-normal'))}
                </div>
              ))}
              {visibleGroups.length === 0 && (
                <p role="presentation" className="px-2.5 py-3 text-sm text-muted-foreground">{t('settings.navNoMatch')}</p>
              )}
            </TabsList>
          </aside>
        )}

        <TabsContent value={active} className="w-full min-w-0 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-primary"
          {...(phone ? { 'aria-labelledby': pickerId } : {})}>
          {!globalAdmin && GLOBAL_ONLY_SECTIONS.has(active) && (
            <div className="mb-8" data-testid="settings-global-only">
              <AlertBanner tone="warning" title={t('settings.globalOnlyTitle')} role="status">
                {t('settings.globalOnlyBody')}
              </AlertBanner>
            </div>
          )}
          {(globalAdmin || !GLOBAL_ONLY_SECTIONS.has(active)) && <>
          {active === 'general' && <GeneralSettings focusKey={focusKey} />}
          {active === 'branding' && <BrandingSettings />}
          {active === 'monitorgroups' && <MonitorGroups />}
          {active === 'platforms' && <PlatformSettings />}
          {active === 'smtp' && <SmtpSettings />}
          {active === 'weeklyavail' && <WeeklyAvailabilitySettings />}
          {active === 'weeklyreports' && <WeeklyReportAccessSettings />}
          {active === 'certinvreport' && <CertInventoryReportSettings />}
          {active === 'storm' && <StormSettings />}
          {active === 'userpush' && <UserPushSettings />}
          {active === 'noc' && <NocSettings readOnly={!globalAdmin} />}
          {active === 'loginanomaly' && <LoginAnomalySettings />}
          {active === 'ldap' && <LdapSettings />}
          {active === 'domaindiag' && <DomainDiagnostics />}
          {active === 'retention' && <RetentionSettings />}
          {active === 'database' && <DatabaseInfo />}
          {active === 'secrets' && <SecretTools />}
          </>}
        </TabsContent>
      </Tabs>
    </div>
  )
}
