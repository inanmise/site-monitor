import { TriangleAlert, Users } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { Progress } from '@/components/shadcn/progress'
import { cn } from '@/lib/utils'

/** Bu oranın altı "düşük kapsam" (uyarı tonu) — kullanıcıların en az %80'i kodu alabilmeli. */
export const LOW_COVERAGE = 0.8

/**
 * Kapsam özeti — `{ active_users, with_phone, with_email }` (sunucuda TEK toplu sorgu) → `{ have, total, pct, low }`;
 * aktif kullanıcı yoksa / veri yoksa null. Yüzde AŞAĞI yuvarlanır (%79,6 "%80" görünüp uyarı vermesin).
 */
export function coverageOf(coverage, kind) {
  const total = Number(coverage?.active_users) || 0
  if (total <= 0) return null
  const raw = Number(kind === 'phone' ? coverage?.with_phone : coverage?.with_email) || 0
  const have = Math.max(0, Math.min(raw, total))
  return { have, total, pct: Math.floor((have / total) * 100), low: have / total < LOW_COVERAGE }
}

/**
 * Yöntem kartındaki kişi bilgisi KAPSAMI ipucu (2026-10-03) — "Kayıtlı cep telefonu olan aktif kullanıcı: N / M (%x)" +
 * ince ilerleme çubuğu; %80'in altında uyarı tonu ve "bu kullanıcılar kod alamaz" açıklaması. Kişi bilgisi değil, yalnız
 * sayılar. Sol renk şeridi YOK (ton metin + simge + çubuk rengiyle).
 *
 * Test kancası: `data-slot="lm-coverage"` + `data-kind` (phone / email) + `data-tone` (ok / warn).
 */
export default function LoginMethodsCoverage({ coverage, kind }) {
  const t = useT()
  const c = coverageOf(coverage, kind)
  if (!c) return null
  const Icon = c.low ? TriangleAlert : Users
  const text = kind === 'phone' ? t('lm.coverage.phone', c.have, c.total, c.pct) : t('lm.coverage.email', c.have, c.total, c.pct)
  return (
    <div data-slot="lm-coverage" data-kind={kind} data-tone={c.low ? 'warn' : 'ok'}
      className="flex min-w-0 flex-col gap-2 rounded-md border bg-muted/40 p-2.5 sm:p-3">
      <p className={cn('m-0 flex min-w-0 items-start gap-2 text-xs', c.low ? 'font-medium text-warning' : 'text-muted-foreground')}>
        <Icon aria-hidden="true" className="mt-px size-3.5 shrink-0" />
        <span className="min-w-0 [overflow-wrap:anywhere]">{text}</span>
      </p>
      <Progress value={c.pct} aria-label={t('lm.coverage.label')} className="h-1.5"
        indicatorClassName={c.low ? 'bg-warning' : undefined} />
      {c.low && (
        <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">
          {kind === 'phone' ? t('lm.coverage.phoneLow') : t('lm.coverage.emailLow')}
        </p>
      )}
    </div>
  )
}
