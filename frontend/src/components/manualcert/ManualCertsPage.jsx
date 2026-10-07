import { lazy, Suspense, useCallback, useEffect, useMemo, useState } from 'react'
import { AlertTriangle, CalendarClock, CalendarX2, FileKey2, FileUp, Layers, Lock, RefreshCw, Search, ShieldCheck, X } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { usePagination } from '../../hooks/usePagination.js'
import { readUrlInt, readUrlParam, useUrlQuerySync } from '../../hooks/useUrlQuerySync.js'
import PageHeader from '../ui/PageHeader.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import MonitorStatsBar from '../MonitorStatsBar.jsx'
import UploadGuidance from './UploadGuidance.jsx'
import ManualCertList from './ManualCertList.jsx'
import {
  NO_TEAM, STATUS_FILTERS, downloadFromUrl, filterManualRows, manualKpis, sortManualRows, teamFilterOptions,
} from './manualCertModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'

const UploadWizard = lazy(() => import('./UploadWizard.jsx'))
const InventoryFormModalForDomain = lazy(() =>
  import('../inventory/InventoryFormModal.jsx').then((m) => ({ default: m.InventoryFormModalForDomain })))

/** Sihirbaz açma isteği (Pano / Envanter "Dosyadan sertifika ekle", derin bağlantı `?tab=manualcerts&mc_upload=1`). */
export const UPLOAD_PARAM = 'mc_upload'
const NO_TEAMS = []

/** İstek tüketildi: `mc_upload` adresten silinir (yenilemede sihirbaz yeniden açılmasın; geçmişe kayıt eklenmez). */
function dropUploadParam() {
  try {
    const url = new URL(window.location.href)
    if (!url.searchParams.has(UPLOAD_PARAM)) return
    url.searchParams.delete(UPLOAD_PARAM)
    const qs = url.searchParams.toString()
    window.history.replaceState(window.history.state, '', url.pathname + (qs ? `?${qs}` : '') + url.hash)
  } catch { /* history yok */ }
}
const KPI_FILTER = { total: 'all', healthy: 'healthy', expiring: 'expiring', expired: 'expired', versions: 'renewed' }

/**
 * MANUEL SERTİFİKALAR sayfası (`?tab=manualcerts`, 2026-10-06). Ağ üzerinden erişilemeyen (ör. OpenShift'te keystore /
 * truststore içinde duran) sertifikalar dosyadan yüklenir; süreleri ağdakilerle AYNI eşik, alarm, eskalasyon ve raporla
 * izlenir. Yerleşim: PageHeader (Yenile · Sertifika yükle) → özet kutuları (süzgeç) → "Hangi dosyayı yüklemeliyim?"
 * rehberi → araç çubuğu (arama · durum · takım) → liste (geniş kapta tablo, dar kapta kart) + sayfalama → yükleme sihirbazı.
 *
 * <p>Görünürlük envanter listesiyle aynı (kapsam sunucuda: `inventory.list` + takım); yükleme envanter ekleme yetkisi
 * (`inventory.crud` edit — sunucunun analiz kapısıyla aynı; yoksa yükleme / yeni sürüm düğmeleri çizilmez). URL: `mc_q`,
 * `mc_status`, `mc_team`, `page`/`ps`;
 * `mc_upload=1` sihirbazı açar (bir kez, adresten silinir).
 */
export default function ManualCertsPage({ systemRole, myTeams = NO_TEAMS, globalAdmin = false, onOpenCert, onInventoryChange }) {
  const t = useT()
  const toast = useToast()
  const perms = usePermissions()
  const canManage = systemRole === 'ADMIN' || systemRole === 'TEAM_ADMIN'
  // Yükleme = dosya çözümleme + envanter yazma: sunucu kapısının aynısı (inventory.crud/edit); rol tek başına yetmez
  const canUpload = perms.canEdit('inventory.crud')
  const permsLoaded = Object.keys(perms.perms || {}).length > 0

  const [rows, setRows] = useState([])
  const [loadState, setLoadState] = useState('loading')   // loading | ready | error | forbidden
  const [loadError, setLoadError] = useState(null)
  const [refreshing, setRefreshing] = useState(false)
  const [q, setQ] = useState(() => readUrlParam('mc_q', ''))
  const [status, setStatus] = useState(() => { const s = readUrlParam('mc_status', 'all'); return STATUS_FILTERS.includes(s) ? s : 'all' })
  const [team, setTeam] = useState(() => readUrlParam('mc_team', 'all'))
  const [wizard, setWizard] = useState(null)   // { renewTarget } | null
  const [pendingUpload, setPendingUpload] = useState(() => readUrlParam(UPLOAD_PARAM, null) === '1')
  const [editDomain, setEditDomain] = useState(null)
  const [teams, setTeams] = useState(myTeams)

  // Takım seçici: yöneticilere kapsamdaki takımlar, diğerlerine üyesi olduğu takımlar (Envanter ile aynı kural).
  useEffect(() => {
    if (!canManage) { setTeams(myTeams); return undefined }
    let alive = true
    Promise.resolve(api.admin?.getTeams?.()).then((r) => { if (alive && r?.success) setTeams(r.data || []) }).catch(() => {})
    return () => { alive = false }
  }, [canManage, myTeams])

  const load = useCallback(async () => {
    try {
      const res = await api.manualCerts.list()
      if (res?.success) {
        setRows(Array.isArray(res.data) ? res.data : [])
        setLoadState('ready'); setLoadError(null)
      } else if (res?.status === 403) {
        setLoadState('forbidden')
      } else if (res) {
        setLoadError(res.error || null)
        setLoadState((s) => (s === 'ready' ? s : 'error'))
        if (res.error) toast.error(res.error)
      }
    } catch (e) {
      setLoadError(e?.message || null)
      setLoadState((s) => (s === 'ready' ? s : 'error'))
    }
  }, [toast])
  useEffect(() => { load() }, []) // eslint-disable-line react-hooks/exhaustive-deps -- ilk açılış

  async function refresh() {
    setRefreshing(true)
    try { await load() } finally { setRefreshing(false) }
  }

  // Sihirbaz isteği: yükleme yetkisi kesinleşince açılır; yetki yoksa istek düşer (Envanter "Domain ekle" sinyaliyle aynı).
  useEffect(() => {
    if (!pendingUpload) return
    if (canUpload) { setWizard({ renewTarget: null }); setPendingUpload(false); dropUploadParam() }
    else if (permsLoaded) { setPendingUpload(false); dropUploadParam() }
  }, [pendingUpload, canUpload, permsLoaded])
  // Sekme zaten açıkken gelen istek (App `sm:tab-params`).
  useEffect(() => {
    const on = (e) => { if (String(e?.detail?.[UPLOAD_PARAM] ?? '') === '1') setPendingUpload(true) }
    window.addEventListener('sm:tab-params', on)
    return () => window.removeEventListener('sm:tab-params', on)
  }, [])

  const sorted = useMemo(() => sortManualRows(rows), [rows])
  const visible = useMemo(() => filterManualRows(sorted, { q, status, team }), [sorted, q, status, team])
  const kpi = useMemo(() => manualKpis(rows), [rows])
  const teamOpts = useMemo(() => teamFilterOptions(rows), [rows])
  const pager = usePagination(visible, {
    listKey: 'manual-certs', preset: 'page', resetDeps: [q, status, team],
    initialPage: readUrlInt('page', 1), initialSize: readUrlInt('ps', null),
  })
  useUrlQuerySync({
    mc_q: q.trim() || null,
    mc_status: status !== 'all' ? status : null,
    mc_team: team !== 'all' ? team : null,
    [UPLOAD_PARAM]: null,
    page: pager.page > 1 ? pager.page : null,
    ps: (pager.pageSize !== 50 || pager.page > 1) ? pager.pageSize : null,
  })
  const filtered = q.trim() !== '' || status !== 'all' || team !== 'all'
  const clearFilters = () => { setQ(''); setStatus('all'); setTeam('all') }

  const openCert = useCallback((row) => onOpenCert?.(row), [onOpenCert])
  async function downloadPem(row) {
    let versionId = row.current_version?.id ?? null
    if (versionId == null) {
      try {
        const res = await api.manualCerts.get(row.inventory_id)
        const versions = Array.isArray(res?.data?.versions) ? res.data.versions : []
        versionId = (versions.find((v) => v.current) || versions[0])?.id ?? null
      } catch { versionId = null }
    }
    if (versionId == null) { toast.error(t('mcert.err.pem')); return }
    downloadFromUrl(api.manualCerts.pemUrl(row.inventory_id, versionId))
  }

  const statItems = [
    { key: 'total', Icon: FileKey2, label: t('mcert.kpi.total'), value: kpi.total, cls: 'total', hint: t('mcert.kpi.totalHint') },
    { key: 'healthy', Icon: ShieldCheck, label: t('mcert.kpi.healthy'), value: kpi.healthy, cls: 'valid', hint: t('mcert.kpi.healthyHint') },
    { key: 'expiring', Icon: CalendarClock, label: t('mcert.kpi.expiring'), value: kpi.expiring, cls: 'warning', hint: t('mcert.kpi.expiringHint') },
    { key: 'expired', Icon: CalendarX2, label: t('mcert.kpi.expired'), value: kpi.expired, cls: 'expired', hint: t('mcert.kpi.expiredHint') },
    { key: 'versions', Icon: Layers, label: t('mcert.kpi.versions'), value: kpi.versions, cls: 'total', hint: t('mcert.kpi.versionsHint') },
  ]
  const activeKpi = Object.entries(KPI_FILTER).find(([k, v]) => v === status && k !== 'total')?.[0] ?? null
  const onStat = (key) => setStatus((s) => (key === 'total' || s === KPI_FILTER[key] ? 'all' : KPI_FILTER[key]))

  const uploadBtn = canUpload && (
    <Button type="button" data-slot="mcert-upload" className="pointer-coarse:h-10" onClick={() => setWizard({ renewTarget: null })}>
      <FileUp aria-hidden="true" />{t('mcert.upload')}
    </Button>
  )
  const empty = loadState === 'ready' && rows.length === 0

  return (
    <div data-slot="manualcerts-page" className="mb-8 min-w-0 [&_[data-slot=page-title]]:mb-0! [&_[data-slot=page-title]]:text-xl! sm:[&_[data-slot=page-title]]:text-2xl!">
      <PageHeader icon={FileKey2} title={t('mcert.title')} description={t('mcert.pageDesc')}
        meta={loadState === 'ready' ? <Badge variant="secondary" data-slot="mcert-count">{t('mcert.metaCount', rows.length)}</Badge> : null}
        actions={(
          <>
            <Button type="button" variant="outline" className="pointer-coarse:h-10 pointer-coarse:min-w-10" onClick={refresh} disabled={refreshing} aria-busy={refreshing || undefined}
              aria-label={t('mcert.refresh')} title={t('mcert.refresh')}>
              <RefreshCw aria-hidden="true" className={refreshing ? 'animate-spin motion-reduce:animate-none' : undefined} />
              <span className="hidden lg:inline">{t('mcert.refresh')}</span>
            </Button>
            {uploadBtn}
          </>
        )} />

      {loadState === 'ready' && rows.length > 0 && (
        <MonitorStatsBar items={statItems} activeFilter={activeKpi} onStatClick={onStat} />
      )}

      <UploadGuidance />

      {loadState === 'loading' ? (
        <LoadingBlock label={t('mcert.loading')} fullWidth />
      ) : loadState === 'forbidden' ? (
        <StatusBlock tone="neutral" icon={Lock} title={t('mcert.forbiddenTitle')} description={t('mcert.forbiddenBody')} />
      ) : loadState === 'error' ? (
        <StatusBlock tone="danger" icon={AlertTriangle} role="alert" title={t('mcert.loadError')} description={loadError || undefined}
          actions={<Button type="button" variant="outline" className="h-10" onClick={refresh}><RefreshCw aria-hidden="true" />{t('mcert.retry')}</Button>} />
      ) : empty ? (
        <StatusBlock tone="neutral" icon={FileKey2} className="rounded-[10px] border border-dashed"
          title={t('mcert.emptyTitle')} description={canUpload ? t('mcert.emptyBody') : t('mcert.emptyBodyRead')}
          actions={uploadBtn || null} />
      ) : (
        <>
          <div data-slot="mcert-toolbar" className="mb-3 flex min-w-0 flex-wrap items-end gap-2">
            <div className="flex w-full min-w-0 flex-col gap-1 sm:w-auto sm:flex-1 sm:max-w-sm">
              <Label htmlFor="mcert-q" className="text-xs text-muted-foreground">{t('mcert.searchLabel')}</Label>
              <InputGroup className="h-10 sm:h-9 pointer-coarse:h-10">
                <InputGroupInput id="mcert-q" type="search" data-page-search value={q} placeholder={t('mcert.search')} autoComplete="off"
                  onChange={(e) => setQ(e.target.value)} className="[&::-webkit-search-cancel-button]:hidden" />
                <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
                {q && (
                  <InputGroupAddon align="inline-end">
                    <InputGroupButton size="icon-xs" className="pointer-coarse:size-10" onClick={() => setQ('')} aria-label={t('app.clearFilter')}>
                      <X aria-hidden="true" />
                    </InputGroupButton>
                  </InputGroupAddon>
                )}
              </InputGroup>
            </div>
            <div className="flex min-w-0 flex-1 flex-col gap-1 sm:flex-none">
              <Label htmlFor="mcert-status" className="text-xs text-muted-foreground">{t('mcert.filterStatus')}</Label>
              <NativeSelect id="mcert-status" value={status} onChange={(e) => setStatus(e.target.value)} className="h-10 sm:h-9 pointer-coarse:h-10">
                {STATUS_FILTERS.map((s) => <NativeSelectOption key={s} value={s}>{t(`mcert.st.${s}`)}</NativeSelectOption>)}
              </NativeSelect>
            </div>
            {(teamOpts.options.length > 1 || teamOpts.hasNone) && (
              <div className="flex min-w-0 flex-1 flex-col gap-1 sm:flex-none">
                <Label htmlFor="mcert-team" className="text-xs text-muted-foreground">{t('mcert.filterTeam')}</Label>
                <NativeSelect id="mcert-team" value={team} onChange={(e) => setTeam(e.target.value)} className="h-10 sm:h-9 pointer-coarse:h-10">
                  <NativeSelectOption value="all">{t('mcert.allTeams')}</NativeSelectOption>
                  {teamOpts.options.map((o) => <NativeSelectOption key={o.value} value={o.value}>{o.label}</NativeSelectOption>)}
                  {teamOpts.hasNone && <NativeSelectOption value={NO_TEAM}>{t('mcert.noTeam')}</NativeSelectOption>}
                </NativeSelect>
              </div>
            )}
            <p role="status" className="m-0 w-full text-xs text-muted-foreground tabular-nums sm:ml-auto sm:w-auto sm:self-center">
              {t('mcert.shown', visible.length, rows.length)}
            </p>
          </div>
          <ManualCertList rows={pager.pageItems} onOpen={openCert} onDownload={downloadPem} canUpload={canUpload}
            onRenew={(r) => setWizard({ renewTarget: { inventory_id: r.inventory_id, domain: r.domain } })}
            emptyActions={filtered ? <Button type="button" variant="secondary" size="sm" className="pointer-coarse:h-10" onClick={clearFilters}>{t('mcert.clearFilters')}</Button> : null} />
          {pager.pageItems.length > 0 && <PaginationBar {...pager} />}
        </>
      )}

      {wizard && (
        <Suspense fallback={null}>
          <UploadWizard renewTarget={wizard.renewTarget} renewCandidates={rows} teams={teams} canOpenSettings={globalAdmin}
            onClose={() => setWizard(null)}
            onDone={() => { load(); onInventoryChange?.() }}
            onOpenCert={(target) => openCert({ ...target, cert_source: 'MANUAL' })}
            onEditInventory={canUpload ? (d) => setEditDomain(d) : undefined} />
        </Suspense>
      )}
      {editDomain && (
        <Suspense fallback={null}>
          <InventoryFormModalForDomain domain={editDomain} mode="edit" canManage={canManage} canMoveTeam={systemRole === 'ADMIN'}
            canOpenSettings={globalAdmin} onClose={() => setEditDomain(null)}
            onSaved={() => { setEditDomain(null); load(); onInventoryChange?.() }} onRefresh={load} />
        </Suspense>
      )}
    </div>
  )
}
