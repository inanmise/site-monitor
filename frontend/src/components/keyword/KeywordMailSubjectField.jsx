import { useMemo, useRef } from 'react'
import { Mail } from 'lucide-react'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { FormField, FormGrid, FormHint, FormSection } from '../monitoring/MonitorForm.jsx'
import { normalizeUrl } from '../../utils/normalizeUrl.js'
import { KW_LIMITS, SUBJECT_PLACEHOLDERS, placeholderText, renderSubject, unknownPlaceholders } from './keywordFormModel.js'

/** Önizlemenin zaman değeri — İstanbul saatiyle "gg.aa.yyyy ss:dd" (sunucunun `{zaman}` biçimine yakın). */
function istanbulNow(now = new Date()) {
  try {
    return new Intl.DateTimeFormat('tr-TR', {
      timeZone: 'Europe/Istanbul', day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit',
    }).format(now)
  } catch { return '' }
}

/**
 * Keyword alarm e-postasının ÖZEL konusu (E3, sözleşme §8.3) — isteğe bağlı; boş = standart konu. Yer tutucu çipleri arayüz
 * dilinde (`{ad}` / `{name}` — ikisi de aynı değeri verir) imlecin olduğu yere eklenir; canlı önizleme formdaki ad / adres /
 * kelimeyle ve örnek değerlerle sunucunun kuralını izler (tek satır, ≤ 200, bilinmeyen yer tutucu olduğu gibi kalır);
 * bilinmeyen yer tutucu uyarısı çizilir. Paylaşımla (yalnız erişim paylaşımı) düzenleyen bu alanı DEĞİŞTİREMEZ (sunucu eşit
 * değeri düşürür, farklıyı 403 ile reddeder) → `locked` alan kapalı + not.
 *
 * <p>Test kancaları: `data-slot="kw-subject"`, `kw-subject-placeholders`, `kw-subject-preview`, `kw-subject-unknown`,
 * `kw-subject-locked`; alan `data-field="mailSubjectTemplate"`.
 */
export default function KeywordMailSubjectField({ form, setForm, fe, locked = false }) {
  const t = useT()
  const { lang } = useLanguage()
  const inputRef = useRef(null)
  const template = form.mailSubjectTemplate || ''
  const values = useMemo(() => ({
    name: (form.name || '').trim() || normalizeUrl(form.url) || t('keyword.subject.sampleName'),
    url: normalizeUrl(form.url) || 'https://www.example.com/',
    keyword: (form.keyword || '').trim() || 'SUCCESS',
    count: '0',
    reason: t('keyword.subject.sampleReason'),
    level: t(`sim.level.${['HIGH', 'CRITICAL'].includes(form.alertLevel) ? form.alertLevel : 'WARNING'}`),
    status: '200',
    time: istanbulNow(),
  }), [form.name, form.url, form.keyword, form.alertLevel, t])
  const preview = template.trim() ? renderSubject(template, values) : ''
  const unknown = unknownPlaceholders(template)

  const set = (v) => { setForm((f) => ({ ...f, mailSubjectTemplate: v })); fe.clear('mailSubjectTemplate') }
  /** Yer tutucuyu imlecin olduğu yere ekler (imleç yoksa sona, gerekirse bir boşlukla); ardından imleç eklenenin sonuna. */
  const insert = (ph) => {
    const el = inputRef.current
    const cur = form.mailSubjectTemplate || ''
    const start = el && typeof el.selectionStart === 'number' ? el.selectionStart : cur.length
    const end = el && typeof el.selectionEnd === 'number' ? el.selectionEnd : start
    const before = cur.slice(0, start)
    const glue = before && !/\s$/.test(before) && start === cur.length ? ' ' : ''
    const next = `${before}${glue}${ph}${cur.slice(end)}`
    set(next)
    const caret = before.length + glue.length + ph.length
    const restore = () => { try { el?.focus(); el?.setSelectionRange(caret, caret) } catch { /* odaklanamaz */ } }
    if (typeof requestAnimationFrame === 'function') requestAnimationFrame(restore); else restore()
  }

  return (
    <div data-slot="kw-subject" className="min-w-0 sm:col-span-2">
      <FormSection title={t('keyword.subject.title')} icon={Mail}>
        {locked && <FormHint full={false} tone="warn"><span data-slot="kw-subject-locked">{t('keyword.subject.locked')}</span></FormHint>}
        <FormGrid>
          <FormField full label={t('keyword.subject.label')} hint={t('keyword.subject.hint')} {...fe.fieldProps('mailSubjectTemplate')} className="mb-0">
            {({ id, describedBy, invalid }) => (
              <Input ref={inputRef} id={id} aria-describedby={describedBy} aria-invalid={invalid} autoComplete="off"
                maxLength={KW_LIMITS.maxSubject} value={template} disabled={locked}
                placeholder={t('keyword.subject.ph')} onChange={(e) => set(e.target.value)} />
            )}
          </FormField>
          <div data-slot="kw-subject-placeholders" className="flex min-w-0 flex-wrap items-center gap-1.5 sm:col-span-2">
            <span className="text-xs text-muted-foreground">{t('keyword.subject.placeholders')}</span>
            {SUBJECT_PLACEHOLDERS.map((p) => {
              const ph = placeholderText(p, lang)
              const desc = t(`keyword.subject.ph.${p.key}`)
              return (
                <Button key={p.key} type="button" variant="outline" size="sm" disabled={locked}
                  className="h-7 px-2 font-mono text-[11px] pointer-coarse:h-10"
                  aria-label={t('keyword.subject.insert', ph, desc)} title={desc} onClick={() => insert(ph)}>
                  {ph}
                </Button>
              )
            })}
          </div>
          {template.trim() && (
            <div data-slot="kw-subject-preview" className="min-w-0 rounded-md border bg-background px-3 py-2 text-sm sm:col-span-2">
              <span className="text-xs text-muted-foreground">{t('keyword.subject.preview')}</span>
              {preview
                ? <p className="m-0 font-medium [overflow-wrap:anywhere]" data-slot="kw-subject-preview-text">{preview}</p>
                : <p className="m-0 text-muted-foreground">{t('keyword.subject.previewEmpty')}</p>}
            </div>
          )}
          {unknown.length > 0 && (
            <FormHint tone="warn">
              <span data-slot="kw-subject-unknown" className="[overflow-wrap:anywhere]">{t('keyword.subject.unknown', unknown.join(', '))}</span>
            </FormHint>
          )}
        </FormGrid>
      </FormSection>
    </div>
  )
}
