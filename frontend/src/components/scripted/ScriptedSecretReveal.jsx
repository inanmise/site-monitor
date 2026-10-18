import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { Eye, EyeOff } from 'lucide-react'
import { api } from '../../api/client'
import AlertBanner from '../ui/AlertBanner.jsx'
import CopyButton from '../ui/CopyButton.jsx'
import { Spinner } from '../ui/Progress.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { cn } from '@/lib/utils'

/**
 * GİZLİ ENV DEĞERİNİ GÖSTER (2026-10-19, kullanıcı isteği: "admin kullanıcısı gizli bilgileri review edebilmeli").
 *
 * <p>Yalnız sunucunun izin verdiği satırda çizilir (liste satırının `can_reveal`'ı: global yönetici ya da takımı KENDİ
 * kapsamıyla yöneten yönetici — takım erişim paylaşımı, AUDIT, 7/24 ve takım üyeleri hayır). Değer
 * `POST /monitoring/scripted/{id}/env/{name}/reveal` ile o an okunur ve YALNIZ bu bileşenin durumunda yaşar: forma,
 * taslağa, yerel yedeğe, kayıtlı görünüme ya da herhangi bir depoya yazılmaz. {@link REVEAL_TTL_MS} sonra, sekme
 * gizlenince / pencere odağı kaybedince, form kapanınca (bileşen söküldüğünde) ve satırın adı değişince kendiliğinden
 * gizlenir; geç gelen yanıt (gizlendikten sonra) yok sayılır. Göstermek değeri DEĞİŞTİRMEZ: yeni değer yine satırın
 * kendi kutusuna yazılır.
 */
export const REVEAL_TTL_MS = 30_000

/**
 * @param {{ monitorId: number|string, name: string }|null} target  gösterilebilir satır; null = düğme yok
 * @returns {{ state: { phase: 'idle'|'loading'|'shown'|'error', value?: string, message?: string }, reveal: Function, hide: Function }}
 */
export function useSecretReveal(target) {
  const enabled = !!(target && target.monitorId != null && target.name)
  const key = enabled ? `${target.monitorId}\u0000${target.name}` : ''
  const [state, setState] = useState({ phase: 'idle' })
  const seq = useRef(0)
  const keyRef = useRef(key)
  // Hedef nesnesi her çizimde yeniden kurulur — istek anındaki kimlik bu ref'ten okunur, bağımlılık `key`'dir
  const targetRef = useRef(target)
  targetRef.current = target

  const hide = useCallback(() => {
    seq.current++   // uçuşan yanıt gizlenmiş satıra düşmesin
    setState((s) => (s.phase === 'idle' ? s : { phase: 'idle' }))
  }, [])

  // Satır başka bir değişkene döndü (ad değişti / satır silindi, sıradaki satır bu konuma kaydı) ya da düğme kalktı → gizle
  useEffect(() => {
    if (keyRef.current !== key) {
      keyRef.current = key
      hide()
    }
  }, [key, hide])

  // 30 sn sonra kendiliğinden gizle
  useEffect(() => {
    if (state.phase !== 'shown') return undefined
    const timer = setTimeout(hide, REVEAL_TTL_MS)
    return () => clearTimeout(timer)
  }, [state, hide])

  // Sekme gizlendi / pencere odağı kaybetti / sayfa kapanıyor → gizle (okunurken de: yanıt düşer)
  useEffect(() => {
    if (state.phase !== 'shown' && state.phase !== 'loading') return undefined
    const onVisibility = () => { if (document.visibilityState === 'hidden') hide() }
    window.addEventListener('blur', hide)
    window.addEventListener('pagehide', hide)
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      window.removeEventListener('blur', hide)
      window.removeEventListener('pagehide', hide)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [state.phase, hide])

  // Söküm (form kapandı, sekme / sayfa değişti, çıkış): uçuşan yanıt hiçbir yere yazılmaz
  useEffect(() => () => { seq.current++ }, [])

  const reveal = useCallback(async () => {
    const tg = targetRef.current
    if (!tg || tg.monitorId == null || !tg.name) return
    const mine = ++seq.current
    setState({ phase: 'loading' })
    try {
      const res = await api.monitoring.revealScriptedSecret(tg.monitorId, tg.name)
      if (mine !== seq.current) return
      if (res?.success && typeof res.data?.value === 'string') setState({ phase: 'shown', value: res.data.value })
      else setState({ phase: 'error', message: typeof res?.error === 'string' ? res.error : null })
    } catch (e) {
      if (mine !== seq.current) return
      setState({ phase: 'error', message: typeof e?.message === 'string' ? e.message : null })
    }
  }, [])

  return { state, reveal, hide }
}

/**
 * Satırdaki göster / gizle düğmesi (aç-kapa, `aria-pressed`). Erişilebilir ad değişkenin adını taşır.
 * Dokunmatikte 40 px.
 */
export function SecretRevealButton({ t, name, state, onReveal, onHide }) {
  const shown = state.phase === 'shown'
  const loading = state.phase === 'loading'
  const label = shown ? t('scripted.revealHideFor', name) : t('scripted.revealShowFor', name)
  return (
    <SimpleTooltip content={shown ? t('scripted.revealHide') : t('scripted.revealShow')}>
      <Button type="button" variant="outline" size="icon-sm" data-slot="env-secret-reveal-toggle"
        className="shrink-0 pointer-coarse:size-10" aria-label={label} aria-pressed={shown} disabled={loading}
        onClick={shown ? onHide : onReveal}>
        {loading ? <Spinner size={14} inline decorative /> : shown ? <EyeOff size={15} aria-hidden="true" /> : <Eye size={15} aria-hidden="true" />}
      </Button>
    </SimpleTooltip>
  )
}

/**
 * Gösterilen değer (salt okunur, eş aralıklı) + Kopyala + Gizle, ya da sunucunun hata iletisi. Boşta hiçbir şey çizmez.
 * Kutu tarayıcı otomatik doldurma / parola yöneticilerine kapalı (`autoComplete="off"`), yazım denetimi kapalı.
 */
export function SecretRevealPanel({ t, name, state, onHide, className }) {
  const valueId = useId()
  if (state.phase === 'error') {
    return (
      <div data-slot="env-secret-reveal" data-state="error" className={className}>
        <AlertBanner tone="danger" className="mb-0" onDismiss={onHide} dismissLabel={t('scripted.revealDismiss')}>
          {state.message || t('scripted.revealFailed')}
        </AlertBanner>
      </div>
    )
  }
  if (state.phase !== 'shown') return null
  return (
    <div data-slot="env-secret-reveal" data-state="shown"
      className={cn('flex flex-wrap items-center gap-2 rounded-md border border-border bg-muted/40 p-2', className)}>
      <Label htmlFor={valueId} className="w-full text-xs font-semibold text-muted-foreground [overflow-wrap:anywhere]">
        {t('scripted.revealSavedValue', name)}
      </Label>
      <Input id={valueId} data-slot="env-secret-value" readOnly value={state.value} autoComplete="off" spellCheck={false}
        data-1p-ignore="true" data-lpignore="true"
        className="min-w-0 flex-1 basis-48 font-mono pointer-coarse:h-10"
        onFocus={(e) => e.target.select()} />
      <CopyButton value={state.value} label={t('scripted.revealCopy')} copiedLabel={t('scripted.revealCopied')}
        className="shrink-0 pointer-coarse:size-10" />
      <Button type="button" variant="outline" size="sm" className="shrink-0 pointer-coarse:h-10" onClick={onHide}
        data-slot="env-secret-hide">
        <EyeOff size={14} aria-hidden="true" /> {t('scripted.revealHide')}
      </Button>
      <p className="w-full text-xs text-muted-foreground">{t('scripted.revealAutoHide', Math.round(REVEAL_TTL_MS / 1000))}</p>
    </div>
  )
}
