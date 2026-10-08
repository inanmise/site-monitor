import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

/**
 * KAPI (2026-10-09, açılış paketi küçültme): ağır ve seyrek kullanılan parçalar İHTİYAÇ ANINDA yüklenir. Kaynak
 * düzeyinde denetlenir (jsdom paket üretmez) — bir statik `import` geri gelirse parça sessizce açılış paketine ya da
 * bir ekranın ön-yükleme grafiğine döner. Ölçüm (vite build): açılış 2818 → 2207 KB ham (850 → 647 KB gzip);
 * sertifika penceresinin ön-yükleme grafiği ~2 MB → ~0,3 MB; izleme sayfaları ~2 MB → ~0,8 MB.
 */
const SRC = path.resolve(__dirname, '..')
const rel = (f) => path.relative(SRC, f).replace(/\\/g, '/')

function walk(dir, acc = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory()) { if (e.name !== 'test' && e.name !== 'assets') walk(path.join(dir, e.name), acc) } else if (/\.(jsx?|mjs)$/.test(e.name)) acc.push(path.join(dir, e.name))
  }
  return acc
}
const FILES = walk(SRC).map((f) => ({ rel: rel(f), src: fs.readFileSync(f, 'utf8') }))
const read = (r) => FILES.find((x) => x.rel === r)?.src ?? ''
const staticImports = (src) => [...src.matchAll(/^\s*import\s+(?:[^'"]*?\s+from\s+)?['"]([^'"]+)['"]/gm)].map((m) => m[1])
const dynamicImports = (src) => [...src.matchAll(/import\(\s*['"]([^'"]+)['"]\s*\)/g)].map((m) => m[1])
const hasStatic = (r, re) => staticImports(read(r)).some((s) => re.test(s))
const hasDynamic = (r, re) => dynamicImports(read(r)).some((s) => re.test(s))

describe('tembel parça kapısı — açılış paketi ve ekran ön-yükleme grafikleri', () => {
  it('Markdown editörü (@uiw/react-md-editor) yalnız mdEditor/ parçalarında; onlar yalnız import() ile yüklenir', () => {
    const users = FILES.filter(({ src }) => [...staticImports(src), ...dynamicImports(src)].some((s) => s.startsWith('@uiw/react-md-editor')))
      .map((x) => x.rel).sort()
    expect(users).toEqual(['components/ui/mdEditor/MdEditorHighlight.jsx', 'components/ui/mdEditor/MdEditorPlain.jsx'])
    // Prism'in TÜM dil paketini (~600 KB) çeken varsayılan giriş kullanılmaz
    expect(read('components/ui/mdEditor/MdEditorPlain.jsx')).toMatch(/from '@uiw\/react-md-editor\/nohighlight'/)
    expect(read('components/ui/mdEditor/MdEditorHighlight.jsx')).toMatch(/from '@uiw\/react-md-editor\/common'/)
    const staticUsers = FILES.filter(({ src }) => staticImports(src).some((s) => /MdEditor(Plain|Highlight)(\.jsx)?$/.test(s))).map((x) => x.rel)
    expect(staticUsers).toEqual([])
    expect(hasDynamic('components/ui/mdEditor/LazyMdEditor.jsx', /MdEditorPlain\.jsx$/)).toBe(true)
    expect(hasDynamic('components/ui/mdEditor/LazyMdEditor.jsx', /MdEditorHighlight\.jsx$/)).toBe(true)
  })

  it('Yardım çekmecesi: markdown çizici ve kılavuzlar açılış parçasında değil; yalnız etkin dilin kılavuzu import() ile', () => {
    const drawer = 'components/HelpDrawer.jsx'
    expect(hasStatic(drawer, /^react-markdown$|^remark-gfm$|\?raw$/)).toBe(false)
    expect(hasDynamic(drawer, /help\/HelpDrawerBody\.jsx$/)).toBe(true)
    const body = 'components/help/HelpDrawerBody.jsx'
    expect(hasStatic(body, /\?raw$/)).toBe(false)
    expect(dynamicImports(read(body)).filter((s) => s.endsWith('?raw')).sort())
      .toEqual(['../../assets/whitepaper.en.md?raw', '../../assets/whitepaper.md?raw'])
  })

  it('Alarm Geçmişi gürültü panelini (grafik kitaplığı) tembel yükler', () => {
    expect(hasStatic('components/admin/AlertHistory.jsx', /AlertNoisePanel(\.jsx)?$/)).toBe(false)
    expect(hasDynamic('components/admin/AlertHistory.jsx', /AlertNoisePanel\.jsx$/)).toBe(true)
  })

  it('Sertifika penceresi: Alarm geçmişi, tanılama ve envanter sekmesi yalnız kendi sekmesinde/düğmesinde yüklenir', () => {
    const modal = 'components/CertificateModal.jsx'
    expect(hasStatic(modal, /admin\/AlertHistory(\.jsx)?$|admin\/DiagnosticsModal(\.jsx)?$|inventory\/InventoryDetails(\.jsx)?$/)).toBe(false)
    for (const re of [/admin\/AlertHistory$/, /admin\/DiagnosticsModal\.jsx$/, /inventory\/InventoryDetails\.jsx$/]) {
      expect(hasDynamic(modal, re), String(re)).toBe(true)
    }
  })

  it('Nav: komut paleti başlatıcıyla ilk kullanımda; kısayol listesi, sürüm penceresi, bakım uzatma penceresi tembel', () => {
    expect(hasStatic('components/Nav.jsx', /\/CommandPalette(\.jsx)?$/)).toBe(false)
    expect(hasDynamic('components/CommandPaletteLauncher.jsx', /\/CommandPalette\.jsx$/)).toBe(true)
    expect(hasStatic('components/KeyboardShortcuts.jsx', /ModalShell|ShortcutsDialog/)).toBe(false)
    expect(hasStatic('components/VersionChip.jsx', /VersionPopover/)).toBe(false)
    expect(hasStatic('components/maintenance/SystemMaintenanceLayer.jsx', /SysMaintExtendDialog/)).toBe(false)
  })

  it('Olaylar sayfasında sayfa düzeyinde saniyelik saat yok — süre kendi yaprağında (LiveDuration)', () => {
    const page = read('components/IncidentsPage.jsx')
    expect(page).not.toMatch(/setNowMs/)
    expect(page).not.toMatch(/setInterval\(/)
    for (const r of ['components/incidents/IncidentCard.jsx', 'components/incidents/IncidentsTable.jsx']) {
      expect(hasStatic(r, /\/LiveDuration\.jsx$/), r).toBe(true)
      expect(read(r)).not.toMatch(/nowMs/)
    }
  })
})
