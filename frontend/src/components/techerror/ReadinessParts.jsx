import { forwardRef, useId } from 'react'
import { CircleAlert, CircleCheck, CircleHelp, ClipboardList, CornerDownRight } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'
import AlertBanner from '../ui/AlertBanner.jsx'
import { cn } from '@/lib/utils'
import { allFields, formatCount, formatValue, issueDisplayTitle, issueOptions } from './techErrorModel.js'
import { groupReadiness, readinessTone } from './readinessModel.js'

/**
 * HAZIRLIK REHBERİ ARAYÜZÜ (2026-11-20, B1 — kullanıcı onayı). Başlık çipi ("Görsel 12/15 · 2 boş alan · 1 uyarı"),
 * hazırlık paneli (bölüme göre gruplu kalemler + "Git"), gönderim / onaya gönderme onayındaki özet cümlesi. Eksikler
 * yalnız uyarır; hiçbir eylemi engellemez. Bilinmeyen girdi (görsel listesi / alıcılar okunamadı) asla "hazır" (yeşil)
 * gösterilmez.
 */

const TONE_CLASS = {
  ok: 'border-success/50 text-foreground',
  warn: 'border-warning/60 text-foreground',
  unknown: 'border-border text-muted-foreground',
}
const TONE_ICON = { ok: CircleCheck, warn: CircleAlert, unknown: CircleHelp }
const TONE_ICON_CLASS = { ok: 'text-success', warn: 'text-warning', unknown: 'text-muted-foreground' }

/** Çipin metni: görsel oranı · boş alan · uyarı; hiç kalem yoksa "hazır" (biliniyorsa) ya da "denetlenemedi". */
export function readinessSummaryText(r, t) {
  if (!r) return t('te.ready.unknown')
  const parts = []
  if (r.images && r.images.total > 0) {
    parts.push(r.images.filled == null ? t('te.ready.imagesUnknown', r.images.total) : t('te.ready.images', r.images.filled, r.images.total))
  }
  if (r.empty > 0) parts.push(t(r.empty === 1 ? 'te.ready.empty.one' : 'te.ready.empty', r.empty))
  if (r.warnings > 0) parts.push(t(r.warnings === 1 ? 'te.ready.warnings.one' : 'te.ready.warnings', r.warnings))
  if (r.items.length === 0) parts.push(r.ready ? t('te.ready.ok') : t('te.ready.unknown'))
  return parts.join(' · ')
}

/**
 * Gönderim / onaya gönderme onayındaki özet cümlesi (engellemez): kalem varsa "Raporda N eksik var (…) — yine de …";
 * kalem yoksa null (bugünkü metin aynen).
 */
export function readinessConfirmText(r, t, kind) {
  if (!r || r.items.length === 0) return null
  const parts = []
  if (r.empty > 0) parts.push(t(r.empty === 1 ? 'te.ready.empty.one' : 'te.ready.empty', r.empty))
  const slots = Number(r.emptySlots) || 0
  if (slots > 0) parts.push(t(slots === 1 ? 'te.ready.emptySlots.one' : 'te.ready.emptySlots', slots))
  if (r.warnings > 0) parts.push(t(r.warnings === 1 ? 'te.ready.warnings.one' : 'te.ready.warnings', r.warnings))
  const n = r.items.length
  const list = parts.join(', ')
  // Tekil anahtar sayıyı metinde taşır; yer tutucular {0}'dan başlar (i18n eşlik kapısı)
  if (n === 1) return t(kind === 'approval' ? 'te.ready.confirmApproval.one' : 'te.ready.confirmSend.one', list)
  return t(kind === 'approval' ? 'te.ready.confirmApproval' : 'te.ready.confirmSend', n, list)
}

/** Başlıktaki hazırlık çipi: tıklanınca panel açılır. Ton yalnız her şey biliniyor ve kalem yokken "tamam". */
export const ReadinessChip = forwardRef(function ReadinessChip({ readiness, onOpen }, ref) {
  const t = useT()
  const tone = readinessTone(readiness)
  const Icon = TONE_ICON[tone]
  const text = readinessSummaryText(readiness, t)
  return (
    <Button ref={ref} type="button" variant="outline" size="sm" onClick={onOpen} data-slot="te-ready-chip" data-tone={tone}
      aria-haspopup="dialog" aria-label={t('te.ready.chipAria', text)}
      className={cn('h-auto min-h-8 whitespace-normal py-1.5 text-left pointer-coarse:min-h-10', TONE_CLASS[tone])}>
      <Icon aria-hidden="true" className={TONE_ICON_CLASS[tone]} />
      <span className="[overflow-wrap:anywhere]">{text}</span>
    </Button>
  )
})

/**
 * Kalemin metni (panel satırı). `ctx`: `{ t, template, resolve, values, previous, uiLang }` — alan etiketleri, birimler,
 * konu başlıkları için.
 */
export function readinessItemText(item, ctx) {
  const { t, template, resolve, values, previous, uiLang } = ctx
  const label = (raw) => (typeof raw === 'string' && raw.trim() ? resolve(raw) : t('te.tplLoss.unnamed'))
  const yesNo = (b) => (b ? t('te.yes') : t('te.no'))
  switch (item.kind) {
    case 'field':
      return item.reason === 'empty' ? t('te.ready.item.empty', label(item.label)) : t('te.ready.item.calc', label(item.label))
    case 'slot':
      return t('te.ready.item.slot', label(item.label))
    case 'topic': {
      const sec = (template?.sections || []).find((s) => s.id === item.sectionId)
      const issue = (values?.issues?.[item.sectionId] || []).find((i) => i?.id === item.issueId)
      const title = (issue && issueDisplayTitle(issue, sec?.item_title)) || t('te.iss.topicN', (item.index ?? 0) + 1)
      const part = item.part === 'outage_id' ? resolve(issueOptions(sec, template?.lang).outageIdLabel)
        : t(item.part === 'start' ? 'te.iss.start' : item.part === 'end' ? 'te.iss.end' : 'te.iss.date')
      return t('te.ready.item.topic', part, title)
    }
    case 'line':
      return t('te.ready.item.line', (item.labels || []).map((l) => label(l)).join(', '))
    case 'scale': {
      const f = allFields(template).find((x) => x.id === item.fieldId) || { type: 'number' }
      const cur = formatValue(f, item.cur, uiLang, yesNo, values?.units?.[item.fieldId] ?? null)
      const prev = formatValue(f, item.prev, uiLang, yesNo, previous?.units?.[item.fieldId] ?? null)
      return t('te.ready.item.scale', label(item.label), prev, cur)
    }
    case 'issueSum':
      return t('te.ready.issueSum', formatCount(item.sum, uiLang), formatCount(item.total, uiLang, values?.units?.[item.ref] ?? null))
    case 'outage':
      return t(item.count === 1 ? 'te.ready.item.outage.one' : 'te.ready.item.outage', label(item.label), item.count)
    case 'teamEmail':
      return t('te.ready.item.teamEmail')
    case 'noApprover':
      return t('te.ready.item.noApprover')
    default:
      return ''
  }
}

function Group({ group, ctx, onGo }) {
  const { t, template, resolve } = ctx
  const id = useId()
  const sec = group.sectionId ? (template?.sections || []).find((s) => s.id === group.sectionId) : null
  const title = group.sectionId ? (sec?.title ? resolve(sec.title) : t('te.tplLoss.unnamed')) : t('te.ready.group.recipients')
  return (
    <div className="flex min-w-0 flex-col gap-2" data-slot="te-ready-group" data-section={group.sectionId || ''}>
      <h3 id={id} className="m-0 text-sm font-semibold [overflow-wrap:anywhere]">{title}</h3>
      <ul aria-labelledby={id} className="m-0 flex list-none flex-col gap-2 p-0">
        {group.items.map((it, i) => {
          const text = readinessItemText(it, ctx)
          return (
            <li key={`${it.kind}:${it.fieldId || it.slotId || it.issueId || it.index || ''}:${it.part || ''}:${i}`}
              className="flex min-w-0 items-start gap-2 rounded-md border p-2" data-slot="te-ready-item" data-kind={it.kind}
              data-severity={it.severity}>
              <CornerDownRight aria-hidden="true" className={cn('mt-0.5 size-4 shrink-0',
                it.severity === 'warning' ? 'text-warning' : 'text-muted-foreground')} />
              <span className="min-w-0 flex-1 text-sm [overflow-wrap:anywhere]">{text}</span>
              <Button type="button" variant="outline" size="sm" className="shrink-0 pointer-coarse:min-h-10" data-slot="te-ready-go"
                aria-label={t('te.ready.goAria', text)} onClick={() => onGo(it)}>
                {t('te.ready.go')}
              </Button>
            </li>
          )
        })}
      </ul>
    </div>
  )
}

/** Hazırlık paneli: kalemler bölüme göre, şablon sırasıyla; "Git" paneli kapatıp alana / yuvaya / konuya götürür. */
export function ReadinessSheet({ open, onClose, readiness, template, resolve, values, previous, uiLang, onGo, returnFocusRef,
  suppressReturnRef = null }) {
  const t = useT()
  const r = readiness
  const groups = groupReadiness(r?.items)
  const ctx = { t, template, resolve, values, previous, uiLang }
  return (
    <Sheet open={open} onOpenChange={(o) => { if (!o) onClose() }}>
      <SheetContent side="right" className="w-full gap-0 sm:max-w-lg" data-slot="te-ready-sheet"
        onCloseAutoFocus={(e) => {
          // "Git" ile kapandıysa odak hedef alanda kalır (çipe dönmez)
          if (suppressReturnRef?.current) { suppressReturnRef.current = false; e.preventDefault(); return }
          if (returnFocusRef?.current) { e.preventDefault(); returnFocusRef.current.focus() }
        }}>
        <SheetHeader className="border-b pe-12">
          <SheetTitle className="flex items-center gap-2"><ClipboardList aria-hidden="true" className="size-4" />{t('te.ready.title')}</SheetTitle>
          <SheetDescription>{t('te.ready.desc')}</SheetDescription>
        </SheetHeader>
        <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto p-4" data-slot="te-ready-body">
          <p className="m-0 text-sm font-medium [overflow-wrap:anywhere]" data-slot="te-ready-summary">{readinessSummaryText(r, t)}</p>
          {r && !r.known && (
            <AlertBanner tone="info" title={t('te.ready.unknownTitle')}>{t('te.ready.unknownBody')}</AlertBanner>
          )}
          {r?.ready && (
            <p className="m-0 flex items-center gap-2 text-sm" data-slot="te-ready-ok">
              <CircleCheck aria-hidden="true" className="size-4 text-success" /> {t('te.ready.okBody')}
            </p>
          )}
          {groups.map((g) => <Group key={g.sectionId || '_recipients'} group={g} ctx={ctx} onGo={onGo} />)}
        </div>
      </SheetContent>
    </Sheet>
  )
}

/** Yeni (boş) raporda "Sonraki adım" ipucu: ilk eksik kaleme götürür. */
export function NextStepHint({ readiness, template, resolve, onGo }) {
  const t = useT()
  const first = readiness?.items?.find((i) => i.sectionId) || null
  if (!first) return null
  const sec = (template?.sections || []).find((s) => s.id === first.sectionId)
  const title = sec?.title ? resolve(sec.title) : t('te.tplLoss.unnamed')
  return (
    <div data-slot="te-next-step">
      <AlertBanner tone="info" role="note" title={t('te.ready.nextTitle')}
        actions={(
          <Button type="button" size="sm" variant="outline" className="pointer-coarse:min-h-10" data-slot="te-next-step-go"
            onClick={() => onGo(first)}>
            {t('te.ready.nextGo')}
          </Button>
        )}>
        {t('te.ready.nextBody', title)}
      </AlertBanner>
    </div>
  )
}
