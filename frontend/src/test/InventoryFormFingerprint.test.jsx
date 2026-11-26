import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import {
  normalizeFingerprint, fingerprintProblem, fingerprintChanged, expectedFingerprintPatch, ugTeamPatch, FP_RAW_MAX,
} from '../components/inventory/expectedFingerprint.js'

/**
 * Envanter formu — beklenen parmak izi alanı ve UG takımı (H paketi 2026-11-26, sertifika B2 + B6).
 *   - "Beklenen parmak izi" görünür, düzenlenebilir, temizlenebilir; biçim (SHA-256, 64 onaltılık) yalnız DEĞİŞEN değerde
 *     denetlenir; DEĞİŞTİYSE boş → null temizler, dolu → normalize; düzenlemede DEĞİŞMEDİYSE saklı ham değer +
 *     `expected_fingerprint_unchanged: true` gider (inceleme bulgusu 1 — sürüm geçişinde eski pod anahtar yoksa siliyordu);
 *   - sunucunun 400 `expected_fingerprint` alan hatası alanın altında;
 *   - manuel (dosyadan yüklenen) kayıtta alan yok, anahtar hiç gitmez;
 *   - UG formda değişmez: düzenlemede saklı değer + `ug_team_id_unchanged: true`, oluşturmada hiçbir şey; UG salt okunur satır.
 */
const confirmMock = vi.fn(() => Promise.resolve(true))
const toastMock = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), dismiss: vi.fn() }))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({
    admin: {
      addInventory:    vi.fn().mockResolvedValue({ success: true }),
      updateInventory: vi.fn().mockResolvedValue({ success: true }),
      getTeams:        vi.fn().mockResolvedValue({ success: true, data: [{ id: 1, name: 'SY-Takım A' }] }),
    },
    monitoring: { listGroups: vi.fn().mockResolvedValue({ success: true, data: [] }) },
  }),
}))
vi.mock('../components/ui/Dialog.jsx', () => ({
  useDialog: () => ({ showConfirm: confirmMock }),
  DialogProvider: ({ children }) => children,
}))
vi.mock('@uiw/react-md-editor/common', () => ({
  default: ({ value }) => <textarea readOnly value={value ?? ''} />,
  commands: { divider: { name: 'divider' }, codeEdit: { name: 'edit' }, codePreview: { name: 'preview' }, fullscreen: { name: 'fullscreen' } },
}))

import { api } from '../api/client'
import InventoryFormModal from '../components/inventory/InventoryFormModal.jsx'

const TEAMS = [{ id: 1, name: 'SY-Takım A' }, { id: 2, name: 'SY-Takım B' }]
const HEX = 'ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12'
const COLONS = HEX.match(/../g).join(':')
const RECORD = {
  id: 42, domain: 'a.example.com', port: 443, active: true, team_id: 1, group_name: 'Prod', tags: 'prod', tier: 2,
  expected_fingerprint: HEX.toUpperCase(), expected_subject: null,
}
const saveBtn = () => screen.getByRole('button', { name: /^Kaydet$|^Save$/i })
const fpInput = () => document.querySelector('input[data-slot="inv-form-fp"]')
const fpField = () => document.querySelector('[data-field="expected_fingerprint"]')
const FP_ERROR = /SHA-256 olmalı|must be SHA-256/

describe('expectedFingerprint (saf — sunucu kuralının aynası)', () => {
  it('normalize: : boşluk - sekme atılır, büyük harf; 256 karakterde durur', () => {
    expect(normalizeFingerprint('ab:cd ef-01\t23')).toBe('ABCDEF0123')
    expect(normalizeFingerprint(null)).toBe('')
    expect(normalizeFingerprint('a'.repeat(400)).length).toBe(256)
  })
  it('biçim: boş geçerli; 64 hex geçerli; kısa / onaltılık dışı / çok uzun hatalı', () => {
    expect(fingerprintProblem('')).toBeNull()
    expect(fingerprintProblem('   ')).toBeNull()
    expect(fingerprintProblem(HEX)).toBeNull()
    expect(fingerprintProblem(COLONS)).toBeNull()
    expect(fingerprintProblem('AA:BB:CC')).toBe('format')
    expect(fingerprintProblem(HEX.slice(0, 40))).toBe('format')       // SHA-1 boyu
    expect(fingerprintProblem(HEX.slice(0, 63) + 'G')).toBe('format')
    expect(fingerprintProblem(HEX + '0')).toBe('format')
    expect(fingerprintProblem('a'.repeat(FP_RAW_MAX + 1))).toBe('tooLong')
  })
  it('değişti mi / gövde parçası: aynı değer başka yazımla değişmemiştir; boşaltma null; yeni değer normalize', () => {
    expect(fingerprintChanged(COLONS, HEX.toUpperCase())).toBe(false)
    expect(fingerprintChanged('', null)).toBe(false)
    // Oluşturma (edit yok): değişmeyen → anahtar yok, bayrak yok
    expect(expectedFingerprintPatch(COLONS, HEX.toUpperCase())).toEqual({})
    expect(expectedFingerprintPatch('', '')).toEqual({})
    // Değişen değer: bayrak YOK (düzenleme de olsa)
    expect(expectedFingerprintPatch('', HEX)).toEqual({ expected_fingerprint: null })
    expect(expectedFingerprintPatch('  ', HEX, { edit: true })).toEqual({ expected_fingerprint: null })
    expect(expectedFingerprintPatch(COLONS, '', { edit: true })).toEqual({ expected_fingerprint: HEX.toUpperCase() })
    // Düzenlemede değişmeyen: saklı HAM değer (normalize edilmez) + bayrak; boş saklı → null + bayrak
    expect(expectedFingerprintPatch(COLONS, HEX.toUpperCase(), { edit: true }))
      .toEqual({ expected_fingerprint: HEX.toUpperCase(), expected_fingerprint_unchanged: true })
    expect(expectedFingerprintPatch('AA:BB:CC', 'aa:bb:cc', { edit: true }))
      .toEqual({ expected_fingerprint: 'aa:bb:cc', expected_fingerprint_unchanged: true })
    expect(expectedFingerprintPatch('', null, { edit: true })).toEqual({ expected_fingerprint: null, expected_fingerprint_unchanged: true })
    expect(expectedFingerprintPatch(' ', '  ', { edit: true })).toEqual({ expected_fingerprint: null, expected_fingerprint_unchanged: true })
  })
  it('UG parçası: düzenlemede saklı değer + bayrak (yok → null); oluşturmada boş', () => {
    expect(ugTeamPatch(9, { edit: true })).toEqual({ ug_team_id: 9, ug_team_id_unchanged: true })
    expect(ugTeamPatch(undefined, { edit: true })).toEqual({ ug_team_id: null, ug_team_id_unchanged: true })
    expect(ugTeamPatch(9)).toEqual({})
  })
})

describe('InventoryFormModal — beklenen parmak izi', () => {
  beforeEach(() => { vi.clearAllMocks(); confirmMock.mockResolvedValue(true) })

  it('düzenle: alan kayıttaki değerle görünür; dokunulmazsa saklı değer + bayrak gider', async () => {
    render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    expect(fpInput()).not.toBeNull()
    expect(fpInput().value).toBe(HEX.toUpperCase())
    expect(screen.getByRole('textbox', { name: /Beklenen Parmak İzi|Expected Fingerprint/ })).toBe(fpInput())
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.admin.updateInventory).toHaveBeenCalled())
    const body = api.admin.updateInventory.mock.calls[0][1]
    expect(body.expected_fingerprint).toBe(HEX.toUpperCase())
    expect(body.expected_fingerprint_unchanged).toBe(true)
    expect(body.ug_team_id).toBeNull()
    expect(body.ug_team_id_unchanged).toBe(true)
  })

  it('düzenle: "Temizle" alanı boşaltır → gövdede expected_fingerprint: null; temizle düğmesi boşken çizilmez', async () => {
    render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    const clear = screen.getByRole('button', { name: /Beklenen parmak izini temizle|Clear the expected fingerprint/ })
    expect(clear.className).toMatch(/pointer-coarse:h-10/)
    fireEvent.click(clear)
    expect(fpInput().value).toBe('')
    expect(document.querySelector('[data-slot="inv-form-fp-clear"]')).toBeNull()
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.admin.updateInventory).toHaveBeenCalled())
    expect(api.admin.updateInventory.mock.calls[0][1].expected_fingerprint).toBeNull()
    // Değişen (temizlenen) alan bayraksız gider — sunucu null'ı uygular
    expect('expected_fingerprint_unchanged' in api.admin.updateInventory.mock.calls[0][1]).toBe(false)
  })

  it('düzenle: geçersiz yeni değer KAYDETMEZ, hata alanın altında (data-field) + bantta; aria-invalid', async () => {
    render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    fireEvent.change(fpInput(), { target: { value: 'AA:BB:CC' } })
    fireEvent.click(saveBtn())
    expect((await screen.findAllByText(FP_ERROR)).length).toBe(2)
    expect(fpField().textContent).toMatch(FP_ERROR)
    expect(fpInput()).toHaveAttribute('aria-invalid', 'true')
    expect(api.admin.updateInventory).not.toHaveBeenCalled()
  })

  it('düzenle: hata alan düzenlenince kalkar; iki nokta / küçük harfli geçerli değer normalize gider', async () => {
    render(<InventoryFormModal mode="edit" record={{ ...RECORD, expected_fingerprint: null }} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    fireEvent.change(fpInput(), { target: { value: 'zz' } })
    fireEvent.blur(fpInput())
    expect(fpField().textContent).toMatch(FP_ERROR)
    fireEvent.change(fpInput(), { target: { value: COLONS } })
    expect(fpField().textContent).not.toMatch(FP_ERROR)
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.admin.updateInventory).toHaveBeenCalled())
    expect(api.admin.updateInventory.mock.calls[0][1].expected_fingerprint).toBe(HEX.toUpperCase())
    expect('expected_fingerprint_unchanged' in api.admin.updateInventory.mock.calls[0][1]).toBe(false)
  })

  it('düzenle: kayıttaki ESKİ bozuk değer (SHA-1 boyu) değişmedikçe kaydı engellemez', async () => {
    render(<InventoryFormModal mode="edit" record={{ ...RECORD, expected_fingerprint: 'AA:BB:CC' }} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    fireEvent.blur(fpInput())
    expect(fpField().textContent).not.toMatch(FP_ERROR)
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.admin.updateInventory).toHaveBeenCalled())
    // Değişmedi: saklı HAM (biçimi bozuk) değer aynen + bayrak — yeni sunucu yok sayar, eski pod aynısını yazar
    expect(api.admin.updateInventory.mock.calls[0][1].expected_fingerprint).toBe('AA:BB:CC')
    expect(api.admin.updateInventory.mock.calls[0][1].expected_fingerprint_unchanged).toBe(true)
  })

  it('sunucunun 400 expected_fingerprint alan hatası alanın ALTINDA; tost yok, pencere açık', async () => {
    api.admin.updateInventory.mockResolvedValueOnce({ success: false, status: 400, code: 'VALIDATION_FAILED',
      field: 'expected_fingerprint', error: 'Sunucu: parmak izi SHA-256 olmalı (64 onaltılık).' })
    const onSaved = vi.fn()
    render(<InventoryFormModal mode="edit" record={{ ...RECORD, expected_fingerprint: null }} teams={TEAMS} onClose={() => {}} onSaved={onSaved} />)
    fireEvent.change(fpInput(), { target: { value: HEX } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(fpField().textContent).toMatch(/Sunucu: parmak izi/))
    expect(toastMock.error).not.toHaveBeenCalled()
    expect(onSaved).not.toHaveBeenCalled()
  })

  it('başka alanın sunucu hatası eskisi gibi tost', async () => {
    api.admin.updateInventory.mockResolvedValueOnce({ success: false, status: 400, code: 'VALIDATION_FAILED', field: 'port', error: 'Port geçersiz.' })
    render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    fireEvent.click(saveBtn())
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith('Port geçersiz.'))
  })

  it('ekle: boş bırakılırsa anahtar yok; geçerli değer normalize gider', async () => {
    const { unmount } = render(<InventoryFormModal mode="add" record={null} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    expect(fpInput().value).toBe('')
    unmount()
    render(<InventoryFormModal mode="duplicate" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    expect(fpInput().value).toBe('')   // kopya beklenen parmak izini taşımaz
    fireEvent.change(screen.getByDisplayValue('a.example.com'), { target: { value: 'b.example.com' } })
    fireEvent.change(fpInput(), { target: { value: HEX } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.admin.addInventory).toHaveBeenCalled())
    expect(api.admin.addInventory.mock.calls[0][0].expected_fingerprint).toBe(HEX.toUpperCase())
    expect('ug_team_id' in api.admin.addInventory.mock.calls[0][0]).toBe(false)
    expect('expected_fingerprint_unchanged' in api.admin.addInventory.mock.calls[0][0]).toBe(false)
    expect('ug_team_id_unchanged' in api.admin.addInventory.mock.calls[0][0]).toBe(false)
  })

  it('manuel kayıt: alan çizilmez, parmak izi anahtarı gövdeye girmez', async () => {
    render(<InventoryFormModal mode="edit" manual record={{ ...RECORD, cert_source: 'MANUAL', domain: 'vendor-api-jks' }}
      teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    expect(fpInput()).toBeNull()
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.admin.updateInventory).toHaveBeenCalled())
    expect('expected_fingerprint' in api.admin.updateInventory.mock.calls[0][1]).toBe(false)
    expect('expected_fingerprint_unchanged' in api.admin.updateInventory.mock.calls[0][1]).toBe(false)
  })

  it('alan ipucu: kontrol tek düğümü görür, eşleşince kendiliğinden temizlenir; birden çok düğümde her düğüm ayrıca doğrulanır', () => {
    render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    expect(fpField().textContent).toMatch(/TEK düğümü|ONE node/)
    expect(fpField().textContent).toMatch(/her düğümü ayrıca doğrulayın|verify each node separately/)
  })
})

describe('InventoryFormModal — UG takımı salt okunur', () => {
  beforeEach(() => { vi.clearAllMocks(); confirmMock.mockResolvedValue(true) })

  it('düzenle: kayıtta UG varsa adıyla salt okunur satır; seçici yok; kaydet saklı UG + bayrak gönderir', async () => {
    const LONG = 'Dijital Kanallar Uygulama Geliştirme Ekibi (Mobil Bankacılık / Ödeme Sistemleri)'
    render(<InventoryFormModal mode="edit" record={{ ...RECORD, ug_team_id: 9, ug_team_name: LONG }} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    const line = document.querySelector('[data-slot="inv-form-ug"]')
    expect(line).not.toBeNull()
    expect(line.textContent).toContain(LONG)
    expect(line.textContent).toMatch(/UG takımını değiştirmez|does not change the UG team/)
    expect(line.querySelector('[role="combobox"]')).toBeNull()
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.admin.updateInventory).toHaveBeenCalled())
    expect(api.admin.updateInventory.mock.calls[0][1].ug_team_id).toBe(9)
    expect(api.admin.updateInventory.mock.calls[0][1].ug_team_id_unchanged).toBe(true)
  })

  it('adı yoksa takım listesinden; o da yoksa "adı okunamayan takım" (çıplak kimlik değil)', () => {
    const { unmount } = render(<InventoryFormModal mode="edit" record={{ ...RECORD, ug_team_id: 2 }} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    expect(document.querySelector('[data-slot="inv-form-ug"]').textContent).toContain('SY-Takım B')
    unmount()
    render(<InventoryFormModal mode="edit" record={{ ...RECORD, ug_team_id: 77 }} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    const line = document.querySelector('[data-slot="inv-form-ug"]')
    expect(line.querySelector('[data-slot="inv-form-ug-unnamed"]')).not.toBeNull()
    expect(line.textContent).not.toMatch(/\b77\b/)
  })

  it('UG yoksa ve ekle / kopyala kipinde satır çizilmez', () => {
    const { unmount } = render(<InventoryFormModal mode="edit" record={RECORD} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    expect(document.querySelector('[data-slot="inv-form-ug"]')).toBeNull()
    unmount()
    render(<InventoryFormModal mode="duplicate" record={{ ...RECORD, ug_team_id: 9, ug_team_name: 'UG' }} teams={TEAMS} onClose={() => {}} onSaved={() => {}} />)
    expect(document.querySelector('[data-slot="inv-form-ug"]')).toBeNull()
  })
})
