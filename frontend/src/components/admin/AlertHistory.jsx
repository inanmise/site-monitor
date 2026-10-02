import { useState, useEffect, useCallback, useRef, useMemo, useId } from 'react'
import {
  History, RefreshCcw, Download, Siren, OctagonAlert, TriangleAlert, CircleAlert, BellRing, UserCheck, Clock,
  CheckCircle2, RotateCcw, FilterX, Eye, Link2, ExternalLink, Inbox, PhoneCall, CalendarSearch, CalendarRange,
} from 'lucide-react'
import { api } from '../../api/client'
import { useDialog } from '../ui/Dialog.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { useIsMobile } from '../../hooks/use-mobile.js'
import { useUrlQuerySync, readUrlParam } from '../../hooks/useUrlQuerySync.js'
import { announceAlertsChanged } from '../../hooks/useOpenAlerts.js'
import { useServerPagination } from '../../hooks/useServerPagination.js'
import { copyText } from '../../utils/copyText.js'
import PageHeader from '../ui/PageHeader.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import SavedViewsMenu from '../ui/SavedViewsMenu.jsx'
import { VIEW_SPECS } from '../../hooks/userPrefsModel.js'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import MonitorStatsBar from '../MonitorStatsBar.jsx'
import AlertNoisePanel from './AlertNoisePanel.jsx'              // gürültü analizi (2026-09-12, #18)
import AlertTeamStatsPanel from './alerts/AlertTeamStatsPanel.jsx'   // takım kırılımı (2026-09-16)
import AlertToolbar from './alerts/AlertToolbar.jsx'
import ReNotifyConfirmModal from './alerts/ReNotifyConfirmModal.jsx'
import { OpenAlertCard, OpenAlertList, AlertRowsList, ListSkeleton } from './alerts/AlertLists.jsx'
import { AlertDetailSheet, AlertDetailModal } from './alerts/AlertDetail.jsx'
import { NocCallQuickSheet } from './alerts/NocCallLog.jsx'   // 7/24 arama kaydı — telefonda alttan hızlı giriş (2026-09-27)
import ActionNoteDialog from '../incidents/ActionNoteDialog.jsx'   // gerekçeli sahiplen / çöz penceresi (2026-09-28)
import { contextFromAlert } from '../incidents/actionNoteModel.js'
import {
  FILTER_DEFAULTS, LEVELS, TABS, tabFromUrl, filtersFromUrl, filtersToUrl, activeAlertFilters, listParams, csvParams,
  iso24hAgo, groupByDay, alertLink, alertHref, alertSourceTab, actBlockReason, isSrcKey, isPreset, widerPreset, sortParts, toggleSort,
} from './alerts/alertHistoryModel.js'
import { presetLabel } from './alerts/AlertToolbar.jsx'
import { ALERT_TYPES, alertTypeLabel } from '../../utils/alertTypeMeta.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Label } from '@/components/shadcn/label'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { cn } from '@/lib/utils'

// Testler ve diğer ekranlar bu adlarla içe aktarıyor — kaynak artık alarm modelinde.
export { groupPushRows, statusLabel } from './alerts/alertHistoryModel.js'

/** Onay/çözüm yanıtından detaya taşınan alanlar (zenginleştirme alanları sunucu yanıtında yok — ezilmesin). */
const ACTION_FIELDS = ['acknowledged', 'acknowledged_by', 'acknowledged_at', 'acknowledged_note', 'resolved', 'resolved_by', 'resolved_at', 'resolved_note']

/**
 * Alarm Geçmişi (2026-09-27 yeniden tasarım, shadcn) — takım kapsamlı alarm listesi, inceleme odaklı:
 *   ui/PageHeader (canlı özet çipleri, Yenile, CSV) → İSTATİSTİKLER EN ÜSTTE (MonitorStatsBar; kartlar süzgeç) →
 *   takım kırılımı + gürültü analizi (katlanır) → Açık | Kapalı | Tümü sekmeleri + süzgeç araç çubuğu (faset menüleri,
 *   etkin çipler; telefonda alt Sheet) → açık alarmlar EYLEM KARTLARI, kapalılar ÖZET SATIRLARI (gün gruplu) →
 *   sağdan açılan DETAY (durum, eylemler, temel bilgiler, zaman çizelgesi, e-posta önizlemesi + push partileri).
 *
 * Sözleşmeler (değişmedi): süzme/sayfalama SUNUCUDA (istemcide süzmek yalnız açık sayfayı süzerdi); sahiplenme ve
 * çözüm GEREKÇE ister (AlertActionNote — tekli ve toplu yolda); yetki sunucuda (`alerts.actions`, 403 → toast);
 * URL anahtarları uygulamanın PAGE_STATE_PARAMS'ı (`view` — `tab` DEĞİL, ISSUE-002; `type q level team ack from to
 * range sort alert page ps` — tam sözleşme alertHistoryModel.js başında; `from` hızlı dönem belirteci de taşır (`1h 24h 7d
 * 30d`), `range` tarih alanı kipi (`active` = "Tümü"nde aralıkta AKTİF olanlar, haftalık e-postanın alarm bağlantısı;
 * `resolved` / `opened`), `sort` sütun sıralaması (`<anahtar>[_asc]`)); bildirim kutusu derin bağlantısı `?alert=<id>`
 * (sm:navigate ile açıkken de). 2026-10-01: hızlı dönemler, sütun sıralaması/süzgeçleri, Takım sütunu (Alarm Geçmişi isteği).
 *
 * @param domain  GÖMÜLÜ kullanım (izleme/sertifika penceresinin "Alarm Geçmişi" sekmesi): yalnız bu hedef.
 * @param types   GÖMÜLÜ kullanım: yalnız bu alarm tipleri (sunucuda süzülür).
 * @param urlSync bağımsız sayfa (?tab=alerthistory): başlık, istatistikler, paneller, araç çubuğu ve URL eşlemesi.
 */
export default function AlertHistory({ domain = null, urlSync = false, types = null, globalViewer = false, myTeamIds = null }) {
  // Dizi kimliği her render'da değişir; yükleme bağımlılığına DİZİ koymak sonsuz döngü demek → tek dize.
  const typesParam = Array.isArray(types) && types.length > 0 ? types.join(',') : null
  const embedded = !urlSync
  const t = useT()
  const locale = useDateLocale()
  const { showConfirm } = useDialog()
  const toast = useToast()
  const phone = useIsMobile()
  const bulkAllId = useId()

  const [tab, setTab] = useState(() => (urlSync ? tabFromUrl(readUrlParam('view', null)) : 'open'))
  const [filters, setFilters] = useState(() => (urlSync ? filtersFromUrl(readUrlParam) : { ...FILTER_DEFAULTS }))
  const patch = useCallback((p) => setFilters((f) => ({ ...f, ...p })), [])
  const resetFilters = useCallback(() => setFilters({ ...FILTER_DEFAULTS }), [])

  const [alerts, setAlerts] = useState([])
  const [facets, setFacets] = useState({ typeCounts: {}, staleHours: 24, push: {} })
  const [loading, setLoading] = useState(true)
  const [loaded, setLoaded] = useState(false)
  const [error, setError] = useState(null)
  const [summary, setSummary] = useState(null)        // null = bilinmiyor → kartlar çizilmez (uydurma sayı yok)
  const [summaryLoading, setSummaryLoading] = useState(urlSync)
  const [updatedAt, setUpdatedAt] = useState(null)
  const [teams, setTeams] = useState([])
  const [selected, setSelected] = useState(() => new Set())
  const [bulkBusy, setBulkBusy] = useState(false)
  const [detail, setDetail] = useState(null)
  const [notifying, setNotifying] = useState(null)
  const [renotifyModal, setRenotifyModal] = useState(null)
  const [renotifySending, setRenotifySending] = useState(false)
  // Gerekçeli sahiplen / çöz penceresi: { action: 'ack'|'resolve', items: ActionContext[], submit(note) } — tekli ve toplu
  const [noteDialog, setNoteDialog] = useState(null)
  const [nowMs, setNowMs] = useState(() => Date.now())
  // Bildirim kutusundan derin bağlantı (2026-09-16): ?alert=<id> — kart vurgulanır, detay açılır, param tüketilir.
  const [linkedAlertId, setLinkedAlertId] = useState(() => (urlSync ? readUrlParam('alert', null) : null))
  const linkOpenedRef = useRef(null)
  const linkFetchedRef = useRef(null)   // listede olmayan bağlantı için tekil uç BİR kez sorulur (aşağıdaki derin bağlantı etkisi)
  // 7/24 arama kaydı (2026-09-27): yazma kapısı SUNUCUDAN (`noc_can_write` — kapsamlı müdür matriste ADMIN görünür ama
  // yazamaz); derin bağlantı `&n_call=1` (7/24 e-postasındaki "Arama kaydı ekle") uyarıyı form odakta açar ve tüketilir.
  const [nocCanWrite, setNocCanWrite] = useState(false)
  // Sahiplen/çöz/tekrar bildir izni (alerts.actions) SUNUCUDAN (`can_act`, 2026-09-28): AUDIT rolündeki 7/24 operatörü
  // arama kaydı girer ama bu eylemleri yapamaz — düğmeler 403'e giden ölü düğme olmasın. Alan yoksa açık sayılır.
  const [canAct, setCanAct] = useState(true)
  const [nocFocus, setNocFocus] = useState(null)      // { id, n } — masaüstü detayında formu odakla
  const [quickCall, setQuickCall] = useState(null)    // telefon: alttan hızlı giriş açık olan uyarı
  const [linkedNocCall, setLinkedNocCall] = useState(() => (urlSync ? readUrlParam('n_call', null) === '1' : false))
  const linkFetchSeq = useRef(0)

  // Sayfalama standardı: süzgeç/sekme DEĞERİ değişince sayfa 1 (aynı render'da), mount'ta ASLA; page/ps yalnız sekmede.
  const sp = useServerPagination({ listKey: 'alert-history', preset: 'page',
    resetDeps: [tab, filters, domain, typesParam],
    url: urlSync ? { pageKey: 'page', sizeKey: 'ps' } : null, apiBase: 0 })
  const { apiPage: page, pageSize, setTotal, reset: resetPage } = sp

  useUrlQuerySync(filtersToUrl(filters, tab), { enabled: urlSync })

  // Fetch yarışı: yalnız EN SON başlatılan isteğin yanıtı uygulanır (bayat sayfa listeyi ezmesin).
  const loadSeq = useRef(0)
  const load = useCallback(async () => {
    const seq = ++loadSeq.current
    setLoading(true)
    try {
      const res = await api.admin.getAlerts(listParams({ tab, filters, page, pageSize, domain, typesParam }))
      if (seq !== loadSeq.current) return
      if (res?.success) {
        const rows = res.data ?? []
        setAlerts(rows)
        // Seçim listede KALANLARA budanır: karttan tekli sahiplen/çöz (refreshAll) uyarıyı listeden düşürür ama seçimi
        // sıfırlamaz → bayat id sayaçta kalıp toplu isteğe (pencere göstermediği hâlde) giriyor, "1 başarısız" üretiyordu.
        setSelected((s) => {
          if (s.size === 0) return s
          const live = new Set(rows.map((a) => a.id))
          const next = new Set([...s].filter((id) => live.has(id)))
          return next.size === s.size ? s : next
        })
        setTotal(res.total ?? 0)
        setFacets({
          typeCounts: res.type_counts ?? {},
          staleHours: res.stale_hours ?? 24,
          push: res.push_summary && typeof res.push_summary === 'object' ? res.push_summary : {},
        })
        setNocCanWrite(res.noc_can_write === true)
        setCanAct(res.can_act !== false)
        setError(null)
        setLoaded(true)
        setUpdatedAt(new Date())
      } else if (res != null) {
        setError(res?.error || t('alh.loadError'))
      }
    } catch (e) {
      if (seq === loadSeq.current) setError(e?.message || t('alh.loadError'))
    } finally {
      if (seq === loadSeq.current) setLoading(false)
    }
  }, [tab, filters, page, pageSize, domain, typesParam, t, setTotal])

  /**
   * Üst istatistikler — süzgeçten BAĞIMSIZ, kapsamdaki AÇIK küme (seviye kırılımı, sahiplenilmemiş, uzun süredir açık)
   * + son 24 saatte çözülen toplamı. İki küçük istek (size=1); yalnız bağımsız sayfada, açılışta / Yenile'de / eylemden sonra.
   */
  const loadSummary = useCallback(async () => {
    if (!urlSync) return
    setSummaryLoading(true)
    try {
      const [open, res24] = await Promise.all([
        api.admin.getAlerts({ resolved: 'false', page: 0, size: 1 }),
        api.admin.getAlerts({ resolved: 'true', resolvedSince: iso24hAgo(Date.now()), page: 0, size: 1 }),
      ])
      if (!open?.success) { setSummary(null); return }
      setSummary({
        open: Number(open.total ?? 0),
        levels: open.level_counts ?? {},
        unacked: Number(open.unacked_total ?? 0),
        stale: Number(open.stale_total ?? 0),
        staleHours: open.stale_hours ?? 24,
        resolved24: res24?.success ? Number(res24.total ?? 0) : null,
      })
    } catch {
      setSummary(null)
    } finally {
      setSummaryLoading(false)
    }
  }, [urlSync])

  // Sıra bilinçli: liste isteği özet isteklerinden ÖNCE başlar.
  useEffect(() => { load() }, [load])
  useEffect(() => { loadSummary() }, [loadSummary])
  const refreshAll = useCallback(() => { load(); loadSummary() }, [load, loadSummary])

  // Takım listesi yalnız bağımsız sayfada ve bir kez.
  useEffect(() => {
    if (!urlSync) return
    Promise.resolve().then(() => api.admin.getTeams())
      .then((res) => { if (res?.success) setTeams(res.data ?? []) }).catch(() => {})
  }, [urlSync])

  // Liste bağlamı değişince seçim sıfırlansın — bayat id'ler seçili kalmasın.
  useEffect(() => { setSelected(new Set()) }, [tab, page, pageSize, filters, domain])

  // Açık alarm varken "açık kalma" süreleri canlı (10 sn).
  useEffect(() => {
    const live = alerts.some((a) => !a.resolved) || (detail && !detail.resolved)
    if (!live) return undefined
    const id = setInterval(() => setNowMs(Date.now()), 10_000)
    return () => clearInterval(id)
  }, [alerts, detail])

  // Liste tazelenince açık detay da güncellensin (aynı alarm sayfadaysa).
  useEffect(() => {
    setDetail((d) => {
      if (!d) return d
      const hit = alerts.find((x) => String(x.id) === String(d.id))
      return hit ? { ...d, ...hit } : d
    })
  }, [alerts])

  // İKİNCİ (ve sonraki) derin bağlantı — sayfa AÇIKKEN gelen bildirim tıklaması: sm:navigate paramları doğrudan
  // uygulanır (App URL'i henüz yazmamış olabilir); geri/ileri düğmesinde URL'den yeniden okunur (2026-09-16).
  useEffect(() => {
    if (!urlSync) return undefined
    const apply = (p) => {
      setTab(tabFromUrl(p.view))
      // Yeni bir bildirim niyeti: kalan süzgeçler sıfırlanır, yoksa aranan alarm eleniyor olabilir.
      setFilters({ ...FILTER_DEFAULTS, type: p.type ? String(p.type) : '', q: p.q ? String(p.q) : '',
        src: p.src && isSrcKey(p.src) ? String(p.src) : '' })   // İzleme menüsü rozeti (2026-09-30): kategori süzgeci
      resetPage()
      linkOpenedRef.current = null
      linkFetchedRef.current = null
      // Önceki bağlantının tekil-uç isteği hâlâ sürüyor olabilir: sıra ilerler → geç gelen yanıtı YENİ bağlantının
      // detayını ezmez (onPop da buradan geçer — geri/ileri de aynı koruma).
      linkFetchSeq.current++
      setLinkedAlertId(p.alert != null ? String(p.alert) : null)
      setLinkedNocCall(p.n_call != null && String(p.n_call) === '1')
    }
    const onNav = (e) => { if (e?.detail?.tab === 'alerthistory') apply(e.detail.params || {}) }
    const onPop = () => apply({ view: readUrlParam('view', null), type: readUrlParam('type', ''), q: readUrlParam('q', ''), src: readUrlParam('src', ''), alert: readUrlParam('alert', null), n_call: readUrlParam('n_call', null) })
    window.addEventListener('sm:navigate', onNav)
    window.addEventListener('popstate', onPop)
    return () => { window.removeEventListener('sm:navigate', onNav); window.removeEventListener('popstate', onPop) }
  }, [urlSync, resetPage])

  const openDetail = useCallback((a) => { setNocFocus(null); setDetail(a) }, [])
  const closeDetail = useCallback(() => setDetail(null), [])
  /** "Arama kaydet": telefonda alttan hızlı giriş, masaüstünde detay + form odakta. Gömülü kullanımda (izleme penceresi,
   *  ModalShell 2000+) alttan Sheet pencerenin ALTINDA kalırdı → orada her zaman detay penceresi + form odakta. */
  const logCall = useCallback((a) => {
    if (!a) return
    if (phone && !embedded) { setQuickCall(a); return }
    setDetail(a)
    setNocFocus((f) => ({ id: a.id, n: (f?.n ?? 0) + 1 }))
  }, [phone, embedded])
  /** Kayıt eklenip/silinince liste göstergesi yeniden yüklemeden güncellenir (işlevsel güncellemeler). */
  const onNocChanged = useCallback((alertId, summary) => {
    if (alertId == null || !summary) return
    const same = (x) => x && String(x.id) === String(alertId)
    setAlerts((prev) => prev.map((x) => (same(x) ? { ...x, ...summary } : x)))
    setDetail((d) => (same(d) ? { ...d, ...summary } : d))
    setQuickCall((q) => (same(q) ? { ...q, ...summary } : q))
  }, [])

  // Derin bağlantı: liste gelince kartı bul → detayı aç + görünüme kaydır; param URL'den silinir (sekme dönüşünde
  // tekrar vurgulamasın). Bulunamazsa (başka sayfa/süzgeç/görünüm) tekil uçtan açılır; o da yoksa yalnız param tüketilir.
  useEffect(() => {
    if (!linkedAlertId || !loaded) return undefined
    const hit = alerts.find((a) => String(a.id) === String(linkedAlertId))
    if (hit && linkOpenedRef.current !== linkedAlertId) {
      linkOpenedRef.current = linkedAlertId
      if (linkedNocCall && nocCanWrite) logCall(hit)
      else setDetail(hit)
      setLinkedNocCall(false)
    } else if (!hit && linkOpenedRef.current !== linkedAlertId && linkFetchedRef.current !== linkedAlertId) {
      // Uyarı bu sayfada/süzgeçte değilse (fırtına, kapalı uyarı, eski olay — 7/24 bağlantısı, Kontrol Geçmişi'nin kesinti
      // çizelgesi, bildirim satırı; E2 2026-09-28e: eskiden yalnız 7/24 bağlantısı açılıyordu) tekil uçtan açılır. Liste
      // kaydı sonradan getirirse üstteki dal açar — hangisi önce gelirse pencere BİR kez açılır. Sıra numaralı: arada başka
      // bir bağlantı gelirse bayat yanıt uygulanmaz.
      linkFetchedRef.current = linkedAlertId
      const my = ++linkFetchSeq.current
      const wanted = linkedAlertId
      const wantCall = linkedNocCall
      Promise.resolve().then(() => api.admin.getAlert(wanted)).catch(() => null).then((res) => {
        const one = res?.success && res.data && !Array.isArray(res.data) && String(res.data.id) === String(wanted) ? res.data : null
        if (my !== linkFetchSeq.current || !one || linkOpenedRef.current === wanted) return
        linkOpenedRef.current = wanted
        setLinkedNocCall(false)
        if (wantCall && res.noc_can_write === true) { setNocCanWrite(true); logCall(one) } else setDetail(one)
      })
    }
    const id = setTimeout(() => {
      try { document.querySelector('[data-alert-id][data-linked="true"]')?.scrollIntoView({ block: 'center', behavior: 'smooth' }) } catch { /* jsdom */ }
    }, 80)
    try {
      // `n_call` de tüketilir: geri gelindiğinde form yeniden açılmasın.
      const url = new URL(window.location.href)
      if (url.searchParams.has('alert') || url.searchParams.has('n_call')) {
        url.searchParams.delete('alert'); url.searchParams.delete('n_call'); window.history.replaceState({}, '', url)
      }
    } catch { /* yoksay */ }
    return () => clearTimeout(id)
  }, [linkedAlertId, alerts, loaded, linkedNocCall, nocCanWrite, logCall])

  // Takım adı dizini (2026-10-01, performans): satır başına `teams.find` (satır × takım) yerine bir kez kurulan
  // id → ad haritası. İlk eşleşme kazanır (eski `find` ile aynı); bulunamayan / adsız takım null.
  const teamNameById = useMemo(() => {
    const m = new Map()
    for (const tm of teams) { const k = String(tm?.id); if (tm?.id != null && !m.has(k)) m.set(k, tm.name) }
    return m
  }, [teams])
  const teamNameOf = useCallback((id) => {
    if (id == null) return null
    return teamNameById.get(String(id)) ?? null
  }, [teamNameById])

  // ── Eylemler (gerekçe zorunlu — sunucu AlertActionNote ile aynı kural) ──
  // Pencere: incidents/ActionNoteDialog (Olaylar ile ortak). Sunucu çağrısı pencerenin İÇİNDEN (`submit`): hata
  // (kural, 403, ağ) pencerede satır içi gösterilir, pencere kapanmaz, not kaybolmaz; başarıda tost + yenileme burada.
  const mergeDetail = (id, data, fallback) => setDetail((d) => {
    if (!d || String(d.id) !== String(id)) return d
    const next = { ...d, ...fallback }
    if (data && typeof data === 'object') for (const k of ACTION_FIELDS) if (data[k] != null) next[k] = data[k]
    return next
  })
  const nowIso = () => new Date().toISOString().slice(0, 19)
  /** Alarm → pencere bağlamı: takım adı sayfanın dizininden, push özeti sayfanın `push_summary`'sinden. */
  const noteContext = (a) => contextFromAlert(a, { teamName: teamNameOf(a.team_id), push: facets.push[String(a.id)] })

  function ack(a) {
    if (!a || actBlocked(a)) return   // başka takımın uyarısı / eylem izni yok — düğme zaten çizilmez (savunma)
    setNoteDialog({ action: 'ack', items: [noteContext(a)], submit: (note) => submitAck(a, note) })
  }
  async function submitAck(a, note) {
    let r
    try { r = await api.admin.acknowledgeAlert(a.id, note) } catch { r = null }
    if (r == null || r.success === false) return { ok: false, error: r?.error || t('alh.note.error') }
    toast.success(t('alh.ackSuccess'))
    mergeDetail(a.id, r.data, { acknowledged: true, acknowledged_note: note, acknowledged_at: nowIso() })
    refreshAll()
    announceAlertsChanged()   // İzleme menüsü rozetleri (sahiplenilmemiş sayısı) hemen tazelensin
    return { ok: true }
  }

  function resolve(a) {
    if (!a || actBlocked(a)) return
    setNoteDialog({ action: 'resolve', items: [noteContext(a)], submit: (note) => submitResolve(a, note) })
  }
  async function submitResolve(a, note) {
    let r
    try { r = await api.admin.resolveAlert(a.id, note) } catch { r = null }
    if (r == null || r.success === false) { refreshAll(); return { ok: false, error: r?.error || t('alh.resolveError') } }
    toast.success(t('alh.resolveSuccess'))
    mergeDetail(a.id, r.data, { resolved: true, resolved_note: note, resolved_at: nowIso() })
    refreshAll()
    announceAlertsChanged()   // İzleme menüsü rozetleri (açık sayı) hemen tazelensin
    return { ok: true }
  }

  // Tekrar Bildir: önce alıcı önizlemesi → onay penceresi; gönderim sendReNotify ile.
  async function reNotify(a) {
    setNotifying(a.id)
    let res
    try { res = await api.admin.previewReNotify(a.id) } catch { res = null } finally { setNotifying(null) }
    if (res?.success) {
      setRenotifyModal({ alertId: a.id, domain: a.domain || '', recipients: res.data?.recipients || [], webhook: res.data?.webhook || null })
    } else {
      toast.error(res?.error || t('alh.renotifyModal.previewError'))
    }
  }

  async function sendReNotify(excludeEmails, excludeUsernames = []) {
    if (!renotifyModal) return
    setRenotifySending(true)
    try {
      const body = {}
      if (excludeEmails.length) body.excludeEmails = excludeEmails
      if (excludeUsernames.length) body.excludeUsernames = excludeUsernames
      const res = await api.admin.reNotifyAlert(renotifyModal.alertId, Object.keys(body).length ? body : undefined)
      if (res?.success) {
        toast.success(t('alh.notifyQueued', res.data?.recipients_queued ?? res.data?.contacts_queued ?? 0))
        setRenotifyModal(null)
        load()
      } else {
        toast.error(res?.error || t('alh.loadError'))
      }
    } finally {
      setRenotifySending(false)
    }
  }

  // ── Toplu seçim + toplu işlem (yalnız açık görünüm; sunucu /admin/alerts/bulk) ──
  // 7/24 operatörü başka takımın uyarısını GÖRÜR ama sahiplenemez/çözemez (sunucu 403); alerts.actions izni olmayan
  // (AUDIT) hiçbir uyarıda bu eylemleri yapamaz — bkz. actBlockReason. Dönüş: neden anahtarı ya da null.
  const actBlocked = (a) => actBlockReason(a, { canAct, nocCanWrite, globalViewer, myTeamIds })
  const toggleSelect = (id) => setSelected((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n })
  const selectable = alerts.filter((a) => !actBlocked(a))
  const allSelected = selectable.length > 0 && selectable.every((a) => selected.has(a.id))
  const toggleSelectAll = () => setSelected(() => (allSelected ? new Set() : new Set(selectable.map((a) => a.id))))

  async function bulkAction(action) {
    const ids = [...selected]
    if (ids.length === 0) return
    // Sahiplen/çöz TOPLU yolda da gerekçe ister — yalnız teklide istenseydi zorunluluk delinirdi. Aynı gerekçeli
    // pencere: seçilenlerin özeti (sayı, önem dağılımı, ilk birkaçı) + tek not hepsine; hata pencerede kalır.
    if (action === 'acknowledge' || action === 'resolve') {
      const byId = new Map(alerts.map((a) => [a.id, a]))
      const items = ids.map((id) => byId.get(id)).filter(Boolean).map(noteContext)
      if (items.length === 0) return
      // Gönderilen = pencerenin GÖSTERDİĞİ (listede olmayan seçim sessizce isteğe girmesin)
      const shownIds = items.map((i) => i.id)
      setNoteDialog({ action: action === 'resolve' ? 'resolve' : 'ack', items, submit: (note) => runBulk(action, shownIds, note) })
      return
    }
    const ok = await showConfirm({ title: t('alh.bulk.renotifyTitle'), message: t('alh.bulk.renotifyMsg', ids.length), variant: 'warning',
      confirmText: t('alh.renotify'), cancelText: t('alh.resolveDialog.cancel') })
    if (!ok) return
    await runBulk(action, ids, null)
  }

  /** Toplu uç — `{ ok, error }` döner (gerekçeli pencere hatayı satır içi gösterir; tekrar bildirde pencere yok → tost). */
  async function runBulk(action, ids, note) {
    setBulkBusy(true)
    let res
    try { res = await api.admin.bulkAlertAction(action, ids, note) } catch { res = null } finally { setBulkBusy(false) }
    if (res?.success) {
      const { processed = 0, skipped = 0, failed = 0 } = res.data ?? {}
      let msg = t('alh.bulk.done', processed)
      if (skipped) msg += ' · ' + t('alh.bulk.skipped', skipped)
      if (failed) msg += ' · ' + t('alh.bulk.failed', failed)
      if (failed) toast.error(msg); else toast.success(msg)
      setSelected(new Set())
      refreshAll()
      announceAlertsChanged()
      return { ok: true }
    }
    const error = res?.error || t('alh.resolveError')
    if (note == null) toast.error(error)
    return { ok: false, error }
  }


  async function copyLink(a) {
    const link = alertLink(a)
    if (await copyText(link)) toast.success(t('share.copied'))
    else toast.error(link)
  }

  /** Aynı imzanın (alan adı + tip) GEÇMİŞİ: kapalı görünüme geçer ve listeyi o imzaya süzer (2026-09-16). */
  const showSignatureHistory = useCallback((a) => {
    if (!a) return
    setDetail(null)
    setTab('closed')
    setFilters({ ...FILTER_DEFAULTS, type: a.alert_type || '', q: a.domain || '' })
    resetPage()
    try { window.scrollTo({ top: 0, behavior: 'smooth' }) } catch { /* jsdom */ }
  }, [resetPage])

  /** Satır/kart menüsü — adı satırı ayırır (KebabMenu rowLabel). Kartta görünür olan eylemler menüde tekrarlanmaz. */
  const menuItems = (a, { card = false } = {}) => [
    { label: t('alh.openDetail'), icon: <Eye aria-hidden="true" />, onClick: () => openDetail(a) },
    { label: alertSourceTab(a.alert_type) ? t('alh.openMonitor') : t('alh.openCerts'), icon: <ExternalLink aria-hidden="true" />,
      onClick: () => window.location.assign(alertHref(a)), hidden: card },
    { label: t('alh.ack'), icon: <UserCheck aria-hidden="true" />, onClick: () => ack(a), hidden: card || a.resolved || a.acknowledged || actBlocked(a) },
    { label: t('alh.resolveAction'), icon: <CheckCircle2 aria-hidden="true" />, onClick: () => resolve(a), hidden: card || a.resolved || actBlocked(a) },
    { label: t('share.copyLink'), icon: <Link2 aria-hidden="true" />, onClick: () => copyLink(a) },
    { label: t('alh.sig.showHistory'), icon: <History aria-hidden="true" />, onClick: () => showSignatureHistory(a), hidden: !(Number(a.history_count) > 1) },
    { label: t('nocCall.logCall'), icon: <PhoneCall aria-hidden="true" />, onClick: () => logCall(a), hidden: card || !nocCanWrite },
  ]

  // ── Üst istatistikler (süzgeç kartları) ──
  const onStatClick = (key) => {
    if (key === 'stale') return   // SAYAÇ: sunucuda karşılığı yok; sahte istemci süzmesi sayfalamayla yanıltırdı
    setTab('open')
    if (key === 'open') { patch({ level: '', ack: '' }); return }
    if (key === 'unacked' || key === 'acked') {
      const v = key === 'unacked' ? 'unack' : 'ack'
      patch({ ack: tab === 'open' && filters.ack === v ? '' : v, level: '' })
      return
    }
    patch({ level: tab === 'open' && filters.level === key ? '' : key, ack: '' })
  }
  // Kapalı görünümde varsayılan tarih alanı KAPANIŞ (range '') — "son 24 saatte çözülen"; dönem belirteci istek anında hesaplanır.
  const showResolved24 = () => { setTab('closed'); patch({ from: '24h', to: '', range: '', level: '', ack: '' }) }
  const activeTile = tab !== 'open' ? null
    : (filters.level || (filters.ack === 'unack' ? 'unacked' : filters.ack === 'ack' ? 'acked' : null))
  const tiles = useMemo(() => {
    if (!summary) return []
    const lv = summary.levels || {}
    const h = summary.staleHours
    return [
      { key: 'open', Icon: Siren, cls: 'error', label: t('alh.tile.open'), value: summary.open, hint: t('alh.tile.openHint') },
      { key: 'CRITICAL', Icon: OctagonAlert, cls: 'critical', label: t('alh.statCritical'), value: lv.CRITICAL ?? 0, hint: t('alh.tile.levelHint', t('alh.statCritical')) },
      { key: 'HIGH', Icon: TriangleAlert, cls: 'high', label: t('alh.statHigh'), value: lv.HIGH ?? 0, hint: t('alh.tile.levelHint', t('alh.statHigh')) },
      { key: 'WARNING', Icon: CircleAlert, cls: 'warning', label: t('alh.statWarning'), value: lv.WARNING ?? 0, hint: t('alh.tile.levelHint', t('alh.statWarning')) },
      { key: 'unacked', Icon: BellRing, cls: 'alert', label: t('alh.statUnacked'), value: summary.unacked, hint: t('mondash.unackedHint') },
      { key: 'acked', Icon: UserCheck, cls: 'total', label: t('alh.ackOnly'), value: Math.max(0, summary.open - summary.unacked), hint: t('alh.tile.ackedHint') },
      { key: 'stale', Icon: Clock, cls: 'high', label: t('alh.statStale', h), value: summary.stale, hint: t('alh.statStaleHint', h) },
      ...(summary.resolved24 != null ? [{ key: 'resolved24', Icon: CheckCircle2, cls: 'valid', label: t('alh.tile.resolved24'),
        value: summary.resolved24, hint: t('alh.tile.resolved24Hint'), tip: t('alh.tile.resolved24'), onClick: showResolved24 }] : []),
    ]
  }, [summary, t]) // eslint-disable-line react-hooks/exhaustive-deps

  const active = activeAlertFilters(filters, tab)
  const filtered = !embedded && active.length > 0
  // Sıralama (2026-10-01): sütun başlığı / araç çubuğu seçicisi → `filters.sort` (URL); gün bölümleri sıralama alanına göre.
  const sort = useMemo(() => sortParts(filters.sort, tab), [filters.sort, tab])
  const onSort = useCallback((key) => patch({ sort: toggleSort(filters.sort, key, tab) }), [patch, filters.sort, tab])
  const pickTeam = useCallback((id) => { if (id != null) patch({ team: String(id) }) }, [patch])
  const groups = useMemo(() => groupByDay(alerts, tab, new Date(nowMs), sort.key), [alerts, tab, nowMs, sort.key])
  // Sütun başlığı süzgeçleri — araç çubuğuyla AYNI durum (çipler + URL tek kaynak); yalnız bağımsız sayfada.
  const columnFilters = useMemo(() => {
    if (!urlSync) return null
    const counts = facets.typeCounts || {}
    const known = ALERT_TYPES.filter((type) => (counts[type] ?? 0) > 0 || filters.type === type)
    const unknown = Object.keys(counts).filter((type) => !ALERT_TYPES.includes(type) && counts[type] > 0)
    return {
      level: { name: 'level', value: filters.level, onChange: (v) => patch({ level: v }),
        options: [{ value: '', label: t('alh.allLevels') }, ...LEVELS.map((l) => ({ value: l, label: t(`alh.level.${l.toLowerCase()}`) }))] },
      type: { name: 'type', value: filters.type, onChange: (v) => patch({ type: v }),
        options: [{ value: '', label: t('alh.typeAll') }, ...[...known, ...unknown].map((type) => ({ value: type, label: alertTypeLabel(t, type), count: counts[type] ?? 0 }))] },
      team: { name: 'team', value: filters.team, onChange: (v) => patch({ team: v }),
        options: [{ value: '', label: t('alh.allTeams') }, ...teams.map((tm) => ({ value: String(tm.id), label: tm.name }))] },
      ack: { name: 'ack', value: filters.ack, onChange: (v) => patch({ ack: v }),
        options: [{ value: '', label: t('alh.allAckStates') }, { value: 'unack', label: t('alh.unackedOnly') }, { value: 'ack', label: t('alh.ackOnly') }] },
    }
  }, [urlSync, facets.typeCounts, filters.level, filters.type, filters.team, filters.ack, teams, t, patch])
  const initial = loading && !loaded && !error
  const updatedText = updatedAt ? updatedAt.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' }) : null

  /** ↑/↓ listedeki alarmlar arasında gezer (kart başlık düğmesi / tablo satırı = `data-alert-open`). */
  const onListKey = (e) => {
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return
    if (!e.target?.matches?.('[data-alert-open]')) return
    const all = [...e.currentTarget.querySelectorAll('[data-alert-open]')]
    const next = all[all.indexOf(e.target) + (e.key === 'ArrowDown' ? 1 : -1)]
    if (next) { e.preventDefault(); next.focus() }
  }

  // Hızlı dönemde boş sonuç (2026-10-01): "Bu dönemde alarm yok" + tek tıkla bir üst dönem (1 sa → 24 sa → 7 g → 30 g → tümü).
  const wider = !embedded && isPreset(filters.from) ? widerPreset(filters.from) : null
  const emptyState = wider != null ? (
    <StatusBlock tone="neutral" icon={CalendarSearch} title={t('alh.emptyPeriod')} description={t('alh.emptyPeriodText', presetLabel(t, filters.from))} className="py-12"
      actions={(
        <>
          <Button type="button" data-slot="widen-period" onClick={() => patch({ from: wider, to: '' })}>
            <CalendarRange aria-hidden="true" />{wider ? t('alh.widenPeriod', presetLabel(t, wider)) : t('alh.widenPeriodAll')}
          </Button>
          {active.length > 1 && <Button type="button" variant="outline" onClick={resetFilters}><FilterX aria-hidden="true" />{t('alh.clearFilters')}</Button>}
        </>
      )} />
  ) : filtered ? (
    <StatusBlock tone="neutral" icon={FilterX} title={t('alh.emptyFiltered')} description={t('alh.emptyFilteredText')} className="py-12"
      actions={<Button type="button" variant="outline" onClick={resetFilters}><FilterX aria-hidden="true" />{t('alh.clearFilters')}</Button>} />
  ) : tab === 'open' ? (
    <StatusBlock tone="success" icon={CheckCircle2} title={t('alh.noOpen')} description={t('alh.noOpenText')} className="py-12" />
  ) : (
    <StatusBlock tone="neutral" icon={Inbox} title={tab === 'closed' ? t('alh.noClosed') : t('alh.noAlerts')} className="py-12" />
  )

  const selectionBar = tab === 'open' && selectable.length > 0 && (
    <div data-slot="alert-selection" className="flex flex-wrap items-center justify-between gap-2 rounded-lg border bg-muted/40 px-3 py-2">
      <div className="flex items-center gap-2 text-sm font-semibold">
        <Checkbox id={bulkAllId} checked={allSelected ? true : selected.size > 0 ? 'indeterminate' : false} onCheckedChange={toggleSelectAll} />
        <Label htmlFor={bulkAllId} className="cursor-pointer py-2">
          {selected.size > 0 ? t('alh.bulk.selected', selected.size) : t('alh.bulk.selectAll')}
        </Label>
      </div>
      {selected.size > 0 && (
        <div className="flex flex-wrap items-center gap-2" data-testid="alh-bulk-actions">
          <Button type="button" variant="outline" size="sm" className="h-9" disabled={bulkBusy} onClick={() => bulkAction('acknowledge')}>
            <UserCheck aria-hidden="true" />{t('alh.ack')}
          </Button>
          <Button type="button" variant="outline" size="sm" className="h-9" disabled={bulkBusy} onClick={() => bulkAction('re-notify')}>
            <BellRing aria-hidden="true" />{t('alh.renotify')}
          </Button>
          <Button type="button" variant="success" size="sm" className="h-9" disabled={bulkBusy} onClick={() => bulkAction('resolve')}>
            <CheckCircle2 aria-hidden="true" />{t('alh.resolveAction')}
          </Button>
          <Button type="button" variant="ghost" size="sm" className="h-9" disabled={bulkBusy} onClick={() => setSelected(new Set())}>
            {t('alh.bulk.clear')}
          </Button>
        </div>
      )}
    </div>
  )

  const listBody = (
    <div className="flex min-w-0 flex-col gap-3">
      {error && (
        <AlertBanner tone="danger" role="alert" title={t('alh.loadError')} className="mb-0"
          actions={<Button type="button" variant="outline" size="sm" onClick={load}><RotateCcw aria-hidden="true" />{t('alh.retry')}</Button>}>
          {error}
        </AlertBanner>
      )}
      {selectionBar}
      {initial ? <ListSkeleton variant={tab === 'open' ? 'cards' : 'rows'} /> : alerts.length === 0 ? (!error && emptyState) : (
        <div data-slot="alert-results" aria-busy={loading || undefined} onKeyDown={onListKey}
          className={cn('min-w-0 transition-opacity', loading && 'opacity-60')}>
          {tab === 'open' ? (
            <OpenAlertList groups={groups} renderCard={(a) => (
              <OpenAlertCard alert={a} nowMs={nowMs} staleHours={facets.staleHours} teamName={teamNameOf(a.team_id)}
                push={facets.push[String(a.id)]} selected={selected.has(a.id)} onToggleSelect={toggleSelect}
                highlighted={detail != null && String(detail.id) === String(a.id)} linked={String(a.id) === String(linkedAlertId)}
                onOpen={openDetail} onAck={ack} onResolve={resolve} onReNotify={reNotify} notifying={notifying === a.id}
                onLogCall={nocCanWrite ? logCall : undefined} actBlocked={actBlocked(a)} onPickTeam={urlSync ? pickTeam : undefined}
                menuItems={(x) => menuItems(x, { card: true })} />
            )} />
          ) : (
            <AlertRowsList groups={groups} tab={tab} nowMs={nowMs} onOpen={openDetail} activeId={detail?.id} linkedId={linkedAlertId}
              menuItems={(x) => menuItems(x)} phone={phone} sort={sort} onSort={onSort} columnFilters={columnFilters}
              onPickTeam={urlSync ? pickTeam : undefined} teamNameOf={teamNameOf} />
          )}
        </div>
      )}
      {/* Standart çubuk; yüklenirken de yerinde kalır (sayfa değişiminde zıplamasın) */}
      <PaginationBar {...sp.bar} />
    </div>
  )

  const detailProps = detail ? {
    nowMs, teamName: teamNameOf(detail.team_id), push: facets.push[String(detail.id)],
    ...(actBlocked(detail) ? { actBlocked: actBlocked(detail) } : { onAck: ack, onResolve: resolve, onReNotify: reNotify }),
    notifying: notifying === detail.id, onShowHistory: showSignatureHistory,
    nocCanWrite, nocFocusKey: nocFocus && String(nocFocus.id) === String(detail.id) ? nocFocus.n : 0, onNocChanged,
  } : null

  return (
    <div data-slot="alert-history" className={cn('flex min-w-0 flex-col', embedded ? 'gap-3' : 'gap-4')}>
      {urlSync && (
        <PageHeader icon={History} title={t('alh.title')} description={t('alh.pageDesc')} className="mb-0"
          meta={summary ? (
            <span data-slot="alert-history-live" className="flex flex-wrap items-center gap-1.5">
              <Badge variant="outline" className="gap-1.5 border-destructive/30 bg-destructive/10 font-semibold text-destructive tabular-nums">
                <span aria-hidden="true" className="size-1.5 rounded-full bg-destructive" />{t('alh.metaOpen', summary.open)}
              </Badge>
              <Badge variant="outline" className="font-medium tabular-nums">{t('alh.metaUnacked', summary.unacked)}</Badge>
              {summary.resolved24 != null && (
                <Badge variant="outline" className="border-success/30 bg-success/10 font-medium text-success tabular-nums">{t('alh.metaResolved24', summary.resolved24)}</Badge>
              )}
              {updatedText && <span aria-live="polite" className="ml-1">{t('alh.metaUpdated', updatedText)}</span>}
            </span>
          ) : summaryLoading ? <Skeleton className="h-5 w-64" aria-hidden="true" /> : null}
          actions={(
            <>
              {/* Kayıtlı görünümler (2026-10-02, öneri 23) — tercihler hazır değilse çizilmez */}
              <SavedViewsMenu listKey="alerthistory" tab="alerthistory" {...VIEW_SPECS.alerthistory} size="default" />
              <Button type="button" variant="outline" onClick={refreshAll} aria-busy={loading || undefined}>
                <RefreshCcw aria-hidden="true" className={cn(loading && 'motion-safe:animate-spin')} />{t('alh.refresh')}
              </Button>
              {/* CSV: EKRANDAKİ süzgeçlerin aynısıyla — ayrı yüzey "ekranda 12, dosyada 800" sürprizi üretirdi */}
              <Button asChild variant="outline" className="text-foreground no-underline">
                <a href={api.admin.getAlertsCsvUrl(csvParams({ tab, filters, domain, typesParam }))} title={t('alh.csvTip')} download>
                  <Download aria-hidden="true" />{t('alh.exportCsv')}
                </a>
              </Button>
            </>
          )} />
      )}

      {/* İstatistikler EN ÜSTTE (2026-09-27 kullanıcı isteği) — kapsamdaki açık küme; kartlar süzgeç */}
      {urlSync && (tiles.length > 0
        ? <MonitorStatsBar items={tiles} activeFilter={activeTile} onStatClick={onStatClick} />
        : summaryLoading && <Skeleton className="h-28 w-full rounded-[10px]" aria-hidden="true" />)}

      {urlSync && (
        <div className="flex min-w-0 flex-col gap-2">
          <AlertTeamStatsPanel activeTeamId={filters.team}
            onPickTeam={(id) => patch({ team: String(id) })}
            onOpenAlert={(a) => { setTab(a.resolved ? 'closed' : 'open'); setFilters({ ...FILTER_DEFAULTS, q: a.domain || '' }); setDetail(a) }} />
          <AlertNoisePanel onPickDomain={(d) => patch({ q: d })}
            onPickDay={(day) => { setTab('all'); patch({ from: day, to: day, range: '' }) }}
            onOpenAlert={(a) => { setTab(a.resolved ? 'closed' : 'open'); setFilters({ ...FILTER_DEFAULTS, q: a.domain || '' }); setDetail(a) }} />
        </div>
      )}

      <Tabs value={tab} onValueChange={(v) => { if (TABS.includes(v)) setTab(v) }} className="min-w-0 gap-3">
        <TabsList aria-label={t('alh.tabs')} className="w-full group-data-[orientation=horizontal]/tabs:h-10 sm:w-fit">
          <TabsTrigger value="open" className="gap-1.5 px-3">
            <Siren aria-hidden="true" />{t('alh.tab.open')}
            {summary && <Badge variant="secondary" data-slot="tab-count" className="h-5 rounded-full px-1.5 tabular-nums">{summary.open}</Badge>}
          </TabsTrigger>
          <TabsTrigger value="closed" className="gap-1.5 px-3"><CheckCircle2 aria-hidden="true" />{t('alh.tab.closed')}</TabsTrigger>
          <TabsTrigger value="all" className="gap-1.5 px-3"><History aria-hidden="true" />{t('alh.tab.all')}</TabsTrigger>
        </TabsList>

        {urlSync && (
          <AlertToolbar tab={tab} filters={filters} patch={patch} reset={resetFilters} typeCounts={facets.typeCounts} teams={teams} phone={phone} />
        )}

        {TABS.map((v) => (
          <TabsContent key={v} value={v} className="min-w-0">{v === tab && listBody}</TabsContent>
        ))}
      </Tabs>

      {detail && (embedded
        ? <AlertDetailModal key={detail.id} alert={detail} onClose={closeDetail} {...detailProps} />
        : <AlertDetailSheet key={detail.id} alert={detail} onClose={closeDetail} {...detailProps} />)}

      {quickCall && (
        <NocCallQuickSheet key={quickCall.id} alert={quickCall} onClose={() => setQuickCall(null)} onChanged={onNocChanged}
          onOpenDetail={(a) => { setQuickCall(null); openDetail(a) }} />
      )}

      {renotifyModal && (
        <ReNotifyConfirmModal domain={renotifyModal.domain} recipients={renotifyModal.recipients} webhook={renotifyModal.webhook}
          sending={renotifySending} onSend={sendReNotify} onClose={() => { if (!renotifySending) setRenotifyModal(null) }} />
      )}

      {noteDialog && (
        <ActionNoteDialog key={`${noteDialog.action}:${noteDialog.items.map((c) => c.id).join(',')}`}
          action={noteDialog.action} subject="alert" items={noteDialog.items}
          onSubmit={noteDialog.submit} onClose={() => setNoteDialog(null)} />
      )}
    </div>
  )
}

