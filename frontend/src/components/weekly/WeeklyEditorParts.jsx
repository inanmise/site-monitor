import { AlertTriangle, Check, CheckCircle2, Circle, CloudCheck, Eye, ListChecks, Lock, Save } from 'lucide-react'
import { useT, useLanguage } from '../../i18n/index.jsx'
import { formatDate } from '../../api/client'
import { ProgressBar, Spinner } from '../ui/Progress.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import { LinkChipList } from './WeeklyLinkField.jsx'
import { LINK_FIELDS, SECTION_KEYS, sortIssues } from './editorModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { cn } from '@/lib/utils'

/*
 * Haftalık rapor düzenleyicisinin yerleşim parçaları (2026-09-27 yeniden tasarım): bölüm kartı, ana hat (xl sağ
 * sütun) / sıkıştırılmış ilerleme (dar ekran), kayıt durumu göstergesi, gönderim öncesi kontrol listesi, rapor
 * ayrıntıları kartı. Durum sayfada; bunlar saf sunum.
 */

/** "1. Proaktif…" → "Proaktif…" (numara kartın dairesinde). */
export const sectionTitle = (t, key) => t(`wr.${key}Title`).replace(/^\d+\.\s*/, '')
export const sectionAnchor = (key) => `wr-sec-${key}`

/** Bölüme kaydır + başlığına odak (klavye / ekran okuyucu da oraya taşınır). */
export function jumpToSection(key) {
  const el = document.getElementById(sectionAnchor(key))
  if (!el) return
  try { el.scrollIntoView({ behavior: 'smooth', block: 'start' }) } catch { el.scrollIntoView?.() }
  el.querySelector('h3')?.focus?.({ preventScroll: true })
}

/** Bölüm kartı: numara + başlık + yardım metni + doluluk rozeti; yazdırırken her madde yeni sayfada (ilki hariç). */
export function SectionCard({ sectionKey, n, filled, description, children, className }) {
  const t = useT()
  return (
    <Card id={sectionAnchor(sectionKey)} data-slot="wr-section" data-section={sectionKey} data-filled={filled || undefined}
      className={cn('scroll-mt-4 gap-0 py-0 shadow-xs', n > 1 ? 'print:break-before-page' : 'print:break-before-avoid', className)}>
      <div className="flex min-w-0 items-start gap-3 border-b px-4 py-3 sm:px-5 print:break-after-avoid">
        <span aria-hidden="true" className={cn('inline-flex size-7 shrink-0 items-center justify-center rounded-full text-sm font-bold',
          filled ? 'bg-success/15 text-success' : 'bg-primary/10 text-primary')}>
          {filled ? <Check className="size-4" /> : n}
        </span>
        <div className="min-w-0 flex-1">
          <h3 tabIndex={-1} className="text-[15px] leading-snug font-semibold outline-none">
            <span className="sr-only">{n}. </span>{sectionTitle(t, sectionKey)}
          </h3>
          {description && <p className="mt-0.5 text-sm text-muted-foreground print:hidden">{description}</p>}
        </div>
        <Badge variant="outline" className={cn('shrink-0 rounded-full print:hidden', filled ? 'border-success/40 text-success' : 'text-muted-foreground')}>
          {filled ? t('wr.ed.filled') : t('wr.ed.empty')}
        </Badge>
      </div>
      <div className="flex min-w-0 flex-col gap-3 px-4 py-4 sm:px-5">{children}</div>
    </Card>
  )
}

/** xl sağ sütun: bölüm listesi (dolu ✓ / boş ○), ilerleme, bölüme atla. */
export function EditorOutline({ outline, className }) {
  const t = useT()
  const done = outline.filter((s) => s.filled).length
  return (
    <Card data-slot="wr-outline" className={cn('gap-0 py-0 shadow-xs', className)}>
      <div className="flex items-center justify-between gap-2 border-b px-4 py-3">
        <h3 className="flex items-center gap-2 text-sm font-semibold"><ListChecks aria-hidden="true" className="size-4 text-muted-foreground" /> {t('wr.ed.outline')}</h3>
        <span className="text-xs text-muted-foreground tabular-nums">{t('wr.ed.progress', done, outline.length)}</span>
      </div>
      <div className="px-4 pt-3"><ProgressBar value={done} max={outline.length} decorative tone={done === outline.length ? 'ok' : 'warn'} /></div>
      <ol className="flex flex-col p-2">
        {outline.map((s, i) => (
          <li key={s.key}>
            <Button type="button" variant="ghost" onClick={() => jumpToSection(s.key)}
              className="h-auto w-full justify-start gap-2 px-2 py-2 text-left font-normal whitespace-normal">
              {s.filled
                ? <CheckCircle2 aria-hidden="true" className="size-4 shrink-0 text-success" />
                : <Circle aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />}
              <span className="min-w-0 text-sm"><span className="text-muted-foreground tabular-nums">{i + 1}.</span> {sectionTitle(t, s.key)}</span>
              <span className="sr-only">{s.filled ? t('wr.ed.filled') : t('wr.ed.empty')}</span>
            </Button>
          </li>
        ))}
      </ol>
    </Card>
  )
}

/** Dar ekran (xl altı): üstte ince ilerleme + bölüm numaralı atlama düğmeleri. */
export function CompactProgress({ outline, className }) {
  const t = useT()
  const done = outline.filter((s) => s.filled).length
  return (
    <div data-slot="wr-progress" className={cn('flex min-w-0 flex-wrap items-center gap-x-3 gap-y-2 rounded-lg border bg-card px-3 py-2 print:hidden', className)}>
      <span className="text-xs font-semibold text-muted-foreground tabular-nums">{t('wr.ed.progress', done, outline.length)}</span>
      <div className="min-w-24 flex-1"><ProgressBar value={done} max={outline.length} decorative tone={done === outline.length ? 'ok' : 'warn'} /></div>
      <div className="flex gap-1">
        {outline.map((s, i) => (
          <Button key={s.key} type="button" variant="outline" size="icon" onClick={() => jumpToSection(s.key)}
            aria-label={t('wr.ed.jumpTo', `${i + 1}. ${sectionTitle(t, s.key)}`)} title={sectionTitle(t, s.key)}
            className={cn('size-8 rounded-full text-xs tabular-nums pointer-coarse:size-10', s.filled && 'border-success/50 bg-success/10 text-success')}>
            {s.filled ? <Check aria-hidden="true" /> : i + 1}
          </Button>
        ))}
      </div>
    </div>
  )
}

/**
 * Kayıt durumu: Salt okunur · Kaydediliyor… · Kaydedilmemiş değişiklikler (otomatik kayıt ≤ 1 dk) · Otomatik kaydedildi
 * HH:MM · Tüm değişiklikler kaydedildi. `aria-live` — durum değişimi duyurulur.
 */
export function SaveState({ editable, dirty, busy, lastAutoSave, conflict, compact = false }) {
  const t = useT()
  const { lang } = useLanguage()
  let tone = 'muted', Icon = CloudCheck, text = t('wr.ed.allSaved'), state = 'saved'
  if (!editable) { Icon = Lock; text = t('wr.ed.readOnly'); state = 'readonly' }
  else if (conflict) { tone = 'danger'; Icon = AlertTriangle; text = t('wr.ed.conflict'); state = 'conflict' }
  else if (busy) { Icon = null; text = t('wr.saving'); state = 'saving' }
  else if (dirty) { tone = 'warn'; Icon = Save; text = compact ? t('wr.ed.unsavedShort') : t('wr.ed.unsaved'); state = 'dirty' }
  else if (lastAutoSave) {
    tone = 'ok'; Icon = Check; state = 'autosaved'
    text = `${t('wr.autoSaved')} ${lastAutoSave.toLocaleTimeString(lang === 'en' ? 'en-GB' : 'tr-TR', { hour: '2-digit', minute: '2-digit' })}`
  }
  return (
    <span role="status" aria-live="polite" data-slot="wr-save-state" data-state={state}
      className={cn('inline-flex min-w-0 items-center gap-1.5 text-xs font-medium',
        tone === 'warn' && 'text-amber-700 dark:text-amber-400', tone === 'ok' && 'text-success',
        tone === 'danger' && 'text-destructive', tone === 'muted' && 'text-muted-foreground')}>
      {Icon ? <Icon aria-hidden="true" className="size-3.5 shrink-0" /> : <Spinner decorative size={13} />}
      <span className="truncate">{text}</span>
    </span>
  )
}

/** Gönderim öncesi kontrol listesi (açılır pencere) — boş bölümler, geçersiz bağlantılar, adsız kanallar; "Git" bölüme atlar. */
export function ValidationSummary({ issues, compact = false }) {
  const t = useT()
  const list = sortIssues(issues)
  if (!list.length) {
    return (
      <span data-slot="wr-validation" data-state="ok" className="inline-flex items-center gap-1.5 text-xs font-medium text-success">
        <CheckCircle2 aria-hidden="true" className="size-3.5" /> {compact ? t('wr.ed.readyShort') : t('wr.ed.ready')}
      </span>
    )
  }
  const text = (i) => {
    if (i.kind === 'empty') return t('wr.ed.issueEmpty', sectionTitle(t, i.section))
    if (i.kind === 'link') return t('wr.ed.issueLink', t(i.field))
    if (i.kind === 'statusMismatch') return t('wr.ed.issueStatusMismatch', i.sum, i.total)
    if (i.kind === 'channelDuplicate') return t('wr.ed.issueChannelDup', i.name)
    return t('wr.ed.issueChannel', i.index + 1)
  }
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button type="button" variant="ghost" size="sm" data-slot="wr-validation" data-state="issues"
          className="h-8 gap-1.5 px-2 text-xs font-semibold text-amber-700 hover:text-amber-800 dark:text-amber-400 pointer-coarse:h-10">
          <AlertTriangle aria-hidden="true" className="size-3.5" /> {t('wr.ed.issues', list.length)}
        </Button>
      </PopoverTrigger>
      <PopoverContent side="top" align="start" className="z-(--z-menu) w-80 max-w-[calc(100vw-2rem)] p-0">
        <div className="border-b px-3 py-2">
          <p className="text-sm font-semibold">{t('wr.ed.checkTitle')}</p>
          <p className="text-xs text-muted-foreground">{t('wr.ed.checkDesc')}</p>
        </div>
        <ul className="flex max-h-72 flex-col overflow-y-auto p-1.5">
          {list.map((i) => (
            <li key={i.id} data-kind={i.kind} className="flex items-center gap-2 rounded-md px-1.5 py-1 text-sm">
              <AlertTriangle aria-hidden="true" className={cn('size-3.5 shrink-0', i.kind === 'empty' ? 'text-muted-foreground' : 'text-amber-600 dark:text-amber-400')} />
              <span className="min-w-0 flex-1">{text(i)}</span>
              <Button type="button" variant="link" size="xs" className="h-8 shrink-0 px-1" onClick={() => jumpToSection(i.section)}
                aria-label={t('wr.ed.goTo', text(i))}>{t('wr.ed.go')}</Button>
            </li>
          ))}
        </ul>
      </PopoverContent>
    </Popover>
  )
}

/** Rapor ayrıntıları: durum geçmişi (oluşturan / son düzenleyen / gönderen / onaylayan / gönderim), bağlantılar, kim görür. */
export function ReportDetailsCard({ report, content, teamName, className, onPreview }) {
  const t = useT()
  const fact = (label, user, iso) => (
    <div className="flex min-w-0 flex-col gap-0.5">
      <dt className="text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">{label}</dt>
      <dd className="flex min-w-0 flex-wrap items-center gap-x-1.5 text-sm">
        {user ? <UserBadge username={user} inline size="sm" nameOnly /> : null}
        {iso ? <span className="text-xs text-muted-foreground">{formatDate(iso)}</span> : (!user && '—')}
      </dd>
    </div>
  )
  const links = LINK_FIELDS.map((f) => ({ key: `${f.section}.${f.key}`, label: t(f.label), url: content?.[f.section]?.[f.key] }))
  const hasLinks = links.some((l) => String(l.url ?? '').trim())
  return (
    <Card data-slot="wr-details" className={cn('gap-0 py-0 shadow-xs', className)}>
      <div className="border-b px-4 py-3"><h3 className="text-sm font-semibold">{t('wr.ed.details')}</h3></div>
      <dl className="grid grid-cols-1 gap-3 px-4 py-3 sm:grid-cols-2 xl:grid-cols-1">
        {fact(t('wr.colCreated'), report.created_by, report.created_at)}
        {fact(t('wr.colUpdated'), report.updated_by, report.updated_at)}
        {report.submitted_at && fact(t('wr.ed.submitted'), report.submitted_by, report.submitted_at)}
        {report.approved_by && fact(t('wr.colApproved'), report.approved_by, report.approved_at)}
        {report.sent_at && fact(t('wr.colSent'), null, report.sent_at)}
      </dl>
      {hasLinks && (
        <div className="border-t px-4 py-3">
          <p className="mb-2 text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">{t('wr.ed.links')}</p>
          <LinkChipList items={links} max={5} />
        </div>
      )}
      <div className="flex flex-col gap-2 border-t px-4 py-3">
        <p className="flex items-start gap-1.5 text-xs text-muted-foreground">
          <Eye aria-hidden="true" className="mt-px size-3.5 shrink-0" /> {t('wr.ed.visibility', teamName || '—')}
        </p>
        {onPreview && (
          <Button type="button" variant="outline" size="sm" className="self-start pointer-coarse:h-10" onClick={onPreview}>
            <Eye aria-hidden="true" /> {t('wr.preview')}
          </Button>
        )}
      </div>
    </Card>
  )
}

export { SECTION_KEYS }
