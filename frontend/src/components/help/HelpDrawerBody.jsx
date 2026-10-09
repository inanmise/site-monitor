import { useEffect, useMemo, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { useT } from '../../i18n/index.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import { extractSection } from './helpSection.js'

/**
 * Yardım çekmecesinin GÖVDESİ — tembel parça (2026-10-09, açılış paketi küçültme).
 *
 * <p>Eskiden HelpDrawer react-markdown + remark-gfm'i ve İKİ kılavuzu (TR + EN, ~336 KB ham metin) statik içe
 * aktarıyordu; App HelpDrawer'ı statik çizdiği için hepsi açılış paketindeydi. Artık "?" düğmesi açılışta kalır,
 * bu gövde çekmece ilk açıldığında yüklenir ve YALNIZ etkin dilin kılavuzunu (dinamik `?raw` içe aktarımı) çeker.
 * Metin modül önbelleğinde tutulur: sonraki açılışlar ve dil geri dönüşü beklemeden çizilir. İçerik ve görünüm aynı.
 */
const LOADERS = {
  tr: () => import('../../assets/whitepaper.md?raw'),
  en: () => import('../../assets/whitepaper.en.md?raw'),
}
const cache = {}

/** Etkin dilin kılavuz metni; yüklenene dek null. Yükleme başarısızsa '' (→ "ayrı bölüm yok, tam kılavuza bakın"). */
function useWhitepaper(lang) {
  const key = lang === 'en' ? 'en' : 'tr'
  const [loaded, setLoaded] = useState(() => ({ key, text: cache[key] ?? null }))
  useEffect(() => {
    if (cache[key] != null) return undefined
    let alive = true
    LOADERS[key]()
      .then((m) => { cache[key] = m.default; if (alive) setLoaded({ key, text: m.default }) })
      .catch(() => { if (alive) setLoaded({ key, text: '' }) })
    return () => { alive = false }
  }, [key])
  if (cache[key] != null) return cache[key]
  return loaded.key === key ? loaded.text : null
}

export default function HelpDrawerBody({ lang, number }) {
  const t = useT()
  const text = useWhitepaper(lang)
  const md = useMemo(() => (text == null ? null : extractSection(text, number)), [text, number])
  if (text == null) return <LoadingBlock label={t('app.loading')} />
  return md
    ? <ReactMarkdown remarkPlugins={[remarkGfm]}>{md}</ReactMarkdown>
    : <p className="text-muted-foreground">{t('helpd.none')}</p>
}
