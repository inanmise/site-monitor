import { useId } from 'react'
import { ChevronDown, Fingerprint, KeyRound, ListChecks, Send, ShieldAlert } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Input } from '@/components/shadcn/input'
import { RadioGroup, RadioGroupItem } from '@/components/shadcn/radio-group'
import { FieldContent, FieldDescription, FieldLabel, FieldTitle, Field as ShField } from '@/components/shadcn/field'
import { cn } from '@/lib/utils'
import AlertBanner from '../ui/AlertBanner.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import { FieldError } from '../ui/Field.jsx'
import { CheckField, FormField, FormHint, FormSection } from '../monitoring/MonitorForm.jsx'
import { AdvancedAuthFields, AdvancedBodyFields } from '../http/HttpAdvancedRequestSection.jsx'
import { DEFAULT_USER_AGENT, KW_LIMITS, STATUS_PRESETS, requestActiveCount, statusModeOf } from './keywordFormModel.js'

/**
 * Keyword izleme formunun açılır bölüm kabuğu (kw 2026-11-05) — "Eşleşme ayarları" ve "Gelişmiş istek" aynı görünümü
 * paylaşır: tam genişlik tetik (40 px, metin sarar), etkin ayar sayısı rozeti, kapalıyken içerik DOM'da yok.
 * Tetiğin erişilebilir adı bölüm başlığıdır (+ rozet metni).
 */
export function KeywordFormCollapsible({ slot, open, onOpenChange, title, count = 0, countLabel, children }) {
  return (
    <Collapsible open={open} onOpenChange={onOpenChange} data-slot={slot}
      className="min-w-0 rounded-lg border bg-muted/30 sm:col-span-2">
      <CollapsibleTrigger asChild>
        <Button type="button" variant="ghost"
          className="h-auto min-h-10 w-full justify-start gap-2 rounded-lg px-3.5 py-3 text-left font-semibold whitespace-normal hover:bg-muted/50">
          <ChevronDown size={16} aria-hidden="true"
            className={cn('shrink-0 text-muted-foreground transition-transform motion-reduce:transition-none', open && 'rotate-180')} />
          <span className="min-w-0">{title}</span>
          {count > 0 && (
            <Badge variant="secondary" data-slot={`${slot}-count`} className="ml-auto shrink-0">{countLabel}</Badge>
          )}
        </Button>
      </CollapsibleTrigger>
      <CollapsibleContent className="flex min-w-0 flex-col gap-3 px-3.5 pb-3.5">{children}</CollapsibleContent>
    </Collapsible>
  )
}

/** Paylaşılan HTTP parçalarının bu formdaki alan hata anahtarları = sunucunun istek anahtarları (sözleşme §10). */
const AUTH_KEYS = Object.freeze({ headers: 'customHeaders', basicUser: 'basicAuthUser', basicPass: 'basicAuthPass' })
const BODY_KEYS = Object.freeze({ body: 'requestBody', contentType: 'requestContentType' })
/** Başlık metinleri keyword'ün kendi anahtarları (önbellek kırma vurgusu); Basic auth metni "Test et"in kayıtlı parolayı kullandığını söyler. */
const AUTH_TEXTS = Object.freeze({
  headersLabel: 'keyword.customHeaders', headersHint: 'keyword.customHeadersHint', headersSavedHint: 'keyword.customHeadersSavedHint',
  headersAdminOnly: 'keyword.customHeadersHint', headersPh: 'keyword.customHeadersPh',
  basicHint: 'keyword.req.basicHint', basicSavedHint: 'keyword.req.basicSavedHint',
})
/** Segment öğeleri telefonda tam genişlik ve metin sarar (dar ekranda yatay taşma olmasın). */
const SEGMENT_ITEM = 'flex-1 min-w-0 whitespace-normal leading-tight sm:flex-none'

/**
 * Keyword izleme formunun "Gelişmiş istek" bölümü (kw sözleşmesi §5, §14.1) — VARSAYILAN KAPALI. Alanlar: yöntem (GET / POST)
 * + gövde ve içerik türü (yalnız POST), özel başlıklar (yalnız global admin, kaldırılabilir) + HTTP Basic auth (parola
 * write-only, kayıtlıyı kaldır), User-Agent kipi (SiteMonitor / tarayıcı benzeri — kurum politikası uyarısıyla / özel),
 * kabul edilen durum kodları (kapalı = bugünkü davranış; ön ayarlar yalnız ÖNERİR), son adres kuralı, yönlendirme takibi.
 * Gövde ve kimlik parçaları HTTP formunun bölümünden YENİDEN kullanılır (kopya yok); JSON doğrulaması ve yavaşlık burada yok.
 *
 * <p>Hata anahtarları istek anahtarlarıdır (`requestBody`, `expectedStatus` …); hata varken sayfa bölümü açar.
 * Test kancaları: kök `data-slot="kw-request"`, `kw-req-method`, `kw-pass-stored`, `kw-ua-mode`, `kw-ua-browser-warn`,
 * `kw-status` / `kw-status-presets` (+ `data-mode`), `kw-follow-redirects`; alanlar `data-field` ile (`expectedFinalUrl` …).
 *
 * @param {object}   form, setForm, fe   sayfanın form durumu / setter'ı / useFormErrors
 * @param {boolean}  globalAdmin         özel başlıklar yalnız onda düzenlenir
 * @param {object}   stored              düzenlenen kaydın write-only durumu `{ hasPass, hasHeaders, headerNames }`
 */
export default function KeywordRequestSection({ form, setForm, fe, open, onOpenChange, globalAdmin = false, stored = {} }) {
  const t = useT()
  const set = (patch, clearKey) => {
    setForm((f) => ({ ...f, ...patch }))
    if (clearKey) fe.clear(clearKey)
  }
  const count = requestActiveCount(form, stored)
  const isPost = form.method === 'POST'
  return (
    <KeywordFormCollapsible slot="kw-request" open={open} onOpenChange={onOpenChange}
      title={t('keyword.req.title')} count={count} countLabel={t('keyword.req.activeCount', count)}>
      <FormHint full={false}>{t('keyword.req.intro')}</FormHint>

      {/* İstek: yöntem + (POST'ta) gövde */}
      <FormSection boxed={false} title={t('keyword.req.requestTitle')} icon={Send}>
        <div data-slot="kw-req-method" data-field="method" className="flex min-w-0 flex-col gap-1.5">
          <SegmentedControl value={form.method === 'POST' ? 'POST' : 'GET'} ariaLabel={t('keyword.req.method')}
            className="w-full sm:w-auto" itemClassName={SEGMENT_ITEM}
            options={[{ value: 'GET', label: 'GET' }, { value: 'POST', label: 'POST' }]}
            onChange={(v) => set({ method: v }, 'method')} />
          <FormHint full={false}>{t('keyword.req.methodHint')}</FormHint>
        </div>
        <AdvancedBodyFields form={form} set={set} fe={fe} isPost={isPost} keys={BODY_KEYS} />
      </FormSection>

      {/* Kimlik: özel başlıklar + Basic auth */}
      <FormSection boxed={false} title={t('http.adv.detailAuth')} icon={KeyRound}>
        <AdvancedAuthFields form={form} set={set} fe={fe} canEditHeaders={globalAdmin} stored={stored}
          keys={AUTH_KEYS} texts={AUTH_TEXTS} passDisabled={!!form.clearBasicAuthPass}
          afterPass={stored.hasPass ? (
            <div data-slot="kw-pass-stored" className="flex min-w-0 flex-wrap items-start gap-x-3 gap-y-2 sm:col-span-2">
              <Badge variant="secondary" className="mt-0.5 overflow-visible rounded-md whitespace-normal">
                <KeyRound aria-hidden="true" className="size-3" />{t('keyword.req.passSaved')}
              </Badge>
              <CheckField checked={form.clearBasicAuthPass} label={t('keyword.req.clearPass')} hint={t('keyword.req.clearPassHint')}
                className="min-w-0 flex-1"
                onCheckedChange={(v) => set(v ? { clearBasicAuthPass: true, basicAuthPass: '' } : { clearBasicAuthPass: false }, 'basicAuthPass')} />
            </div>
          ) : null} />
      </FormSection>

      <UserAgentPart form={form} set={set} fe={fe} />
      <ResponseRulesPart form={form} set={set} fe={fe} />
    </KeywordFormCollapsible>
  )
}

/** Tarayıcı kimliği (User-Agent): SiteMonitor (varsayılan) · tarayıcı benzeri (politika uyarısı) · özel (değer zorunlu). */
function UserAgentPart({ form, set, fe }) {
  const t = useT()
  const mode = ['BROWSER', 'CUSTOM'].includes(form.userAgentMode) ? form.userAgentMode : 'DEFAULT'
  return (
    <FormSection boxed={false} title={t('keyword.req.uaTitle')} icon={Fingerprint}>
      <div data-slot="kw-ua-mode" data-mode={mode} className="flex min-w-0 flex-col gap-1.5">
        <SegmentedControl value={mode} ariaLabel={t('keyword.req.uaMode')} className="w-full sm:w-auto" itemClassName={SEGMENT_ITEM}
          options={[{ value: 'DEFAULT', label: t('keyword.req.uaDefault') }, { value: 'BROWSER', label: t('keyword.req.uaBrowser') },
            { value: 'CUSTOM', label: t('keyword.req.uaCustom') }]}
          onChange={(v) => set({ userAgentMode: v }, 'userAgent')} />
        {mode === 'DEFAULT' && <FormHint full={false}>{t('keyword.req.uaDefaultHint', DEFAULT_USER_AGENT)}</FormHint>}
        {mode === 'BROWSER' && <FormHint full={false}>{t('keyword.req.uaBrowserHint')}</FormHint>}
      </div>
      {mode === 'BROWSER' && (
        <div data-slot="kw-ua-browser-warn" className="min-w-0">
          <AlertBanner tone="warning" icon={ShieldAlert} className="mb-0" title={t('keyword.req.uaBrowserWarnTitle')}>
            {t('keyword.req.uaBrowserWarn')}
          </AlertBanner>
        </div>
      )}
      {mode === 'CUSTOM' && (
        <FormField label={t('keyword.req.uaCustomLabel')} hint={t('keyword.req.uaCustomHint')} {...fe.fieldProps('userAgent')} className="mb-0">
          {({ id, describedBy, invalid }) => (
            <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} autoComplete="off" spellCheck={false}
              maxLength={KW_LIMITS.maxUserAgent} value={form.userAgent || ''} placeholder={t('keyword.req.uaCustomPh')}
              className="font-mono" onChange={(e) => set({ userAgent: e.target.value }, 'userAgent')} />
          )}
        </FormField>
      )}
    </FormSection>
  )
}

const STATUS_OPTIONS = Object.freeze(['OFF', 'RECOMMENDED', 'ONLY_200', 'CUSTOM'])
const STATUS_TEXT = Object.freeze({
  OFF: ['keyword.req.statusOff', 'keyword.req.statusOffHint'],
  RECOMMENDED: ['keyword.req.statusRecommended', 'keyword.req.statusRecommendedHint'],
  ONLY_200: ['keyword.req.status200', 'keyword.req.status200Hint'],
  CUSTOM: ['keyword.req.statusCustom', 'keyword.req.statusCustomHint'],
})

/**
 * Yanıt kuralları: kabul edilen durum kodları (seçim kartları — kapalı varsayılan, 2xx–3xx "Önerilen" rozetiyle, yalnız 200,
 * özel), son adres kuralı, yönlendirme takibi. Kip değerden türetilir; "Özel" seçimi yazarken değer bir ön ayara denk gelse
 * bile (ör. "200") özel kutuda kalınır.
 */
function ResponseRulesPart({ form, set, fe }) {
  const t = useT()
  const uid = useId()
  const labelId = `${uid}-status`
  const errId = `${uid}-status-err`
  // Kip FORMDA (`expectedStatusCustom`): bölüm kapanınca bu parça sökülür — yerel durum seçimi unuturdu (DÜŞÜK-8)
  const mode = form.expectedStatusCustom ? 'CUSTOM' : statusModeOf(form.expectedStatus)
  const statusErr = fe.errors?.expectedStatus
  const pick = (v) => {
    if (v === 'CUSTOM') { set({ expectedStatusCustom: true }, 'expectedStatus'); return }
    set({ expectedStatusCustom: false, expectedStatus: STATUS_PRESETS[v] ?? '' }, 'expectedStatus')
  }
  return (
    <FormSection boxed={false} title={t('keyword.req.rulesTitle')} icon={ListChecks}>
      <div data-slot="kw-status" data-mode={mode} data-field={mode === 'CUSTOM' ? undefined : 'expectedStatus'}
        data-invalid={statusErr && mode !== 'CUSTOM' ? true : undefined} className="flex min-w-0 flex-col gap-2">
        <span id={labelId} className="text-sm font-semibold">{t('keyword.req.statusLabel')}</span>
        <RadioGroup value={mode} onValueChange={pick} aria-labelledby={labelId}
          aria-describedby={statusErr && mode !== 'CUSTOM' ? errId : undefined}
          data-slot="kw-status-presets" className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          {STATUS_OPTIONS.map((o) => {
            const rid = `${uid}-${o}`
            const [title, hint] = STATUS_TEXT[o]
            return (
              <FieldLabel key={o} htmlFor={rid} className="w-full cursor-pointer">
                <ShField orientation="horizontal" data-mode={o} className="min-h-10 items-start gap-2 px-3! py-2!">
                  <RadioGroupItem value={o} id={rid} className="mt-0.5" />
                  <FieldContent className="gap-0.5">
                    <FieldTitle className="flex flex-wrap items-center gap-1.5 text-sm">
                      {t(title)}
                      {o === 'RECOMMENDED' && (
                        <Badge variant="outline" data-slot="kw-status-recommended"
                          className="overflow-visible rounded-md whitespace-normal text-[11px]">{t('keyword.req.statusRecommendedBadge')}</Badge>
                      )}
                    </FieldTitle>
                    <FieldDescription className="m-0 text-xs leading-snug">{t(hint)}</FieldDescription>
                  </FieldContent>
                </ShField>
              </FieldLabel>
            )
          })}
        </RadioGroup>
        {mode === 'CUSTOM' ? (
          <FormField label={t('keyword.req.statusCustomLabel')} hint={t('keyword.req.statusCustomFormat')}
            {...fe.fieldProps('expectedStatus')} className="mb-0">
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} autoComplete="off" spellCheck={false}
                maxLength={KW_LIMITS.maxExpectedStatus} value={form.expectedStatus || ''} placeholder={t('keyword.req.statusCustomPh')}
                className="font-mono" onChange={(e) => set({ expectedStatus: e.target.value }, 'expectedStatus')} />
            )}
          </FormField>
        ) : statusErr ? <FieldError id={errId}>{statusErr}</FieldError> : null}
      </div>

      <FormField full label={t('keyword.req.finalUrlLabel')} hint={t('keyword.req.finalUrlHint')} {...fe.fieldProps('expectedFinalUrl')} className="mb-0">
        {({ id, describedBy, invalid }) => (
          <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} autoComplete="off" spellCheck={false} inputMode="url"
            maxLength={KW_LIMITS.maxFinalUrl} value={form.expectedFinalUrl || ''}
            placeholder={t('keyword.req.finalUrlPh')}
            onChange={(e) => set({ expectedFinalUrl: e.target.value }, 'expectedFinalUrl')} />
        )}
      </FormField>

      <div data-slot="kw-follow-redirects" className="min-w-0">
        <CheckField checked={form.followRedirects !== false} label={t('keyword.req.followRedirects')} hint={t('keyword.req.followRedirectsHint')}
          onCheckedChange={(v) => set({ followRedirects: v })} />
      </div>
    </FormSection>
  )
}
