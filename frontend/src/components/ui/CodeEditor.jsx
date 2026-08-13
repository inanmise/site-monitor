import Editor from 'react-simple-code-editor'
import { highlight, languages } from 'prismjs/components/prism-core'
import 'prismjs/components/prism-clike'
import 'prismjs/components/prism-javascript'
import 'prismjs/themes/prism.css'

/**
 * Hafif JavaScript kod editörü (react-simple-code-editor + prismjs) — k6 scriptleri için syntax highlight.
 * Kendi (açık) kod arka planına sahiptir; uygulama teması ne olursa olsun okunaklı kalır. Monaco/CodeMirror gibi
 * ağır bağımlılık YOK.
 *
 * `textareaId` parametrik: eskiden sabitti ve aynı ekranda ikinci bir editör (sürüm önizlemesi)
 * render edilince DOM'da id çakışması oluyordu.
 */
export default function CodeEditor({ value, onChange, placeholder, readOnly = false, minHeight = 280,
                                     textareaId = 'k6-script-editor' }) {
  return (
    <div className="code-editor-wrap" style={{
      border: '1px solid var(--border, #d1d5db)', borderRadius: 8, overflow: 'auto',
      background: '#fbfbfd', maxHeight: 520,
    }}>
      <Editor
        value={value || ''}
        onValueChange={(code) => onChange && onChange(code)}
        highlight={(code) => highlight(code, languages.javascript, 'javascript')}
        padding={12}
        readOnly={readOnly}
        placeholder={placeholder}
        textareaId={textareaId}
        spellCheck={false}
        style={{
          fontFamily: '"JetBrains Mono", Consolas, Menlo, monospace',
          fontSize: 13, lineHeight: 1.5, minHeight, color: '#1f2937',
          caretColor: '#111',
        }}
      />
    </div>
  )
}
