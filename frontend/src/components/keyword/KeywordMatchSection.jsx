import { useId } from 'react'
import { CaseSensitive, Code2, Eye } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { RadioGroup, RadioGroupItem } from '@/components/shadcn/radio-group'
import { FieldContent, FieldDescription, FieldLabel, FieldTitle, Field as ShField } from '@/components/shadcn/field'
import { Switch } from '@/components/shadcn/switch'
import AlertBanner from '../ui/AlertBanner.jsx'
import { FormHint } from '../monitoring/MonitorForm.jsx'
import { KeywordFormCollapsible } from './KeywordRequestSection.jsx'
import { matchActiveCount } from './keywordFormModel.js'

const SCOPES = Object.freeze([
  { value: 'RAW', icon: Code2, title: 'keyword.match.raw', hint: 'keyword.match.rawHint' },
  { value: 'VISIBLE', icon: Eye, title: 'keyword.match.visible', hint: 'keyword.match.visibleHint' },
])

/**
 * Keyword izleme formunun "Eşleşme ayarları" bölümü (kw sözleşmesi §4.3, §14.1) — VARSAYILAN KAPALI. Kelimenin nerede
 * aranacağı (Ham HTML = bugünkü davranış · Görünür metin — betik / stil sayılmaz, etiketlerle bölünmüş ifade bulunur,
 * JavaScript çalıştırılmaz) seçim kartlarıyla; boşluk + HTML karakter kodu eşitleme anahtarı yalnız Ham HTML'de anlamlıdır
 * (görünür metinde her zaman uygulanır — anahtar açık ve kilitli görünür, formdaki değer DEĞİŞMEZ). Türkçe harf katlama
 * ve karakter kümesi notu herkese açık bilgidir (ayar değil, kw kararı 2).
 *
 * <p>Test kancaları: kök `data-slot="kw-match"`, `kw-match-scope` (+ kart `data-scope`), `kw-normalize` (+ `data-locked`),
 * `kw-turkish-note`.
 */
export default function KeywordMatchSection({ form, setForm, open, onOpenChange }) {
  const t = useT()
  const uid = useId()
  const labelId = `${uid}-scope`
  const normId = `${uid}-norm`
  const visible = form.matchScope === 'VISIBLE'
  const count = matchActiveCount(form)
  return (
    <KeywordFormCollapsible slot="kw-match" open={open} onOpenChange={onOpenChange}
      title={t('keyword.match.title')} count={count} countLabel={t('keyword.match.activeCount', count)}>
      <FormHint full={false}>{t('keyword.match.intro')}</FormHint>

      <div data-field="matchScope" className="flex min-w-0 flex-col gap-1.5">
        <span id={labelId} className="text-sm font-semibold">{t('keyword.match.scopeLabel')}</span>
        <RadioGroup value={visible ? 'VISIBLE' : 'RAW'} onValueChange={(v) => setForm((f) => ({ ...f, matchScope: v }))}
          aria-labelledby={labelId} data-slot="kw-match-scope" className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          {SCOPES.map((o) => {
            const Icon = o.icon
            const rid = `${uid}-${o.value}`
            return (
              <FieldLabel key={o.value} htmlFor={rid} className="w-full cursor-pointer">
                <ShField orientation="horizontal" data-scope={o.value} className="min-h-10 items-start gap-2 px-3! py-2!">
                  <RadioGroupItem value={o.value} id={rid} className="mt-0.5" />
                  <FieldContent className="gap-0.5">
                    <FieldTitle className="flex items-center gap-1.5 text-sm">
                      <Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />{t(o.title)}
                    </FieldTitle>
                    <FieldDescription className="m-0 text-xs leading-snug">{t(o.hint)}</FieldDescription>
                  </FieldContent>
                </ShField>
              </FieldLabel>
            )
          })}
        </RadioGroup>
      </div>

      {/* Boşluk + karakter kodu eşitleme: kartın tamamı dokunma hedefi (NocNotifyField deseni) */}
      <FieldLabel htmlFor={normId} data-slot="kw-normalize" data-locked={visible ? 'true' : undefined}
        className="w-full cursor-pointer has-[:disabled]:cursor-default">
        <ShField orientation="horizontal" role={undefined} data-disabled={visible ? 'true' : undefined} className="min-h-11 items-center gap-3">
          <FieldContent className="gap-1">
            <FieldTitle id={`${normId}-t`} className="font-semibold">{t('keyword.match.normalize')}</FieldTitle>
            <FieldDescription id={`${normId}-d`} className="text-xs leading-snug">
              {visible ? t('keyword.match.normalizeVisible') : t('keyword.match.normalizeHint')}
            </FieldDescription>
          </FieldContent>
          <Switch id={normId} checked={visible || !!form.normalizeWs} disabled={visible}
            aria-labelledby={`${normId}-t`} aria-describedby={`${normId}-d`}
            onCheckedChange={(v) => setForm((f) => ({ ...f, normalizeWs: v === true }))} />
        </ShField>
      </FieldLabel>

      <div data-slot="kw-turkish-note" className="min-w-0">
        <AlertBanner tone="info" icon={CaseSensitive} role="note" className="mb-0">{t('keyword.match.turkishNote')}</AlertBanner>
      </div>
    </KeywordFormCollapsible>
  )
}
