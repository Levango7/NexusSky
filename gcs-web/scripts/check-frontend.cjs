// Frontend syntax + import-graph check (vite/esbuild is blocked by the
// sandbox's stdio-pipe policy, so we validate with @babel/parser instead).
// - Parses every .jsx/.js source (catches JSX/syntax errors)
// - Resolves every import specifier to a real file or dependency
// - Verifies named exports of local modules exist
const fs = require('node:fs')
const path = require('node:path')
// pnpm layout: resolve @babel/parser from vite's dependency tree directly
function findBabelParser(root) {
  const dir = path.join(root, 'node_modules', '.pnpm')
  for (const e of fs.readdirSync(dir)) {
    if (e.startsWith('@babel+parser@')) {
      return path.join(dir, e, 'node_modules', '@babel', 'parser')
    }
  }
  return null
}
const parserPath = findBabelParser(path.resolve(__dirname, '..'))
if (!parserPath) throw new Error('@babel/parser not found under node_modules/.pnpm')
const parser = require(parserPath)

const ROOT = path.resolve(__dirname, '..')
const SRC = path.join(ROOT, 'src')
let failures = 0

function fail(file, msg) {
  failures++
  console.error(`FAIL ${path.relative(ROOT, file)}: ${msg}`)
}

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory()) walk(path.join(dir, e.name), out)
    else if (/\.(jsx|js)$/.test(e.name)) out.push(path.join(dir, e.name))
  }
  return out
}

// Parse a file, return { imports: Map(specifier -> [names]), exports: [names] }
function analyze(file) {
  const src = fs.readFileSync(file, 'utf8')
  const ast = parser.parse(src, {
    sourceType: 'module',
    plugins: ['jsx'],
  })
  const imports = new Map()
  const exports = []
  for (const n of ast.program.body) {
    if (n.type === 'ImportDeclaration') {
      const names = n.specifiers.map((s) => s.imported ? s.imported.name : 'default')
      imports.set(n.source.value, names)
    } else if (n.type === 'ExportDefaultDeclaration') {
      exports.push('default')
    } else if (n.type === 'ExportNamedDeclaration') {
      if (n.declaration) {
        if (n.declaration.type === 'VariableDeclaration') {
          for (const d of n.declaration.declarations) {
            if (d.id.type === 'Identifier') exports.push(d.id.name)
          }
        } else if (n.declaration.id) {
          exports.push(n.declaration.id.name)
        }
      }
      for (const s of n.specifiers || []) exports.push(s.exported.name)
    }
  }
  return { imports, exports }
}

const files = walk(SRC)
const cache = new Map() // file -> exports (local modules only)
for (const f of files) {
  try {
    const { exports } = analyze(f)
    cache.set(f, exports)
  } catch (e) {
    fail(f, 'parse error: ' + e.message)
  }
}

// Resolve an import specifier from a base file to a real file path
function resolveFrom(base, spec) {
  if (!spec.startsWith('.') && !spec.startsWith('/')) return null // dependency
  let p = spec.startsWith('.')
    ? path.resolve(path.dirname(base), spec)
    : path.join(ROOT, spec)
  for (const cand of [p, p + '.jsx', p + '.js', path.join(p, 'index.jsx'), path.join(p, 'index.js')]) {
    if (fs.existsSync(cand) && fs.statSync(cand).isFile()) return cand
  }
  return null
}

for (const f of files) {
  const { imports } = analyze(f)
  for (const [spec, names] of imports) {
    const target = resolveFrom(f, spec)
    if (spec.startsWith('.') || spec.startsWith('/')) {
      if (!target) { fail(f, `unresolved import '${spec}'`); continue }
      const avail = cache.get(target) || []
      for (const name of names) {
        if (name === 'default' ? !avail.includes('default') : !avail.includes(name)) {
          fail(f, `'${spec}' has no export '${name}' (available: ${avail.join(', ') || 'none'})`)
        }
      }
    } else {
      // bare dependency: must exist in node_modules
      const pkg = spec.split('/')[0].startsWith('@') ? spec.split('/').slice(0, 2).join('/') : spec.split('/')[0]
      if (!fs.existsSync(path.join(ROOT, 'node_modules', pkg))) {
        fail(f, `missing dependency '${spec}'`)
      }
    }
  }
}

console.log(`checked ${files.length} files`)
if (failures) {
  console.error(`${failures} failure(s)`)
  process.exit(1)
}
console.log('OK: syntax + import graph valid')
