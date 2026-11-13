import { Braces } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import Field from '../../ui/Field.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Input } from '@/components/shadcn/input'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { cn } from '@/lib/utils'
import { MAX_PARAMS, MAX_PARAM_VALUE, PARAM_TYPES, guessParamType } from './sqlParams.js'

const ERROR_KEY = {
  required: 'sql.param.err.required', tooLong: 'sql.param.err.tooLong', number: 'sql.param.err.number', date: 'sql.param.err.date',
  timestamp: 'sql.param.err.timestamp', bool: 'sql.param.err.bool', type: 'sql.param.err.type',
}

/** Hata kodu (istemci) ya da sunucu metni → görünen metin. */
export function paramErrorText(t, err) {
  if (!err) return undefined
  return ERROR_KEY[err] ? t(ERROR_KEY[err], MAX_PARAM_VALUE) : String(err)
}

/**
 * Parametre formu (2026-11-13, B4): sorgudaki `:ad` yer tutucuları için değer + tür. Değerler sekmede (kişisel taslak)
 * saklanır; sunucuya `params: { ad: { value, type } }` gider, sunucu JDBC `?` ile türüyle bağlar. Hatalar alanın altında
 * (`params.<ad>` — istemci denetimi ya da sunucunun SQL_PARAM_MISSING / SQL_PARAM_INVALID alan hatası).
 */
export default function ParamsForm({ names, state, errors, onChange, className }) {
  const t = useT()
  if (!names?.length) return null
  const tooMany = names.length > MAX_PARAMS
  const set = (name, patch) => onChange?.(name, { ...(state?.[name] || { value: '', type: guessParamType(name) }), ...patch })
  return (
    <section data-slot="sql-params" aria-label={t('sql.param.title')}
      className={cn('min-w-0 rounded-xl border bg-card p-3 text-card-foreground shadow-sm', className)}>
      <div className="mb-2 flex flex-wrap items-center gap-2">
        <Braces aria-hidden="true" className="size-4 text-muted-foreground" />
        <h3 className="text-sm font-semibold">{t('sql.param.title')}</h3>
        <Badge variant="secondary" className="rounded-full tabular-nums">{names.length}</Badge>
        <span className="min-w-0 text-xs text-muted-foreground">{t('sql.param.hint')}</span>
      </div>
      {tooMany && (
        <div data-slot="sql-params-too-many"><AlertBanner tone="warning" className="mb-2">{t('sql.param.tooMany', MAX_PARAMS)}</AlertBanner></div>
      )}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">
        {names.slice(0, MAX_PARAMS).map((name) => {
          const p = state?.[name] || { value: '', type: guessParamType(name) }
          const type = PARAM_TYPES.includes(p.type) ? p.type : 'text'
          const key = `params.${name}`
          return (
            <div key={name} data-param={name} className="grid min-w-0 grid-cols-[minmax(0,1fr)_7.5rem] items-start gap-2">
              <Field label={<span className="font-mono [overflow-wrap:anywhere]">:{name}</span>} name={key} required
                error={paramErrorText(t, errors?.[key])}>
                {({ id, describedBy, invalid }) => (
                  type === 'bool' ? (
                    <div className="[&_[data-slot=native-select-wrapper]]:w-full">
                      <NativeSelect id={id} aria-describedby={describedBy} aria-invalid={invalid} value={p.value}
                        onChange={(e) => set(name, { value: e.target.value })} className="pointer-coarse:h-10">
                        <NativeSelectOption value="">{t('sql.param.pick')}</NativeSelectOption>
                        <NativeSelectOption value="true">true</NativeSelectOption>
                        <NativeSelectOption value="false">false</NativeSelectOption>
                      </NativeSelect>
                    </div>
                  ) : (
                    <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} value={p.value}
                      type={type === 'date' ? 'date' : type === 'timestamp' ? 'datetime-local' : 'text'}
                      step={type === 'timestamp' ? 1 : undefined}
                      inputMode={type === 'number' ? 'decimal' : undefined} maxLength={MAX_PARAM_VALUE}
                      onChange={(e) => set(name, { value: e.target.value })}
                      placeholder={type === 'number' ? '0' : type === 'text' ? t('sql.param.textPlaceholder') : undefined}
                      className="font-mono" />
                  )
                )}
              </Field>
              <Field label={t('sql.param.type')} name={`${key}.type`}>
                {({ id }) => (
                  <div className="[&_[data-slot=native-select-wrapper]]:w-full">
                    <NativeSelect id={id} value={type} aria-label={t('sql.param.typeFor', name)}
                      onChange={(e) => set(name, { type: e.target.value, value: '' })} className="pointer-coarse:h-10">
                      {PARAM_TYPES.map((ty) => <NativeSelectOption key={ty} value={ty}>{t(`sql.param.type.${ty}`)}</NativeSelectOption>)}
                    </NativeSelect>
                  </div>
                )}
              </Field>
            </div>
          )
        })}
      </div>
    </section>
  )
}
