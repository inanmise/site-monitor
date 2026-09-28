import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * KAPI: `vi.mock('<göreli yol>')` GERÇEK bir modülü göstermeli.
 *
 * <p><b>Neden kapı (2026-09-28c, C3).</b> Üç test (`CertificateCardExtras` ×2, `SmtpLogView`) var olmayan
 * `contexts/TeamDirectoryProvider.jsx` yolunu taklit ediyordu; gerçek modül `components/ui/TeamDirectory.jsx`. Vitest
 * var olmayan bir yolun taklidini HATA VERMEDEN kaydeder, bileşen gerçek modülü yükler → test "taklit edilmiş boş dizin"
 * sanarak gerçek sağlayıcıyla koşar ve yeşil kalır. Sınıf ancak kaynak taramasıyla yakalanır: her göreli (`./`, `../`)
 * ya da takma adlı (`@/`) `vi.mock` / `vi.doMock` yolu, test dosyasına göre çözülüp diskte (uzantı + index denemeleriyle)
 * aranır. Paket adları (`recharts`) kapsam dışı.
 */

const SRC = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const MOCK = /vi\.(?:mock|doMock)\(\s*(['"`])([^'"`]+)\1/g
const EXTS = ['', '.js', '.jsx', '.ts', '.tsx', '/index.js', '/index.jsx']

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) { if (e.name !== 'node_modules') walk(p, out) } else if (/\.test\.(jsx?|tsx?)$/.test(e.name)) out.push(p)
  }
  return out
}

export function unresolvedMocks(file, src) {
  const bad = []
  for (const m of src.matchAll(MOCK)) {
    const spec = m[2].replace(/[?#].*$/, '')   // Vite sorgu ekleri (?raw) yol değildir
    let base
    if (spec.startsWith('./') || spec.startsWith('../')) base = path.resolve(path.dirname(file), spec)
    else if (spec.startsWith('@/')) base = path.join(SRC, spec.slice(2))
    else continue
    if (!EXTS.some((x) => fs.existsSync(base + x) && fs.statSync(base + x).isFile())) bad.push(spec)
  }
  return bad
}

describe('vi.mock yolları gerçek modüle çözülür', () => {
  it('çözülemeyen taklit yolu yok (sessizce UYGULANMAYAN taklit = yanlış şeyi sınayan test)', () => {
    const offenders = []
    for (const f of walk(SRC).filter((p) => path.basename(p) !== 'viMockPaths.test.js')) {   // kendi öz-sınama dizeleri hariç
      for (const spec of unresolvedMocks(f, fs.readFileSync(f, 'utf8'))) offenders.push(`${path.relative(SRC, f)} → ${spec}`)
    }
    expect(offenders).toEqual([])
  })

  it('kapının kendisi ısırır: var olmayan yol yakalanır, gerçek yol ve paket adı geçer', () => {
    const f = path.join(SRC, 'test', 'x.test.jsx')
    expect(unresolvedMocks(f, "vi.mock('../contexts/TeamDirectoryProvider.jsx', () => ({}))")).toEqual(['../contexts/TeamDirectoryProvider.jsx'])
    expect(unresolvedMocks(f, "vi.mock('../components/ui/TeamDirectory.jsx', () => ({}))")).toEqual([])
    expect(unresolvedMocks(f, "vi.mock('../api/client', () => ({}))")).toEqual([])
    expect(unresolvedMocks(f, "vi.mock('@/components/shadcn/button', () => ({}))")).toEqual([])
    expect(unresolvedMocks(f, "vi.mock('recharts', () => ({}))")).toEqual([])
    expect(unresolvedMocks(f, "vi.mock('../assets/whitepaper.md?raw', () => ({}))")).toEqual([])   // Vite sorgu eki
  })
})
