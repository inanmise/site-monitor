import { useId, useState } from 'react'
import { ChevronDown, FileKey2, KeyRound, ShieldAlert } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { Label } from '@/components/shadcn/label'
import { Switch } from '@/components/shadcn/switch'
import { Checkbox } from '@/components/shadcn/checkbox'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Button } from '@/components/shadcn/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import CopyButton from '../ui/CopyButton.jsx'
import { cn } from '@/lib/utils'
import Field from '../ui/Field.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import {
  CONNECT_TIMEOUT, DB_TYPES, ORACLE_MODES, ORACLE_TCPS_PORT, PG_SSL_MODES, PG_WEAK_SSL, changeDbType, readOnlyAccountScript, setOracleTcps,
} from './connectionModel.js'

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
  const encId = useId(), trustId = useId(), clearId = useId(), tcpsId = useId()

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
      {type === 'ORACLE' && (
        // Oracle TCPS (TLS, 2.B): açılınca port varsayılandaysa 2484; sunucu adı sertifikayla doğrulanır
        <div data-slot="dbq-conn-tcps" className="mb-3.5 flex flex-col gap-1 rounded-md border p-3">
          <div className="flex min-h-9 items-center gap-2 pointer-coarse:min-h-10">
            <Switch id={tcpsId} checked={!!form.oracle_tcps} disabled={disabled} onCheckedChange={(b) => onChange(setOracleTcps(form, b === true))} />
            <Label htmlFor={tcpsId} className="cursor-pointer self-stretch font-normal">{t('dbq.conn.tcps')}</Label>
          </div>
          <p className="m-0 text-xs text-muted-foreground">{t(form.oracle_tcps ? 'dbq.conn.tcpsOnHint' : 'dbq.conn.tcpsOffHint', ORACLE_TCPS_PORT)}</p>
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

      {type === 'POSTGRESQL' && PG_WEAK_SSL.includes(form.ssl_mode) && (
        // `prefer` sunucu desteklemezse ŞİFRESİZ bağlantıya düşer; `disable` hiç şifrelemez (2.B) — kimlik bilgisi ve alarm
        // satırları ağda açık akabilir. Yeni bağlantının varsayılanı `require`.
        <p data-slot="dbq-conn-ssl-weak" data-mode={form.ssl_mode}
          className="mb-3.5 mt-0 flex items-start gap-1.5 rounded-md border border-amber-500/40 bg-amber-500/5 px-2.5 py-2 text-xs text-amber-800 dark:bg-amber-500/10 dark:text-amber-200">
          <ShieldAlert aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
          <span className="[overflow-wrap:anywhere]">{t(form.ssl_mode === 'disable' ? 'dbq.conn.sslWeak.disable' : 'dbq.conn.sslWeak.prefer')}</span>
        </p>
      )}

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
        {/* Parola isteğe bağlı (O1): boş = parolasız bağlantı (yeni) / kayıtlı parola değişmez (düzenleme). Zorunlu yıldızı yok. */}
        <Field label={t('dbq.conn.password')} name="password" error={errors.password}
          hint={form.password_set
            ? t(form.password_clear ? 'dbq.conn.passwordClearHint' : 'dbq.conn.passwordStoredHint')
            : t('dbq.conn.passwordOptionalHint')}>
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

      <ReadOnlyAccountScript type={type} />

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

/**
 * "Salt-okur hesap betiği" (2.B): DBA'ya verilecek, türe göre hazır örnek (statik metin — SiteMonitor çalıştırmaz, sunucuya
 * gitmez). Kapalı başlar; kopyalanabilir; uzun satırlar KENDİ kabında yatay kayar (sayfa kaymaz).
 */
function ReadOnlyAccountScript({ type }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const script = readOnlyAccountScript(type, t)
  return (
    <Collapsible open={open} onOpenChange={setOpen} data-slot="dbq-conn-dba" className="mb-3.5 rounded-md border">
      <CollapsibleTrigger asChild>
        <Button type="button" variant="ghost" aria-expanded={open}
          className="h-auto w-full justify-start gap-2 rounded-none px-3 py-2.5 text-left font-semibold whitespace-normal hover:bg-muted/60 pointer-coarse:min-h-10">
          <ChevronDown aria-hidden="true" className={cn('size-4 shrink-0 text-muted-foreground transition-transform motion-reduce:transition-none', open && 'rotate-180')} />
          <FileKey2 aria-hidden="true" className="size-4 shrink-0" />
          <span className="[overflow-wrap:anywhere]">{t('dbq.conn.dba.title')}</span>
        </Button>
      </CollapsibleTrigger>
      <CollapsibleContent className="flex flex-col gap-2 px-3 pb-3">
        <p className="m-0 text-xs text-muted-foreground">{t('dbq.conn.dba.hint')}</p>
        <div className="flex min-w-0 items-start gap-2">
          <pre data-slot="dbq-conn-dba-script" tabIndex={0} aria-label={t('dbq.conn.dba.title')}
            className="m-0 min-w-0 flex-1 overflow-x-auto rounded-md border bg-muted/50 px-3 py-2 font-mono text-xs leading-5 whitespace-pre text-foreground outline-none focus-visible:ring-2 focus-visible:ring-ring">
            {script}
          </pre>
          <CopyButton value={script} label={t('dbq.conn.dba.copy')} copiedLabel={t('dbq.conn.dba.copied')}
            className="shrink-0 pointer-coarse:size-10" />
        </div>
      </CollapsibleContent>
    </Collapsible>
  )
}
