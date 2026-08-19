import { describe, it, expect, vi, beforeEach } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { render, cleanup } from './test-utils.jsx'

/**
 * BEKÇİ: App.jsx'teki HER lazy sekme, mount edildiğinde çökmeden render olmalı.
 *
 * Gerekçe: 2026-08-19'daki `ProgressBar is not defined` olayında kırık kod 27 lazy
 * bileşenden birinin içindeydi. Lazy chunk yalnız o sekme AÇILDIĞINDA çalıştığı için
 * ne build, ne testler, ne de manuel QA ona dokundu — hata doğrudan üretimde göründü.
 *
 * Sekme listesi App.jsx'ten TÜRETİLİR, elle yazılmaz: yeni bir lazy sekme eklendiği gün
 * kendiliğinden kapsama girer (liste elle tutulsaydı ilk unutulan yine kapsam dışı kalırdı).
 *
 * SINIR — bilinçli ve önemli: bu test yalnız İLK RENDER YOLUNU çalıştırır. Asıl olaydaki
 * `<ProgressBar>` kapalı bir akordiyonun ardındaydı, dolayısıyla bu smoke onu TEK BAŞINA
 * yakalayamazdı; onu ESLint (react/jsx-no-undef) ve jsx-undefined-guard yakalar.
 * Buranın değeri farklı bir sınıfta: modül gövdesinde patlayan kod, bozuk hook sırası,
 * eksik sağlayıcı, kırık import yolu.
 */

/** Her api.* çağrısına başarılı boş yanıt döndüren özyinelemeli vekil. */
function apiProxy() {
  const call = () => Promise.resolve({ success: true, data: [] })
  return new Proxy(call, {
    get(_target, prop) {
      // Promise gibi davranmasın: await edilirse sonsuz zincire girer.
      if (prop === 'then' || prop === 'catch' || prop === 'finally') return undefined
      if (prop === Symbol.toPrimitive || typeof prop === 'symbol') return undefined
      return apiProxy()
    },
    apply: () => Promise.resolve({ success: true, data: [] }),
  })
}

vi.mock('../api/client', () => ({
  api: apiProxy(),
  formatDate:     (s) => s ?? '',
  formatDateSec:  (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
}))

/** App.jsx'teki `lazy(() => import('./x/Y'))` yollarını çıkarır. */
function lazyPathsFromApp() {
  const appPath = path.resolve(__dirname, '..', 'App.jsx')
  const src = fs.readFileSync(appPath, 'utf8')
  const out = []
  for (const m of src.matchAll(/const\s+(\w+)\s*=\s*lazy\(\(\)\s*=>\s*import\('([^']+)'\)\)/g)) {
    out.push({ name: m[1], spec: m[2] })
  }
  return out
}

// Vite'ın statik analizi değişken yollu import()'u çözemez → glob ile önceden toplanır.
const MODULES = {
  ...import.meta.glob('../components/**/*.jsx'),
  ...import.meta.glob('../pages/**/*.jsx'),
}

/** './components/admin/SystemHealth' → '../components/admin/SystemHealth.jsx' */
function toGlobKey(spec) {
  const rel = spec.replace(/^\.\//, '../')
  return MODULES[`${rel}.jsx`] ? `${rel}.jsx` : null
}

const LAZY = lazyPathsFromApp()

/** Mount edilemeyen bileşenler — her biri gerekçeli. Boş kalması hedeftir. */
const EXEMPT = new Set([])

beforeEach(() => { cleanup() })

describe('lazy sekme mount bekçisi', () => {
  it('App.jsx\'ten lazy sekme listesi çıkarılabiliyor', () => {
    // Regex bozulursa liste boşalır ve test sessizce yeşil kalırdı — bu assert onu engeller.
    expect(LAZY.length).toBeGreaterThanOrEqual(20)
  })

  it('her lazy sekmenin modülü glob ile çözülebiliyor', () => {
    const missing = LAZY.filter(l => !toGlobKey(l.spec)).map(l => `${l.name} → ${l.spec}`)
    expect(missing, `glob deseni bu modülleri kapsamıyor:\n${missing.join('\n')}`).toEqual([])
  })

  it.each(LAZY.map(l => [l.name, l.spec]))('%s mount edilince çökmez', async (name, spec) => {
    if (EXEMPT.has(name)) return

    const mod = await MODULES[toGlobKey(spec)]()
    const Component = mod.default
    expect(Component, `${name} default export etmiyor`).toBeTypeOf('function')

    // Konsol gürültüsünü bastır ama HATAYI YUTMA: render throw ederse test kırılır.
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      render(<Component systemRole="ADMIN" globalAdmin />)
    } finally {
      spy.mockRestore()
    }
  })
})
