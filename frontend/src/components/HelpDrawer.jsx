import { useEffect, useMemo, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { HelpCircle, BookOpen } from 'lucide-react'
import { useT, useLanguage } from '../i18n/index.jsx'
import { navigateTo } from '../utils/navigate.js'
import { useTour } from './tour/TourProvider.jsx'
import { PAGE_TOURS } from './tour/tourSteps.js'
import whitepaperTr from '../assets/whitepaper.md?raw'
import whitepaperEn from '../assets/whitepaper.en.md?raw'
import SimpleTooltip from './ui/SimpleTooltip.jsx'
import { Button } from '@/components/shadcn/button'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'

/**
 * Bağlama duyarlı yardım (2026-09-12, zenginleştirme #24): sağ altta "?" — o sayfanın kılavuz bölümü
 * (whitepaper §14.x) yan panelde açılır; Yardım sekmesine gidip aramak gerekmez. Bölüm, sekme → "14.N"
 * numarasıyla bulunur (TR/EN kılavuzda numaralama aynı). Bölüm yoksa "Tam kılavuz" bağlantısı kalır.
 *
 * Çizim shadcn Sheet (sağdan; telefonda tam genişlik): Escape / dış tıklama / odak tuzağı / odak iadesi
 * bileşenden gelir. Katman `--z-dialog`: ürün turu (3000) "Daha fazla" ile bu paneli açar — tur örtüsünün
 * ALTINDA kalırsa panel görünmez olurdu.
 */
export const TAB_HELP_SECTION = {
  dashboard: '14.4', stats: '14.6', warnings: '14.7', all: '14.8', renewal: '14.9', 'renewal-guide': '14.10', forecast: '14.11',
  domains: '14.12', weakalgo: '14.13',
  uptime: '14.14', http: '14.14', domain: '14.14', port: '14.14', dns: '14.14', keyword: '14.14', ping: '14.14', page: '14.14', pagespeed: '14.14', scripted: '14.14',
  incidents: '14.15', maintenance: '14.16', alerthistory: '14.17', weeklyreports: '14.18', 'incident-history': '14.19',
  activity: '14.20', myactivity: '14.21', system: '14.22', 'login-issues': '14.23', admin: '14.24', permissions: '14.25',
  settings: '14.26', health: '14.27', sqlplayground: '14.28', help: '14.29', monitorchanges: '14.14', noc: '14.31',
}

/** "### 14.N …" başlığından bir sonraki "### " ya da "## " başlığına kadar olan parçayı döner. */
export function extractSection(md, number) {
  if (!md || !number) return null
  const lines = md.split(/\r?\n/)
  const start = lines.findIndex((l) => new RegExp(`^###\\s+${number.replace('.', '\\.')}(\\s|$)`).test(l))
  if (start < 0) return null
  let end = lines.length
  for (let i = start + 1; i < lines.length; i++) { if (/^##\s|^###\s/.test(lines[i])) { end = i; break } }
  return lines.slice(start, end).join('\n')
}

export default function HelpDrawer({ tab }) {
  const t = useT()
  const { lang } = useLanguage()
  const [open, setOpen] = useState(false)
  const [section, setSection] = useState(null)
  const tour = useTour()   // ürün turu (2026-09-13): çekmeceden genel tur / sayfa turu

  useEffect(() => {
    const onHelp = (e) => { setSection(e?.detail?.section || null); setOpen(true) }
    window.addEventListener('sm:help', onHelp)
    return () => { window.removeEventListener('sm:help', onHelp) }
  }, [])

  const number = section || TAB_HELP_SECTION[tab] || null
  const md = useMemo(() => extractSection(lang === 'en' ? whitepaperEn : whitepaperTr, number), [lang, number])

  const go = (fn) => { setOpen(false); fn() }

  return (
    <>
      <SimpleTooltip content={t('helpd.open')} side="left">
        <Button type="button" size="icon" data-tour="help-fab" aria-label={t('helpd.open')}
          onClick={() => { setSection(null); setOpen(true) }}
          // z-40 (2026-09-27): Sheet/Dialog katmanları z-50 — eskiden z-[900] ile açık panellerin ÜSTÜNDE kalıp sağ alttaki
          // Kaydet/Uygula düğmelerini örtüyordu. Yapışkan alt eylem çubuğu varken yukarı kalkması App.css'te
          // (`body:has(.app-main .sticky.bottom-0) [data-tour="help-fab"]`).
          className="fixed right-[18px] bottom-[calc(18px+env(safe-area-inset-bottom))] z-40 size-[42px] rounded-full shadow-lg print:hidden">
          <HelpCircle aria-hidden="true" className="size-5" />
        </Button>
      </SimpleTooltip>
      <Sheet open={open} onOpenChange={setOpen}>
        <SheetContent side="right" className="z-[var(--z-dialog)] w-full gap-0 p-0 sm:max-w-[520px]">
          <SheetHeader className="gap-2 border-b px-4 py-3 pr-12">
            <SheetTitle className="flex items-center gap-2">
              <BookOpen aria-hidden="true" className="size-4" /> {t('helpd.title')}
            </SheetTitle>
            <SheetDescription className="sr-only">{t('helpd.open')}</SheetDescription>
            <div className="flex flex-wrap gap-2">
              <Button type="button" variant="secondary" size="sm" onClick={() => go(() => navigateTo('help'))}>{t('helpd.full')}</Button>
              <Button type="button" variant="secondary" size="sm" onClick={() => go(() => tour.start('main'))}>{t('tour.restart')}</Button>
              {PAGE_TOURS[tab]?.length > 0 && (
                <Button type="button" variant="secondary" size="sm" onClick={() => go(() => tour.start('page', { pageId: tab }))}>{t('tour.pageStart')}</Button>
              )}
            </div>
          </SheetHeader>
          {/* Gövde: whitepaper tipografisi (.help-content — Yardım sekmesiyle ortak markdown biçimi; shadcn öğesi değil). */}
          <div className="helpd-body help-content min-h-0">
            {md ? <ReactMarkdown remarkPlugins={[remarkGfm]}>{md}</ReactMarkdown> : <p className="text-muted-foreground">{t('helpd.none')}</p>}
          </div>
        </SheetContent>
      </Sheet>
    </>
  )
}
