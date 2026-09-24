import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import CodeEditor from '../../src/components/ui/CodeEditor.jsx'
import '../../src/styles/globals.css'   // uygulamayla aynı kaskat: shadcn jetonları + Tailwind
import '../../src/App.css'

/**
 * CodeEditor izole harness'ı — YALNIZ Playwright içindir, ürüne girmez (bkz. editor.html).
 *
 * Testin ihtiyaç duyduğu her şey URL parametresiyle kurulur; böylece senaryolar sayfayı
 * yeniden yükleyerek farklı durumları deneyebilir, testte React'a dokunmaya gerek kalmaz:
 *   ?value=<encodeURIComponent(kod)>   başlangıç içeriği
 *   ?lines=N                            N satırlık üretilmiş içerik ("satır 1", "satır 2", …)
 *   ?long=1                             sarmayı zorlayan çok uzun bir satır ekler
 *   ?marker=N                           N. satıra hata işareti koyar
 *   ?gutter=0                           cetveli kapatır (geri alma anahtarının testi)
 */
const q = new URLSearchParams(location.search)

const LONG = "  const uzunSatir = 'aaaaaaaaaa bbbbbbbbbb cccccccccc dddddddddd eeeeeeeeee ffffffffff gggggggggg hhhhhhhhhh iiiiiiiiii jjjjjjjjjj kkkkkkkkkk llllllllll mmmmmmmmmm nnnnnnnnnn oooooooooo pppppppppp qqqqqqqqqq rrrrrrrrrr ssssssssss tttttttttt';"

function initialValue() {
  if (q.has('value')) return q.get('value')
  const n = Number(q.get('lines') || 0)
  if (n > 0) {
    const lines = Array.from({ length: n }, (_, i) => `satır ${i + 1}`)
    if (q.get('long') === '1') lines.splice(1, 0, LONG)
    return lines.join('\n')
  }
  return "import http from 'k6/http';\nexport default function () {\n  http.get('https://example.com');\n}"
}

function App() {
  const [value, setValue] = useState(initialValue)
  const markerLine = Number(q.get('marker') || 0)
  const markers = markerLine > 0
    ? [{ line: markerLine, type: 'error', message: `Unexpected token (${markerLine}:5)` }]
    : []

  return (
    <>
      <CodeEditor
        value={value}
        onChange={setValue}
        markers={markers}
        revealMarkers={markerLine > 0}
        readOnly={q.get('readonly') === '1'}
        showLineNumbers={q.get('gutter') !== '0'}
      />
      {/* Testin "onChange gerçekten tetiklendi mi" sorusunu React'a girmeden cevaplaması için. */}
      <pre data-testid="mirror-value">{value}</pre>
    </>
  )
}

createRoot(document.getElementById('host')).render(<App />)
