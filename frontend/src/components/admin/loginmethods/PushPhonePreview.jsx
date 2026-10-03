import { KeyRound } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'

/**
 * Telefon KİLİT EKRANI önizlemesi (2026-10-03, Giriş Yöntemleri → Push mesajı) — bildirim kartı telefona giden metni
 * TAM OLARAK gösterir: örnek değerlerle doldurulmuş, kanal süzgecinden (ISO-8859-9: "—" → "-", emoji düşer) ve tavan
 * kırpmasından geçmiş hâli (`pushTemplateModel.phoneView`). Kilit ekranı bilinçli olarak her iki temada koyu
 * (gerçek telefonun görünümü); kart üzerindeki metin kontrastı sabit.
 *
 * Sabit piksel genişliği yok: kap `w-full max-w-sm`, telefonda tam genişlik. Test kancaları: `data-slot="lm-push-preview"`,
 * `lm-push-preview-title`, `lm-push-preview-message`.
 */
export default function PushPhonePreview({ title, message, clock, lang }) {
  const t = useT()
  return (
    <figure data-slot="lm-push-preview" data-lang={lang} className="m-0 flex w-full min-w-0 flex-col gap-2">
      <div role="img" aria-label={t('lm.push.tpl.previewAria', title || '', message || '')}
        className="mx-auto flex w-full max-w-sm min-w-0 flex-col gap-3 rounded-[1.75rem] border border-zinc-700 bg-gradient-to-b from-zinc-800 via-zinc-900 to-zinc-950 p-3 pb-6 shadow-lg">
        <div aria-hidden="true" className="flex flex-col items-center pt-2 text-white">
          <span className="text-4xl leading-none font-light tracking-tight tabular-nums">{clock}</span>
        </div>
        <div aria-hidden="true" data-slot="lm-push-card"
          className="flex min-w-0 flex-col gap-1 rounded-2xl bg-white/95 px-3.5 py-3 text-zinc-900 shadow-md">
          <div className="flex min-w-0 items-center gap-2 text-[11px] text-zinc-500">
            <span className="inline-flex size-5 shrink-0 items-center justify-center rounded-md bg-primary text-primary-foreground">
              <KeyRound className="size-3" />
            </span>
            <span className="min-w-0 truncate font-semibold tracking-wide uppercase">SiteMonitor</span>
            <span className="ml-auto shrink-0">{t('lm.push.tpl.now')}</span>
          </div>
          <p data-slot="lm-push-preview-title" className="m-0 text-sm leading-snug font-semibold [overflow-wrap:anywhere]">{title}</p>
          <p data-slot="lm-push-preview-message" className="m-0 text-sm leading-snug whitespace-pre-line [overflow-wrap:anywhere]">{message}</p>
        </div>
      </div>
    </figure>
  )
}
