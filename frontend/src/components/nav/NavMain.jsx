import { Fragment } from 'react'
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
import { NAV_ITEM, NAV_SUB_ITEM, NAV_SECTION_OPEN, NAV_GROUP_LABEL } from './navStyles.js'

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

/** Alt sekme satırı (genişken). Ara başlık (`section`) önüne tıklanmayan küçük bir başlık çizer. */
function SubItems({ items, activeTab, onSelect }) {
  const t = useT()
  return items.map(({ id, Icon, labelKey, section }) => {
    const active = activeTab === id
    return (
      <Fragment key={id}>
        {section && (
          // Ara başlık: denetim değil, grup adı (grup etiketinin küçük kardeşi). Klavye odağına girmez.
          <li role="presentation" data-nav-section=""
              className="flex h-6 items-end px-2 pb-0.5 text-[10px] font-semibold tracking-wider text-sidebar-foreground/55 uppercase [&:not(:first-child)]:mt-1.5">
            {t(section)}
          </li>
        )}
        <SidebarMenuSubItem>
          <SidebarMenuSubButton
            as="button"
            type="button"
            isActive={active}
            aria-current={active ? 'page' : undefined}
            data-tour={`nav-tab-${id}`}
            onClick={() => onSelect(id)}
            className={NAV_SUB_ITEM}
          >
            <Icon aria-hidden="true" />
            <span>{t(labelKey)}</span>
          </SidebarMenuSubButton>
        </SidebarMenuSubItem>
      </Fragment>
    )
  })
}

/** İkon kipinde bölümün yana açılan menüsü (DropdownMenu). */
function SectionFlyout({ section, activeTab, onSelect }) {
  const t = useT()
  // Ara başlıklara göre kümeler: [{ caption, items }]
  const chunks = []
  for (const it of section.items) {
    if (it.section || chunks.length === 0) chunks.push({ caption: it.section || null, items: [] })
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
            <DropdownMenuLabel className="pt-2 pb-1 text-[11px] font-medium text-muted-foreground">{t(c.caption)}</DropdownMenuLabel>
          )}
          {c.items.map(({ id, Icon, labelKey }) => {
            const active = activeTab === id
            return (
              <DropdownMenuItem key={id} onSelect={() => onSelect(id)} aria-current={active ? 'page' : undefined}
                data-active={active || undefined}
                className="data-[active]:bg-primary/10 data-[active]:font-semibold data-[active]:text-primary data-[active]:[&>svg]:text-primary">
                <Icon aria-hidden="true" />
                {t(labelKey)}
              </DropdownMenuItem>
            )
          })}
        </DropdownMenuGroup>
      ))}
    </DropdownMenuContent>
  )
}

/** Çocuklu bölüm: genişken Collapsible (akordeon), ikon kipinde yana açılan menü. */
function NavSection({ section, activeTab, open, onToggle, onSelect, collapsed }) {
  const t = useT()
  const { key, Icon, labelKey, items } = section
  const containsActive = items.some((it) => it.id === activeTab)
  const label = t(labelKey)
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
              <SidebarMenuButton tooltip={label} isActive={containsActive} data-nav-section-trigger={key} className={buttonClass}>
                <Icon aria-hidden="true" />
                <span>{label}</span>
              </SidebarMenuButton>
            </DropdownMenuTrigger>
            <SectionFlyout section={section} activeTab={activeTab} onSelect={onSelect} />
          </DropdownMenu>
        ) : (
          <CollapsibleTrigger asChild>
            <SidebarMenuButton tooltip={label} data-nav-section-trigger={key} className={buttonClass}>
              <Icon aria-hidden="true" />
              <span className="min-w-0 flex-1 truncate">{label}</span>
              <ChevronRight aria-hidden="true"
                className="ml-auto size-4 text-sidebar-foreground/50 transition-transform duration-200 group-data-[state=open]/collapsible:rotate-90 motion-reduce:transition-none" />
            </SidebarMenuButton>
          </CollapsibleTrigger>
        )}
        <CollapsibleContent forceMount hidden={!open}>
          <SidebarMenuSub className="mr-0 pr-0">
            <SubItems items={items} activeTab={activeTab} onSelect={onSelect} />
          </SidebarMenuSub>
        </CollapsibleContent>
      </Collapsible>
    </SidebarMenuItem>
  )
}

/**
 * @param sections  [{ key, Icon, labelKey, items:[{id,Icon,labelKey,section?}] } | { id, Icon, labelKey } (yaprak)]
 */
export default function NavMain({ label, sections, activeTab, openSection, onToggle, onSelect, collapsed }) {
  return (
    <SidebarGroup>
      <SidebarGroupLabel className={NAV_GROUP_LABEL}>{label}</SidebarGroupLabel>
      <SidebarGroupContent>
        <SidebarMenu>
          {sections.map((s) => (s.items
            ? <NavSection key={s.key} section={s} activeTab={activeTab} open={openSection === s.key} onToggle={onToggle}
                onSelect={onSelect} collapsed={collapsed} />
            : <NavLeafItem key={s.id} item={s} active={activeTab === s.id} onSelect={onSelect} />))}
        </SidebarMenu>
      </SidebarGroupContent>
    </SidebarGroup>
  )
}
