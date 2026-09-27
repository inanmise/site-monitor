import { Bell, BellOff, CheckCheck, Eye, EyeOff, History, Inbox, MoreHorizontal, Trash2, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/shadcn/dropdown-menu'
import { SheetClose, SheetDescription, SheetTitle } from '@/components/shadcn/sheet'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { cn } from '@/lib/utils'
import InboxRow from './InboxRow.jsx'
import { groupByDay } from './inboxModel.js'

const GROUP_KEY = { upcoming: 'inbox.groupUpcoming', today: 'inbox.groupToday', yesterday: 'inbox.groupYesterday', earlier: 'inbox.groupEarlier' }

/** Ok tuşlarıyla satırlar arasında dolaşma (Tab durakları düğmelerde; ↑/↓/Home/End satır ana düğmeleri arasında). */
function rowKeyNav(e) {
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(e.key)) return
  const rows = [...e.currentTarget.querySelectorAll('[data-inbox-open]')]
  const i = rows.indexOf(document.activeElement)
  if (i < 0 || rows.length === 0) return
  e.preventDefault()
  const next = e.key === 'ArrowDown' ? Math.min(i + 1, rows.length - 1)
    : e.key === 'ArrowUp' ? Math.max(i - 1, 0) : e.key === 'Home' ? 0 : rows.length - 1
  rows[next]?.focus()
}

/** Yükleme iskeleti — gerçek satırla aynı boyut (kutucuk + üç satır), ekran okuyucuya "Yükleniyor…". */
function RowsSkeleton({ label }) {
  return (
    <div role="status" aria-live="polite" className="flex flex-col p-2">
      <span className="sr-only">{label}</span>
      {[0, 1, 2].map((i) => (
        <div key={i} aria-hidden="true" className="flex items-start gap-3 px-2.5 py-2.5">
          <Skeleton className="size-9 shrink-0 rounded-lg" />
          <div className="flex min-w-0 flex-1 flex-col gap-2 pt-0.5">
            <Skeleton className="h-3.5 w-2/3" />
            <Skeleton className="h-3 w-1/2" />
            <Skeleton className="h-3 w-1/3" />
          </div>
        </div>
      ))}
    </div>
  )
}

/**
 * Panel içeriği (v3, 2026-09-26) — masaüstünde Popover, telefonda Sheet aynı gövdeyi çizer:
 * başlık (ad · "N yeni" rozeti · Tümünü okundu say · diğer işlemler) → çizgi sekmeler (Okunmamış / Tümü / Geçmiş)
 * → güne göre gruplu liste (yapışkan grup başlıkları) ya da boş durum / iskelet / hata; Geçmiş sunucu sayfalı
 * (standart compact PaginationBar). Pasif sekme içeriği Radix'in `hidden`'ıyla DOM'da kalır ve globals.css
 * `[hidden]` kuralıyla yer kaplamaz (e2e/inbox-panel.spec.js kapısı) — sabit panel yüksekliği YAZILMAZ.
 */
export default function InboxPanel({ inbox, view, onViewChange, mobile = false, sheet = false, titleId, panelId,
  onOpenItem, onOpenMonitor }) {
  const t = useT()
  const unread = inbox.unreadCount

  const rowProps = (it, { history = false } = {}) => ({
    it, history, mobile, tick: inbox.tick,
    unread: !history && !inbox.seen.has(it.key),
    dismissed: !history && inbox.dismissed.has(it.key),
    onOpen: onOpenItem, onOpenMonitor: it.monitor_tab ? onOpenMonitor : undefined,
    onMarkRead: history ? undefined : (x) => inbox.markRead([x.key]),
    onDismiss: history ? undefined : (x) => inbox.dismiss([x.key]),
  })

  const grouped = (items) => groupByDay(items).map((g) => (
    <section key={g.key} aria-label={t(GROUP_KEY[g.key])}>
      <h3 className="sticky top-0 z-10 m-0 bg-popover/95 px-4 pt-2.5 pb-1 text-[11px] font-semibold tracking-wider text-muted-foreground uppercase backdrop-blur-sm">
        {t(GROUP_KEY[g.key])}
      </h3>
      <div className="flex flex-col pb-1">{g.items.map((it) => <InboxRow key={it.key} {...rowProps(it)} />)}</div>
    </section>
  ))

  const empty = (icon, title, description) => (
    <StatusBlock icon={icon} title={title} description={description} className="py-10 text-[0.95em]" />
  )
  const loadError = (retry) => (
    <AlertBanner tone="danger" className="m-3"
      actions={<Button type="button" size="sm" variant="outline" onClick={retry}>{t('inbox.retry')}</Button>}>
      {t('inbox.loadError')}
    </AlertBanner>
  )

  const listBody = (items, emptyNode) => {
    if (inbox.loading) return <RowsSkeleton label={t('inbox.loading')} />
    if (inbox.error && inbox.items === null) return loadError(inbox.retry)
    return items.length === 0 ? emptyNode : grouped(items)
  }

  // Sheet'te başlık Radix DialogTitle'dır (kimliğini ve dialog bağını Radix kurar); Popover'da h2 + titleId (aria-labelledby).
  const titleCls = 'm-0 flex shrink-0 items-center gap-2 text-base font-semibold text-foreground'
  const titleBody = <><Bell aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" /><span className="truncate">{t('inbox.title')}</span></>
  const listCls = 'min-h-0 flex-1 overflow-y-auto overscroll-contain'

  return (
    <div id={panelId} tabIndex={-1} data-slot="inbox-panel" className="flex min-h-0 flex-1 flex-col outline-none">
      {/* Başlık */}
      <div className="flex shrink-0 items-center gap-2 border-b py-3 pr-2 pl-4">
        {sheet ? <SheetTitle className={titleCls}>{titleBody}</SheetTitle> : <h2 id={titleId} className={titleCls}>{titleBody}</h2>}
        {unread > 0 && <Badge className="h-5 shrink-0 px-2 tabular-nums">{t('inbox.new', unread)}</Badge>}
        {/* Ekran okuyucuya okunmamış sayısı (kibar duyuru); Sheet'te açıklama olarak da bağlanır */}
        {sheet
          ? <SheetDescription aria-live="polite" className="sr-only">{unread > 0 ? t('inbox.unread', unread) : t('inbox.allRead')}</SheetDescription>
          : <span aria-live="polite" className="sr-only">{unread > 0 ? t('inbox.unread', unread) : t('inbox.allRead')}</span>}
        <div className="ml-auto flex shrink-0 items-center gap-0.5">
          <Button type="button" variant="ghost" size="sm" onClick={inbox.markAll} disabled={!unread} title={t('inbox.markAll')}
            className={cn('text-muted-foreground hover:text-foreground has-[>svg]:px-2', mobile && 'size-10 px-0')}>
            <CheckCheck aria-hidden="true" />
            <span className={cn(mobile && 'sr-only')}>{t('inbox.markAll')}</span>
          </Button>
          <DropdownMenu modal={false}>
            <DropdownMenuTrigger asChild>
              <Button type="button" variant="ghost" size="icon-sm" aria-label={t('inbox.more')} title={t('inbox.more')}
                className={cn('text-muted-foreground hover:text-foreground', mobile && 'size-10')}>
                <MoreHorizontal aria-hidden="true" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" collisionPadding={8} className="z-(--z-menu)">
              <DropdownMenuItem disabled={inbox.active.length === 0} onSelect={inbox.clearAll}>
                <Trash2 aria-hidden="true" /> {t('inbox.clearAll')}
              </DropdownMenuItem>
              {inbox.dismissedCount > 0 && (
                <DropdownMenuItem onSelect={() => inbox.setShowDismissed((v) => !v)}>
                  {inbox.showDismissed ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}
                  {inbox.showDismissed ? t('inbox.hideCleared') : t('inbox.showCleared', inbox.dismissedCount)}
                </DropdownMenuItem>
              )}
            </DropdownMenuContent>
          </DropdownMenu>
          {sheet && (
            <SheetClose asChild>
              <Button type="button" variant="ghost" size="icon" aria-label={t('app.close')} title={t('app.close')} className="text-muted-foreground">
                <X aria-hidden="true" className="size-5" />
              </Button>
            </SheetClose>
          )}
        </div>
      </div>

      {/* Sekmeler: çizgi varyantı, tam genişlik, 40 px dokunma yüksekliği */}
      <Tabs value={view} onValueChange={onViewChange} className="flex min-h-0 flex-1 flex-col gap-0">
        <TabsList variant="line" className="h-auto! w-full shrink-0 justify-start gap-0 border-b px-2">
          <TabsTrigger value="unread" className="h-10 max-w-40 flex-1 gap-1.5">
            {t('inbox.tabUnread')}
            {unread > 0 && <Badge variant="secondary" className="h-4 min-w-4 px-1 text-[10px] tabular-nums">{unread}</Badge>}
          </TabsTrigger>
          <TabsTrigger value="all" className="h-10 max-w-40 flex-1 gap-1.5">
            {t('inbox.tabAll')}
            {inbox.allItems.length > 0 && <Badge variant="secondary" className="h-4 min-w-4 px-1 text-[10px] tabular-nums">{inbox.allItems.length}</Badge>}
          </TabsTrigger>
          <TabsTrigger value="history" className="h-10 max-w-40 flex-1 gap-1.5"><History aria-hidden="true" /> {t('inbox.tabHistory')}</TabsTrigger>
        </TabsList>

        <TabsContent value="unread" className={listCls} onKeyDown={rowKeyNav}>
          {listBody(inbox.unreadItems, empty(Inbox, t('inbox.caughtUp'), t('inbox.caughtUpHint')))}
        </TabsContent>
        <TabsContent value="all" className={listCls} onKeyDown={rowKeyNav}>
          {listBody(inbox.allItems, empty(BellOff, t('inbox.emptyTitle'),
            inbox.dismissedCount > 0 ? t('inbox.emptyCleared', inbox.dismissedCount) : t('inbox.emptyHint')))}
        </TabsContent>
        <TabsContent value="history" className="flex min-h-0 flex-1 flex-col">
          <div className={listCls} onKeyDown={rowKeyNav}>
            {inbox.histLoading && inbox.histItems.length === 0 && <RowsSkeleton label={t('inbox.loading')} />}
            {!inbox.histLoading && inbox.histError && loadError(inbox.retryHistory)}
            {!inbox.histLoading && !inbox.histError && inbox.histItems.length === 0 && empty(History, t('inbox.historyEmpty'))}
            {inbox.histItems.length > 0 && (
              <div className="flex flex-col py-1">
                {inbox.histItems.map((it) => <InboxRow key={it.key} {...rowProps(it, { history: true })} />)}
              </div>
            )}
          </div>
          {inbox.hist && (
            <div className="flex shrink-0 flex-col gap-1 border-t px-3 pb-2 text-xs text-muted-foreground">
              {/* Geçmiş sayfalaması: standart compact çubuk (modal ön ayarı) */}
              <PaginationBar {...inbox.histPager.bar} />
              <span className="text-[11px]">{t('inbox.historyNote')}</span>
            </div>
          )}
        </TabsContent>
      </Tabs>
    </div>
  )
}
