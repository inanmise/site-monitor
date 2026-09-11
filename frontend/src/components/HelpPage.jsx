import { useEffect, useMemo, useRef, useState } from 'react'
import { useAppVersion } from '../contexts/BrandingProvider.jsx'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { useT, useLanguage } from '../i18n/index.jsx'
import { slugify, parseToc } from '../utils/mdToc.js'
import BrandLogo from './BrandLogo.jsx'
import SegmentedControl from './ui/SegmentedControl.jsx'
import ReleaseNotesPanel from './ReleaseNotesPanel.jsx'
import { readUrlParam, useUrlQuerySync } from '../hooks/useUrlQuerySync.js'
import { BookOpen, Sparkles } from 'lucide-react'
import whitepaperTr from '../assets/whitepaper.md?raw'
import whitepaperEn from '../assets/whitepaper.en.md?raw'

/**
 * Kılavuz dile göre seçilir — monitorGuides.js'teki {tr, en} deseniyle aynı.
 * PDF'ler `frontend/scripts/gen-whitepaper-pdf.mjs` ile aynı markdown'dan üretilir;
 * whitepaper-pdf-freshness.test.jsx kaynak ile PDF'in ayrışmasını engeller.
 */
const GUIDES = {
  tr: { md: whitepaperTr, pdf: '/whitepaper.tr.pdf', file: 'Site-Monitor-Kullanim-Kilavuzu.pdf' },
  en: { md: whitepaperEn, pdf: '/whitepaper.en.pdf', file: 'Site-Monitor-User-Guide.pdf' },
}

export default function HelpPage() {
  const t = useT()
  const { lang } = useLanguage()
  const guide = GUIDES[lang] ?? GUIDES.tr
  const contentRef = useRef(null)
  const [activeId, setActiveId] = useState('')
  // Görünüm: kılavuz (markdown) | yenilikler (yayın dizini). URL `view` param'ı (PAGE_STATE_PARAMS'ta;
  // sürüm çipi `?view=releases` ile gelir). Kılavuz varsayılan → param'sız.
  const [view, setView] = useState(() => (readUrlParam('view', 'guide') === 'releases' ? 'releases' : 'guide'))
  useUrlQuerySync({ view: view === 'releases' ? 'releases' : null })
  // Aynı sekmedeyken (Yardım açıkken çipten "Yenilikler") App param'ı olayla iletir — mount tekrar olmaz.
  useEffect(() => {
    const on = (e) => { const v = e?.detail?.view; if (v === 'releases' || v === 'guide') setView(v) }
    window.addEventListener('sm:tab-params', on)
    return () => window.removeEventListener('sm:tab-params', on)
  }, [])

  // Sürüm damgası kaynakta {{VERSION}} olarak durur ve BURADA çözülür. Elle yazılan bir
  // numara her sürümde eskiyordu (kılavuz 20.23.0 derken uygulama 20.24.1'di); doğruluk kaynağı
  // kök VERSION dosyasıdır ve ÇALIŞMA ANINDA sunucudan gelir (derleme zamanı değeri yalnız yedek —
  // gömülü olan, dev-server yeniden başlatılmadıkça bayat kalıyordu).
  const appVersion = useAppVersion()
  const markdown = useMemo(
    () => guide.md.split('{{VERSION}}').join(appVersion),
    [guide.md, appVersion]
  )
  // markdown modül seviyesi sabitten türer → memo yalnız dil değişiminde yeniden hesaplar.
  const tocItems = useMemo(() => parseToc(markdown), [markdown])

  useEffect(() => {
    const el = contentRef.current
    if (!el) return
    // Dil değişti: eski slug'a işaret eden aktif başlığı ve kaydırma konumunu sıfırla,
    // yoksa okuyucu yeni belgenin ortasında ve yanlış TOC vurgusuyla açılır.
    el.scrollTop = 0
    setActiveId('')
    const handler = () => {
      const headings = el.querySelectorAll('h1[id], h2[id], h3[id]')
      const containerTop = el.getBoundingClientRect().top
      let current = ''
      for (const h of headings) {
        if (h.getBoundingClientRect().top - containerTop <= 40) current = h.id
      }
      setActiveId(current)
    }
    el.addEventListener('scroll', handler, { passive: true })
    return () => el.removeEventListener('scroll', handler)
  }, [lang])

  function scrollTo(id) {
    const container = contentRef.current
    const heading = document.getElementById(id)
    if (!container || !heading) return
    const containerRect = container.getBoundingClientRect()
    const headingRect = heading.getBoundingClientRect()
    container.scrollBy({ top: headingRect.top - containerRect.top - 16, behavior: 'smooth' })
  }

  // Sabit tutulur: her render'da yeniden yaratmak react-markdown'a 100 KB'lık belgeyi
  // baştan render ettirir — scroll-spy her kaydırmada setActiveId çağırdığı için pahalı.
  const components = useMemo(() => {
    function extractText(children) {
      if (typeof children === 'string') return children
      if (Array.isArray(children)) return children.map(extractText).join('')
      if (children?.props?.children != null) return extractText(children.props.children)
      return ''
    }
    return {
      h1: ({ children }) => <h1 id={slugify(extractText(children))}>{children}</h1>,
      h2: ({ children }) => <h2 id={slugify(extractText(children))}>{children}</h2>,
      h3: ({ children }) => <h3 id={slugify(extractText(children))}>{children}</h3>,
      h4: ({ children }) => <h4 id={slugify(extractText(children))}>{children}</h4>,
      // `code` BİLİNÇLİ olarak override edilmiyor. react-markdown 9+ sürümlerinde `inline`
      // prop'u kaldırıldı; eski override'da koşul her zaman false'a düşüyor ve satır içi
      // `kod` parçaları tam genişlikte <pre> bloğuna dönüşerek cümleleri ve tablo
      // hücrelerini parçalıyordu. Varsayılan davranış zaten doğru: çit → <pre><code>,
      // satır içi → <code>; ikisinin stili de App.css'te .help-content altında tanımlı.
    }
  }, [])

  return (
    <div className="help-page">
      <div className="help-header">
        {/* Nötr marka logosu + sürüm (BRAND.md: Yardım/Hakkında yüzeyi) */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <BrandLogo status="ok" size={64} />
          <div>
            <h2 className="help-header-title" style={{ margin: 0 }}>{t('help.title')}</h2>
            <span style={{ fontSize: 12, opacity: .65 }}>
              v{appVersion} · {t('help.langNote')}
            </span>
          </div>
        </div>
        <div className="help-header-right">
          <SegmentedControl value={view} onChange={setView} ariaLabel={t('help.title')}
            options={[
              { value: 'guide', label: t('help.view.guide'), icon: BookOpen },
              { value: 'releases', label: t('help.view.releases'), icon: Sparkles },
            ]} />
          {view === 'guide' && (
            <a
              href={guide.pdf}
              download={guide.file}
              title={t('help.downloadTitle')}
              className="btn btn-sm help-download-btn"
            >
              {t('help.download')}
            </a>
          )}
        </div>
      </div>

      {view === 'releases' && (
        <div className="help-releases">
          <ReleaseNotesPanel />
        </div>
      )}

      {view === 'guide' && (
      <div className="help-layout">
        <nav className="help-toc">
          <h3 className="help-toc-heading">{t('help.toc')}</h3>
          {tocItems.map((item, i) => (
            <a
              key={i}
              className={`toc-item toc-level-${item.level}${activeId === item.id ? ' toc-active' : ''}`}
              href={`#${item.id}`}
              onClick={e => { e.preventDefault(); scrollTo(item.id) }}
            >
              {item.text}
            </a>
          ))}
        </nav>

        <div className="help-content" ref={contentRef}>
          <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
            {markdown}
          </ReactMarkdown>
        </div>
      </div>
      )}
    </div>
  )
}
