import { useCallback, useState } from 'react'
import { ShieldCheck, AlertTriangle, OctagonAlert, CircleOff, RefreshCw, ChevronDown, ArrowRight } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useVisibleInterval } from '../../hooks/useVisibleInterval.js'
import { navigateTo } from '../../utils/navigate.js'
import { LoadingBlock } from '../ui/Progress.jsx'
import ToneBadge from './ToneBadge.jsx'
import { Button } from '@/components/shadcn/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'

/**
 * Yapılandırma sağlığı kartı (2026-09-12, zenginleştirme #25): Ayarlar 12 sekmeye dağılmış — "kırmızı
 * olan ne" tek kartta. Her satır tıklanınca ilgili ayar bölümüne (ya da nav sekmesine) götürür.
 * Sorunsuzken küçük yeşil şerit; sorun varsa açık liste. 5 dk'da bir görünürken tazelenir.
 */
const SETTINGS_SECTIONS = new Set(['general', 'smtp', 'ldap', 'userpush', 'weeklyavail', 'retention', 'branding'])
/** Kontrol → Genel Ayarlar'daki alan anahtarı (Aç → kaydır + odakla). */
const CHECK_SETTING_KEY = { base_url: 'site.monitor.app.base-url', admin_email: 'site.monitor.system-admin.email', reminder: 'site.monitor.weekly-report.deadline-day' }
const ICON = { ok: ShieldCheck, warn: AlertTriangle, bad: OctagonAlert, off: CircleOff }
/** Genel durum → kartın TÜM çerçevesi (sol şerit değil), ikon mürekkebi, satır zemini. */
const FRAME = { ok: 'border-success/40', warn: 'border-amber-500/50', bad: 'border-destructive/50', off: '' }
const INK = { ok: 'text-success', warn: 'text-amber-600 dark:text-amber-400', bad: 'text-destructive', off: 'text-muted-foreground' }
const ROW = {
  ok: 'text-muted-foreground', off: 'text-muted-foreground opacity-80',
  warn: 'bg-amber-500/10 text-amber-900 dark:text-amber-200', bad: 'bg-destructive/10 text-red-900 dark:text-red-200',
}

export default function ConfigHealthCard({ onOpenSection, onData }) {
  const t = useT()
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(true)
  // Varsayılan KAPALI (kullanıcı kararı 2026-09-13): sorun olsa da kart kendiliğinden açılmaz — başlıktaki
  // sayaç çipleri ("1 sorun · 2 uyarı") zaten yeterli; açan kişinin tercihi bu tarayıcıda kalır.
  const [open, setOpen] = useState(() => { try { return localStorage.getItem('cfg-health-open') === 'true' } catch { return false } })

  const load = useCallback(async () => {
    try {
      const r = await api.admin.getConfigHealth()
      // onData (2026-09-27): Ayarlar kabuğu aynı veriyi gezinti durum noktaları için okur — ek istek yok.
      if (r?.success) { setData(r.data); onData?.(r.data) }
    } catch { /* kart süs — ayar sayfası etkilenmez */ }
    finally { setLoading(false) }
  }, [onData])
  useVisibleInterval(load, 300_000, true)

  if (loading && !data) {
    return <LoadingBlock label={t('cfg.loading')} size={14} className="mb-3.5 justify-start rounded-[10px] border bg-muted/40 px-3.5 py-2.5 text-sm" />
  }
  if (!data) return null

  const overall = data.overall || 'ok'
  const Icon = ICON[overall] || ShieldCheck
  const checks = data.checks || []

  function go(c) {
    // Bölüm zaten açıksa "Aç" hiçbir şey yapmıyor gibi görünüyordu (QA ISSUE-012): ilgili alan anahtarı
    // da iletilir; GeneralSettings alana kaydırır, odaklar ve kısa süre vurgular.
    if (SETTINGS_SECTIONS.has(c.tab)) { onOpenSection?.(c.tab, CHECK_SETTING_KEY[c.key] || null); return }
    navigateTo(c.tab === 'inventory' ? 'admin' : c.tab)
  }

  function detailText(c) {
    const d = c.detail || ''
    const [kind, rest] = d.includes(':') ? [d.slice(0, d.indexOf(':')), d.slice(d.indexOf(':') + 1)] : [d, '']
    const key = `cfg.detail.${kind}`
    // Ham ISO damgası ("2026-09-12T14:33:10.386063100") arayüz biçimine (QA ISSUE-011)
    const arg = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(rest) ? formatDate(rest.replace(/(\.\d{3})\d+$/, '$1')) : rest
    const tr = t(key, arg)
    return tr === key ? d : tr
  }

  function toggle(next) {
    setOpen(next)
    try { localStorage.setItem('cfg-health-open', String(next)) } catch { /* yoksay */ }
  }

  return (
    // Durum TÜM çerçeve + ikon rengiyle (sol renk şeridi YOK — kullanıcı kararı 2026-09-26)
    <Collapsible open={open} onOpenChange={toggle} asChild>
      <section data-slot="config-health" data-status={overall} aria-label={t('cfg.title')}
        className={cn('mb-3.5 rounded-[10px] border bg-muted/30', FRAME[overall])}>
        <CollapsibleTrigger asChild>
          <Button type="button" variant="ghost"
            className="h-auto min-h-11 w-full flex-wrap justify-start gap-2.5 rounded-[10px] px-3.5 py-2.5 text-left font-semibold whitespace-normal hover:bg-transparent dark:hover:bg-transparent">
            <Icon aria-hidden="true" className={cn('size-[18px] shrink-0', INK[overall])} />
            <span className="min-w-0 flex-1">{t('cfg.title')}</span>
            <span className="flex flex-wrap gap-1.5">
              {data.bad > 0 && <ToneBadge tone="danger" className="rounded-full font-bold">{data.bad === 1 ? t('cfg.badOne') : t('cfg.bad', data.bad)}</ToneBadge>}
              {data.warn > 0 && <ToneBadge tone="warning" className="rounded-full font-bold">{data.warn === 1 ? t('cfg.warnOne') : t('cfg.warn', data.warn)}</ToneBadge>}
              <ToneBadge tone="success" className="rounded-full font-bold">{t('cfg.ok', data.ok)}</ToneBadge>
            </span>
            <ChevronDown aria-hidden="true" className={cn('size-4 shrink-0 transition-transform motion-reduce:transition-none', open && 'rotate-180')} />
          </Button>
        </CollapsibleTrigger>
        <CollapsibleContent>
          <ul className="flex flex-col gap-1 px-3.5 pb-2.5">
            {checks.map((c) => {
              const CI = ICON[c.status] || ShieldCheck
              const name = t(`cfg.check.${c.key}`)
              return (
                <li key={c.key} data-slot="config-check" data-status={c.status}
                  className={cn('grid grid-cols-[16px_minmax(0,1fr)_auto] items-center gap-x-2.5 gap-y-0.5 rounded-lg px-2 py-1.5 text-[0.88em] sm:grid-cols-[16px_minmax(120px,1fr)_2fr_auto]', ROW[c.status])}>
                  <CI size={14} aria-hidden="true" className={INK[c.status]} />
                  <span className="font-semibold">{name}</span>
                  <span className="col-start-2 row-start-2 min-w-0 break-words sm:col-start-3 sm:row-start-1">{detailText(c)}</span>
                  <Button type="button" variant="secondary" size="sm" data-go="" onClick={() => go(c)}
                    className="col-start-3 row-span-2 row-start-1 sm:col-start-4 sm:row-span-1"
                    aria-label={t('a11y.rowAction', t('cfg.go'), name)}>
                    {t('cfg.go')} <ArrowRight size={12} aria-hidden="true" />
                  </Button>
                </li>
              )
            })}
            <li className="flex justify-end px-2 pt-1">
              <Button type="button" variant="secondary" size="sm" onClick={load}><RefreshCw size={12} /> {t('cfg.refresh')}</Button>
            </li>
          </ul>
        </CollapsibleContent>
      </section>
    </Collapsible>
  )
}
