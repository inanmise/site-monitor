import { useCallback, useId, useMemo, useRef, useState } from 'react'
import { Database } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { cn } from '@/lib/utils'
import { DbHeader, DbNotices } from './dbanalytics/DbHeader.jsx'
import DbKpiGrid from './dbanalytics/DbKpiGrid.jsx'
import DbLoadChart from './dbanalytics/DbLoadChart.jsx'
import DbHealthCard from './dbanalytics/DbHealthCard.jsx'
import DbDataList from './dbanalytics/DbDataList.jsx'
import { DbSqlDetail, DbTableDetail } from './dbanalytics/DbDetails.jsx'
import { DbSkeleton } from './dbanalytics/DbParts.jsx'
import { buildColumns } from './dbanalytics/dbColumns.jsx'
import { buildChart, buildTables, connModel, deadSummary } from './dbanalytics/dbModel.js'
import { useMinWidth } from './dbanalytics/useMinWidth.js'

/**
 * Sistem Sağlığı → Veritabanı Analitiği (2026-09-26 shadcn; 2026-09-28 zenginleştirilmiş yeniden tasarım).
 *
 * Veri: GET /admin/system/db-analytics?days=1|7|30 (DbAnalyticsService.getOverview — tek payload, 60 sn önbellekli).
 * Kaynaklar: sql_query_history (SQL Playground), pg_stat_statements (VARSA — DB geneli; yoksa `summary.pgss=false` ve
 * ekranda AÇIKÇA söylenir + nasıl etkinleştirileceği), pg_stat_user_tables (tablo boyut/kullanım/bakım),
 * pg_stat_activity (bağlantı durumları, uzun süren işler), pg_stat_database (önbellek, commit/rollback, deadlock).
 * Okunamayan her kaynak "Bilinmiyor" yazar — "sorun yok" ile aynı ekrana düşmez.
 *
 * Yerleşim (parçalar `dbanalytics/*`): başlık (kaynak rozeti, son güncelleme, pencere, Yenile) → bantlar (hata,
 * pg_stat_statements yok, satır tavanı) → özet kutucukları (iki grup; tıklanınca ilgili sekme + süzgeç ya da sağlık
 * kartı) → sorgu yükü (adet + süre, ortak zaman ekseni, seri aç/kapa, eşik, tablo görünümü) → bağlantılar ve sağlık →
 * sekmeler: Tablolar / Sorgular / Başarısız / Kullanıcılar (≥ 1024 px tablo, altında kart; telefonda bölüm seçici).
 * Kartlarda sol renk şeridi YOK; tonlar rozetle.
 *
 * YARIŞ KURALI (BF2): pencere etiketi ve kova biçimi ÇİZİLEN verinin penceresinden (`summary.days`), seçiciden değil —
 * seçici yeni pencereye geçip istek uçuştayken ekrandaki eski veri yanlış etiketle çizilmesin.
 * DURUM KORUMA: sekme, SQL görünümü, her listenin arama/sıralama/sayfası panelde tutulur → Yenile ve pencere
 * değişimi bunları SIFIRLAMAZ.
 */
const TABS = ['tables', 'sql', 'failed', 'users']
const SQL_DEFAULT_SORT = {
  top: { key: 'calls', dir: 'desc' },
  slowestPgss: { key: 'avg_ms', dir: 'desc' },
  slowest: { key: 'duration_ms', dir: 'desc' },
  recent: { key: 'time', dir: 'desc' },
}

export default function DbAnalyticsPanel({ data, loading = false, error = null, days = 7, onDaysChange, onRefresh, updatedAt = null, appPool = null }) {
  const t = useT()
  // Uç bazı testlerde/eski sürümlerde boş dizi dönebiliyor → boş nesne gibi davran (bölüm çökmez, sıfırlar görünür).
  const d = useMemo(() => (Array.isArray(data) ? {} : (data || null)), [data])
  const sum = useMemo(() => d?.summary || {}, [d])
  const pgss = !!sum.pgss
  // SQL metni / hata iletisi / kullanıcı adı yalnız global yönetici + denetçiye (2026-09-28c): sayılar herkese açık.
  const sqlMasked = d?.sql_masked === true
  const dataDays = Number(sum.days) || days
  const winLbl = dataDays === 1 ? t('uact.range1d') : dataDays === 7 ? t('uact.range7d') : t('uact.range30d')
  const gran = dataDays === 1 ? 'hour' : 'day'
  const wide = useMinWidth(1024)

  const [tab, setTab] = useState('tables')
  const [sqlView, setSqlView] = useState('top')
  const [lists, setLists] = useState({})                // listKey → { q, sort, page, nonce }
  const [detail, setDetail] = useState(null)            // { type: 'sql'|'table', row, kind }
  const tabsRef = useRef(null)
  const healthRef = useRef(null)
  const pickerId = useId()

  // ── Türetimler ──
  const tables = useMemo(() => buildTables(d), [d])
  const dead = useMemo(() => deadSummary(tables), [tables])
  const maxBytes = useMemo(() => Math.max(1, ...tables.map((r) => Number(r.total_size_bytes) || 0)), [tables])
  const chart = useMemo(() => buildChart(d?.series, gran), [d, gran])
  const conn = useMemo(() => connModel(d), [d])
  const stats = d?.db_stats ?? null
  const failedRows = useMemo(() => d?.failed || [], [d])
  const users = useMemo(() => d?.top_users || [], [d])
  const sqlRows = useMemo(() => ({ top: d?.top_sql || [], slowest: d?.slowest_sql || [], recent: d?.recent_queries || [] }), [d])

  const openSql = useCallback((row, kind) => setDetail({ type: 'sql', row, kind }), [])
  const openTable = useCallback((row) => setDetail({ type: 'table', row }), [])
  const cols = useMemo(() => buildColumns({ t, pgss, maxBytes, openSql, openTable, sqlMasked }), [t, pgss, maxBytes, openSql, openTable, sqlMasked])

  const patchList = useCallback((key, patch) => setLists((s) => ({ ...s, [key]: { ...(s[key] || {}), ...patch } })), [])
  const listProps = (key) => ({ listKey: `db-${key}`, state: lists[key] || {}, onState: (p) => patchList(key, p), wide, t })

  const reveal = (ref, focus = false) => {
    setTimeout(() => {
      try { ref.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' }) } catch { /* jsdom */ }
      if (focus) try { ref.current?.focus?.({ preventScroll: true }) } catch { /* yoksay */ }
    }, 0)
  }
  /** Sekmeye git + (varsa) o listenin süzgecini sıfırla/sırala — `nonce` sayfayı 1'e çeker. */
  function goTab(next, { view, listKey, sort } = {}) {
    setTab(next)
    if (view) setSqlView(view)
    if (listKey) setLists((s) => ({ ...s, [listKey]: { q: '', sort: sort || null, page: 1, nonce: (s[listKey]?.nonce || 0) + 1 } }))
    reveal(tabsRef)
  }
  const onKpi = (key) => {
    switch (key) {
      case 'size': return goTab('tables', { listKey: 'tables', sort: { key: 'total_size', dir: 'desc' } })
      case 'dead': return goTab('tables', { listKey: 'tables', sort: { key: 'dead_pct', dir: 'desc' } })
      case 'queries': return goTab('sql', { view: 'recent', listKey: 'sql-recent' })
      case 'avg': return goTab('sql', { view: 'recent', listKey: 'sql-recent', sort: { key: 'duration_ms', dir: 'desc' } })
      case 'slowest': return goTab('sql', { view: 'slowest', listKey: 'sql-slowest' })
      case 'failed': return goTab('failed', { listKey: 'failed' })
      default: return reveal(healthRef, true)   // connections / cache → sağlık kartı
    }
  }

  const count = (n, danger = false) => (
    <Badge variant={danger ? 'destructive' : 'secondary'} className="ml-1 h-5 min-w-5 rounded-full px-1.5 text-[11px] tabular-nums">{n}</Badge>
  )
  const tabMeta = {
    tables: { label: t('db.tabTables'), n: tables.length },
    sql: { label: t('dba.tabSql'), n: null },
    failed: { label: t('db.kpiFailed'), n: failedRows.length, danger: failedRows.length > 0 },
    users: { label: t('db.tabUsers'), n: users.length },
  }
  const sqlKey = `sql-${sqlView}`
  const sqlDefault = sqlView === 'slowest' ? (pgss ? SQL_DEFAULT_SORT.slowestPgss : SQL_DEFAULT_SORT.slowest) : SQL_DEFAULT_SORT[sqlView]

  return (
    <div data-testid="db-analytics" className="@container flex min-w-0 flex-col gap-4">
      <DbHeader t={t} hasData={!!d} pgss={pgss} generatedAt={d?.generated_at} updatedAt={updatedAt} days={days}
        onDaysChange={onDaysChange} onRefresh={onRefresh} loading={loading} />
      <DbNotices t={t} error={error} loading={loading} onRefresh={onRefresh} hasData={!!d} pgss={pgss}
        truncated={!!d?.truncated} rowLimit={d?.row_limit} />

      {!d ? (loading ? <DbSkeleton label={t('dba.loading')} /> : !error && <StatusBlock tone="neutral" icon={Database} title={t('db.noRows')} />) : (
        <>
          <DbKpiGrid t={t} sum={sum} conn={{ ...conn, dbSize: d.connections?.db_size }} stats={stats} dead={dead} tables={tables}
            slowestSql={d.slowest_sql} pgss={pgss} winLbl={winLbl} onAction={onKpi} />

          <DbLoadChart t={t} chart={chart} winLbl={winLbl} />

          <DbHealthCard ref={healthRef} t={t} conn={conn} stats={stats} appPool={appPool}
            dbSize={sum.db_size || d.connections?.db_size} tableCount={sum.table_count ?? (tables.length || null)} />

          {/* Ayrıntı sekmeleri — dar KAPTA (< 36rem: telefon, kenar çubuğu açık tablet) bölüm seçici; sekme listesi
              yatay kaydırmaz ve sarmaz (TabsList sabit yükseklikli — sarınca alttaki aramanın üstüne biniyordu) */}
          <div ref={tabsRef} className="flex scroll-mt-36 flex-col gap-3 md:scroll-mt-4">
            {sqlMasked && <AlertBanner tone="info" className="mb-0">{t('dba.sqlMaskedBanner')}</AlertBanner>}
            <Tabs value={tab} onValueChange={setTab} className="gap-3">
              <div className="flex flex-col gap-1.5 @xl:hidden" data-slot="db-section-picker">
                <Label htmlFor={pickerId} className="text-xs font-normal text-muted-foreground">{t('dba.sectionPicker')}</Label>
                <NativeSelect id={pickerId} value={tab} onChange={(e) => setTab(e.target.value)} className="h-10 w-full">
                  {TABS.map((k) => (
                    <NativeSelectOption key={k} value={k}>{tabMeta[k].n != null ? `${tabMeta[k].label} (${tabMeta[k].n})` : tabMeta[k].label}</NativeSelectOption>
                  ))}
                </NativeSelect>
              </div>
              <TabsList className="hidden justify-start group-data-[orientation=horizontal]/tabs:h-auto @xl:inline-flex">
                {TABS.map((k) => (
                  <TabsTrigger key={k} value={k} data-tab={k} className="h-10 flex-none px-3 lg:h-9 pointer-coarse:h-10">
                    {tabMeta[k].label}{tabMeta[k].n != null && count(tabMeta[k].n, tabMeta[k].danger)}
                  </TabsTrigger>
                ))}
              </TabsList>

              <TabsContent value="tables">
                <DbDataList {...listProps('tables')} testId="db-tables" rows={tables} columns={cols.tables} renderCard={cols.tableCard}
                  searchKeys={['table_name', 'schema_name']} searchLabel={t('db.searchTables')}
                  defaultSort={{ key: 'total_size', dir: 'desc' }} emptyText={t('db.noRows')} />
              </TabsContent>

              <TabsContent value="sql" className="flex flex-col gap-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <SegmentedControl value={sqlView} onChange={setSqlView} ariaLabel={t('dba.sqlViewLabel')}
                    className="max-w-full flex-wrap [&_[data-slot=toggle-group-item]]:h-10 lg:[&_[data-slot=toggle-group-item]]:h-8 pointer-coarse:[&_[data-slot=toggle-group-item]]:h-10"
                    options={[
                      { value: 'top', label: t('db.secTopSql') },
                      { value: 'slowest', label: t('db.secSlowest') },
                      { value: 'recent', label: t('db.secRecent') },
                    ]} />
                  <span className={cn('text-xs text-muted-foreground', sqlView === 'recent' && 'invisible')} data-testid="db-sql-source">
                    {pgss ? t('db.srcPgss') : t('db.srcPlayground')}
                  </span>
                </div>
                <DbDataList key={sqlKey} {...listProps(sqlKey)} testId="db-sql" rows={sqlRows[sqlView]} columns={cols.sql[sqlView]}
                  renderCard={cols.sqlCard(sqlView)} searchKeys={['sql', 'username']} searchLabel={t('db.searchSql')}
                  defaultSort={sqlDefault} emptyText={t('db.noRows')} />
              </TabsContent>

              <TabsContent value="failed">
                <DbDataList {...listProps('failed')} testId="db-failed" rows={failedRows} columns={cols.failed} renderCard={cols.failedCard}
                  searchKeys={['sql', 'username', 'error']} searchLabel={t('dba.searchFailed')}
                  defaultSort={{ key: 'time', dir: 'desc' }} emptyText={t('db.kpiNone')} />
              </TabsContent>

              <TabsContent value="users">
                <DbDataList {...listProps('users')} testId="db-users" rows={users} columns={cols.users} renderCard={cols.userCard}
                  searchKeys={['username']} searchLabel={t('db.searchUsers')}
                  defaultSort={{ key: 'queries', dir: 'desc' }} emptyText={t('db.noRows')} />
              </TabsContent>
            </Tabs>
          </div>
        </>
      )}

      {detail?.type === 'sql' && <DbSqlDetail t={t} row={detail.row} kind={detail.kind} pgss={pgss} sqlMasked={sqlMasked} onClose={() => setDetail(null)} />}
      {detail?.type === 'table' && <DbTableDetail t={t} row={detail.row} onClose={() => setDetail(null)} />}
    </div>
  )
}
