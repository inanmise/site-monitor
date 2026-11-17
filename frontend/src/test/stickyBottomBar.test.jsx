import { describe, it, expect, afterEach } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { StrictMode, useState } from 'react'
import { render, act, cleanup } from '@testing-library/react'
import { useStickyBottomBar, stickyBottomBarCount, STICKY_BAR_ATTR } from '../hooks/useStickyBottomBar.js'

/**
 * Yapışkan alt çubuk işareti (2026-11-17, performans): `body:has(.app-main .sticky.bottom-0)` her DOM değişiminde bütün
 * sayfanın stilini yeniden hesaplatıyordu (SQL Playground: tuş başına ~1480 öğe, 30 tuşta ~567 ms). Yerine `body`'ye
 * `data-sticky-bar` konur; CSS öznitelik seçicisi yalnız takma / sökme anında çalışır.
 *  - Kanca: `.app-main` içindeki çubuk sayılır, dışındaki (portal) sayılmaz; birden çok çubuk sayaçla; `active` izlenir;
 *    StrictMode sayacı bozmaz; ileri ref de değeri alır.
 *  - KAPI: CSS'te `:has(` yok; sayfa düzeyindeki her `sticky bottom-0` çubuğu kancanın ref'ini alır.
 */
const SRC = path.resolve(__dirname, '..')
const body = () => document.body.hasAttribute(STICKY_BAR_ATTR)

function Bar({ active = true, fwd }) {
  const ref = useStickyBottomBar(fwd, active)
  return <div ref={ref} className="sticky bottom-0" data-testid="bar" />
}

function Page({ children }) {
  return <main className="app-main">{children}</main>
}

afterEach(() => { cleanup() })

describe('useStickyBottomBar', () => {
  it('.app-main içindeki çubuk takılınca body işaretlenir, sökülünce kalkar', () => {
    const { unmount } = render(<Page><Bar /></Page>)
    expect(body()).toBe(true)
    expect(stickyBottomBarCount()).toBe(1)
    unmount()
    expect(body()).toBe(false)
    expect(stickyBottomBarCount()).toBe(0)
  })

  it('.app-main dışındaki (diyalog / açılır pencere) çubuk sayılmaz', () => {
    render(<Bar />)
    expect(body()).toBe(false)
    expect(stickyBottomBarCount()).toBe(0)
  })

  it('iki çubuk: biri sökülünce işaret kalır, ikisi de sökülünce kalkar', () => {
    function Two() {
      const [a, setA] = useState(true)
      const [b, setB] = useState(true)
      Two.setA = setA
      Two.setB = setB
      return <Page>{a && <Bar />}{b && <Bar />}</Page>
    }
    render(<Two />)
    expect(stickyBottomBarCount()).toBe(2)
    act(() => Two.setA(false))
    expect(body()).toBe(true)
    expect(stickyBottomBarCount()).toBe(1)
    act(() => Two.setB(false))
    expect(body()).toBe(false)
    expect(stickyBottomBarCount()).toBe(0)
  })

  it('active (yalnız kirliyken yapışkan kaydet çubuğu) izlenir', () => {
    const { rerender } = render(<Page><Bar active={false} /></Page>)
    expect(body()).toBe(false)
    rerender(<Page><Bar active /></Page>)
    expect(body()).toBe(true)
    expect(stickyBottomBarCount()).toBe(1)
    rerender(<Page><Bar active /></Page>)
    expect(stickyBottomBarCount()).toBe(1)
    rerender(<Page><Bar active={false} /></Page>)
    expect(body()).toBe(false)
    expect(stickyBottomBarCount()).toBe(0)
  })

  it('StrictMode (ref ve efekt söküp takma) sayacı bozmaz', () => {
    const { unmount } = render(<StrictMode><Page><Bar /><Bar /></Page></StrictMode>)
    expect(stickyBottomBarCount()).toBe(2)
    unmount()
    expect(stickyBottomBarCount()).toBe(0)
    expect(body()).toBe(false)
  })

  it('ileri ref (callback ya da nesne) öğeyi alır, sökülünce null', () => {
    const seen = []
    const obj = { current: null }
    const { unmount } = render(<Page><Bar fwd={(el) => seen.push(el)} /><Bar fwd={obj} /></Page>)
    expect(seen.at(-1)).toBeInstanceOf(HTMLElement)
    expect(obj.current).toBeInstanceOf(HTMLElement)
    unmount()
    expect(seen.at(-1)).toBe(null)
    expect(obj.current).toBe(null)
  })
})

function walk(dir, acc = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory()) {
      if (e.name === 'test' || e.name === 'assets' || e.name === 'shadcn') continue
      walk(path.join(dir, e.name), acc)
    } else if (/\.(jsx?|css)$/.test(e.name)) acc.push(path.join(dir, e.name))
  }
  return acc
}

/** `sticky bottom-0` yalnız portal içinde (diyalog / açılır pencere) çizilen ya da yorumda geçen dosyalar. */
const PORTAL_ONLY = new Set([
  'components/admin/HeartbeatHistoryModal.jsx',
  'components/admin/alerts/NocPersonPicker.jsx',
  'components/admin/permissions/PendingChanges.jsx',
])

describe('KAPI: yapışkan alt çubuk işareti', () => {
  const files = walk(SRC)

  it('CSS dosyalarında :has( yok (body:has her DOM değişiminde bütün sayfayı yeniden hesaplatıyordu)', () => {
    const bad = files.filter((f) => f.endsWith('.css') && fs.readFileSync(f, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '').includes(':has('))
    expect(bad.map((f) => path.relative(SRC, f))).toEqual([])
  })

  it('sayfa düzeyindeki her `sticky bottom-0` çubuğu useStickyBottomBar ref\'ini alır', () => {
    const missing = []
    for (const f of files) {
      if (!/\.jsx?$/.test(f)) continue
      const rel = path.relative(SRC, f).split(path.sep).join('/')
      if (PORTAL_ONLY.has(rel)) continue
      const src = fs.readFileSync(f, 'utf8')
      if (!/className=[^>]*\bsticky bottom-0\b|'sticky bottom-0/.test(src)) continue
      if (!src.includes('useStickyBottomBar(')) missing.push(rel)
    }
    expect(missing).toEqual([])
  })
})
