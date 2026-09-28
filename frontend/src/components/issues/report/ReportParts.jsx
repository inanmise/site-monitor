import { useId, useState } from 'react'
import { ChevronDown, CheckCircle2, ShieldCheck, OctagonAlert, Frown, Lightbulb, Info, X, Check } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import CopyableRef from '../../ui/CopyableRef.jsx'
import Field from '../../ui/Field.jsx'
import { IMPACTS, IMPACT_OTHER_MAX } from '../issuesModel.js'
import { IMPACT_ICONS } from '../impactIcons.js'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { RadioGroup, RadioGroupItem } from '@/components/shadcn/radio-group'
import { FieldSet, FieldLegend, FieldDescription, FieldLabel, Field as ShField, FieldContent, FieldTitle } from '@/components/shadcn/field'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'

/**
 * Sorun bildirimi formunun ortak parçaları (2026-09-27 yeniden tasarım) — oturum içi pencere ve giriş sayfası
 * penceresi aynı sakin, bölümlü düzeni kullanır: numaralı bölüm (FieldSet + FieldLegend), önem "seçim kartları"
 * (RadioGroup + FieldLabel — shadcn choice card deseni), katlanır "eklenecek teknik ayrıntılar", gizlilik notu ve
 * başarı ekranı (referans + kopyala + "sırada ne var").
 */

/**
 * Telefonda (<640 px) ModalShell kutusunu TAM EKRAN yapar: köşe/kenar yok, yükseklik 100dvh, altlık sabit (scrollBody).
 * Geniş ekranda ModalShell'in boyut sınıfı geçerli kalır.
 */
export const PHONE_FULLSCREEN = 'max-sm:top-0 max-sm:left-0 max-sm:h-[100dvh] max-sm:max-h-none max-sm:max-w-none max-sm:translate-x-0 max-sm:translate-y-0 max-sm:rounded-none max-sm:border-0 max-sm:p-4 max-sm:pb-[max(1rem,env(safe-area-inset-bottom))]'

/** Numaralı form bölümü — `optional` rozetli; açıklama FieldDescription. */
export function ReportSection({ n, title, hint, optional = false, children, className, ...rest }) {
  const t = useT()
  return (
    <FieldSet data-slot="report-section" className={cn('min-w-0 gap-3 border-0 p-0', className)} {...rest}>
      <FieldLegend variant="label" className="mb-0 flex items-center gap-2 text-sm font-semibold">
        <span aria-hidden="true" className="inline-grid size-6 shrink-0 place-items-center rounded-full bg-primary/10 text-xs font-bold text-primary tabular-nums">{n}</span>
        <span>{title}</span>
        {optional && <span className="text-xs font-normal text-muted-foreground">{t('irf.optional')}</span>}
      </FieldLegend>
      {hint && <FieldDescription className="-mt-1.5 text-xs">{hint}</FieldDescription>}
      {children}
    </FieldSet>
  )
}

const CATS = [
  { value: 'BLOCKER', Icon: OctagonAlert, title: 'issue.catBlocker', desc: 'irf.catBlockerDesc', ink: 'text-orange-600 dark:text-orange-400' },
  { value: 'ANNOYANCE', Icon: Frown, title: 'issue.catAnnoyance', desc: 'irf.catAnnoyanceDesc', ink: 'text-amber-600 dark:text-amber-400' },
  { value: 'SUGGESTION', Icon: Lightbulb, title: 'issue.catSuggestion', desc: 'irf.catSuggestionDesc', ink: 'text-violet-600 dark:text-violet-400' },
]

/**
 * Önem ("Ne kadar etkiliyor?") — KOMPAKT tekli seçim (2026-09-28): üç çip (ikon + başlık + radyo), telefonda alt alta
 * (≥40 px), geniş ekranda tek satır; SEÇİLENİN açıklaması altta görünür metin (ipucuna saklanmaz). Seçim opsiyoneldir:
 * seçiliyken "Seçimi temizle" düğmesi çıkar (radyo kendiliğinden boşaltılamaz). `value` '' = belirtilmedi.
 */
export function CategoryCards({ value, onChange, disabled = false }) {
  const t = useT()
  const base = useId()
  const selected = CATS.find((c) => c.value === value)
  return (
    <div className="flex min-w-0 flex-col gap-2">
      <RadioGroup value={value || ''} onValueChange={onChange} disabled={disabled} aria-label={t('issue.category')}
        data-slot="issue-category-cards" className="grid grid-cols-1 gap-2 sm:grid-cols-3">
        {CATS.map((c) => {
          const id = `${base}-${c.value}`
          return (
            <FieldLabel key={c.value} htmlFor={id} className="cursor-pointer">
              <ShField orientation="horizontal" className="min-h-10 items-center gap-2 px-3! py-2!">
                <c.Icon aria-hidden="true" className={cn('size-4 shrink-0', c.ink)} />
                <FieldContent className="gap-0">
                  <FieldTitle className="text-sm">{t(c.title)}</FieldTitle>
                </FieldContent>
                <RadioGroupItem value={c.value} id={id} />
              </ShField>
            </FieldLabel>
          )
        })}
      </RadioGroup>
      {selected && <FieldDescription data-slot="issue-category-desc" className="m-0 text-xs">{t(selected.desc)}</FieldDescription>}
      {value && (
        <Button type="button" variant="link" size="xs" className="h-auto self-start px-0 text-muted-foreground" onClick={() => onChange('')} disabled={disabled}>
          <X aria-hidden="true" />{t('irf.catClear')}
        </Button>
      )}
    </div>
  )
}

/**
 * "Ne yaşıyorsunuz?" — ÇOKLU seçim çipleri (2026-09-28): en sık görülen 12 durum (ikon + tam cümle), shadcn ToggleGroup
 * `type="multiple"` (her çip `aria-pressed`). Telefonda tek sütun tam genişlik (≥40 px dokunma), sm+ iki sütun. Seçim
 * kanonik sırada döner (IMPACTS). "Diğer" seçilince kısa serbest metin (≤200, sunucuyla aynı sınır).
 */
export function ImpactPicker({ value = [], onChange, other = '', onOther, disabled = false }) {
  const t = useT()
  return (
    <div className="flex min-w-0 flex-col gap-2">
      <ToggleGroup type="multiple" variant="outline" spacing={2} value={value} disabled={disabled}
        onValueChange={(v) => onChange(IMPACTS.filter((c) => v.includes(c)))} role="group" aria-label={t('irf.impactsLabel')}
        data-slot="issue-impact-picker" className="grid w-full grid-cols-1 gap-2 sm:grid-cols-2">
        {IMPACTS.map((c) => {
          const Icon = IMPACT_ICONS[c]
          return (
            <ToggleGroupItem key={c} value={c} data-impact={c}
              className="group/impact h-auto min-h-10 w-full justify-start gap-2 py-2 text-left font-normal whitespace-normal data-[state=on]:border-primary data-[state=on]:bg-primary/10 data-[state=on]:text-foreground">
              <Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground group-data-[state=on]/impact:text-primary" />
              <span className="min-w-0 flex-1">{t('issue.impact.' + c)}</span>
              <Check aria-hidden="true" className="size-4 shrink-0 text-primary opacity-0 group-data-[state=on]/impact:opacity-100" />
            </ToggleGroupItem>
          )
        })}
      </ToggleGroup>
      {value.includes('OTHER') && (
        <Field label={t('irf.impactOtherLabel')} className="mb-0">
          {({ id, describedBy }) => (
            <Input id={id} aria-describedby={describedBy} value={other} maxLength={IMPACT_OTHER_MAX} disabled={disabled}
              placeholder={t('irf.impactOtherPh')} onChange={(e) => onOther(e.target.value)} />
          )}
        </Field>
      )}
    </div>
  )
}

/**
 * "Eklenecek teknik ayrıntılar" — kullanıcıya SORULMAYAN, otomatik toplanan bağlam (kural 1: şeffaflık). Varsayılan
 * kapalı: formu sakin tutar, merak eden açar. `rows` = [[etiket, değer, mono?], …].
 */
export function TechnicalDetails({ rows, defaultOpen = false }) {
  const t = useT()
  const [open, setOpen] = useState(defaultOpen)
  return (
    <Collapsible open={open} onOpenChange={setOpen} data-slot="report-technical" className="rounded-lg border bg-muted/30">
      <CollapsibleTrigger asChild>
        <Button type="button" variant="ghost" aria-expanded={open}
          className="h-auto min-h-10 w-full justify-between gap-2 rounded-lg px-3 py-2 text-left text-sm font-medium whitespace-normal">
          <span className="flex min-w-0 items-center gap-2"><Info aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
            {t('irf.techTitle', rows.length)}</span>
          <ChevronDown aria-hidden="true" className={cn('size-4 shrink-0 transition-transform motion-reduce:transition-none', open && 'rotate-180')} />
        </Button>
      </CollapsibleTrigger>
      <CollapsibleContent>
        <dl className="m-0 grid grid-cols-[max-content_minmax(0,1fr)] gap-x-3 gap-y-1.5 px-3 pt-1 pb-3 text-[13px]">
          {rows.map(([k, v, mono]) => (
            <div key={k} className="contents">
              <dt className="text-muted-foreground">{k}</dt>
              <dd className={cn('m-0 min-w-0 [overflow-wrap:anywhere]', mono && 'font-mono text-xs')}>{v}</dd>
            </div>
          ))}
        </dl>
      </CollapsibleContent>
    </Collapsible>
  )
}

/** Gizlilik notu — ne eklendiğini ve ASLA ne eklenmediğini açıkça söyler. */
export function PrivacyNote({ children }) {
  return (
    <p data-slot="report-privacy" className="m-0 flex items-start gap-2 text-xs leading-relaxed text-muted-foreground">
      <ShieldCheck aria-hidden="true" className="mt-px size-4 shrink-0 text-success" />
      <span>{children}</span>
    </p>
  )
}

/**
 * Başarı ekranı — büyük onay, referans kodu (kopyalanabilir, tek metin düğümü), "sırada ne var" adımları.
 * Eylem düğmeleri çağıranın altlığında. `role="status"`: ekran okuyucu gönderimin tamamlandığını duyurur.
 */
export function ReportSuccess({ reference, steps, title }) {
  const t = useT()
  return (
    <div data-slot="report-success" role="status" className="flex min-w-0 flex-col items-center gap-4 px-1 py-4 text-center">
      <span aria-hidden="true" className="grid size-14 place-items-center rounded-full bg-success/12 text-success ring-8 ring-success/5">
        <CheckCircle2 className="size-8" />
      </span>
      <div className="flex flex-col gap-1">
        <div className="text-lg font-semibold">{title || t('issue.thanks')}</div>
        {reference && <p className="m-0 text-sm text-muted-foreground">{t('irf.refIntro')}</p>}
      </div>
      {reference && (
        <div data-slot="report-reference" className="flex items-center gap-1 rounded-lg border bg-muted/40 px-3 py-1.5 font-mono text-base font-semibold tracking-wide">
          <CopyableRef value={reference} copyLabel={t('irf.copyRef')} copiedLabel={t('irf.copied')} />
        </div>
      )}
      {steps?.length > 0 && (
        <div className="w-full max-w-md text-left">
          <div className="mb-2 text-xs font-bold tracking-wide text-muted-foreground uppercase">{t('irf.nextTitle')}</div>
          <ol className="m-0 flex list-none flex-col gap-2 p-0">
            {steps.map((s, i) => (
              <li key={i} className="flex items-start gap-2.5 text-sm">
                <span aria-hidden="true" className="mt-px inline-grid size-5 shrink-0 place-items-center rounded-full bg-primary/10 text-[11px] font-bold text-primary">{i + 1}</span>
                <span className="min-w-0 [overflow-wrap:anywhere]">{s}</span>
              </li>
            ))}
          </ol>
        </div>
      )}
    </div>
  )
}

/** Karakter sayacı — sınıra yaklaşınca amber, aşınca kırmızı. `aria-live` YOK (ModalShell içinde hideOthers tuzağı). */
export function CharCounter({ value, max, id }) {
  const n = String(value || '').length
  return (
    <span id={id} data-slot="char-counter" className={cn('text-xs tabular-nums text-muted-foreground',
      n > max * 0.9 && 'text-amber-600 dark:text-amber-400', n > max && 'font-semibold text-destructive')}>
      {n} / {max}
    </span>
  )
}
