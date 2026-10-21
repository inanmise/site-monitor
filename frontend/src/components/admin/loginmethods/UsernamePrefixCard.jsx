import { useCallback, useEffect, useRef, useState } from 'react'
import { IdCard, RefreshCw, ShieldAlert, Users } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import Field from '../../ui/Field.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Spinner } from '../../ui/Progress.jsx'
import { SettingsSection, ToggleRow, helpLabel } from '../SettingsControls.jsx'
import TokenChipsInput from './TokenChipsInput.jsx'
import {
  DEFAULT_LIMITS, FIELD_ALLOWED, FIELD_ENABLED, FIELD_EXEMPT, asList, exemptProblem, normalizeExempt, normalizePrefix,
  prefixProblem, previewKey,
} from './usernamePrefixModel.js'

/** Önizleme isteği kaç ms sessizlikten sonra atılsın (yazarken her tuşta sorgu yok). */
export const PREVIEW_DEBOUNCE_MS = 500

/**
 * Ayarlar → Güvenlik → Giriş Yöntemleri → "Kullanıcı adı ön eki" kartı (2026-10-21, kullanıcı isteği: "yalnız N ile başlayan
 * kullanıcıya login izni; MP'li ya da başka harfli olana izin vermeyelim; harfleri ayarlardan yöneteyim").
 *
 * Düzen: anahtar (kural açık / kapalı) → izinli ön ekler (çip) → muaf kullanıcı adları (çip; kurulumdaki yönetici HER ZAMAN
 * muaf) → CANLI önizleme (kaydetmeden: kural bu değerlerle açık olsaydı kaç aktif kullanıcı giremezdi + ilk 50 ad; kendi
 * hesabınız giremezse tehlike uyarısı). Önizleme yazmayı bekler (500 ms), geç yanıtlar düşer (sıra numarası), hata "okunamadı"
 * der — asla "0 kullanıcı" uydurmaz. Değerler Giriş Yöntemleri formunun içindedir (tek kaydet çubuğu); kayıtta yalnız değişen
 * ön ek alanları gider. Sayfa yalnız global yöneticiye açıktır (kabuk müdüre "yalnız global yönetici" notu çizer; sunucu 403).
 *
 * Test kancaları: `data-slot="lm-prefix"` (data-state on|off), `lm-prefix-allowed`, `lm-prefix-exempt`, `lm-prefix-preview`
 * (data-state loading|ready|error|idle), `lm-prefix-refused`, `lm-prefix-self`.
 */
export default function UsernamePrefixCard({ form, onField, fe, info, onPreview, onEntryErrors }) {
  const t = useT()
  const limits = { ...DEFAULT_LIMITS, ...(info?.limits || {}) }
  const enabled = !!form?.[FIELD_ENABLED]
  const allowed = asList(form?.[FIELD_ALLOWED])
  const exempt = asList(form?.[FIELD_EXEMPT])
  const [entryErr, setEntryErr] = useState({})
  const [preview, setPreview] = useState({ state: 'idle', data: null, key: null })
  const seq = useRef(0)

  const problemText = (p) => {
    if (!p) return null
    return p.key === 'lm.prefix.err.prefixFormat'
      ? t('lm.prefix.err.prefixFormat', ...p.params) : t('lm.prefix.err.exemptFormat', ...p.params)
  }

  const key = previewKey(allowed, exempt)
  const runPreview = useCallback(async (a, e, k) => {
    const my = ++seq.current
    setPreview((p) => ({ state: 'loading', data: p.key === k ? p.data : null, key: k }))
    try {
      const r = await api.loginMethodsAdmin.prefixPreview(a, e)
      if (my !== seq.current) return
      if (r?.success && r.data) setPreview({ state: 'ready', data: r.data, key: k })
      else setPreview({ state: 'error', data: null, key: k, message: r?.error })
    } catch (err) {
      if (my !== seq.current) return
      setPreview({ state: 'error', data: null, key: k, message: err?.message })
    }
  }, [])

  // Değerler değişince (ve en az bir ön ek varken) sessizce yeniden önizle — yazarken her tuşta değil
  useEffect(() => {
    if (allowed.length === 0) { seq.current++; setPreview({ state: 'idle', data: null, key: null }); return undefined }
    const id = setTimeout(() => runPreview(allowed, exempt, key), PREVIEW_DEBOUNCE_MS)
    return () => clearTimeout(id)
  }, [key]) // eslint-disable-line react-hooks/exhaustive-deps

  // Kutuda kalan GEÇERSİZ yazı kabuğa bildirilir: kayıt onu sessizce atlayıp "kaydedildi" demesin (kayıt durur, hata alanda)
  const onEntryErrorsRef = useRef(onEntryErrors)
  onEntryErrorsRef.current = onEntryErrors
  useEffect(() => { onEntryErrorsRef.current?.(entryErr) }, [entryErr])

  // Son önizleme kabuğa bildirilir (kayıt onayı etkilenecek kişi sayısını söylesin)
  const onPreviewRef = useRef(onPreview)
  onPreviewRef.current = onPreview
  useEffect(() => { onPreviewRef.current?.(preview) }, [preview])

  const setList = (field, list) => {
    onField(field, list)
    fe.clear(field)
  }

  const data = preview.data
  const refused = Number(data?.refused ?? 0)
  const sample = Array.isArray(data?.sample) ? data.sample : []
  const bootstrap = info?.bootstrap_admin

  return (
    <SettingsSection
      title={<span className="inline-flex items-center gap-2"><IdCard aria-hidden="true" className="size-4" />{t('lm.prefix.title')}</span>}
      description={t('lm.prefix.desc')} contentClassName="flex min-w-0 flex-col gap-3">
      <div data-slot="lm-prefix" data-state={enabled ? 'on' : 'off'} className="flex min-w-0 flex-col gap-3">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <ToggleRow checked={enabled} onChange={(v) => { onField(FIELD_ENABLED, v); fe.clear(FIELD_ALLOWED) }}
            label={t('lm.prefix.toggle')} helpKey="help.set.site.monitor.login.username-prefix.enabled" touch />
          <Badge variant={enabled ? 'secondary' : 'outline'} data-slot="lm-prefix-state">
            {enabled ? t('lm.status.on') : t('lm.status.off')}
          </Badge>
        </div>
        <p className="m-0 text-xs text-muted-foreground">{t('lm.prefix.toggleHint')}</p>

        <Field label={helpLabel(t('lm.prefix.allowed'), 'help.set.site.monitor.login.username-prefix.allowed')}
          name={FIELD_ALLOWED} className="mb-0" hint={t('lm.prefix.allowedHint', limits.prefix_max_len, limits.max_prefixes)}
          error={entryErr[FIELD_ALLOWED] || fe.errors[FIELD_ALLOWED] || undefined}>
          {({ id, describedBy, invalid }) => (
            <div data-slot="lm-prefix-allowed" className="min-w-0">
              <TokenChipsInput id={id} describedBy={describedBy} invalid={invalid} values={allowed} max={limits.max_prefixes}
                slot="lm-prefix-allowed-chips" placeholder={t('lm.prefix.allowedPh')}
                validate={(s) => prefixProblem(s, limits)} normalize={normalizePrefix}
                onEntryError={(p) => setEntryErr((m) => ({ ...m, [FIELD_ALLOWED]: problemText(p) }))}
                removeLabel={(v) => t('lm.prefix.removePrefix', v)}
                onChange={(list) => setList(FIELD_ALLOWED, list)} />
            </div>
          )}
        </Field>

        <Field label={helpLabel(t('lm.prefix.exempt'), 'help.set.site.monitor.login.username-prefix.exempt')}
          name={FIELD_EXEMPT} className="mb-0"
          hint={bootstrap ? t('lm.prefix.exemptHint', bootstrap, limits.max_exempt) : t('lm.prefix.exemptHintNoAdmin', limits.max_exempt)}
          error={entryErr[FIELD_EXEMPT] || fe.errors[FIELD_EXEMPT] || undefined}>
          {({ id, describedBy, invalid }) => (
            <div data-slot="lm-prefix-exempt" className="min-w-0">
              <TokenChipsInput id={id} describedBy={describedBy} invalid={invalid} values={exempt} max={limits.max_exempt}
                slot="lm-prefix-exempt-chips" placeholder={t('lm.prefix.exemptPh')}
                validate={(s) => exemptProblem(s, limits)} normalize={normalizeExempt}
                onEntryError={(p) => setEntryErr((m) => ({ ...m, [FIELD_EXEMPT]: problemText(p) }))}
                removeLabel={(v) => t('lm.prefix.removeExempt', v)}
                onChange={(list) => setList(FIELD_EXEMPT, list)} />
            </div>
          )}
        </Field>

        <PreviewPanel t={t} preview={preview} enabled={enabled} refused={refused} sample={sample} hasPrefixes={allowed.length > 0}
          onRetry={() => runPreview(allowed, exempt, key)} />
      </div>
    </SettingsSection>
  )
}

/** Önizleme bölümü — yükleniyor / hazır / hata / boş; bilinmeyen sonuç ASLA "0 kullanıcı" olarak çizilmez. */
function PreviewPanel({ t, preview, enabled, refused, sample, hasPrefixes, onRetry }) {
  const data = preview.data
  const state = !hasPrefixes ? 'idle' : preview.state
  return (
    <section data-slot="lm-prefix-preview" data-state={state} aria-labelledby="lm-prefix-preview-title"
      className="flex min-w-0 flex-col gap-2 rounded-lg border bg-muted/30 p-3 sm:p-4">
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
        <h5 id="lm-prefix-preview-title" className="m-0 inline-flex items-center gap-2 text-sm font-semibold">
          <Users aria-hidden="true" className="size-4" />{t('lm.prefix.preview.title')}
        </h5>
        {hasPrefixes && (
          <Button type="button" variant="outline" size="sm" className="min-h-10 sm:min-h-8 sm:pointer-coarse:min-h-10" onClick={onRetry}
            disabled={preview.state === 'loading'} data-slot="lm-prefix-refresh">
            <RefreshCw aria-hidden="true" />{t('lm.prefix.preview.refresh')}
          </Button>
        )}
      </div>

      <div role="status" aria-live="polite" className="flex min-w-0 flex-col gap-2">
        {state === 'idle' && <p className="m-0 text-xs text-muted-foreground">{t('lm.prefix.preview.idle')}</p>}
        {state === 'loading' && !data && (
          <p className="m-0 inline-flex items-center gap-2 text-xs text-muted-foreground">
            <Spinner size={14} inline decorative />{t('lm.prefix.preview.loading')}
          </p>
        )}
        {state === 'error' && (
          <div data-slot="lm-prefix-preview-error">
            <AlertBanner tone="danger" className="mb-0"
              actions={<Button type="button" variant="outline" size="sm" className="min-h-10 sm:min-h-8 sm:pointer-coarse:min-h-10" onClick={onRetry}>{t('lm.retry')}</Button>}>
              {t('lm.prefix.preview.errorText')}
            </AlertBanner>
          </div>
        )}
        {data && state !== 'idle' && state !== 'error' && (
          <>
            {data.self_refused && (
              <div data-slot="lm-prefix-self">
                <AlertBanner tone="danger" className="mb-0" title={t('lm.prefix.preview.selfTitle')}>
                  {t('lm.prefix.preview.selfBody')}
                </AlertBanner>
              </div>
            )}
            <p data-slot="lm-prefix-refused" data-count={refused} className={refused > 0
              ? 'm-0 inline-flex items-start gap-2 text-sm font-medium text-warning [overflow-wrap:anywhere]'
              : 'm-0 text-sm text-muted-foreground'}>
              {refused > 0 && <ShieldAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />}
              {refused > 0
                ? (enabled ? t('lm.prefix.preview.refusedOn', refused, data.active_users ?? '—') : t('lm.prefix.preview.refusedOff', refused, data.active_users ?? '—'))
                : t('lm.prefix.preview.none', data.active_users ?? '—')}
            </p>
            {refused > 0 && <p className="m-0 text-xs text-muted-foreground">{t('lm.prefix.preview.sessionsNote')}</p>}
            {sample.length > 0 && (
              <ul data-slot="lm-prefix-sample" className="m-0 grid max-h-64 min-w-0 list-none grid-cols-1 gap-1 overflow-y-auto p-0 text-sm sm:grid-cols-2">
                {sample.map((u) => (
                  <li key={u.username} data-slot="lm-prefix-user" className="min-w-0 rounded-md bg-background px-2 py-1 [overflow-wrap:anywhere]">
                    <span className="font-medium">{u.username}</span>
                    {u.display_name && <span className="text-muted-foreground"> · {u.display_name}</span>}
                  </li>
                ))}
              </ul>
            )}
            {data.truncated && (
              <p className="m-0 text-xs text-muted-foreground">{t('lm.prefix.preview.truncated', sample.length, refused)}</p>
            )}
            {preview.state === 'loading' && (
              <p className="m-0 inline-flex items-center gap-2 text-xs text-muted-foreground">
                <Spinner size={14} inline decorative />{t('lm.prefix.preview.loading')}
              </p>
            )}
          </>
        )}
      </div>
    </section>
  )
}
