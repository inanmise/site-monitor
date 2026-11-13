import { Bookmark, Plus, X } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import KebabMenu from '../../ui/KebabMenu.jsx'
import { Button } from '@/components/shadcn/button'
import { Tabs, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { cn } from '@/lib/utils'
import { MAX_TABS, tabDirty } from './sqlTabs.js'

/** Sekmenin görünen adı: kullanıcı başlığı > "Sorgu n". */
export function tabTitle(t, tab) {
  return tab?.title?.trim() || t('sql.tabs.untitled', tab?.n ?? 1)
}

/**
 * Sorgu sekmeleri (2026-11-13, B3) — shadcn Tabs (TabsList + TabsTrigger); kapatma düğmesi tetiğin KARDEŞİ (düğme içinde
 * düğme olmaz). Sekmeler satıra sığmazsa ALT SATIRA sarar (gizli taşma yok); başlık kırpılmaz, sarar. Kayıtlı sorguya bağlı
 * sekmede yer imi; kaydedilmemiş değişiklik noktası. Menü: yeniden adlandır, çoğalt, diğerlerini kapat, kapat. En çok 10.
 */
export default function SqlTabsBar({ state, running, onSelect, onAdd, onClose, onRename, onDuplicate, onCloseOthers, className }) {
  const t = useT()
  const full = state.tabs.length >= MAX_TABS
  return (
    <div data-slot="sql-tabs" className={cn('flex min-w-0 flex-wrap items-start gap-2', className)}>
      <Tabs value={state.active} onValueChange={onSelect} className="min-w-0 flex-1">
        <TabsList aria-label={t('sql.tabs.label')}
          className="h-auto w-full flex-wrap justify-start gap-1 bg-transparent p-0 group-data-[orientation=horizontal]/tabs:h-auto">
          {state.tabs.map((tab) => {
            const title = tabTitle(t, tab)
            const dirty = tabDirty(tab) && tab.savedId != null
            return (
              <div key={tab.id} data-slot="sql-tab" data-active={tab.id === state.active ? 'true' : undefined}
                className="flex max-w-full min-w-0 items-stretch rounded-md border bg-muted/40 data-[active=true]:border-primary/40 data-[active=true]:bg-background data-[active=true]:shadow-sm">
                <TabsTrigger value={tab.id} title={title}
                  className="h-auto min-h-8 max-w-[16rem] flex-none justify-start gap-1.5 rounded-r-none border-0 px-2.5 py-1 text-left text-xs whitespace-normal shadow-none data-[state=active]:shadow-none pointer-coarse:min-h-10">
                  {tab.savedId != null && <Bookmark aria-hidden="true" className="size-3.5 shrink-0" />}
                  <span className="min-w-0 [overflow-wrap:anywhere]">{title}</span>
                  {dirty && <span aria-label={t('sql.tabs.unsaved')} role="img" className="size-1.5 shrink-0 rounded-full bg-amber-500" />}
                  {running === tab.id && <span className="sr-only">{t('sql.tabs.running')}</span>}
                </TabsTrigger>
                <KebabMenu label={t('sql.tabs.menu')} rowLabel={title} items={[
                  { label: t('sql.tabs.rename'), onClick: () => onRename?.(tab.id) },
                  { label: t('sql.tabs.duplicate'), onClick: () => onDuplicate?.(tab.id), disabled: full },
                  { label: t('sql.tabs.closeOthers'), onClick: () => onCloseOthers?.(tab.id), disabled: state.tabs.length < 2 },
                ]} />
                <Button type="button" variant="ghost" size="icon-sm" onClick={() => onClose?.(tab.id)}
                  aria-label={t('sql.tabs.close', title)} className="h-auto min-h-8 rounded-l-none text-muted-foreground pointer-coarse:size-auto pointer-coarse:min-h-10 pointer-coarse:min-w-10"
                  disabled={running === tab.id}>
                  <X />
                </Button>
              </div>
            )
          })}
        </TabsList>
      </Tabs>
      <Button type="button" variant="outline" size="sm" onClick={onAdd} disabled={full} data-slot="sql-tab-add"
        title={full ? t('sql.tabs.full', MAX_TABS) : undefined} className="pointer-coarse:h-10">
        <Plus /> {t('sql.tabs.add')}
      </Button>
    </div>
  )
}
