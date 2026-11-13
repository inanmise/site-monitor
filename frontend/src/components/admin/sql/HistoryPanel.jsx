import { useEffect, useRef, useState } from 'react'
import { History, Play, Plus, Search, SquarePen, X } from 'lucide-react'
import { api, formatDateSec } from '../../../api/client'
import { useDateLocale, useT } from '../../../i18n/index.jsx'
import { useServerPagination } from '../../../hooks/useServerPagination.js'
import AlertBanner from '../../ui/AlertBanner.jsx'
import Field from '../../ui/Field.jsx'
import PaginationBar from '../../ui/PaginationBar.jsx'
import { LoadingBlock } from '../../ui/Progress.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'
import ChoiceToggle from './ChoiceToggle.jsx'
import {
  HISTORY_Q_MAX, SLOW_MS_DEFAULT, filterLocalHistory, historyQuery, historyRangeError, historyState, isPagedHistory,
} from './sqlLibraryModel.js'
import { formatDuration, relativeFrom } from './sqlUtils.js'

const STATE_LABEL = { ok: 'sql.hist.state.ok', error: 'sql.hist.state.bad', slow: 'sql.hist.state.slow', cancelled: 'sql.hist.state.cancelled' }
const STATE_TONE = {
  ok: 'border-success/30 bg-success/10 text-success',
  error: 'border-destructive/30 bg-destructive/10 text-destructive',
  slow: 'border-amber-500/40 bg-amber-500/10 text-amber-700 dark:text-amber-300',
  cancelled: 'text-muted-foreground',
}

/**
 * Sorgu geçmişi paneli (2026-11-13, A4) — sağdan açılan Sheet: metinde arama, durum (tümü / başarılı / hatalı / yavaş ≥ 5 sn),
 * kapsam (kendi / tüm yöneticiler — denetim görünümü), Istanbul günüyle tarih aralığı, sunucu sayfalaması. Her satır:
 * düzenleyiciye al · yeni sekmede aç · çalıştır. Eski sunucu sayfalı kipi bilmez (yanıtta `total` yok) → yalnız kendi son 50
 * sorgunuzda metin + durum süzgeci, not ile (kapsam / tarih kapalı). Okunamayan liste "okunamadı + yeniden dene", asla "boş".
 */
export default function HistoryPanel({ open, onOpenChange, onUse, onOpenTab, onRun }) {
  const t = useT()
  const locale = useDateLocale()
  const [q, setQ] = useState('')
  const [qDebounced, setQDebounced] = useState('')
  const [status, setStatus] = useState('all')
  const [scope, setScope] = useState('mine')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [state, setState] = useState({ loading: false, error: null, rows: [], total: null, paged: true, slowMs: SLOW_MS_DEFAULT, fromError: null })
  const [reload, setReload] = useState(0)
  const seq = useRef(0)

  useEffect(() => {
    const h = setTimeout(() => setQDebounced(q), 300)
    return () => clearTimeout(h)
  }, [q])
  // Standart sunucu sayfalaması: süzgeç değişince sayfa 1 (mount'ta sıfırlama YOK), boyut kalıcı (`sm.pageSize.sqlHistory`)
  const sp = useServerPagination({ listKey: 'sqlHistory', preset: 'modal', resetDeps: [qDebounced, status, scope, from, to] })
  const { bind, apiPage, pageSize } = sp

  const rangeError = historyRangeError(from, to)
  useEffect(() => {
    if (!open || rangeError) return
    const my = ++seq.current
    setState((s) => ({ ...s, loading: true, error: null, fromError: null }))
    const qs = historyQuery({ q: qDebounced, status, scope, from, to, page: apiPage, size: pageSize })
    api.admin.sqlHistoryPage(qs).then((r) => {
      if (my !== seq.current) return
      if (r?.success && isPagedHistory(r)) {
        bind(r)
        setState({ loading: false, error: null, rows: Array.isArray(r.data) ? r.data : [], total: Number(r.total), paged: true,
          slowMs: Number(r.slow_ms) || SLOW_MS_DEFAULT, fromError: null })
      } else if (r?.success && Array.isArray(r.data)) {
        // Eski sunucu: parametreleri yok sayıp kendi son 50'yi döndü
        const rows = filterLocalHistory(r.data, { q: qDebounced, status })
        setState({ loading: false, error: null, rows, total: rows.length, paged: false, slowMs: SLOW_MS_DEFAULT, fromError: null })
      } else {
        const fromErr = r?.field === 'from' || r?.fields?.from ? (r.error || t('sql.hist.rangeReversed')) : null
        setState((s) => ({ ...s, loading: false, error: fromErr ? null : (r?.error || t('sql.hist.loadError')), fromError: fromErr, rows: fromErr ? [] : s.rows }))
      }
    }).catch((e) => {
      if (my !== seq.current) return
      setState((s) => ({ ...s, loading: false, error: e?.message || t('sql.hist.loadError') }))
    })
  }, [open, qDebounced, status, scope, from, to, apiPage, pageSize, reload, rangeError, t, bind])

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full gap-0 p-0 sm:max-w-xl" data-slot="sql-history-panel">
        <SheetHeader className="border-b pr-12">
          <SheetTitle className="flex items-center gap-2"><History aria-hidden="true" className="size-4" /> {t('sql.hist.title')}</SheetTitle>
          <SheetDescription className="text-xs">{t('sql.hist.desc')}</SheetDescription>
        </SheetHeader>
        <div className="flex flex-col gap-3 border-b p-3">
          <InputGroup className="h-10 sm:h-9 pointer-coarse:h-10">
            <InputGroupInput value={q} maxLength={HISTORY_Q_MAX} onChange={(e) => setQ(e.target.value)}
              placeholder={t('sql.hist.search')} aria-label={t('sql.hist.search')} />
            <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
            {q && (
              <InputGroupAddon align="inline-end">
                <InputGroupButton size="icon-xs" aria-label={t('sql.ex.clearSearch')} onClick={() => setQ('')}><X /></InputGroupButton>
              </InputGroupAddon>
            )}
          </InputGroup>
          <div className="flex flex-wrap items-center gap-2">
            <ChoiceToggle ariaLabel={t('sql.hist.status')} value={status} onChange={setStatus} options={[
              { value: 'all', label: t('sql.hist.st.all') }, { value: 'ok', label: t('sql.hist.st.ok') },
              { value: 'error', label: t('sql.hist.st.bad') }, { value: 'slow', label: t('sql.hist.st.slow') },
            ]} />
            {state.paged && (
              <ChoiceToggle ariaLabel={t('sql.hist.scope')} value={scope} onChange={setScope} options={[
                { value: 'mine', label: t('sql.hist.scopeMine') }, { value: 'all', label: t('sql.hist.scopeAll') },
              ]} />
            )}
          </div>
          {state.paged && (
            <div className="grid grid-cols-2 gap-2">
              <Field label={t('sql.hist.from')} name="from" error={rangeError ? t('sql.hist.rangeReversed') : state.fromError || undefined}>
                {({ id, describedBy, invalid }) => (
                  <Input id={id} type="date" value={from} onChange={(e) => setFrom(e.target.value)} aria-describedby={describedBy} aria-invalid={invalid} />
                )}
              </Field>
              <Field label={t('sql.hist.to')} name="to">
                {({ id }) => <Input id={id} type="date" value={to} onChange={(e) => setTo(e.target.value)} />}
              </Field>
            </div>
          )}
          {!state.paged && (
            <div data-slot="sql-history-legacy"><AlertBanner tone="info" className="mb-0">{t('sql.hist.legacy')}</AlertBanner></div>
          )}
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain p-3">
          {state.loading && !state.rows.length ? (
            <LoadingBlock label={t('sql.hist.loading')} size={18} />
          ) : state.error ? (
            <StatusBlock tone="danger" title={t('sql.hist.loadError')} description={state.error} className="py-6"
              actions={<Button type="button" variant="outline" size="sm" onClick={() => setReload((n) => n + 1)} className="pointer-coarse:h-10">{t('sql.retry')}</Button>} />
          ) : !state.rows.length ? (
            <StatusBlock icon={History} title={t('sql.hist.empty')} className="py-6" />
          ) : (
            <ul data-slot="sql-history-list" aria-label={t('sql.hist.listLabel')} className="flex flex-col gap-2" aria-busy={state.loading || undefined}>
              {state.rows.map((h, i) => {
                const sql = h.sql_text ?? h.sqlText ?? ''
                const st = historyState(h, state.slowMs)
                const when = h.executed_at ?? h.executedAt
                return (
                  <li key={h.id ?? i} data-slot="sql-history-item" data-state={st} className="flex min-w-0 flex-col gap-2 rounded-lg border p-2.5">
                    <pre className="m-0 max-h-32 overflow-auto rounded-md bg-muted/40 p-2 font-mono text-xs break-words whitespace-pre-wrap">{sql}</pre>
                    <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
                      <Badge variant="outline" className={STATE_TONE[st]}>{t(STATE_LABEL[st] ?? 'sql.hist.state.ok')}</Badge>
                      {st !== 'error' && st !== 'cancelled' && <span className="tabular-nums">{t('sql.rowsCount', h.row_count ?? h.rowCount ?? 0)}</span>}
                      <span className="tabular-nums">{formatDuration(h.duration_ms ?? h.durationMs)}</span>
                      {when && <span title={formatDateSec(when)}>{relativeFrom(when, locale) || formatDateSec(when)}</span>}
                      {scope === 'all' && h.executed_by && <span className="font-medium text-foreground [overflow-wrap:anywhere]">{h.executed_by}</span>}
                    </div>
                    {st === 'error' && (h.error_message || h.errorMessage) && (
                      <p className="m-0 text-xs break-words text-destructive">{h.error_message || h.errorMessage}</p>
                    )}
                    <div className="flex flex-wrap gap-2">
                      <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => onUse?.(sql)}>
                        <SquarePen /> {t('sql.hist.use')}
                      </Button>
                      <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => onOpenTab?.(sql)}>
                        <Plus /> {t('sql.hist.newTab')}
                      </Button>
                      <Button type="button" size="sm" className="pointer-coarse:h-10" onClick={() => onRun?.(sql)}>
                        <Play /> {t('sql.hist.run')}
                      </Button>
                    </div>
                  </li>
                )
              })}
            </ul>
          )}
        </div>
        {state.paged && (
          <div className="border-t px-3 pb-2" data-slot="sql-history-pager">
            <PaginationBar {...sp.bar} />
          </div>
        )}
      </SheetContent>
    </Sheet>
  )
}
