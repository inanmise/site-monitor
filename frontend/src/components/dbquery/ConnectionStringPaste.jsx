import { useId, useState } from 'react'
import { CheckCircle2, ClipboardPaste, KeyRound, TriangleAlert, Wand2, CircleAlert } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { cn } from '@/lib/utils'
import { MAX_CONN_STRING, applyConnectionString, applySuggestedType, parseConnectionString } from './connectionString.js'

/** Doldurulan alanın etiket anahtarı (form etiketleriyle aynı metin). */
const FIELD_LABEL = {
  db_type: 'dbq.conn.dbType', host: 'dbq.conn.host', port: 'dbq.conn.port', database_name: 'dbq.conn.database',
  mssql_instance: 'dbq.conn.instance', username: 'dbq.conn.username', ssl_mode: 'dbq.conn.sslMode',
  mssql_encrypt: 'dbq.conn.encrypt', mssql_trust_server_certificate: 'dbq.conn.trustCert', oracle_mode: 'dbq.conn.oracleMode',
  oracle_tcps: 'dbq.conn.tcps',
}

/**
 * "Bağlantı dizesinden doldur" (A1, 2026-11-05) — JDBC / URI / ADO.NET / `sunucu:port/veritabanı` dizesini istemcide
 * ayrıştırıp bağlantı formunun alanlarını doldurur ve NEYİN doldurulduğunu söyler. Dizedeki PAROLA forma alınmaz: dize
 * parola içeriyorsa uyarı verilir ve yapıştırılan metin kutudan hemen silinir. Açık türlü dize türü değiştirir; yalnız
 * porttan çıkan tür ÖNERİLİR ("Türü X yap"). Yapıştırma (Ctrl/⌘+V) anında işler; elle yazılan dize "Doldur" ya da Enter ile.
 */
export default function ConnectionStringPaste({ form, onChange, disabled = false }) {
  const t = useT()
  const id = useId()
  const hintId = `${id}-hint`
  const [text, setText] = useState('')
  const [outcome, setOutcome] = useState(null)   // { kind, filled, notes, hadPassword, suggestType, error }

  const process = (raw) => {
    if (disabled) return
    const parsed = parseConnectionString(raw)
    if (!parsed.ok) {
      // Parola içeren başarısız dize de kutuda KALMAZ
      if (parsed.hadPassword) setText('')
      setOutcome({ kind: 'error', error: parsed.error, hadPassword: parsed.hadPassword, notes: parsed.notes || [] })
      return
    }
    const applied = applyConnectionString(form, parsed)
    onChange(applied.form)
    setText('')
    setOutcome({
      kind: 'ok', filled: applied.filled, notes: parsed.notes || [], hadPassword: parsed.hadPassword,
      suggestType: applied.suggestType, typeChanged: applied.typeChanged,
    })
  }

  /** Alan etiketi — Oracle'da veritabanı alanı servis adı / SID'dir. */
  const labelOf = (k) => {
    if (k === 'database_name' && form.db_type === 'ORACLE') return t(form.oracle_mode === 'SID' ? 'dbq.conn.sid' : 'dbq.conn.serviceName')
    return t(FIELD_LABEL[k] || 'dbq.conn.paste.field')
  }

  const acceptSuggestion = () => {
    if (!outcome?.suggestType) return
    const next = applySuggestedType(form, outcome.suggestType)
    onChange(next)
    setOutcome((o) => (o ? { ...o, suggestType: null, filled: [...new Set(['db_type', ...(o.filled || [])])] } : o))
  }

  return (
    <section data-slot="dbq-conn-paste" aria-labelledby={`${id}-title`}
      className="mb-3.5 flex min-w-0 flex-col gap-2 rounded-md border border-dashed bg-muted/30 p-3">
      <div className="flex min-w-0 items-start gap-2">
        <ClipboardPaste aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
        <div className="flex min-w-0 flex-col gap-0.5">
          <Label id={`${id}-title`} htmlFor={id} className="text-sm font-semibold">{t('dbq.conn.paste.title')}</Label>
          <p id={hintId} className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('dbq.conn.paste.hint')}</p>
        </div>
      </div>
      <div className="flex min-w-0 flex-col gap-2 sm:flex-row">
        <Input id={id} value={text} disabled={disabled} maxLength={MAX_CONN_STRING} aria-describedby={hintId}
          autoComplete="off" spellCheck={false} autoCapitalize="off" data-1p-ignore data-lpignore="true"
          placeholder={t('dbq.conn.paste.placeholder')} className="h-9 min-w-0 flex-1 font-mono pointer-coarse:h-10"
          onChange={(e) => { setText(e.target.value); if (outcome?.kind === 'error') setOutcome(null) }}
          onPaste={(e) => {
            const pasted = e.clipboardData?.getData('text')
            if (typeof pasted === 'string' && pasted.trim()) { e.preventDefault(); process(pasted) }
          }}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); if (text.trim()) process(text) } }} />
        <Button type="button" variant="secondary" disabled={disabled || !text.trim()} onClick={() => process(text)}
          data-slot="dbq-conn-paste-apply" className="shrink-0 pointer-coarse:h-10">
          <Wand2 aria-hidden="true" />{t('dbq.conn.paste.apply')}
        </Button>
      </div>

      <div aria-live="polite" className="flex min-w-0 flex-col gap-1.5">
        {outcome?.hadPassword && (
          <p data-slot="dbq-conn-paste-password" role="note"
            className="m-0 flex items-start gap-1.5 rounded-md border border-amber-500/40 bg-amber-500/5 px-2.5 py-2 text-xs text-amber-800 dark:bg-amber-500/10 dark:text-amber-200">
            <KeyRound aria-hidden="true" className="mt-px size-3.5 shrink-0" />
            <span className="[overflow-wrap:anywhere]">{t('dbq.conn.paste.passwordDropped')}</span>
          </p>
        )}
        {outcome?.kind === 'ok' && (
          <p data-slot="dbq-conn-paste-filled" className="m-0 flex items-start gap-1.5 text-xs text-foreground">
            <CheckCircle2 aria-hidden="true" className="mt-px size-3.5 shrink-0 text-success" />
            <span className="[overflow-wrap:anywhere]">
              {outcome.filled.length
                ? t('dbq.conn.paste.filled', outcome.filled.map(labelOf).join(', '))
                : t('dbq.conn.paste.nothingFilled')}
            </span>
          </p>
        )}
        {outcome?.kind === 'ok' && outcome.suggestType && (
          <div data-slot="dbq-conn-paste-suggest" data-type={outcome.suggestType}
            className="flex min-w-0 flex-col gap-1.5 rounded-md border bg-background px-2.5 py-2 text-xs sm:flex-row sm:items-center sm:justify-between">
            <span className="[overflow-wrap:anywhere]">
              {t('dbq.conn.paste.suggestType', t(`dbq.conn.type.${outcome.suggestType}`), t(`dbq.conn.type.${form.db_type}`))}
            </span>
            <Button type="button" variant="outline" size="sm" onClick={acceptSuggestion} disabled={disabled}
              data-slot="dbq-conn-paste-suggest-apply" className="shrink-0 self-start pointer-coarse:h-10 sm:self-auto">
              {t('dbq.conn.paste.useType', t(`dbq.conn.type.${outcome.suggestType}`))}
            </Button>
          </div>
        )}
        {(outcome?.notes || []).length > 0 && (
          <ul data-slot="dbq-conn-paste-notes" className="m-0 flex list-none flex-col gap-1 p-0">
            {outcome.notes.map((n, i) => (
              <li key={`${n.code}-${i}`} data-code={n.code} className="flex items-start gap-1.5 text-xs text-amber-800 dark:text-amber-200">
                <TriangleAlert aria-hidden="true" className="mt-px size-3.5 shrink-0" />
                <span className="[overflow-wrap:anywhere]">{t(`dbq.conn.paste.note.${n.code}`, n.value ?? '')}</span>
              </li>
            ))}
          </ul>
        )}
        {outcome?.kind === 'error' && (
          <p data-slot="dbq-conn-paste-error" role="alert"
            className={cn('m-0 flex items-start gap-1.5 text-xs text-destructive')}>
            <CircleAlert aria-hidden="true" className="mt-px size-3.5 shrink-0" />
            <span className="[overflow-wrap:anywhere]">{t(`dbq.conn.paste.err.${outcome.error || 'UNRECOGNIZED'}`, MAX_CONN_STRING)}</span>
          </p>
        )}
      </div>
    </section>
  )
}
