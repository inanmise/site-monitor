import { describe, it, expect, afterEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import { useStatusFavicon } from '../hooks/useStatusFavicon.js'

function addIcon(href, type, sizes) {
  const link = document.createElement('link')
  link.rel = 'icon'
  link.setAttribute('href', href)
  if (type) link.setAttribute('type', type)
  if (sizes) link.setAttribute('sizes', sizes)
  document.head.appendChild(link)
  return link
}
const icons = () => [...document.querySelectorAll('link[rel="icon"]')]

describe('useStatusFavicon (dinamik favicon + sekme başlığı)', () => {
  afterEach(() => {
    document.querySelectorAll('link[rel="icon"]').forEach((l) => l.remove())
    document.title = ''
  })

  it('ok: index.html\'deki statik favicon seti (svg + ico) EZİLMEZ — favicon-optimize işaret kalır (BRAND.md §7)', () => {
    addIcon('/favicon.ico', null, '32x32')
    addIcon('/favicon.svg', 'image/svg+xml')
    renderHook(() => useStatusFavicon('ok'))
    expect(icons().map((l) => l.getAttribute('href'))).toEqual(['/favicon.ico', '/favicon.svg'])
    expect(icons()[1].getAttribute('type')).toBe('image/svg+xml')
  })

  it('durum varyantı her icon bağlantısını /brand/logo-{status}-32.png yapar; ok\'a dönünce asıl değerler geri gelir', () => {
    addIcon('/favicon.ico', null, '32x32')
    addIcon('/favicon.svg', 'image/svg+xml')
    const { rerender } = renderHook(({ s }) => useStatusFavicon(s), { initialProps: { s: 'warning' } })
    expect(icons().map((l) => l.getAttribute('href'))).toEqual(['/brand/logo-warning-32.png', '/brand/logo-warning-32.png'])
    expect(icons().every((l) => l.getAttribute('type') === 'image/png')).toBe(true)
    rerender({ s: 'critical' })
    expect(icons()[0].getAttribute('href')).toBe('/brand/logo-critical-32.png')
    rerender({ s: 'ok' })
    expect(icons().map((l) => l.getAttribute('href'))).toEqual(['/favicon.ico', '/favicon.svg'])
    expect(icons()[0].hasAttribute('type')).toBe(false)
    expect(icons()[1].getAttribute('type')).toBe('image/svg+xml')
    expect(icons().some((l) => l.hasAttribute('data-sm-href'))).toBe(false)
  })

  it('bağlantı yoksa durum için yaratılır, ok\'a dönünce kaldırılır', () => {
    const { rerender } = renderHook(({ s }) => useStatusFavicon(s), { initialProps: { s: 'muted' } })
    expect(icons().map((l) => l.getAttribute('href'))).toEqual(['/brand/logo-muted-32.png'])
    rerender({ s: 'ok' })
    expect(icons()).toHaveLength(0)
  })

  it('critical → title "(!) " öneki alır; normale dönünce önek kalkar (taban title korunur)', () => {
    document.title = 'SiteMonitor'
    const { rerender } = renderHook(({ s }) => useStatusFavicon(s), { initialProps: { s: 'ok' } })
    expect(document.title).toBe('SiteMonitor')
    rerender({ s: 'critical' })
    expect(document.title).toBe('(!) SiteMonitor')
    rerender({ s: 'critical' })
    expect(document.title).toBe('(!) SiteMonitor')   // idempotent — önek birikmez
    rerender({ s: 'ok' })
    expect(document.title).toBe('SiteMonitor')
  })
})
