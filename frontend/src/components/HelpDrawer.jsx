import { useEffect, useMemo, useState } from 'react'
import { createPortal } from 'react-dom'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { HelpCircle, X, BookOpen } from 'lucide-react'
import { useT, useLanguage } from '../i18n/index.jsx'
import { navigateTo } from '../utils/navigate.js'
import { useTour } from './tour/TourProvider.jsx'
import { PAGE_TOURS } from './tour/tourSteps.js'
import whitepaperTr from '../assets/whitepaper.md?raw'
import whitepaperEn from '../assets/whitepaper.en.md?raw'
import { Button } from '@/components/shadcn/button'

/**
 * Bağlama duyarlı yardım (2026-09-12, zenginleştirme #24): sağ altta "?" — o sayfanın kılavuz bölümü
 * (whitepaper §14.x) yan panelde açılır; Yardım sekmesine gidip aramak gerekmez. Bölüm, sekme → "14.N"
 * numarasıyla bulunur (TR/EN kılavuzda numaralama aynı). Bölüm yoksa "Tam kılavuz" bağlantısı kalır.
 */
export const TAB_HELP_SECTION = {
  dashboard: '14.4', stats: '14.6', warnings: '14.7', all: '14.8', renewal: '14.9', 'renewal-guide': '14.10', forecast: '14.11',
  domains: '14.12', weakalgo: '14.13',
  uptime: '14.14', http: '14.14', domain: '14.14', port: '14.14', dns: '14.14', keyword: '14.14', ping: '14.14', page: '14.14', pagespeed: '14.14', scripted: '14.14',
  incidents: '14.15', maintenance: '14.16', alerthistory: '14.17', weeklyreports: '14.18', 'incident-history': '14.19',
  activity: '14.20', myactivity: '14.21', system: '14.22', 'login-issues': '14.23', admin: '14.24', permissions: '14.25',
  settings: '14.26', health: '14.27', sqlplayground: '14.28', help: '14.29', monitorchanges: '14.14',
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
    const onKey = (e) => { if (e.key === 'Escape') setOpen(false) }
    window.addEventListener('sm:help', onHelp)
    window.addEventListener('keydown', onKey)
    return () => { window.removeEventListener('sm:help', onHelp); window.removeEventListener('keydown', onKey) }
  }, [])

  const number = section || TAB_HELP_SECTION[tab] || null
  const md = useMemo(() => extractSection(lang === 'en' ? whitepaperEn : whitepaperTr, number), [lang, number])

  return (
    <>
      <button type="button" className="help-fab" data-tour="help-fab" onClick={() => { setSection(null); setOpen(true) }} aria-label={t('helpd.open')} title={t('helpd.open')}>
        <HelpCircle size={20} aria-hidden="true" />
      </button>
      {open && createPortal(
        <div className="helpd-overlay" onMouseDown={(e) => { if (e.target === e.currentTarget) setOpen(false) }}>
          <aside className="helpd" role="dialog" aria-modal="true" aria-label={t('helpd.title')}>
            <div className="helpd-head">
              <BookOpen size={16} aria-hidden="true" />
              <span className="helpd-title">{t('helpd.title')}</span>
              <Button type="button" variant="secondary" size="sm" onClick={() => { setOpen(false); navigateTo('help') }}>{t('helpd.full')}</Button>
              <Button type="button" variant="secondary" size="sm" onClick={() => { setOpen(false); tour.start('main') }}>{t('tour.restart')}</Button>
              {PAGE_TOURS[tab]?.length > 0 && <Button type="button" variant="secondary" size="sm" onClick={() => { setOpen(false); tour.start('page', { pageId: tab }) }}>{t('tour.pageStart')}</Button>}
              <button type="button" className="helpd-close" onClick={() => setOpen(false)} aria-label={t('app.close')}><X size={14} /></button>
            </div>
            <div className="helpd-body help-content">
              {md ? <ReactMarkdown remarkPlugins={[remarkGfm]}>{md}</ReactMarkdown> : <p className="helpd-empty">{t('helpd.none')}</p>}
            </div>
          </aside>
        </div>,
        document.body,
      )}
    </>
  )
}
