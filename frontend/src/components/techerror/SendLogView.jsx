import { useCallback, useEffect, useRef, useState } from 'react'
import { CircleAlert, EyeOff, Inbox, RefreshCw, X } from 'lucide-react'
import { api } from '../../api/client'
import { useT, useLanguage } from '../../i18n/index.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import { useElementWidth } from '../../hooks/useElementWidth.js'
import { useServerPagination } from '../../hooks/useServerPagination.js'
import { readUrlParam, useUrlQuerySync } from '../../hooks/useUrlQuerySync.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { cn } from '@/lib/utils'
import { fmtDateTime, periodLabel } from './TechErrorParts.jsx'
import {
  SENDLOG_KINDS, SENDLOG_KIND_KEYS, SENDLOG_SIZE_OPTIONS, SENDLOG_STATUSES, SENDLOG_STATUS_KEYS, SENDLOG_STATUS_TONE, TE_SLK, TE_SLP, TE_SLPS,
  TE_SLS, normalizeSendLogKind, normalizeSendLogStatus, sendLogKindOf, sendLogMore, sendLogReasonKey, sendLogRoleKey, sendLogStatusOf,
} from './sendModel.js'

/** Tablo ↔ kart eşiği: LİSTE KABININ genişliği (görünüm alanı değil; jsdom 0 → tablo). */
export const SENDLOG_TABLE_MIN_PX = 760

/**
 * Kanalın GÖNDERİM GÜNLÜĞÜ (R18 / E22, 2026-10-18 — kullanıcı onayı): `te_tab=sendlog`. Rapor gönderimi, kendine test,
 * onay ve hatırlatma postalarının `notification_logs` satırları — en yeni önce, sunucu sayfalı (`te_slp` / `te_slps`),
 * tür (`te_slk`) ve durum sınıfı (`te_sls`) süzgeçli. Kap ≥ 760 px tablo, altında kart; alıcılar sararak yazılır
 * (kırpılmaz). Günlük kolonu (255 karakter) sığmayan adresleri " … +N" olarak saklar: sunucu eki ayırır, burada
 * "+N adres daha" yazılır — "tam liste" denmez. Ham durum metni (posta sunucusunun iletisi) gelmez; yalnız sebep kodu
 * (`reason`) dilinde yazılır. Yalnız paylaşımla okuyan adresleri değil sayıları görür (sunucu `to` / `cc` vermez). Okuma
 * hatası "kayıt yok" değildir: tehlike kutusu + yeniden dene; elde önceki sayfa varsa üstte uyarı, satırlar kalır — ama
 * SÜZGEÇ (tür / durum / kanal) değişince önceki süzgecin satırları ve hatası hemen kalkar (inceleme 2026-10-18): yeni
 * süzgeç yüklenirken eski satırlar onun sonucu gibi görünmez, okunamazsa yalnız hata görünür. Sunucu son sayfadan büyük bir
 * sayfayı son sayfaya sıkıştırır; yanıttaki sayfaya bağlanılır.
 */
export default function SendLogView({ channelId }) {
  const t = useT()
  const { lang } = useLanguage()
  const [kind, setKind] = useState(() => normalizeSendLogKind(readUrlParam(TE_SLK, 'all')))
  const [status, setStatus] = useState(() => normalizeSendLogStatus(readUrlParam(TE_SLS, 'all')))
  const [state, setState] = useState({ loading: true, failed: false, error: null, data: null })
  const [listRef, listWidth] = useElementWidth()
  const sp = useServerPagination({
    listKey: 'te-sendlog', preset: 'panel', sizeOptions: SENDLOG_SIZE_OPTIONS, resetDeps: [kind, status, channelId], apiBase: 0,
    url: { pageKey: TE_SLP, sizeKey: TE_SLPS },
  })
  const seq = useRef(0)
  const { bind } = sp
  // Son yüklenen süzgeç: değişince eski satırlar / hata hemen kalkar (sayfa değişimi ve Yenile satırları korur)
  const filterKey = `${channelId}|${kind}|${status}`
  const loadedFilter = useRef(null)
  // Sunucunun sıkıştırdığı sayfaya bağlanmak için (setPage her çizimde yeni — yükleme geri çağrısına bağımlılık olmasın)
  const setPageRef = useRef(sp.setPage)
  useEffect(() => { setPageRef.current = sp.setPage })

  useUrlQuerySync({
    [TE_SLK]: kind === 'all' ? null : kind,
    [TE_SLS]: status === 'all' ? null : status,
  })

  const load = useCallback(() => {
    const my = ++seq.current
    const requested = sp.apiPage
    const freshFilter = loadedFilter.current !== filterKey
    loadedFilter.current = filterKey
    setState((s) => (freshFilter ? { loading: true, failed: false, error: null, data: null } : { ...s, loading: true }))
    api.techErrors.sendLog(channelId, { page: requested, size: sp.pageSize, kind, status }).then((res) => {
      if (my !== seq.current) return
      if (res?.success && res.data) {
        bind(res)
        setState({ loading: false, failed: false, error: null, data: res.data })
        // Sunucu sayfayı sıkıştırdıysa (son sayfadan büyük derin bağlantı) çubuk ve adres gerçek sayfaya geçer
        const served = Number(res.data.page)
        if (Number.isInteger(served) && served >= 0 && served !== requested) setPageRef.current(served + 1)
      } else {
        // Son bilinen toplam kalsın (bind çağrılmaz) — çubuk kaybolmasın
        setState((s) => ({ ...s, loading: false, failed: true, error: res?.error || null }))
      }
    }).catch((e) => {
      if (my !== seq.current) return
      setState((s) => ({ ...s, loading: false, failed: true, error: e?.message || null }))
    })
  }, [channelId, sp.apiPage, sp.pageSize, kind, status, bind, filterKey])

  useEffect(() => {
    load()
    return () => { seq.current++ }
  }, [load])

  const data = state.data
  const rows = Array.isArray(data?.rows) ? data.rows : []
  const hidden = !!data?.recipients_hidden
  const narrow = listWidth > 0 && listWidth < SENDLOG_TABLE_MIN_PX
  const filtersActive = kind !== 'all' || status !== 'all'

  function clearFilters() {
    setKind('all')
    setStatus('all')
  }

  const toolbar = (
    <Card data-slot="te-sendlog-toolbar" className="py-3">
      <CardContent className="flex flex-wrap items-end gap-3 px-3 sm:px-4">
        <div className="flex min-w-0 flex-col gap-1.5">
          <label htmlFor="te-slog-kind" className="text-xs font-medium text-muted-foreground">{t('te.slog.filterKind')}</label>
          <NativeSelect id="te-slog-kind" value={kind} onChange={(e) => setKind(normalizeSendLogKind(e.target.value))}
            className="pointer-coarse:h-10" data-slot="te-slog-kind">
            {SENDLOG_KINDS.map((k) => <NativeSelectOption key={k} value={k}>{t(SENDLOG_KIND_KEYS[k])}</NativeSelectOption>)}
          </NativeSelect>
        </div>
        <div className="flex min-w-0 flex-col gap-1.5">
          <label htmlFor="te-slog-status" className="text-xs font-medium text-muted-foreground">{t('te.slog.filterStatus')}</label>
          <NativeSelect id="te-slog-status" value={status} onChange={(e) => setStatus(normalizeSendLogStatus(e.target.value))}
            className="pointer-coarse:h-10" data-slot="te-slog-status">
            {SENDLOG_STATUSES.map((s) => <NativeSelectOption key={s} value={s}>{t(SENDLOG_STATUS_KEYS[s])}</NativeSelectOption>)}
          </NativeSelect>
        </div>
        {filtersActive && (
          <Button type="button" variant="ghost" onClick={clearFilters} className="pointer-coarse:min-h-10" data-slot="te-slog-clear">
            <X aria-hidden="true" /> {t('te.filters.clear')}
          </Button>
        )}
        <Button type="button" variant="outline" onClick={load} disabled={state.loading} className="ml-auto pointer-coarse:min-h-10"
          data-slot="te-slog-refresh">
          <RefreshCw aria-hidden="true" className={cn(state.loading && 'animate-spin motion-reduce:animate-none')} /> {t('te.slog.refresh')}
        </Button>
      </CardContent>
    </Card>
  )

  let body
  if (state.loading && !data) {
    body = (
      <div role="status" aria-live="polite" className="flex flex-col gap-2">
        <span className="sr-only">{t('te.loading')}</span>
        <Skeleton className="h-16 w-full rounded-xl motion-reduce:animate-none" />
        <Skeleton className="h-16 w-full rounded-xl motion-reduce:animate-none" />
      </div>
    )
  } else if (state.failed && !data) {
    body = (
      <StatusBlock tone="danger" icon={CircleAlert} title={t('te.slog.unavailableTitle')} description={state.error || t('te.slog.unavailableBody')}
        actions={<Button type="button" variant="outline" className="pointer-coarse:min-h-10" onClick={load}>{t('te.retry')}</Button>} />
    )
  } else if (rows.length === 0) {
    body = filtersActive ? (
      <StatusBlock tone="neutral" icon={Inbox} title={t('te.slog.noMatchTitle')} description={t('te.slog.noMatchBody')}
        actions={<Button type="button" variant="outline" onClick={clearFilters}><X aria-hidden="true" /> {t('te.filters.clear')}</Button>} />
    ) : (
      <StatusBlock tone="neutral" icon={Inbox} title={t('te.slog.emptyTitle')} description={t('te.slog.emptyBody')} />
    )
  } else if (narrow) {
    body = (
      <ul className="flex flex-col gap-2" data-slot="te-sendlog-cards">
        {rows.map((r) => (
          <li key={r.id}>
            <Card className="gap-2 py-3" data-slot="te-sendlog-row" data-status={sendLogStatusOf(r)} data-kind={sendLogKindOf(r)}>
              <CardContent className="flex flex-col gap-2 px-4">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="text-sm font-semibold">{fmtDateTime(r.at, lang)}</span>
                  <StatusPill row={r} t={t} />
                </div>
                <KindLine row={r} t={t} />
                <Recipients row={r} hidden={hidden} t={t} />
                <ReasonText row={r} t={t} />
              </CardContent>
            </Card>
          </li>
        ))}
      </ul>
    )
  } else {
    body = (
      <div className="overflow-hidden rounded-xl border" data-slot="te-sendlog-table">
        <Table className="table-fixed">
          <TableHeader>
            <TableRow>
              <TableHead className="w-40 whitespace-normal">{t('te.slog.col.time')}</TableHead>
              <TableHead className="w-48 whitespace-normal">{t('te.slog.col.kind')}</TableHead>
              <TableHead className="whitespace-normal">{t('te.slog.col.recipients')}</TableHead>
              <TableHead className="w-48 whitespace-normal">{t('te.slog.col.status')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((r) => (
              <TableRow key={r.id} data-slot="te-sendlog-row" data-status={sendLogStatusOf(r)} data-kind={sendLogKindOf(r)}>
                <TableCell className="align-top whitespace-normal text-sm">{fmtDateTime(r.at, lang)}</TableCell>
                <TableCell className="align-top whitespace-normal"><KindLine row={r} t={t} /></TableCell>
                <TableCell className="align-top whitespace-normal"><Recipients row={r} hidden={hidden} t={t} /></TableCell>
                <TableCell className="align-top whitespace-normal">
                  <div className="flex flex-col gap-1">
                    <StatusPill row={r} t={t} />
                    <ReasonText row={r} t={t} />
                  </div>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-3" data-slot="te-sendlog" data-layout={narrow ? 'cards' : 'table'}>
      <div className="flex flex-col gap-1">
        <h3 id="te-sendlog-title" className="m-0 text-base font-semibold">{t('te.slog.title')}</h3>
        <p className="m-0 text-sm text-muted-foreground [overflow-wrap:anywhere]">{t('te.slog.hint')}</p>
      </div>
      {toolbar}
      {hidden && (
        <div data-slot="te-sendlog-hidden">
          <AlertBanner tone="info" icon={EyeOff} title={t('te.slog.hiddenTitle')}>{t('te.slog.hiddenBody')}</AlertBanner>
        </div>
      )}
      {state.failed && data && (
        <AlertBanner tone="danger" title={t('te.slog.refreshProblemTitle')}
          actions={<Button type="button" size="sm" variant="outline" onClick={load}>{t('te.retry')}</Button>}>
          {state.error || t('te.slog.unavailableBody')}
        </AlertBanner>
      )}
      <div ref={listRef} className="flex flex-col gap-3" data-slot="te-sendlog-list">
        {body}
        {rows.length > 0 && <PaginationBar {...sp.bar} />}
      </div>
    </div>
  )
}

function StatusPill({ row, t }) {
  const st = sendLogStatusOf(row)
  return (
    <Badge className={cn('w-fit rounded-md overflow-visible whitespace-normal', SENDLOG_STATUS_TONE[st])} data-slot="te-sendlog-status">
      {t(SENDLOG_STATUS_KEYS[st])}
    </Badge>
  )
}

function KindLine({ row, t }) {
  const period = row?.year && row?.month ? periodLabel(row, t) : null
  return (
    <div className="flex flex-col gap-0.5 text-sm [overflow-wrap:anywhere]">
      <span className="font-medium" data-slot="te-sendlog-kind">{t(SENDLOG_KIND_KEYS[sendLogKindOf(row)])}</span>
      <span className="text-xs text-muted-foreground">
        {t(sendLogRoleKey(row))}{period ? ` · ${period}` : ''}
      </span>
    </div>
  )
}

/**
 * Alıcılar: Kime + Bilgi sararak (kırpılmaz); günlük kolonuna sığmayanlar "+N adres daha" (rapor gönderimi teslim edildiyse
 * tam liste raporun geçmişinde — geçmiş olayı Kime'yi tam yazar); paylaşımla okuyana yalnız sayılar.
 */
function Recipients({ row, hidden, t }) {
  if (hidden || !Array.isArray(row?.to)) {
    return (
      <p className="m-0 text-sm text-muted-foreground" data-slot="te-sendlog-counts">
        {t('te.slog.counts', Number(row?.to_count) || 0, Number(row?.cc_count) || 0)}
      </p>
    )
  }
  const to = row.to || []
  const cc = Array.isArray(row.cc) ? row.cc : []
  const toMore = sendLogMore(row, 'to')
  const ccMore = sendLogMore(row, 'cc')
  const st = sendLogStatusOf(row)
  // Geçmiş olayı Kime'yi tam yazar yalnız teslim edilen RAPOR gönderiminde (test / onay postası tek alıcı; gönderilmeyende alıcı yok)
  const historyHasList = sendLogKindOf(row) === 'report' && (st === 'sent' || st === 'queued')
  return (
    <div className="flex flex-col gap-1 text-sm" data-slot="te-sendlog-recipients">
      <p className="m-0 [overflow-wrap:anywhere]"><span className="text-muted-foreground">{t('te.slog.to')}: </span>{to.join(', ') || '—'}</p>
      {toMore > 0 && (
        <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-sendlog-to-more">
          {historyHasList ? t('te.slog.moreTo', toMore) : t('te.slog.more', toMore)}
        </p>
      )}
      {(cc.length > 0 || ccMore > 0) && (
        <p className="m-0 [overflow-wrap:anywhere]" data-slot="te-sendlog-cc"><span className="text-muted-foreground">{t('te.slog.cc')}: </span>{cc.join(', ') || '—'}</p>
      )}
      {ccMore > 0 && (
        <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-sendlog-cc-more">{t('te.slog.more', ccMore)}</p>
      )}
    </div>
  )
}

/**
 * Sebep (inceleme 2026-10-18): sunucunun SABİT kodu, arayüz dilinde — ham posta sunucusu iletisi değil. Gönderilen satırda
 * kod yok (null); bilinmeyen kod yazılmaz (ham anahtar gösterilmez). Kişi verisi taşımaz: paylaşımla okuyana da görünür.
 */
function ReasonText({ row, t }) {
  const key = sendLogReasonKey(row)
  if (!key) return null
  return (
    <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-sendlog-reason" data-reason={row.reason}>
      {t(key)}
    </p>
  )
}
