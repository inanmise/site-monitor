import { useCallback, useEffect, useRef, useState } from 'react'
import { ChevronDown, Copy, RefreshCw, RotateCcw, Search, ShieldAlert } from 'lucide-react'
import { useDialog } from '../ui/Dialog.jsx'
import { useToast } from '../ui/Toast.jsx'
import { api, formatDateSec } from '../../api/client'
import { useIsMobile } from '../../hooks/use-mobile.js'
import StatusBlock from '../ui/StatusBlock.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import { useServerPagination } from '../../hooks/useServerPagination.js'
import { LoadingBlock } from '../ui/Progress.jsx'
import ChangeDiffChips from './ChangeDiffChips.jsx'
import DiffTable from '../admin/audit/DiffTable.jsx'
import { ActorBadge, EventBadge, TimeAgo } from '../admin/monitorchanges/changeParts.jsx'
import { fieldLabel, formatValue, parseChanges, parseSnapshot, shortUserAgent } from './changeFields.js'
import { copyText } from '../../utils/copyText.js'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import MaskedValue from '../ui/MaskedValue.jsx'

/**
 * Bir izlemenin YAPILANDIRMA geçmişi — "kim, ne zaman, hangi IP'den, neyi değiştirdi".
 *
 * <p>{@code CheckHistoryTab}'ın kardeşi ve onunla KARIŞTIRILMAMALI: orası kontrol SONUÇLARINI
 * (hedef ayakta mıydı) gösterir, burası ayarların kendisinin nasıl değiştiğini. Sentetik izlemede
 * ayrıca bir "Sürümler" sekmesi var; o da script GÖVDESİNİN sürümleri — üçü farklı sorulara cevap
 * verir ve ayrı durmaları bilinçlidir.
 *
 * <p>2026-09-27 yeniden tasarım (shadcn + mobil web): İzleme Değişiklikleri konsoluyla AYNI satır parçaları
 * (`admin/monitorchanges/changeParts`: olay rozeti, göreli + tam zaman, aktör; alan çipleri `ChangeDiffChips`),
 * md+ ekranda shadcn Table, telefonda Card listesi (`useIsMobile` — TEK varyant). Satır açılınca ayrıntı
 * paneli: not, alan farkı (Denetim Kaydı ile aynı `audit/DiffTable`), o anki tam ayarlar (ayrı uç
 * `getChangeDetail` — liste yanıtı snapshot taşımaz), yönetebilene "eski hâline dön". Takım kapsamı dışı /
 * hata → StatusBlock (kırmızı ton) + yeniden dene. Test kancaları: satır `data-chg-row`, IP `data-slot="chg-ip"`,
 * panel `data-slot="chg-panel"`, aç/kapa `data-open-detail` (aria-expanded), snapshot `data-slot="chg-snapshot"`.
 *
 * <p>`onRestored` (isteğe bağlı, 2026-10-09): başarılı geri almadan SONRA geri alma yanıtıyla çağrılır. İzleme sayfası
 * listesini yeniden yükleyip açık detay penceresinin kopyasını tazeler — aksi halde aynı pencereden "Düzenle" bayat
 * kopyadan kurulur ve kaydetme geri alınan değerleri yeniden yazardı. Verilmezse davranış eskisiyle birebir aynı.
 */
export default function ChangeHistoryTab({ t, kind, monitorId, teamNames = {}, canManage = false, onRestored }) {
  const isMobile = useIsMobile()
  const [rows, setRows] = useState(null)
  const [total, setTotal] = useState(0)
  // Sayfalama standardı (2026-09-26): taban dönüşümü (UI 1 / API 0) kancada; başka izleme açılınca sayfa 1.
  const sp = useServerPagination({ listKey: 'monitor-change-history', preset: 'panel', resetDeps: [kind, monitorId], apiBase: 0 })
  const { apiPage: page, pageSize: size, setTotal: bindTotal } = sp
  const [openSeq, setOpenSeq] = useState(null)
  const [detail, setDetail] = useState({ seq: null, state: 'idle', data: null })
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)
  const [restoring, setRestoring] = useState(false)
  const { showNoteConfirm } = useDialog()
  const toast = useToast()

  // Fetch yarışı: sekme remount OLMADAN kayıt değişebilir (envanter çekmecesi Alt+←/→ ile kayıttan kayda geçer) ve
  // sayfa/boyut değişir; geç dönen ESKİ kaydın geçmişi yeni kaydın altına yazılmasın. Yalnız EN SON istek uygulanır.
  const loadSeq = useRef(0)
  const load = useCallback(async () => {
    const my = ++loadSeq.current
    setLoading(true)
    setOpenSeq(null)
    try {
      const res = await api.monitoring.getChanges(kind, monitorId, { page, size })
      if (my !== loadSeq.current) return   // bayat yanıt — daha yeni bir istek yolda
      if (res?.success) {
        setRows(res.data?.changes || [])
        setTotal(Number(res.data?.total) || 0)
        bindTotal(res.data?.total || 0)
        setError(null)
      } else {
        setRows([])
        setError(res?.error || t('chg.loadError'))
      }
    } catch (e) {
      if (my !== loadSeq.current) return
      setRows([])
      setError(e?.message || String(e))
    } finally {
      if (my === loadSeq.current) setLoading(false)
    }
  }, [kind, monitorId, page, size, t, bindTotal])

  useEffect(() => { load() }, [load])

  // Açılan olayın TAM detayı (snapshot) ayrı uçtan gelir — liste yanıtı onu taşımaz.
  useEffect(() => {
    if (openSeq == null) { setDetail({ seq: null, state: 'idle', data: null }); return undefined }
    let alive = true
    setDetail({ seq: openSeq, state: 'loading', data: null })
    Promise.resolve()
      .then(() => api.monitoring.getChangeDetail(kind, monitorId, openSeq))
      .then(r => { if (alive) setDetail({ seq: openSeq, state: r?.success ? 'ok' : 'error', data: r?.success ? r.data : null }) })
      .catch(() => { if (alive) setDetail({ seq: openSeq, state: 'error', data: null }) })
    return () => { alive = false }
  }, [kind, monitorId, openSeq])

  const sel = openSeq == null ? null : (rows || []).find(r => r.seq === openSeq) || null

  /**
   * Seçili anın ayarlarına geri döner. Onay ZORUNLU ve gerekçe notu istenir: bu bir yazma
   * işlemi ve geçmişte "kim neden geri aldı" satırı olarak duracak.
   */
  async function restore() {
    if (!sel) return
    const res = await showNoteConfirm({
      title: t('chg.restoreTitle'),
      message: t('chg.restoreConfirm', sel.seq),
      confirmText: t('chg.restoreAction'),
      noteLabel: t('chg.changeNote'),
    })
    if (!res?.confirmed) return
    setRestoring(true)
    try {
      const r = await api.monitoring.restoreChange(kind, monitorId, sel.seq, res.note)
      if (!r?.success) { toast.error(r?.error || t('chg.restoreError')); return }
      const skipped = r.data?.skipped_masked || []
      toast.success(t('chg.restoreDone', (r.data?.fields || []).length))
      // Atlanan gizli alanlar SESSİZ geçilmez: kullanıcı parolanın dönmediğini bilmeli.
      if (skipped.length) toast.info(t('chg.restoreMasked', skipped.length))
      load()
      // Sayfanın açık detay kopyası da tazelensin (bkz. bileşen notu). Sayfanın hatası geri alma sonucunu bozmaz.
      if (onRestored) {
        try { Promise.resolve(onRestored(r.data)).catch(() => {}) } catch { /* sayfanın tazelemesi — en iyi çaba */ }
      }
    } finally {
      setRestoring(false)
    }
  }

  const toggle = (seq) => setOpenSeq(prev => (prev === seq ? null : seq))
  const now = Date.now()

  if (rows === null && !error) return <LoadingBlock label={t('modal.loading')} />

  const panel = (r) => (
    <ChangePanel t={t} row={r} detail={detail.seq === r.seq ? detail : null} teamNames={teamNames}
      canManage={canManage} restoring={restoring} onRestore={restore} />
  )

  let body
  if (error && (!rows || rows.length === 0)) {
    body = (
      <StatusBlock tone="danger" icon={ShieldAlert} title={t('chg.loadError')} description={error}
        className="rounded-lg border border-dashed"
        actions={<Button type="button" variant="outline" className="h-10" onClick={() => load()}><RefreshCw aria-hidden="true" /> {t('chg.retry')}</Button>} />
    )
  } else if (rows.length === 0) {
    body = (
      <StatusBlock tone="neutral" icon={Search} title={t('chg.emptyTitle')} description={t('chg.emptyText')}
        className="rounded-lg border border-dashed" />
    )
  } else if (isMobile) {
    body = (
      <ul data-slot="chg-rows" aria-busy={loading || undefined}
        className={cn('m-0 flex list-none flex-col gap-2 p-0 transition-opacity', loading && 'opacity-60')}>
        {rows.map(r => {
          const open = openSeq === r.seq
          return (
            <li key={r.seq} data-chg-row={r.seq} className="min-w-0">
              <Card data-state={open ? 'selected' : undefined} className="min-w-0 gap-2 px-3 py-3 shadow-none data-[state=selected]:border-primary/50">
                <div className="flex min-w-0 flex-wrap items-center gap-2 text-xs">
                  <EventBadge t={t} ev={r.event_type} />
                  <TimeAgo at={r.at} t={t} now={now} className="text-muted-foreground" />
                  <span className="ml-auto text-muted-foreground tabular-nums">#{r.seq}</span>
                </div>
                <div className="flex min-w-0 items-center gap-2 text-xs"><ActorBadge r={r} t={t} /></div>
                {r.changes && <ChangeDiffChips t={t} changes={r.changes} teamNames={teamNames} limit={3} wrap />}
                {r.note && <p data-slot="chg-row-note" className="m-0 line-clamp-2 text-xs text-muted-foreground italic">{r.note}</p>}
                <Button type="button" variant="outline" size="sm" data-open-detail="" aria-expanded={open}
                  aria-label={t('a11y.rowAction', `#${r.seq}`, open ? t('chg.hideDetails') : t('chg.details'))}
                  className="h-10 w-full" onClick={() => toggle(r.seq)}>
                  <ChevronDown aria-hidden="true" className={cn('transition-transform motion-reduce:transition-none', open && 'rotate-180')} />
                  {open ? t('chg.hideDetails') : t('chg.details')}
                </Button>
                {open && panel(r)}
              </Card>
            </li>
          )
        })}
      </ul>
    )
  } else {
    const TH = 'h-9 px-3 text-[0.75em] font-semibold tracking-wide text-muted-foreground uppercase'
    const TD = 'px-3 py-2 align-top'
    body = (
      <div data-slot="chg-rows" aria-busy={loading || undefined}
        className={cn('overflow-hidden rounded-lg border bg-card transition-opacity', loading && 'opacity-60')}>
        <Table className="text-[0.85em]">
          <TableHeader className="bg-muted/50">
            <TableRow className="hover:bg-transparent">
              <TableHead className={cn(TH, 'w-px')}>{t('chg.colTime')}</TableHead>
              <TableHead className={TH}>{t('chg.colUser')}</TableHead>
              <TableHead className={cn(TH, 'w-px')}>{t('chg.colAction')}</TableHead>
              <TableHead className={TH}>{t('chg.colChanges')}</TableHead>
              <TableHead className={cn(TH, 'hidden w-px lg:table-cell')}>{t('chg.colIp')}</TableHead>
              <TableHead className={cn(TH, 'w-10 px-1')}><span className="sr-only">{t('chg.details')}</span></TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map(r => {
              const open = openSeq === r.seq
              const rowLabel = t('a11y.rowAction', `#${r.seq}`, open ? t('chg.hideDetails') : t('chg.details'))
              return [
                <TableRow key={r.seq} data-chg-row={r.seq} tabIndex={0} aria-expanded={open}
                  data-state={open ? 'selected' : undefined}
                  className="cursor-pointer outline-none data-[state=selected]:bg-primary/5 focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-primary"
                  onClick={(e) => { if (e.currentTarget.contains(e.target)) toggle(r.seq) }}
                  onKeyDown={(e) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); toggle(r.seq) } }}>
                  <TableCell className={cn(TD, 'whitespace-nowrap text-muted-foreground')}>
                    <span className="flex flex-col gap-0.5">
                      <TimeAgo at={r.at} t={t} now={now} className="font-medium text-foreground" />
                      <time dateTime={r.at} className="text-[0.85em] tabular-nums">{formatDateSec(r.at)}</time>
                    </span>
                  </TableCell>
                  <TableCell className={cn(TD, 'max-w-[14rem]')}>
                    <span className="flex min-w-0 flex-col gap-0.5">
                      <ActorBadge r={r} t={t} />
                      {r.user_agent && <span className="truncate text-[0.8em] text-muted-foreground" title={r.user_agent}>{shortUserAgent(r.user_agent)}</span>}
                    </span>
                  </TableCell>
                  <TableCell className={TD}><EventBadge t={t} ev={r.event_type} /></TableCell>
                  {/* `w-full max-w-0`: otomatik tablo düzeninde hücre yalnız ARTAN genişliği alır (çipler tabloyu taşırmasın) */}
                  <TableCell className={cn(TD, 'w-full max-w-0 min-w-[12rem] whitespace-normal')}>
                    <span className="flex min-w-0 flex-col gap-1">
                      {r.changes ? <ChangeDiffChips t={t} changes={r.changes} teamNames={teamNames} limit={3} wrap /> : <span className="text-muted-foreground">—</span>}
                      {r.note && <span data-slot="chg-row-note" className="line-clamp-2 text-[0.9em] text-muted-foreground italic">{r.note}</span>}
                    </span>
                  </TableCell>
                  <TableCell className={cn(TD, 'hidden lg:table-cell')}><IpCopy t={t} ip={r.ip_address} masked={r.identity_masked === true} /></TableCell>
                  <TableCell className="w-10 px-1 align-top">
                    <Button type="button" variant="ghost" size="icon-sm" data-open-detail="" aria-expanded={open} aria-label={rowLabel}
                      className="text-muted-foreground" onClick={(e) => { e.stopPropagation(); toggle(r.seq) }}>
                      <ChevronDown aria-hidden="true" className={cn('transition-transform motion-reduce:transition-none', open && 'rotate-180')} />
                    </Button>
                  </TableCell>
                </TableRow>,
                open && (
                  <TableRow key={`${r.seq}-panel`} className="bg-muted/20 hover:bg-muted/20">
                    <TableCell colSpan={6} className="p-3">{panel(r)}</TableCell>
                  </TableRow>
                ),
              ]
            })}
          </TableBody>
        </Table>
      </div>
    )
  }

  return (
    <div data-slot="change-history" className="chg-tab flex min-w-0 flex-col gap-3">
      <div className="flex min-w-0 flex-wrap items-center gap-2 text-xs text-muted-foreground">
        <span data-slot="chg-total" className="tabular-nums">{t('chg.countTotal', total.toLocaleString())}</span>
        <span className="ml-auto" />
        <SimpleTooltip content={t('app.refresh')}>
          <Button type="button" variant="outline" size="icon-sm" className="pointer-coarse:size-10" aria-label={t('app.refresh')}
            aria-busy={loading || undefined} onClick={() => load()}>
            <RefreshCw aria-hidden="true" className={loading ? 'animate-spin motion-reduce:animate-none' : undefined} />
          </Button>
        </SimpleTooltip>
      </div>

      {body}

      {/* Sunucu sayfalaması: useServerPagination. TABAN DÖNÜŞÜMÜ kancada (API 0-tabanlı `apiPage`, çubuk
          1-tabanlı `sp.bar`). Elle `page + 1` yazılırken state=0'da hiçbir sayfa aktif görünmüyor ve "1"
          API'nin 2. sayfasına gidiyordu (S6, paginationBase.test.js). */}
      {rows && rows.length > 0 && <PaginationBar {...sp.bar} />}
    </div>
  )
}

/** IP adresi + kopyala — GERÇEK düğme (satır tıklamasına sızmaz); erişilebilir ad IP'yi taşır. `masked`: IP sunucuda
 *  bu görüntüleyici için düşürüldü (satır `identity_masked`, 2026-09-28c) → "Gizli". */
function IpCopy({ t, ip, masked = false }) {
  if (masked && !ip) return <MaskedValue />
  if (!ip) return <span className="text-muted-foreground">—</span>
  return (
    <Button type="button" variant="ghost" size="xs" data-slot="chg-ip" title={t('chg.ipTitle')}
      aria-label={`${ip} — ${t('chg.ipTitle')}`}
      className="h-auto gap-1 px-1 py-0.5 font-mono text-[.85em] font-normal text-muted-foreground hover:bg-transparent hover:text-primary pointer-coarse:min-h-10 dark:hover:bg-transparent"
      onClick={(e) => { e.stopPropagation(); copyText(ip) }}>
      {ip}<Copy className="size-3" aria-hidden="true" />
    </Button>
  )
}

/** Tam değer: çip biçimi 120 karakterde kırpar (`formatValue`); ayrıntıda metin kırpılmaz. */
function fullValue(key, value, ctx) {
  if (typeof value === 'string' && value.length > 120) return value
  return formatValue(key, value, ctx)
}

function SnapshotList({ t, items, teamNames }) {
  return (
    <dl data-slot="chg-snapshot" className="chg-snapshot m-0 grid grid-cols-1 gap-x-5 gap-y-1.5 sm:grid-cols-2">
      {items.map(f => (
        <div key={f.key} className="flex min-w-0 flex-col gap-0.5 border-b border-dashed pb-1.5">
          <dt className="text-xs text-muted-foreground">{fieldLabel(t, f.key)}</dt>
          <dd className="m-0 min-w-0 text-sm break-words [overflow-wrap:anywhere]">{fullValue(f.key, f.value, { t, teamNames })}</dd>
        </div>
      ))}
    </dl>
  )
}

/**
 * Açılan satırın ayrıntısı: künye (kayıt no, tam zaman, IP, tarayıcı), değişiklik nedeni notu (tam çerçeveli kutu —
 * sol renk şeridi YOK), alan farkı (DiffTable) ve o anki tam ayarlar. Oluşturma/geri alma gibi farksız olaylarda
 * snapshot doğrudan açık ("ilk değerler"); düzenlemede fark önce, tam ayarlar katlanır bölümde.
 */
function ChangePanel({ t, row: r, detail, teamNames, canManage, restoring, onRestore }) {
  const [stateOpen, setStateOpen] = useState(false)
  const diff = parseChanges(r.changes)
  const ctx = { t, teamNames }
  const snapshotItems = detail?.state === 'ok' ? parseSnapshot(detail.data?.snapshot) : []
  const snapshotTitle = r.event_type === 'CREATE' ? t('chg.initialValues')
    : r.event_type === 'DELETE' ? t('chg.stateBeforeDelete') : t('chg.stateAfter')
  const snapshotBody = !detail || detail.state === 'loading' ? (
    <div className="flex flex-col gap-2" aria-busy="true">
      <span className="sr-only" role="status">{t('modal.loading')}</span>
      <Skeleton className="h-4 w-2/3" /><Skeleton className="h-4 w-1/2" /><Skeleton className="h-4 w-3/5" />
    </div>
  ) : detail.state === 'error' ? (
    <p className="m-0 text-sm text-muted-foreground">{t('chg.snapshotError')}</p>
  ) : snapshotItems.length === 0 ? (
    <p className="m-0 text-sm text-muted-foreground">—</p>
  ) : <SnapshotList t={t} items={snapshotItems} teamNames={teamNames} />

  return (
    <div data-slot="chg-panel" className="chg-detail flex min-w-0 flex-col gap-3 text-sm">
      <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
        <span className="font-semibold text-foreground tabular-nums">{t('chg.detailRecord')} #{r.seq}</span>
        <time dateTime={r.at} className="tabular-nums">{formatDateSec(r.at)}</time>
        {(r.ip_address || r.identity_masked === true) && <IpCopy t={t} ip={r.ip_address} masked={r.identity_masked === true} />}
        {r.user_agent && <span className="truncate" title={r.user_agent}>{shortUserAgent(r.user_agent)}</span>}
        {/* Geri döndürme yalnız YÖNETEBİLENE ve durum kaydı olan olaylarda çıkar —
            düğmenin görünüp 403 vermesi kullanıcıyı boşuna umutlandırırdı. */}
        {canManage && detail?.state === 'ok' && detail.data?.snapshot && (
          <Button type="button" variant="secondary" size="sm" className="chg-restore-btn ml-auto pointer-coarse:h-10"
            disabled={restoring} aria-busy={restoring || undefined} onClick={onRestore}>
            <RotateCcw aria-hidden="true" /> {restoring ? t('chg.restoring') : t('chg.restoreAction')}
          </Button>
        )}
      </div>

      {r.note && (
        <section className="rounded-md border bg-muted/40 px-3 py-2">
          <h4 className="m-0 mb-0.5 text-xs font-semibold text-muted-foreground">{t('chg.noteTitle')}</h4>
          <p className="m-0 text-sm break-words">{r.note}</p>
        </section>
      )}

      {diff.length > 0 ? (
        <section className="flex min-w-0 flex-col gap-1.5">
          <h4 className="m-0 text-xs font-semibold text-muted-foreground">{t('chg.diffTitle')} <span className="font-normal tabular-nums">({diff.length})</span></h4>
          <DiffTable className="w-full" fieldLabel={t('audit.diffField')} fromLabel={t('audit.diffFrom')} toLabel={t('audit.diffTo')}
            rows={diff.map(d => [d.key, fieldLabel(t, d.key), fullValue(d.key, d.from, ctx), fullValue(d.key, d.to, ctx)])} />
        </section>
      ) : (
        r.event_type !== 'CREATE' && r.event_type !== 'DELETE' && (
          <p className="m-0 text-xs text-muted-foreground">{t('chg.noDiff')}</p>
        )
      )}

      {/* Tam ayarlar: fark yoksa (oluşturma/silme) doğrudan açık; düzenlemede katlanır — fark asıl bilgi. */}
      {diff.length === 0 ? (
        <section className="flex min-w-0 flex-col gap-1.5">
          <h4 className="m-0 text-xs font-semibold text-muted-foreground">{snapshotTitle}</h4>
          {snapshotBody}
        </section>
      ) : (
        <Collapsible open={stateOpen} onOpenChange={setStateOpen} className="flex min-w-0 flex-col gap-2">
          <CollapsibleTrigger asChild>
            <Button type="button" variant="outline" size="sm" className="h-9 self-start pointer-coarse:h-10">
              <ChevronDown aria-hidden="true" className={cn('transition-transform motion-reduce:transition-none', stateOpen && 'rotate-180')} />
              {stateOpen ? t('chg.hideFullState') : snapshotTitle}
            </Button>
          </CollapsibleTrigger>
          <CollapsibleContent>{snapshotBody}</CollapsibleContent>
        </Collapsible>
      )}
    </div>
  )
}
