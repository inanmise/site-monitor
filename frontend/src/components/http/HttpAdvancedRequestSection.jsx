import { ChevronDown, Gauge, Braces, KeyRound, FileJson } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { cn } from '@/lib/utils'
import { CheckField, FormField, FormGrid, FormHint, FormSection } from '../monitoring/MonitorForm.jsx'
import { ADV_LIMITS, advancedActiveCount } from './httpAdvancedModel.js'

/**
 * HTTP izleme formunun "Gelişmiş istek" bölümü (2026-10-01, onaylı öneri 9) — VARSAYILAN KAPALI (shadcn Collapsible;
 * kapalıyken içerik DOM'da yok). Alanlar: özel başlıklar (yalnız global admin, write-only), HTTP Basic auth
 * (parola write-only), POST gövdesi + içerik türü (yalnız POST seçiliyken), JSON doğrulaması, yavaş yanıt alarmı.
 * Hepsi isteğe bağlıdır; boş bırakılan alan isteği değiştirmez (yük de değişmez — bkz. httpAdvancedModel).
 *
 * <p>Doğrulama hataları alanın altında (`useFormErrors` — anahtarlar `adv*`, `data-field` ile ilk hatalıya kaydırma);
 * hata varken sayfa bölümü açar. Mobil: tek sütun, sabit genişlik yok, tetik 40 px.
 *
 * <p>Test kancaları: kök `data-slot="http-advanced"`, tetik düğmesi (erişilebilir adı bölüm başlığı), sayaç
 * `data-slot="http-advanced-count"`.
 *
 * @param {object}  form          sayfanın form durumu (gelişmiş alanlar: httpAdvancedModel.ADV_EMPTY anahtarları)
 * @param {Function} setForm      sayfanın setForm'u
 * @param {object}  fe            useFormErrors(...)
 * @param {string}  method        formdaki HTTP yöntemi (gövde yalnız POST'ta)
 * @param {boolean} canEditHeaders global admin mi (başlıklar yalnız onda düzenlenir)
 * @param {object}  stored        düzenlenen kaydın write-only durumu: { hasHeaders, headerNames, hasPass }
 */
export default function HttpAdvancedRequestSection({ form, setForm, fe, method, canEditHeaders, stored = {}, open, onOpenChange }) {
  const t = useT()
  const set = (patch, clearKey) => {
    setForm((f) => ({ ...f, ...patch }))
    if (clearKey) fe.clear(clearKey)
  }
  const count = advancedActiveCount(form, stored)
  const isPost = method === 'POST'
  const headerNames = (stored.headerNames || []).filter(Boolean).join(', ')
  return (
    <Collapsible open={open} onOpenChange={onOpenChange} data-slot="http-advanced"
      className="min-w-0 rounded-lg border bg-muted/30 sm:col-span-2">
      <CollapsibleTrigger asChild>
        <Button type="button" variant="ghost"
          className="h-auto min-h-10 w-full justify-start gap-2 rounded-lg px-3.5 py-3 text-left font-semibold whitespace-normal hover:bg-muted/50">
          <ChevronDown size={16} aria-hidden="true"
            className={cn('shrink-0 text-muted-foreground transition-transform motion-reduce:transition-none', open && 'rotate-180')} />
          <span className="min-w-0">{t('http.adv.title')}</span>
          {count > 0 && (
            <Badge variant="secondary" data-slot="http-advanced-count" className="ml-auto shrink-0">
              {t('http.adv.activeCount', count)}
            </Badge>
          )}
        </Button>
      </CollapsibleTrigger>
      <CollapsibleContent className="flex min-w-0 flex-col gap-3 px-3.5 pb-3.5">
        <FormHint full={false}>{t('http.adv.intro')}</FormHint>

        {/* Kimlik: özel başlıklar + Basic auth */}
        <FormSection boxed={false} title={t('http.adv.detailAuth')} icon={KeyRound}>
          <FormGrid>
            <FormField full label={t('http.adv.headers')} {...fe.fieldProps('advHeaders')}
              hint={!canEditHeaders ? t('http.adv.headersAdminOnly')
                : stored.hasHeaders ? t('http.adv.headersSavedHint', headerNames || t('mon.customHeadersSavedUnnamed'))
                  : t('http.adv.headersHint')}>
              {({ id, describedBy, invalid }) => (
                <Textarea id={id} aria-describedby={describedBy} aria-invalid={invalid} rows={3} spellCheck={false}
                  autoComplete="off" disabled={!canEditHeaders} value={form.customHeaders}
                  placeholder={t('http.adv.headersPh')} className="min-w-0 font-mono text-xs"
                  onChange={(e) => set({ customHeaders: e.target.value }, 'advHeaders')} />
              )}
            </FormField>
            {canEditHeaders && stored.hasHeaders && (
              <CheckField full checked={form.clearHeaders} label={t('http.adv.clearHeaders')}
                disabled={!!String(form.customHeaders || '').trim()}
                onCheckedChange={(v) => set({ clearHeaders: v })} />
            )}
            <FormField label={t('http.adv.basicUser')} {...fe.fieldProps('advBasicUser')}>
              {({ id, describedBy, invalid }) => (
                <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} autoComplete="off"
                  value={form.basicAuthUser} onChange={(e) => set({ basicAuthUser: e.target.value }, 'advBasicUser')} />
              )}
            </FormField>
            <FormField label={t('http.adv.basicPass')}>
              {({ id, describedBy }) => (
                <Input id={id} aria-describedby={describedBy} type="password" autoComplete="new-password"
                  value={form.basicAuthPass} placeholder={stored.hasPass ? t('http.adv.passStoredPh') : ''}
                  onChange={(e) => set({ basicAuthPass: e.target.value })} />
              )}
            </FormField>
            <FormHint>{stored.hasPass ? t('http.adv.basicSavedHint') : t('http.adv.basicHint')}</FormHint>
          </FormGrid>
        </FormSection>

        {/* İstek gövdesi — yalnız POST */}
        <FormSection boxed={false} title={t('http.adv.bodyTitle')} icon={Braces}>
          {isPost ? (
            <FormGrid>
              <FormField full label={t('http.adv.body')} hint={t('http.adv.bodyHint')} {...fe.fieldProps('advBody')}>
                {({ id, describedBy, invalid }) => (
                  <Textarea id={id} aria-describedby={describedBy} aria-invalid={invalid} rows={4} spellCheck={false}
                    value={form.requestBody} placeholder='{"ping": true}' className="min-w-0 font-mono text-xs"
                    onChange={(e) => set({ requestBody: e.target.value }, 'advBody')} />
                )}
              </FormField>
              <FormField label={t('http.adv.contentType')} hint={t('http.adv.contentTypeHint')} {...fe.fieldProps('advContentType')}>
                {({ id, describedBy, invalid }) => (
                  <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} autoComplete="off"
                    maxLength={ADV_LIMITS.maxContentType} value={form.requestContentType} placeholder="application/json"
                    onChange={(e) => set({ requestContentType: e.target.value }, 'advContentType')} />
                )}
              </FormField>
            </FormGrid>
          ) : (
            <FormHint full={false}>{t('http.adv.bodyPostOnly')}</FormHint>
          )}
        </FormSection>

        {/* JSON doğrulaması */}
        <FormSection boxed={false} title={t('http.adv.jsonTitle')} icon={FileJson}>
          <FormGrid>
            <FormField label={t('http.adv.jsonPath')} hint={t('http.adv.jsonPathHint')} {...fe.fieldProps('advJsonPath')}>
              {({ id, describedBy, invalid }) => (
                <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} autoComplete="off" spellCheck={false}
                  maxLength={ADV_LIMITS.maxJsonPath} value={form.jsonPath} placeholder="$.status" className="font-mono"
                  onChange={(e) => set({ jsonPath: e.target.value }, 'advJsonPath')} />
              )}
            </FormField>
            <FormField label={t('http.adv.jsonExpected')} hint={t('http.adv.jsonExpectedHint')}>
              {({ id, describedBy }) => (
                <Input id={id} aria-describedby={describedBy} autoComplete="off" spellCheck={false}
                  maxLength={ADV_LIMITS.maxJsonExpected} value={form.jsonExpected} placeholder="ok"
                  onChange={(e) => set({ jsonExpected: e.target.value })} />
              )}
            </FormField>
          </FormGrid>
        </FormSection>

        {/* Yavaş yanıt alarmı (opt-in; HTTP_SLOW) */}
        <FormSection boxed={false} title={t('http.adv.slowTitle')} icon={Gauge}>
          <CheckField checked={form.slowResponseEnabled} label={t('http.adv.slowEnable')}
            onCheckedChange={(v) => set({ slowResponseEnabled: v }, v ? null : 'advSlowThreshold')} />
          {form.slowResponseEnabled && (
            <FormField label={t('http.adv.slowThreshold')} {...fe.fieldProps('advSlowThreshold')} className="sm:max-w-xs">
              {({ id, describedBy, invalid }) => (
                <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} type="number" inputMode="numeric"
                  min={ADV_LIMITS.minSlowMs} max={ADV_LIMITS.maxSlowMs} step="100" value={form.slowThresholdMs}
                  onChange={(e) => set({ slowThresholdMs: e.target.value === '' ? '' : Number(e.target.value) }, 'advSlowThreshold')} />
              )}
            </FormField>
          )}
          <FormHint full={false}>{t('http.adv.slowHint')}</FormHint>
        </FormSection>
      </CollapsibleContent>
    </Collapsible>
  )
}
