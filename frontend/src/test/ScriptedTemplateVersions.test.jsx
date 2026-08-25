import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../components/ui/CodeEditor.jsx', () => ({
  default: ({ value, textareaId }) => <textarea data-testid="code-editor" id={textareaId} readOnly value={value} />,
}))

const { apiMock } = vi.hoisted(() => {
  const deep = (obj) => new Proxy(obj, {
    get(t, prop) {
      if (prop === 'then' || typeof prop === 'symbol') return undefined
      if (prop in t) return typeof t[prop] === 'object' && t[prop] !== null ? deep(t[prop]) : t[prop]
      t[prop] = vi.fn(() => Promise.resolve({ success: true, data: {} }))
      return t[prop]
    },
  })
  return { apiMock: deep({ monitoring: {}, users: {} }) }
})

vi.mock('../api/client', () => ({
  api: apiMock,
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  formatDate: (s) => s ?? '',
}))

import { api } from '../api/client'
import ScriptedTemplateVersions from '../components/scripted/ScriptedTemplateVersions.jsx'

// Olay etiketleri GERÇEK çeviride karşılığı olan anahtarlardır: bileşen "karşılığı yoksa ham olayı
// bas" kuralını `t(key) === key` ile uyguluyor, o yüzden testin sözlüğü de karşılık vermeli.
const LABELS = {
  'tpl.eventPROMOTE': 'Genele açıldı',
  'tpl.eventEDIT': 'Düzenlendi',
  'tpl.eventCREATE': 'Oluşturuldu',
}
const t = (k, ...a) => LABELS[k] ?? (a.length ? `${k}:${a.join('|')}` : k)
const TPL = { id: 7, name: 'Ödeme akışı', current_version: '1.1.0' }

const VERSIONS = [
  { id: 30, version: '1.1.0', sequence_no: 3, event_type: 'PROMOTE', created_at: '2026-08-20T12:00:00',
    created_by: 'N23456', note: 'Kanal takımından genele açıldı', current: true },
  { id: 20, version: '1.1.0', sequence_no: 2, event_type: 'EDIT', created_at: '2026-08-20T11:00:00',
    created_by: 'N23456', note: null },
  { id: 10, version: '1.0.0', sequence_no: 0, event_type: 'CREATE', created_at: '2026-08-20T10:00:00',
    created_by: 'N23456', note: null },
]

const OLD_SCRIPT = 'http.get(URL);'
const NEW_SCRIPT = "http.get(URL, { timeout: '20s' });"

beforeEach(() => {
  vi.clearAllMocks()
  api.monitoring.getScriptedTemplateVersions.mockResolvedValue({
    success: true, data: { versions: VERSIONS, current_version: '1.1.0' },
  })
  api.monitoring.getScriptedTemplateVersion.mockImplementation((_id, vid) => Promise.resolve({
    success: true,
    data: { id: vid, script: vid === 10 ? OLD_SCRIPT : NEW_SCRIPT, env: [{ name: 'URL' }] },
  }))
})

function draw(props = {}) {
  return render(<ScriptedTemplateVersions t={t} template={TPL} canEdit onClose={() => {}} {...props} />)
}

describe('ScriptedTemplateVersions', () => {
  it('yaşam döngüsü olaylarını da tek zaman çizelgesinde gösterir', async () => {
    draw()
    expect(await screen.findByText('Genele açıldı')).toBeInTheDocument()
    expect(screen.getByText('Düzenlendi')).toBeInTheDocument()
    expect(screen.getByText('Oluşturuldu')).toBeInTheDocument()
    expect(screen.getByText('Kanal takımından genele açıldı')).toBeInTheDocument()
  })

  it('bilinmeyen olay ham kalır — sessiz boşluk bırakmaz', async () => {
    api.monitoring.getScriptedTemplateVersions.mockResolvedValue({
      success: true, data: { versions: [{ ...VERSIONS[0], event_type: 'YENI_OLAY' }] },
    })
    draw()
    expect(await screen.findByText('YENI_OLAY')).toBeInTheDocument()
  })

  it('sürüm seçilince bir öncekiyle farkı gösterilir', async () => {
    draw()
    fireEvent.click(await screen.findByText('Düzenlendi'))
    await waitFor(() => expect(api.monitoring.getScriptedTemplateVersion).toHaveBeenCalledWith(7, 20))
    expect(await screen.findByText(/scripted\.verDiffSummary/)).toBeInTheDocument()
  })

  it('GÜNCEL sürümde geri yükleme düğmesi yoktur', async () => {
    draw()
    fireEvent.click(await screen.findByText('Genele açıldı'))
    await waitFor(() => expect(api.monitoring.getScriptedTemplateVersion).toHaveBeenCalledWith(7, 30))
    expect(screen.queryByText('tpl.versionRestore')).not.toBeInTheDocument()
  })

  it('geri yükleme PUT + restoredFrom ile yapılır (geçmiş EZİLMEZ)', async () => {
    api.monitoring.updateScriptedTemplate.mockResolvedValue({ success: true, data: { id: 7 } })
    const onRestored = vi.fn()
    draw({ onRestored })
    fireEvent.click(await screen.findByText('Oluşturuldu'))
    fireEvent.click(await screen.findByText('tpl.versionRestore'))
    await waitFor(() => expect(api.monitoring.updateScriptedTemplate).toHaveBeenCalled())
    const [id, payload] = api.monitoring.updateScriptedTemplate.mock.calls[0]
    expect(id).toBe(7)
    expect(payload.restoredFrom).toBe('v1.0.0')
    expect(payload.script).toBe(OLD_SCRIPT)
    await waitFor(() => expect(onRestored).toHaveBeenCalledWith(expect.anything(), '1.0.0'))
  })

  it('yazma yetkisi yoksa geri yükleme düğmesi çizilmez', async () => {
    draw({ canEdit: false })
    fireEvent.click(await screen.findByText('Oluşturuldu'))
    await waitFor(() => expect(api.monitoring.getScriptedTemplateVersion).toHaveBeenCalledWith(7, 10))
    expect(screen.queryByText('tpl.versionRestore')).not.toBeInTheDocument()
  })

  it('geçmiş zaman çizelgesi olarak çizilir — Sistem Sağlığı tablosu ÖDÜNÇ ALINMAZ', async () => {
    // Onceden `health-dbtable` kullaniliyordu: o tablo `table-layout: fixed` + kendi genislik
    // siniflariyla calisiyor, buradaki bes sutun onlari hic tasimadigi icin esit bolunuyor ve
    // hucreler dolgusuz/kenarliksiz kaliyordu. jsdom yerlesim hesaplamaz, o yuzden gorunum degil
    // YAPI pinleniyor: tablo yok, her olay bir zaman cizelgesi satiri.
    // ModalShell portal kullaniyor (createPortal → document.body): RTL `container`'i BOS kalir,
    // sorgular document uzerinden yapilmali.
    draw()
    await screen.findByText('Genele açıldı')

    expect(document.querySelector('table')).toBeNull()
    expect(document.querySelectorAll('.sc-vt-row')).toHaveLength(VERSIONS.length)
    // Not KENDI satirinda (tabloda 1/5 sutuna sikisiyordu).
    expect(document.querySelector('.sc-vt-note').textContent).toBe('Kanal takımından genele açıldı')
    // Notu olmayan surumde bos "—" hucresi degil, hic satir yok.
    expect(document.querySelectorAll('.sc-vt-note')).toHaveLength(1)
  })

  it('satır klavyeyle seçilebilir (Enter) — tıklama tek yol değil', async () => {
    draw()
    await screen.findByText('Düzenlendi')

    const rows = document.querySelectorAll('.sc-vt-row')
    expect(rows[0].getAttribute('tabindex')).toBe('0')   // ModalShell odak tuzağı da görsün
    fireEvent.keyDown(rows[1], { key: 'Enter' })

    await waitFor(() => expect(api.monitoring.getScriptedTemplateVersion).toHaveBeenCalledWith(7, 20))
  })

  it('hiç sürüm yoksa boş durum gösterilir', async () => {
    api.monitoring.getScriptedTemplateVersions.mockResolvedValue({ success: true, data: { versions: [] } })
    draw()
    expect(await screen.findByText('tpl.versionNone')).toBeInTheDocument()
  })
})
