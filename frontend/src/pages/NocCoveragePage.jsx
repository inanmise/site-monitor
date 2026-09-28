import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { BellOff, BellRing, CircleCheck, CirclePause, Headset, ListChecks, RefreshCw, Settings2, ShieldCheck, ShieldOff } from 'lucide-react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { navigateTo } from '../utils/navigate.js'
import { useIsMobile } from '../hooks/use-mobile.js'
import { flushUrlQuerySync, readUrlParam, useUrlQuerySync } from '../hooks/useUrlQuerySync.js'
import { DEEP_OPEN } from '../utils/monitorDeepLink.js'
import { useCopyLink } from '../components/ui/CopyLinkButton.jsx'
import { usePagination } from '../hooks/usePagination.js'
import PageHeader from '../components/ui/PageHeader.jsx'
import AlertBanner from '../components/ui/AlertBanner.jsx'
import StatusBlock from '../components/ui/StatusBlock.jsx'
import PaginationBar from '../components/ui/PaginationBar.jsx'
import { Spinner } from '../components/ui/Progress.jsx'
import { useToast } from '../components/ui/Toast.jsx'
import { useTeamDirectory } from '../components/ui/TeamDirectory.jsx'
import MonitorStatsBar from '../components/MonitorStatsBar.jsx'
import NocCoverageToolbar from '../components/noc/NocCoverageToolbar.jsx'
import NocCoverageList from '../components/noc/NocCoverageList.jsx'
import NocTypeCoverage from '../components/noc/NocTypeCoverage.jsx'
import NocCallListCard from '../components/noc/NocCallListCard.jsx'
import { typeLabel } from '../components/noc/nocUi.jsx'
import {
  BULK_SKIP_REASONS, NOC_LEVELS, NOC_REASONS, NOC_TYPES, STATUS_KEYS, applyFilters, canEditCallList, canEditItem, deepLinkOf, itemKey,
  openTargetOf, reasonOptions, sortItems, summarize, summarizeSkipped, teamOptions, typeOptions, unwrap, withNotify,
} from '../components/noc/nocModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import { useElementWidth } from './forecast/forecastUi.jsx'

/**
 * **7/24 Kapsamı** (`?tab=noc`, 2026-09-27; `.migration/noc/CONTRACT.md`).
 *
 * <p>Neden var: gece bir kesinti olduğunda takım uyuyor olabilir; kurumun 7/24 izleme ekibi (NOC) yalnız "7/24'e
 * bildir" açık izlemelerden haberdar olur ve ilgili takımı TELEFONLA arar. Bu sayfa kullanıcının görüş kapsamındaki
 * izlemelerden hangilerinin NOC'a GİTMEDİĞİNİ ve NEDENİNİ (düz sözcüklerle) gösterir, tek tıkla açtırır.
 *
 * <p>Yapı: PageHeader (kapsam oranı, aktif grup sayısı) → hiç aktif grup yoksa uyarı (yöneticiye Ayarlar bağlantısı,
 * diğerlerine "yöneticinize sorun") · yöneticinin kapattığı türler → kutucuklar (toplam / kapsanan / kapsanmayan /
 * duraklatılmış — süzgeç `n_status`) → tür bazında kapsam (ProgressBar) → süzgeçler (arama `n_q`, takım `n_team`, tür
 * `n_type`, neden `n_reason`) → liste (geniş kapta tablo, dar kapta kart; sayfalama `n_page`/`n_ps`) + toplu
 * "Seçilenleri bildir" → takım arama listesi (`n_ct`). URL önekleri `n_` (PAGE_STATE_PREFIXES) — uygulamanın
 * `tab`/`domain`/`monitor`/`incident` anahtarlarına dokunulmaz.
 *
 * <p>İzlemeye git (2026-09-28): satır adı ve "İzlemeyi aç" ikonu GERÇEK bağlantı (nocModel.deepLinkOf) — kartına
 * tıklamakla aynı pencere (9 tür `?tab=<tür>&monitor=<id>`, SSL `?tab=dashboard&domain=<d>&open=cert`). Düz tık uygulama
 * içinde gezinir (Geri buraya süzgeçleriyle döner), Ctrl/⌘/orta tık yeni sekme. "…" menüsü: bağlantıyı kopyala ·
 * 7/24 ayarını düzenle (`open=noc`: form 7/24 alanına kaydırılmış) · bildirimi kapat.
 *
 * <p>Tek tık / toplu açma İYİMSER: satır hemen "kapsanıyor" görünür, sunucu satırı gelince onunla değişir; hata ya da
 * atlanan satır eski hâline döner (geri alma). Yarış koruması: `loadSeq` — hızlı yeniden yüklemede eski yanıt yenisini,
 * bir yazma sürerken başlamış eski yükleme de iyimser durumu EZMEZ. Kutucuk sayıları satırlardan türer (tek kaynak).
 */
const PAGE_URL = Object.freeze({ pageKey: 'n_page', sizeKey: 'n_ps' })
/** Toplu uç tavanı (NocController.MAX_BULK) — daha büyük seçim parçalara bölünür. */
const BULK_MAX = 500
const TILE_ICON = { total: ListChecks, covered: ShieldCheck, not_covered: BellOff, paused: CirclePause }
const TILE_TONE = { total: 'total', covered: 'valid', not_covered: 'warning', paused: 'paused' }

const parseList = (s, allowed) => String(s || '').split(',').map((x) => x.trim()).filter((x) => x && (!allowed || allowed.includes(x)))
const serializeList = (arr) => (arr && arr.length ? arr.join(',') : null)

function PageSkeleton({ label }) {
  return (
    <div aria-busy="true" data-slot="noc-skeleton" className="flex min-w-0 flex-col gap-3">
      <span role="status" className="sr-only">{label}</span>
      <div className="grid grid-cols-2 gap-2 lg:grid-cols-4">
        {Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-20 rounded-lg" />)}
      </div>
      <Skeleton className="h-28 w-full rounded-xl" />
      <Skeleton className="h-9 w-full sm:w-2/3" />
      {Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-14 rounded-lg" />)}
    </div>
  )
}

export default function NocCoveragePage({
  systemRole = 'USER', globalAdmin = false, myTeamIds = [], myTeams = [], userId = null, refreshKey = null,
}) {
  const t = useT()
  const toast = useToast()
  const copyLink = useCopyLink()
  const isMobile = useIsMobile()
  const dir = useTeamDirectory()
  const [listWidth, listRef] = useElementWidth()
  // Tablo ~880 px ister (seçim · tür · izleme · takım · durum · eylem); daha dar kapta kartlar
  const narrow = isMobile || (listWidth > 0 && listWidth < 880)

  // ── Veri ──────────────────────────────────────────────────────────────────────────────────────
  const [data, setData] = useState({ items: null, summary: null, error: null, at: null })
  const [refreshing, setRefreshing] = useState(false)
  const loadSeq = useRef(0)   // yarış koruması (proje deseni): yalnız EN SON başlatılan yükleme durum yazar
  const alive = useRef(true)
  const load = useCallback(async (manual = false) => {
    const id = ++loadSeq.current
    if (manual === true) setRefreshing(true)
    try {
      const r = unwrap(await api.noc.coverage())
      if (!alive.current || id !== loadSeq.current) return
      if (r.ok) {
        setData({ items: Array.isArray(r.data?.items) ? r.data.items : [], summary: r.data?.summary || null, error: null, at: Date.now() })
      } else {
        setData((d) => ({ ...d, error: r.error || 'error' }))
      }
    } catch (e) {
      if (alive.current && id === loadSeq.current) setData((d) => ({ ...d, error: e?.message || 'error' }))
    } finally {
      if (manual === true && alive.current) setRefreshing(false)
    }
  }, [])
  useEffect(() => {
    alive.current = true
    load()
    return () => { alive.current = false }
  }, [load])
  // Pano verisi tazelenince (5 dk döngüsü) liste de sessizce tazelenir — ilk damga hariç; kendi yoklaması YOK.
  const prevKey = useRef(refreshKey)
  useEffect(() => {
    if (prevKey.current === refreshKey) return
    const had = prevKey.current != null
    prevKey.current = refreshKey
    if (had) load()
  }, [refreshKey, load])

  // ── Süzgeç durumu (URL `n_*`) ─────────────────────────────────────────────────────────────────
  const [q, setQ] = useState(() => readUrlParam('n_q', ''))
  const [teams, setTeams] = useState(() => parseList(readUrlParam('n_team', '')))
  const [types, setTypes] = useState(() => parseList(readUrlParam('n_type', ''), NOC_TYPES))
  const [reasons, setReasons] = useState(() => parseList(readUrlParam('n_reason', ''), NOC_REASONS))
  const [status, setStatus] = useState(() => { const v = readUrlParam('n_status', ''); return STATUS_KEYS.includes(v) ? v : null })
  const [callTeam, setCallTeam] = useState(() => readUrlParam('n_ct', null))
  useUrlQuerySync({
    n_q: q.trim() || null, n_team: serializeList(teams), n_type: serializeList(types), n_reason: serializeList(reasons),
    n_status: status, n_ct: callTeam,
  })

  // ── Türetme ───────────────────────────────────────────────────────────────────────────────────
  const items = data.items
  const summary = useMemo(() => summarize(items || []), [items])
  const activeGroups = data.summary?.active_groups
  const disabledTypes = useMemo(() => (Array.isArray(data.summary?.disabled_types) ? data.summary.disabled_types : []), [data.summary])
  const ctx = useMemo(() => ({ disabledTypes, activeGroups: activeGroups ?? 1 }), [disabledTypes, activeGroups])
  const perm = useMemo(() => ({ systemRole, globalAdmin, myTeamIds }), [systemRole, globalAdmin, myTeamIds])
  const canEdit = useCallback((it) => canEditItem(it, perm), [perm])

  const filters = { q, teams, types, reasons, status }
  const filtered = useMemo(() => sortItems(applyFilters(items || [], filters)), [items, q, teams, types, reasons, status]) // eslint-disable-line react-hooks/exhaustive-deps
  const pager = usePagination(filtered, {
    listKey: 'noc-coverage', preset: 'page', resetDeps: [q, teams, types, reasons, status], url: PAGE_URL,
  })
  // Faset sayıları: "öteki süzgeçler + bu seçenek" ile kalan satır
  const teamOpts = useMemo(() => teamOptions(items || [], t('app.noTeam'), applyFilters(items || [], { ...filters, teams: [] })), [items, q, types, reasons, status, t]) // eslint-disable-line react-hooks/exhaustive-deps
  const typeOpts = useMemo(() => typeOptions(items || [], (k) => typeLabel(t, k), applyFilters(items || [], { ...filters, types: [] })), [items, q, teams, reasons, status, t]) // eslint-disable-line react-hooks/exhaustive-deps
  const reasonOpts = useMemo(() => reasonOptions(items || [], (k) => t(`noc.reason.${k}`), applyFilters(items || [], { ...filters, reasons: [] }))
    .map((o) => ({ ...o, hint: t(`noc.reasonHint.${o.value}`) })), [items, q, teams, types, status, t]) // eslint-disable-line react-hooks/exhaustive-deps

  // ── Seçim + yazma ────────────────────────────────────────────────────────────────────────────
  const [selected, setSelected] = useState(() => new Set())
  const [pending, setPending] = useState(() => new Set())
  const [bulkBusy, setBulkBusy] = useState(false)
  const selectableKeys = useMemo(() => new Set((items || []).filter((it) => canEdit(it) && !it.noc_notify).map(itemKey)), [items, canEdit])
  const selectedLive = useMemo(() => [...selected].filter((k) => selectableKeys.has(k)), [selected, selectableKeys])

  const patch = (keys, fn) => setData((d) => ({ ...d, items: (d.items || []).map((it) => (keys.has(itemKey(it)) ? fn(it) : it)) }))
  const markPending = (key, on) => setPending((p) => { const n = new Set(p); if (on) n.add(key); else n.delete(key); return n })

  async function setNotify(it, enabled) {
    const key = itemKey(it)
    const original = it
    markPending(key, true)
    loadSeq.current++   // sürmekte olan (eski) yükleme iyimser durumu ezmesin
    patch(new Set([key]), (cur) => withNotify(cur, enabled, ctx))
    if (enabled) setSelected((s) => { if (!s.has(key)) return s; const n = new Set(s); n.delete(key); return n })
    try {
      const r = unwrap(await api.noc.setMonitor(it.type, it.id, { enabled }))
      if (!alive.current) return
      if (!r.ok) throw new Error(r.error || '')
      const row = r.data && typeof r.data === 'object' && !Array.isArray(r.data) && r.data.id != null ? r.data : null
      if (row) patch(new Set([key]), (cur) => ({ ...cur, ...row }))
      toast.success(enabled ? t('noc.enabledToast', it.name) : t('noc.disabledToast', it.name))
    } catch (e) {
      if (!alive.current) return
      patch(new Set([key]), () => original)   // geri al
      const base = enabled ? t('noc.enableFail', it.name) : t('noc.disableFail', it.name)
      toast.error(e?.message ? `${base} — ${e.message}` : base)
    } finally {
      if (alive.current) markPending(key, false)
    }
  }

  async function enableSelected() {
    const keys = new Set(selectedLive)
    const targets = (items || []).filter((it) => keys.has(itemKey(it)))
    if (!targets.length) return
    const originals = new Map(targets.map((it) => [itemKey(it), it]))
    setBulkBusy(true)
    loadSeq.current++
    patch(keys, (cur) => withNotify(cur, true, ctx))
    // Başarıyla yazılmış parçalardaki satır sayısı (targets sırası) — kısmi hatada yalnız KALANI geri almak için
    let done = 0
    const skipped = []
    try {
      // Sunucu tek istekte en fazla BULK_MAX izleme kabul eder → parçalar sırayla
      let updated = 0
      for (let i = 0; i < targets.length; i += BULK_MAX) {
        const part = targets.slice(i, i + BULK_MAX)
        const r = unwrap(await api.noc.bulk(part, true))
        if (!alive.current) return
        if (!r.ok) throw new Error(r.error || '')
        done = i + part.length
        const sk = Array.isArray(r.data?.skipped) ? r.data.skipped : []
        skipped.push(...sk)
        updated += Number(r.data?.updated ?? part.length - sk.length) || 0
      }
      // UNCHANGED = sunucuda zaten açık → iyimser hâl doğru, geri alınmaz; diğer atlananlar (yetki, bulunamadı) geri alınır
      const { rollback, counts, failed } = summarizeSkipped(skipped)
      if (rollback.size) patch(rollback, (cur) => originals.get(itemKey(cur)) ?? cur)
      setSelected(new Set())
      // Atlama nedenleri düz sözcükle: "yetkiniz yok: 2, zaten açıktı: 1"
      const why = BULK_SKIP_REASONS.filter((k) => counts[k]).map((k) => t(`noc.bulkReason.${k}`, counts[k])).join(', ')
      if (failed) toast.error(t('noc.bulkPartial', updated, why))
      else toast.success(why ? `${t('noc.bulkDone', updated)} · ${why}` : t('noc.bulkDone', updated))
      load()   // sunucu gerçeğiyle eşitle (nedenler iyimser tahminden farklı olabilir)
    } catch (e) {
      if (!alive.current) return
      // Kısmi başarı: önceki parçalar sunucuda YAZILDI → yalnız yazılmamış parçaların satırları + o ana dek atlananlar
      // geri alınır (hepsini geri almak açılmış izlemeleri ekranda "kapalı" gösterirdi); sonra sunucuyla eşitlenir.
      const back = new Set(targets.slice(done).map(itemKey))
      for (const k of summarizeSkipped(skipped).rollback) back.add(k)
      if (back.size) patch(back, (cur) => originals.get(itemKey(cur)) ?? cur)
      toast.error(e?.message ? `${t('noc.bulkError')} — ${e.message}` : t('noc.bulkError'))
      load()
    } finally {
      if (alive.current) setBulkBusy(false)
    }
  }

  // İzlemeye git (2026-09-28): kartına tıklamakla aynı pencere. Gitmeden ÖNCE bekleyen süzgeç yazımı (300 ms debounce)
  // bu geçmiş kaydına işlenir — Geri kapsam sayfasına süzgeçleriyle döner (sekme geçişi sayfayı söker, bekleyen yazım
  // iptal olurdu). navigateTo → App.handleTabChange yeni geçmiş kaydı AÇAR (pushState).
  const go = (it, opts) => {
    const target = openTargetOf(it, opts)
    if (!target) return
    flushUrlQuerySync()
    navigateTo(target.tab, target.params)
  }
  const h = {
    canEdit,
    hrefOf: (it) => deepLinkOf(it),
    onOpen: (it) => go(it),
    onEditNoc: (it) => go(it, { action: DEEP_OPEN.NOC }),
    onCopyLink: (it) => copyLink(deepLinkOf(it)),
    onEnable: (it) => setNotify(it, true),
    onDisable: (it) => setNotify(it, false),
    onToggle: (key) => setSelected((s) => { const n = new Set(s); if (n.has(key)) n.delete(key); else n.add(key); return n }),
    onSelectPage: (rows, on) => setSelected((s) => {
      const n = new Set(s)
      for (const it of rows) { if (on) n.add(itemKey(it)); else n.delete(itemKey(it)) }
      return n
    }),
  }

  // ── Takım arama listesi ──────────────────────────────────────────────────────────────────────
  // Seçenekler = arama listesini OKUYABİLECEĞİ takımlar (sunucu: görüş kapsamı YA DA lider/elle atanmış müdür):
  //  - global yönetici: tüm rehber · lider/müdür olduğu takımlar (üye olmasa da — listesini o yönetir) · kendi takımları
  //  - satır takımları YALNIZ üyesiyse (ya da global görüntüleyiciyse): envanter kökenli satır UG takımına da görünür ama
  //    `team_id` SY takımıdır → UG kullanıcısı onu seçip (ya da ilk açılışta düşüp) 403 bandı görürdü.
  const globalViewer = globalAdmin || systemRole === 'AUDIT'
  const callTeamOptions = useMemo(() => {
    const m = new Map()
    const put = (id, name) => { if (id == null) return; const k = String(id); m.set(k, name || m.get(k) || k) }
    if (globalAdmin && dir.ready) for (const tm of dir.byId.values()) put(tm?.id, tm?.name)
    if (userId != null) {
      for (const tm of dir.byId.values()) {
        if ([tm?.leader_id, tm?.manager_id].some((x) => x != null && String(x) === String(userId))) put(tm.id, tm.name)
      }
    }
    for (const tm of myTeams || []) put(tm?.id, tm?.name)
    const mine = new Set((myTeamIds || []).map(String))
    for (const it of items || []) if (it.team_id != null && (globalViewer || mine.has(String(it.team_id)))) put(it.team_id, it.team_name)
    return [...m.entries()].map(([value, label]) => ({ value, label })).sort((a, b) => a.label.localeCompare(b.label, 'tr'))
  }, [globalAdmin, globalViewer, userId, dir, myTeams, myTeamIds, items])
  const effectiveCallTeam = useMemo(() => {
    if (callTeam && callTeamOptions.some((o) => o.value === String(callTeam))) return String(callTeam)
    const own = (myTeams || []).map((tm) => String(tm.id)).find((id) => callTeamOptions.some((o) => o.value === id))
    return own ?? callTeamOptions[0]?.value ?? null
  }, [callTeam, callTeamOptions, myTeams])
  const canEditCallFor = useCallback((tid) => canEditCallList(tid, {
    systemRole, globalAdmin, myTeamIds, userId,
    leaderId: dir.byId.get(Number(tid))?.leader_id ?? null, managerId: dir.byId.get(Number(tid))?.manager_id ?? null,
  }), [systemRole, globalAdmin, myTeamIds, userId, dir])

  // ── Çizim ─────────────────────────────────────────────────────────────────────────────────────
  const loading = items === null && !data.error
  const total = summary.total
  const pct = total - summary.paused > 0 ? Math.round((summary.covered / (total - summary.paused)) * 100) : null
  const noGroups = items !== null && activeGroups != null && !(activeGroups > 0)
  const canOpenSettings = systemRole === 'ADMIN'

  const tiles = STATUS_KEYS.reduce((acc, k) => [...acc, {
    key: k, Icon: TILE_ICON[k], cls: TILE_TONE[k], value: summary[k], label: t(`noc.tile.${k}`), hint: t(`noc.tileHint.${k}`),
  }], [{ key: 'total', Icon: TILE_ICON.total, cls: TILE_TONE.total, value: total, label: t('noc.tile.total'), hint: t('noc.tileHint.total') }])

  const teamLabel = (v) => teamOpts.find((o) => o.value === v)?.label ?? v
  const chips = [
    ...(status ? [{ key: 'status', label: t('noc.chipStatus', t(`noc.tile.${status}`)), onRemove: () => setStatus(null) }] : []),
    ...(q.trim() ? [{ key: 'q', label: `“${q.trim()}”`, onRemove: () => setQ('') }] : []),
    ...(teams.length ? [{ key: 'team', label: t('noc.chipTeam', teams.map(teamLabel).join(', ')), onRemove: () => setTeams([]) }] : []),
    ...(types.length ? [{ key: 'type', label: t('noc.chipType', types.map((k) => typeLabel(t, k)).join(', ')), onRemove: () => setTypes([]) }] : []),
    ...(reasons.length ? [{ key: 'reason', label: t('noc.chipReason', reasons.map((k) => t(`noc.reason.${k}`)).join(', ')), onRemove: () => setReasons([]) }] : []),
  ]
  const clearAll = () => { setStatus(null); setQ(''); setTeams([]); setTypes([]); setReasons([]) }

  return (
    <div data-slot="noc-page" className="flex min-w-0 flex-col gap-4">
      <PageHeader icon={Headset} title={t('noc.pageTitle')} description={t('noc.pageDesc')} className="mb-0"
        meta={(
          <>
            {pct != null && (
              <Badge variant="secondary" data-slot="noc-meta-pct"
                className={pct === 100 ? 'gap-1 bg-success/15 text-success tabular-nums dark:bg-success/20' : 'tabular-nums'}>
                {pct === 100 && <CircleCheck aria-hidden="true" />}{t('noc.metaPct', pct)}
              </Badge>
            )}
            {NOC_LEVELS.includes(data.summary?.min_level) && (
              <Badge variant="outline" data-slot="noc-meta-level" className="font-normal text-muted-foreground">
                {t('noc.metaMinLevel', t(`noc.level.${data.summary.min_level}`))}
              </Badge>
            )}
            {activeGroups != null && (
              <Badge variant="outline" data-slot="noc-meta-groups" className="font-normal text-muted-foreground">{t('noc.metaActiveGroups', activeGroups)}</Badge>
            )}
            {data.at && <span data-slot="noc-meta-updated">{t('app.lastUpdate')} {formatDate(new Date(data.at).toISOString())}</span>}
          </>
        )}
        actions={(
          <>
            <Button type="button" variant="outline" onClick={() => load(true)} disabled={refreshing} aria-busy={refreshing || undefined}
              title={t('app.refresh')} aria-label={t('app.refresh')} className="sm:pointer-coarse:min-h-10">
              <RefreshCw aria-hidden="true" className={refreshing ? 'animate-spin motion-reduce:animate-none' : undefined} />
              <span className="hidden md:inline">{t('app.refresh')}</span>
            </Button>
            {canOpenSettings && (
              <Button type="button" variant="secondary" onClick={() => navigateTo('settings', { sec: 'noc' })} data-action="noc-open-settings" className="sm:pointer-coarse:min-h-10">
                <Settings2 aria-hidden="true" />{t('noc.openSettings')}
              </Button>
            )}
          </>
        )} />

      {data.error && (
        <AlertBanner tone="danger" role="alert" title={t('noc.loadError')}
          actions={<Button type="button" variant="outline" size="sm" className="h-10 sm:h-8 sm:pointer-coarse:h-10" onClick={() => load(true)} disabled={refreshing}><RefreshCw aria-hidden="true" />{t('noc.retry')}</Button>}>
          {String(data.error)}{items ? ` · ${t('noc.staleShown')}` : ''}
        </AlertBanner>
      )}

      {loading && <PageSkeleton label={t('noc.loading')} />}

      {noGroups && (
        <AlertBanner tone="warning" icon={ShieldOff} title={t('noc.noGroupsTitle')} className="mb-0">
          <span className="block" data-slot="noc-no-groups">{globalAdmin ? t('noc.noGroupsAdmin') : t('noc.noGroupsUser')}</span>
          {globalAdmin && (
            <Button type="button" variant="outline" size="sm" className="mt-2 h-10 sm:h-8 sm:pointer-coarse:h-10" onClick={() => navigateTo('settings', { sec: 'noc' })}>
              <Settings2 aria-hidden="true" />{t('noc.noGroupsCta')}
            </Button>
          )}
        </AlertBanner>
      )}
      {items !== null && disabledTypes.length > 0 && (
        <AlertBanner tone="info" title={t('noc.disabledTypesTitle')} className="mb-0">
          {t('noc.disabledTypesBody', disabledTypes.map((k) => typeLabel(t, k)).join(', '))}
        </AlertBanner>
      )}

      {items !== null && total === 0 && (
        <StatusBlock tone="neutral" icon={Headset} title={t('noc.emptyScopeTitle')} description={t('noc.emptyScopeDesc')}
          className="rounded-xl border border-dashed" />
      )}

      {items !== null && total > 0 && (
        <>
          {summary.not_covered === 0 && !noGroups && (
            <AlertBanner tone="success" icon={CircleCheck} title={t('noc.allCoveredTitle')} className="mb-0">
              <span data-slot="noc-all-covered">{t('noc.allCoveredBody')}</span>
            </AlertBanner>
          )}

          <div className="[&>[data-slot=stats-panel]]:mb-0">
            <MonitorStatsBar items={tiles} activeFilter={status}
              onStatClick={(k) => setStatus((cur) => (k === 'total' || cur === k ? null : k))} />
          </div>

          <NocTypeCoverage byType={summary.by_type} disabledTypes={disabledTypes} t={t} />

          <NocCoverageToolbar t={t} q={q} onQ={setQ} teamOpts={teamOpts} teams={teams} onTeams={setTeams}
            typeOpts={typeOpts} types={types} onTypes={setTypes} reasonOpts={reasonOpts} reasons={reasons} onReasons={setReasons}
            chips={chips} onClearAll={clearAll} shown={filtered.length} total={total} />

          <section ref={listRef} aria-label={t('noc.listLabel')} className="flex min-w-0 flex-col gap-3">
            {filtered.length === 0 ? (
              <StatusBlock tone="neutral" icon={ListChecks} title={t('noc.noneFiltered')} description={t('empty.hintFilter')}
                className="rounded-xl border border-dashed"
                actions={<Button type="button" variant="outline" size="sm" className="h-10 sm:h-8 sm:pointer-coarse:h-10" onClick={clearAll}>{t('app.clearFilters')}</Button>} />
            ) : (
              <NocCoverageList items={pager.pageItems} narrow={narrow} t={t} h={h} selected={selected} pending={pending} />
            )}
            <PaginationBar {...pager} />
          </section>

          {selectedLive.length > 0 && (
            <div data-slot="noc-bulk-bar" role="region" aria-label={t('noc.bulkRegion')}
              className="sticky bottom-0 z-[60] flex flex-wrap items-center gap-2 rounded-xl border border-primary bg-card/95 px-4 py-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] shadow-[0_-4px_18px_rgba(0,0,0,.10)] backdrop-blur">
              <span className="mr-auto text-sm font-semibold tabular-nums">{t('noc.bulkSelected', selectedLive.length)}</span>
              <Button type="button" variant="outline" className="h-10 flex-1 sm:h-9 sm:flex-none sm:pointer-coarse:h-10" onClick={() => setSelected(new Set())} disabled={bulkBusy}>
                {t('noc.bulkClear')}
              </Button>
              <Button type="button" className="h-10 flex-1 sm:h-9 sm:flex-none sm:pointer-coarse:h-10" onClick={enableSelected} disabled={bulkBusy}
                aria-busy={bulkBusy || undefined} data-action="noc-bulk-enable">
                {bulkBusy ? <Spinner size={15} inline decorative /> : <BellRing aria-hidden="true" />}{t('noc.bulkEnable', selectedLive.length)}
              </Button>
            </div>
          )}
        </>
      )}

      {items !== null && callTeamOptions.length > 0 && (
        <NocCallListCard t={t} teamOptions={callTeamOptions} teamId={effectiveCallTeam}
          onTeamChange={(v) => setCallTeam(v || null)} canEditFor={canEditCallFor} />
      )}
    </div>
  )
}
