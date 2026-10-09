import { useCallback, useMemo, useState } from 'react'
import { Download, ExternalLink, ListChecks, RefreshCw, SearchX, ShieldAlert, UserCheck, UserX } from 'lucide-react'
import { useT } from '../../../../i18n/index.jsx'
import { formatDateSec } from '../../../../api/client'
import ModalShell from '../../../ui/ModalShell.jsx'
import PaginationBar from '../../../ui/PaginationBar.jsx'
import StatusBlock from '../../../ui/StatusBlock.jsx'
import AlertBanner from '../../../ui/AlertBanner.jsx'
import { LoadingBlock, Spinner } from '../../../ui/Progress.jsx'
import { usePagination } from '../../../../hooks/usePagination.js'
import { navigateTo } from '../../../../utils/navigate.js'
import { downloadCsv, stampedName } from '../../../../utils/csvExport.js'
import { PHONE_MAX, WIDE_MIN, useViewportWidth } from '../useViewportWidth.js'
import {
  DEFAULT_SORT, EMPTY_FILTERS, bulkWizardParams, dormantCsv, dormantMatches, dormantStats, enrichDormant, facetOptions,
  sortDormant, usersPageParams,
} from './dormantModel.js'
import DormantStats from './DormantStats.jsx'
import DormantToolbar, { DormantChips, DormantSortSelect, useDormantLabels } from './DormantToolbar.jsx'
import DormantList from './DormantList.jsx'
import { useDormantList } from './useDormantList.js'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'

/**
 * Atıl hesaplar (2026-10-09 yeniden tasarım, kullanıcı isteği: "Dormant accounts sayfasını mevcut fonksiyonları koruyarak
 * yeniden shadcn ile tasarlayalım. mweb responsive yapıda olsun. istatistiklerimizi sunalım. zenginleştirelim").
 * Sistem Sağlığı → Kullanıcı / Oturum → "Atıl hesap" KPI kartıyla açılır (eskiden KpiDetailModal'ın bir dalıydı; diğer
 * KPI ayrıntıları orada aynen kalır).
 *
 * Düzen (mobil-önce, Kullanıcı Dizini ile aynı dağarcık): sabit boyutlu pencere, YALNIZ gövde kayar → açıklama →
 * (liste kırpıldıysa bant) → istatistikler (kutucuklar + dağılım kartları, hepsi süzgeç düğmesi) → sonraki adım
 * (yönetici) → YAPIŞKAN süzgeç çubuğu + etkin süzgeç çipleri → sonuç satırı → liste (lg+ tablo, altında kart) →
 * sayfalama. Satır / kart kullanıcının oturum / kullanıcı detayını açar (`onUser`, mevcut detay penceresi).
 *
 * Sonraki adım: GLOBAL yöneticiye "Toplu pasife almada incele" — mevcut sihirbazı süzgece uygun ölçütle ÖN DOLDURARAK
 * açar (Kullanıcılar sayfası `g_bd*` paramları); bu görünüm HİÇBİR hesabı kendisi değiştirmez, sihirbaz önizleme +
 * sayıyı yazarak onay ister. Yöneticiye (kapsamlı dahil) "Kullanıcılar listesinde aç" (`g_dormant` süzgeci).
 *
 * Veri: yoklanan özet (SystemHealth 30 sn) atıl satırları yalnız ilk 500'e kadar taşır — Sistem Sağlığı'nın HER
 * bölümünde yoklandığı için yük hafif kalır. TAM liste (≤ 5000) pencere açılınca BİR KEZ ve Yenile / Yeniden dene ile
 * `useDormantList` üzerinden istenir (yoklama yok, istekler üst üste binmez, kapanıştan sonra gelen yanıt yok sayılır);
 * gelene kadar / gelmezse özetin satırları gösterilir. Süzgeç / sıralama / sayfa pencerede tutulur, yenilemede KORUNUR.
 * Satırlar kimlik izi (IP / konum / tarayıcı) taşımaz; kullanıcı kimliği global olmayan görüntüleyicide sunucuda
 * opaklaşır (UserRefWire) — burada sayıya çevrilmez.
 */
export default function DormantAccountsModal({
  data, error = false, isAdmin = false, globalAdmin = false, refreshing = false, onClose, onUser, onRefresh,
}) {
  const t = useT()
  const width = useViewportWidth()
  const wide = width >= WIDE_MIN
  const phone = width < PHONE_MAX

  const [f, setF] = useState(() => ({ ...EMPTY_FILTERS }))
  const patch = useCallback((p) => setF((x) => ({ ...x, ...p })), [])
  const clearAll = useCallback(() => setF({ ...EMPTY_FILTERS }), [])
  const [sort, setSort] = useState(DEFAULT_SORT)

  // TAM liste açılışta bir kez istenir (useDormantList — yoklama yok, üst üste binmez, bayat yanıt ezmez). Gelene kadar
  // ve gelmezse yoklanan özetin ilk satırları (≤ 500) gösterilir; istatistik / süzgeç / CSV / toplu pasife alma
  // bağlantısı her zaman EKRANDAKİ listeden.
  const full = useDormantList()
  const polledRows = data?.details?.dormant
  const rawRows = useMemo(() => (full.data ? full.data.rows : (polledRows || [])), [full.data, polledRows])
  const meta = full.data ? full.data.meta : data?.details?.dormant_meta
  const generatedAt = full.data?.generated_at || data?.generated_at
  const previewOnly = !full.data
  // "Şimdi" yük başına sabit (yedek gün hesabı iki çizim arasında kaymasın); sunucu `inactive_days` öncelikli.
  const now = useMemo(() => (rawRows ? Date.now() : 0), [rawRows])
  const all = useMemo(() => enrichDormant(rawRows, now), [rawRows, now])
  const stats = useMemo(() => dormantStats(all, data?.summary?.total_users), [all, data])
  const options = useMemo(() => facetOptions(all, f), [all, f])
  const rows = useMemo(() => sortDormant(all.filter((r) => dormantMatches(r, f)), sort), [all, f, sort])
  const pager = usePagination(rows, { listKey: 'uact-dormant', preset: 'modal', resetDeps: [f, sort] })
  const refreshAll = () => { onRefresh?.(); full.reload() }

  const sourceLabel = useCallback((k) => (k === 'LDAP' ? 'LDAP' : k === 'LOCAL' ? t('usr.authLocal') : String(k)), [t])
  const roleLabel = useCallback((k) => String(k), [])
  const daysText = useCallback((n) => (n === 1 ? t('dorm.dayOne') : t('dorm.daysShort', n)), [t])
  const labels = useDormantLabels(options, { roleLabel, sourceLabel })

  const touch = 'h-10 sm:pointer-fine:h-9'
  // Yenile: tam listeyi yeniden ister (+ panel özetini); istek yoldayken kapalı — üst üste istek yok.
  const busy = refreshing || full.loading
  const refreshBtn = (
    <div className="ml-auto flex shrink-0 items-center">
      <Button type="button" variant="ghost" size="icon-sm" className="-my-1 text-muted-foreground pointer-coarse:size-10" data-slot="dormant-refresh"
        onClick={refreshAll} disabled={busy} aria-busy={busy || undefined} aria-label={t('uact.refresh')} title={t('uact.refresh')}>
        <RefreshCw aria-hidden="true" className={cn(busy && 'animate-spin motion-reduce:animate-none')} />
      </Button>
    </div>
  )

  return (
    <ModalShell open onClose={onClose} icon={UserX} size="xl" scrollBody headerExtra={refreshBtn}
      title={<span data-slot="dormant-title" className="min-w-0 truncate">{`${t('uact.dormant')} · ${rows.length}${rows.length !== all.length ? ' / ' + all.length : ''}`}</span>}
      // SABİT BOYUT (Kullanıcı Dizini deseni): süzgeç değişince pencere zıplamasın; telefonda neredeyse tam ekran.
      className={cn('max-w-[calc(100%-1rem)] gap-3 p-4 sm:gap-4 sm:p-6',
        'h-[calc(100dvh-1rem)] max-h-[calc(100dvh-1rem)] sm:h-[min(88vh,calc(100dvh-2rem))] sm:max-h-[min(88vh,calc(100dvh-2rem))] sm:w-full',
        '[&_[data-slot=modal-shell-body]]:[scrollbar-gutter:stable]')}
      footer={<div className="flex w-full gap-2 sm:w-auto">
        <Button type="button" variant="secondary" className={cn(touch, 'min-w-0 flex-1 sm:flex-none')} disabled={!rows.length}
          data-slot="dormant-export" onClick={() => downloadCsv(stampedName('dormant-accounts'), dormantCsv(rows, t))}>
          <Download size={14} aria-hidden="true" /> <span className="truncate">{t('dorm.export')}</span>
        </Button>
        <Button type="button" className={cn(touch, 'min-w-0 flex-1 sm:flex-none')} onClick={onClose}>{t('app.dismiss')}</Button>
      </div>}>
      {!data && !full.data ? (
        (error || full.error) && !full.loading
          ? <StatusBlock tone="danger" icon={ShieldAlert} title={t('uact.loadError')}
              actions={<Button type="button" variant="secondary" className={touch} onClick={refreshAll}>{t('uact.refresh')}</Button>} />
          : <LoadingBlock label={t('app.loading')} />
      ) : (
        <div className="flex min-w-0 flex-col gap-3">
          <p className="m-0 flex flex-col gap-0.5 text-xs text-muted-foreground sm:flex-row sm:flex-wrap sm:gap-x-3">
            <span>{t('uact.dormantHint')}</span>
            {generatedAt && <span className="tabular-nums">{t('uact.dataAsOf', formatDateSec(generatedAt))}</span>}
          </p>
          {previewOnly && full.loading && (
            <p data-slot="dormant-loading" role="status" className="m-0 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs text-muted-foreground">
              <Spinner size={14} inline decorative />
              <span className="font-medium text-foreground">{t('dorm.loadingFull')}</span>
              {meta?.truncated && <span>{t('dorm.previewNote', all.length, meta.total ?? all.length)}</span>}
            </p>
          )}
          {full.error && !full.loading && (
            <div data-slot="dormant-load-error">
              <AlertBanner tone="danger" role="alert" className="mb-0" title={t('dorm.loadFailed')}
                actions={<Button type="button" variant="secondary" size="sm" className="h-10 sm:pointer-fine:h-8" onClick={() => full.reload()}>
                  <RefreshCw aria-hidden="true" /> {t('dorm.retry')}
                </Button>}>
                {typeof full.error === 'string' ? full.error : t('dorm.loadFailedHint')}
              </AlertBanner>
            </div>
          )}
          {meta?.truncated && !(previewOnly && full.loading) && (
            <div data-slot="dormant-truncated">
              <AlertBanner tone="warning" className="mb-0">{t('dorm.truncated', meta.cap ?? all.length, meta.total ?? all.length)}</AlertBanner>
            </div>
          )}

          {all.length === 0 && previewOnly && full.loading ? (
            <LoadingBlock label={t('dorm.loadingFull')} />
          ) : all.length === 0 ? (
            <StatusBlock tone="success" icon={UserCheck} title={t('dorm.emptyTitle')} description={t('uact.noDormant')} />
          ) : (
            <>
              <DormantStats stats={stats} f={f} onPatch={patch} onClearAll={clearAll} roleLabel={roleLabel} sourceLabel={sourceLabel} daysText={daysText}
                defaultBreakdownOpen={!phone} />

              <NextSteps f={f} isAdmin={isAdmin} globalAdmin={globalAdmin} onClose={onClose} sourceLabel={sourceLabel} />

              <div data-slot="dormant-filterbar" className="sticky top-0 z-20 -mx-1 flex flex-col gap-2 border-b bg-background px-1 pt-1 pb-2.5">
                <DormantToolbar f={f} onPatch={patch} onClearAll={clearAll} options={options} labels={labels}
                  compact={!wide} phone={phone} sort={sort} onSort={setSort} shown={{ count: rows.length, total: all.length }} />
                <DormantChips f={f} labels={labels} onPatch={patch} onClearAll={clearAll} />
              </div>

              <div className="flex items-center justify-between gap-3 text-xs text-muted-foreground">
                <span aria-live="polite" data-slot="dormant-count" className="font-medium whitespace-nowrap text-foreground tabular-nums">{t('udir.resultCount', rows.length, all.length)}</span>
                {!wide && rows.length > 1 && <DormantSortSelect sort={sort} onSort={setSort} className="max-w-[14rem] sm:max-w-none" />}
              </div>

              {rows.length === 0 ? (
                <StatusBlock tone="neutral" icon={SearchX} title={t('udir.noMatchTitle')} description={t('udir.noMatchDesc')}
                  actions={<Button type="button" variant="secondary" className={touch} onClick={clearAll}>{t('uact.filterClear')}</Button>} />
              ) : (
                <DormantList wide={wide} rows={pager.pageItems} sort={sort} onSort={setSort} onOpen={(r) => onUser?.(r)}
                  roleLabel={roleLabel} daysText={daysText} />
              )}
              <PaginationBar {...pager} />
            </>
          )}
        </div>
      )}
    </ModalShell>
  )
}

/**
 * Sonraki adım (yalnız yönetici): global yönetici → toplu pasife alma sihirbazı (süzgece uygun ön doldurulmuş ölçüt,
 * önizleme + onay sihirbazda); her yönetici → Kullanıcılar listesi "girmeyen" süzgeciyle. Bu kart hiçbir hesabı
 * değiştirmez; yalnız gezinir.
 */
function NextSteps({ f, isAdmin, globalAdmin, onClose, sourceLabel }) {
  const t = useT()
  if (!isAdmin && !globalAdmin) return null
  const bulk = bulkWizardParams(f)
  const users = usersPageParams(f)
  const teamCount = bulk.g_bd_teams ? bulk.g_bd_teams.split(',').length : 0
  const criteria = [
    t('dorm.crit.days', bulk.g_bd_days),
    bulk.g_bd_never === '1' && t('dorm.crit.never'),
    bulk.g_bd_src && t('dorm.crit.source', sourceLabel(bulk.g_bd_src)),
    bulk.g_bd_role && t('dorm.crit.role', bulk.g_bd_role),
    teamCount > 0 && t('dorm.crit.teams', teamCount),
  ].filter(Boolean).join(' · ')
  const usersLabel = { 30: t('usr.dormant30'), 90: t('usr.dormant90'), 180: t('usr.dormant180'), never: t('usr.dormantNever') }[users.g_dormant]
  const go = (params) => { onClose?.(); navigateTo('admin', params) }
  const btn = 'h-10 w-full sm:w-auto sm:pointer-fine:h-9'
  return (
    <Card data-slot="dormant-next" className="min-w-0 gap-3 px-4 py-3 shadow-xs">
      <div className="flex min-w-0 items-start gap-2.5">
        <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-primary/10 text-primary"><ListChecks className="size-4" aria-hidden="true" /></span>
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <h4 className="m-0 text-sm font-semibold">{t('dorm.nextTitle')}</h4>
          {globalAdmin && <p className="m-0 text-xs leading-relaxed text-muted-foreground" data-slot="dormant-next-bulk-desc">{t('dorm.nextBulkDesc', criteria)}</p>}
          <p className="m-0 text-xs leading-relaxed text-muted-foreground">{t('dorm.nextUsersDesc', usersLabel)}</p>
        </div>
      </div>
      <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:justify-end">
        <Button type="button" variant="outline" className={btn} data-slot="dormant-open-users" onClick={() => go(users)}>
          <ExternalLink aria-hidden="true" /> {t('dorm.nextUsers')}
        </Button>
        {globalAdmin && (
          <Button type="button" variant="outline" data-slot="dormant-open-bulk" onClick={() => go(bulk)}
            className={cn(btn, 'border-destructive/40 text-destructive hover:bg-destructive/10 hover:text-destructive')}>
            <UserX aria-hidden="true" /> {t('dorm.nextBulk')}
          </Button>
        )}
      </div>
    </Card>
  )
}
