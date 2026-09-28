import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AlertOctagon, BarChart3, Info, RefreshCw, Search } from 'lucide-react'
import { api, formatDateSec } from '../../api/client'
import { useLanguage, useT } from '../../i18n/index.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import { useServerPagination } from '../../hooks/useServerPagination.js'
import { useIsMobile } from '../../hooks/use-mobile.js'
import { useDelayedFlag } from '../../hooks/useDelayedFlag.js'
import { useUrlQuerySync } from '../../hooks/useUrlQuerySync.js'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import CollapsibleSection from '../ui/CollapsibleSection.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { useToast } from '../ui/Toast.jsx'
import ChangeKindCards from './ChangeKindCards.jsx'
import { toApiTime, startOfLastNDays } from '../../utils/apiTime.js'
import { downloadCsv, stampedName } from '../../utils/csvExport.js'
import { localDayKey } from '../../utils/localDay.js'
import ChangeKpis from './monitorchanges/ChangeKpis.jsx'
import ChangeTimeline, { ChangeTimelineSkeleton } from './monitorchanges/ChangeTimeline.jsx'
import { FilterBar, PeriodBar } from './monitorchanges/ChangeToolbar.jsx'
import { labelOf } from './monitorchanges/ChangeFilters.jsx'
import ChangeDetailSheet from './monitorchanges/ChangeDetailSheet.jsx'
import {
  EVENTS, KINDS, URL_KEYS, changesCsv, countChanges, detailKey, eventLabel, kindKey, parseRes, readUrlState, resKey, rowId,
  shortDay, urlMapping, windowFor,
} from './monitorchanges/changeModel.js'
import { Button } from '@/components/shadcn/button'

/**
 * İzleme Değişiklikleri (Loglar → İzleme Değişiklikleri) — TÜM izlemelerdeki yapılandırma değişikliklerinin denetim
 * izi: kim, neyi, ne zaman, hangi IP'den değiştirdi ve alan düzeyinde önce → sonra.
 *
 * <p>Yeniden tasarım (2026-09-28, shadcn + mobil web, "zenginleştir"): üstte DÖNEM çubuğu (hazır pencere / özel aralık
 * + CSV + Yenile), dönemin ÖZET kartları (toplam + günlük eğri, işlem dağılımı, en çok değişen izlemeler, en aktif
 * kişiler — hepsi tıklanınca süzgeç; `/changes/summary`), katlanır tür kırılımı, liste süzgeçleri (arama + tür / olay /
 * kişi / takım; telefonda alt Sheet) ve GÜNE GÖRE gruplanmış zaman çizelgesi (yapışkan gün başlıkları, satır içi açılan
 * alan farkı). Satır ayrıntısı yan panelde (telefonda tam ekran): tam fark, o anki ayarlar, ham JSON, bağlantı.
 *
 * <p>Veri yükleme: ilk açılışta iskelet; süzgeç değişince ÖNCEKİ sonuç ekranda kalır (180 ms'den uzun sürerse soluk —
 * `useDelayedFlag`, titreme yok). Yarış: liste ve özet ayrı tur sayaçlarıyla (`listSeq` / `sumSeq`) — yalnız son
 * isteğin yanıtı yazılır, yükleme bayrağı yalnız son tur tarafından (o tur her zaman gelir) düşürülür.
 *
 * <p>URL: süzgeçler, sayfa ve açık ayrıntı `ch_*` anahtarlarında (`useUrlQuerySync`, PAGE_STATE_PREFIXES — sekme
 * değişince temizlenir); uygulamanın `tab / domain / monitor / incident` anahtarlarına dokunulmaz. Eski
 * `?tab=monitorchanges` bağlantısı aynen çalışır.
 *
 * <p>Kapsam SUNUCUDA: global admin/AUDIT her şeyi görür, diğerleri {@code viewTeamIds} kesişimini (takımsız satırlar
 * ekip üyesinin değişikliğiyse görünür — TeamActorScope). `globalViewer` yalnız SUNUM içindir (kapsam notu).
 */

/** CSV'ye yazılacak en fazla satır — 200'lük sayfalarla en çok 25 istek. */
const EXPORT_CAP = 5000
const EXPORT_PAGE = 200

/**
 * @param {boolean} globalViewer  Backend'deki SessionScope.isGlobalViewer karşılığı — global admin ya da AUDIT.
 *   Kapsamlı müdür-admin buraya girmez.
 */
export default function MonitorChangesConsole({ globalViewer = false }) {
  const t = useT()
  const { lang } = useLanguage()
  const toast = useToast()
  const toastRef = useRef(toast)          // memo'suz context değeri — bağımlılığa girmesin (async-guard T2)
  toastRef.current = toast
  const isMobile = useIsMobile()

  // ── Süzgeç durumu (açılışta URL'den) ─────────────────────────────────────────────────────────────
  const [init] = useState(() => readUrlState())
  const [kind, setKind] = useState(init.kind)
  const [eventType, setEventType] = useState(init.eventType)
  const [actor, setActor] = useState(init.actor)
  const [teamId, setTeamId] = useState(init.teamId)
  const [res, setRes] = useState(init.res)
  const [resName, setResName] = useState('')
  const [q, setQ] = useState(init.q)
  const [qTerm, setQTerm] = useState(init.q)   // 300 ms debounce — her tuşta sunucu araması yok
  useEffect(() => { const id = setTimeout(() => setQTerm(q), 300); return () => clearTimeout(id) }, [q])
  // Seçili aralık DÜĞMESİ ayrı tutulur: from/to'dan geri çıkarmak ("30 gün mü, özel mi") tahmin işi olurdu.
  const [rangeKey, setRangeKey] = useState(init.rangeKey)
  const [from, setFrom] = useState(init.from)
  const [to, setTo] = useState(init.to)
  const [teams, setTeams] = useState([])
  const [kindsOpen, setKindsOpen] = useState(false)
  const [detail, setDetail] = useState(null)
  const [exporting, setExporting] = useState(false)

  const resObj = parseRes(res)

  // Sayfalama standardı: kanca süzgecin DEĞERİ değişince aynı render'da sayfa 1'e döner (tek istek). URL'den gelen
  // sayfa (ch_page) açılışta korunur. API 0-tabanlı.
  const sp = useServerPagination({ listKey: 'monitor-changes', preset: 'page',
    resetDeps: [kind, eventType, actor, qTerm, from, to, teamId, rangeKey, res], apiBase: 0,
    url: { pageKey: URL_KEYS.page, sizeKey: URL_KEYS.size } })
  const { apiPage: page, pageSize: size, setTotal } = sp

  // ── Liste ────────────────────────────────────────────────────────────────────────────────────────
  const [rows, setRows] = useState(null)      // null = ilk yükleme (iskelet)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const listSeq = useRef(0)

  /** İstek gövdesi — izleme süzgecinde tür izlemenin türüdür (kimlikler tür başına ayrı tablodan). */
  const listParams = useMemo(() => ({
    page, size, kind: resObj ? resObj.kind : kind, eventType, actor, q: qTerm, from, to,
    ...(teamId ? { teamId } : {}),
    ...(resObj ? { resourceId: resObj.id } : {}),
    counts: false,   // sayaçlar özet ucundan — sayfa çevirmek toplama sorgularını yeniden koşturmasın
  }), [page, size, kind, eventType, actor, qTerm, from, to, teamId, res])  // eslint-disable-line react-hooks/exhaustive-deps

  const load = useCallback(async () => {
    const seq = ++listSeq.current
    setLoading(true)
    try {
      const resp = await api.monitoring.getRecentChanges(listParams)
      if (seq !== listSeq.current) return   // bayat yanıt — daha yeni bir istek yolda
      if (resp?.success) {
        setRows(resp.data?.changes || [])
        setTotal(resp.data?.total || 0)
        setError(null)
      } else {
        setRows([])
        setError(resp?.error || t('chg.loadError'))
      }
    } catch (e) {
      if (seq !== listSeq.current) return
      setRows([])
      setError(e?.message || String(e))
    } finally {
      // Bayrağı yalnız SON tur düşürür; bayat tur düşürseydi yeni istek yolda iken gösterge sönerdi. Son tur her
      // koşulda buraya gelir (başarı / hata / fırlatma), bayrak sızmaz.
      if (seq === listSeq.current) setLoading(false)
    }
  }, [listParams, t, setTotal])
  useEffect(() => { load() }, [load])

  // ── Özet kartları (pencere + takım değişince; sayfa / tür / olay / kişi / arama DEĞİL) ────────────────
  const tz = useMemo(() => { try { return Intl.DateTimeFormat().resolvedOptions().timeZone || '' } catch { return '' } }, [])
  const [summary, setSummary] = useState(null)
  const [sumLoading, setSumLoading] = useState(true)
  const [sumError, setSumError] = useState(null)
  const sumSeq = useRef(0)
  const loadSummary = useCallback(async () => {
    const seq = ++sumSeq.current
    setSumLoading(true)
    try {
      const resp = await api.monitoring.getChangeSummary({ from, to, tz, ...(teamId ? { teamId } : {}) })
      if (seq !== sumSeq.current) return
      if (resp?.success) { setSummary(resp.data || {}); setSumError(null) }
      else setSumError(resp?.error || t('chg.kpiError'))
    } catch (e) {
      if (seq !== sumSeq.current) return
      setSumError(e?.message || String(e))
    } finally {
      if (seq === sumSeq.current) setSumLoading(false)
    }
  }, [from, to, teamId, tz, t])
  useEffect(() => { loadSummary() }, [loadSummary])

  // Takım listesi uçtan GÖRÜŞ KAPSAMINA göre süzülü gelir (AdminController.listTeams): kapsam tek yerde, sunucuda.
  // Patlarsa sessiz geçilir: takım seçici çıkmaz ama liste çalışmaya devam eder (süzgeç bir kolaylık).
  useEffect(() => {
    api.admin.getTeams()
      .then(r => setTeams(Array.isArray(r?.data) ? r.data : []))
      .catch(() => {})
  }, [])

  // Derin bağlantı (`ch_id=tür:id:sıra`): ayrıntı ucundan satırı getir, paneli aç. Yetkisizse / yoksa sessiz (panel
  // açılmaz; anahtar ilk URL yazımında düşer).
  useEffect(() => {
    if (!init.openId) return undefined
    let alive = true
    const [k, id, seq] = init.openId.split(':')
    Promise.resolve()
      .then(() => api.monitoring.getChangeDetail(k, Number(id), Number(seq)))
      .then(r => { if (alive && r?.success && r.data) setDetail(r.data) })
      .catch(() => {})
    return () => { alive = false }
  }, [init.openId])

  // ── Durum → URL (sayfa ve boyut useServerPagination'da) ────────────────────────────────────────────
  useUrlQuerySync(urlMapping({ q, kind, eventType, actor, teamId, res, rangeKey, from, to, openId: detailKey(detail) }))

  const listStale = useDelayedFlag(loading && rows !== null)
  const sumStale = useDelayedFlag(sumLoading && summary !== null)

  // ── Seçenekler ───────────────────────────────────────────────────────────────────────────────────
  /**
   * Takım seçici ancak BİRDEN FAZLA takım görünüyorsa çizilir ("Tüm takımlar" + en az iki takım). Bu koşul YALNIZ
   * seçiciyi kapatır; satırdaki takım adı gibi İÇERİK buna bağlanmaz (takım listesi yardımcı bir istektir).
   */
  const teamOptions = useMemo(() => [
    { value: '', label: t('chg.allTeams') },
    ...teams.map(tm => ({ value: String(tm.id), label: tm.name })),
  ], [teams, t])
  const multiTeam = teamOptions.length > 2

  /** Kişi seçenekleri PENCEREDEKİ tüm kişilerden (özet ucu, sayıya göre) — eskiden yalnız görünen sayfadan türüyordu. */
  const actorOptions = useMemo(() => {
    const seen = new Map()
    ;(summary?.actors || []).forEach(a => { if (a.actor) seen.set(a.actor, a.actor_name || a.actor) })
    ;(rows || []).forEach(r => {
      if (r.actor && r.actor !== 'system' && !seen.has(r.actor)) seen.set(r.actor, r.actor_name || r.actor)
    })
    if (actor && !seen.has(actor)) seen.set(actor, actor)
    return [{ value: '', label: t('chg.allActors') }, ...[...seen.entries()].map(([v, label]) => ({ value: v, label }))]
  }, [summary, rows, actor, t])

  const kindOptions = useMemo(() => [
    { value: '', label: t('chg.allKinds') },
    ...KINDS.map(k => ({ value: k, label: t('chg.kind.' + k) })),
  ], [t])

  const eventOptions = useMemo(() => [
    { value: '', label: t('chg.allEventTypes') },
    ...EVENTS.map(e => ({ value: e, label: eventLabel(t, e) })),
  ], [t])

  /** Tür süzgeci izleme süzgeciyle çelişirse izleme süzgeci kalkar (izleme bir türe aittir). */
  const onKind = (k) => {
    setKind(k)
    if (resObj && k && resObj.kind !== k) setRes('')
  }
  const onResource = (item) => {
    if (!item) { setRes(''); return }
    setRes(resKey(item.kind, item.id))
    setResName(item.name || '')
    if (kind && kind !== kindKey(item.kind)) setKind('')
  }

  /** Süzgeç tanımı — araç çubuğu, telefon Sheet'i ve etkin süzgeç rozetleri AYNI listeyi çizer. */
  const filters = [
    { key: 'kind', label: t('chg.filterKind'), ariaLabel: t('flt.monitorType'), value: kind, options: kindOptions, onChange: onKind, searchThreshold: 6 },
    { key: 'event', label: t('chg.filterEvent'), ariaLabel: t('chg.eventFilter'), value: eventType, options: eventOptions, onChange: setEventType, searchThreshold: 99 },
    { key: 'actor', label: t('chg.filterActor'), ariaLabel: t('flt.actor'), value: actor, options: actorOptions, onChange: setActor, searchThreshold: 6 },
    ...(multiTeam ? [{ key: 'team', label: t('chg.filterTeam'), ariaLabel: t('chg.teamFilter'), value: teamId, options: teamOptions, onChange: setTeamId, searchThreshold: 2 }] : []),
  ]
  const sheetActive = [kind, eventType, actor, teamId].filter(Boolean).length

  // Özel aralık seçicisinin uçları KARARLI (2026-09-27 regresyon B1): `to` boşken her çizimde `new Date()` geçmek
  // seçicinin taslağını her yeniden çizimde sıfırlıyordu.
  const customOpen = rangeKey === 'custom'
  const pickerRange = useMemo(() => (customOpen ? {
    from: from ? new Date(from + 'Z') : new Date(Date.now() - 29 * 864e5),
    to: to ? new Date(to + 'Z') : new Date(),
  } : null), [customOpen, from, to])

  /** Zaman aralığı: pencere YEREL takvimden kurulur, UTC'ye çevrilip gönderilir (utils/apiTime.js). */
  function applyRange(key) {
    setRangeKey(key)
    if (key === 'custom') {
      // Seçici EKRANDA GÖRÜNEN pencereyi göstermeli: "Tümü"den geliniyorsa varsayılan (son 30 gün) hemen uygulanır —
      // aksi halde düğme "Özel" derken liste hâlâ tüm zamanı gösterir.
      if (!from) {
        setFrom(toApiTime(startOfLastNDays(30)))
        setTo(toApiTime(new Date()))
      }
      return
    }
    const w = windowFor(key)
    setFrom(w.from)
    setTo(w.to)
  }

  /** Özel aralık: seçici Date verir, uç ISO metin bekler. */
  function applyCustom(f, tDate) {
    setRangeKey('custom')
    setFrom(f ? toApiTime(f) : '')
    setTo(tDate ? toApiTime(tDate) : '')
  }

  function clearSheetFilters() {
    setKind(''); setEventType(''); setActor(''); setTeamId('')
  }

  /** HEPSİ: süzgeçler + izleme + arama (debounce beklemeden) + zaman aralığı. */
  function clearFilters() {
    sp.reset()
    setRangeKey('all')
    setFrom(''); setTo(''); setQ(''); setQTerm(''); setRes('')
    clearSheetFilters()
  }

  // ── Etkin süzgeç rozetleri ─────────────────────────────────────────────────────────────────────────
  const resLabel = !resObj ? '' : (resName
    || (summary?.top_resources || []).find(i => resKey(i.kind, i.resource_id) === res)?.resource_name
    || (rows || []).find(r => resKey(r.kind, r.resource_id) === res)?.resource_name
    || `#${resObj.id}`)
  const rangeLabel = rangeKey === 'custom'
    ? `${from ? shortDay(localDayKey(from), lang) : '…'} – ${to ? shortDay(localDayKey(to), lang) : t('chg.rangeNow')}`
    : rangeKey === 'today' ? t('chg.rangeToday') : t('chg.rangeDays', rangeKey)
  const chips = [
    ...(q ? [{ key: 'q', label: t('chg.filterSearch'), value: q, onRemove: () => { setQ(''); setQTerm('') } }] : []),
    ...(resObj ? [{ key: 'res', label: t('chg.filterMonitor'), value: resLabel, onRemove: () => setRes('') }] : []),
    ...filters.filter(f => f.value).map(f => ({ key: f.key, label: f.label, value: labelOf(f), onRemove: () => f.onChange('') })),
    ...(rangeKey !== 'all' ? [{ key: 'range', label: t('chg.rangeFilter'), value: rangeLabel, onRemove: () => applyRange('all') }] : []),
  ]
  const anyFilter = chips.length > 0

  const status = rows === null || error ? null : (
    <span role="status" data-slot="chg-count" className="inline-flex items-center gap-1.5 tabular-nums">
      {listStale && <Spinner decorative size={12} />}
      {countChanges(t, sp.total ?? rows.length, (sp.total ?? rows.length).toLocaleString(lang === 'en' ? 'en-GB' : 'tr-TR'))}
    </span>
  )

  function refresh() {
    load()
    loadSummary()
  }

  /** Süzülmüş listenin TAMAMI (tavanlı) — 200'lük sayfalarla, sayaçsız; satır düzeyinde tam detay. */
  async function exportCsv() {
    setExporting(true)
    try {
      const all = []
      let total = Infinity
      for (let p = 0; all.length < total && all.length < EXPORT_CAP; p++) {
        const resp = await api.monitoring.getRecentChanges({ ...listParams, page: p, size: EXPORT_PAGE })
        if (!resp?.success) throw new Error(resp?.error || t('chg.loadError'))
        const batch = resp.data?.changes || []
        total = Number(resp.data?.total ?? batch.length)
        all.push(...batch)
        if (batch.length < EXPORT_PAGE) break
      }
      const out = all.slice(0, EXPORT_CAP)
      downloadCsv(stampedName('monitor-changes'), changesCsv(out, t, formatDateSec))
      if (total > EXPORT_CAP) toastRef.current.info(t('chg.exportCapped', EXPORT_CAP, total))
      else toastRef.current.success(t('chg.exportDone', out.length))
    } catch (e) {
      toastRef.current.error(`${t('chg.exportError')}: ${e?.message || e}`)
    } finally {
      setExporting(false)
    }
  }

  const now = Date.now()

  let body
  if (rows === null && !error) body = <ChangeTimelineSkeleton t={t} />
  else if (error && (!rows || rows.length === 0)) body = null   // hata bandı yeter; boş durum ikinci bir "sonuç yok" demesin
  else if (rows.length === 0) {
    body = anyFilter ? (
      <StatusBlock tone="neutral" icon={Search} title={t('chg.noMatchTitle')} description={t('chg.consoleEmptyText')}
        className="rounded-lg border border-dashed"
        actions={<Button type="button" variant="outline" className="h-10" onClick={clearFilters}>{t('chg.presetClear')}</Button>} />
    ) : (
      <StatusBlock tone="neutral" icon={Search} title={t('chg.emptyTitle')} description={t('chg.consoleEmptyNone')}
        className="rounded-lg border border-dashed" />
    )
  } else {
    body = (<>
      <ChangeTimeline rows={rows} t={t} now={now} lang={lang} onOpen={setDetail}
        selectedId={detail ? rowId(detail) : null} loading={loading} stale={listStale} />
      {/* Taban dönüşümü kancada: API 0-tabanlı (`apiPage`), çubuk 1-tabanlı (`sp.bar`). */}
      <PaginationBar {...sp.bar} />
    </>)
  }

  return (
    <div data-slot="chg-console" className="@container/chg flex min-w-0 flex-col gap-3">
      {/* Kapsam notu — yalnız takım kapsamlı kullanıcıya: liste kapsamla sınırlıdır ve takımsız kayıtlar burada
          görünmez. Yazılmasaydı kullanıcı eksik gördüğünü fark edemez, "demek hiç değişmemiş" diye okurdu. */}
      {!globalViewer && (
        <p className="m-0 flex items-start gap-1.5 text-xs text-muted-foreground">
          <Info aria-hidden="true" className="mt-px size-3.5 shrink-0" />{t('chg.scopeNote')}
        </p>
      )}

      <PeriodBar t={t} rangeKey={rangeKey} onRange={applyRange} pickerRange={pickerRange} onCustom={applyCustom}
        loading={loading || sumLoading} busy={listStale || sumStale} onRefresh={refresh}
        exporting={exporting} onExport={exportCsv} canExport={!!rows && rows.length > 0} />

      <ChangeKpis summary={summary} loading={sumLoading} error={sumError} stale={sumStale} onRetry={loadSummary}
        range={{ rangeKey, from, to }} eventType={eventType} onEvent={setEventType}
        res={res} onResource={onResource} actor={actor} onActor={setActor} t={t} lang={lang} now={now} />

      {/* Tür kırılımı — katlanır (varsayılan kapalı): kartlar tür süzgecidir, sayılar seçili dönemin TAMAMI. */}
      <CollapsibleSection open={kindsOpen} onOpenChange={setKindsOpen}
        icon={BarChart3} label={t('chg.byKindTitle')} hint={t('chg.byKindHint')}
        toggleLabel={t('chg.byKindTitle')}
        contentClassName="pt-1">
        <ChangeKindCards t={t} kindCounts={summary?.kind_counts || {}} selected={kind} onSelect={onKind} />
      </CollapsibleSection>

      <FilterBar t={t} isMobile={isMobile} q={q} onQ={(v) => { setQ(v); if (!v) setQTerm('') }}
        filters={filters} sheetActive={sheetActive} onClearSheet={clearSheetFilters}
        chips={chips} onClearAll={clearFilters} status={status} />

      {error && (
        <AlertBanner tone="danger" role="alert" icon={AlertOctagon} title={t('chg.loadError')} className="mb-0"
          actions={<Button type="button" variant="outline" size="sm" className="max-md:h-10" onClick={() => load()}>
            <RefreshCw aria-hidden="true" /> {t('chg.retry')}</Button>}>
          {error}
        </AlertBanner>
      )}

      {body}

      <ChangeDetailSheet row={detail} t={t} now={now} onClose={() => setDetail(null)}
        onFilterResource={(r) => onResource({ kind: kindKey(r.kind), id: r.resource_id, name: r.resource_name })} />
    </div>
  )
}
