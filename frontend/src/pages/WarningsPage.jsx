import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  AlertOctagon, BellOff, Clock, Download, Flame, Hourglass, KeyRound, Link2Off, MailWarning, PlayCircle, RefreshCw,
  ShieldCheck, TriangleAlert, Wifi, WifiOff,
} from 'lucide-react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { navigateTo } from '../utils/navigate.js'
import { useIsMobile } from '../hooks/use-mobile.js'
import { useUrlQuerySync, readUrlParam } from '../hooks/useUrlQuerySync.js'
import { usePagination } from '../hooks/usePagination.js'
import PageHeader from '../components/ui/PageHeader.jsx'
import HintPopover from '../components/ui/HintPopover.jsx'
import AlertBanner from '../components/ui/AlertBanner.jsx'
import StatusBlock from '../components/ui/StatusBlock.jsx'
import CollapsibleSection from '../components/ui/CollapsibleSection.jsx'
import PaginationBar from '../components/ui/PaginationBar.jsx'
import { Spinner } from '../components/ui/Progress.jsx'
import MonitorStatsBar from '../components/MonitorStatsBar.jsx'
import NetworkOutageHistory from '../components/NetworkOutageHistory.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import { useElementWidth } from './forecast/forecastUi.jsx'
import WarningsToolbar from './warnings/WarningsToolbar.jsx'
import AttentionList from './warnings/AttentionList.jsx'
import { EMPTY_ICONS, attentionCsv } from './warnings/AttentionParts.jsx'
import {
  GROUP_KEYS, SORT_KEYS, TIER_VALUES, WHY_KEYS, analyze, applyFacets, enrich, groupItems, groupOf, matchesWhy, parseList,
  serializeList, sortItems, teamOptions, tierOptions, whyCounts,
} from './warnings/warningsModel.js'

/**
 * Dikkat Gerektiren Sertifikalar (Uyarılar, `?tab=warnings`) — 2026-09-27 shadcn + mobil web yeniden tasarımı
 * (App.jsx'teki satır içi bloktan çıkarıldı).
 *
 * Yapı: PageHeader (sayaçlar, son güncelleme, "saatlik kontrol" açıklaması; Yenile · CSV · Şimdi Kontrol Et) →
 * "neden" kutucukları (MonitorStatsBar, süzgeç `wa_why`) → süzgeç çubuğu (arama `wa_q`, takım `wa_team`, kritiklik
 * `wa_tier`, sıralama `wa_sort`, görünüm `wa_view`) → aciliyete göre GRUPLU liste (Hemen müdahale · Yapılandırma
 * sorunları · Bu ay yenilenecek; her satırda gerekçe çipleri + önerilen sonraki adım + hızlı eylemler; tablo / telefonda
 * kart / Pano kartları) → standart sayfalama (`wa_page`/`wa_ps`) → Ağ Erişim Geçmişi (katlanır; süren kesinti varsa açık).
 *
 * Veri SAYFADA yüklenir (`/api/warnings` + `/api/system/network-outage-history`): ilk yüklemede iskelet (eski sürüm
 * boş listeyle "uyarı yok" gösteriyordu — yalancı "her şey yolunda"), hata bandı + yeniden dene. App'in 5 dk'lık
 * tazelemesi ve "Şimdi Kontrol Et" `refreshKey` (Pano'nun son güncelleme damgası) ile listeyi de tazeler. Takım /
 * kritiklik / platform, uyarı ucu taşımadığı için Pano listesinden (`certs`) doldurulur (warningsModel.enrich).
 * 7/24 durumu (`noc_notify` + `noc_group_ids`, 2026-09-28) ise uyarı satırının KENDİSİNDE: her satırda Genel Bakış
 * kartıyla aynı gösterge (noc/NocStatus, tür SSL; sayfa başına tek 7/24 isteği — noc/useNocState).
 *
 * App'ten gelen eylemler: kart detayı, sağlık sekmesi, yenileme planı (ortak RenewalPlanModal App'te), kart eylemleri
 * (Şimdi kontrol et / Düzenle / Kopyala / Sil — CertificateCard sözleşmesi DEĞİŞMEDİ; Düzenle aynı zamanda 7/24
 * göstergesinin düzenleme eylemi), e-posta hatası → Sistem Sağlığı SMTP günlüğü, toplu "Şimdi Kontrol Et" (Pano'nun
 * takım seçici akışı).
 */
const WARN_PAGE_URL = Object.freeze({ pageKey: 'wa_page', sizeKey: 'wa_ps' })
const VIEW_KEY = 'sm.warnings.view'
const OUTAGE_FOLD = 5
const WHY_ICON = { expired: AlertOctagon, week: Flame, month: Hourglass, chain: Link2Off, error: WifiOff, weak: KeyRound, silent: BellOff, mail: MailWarning }
const WHY_TONE = { expired: 'expired', week: 'critical', month: 'warning', chain: 'certissue', error: 'error', weak: 'weak', silent: 'alert', mail: 'error' }

function initialView() {
  const v = readUrlParam('wa_view', '')
  if (v === 'cards' || v === 'list') return v
  try { return localStorage.getItem(VIEW_KEY) === 'cards' ? 'cards' : 'list' } catch { return 'list' }
}

function PageSkeleton({ label }) {
  return (
    <div aria-busy="true" data-slot="attn-skeleton" className="flex min-w-0 flex-col gap-3">
      <span role="status" className="sr-only">{label}</span>
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-5">
        {Array.from({ length: 5 }, (_, i) => <Skeleton key={i} className="h-24 rounded-lg" />)}
      </div>
      <Skeleton className="h-9 w-full sm:w-2/3" />
      {Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-20 rounded-lg" />)}
    </div>
  )
}

const has = (set, d) => !!set && typeof set.has === 'function' && set.has(d)

export default function WarningsPage({
  certs = [], refreshKey = null, cardExtras = null, silentAlertDomains = null, mailFailureDomains = null, weakDomains = null,
  onOpenCert, onOpenHealth, onPlanRenewal, cardActions, onMailFailure, onCheckAll, checkingAll = false, checkProgress = null,
}) {
  const t = useT()
  const isMobile = useIsMobile()
  // Kart/tablo kararı KAP genişliğinden (tablette kenar çubuğu açıkken içerik ~440 px) — Vade Takvimi ile aynı
  const [listWidth, listRef] = useElementWidth()
  const narrow = isMobile || (listWidth > 0 && listWidth < 640)

  // ── Veri ──────────────────────────────────────────────────────────────────────────────────────
  const [data, setData] = useState({ rows: null, ts: null, error: null })
  const [outages, setOutages] = useState(null)
  const [refreshing, setRefreshing] = useState(false)
  const seq = useRef(0)       // yarış koruması: hızlı yeniden yüklemede YAVAŞ olan eski yanıt yenisini ezmesin
  const alive = useRef(true)  // söküm sonrası gelen yanıt durum yazmasın
  const load = useCallback(async (manual = false) => {
    const id = ++seq.current
    if (manual === true) setRefreshing(true)
    const [w, o] = await Promise.allSettled([api.getWarnings(), api.getNetworkOutageHistory(50)])
    if (!alive.current) return
    if (manual === true) setRefreshing(false)
    if (id !== seq.current) return
    const wr = w.status === 'fulfilled' ? w.value : null
    if (wr?.success) setData({ rows: Array.isArray(wr.data) ? wr.data : [], ts: wr.timestamp ?? null, error: null })
    else setData((d) => ({ ...d, error: (w.status === 'rejected' ? w.reason?.message : wr?.error) || 'error' }))
    const or = o.status === 'fulfilled' ? o.value : null
    if (or?.success && Array.isArray(or.events)) setOutages(or.events)
    else setOutages((prev) => prev ?? [])
  }, [])
  useEffect(() => {
    alive.current = true   // StrictMode söküm → yeniden kurulum
    load()
    return () => { alive.current = false }
  }, [load])
  // App verisi tazelenince (5 dk döngüsü, Şimdi Kontrol Et, tek kart kontrolü) liste de tazelenir — ilk damga hariç
  const prevKey = useRef(refreshKey)
  useEffect(() => {
    if (prevKey.current === refreshKey) return
    const had = prevKey.current != null
    prevKey.current = refreshKey
    if (had) load()
  }, [refreshKey, load])

  // ── Süzgeç durumu (URL `wa_*`) ────────────────────────────────────────────────────────────────
  const [why, setWhy] = useState(() => { const v = readUrlParam('wa_why', ''); return WHY_KEYS.includes(v) ? v : null })
  const [q, setQ] = useState(() => readUrlParam('wa_q', ''))
  const [teams, setTeams] = useState(() => parseList(readUrlParam('wa_team', '')))
  const [tiers, setTiers] = useState(() => parseList(readUrlParam('wa_tier', ''), TIER_VALUES))
  const [sort, setSort] = useState(() => { const v = readUrlParam('wa_sort', ''); return SORT_KEYS.includes(v) ? v : 'urgency' })
  const [view, setViewRaw] = useState(initialView)
  const setView = (v) => {
    setViewRaw(v)
    try { localStorage.setItem(VIEW_KEY, v) } catch { /* depolama yok: tercih yalnız bu oturumda */ }
  }
  useUrlQuerySync({
    wa_why: why, wa_q: q.trim() || null, wa_team: serializeList(teams), wa_tier: serializeList(tiers),
    wa_sort: sort !== 'urgency' ? sort : null, wa_view: view === 'cards' ? 'cards' : null,
  })
  const [outageOpen, setOutageOpen] = useState(null)   // null = kullanıcı dokunmadı → süren kesinti varsa açık
  const [outageExpanded, setOutageExpanded] = useState(false)

  // ── Türetme ───────────────────────────────────────────────────────────────────────────────────
  const ctx = useMemo(() => ({ weak: weakDomains, silent: silentAlertDomains, mail: mailFailureDomains, plans: cardExtras }),
    [weakDomains, silentAlertDomains, mailFailureDomains, cardExtras])
  const rows = useMemo(() => enrich(data.rows || [], certs), [data.rows, certs])
  const faceted = useMemo(() => applyFacets(rows, { q, teams, tiers }), [rows, q, teams, tiers])
  const counts = useMemo(() => whyCounts(faceted, ctx), [faceted, ctx])
  const items = useMemo(() => sortItems(
    faceted.filter((r) => !why || matchesWhy(r, why, ctx)).map((r) => analyze(r, ctx)), sort), [faceted, why, ctx, sort])
  const groupCounts = useMemo(() => {
    const out = Object.fromEntries(GROUP_KEYS.map((k) => [k, 0]))
    for (const it of items) out[it.group]++
    return out
  }, [items])
  const nowTotal = useMemo(() => rows.filter((r) => groupOf(r, ctx) === 'now').length, [rows, ctx])
  const pager = usePagination(items, {
    listKey: 'warnings-certs', preset: 'page', resetDeps: [why, q, teams, tiers, sort], url: WARN_PAGE_URL,
  })
  const groups = useMemo(() => groupItems(pager.pageItems), [pager.pageItems])
  // Faset sayıları: her faset "öteki süzgeçler + bu seçenek" ile kaç satır kalacağını söyler
  const teamOpts = useMemo(() => teamOptions(rows, t('app.noTeam'), applyFacets(rows, { q, tiers })), [rows, q, tiers, t])
  const tierOpts = useMemo(() => tierOptions(rows, t('attn.tierNone'), applyFacets(rows, { q, teams })), [rows, q, teams, t])

  const tiles = useMemo(() => WHY_KEYS
    // Zayıf-algoritma verisi yoksa kutucuk yok (bilinmiyor ≠ sıfır); sessiz alarm / e-posta yalnız varsa.
    // Telefonda sıfır sayılı kutucuk gösterilmez (2 sütunda 8 kutucuk listeyi ekranın altına itiyordu); etkin olan kalır.
    .filter((k) => (k !== 'weak' || weakDomains) && ((k !== 'silent' && k !== 'mail') || counts[k] > 0 || why === k)
      && (!isMobile || counts[k] > 0 || why === k))
    .map((k) => ({ key: k, Icon: WHY_ICON[k], cls: WHY_TONE[k], value: counts[k], label: t(`attn.why.${k}`), hint: t(`attn.whyHint.${k}`) })),
  [counts, weakDomains, why, isMobile, t])

  const teamLabel = (v) => teamOpts.find((o) => o.value === v)?.label ?? (v === '__none__' ? t('app.noTeam') : v)
  const chips = [
    ...(why ? [{ key: 'why', label: t('attn.chipWhy', t(`attn.why.${why}`)), onRemove: () => setWhy(null) }] : []),
    ...(q.trim() ? [{ key: 'q', label: `“${q.trim()}”`, onRemove: () => setQ('') }] : []),
    ...(teams.length ? [{ key: 'team', label: t('attn.chipTeam', teams.map(teamLabel).join(', ')), onRemove: () => setTeams([]) }] : []),
    ...(tiers.length ? [{ key: 'tier', label: t('attn.chipTier', tiers.map((v) => (v === 'none' ? t('attn.tierNone') : `T${v}`)).join(', ')), onRemove: () => setTiers([]) }] : []),
  ]
  const clearAll = () => { setWhy(null); setQ(''); setTeams([]); setTiers([]) }

  // ── Eylemler ──────────────────────────────────────────────────────────────────────────────────
  const h = {
    onOpen: (d) => onOpenCert?.(d),
    onHealth: onOpenHealth ? (d) => onOpenHealth(d) : null,
    onPlan: onPlanRenewal ? (row, plan) => onPlanRenewal(row, plan) : null,
    onCheck: cardActions ? (row) => cardActions(row)?.onCheckNow?.() : null,
    isChecking: cardActions ? (row) => !!cardActions(row)?.checking : null,
    onInventory: (d) => navigateTo('domains', { i_q: d }),
    onMail: onMailFailure ? (d) => onMailFailure(d) : null,
    // 7/24 göstergesinin "7/24 ayarını düzenle" eylemi — Genel Bakış sertifika kartıyla AYNI yol ve AYNI kapı: kartın
    // Düzenle işleyicisi (App cardActions → envanter formu, 7/24 alanına kaydırılmış). Yoksa gösterge Kapsam bağlantısı verir.
    nocEdit: cardActions ? (row) => cardActions(row)?.onEdit ?? null : null,
  }
  const cardProps = (row) => ({
    hasSilentAlert: has(silentAlertDomains, row.domain),
    hasMailFailure: has(mailFailureDomains, row.domain),
    onMailFailureClick: onMailFailure ? () => onMailFailure(row.domain) : undefined,
    isWeak: weakDomains ? weakDomains.has(row.domain) : undefined,
    ...(cardActions ? cardActions(row) : {}),
  })
  function exportCsv() {
    try {
      const blob = new Blob([attentionCsv(items, t)], { type: 'text/csv;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `certificates-needing-attention-${new Date().toISOString().slice(0, 10)}.csv`
      document.body.appendChild(a); a.click(); a.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch { /* jsdom / kısıtlı ortam */ }
  }

  const loading = data.rows === null && !data.error
  const total = rows.length
  const ongoing = (outages || []).some((e) => e.status === 'ONGOING')
  const outageIsOpen = outageOpen ?? ongoing
  const outageHint = ongoing ? t('attn.outageHintOngoing')
    : outages?.length ? t('attn.outageHint', outages.length, formatDate(outages[0].detected_at)) : t('attn.outageHintNone')

  return (
    <div data-slot="attn-page" className="flex min-w-0 flex-col gap-4">
      <PageHeader icon={TriangleAlert} title={t('app.warningsTitle')} description={t('attn.desc')} className="mb-0"
        meta={(
          <>
            {data.rows !== null && (total === 0
              ? <Badge variant="secondary" data-slot="attn-meta-total" className="gap-1 bg-success/15 text-success dark:bg-success/20"><ShieldCheck aria-hidden="true" />{t('attn.metaClear')}</Badge>
              : <Badge variant="secondary" data-slot="attn-meta-total" className="tabular-nums">{total === 1 ? t('attn.metaTotal1') : t('attn.metaTotal', total)}</Badge>)}
            {nowTotal > 0 && (
              <Badge variant="secondary" data-slot="attn-meta-now" className="gap-1 bg-destructive/10 text-destructive tabular-nums dark:bg-destructive/20">
                <Flame aria-hidden="true" />{t('attn.metaNow', nowTotal)}
              </Badge>
            )}
            {data.ts && <span data-slot="attn-meta-updated">{t('app.lastUpdate')} {formatDate(data.ts)}</span>}
            <HintPopover content={t('app.sslHourlyNote')} triggerClassName="rounded-full">
              <Badge variant="outline" className="gap-1 font-normal text-muted-foreground"><Clock aria-hidden="true" />{t('attn.hourlyChip')}</Badge>
            </HintPopover>
          </>
        )}
        actions={(
          <>
            <Button type="button" variant="outline" onClick={() => load(true)} disabled={refreshing} aria-busy={refreshing || undefined}
              title={t('app.refresh')} aria-label={t('app.refresh')}>
              <RefreshCw aria-hidden="true" className={refreshing ? 'animate-spin motion-reduce:animate-none' : undefined} />
              <span className="hidden md:inline">{t('app.refresh')}</span>
            </Button>
            <Button type="button" variant="outline" onClick={exportCsv} disabled={!items.length} data-slot="attn-export"
              title={t('attn.exportCsv')} aria-label={t('attn.exportCsv')}>
              <Download aria-hidden="true" /><span className="hidden md:inline">{t('attn.exportCsv')}</span>
            </Button>
            {onCheckAll && (
              <Button type="button" onClick={onCheckAll} disabled={checkingAll} aria-busy={checkingAll || undefined}
                title={t('app.checkNowTip')} data-slot="attn-check-all">
                {checkingAll
                  ? <><Spinner decorative inline />{checkProgress || t('app.checkNow')}</>
                  : <><PlayCircle aria-hidden="true" />{t('app.checkNow')}</>}
              </Button>
            )}
          </>
        )} />

      {data.error && (
        <AlertBanner tone="danger" role="alert" title={t('attn.loadError')}
          actions={<Button type="button" variant="outline" size="sm" onClick={() => load(true)} disabled={refreshing}><RefreshCw aria-hidden="true" />{t('forecast.retry')}</Button>}>
          {String(data.error)}{data.rows ? ` · ${t('attn.staleShown')}` : ''}
        </AlertBanner>
      )}

      {loading && <PageSkeleton label={t('attn.loading')} />}

      {data.rows !== null && total === 0 && (
        <StatusBlock tone="success" icon={ShieldCheck} title={t('app.noWarnings')} description={t('empty.hintAllGood')}
          className="rounded-xl border border-dashed" />
      )}

      {total > 0 && (
        <>
          {/* ── "Neden" kutucukları = süzgeç ── */}
          <div className="[&>[data-slot=stats-panel]]:mb-0">
            <MonitorStatsBar items={tiles} activeFilter={why} onStatClick={(k) => setWhy((cur) => (cur === k ? null : k))} />
          </div>

          <WarningsToolbar q={q} onQ={setQ} teamOpts={teamOpts} teams={teams} onTeams={setTeams} tierOpts={tierOpts} tiers={tiers}
            onTiers={setTiers} sort={sort} onSort={setSort} view={view} onView={setView} chips={chips} onClearAll={clearAll}
            shown={items.length} total={total} />

          <section ref={listRef} data-slot="attn-list" data-view={view} aria-label={t('app.warningsTitle')} className="flex min-w-0 flex-col gap-3">
            {items.length === 0 ? (
              <StatusBlock tone="neutral" icon={EMPTY_ICONS.filtered} title={t('attn.noneFiltered')} description={t('empty.hintFilter')}
                className="rounded-xl border border-dashed"
                actions={<Button type="button" variant="outline" size="sm" onClick={clearAll}>{t('app.clearFilters')}</Button>} />
            ) : (
              <AttentionList groups={groups} groupCounts={groupCounts} view={view} narrow={narrow} h={h} cardProps={cardProps} />
            )}
            <PaginationBar {...pager} />
          </section>
        </>
      )}

      {/* ── Ağ Erişim Geçmişi — katlanır; süren kesinti varsa açık başlar ── */}
      {outages !== null && (
        <CollapsibleSection data-slot="attn-outage-section" open={outageIsOpen} onOpenChange={setOutageOpen}
          icon={ongoing ? WifiOff : Wifi} label={t('app.networkOutageHistoryTitle')} hint={outageHint}
          toggleLabel={t('app.networkOutageHistoryTitle')} contentClassName="pt-3">
          <NetworkOutageHistory events={outages} fold={OUTAGE_FOLD} expanded={outageExpanded}
            onToggleExpanded={() => setOutageExpanded((v) => !v)} heading={false} className="mt-0" />
        </CollapsibleSection>
      )}
    </div>
  )
}
