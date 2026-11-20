// Architecture rule for plugin frontends (RNF17): a plugin imports only @alinha/plugin-sdk,
// @alinha/ui and @alinha/api-client from the platform, and never reaches outside its own
// frontend folder (another plugin, the shell). Relative imports are resolved against the file,
// so nesting depth does not matter. Covers static, type-only, re-export, dynamic and require imports,
// and Vite's import.meta.glob and new URL(..., import.meta.url).
// Any other bare name must be a package the plugin declares in its package.json, or a Node builtin, so that a path
// alias (Vite resolve.alias, tsconfig paths: `@/`, `~/`, `#x`) cannot reach into the repo dressed as a package name.
// Option `root`: the repo root (eslint.config.js passes its own folder). Without it, ESLint's cwd is used,
// and the rule would not recognise plugin files when ESLint runs from inside a plugin.
import { readFileSync } from 'node:fs'
import { builtinModules } from 'node:module'
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path'

const ALLOWED_PLATFORM_PACKAGES = ['@alinha/plugin-sdk', '@alinha/ui', '@alinha/api-client']

// plugins/<kind>/<id>/frontend/...
const PLUGIN_FRONTEND = /^plugins\/[^/]+\/[^/]+\/frontend(?=\/)/

const toPosix = (p) => p.split(sep).join('/')

const isAllowedPlatformPackage = (spec) =>
  ALLOWED_PLATFORM_PACKAGES.some((pkg) => spec === pkg || spec.startsWith(`${pkg}/`))

/** Plugin frontend folder (absolute) that contains `filename`, or null if it is not plugin code. */
export function pluginRootOf(filename, repoRoot) {
  const match = toPosix(relative(repoRoot, filename)).match(PLUGIN_FRONTEND)
  return match ? resolve(repoRoot, match[0]) : null
}

/** `import.meta` */
const isImportMeta = (node) => node?.type === 'MetaProperty' && node.meta.name === 'import' && node.property.name === 'meta'
/** `import.meta.<name>` */
const isImportMetaProperty = (node, names) =>
  node?.type === 'MemberExpression' && isImportMeta(node.object) && node.property.type === 'Identifier' && names.includes(node.property.name)

const DEPENDENCY_FIELDS = ['dependencies', 'peerDependencies', 'devDependencies', 'optionalDependencies']
const declaredPackagesCache = new Map()

/** Package names in `<pluginRoot>/package.json`. Empty when it is missing or unreadable, so the rule fails closed. */
export function declaredPackagesOf(pluginRoot) {
  if (!declaredPackagesCache.has(pluginRoot)) {
    let names = []
    try {
      const manifest = JSON.parse(readFileSync(join(pluginRoot, 'package.json'), 'utf8'))
      names = DEPENDENCY_FIELDS.flatMap((field) => Object.keys(manifest[field] ?? {}))
    } catch {
      // No package.json (or invalid JSON): nothing is declared, every bare name is reported.
    }
    declaredPackagesCache.set(pluginRoot, new Set(names))
  }
  return declaredPackagesCache.get(pluginRoot)
}

/** `react` for 'react/jsx-runtime', `@scope/name` for '@scope/name/sub'; null if `spec` is not a valid package name. */
export function packageNameOf(spec) {
  const [first, second] = spec.split('/')
  if (!first.startsWith('@')) return first
  return first.length > 1 && second ? `${first}/${second}` : null
}

const isNodeBuiltin = (spec) => spec.startsWith('node:') || builtinModules.includes(packageNameOf(spec))

const isRelative = (spec) => spec === '.' || spec === '..' || spec.startsWith('./') || spec.startsWith('../')

/** Why `spec`, imported from `filename` inside `pluginRoot`, breaks the boundary; null if it is fine. */
export function violationOf(spec, filename, pluginRoot) {
  if (spec.startsWith('@alinha/')) return isAllowedPlatformPackage(spec) ? null : 'platformPackage'
  // file: URLs name a path on disk directly, like an absolute path. So does Vite's /@fs/ prefix.
  if (isAbsolute(spec) || /^file:/i.test(spec)) return 'outside'
  if (isRelative(spec)) {
    const target = relative(pluginRoot, resolve(dirname(filename), spec))
    return target === '..' || target.startsWith(`..${sep}`) || isAbsolute(target) ? 'outside' : null
  }
  if (isNodeBuiltin(spec)) return null
  return declaredPackagesOf(pluginRoot).has(packageNameOf(spec)) ? null : 'undeclared'
}

/** Static string value of an import source node, or undefined if it is computed at runtime. */
function staticValue(node) {
  if (!node) return undefined
  if (node.type === 'Literal' && typeof node.value === 'string') return node.value
  if (node.type === 'TemplateLiteral' && node.expressions.length === 0) return node.quasis[0].value.cooked
  if (node.type === 'TSLiteralType') return staticValue(node.literal)
  return undefined
}

export default {
  meta: {
    type: 'problem',
    docs: { description: 'Plugin frontends import only the SDK, the UI kit and the API client' },
    schema: [{ type: 'object', properties: { root: { type: 'string' } }, additionalProperties: false }],
    messages: {
      platformPackage:
        "'{{spec}}' is not allowed in a plugin. Use only @alinha/plugin-sdk, @alinha/ui and @alinha/api-client; reach other plugins through the API («api» contracts) or events.",
      outside: "'{{spec}}' points outside this plugin's frontend folder (another plugin or the shell). Use @alinha/plugin-sdk.",
      undeclared:
        "'{{spec}}' is not a package declared in this plugin's package.json. Path aliases (@/, ~/, #...) are not allowed in plugins: use a relative import inside the plugin, @alinha/* for the platform, or declare the package.",
      dynamic: 'Dynamic imports in a plugin must use a string literal, so the plugin boundary can be checked.',
    },
  },
  create(context) {
    const filename = context.filename
    const pluginRoot = pluginRootOf(filename, context.options[0]?.root ?? context.cwd)
    if (!pluginRoot) return {}

    const check = (sourceNode, { mustBeStatic = false, glob = false } = {}) => {
      const value = staticValue(sourceNode)
      if (value === undefined) {
        if (mustBeStatic) context.report({ node: sourceNode, messageId: 'dynamic' })
        return
      }
      // A glob may exclude files with a leading "!"; the path after it must stay inside too.
      const spec = glob && value.startsWith('!') ? value.slice(1) : value
      const messageId = violationOf(spec, filename, pluginRoot)
      if (messageId) context.report({ node: sourceNode, messageId, data: { spec: value } })
    }

    /** import.meta.glob('...') or import.meta.glob(['...', '!...']) */
    const checkGlob = (arg) => {
      if (arg?.type === 'ArrayExpression') arg.elements.forEach((el) => check(el, { mustBeStatic: true, glob: true }))
      else check(arg, { mustBeStatic: true, glob: true })
    }

    return {
      ImportDeclaration: (node) => check(node.source),
      ExportNamedDeclaration: (node) => node.source && check(node.source),
      ExportAllDeclaration: (node) => check(node.source),
      ImportExpression: (node) => check(node.source, { mustBeStatic: true }),
      TSImportEqualsDeclaration: (node) =>
        node.moduleReference.type === 'TSExternalModuleReference' && check(node.moduleReference.expression),
      TSImportType: (node) => check(node.source ?? node.argument),
      CallExpression: (node) => {
        if (node.callee.type === 'Identifier' && node.callee.name === 'require' && node.arguments.length === 1) {
          check(node.arguments[0], { mustBeStatic: true })
        }
        if (isImportMetaProperty(node.callee, ['glob', 'globEager'])) checkGlob(node.arguments[0])
      },
      // new URL('./asset.svg', import.meta.url): Vite resolves the first argument like an import.
      NewExpression: (node) => {
        if (node.callee.type === 'Identifier' && node.callee.name === 'URL' && isImportMetaProperty(node.arguments[1], ['url'])) {
          check(node.arguments[0], { mustBeStatic: true })
        }
      },
    }
  },
}
