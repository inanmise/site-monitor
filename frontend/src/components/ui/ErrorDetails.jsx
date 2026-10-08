import { useMemo } from 'react'
import { ChevronRight } from 'lucide-react'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Button } from '@/components/shadcn/button'
import CopyButton from './CopyButton.jsx'
import { useT } from '@/i18n/index.jsx'
import { errorInfoOf, lookupErrorInfo } from '@/utils/errorMessages.js'
import { cn } from '@/lib/utils'

/** Panoya giden tek satır: "HTTP 403 · code FORBIDDEN · request 1a2b…" — dilden bağımsız (destek ekibi okur). */
export function errorDetailText(info) {
  if (!info) return ''
  const parts = []
  if (info.status !== undefined) parts.push(info.status ? `HTTP ${info.status}` : 'HTTP 0 (no response)')
  if (info.code) parts.push(`code ${info.code}`)
  if (info.requestId) parts.push(`request ${info.requestId}`)
  return parts.join(' · ')
}

/**
 * Katlanır "Teknik ayrıntı" (2026-10-08): HTTP durumu · hata kodu · istek kimliği. Kullanıcıya önce SADE metin
 * gösterilir; destek için gereken künye bir tık altında durur ve tek tuşla kopyalanır.
 *
 * Künye kaynağı: `info` (açıkça verilen gövde/ApiError ya da {status, code, requestId}) yoksa `message` ile
 * istemcinin kısa ömürlü metin→künye kaydı (`utils/errorMessages.lookupErrorInfo`) — böylece ortak bildirim/afiş
 * bileşenleri çağrı yerlerine dokunmadan künyeyi bulur. Künye yoksa HİÇBİR ŞEY çizilmez.
 *
 * `onOpenChange`: açılınca bildirimin kendiliğinden kapanma süresi durdurulur (Toast). Test kancası:
 * kök `data-slot="error-details"`.
 */
export default function ErrorDetails({ info, message, onOpenChange, className }) {
  const t = useT()
  const resolved = useMemo(
    () => errorInfoOf(info) ?? (typeof message === 'string' ? lookupErrorInfo(message) : null),
    [info, message],
  )
  if (!resolved) return null
  const rows = []
  if (resolved.status !== undefined) {
    rows.push(['status', t('errinfo.status'), resolved.status ? String(resolved.status) : t('errinfo.noResponse')])
  }
  if (resolved.code) rows.push(['code', t('errinfo.code'), resolved.code])
  if (resolved.requestId) rows.push(['request', t('errinfo.requestId'), resolved.requestId])
  if (rows.length === 0) return null
  return (
    <Collapsible data-slot="error-details" onOpenChange={onOpenChange} className={cn('w-full text-xs', className)}>
      <CollapsibleTrigger asChild>
        <Button type="button" variant="link" size="xs"
          className="group/errinfo h-auto min-h-6 px-0 text-current opacity-80 hover:opacity-100">
          <ChevronRight className="transition-transform group-data-[state=open]/errinfo:rotate-90" aria-hidden="true" />
          {t('errinfo.toggle')}
        </Button>
      </CollapsibleTrigger>
      <CollapsibleContent className="mt-1 rounded-md border border-current/20 bg-background/40 px-2 py-1.5">
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5">
          {rows.map(([key, label, value]) => (
            <div key={key} className="contents" data-detail={key}>
              <dt className="opacity-80">{label}</dt>
              <dd className="font-mono [overflow-wrap:anywhere]">{value}</dd>
            </div>
          ))}
        </dl>
        <div className="mt-1 flex flex-wrap items-center gap-2">
          <span className="opacity-80">{t('errinfo.hint')}</span>
          <CopyButton value={errorDetailText(resolved)} label={t('errinfo.copy')} copiedLabel={t('errinfo.copied')}
            variant="ghost" buttonSize="icon-xs" size={12} />
        </div>
      </CollapsibleContent>
    </Collapsible>
  )
}
