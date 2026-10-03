import { useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Separator } from '@/components/shadcn/separator'
import { CHANNEL_ICON } from './OtpLoginFlow.jsx'

/**
 * Giriş formunun altındaki ince "veya" ayıracı + AÇIK kod yöntemlerinin düğmeleri (2026-10-02). Giriş sayfası ve
 * Ayarlar → Giriş Yöntemleri önizlemesi AYNI bileşeni çizer (önizleme gerçek görünümü gösterir). Kanal yoksa hiçbir şey
 * çizilmez. Telefonda düğmeler alt alta tam genişlik, ≥ 640 px yan yana; dokunma hedefi ≥ 40 px.
 *
 * Test kancası: `data-slot="login-otp-methods"`, düğmelerde `data-channel`.
 */
export default function OtpMethodButtons({ channels, onPick }) {
  const t = useT()
  if (!channels?.length) return null
  return (
    <div data-slot="login-otp-methods" className="flex w-full min-w-0 flex-col gap-3">
      <div className="flex items-center gap-3 text-xs text-muted-foreground" aria-hidden="true">
        <Separator className="flex-1" />
        <span className="tracking-wide uppercase">{t('login.otp.or')}</span>
        <Separator className="flex-1" />
      </div>
      <div className="grid grid-cols-1 gap-2 sm:auto-cols-fr sm:grid-flow-col">
        {channels.map((ch) => {
          const Icon = CHANNEL_ICON[ch]
          return (
            <Button key={ch} type="button" variant="outline" className="min-h-10 w-full whitespace-normal"
              data-channel={ch} onClick={() => onPick?.(ch)}>
              <Icon /> {ch === 'email' ? t('login.otp.emailButton') : t('login.otp.pushButton')}
            </Button>
          )
        })}
      </div>
    </div>
  )
}
