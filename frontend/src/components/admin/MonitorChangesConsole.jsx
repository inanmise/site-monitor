import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Search, ChevronRight, Copy, BarChart3, ChevronDown } from 'lucide-react'
import { api, formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import DateTimeRangePicker from '../ui/DateTimeRangePicker.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import ChangeDiffChips from '../history/ChangeDiffChips.jsx'
import ChangeKindCards from './ChangeKindCards.jsx'
import { toApiTime, startOfLocalDay, startOfLastNDays } from '../../utils/apiTime.js'
import { shortUserAgent } from '../history/changeFields.js'
import { copyText } from '../../utils/copyText.js'

/**
 * Yönetici konsolu — TÜM izlemelerdeki yapılandırma değişiklikleri tek listede.
 *
 * <p>"Kim, nerede, neyi ekledi/değiştirdi, ne zaman" sorusunu tek noktadan cevaplar. İzlemenin
 * kendi "Değişiklikler" sekmesiyle AYNI veriyi ve AYNI sunum parçalarını kullanır
 * ({@code ChangeDiffChips}, {@code changeFields}); fark yalnız kapsam (tümü ↔ tek kaynak) ve
 * süzgeç zenginliğidir. Denetim konsolunun (`AuditLogViewer`) `audit-*` CSS ailesi yeniden
 * kullanılır — yeni bir görsel dil icat edilmez.
 *
 * <p>Kapsam sunucuda: global admin/AUDIT her şeyi görür, diğerleri {@code viewTeamIds} kesişimini.
 * Bu ekran yalnız yöneticiye gösteriliyor ama uç kapsamı doğru uyguladığı için ileride takıma
 * açmak yalnız bir görünürlük kararı olur.
 */

const KINDS = ['port', 'dns', 'keyword', 'http', 'page', 'pagespeed', 'scripted', 'domain',
  'ping', 'inventory', 'group', 'maintenance']
const EVENTS = ['CREATE', 'UPDATE', 'DELETE', 'RESTORE', 'GROUP_RENAME']

/** Zaman pencereleri. Saklama süresi 730 gün; 90 günden uzun pencereler için özel aralık var. */
const RANGE_KEYS = ['all', 'today', '7', '15', '30', '45', '60', '90', 'custom']


/**
 * İzleme türü → uygulama sekmesi (satırdan izlemenin kendi geçmişine gitmek için).
 *
 * KINDS'teki her İZLEME türü burada olmak ZORUNDA: eksik olan tür için satırda "İzlemeyi aç"
 * bağlantısı hiç çizilmez (`{tab && <a …>}`) ve kullanıcı değişikliği gördüğü monitöre atlayamaz.
 * `pagespeed` tam olarak böyle eksikti — KINDS'e, etiketlere ve ikonlara eklenmiş, buraya
 * eklenmemişti; `change-kinds-sync` kapısı da yalnız o üçlüyü sayıyordu.
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
 * @param {boolean} globalViewer  Backend'deki SessionScope.isGlobalViewer karşılığı — global
 *   admin ya da AUDIT. Yalnız SUNUM için kullanılır (kapsam notu, takım rozeti); gerçek kapsam
 *   uçta uygulanır. Kapsamlı müdür-admin buraya girmez.
 */
export default function MonitorChangesConsole({ globalViewer = false }) {
  const t = useT()
  const [rows, setRows] = useState(null)
  const [counts, setCounts] = useState({})
  const [kindCounts, setKindCounts] = useState({})
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(25)
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
  const [open, setOpen] = useState(null)
  // Ozet serit + tur kartlari VARSAYILAN KAPALI — izleme sayfalarindaki istatistik seridiyle
  // ayni davranis. Bu ekranin isi "kim neyi degistirdi" listesi; kartlar 12 tur x 3 olay ile
  // ilk ekrani doldurup asil listeyi katlamanin altina itiyordu. Tur suzgeci kapaliyken de
  // erisilebilir kalir (asagidaki acilir liste), yani katlamak hicbir yolu kapatmaz.
  const [statsVisible, setStatsVisible] = useState(false)
  const [error, setError] = useState(null)
  const [teamId, setTeamId] = useState('')
  const [teams, setTeams] = useState([])

  const load = useCallback(async () => {
    const seq = ++loadSeq.current
    setRows(null)
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
    }
  }, [page, size, kind, eventType, actor, qTerm, from, to, teamId, t])

  useEffect(() => { load() }, [load])

  // Takım listesi uçtan GÖRÜŞ KAPSAMINA göre süzülü gelir (AdminController.listTeams): yönetici
  // hepsini, diğerleri yalnız kendi takımlarını görür. Yani seçenekleri burada ayrıca elemeye
  // gerek yok — kapsam tek yerde, sunucuda.
  // Patlarsa sessiz geçilir: takım seçici çıkmaz ama liste çalışmaya devam eder (AlertHistory
  // ile aynı duruş). Süzgeç bir kolaylık, ekranın çalışma şartı değil.
  useEffect(() => {
    api.admin.getTeams()
      .then(res => setTeams(Array.isArray(res?.data) ? res.data : []))
      .catch(() => {})
  }, [])

  /**
   * Seçici ancak BİRDEN FAZLA takım görünüyorsa çizilir ("Tüm takımlar" + en az iki takım).
   * Tek takımlı kullanıcıda tek seçenekli bir açılır liste hiçbir şey yapmaz, yalnız araç
   * çubuğunu doldurur.
   *
   * <p>Bu koşul YALNIZ seçiciyi kapatır. Satırdaki takım adı gibi İÇERİK buna bağlanmaz:
   * takım listesi yardımcı bir istektir (yetki/ağ nedeniyle boş gelebilir) ve boş gelmesi
   * satırın kendi taşıdığı bilgiyi gizlememeli.
   */
  const teamOptions = useMemo(() => [
    { value: '', label: t('chg.allTeams') },
    ...teams.map(tm => ({ value: String(tm.id), label: tm.name })),
  ], [teams, t])
  const multiTeam = teamOptions.length > 2

  /** Aktör seçenekleri görünen satırlardan türetilir — ayrı bir uç açmaya değmez. */
  const actorOptions = useMemo(() => {
    const seen = new Map()
    ;(rows || []).forEach(r => { if (r.actor) seen.set(r.actor, r.actor_name || r.actor) })
    return [{ value: '', label: t('chg.allActors') },
      ...[...seen.entries()].map(([v, label]) => ({ value: v, label }))]
  }, [rows, t])

  const kindOptions = useMemo(() => [
    { value: '', label: t('chg.allKinds') },
    ...KINDS.map(k => ({ value: k, label: t('chg.kind.' + k) })),
  ], [t])

  const eventLabel = (ev) => {
    const key = `chg.event${ev}`
    const label = t(key)
    return label === key ? ev : label
  }

  /**
   * Zaman aralığı seçimi.
   *
   * <p>Pencere kullanıcının YEREL takvimine göre kurulur ("bugün" = yerel gece yarısı), sonra
   * {@code toApiTime} ile UTC'ye çevrilip gönderilir (utils/apiTime.js).
   */
  function applyRange(key) {
    setPage(0)
    setRangeKey(key)
    if (key === 'custom') {
      // Seçici, EKRANDA GÖRÜNEN pencereyi göstermeli. Aralık zaten varsa ona dokunulmaz;
      // "Tümü"den geliniyorsa seçicinin varsayılanı (son 30 gün) hemen uygulanır — aksi halde
      // düğme "Özel" derken liste hâlâ tüm zamanı gösterir ve kontrol ekranla çelişir.
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
    setPage(0)
    setRangeKey('custom')
    setFrom(f ? toApiTime(f) : '')
    setTo(tDate ? toApiTime(tDate) : '')
  }

  function clearFilters() {
    setPage(0)
    setRangeKey('all')
    setFrom(''); setTo(''); setEventType(''); setActor(''); setKind(''); setQ(''); setTeamId('')
  }

  const totalPages = Math.max(1, Math.ceil(total / size))

  return (
    <div className="audit-viewer chg-console">
      {/* Kapsam notu — yalnız takım kapsamlı kullanıcıya. İki şeyi birden söyler: liste
          kapsamla sınırlıdır VE takımsız kayıtlar burada görünmez. Yazılmasaydı kullanıcı eksik
          gördüğünü fark edemez, "demek hiç değişmemiş" diye okurdu. */}
      {!globalViewer && <p className="field-hint chg-scope-note">{t('chg.scopeNote')}</p>}

      {/* Katlama başlığı — izleme sayfalarındaki `stats-collapse-bar` ile AYNI şekil ve
          aynı sözlük anahtarları: kullanıcı burada yeni bir kalıp öğrenmez. */}
      <div className="stats-collapse-bar" onClick={() => setStatsVisible(v => !v)}
        role="button" tabIndex={0} aria-expanded={statsVisible}
        aria-label={statsVisible ? t('app.collapseStats') : t('app.expandStats')}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setStatsVisible(v => !v) } }}
        title={statsVisible ? t('app.collapseStats') : t('app.expandStats')}>
        <span className="stats-collapse-icon"><BarChart3 size={18} /></span>
        <span className="stats-collapse-label">{t('app.statistics')}</span>
        {!statsVisible && <span className="stats-collapse-hint">{t('app.expandStats')}</span>}
        <span className={`stats-collapse-chevron${statsVisible ? ' open' : ''}`}><ChevronDown size={18} /></span>
      </div>

      {statsVisible && (
        <>
          {/* Özet şeridi — seçili zaman penceresinin TAMAMI (sayfalanan liste değil). */}
          <div className="audit-stats-row chg-stats-row">
            <Stat label={t('chg.statTotal')} value={counts.TOTAL ?? sumCounts(counts)} />
            <Stat label={t('chg.eventCREATE')} value={counts.CREATE ?? 0} tone="new" />
            <Stat label={t('chg.eventUPDATE')} value={counts.UPDATE ?? 0} tone="edit" />
            <Stat label={t('chg.eventDELETE')} value={counts.DELETE ?? 0} tone="danger" />
          </div>

          {/* Tür kartları: hangi izlemede ne kadar oluşturma/değişiklik/silme — ve tür süzgeci. */}
          <ChangeKindCards t={t} kindCounts={kindCounts} selected={kind}
            onSelect={(v) => { setKind(v); setPage(0) }} />
        </>
      )}

      {/* Zaman aralığı: hazır pencereler + özel tarih. Kısa etiket (7g) ile uzun açıklama
          (Son 7 gün) ayrı: şerit dar kalsın ama ne olduğu tooltip'te tam yazsın. */}
      <div className="chg-range-row">
        <SegmentedControl value={rangeKey} onChange={applyRange}
          ariaLabel={t('chg.rangeFilter')} className="chg-range-seg"
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
        {rangeKey === 'custom' && (
          <DateTimeRangePicker
            from={from ? new Date(from) : new Date(Date.now() - 29 * 864e5)}
            to={to ? new Date(to) : new Date()}
            onApply={applyCustom} />
        )}
        {rangeKey !== 'all' && (
          <span className="chg-range-note">{t('chg.rangeNote')}</span>
        )}
      </div>

      <div className="audit-toolbar">
        <div className="audit-presets">
          <button className="audit-filter-btn" onClick={() => { setEventType('DELETE'); setPage(0) }}>
            {t('chg.presetDeletes')}</button>
          <button className="audit-filter-btn" onClick={clearFilters}>{t('chg.presetClear')}</button>
        </div>
        <div className="audit-toolbar-actions chg-filters">
          {multiTeam && (
            <SearchableSelect value={teamId} onChange={(v) => { setTeamId(v); setPage(0) }}
              options={teamOptions} searchThreshold={2} ariaLabel={t('chg.teamFilter')} />
          )}
          <SearchableSelect value={kind} onChange={(v) => { setKind(v); setPage(0) }}
            options={kindOptions} searchThreshold={6} ariaLabel={t('flt.monitorType')} />
          <SearchableSelect value={actor} onChange={(v) => { setActor(v); setPage(0) }}
            options={actorOptions} searchThreshold={6} ariaLabel={t('flt.actor')} />
          <input className="upt-search" type="text" value={q} aria-label={t('chg.searchPlaceholder')}
            placeholder={t('chg.searchPlaceholder')}
            onChange={(e) => { setQ(e.target.value); setPage(0) }} />
        </div>
      </div>

      <div className="chg-console-events">
        <SegmentedControl value={eventType} onChange={(v) => { setEventType(v); setPage(0) }}
          ariaLabel={t('chg.eventFilter')}
          options={[{ value: '', label: t('chg.allEvents') },
            ...EVENTS.map(e => ({ value: e, label: eventLabel(e) }))]} />
      </div>

      {error && <AlertBanner tone="danger" title={t('chg.loadError')}>{error}</AlertBanner>}

      {rows === null ? <LoadingBlock label={t('modal.loading')} />
        : rows.length === 0 ? (
          <StatusBlock tone="neutral" icon={Search} title={t('chg.emptyTitle')}
            description={t('chg.consoleEmptyText')} />
        ) : (<>
          <div className="chg-rows">
            {rows.map(r => {
              const id = `${r.kind}-${r.resource_id}-${r.seq}`
              const isOpen = open === id
              const tab = TAB_BY_KIND[String(r.kind).toLowerCase()]
              return (
                <div key={id} className={`chg-row${isOpen ? ' is-open' : ''}`}>
                  <button type="button" className="chg-row-head" aria-expanded={isOpen}
                    onClick={() => setOpen(isOpen ? null : id)}>
                    <ChevronRight size={15} className="chg-row-caret" aria-hidden="true" />
                    <span className={`chg-ev chg-ev--${String(r.event_type).toLowerCase()}`}>
                      {eventLabel(r.event_type)}
                    </span>
                    <span className="chg-row-main">
                      <span className="chg-row-name">{r.resource_name || `#${r.resource_id}`}</span>
                      <span className="chg-row-meta">
                        {t('chg.kind.' + String(r.kind).toLowerCase()) }
                        {/* as="span": satır başlığı zaten <button>; button içinde button geçersiz HTML
                            (React validateDOMNesting, QA ISSUE-001 2026-09-10). */}
                        {r.team_name ? <> · <TeamBadge teamId={r.team_id} teamName={r.team_name} size={11} as="span" /></> : ''}
                      </span>
                    </span>
                    <span className="chg-row-who">
                      <UserBadge username={r.actor} displayName={r.actor_name} size="sm" inline nameOnly />
                      <span className="chg-row-when sys-mono">{formatDateSec(r.at)}</span>
                    </span>
                  </button>

                  {/* Kapalı satırda bile ilk birkaç alan görünür: liste taranırken açmadan okunsun. */}
                  {!isOpen && <ChangeDiffChips t={t} changes={r.changes} limit={3} className="chg-row-chips" />}

                  {isOpen && (
                    <div className="chg-row-detail">
                      {r.note && <p className="chg-note">{r.note}</p>}
                      <ChangeDiffChips t={t} changes={r.changes} />
                      <div className="chg-row-facts">
                        {r.ip_address && (
                          <span className="chg-ip" title={t('chg.ipTitle')}
                            role="button" tabIndex={0}
                            aria-label={`${r.ip_address} — ${t('chg.ipTitle')}`}
                            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); copyText(r.ip_address) } }}
                            onClick={() => copyText(r.ip_address)}>
                            {r.ip_address}<Copy size={10} aria-hidden="true" />
                          </span>
                        )}
                        {r.user_agent && <span className="chg-ua" title={r.user_agent}>{shortUserAgent(r.user_agent)}</span>}
                        {tab && (
                          <a className="chg-goto" href={`?tab=${tab}&monitor=${r.resource_id}&mtab=changes`}>
                            {t('chg.openMonitor')}
                          </a>
                        )}
                      </div>
                    </div>
                  )}
                </div>
              )
            })}
          </div>

          {/* TABAN DÖNÜŞÜMÜ ŞART: `page` state'i ve API 0-tabanlı (bkz. :83), PaginationBar
              1-tabanlı. Dönüşüm yokken state=0'da hiçbir sayfa aktif görünmüyor ve "1" düğmesi
              API'nin 2. sayfasına gidiyordu. rangeStart/rangeEnd 0-tabanlı kalır. */}
          <PaginationBar
            page={page + 1} totalPages={totalPages} totalItems={total}
            rangeStart={total === 0 ? 0 : page * size + 1}
            rangeEnd={Math.min(total, (page + 1) * size)}
            pageSize={size}
            onPageChange={(p) => setPage(p - 1)}
            onPageSizeChange={(s) => { setSize(s); setPage(0) }} />
        </>)}
    </div>
  )
}

/** Olay sayaçları toplamı — "toplam değişiklik" sayfalanan listeden DEĞİL, pencereden okunur. */
function sumCounts(counts) {
  return Object.values(counts || {}).reduce((a, b) => a + Number(b || 0), 0)
}

/**
 * Denetim konsolunun kart sınıfı kullanılır: `.audit-stat` diye bir CSS kuralı YOK — ilk sürüm
 * onu kullandığı için şerit çerçevesiz/dolgusuz, yani "çıplak" görünüyordu (kullanıcı bildirimi).
 */
function Stat({ label, value, tone }) {
  return (
    <div className={`audit-stat-card${tone ? ` chg-stat--${tone}` : ''}`}>
      <div className="audit-stat-value">{value ?? '—'}</div>
      <div className="audit-stat-label">{label}</div>
    </div>
  )
}
