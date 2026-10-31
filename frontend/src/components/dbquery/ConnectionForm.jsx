import { useId } from 'react'
import { KeyRound, ShieldAlert } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { Label } from '@/components/shadcn/label'
import { Switch } from '@/components/shadcn/switch'
import { Checkbox } from '@/components/shadcn/checkbox'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { cn } from '@/lib/utils'
import Field from '../ui/Field.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import { CONNECT_TIMEOUT, DB_TYPES, ORACLE_MODES, PG_SSL_MODES, changeDbType } from './connectionModel.js'

const CONTROL = 'h-9 w-full pointer-coarse:h-10'
const FULL_SELECT = '[&>[data-slot=native-select-wrapper]]:w-full'
const GRID = 'grid grid-cols-1 gap-x-4 sm:grid-cols-2'

/**
 * Bağlantı formu (Veritabanı Sorgu İzleme, 2026-10-31) — DB türüne göre alanlar: PostgreSQL sunucu / port 5432 /
 * veritabanı / SSL kipi; SQL Server sunucu / port 1433 / örnek / veritabanı / şifrele / sertifikaya güven; Oracle sunucu /
 * port 1521 / servis adı ya da SID. Ortak: kullanıcı adı, parola (kayıtlıysa "kayıtlı — değiştirmek için yazın"; kayıtlı
 * parola ASLA gösterilmez ya da alana doldurulmaz), bağlanma zaman aşımı, açıklama. Hatalar alanın altında (`errors`).
 * Saf sunum: durumu çağıran tutar (`form`, `onChange(form)`).
 */
export default function ConnectionForm({ form, onChange, errors = {}, disabled = false, teams = [], showTeam = false }) {
  const t = useT()
  const set = (patch) => onChange({ ...form, ...patch })
  const type = form.db_type
  const encId = useId(), trustId = useId(), clearId = useId()

  const field = (name, label, props = {}, extra = {}) => (
    <Field label={label} required={extra.required} name={name} error={errors[name]} hint={extra.hint} className={extra.className}>
      {({ id, describedBy, invalid }) => (
        <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled} className={CONTROL}
          value={form[name] ?? ''} onChange={(e) => set({ [name]: e.target.value })} autoComplete="off" spellCheck={false} {...props} />
      )}
    </Field>
  )

  return (
    <div data-slot="dbq-conn-form" data-type={type} className="flex min-w-0 flex-col">
      {showTeam && teams.length > 1 && (
        <Field label={t('dbq.conn.team')} required name="team_id" error={errors.team_id} className={FULL_SELECT}>
          {({ id, describedBy, invalid }) => (
            <NativeSelect id={id} aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled} className={CONTROL}
              value={form.team_id ?? ''} onChange={(e) => set({ team_id: e.target.value === '' ? null : Number(e.target.value) })}>
              {teams.map((tm) => <NativeSelectOption key={tm.id} value={tm.id}>{tm.name}</NativeSelectOption>)}
            </NativeSelect>
          )}
        </Field>
      )}

      <div className={GRID}>
        {field('name', t('dbq.conn.name'), {}, { required: true })}
        <div className="mb-3.5 flex flex-col gap-1.5" data-field="db_type">
          <span className="text-sm font-semibold">{t('dbq.conn.dbType')} <span data-slot="field-required" className="text-destructive">*</span></span>
          <SegmentedControl value={type} ariaLabel={t('dbq.conn.dbType')} className="w-full" itemClassName="flex-1"
            onChange={(v) => !disabled && onChange(changeDbType(form, v))}
            options={DB_TYPES.map((d) => ({ value: d, label: t(`dbq.conn.type.${d}`), disabled }))} />
          {errors.db_type && <p className="m-0 text-xs text-destructive">{errors.db_type}</p>}
        </div>
      </div>

      <div className="grid grid-cols-1 gap-x-4 sm:grid-cols-[minmax(0,1fr)_9rem]">
        {field('host', t('dbq.conn.host'), { placeholder: 'db01.example.com' }, { required: true, hint: t('dbq.conn.hostHint') })}
        {field('port', t('dbq.conn.port'), { inputMode: 'numeric' }, { required: true })}
      </div>

      {type === 'MSSQL' && field('mssql_instance', t('dbq.conn.instance'), { placeholder: 'SQL01' }, { hint: t('dbq.conn.instanceHint') })}

      {type === 'ORACLE' && (
        <div className="mb-3.5 flex flex-col gap-1.5" data-field="oracle_mode">
          <span className="text-sm font-semibold">{t('dbq.conn.oracleMode')}</span>
          <SegmentedControl value={form.oracle_mode} ariaLabel={t('dbq.conn.oracleMode')} className="w-full sm:w-auto" itemClassName="flex-1 sm:flex-none"
            onChange={(v) => !disabled && set({ oracle_mode: v })}
            options={ORACLE_MODES.map((m) => ({ value: m, label: t(`dbq.conn.oracleModeName.${m}`), disabled }))} />
        </div>
      )}

      <div className={GRID}>
        {field('database_name',
          type === 'ORACLE' ? t(form.oracle_mode === 'SID' ? 'dbq.conn.sid' : 'dbq.conn.serviceName') : t('dbq.conn.database'),
          { className: cn(CONTROL, 'font-mono') }, { required: true })}
        {type === 'POSTGRESQL' && (
          <Field label={t('dbq.conn.sslMode')} name="ssl_mode" error={errors.ssl_mode} hint={t(`dbq.conn.sslHint.${form.ssl_mode}`)} className={FULL_SELECT}>
            {({ id, describedBy, invalid }) => (
              <NativeSelect id={id} aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled} className={CONTROL}
                value={form.ssl_mode} onChange={(e) => set({ ssl_mode: e.target.value })}>
                {PG_SSL_MODES.map((m) => <NativeSelectOption key={m} value={m}>{m}</NativeSelectOption>)}
              </NativeSelect>
            )}
          </Field>
        )}
      </div>

      {type === 'MSSQL' && (
        <div className="mb-3.5 flex flex-col gap-2 rounded-md border p-3">
          <div className="flex min-h-9 items-center gap-2 pointer-coarse:min-h-10">
            <Switch id={encId} checked={!!form.mssql_encrypt} disabled={disabled} onCheckedChange={(b) => set({ mssql_encrypt: b === true })} />
            <Label htmlFor={encId} className="cursor-pointer self-stretch font-normal">{t('dbq.conn.encrypt')}</Label>
          </div>
          <div className="flex min-h-9 items-center gap-2 pointer-coarse:min-h-10">
            <Switch id={trustId} checked={!!form.mssql_trust_server_certificate} disabled={disabled}
              onCheckedChange={(b) => set({ mssql_trust_server_certificate: b === true })} />
            <Label htmlFor={trustId} className="cursor-pointer self-stretch font-normal">{t('dbq.conn.trustCert')}</Label>
          </div>
          {form.mssql_trust_server_certificate && (
            <p className="m-0 inline-flex items-start gap-1.5 text-xs text-amber-700 dark:text-amber-300">
              <ShieldAlert aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />{t('dbq.conn.trustCertWarn')}
            </p>
          )}
        </div>
      )}

      <div className={GRID}>
        {field('username', t('dbq.conn.username'), {}, { required: true })}
        <Field label={t('dbq.conn.password')} required={!form.password_set || form.password_clear} name="password" error={errors.password}
          hint={form.password_set && !form.password_clear ? t('dbq.conn.passwordStoredHint') : t('dbq.conn.passwordHint')}>
          {({ id, describedBy, invalid }) => (
            <Input id={id} type="password" aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled || form.password_clear}
              className={CONTROL} autoComplete="new-password" data-1p-ignore data-lpignore="true"
              placeholder={form.password_set && !form.password_clear ? t('dbq.conn.passwordStored') : ''}
              value={form.password} onChange={(e) => set({ password: e.target.value })} />
          )}
        </Field>
      </div>
      {form.password_set && (
        <div className="mb-3.5 flex min-h-9 items-center gap-2 pointer-coarse:min-h-10">
          <Checkbox id={clearId} checked={!!form.password_clear} disabled={disabled}
            onCheckedChange={(b) => set({ password_clear: b === true, password: b === true ? '' : form.password })} />
          <Label htmlFor={clearId} className="cursor-pointer font-normal"><KeyRound aria-hidden="true" className="size-3.5" />{t('dbq.conn.passwordClear')}</Label>
        </div>
      )}

      {field('connect_timeout_ms', t('dbq.conn.timeout'), { inputMode: 'numeric', className: cn(CONTROL, 'tabular-nums sm:w-48') },
        { hint: t('dbq.conn.timeoutHint', CONNECT_TIMEOUT.min, CONNECT_TIMEOUT.max, CONNECT_TIMEOUT.def) })}

      <Field label={t('dbq.conn.description')} name="description" error={errors.description} className="mb-0">
        {({ id, describedBy, invalid }) => (
          <Textarea id={id} aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled} rows={2}
            value={form.description ?? ''} onChange={(e) => set({ description: e.target.value })} />
        )}
      </Field>
    </div>
  )
}
