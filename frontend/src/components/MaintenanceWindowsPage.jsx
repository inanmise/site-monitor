import { useState, useEffect, useCallback, useMemo, useRef, lazy, Suspense } from 'react'
import { api } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useToast } from './ui/Toast.jsx'
import { useDialog } from './ui/Dialog.jsx'
import { usePagination } from '../hooks/usePagination.js'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'
import { readUrlParam, useUrlQuerySync } from '../hooks/useUrlQuerySync.js'
import { useIsMobile } from '../hooks/use-mobile.js'
import PaginationBar from './ui/PaginationBar.jsx'
import MonthCalendar from './ui/MonthCalendar.jsx'
import ModalShell from './ui/ModalShell.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import MonitorStatsBar from './MonitorStatsBar.jsx'
import MaintenanceActiveStrip from './maintenance/MaintenanceActiveStrip.jsx'
import MaintenanceAgenda from './maintenance/MaintenanceAgenda.jsx'
import MaintenanceList from './maintenance/MaintenanceList.jsx'
import MaintenanceEditor, { EMPTY_FORM, formFromWindow } from './maintenance/MaintenanceEditor.jsx'
import MaintenanceQuickModal from './maintenance/MaintenanceQuickModal.jsx'
import { DAY_MS, TILE_PRED, nextOccurrence, occurrences, parseIso, sortWindows, toIso } from './maintenance/maintenanceSchedule.js'
import { countText, useScheduleText } from './maintenance/maintenanceUi.jsx'
import { Wrench, Plus, Play, RefreshCw, History, CalendarDays, List as ListIcon, BellOff, Clock, CalendarRange, Repeat, Pause, Archive } from 'lucide-react'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'

const ChangeHistoryTab = lazy(() => import('./history/ChangeHistoryTab.jsx'))

const MON_TYPES = [
  ['http', 'getHttpMonitors', m => m.url],
  ['port', 'getPortMonitors', m => m.host],
  ['keyword', 'getKeywordMonitors', m => m.url],
  ['ping', 'getPingMonitors', m => m.host],
  ['page', 'getPageMonitors', m => m.url],
  ['pagespeed', 'getPageSpeedMonitors', m => m.url],
  ['dns', 'getDnsMonitors', m => m.domain],
  ['domain', 'getDomainMonitors', m => m.domain],
  ['cert', 'getUptimeOverview', m => m.domain],
  // Sentetik: iki farklılık var — liste ucu {monitors:[…]} ile sarmalıyor ve alarm anahtarı
  // monitörün ADI (SweepItem.domain = m.getName()), diğer türlerdeki url/host/domain değil.
  // Motor tarafı zaten hazırdı (isUnderMaintenance sentetik alarmın da geçtiği yolda); eksik
  // olan yalnız bu listeydi, bu yüzden planlı kesintide sentetik monitörler susturulamıyor,
  // tek çare "tüm monitörler" bayrağıyla her şeyi birden susturmaktı.
  ['scripted', 'getScriptedMonitors', m => m.name, d => d?.monitors || []],
]

const VIEWS = ['list', 'agenda', 'calendar']
/** Özet kartları — süzgeç mantığı `maintenanceSchedule.TILE_PRED` (saf); burada yalnız ikon + ton. */
const TILES = [
  { key: 'active', Icon: BellOff, cls: 'valid' },
  { key: 'next24h', Icon: Clock, cls: 'warning' },
  { key: 'next7d', Icon: CalendarRange, cls: 'total' },
  { key: 'recurring', Icon: Repeat, cls: 'weak' },
  { key: 'paused', Icon: Pause, cls: 'paused' },
  { key: 'past', Icon: Archive, cls: 'paused' },
]

/**
 * Bakım Pencereleri (2026-09-26 yeniden tasarım, shadcn + mweb): başlık + amaç, "Şu an susturulanlar" şeridi
 * (kalan süre + Şimdi bitir), süzen özet kartları, üç görünüm (Liste / Ajanda 7 gün / Takvim), düzenleyici ve hızlı
 * pencere modalları, satır başına değişiklik geçmişi. Veri tek uçtan (`maintenance.list`); durum/oluşum açılımı
 * istemcide (`maintenance/maintenanceSchedule.js`), 30 sn'de bir kalan süre tazelenir (sekme görünürken).
 * URL: `view` (list dışı görünüm). Test kancası: kök `data-slot="maintenance-page"`.
 */
export default function MaintenanceWindowsPage({ systemRole, teamId, teamName, globalAdmin = false }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const toast = useToast()
  const { range } = useScheduleText()
  const canManage = systemRole === 'ADMIN' || systemRole === 'TEAM_ADMIN'
  // Telefonda tablo yerine kart listesi + eylem menüsü (yapı farkı → useIsMobile; jsdom tek varyant çizer)
  const phone = useIsMobile()

  const [rows, setRows] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)          // string (sunucu metni) | true (genel)
  const [now, setNow] = useState(() => Date.now())
  const [view, setView] = useState(() => { const v = readUrlParam('view'); return VIEWS.includes(v) ? v : 'list' })
  const [filter, setFilter] = useState(null)
  const [modal, setModal] = useState(null)          // { kind: 'new' } | { kind: 'edit', w } | { kind: 'quick' }
  const [editorInitial, setEditorInitial] = useState(EMPTY_FORM)
  const [historyItem, setHistoryItem] = useState(null)   // değişiklik geçmişi penceresi
  const [saving, setSaving] = useState(false)
  const [monitorOptions, setMonitorOptions] = useState([])
  const optsLoaded = useRef(false)

  useUrlQuerySync({ view: view === 'list' ? null : view })
  // Kalan süre / "sıradaki" metinleri sekme görünürken 30 sn'de bir tazelenir (yeni istek yok).
  useVisibleInterval(() => setNow(Date.now()), 30_000, false)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await api.monitoring.maintenance.list()
      if (res?.success) { setRows(res.data ?? []); setNow(Date.now()) }
      else setError(res?.error || true)
    } catch (e) {
      setError(e?.message || true)
    } finally {
      setLoading(false)
    }
  }, [])
  useEffect(() => { load() }, [load])

  async function loadMonitorOptions() {
    if (optsLoaded.current) return
    optsLoaded.current = true
    const out = []
    await Promise.all(MON_TYPES.map(async ([type, fn, key, pick]) => {
      try {
        const res = await api.monitoring[fn]?.()
        // `pick`: yanıtı düz diziye indirger — sentetik uç {monitors:[…]} ile sarmalıyor.
        const list = res?.success ? (pick ? pick(res.data) : (res.data || [])) : []
        for (const m of list) {
          const target = key(m)
          if (!target) continue
          // Kimlik TUR + hedef: ayni URL hem Sayfa Butunlugu hem Sayfa Hizi monitoru olabilir; kimlik
          // yalniz hedef olunca ikincisi dedup'ta ELENIYOR ve o tur bakim penceresinde hic secilemiyordu
          // (2026-09-17). Tur adlari ':' icermez, bu yuzden ilk ':' guvenli ayiricidir (targetObjs geri ayirir).
          out.push({ value: `${type}:${target}`, target, type, name: m.name || target, label: `${m.name || target} · ${t('mw.type.' + type)}` })
        }
      } catch { /* atla */ }
    }))
    const seen = new Set()
    setMonitorOptions(out.filter(o => seen.has(o.value) ? false : (seen.add(o.value), true)))
  }

  // ── Türetilmiş veri ─────────────────────────────────────────────────────────
  const enriched = useMemo(() => rows.map((w) => ({ w, next: nextOccurrence(w, now) })), [rows, now])
  const counts = useMemo(() => Object.fromEntries(Object.entries(TILE_PRED).map(([k, p]) => [k, enriched.filter((x) => p(x, now)).length])), [enriched, now])
  const activeWindows = useMemo(() => enriched.filter(TILE_PRED.active).map((x) => x.w), [enriched])
  const filtered = useMemo(() => sortWindows(filter ? enriched.filter((x) => TILE_PRED[filter](x, now)) : enriched).map((x) => x.w), [enriched, filter, now])
  const pager = usePagination(filtered, { listKey: 'maintenance-windows', preset: 'page', resetDeps: [filter] })
  // Takvim olayları: −30…+60 günlük oluşumlar (tekrarlayanlar açılır); süren oluşum amber, diğerleri mavi. Sınır 92 =
  // 91 günlük aralığın tamamı (2026-10-09: 40'ta kesiliyordu → GÜNLÜK pencere takvimde ~10 gün sonra kayboluyordu).
  const calEvents = useMemo(() => (view !== 'calendar' ? [] : rows.flatMap((w) => occurrences(w, now - 30 * DAY_MS, now + 60 * DAY_MS, { limit: 92 }).map((o) => ({
    date: toIso(o.start), label: w.name, title: `${w.name} · ${range(o.start, o.end, w.timezone)}`,
    tone: o.start <= now && o.end > now ? 'warn' : 'info', onClick: canManage ? () => openEdit(w) : undefined,
  })))), [view, rows, now, range, canManage])   // eslint-disable-line react-hooks/exhaustive-deps

  // ── Eylemler ────────────────────────────────────────────────────────────────
  function openNew() { setEditorInitial(EMPTY_FORM); loadMonitorOptions(); setModal({ kind: 'new' }) }
  function openEdit(w) { setEditorInitial(formFromWindow(w)); loadMonitorOptions(); setModal({ kind: 'edit', w }) }
  function openQuick() { loadMonitorOptions(); setModal({ kind: 'quick' }) }
  function close() { setModal(null) }

  async function saveWindow(payload) {
    setSaving(true)
    try {
      const res = modal?.kind === 'edit' ? await api.monitoring.maintenance.update(modal.w.id, payload) : await api.monitoring.maintenance.create(payload)
      if (res?.success) { toast.success(t('mw.saved')); close(); load() }
      return res ?? { success: false }
    } finally {
      setSaving(false)
    }
  }
  async function startQuick(payload) {
    setSaving(true)
    try {
      const res = await api.monitoring.maintenance.quick(payload)
      if (res?.success) { toast.success(t('mw.started')); close(); load() }
      return res ?? { success: false }
    } finally {
      setSaving(false)
    }
  }
  async function togglePause(w) {
    const res = w.status === 'paused' ? await api.monitoring.maintenance.resume(w.id) : await api.monitoring.maintenance.pause(w.id)
    if (!res?.success) { toast.error(res?.error || t('mw.saveError')); return }
    load()
  }
  /**
   * "Şimdi bitir": sunucuda ayrı uç yok. Tek seferlik pencerede süre = geçen dakika (pencere hemen "bitmiş" olur);
   * tekrarlayan pencerede yalnız bu oluşum bitirilemez → seri DURAKLATILIR (onay metni bunu söyler, sonra sürdürülür).
   */
  async function endNow(w) {
    const recurring = !!w.recurrence && w.recurrence !== 'NONE'
    if (!await showConfirm({
      title: t('mw.endNow'), message: recurring ? t('mw.endNowRecurringConfirm', w.name) : t('mw.endNowConfirm', w.name),
      confirmText: t('mw.endNow'), variant: 'warning',
    })) return
    let res
    if (recurring) res = await api.monitoring.maintenance.pause(w.id)
    else {
      const start = parseIso(w.start_at)
      const elapsed = start == null ? 1 : Math.max(1, Math.floor((Date.now() - start) / 60_000))
      res = await api.monitoring.maintenance.update(w.id, { durationMinutes: elapsed })
    }
    if (!res?.success) { toast.error(res?.error || t('mw.saveError')); return }
    toast.success(t('mw.ended')); load()
  }
  async function del(w) {
    if (!await showConfirm({
      title: t('mw.delete'), message: t('mw.deleteConfirm'),
      confirmText: t('mw.delete'), variant: 'danger',
    })) return
    const res = await api.monitoring.maintenance.remove(w.id)
    if (!res?.success) { toast.error(res?.error || t('mw.deleteError')); return }
    toast.success(t('mw.deleted')); load()
  }
  const rowActions = { onEdit: openEdit, onTogglePause: togglePause, onEndNow: endNow, onHistory: setHistoryItem, onDelete: del }

  function onTile(key) {
    setFilter((f) => (f === key ? null : key))
    if (view !== 'list') setView('list')
  }

  const tiles = TILES.map((x) => ({ ...x, label: t('mw.tile.' + x.key), hint: t('mw.tile.' + x.key + 'Hint'), value: counts[x.key] ?? 0 }))

  return (
    <div data-slot="maintenance-page" className="flex min-w-0 flex-col gap-4">
      {/* Başlık + amaç + eylemler — telefonda alt alta, eylemler sarar */}
      <div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
        <div className="min-w-0">
          <h2 className="flex items-center gap-1.5 text-lg font-bold"><Wrench aria-hidden="true" className="size-5 shrink-0" />{t('mw.title')}</h2>
          <p className="mt-1 max-w-[78ch] text-xs text-muted-foreground">{t('mw.purpose')}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="outline" size="sm" onClick={load} disabled={loading}><RefreshCw aria-hidden="true" />{t('mw.refresh')}</Button>
          {canManage && <Button variant="secondary" size="sm" onClick={openQuick}><Play aria-hidden="true" />{t('mw.quickWindow')}</Button>}
          {canManage && <Button size="sm" onClick={openNew}><Plus aria-hidden="true" />{t('mw.newWindow')}</Button>}
        </div>
      </div>

      {loading && rows.length === 0 ? (
        <div data-slot="mw-skeleton" role="status" aria-busy="true" aria-label={t('tbl.loading')} className="flex flex-col gap-4">
          <div className="grid grid-cols-2 gap-2 rounded-[10px] border bg-card p-2 sm:grid-cols-3 sm:gap-3 sm:p-3 lg:grid-cols-6">
            {TILES.map((x) => <Skeleton key={x.key} className="h-24 rounded-lg" />)}
          </div>
          <Skeleton className="h-9 w-64 max-w-full" />
          <div className="flex flex-col gap-2">{[0, 1, 2, 3].map((i) => <Skeleton key={i} className="h-14 w-full" />)}</div>
        </div>
      ) : error ? (
        <AlertBanner tone="danger" role="alert" title={t('mw.loadError')}
          actions={<Button type="button" size="sm" variant="outline" onClick={load}><RefreshCw aria-hidden="true" />{t('mw.retry')}</Button>}>
          {typeof error === 'string' ? error : null}
        </AlertBanner>
      ) : rows.length === 0 ? (
        <StatusBlock tone="neutral" icon={Wrench} title={t('mw.emptyTitle')} description={t('mw.emptyText')} className="py-14"
          actions={canManage && <Button onClick={openNew}><Plus aria-hidden="true" />{t('mw.newWindow')}</Button>} />
      ) : (<>
        <MaintenanceActiveStrip windows={activeWindows} now={now} canManage={canManage} onEndNow={endNow} onEdit={openEdit} teamId={teamId} teamName={teamName} />
        <MonitorStatsBar items={tiles} activeFilter={filter} onStatClick={onTile} />

        <Tabs value={view} onValueChange={setView} className="min-w-0 gap-3">
          <TabsList aria-label={t('mw.viewsAria')} className="w-full justify-start overflow-x-auto sm:w-fit">
            <TabsTrigger value="list" className="flex-none px-3"><ListIcon aria-hidden="true" />{t('mw.view.list')}</TabsTrigger>
            <TabsTrigger value="agenda" className="flex-none px-3"><CalendarDays aria-hidden="true" />{t('mw.view.agenda')}</TabsTrigger>
            <TabsTrigger value="calendar" className="flex-none px-3"><CalendarRange aria-hidden="true" />{t('mw.calendar')}</TabsTrigger>
          </TabsList>
          <TabsContent value="list" className="flex min-w-0 flex-col gap-3">
            {filter && (
              <div className="flex flex-wrap items-center gap-2 text-[0.86em] text-muted-foreground">
                <span>{t('mondash.filterTip', t('mw.tile.' + filter))} · {countText(t, filtered.length, 'mw.matchOne', 'mw.matchCount')}</span>
                <Button type="button" variant="link" size="xs" className="h-auto px-1" onClick={() => setFilter(null)}>{t('app.clearFilter')}</Button>
              </div>
            )}
            {filtered.length === 0 ? (
              <StatusBlock tone="neutral" icon={Wrench} description={t('mw.noMatch')} className="py-8"
                actions={<Button type="button" variant="outline" size="sm" onClick={() => setFilter(null)}>{t('app.clearFilter')}</Button>} />
            ) : (<>
              <MaintenanceList items={pager.pageItems} phone={phone} canManage={canManage} now={now} actions={rowActions} teamId={teamId} teamName={teamName} />
              {/* Çubuk tablo kabının DIŞINDA (telefonda tabloyla birlikte kayıp gitmesin) */}
              <PaginationBar {...pager} />
            </>)}
          </TabsContent>
          <TabsContent value="agenda" className="min-w-0">
            <MaintenanceAgenda windows={rows} now={now} canManage={canManage} onOpen={openEdit} teamId={teamId} teamName={teamName} />
          </TabsContent>
          <TabsContent value="calendar" className="min-w-0">
            <MonthCalendar events={calEvents} ariaLabel={t('mw.calendar')} />
          </TabsContent>
        </Tabs>
      </>)}

      <MaintenanceEditor open={!!modal && modal.kind !== 'quick'} mode={modal?.kind === 'edit' ? 'edit' : 'new'} initial={editorInitial}
        monitorOptions={monitorOptions} saving={saving} onSave={saveWindow} onClose={close} canAllMonitors={globalAdmin} />
      <MaintenanceQuickModal open={modal?.kind === 'quick'} monitorOptions={monitorOptions} saving={saving} onStart={startQuick} onClose={close}
        canAllMonitors={globalAdmin} />

      {/* Değişiklik geçmişi — ayrı ve SALT-OKUNUR bir kabuk (formun içine sekme olarak konsaydı yanlışlıkla kayıt riski doğardı). */}
      <ModalShell open={!!historyItem} onClose={() => setHistoryItem(null)}
        title={historyItem ? historyItem.name : ''} icon={History} size="lg" scrollBody>
        {historyItem && (
          <Suspense fallback={<LoadingBlock label={t('modal.loading')} />}>
            <ChangeHistoryTab t={t} kind="maintenance" monitorId={historyItem.id} />
          </Suspense>
        )}
      </ModalShell>
    </div>
  )
}
