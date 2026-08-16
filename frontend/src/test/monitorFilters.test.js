import { describe, it, expect } from 'vitest'
import { matchesTeamAndGroup, NO_ASSIGNMENT } from '../utils/monitorFilters.js'

/**
 * TAKIM/GRUP KAPSAMI — sekiz izleme sayfasında birebir kopyalanmış sekiz satırdı ve
 * <b>hiçbiri test edilmiyordu</b>. Çıkarımdan önce ölçüldü: {@code __none__} dalını silmek
 * 797 testin HİÇBİRİNİ kırmadı. Oysa o dal silinince "Takımsız" seçildiğinde filtre hiçbir
 * şeyi elemez ve kullanıcı <b>tüm takımların</b> monitörlerini "takımsız" başlığı altında
 * görürdü — sekiz sayfada birden.
 *
 * Bu ekrandaki bir kapsam hatası yetki ihlali değildir (sunucu zaten kendi kapsamını
 * uyguluyor) ama operasyonel olarak yanıltıcıdır: yanlış takımın kesintisi sizin listenizde
 * görünür ve yanlış kişi müdahale eder.
 */
const M = (team, group) => ({ team_name: team, group_name: group })

describe('matchesTeamAndGroup', () => {
  it("'all' hiçbir şeyi elemez — filtre sıfırlama yolu", () => {
    expect(matchesTeamAndGroup(M('SY-A', 'G1'), 'all', 'all')).toBe(true)
    expect(matchesTeamAndGroup(M(null, null), 'all', 'all')).toBe(true)
  })

  it('takım adı TAM eşleşmeli — kısmi eşleşme yanlış satır getirmesin', () => {
    expect(matchesTeamAndGroup(M('SY-A'), 'SY-A', 'all')).toBe(true)
    expect(matchesTeamAndGroup(M('SY-AB'), 'SY-A', 'all')).toBe(false)   // önek eşleşmesi YOK
    expect(matchesTeamAndGroup(M('sy-a'), 'SY-A', 'all')).toBe(false)    // büyük/küçük harf duyarlı
  })

  it('__none__ YALNIZ ataması olmayanları geçirir (mutasyonla test edilen dal)', () => {
    expect(matchesTeamAndGroup(M(null), NO_ASSIGNMENT, 'all')).toBe(true)
    expect(matchesTeamAndGroup(M(undefined), NO_ASSIGNMENT, 'all')).toBe(true)
    expect(matchesTeamAndGroup(M(''), NO_ASSIGNMENT, 'all')).toBe(true)   // boş ad = atanmamış
    expect(matchesTeamAndGroup(M('SY-A'), NO_ASSIGNMENT, 'all')).toBe(false)
  })

  it('grup boyutu takımdan BAĞIMSIZ ve aynı kurallarla çalışır', () => {
    expect(matchesTeamAndGroup(M('SY-A', 'G1'), 'all', 'G1')).toBe(true)
    expect(matchesTeamAndGroup(M('SY-A', 'G2'), 'all', 'G1')).toBe(false)
    expect(matchesTeamAndGroup(M('SY-A', null), 'all', NO_ASSIGNMENT)).toBe(true)
    expect(matchesTeamAndGroup(M('SY-A', 'G1'), 'all', NO_ASSIGNMENT)).toBe(false)
  })

  it('iki boyut VE ile birleşir — birinin geçmesi yetmez', () => {
    expect(matchesTeamAndGroup(M('SY-A', 'G1'), 'SY-A', 'G1')).toBe(true)
    expect(matchesTeamAndGroup(M('SY-A', 'G2'), 'SY-A', 'G1')).toBe(false)   // takım tutuyor, grup tutmuyor
    expect(matchesTeamAndGroup(M('SY-B', 'G1'), 'SY-A', 'G1')).toBe(false)   // grup tutuyor, takım tutmuyor
  })

  it('takımsız + grupsuz kombinasyonu birlikte çalışır', () => {
    expect(matchesTeamAndGroup(M(null, null), NO_ASSIGNMENT, NO_ASSIGNMENT)).toBe(true)
    expect(matchesTeamAndGroup(M(null, 'G1'), NO_ASSIGNMENT, NO_ASSIGNMENT)).toBe(false)
    expect(matchesTeamAndGroup(M('SY-A', null), NO_ASSIGNMENT, NO_ASSIGNMENT)).toBe(false)
  })
})
