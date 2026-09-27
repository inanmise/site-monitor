import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Search, BarChart3, Sigma, FilePlus2, Pencil, Trash2, RefreshCw, Info, AlertOctagon } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import { useServerPagination } from '../../hooks/useServerPagination.js'
import { useIsMobile } from '../../hooks/use-mobile.js'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import DateTimeRangePicker from '../ui/DateTimeRangePicker.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import CollapsibleSection from '../ui/CollapsibleSection.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import ChangeKindCards from './ChangeKindCards.jsx'
import MonitorStatsBar from '../MonitorStatsBar.jsx'
import { toApiTime, startOfLocalDay, startOfLastNDays } from '../../utils/apiTime.js'
import { eventLabel } from './monitorchanges/changeParts.jsx'
import { ChangeCards, ChangeListSkeleton, ChangeTable, rowId } from './monitorchanges/ChangeList.jsx'
import { ActiveFilterChips, FilterFields, FilterSheet } from './monitorchanges/ChangeFilters.jsx'
import ChangeDetailSheet from './monitorchanges/ChangeDetailSheet.jsx'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'

/**
 * İzleme Değişiklikleri (Loglar → İzleme Değişiklikleri) — TÜM izlemelerdeki yapılandırma değişiklikleri tek listede.
 *
 * <p>"Kim, nerede, neyi ekledi/değiştirdi, ne zaman" sorusunu tek noktadan cevaplar. İzlemenin kendi "Değişiklikler"
 * sekmesiyle AYNI veriyi ve AYNI sunum parçalarını kullanır (`ChangeDiffChips` → `ChangeChipList`, `changeFields`);
 * fark yalnız kapsam (tümü ↔ tek kaynak) ve süzgeç zenginliğidir.
 *
 * <p>Yeniden tasarım (2026-09-26, shadcn + mobil web): üstte katlanır özet (MonitorStatsBar kartları = olay süzgeci,
 * tür kartları = tür süzgeci), araç çubuğu (arama + süzgeç seçicileri; telefonda "Süzgeçler (n)" alt Sheet'i), zaman
 * aralığı (kendi kutusunda kayar), etkin süzgeç rozetleri, liste: masaüstü/tablet shadcn Table (Data Table görünümü),
 * telefon Card listesi (`useIsMobile` — TEK varyant çizilir). Satır/kart ayrıntıyı yan panelde açar (Sheet: künye,
 * not, `audit/DiffTable` farkı, o anki tam ayarlar). Kap genişliği `@container/chg` — kenar çubuğu açıkken tablette
 * içerik ~460 px kalıyor; araç çubuğu ve tablo sütunları görünüm alanına değil KABA göre yerleşir.
 *
 * <p>Kapsam sunucuda: global admin/AUDIT her şeyi görür, diğerleri {@code viewTeamIds} kesişimini (takımsız satırlar
 * ekip üyesinin değişikliğiyse görünür — TeamActorScope). Ekran URL'e süzgeç YAZMAZ (uygulama anahtarları
 * `tab/domain/monitor/incident` ile çakışma yok); izlemeye giden bağlantı `?tab=<tür>&monitor=<id>&mtab=changes`.
 */

const KINDS = ['port', 'dns', 'keyword', 'http', 'page', 'pagespeed', 'scripted', 'domain',
  'ping', 'inventory', 'group', 'maintenance']
const EVENTS = ['CREATE', 'UPDATE', 'DELETE', 'RESTORE', 'GROUP_RENAME']

/** Zaman pencereleri. Saklama süresi 730 gün; 90 günden uzun pencereler için özel aralık var. */
const RANGE_KEYS = ['all', 'today', '7', '15', '30', '45', '60', '90', 'custom']


/**
 * İzleme türü → uygulama sekmesi (satırdan izlemenin kendi geçmişine gitmek için).
 *
 * KINDS'teki her İZLEME türü burada olmak ZORUNDA: eksik olan tür için satırdaki izleme adı bağlantı olmaz ve
 * kullanıcı değişikliği gördüğü monitöre atlayamaz. `pagespeed` tam olarak böyle eksikti — KINDS'e, etiketlere ve
 * ikonlara eklenmiş, buraya eklenmemişti; `change-kinds-sync` kapısı da yalnız o üçlüyü sayıyordu.
 *
 * İzleme OLMAYAN üç tür bilinçli olarak dışarıda (kapı testindeki muafiyet listesiyle birebir):
 * `inventory` (kendi monitör sayfası yok — envanter sekmesi `?monitor=` parametresi taşımaz),
 * `group` ve `maintenance` (tekil monitör kaydı yok).
 */
const TAB_BY_KIND = {
  port: 'port', dns: 'dns', keyword: 'keyword', http: 'http', page: 'page',
  pagespeed: 'pagespeed', scripted: 'scripted', domain: 'domain', ping: 'ping',
}

/**
 * Satırın izlemesine giden bağlantı — izleme türü değilse ya da olay SİLME ise yok (silinmiş izlemenin sayfası
 * açılmaz; kullanıcıyı boş bir sayfaya götürmek yerine ad düz metin kalır). `monitor` + `mtab=changes`: izlemenin
 * detayı Değişiklikler sekmesinde açılır.
 */
function linkFor(r) {
  if (!r || r.event_type === 'DELETE' || r.resource_id == null) return null
  const tab = TAB_BY_KIND[String(r.kind).toLowerCase()]
  if (!tab) return null
  return {
    tab,
    params: { monitor: r.resource_id, mtab: 'changes' },
    href: `?tab=${tab}&monitor=${r.resource_id}&mtab=changes`,
  }
}

/**
 * Liste kabı 640 px'ten darsa (kenar çubuğu açık tablette içerik ≈ 460 px) tablo yerine kart listesi: altı sütunlu
 * tablo o genişlikte yalnız yatay kaydırmayla okunuyordu. Görünüm alanı değil KAP ölçülür (kenar çubuğu
 * katlanınca tablo geri gelir). jsdom'da ResizeObserver no-op ve clientWidth 0 → dar sayılmaz (testler
 * `useIsMobile` ile varyant seçer).
 */
function useNarrowContainer(ref, limit = 640) {
  const [narrow, setNarrow] = useState(false)
  useEffect(() => {
    const el = ref.current
    if (!el || typeof ResizeObserver === 'undefined') return undefined
    const check = () => setNarrow(el.clientWidth > 0 && el.clientWidth < limit)
    check()
    const ro = new ResizeObserver(check)
    ro.observe(el)
    return () => ro.disconnect()
  }, [ref, limit])
  return narrow
}

/**
 * @param {boolean} globalViewer  Backend'deki SessionScope.isGlobalViewer karşılığı — global
 *   admin ya da AUDIT. Yalnız SUNUM için kullanılır (kapsam notu); gerçek kapsam uçta uygulanır.
 *   Kapsamlı müdür-admin buraya girmez.
 */
export default function MonitorChangesConsole({ globalViewer = false }) {
  const t = useT()
  const rootRef = useRef(null)
  const isMobile = useIsMobile()                  // telefon: süzgeçler Sheet'te, liste kart
  const narrow = useNarrowContainer(rootRef)      // dar kap (kenar çubuğu açık tablet): yalnız liste kart
  const cards = isMobile || narrow
  const [rows, setRows] = useState(null)          // null = ilk yükleme (iskelet)
  const [loading, setLoading] = useState(true)
  const [counts, setCounts] = useState({})
  const [kindCounts, setKindCounts] = useState({})
  const [kind, setKind] = useState('')
  const [eventType, setEventType] = useState('')
  const [actor, setActor] = useState('')
  const [q, setQ] = useState('')
  const [qTerm, setQTerm] = useState('')   // 300 ms debounce — her tuşta sunucu araması yok
  useEffect(() => { const id = setTimeout(() => setQTerm(q), 300); return () => clearTimeout(id) }, [q])
  const loadSeq = useRef(0)                // fetch yarışı: yalnız son isteğin yanıtı uygulanır
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  // Seçili aralık DÜĞMESİ ayrı tutulur: from/to'dan geri çıkarmak ("30 gün mü, özel mi")
  // tahmin işi olurdu ve gün sınırındaki bir yenilemede seçim kayardı.
  const [rangeKey, setRangeKey] = useState('all')
  // Özet şerit + tür kartları VARSAYILAN KAPALI — izleme sayfalarındaki istatistik şeridiyle aynı davranış. Bu
  // ekranın işi "kim neyi değiştirdi" listesi; kartlar ilk ekranı doldurup listeyi katlamanın altına itiyordu.
  // Tür/olay süzgeçleri kapalıyken de araç çubuğunda erişilebilir (katlamak hiçbir yolu kapatmaz).
  const [statsVisible, setStatsVisible] = useState(false)
  const [error, setError] = useState(null)
  const [teamId, setTeamId] = useState('')
  const [teams, setTeams] = useState([])
  const [detail, setDetail] = useState(null)     // ayrıntı panelindeki satır
  // Sayfalama standardı (2026-09-26): kanca süzgecin DEĞERİ değişince aynı render'da sayfa 1'e döner (tek istek).
  // API 0-tabanlı; boyut ön ayardan.
  const sp = useServerPagination({ listKey: 'monitor-changes', preset: 'page',
    resetDeps: [kind, eventType, actor, qTerm, from, to, teamId, rangeKey], apiBase: 0 })
  const { apiPage: page, pageSize: size, setTotal } = sp

  const load = useCallback(async () => {
    const seq = ++loadSeq.current
    setLoading(true)
    try {
      const res = await api.monitoring.getRecentChanges({
        page, size, kind, eventType, actor, q: qTerm, from, to, ...(teamId ? { teamId } : {}),
      })
      if (seq !== loadSeq.current) return   // bayat yanıt
      if (res?.success) {
        setRows(res.data?.changes || [])
        setTotal(res.data?.total || 0)
        setCounts(res.data?.event_counts || {})
        setKindCounts(res.data?.kind_counts || {})
        setError(null)
      } else {
        setRows([])
        setError(res?.error || t('chg.loadError'))
      }
    } catch (e) {
      if (seq !== loadSeq.current) return
      setRows([])
      setError(e?.message || String(e))
    } finally {
      if (seq === loadSeq.current) setLoading(false)
    }
  }, [page, size, kind, eventType, actor, qTerm, from, to, teamId, t, setTotal])

  useEffect(() => { load() }, [load])

  // Takım listesi uçtan GÖRÜŞ KAPSAMINA göre süzülü gelir (AdminController.listTeams): kapsam tek yerde, sunucuda.
  // Patlarsa sessiz geçilir: takım seçici çıkmaz ama liste çalışmaya devam eder (süzgeç bir kolaylık).
  useEffect(() => {
    api.admin.getTeams()
      .then(res => setTeams(Array.isArray(res?.data) ? res.data : []))
      .catch(() => {})
  }, [])

  /**
   * Takım seçici ancak BİRDEN FAZLA takım görünüyorsa çizilir ("Tüm takımlar" + en az iki takım). Bu koşul YALNIZ
   * seçiciyi kapatır; satırdaki takım adı gibi İÇERİK buna bağlanmaz (takım listesi yardımcı bir istektir).
   */
  const teamOptions = useMemo(() => [
    { value: '', label: t('chg.allTeams') },
    ...teams.map(tm => ({ value: String(tm.id), label: tm.name })),
  ], [teams, t])
  const multiTeam = teamOptions.length > 2

  /** Aktör seçenekleri görünen satırlardan türetilir — ayrı bir uç açmaya değmez. */
  const actorOptions = useMemo(() => {
    const seen = new Map()
    ;(rows || []).forEach(r => {
      if (r.actor) seen.set(r.actor, r.actor === 'system' ? t('audit.systemActor') : (r.actor_name || r.actor))
    })
    return [{ value: '', label: t('chg.allActors') },
      ...[...seen.entries()].map(([v, label]) => ({ value: v, label }))]
  }, [rows, t])

  const kindOptions = useMemo(() => [
    { value: '', label: t('chg.allKinds') },
    ...KINDS.map(k => ({ value: k, label: t('chg.kind.' + k) })),
  ], [t])

  const eventOptions = useMemo(() => [
    { value: '', label: t('chg.allEventTypes') },
    ...EVENTS.map(e => ({ value: e, label: eventLabel(t, e) })),
  ], [t])

  /** Süzgeç tanımı — araç çubuğu, telefon Sheet'i ve etkin süzgeç rozetleri AYNI listeyi çizer. */
  const filters = [
    { key: 'kind', label: t('chg.filterKind'), ariaLabel: t('flt.monitorType'), value: kind, options: kindOptions, onChange: setKind, searchThreshold: 6 },
    { key: 'event', label: t('chg.filterEvent'), ariaLabel: t('chg.eventFilter'), value: eventType, options: eventOptions, onChange: setEventType, searchThreshold: 99 },
    { key: 'actor', label: t('chg.filterActor'), ariaLabel: t('flt.actor'), value: actor, options: actorOptions, onChange: setActor, searchThreshold: 6 },
    ...(multiTeam ? [{ key: 'team', label: t('chg.filterTeam'), ariaLabel: t('chg.teamFilter'), value: teamId, options: teamOptions, onChange: setTeamId, searchThreshold: 2 }] : []),
  ]
  const sheetActive = [kind, eventType, actor, teamId].filter(Boolean).length
  const anyFilter = sheetActive > 0 || q !== '' || rangeKey !== 'all'
  // Özel aralık seçicisinin uçları KARARLI (2026-09-27 regresyon B1): `to` boşken (ör. "Son 7 gün"den Özel'e
  // geçiş) her çizimde `new Date()` geçmek seçicinin taslağını her yeniden çizimde sıfırlıyordu.
  const customOpen = rangeKey === 'custom'
  const pickerRange = useMemo(() => (customOpen ? {
    from: from ? new Date(from) : new Date(Date.now() - 29 * 864e5),
    to: to ? new Date(to) : new Date(),
  } : null), [customOpen, from, to])

  /**
   * Zaman aralığı seçimi. Pencere kullanıcının YEREL takvimine göre kurulur ("bugün" = yerel gece yarısı), sonra
   * {@code toApiTime} ile UTC'ye çevrilip gönderilir (utils/apiTime.js).
   */
  function applyRange(key) {
    setRangeKey(key)
    if (key === 'custom') {
      // Seçici, EKRANDA GÖRÜNEN pencereyi göstermeli. Aralık zaten varsa ona dokunulmaz; "Tümü"den geliniyorsa
      // seçicinin varsayılanı (son 30 gün) hemen uygulanır — aksi halde düğme "Özel" derken liste hâlâ tüm
      // zamanı gösterir ve kontrol ekranla çelişir.
      if (!from) {
        setFrom(toApiTime(startOfLastNDays(30)))
        setTo(toApiTime(new Date()))
      }
      return
    }
    setTo('')
    if (key === 'all') { setFrom(''); return }
    // Sınır yerel takvimden, gönderim UTC — kural utils/apiTime.js'te tek yerde.
    setFrom(toApiTime(key === 'today' ? startOfLocalDay() : startOfLastNDays(Number(key))))
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

  /** HEPSİ: süzgeçler + arama (debounce beklemeden) + zaman aralığı. */
  function clearFilters() {
    sp.reset()
    setRangeKey('all')
    setFrom(''); setTo(''); setQ(''); setQTerm('')
    clearSheetFilters()
  }

  // Özet şeridi = olay süzgeci (MonitorStatsBar; kart tekrar tıklanınca süzgeç kalkar, "Toplam" temizler).
  const statItems = [
    { key: 'TOTAL', Icon: Sigma, label: t('chg.statTotal'), value: counts.TOTAL ?? sumCounts(counts), cls: 'total' },
    { key: 'CREATE', Icon: FilePlus2, label: t('chg.eventCREATE'), value: counts.CREATE ?? 0, cls: 'valid' },
    { key: 'UPDATE', Icon: Pencil, label: t('chg.eventUPDATE'), value: counts.UPDATE ?? 0, cls: 'warning' },
    { key: 'DELETE', Icon: Trash2, label: t('chg.eventDELETE'), value: counts.DELETE ?? 0, cls: 'critical' },
  ]
  const onStatClick = (key) => setEventType(prev => (key === 'TOTAL' || prev === key ? '' : key))

  const refreshBtn = (
    <SimpleTooltip content={t('app.refresh')}>
      <Button type="button" variant="outline" size="icon" aria-label={t('app.refresh')} aria-busy={loading || undefined}
        className="size-10 shrink-0 max-md:order-last md:size-9 @3xl/chg:order-last @3xl/chg:ml-auto" onClick={() => load()}>
        <RefreshCw aria-hidden="true" className={loading ? 'animate-spin motion-reduce:animate-none' : undefined} />
      </Button>
    </SimpleTooltip>
  )

  const now = Date.now()
  const listProps = { rows: rows || [], t, now, linkFor, onOpen: setDetail, selectedId: detail ? rowId(detail) : null, loading }

  let body
  if (rows === null && !error) body = <ChangeListSkeleton mobile={cards} t={t} />
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
      {cards ? <ChangeCards {...listProps} /> : <ChangeTable {...listProps} />}
      {/* Taban dönüşümü kancada: API 0-tabanlı (`apiPage`), çubuk 1-tabanlı (`sp.bar`). */}
      <PaginationBar {...sp.bar} />
    </>)
  }

  return (
    <div ref={rootRef} data-slot="chg-console" className="@container/chg flex min-w-0 flex-col gap-3">
      {/* Kapsam notu — yalnız takım kapsamlı kullanıcıya: liste kapsamla sınırlıdır ve takımsız kayıtlar burada
          görünmez. Yazılmasaydı kullanıcı eksik gördüğünü fark edemez, "demek hiç değişmemiş" diye okurdu. */}
      {!globalViewer && (
        <p className="m-0 flex items-start gap-1.5 text-xs text-muted-foreground">
          <Info aria-hidden="true" className="mt-px size-3.5 shrink-0" />{t('chg.scopeNote')}
        </p>
      )}

      {/* Katlanır özet — projenin tek "İstatistikler ▾" şeridi (ui/CollapsibleSection). Seçili zaman penceresinin
          TAMAMI (sayfalanan liste değil); kartlar olay ve tür süzgecidir. */}
      <CollapsibleSection open={statsVisible} onOpenChange={setStatsVisible}
        icon={BarChart3} label={t('app.statistics')} hint={t('app.expandStats')}
        toggleLabel={statsVisible ? t('app.collapseStats') : t('app.expandStats')}
        contentClassName="pt-3">
        <MonitorStatsBar items={statItems} activeFilter={eventType || null} onStatClick={onStatClick} />
        <ChangeKindCards t={t} kindCounts={kindCounts} selected={kind} onSelect={setKind} />
      </CollapsibleSection>

      {/* Araç çubuğu — dar kapta: arama + yenile bir satır, seçiciler 2×2 ızgara; geniş kapta tek satır.
          Telefonda seçiciler "Süzgeçler (n)" alt Sheet'inde. */}
      <div data-slot="chg-toolbar" className="flex min-w-0 flex-wrap items-center gap-2">
        <InputGroup className="max-md:basis-full min-w-0 flex-1 basis-56 @3xl/chg:w-72 @3xl/chg:flex-none">
          <InputGroupInput type="search" value={q} aria-label={t('chg.searchPlaceholder')}
            placeholder={t('chg.searchPlaceholder')} onChange={(e) => setQ(e.target.value)} />
          <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
        </InputGroup>
        {/* DOM'da aramadan hemen sonra: dar kapta aramayla aynı satırda; telefonda ve geniş kapta en sonda (order). */}
        {refreshBtn}
        {isMobile ? (
          <FilterSheet t={t} filters={filters} activeCount={sheetActive} onClear={clearSheetFilters} />
        ) : (
          <div data-slot="chg-filters"
            className="grid w-full min-w-0 grid-cols-2 gap-2 @3xl/chg:flex @3xl/chg:w-auto @3xl/chg:flex-wrap @3xl/chg:items-center">
            <FilterFields filters={filters} />
          </div>
        )}
      </div>

      {/* Zaman aralığı: hazır pencereler kendi kutusunda yatay kayar (telefonda satırı taşırmaz) + özel tarih. */}
      <div data-slot="chg-range" className="flex min-w-0 flex-col gap-2 @3xl/chg:flex-row @3xl/chg:flex-wrap @3xl/chg:items-center">
        <div className="max-w-full min-w-0 self-start overflow-x-auto overscroll-x-contain rounded-lg">
          <SegmentedControl value={rangeKey} onChange={applyRange}
            ariaLabel={t('chg.rangeFilter')} className="w-max flex-nowrap [&>[data-slot=toggle-group-item]]:max-md:h-9"
            options={RANGE_KEYS.map(k => ({
              value: k,
              label: k === 'all' ? t('chg.rangeAll')
                : k === 'today' ? t('chg.rangeToday')
                  : k === 'custom' ? t('chg.rangeCustom')
                    : t('chg.rangeDaysShort', k),
              title: k === 'all' ? t('chg.rangeAll')
                : k === 'today' ? t('chg.rangeToday')
                  : k === 'custom' ? t('chg.rangeCustomHint')
                    : t('chg.rangeDays', k),
            }))} />
        </div>
        {pickerRange && (
          <DateTimeRangePicker
            from={pickerRange.from}
            to={pickerRange.to}
            onApply={applyCustom} />
        )}
        {rangeKey !== 'all' && <span className="text-xs text-muted-foreground">{t('chg.rangeNote')}</span>}
      </div>

      <ActiveFilterChips t={t} filters={filters} showClearAll={anyFilter} onClearAll={clearFilters} />

      {error && (
        <AlertBanner tone="danger" role="alert" icon={AlertOctagon} title={t('chg.loadError')} className="mb-0"
          actions={<Button type="button" variant="outline" size="sm" className="max-md:h-10" onClick={() => load()}>
            <RefreshCw aria-hidden="true" /> {t('chg.retry')}</Button>}>
          {error}
        </AlertBanner>
      )}

      {body}

      <ChangeDetailSheet row={detail} t={t} now={now} linkFor={linkFor} onClose={() => setDetail(null)} />
    </div>
  )
}

/** Olay sayaçları toplamı — "toplam değişiklik" sayfalanan listeden DEĞİL, pencereden okunur. */
function sumCounts(counts) {
  return Object.values(counts || {}).reduce((a, b) => a + Number(b || 0), 0)
}
