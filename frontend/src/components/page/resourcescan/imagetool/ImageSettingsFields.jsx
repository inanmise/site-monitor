import { useId } from 'react'
import { useT, useDateLocale } from '../../../../i18n/index.jsx'
import Field from '../../../ui/Field.jsx'
import SegmentedControl from '../../../ui/SegmentedControl.jsx'
import { formatNumber } from '../resourceScanModel.js'
import { WRAP } from '../ResourceScanParts.jsx'
import { FORMATS, METHODS, QUALITY_MODES, pngPaletteColors } from './imageToolModel.js'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Slider } from '@/components/shadcn/slider'
import { Switch } from '@/components/shadcn/switch'
import { cn } from '@/lib/utils'

const TOUCH_INPUT = 'pointer-coarse:h-10'

/**
 * Görsel aracı ayar alanları (2026-11-14) — araç görünümünün paneli ve tarama görselinin "Özel boyut" penceresi AYNI
 * alanları kullanır (`sections` ile hangileri çizileceği seçilir). Alan hatası ALANIN altında (`errors` =
 * `imageToolModel.validateSettings` çıktısı). Saf denetimli bileşen: değer dışarıda, `onChange(patch)`.
 *
 * Kurallar: WebP bu tarayıcıda kodlanamıyorsa seçenek kapalıdır ve nedeni yazılır; yeniden boyutlandırma hiçbir zaman
 * BÜYÜTMEZ (ipucunda söylenir); üst veri anahtarları varsayılan kapalıdır ve yalnız görselde o veri varsa açılabilir.
 */
export default function ImageSettingsFields({
  value, onChange, errors = null, support, limits, disabled = false,
  sections = { format: true, quality: true, resize: true, background: true, metadata: true },
  metadata = null,            // { copyright, creation, location } — kuyruktaki görsellerde VAR olan üst veri türleri (null = bilinmiyor)
}) {
  const t = useT()
  const locale = useDateLocale()
  const uid = useId()
  const err = (k) => (errors?.[k] ? t(errors[k].key, ...(errors[k].args || []).map((a) => (typeof a === 'number' ? formatNumber(a, locale) : a))) : undefined)
  const s = value
  const webp = support?.webp === true
  const supportKnown = support?.state === 'ready'
  const sized = s.method !== 'none'
  const both = s.method === 'fit' || s.method === 'cover' || s.method === 'thumb'
  const maxSide = limits?.maxSide ?? 30_000
  const numeric = { type: 'text', inputMode: 'numeric', autoComplete: 'off' }

  return (
    <div data-slot="prs-it-settings" className="flex min-w-0 flex-col">
      {sections.format && (
        <Field label={t('prs.it.set.format')} name="format"
          hint={supportKnown && !webp ? t('prs.it.set.webpOff')
            : ['jpeg', 'png', 'webp'].includes(s.format) ? `${t(`prs.it.set.formatHint.${s.format}`)} ${t('prs.it.set.formatConvertNote')}`
              : t(`prs.it.set.formatHint.${s.format}`)}>
          {({ id, describedBy }) => (
            <NativeSelect id={id} aria-describedby={describedBy} value={s.format} disabled={disabled} data-slot="prs-it-format"
              className={TOUCH_INPUT} onChange={(e) => onChange({ format: e.target.value })}>
              {FORMATS.map((f) => (
                <NativeSelectOption key={f} value={f} disabled={f === 'webp' && supportKnown && !webp}>
                  {t(`prs.it.set.format.${f}`)}{f === 'webp' && supportKnown && !webp ? ` — ${t('prs.it.set.webpOffShort')}` : ''}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          )}
        </Field>
      )}

      {sections.quality && (
        <>
          <Field label={t('prs.it.set.quality')} name="qualityMode" hint={t(`prs.it.set.qualityHint.${s.qualityMode}`)}>
            {() => (
              <div data-slot="prs-it-qmode">
                <SegmentedControl value={s.qualityMode} onChange={(v) => onChange({ qualityMode: v })} ariaLabel={t('prs.it.set.quality')}
                  className="flex-wrap" options={QUALITY_MODES.map((m) => ({ value: m, label: t(`prs.it.set.qmode.${m}`), disabled }))} />
              </div>
            )}
          </Field>
          {s.qualityMode === 'manual' && (
            <Field label={t('prs.it.set.qualityValue', formatNumber(s.quality, locale))} name="quality" error={err('quality')}
              hint={s.format === 'jpeg' || s.format === 'webp' ? undefined
                : pngPaletteColors(s.quality) === null ? t('prs.it.set.qualityPngLossless')
                  : t('prs.it.set.qualityPngPalette', formatNumber(Number(s.quality), locale), formatNumber(pngPaletteColors(s.quality), locale))}>
              {({ labelId, describedBy }) => (
                <Slider min={1} max={100} step={1} value={[Number(s.quality) || 1]} disabled={disabled} data-slot="prs-it-quality"
                  aria-describedby={describedBy} className="py-2 pointer-coarse:py-3"
                  thumbProps={{ 'aria-labelledby': labelId, className: 'relative block size-4 shrink-0 rounded-full border border-primary bg-white shadow-sm ring-ring/50 transition-[color,box-shadow] hover:ring-4 focus-visible:ring-4 focus-visible:outline-hidden pointer-coarse:size-6 pointer-coarse:after:absolute pointer-coarse:after:top-1/2 pointer-coarse:after:left-1/2 pointer-coarse:after:size-10 pointer-coarse:after:-translate-x-1/2 pointer-coarse:after:-translate-y-1/2' }}
                  onValueChange={(v) => onChange({ quality: v[0] })} />
              )}
            </Field>
          )}
          {s.qualityMode === 'target' && (
            <Field label={t('prs.it.set.targetKb')} name="targetKb" error={err('targetKb')} hint={t('prs.it.set.targetHint')}>
              {({ id, describedBy, invalid }) => (
                <Input id={id} {...numeric} aria-describedby={describedBy} aria-invalid={invalid} value={s.targetKb} disabled={disabled}
                  data-slot="prs-it-target" className={cn('max-w-40', TOUCH_INPUT)} onChange={(e) => onChange({ targetKb: e.target.value })} />
              )}
            </Field>
          )}
        </>
      )}

      {sections.resize && (
        <>
          <Field label={t('prs.it.set.method')} name="method" hint={`${t(`prs.it.set.methodHint.${s.method}`)}${sized ? ` ${t('prs.it.set.noUpscale')}` : ''}`}>
            {({ id, describedBy }) => (
              <NativeSelect id={id} aria-describedby={describedBy} value={s.method} disabled={disabled} data-slot="prs-it-method"
                className={TOUCH_INPUT} onChange={(e) => onChange({ method: e.target.value })}>
                {METHODS.map((m) => <NativeSelectOption key={m} value={m}>{t(`prs.it.set.method.${m}`)}</NativeSelectOption>)}
              </NativeSelect>
            )}
          </Field>
          {sized && (
            <div className="grid min-w-0 grid-cols-2 gap-3">
              <Field label={t('prs.it.set.width')} name="width" error={err('width')} required={both}
                hint={both ? undefined : t('prs.it.set.scaleHint')}>
                {({ id, describedBy, invalid }) => (
                  <Input id={id} {...numeric} aria-describedby={describedBy} aria-invalid={invalid} value={s.width} disabled={disabled}
                    data-slot="prs-it-width" className={TOUCH_INPUT} placeholder={t('prs.it.set.pxPh', formatNumber(maxSide, locale))}
                    onChange={(e) => onChange({ width: e.target.value })} />
                )}
              </Field>
              <Field label={t('prs.it.set.height')} name="height" error={err('height')} required={both}>
                {({ id, describedBy, invalid }) => (
                  <Input id={id} {...numeric} aria-describedby={describedBy} aria-invalid={invalid} value={s.height} disabled={disabled}
                    data-slot="prs-it-height" className={TOUCH_INPUT} placeholder={t('prs.it.set.pxPh', formatNumber(maxSide, locale))}
                    onChange={(e) => onChange({ height: e.target.value })} />
                )}
              </Field>
            </div>
          )}
        </>
      )}

      {sections.background && (
        <Field label={t('prs.it.set.background')} name="background" error={err('background')} hint={t('prs.it.set.backgroundHint')}>
          {({ id, describedBy, invalid }) => (
            <div className="flex min-w-0 items-center gap-2">
              <Input type="color" aria-label={t('prs.it.set.backgroundPick')} value={/^#[0-9a-f]{6}$/i.test(s.background) ? s.background : '#ffffff'}
                disabled={disabled} data-slot="prs-it-bg-pick" className="h-9 w-14 shrink-0 cursor-pointer p-1 pointer-coarse:h-10"
                onChange={(e) => onChange({ background: e.target.value })} />
              <Input id={id} type="text" autoComplete="off" spellCheck={false} aria-describedby={describedBy} aria-invalid={invalid}
                value={s.background} disabled={disabled} data-slot="prs-it-bg" className={cn('max-w-32 font-mono', TOUCH_INPUT)}
                onChange={(e) => onChange({ background: e.target.value })} />
            </div>
          )}
        </Field>
      )}

      {sections.metadata && (
        <div data-slot="prs-it-meta" role="group" aria-labelledby={`${uid}-meta`} className="mb-3.5 flex min-w-0 flex-col gap-2">
          <span id={`${uid}-meta`} className="text-sm font-semibold">{t('prs.it.set.metadata')}</span>
          <p className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t('prs.it.set.metadataHint')}</p>
          {[['keepCopyright', 'copyright'], ['keepCreation', 'creation'], ['keepLocation', 'location']].map(([key, kind]) => {
            const has = metadata?.[kind] === true
            const id = `${uid}-${kind}`
            return (
              <div key={key} data-meta={kind} data-available={has ? 'true' : 'false'} className="flex min-w-0 items-start gap-3">
                <Switch id={id} checked={s[key] === true && has} disabled={disabled || !has} aria-describedby={`${id}-h`}
                  className="mt-0.5" onCheckedChange={(v) => onChange({ [key]: v === true })} />
                <div className="flex min-w-0 flex-col gap-0.5">
                  <Label htmlFor={id} className={cn('text-sm font-medium', WRAP)}>{t(`prs.it.set.meta.${kind}`)}</Label>
                  <span id={`${id}-h`} className={cn('text-xs text-muted-foreground', WRAP)}>
                    {has ? t(`prs.it.set.metaHint.${kind}`) : t(metadata ? 'prs.it.set.metaAbsent' : 'prs.it.set.metaUnknown')}
                  </span>
                </div>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
