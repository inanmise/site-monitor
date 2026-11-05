import { useEffect, useId, useState } from 'react'
import { ChevronDown, FileText, Globe, Play, RotateCw } from 'lucide-react'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import Field from '../../ui/Field.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { defaultSitePages, siteLimits, sitePageChoices } from './resourceScanReportModel.js'
import { formatMs, formatNumber } from './resourceScanModel.js'
import { TOUCH, WRAP } from './ResourceScanParts.jsx'
import { Button } from '@/components/shadcn/button'
import { ButtonGroup } from '@/components/shadcn/button-group'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/shadcn/dropdown-menu'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { cn } from '@/lib/utils'

/**
 * Kaynak analizi → BAŞLATMA (prs3 G, 2026-11-04): bölünmüş düğme — ana düğme BUGÜNKÜ tek tıklık akış (bu sayfa; adı ve
 * `data-slot="prs-start"`'ı değişmez), yanındaki ok ikinci seçeneği sunar: "Siteyi analiz et (N sayfa)". Site seçeneği
 * küçük bir pencere açar: sayfa sayısı (2 … sunucu sınırı), ne yapılacağı ve süre sınırı. Eski sunucu gövdeyi
 * (`scope`) bilmez ve tek sayfa analizi başlatır — sekme bunu yanıttan anlar ve söyler (`siteScopeIgnored`).
 */
export default function ResourceScanStart({ hasFinished, running, starting, limits, onStartPage, onStartSite }) {
  const t = useT()
  const locale = useDateLocale()
  const [siteOpen, setSiteOpen] = useState(false)
  const { maxPages } = siteLimits(limits)
  const busy = starting || Boolean(running)
  const main = running ? t('prs.runningShort') : hasFinished ? t('prs.restart') : t('prs.start')
  return (
    <>
      <ButtonGroup aria-label={t('prs.site.start.group')} data-slot="prs-start-group" className="min-w-0 max-sm:min-w-full">
        <Button type="button" onClick={() => onStartPage?.()} disabled={busy} data-slot="prs-start" className={cn('min-w-0 flex-1 sm:flex-none', TOUCH)}
          aria-busy={starting || undefined}>
          {starting ? <Spinner size={14} inline decorative /> : hasFinished ? <RotateCw aria-hidden="true" /> : <Play aria-hidden="true" />}
          {main}
        </Button>
        <DropdownMenu modal={false}>
          <DropdownMenuTrigger asChild>
            <Button type="button" size="icon" data-slot="prs-start-more" disabled={busy} aria-label={t('prs.site.start.more')}
              title={t('prs.site.start.more')} className="shrink-0 border-l border-primary-foreground/25 max-sm:min-w-10 pointer-coarse:size-10">
              <ChevronDown aria-hidden="true" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="z-(--z-menu) w-[min(320px,calc(100vw-2rem))]">
            <DropdownMenuItem data-slot="prs-start-page" className="items-start gap-2.5 py-2 pointer-coarse:min-h-10" onSelect={() => onStartPage?.()}>
              <FileText aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
              <span className="flex min-w-0 flex-col gap-0.5">
                <span className="font-semibold">{t('prs.site.start.page')}</span>
                <span className={cn('text-xs text-muted-foreground', WRAP)}>{t('prs.site.start.pageHint')}</span>
              </span>
            </DropdownMenuItem>
            <DropdownMenuItem data-slot="prs-start-site" className="items-start gap-2.5 py-2 pointer-coarse:min-h-10"
              onSelect={() => setSiteOpen(true)}>
              <Globe aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
              <span className="flex min-w-0 flex-col gap-0.5">
                <span className="font-semibold">{t('prs.site.start.site', formatNumber(maxPages, locale))}</span>
                <span className={cn('text-xs text-muted-foreground', WRAP)}>{t('prs.site.start.siteHint', formatNumber(maxPages, locale))}</span>
              </span>
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </ButtonGroup>
      <SiteStartDialog open={siteOpen} onClose={() => setSiteOpen(false)} limits={limits} starting={starting}
        onConfirm={async (n) => { const ok = await onStartSite?.(n); if (ok !== false) setSiteOpen(false) }} />
    </>
  )
}

/** "Siteyi analiz et" penceresi: sayfa sayısı + ne yapılacağı. */
export function SiteStartDialog({ open, onClose, limits, starting, onConfirm }) {
  const t = useT()
  const locale = useDateLocale()
  const { maxPages, maxSeconds } = siteLimits(limits)
  const choices = sitePageChoices(maxPages)
  const [pages, setPages] = useState(() => defaultSitePages(maxPages))
  const hintId = useId()
  useEffect(() => { if (open) setPages(defaultSitePages(maxPages)) }, [open, maxPages])
  const footer = (
    <div className="flex w-full flex-wrap items-center justify-end gap-2">
      <Button type="button" variant="outline" className={TOUCH} onClick={onClose} disabled={starting}>{t('prs.site.dlg.cancel')}</Button>
      <Button type="button" data-slot="prs-site-confirm" className={TOUCH} disabled={starting} aria-busy={starting || undefined}
        onClick={() => onConfirm?.(pages)}>
        {starting ? <Spinner size={14} inline decorative /> : <Globe aria-hidden="true" />}{t('prs.site.dlg.confirm', formatNumber(pages, locale))}
      </Button>
    </div>
  )
  return (
    <ModalShell open={open} onClose={onClose} title={t('prs.site.dlg.title')} icon={Globe} size="sm" busy={starting} footer={footer}>
      <div data-slot="prs-site-dialog" className="flex min-w-0 flex-col gap-3">
        <p className={cn('m-0 text-sm text-muted-foreground', WRAP)}>{t('prs.site.dlg.intro')}</p>
        <Field label={t('prs.site.dlg.pages')} hint={t('prs.site.dlg.pagesHint', formatNumber(maxPages, locale))} className="mb-0">
          {({ id, describedBy }) => (
            <NativeSelect id={id} aria-describedby={[describedBy, hintId].filter(Boolean).join(' ')} data-slot="prs-site-pages"
              className="w-full pointer-coarse:h-10" value={String(pages)} onChange={(e) => setPages(Number(e.target.value))}>
              {choices.map((n) => <NativeSelectOption key={n} value={String(n)}>{t('prs.site.dlg.pagesOption', formatNumber(n, locale))}</NativeSelectOption>)}
            </NativeSelect>
          )}
        </Field>
        <ul id={hintId} data-slot="prs-site-facts" className="m-0 flex list-disc flex-col gap-1 pl-5 text-xs text-muted-foreground">
          <li className={WRAP}>{t('prs.site.dlg.factPages')}</li>
          <li className={WRAP}>{t('prs.site.dlg.factShared')}</li>
          <li className={WRAP}>{t('prs.site.dlg.factLinks')}</li>
          <li className={WRAP}>{t('prs.site.dlg.factTime', formatMs(maxSeconds * 1000, locale))}</li>
          <li className={WRAP}>{t('prs.site.dlg.factJs')}</li>
        </ul>
      </div>
    </ModalShell>
  )
}
