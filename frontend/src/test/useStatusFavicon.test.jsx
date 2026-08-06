import { describe, it, expect, afterEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import { useStatusFavicon } from '../hooks/useStatusFavicon.js'

describe('useStatusFavicon (dinamik favicon + sekme başlığı)', () => {
  afterEach(() => {
    document.querySelectorAll('link[rel="icon"]').forEach((l) => l.remove())
    document.title = ''
  })

  it('status değişince link[rel=icon] href /brand/logo-{status}-32.png olur (link yoksa yaratılır)', () => {
    const { rerender } = renderHook(({ s }) => useStatusFavicon(s), { initialProps: { s: 'ok' } })
    expect(document.querySelector('link[rel="icon"]').getAttribute('href')).toBe('/brand/logo-ok-32.png')
    rerender({ s: 'warning' })
    expect(document.querySelector('link[rel="icon"]').getAttribute('href')).toBe('/brand/logo-warning-32.png')
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
