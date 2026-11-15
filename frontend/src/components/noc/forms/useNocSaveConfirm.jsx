import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { Headset } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Field, FieldContent, FieldDescription, FieldLabel } from '@/components/shadcn/field'
import { loadNocFormOptions } from './useNocFormOptions.js'
import {
  addNoAsk, confirmGroupNames, isNoAsk, nocAvailable, NOC_OPTIONS_WAIT_MS, noAskKey, shouldAskNoc, withTimeout,
} from './nocSaveConfirmModel.js'

function store() {
  try { return window.localStorage } catch { return null }
}

/** Sorulmadan geçen karar: form değeri olduğu gibi, "bir daha sorma" yazılmaz. */
function passThrough(nocNotify) {
  return { nocNotify: !!nocNotify, asked: false, apply: (f) => f, done: () => {} }
}

/**
 * Kayıtta 7/24 onayı (2026-11-15) — formların `save()` akışına tek çağrıyla eklenir:
 *
 * <pre>
 * const nocConfirm = useNocSaveConfirm()
 * async function save() {
 *   if (fe.check(…)) return
 *   const noc = await nocConfirm.ask({ type: 'PING', monitorId: modal === 'new' ? null : modal.id,
 *                                      nocNotify: form.nocNotify, locked: routingLock })
 *   if (!noc) return                       // Vazgeç: form açık kalır
 *   const f = noc.apply(form)              // Evet → { …form, nocNotify: true }
 *   … yük f'den kurulur, kayıt …
 *   if (res?.success) noc.done(res.data?.id)
 * }
 * // form penceresinin İÇİNDE: {nocConfirm.dialog}
 * </pre>
 *
 * <p>Kurallar `nocSaveConfirmModel.shouldAskNoc`. Seçenekler en çok {@link NOC_OPTIONS_WAIT_MS} beklenir (çoğunlukla form
 * alanı zaten önbelleğe almıştır); gelmezse sorulmaz. Pencere kapanırsa / bileşen sökülürse bekleyen söz "Vazgeç" ile
 * çözülür — asılı söz kalmaz.
 *
 * <p><b>Form kapanırsa kayıt yok (inceleme 2026-11-15):</b> `dialog` formun İÇİNDE bir yuva olarak çizilir; yuva sökülünce
 * (form kapandı) bekleyen soru "Vazgeç" ile çözülür ve seçenek beklemesi sırasında form kapandıysa / yeniden açıldıysa
 * `ask` null döner — kullanıcının kapattığı formun kaydı gönderilmez, eski formun sorusu yeni formda çıkmaz.
 */
export function useNocSaveConfirm() {
  const [dlg, setDlg] = useState(null)
  const resolver = useRef(null)
  const alive = useRef(true)
  const busy = useRef(false)
  /** Yuva sökülünce artar: bir sorunun başladığı form hâlâ açık mı. */
  const hostEpoch = useRef(0)
  const hostCount = useRef(0)

  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
      const r = resolver.current
      resolver.current = null
      r?.(null)
    }
  }, [])

  const hostMounted = useCallback(() => {
    hostCount.current += 1
    return () => {
      hostCount.current -= 1
      hostEpoch.current += 1
      const r = resolver.current
      resolver.current = null
      r?.(null)
    }
  }, [])

  const ask = useCallback(async ({ type, monitorId = null, nocNotify = false, locked = false, groupIds = null } = {}) => {
    if (nocNotify || locked) return passThrough(nocNotify)
    const key = noAskKey(type, monitorId)
    if (isNoAsk(store(), key)) return passThrough(false)
    // Çift tıklama: seçenekler beklenirken ya da pencere açıkken gelen ikinci "Kaydet" hiçbir şey yapmaz (çift kayıt yok).
    if (busy.current) return null
    busy.current = true
    let choice
    let options
    const epoch = hostEpoch.current
    const hadHost = hostCount.current > 0
    try {
      options = await withTimeout(loadNocFormOptions(), NOC_OPTIONS_WAIT_MS, null)
      // Beklerken form kapandı / yeniden açıldı ya da bileşen söküldü → bu kayıt artık istenmiyor
      if (!alive.current || (hadHost && hostEpoch.current !== epoch)) return null
      const available = nocAvailable(options, type)
      if (!shouldAskNoc({ nocNotify, locked, available, noAsk: false })) return passThrough(false)
      choice = await new Promise((resolve) => {
        resolver.current = resolve
        setDlg({ type, isNew: monitorId == null, groups: confirmGroupNames(options?.groups, groupIds) })
      })
    } finally {
      busy.current = false
      resolver.current = null
    }
    if (alive.current) setDlg(null)
    if (!choice) return null
    const done = (savedId) => {
      if (!choice.noAsk) return
      const k = noAskKey(type, savedId ?? monitorId)
      if (k) addNoAsk(store(), k)
    }
    return {
      nocNotify: !!choice.yes,
      asked: true,
      apply: (f) => (choice.yes ? { ...f, nocNotify: true } : f),
      done,
    }
  }, [])

  const answer = useCallback((value) => {
    const r = resolver.current
    resolver.current = null
    r?.(value)
  }, [])

  const dialog = (
    <NocConfirmHost onMount={hostMounted}>
      {dlg ? <NocSaveConfirmDialog {...dlg} onAnswer={answer} /> : null}
    </NocConfirmHost>
  )
  return { ask, dialog }
}

/** Formun içindeki yuva: takılınca sayılır, sökülünce (form kapandı) bekleyen soru iptal edilir. */
function NocConfirmHost({ onMount, children }) {
  useEffect(() => onMount(), [onMount])
  return children
}

/**
 * Onay penceresi — shadcn düğmeler + onay kutusu, form penceresinin içinde iç içe ModalShell. Üç yol: Evet (7/24 açılıp
 * kaydedilir), Hayır (bildirmeden kaydedilir), Vazgeç (forma dönülür; Escape / X da Vazgeç). Test kancaları:
 * `data-slot="noc-save-confirm"`, `noc-confirm-yes|no|cancel`, `noc-confirm-noask`.
 */
export function NocSaveConfirmDialog({ type, isNew = false, groups = [], onAnswer }) {
  const t = useT()
  const [noAsk, setNoAsk] = useState(false)
  const id = useId()
  const typeLabel = t(`noc.type.${type}`)
  return (
    <ModalShell
      open
      size="md"
      icon={Headset}
      title={t('noc.confirm.title')}
      onClose={() => onAnswer(null)}
      footer={
        <div data-slot="noc-confirm-actions" className="flex w-full flex-col-reverse gap-2 sm:flex-row sm:flex-wrap sm:justify-end">
          <Button type="button" variant="ghost" data-slot="noc-confirm-cancel" className="pointer-coarse:h-10"
            onClick={() => onAnswer(null)}>{t('noc.confirm.cancel')}</Button>
          <Button type="button" variant="outline" data-slot="noc-confirm-no" className="h-auto min-h-9 whitespace-normal pointer-coarse:min-h-10"
            onClick={() => onAnswer({ yes: false, noAsk })}>{t('noc.confirm.no')}</Button>
          <Button type="button" data-slot="noc-confirm-yes" autoFocus className="h-auto min-h-9 whitespace-normal pointer-coarse:min-h-10"
            onClick={() => onAnswer({ yes: true, noAsk })}>{t('noc.confirm.yes')}</Button>
        </div>
      }
    >
      <div data-slot="noc-save-confirm" data-type={type} className="flex flex-col gap-3 text-sm">
        <p className="[overflow-wrap:anywhere]">{t('noc.confirm.body', typeLabel)}</p>
        <p className="text-muted-foreground [overflow-wrap:anywhere]">{t('noc.confirm.why')}</p>
        {groups.length > 0 && (
          <p data-slot="noc-confirm-groups" className="text-muted-foreground [overflow-wrap:anywhere]">
            {t('noc.confirm.groups', groups.join(', '))}
          </p>
        )}
        <Field orientation="horizontal" data-slot="noc-confirm-noask-row" className="items-start gap-2 pointer-coarse:min-h-10 pointer-coarse:items-center">
          <Checkbox id={id} data-slot="noc-confirm-noask" checked={noAsk} aria-describedby={`${id}-h`}
            onCheckedChange={(v) => setNoAsk(v === true)} className="mt-0.5" />
          <FieldContent className="gap-1">
            <FieldLabel htmlFor={id} className="font-normal">{t('noc.confirm.noAsk')}</FieldLabel>
            <FieldDescription id={`${id}-h`} className="text-xs">
              {t(isNew ? 'noc.confirm.noAskHintNew' : 'noc.confirm.noAskHint')}
            </FieldDescription>
          </FieldContent>
        </Field>
      </div>
    </ModalShell>
  )
}
