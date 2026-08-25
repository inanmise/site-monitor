import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils'
import NotificationGroupHistory from '../components/admin/NotificationGroupHistory.jsx'

/**
 * Değişiklik geçmişinin OKUNABİLİRLİĞİ.
 *
 * Denetim kaydı iki ayrı biçim taşır: oluşturma/silme ANLIK GÖRÜNTÜ (o an ne vardı), düzenleme
 * FARK (neyden neye). İkisini tek şablona sıkıştırmak oluşturmada "boştan şuna" gibi yanlış bir
 * okuma üretirdi; testler biçim ayrımını ve eksikliklerin AÇIKÇA bildirilmesini sabitliyor.
 */

const base = {
  id: 1, at: '2026-08-25T10:00:00', actor: 'ayse',
  group_id: '10', group_name: 'Ödeme Nöbetçi', team_name: 'Takım A', ip: '10.0.0.1',
}

const row = (o) => ({ ...base, ...o })

const renderList = (rows, extra = {}) =>
  render(<NotificationGroupHistory rows={rows} truncated={false} hidden={0} {...extra} />)

describe('NotificationGroupHistory', () => {
  it('OLUŞTURMA kaydı anlık görüntü olarak çizilir — fark değil', () => {
    renderList([row({
      action: 'CREATE',
      changes: '{"name":"Ödeme Nöbetçi","emails":"a@example.com","isDefault":false,"teamId":1}',
    })])

    expect(screen.getByText('Initial contents')).toBeTruthy()
    expect(screen.getByText('Group name')).toBeTruthy()
    expect(screen.getByText('a@example.com')).toBeTruthy()
    // Boolean ham "false" olarak değil, insan okunur biçimde.
    expect(screen.getByText('No')).toBeTruthy()
    // Anlık görüntüde "neyden" sütunu OLMAMALI: oluşturmanın öncesi yoktur.
    expect(document.querySelector('.audit-diff-from')).toBeNull()
  })

  it('DÜZENLEME kaydı neyden→neye tablosu çizer', () => {
    renderList([row({
      action: 'UPDATE',
      changes: '{"name":{"from":"Eski","to":"Yeni"},"emails":{"from":"a@example.com","to":"b@example.com"}}',
    })])

    expect(document.querySelectorAll('.audit-diff-from')).toHaveLength(2)
    expect(screen.getByText('Eski')).toBeTruthy()
    expect(screen.getByText('Yeni')).toBeTruthy()
  })

  it('SİLME kaydı "son durum" başlığıyla çizilir — kim, neyi sildi görünür', () => {
    renderList([row({
      action: 'DELETE', actor: 'mehmet',
      changes: '{"name":"SY_MAIL_2","emails":"eski@example.com","teamId":1}',
    })])

    expect(screen.getByText('Last state before deletion')).toBeTruthy()
    expect(screen.getByText('mehmet')).toBeTruthy()
    expect(screen.getByText('deleted')).toBeTruthy()
    expect(screen.getByText('eski@example.com')).toBeTruthy()
  })

  it('TAŞIMA kaydı taşınan sayısını ve hedefi yazar', () => {
    renderList([
      row({ id: 1, action: 'REASSIGN', changes: '{"to":20,"moved":7}' }),
      row({ id: 2, action: 'REASSIGN', changes: '{"to":null,"moved":2}' }),
    ])

    expect(screen.getByText('7 monitors moved')).toBeTruthy()
    expect(screen.getByText('to group #20')).toBeTruthy()
    // Hedef yoksa "takım varsayılanı" denir; boş bırakmak "nereye gitti" sorusunu açıkta bırakırdı.
    expect(screen.getByText('to the team default')).toBeTruthy()
  })

  it('AYRIŞTIRILAMAYAN eski kayıt satırı DÜŞÜRMEZ — kim/ne zaman/ne yaptı yine görünür', () => {
    renderList([row({ action: 'DELETE', changes: '{name=Eski, teamId=1}' })])

    expect(screen.getByText('ayse')).toBeTruthy()
    expect(screen.getByText('deleted')).toBeTruthy()
    expect(document.querySelector('.audit-diff-table')).toBeNull()
  })

  it('Kesilme ve gizlenen sayısı AÇIKÇA bildirilir', () => {
    renderList([row({ action: 'UPDATE', changes: null })], { truncated: true, hidden: 4 })

    expect(screen.getByText(/there may be older ones/i)).toBeTruthy()
    expect(screen.getByText(/4 entries are hidden/i)).toBeTruthy()
  })

  it('Boş durum: süzgeç varken ve yokken FARKLI cümle', () => {
    const { unmount } = renderList([])
    expect(screen.getByText('Nothing has been changed yet.')).toBeTruthy()
    unmount()

    renderList([], { filterName: 'Ödeme Nöbetçi' })
    expect(screen.getByText('Nothing has been changed on this group yet.')).toBeTruthy()
  })

  it('Süzgeç bandı temizlenebilir', () => {
    const onClearFilter = vi.fn()
    renderList([], { filterName: 'Ödeme Nöbetçi', onClearFilter })

    fireEvent.click(screen.getByRole('button', { name: /Show everything/i }))
    expect(onClearFilter).toHaveBeenCalled()
  })

  it('Yükleniyor ve hata durumları listeyi çizmez', () => {
    const { unmount } = renderList([row({ action: 'UPDATE', changes: null })], { loading: true })
    expect(document.querySelector('.ng-hist-list')).toBeNull()
    unmount()

    renderList([row({ action: 'UPDATE', changes: null })], { error: 'boom' })
    expect(screen.getByText('boom')).toBeTruthy()
    expect(document.querySelector('.ng-hist-list')).toBeNull()
  })
})
