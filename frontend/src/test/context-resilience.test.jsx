import { describe, it, expect, vi } from 'vitest'
import { render as rawRender, screen } from '@testing-library/react'

/**
 * Uygulama-geneli context'lerin HMR çift-modül dayanıklılığı (2026-09-26).
 *
 * Olay: çok ajanlı geliştirme gününde Vite HMR `Toast.jsx` ve `sidebar.jsx`'i iki ayrı modül örneği olarak yükledi
 * (yığında aynı dosyanın iki farklı `?t=` damgası): ağaçtaki Provider ESKİ örnekten, bileşendeki hook YENİ örnekten
 * gelince iki ayrı context oluştu → "useToast must be used within ToastProvider" / "useSidebar must be used within a
 * SidebarProvider" ile çökme. Kilit: context nesnesi globalThis'e sabit (`i18n-context-resilience.test.jsx` deseni) —
 * A örneğinin Provider'ı B örneğinin hook'unu beslemeli. Bu dosya bilinçli olarak test-utils render()'ını KULLANMAZ.
 */
const CASES = [
  { name: 'Toast', path: '../components/ui/Toast.jsx', provider: 'ToastProvider', hook: 'useToast', probe: (v) => typeof v?.success },
  { name: 'Dialog', path: '../components/ui/Dialog.jsx', provider: 'DialogProvider', hook: 'useDialog', probe: (v) => typeof v?.showConfirm },
  { name: 'Permissions', path: '../contexts/PermissionsProvider.jsx', provider: 'PermissionsProvider', hook: 'usePermissions', probe: (v) => typeof v?.canView },
  { name: 'Branding', path: '../contexts/BrandingProvider.jsx', provider: 'BrandingProvider', hook: 'useBranding', probe: (v) => typeof v?.get },
  { name: 'Sidebar', path: '../components/shadcn/sidebar.jsx', provider: 'SidebarProvider', hook: 'useSidebar', probe: (v) => typeof v?.setOpen },
]

describe('uygulama-geneli context\'ler HMR çift-modül senaryosunda AYNI nesneyi paylaşır', () => {
  for (const c of CASES) {
    it(`${c.name}: A örneğinin Provider'ı B örneğinin ${c.hook}'unu besler`, async () => {
      vi.resetModules()
      const a = await import(/* @vite-ignore */ c.path)
      vi.resetModules()
      const b = await import(/* @vite-ignore */ c.path)
      expect(a[c.hook], 'resetModules sonrası ayrı modül örneği bekleniyor').not.toBe(b[c.hook])
      function BProbe() {
        const v = b[c.hook]()
        return <div data-testid={`probe-${c.name}`}>{c.probe(v)}</div>
      }
      const A = a[c.provider]
      expect(() => rawRender(<A><BProbe /></A>)).not.toThrow()
      expect(screen.getByTestId(`probe-${c.name}`).textContent).toBe('function')
    })
  }
})
