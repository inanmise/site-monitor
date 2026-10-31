import { useCallback, useEffect, useRef, useState } from 'react'
import { DatabaseZap, EyeOff, Lock, RefreshCw, Clock } from 'lucide-react'
import { api, formatDateSec } from '../../api/client'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import AlertBanner from '../ui/AlertBanner.jsx'

/** DBQUERY_* alarm türü mü (FAIL / THRESHOLD / SLOW ve ileride eklenecekler). */
export const isDbQueryAlertType = (type) => typeof type === 'string' && type.startsWith('DBQUERY_')

const SECTION = 'mt-5 mb-2 text-xs font-bold tracking-wide text-muted-foreground uppercase'

/**
 * Alarm anındaki sorgu sonucu (Veritabanı Sorgu İzleme, 2026-10-31; kullanıcı kararı) — SATIR VERİSİNİN arayüzde
 * göründüğü TEK yer. `GET /monitoring/dbquery/alerts/{id}/rows`:
 *   • `hidden: true`  → izlemenin takımını kendi kapsamıyla görmeyen (paylaşımla okuyan, 7/24 operatörü — K4): satırlar
 *                       gelmez, yalnız satır sayısı söylenir
 *   • `data: null`    → bu alarm için satır kaydı yok (alarm satırsız açılmış, saklama süresi geçmiş …)
 *   • veri            → yakalama zamanı, satır sayısı ("≥ N" sınırlı), gösterilen satır ("ilk 50"), maskeli kolon
 *                       rozetleri, yatay kaydırmalı tablo — hücre metni KIRPILMAZ, sarar
 *   • okunamadı       → hata + tekrar dene (asla "satır yok" denmez)
 * DBQUERY dışı alarmda hiçbir şey çizmez, istek de atmaz.
 */
export default function DbQueryAlertRows({ alertId, alertType }) {
  const t = useT()
  const { lang } = useLanguage()
  const applies = isDbQueryAlertType(alertType) && alertId != null
  const [state, setState] = useState({ state: 'loading', data: null, message: null })
  const seq = useRef(0)

  const load = useCallback(async () => {
    const n = ++seq.current
    setState({ state: 'loading', data: null, message: null })
    try {
      const res = await api.monitoring.dbQueryAlertRows(alertId)
      if (n !== seq.current) return
      if (res?.success) setState({ state: res.data == null ? 'none' : (res.data.hidden ? 'hidden' : 'ok'), data: res.data ?? null, message: null })
      else setState({ state: 'error', data: null, message: res?.error || res?.message || null })
    } catch (e) {
      if (n !== seq.current) return
      setState({ state: 'error', data: null, message: e?.message || null })
    }
  }, [alertId])

  useEffect(() => {
    if (!applies) return undefined
    load()
    // sıra numarası bir ARTTIRILIR: uçuştaki yanıt (önceki alarmın) bayat sayılsın — DOM ref değil, uyarı yersiz
    // eslint-disable-next-line react-hooks/exhaustive-deps
    return () => { seq.current++ }
  }, [applies, load])

  if (!applies) return null
  const d = state.data
  const fmt = (n) => new Intl.NumberFormat(lang === 'en' ? 'en-GB' : 'tr-TR').format(n)

  return (
    <section data-slot="dbq-alert-rows" data-state={state.state} aria-labelledby={`dbq-rows-h-${alertId}`} className="min-w-0">
      <h3 id={`dbq-rows-h-${alertId}`} className={cn(SECTION, 'inline-flex items-center gap-1.5')}>
        <DatabaseZap aria-hidden="true" className="size-3.5" />{t('dbq.rows.title')}
      </h3>

      {state.state === 'loading' && (
        <div className="flex flex-col gap-2" aria-busy="true"><Skeleton className="h-5 w-56" /><Skeleton className="h-24 w-full" /></div>
      )}

      {state.state === 'error' && (
        <AlertBanner tone="danger" title={t('dbq.rows.loadFailedTitle')} className="mb-0"
          actions={<Button type="button" variant="outline" size="sm" onClick={load} className="pointer-coarse:h-10"><RefreshCw aria-hidden="true" />{t('dbq.rows.retry')}</Button>}>
          {state.message || t('dbq.rows.loadFailedBody')}
        </AlertBanner>
      )}

      {state.state === 'none' && (
        <p data-slot="dbq-alert-rows-none" className="m-0 rounded-md border border-dashed px-3 py-2.5 text-sm text-muted-foreground">{t('dbq.rows.none')}</p>
      )}

      {state.state === 'hidden' && (
        <AlertBanner tone="info" icon={EyeOff} title={t('dbq.rows.hiddenTitle')} className="mb-0">
          {d?.row_count != null
            ? t(d.row_count_capped ? 'dbq.rows.hiddenBodyCapped' : 'dbq.rows.hiddenBody', fmt(d.row_count))
            : t('dbq.rows.hiddenBodyUnknown')}
        </AlertBanner>
      )}

      {state.state === 'ok' && <RowsTable d={d} t={t} fmt={fmt} />}
    </section>
  )
}

function RowsTable({ d, t, fmt }) {
  const columns = Array.isArray(d.columns) ? d.columns : []
  const rows = Array.isArray(d.rows) ? d.rows : []
  const masked = new Set((Array.isArray(d.masked) ? d.masked : []).map((c) => String(c).toLowerCase()))
  const isMasked = (c) => masked.has(String(c).toLowerCase())
  const total = Number(d.row_count)
  const totalText = Number.isFinite(total)
    ? (d.row_count_capped ? t('dbq.rows.countCapped', fmt(total)) : (total === 1 ? t('dbq.rows.count.one', fmt(total)) : t('dbq.rows.count', fmt(total))))
    : t('dbq.rows.countUnknown')
  const partial = d.truncated || (Number.isFinite(total) && rows.length < total)

  return (
    <div className="flex min-w-0 flex-col gap-2">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
        <span className="inline-flex items-center gap-1"><Clock aria-hidden="true" className="size-3.5" />
          {t('dbq.rows.capturedAt')} <strong className="font-semibold text-foreground">{d.captured_at ? formatDateSec(d.captured_at) : '—'}</strong></span>
        <span data-slot="dbq-alert-rows-count" className="font-semibold text-foreground tabular-nums">{totalText}</span>
        {partial && rows.length > 0 && <span data-slot="dbq-alert-rows-partial">{t('dbq.rows.showingFirst', fmt(rows.length))}</span>}
      </div>
      {masked.size > 0 && (
        <div data-slot="dbq-alert-rows-masked" className="flex flex-wrap items-center gap-1.5">
          <span className="text-xs text-muted-foreground">{t('dbq.rows.maskedLabel')}</span>
          {columns.filter(isMasked).map((c, i) => (
            <Badge key={`${i}-${c}`} variant="outline" className="h-auto gap-1 rounded-md font-mono font-normal whitespace-normal [overflow-wrap:anywhere]">
              <Lock aria-hidden="true" />{c}
            </Badge>
          ))}
        </div>
      )}
      {rows.length === 0
        ? <p className="m-0 rounded-md border border-dashed px-3 py-2.5 text-sm text-muted-foreground">{t('dbq.rows.empty')}</p>
        : (
          // Kaydırıcı DIŞ kaptır (shadcn kabının kendi kaydırması kapatılır): bölge adı + tabIndex onda → klavyeyle de yatay
          // kaydırılır (10b). Aynı adlı kolonlarda (SELECT a.id, b.id) anahtar SIRA ile — yinelenen anahtar yok (11).
          <div data-slot="dbq-alert-rows-scroll" role="region" aria-label={t('dbq.rows.tableLabel')} tabIndex={0}
            className="min-w-0 overflow-x-auto rounded-md border outline-none focus-visible:ring-2 focus-visible:ring-ring [&>[data-slot=table-container]]:overflow-visible">
            <Table data-slot="dbq-alert-rows-table" className="w-max min-w-full">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-10 text-right text-muted-foreground">#</TableHead>
                  {columns.map((c, ci) => (
                    <TableHead key={ci} data-masked={isMasked(c) ? 'true' : undefined} className="align-bottom whitespace-normal">
                      {/* Genişlik İÇ kutuda: tablo hücresinde max-width güvenilmez (tarayıcı yok sayar); iç kutunun katkısı sınırlanır */}
                      <span className="flex max-w-[28rem] min-w-[8rem] items-center gap-1 font-mono text-xs [overflow-wrap:anywhere]">
                        {isMasked(c) && <Lock aria-hidden="true" className="size-3 shrink-0 text-muted-foreground" />}{c}
                        {isMasked(c) && <span className="sr-only"> ({t('dbq.rows.maskedSr')})</span>}
                      </span>
                    </TableHead>
                  ))}
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((r, i) => (
                  <TableRow key={i} data-slot="dbq-alert-row">
                    <TableCell className="w-10 text-right align-top text-xs text-muted-foreground tabular-nums">{i + 1}</TableCell>
                    {columns.map((c, j) => {
                      const v = Array.isArray(r) ? r[j] : null
                      return (
                        <TableCell key={j} className="align-top whitespace-normal">
                          <div data-slot="dbq-cell" className="max-w-[28rem] min-w-[8rem] font-mono text-xs whitespace-pre-wrap [overflow-wrap:anywhere]">
                            {v == null ? <span className="text-muted-foreground italic">NULL</span> : String(v)}
                          </div>
                        </TableCell>
                      )
                    })}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      <p className="m-0 text-xs text-muted-foreground">{t('dbq.rows.privacyNote')}</p>
    </div>
  )
}
