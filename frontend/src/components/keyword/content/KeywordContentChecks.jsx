import { useEffect, useId, useState } from 'react'
import { FileDiff, Info, Ruler, TriangleAlert } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { Input } from '@/components/shadcn/input'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Switch } from '@/components/shadcn/switch'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'
import Field from '../../ui/Field.jsx'
import { FormSection } from '../../monitoring/MonitorForm.jsx'
import {
  CHANGE_MASKS, SIZE_UNITS, changeSettingsReset, maskList, parseSize, sizeText, unitFor, validateContentChecks,
} from './contentChecksModel.js'

const CONTROL = 'h-9 w-full pointer-coarse:h-10'

/** Alanın sunucu hatası önce (dizge); yoksa istemci kodu (`keyword.content.v.<kod>`) — yalnız dokunulmuş alan / kayıt denemesi. */
function errText(t, server, client, key, show) {
  const sv = server?.[key]
  if (sv) return typeof sv === 'string' ? sv : (sv.message || sv.error || null)
  const c = show ? client[key] : null
  return c ? t(`keyword.content.v.${c.code}`, ...(c.params || [])) : null
}

/** Boyut alanı: sayı + birim (B / KB). Yazılan metin yerelde tutulur — birim değişince değer yeniden hesaplanır. */
function SizeField({ label, field, bytes, onBytes, error, disabled, onBlur }) {
  const t = useT()
  const [unit, setUnit] = useState(() => unitFor(bytes))
  const [text, setText] = useState(() => sizeText(bytes, unitFor(bytes)))
  // Dışarıdan gelen değer (form sıfırlandı / sunucudan yüklendi) yazılan metinle uyuşmuyorsa metni ondan kur
  useEffect(() => {
    if (parseSize(text, unit) !== bytes && !(bytes == null && text.trim() === '')) {
      const u = unitFor(bytes)
      setUnit(u)
      setText(sizeText(bytes, u))
    }
  }, [bytes])   // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <Field label={label} name={field} error={error} className="mb-0">
      {({ id, describedBy, invalid }) => (
        <div className="flex min-w-0 gap-2">
          <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled} inputMode="numeric"
            data-slot={`kw-content-${field}`} className={cn(CONTROL, 'min-w-0 flex-1 tabular-nums')} value={text}
            onChange={(e) => { setText(e.target.value); onBytes(parseSize(e.target.value, unit)) }} onBlur={onBlur} />
          <NativeSelect aria-label={t('keyword.content.unitFor', label)} disabled={disabled} className="h-9 w-[5.5rem] pointer-coarse:h-10"
            value={unit} data-slot={`kw-content-${field}-unit`}
            onChange={(e) => { const u = e.target.value; setUnit(u); onBytes(parseSize(text, u)) }}>
            {Object.keys(SIZE_UNITS).map((u) => <NativeSelectOption key={u} value={u}>{t(`keyword.content.unit.${u}`)}</NativeSelectOption>)}
          </NativeSelect>
        </div>
      )}
    </Field>
  )
}

/**
 * İÇERİK KONTROLLERİ bölümü (kw paket D) — formda SSL bölümünün ardındaki işaretli yere bağlanır (Aşama 2). İki parça:
 * <ul>
 *   <li><b>Sayfa boyutu</b>: en az / en fazla (B ya da KB; 1 bayt – 2 000 000 bayt); boş sınır denetlenmez. Aralık dışı =
 *       KEYWORD alarmı (BODY_TOO_SMALL / BODY_TOO_LARGE).</li>
 *   <li><b>İçerik değişimi</b>: aç / kapa + değişken kısımları yok sayma (rakamlar, tarih / saat) + isteğe bağlı bölge (CSS).
 *       İlk zamanlanmış kontrol temeli otomatik alır; fark = "İçerik değişti" alarmı (KEYWORD_CHANGED, kesinti değil).
 *       Dinamik sayfada maskesiz ve bölgesiz kullanım uyarılır; düzenlemede ayar değişimi temeli sıfırlayacaksa önceden
 *       söylenir.</li>
 * </ul>
 * Alan anahtarları sunucuyla aynı (`minBytes`, `maxBytes`, `changeSelector`, `changeMask`).
 *
 * @param {object}   value       { minBytes, maxBytes, changeDetect, changeMask, changeSelector }
 * @param {Function} onChange    (patch) — değişen alanlar
 * @param {object}   stored      kayıtlı değerler (düzenlemede; yeni izlemede null)
 * @param {object}   errors      sunucu alan hataları
 * @param {boolean}  showErrors  istemci hatalarını dokunulmamış alanda da göster
 */
export default function KeywordContentChecks({ value, onChange, stored = null, errors = null, showErrors = false, disabled = false }) {
  const t = useT()
  const v = value || {}
  const client = validateContentChecks(v)
  const [touched, setTouched] = useState(() => new Set())
  const touch = (k) => () => setTouched((s) => (s.has(k) ? s : new Set(s).add(k)))
  const err = (k) => errText(t, errors, client, k, showErrors || touched.has(k))
  const masks = maskList(v.changeMask)
  const dynamicRisk = v.changeDetect === true && masks.length === 0 && !String(v.changeSelector ?? '').trim()
  const willReset = changeSettingsReset(v, stored)
  const maskLabelId = `${useId()}-mask`

  return (
    <div data-slot="kw-content-checks" className="flex min-w-0 flex-col gap-3 sm:col-span-2">
      <FormSection title={t('keyword.content.sizeTitle')} icon={Ruler} hint={t('keyword.content.sizeHint')}>
        <div className="grid grid-cols-1 gap-x-4 gap-y-3 sm:grid-cols-2">
          <SizeField label={t('keyword.content.minBytes')} field="minBytes" bytes={v.minBytes ?? null} disabled={disabled}
            error={err('minBytes')} onBlur={touch('minBytes')} onBytes={(b) => onChange?.({ minBytes: b })} />
          <SizeField label={t('keyword.content.maxBytes')} field="maxBytes" bytes={v.maxBytes ?? null} disabled={disabled}
            error={err('maxBytes')} onBlur={touch('maxBytes')} onBytes={(b) => onChange?.({ maxBytes: b })} />
        </div>
      </FormSection>

      <FormSection title={t('keyword.content.changeTitle')} icon={FileDiff} name="changeMask" error={err('changeMask')}>
        <label className="flex min-w-0 cursor-pointer items-start gap-3">
          <Switch checked={v.changeDetect === true} disabled={disabled} data-slot="kw-content-change-toggle"
            onCheckedChange={(on) => onChange?.({ changeDetect: on })} aria-label={t('keyword.content.changeToggle')} className="mt-0.5 shrink-0" />
          <span className="flex min-w-0 flex-col gap-0.5">
            <span className="text-sm font-semibold">{t('keyword.content.changeToggle')}</span>
            <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('keyword.content.changeHint')}</span>
          </span>
        </label>

        {v.changeDetect === true && (
          <div className="flex min-w-0 flex-col gap-3">
            <div className="flex min-w-0 flex-col gap-1.5">
              <span id={maskLabelId} className="text-sm font-semibold">{t('keyword.content.maskLabel')}</span>
              <ToggleGroup type="multiple" variant="outline" size="sm" value={masks} aria-labelledby={maskLabelId}
                data-slot="kw-content-masks" disabled={disabled} className="flex-wrap justify-start"
                onValueChange={(next) => onChange?.({ changeMask: maskList(next) })}>
                {CHANGE_MASKS.map((m) => (
                  <ToggleGroupItem key={m} value={m} data-mask={m}
                    className="h-8 rounded-md px-3 text-xs font-medium data-[state=on]:bg-muted pointer-coarse:h-10">
                    {t(`keyword.content.mask.${m}`)}
                  </ToggleGroupItem>
                ))}
              </ToggleGroup>
              <span className="text-xs text-muted-foreground">{t('keyword.content.maskHint')}</span>
            </div>
            <Field label={t('keyword.content.selector')} hint={t('keyword.content.selectorHint')} name="changeSelector"
              error={err('changeSelector')} className="mb-0">
              {({ id, describedBy, invalid }) => (
                <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled} data-slot="kw-content-selector"
                  className={cn(CONTROL, 'font-mono')} value={v.changeSelector ?? ''} placeholder="main"
                  autoComplete="off" spellCheck={false}
                  onChange={(e) => onChange?.({ changeSelector: e.target.value })} onBlur={touch('changeSelector')} />
              )}
            </Field>
            {dynamicRisk && (
              <p data-slot="kw-content-dynamic-warn" role="note" className="m-0 flex items-start gap-1.5 rounded-md bg-amber-500/10 px-2.5 py-2 text-xs text-amber-800 dark:text-amber-300">
                <TriangleAlert aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
                <span className="[overflow-wrap:anywhere]">{t('keyword.content.dynamicWarn')}</span>
              </p>
            )}
          </div>
        )}
        {willReset && (
          <p data-slot="kw-content-reset-note" role="note" className="m-0 flex items-start gap-1.5 rounded-md bg-primary/5 px-2.5 py-2 text-xs text-foreground">
            <Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0 text-primary" />
            <span className="[overflow-wrap:anywhere]">{t('keyword.content.resetNote')}</span>
          </p>
        )}
      </FormSection>
    </div>
  )
}
