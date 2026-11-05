import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { CircleAlert, Info, RefreshCw, TriangleAlert, Wand2 } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import ModalShell from '../ui/ModalShell.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { isUnsupportedResponse } from './connectionDiagnosis.js'
import { diffStats, fixWarnings, lineDiff, normalizeFixes, normalizeSkipped, selectStarFix } from './sqlFix.js'

const LINE_TONE = {
  add: 'bg-success/10 text-foreground',
  del: 'bg-destructive/10 text-foreground line-through decoration-destructive/60',
  same: 'text-muted-foreground',
}
const SIGN = { add: '+', del: '−', same: ' ' }

/** Önce / sonra satır farkı — eklenen yeşil zemin, silinen kırmızı zemin + üstü çizili; işaret sütunu ekran okuyucuya söylenir. */
export function SqlDiff({ before, after }) {
  const t = useT()
  const diff = useMemo(() => lineDiff(before, after), [before, after])
  const stats = diffStats(diff)
  return (
    <div data-slot="dbq-fix-diff" className="flex min-w-0 flex-col gap-1">
      <span className="text-xs text-muted-foreground" data-slot="dbq-fix-diff-stats">{t('dbq.fix.diffStats', stats.add, stats.del)}</span>
      <div role="group" aria-label={t('dbq.fix.diffLabel')}
        className="max-h-72 overflow-auto rounded-md border bg-muted/30 py-1 font-mono text-xs leading-5">
        {diff.map((d, i) => (
          <div key={i} data-type={d.type} className={cn('flex min-w-0 gap-2 px-2', LINE_TONE[d.type])}>
            <span aria-hidden="true" className="w-3 shrink-0 select-none text-center font-bold">{SIGN[d.type]}</span>
            {d.type !== 'same' && <span className="sr-only">{t(d.type === 'add' ? 'dbq.fix.lineAdded' : 'dbq.fix.lineRemoved')}</span>}
            <span className="min-w-0 whitespace-pre-wrap [overflow-wrap:anywhere]">{d.text || ' '}</span>
          </div>
        ))}
      </div>
    </div>
  )
}

/**
 * "Düzelt" (B2, 2026-11-05) — güvenlik denetiminin düzeltebildiği sorunlar için önizlemeli öneri: sunucunun önerileri
 * (`ADD_NOLOCK` SQL Server ipucusuz tablolara `WITH (NOLOCK)`, `ADD_ROW_LIMIT` satır sınırı; sunucu önerdiği SQL'i
 * güvenlik denetiminden geçirmiştir) + istemcinin "SELECT * yerine kolonlar" önerisi (yalnız son denemenin kolonları
 * biliniyorsa ve sorgu tek tablolu basit `SELECT * FROM …` ise). Her öneri önce / sonra farkıyla gösterilir; "Uygula"
 * düzenleyicideki sorguyu değiştirir (kaydetmez). Satır sınırı satır sayısı kuralını etkiliyorsa uyarı. Sunucu ucu yoksa
 * (eski sürüm, 404 / 405) "desteklenmiyor" der — istemci önerisi yine çalışır. Veritabanına gitmez, deneme kotası harcamaz.
 */
export default function SqlFixDialog({ open, onClose, sql, dbType, rules = [], columns = [], rowLimit = null, onApply }) {
  const t = useT()
  const [load, setLoad] = useState({ state: 'idle', fixes: [], skipped: [], message: null })
  const seq = useRef(0)
  const clientFix = useMemo(() => selectStarFix(sql, columns, dbType), [sql, columns, dbType])

  const fetchFixes = useCallback(async () => {
    const n = ++seq.current
    if (!dbType) { setLoad({ state: 'noType', fixes: [], skipped: [], message: null }); return }
    setLoad({ state: 'loading', fixes: [], skipped: [], message: null })
    let res
    try {
      const limit = Number(rowLimit)
      res = await api.monitoring.dbQuerySqlFix({ sql, db_type: dbType, ...(Number.isInteger(limit) && limit > 0 ? { row_limit: limit } : {}) })
    } catch (e) {
      res = { success: false, error: e?.message || null }
    }
    if (n !== seq.current) return
    if (res?.success) setLoad({ state: 'ok', fixes: normalizeFixes(res.data, sql), skipped: normalizeSkipped(res.data), message: null })
    else if (isUnsupportedResponse(res)) setLoad({ state: 'unsupported', fixes: [], skipped: [], message: null })
    else setLoad({ state: 'error', fixes: [], skipped: [], message: res?.error || res?.message || t('dbq.fix.loadFailed') })
  }, [sql, dbType, rowLimit, t])

  useEffect(() => {
    if (open) fetchFixes()
    else seq.current++
  }, [open, fetchFixes])

  const all = [...load.fixes, ...(clientFix ? [clientFix] : [])]
  const loading = load.state === 'loading'
  return (
    <ModalShell open={!!open} onClose={onClose} size="lg" scrollBody icon={Wand2} title={t('dbq.fix.title')}
      footer={<Button type="button" variant="secondary" onClick={onClose} className="pointer-coarse:h-10">{t('dbq.fix.close')}</Button>}>
      <div data-slot="dbq-fix" data-state={load.state} className="flex min-w-0 flex-col gap-3">
        <p className="m-0 text-sm text-muted-foreground">{t('dbq.fix.intro')}</p>
        {loading && <div className="flex flex-col gap-2" aria-busy="true"><Skeleton className="h-24 w-full" /><Skeleton className="h-24 w-full" /></div>}
        {load.state === 'unsupported' && (
          <AlertBanner tone="info" icon={Info} className="mb-0"><span data-slot="dbq-fix-unsupported">{t('dbq.fix.unsupported')}</span></AlertBanner>
        )}
        {load.state === 'noType' && (
          <AlertBanner tone="info" icon={Info} className="mb-0"><span data-slot="dbq-fix-notype">{t('dbq.fix.needsType')}</span></AlertBanner>
        )}
        {load.state === 'error' && (
          <AlertBanner tone="danger" icon={CircleAlert} title={t('dbq.fix.unreadableTitle')} className="mb-0"
            actions={<Button type="button" variant="outline" size="sm" onClick={fetchFixes} className="pointer-coarse:h-10"><RefreshCw aria-hidden="true" />{t('dbq.fix.retry')}</Button>}>
            <span data-slot="dbq-fix-error" className="[overflow-wrap:anywhere]">{load.message}</span>
          </AlertBanner>
        )}
        {/* Sunucunun önermediği düzeltmeler ve nedeni (ör. tabloda başka ipucu var, sorguda zaten sınır var) */}
        {load.skipped.length > 0 && (
          <ul data-slot="dbq-fix-skipped" className="m-0 flex list-none flex-col gap-1 rounded-md border border-dashed px-3 py-2 text-xs text-muted-foreground">
            {load.skipped.map((sk, i) => (
              <li key={`${sk.code}-${i}`} data-code={sk.code} data-reason={sk.reason || undefined} className="[overflow-wrap:anywhere]">
                <span className="font-semibold">{t(`dbq.fix.code.${sk.code}.title`)}: </span>
                {sk.note || t(`dbq.fix.reason.${sk.reason}`)}
              </li>
            ))}
          </ul>
        )}
        {!loading && all.length === 0 && load.state !== 'error' && (
          <p data-slot="dbq-fix-none" className="m-0 rounded-md border border-dashed px-3 py-2.5 text-sm text-muted-foreground">{t('dbq.fix.none')}</p>
        )}
        <ul className="m-0 flex list-none flex-col gap-3 p-0">
          {all.map((fix) => {
            const warns = fixWarnings(fix, rules)
            return (
              <li key={fix.code}>
                <Card data-slot="dbq-fix-item" data-code={fix.code} className="gap-2.5 px-3.5 py-3 shadow-none">
                  <div className="flex min-w-0 flex-col gap-1">
                    <span className="flex flex-wrap items-center gap-1.5 font-semibold">
                      <Wand2 aria-hidden="true" className="size-4 shrink-0 text-primary" />
                      <span className="[overflow-wrap:anywhere]">{t(`dbq.fix.code.${fix.code}.title`)}</span>
                    </span>
                    <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{t(`dbq.fix.code.${fix.code}.desc`)}</span>
                    {fix.note && <span data-slot="dbq-fix-note" className="text-xs [overflow-wrap:anywhere]">{fix.note}</span>}
                  </div>
                  {fix.changed.length > 0 && (
                    <div data-slot="dbq-fix-changed" className="flex min-w-0 flex-wrap items-center gap-1.5">
                      <span className="text-xs font-semibold text-muted-foreground">{t(fix.code === 'SELECT_COLUMNS' ? 'dbq.fix.columns' : 'dbq.fix.changed')}</span>
                      {fix.changed.map((c) => (
                        <Badge key={c} variant="outline" className="h-auto rounded-md font-mono text-[11px] font-normal whitespace-normal [overflow-wrap:anywhere]">{c}</Badge>
                      ))}
                    </div>
                  )}
                  {warns.map((w) => (
                    <p key={w} data-slot="dbq-fix-warning" data-code={w}
                      className="m-0 flex items-start gap-1.5 rounded-md border border-amber-500/40 bg-amber-500/5 px-2.5 py-2 text-xs text-amber-800 dark:bg-amber-500/10 dark:text-amber-200">
                      <TriangleAlert aria-hidden="true" className="mt-px size-3.5 shrink-0" />
                      <span className="[overflow-wrap:anywhere]">{t(`dbq.fix.warn.${w}`)}</span>
                    </p>
                  ))}
                  <SqlDiff before={sql} after={fix.sql} />
                  <div className="flex justify-end">
                    <Button type="button" onClick={() => onApply?.(fix)} data-slot="dbq-fix-apply" className="pointer-coarse:h-10"
                      aria-label={t('dbq.fix.applyOf', t(`dbq.fix.code.${fix.code}.title`))}>
                      {t('dbq.fix.apply')}
                    </Button>
                  </div>
                </Card>
              </li>
            )
          })}
        </ul>
      </div>
    </ModalShell>
  )
}
