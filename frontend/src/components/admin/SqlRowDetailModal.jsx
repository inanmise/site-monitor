import { useEffect, useMemo, useRef, useState } from 'react'
import { Check, ChevronLeft, ChevronRight, Copy, Search, Table, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { cn } from '@/lib/utils'
import { cellText } from './sql/sqlUtils.js'

const TYPE_LABEL = { number: 'num', boolean: 'bool', datetime: 'time', json: 'json', text: 'text', null: '—' }

/**
 * SQL sonucunun TEK satırı — anahtar/değer listesi (salt okunur). Sonuç tablosunda satıra tıklayınca/Enter ile açılır.
 *
 * - Her değerin yanında kopyala (geri bildirim yerinde, 1.2 sn; zamanlayıcı unmount'ta temizlenir — CopyTimerCleanup testi);
 *   NULL soluk rozet, JSON girintili, uzun metin sarılır.
 * - Geniş satırda (8+ kolon) alan süzgeci; önceki/sonraki satır gezintisi (sıralı/süzülmüş görünüm sırası) + ← → tuşları.
 * - Altta "satırı JSON olarak kopyala".
 * Kabuk: ui/ModalShell (shadcn Dialog) — odak tuzağı, Escape, telefonda kayan gövde.
 */
export default function SqlRowDetailModal({ row, cols, index, onClose, onPrev, onNext, position, types, onCopied }) {
  const t = useT()
  const [copiedKey, setCopiedKey] = useState(null)
  const [q, setQ] = useState('')
  // Kopyalama geri bildirimi zamanlayıcısı ref'te tutulur ve unmount'ta temizlenir (CopyButton.jsx deseni).
  const timer = useRef(null)
  useEffect(() => () => clearTimeout(timer.current), [])

  function copyValue(key, val) {
    const text = val == null ? '' : (typeof val === 'object' ? JSON.stringify(val, null, 2) : String(val))
    if (!text) return
    try {
      navigator.clipboard.writeText(text).then(() => {
        setCopiedKey(key)
        clearTimeout(timer.current)
        timer.current = setTimeout(() => setCopiedKey(null), 1200)
        onCopied?.(key)
      }).catch(() => {})
    } catch { /* clipboard unavailable */ }
  }

  const shown = useMemo(() => {
    const needle = q.trim().toLowerCase()
    if (!needle) return cols
    return cols.filter((c) => c.toLowerCase().includes(needle) || (cellText(row[c]) ?? '').toLowerCase().includes(needle))
  }, [cols, row, q])

  const onKeyDown = (e) => {
    if (e.target.closest?.('input, textarea')) return
    if (e.key === 'ArrowLeft' && onPrev) { e.preventDefault(); onPrev() }
    if (e.key === 'ArrowRight' && onNext) { e.preventDefault(); onNext() }
  }

  const nav = (onPrev || onNext) && (
    <div className="flex items-center gap-1">
      {position && <span className="mr-1 text-xs text-muted-foreground tabular-nums">{position}</span>}
      <Button type="button" variant="outline" size="icon-sm" className="pointer-coarse:size-10" onClick={onPrev} disabled={!onPrev}
        aria-label={t('sql.row.prev')}><ChevronLeft /></Button>
      <Button type="button" variant="outline" size="icon-sm" className="pointer-coarse:size-10" onClick={onNext} disabled={!onNext}
        aria-label={t('sql.row.next')}><ChevronRight /></Button>
    </div>
  )

  return (
    <ModalShell open onClose={onClose} title={t('sql.rowDetails', String(index + 1))} icon={Table} size="lg" scrollBody
      headerExtra={<div className="ml-auto">{nav}</div>}
      footer={
        <div className="flex w-full flex-wrap items-center justify-end gap-2">
          <Button type="button" variant="outline" onClick={() => copyValue('__row__', Object.fromEntries(cols.map((c) => [c, row[c] ?? null])))}>
            {copiedKey === '__row__' ? <Check /> : <Copy />} {t('sql.row.copyJson')}
          </Button>
          <Button type="button" variant="secondary" onClick={onClose}>{t('sql.closeRowDetails')}</Button>
        </div>
      }>
      <div className="flex flex-col gap-3" onKeyDown={onKeyDown}>
        {cols.length > 8 && (
          <InputGroup className="h-10 sm:h-9">
            <InputGroupInput value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('sql.row.filter')} aria-label={t('sql.row.filter')} />
            <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
            {q && (
              <InputGroupAddon align="inline-end">
                <InputGroupButton size="icon-xs" aria-label={t('sql.ex.clearSearch')} onClick={() => setQ('')}><X /></InputGroupButton>
              </InputGroupAddon>
            )}
          </InputGroup>
        )}
        <dl data-slot="sql-row-detail" className="overflow-hidden rounded-md border">
          {shown.map((col) => {
            const val = row[col]
            const isNull = val == null
            const isJson = !isNull && typeof val === 'object'
            return (
              <div key={col} className="grid grid-cols-1 items-start gap-1 border-b px-3 py-2 last:border-b-0 even:bg-muted/40 sm:grid-cols-[minmax(140px,28%)_1fr] sm:gap-3">
                <dt className="flex flex-wrap items-center gap-1.5 font-mono text-xs font-semibold break-all text-muted-foreground">
                  {col}
                  {types?.[col] && <span className="font-sans text-[10px] font-normal tracking-wide uppercase opacity-70">{TYPE_LABEL[types[col]] ?? types[col]}</span>}
                </dt>
                <dd className="flex min-w-0 items-start gap-2 text-sm">
                  {isNull
                    ? <span className="flex-1"><Badge variant="outline" className="border-dashed px-1.5 text-[10px] font-medium text-muted-foreground">NULL</Badge></span>
                    : <span className={cn('min-w-0 flex-1 font-mono text-xs break-all whitespace-pre-wrap', isJson && 'rounded bg-muted/60 p-2')}>
                      {isJson ? JSON.stringify(val, null, 2) : String(val)}
                    </span>}
                  <SimpleTooltip content={isNull ? null : t('sql.copyValue')}>
                    <Button type="button" variant="ghost" size="icon-sm" onClick={() => copyValue(col, val)} disabled={isNull}
                      aria-label={t('a11y.rowAction', t('sql.copyValue'), col)} className="size-7 shrink-0 text-muted-foreground pointer-coarse:size-10">
                      {copiedKey === col ? <Check /> : <Copy />}
                    </Button>
                  </SimpleTooltip>
                </dd>
              </div>
            )
          })}
          {shown.length === 0 && <p className="px-3 py-4 text-center text-sm text-muted-foreground">{t('sql.row.noFieldMatch')}</p>}
        </dl>
      </div>
    </ModalShell>
  )
}
