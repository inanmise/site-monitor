import { useEffect, useRef, useState } from 'react'
import {
  ArrowDown, ArrowUp, CalendarClock, EyeOff, FileText, Hash, ImageIcon, Plus, ScrollText, Tag, Trash2, Users, Wand2, Zap,
} from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import DateTimeField from '../ui/DateTimeField.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Input } from '@/components/shadcn/input'
import { Checkbox } from '@/components/shadcn/checkbox'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'
import { NumberInput, issueErrorKey } from './ReportSections.jsx'
import { RichText, RichTextarea } from './RichText.jsx'
import { CopyTextButton } from './TechErrorParts.jsx'
import { CollapsibleCard } from './SectionCollapse.jsx'
import { ImageSlotTile } from './ReportImages.jsx'
import {
  ISSUE_IMAGES, ISSUE_LIMITS, LIMITS, allFields, formatCount, formatPercent, issueDayLabel, issueDisplayTitle, issueHidden,
  issueLeadText, issueLegacy, issueOptions, issueRangeLabel, issueShare, issueSlotId, issueUnitKey, move, newIssue, reportMonthBounds,
  resolveIssueTitle,
} from './techErrorModel.js'

/**
 * ANOMALİ KONULARI (2026-10-18, kullanıcı isteği: "her konu için başlık; NE ZAMAN — gün + saat aralığı; kaç teknik hata ve tüm
 * teknik hatalar içindeki payı (otomatik); kesinti kaynaklı mı — Evet ise Kesinti ID zorunlu; müşteri etkisi var mı — yoksa
 * gösterilmesin; isteğe bağlı görseller; rapor metni bunlara göre üretilsin").
 *
 * <p>Her konu bir KART: başlık + durum çipleri (zaman, adet + pay, kesinti + ID, müşteri etkisi, "rapora girmeyecek"), altında
 * gruplanmış alanlar — Konu, Ne zaman? (takvim + iki saat + "ertesi gün"), Teknik hata (adet + CANLI pay — taslağa yazılmaz,
 * sunucu kaydederken hesaplar), Kesinti (Evet / Hayır; Evet'te ID alanı açılır, zorunlu), Müşteri etkisi (Evet'te müşteri
 * sayısı açılır), rapordaki metnin önizlemesi (PDF / e-postadaki giriş metninin aynısı), Açıklama ve Görseller (en çok iki,
 * tam genişlik, açıklamalı). Hangi grubun sorulacağı şablondan gelir ({@code issueOptions}); hiçbir soru açık değilse eski
 * düzen (müşteri + hata adedi). Hata anahtarları konunun KİMLİĞİYLE: istemci `issue:<bölüm>:<konu>:<alan>`, sunucu
 * `issues.<bölüm>.<konu>.<alan>` — konu taşınınca hata başka konuya kaymaz.
 */
export function IssuesBlock({ section, template, values, shownFields, resolve, uiLang, readOnly, errors, onIssues, onFieldError,
  onUnit = null, reportId, images = [], onImagesChange, period, beforeIssueUpload }) {
  const t = useT()
  const list = values.issues[section.id] || []
  const set = (fn) => onIssues(section.id, fn(list))
  const opts = issueOptions(section, template?.lang)
  const legacy = issueLegacy(opts)
  const hidden = list.filter((i) => issueHidden(section, i, template?.lang)).length
  return (
    <CollapsibleCard sectionId={section.id} slot="te-issues" title={resolve(section.title)} contentClassName="flex flex-col gap-4"
      hint={<p className="text-sm text-muted-foreground">{t(legacy ? 'te.issues.hint' : 'te.iss.hint')}</p>}>
      {list.length === 0 && <p className="m-0 text-sm text-muted-foreground" data-slot="te-issues-empty">{t('te.issues.empty')}</p>}
      {hidden > 0 && (
        <p className="m-0 flex items-start gap-2 text-sm text-muted-foreground" data-slot="te-issues-hidden-note" role="status">
          <EyeOff aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          <span className="[overflow-wrap:anywhere]">{t('te.iss.hiddenCount', hidden)}</span>
        </p>
      )}
      <ol className="m-0 flex list-none flex-col gap-4 p-0">
        {list.map((iss, idx) => (
          <IssueCard key={iss.id} section={section} template={template} opts={opts} legacy={legacy} issue={iss} index={idx}
            count={list.length} shownFields={shownFields} resolve={resolve} uiLang={uiLang} readOnly={readOnly} errors={errors}
            onFieldError={onFieldError} reportId={reportId} images={images} onImagesChange={onImagesChange} period={period}
            beforeIssueUpload={beforeIssueUpload} onUnit={onUnit}
            errorsUnit={values.units?.[issueUnitKey(section.id, iss.id, 'errors')] ?? null}
            customersUnit={values.units?.[issueUnitKey(section.id, iss.id, 'customers')] ?? null}
            onPatch={(p) => set((l) => l.map((i) => (i.id === iss.id ? { ...i, ...p } : i)))}
            onMove={(delta) => set((l) => move(l, idx, delta))}
            onRemove={() => set((l) => l.filter((x) => x.id !== iss.id))} />
        ))}
      </ol>
      {!readOnly && (
        <Button type="button" variant="outline" className="w-fit pointer-coarse:min-h-10" disabled={list.length >= LIMITS.issues}
          onClick={() => set((l) => [...l, newIssue(l)])} data-slot="te-add-issue">
          <Plus aria-hidden="true" /> {t('te.issues.add')}
        </Button>
      )}
    </CollapsibleCard>
  )
}

/**
 * Kart içi alan grubu: simgeli başlık + içerik; gruplar arasında ince çizgi. Bölge adı (aria-labelledby) BİLEREK yok: grup
 * başlığı alanın etiketiyle aynı olabilir (açıklama) — etiketle arama iki öğe bulmasın, sayfada 30 adsız bölge oluşmasın.
 */
function Group({ icon: Icon, title, slot, children, first = false, copy = null }) {
  return (
    <section data-slot={slot} className={cn('flex min-w-0 flex-col gap-3', !first && 'border-t pt-4')}>
      <div data-slot="te-copy-row" className="text-sm leading-snug [overflow-wrap:anywhere]">
        <h4 className="inline-flex items-center gap-2 align-middle text-sm font-semibold">
          <Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
          <span>{title}</span>
        </h4>
        {copy ? <CopyTextButton text={copy} /> : null}
      </div>
      {children}
    </section>
  )
}

/** Alan altı hata (sunucu ya da istemci) — rol alert, kimliği girişin aria-describedby'ında. */
function FieldError({ id, text }) {
  if (!text) return null
  return <p id={id} className="m-0 text-sm text-destructive [overflow-wrap:anywhere]" role="alert">{text}</p>
}

/** Evet / Hayır (shadcn ToggleGroup — rapordaki evet/hayır alanlarıyla aynı). Seçiliye yeniden basmak cevabı siler. */
function YesNo({ labelledBy, value, onChange, disabled, describedBy, invalid, t }) {
  return (
    <ToggleGroup type="single" variant="outline" disabled={disabled} value={value === true ? 'yes' : value === false ? 'no' : ''}
      aria-labelledby={labelledBy} aria-describedby={describedBy} aria-invalid={invalid || undefined} className="w-fit"
      onValueChange={(x) => onChange(x === 'yes' ? true : x === 'no' ? false : null)}>
      <ToggleGroupItem value="yes" className="px-4 pointer-coarse:min-h-10">{t('te.yes')}</ToggleGroupItem>
      <ToggleGroupItem value="no" className="px-4 pointer-coarse:min-h-10">{t('te.no')}</ToggleGroupItem>
    </ToggleGroup>
  )
}

/** Payın notu: formül ya da neden hesaplanamadığı (sahte %0 yok). */
function shareNote(res, totalLabel, errorsLabel, t) {
  switch (res?.status) {
    case 'OK': return t('te.iss.shareFormula', errorsLabel, totalLabel)
    case 'MISSING': return res.missing === 'errors' ? t('te.iss.shareMissingCount') : t('te.iss.shareMissingTotal', totalLabel)
    case 'ZERO': return t('te.iss.shareZero', totalLabel)
    case 'OVER': return t('te.iss.shareOver', totalLabel)
    case 'EXEMPT': return t('te.iss.shareExempt', totalLabel)
    default: return t('te.iss.shareBadRef')
  }
}

const REVEAL = 'animate-in fade-in-0 slide-in-from-top-1 duration-200 motion-reduce:animate-none'

function IssueCard({ section, template, opts, legacy, issue: iss, index: idx, count, shownFields, resolve, uiLang, readOnly, errors,
  onFieldError, reportId, images, onImagesChange, period, beforeIssueUpload, onPatch, onMove, onRemove, onUnit = null,
  errorsUnit = null, customersUnit = null }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const sid = section.id
  const base = `te-iss-${sid}-${iss.id}`
  const pattern = section.item_title
  const shownTitle = issueDisplayTitle(iss, pattern)
  const tplLang = template?.lang === 'en' ? 'en' : 'tr'
  // Sunucu hatası konunun kimliği + alanıyla gelir; eski sunucu sıra numarasıyla (genel hata, açıklamanın altında)
  const err = (f) => errors[issueErrorKey(sid, iss.id, f)] || errors[`issues.${sid}.${iss.id}.${f}`] || null
  const legacyErr = errors[`issues.${sid}.${idx + 1}`] || null
  const fieldKey = (f) => issueErrorKey(sid, iss.id, f)
  const share = issueShare(section, iss, template, shownFields)
  const totalField = opts.ratioOf ? allFields(template).find((f) => f.id === opts.ratioOf) : null
  const totalLabel = resolve(totalField?.label || opts.ratioOf || '')
  // Kısaltmayla girilmiş adet (2026-10-18): "1,25M" çipte ve giriş metninde de "1,25M"; pay SAYIYLA (issueShare)
  const lead = legacy ? '' : issueLeadText(section, iss, share?.status === 'OK' ? share.value : null, tplLang, resolve, errorsUnit, customersUnit)
  const isHidden = issueHidden(section, iss, tplLang)
  const bounds = period ? reportMonthBounds(period.year, period.month) : null
  const myImages = opts.allowImages
    ? Array.from({ length: ISSUE_IMAGES }, (_, k) => images.find((i) => i.slot_id === issueSlotId(sid, iss.id, k + 1)) || null)
    : []
  const imageCount = images.filter((i) => String(i.slot_id || '').startsWith(`iss:${sid}:${iss.id}:`)).length

  // Evet seçilince açılan zorunlu / ilgili alana odak (boşsa) — klavye kullanıcısı bir sonraki adımı aramasın
  const outageIdRef = useRef(null)
  const customersRef = useRef(null)
  const [focusNext, setFocusNext] = useState(null)
  useEffect(() => {
    if (!focusNext) return
    const el = focusNext === 'outage_id' ? outageIdRef.current : customersRef.current?.querySelector('input')
    el?.focus?.({ preventScroll: false })
    setFocusNext(null)
  }, [focusNext])

  async function remove() {
    // Görseli olan konu: kayıtta görselleri de kalıcı olarak silinir — sorulur
    if (imageCount > 0) {
      const ok = await showConfirm({ title: t('te.iss.removeTitle'), message: t('te.iss.removeBody', shownTitle || idx + 1, imageCount),
        variant: 'danger', confirmText: t('te.iss.removeConfirm'), cancelText: t('te.cancel') })
      if (!ok) return
    }
    for (const f of ['customers', 'errors', 'date', 'start', 'end', 'outage_id', 'outage', 'impact']) onFieldError(fieldKey(f), null)
    onRemove()
  }

  const chips = []
  if (!legacy && opts.askTime) {
    const day = issueDayLabel(iss.date, uiLang)
    const range = issueRangeLabel(iss, uiLang)
    if (day) chips.push({ key: 'when', icon: CalendarClock, text: range ? `${day} · ${range}` : day })
  }
  if (iss.errors != null) {
    const n = formatCount(iss.errors, uiLang, errorsUnit)
    chips.push({ key: 'count', icon: Hash, text: share?.status === 'OK' ? t('te.iss.chip.countShare', n, formatPercent(share.value, uiLang)) : t('te.iss.chip.count', n) })
  }
  if (!legacy && opts.askOutage && (iss.outage === true || iss.outage === false)) {
    const id = typeof iss.outage_id === 'string' ? iss.outage_id.trim() : ''
    chips.push({ key: 'outage', icon: Zap, tone: iss.outage ? 'warn' : null,
      text: iss.outage ? (id ? t('te.iss.chip.outage', id) : t('te.iss.chip.outageNoId')) : t('te.iss.chip.noOutage') })
  }
  if (!legacy && opts.askImpact && (iss.impact === true || iss.impact === false)) {
    chips.push({ key: 'impact', icon: Users,
      text: iss.impact ? (iss.customers != null ? t('te.iss.chip.customers', formatCount(iss.customers, uiLang, customersUnit)) : t('te.iss.chip.impactYes'))
        : t('te.iss.chip.noImpact') })
  } else if ((legacy || !opts.askImpact) && iss.customers != null) {
    chips.push({ key: 'impact', icon: Users, text: t('te.iss.chip.customers', formatCount(iss.customers, uiLang, customersUnit)) })
  }
  if (isHidden) chips.push({ key: 'hidden', icon: EyeOff, tone: 'muted', text: t('te.iss.chip.hidden') })

  const numberField = (key, label, extra = {}) => {
    const fe = err(key)
    const errId = fe ? `${base}-${key}-e` : undefined
    return (
      <div className="flex min-w-0 flex-col gap-1.5" data-field={fieldKey(key)} ref={key === 'customers' ? customersRef : undefined}
        {...extra}>
        <div data-slot="te-copy-row" className="text-sm leading-snug [overflow-wrap:anywhere]">
          <label htmlFor={`${base}-${key}`} className="inline text-sm font-medium">{resolve(label)}</label>
          <CopyTextButton text={resolve(label)} />
        </div>
        <NumberInput id={`${base}-${key}`} value={iss[key] ?? null} lang={uiLang} disabled={readOnly}
          invalid={fe || legacyErr ? true : undefined} describedBy={errId || (legacyErr ? `${base}-e` : undefined)}
          // Kısaltma üssü (2026-10-18): "1,25M" kayıtta da kısaltmasıyla kalır; sıfır işareti konu adedinde YOK (karar)
          unit={onUnit ? (key === 'errors' ? errorsUnit : customersUnit) : undefined}
          onChange={(n, exp) => {
            onPatch({ [key]: n })
            onUnit?.(issueUnitKey(sid, iss.id, key), n == null ? null : exp)
          }} pendingKey={fieldKey(key)}
          onError={(k) => onFieldError(fieldKey(key), k ? t(k) : null)} />
        <FieldError id={errId} text={fe} />
      </div>
    )
  }

  const shareBlock = opts.ratioOf ? (
    <div className="flex min-w-0 flex-col gap-1.5" data-slot="te-issue-share" data-status={share?.status || 'NONE'}>
      <span id={`${base}-share-l`} className="text-sm font-medium [overflow-wrap:anywhere]">{t('te.iss.share')}</span>
      <div className="flex min-h-9 min-w-0 flex-wrap items-center gap-2 rounded-md border border-dashed bg-muted/40 px-3 py-1.5 pointer-coarse:min-h-10">
        <output aria-labelledby={`${base}-share-l`} aria-describedby={`${base}-share-n`} className="text-base font-semibold tabular-nums"
          data-slot="te-issue-share-value">
          {share?.status === 'OK' ? formatPercent(share.value, uiLang) : '—'}
        </output>
        <Badge variant="secondary" className="rounded-md overflow-visible">{t('te.calc.auto')}</Badge>
      </div>
      <p id={`${base}-share-n`} className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-issue-share-note">
        {shareNote(share, totalLabel, resolve(section.errors_label), t)}
      </p>
    </div>
  ) : null

  const dateErr = err('date')
  const startErr = err('start')
  const endErr = err('end')
  const idErr = err('outage_id')
  return (
    <li className={cn('rounded-xl border bg-card p-3 sm:p-4', isHidden && 'border-dashed')} data-slot="te-issue" data-issue={iss.id}
      data-hidden={isHidden ? 'true' : undefined} data-field={`issues.${sid}.${idx + 1}`}>
      <div className="flex items-start justify-between gap-2">
        <div className="flex min-w-0 flex-1 flex-col gap-2">
          <p className="m-0 text-sm font-semibold leading-snug [overflow-wrap:anywhere] sm:text-base" data-slot="te-issue-title">
            {idx + 1}. {shownTitle || t('te.issues.untitled')}
          </p>
          {chips.length > 0 && (
            <ul className="m-0 flex list-none flex-wrap gap-1.5 p-0" data-slot="te-issue-chips" aria-label={t('te.iss.chipsLabel')}>
              {chips.map((c) => (
                <li key={c.key} className="min-w-0 max-w-full">
                  <Badge variant="outline" data-chip={c.key}
                    className={cn('h-auto max-w-full justify-start gap-1 whitespace-normal rounded-md py-1 text-left font-normal leading-snug overflow-visible [overflow-wrap:anywhere]',
                      c.tone === 'warn' && 'border-amber-300 text-amber-900 dark:border-amber-800 dark:text-amber-200',
                      c.tone === 'muted' && 'text-muted-foreground')}>
                    <c.icon aria-hidden="true" className="size-3.5 shrink-0" />
                    <span className="min-w-0">{c.text}</span>
                  </Badge>
                </li>
              ))}
            </ul>
          )}
        </div>
        {!readOnly && (
          <div className="flex shrink-0 items-center gap-0.5">
            <Button type="button" size="icon" variant="ghost" className="size-8 pointer-coarse:size-10" disabled={idx === 0}
              aria-label={t('te.moveUp', shownTitle || idx + 1)} onClick={() => onMove(-1)}><ArrowUp aria-hidden="true" /></Button>
            <Button type="button" size="icon" variant="ghost" className="size-8 pointer-coarse:size-10" disabled={idx === count - 1}
              aria-label={t('te.moveDown', shownTitle || idx + 1)} onClick={() => onMove(1)}><ArrowDown aria-hidden="true" /></Button>
            <Button type="button" size="icon" variant="ghost" className="size-8 text-destructive pointer-coarse:size-10"
              aria-label={t('te.issues.remove', shownTitle || idx + 1)} onClick={remove} data-slot="te-issue-remove">
              <Trash2 aria-hidden="true" />
            </Button>
          </div>
        )}
      </div>

      <div className="mt-4 flex flex-col gap-4">
        <Group icon={Tag} title={t('te.iss.groupTopic')} slot="te-issue-topic" first>
          <div className="grid gap-3 md:grid-cols-2">
            <div className="flex min-w-0 flex-col gap-1.5" data-field={fieldKey('topic')}>
              <label htmlFor={`${base}-topic`} className="text-sm font-medium">{t('te.issues.topic')}</label>
              <Input id={`${base}-topic`} className="pointer-coarse:h-10" value={iss.topic || ''} disabled={readOnly} maxLength={LIMITS.topic}
                placeholder={t('te.issues.topicPlaceholder')} aria-invalid={err('topic') ? true : undefined}
                onChange={(e) => {
                  const topic = e.target.value
                  onPatch(iss.title_auto !== false ? { topic, title: resolveIssueTitle(pattern, topic), title_auto: true } : { topic })
                }} />
              <FieldError id={`${base}-topic-e`} text={err('topic')} />
            </div>
            <div className="flex min-w-0 flex-col gap-1.5" data-field={fieldKey('title')}>
              <div className="flex flex-wrap items-center justify-between gap-1">
                <label htmlFor={`${base}-title`} className="text-sm font-medium">{t('te.issues.title')}</label>
                {iss.title_auto !== false ? (
                  <Badge variant="secondary" className="rounded-md overflow-visible">{t('te.issues.auto')}</Badge>
                ) : !readOnly && (
                  <Button type="button" size="sm" variant="ghost" className="h-7 px-2 text-xs pointer-coarse:h-10"
                    onClick={() => onPatch({ title: resolveIssueTitle(pattern, iss.topic), title_auto: true })}>
                    <Wand2 aria-hidden="true" /> {t('te.issues.fromTopic')}
                  </Button>
                )}
              </div>
              <Input id={`${base}-title`} className="pointer-coarse:h-10" disabled={readOnly} maxLength={LIMITS.label}
                value={iss.title_auto !== false ? resolveIssueTitle(pattern, iss.topic) : (iss.title || '')}
                aria-invalid={err('title') ? true : undefined}
                onChange={(e) => onPatch({ title: e.target.value, title_auto: false })} />
              <FieldError id={`${base}-title-e`} text={err('title')} />
            </div>
          </div>
        </Group>

        {!legacy && opts.askTime && (
          <Group icon={CalendarClock} title={resolve(opts.timeLabel)} copy={resolve(opts.timeLabel)} slot="te-issue-when">
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)_minmax(0,1fr)]">
              <div className="col-span-2 flex min-w-0 flex-col gap-1.5 sm:col-span-1" data-field={fieldKey('date')}>
                <label htmlFor={`${base}-date`} className="text-sm font-medium">{t('te.iss.date')}</label>
                <DateTimeField id={`${base}-date`} dateOnly clearable disabled={readOnly} value={iss.date || ''}
                  min={bounds?.first} max={bounds?.last} placeholder={t('te.iss.datePlaceholder')}
                  invalid={dateErr ? true : undefined} describedBy={dateErr ? `${base}-date-e` : `${base}-when-h`}
                  onChange={(v) => onPatch({ date: v || null })} />
                <FieldError id={`${base}-date-e`} text={dateErr} />
              </div>
              <div className="flex min-w-0 flex-col gap-1.5" data-field={fieldKey('start')}>
                <label htmlFor={`${base}-start`} className="text-sm font-medium">{t('te.iss.start')}</label>
                <Input id={`${base}-start`} type="time" step={60} className="tabular-nums pointer-coarse:h-10" disabled={readOnly}
                  value={iss.start || ''} aria-invalid={startErr ? true : undefined} aria-describedby={startErr ? `${base}-start-e` : undefined}
                  onChange={(e) => onPatch({ start: e.target.value || null })} />
                <FieldError id={`${base}-start-e`} text={startErr} />
              </div>
              <div className="flex min-w-0 flex-col gap-1.5" data-field={fieldKey('end')}>
                <label htmlFor={`${base}-end`} className="text-sm font-medium">{t('te.iss.end')}</label>
                <Input id={`${base}-end`} type="time" step={60} className="tabular-nums pointer-coarse:h-10" disabled={readOnly}
                  value={iss.end || ''} aria-invalid={endErr ? true : undefined} aria-describedby={endErr ? `${base}-end-e` : undefined}
                  onChange={(e) => onPatch({ end: e.target.value || null })} />
                <FieldError id={`${base}-end-e`} text={endErr} />
              </div>
            </div>
            <div className="flex items-start gap-2.5">
              <Checkbox id={`${base}-next`} className="mt-0.5" checked={!!iss.end_next_day} disabled={readOnly}
                aria-describedby={`${base}-next-h`} onCheckedChange={(c) => onPatch({ end_next_day: c === true })} />
              <div className="flex min-w-0 flex-col gap-0.5">
                <label htmlFor={`${base}-next`} className="text-sm font-medium leading-snug">{t('te.iss.nextDay')}</label>
                <p id={`${base}-next-h`} className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('te.iss.nextDayHint')}</p>
              </div>
            </div>
            <p id={`${base}-when-h`} className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">
              {period ? t('te.iss.whenHint', `${period.monthName || ''} ${period.year || ''}`.trim()) : t('te.iss.whenHintPlain')}
            </p>
          </Group>
        )}

        <Group icon={Hash} title={t('te.iss.groupCount')} slot="te-issue-count">
          <div className={cn('grid gap-3', (shareBlock || (legacy || !opts.askImpact)) && 'sm:grid-cols-2')}>
            {numberField('errors', section.errors_label)}
            {shareBlock}
            {(legacy || !opts.askImpact) && numberField('customers', section.customers_label)}
          </div>
        </Group>

        {!legacy && opts.askOutage && (
          <Group icon={Zap} title={t('te.iss.groupOutage')} slot="te-issue-outage">
            <div className="flex min-w-0 flex-col gap-1.5" data-field={fieldKey('outage')}>
              <div data-slot="te-copy-row" className="text-sm leading-snug [overflow-wrap:anywhere]">
                <span id={`${base}-outage-l`} className="inline text-sm font-medium">{resolve(opts.outageLabel)}</span>
                <CopyTextButton text={resolve(opts.outageLabel)} />
              </div>
              <YesNo labelledBy={`${base}-outage-l`} value={iss.outage} disabled={readOnly} t={t}
                invalid={err('outage') ? true : undefined} describedBy={err('outage') ? `${base}-outage-e` : undefined}
                onChange={(v) => {
                  onPatch({ outage: v })
                  if (v === true && !(iss.outage_id || '').trim()) setFocusNext('outage_id')
                  if (v !== true) onFieldError(fieldKey('outage_id'), null)
                }} />
              <FieldError id={`${base}-outage-e`} text={err('outage')} />
            </div>
            {iss.outage === true && (
              <div className={cn('flex min-w-0 flex-col gap-1.5 sm:max-w-md', REVEAL)} data-field={fieldKey('outage_id')} data-slot="te-issue-outage-id">
                <div data-slot="te-copy-row" className="text-sm leading-snug [overflow-wrap:anywhere]">
                  <label htmlFor={`${base}-oid`} className="inline text-sm font-medium">{resolve(opts.outageIdLabel)}</label>
                  <span aria-hidden="true" className="req-star ms-0.5">*</span>
                  <CopyTextButton text={resolve(opts.outageIdLabel)} />
                </div>
                <Input ref={outageIdRef} id={`${base}-oid`} className="pointer-coarse:h-10" disabled={readOnly} maxLength={ISSUE_LIMITS.outageId}
                  value={iss.outage_id || ''} aria-required="true" aria-invalid={idErr ? true : undefined}
                  aria-describedby={[`${base}-oid-h`, idErr ? `${base}-oid-e` : null].filter(Boolean).join(' ')}
                  placeholder={t('te.iss.outageIdPlaceholder')}
                  onChange={(e) => onPatch({ outage_id: e.target.value === '' ? null : e.target.value })} />
                <p id={`${base}-oid-h`} className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('te.iss.outageIdHint', ISSUE_LIMITS.outageId)}</p>
                <FieldError id={`${base}-oid-e`} text={idErr} />
              </div>
            )}
          </Group>
        )}

        {!legacy && opts.askImpact && (
          <Group icon={Users} title={t('te.iss.groupImpact')} slot="te-issue-impact">
            <div className="flex min-w-0 flex-col gap-1.5" data-field={fieldKey('impact')}>
              <div data-slot="te-copy-row" className="text-sm leading-snug [overflow-wrap:anywhere]">
                <span id={`${base}-impact-l`} className="inline text-sm font-medium">{resolve(opts.impactLabel)}</span>
                <CopyTextButton text={resolve(opts.impactLabel)} />
              </div>
              <YesNo labelledBy={`${base}-impact-l`} value={iss.impact} disabled={readOnly} t={t}
                invalid={err('impact') ? true : undefined} describedBy={err('impact') ? `${base}-impact-e` : undefined}
                onChange={(v) => {
                  onPatch({ impact: v })
                  if (v === true && iss.customers == null) setFocusNext('customers')
                  if (v !== true) onFieldError(fieldKey('customers'), null)
                }} />
              <FieldError id={`${base}-impact-e`} text={err('impact')} />
            </div>
            {iss.impact === true && (
              <div className={cn('grid gap-3 sm:max-w-md', REVEAL)} data-slot="te-issue-customers">
                {numberField('customers', section.customers_label)}
              </div>
            )}
            {iss.impact === false && (
              <p className={cn('m-0 flex items-start gap-2 rounded-md border border-dashed p-2.5 text-sm', REVEAL,
                isHidden ? 'text-amber-900 dark:text-amber-200' : 'text-muted-foreground')}
                data-slot={isHidden ? 'te-issue-hidden-note' : 'te-issue-noimpact-note'} role="status">
                <EyeOff aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
                <span className="[overflow-wrap:anywhere]">{t(isHidden ? 'te.iss.noImpactTopic' : 'te.iss.noImpactCount')}</span>
              </p>
            )}
          </Group>
        )}

        {!legacy && (
          <div className="flex min-w-0 flex-col gap-1.5 rounded-lg border bg-muted/30 p-3" data-slot="te-issue-lead">
            <p className="m-0 inline-flex items-center gap-1.5 text-xs font-semibold text-muted-foreground">
              <ScrollText aria-hidden="true" className="size-3.5" /> {t('te.iss.lead')}
            </p>
            {lead ? <RichText text={lead} className="text-sm" slot="te-issue-lead-text" />
              : <p className="m-0 text-sm text-muted-foreground">{t('te.iss.leadEmpty')}</p>}
            <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">
              {isHidden ? t('te.iss.leadHidden') : t('te.iss.leadHint')}
            </p>
          </div>
        )}

        <Group icon={FileText} title={resolve(section.description_label)} copy={resolve(section.description_label)} slot="te-issue-desc">
          <div className="flex min-w-0 flex-col gap-1.5" data-field={fieldKey('description')}>
            <label htmlFor={`${base}-desc`} className="sr-only">{resolve(section.description_label)}</label>
            <RichTextarea id={`${base}-desc`} rows={4} value={iss.description || ''} disabled={readOnly} maxLength={LIMITS.longText}
              placeholder={t('te.issues.descriptionPlaceholder')} label={resolve(section.description_label)}
              invalid={err('description') || legacyErr ? true : undefined}
              describedBy={err('description') ? `${base}-description-e` : legacyErr ? `${base}-e` : undefined}
              onChange={(x) => onPatch({ description: x })} />
            <FieldError id={`${base}-description-e`} text={err('description')} />
          </div>
        </Group>

        {!legacy && opts.allowImages && (
          <Group icon={ImageIcon} title={t('te.iss.groupImages')} slot="te-issue-images">
            <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('te.iss.imagesHint')}</p>
            <div className="flex min-w-0 flex-col gap-3">
              {myImages.map((img, k) => {
                // İkinci yuva ilki dolunca açılır (boş iki kutu kartı uzatmasın); dolu ikinci yuva her zaman görünür
                if (k > 0 && !myImages[k - 1] && !img) return null
                const n = k + 1
                const slotId = issueSlotId(sid, iss.id, n)
                const label = t('te.iss.imageN', n)
                const capErr = err(`caption${n}`)
                return (
                  <div key={slotId} className="flex min-w-0 flex-col gap-2" data-slot="te-issue-image" data-n={n}>
                    <ImageSlotTile slot={{ id: slotId, label, width: 'full' }} image={img} reportId={reportId} resolve={(x) => x}
                      uiLang={uiLang} readOnly={readOnly} onImagesChange={onImagesChange} hideWidth
                      otherBytes={() => images.reduce((a, i) => a + (i.slot_id === slotId ? 0 : (i.size_bytes || 0)), 0)}
                      beforeUpload={beforeIssueUpload ? () => beforeIssueUpload(sid, iss.id) : null} />
                    {img && (
                      <div className="flex min-w-0 flex-col gap-1.5" data-field={fieldKey(`caption${n}`)}>
                        <label htmlFor={`${base}-cap${n}`} className="text-sm font-medium">{t('te.iss.caption', n)}</label>
                        <Input id={`${base}-cap${n}`} className="pointer-coarse:h-10" disabled={readOnly} maxLength={ISSUE_LIMITS.caption}
                          value={iss.captions?.[String(n)] || ''} placeholder={t('te.iss.captionPlaceholder')}
                          aria-invalid={capErr ? true : undefined}
                          onChange={(e) => {
                            const captions = { ...(iss.captions || {}) }
                            if (e.target.value === '') delete captions[String(n)]
                            else captions[String(n)] = e.target.value
                            onPatch({ captions: Object.keys(captions).length ? captions : null })
                          }} />
                        <FieldError id={`${base}-cap${n}-e`} text={capErr} />
                      </div>
                    )}
                  </div>
                )
              })}
            </div>
          </Group>
        )}
      </div>
      {legacyErr && <p id={`${base}-e`} className="mt-2 text-sm text-destructive" role="alert">{legacyErr}</p>}
    </li>
  )
}
