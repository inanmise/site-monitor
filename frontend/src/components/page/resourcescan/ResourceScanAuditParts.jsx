import { useState } from 'react'
import { ChevronDown, FlaskConical, ListFilter, PanelRightOpen, Sparkles } from 'lucide-react'
import { useElementWidth } from '../../../hooks/useElementWidth.js'
import ToneBadge from '../../admin/ToneBadge.jsx'
import { AUDIT_LIST_PREVIEW, AUDIT_TABLE_MIN_PX, formatNumber, plural } from './resourceScanModel.js'
import { CHIP, TOUCH, UrlText, WRAP } from './ResourceScanParts.jsx'
import { Button } from '@/components/shadcn/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/**
 * Kaynak analizi → ayrıntılı denetim (prs3) ortak parçaları: tablo ↔ kart listesi (KAP genişliğine göre), "tahmin" /
 * "sezgisel" rozetleri, sayı kutucukları, "bu analizde yok" satırı, bulgu → nesne listesi düğmesi, URL + ayrıntı.
 * Saf sunum — metinler çağırandan (`t`).
 */

/** "Tahmin" (model hesabı) ya da "Sezgisel" (kural tahmini) rozeti — kullanıcı sonucun ölçüm olmadığını görsün. */
export function KindBadge({ kind, t }) {
  const est = kind === 'estimate'
  return (
    <ToneBadge tone="muted" data-slot="prs-deep-kind" data-kind={kind} className={cn(CHIP, 'gap-1 font-medium')}
      title={t(est ? 'prs.deep.kind.estimateHint' : 'prs.deep.kind.heuristicHint')}>
      {est ? <FlaskConical aria-hidden="true" /> : <Sparkles aria-hidden="true" />}
      {t(est ? 'prs.deep.kind.estimate' : 'prs.deep.kind.heuristic')}
    </ToneBadge>
  )
}

/** Sayı kutucukları (2 sütun telefonda; kap genişledikçe 3 / 4). `tiles` = [{ k, label, value, sub, tone }]. */
export function StatTiles({ tiles, slot = 'prs-deep-tiles' }) {
  const list = (tiles || []).filter(Boolean)
  if (!list.length) return null
  return (
    <div data-slot={slot} className="@container min-w-0">
      <div className="grid min-w-0 grid-cols-2 gap-2 @lg:grid-cols-3 @3xl:grid-cols-4">
        {list.map((x) => (
          <div key={x.k} data-slot="prs-deep-tile" data-key={x.k} data-tone={x.tone || undefined}
            className="flex min-w-0 flex-col gap-0.5 rounded-lg border bg-muted/30 px-3 py-2">
            <span className={cn('text-[11px] font-semibold tracking-[.04em] text-muted-foreground uppercase max-sm:text-xs', WRAP)}>{x.label}</span>
            <span className={cn('text-base leading-tight font-bold tabular-nums', x.tone === 'danger' ? 'text-destructive'
              : x.tone === 'warning' ? 'text-amber-700 dark:text-amber-300' : 'text-foreground', WRAP)}>{x.value}</span>
            {x.sub && <span className={cn('text-xs text-muted-foreground', WRAP)}>{x.sub}</span>}
          </div>
        ))}
      </div>
    </div>
  )
}

/**
 * Bölüm bu analizde hesaplanamadı — sunucu (algoritma 3) bloğu üretemedi: sayfanın HTML'i ya da yanıt başlıkları
 * okunamadı. Asla "sorun yok" demez. (Eski analizin tamamı ResourceScanAudit'te ayrıca "bu analizde yok" der.)
 */
export function NotInScan({ t, slot = 'prs-deep-missing', text }) {
  return <p data-slot={slot} className="m-0 text-sm text-muted-foreground italic">{text || t('prs.deep.notComputed')}</p>
}

/** Boş liste metni (veri VAR ve liste boş — örn. "engelleyen kaynak yok"); `good` başarı renginde. */
export function EmptyLine({ children, good = false, slot = 'prs-deep-empty' }) {
  return <p data-slot={slot} className={cn('m-0 text-sm', good ? 'text-success' : 'text-muted-foreground', WRAP)}>{children}</p>
}

/** Bulgu süzgeciyle nesne listesine geçiş düğmesi (nesne ayrıntısı saklıysa ve en az bir nesne varsa). */
export function ShowObjectsButton({ count, onClick, t, locale, label }) {
  if (!onClick || !(count > 0)) return null
  return (
    <Button type="button" variant="outline" size="sm" data-slot="prs-deep-show-objects" data-count={count} className={cn('w-fit', TOUCH)}
      aria-label={label ? t('prs.deep.showObjectsFor', label, count) : undefined} onClick={onClick}>
      <ListFilter aria-hidden="true" />{t('prs.rec.showObjects', formatNumber(count, locale))}
    </Button>
  )
}

/** Adres + (nesne listesinde varsa) "Ayrıntı" düğmesi. */
export function UrlCell({ url, t, item, onOpenItem, className }) {
  return (
    <span className="flex min-w-0 flex-col items-start gap-1">
      <UrlText url={url} t={t} className={cn('text-xs', className)} />
      {item && onOpenItem && (
        <Button type="button" variant="ghost" size="sm" data-slot="prs-deep-open" onClick={() => onOpenItem(item)}
          aria-label={t('prs.od.openFor', url || '—')} className={cn('h-7 w-fit px-2 text-xs', TOUCH)}>
          <PanelRightOpen aria-hidden="true" />{t('prs.od.open')}
        </Button>
      )}
    </span>
  )
}

/**
 * Tablo ↔ kart listesi: kap ≥ `minPx` ise tablo, değilse kart (jsdom 0 → tablo). `columns` = [{ key, head, cell(row),
 * className, align, primary }] — kartta `primary` sütun başlık olur, diğerleri "etiket: değer" satırları. İlk `preview`
 * satırdan fazlası "Tümünü göster" ile açılır; sunucunun göndermediği satırlar (`more`) not olarak yazılır.
 */
export function ResponsiveRows({
  slot, rows, columns, rowKey, rowSlot = 'prs-deep-row', rowAttrs, minPx = AUDIT_TABLE_MIN_PX, preview = AUDIT_LIST_PREVIEW, more = 0, t, locale,
  caption,
}) {
  const [ref, width] = useElementWidth()
  const [open, setOpen] = useState(false)
  const list = Array.isArray(rows) ? rows : []
  const table = width === 0 || width >= minPx
  const shown = open ? list : list.slice(0, preview)
  const hidden = list.length - shown.length
  const primary = columns.find((c) => c.primary) || columns[0]
  const rest = columns.filter((c) => c !== primary)
  const keyOf = (r, i) => (rowKey ? `${rowKey(r)}#${i}` : i)
  return (
    <div ref={ref} data-slot={slot} data-layout={table ? 'table' : 'cards'} className="flex min-w-0 flex-col gap-2">
      {table ? (
        <div className="overflow-hidden rounded-xl border">
          <Table className="table-fixed">
            {caption && <caption className="sr-only">{caption}</caption>}
            <TableHeader>
              <TableRow>
                {columns.map((c) => (
                  <TableHead key={c.key} className={cn('whitespace-normal', c.align === 'right' && 'text-right', c.className)}>{c.head}</TableHead>
                ))}
              </TableRow>
            </TableHeader>
            <TableBody>
              {shown.map((r, i) => (
                <TableRow key={keyOf(r, i)} data-slot={rowSlot} {...(rowAttrs ? rowAttrs(r) : {})}>
                  {columns.map((c) => (
                    <TableCell key={c.key} className={cn('align-top text-xs whitespace-normal', c.align === 'right' && 'text-right tabular-nums', WRAP)}>
                      {c.cell(r)}
                    </TableCell>
                  ))}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      ) : (
        <ul className="m-0 grid list-none grid-cols-1 gap-2 p-0">
          {shown.map((r, i) => (
            <li key={keyOf(r, i)} data-slot={rowSlot} {...(rowAttrs ? rowAttrs(r) : {})}
              className="flex min-w-0 flex-col gap-1.5 rounded-lg border bg-card px-3 py-2.5 text-xs">
              <div className="min-w-0 text-sm">{primary.cell(r)}</div>
              {rest.length > 0 && (
                <dl className="m-0 grid min-w-0 grid-cols-1 gap-1 min-[360px]:grid-cols-2">
                  {rest.map((c) => (
                    <div key={c.key} className="flex min-w-0 flex-col gap-0.5">
                      <dt className={cn('text-[11px] font-semibold tracking-[.04em] text-muted-foreground uppercase max-sm:text-xs', WRAP)}>{c.head}</dt>
                      <dd className={cn('m-0 text-xs text-foreground', c.align === 'right' && 'tabular-nums', WRAP)}>{c.cell(r)}</dd>
                    </div>
                  ))}
                </dl>
              )}
            </li>
          ))}
        </ul>
      )}
      <ListFooter hidden={hidden} open={open} onToggle={() => setOpen((o) => !o)} total={list.length} more={more} t={t} locale={locale} />
    </div>
  )
}

/** Liste altı: "Tümünü göster (N)" / "Daha az göster" ve sunucunun göndermediği satır notu. */
export function ListFooter({ hidden, open, onToggle, total, more, t, locale }) {
  if (!(hidden > 0) && !open && !(more > 0)) return null
  return (
    <div className="flex min-w-0 flex-wrap items-center gap-2">
      {(hidden > 0 || open) && total > AUDIT_LIST_PREVIEW && (
        <Button type="button" variant="ghost" size="sm" data-slot="prs-deep-more" aria-expanded={open} onClick={onToggle}
          className={cn('group h-auto w-fit px-2 py-1 text-xs', TOUCH)}>
          <ChevronDown aria-hidden="true" className={cn('transition-transform', open && 'rotate-180')} />
          {open ? t('prs.deep.showLess') : t('prs.deep.showAll', formatNumber(total, locale))}
        </Button>
      )}
      {more > 0 && (
        <span data-slot="prs-deep-capped" className={cn('text-xs text-muted-foreground', WRAP)}>
          {plural(t, 'prs.deep.serverMore', more, formatNumber(more, locale))}
        </span>
      )}
    </div>
  )
}

/** Basit adres listesi (yalnız URL'ler — ör. "boyutu yazılmamış görseller"). */
export function UrlList({ slot, urls, t, locale, preview = AUDIT_LIST_PREVIEW, more = 0, renderMeta, itemOf, onOpenItem }) {
  const [open, setOpen] = useState(false)
  const list = Array.isArray(urls) ? urls : []
  const shown = open ? list : list.slice(0, preview)
  return (
    <div data-slot={slot} className="flex min-w-0 flex-col gap-1.5">
      <ul className="m-0 flex list-none flex-col gap-1 p-0">
        {shown.map((r, i) => {
          const url = typeof r === 'string' ? r : r?.url
          return (
            <li key={`${url}#${i}`} data-slot="prs-deep-url" className="flex min-w-0 flex-col gap-0.5 rounded-md border px-3 py-1.5">
              <UrlCell url={url} t={t} item={itemOf ? itemOf(url) : null} onOpenItem={onOpenItem} />
              {renderMeta && <span className={cn('text-xs text-muted-foreground tabular-nums', WRAP)}>{renderMeta(r)}</span>}
            </li>
          )
        })}
      </ul>
      <ListFooter hidden={list.length - shown.length} open={open} onToggle={() => setOpen((o) => !o)} total={list.length} more={more} t={t} locale={locale} />
    </div>
  )
}

/** Küçük durum rozeti (sarar, kırpılmaz). */
export function StateChip({ tone = 'muted', children, className, ...rest }) {
  return <ToneBadge tone={tone} className={cn(CHIP, 'font-semibold', className)} {...rest}>{children}</ToneBadge>
}
