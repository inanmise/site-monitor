import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { EN } from '../i18n/en.js'
import LdapSettings from '../components/admin/LdapSettings.jsx'
import {
  cleanRules, hasChanges, isDnSpec, mappingKey, moveRow, ruleError, ROLES, NEW_ROW_ROLE,
} from '../components/admin/ldap/ldapRoleMappingModel.js'

/**
 * LDAP grup → rol eşlemesi ekranı (2026-10-11, kullanıcı kararı: "açılırsa, elle değişeni ezmez"):
 * anahtar varsayılan kapalı, sıralı kurallar (yukarı / aşağı), yeni satır USER, "kim ne olacak?" önizlemesi formdaki
 * (kaydedilmemiş) kurallarla, açılırken önizlenmemişse uyarı; kayıt gövdesi yeni alanları taşır.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({
    admin: {
      getLdapSettings: vi.fn(),
      saveLdapSettings: vi.fn(),
      testLdap: vi.fn(),
      queryLdapUser: vi.fn(),
      previewLdapRoleMapping: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'

const base = {
  enabled: true, host: 'ldap.example.com', port: 636, use_ldaps: true, start_tls: false,
  skip_cert_verification: false, ca_cert_pem: '', bind_dn: 'CN=svc', bind_password_set: true,
  base_dn: 'DC=example', user_search_filter: '(objectclass=person)', user_attribute: 'sAMAccountName',
  email_attribute: 'mail', display_attribute: 'displayName', group_filter: '(objectclass=group)',
  skip_member_of: false, default_role: 'ADMIN', role_mapping_enabled: false, role_mapping_fallback: 'DERIVED',
  role_mappings: [{ group: 'CN=SM-Denetim', role: 'AUDIT' }, { group: 'CN=SiteMonitor Admins,OU=Groups,DC=example,DC=com', role: 'ADMIN' }],
}
const PREVIEW = {
  ldap_users: 42, fallback: 'DERIVED',
  counts: { DEMOTE: 1, PROMOTE: 2, CHANGE: 0, LAST_ADMIN: 0, LOCKED: 1, PROTECTED: 0, SAME: 5, DERIVED: 33 },
  rules: [{ index: 0, group: 'CN=SM-Denetim', role: 'AUDIT', groups_found: 1, group_dns: ['CN=SM-Denetim,OU=G'], ad_members: 7, ad_members_truncated: false, matched_users: 3 }],
  rows: [{ username: 'N12345', display_name: 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi Bölüm Başkanı)', role_before: 'TEAM_ADMIN', role_after: 'USER', outcome: 'DEMOTE', source: 'GROUP', rule_group: 'CN=SM-Denetim' }],
  rows_truncated: false,
}
const ruleRows = () => [...document.querySelectorAll('[data-slot="ldap-rm-rule"]')]
const groupInputs = () => ruleRows().map((r) => r.querySelector('input').value)

async function mount(data = base) {
  api.admin.getLdapSettings.mockResolvedValue({ success: true, data, secret_key_set: true })
  render(<LdapSettings />)
  await screen.findByDisplayValue('ldap.example.com')
}

describe('ldapRoleMappingModel', () => {
  it('DN ayrımı: "=" VE virgül; tek "CN=Ad" grup adıdır', () => {
    expect(isDnSpec('CN=A,OU=B')).toBe(true)
    expect(isDnSpec('CN=CertAdmins')).toBe(false)
    expect(isDnSpec('Admins')).toBe(false)
  })
  it('cleanRules boş grubu atlar, kırpar, rolü büyük harfe çevirir; moveRow sınırda aynı', () => {
    expect(cleanRules([{ group: ' A ', role: 'audit' }, { group: '  ', role: 'ADMIN' }])).toEqual([{ group: 'A', role: 'AUDIT' }])
    expect(moveRow(['a', 'b', 'c'], 0, 1)).toEqual(['b', 'a', 'c'])
    expect(moveRow(['a', 'b'], 0, -1)).toEqual(['a', 'b'])
  })
  it('ruleError: uzunluk ve rol; hasChanges yalnız yükseltme / düşürme / değişim; mappingKey sıraya duyarlı', () => {
    expect(ruleError({ group: 'x'.repeat(513), role: 'USER' })).toBe('ldap.rm.errTooLong')
    expect(ruleError({ group: 'A', role: 'ROOT' })).toBe('ldap.rm.errRole')
    expect(ruleError({ group: '', role: 'ROOT' })).toBeNull()
    expect(hasChanges({ counts: { SAME: 3, LOCKED: 1 } })).toBe(false)
    expect(hasChanges({ counts: { DEMOTE: 1 } })).toBe(true)
    expect(mappingKey({ role_mappings: [{ group: 'A', role: 'USER' }, { group: 'B', role: 'USER' }] }))
      .not.toBe(mappingKey({ role_mappings: [{ group: 'B', role: 'USER' }, { group: 'A', role: 'USER' }] }))
    expect(ROLES).toEqual(['ADMIN', 'TEAM_ADMIN', 'AUDIT', 'USER'])
    expect(NEW_ROW_ROLE).toBe('USER')
  })
})

describe('LdapSettings — grup → rol eşlemesi (2026-10-11)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.saveLdapSettings.mockImplementation(async (dto) => ({ success: true, data: dto }))
    api.admin.previewLdapRoleMapping.mockResolvedValue({ success: true, data: PREVIEW })
  })

  it('varsayılan kapalı: kapalı açıklaması, önizleme uyarısı yok; kural türü ipucu (DN / ad)', async () => {
    await mount()
    expect(screen.getByRole('switch', { name: EN['ldap.rm.enable'] })).not.toBeChecked()
    expect(screen.getByText(EN['ldap.rm.offHint'])).toBeInTheDocument()
    expect(document.querySelector('[data-slot="ldap-rm-needs-preview"]')).toBeNull()
    expect(within(ruleRows()[0]).getByText(EN['ldap.rm.kindCn'])).toBeInTheDocument()
    expect(within(ruleRows()[1]).getByText(EN['ldap.rm.kindDn'])).toBeInTheDocument()
  })

  it('açınca önizleme uyarısı çıkar; kayıt gövdesi role_mapping_enabled + fallback + sıralı kuralları taşır', async () => {
    await mount()
    fireEvent.click(screen.getByRole('switch', { name: EN['ldap.rm.enable'] }))
    expect(document.querySelector('[data-slot="ldap-rm-needs-preview"]')).not.toBeNull()
    fireEvent.change(screen.getByLabelText(EN['ldap.rm.fallback']), { target: { value: 'USER' } })
    fireEvent.click(screen.getByRole('button', { name: /^Save|^Kaydet/ }))
    await waitFor(() => expect(api.admin.saveLdapSettings).toHaveBeenCalled())
    const dto = api.admin.saveLdapSettings.mock.calls[0][0]
    expect(dto.role_mapping_enabled).toBe(true)
    expect(dto.role_mapping_fallback).toBe('USER')
    expect(dto.role_mappings.map((r) => r.group)).toEqual(['CN=SM-Denetim', 'CN=SiteMonitor Admins,OU=Groups,DC=example,DC=com'])
    // 2026-10-12: yalnız değişen alanlar (rol eşlemesi bir bütün) — bağlantı ayarları gönderilmez, başkasının kaydını ezmez
    expect(dto).not.toHaveProperty('host')
    expect(dto).not.toHaveProperty('updated_at')
  })

  it('sıralama: aşağı / yukarı taşı; ilk satırda "yukarı" kapalı; yeni satır USER; satır silinir', async () => {
    await mount()
    const up0 = within(ruleRows()[0]).getAllByRole('button')[0]
    expect(up0).toBeDisabled()
    fireEvent.click(within(ruleRows()[0]).getByRole('button', { name: /Move down/ }))
    expect(groupInputs()[0]).toMatch(/SiteMonitor Admins/)
    fireEvent.click(screen.getByRole('button', { name: EN['ldap.addMapping'] }))
    const last = ruleRows()[ruleRows().length - 1]
    expect(last.querySelector('select').value).toBe('USER')
    fireEvent.click(within(last).getByRole('button', { name: new RegExp(EN['ldap.removeMapping']) }))
    expect(ruleRows()).toHaveLength(2)
  })

  it('"Kim ne olacak?": formdaki kurallarla sunucuya gider, sayaçlar + etkilenen hesap (ad kesilmez); sonra "önizlendi", kural değişince yeniden uyarı', async () => {
    await mount()
    fireEvent.click(screen.getByRole('switch', { name: EN['ldap.rm.enable'] }))
    fireEvent.click(screen.getByRole('button', { name: EN['ldap.rm.preview'] }))
    await waitFor(() => expect(api.admin.previewLdapRoleMapping).toHaveBeenCalledWith({
      role_mappings: [{ group: 'CN=SM-Denetim', role: 'AUDIT' }, { group: 'CN=SiteMonitor Admins,OU=Groups,DC=example,DC=com', role: 'ADMIN' }],
      role_mapping_fallback: 'DERIVED',
    }))
    const dlg = await screen.findByRole('dialog')
    await within(dlg).findByText(EN['ldap.rm.ldapUsers'].replace('{0}', '42'))
    expect(dlg.querySelector('[data-outcome="DEMOTE"] .tabular-nums').textContent).toBe('1')
    const row = dlg.querySelector('[data-slot="ldap-rm-row"]')
    expect(row.textContent).toContain('Bölüm Başkanı)')
    expect(row.getAttribute('data-outcome')).toBe('DEMOTE')
    fireEvent.click(within(dlg).getAllByRole('button', { name: EN['ldap.rm.close'] })[0])
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(document.querySelector('[data-slot="ldap-rm-needs-preview"]')).toBeNull()
    expect(screen.getByText(EN['ldap.rm.previewed'])).toBeInTheDocument()
    fireEvent.change(ruleRows()[0].querySelector('input'), { target: { value: 'CN=Baska' } })
    expect(document.querySelector('[data-slot="ldap-rm-needs-preview"]')).not.toBeNull()
  })

  it('önizleme hatası pencerede açıklanır', async () => {
    api.admin.previewLdapRoleMapping.mockResolvedValue({ success: false, error: 'AD okunamadı · bağlantıyı sınayın' })
    await mount()
    fireEvent.click(screen.getByRole('button', { name: EN['ldap.rm.preview'] }))
    const dlg = await screen.findByRole('dialog')
    expect(await within(dlg).findByText('AD okunamadı · bağlantıyı sınayın')).toBeInTheDocument()
  })
})
