import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Bell, CheckCheck, Siren, CheckCircle2, Wrench, CalendarDays, ClipboardX, Activity, Trash2, History, ChevronLeft, ChevronRight, UsersRound } from 'lucide-react'
import { api, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'
import { navigateTo } from '../utils/navigate.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { SidebarMenuBadge, SidebarMenuButton } from '@/components/shadcn/sidebar'
import { cn } from '@/lib/utils'

/**
 * Bildirim kutusu (2026-09-12, zenginleştirme #2; v2 2026-09-20): Nav'daki zil — açık alarm, son 24 saatte çözülen,
 * bakım penceresi (aktif / yaklaşan), bugün son giriş günüyse eksik haftalık rapor, süresi dolan istisna.
 *
 * <p>v2 (kullanıcı bildirimi): her satırda TAKIM, başlangıç zamanı ve canlı süre ("3 sa 12 dk açık" / "sürdü 2 sa");
 * "İzlemeye git" ikinci eylem (alarm sayfası yerine izlemenin kendisi); çoklu seçim → seçilenleri okundu say / temizle;
 * "Tümünü temizle"; "Geçmiş" sekmesi (çözülmüş alarmlar 30 gün, sayfalı). Okundu ve temizlendi kümeleri localStorage'da
 * (kullanıcı adına göre ayrık). 60 sn'de bir görünürken tazelenir.
 */
const KIND_ICON = { alert_open: Siren, alert_resolved: CheckCircle2, maintenance_active: Wrench, maintenance_soon: Wrench, weekly_due: CalendarDays, exception_expired: ClipboardX }
const STORE = (u) => `inbox-seen:${u || 'anon'}`
const STORE_DISMISSED = (u) => `inbox-dismissed:${u || 'anon'}`
function readSet(k) { try { return new Set(JSON.parse(localStorage.getItem(k) || '[]')) } catch { return new Set() } }
function writeSet(k, set) { try { localStorage.setItem(k, JSON.stringify([...set].slice(-500))) } catch { /* yoksay */ } }

function toMs(iso) {
  if (!iso) return NaN
  return new Date(iso + (iso.endsWith('Z') ? '' : 'Z')).getTime()
}
/** Süre metni: "12 dk" / "3 sa 12 dk" / "2 g 5 sa". */
export function fmtDuration(ms, t) {
  if (!Number.isFinite(ms) || ms < 0) return ''
  const m = Math.floor(ms / 60000)
  if (m < 60) return t('inbox.durMin', m)
  const h = Math.floor(m / 60)
  if (h < 48) return t('inbox.durHour', h, m % 60)
  return t('inbox.durDay', Math.floor(h / 24), h % 24)
}

export default function InboxBell({ username }) {
  const t = useT()
  const [items, setItems] = useState([])
  const [open, setOpen] = useState(false)
  const [seen, setSeen] = useState(() => readSet(STORE(username)))
  const [dismissed, setDismissed] = useState(() => readSet(STORE_DISMISSED(username)))
  const [showDismissed, setShowDismissed] = useState(false)
  const [selected, setSelected] = useState(() => new Set())
  const [view, setView] = useState('current')          // current | history
  const [hist, setHist] = useState(null)               // { data, total, page, total_pages }
  const [histPage, setHistPage] = useState(0)
  const [histLoading, setHistLoading] = useState(false)
  const [tick, setTick] = useState(0)                  // canlı süre için dakikada bir yeniden çizim
  const btnRef = useRef(null)

  const load = useCallback(async () => {
    try { const r = await api.me.inbox(); if (r?.success && Array.isArray(r.data)) setItems(r.data) } catch { /* zil süs */ }
  }, [])
  useVisibleInterval(load, 60_000, true)
  useVisibleInterval(() => setTick(x => x + 1), open ? 60_000 : 0)

  // Geçmiş sekmesi: açılınca / sayfa değişince yüklenir.
  useEffect(() => {
    if (!open || view !== 'history') return undefined
    let alive = true
    setHistLoading(true)
    Promise.resolve(api.me.inboxHistory?.(histPage, 25))
      .then(r => { if (alive && r?.success) setHist(r) })
      .catch(() => {})
      .finally(() => { if (alive) setHistLoading(false) })
    return () => { alive = false }
  }, [open, view, histPage])

  const visible = useMemo(() => showDismissed ? items : items.filter((i) => !dismissed.has(i.key)), [items, dismissed, showDismissed])
  const unread = useMemo(() => items.filter((i) => !seen.has(i.key) && !dismissed.has(i.key)).length, [items, seen, dismissed])
  const dismissedCount = useMemo(() => items.filter((i) => dismissed.has(i.key)).length, [items, dismissed])

  function persistSeen(next) { setSeen(next); writeSet(STORE(username), next) }
  function persistDismissed(next) { setDismissed(next); writeSet(STORE_DISMISSED(username), next) }
  function markAll() { const next = new Set(seen); items.forEach((i) => next.add(i.key)); persistSeen(next) }
  function markSelected() { const next = new Set(seen); selected.forEach((k) => next.add(k)); persistSeen(next); setSelected(new Set()) }
  function dismissKeys(keys) {
    const next = new Set(dismissed); keys.forEach((k) => next.add(k)); persistDismissed(next)
    const s2 = new Set(seen); keys.forEach((k) => s2.add(k)); persistSeen(s2)
    setSelected(new Set())
  }
  function clearAll() { dismissKeys(visible.map((i) => i.key)) }
  function toggleSel(key) { setSelected((prev) => { const n = new Set(prev); if (n.has(key)) n.delete(key); else n.add(key); return n }) }
  function go(it, target) {
    const next = new Set(seen); next.add(it.key); persistSeen(next)
    setOpen(false)
    if (target === 'monitor' && it.monitor_tab) navigateTo(it.monitor_tab, it.monitor_params || undefined)
    else navigateTo(it.tab, it.params)
  }

  /** Zaman satırı: başladı · süre (açıksa canlı) · çözüldü. `tick` bağımlılığı: dakikada bir yeniden hesap. */
  function timeline(it) {
    void tick
    const parts = []
    const start = it.started_at || null
    if (it.kind === 'alert_open' && start) {
      parts.push(t('inbox.startedAt', formatDateSec(start)))
      const d = fmtDuration(Date.now() - toMs(start), t)
      if (d) parts.push(t('inbox.openFor', d))
    } else if (it.kind === 'alert_resolved' && start) {
      parts.push(t('inbox.startedAt', formatDateSec(start)))
      const end = it.ended_at || it.at
      const d = fmtDuration(toMs(end) - toMs(start), t)
      if (d) parts.push(t('inbox.lasted', d))
      if (end) parts.push(t('inbox.resolvedAt', formatDateSec(end)))
    } else if (it.at) {
      parts.push(formatDateSec(it.at))
    }
    return parts.join(' · ')
  }

  // Uyarı seviyesi rozeti: shadcn Badge + seviye tonu (açık/koyu temada okunur)
  const LEVEL_TONE = {
    critical: 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300',
    high: 'bg-orange-100 text-orange-800 dark:bg-orange-950 dark:text-orange-300',
    warning: 'bg-yellow-100 text-yellow-800 dark:bg-yellow-950 dark:text-yellow-300',
  }
  const KIND_TONE = {
    alert_open: 'text-destructive', alert_resolved: 'text-success', maintenance_active: 'text-amber-600',
    maintenance_soon: 'text-amber-600', weekly_due: 'text-primary', exception_expired: 'text-amber-600',
  }

  function renderItem(it, { selectable }) {
    const Icon = KIND_ICON[it.kind] || Bell
    const isNew = !seen.has(it.key)
    const isDismissed = dismissed.has(it.key)
    const sel = selected.has(it.key)
    const lvl = String(it.level || '').toLowerCase()
    return (
      // data-inbox-row / data-dismissed: test ve tur kancası (görünüm Tailwind'den)
      <div key={it.key} data-inbox-row={it.kind} data-dismissed={isDismissed || undefined}
           className={cn('flex items-center gap-1.5', isDismissed && 'opacity-55')}>
        {selectable && (
          <Checkbox className="ml-1.5 shrink-0" checked={sel} onCheckedChange={() => toggleSel(it.key)} aria-label={t('inbox.select', it.title)} />
        )}
        <Button type="button" variant="ghost" onClick={() => go(it, 'alert')} title={t('inbox.goAlert')}
          className={cn('h-auto min-w-0 flex-1 justify-start gap-2.5 px-2.5 py-2 text-left font-normal whitespace-normal',
            isNew && !isDismissed && 'bg-primary/8', sel && 'bg-primary/12')}>
          <Icon aria-hidden="true" className={cn('size-4 shrink-0', KIND_TONE[it.kind])} />
          <span className="flex min-w-0 flex-1 flex-col">
            <span className={cn('truncate text-sm', isNew && !isDismissed && 'font-semibold')}>{t(`inbox.kind.${it.kind}`)} · <b>{it.title}</b></span>
            <span className="flex min-w-0 flex-wrap items-center gap-2">
              {it.team_name && (
                <Badge variant="outline" className="h-4 gap-0.5 px-1.5 text-[10px]"><UsersRound aria-hidden="true" /> {it.team_name}</Badge>
              )}
              {it.sub && <span className="truncate text-xs text-muted-foreground">{it.sub}</span>}
              {it.monitor_name && it.monitor_name !== it.title && <span className="truncate text-xs text-muted-foreground">{it.monitor_name}</span>}
            </span>
            <span className="truncate text-[11px] text-muted-foreground">{timeline(it)}</span>
          </span>
          {it.level && it.kind === 'alert_open' && (
            <Badge variant="secondary" className={cn('shrink-0 text-[10px] font-bold', LEVEL_TONE[lvl])}>{it.level}</Badge>
          )}
        </Button>
        {it.monitor_tab && (
          <Button type="button" variant="outline" size="icon-sm" className="shrink-0" onClick={() => go(it, 'monitor')}
            title={t('inbox.goMonitor')} aria-label={t('inbox.goMonitor')}>
            <Activity aria-hidden="true" />
          </Button>
        )}
      </div>
    )
  }

  const histItems = hist?.data || []
  const histPages = Math.max(1, Number(hist?.total_pages ?? 1))

  return (
    <>
      {/* Tetik: shadcn SidebarMenuButton (daraltılmış kenar çubuğunda ipucu), okunmamış sayısı SidebarMenuBadge */}
      <SidebarMenuButton ref={btnRef} variant="outline" className="text-muted-foreground" onClick={() => setOpen((o) => !o)}
        aria-label={t('inbox.title')} aria-expanded={open} tooltip={unread ? t('inbox.unread', unread) : t('inbox.title')}>
        <Bell aria-hidden="true" />
        <span>{t('inbox.title')}</span>
      </SidebarMenuButton>
      {unread > 0 && (
        <SidebarMenuBadge aria-hidden="true" className="rounded-full bg-destructive text-white peer-hover/menu-button:text-white">
          {unread > 99 ? '99+' : unread}
        </SidebarMenuBadge>
      )}
      {/* Panel: shadcn Sheet (soldan; Escape / dış tıklama / odak tuzağı bileşenden) */}
      <Sheet open={open} onOpenChange={setOpen}>
        <SheetContent side="left" className="w-full gap-0 p-0 sm:max-w-xl">
          <SheetHeader className="gap-1 border-b px-4 py-3 pr-12">
            <SheetTitle className="flex items-center gap-2"><Bell className="size-4" aria-hidden="true" /> {t('inbox.title')}</SheetTitle>
            <SheetDescription>{unread > 0 ? t('inbox.unread', unread) : t('inbox.allRead')}</SheetDescription>
            {view === 'current' && (
              <div className="mt-1 flex flex-wrap gap-2">
                <Button type="button" variant="secondary" size="sm" onClick={markAll} disabled={!unread}><CheckCheck /> {t('inbox.markAll')}</Button>
                <Button type="button" variant="secondary" size="sm" onClick={clearAll} disabled={visible.length === 0} title={t('inbox.clearAllTip')}><Trash2 /> {t('inbox.clearAll')}</Button>
              </div>
            )}
          </SheetHeader>
          <Tabs value={view} onValueChange={setView} className="min-h-0 flex-1 gap-0">
            <TabsList className="mx-3 mt-2">
              <TabsTrigger value="current"><Bell /> {t('inbox.tabCurrent')}{visible.length > 0 ? ` (${visible.length})` : ''}</TabsTrigger>
              <TabsTrigger value="history"><History /> {t('inbox.tabHistory')}</TabsTrigger>
            </TabsList>

            {view === 'current' && selected.size > 0 && (
              <div className="mx-3 mt-2 flex flex-wrap items-center gap-2 rounded-md bg-primary/8 px-2.5 py-1.5 text-xs" data-testid="inbox-selbar">
                <span className="font-medium">{t('inbox.selected', selected.size)}</span>
                <Button type="button" variant="secondary" size="sm" onClick={markSelected}><CheckCheck /> {t('inbox.markSelected')}</Button>
                <Button type="button" variant="secondary" size="sm" onClick={() => dismissKeys([...selected])}><Trash2 /> {t('inbox.clearSelected')}</Button>
                <Button type="button" variant="ghost" size="sm" onClick={() => setSelected(new Set())}>{t('inbox.cancelSelect')}</Button>
              </div>
            )}

            <TabsContent value="current" className="flex min-h-0 flex-col gap-0.5 overflow-y-auto p-1.5">
              {visible.length === 0 && (
                <p className="px-3 py-6 text-center text-sm text-muted-foreground">{dismissedCount > 0 ? t('inbox.emptyCleared', dismissedCount) : t('inbox.empty')}</p>
              )}
              {visible.map((it) => renderItem(it, { selectable: true }))}
            </TabsContent>
            <TabsContent value="history" className="flex min-h-0 flex-col gap-0.5 overflow-y-auto p-1.5">
              {histLoading && histItems.length === 0 && <p className="px-3 py-6 text-center text-sm text-muted-foreground">{t('inbox.loading')}</p>}
              {!histLoading && histItems.length === 0 && <p className="px-3 py-6 text-center text-sm text-muted-foreground">{t('inbox.historyEmpty')}</p>}
              {histItems.map((it) => renderItem(it, { selectable: false }))}
            </TabsContent>
          </Tabs>

          {((view === 'current' && dismissedCount > 0) || (view === 'history' && hist) || view === 'history') && (
            <div className="flex flex-wrap items-center gap-2.5 border-t px-4 py-2 text-xs text-muted-foreground">
              {view === 'current' && dismissedCount > 0 && (
                <Button type="button" variant="link" size="sm" className="h-auto p-0 text-xs" onClick={() => setShowDismissed((v) => !v)}>
                  {showDismissed ? t('inbox.hideCleared') : t('inbox.showCleared', dismissedCount)}
                </Button>
              )}
              {view === 'history' && hist && (
                <span className="inline-flex items-center gap-2">
                  <Button type="button" variant="outline" size="icon-sm" disabled={histPage <= 0} onClick={() => setHistPage((p) => p - 1)} aria-label={t('app.prevPage')}><ChevronLeft /></Button>
                  <span>{t('inbox.pageInfo', histPage + 1, histPages, hist.total ?? 0)}</span>
                  <Button type="button" variant="outline" size="icon-sm" disabled={histPage + 1 >= histPages} onClick={() => setHistPage((p) => p + 1)} aria-label={t('app.nextPage')}><ChevronRight /></Button>
                </span>
              )}
              {view === 'history' && <span className="ml-auto">{t('inbox.historyNote')}</span>}
            </div>
          )}
        </SheetContent>
      </Sheet>
    </>
  )
}
