import { useEffect, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { useT } from '../i18n/index.jsx'
import BrandLogo from './BrandLogo.jsx'
import whitepaperContent from '../assets/whitepaper.md?raw'

function slugify(text) {
  return String(text)
    .toLowerCase()
    .replace(/ş/g, 's').replace(/ğ/g, 'g').replace(/ı/g, 'i')
    .replace(/ü/g, 'u').replace(/ö/g, 'o').replace(/ç/g, 'c')
    .replace(/[^a-z0-9\s-]/g, '')
    .trim()
    .replace(/\s+/g, '-')
}

function parseToc(markdown) {
  const lines = markdown.split('\n')
  const items = []
  for (const line of lines) {
    const m = line.match(/^(#{1,4})\s+(.+)/)
    if (!m) continue
    const level = m[1].length
    if (level > 3) continue
    const text = m[2].replace(/\*\*/g, '').replace(/`/g, '').trim()
    items.push({ level, text, id: slugify(text) })
  }
  return items
}

const tocItems = parseToc(whitepaperContent)

export default function HelpPage() {
  const t = useT()
  const contentRef = useRef(null)
  const [activeId, setActiveId] = useState('')

  useEffect(() => {
    const el = contentRef.current
    if (!el) return
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
  }, [])

  function scrollTo(id) {
    const container = contentRef.current
    const heading = document.getElementById(id)
    if (!container || !heading) return
    const containerRect = container.getBoundingClientRect()
    const headingRect = heading.getBoundingClientRect()
    container.scrollBy({ top: headingRect.top - containerRect.top - 16, behavior: 'smooth' })
  }

  function extractText(children) {
    if (typeof children === 'string') return children
    if (Array.isArray(children)) return children.map(extractText).join('')
    if (children?.props?.children != null) return extractText(children.props.children)
    return ''
  }

  const components = {
    h1: ({ children }) => <h1 id={slugify(extractText(children))}>{children}</h1>,
    h2: ({ children }) => <h2 id={slugify(extractText(children))}>{children}</h2>,
    h3: ({ children }) => <h3 id={slugify(extractText(children))}>{children}</h3>,
    h4: ({ children }) => <h4 id={slugify(extractText(children))}>{children}</h4>,
    code: ({ inline, className, children }) => {
      if (inline) return <code>{children}</code>
      return (
        <pre>
          <code className={className}>{children}</code>
        </pre>
      )
    },
  }

  return (
    <div className="help-page">
      <div className="help-header">
        {/* Nötr marka logosu + sürüm (BRAND.md: Yardım/Hakkında yüzeyi) */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <BrandLogo status="ok" size={64} />
          <div>
            <h2 className="help-header-title" style={{ margin: 0 }}>{t('help.title')}</h2>
            <span style={{ fontSize: 12, opacity: .65 }}>v{__APP_VERSION__}</span>
          </div>
        </div>
        <a
          href="/whitepaper.pdf"
          download="SiteMonitor-WhitePaper.pdf"
          className="btn btn-sm help-download-btn"
        >
          {t('help.download')}
        </a>
      </div>

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
            {whitepaperContent}
          </ReactMarkdown>
        </div>
      </div>
    </div>
  )
}
