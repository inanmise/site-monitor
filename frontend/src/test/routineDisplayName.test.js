import { describe, it, expect } from 'vitest'
import { displayNameOf } from '../components/oncallroutine/displayName.js'

/**
 * Nöbet Rutinleri — oturumdaki kişinin ekranda görünen adı (inceleme bulgusu #7, 2026-11-26). App `meName`'i /me
 * profilinden bununla üretir; iyimser işaretteki ve "son kaydeden" satırındaki "kim" bu addır. Sıra: `display_name` →
 * ad + soyad → kullanıcı adı → null (ad uydurulmaz).
 */
describe('displayNameOf', () => {
  it.each([
    ['görünen ad önce', { display_name: 'Ayşe Yılmaz (Dijital Kanallar / Uzman)', first_name: 'Ayşe', last_name: 'Yılmaz' }, 'N12345',
      'Ayşe Yılmaz (Dijital Kanallar / Uzman)'],
    ['görünen ad kenar boşluğu atılır', { display_name: '  Ayşe Yılmaz  ' }, 'N12345', 'Ayşe Yılmaz'],
    ['görünen ad yoksa ad + soyad', { display_name: '', first_name: 'Mehmet', last_name: 'Demir' }, 'N67890', 'Mehmet Demir'],
    ['yalnız ad', { first_name: 'Zeynep' }, 'N24680', 'Zeynep'],
    ['yalnız soyad', { last_name: 'Kaya' }, 'N24680', 'Kaya'],
    ['yalnız boşluk görünen ad → kullanıcı adı', { display_name: '   ' }, 'N12345', 'N12345'],
    ['boş profil → kullanıcı adı', {}, 'N12345', 'N12345'],
    ['profil yok → kullanıcı adı', null, 'N12345', 'N12345'],
    ['profil tanımsız → kullanıcı adı', undefined, 'N12345', 'N12345'],
    ['sayısal kullanıcı adı metne çevrilir', null, 42, '42'],
    ['ad da kullanıcı adı da yok → null', {}, null, null],
    ['boş kullanıcı adı → null', null, '', null],
    ['hiçbir şey yok → null', undefined, undefined, null],
  ])('%s', (_label, profile, username, expected) => {
    expect(displayNameOf(profile, username)).toBe(expected)
  })
})
