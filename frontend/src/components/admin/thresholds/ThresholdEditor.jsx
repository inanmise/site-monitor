import { useEffect, useMemo, useState } from 'react'
import { Repeat, SlidersHorizontal } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import Field from '../../ui/Field.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupInput, InputGroupText } from '@/components/shadcn/input-group'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Slider } from '@/components/shadcn/slider'
import { cn } from '@/lib/utils'
import {
  BUILTIN, MAX_DAYS, RE_ALERT_PRESETS, axisMaxFor, daysOf, hoursKey, tierOf, validateDays,
} from './thresholdModel.js'
import ThresholdScale, { LEVEL_DOT, daysText } from './ThresholdScale.jsx'
import ThresholdImpact from './ThresholdImpact.jsx'

const PREVIEW_DEBOUNCE_MS = 400
const DAY_FIELDS = ['critical', 'high', 'warning']

/**
 * Başparmak: ölçeğin üstünde, her renkte seçilebilir (açık zemin + koyu halka; koyu temada tersi). Slider'ın
 * `thumbProps.className`'i varsayılan sınıfların YERİNE geçer — tam liste burada bilinçli olarak yazıldı.
 */
const THUMB = 'block size-5 shrink-0 rounded-full border-2 border-foreground bg-background shadow-md ring-ring/50 transition-[color,box-shadow] hover:ring-4 focus-visible:ring-4 focus-visible:outline-hidden disabled:pointer-events-none disabled:opacity-50'

/**
 * Eşik düzenleme / tier eşiği ekleme penceresi (ui/ModalShell).
 *
 * <p>Üç bağlı giriş: renkli ölçeğin ÜSTÜNE oturan üç başparmaklı kaydırıcı (Radix Slider sırayı kendisi korur)
 * + üç sayı kutusu. Değerler canlı doğrulanır (tam sayı, ≥ 0, ≤ {@link MAX_DAYS}, kritik ≤ yüksek ≤ uyarı —
 * sunucunun kuralı), ölçek anında güncellenir ve geçerli her değişiklikten 400 ms sonra etki önizlemesi istenir
 * ("bu değerlerle bugün kaç alan hangi seviyede — şu ankine göre fark"). Sunucu hatası pencerenin İÇİNDE
 * gösterilir (AlertBanner), pencere kapanmaz.
 *
 * <p>Yeniden uyarı aralığı YALNIZ varsayılan satırda düzenlenir: sunucu aralığı her alarm için varsayılan
 * satırdan okur (EscalationService.reAlertIntervalHours) — tier satırındaki değer kullanılmaz; kullanıcıya
 * ölü bir ayar gösterilmez.
 *
 * @param target  { mode: 'edit'|'create', row, title }
 * @param defaultRow  kaydedilmiş varsayılan satır (yeniden uyarı bilgisi için); yoksa yerleşik değerler
 * @param scopeTotal  bu satırın kapsamındaki alan sayısı (önizlemeden; bilinmiyorsa null)
 * @param onSaved (mode) => void — üst bileşen pencereyi kapatır, bildirir ve listeyi tazeler
 */
export default function ThresholdEditor({ target, defaultRow, scopeTotal, onClose, onSaved }) {
  const t = useT()
  const { mode, row, title } = target
  const tier = tierOf(row)
  const isDefault = tier == null
  const init = useMemo(() => daysOf(row), [row])
  const [raw, setRaw] = useState(() => ({ critical: String(init.critical), high: String(init.high), warning: String(init.warning) }))
  const [shown, setShown] = useState(init)           // ölçeğin/kaydırıcının son GEÇERLİ değerleri
  const [reAlert, setReAlert] = useState(() => Number(row.re_alert_interval_hours ?? BUILTIN.re_alert_interval_hours))
  const [saving, setSaving] = useState(false)
  const [serverError, setServerError] = useState(null)
  const [preview, setPreview] = useState(null)

  const v = validateDays(raw)
  // Kaydırıcı/ölçek tavanı açılışta sabitlenir (sürüklerken eksen kaymasın); daha büyük yazılan değer genişletir.
  const baseMax = useMemo(() => axisMaxFor(init.warning), [init.warning])
  const sliderMax = Math.max(baseMax, shown.warning)
  const defaultReAlert = Number(defaultRow?.re_alert_interval_hours ?? BUILTIN.re_alert_interval_hours)

  function update(patch) {
    const next = { ...raw, ...patch }
    setRaw(next)
    const nv = validateDays(next)
    if (nv.valid) setShown(nv.values)
    setServerError(null)
  }

  // Canlı etki önizlemesi — yalnız geçerli değerlerde, debounce'lu; bayat yanıt yok sayılır.
  const previewKey = v.valid ? `${v.values.critical}/${v.values.high}/${v.values.warning}` : null
  useEffect(() => {
    if (!previewKey || typeof api.admin.previewThreshold !== 'function') { setPreview(null); return undefined }
    const [critical, high, warning] = previewKey.split('/').map(Number)
    let cancelled = false
    setPreview(p => ({ ...(p || {}), loading: true }))
    const timer = setTimeout(async () => {
      try {
        const res = await api.admin.previewThreshold({ tier, warning, high, critical })
        if (cancelled) return
        if (res?.success) setPreview({ loading: false, data: res.data })
        else setPreview({ loading: false, data: null, error: res?.error || t('thr.previewError') })
      } catch (e) {
        if (!cancelled) setPreview({ loading: false, data: null, error: e?.message || t('thr.previewError') })
      }
    }, PREVIEW_DEBOUNCE_MS)
    return () => { cancelled = true; clearTimeout(timer) }
  }, [previewKey, tier, t])

  async function save() {
    if (!v.valid || saving) return
    setSaving(true)
    setServerError(null)
    const body = {
      tier,
      warning_days: v.values.warning, high_days: v.values.high, critical_days: v.values.critical,
      // Tier satırının aralığı sunucuda kullanılmaz; mevcut değer (yeni satırda varsayılanınki) korunur.
      re_alert_interval_hours: isDefault ? Number(reAlert) : Number(row.re_alert_interval_hours ?? defaultReAlert),
    }
    try {
      const res = mode === 'create'
        ? await api.admin.createThreshold(body)
        : await api.admin.updateThreshold(row.id, {
          ...(row.name != null ? { name: row.name } : {}),
          ...(row.active != null ? { active: row.active } : {}),
          ...body,
        })
      if (res?.success) { onSaved?.(mode); return }
      setServerError(res?.error || t('thr.saveError'))
    } catch (e) {
      setServerError(e?.message || t('thr.saveError'))
    } finally {
      setSaving(false)
    }
  }

  const reAlertOptions = RE_ALERT_PRESETS.includes(reAlert) ? RE_ALERT_PRESETS : [...RE_ALERT_PRESETS, reAlert].sort((a, b) => a - b)
  const footer = (
    <>
      <Button type="button" variant="outline" className="max-sm:h-10" onClick={onClose} disabled={saving}>{t('thr.cancel')}</Button>
      <Button type="button" className="max-sm:h-10" onClick={save} disabled={!v.valid || saving} aria-busy={saving || undefined}>
        {saving && <Spinner size={14} inline decorative />}
        {saving ? t('thr.saving') : t('thr.save')}
      </Button>
    </>
  )

  return (
    <ModalShell open onClose={onClose} busy={saving} size="lg" scrollBody icon={SlidersHorizontal} footer={footer}
      title={mode === 'create' ? t('thr.addTitle', title) : t('thr.editTitle', title)}>
      <div data-slot="threshold-form" className="flex flex-col gap-4 pb-1">
        <p className="text-sm text-muted-foreground">
          {isDefault ? t('thr.appliesDefault') : t('thr.appliesTier', t('thr.tierShort', tier))}
          {scopeTotal != null && <> {t('thr.domainsToday', scopeTotal)}</>}
          {mode === 'create' && !isDefault && <> {t('thr.createNote')}</>}
        </p>

        {serverError && (
          <AlertBanner tone="danger" role="alert" title={t('thr.saveError')} className="mb-0">{String(serverError)}</AlertBanner>
        )}

        {/* Ölçek + üstüne oturan üç başparmaklı kaydırıcı (dokunmatikte 44 px yüksek dokunma alanı). */}
        <div className="px-1 pt-2">
          <ThresholdScale values={shown} axisMax={sliderMax} linear>
            <Slider
              min={0} max={sliderMax} step={1}
              value={[shown.critical, shown.high, shown.warning]}
              onValueChange={([c, h, w]) => update({ critical: String(c), high: String(h), warning: String(w) })}
              disabled={saving}
              thumbProps={(i) => ({
                className: THUMB,
                'aria-label': t('thr.sliderThumb', t(`thr.lv.${DAY_FIELDS[i]}`)),
                'aria-valuetext': daysText(t, [shown.critical, shown.high, shown.warning][i]),
              })}
              className="absolute inset-x-0 -top-2 h-11 [&_[data-slot=slider-range]]:bg-transparent [&_[data-slot=slider-track]]:bg-transparent"
            />
          </ThresholdScale>
        </div>

        <div className="grid grid-cols-3 gap-2 sm:gap-4">
          {DAY_FIELDS.map(k => {
            const fe = v.fields[k]
            const invalid = Boolean(fe) || v.orderFields.has(k)
            return (
              <Field key={k} className="mb-0 min-w-0"
                label={(
                  <span className="inline-flex min-w-0 items-center gap-1.5">
                    <span aria-hidden="true" className={cn('size-2.5 shrink-0 rounded-full', LEVEL_DOT[k])} />
                    <span className="truncate">{t(`thr.lv.${k}`)}</span>
                    <span className="sr-only">{' '}{t('thr.inDaysSr')}</span>
                  </span>
                )}
                error={fe ? t(fe.key, fe.arg) : undefined}>
                {({ id, describedBy }) => (
                  <InputGroup className="h-10 md:h-9">
                    <InputGroupInput id={id} aria-describedby={describedBy} aria-invalid={invalid || undefined}
                      data-field={k} type="number" inputMode="numeric" min={0} max={MAX_DAYS} step={1}
                      value={raw[k]} disabled={saving}
                      onChange={(e) => update({ [k]: e.target.value })} className="min-w-0 tabular-nums" />
                    <InputGroupAddon align="inline-end" className="pl-0">
                      <InputGroupText className="text-xs">{t('thr.daysUnit')}</InputGroupText>
                    </InputGroupAddon>
                  </InputGroup>
                )}
              </Field>
            )
          })}
        </div>

        {v.order && <AlertBanner tone="danger" className="mb-0">{t('thr.orderError')}</AlertBanner>}
        {v.equal.map(eq => (
          <AlertBanner key={eq} tone="warning" className="mb-0">{t(eq === 'critical-high' ? 'thr.equalCH' : 'thr.equalHW')}</AlertBanner>
        ))}

        <div className="flex flex-col gap-1.5">
          <span className="text-xs font-semibold text-muted-foreground">{t('thr.whoTitle')}</span>
          <ul data-slot="threshold-audience" className="grid list-none grid-cols-1 gap-1 text-xs text-muted-foreground sm:grid-cols-3">
            {DAY_FIELDS.map(k => (
              <li key={k} className="flex min-w-0 items-start gap-1.5">
                <span aria-hidden="true" className={cn('mt-1 size-2 shrink-0 rounded-full', LEVEL_DOT[k])} />
                <span><span className="font-medium text-foreground">{t(`thr.lv.${k}`)}:</span> {t(`thr.who.${k}`)}</span>
              </li>
            ))}
          </ul>
        </div>

        {isDefault ? (
          <Field className="mb-0 [&_[data-slot=native-select-wrapper]]:w-full sm:[&_[data-slot=native-select-wrapper]]:max-w-xs"
            label={t('thr.reAlertLabel')} hint={t('thr.reAlertHint')}>
            {({ id, describedBy }) => (
              <NativeSelect id={id} aria-describedby={describedBy} value={String(reAlert)} disabled={saving}
                onChange={(e) => { setReAlert(Number(e.target.value)); setServerError(null) }} className="h-10 md:h-9">
                {reAlertOptions.map(h => <NativeSelectOption key={h} value={String(h)}>{t(...hoursKey(h))}</NativeSelectOption>)}
              </NativeSelect>
            )}
          </Field>
        ) : (
          <p data-slot="threshold-realert-note" className="flex items-start gap-2 text-xs text-muted-foreground">
            <Repeat size={14} aria-hidden="true" className="mt-0.5 shrink-0" />
            <span>{t('thr.reAlertFollows', t(...hoursKey(defaultReAlert)))}</span>
          </p>
        )}

        {v.valid
          ? <ThresholdImpact preview={preview} />
          : <p className="text-xs text-muted-foreground">{t('thr.fixToPreview')}</p>}
      </div>
    </ModalShell>
  )
}
