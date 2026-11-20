import { useEffect, useId, useState } from 'react'
import { X } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import Field, { FieldError } from '../ui/Field.jsx'
import SmartDateTimeField from '../ui/SmartDateTimeField.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import MarkdownEditor from '../ui/MarkdownEditor.jsx'
import { Input } from '@/components/shadcn/input'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'
import { csvList } from './incidentHistoryModel.js'

/**
 * Olay formunun alan bileşenleri — MODÜL düzeyinde (kararlı kimlik: yazarken alan yeniden kurulmaz, odak kaybolmaz).
 * Hepsi `ui/Field` (etiket ↔ kontrol bağı + ipucu + satır içi hata) + shadcn kontrolü. `full` → ızgarada tam satır.
 * Telefonda dokunma hedefi ≥ 40 px formun kabından verilir (IncidentFormModal `PHONE_TOUCH`).
 */
export const FULL = 'col-span-full'

/**
 * `name` → alanın `data-field` kancası (sunucu / doğrulama hatasında o alana odak, testlerde bulma); `inputMode` verilirse
 * tür varsayılanının yerine geçer (ör. metin kutusunda sayısal tuş takımı). İkisi de verilmezse çıktı eskisiyle aynıdır.
 */
export function TextInput({
  label, value, onChange, disabled, type = 'text', req, full, error, hint, autoFocus, placeholder, name, inputMode, autoComplete,
}) {
  return (
    <Field label={label} required={req} className={full ? FULL : undefined} error={error} hint={hint} name={name}>
      {({ id, describedBy, invalid }) => (
        <Input id={id} type={type} value={value ?? ''} disabled={disabled} autoFocus={autoFocus} placeholder={placeholder}
          aria-describedby={describedBy} aria-invalid={invalid} aria-required={req || undefined}
          min={type === 'number' ? 0 : undefined} autoComplete={autoComplete}
          inputMode={inputMode ?? (type === 'number' ? 'decimal' : undefined)}
          onChange={(e) => onChange(e.target.value)} />
      )}
    </Field>
  )
}

const YES_NO_ITEM = 'px-4 max-sm:min-h-10 pointer-coarse:min-h-10'

/**
 * Üç durumlu Evet / Hayır (shadcn ToggleGroup, tek seçim): `true` / `false` / `null` (bilinmiyor). Seçili cevaba yeniden
 * basmak seçimi KALDIRIR (null) — bilinmeyen "Hayır" değildir, ayrı bir durumdur. Grup adını alan etiketinden alır
 * (`aria-labelledby`), ipucu ve hata `aria-describedby` ile bağlıdır; telefonda ve dokunmatikte hedef ≥ 40 px.
 */
export function YesNoInput({ label, value, onChange, disabled, hint, error, name, yesLabel, noLabel, full }) {
  const answer = value === true ? 'yes' : value === false ? 'no' : ''
  return (
    <Field label={label} className={full ? FULL : undefined} hint={hint} error={error} name={name}>
      {({ labelId, describedBy }) => (
        <ToggleGroup type="single" variant="outline" disabled={disabled} value={answer} data-answer={answer || 'unknown'}
          aria-labelledby={labelId} aria-describedby={describedBy} className="w-fit max-w-full"
          onValueChange={(x) => onChange(x === 'yes' ? true : x === 'no' ? false : null)}>
          <ToggleGroupItem value="yes" className={YES_NO_ITEM}>{yesLabel}</ToggleGroupItem>
          <ToggleGroupItem value="no" className={YES_NO_ITEM}>{noLabel}</ToggleGroupItem>
        </ToggleGroup>
      )}
    </Field>
  )
}

/**
 * Olay formunun tarih + saat alanı (2026-11-17, kullanıcı isteği: giriş çok zordu) — `ui/SmartDateTimeField`: yazılabilir
 * ("16.11 14:30", "dün 09:00", "şimdi"), hızlı seçim çipleri, ay / yıl açılır takvim + saat / dakika listeleri. `anchor`
 * (tespit / çözüm zamanı için oluş zamanı) verilirse çipler "+N dk" olur ve altında "<anchorLabel> zamanından N sonra" yazar.
 */
export function DateInput({ label, value, onChange, disabled, req, min, minLabel, error, hint, anchor, anchorLabel, onProblem, name }) {
  return (
    <Field label={label} required={req} error={error} hint={hint} name={name}>
      {({ id, describedBy, invalid }) => (
        <SmartDateTimeField id={id} value={value} onChange={onChange} disabled={disabled} clearable={!req} min={min}
          minLabel={minLabel} label={label} anchor={anchor} anchorLabel={anchorLabel} invalid={invalid}
          describedBy={describedBy} onProblem={onProblem} />
      )}
    </Field>
  )
}

export function SelectInput({ label, value, onChange, disabled, options, req, error, hint, name }) {
  return (
    <Field label={label} required={req} error={error} hint={hint} name={name}>
      {({ id, describedBy, invalid }) => (
        // NativeSelect sarmalayıcısı `w-fit` — alanı doldursun diye doğrudan çocuğa w-full
        <div className="*:w-full">
          <NativeSelect id={id} value={value ?? ''} disabled={disabled} aria-describedby={describedBy} aria-invalid={invalid}
            aria-required={req || undefined} onChange={(e) => onChange(e.target.value)}>
            {options.map((o) => <NativeSelectOption key={o.value} value={o.value}>{o.label}</NativeSelectOption>)}
          </NativeSelect>
        </div>
      )}
    </Field>
  )
}

/**
 * Yönetilen seçim (hata/fonksiyon/kanal kodu) — sabit liste + yeni değer ekleme / silme (creatable). `error` / `name`
 * (2026-11-20): sunucunun alan hatası alanın altında, tetikleyici `aria-invalid` + `aria-describedby` ile bağlı.
 */
export function CreatableSelect({ label, value, onChange, options, disabled, onCreate, onDelete, hint, error, name }) {
  const opts = [{ value: '', label: '—' }, ...options.map((o) => ({ value: o, label: o }))]
  return (
    <Field label={label} hint={hint} error={error} name={name}>
      {({ id, describedBy, invalid }) => (
        <SearchableSelect id={id} value={value ?? ''} onChange={onChange} disabled={disabled}
          options={opts} creatable={!disabled} onCreate={onCreate} ariaDescribedBy={describedBy} ariaInvalid={invalid}
          onDelete={disabled ? undefined : onDelete} placeholder="—" />
      )}
    </Field>
  )
}

/**
 * Renkli değer çipi. Düzenlenebilirken ÇİPİN TAMAMI kaldırma düğmesidir (× ikonu görsel ipucu; telefonda 40 px
 * hedef) — ad satırı ayırır ("<değer> etiketini kaldır"). Salt okunurken düz Badge. Ton `hue` (0-360).
 */
export function HueChip({ value, hue, disabled, onRemove, removeLabel }) {
  const style = { background: `hsl(${hue},70%,93%)`, color: `hsl(${hue},65%,30%)`, borderColor: `hsl(${hue},70%,78%)` }
  if (disabled) {
    return <Badge variant="outline" data-tag={value} className="max-w-full rounded-full px-2.5 font-semibold" style={style}><span className="truncate">{value}</span></Badge>
  }
  return (
    <Button type="button" variant="outline" size="xs" data-tag={value} aria-label={removeLabel} title={removeLabel} onClick={onRemove}
      className="h-7 max-w-full gap-1 rounded-full pr-1.5 pl-2.5 font-semibold hover:opacity-85 max-sm:h-10 dark:border-transparent pointer-coarse:h-10" style={style}>
      <span className="truncate">{value}</span>
      <X aria-hidden="true" className="size-3.5 opacity-70" />
    </Button>
  )
}

/** İlk render karesi için deterministik ton (etiketin kalıcı rengi yok; oturumda sabit kalır). */
export function tagHue(s) {
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 360
  return h
}
const randomHue = () => Math.floor(Math.random() * 360)

/**
 * Çoklu seçim + creatable — değer CSV ('a, b, c'). Seçilenler kaldırılabilir çip; "ekle" tekil SearchableSelect
 * (seçilenler hariç), seçimden sonra boş kalır.
 */
export function CreatableMultiSelect({ label, value, onChange, options, disabled, onCreate, onDelete, placeholder, full, hint, error, name }) {
  const t = useT()
  const selected = csvList(value)
  const add = (v) => {
    const x = (v ?? '').trim()
    if (x && !selected.some((s) => s.toLowerCase() === x.toLowerCase())) onChange([...selected, x].join(', '))
  }
  const remove = (val) => onChange(selected.filter((x) => x !== val).join(', '))
  const opts = [
    { value: '', label: placeholder || '—' },
    ...options.filter((o) => !selected.some((s) => s.toLowerCase() === String(o).toLowerCase()))
      .map((o) => ({ value: o, label: o })),
  ]
  return (
    <Field label={label} className={full ? FULL : undefined} hint={hint} error={error} name={name}>
      {({ id, describedBy, invalid }) => (
        <div className="flex min-w-0 flex-col gap-1.5">
          {!disabled && (
            <SearchableSelect id={id} value="" onChange={add} options={opts} creatable
              ariaDescribedBy={describedBy} ariaInvalid={invalid}
              onCreate={(v) => { onCreate?.(v); add(v) }} onDelete={onDelete} placeholder={placeholder || '—'} />
          )}
          {selected.length > 0 ? (
            <div className="flex flex-wrap gap-1.5">
              {selected.map((val) => (
                <HueChip key={val} value={val} hue={tagHue(val)} disabled={disabled}
                  removeLabel={t('tag.removeTag', val)} onRemove={() => remove(val)} />
              ))}
            </div>
          ) : (disabled && <span className="text-sm text-muted-foreground">—</span>)}
        </div>
      )}
    </Field>
  )
}

/**
 * Etiket girişi — yazıp Enter (ya da virgül) → RASTGELE renkli çip. Renk eklenince atanır ve oturumda sabit kalır
 * (CSV'ye yazılmaz, her render'da titremez). Odak kaybında yazılan etiket de eklenir.
 */
export function TagField({ label, value, onChange, disabled, hint, error, name }) {
  const t = useT()
  const [text, setText] = useState('')
  const [hues, setHues] = useState({})
  const tags = csvList(value)
  useEffect(() => {
    setHues((prev) => {
      let changed = false
      const next = { ...prev }
      for (const tag of tags) if (next[tag] == null) { next[tag] = randomHue(); changed = true }
      return changed ? next : prev
    })
  }, [value]) // eslint-disable-line react-hooks/exhaustive-deps
  const add = () => {
    const v = text.trim().replace(/,$/, '')
    if (v && !tags.some((x) => x.toLowerCase() === v.toLowerCase())) {
      setHues((prev) => ({ ...prev, [v]: randomHue() }))
      onChange([...tags, v].join(', '))
    }
    setText('')
  }
  const remove = (tag) => onChange(tags.filter((x) => x !== tag).join(', '))
  return (
    <Field label={label} className={FULL} hint={hint ?? (!disabled ? t('inc.tagsHint') : undefined)} error={error} name={name}>
      {({ id, describedBy, invalid }) => (
        <div className="flex min-w-0 flex-col gap-1.5">
          {!disabled && (
            <Input id={id} type="text" value={text} placeholder={t('inc.tagsPlaceholder')} aria-describedby={describedBy}
              aria-invalid={invalid}
              onChange={(e) => setText(e.target.value)} onBlur={add}
              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); add() } }} />
          )}
          {tags.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {tags.map((tag) => (
                <HueChip key={tag} value={tag} hue={hues[tag] ?? tagHue(tag)} disabled={disabled}
                  removeLabel={t('tag.removeTag', tag)} onRemove={() => remove(tag)} />
              ))}
            </div>
          )}
        </div>
      )}
    </Field>
  )
}

/**
 * Zengin metin alanı — Haftalık Raporlar ile AYNI markdown editör. Görsel yükleme açık; kimlik yoksa (yeni kayıt)
 * taslak yüklenir, kaydedince sunucu görseli olaya bağlar. `makeUniqueCaption` aynı olay içinde adları tekilleştirir.
 */
export function MdArea({ label, hint, value, onChange, incidentId, makeUniqueCaption, height = 220, error, name }) {
  const labelId = useId()
  const errorId = useId()
  const uploadImage = async (file, caption) => {
    const res = await api.incidents.uploadImage(incidentId, file, caption)
    return res?.success ? `/api/incidents/images/${res.data.id}` : null
  }
  // Sunucu alan hatası (2026-11-20, ör. 20 000 karakter sınırı): grubun altında, grup `aria-describedby` ile bağlı;
  // `data-invalid` odak kancası (form ilk hatalı alanın metin kutusuna odaklanır).
  return (
    <div role="group" aria-labelledby={labelId} aria-describedby={error ? errorId : undefined} data-slot="ih-md-field"
      data-field={name || undefined} data-invalid={error ? true : undefined} className={cn(FULL, 'mb-3.5 flex min-w-0 flex-col gap-1.5')}>
      <span id={labelId} className="text-sm font-semibold">{label}</span>
      {hint && <span className="-mt-1 text-xs text-muted-foreground">{hint}</span>}
      <MarkdownEditor value={value} onChange={onChange} editable height={height}
        uploadImage={uploadImage} makeUniqueCaption={makeUniqueCaption} />
      {error && <FieldError id={errorId}>{error}</FieldError>}
    </div>
  )
}

export function CheckInput({ label, hint, checked, onChange, disabled, error, name }) {
  const id = useId()
  const hintId = useId()
  const errorId = useId()
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ') || undefined
  return (
    <div className="mb-3.5 flex min-h-10 items-start gap-0.5 sm:min-h-9" data-field={name || undefined}>
      {/* Metinsiz sarmalayıcı etiket: dokunma alanı 40 px (ad yine htmlFor etiketinden gelir). */}
      <Label className="-my-2.5 -ml-3 inline-flex size-10 shrink-0 cursor-pointer items-center justify-center">
        <Checkbox id={id} checked={!!checked} disabled={disabled} aria-describedby={describedBy}
          aria-invalid={error ? true : undefined} onCheckedChange={(v) => onChange(v === true)} />
      </Label>
      <div className="flex min-w-0 flex-col gap-0.5">
        <Label htmlFor={id} className="cursor-pointer leading-snug font-medium">{label}</Label>
        {hint && <span id={hintId} className="text-xs text-muted-foreground">{hint}</span>}
        {error && <FieldError id={errorId}>{error}</FieldError>}
      </div>
    </div>
  )
}
