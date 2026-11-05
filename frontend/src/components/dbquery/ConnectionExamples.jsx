import { useState } from 'react'
import { BookOpenText, ChevronDown } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import CopyButton from '../ui/CopyButton.jsx'
import { cn } from '@/lib/utils'
import { CONNECT_TIMEOUT, DEFAULT_PORT } from './connectionModel.js'
import { EXAMPLE_FIELD_ORDER, examplesFor, placeholdersFor } from './connectionExamples.js'

/**
 * "Bağlantı örnekleri" (Veritabanı Sorgu İzleme, 2026-11-05) — bağlantı formunda, seçili veritabanı türünün hazır
 * senaryoları (alan alan değerler + kopyalanabilir bağlantı dizesi) ve "Alan alan ne yazılır" listesi. Kapalı başlar;
 * tür değişince örnekler de değişir. Örnek değerler gerçek değil (example.com), parola hiçbir örnekte yok. Dizeler
 * "Bağlantı dizesinden doldur" kutusuna yapıştırılabilir (`showPasteHint`). Saf sunum — formu değiştirmez.
 */
export default function ConnectionExamples({ type, oracleMode = 'SERVICE', showPasteHint = true }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const examples = examplesFor(type)
  if (!examples.length) return null
  const typeLabel = t(`dbq.conn.type.${type}`)
  return (
    <Collapsible open={open} onOpenChange={setOpen} data-slot="dbq-conn-examples" data-type={type} className="mb-3.5 rounded-md border">
      <CollapsibleTrigger asChild>
        <Button type="button" variant="ghost" aria-expanded={open} data-slot="dbq-conn-examples-toggle"
          className="h-auto w-full justify-start gap-2 rounded-none px-3 py-2.5 text-left font-semibold whitespace-normal hover:bg-muted/60 pointer-coarse:min-h-10">
          <ChevronDown aria-hidden="true" className={cn('size-4 shrink-0 text-muted-foreground transition-transform motion-reduce:transition-none', open && 'rotate-180')} />
          <BookOpenText aria-hidden="true" className="size-4 shrink-0" />
          <span className="[overflow-wrap:anywhere]">{t('dbq.conn.ex.title', typeLabel)}</span>
        </Button>
      </CollapsibleTrigger>
      <CollapsibleContent className="flex flex-col gap-3 px-3 pb-3">
        <p className="m-0 text-xs text-muted-foreground">{t('dbq.conn.ex.hint')}</p>
        {showPasteHint && <p data-slot="dbq-conn-examples-paste" className="m-0 text-xs text-muted-foreground">{t('dbq.conn.ex.pasteHint')}</p>}
        <ul className="m-0 flex list-none flex-col gap-3 p-0">
          {examples.map((ex) => <ExampleCard key={ex.id} ex={ex} type={type} t={t} />)}
        </ul>
        <FieldGuide type={type} oracleMode={oracleMode} typeLabel={typeLabel} t={t} />
      </CollapsibleContent>
    </Collapsible>
  )
}

/** Örnek alanının formdaki etiketi (Oracle veritabanı alanı kipe göre servis adı / SID). */
export function exampleFieldLabel(field, type, oracleMode, t) {
  switch (field) {
    case 'host': return t('dbq.conn.host')
    case 'port': return t('dbq.conn.port')
    case 'mssql_instance': return t('dbq.conn.instance')
    case 'oracle_mode': return t('dbq.conn.oracleMode')
    case 'oracle_tcps': return t('dbq.conn.tcps')
    case 'database_name':
      if (type === 'ORACLE') return t(oracleMode === 'SID' ? 'dbq.conn.sid' : 'dbq.conn.serviceName')
      return t('dbq.conn.database')
    case 'ssl_mode': return t('dbq.conn.sslMode')
    case 'mssql_encrypt': return t('dbq.conn.encrypt')
    case 'mssql_trust_server_certificate': return t('dbq.conn.trustCert')
    case 'username': return t('dbq.conn.username')
    default: return field
  }
}

/** Örnek değerin gösterimi: aç / kapa anahtarları "Açık" / "Kapalı", Oracle kipi kendi adıyla. */
export function exampleFieldValue(field, value, t) {
  if (typeof value === 'boolean') return t(value ? 'dbq.conn.ex.on' : 'dbq.conn.ex.off')
  if (field === 'oracle_mode') return t(`dbq.conn.oracleModeName.${value}`)
  return String(value)
}

function ExampleCard({ ex, type, t }) {
  const title = t(`dbq.conn.ex.${ex.id}.title`)
  const keys = EXAMPLE_FIELD_ORDER.filter((k) => Object.prototype.hasOwnProperty.call(ex.fields, k))
  return (
    <li data-slot="dbq-conn-example" data-id={ex.id} className="flex min-w-0 flex-col gap-2 rounded-md border bg-muted/30 p-3">
      <div className="flex min-w-0 flex-col gap-0.5">
        <h4 className="m-0 text-sm font-semibold [overflow-wrap:anywhere]">{title}</h4>
        <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{t(`dbq.conn.ex.${ex.id}.note`)}</p>
      </div>
      <dl className="m-0 grid grid-cols-1 gap-x-4 gap-y-1.5 sm:grid-cols-2">
        {keys.map((k) => (
          <div key={k} data-slot="dbq-conn-example-field" data-field={k} className="flex min-w-0 flex-col">
            <dt className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{exampleFieldLabel(k, type, ex.fields.oracle_mode, t)}</dt>
            <dd className={cn('m-0 text-sm [overflow-wrap:anywhere]', typeof ex.fields[k] !== 'boolean' && k !== 'oracle_mode' && 'font-mono')}>
              {exampleFieldValue(k, ex.fields[k], t)}
            </dd>
          </div>
        ))}
      </dl>
      <div className="flex min-w-0 flex-col gap-1">
        <span className="text-xs font-semibold">{t('dbq.conn.ex.connString')}</span>
        <div className="flex min-w-0 items-start gap-2">
          <code data-slot="dbq-conn-example-string"
            className="min-w-0 flex-1 rounded-md border bg-background px-2.5 py-1.5 font-mono text-xs leading-5 whitespace-pre-wrap text-foreground [overflow-wrap:anywhere]">
            {ex.conn}
          </code>
          <CopyButton value={ex.conn} label={t('dbq.conn.ex.copy', title)} copiedLabel={t('dbq.conn.ex.copied')}
            className="shrink-0 pointer-coarse:size-10" />
        </div>
      </div>
    </li>
  )
}

/** "Alan alan ne yazılır" — formun alan sırasıyla, türe göre örnek değerler ve sunucu kuralları. */
function FieldGuide({ type, oracleMode, typeLabel, t }) {
  const ph = placeholdersFor(type, oracleMode)
  const rows = [
    ['name', t('dbq.conn.name'), t('dbq.conn.guide.name')],
    ['host', t('dbq.conn.host'), t('dbq.conn.guide.host', ph.host)],
    ['port', t('dbq.conn.port'), t('dbq.conn.guide.port', typeLabel, DEFAULT_PORT[type])],
    ...(type === 'MSSQL' ? [['mssql_instance', t('dbq.conn.instance'), t('dbq.conn.guide.instance')]] : []),
    ['database_name', exampleFieldLabel('database_name', type, oracleMode, t),
      type === 'ORACLE'
        ? t(oracleMode === 'SID' ? 'dbq.conn.guide.sid' : 'dbq.conn.guide.service', ph.database_name)
        : t('dbq.conn.guide.database', ph.database_name)],
    ['username', t('dbq.conn.username'), t('dbq.conn.guide.username', ph.username)],
    ['password', t('dbq.conn.password'), t('dbq.conn.guide.password')],
    ['connect_timeout_ms', t('dbq.conn.timeout'), t('dbq.conn.guide.timeout', CONNECT_TIMEOUT.min, CONNECT_TIMEOUT.max, CONNECT_TIMEOUT.def)],
    ['description', t('dbq.conn.description'), t('dbq.conn.guide.description')],
  ]
  return (
    <div data-slot="dbq-conn-guide" className="flex min-w-0 flex-col gap-2 rounded-md border p-3">
      <h4 className="m-0 text-sm font-semibold">{t('dbq.conn.guide.title')}</h4>
      <dl className="m-0 grid grid-cols-1 gap-x-4 gap-y-2 sm:grid-cols-[minmax(0,11rem)_minmax(0,1fr)]">
        {rows.map(([key, label, text]) => (
          <div key={key} data-slot="dbq-conn-guide-row" data-field={key} className="contents">
            <dt className="text-xs font-semibold [overflow-wrap:anywhere]">{label}</dt>
            <dd className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere] max-sm:mb-1">{text}</dd>
          </div>
        ))}
      </dl>
    </div>
  )
}
