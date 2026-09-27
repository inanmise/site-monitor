import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { MessageSquareWarning, RefreshCw, Bug, CircleDot, Hourglass, CheckCircle2, MessageSquareReply, UserRound, Inbox, FilterX, SearchX } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useIsMobile } from '../../hooks/use-mobile.js'
import { readUrlParam, useUrlQuerySync } from '../../hooks/useUrlQuerySync.js'
import { usePagination } from '../../hooks/usePagination.js'
import { useServerPagination } from '../../hooks/useServerPagination.js'
import { useVisibleInterval } from '../../hooks/useVisibleInterval.js'
import PageHeader from '../ui/PageHeader.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import MonitorStatsBar from '../MonitorStatsBar.jsx'
import IssueReportModal from '../IssueReportModal.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import IssuesToolbar from './IssuesToolbar.jsx'
import IssuesList, { ListSkeleton, SignatureGroups } from './IssuesList.jsx'
import IssueDetailSheet from './IssueDetailSheet.jsx'
import {
  FILTER_DEFAULTS, MINE_FETCH_SIZE, OPEN_PARAM, VIEW_PARAM, activeFilters, filtersFromUrl, filtersToUrl, matchesFilters,
  sortRows, serverParams, isRefQuery, idFromRef, detailToRow, fmtDate, fmtRelative, toMs,
} from './issuesModel.js'

const TAB_PARAMS_EVENT = 'sm:tab-params'

function readOpenId() {
  const n = Number(readUrlParam(OPEN_PARAM, null))
  return Number.isInteger(n) && n > 0 ? n : null
}

/**
 * Sorun Bildirimleri sayfası (2026-09-27 uçtan uca yeniden tasarım) — HERKES için: kullanıcı kendi bildirimlerini
 * izler; yönetici (issues.login-reports) tüm kayıtları triyaj eder ve "Tüm bildirimler | Bildirimlerim" arasında geçer.
 *
 * <p>Düzen (ekranın her kitlede aynı dili): PageHeader (ikon, amaç, meta çipleri, Yenile + BİRİNCİL "Sorun bildir")
 * → sayaç kartları (MonitorStatsBar; süzgeç — `aria-pressed`) → araç çubuğu (arama, durum/kaynak/önem/tarih, sıralama,
 * etkin çipler; telefonda "Süzgeçler (n)" alt Sheet) → liste (md+ tablo / telefonda kart) → standart sayfalama →
 * ayrıntı Sheet'i (IssueDetailSheet) → rapor penceresi (IssueReportModal).
 *
 * <p>Derin bağlantılar DEĞİŞMEDİ: `ir_id` (kayıt), `ir_view` (yönetici görünümü: all | mine; yalnız `ir_id` → kendi kaydı).
 * Sekme zaten açıkken bildirim kutusundan gelen bağlantı `sm:tab-params` olayıyla işlenir.
 */
export default function IssueReportsPage({ adminAudience = false, canEdit = false, canPurge = false }) {
  const t = useT()
  const [view, setView] = useState(() => {
    if (!adminAudience) return 'mine'
    const v = readUrlParam(VIEW_PARAM, null)
    if (v === 'all' || v === 'mine') return v
    return readOpenId() ? 'mine' : 'all'
  })
  const [openRequest, setOpenRequest] = useState(null)   // { id, n } — sekme açıkken gelen derin bağlantı
  // İstek TÜKETİLİR: pano `key={source}` ile yeniden kurulduğunda (görünüm anahtarı) aynı istek yeniden oynatılıp
  // kullanıcının çoktan kapattığı kaydı tekrar açmasın.
  const consumeOpenRequest = useCallback(() => setOpenRequest(null), [])
  useUrlQuerySync({ [VIEW_PARAM]: adminAudience && view === 'mine' ? 'mine' : null })

  useEffect(() => {
    const on = (e) => {
      const d = e?.detail || {}
      const id = Number(d[OPEN_PARAM])
      if (adminAudience) {
        const v = d[VIEW_PARAM] === 'all' || d[VIEW_PARAM] === 'mine' ? d[VIEW_PARAM] : (id > 0 ? 'mine' : null)
        if (v) setView(v)
      }
      if (Number.isInteger(id) && id > 0) setOpenRequest((r) => ({ id, n: (r?.n || 0) + 1 }))
    }
    window.addEventListener(TAB_PARAMS_EVENT, on)
    return () => window.removeEventListener(TAB_PARAMS_EVENT, on)
  }, [adminAudience])

  const source = adminAudience && view === 'all' ? 'admin' : 'mine'
  const viewSwitch = adminAudience && (
    <SegmentedControl value={view} onChange={(v) => { if (v) setView(v) }} ariaLabel={t('issues.viewLabel')} className="self-start"
      options={[{ value: 'all', label: t('loginIssues.tabAll') }, { value: 'mine', label: t('loginIssues.tabMine') }]} />
  )
  return (
    <IssuesBoard key={source} source={source} adminAudience={adminAudience} canEdit={canEdit} canPurge={canPurge}
      viewSwitch={viewSwitch} onShowMine={() => setView('mine')} openRequest={openRequest} onOpenRequestHandled={consumeOpenRequest} />
  )
}

function IssuesBoard({ source, adminAudience, canEdit, canPurge, viewSwitch, onShowMine, openRequest, onOpenRequestHandled }) {
  const t = useT()
  const phone = useIsMobile()
  const admin = source === 'admin'
  const [filters, setFilters] = useState(() => filtersFromUrl((k) => readUrlParam(k, '')))
  const patch = useCallback((p) => setFilters((f) => ({ ...f, ...p })), [])
  const reset = useCallback(() => setFilters((f) => ({ ...FILTER_DEFAULTS, sort: f.sort })), [])
  const [openId, setOpenId] = useState(() => {
    // Yönetici "Tüm bildirimler" görünümü ir_id'yi yalnız ir_view=all ile açar; yalnız ir_id kişinin kendi kaydıdır.
    if (admin && readUrlParam(VIEW_PARAM, null) !== 'all') return null
    return readOpenId()
  })
  useEffect(() => {
    if (!openRequest?.id) return
    setOpenId(openRequest.id)
    onOpenRequestHandled?.()
  }, [openRequest, onOpenRequestHandled])
  const [reportOpen, setReportOpen] = useState(false)
  const [grouped, setGrouped] = useState(false)
  const [nowMs, setNowMs] = useState(Date.now())
  useUrlQuerySync({ ...filtersToUrl(filters), [OPEN_PARAM]: openId })

  // ── Veri ────────────────────────────────────────────────────────────────
  const [data, setData] = useState(null)          // { rows, total, counts }
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [mineTotal, setMineTotal] = useState(null) // yönetici: kendi kayıt sayısı (kart)
  const serverKey = JSON.stringify(admin ? serverParams(filters) : {})
  const sp = useServerPagination({ listKey: 'login-issues', preset: 'page', resetDeps: [serverKey], apiBase: 0 })
  const { apiPage, pageSize } = sp
  const refQuery = admin && isRefQuery(filters.q) ? idFromRef(filters.q) : null

  // Fetch yarışı (2026-09-27 regresyonu — HEAD'deki LoginIssueReports `loadSeq` taşıyordu, yeniden tasarımda düştü):
  // süzgeç/kart/arama/sayfa/60 sn yoklama/onChanged art arda istek çıkarır; geç dönen ESKİ yanıt yeni süzgecin
  // satırlarını, sayacını ve toplamını ezmesin. Yalnız EN SON isteğin yanıtı + bayrak temizliği uygulanır.
  const loadSeq = useRef(0)
  const load = useCallback(async ({ quiet = false } = {}) => {
    const my = ++loadSeq.current
    if (!quiet) setLoading(true)
    try {
      if (!admin) {
        const res = await api.issueReports.mine({ page: 0, size: MINE_FETCH_SIZE })
        if (my !== loadSeq.current) return   // bayat yanıt — daha yeni bir istek yolda
        if (!res?.success) throw new Error(res?.error || t('settings.loadError'))
        setData({ rows: res.data || [], total: res.total ?? (res.data || []).length, counts: res.counts || {} })
      } else if (refQuery != null) {
        // Referans kodu araması: sunucu metin araması kodu bilmez → kaydı doğrudan getir (yoksa boş liste).
        const res = await api.admin.getLoginIssue(refQuery).catch(() => null)
        if (my !== loadSeq.current) return
        const row = res?.success ? detailToRow(res.data) : null
        setData((prev) => ({ rows: row ? [row] : [], total: row ? 1 : 0, counts: prev?.counts || {} }))
        sp.setTotal(row ? 1 : 0)
      } else {
        const res = await api.admin.getLoginIssues({ ...serverParams(filters), page: apiPage, size: pageSize })
        if (my !== loadSeq.current) return
        if (!res?.success) throw new Error(res?.error || t('settings.loadError'))
        setData({ rows: res.data || [], total: res.total || 0, counts: res.counts || {} })
        sp.setTotal(res.total || 0)
      }
      setError(null)
    } catch (e) {
      if (my !== loadSeq.current) return
      setError(e?.message || t('settings.loadError'))   // bayat satırlar korunur, altında bant
    } finally {
      // Bayrak YALNIZ güncel istekte iner: bastırılan eski yanıt, uçuştaki yenisinin spinner'ını söndürmesin.
      if (my === loadSeq.current) {
        setLoading(false)
        setNowMs(Date.now())
      }
    }
  }, [admin, serverKey, refQuery, apiPage, pageSize])   // eslint-disable-line react-hooks/exhaustive-deps -- sp/t kararlı değil; anahtarlar yeterli
  useEffect(() => { load() }, [load])
  useVisibleInterval(() => load({ quiet: true }), 60_000, false)   // yeni yanıtlar/durumlar kendiliğinden gelsin
  useEffect(() => {
    if (!admin) return
    api.issueReports.mine({ page: 0, size: 1 }).then((r) => { if (r?.success) setMineTotal(r.total ?? 0) }).catch(() => {})
  }, [admin])

  // ── Görünen satırlar ────────────────────────────────────────────────────
  const allRows = useMemo(() => data?.rows || [], [data])
  const filtered = useMemo(() => {
    if (admin) return filters.sort === 'activity' ? sortRows(allRows, 'activity') : allRows
    return sortRows(allRows.filter((r) => matchesFilters(r, filters, t)), filters.sort)
  }, [admin, allRows, filters, t])
  const filtersKey = JSON.stringify(filters)
  const pager = usePagination(admin ? [] : filtered, { listKey: 'issue-reports-mine', preset: 'page', resetDeps: [filtersKey] })
  const rows = admin ? filtered : pager.pageItems
  const count = admin ? (data?.total ?? 0) : filtered.length

  const counts = data?.counts || {}
  const unread = admin ? 0 : allRows.filter((r) => r.unread).length
  const unresolved = (counts.OPEN || 0) + (counts.IN_PROGRESS || 0)
  const lastActivity = allRows.reduce((best, r) => (toMs(r.lastActivityAt) > toMs(best) || !best ? r.lastActivityAt : best), null)
  const active = activeFilters(filters)

  const tiles = [
    { key: 'OPEN', Icon: CircleDot, label: t('loginIssues.statusOpen'), value: counts.OPEN ?? 0, cls: 'warning', hint: t('issues.tileOpenHint') },
    { key: 'IN_PROGRESS', Icon: Hourglass, label: t('loginIssues.statusInProgress'), value: counts.IN_PROGRESS ?? 0, cls: 'total', hint: t('issues.tileInProgressHint') },
    { key: 'RESOLVED', Icon: CheckCircle2, label: t('loginIssues.statusResolved'), value: counts.RESOLVED ?? 0, cls: 'valid', hint: t('issues.tileResolvedHint') },
    admin
      ? { key: 'mine', Icon: UserRound, label: t('loginIssues.tabMine'), value: mineTotal ?? '—', cls: 'paused', hint: t('issues.tileMineHint'), onClick: onShowMine }
      : { key: 'awaiting', Icon: MessageSquareReply, label: t('issues.tileAwaiting'), value: unread, cls: 'high', hint: t('issues.tileAwaitingHint') },
  ]
  const onTile = (key) => {
    if (key === 'awaiting') patch({ awaiting: !filters.awaiting, status: '' })
    else patch({ status: filters.status === key ? '' : key, awaiting: false })
  }

  const meta = data && (
    <>
      <Badge variant="outline" className="font-medium tabular-nums">{t('issues.metaUnresolved', unresolved)}</Badge>
      {admin
        ? <Badge variant="outline" className="font-medium tabular-nums">{t('issues.metaUntriaged', counts.OPEN || 0)}</Badge>
        : <Badge variant="outline" data-unread={unread > 0 || undefined}
            className={cn('font-medium tabular-nums', unread > 0 && 'border-primary/40 bg-primary/10 text-primary')}>{t('issues.metaNewReplies', unread)}</Badge>}
      {lastActivity && <span title={fmtDate(lastActivity)}>{t('issues.lastActivity', fmtRelative(lastActivity, t, nowMs))}</span>}
    </>
  )
  const reportBtn = (
    <Button type="button" onClick={() => setReportOpen(true)}><Bug aria-hidden="true" />{t('issues.reportProblem')}</Button>
  )

  let body
  if (!data && loading) body = <ListSkeleton phone={phone} />
  else if (!data && error) {
    body = (
      <AlertBanner tone="danger" role="alert" title={t('settings.loadError')} className="mb-0">
        <div>{String(error)}</div>
        <div className="mt-2"><Button type="button" size="sm" variant="outline" onClick={() => load()}><RefreshCw aria-hidden="true" />{t('issues.retry')}</Button></div>
      </AlertBanner>
    )
  } else if (rows.length === 0) {
    body = active.length > 0
      ? <StatusBlock tone="neutral" icon={SearchX} title={t('issues.emptyFiltered')} description={t('issues.emptyFilteredHint')}
          actions={<Button type="button" variant="outline" onClick={reset}><FilterX aria-hidden="true" />{t('issues.clearAll')}</Button>} />
      : admin
        ? <StatusBlock tone="neutral" icon={Inbox} title={t('issues.emptyAdmin')} description={t('issues.emptyAdminHint')} />
        : <StatusBlock tone="neutral" icon={Inbox} title={t('myIssues.empty')} description={t('myIssues.emptyHint')} actions={reportBtn} />
  } else if (admin && grouped) {
    body = <SignatureGroups rows={rows} onOpen={(r) => setOpenId(r.id)} />
  } else {
    body = <IssuesList rows={rows} admin={admin} phone={phone} selectedId={openId} onOpen={(r) => setOpenId(r.id)} nowMs={nowMs} />
  }

  return (
    <section data-slot="issue-reports" data-audience={adminAudience ? 'admin' : 'user'} data-source={source} className="mb-8 flex min-w-0 flex-col gap-4">
      <PageHeader className="mb-0" icon={MessageSquareWarning} title={t('issues.title')}
        description={admin ? t('issues.descAdmin') : adminAudience ? t('issues.descAdminMine') : t('issues.descUser')}
        meta={meta}
        actions={(
          <>
            <Button type="button" variant="outline" onClick={() => load()} aria-busy={loading || undefined}>
              <RefreshCw aria-hidden="true" className={cn(loading && 'motion-safe:animate-spin')} />{t('issues.refresh')}
            </Button>
            {reportBtn}
          </>
        )}>
        {viewSwitch}
      </PageHeader>

      {error && data && (
        <AlertBanner tone="danger" role="alert" title={t('settings.loadError')} className="mb-0">{String(error)}</AlertBanner>
      )}

      <MonitorStatsBar items={tiles} activeFilter={filters.awaiting ? 'awaiting' : (filters.status || null)} onStatClick={onTile} />

      <IssuesToolbar filters={filters} patch={patch} reset={reset} phone={phone} count={count}
        pageSortNote={admin && refQuery == null} showGroup={admin} grouped={grouped} onGrouped={setGrouped} />

      {!admin && data && data.total > allRows.length && (
        <AlertBanner tone="info" className="mb-0">{t('issues.mineCapped', allRows.length)}</AlertBanner>
      )}

      {body}

      {rows.length > 0 && (admin ? <PaginationBar {...sp.bar} /> : <PaginationBar {...pager} />)}

      {openId != null && (
        <IssueDetailSheet key={`${source}-${openId}`} id={openId} source={source} canEdit={admin && canEdit} canPurge={admin && canPurge}
          onClose={() => setOpenId(null)}
          onChanged={() => load({ quiet: true })}
          onPurged={() => { setOpenId(null); load() }} />
      )}

      <IssueReportModal open={reportOpen} onClose={() => { setReportOpen(false); load({ quiet: true }) }} />
    </section>
  )
}
