import { Check, CircleAlert, Info, ShieldCheck } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import CopyButton from '../ui/CopyButton.jsx'
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '@/components/shadcn/accordion'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { hstsAdvice, maxAgeVerdict, normalizePolicy, preloadMissing } from './hstsModel.js'

/**
 * Sağlık sekmesi → "HSTS başlığı gönderiliyor" satırı açılınca (2026-10-08, kullanıcı: "HSTS çok bilinen bir konu değil;
 * ne işe yarıyor, neden missing, eklemezse ne olur, preload / includeSubDomains eklenmeli mi — kapsamlı anlatılmalı").
 *
 * <p>İki parça: (1) BU SİTEDE DURUM — sunucunun gönderdiği ham başlık, yönerge yönerge değerlendirme ve "ne yapmalı"
 * önerileri (`hstsModel`, sunucunun `hsts_policy` kanıtından); (2) SSS — HSTS nedir, eklenmezse ne olur, neden "Yok"
 * görünür, nasıl eklenir (örnek yapılandırmalar), includeSubDomains / preload eklenmeli mi. Politika henüz yoksa (hiç
 * bakılmamış ya da eski kayıt) yalnız açıklama + "Şimdi kontrol et" yönlendirmesi.
 */
const TONE = {
  good: 'border-emerald-500/40 bg-emerald-500/10 text-emerald-800 dark:text-emerald-300',
  ok: 'border-emerald-500/30 bg-emerald-500/5 text-emerald-800 dark:text-emerald-300',
  warn: 'border-amber-500/40 bg-amber-500/10 text-amber-900 dark:text-amber-300',
  bad: 'border-destructive/40 bg-destructive/10 text-destructive',
  muted: 'border-border bg-muted text-muted-foreground',
}
const MAX_AGE_TONE = { good: 'good', ok: 'ok', short: 'warn', off: 'bad', missing: 'bad' }
const ADVICE_ICON = { warn: CircleAlert, info: Info, ok: Check }

const EXAMPLE = 'max-age=31536000; includeSubDomains'
const SNIPPETS = [
  { id: 'nginx', code: `add_header Strict-Transport-Security "${EXAMPLE}" always;` },
  { id: 'apache', code: `Header always set Strict-Transport-Security "${EXAMPLE}"` },
  { id: 'iis', code: `<httpProtocol>\n  <customHeaders>\n    <add name="Strict-Transport-Security" value="${EXAMPLE}" />\n  </customHeaders>\n</httpProtocol>` },
]

function Verdict({ tone, children }) {
  return (
    <Badge variant="outline" data-slot="hsts-verdict" data-tone={tone} className={cn('h-5 shrink-0 px-1.5 text-[11px] font-semibold', TONE[tone] || TONE.muted)}>
      {children}
    </Badge>
  )
}

function Directive({ id, name, value, verdict, tone, desc }) {
  return (
    <div data-slot="hsts-directive" data-directive={id} className="flex min-w-0 flex-col gap-1 rounded-md border p-3">
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <code className="font-mono text-[12.5px] font-semibold">{name}</code>
        <Verdict tone={tone}>{verdict}</Verdict>
        {value && <span className="text-xs text-muted-foreground tabular-nums">{value}</span>}
      </div>
      <p className="m-0 text-xs leading-snug text-muted-foreground">{desc}</p>
    </div>
  )
}

export default function HstsExplainer({ policy: rawPolicy }) {
  const t = useT()
  const policy = normalizePolicy(rawPolicy)
  const v = policy ? maxAgeVerdict(policy) : null
  const advice = policy ? hstsAdvice(policy) : []
  const days = policy?.maxAge != null ? Math.floor(policy.maxAge / 86_400) : null
  const reqText = (reqs) => (reqs || []).map((r) => t(`hsts.req.${r}`)).join(', ')
  const preloadOk = !!policy?.preload && preloadMissing(policy).length === 0

  return (
    <div data-slot="hsts-explainer" className="flex min-w-0 flex-col gap-3 px-3 pb-3 sm:px-4">
      <section data-slot="hsts-current" className="flex min-w-0 flex-col gap-2">
        <h4 className="m-0 flex items-center gap-1.5 text-xs font-bold tracking-wide text-muted-foreground uppercase">
          <ShieldCheck aria-hidden="true" className="size-3.5" />{t('hsts.current.title')}
        </h4>
        {!policy ? (
          <p data-slot="hsts-no-policy" className="m-0 text-sm text-muted-foreground">{t('hsts.current.noPolicy')}</p>
        ) : (
          <>
            <div className="flex min-w-0 flex-col gap-1 rounded-md border bg-muted/40 p-3">
              <span className="text-xs text-muted-foreground">{t('hsts.current.header')}</span>
              {policy.header
                ? (
                  <span className="flex min-w-0 items-start gap-2">
                    <code data-slot="hsts-header" className="min-w-0 flex-1 font-mono text-[12.5px] [overflow-wrap:anywhere]">Strict-Transport-Security: {policy.header}</code>
                    <CopyButton value={policy.header} label={t('hsts.copyHeader')} copiedLabel={t('hsts.copied')} />
                  </span>
                )
                : <span data-slot="hsts-header-missing" className="text-sm font-semibold">{t('hsts.current.noHeader')}</span>}
            </div>
            <div className="grid min-w-0 grid-cols-1 gap-2 md:grid-cols-2">
              <Directive id="max-age" name="max-age"
                verdict={t(`hsts.v.maxAge.${v}`)} tone={MAX_AGE_TONE[v] || 'muted'}
                value={policy.maxAge != null && policy.maxAge > 0 ? t('hsts.dir.maxAge.value', days, policy.maxAge) : null}
                desc={t('hsts.dir.maxAge.desc')} />
              <Directive id="includeSubDomains" name="includeSubDomains"
                verdict={policy.includeSubDomains ? t('hsts.v.present') : t('hsts.v.absent')}
                tone={policy.includeSubDomains ? 'good' : 'muted'}
                desc={t('hsts.dir.includeSub.desc')} />
              <Directive id="preload" name="preload"
                verdict={policy.preload ? (preloadOk ? t('hsts.v.present') : t('hsts.v.ineligible')) : t('hsts.v.absent')}
                tone={policy.preload ? (preloadOk ? 'good' : 'warn') : 'muted'}
                desc={t('hsts.dir.preload.desc')} />
              <Directive id="redirect" name={t('hsts.dir.redirect')}
                verdict={policy.redirect == null ? t('hsts.v.unknown') : (policy.redirect ? t('hsts.v.present') : t('hsts.v.absent'))}
                tone={policy.redirect == null ? 'muted' : (policy.redirect ? 'good' : 'warn')}
                desc={t('hsts.dir.redirect.desc')} />
            </div>
            {advice.length > 0 && (
              <div data-slot="hsts-advice" className="flex min-w-0 flex-col gap-1.5">
                <span className="text-xs font-semibold text-muted-foreground">{t('hsts.advice.title')}</span>
                <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
                  {advice.map((a) => {
                    const Icon = ADVICE_ICON[a.tone] || Info
                    return (
                      <li key={a.key} data-slot="hsts-advice-item" data-advice={a.key.replace('hsts.adv.', '')} data-tone={a.tone}
                        className={cn('flex min-w-0 items-start gap-2 rounded-md border px-3 py-2 text-sm leading-snug', TONE[a.tone === 'info' ? 'muted' : a.tone] || TONE.muted)}>
                        <Icon aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
                        <span className="min-w-0 [overflow-wrap:anywhere]">{t(a.key, ...(a.args || []), reqText(a.reqs))}</span>
                      </li>
                    )
                  })}
                </ul>
              </div>
            )}
          </>
        )}
      </section>

      <Accordion type="multiple" data-slot="hsts-faq" className="min-w-0 rounded-md border">
        {['what', 'why', 'missing', 'how', 'params'].map((id) => (
          <AccordionItem key={id} value={id} data-slot="hsts-faq-item" data-faq={id} className="min-w-0 px-3">
            <AccordionTrigger className="min-h-11 py-2.5 text-sm hover:no-underline">{t(`hsts.faq.${id}.q`)}</AccordionTrigger>
            <AccordionContent className="flex min-w-0 flex-col gap-2 text-sm leading-relaxed text-muted-foreground">
              <p className="m-0">{t(`hsts.faq.${id}.a`)}</p>
              {id === 'how' && (
                <div className="flex min-w-0 flex-col gap-2">
                  <span className="text-xs">{t('hsts.faq.how.example')}</span>
                  <code className="block font-mono text-[12.5px] [overflow-wrap:anywhere] text-foreground">Strict-Transport-Security: {EXAMPLE}</code>
                  {SNIPPETS.map((s) => (
                    <div key={s.id} data-slot="hsts-snippet" data-server={s.id} className="flex min-w-0 flex-col gap-1">
                      <span className="flex items-center justify-between gap-2 text-xs font-semibold text-foreground">
                        {t(`hsts.faq.how.${s.id}`)}
                        <CopyButton value={s.code} label={t('hsts.copySnippet', t(`hsts.faq.how.${s.id}`))} copiedLabel={t('hsts.copied')} />
                      </span>
                      <pre className="m-0 max-w-full overflow-x-auto rounded-md border bg-muted/50 p-2 font-mono text-[12px] text-foreground">{s.code}</pre>
                    </div>
                  ))}
                  <p className="m-0 text-xs">{t('hsts.faq.how.lb')}</p>
                </div>
              )}
            </AccordionContent>
          </AccordionItem>
        ))}
      </Accordion>
    </div>
  )
}
