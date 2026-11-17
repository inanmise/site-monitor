import { describe, it, expect, afterEach } from 'vitest'
import { createRoot } from 'react-dom/client'
import { flushSync } from 'react-dom'
import CertHierarchyView from '../components/certmodal/CertHierarchyView.jsx'
import { LangProvider } from '../i18n/index.jsx'
import { fullChainNodes } from './helpers/certHierarchyFixture.js'

/**
 * Seçim yarışı (2026-11-17, React 19 geçişinden sonra main CI'da yakalandı): ağacın "yeni zincir → açılış düğümüne dön"
 * kuralı bir etki (useEffect) idi; etki İLK takılmada da koşar. React 19'da varsayılan öncelikli bir çizimin pasif etkileri
 * ayrı bir görevde koşar — çizim DOM'a yazıldıktan sonra, o görevden önce gelen tıklama, React'in bekleyen etkileri önce
 * boşaltması yüzünden yaprağa geri çevriliyordu (kökü seçen kullanıcı yaprağın ayrıntısını görüyordu).
 *
 * Test act DIŞINDA çizer ve tıklamayı DOM'un yazıldığı anın mikro görevinde (MutationObserver) yapar — pasif etkiler o
 * anda henüz koşmamıştır. Eski kodla her seferinde düşer.
 */
let root = null
let host = null
afterEach(() => {
  if (root) flushSync(() => root.unmount())
  root = null
  host?.remove()
  host = null
  globalThis.IS_REACT_ACT_ENVIRONMENT = true
})

describe('CertHierarchyView — açılışın hemen ardından yapılan seçim', () => {
  it('çizimin hemen ardından (etkilerden önce) seçilen kök seçili kalır', async () => {
    globalThis.IS_REACT_ACT_ENVIRONMENT = false
    host = document.createElement('div')
    document.body.appendChild(host)
    const clicked = new Promise((resolve, reject) => {
      const mo = new MutationObserver(() => {
        const items = host.querySelectorAll('[role="treeitem"]')
        if (items.length !== 3) return
        mo.disconnect()
        try {
          items[0].dispatchEvent(new MouseEvent('click', { bubbles: true }))
          resolve()
        } catch (e) { reject(e) }
      })
      mo.observe(host, { childList: true, subtree: true })
    })
    root = createRoot(host)
    root.render(<LangProvider><CertHierarchyView nodes={fullChainNodes()} /></LangProvider>)
    await clicked
    // Bekleyen tüm görevler (pasif etkiler dahil) koşsun
    for (let i = 0; i < 5; i++) await new Promise((r) => setTimeout(r, 10))
    const selected = [...host.querySelectorAll('[role="treeitem"]')].map((li) => li.getAttribute('aria-selected'))
    expect(selected).toEqual(['true', 'false', 'false'])
    expect(host.querySelector('[data-slot="cert-hierarchy-details-title"]').textContent).toBe('Test Root CA')
  })

  it('yeni zincir gelince seçim yine açılış düğümüne (yaprağa) döner', async () => {
    globalThis.IS_REACT_ACT_ENVIRONMENT = false
    host = document.createElement('div')
    document.body.appendChild(host)
    root = createRoot(host)
    flushSync(() => root.render(<LangProvider><CertHierarchyView nodes={fullChainNodes()} /></LangProvider>))
    for (let i = 0; i < 3; i++) await new Promise((r) => setTimeout(r, 10))
    flushSync(() => host.querySelectorAll('[role="treeitem"]')[0].dispatchEvent(new MouseEvent('click', { bubbles: true })))
    expect(host.querySelectorAll('[role="treeitem"]')[0].getAttribute('aria-selected')).toBe('true')
    flushSync(() => root.render(<LangProvider><CertHierarchyView nodes={fullChainNodes()} /></LangProvider>))   // yeni dizi
    for (let i = 0; i < 3; i++) await new Promise((r) => setTimeout(r, 10))
    const selected = [...host.querySelectorAll('[role="treeitem"]')].map((li) => li.getAttribute('aria-selected'))
    expect(selected).toEqual(['false', 'false', 'true'])
  })
})
