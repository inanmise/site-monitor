import { ChevronRight } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import {
  SidebarGroup, SidebarGroupContent, SidebarGroupLabel, SidebarMenu, SidebarMenuButton, SidebarMenuItem,
  SidebarMenuSub, SidebarMenuSubButton, SidebarMenuSubItem,
} from '@/components/shadcn/sidebar'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { cn } from '@/lib/utils'
import { NAV_ITEM, NAV_SUB_ITEM, NAV_SUB_GROUP, NAV_SECTION_OPEN, NAV_GROUP_LABEL } from './navStyles.js'
import { OpenAlertBadge, CountPill, tabSummary, topLevel } from './NavAlertBadge.jsx'

/**
 * Ana menü — shadcn `nav-main` deseni (sidebar-07, 2026-09-26 yeniden tasarım; tur 2: düğme görünümlü satırlar,
 * bkz. navStyles.js).
 *
 * Her üst bölüm kendi ikonuyla bir SidebarMenuItem'dır. Çocuklu bölüm Collapsible + dönen ok + SidebarMenuSub;
 * etkin sekmenin bölümü kendiliğinden açılır (Nav.jsx), etkin öğe `data-active` + `aria-current="page"`.
 * İkon (daraltılmış) kipte alt liste görünmez → bölüm düğmesi yana açılan bir DropdownMenu'dür (klavye:
 * Enter/Boşluk/↓ açar, oklar gezer, Esc kapatır); etkin sekmeyi içeren bölüm vurgulanır.
 *
 * Alt liste `forceMount` ile HEP DOM'da: ürün turu kapalı bölümdeki `data-tour="nav-tab-*"` kancalarını da bulur.
 * Radix forceMount'ta içeriği açık çizer; kapalıyken gizlemek bizim işimiz → `hidden` (erişilebilirlik ağacı dışı).
 *
 * İzleme alt grupları (2026-11-05): `section` taşıyan sekme bir ALT GRUP başlatır (Erişilebilirlik, Alan Adı ve DNS, İçerik ve
 * Deneyim, Veri ve iş kuralları). Genişken her alt grup kendi akordeonudur — ikon, ad, kapalıyken alt sekmelerin alarm toplamı
 * ve dönen ok; birbirinden bağımsız açılır (açık küme Nav.jsx'te). İkon kipinde yana açılan menü grupları başlıklarla gösterir.
 */

/** Tek (çocuksuz) gezinme öğesi — Pano, Yardım. */
export function NavLeafItem({ item, active, onSelect }) {
  const t = useT()
  const { id, Icon, labelKey } = item
  return (
    <SidebarMenuItem>
      <SidebarMenuButton
        isActive={active}
        aria-current={active ? 'page' : undefined}
        tooltip={t(labelKey)}
        data-tour={`nav-tab-${id}`}
        onClick={() => onSelect(id)}
        className={NAV_ITEM}
      >
        <Icon aria-hidden="true" />
        <span>{t(labelKey)}</span>
      </SidebarMenuButton>
    </SidebarMenuItem>
  )
}

/**
 * Alt sekme satırı (genişken). Aktif alarm rozeti (2026-09-30): satır düğmesinin KARDEŞİ (iç içe düğme yok), sağa yaslı; varsa
 * satır sağdan pay bırakır. Tıklama Alarm Geçmişi'ne (o türe süzülmüş) gider, üzerine gelince özet kartı açılır.
 */
function TabRow({ item, activeTab, onSelect, alertCounts, onGoAlerts }) {
  const t = useT()
  const { id, Icon, labelKey } = item
  const active = activeTab === id
  const summary = tabSummary(alertCounts, id)
  return (
    <SidebarMenuSubItem className="relative">
      <SidebarMenuSubButton
        as="button"
        type="button"
        isActive={active}
        aria-current={active ? 'page' : undefined}
        data-tour={`nav-tab-${id}`}
        onClick={() => onSelect(id)}
        className={cn(NAV_SUB_ITEM, summary && 'pr-10')}
      >
        <Icon aria-hidden="true" />
        <span>{t(labelKey)}</span>
      </SidebarMenuSubButton>
      {summary && (
        <span className="absolute top-1/2 right-1 -translate-y-1/2">
          <OpenAlertBadge id={id} label={t(labelKey)} summary={summary} onGo={(alertId) => onGoAlerts?.(id, alertId)} />
        </span>
      )}
    </SidebarMenuSubItem>
  )
}

/**
 * Bölüm sekmelerini alt gruplara böler: ilk `section`'dan önceki sekmeler gruba girmez (`lead`); her `section` yeni bir grup
 * başlatır ve sonraki sekmeler bir sonraki `section`'a dek o gruptadır.
 */
export function splitSubgroups(items) {
  const lead = []
  const groups = []
  for (const it of items || []) {
    if (it.section) groups.push({ key: it.section, labelKey: it.section, Icon: it.sectionIcon || null, items: [] })
    if (groups.length) groups[groups.length - 1].items.push(it)
    else lead.push(it)
  }
  return { lead, groups }
}

/**
 * Alt grup akordeonu (2026-11-05): başlık bir düğmedir (Enter / Boşluk açar-kapar, `aria-expanded`); kapalıyken alt sekmelerin
 * aktif alarm toplamı görünür, açıkken sekmelerin kendi rozetleri konuşur (çift sayı yok). İçerik `forceMount` + `hidden`:
 * ürün turu kapalı gruptaki `nav-tab-*` kancalarını da bulur. Etkin sekmeyi içeren grubun kutucuğu boyalıdır.
 */
function NavSubGroup({ group, open, onToggle, activeTab, onSelect, alertCounts, onGoAlerts }) {
  const t = useT()
  const label = t(group.labelKey)
  const containsActive = group.items.some((it) => it.id === activeTab)
  const total = sectionAlertTotal(group, alertCounts)
  const GroupIcon = group.Icon
  return (
    <SidebarMenuSubItem data-nav-subgroup={group.key}>
      <Collapsible open={open} onOpenChange={() => onToggle?.(group.key)} className="group/subgroup">
        <CollapsibleTrigger asChild>
          <SidebarMenuSubButton as="button" type="button" data-nav-subgroup-trigger={group.key}
            data-contains-active={containsActive || undefined}
            className={cn(NAV_SUB_GROUP, containsActive && '[&>svg:first-child]:bg-primary/15 [&>svg:first-child]:text-primary')}>
            {GroupIcon ? <GroupIcon aria-hidden="true" /> : null}
            <span className="min-w-0 flex-1 text-left leading-snug whitespace-normal [overflow-wrap:anywhere]">{label}</span>
            {total.count > 0 && !open && (
              <CountPill count={total.count} level={total.level} data-slot="nav-subgroup-alert-count"
                aria-label={t('nav.openAlerts', total.count)} />
            )}
            <ChevronRight aria-hidden="true"
              className="size-4 shrink-0 text-sidebar-foreground/50 transition-transform duration-200 group-data-[state=open]/subgroup:rotate-90 motion-reduce:transition-none" />
          </SidebarMenuSubButton>
        </CollapsibleTrigger>
        <CollapsibleContent forceMount hidden={!open}>
          <SidebarMenuSub data-slot="nav-subgroup-list" className="mx-0 mt-0.5 mr-0 ml-3 pr-0 pl-2">
            {group.items.map((it) => (
              <TabRow key={it.id} item={it} activeTab={activeTab} onSelect={onSelect} alertCounts={alertCounts} onGoAlerts={onGoAlerts} />
            ))}
          </SidebarMenuSub>
        </CollapsibleContent>
      </Collapsible>
    </SidebarMenuSubItem>
  )
}

/** Bölümün alt listesi: önce gruba girmeyen sekmeler, sonra alt grup akordeonları. */
function SubItems({ items, activeTab, onSelect, alertCounts, onGoAlerts, openSubs, onToggleSub }) {
  const { lead, groups } = splitSubgroups(items)
  return (
    <>
      {lead.map((it) => (
        <TabRow key={it.id} item={it} activeTab={activeTab} onSelect={onSelect} alertCounts={alertCounts} onGoAlerts={onGoAlerts} />
      ))}
      {groups.map((g) => (
        <NavSubGroup key={g.key} group={g} open={(openSubs || []).includes(g.key)} onToggle={onToggleSub}
          activeTab={activeTab} onSelect={onSelect} alertCounts={alertCounts} onGoAlerts={onGoAlerts} />
      ))}
    </>
  )
}

/** Bölümün alt sekmelerindeki aktif alarm toplamı ve en yüksek seviyesi (bölüm başlığı / daraltılmış menü rozeti). */
export function sectionAlertTotal(section, alertCounts) {
  let count = 0
  let level = 'other'
  const rank = { other: 0, warning: 1, high: 2, critical: 3 }
  for (const it of section?.items || []) {
    const s = tabSummary(alertCounts, it.id)
    if (!s) continue
    count += Number(s.count)
    const l = topLevel(s)
    if (rank[l] > rank[level]) level = l
  }
  return { count, level }
}

/** İkon kipinde bölümün yana açılan menüsü (DropdownMenu). Alt sekmede aktif alarm sayısı sağda (rozet). */
function SectionFlyout({ section, activeTab, onSelect, alertCounts }) {
  const t = useT()
  // Ara başlıklara göre kümeler: [{ caption, items }]
  const chunks = []
  for (const it of section.items) {
    if (it.section || chunks.length === 0) chunks.push({ caption: it.section || null, Icon: it.sectionIcon || null, items: [] })
    chunks[chunks.length - 1].items.push(it)
  }
  return (
    <DropdownMenuContent side="right" align="start" sideOffset={8} className="z-(--z-menu) min-w-56">
      <DropdownMenuLabel className="flex items-center gap-2">
        <section.Icon aria-hidden="true" className="size-4 text-muted-foreground" />
        {t(section.labelKey)}
      </DropdownMenuLabel>
      <DropdownMenuSeparator />
      {chunks.map((c, ci) => (
        <DropdownMenuGroup key={c.caption || ci}>
          {c.caption && (
            <DropdownMenuLabel className="flex items-center gap-1.5 pt-2 pb-1 text-[11px] font-medium text-muted-foreground">
              {c.Icon && <c.Icon aria-hidden="true" className="size-3.5" />}
              {t(c.caption)}
            </DropdownMenuLabel>
          )}
          {c.items.map(({ id, Icon, labelKey }) => {
            const active = activeTab === id
            const summary = tabSummary(alertCounts, id)
            return (
              <DropdownMenuItem key={id} onSelect={() => onSelect(id)} aria-current={active ? 'page' : undefined}
                data-active={active || undefined}
                className="data-[active]:bg-primary/10 data-[active]:font-semibold data-[active]:text-primary data-[active]:[&>svg]:text-primary">
                <Icon aria-hidden="true" />
                <span className="min-w-0 flex-1 truncate">{t(labelKey)}</span>
                {summary && <CountPill count={summary.count} level={topLevel(summary)} className="ml-2"
                  aria-label={t('nav.openAlerts', summary.count)} />}
              </DropdownMenuItem>
            )
          })}
        </DropdownMenuGroup>
      ))}
    </DropdownMenuContent>
  )
}

/** Çocuklu bölüm: genişken Collapsible (akordeon), ikon kipinde yana açılan menü. */
function NavSection({ section, activeTab, open, onToggle, onSelect, collapsed, alertCounts, onGoAlerts, openSubs, onToggleSub }) {
  const t = useT()
  const { key, Icon, labelKey, items } = section
  const containsActive = items.some((it) => it.id === activeTab)
  const label = t(labelKey)
  // Bölüm toplamı (2026-09-30): kapalı akordeonda / ikon kipinde alt sekmelerin aktif alarmları görünür kalsın.
  const total = sectionAlertTotal(section, alertCounts)
  // Etkin sekmeyi içeren bölümün kutucuğu boyalı (açıkken çocuk zaten vurgulu; kapalıyken "buradasın" izi)
  const buttonClass = cn(NAV_ITEM, NAV_SECTION_OPEN,
    containsActive && '[&>svg:first-child]:bg-primary/15 [&>svg:first-child]:text-primary')

  return (
    <SidebarMenuItem>
      <Collapsible open={open} onOpenChange={() => { if (!collapsed) onToggle(key) }} className="group/collapsible">
        {collapsed ? (
          <DropdownMenu modal={false}>
            <DropdownMenuTrigger asChild>
              {/* İkon kipi: bölümün etkin sekmeyi içerdiği görünür olsun (alt liste gizli) */}
              <SidebarMenuButton tooltip={total.count > 0 ? `${label} · ${t('nav.openAlerts', total.count)}` : label}
                isActive={containsActive} data-nav-section-trigger={key} className={cn(buttonClass, 'relative')}>
                <Icon aria-hidden="true" />
                <span>{label}</span>
                {total.count > 0 && (
                  // İkon kipi: sayı ikonun köşesinde küçük rozet (menü daralınca alarm görünmez kalmasın)
                  <CountPill count={total.count} level={total.level} data-slot="nav-section-alert-count"
                    className="absolute -top-0.5 -right-0.5 h-4 min-w-4 px-1 text-[9px]" aria-label={t('nav.openAlerts', total.count)} />
                )}
              </SidebarMenuButton>
            </DropdownMenuTrigger>
            <SectionFlyout section={section} activeTab={activeTab} onSelect={onSelect} alertCounts={alertCounts} />
          </DropdownMenu>
        ) : (
          <CollapsibleTrigger asChild>
            <SidebarMenuButton tooltip={label} data-nav-section-trigger={key} className={buttonClass}>
              <Icon aria-hidden="true" />
              <span className="min-w-0 flex-1 truncate">{label}</span>
              {total.count > 0 && !open && (
                // Akordeon kapalıyken toplam görünür; açıkken sekmelerin kendi rozetleri konuşur (çift sayı yok)
                <CountPill count={total.count} level={total.level} data-slot="nav-section-alert-count" className="ml-auto"
                  aria-label={t('nav.openAlerts', total.count)} />
              )}
              <ChevronRight aria-hidden="true"
                className={cn('size-4 text-sidebar-foreground/50 transition-transform duration-200 group-data-[state=open]/collapsible:rotate-90 motion-reduce:transition-none',
                  total.count > 0 && !open ? 'ml-1' : 'ml-auto')} />
            </SidebarMenuButton>
          </CollapsibleTrigger>
        )}
        <CollapsibleContent forceMount hidden={!open}>
          <SidebarMenuSub className="mr-0 pr-0">
            <SubItems items={items} activeTab={activeTab} onSelect={onSelect} alertCounts={alertCounts} onGoAlerts={onGoAlerts}
              openSubs={openSubs} onToggleSub={onToggleSub} />
          </SidebarMenuSub>
        </CollapsibleContent>
      </Collapsible>
    </SidebarMenuItem>
  )
}

/**
 * @param sections     [{ key, Icon, labelKey, items:[{id,Icon,labelKey,section?}] } | { id, Icon, labelKey } (yaprak)]
 * @param alertCounts  sekme id → açık alarm özeti (useOpenAlerts.byTab); yoksa rozet çizilmez
 * @param onGoAlerts   (tabId, alertId?) → Alarm Geçmişi'ne git (o türe süzülmüş; alertId verilirse o alarm açılır)
 * @param openSubs     açık alt grup anahtarları (`section`); onToggleSub(anahtar) aç / kapa
 */
export default function NavMain({ label, sections, activeTab, openSection, onToggle, onSelect, collapsed, alertCounts = null, onGoAlerts = null,
  openSubs = [], onToggleSub = null }) {
  return (
    <SidebarGroup>
      <SidebarGroupLabel className={NAV_GROUP_LABEL}>{label}</SidebarGroupLabel>
      <SidebarGroupContent>
        <SidebarMenu>
          {sections.map((s) => (s.items
            ? <NavSection key={s.key} section={s} activeTab={activeTab} open={openSection === s.key} onToggle={onToggle}
                onSelect={onSelect} collapsed={collapsed} alertCounts={alertCounts} onGoAlerts={onGoAlerts}
                openSubs={openSubs} onToggleSub={onToggleSub} />
            : <NavLeafItem key={s.id} item={s} active={activeTab === s.id} onSelect={onSelect} />))}
        </SidebarMenu>
      </SidebarGroupContent>
    </SidebarGroup>
  )
}
