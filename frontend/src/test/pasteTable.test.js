import { describe, it, expect } from 'vitest'
import { clipboardToMarkdownTable } from '../utils/pasteTable'

function clip({ html = '', text = '' } = {}) {
  return { getData: (type) => (type === 'text/html' ? html : text) }
}

describe('clipboardToMarkdownTable', () => {
  it('converts Excel TSV to a markdown table', () => {
    const md = clipboardToMarkdownTable(clip({
      text: 'Kayıt\tDurum\tAdet\r\nSSL Yenileme\tÇalışılıyor\t3\r\nDNS Taşıma\tTamamlandı\t1',
    }))
    expect(md).toContain('| Kayıt | Durum | Adet |')
    expect(md).toContain('| --- | --- | --- |')
    expect(md).toContain('| SSL Yenileme | Çalışılıyor | 3 |')
    expect(md).toContain('| DNS Taşıma | Tamamlandı | 1 |')
  })

  it('converts an HTML <table> to a markdown table', () => {
    const md = clipboardToMarkdownTable(clip({
      html: '<table><tr><th>Ad</th><th>Sayı</th></tr><tr><td>Olay</td><td>5</td></tr></table>',
    }))
    expect(md).toContain('| Ad | Sayı |')
    expect(md).toContain('| Olay | 5 |')
  })

  it('converts an uppercase <TABLE> (legacy clipboard HTML) too', () => {
    const md = clipboardToMarkdownTable(clip({
      html: '<TABLE><TR><TD>X</TD><TD>Y</TD></TR><TR><TD>1</TD><TD>2</TD></TR></TABLE>',
    }))
    expect(md).toContain('| X | Y |')
    expect(md).toContain('| 1 | 2 |')
  })

  it('escapes pipe characters in cells', () => {
    const md = clipboardToMarkdownTable(clip({ text: 'a|b\tc\r\n1\t2' }))
    expect(md).toContain('a\\|b')
  })

  it('pads ragged rows to the header column count', () => {
    const md = clipboardToMarkdownTable(clip({ text: 'a\tb\tc\r\n1\t2' }))
    expect(md).toContain('| 1 | 2 |  |')
  })

  it('returns null for plain text without tabs', () => {
    expect(clipboardToMarkdownTable(clip({ text: 'sadece düz metin' }))).toBeNull()
  })

  it('returns null for empty clipboard', () => {
    expect(clipboardToMarkdownTable(clip())).toBeNull()
    expect(clipboardToMarkdownTable(null)).toBeNull()
  })

  it('returns null for a pasted exception stack trace (ilk satır = mesaj, tab yok → tablo değil)', () => {
    const stack = [
      'com.example.bsa.core.exception.BSAException: Teknik bir hata oluştu. İşleminizi kontrol ediniz.',
      '\tat deployment.BSAWEB.war//com.example.channel.AbstractChannelDispatcher.dispach(AbstractChannelDispatcher.java:134)',
      '\tat deployment.BSAWEB.war//com.example.channel.ChannelWsDispatcher.dispach(ChannelWsDispatcher.java:11)',
    ].join('\r\n')
    expect(clipboardToMarkdownTable(clip({ text: stack }))).toBeNull()
  })

  it('returns null when only tab-indented lines are pasted (girinti, ızgara değil)', () => {
    const text = ['\tat foo.Bar.baz(Bar.java:1)', '\tat foo.Bar.qux(Bar.java:2)'].join('\n')
    expect(clipboardToMarkdownTable(clip({ text }))).toBeNull()
  })
})
