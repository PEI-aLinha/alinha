// Architecture rule for plugin frontends (RNF17): a plugin imports only @alinha/plugin-sdk,
// @alinha/ui and @alinha/api-client from the platform, and never reaches outside its own
// frontend folder (another plugin, the shell). Relative imports are resolved against the file,
// so nesting depth does not matter. Covers static, type-only, re-export, dynamic and require imports,
// and Vite's import.meta.glob and new URL(..., import.meta.url).
// Option `root`: the repo root (eslint.config.js passes its own folder). Without it, ESLint's cwd is used,
// and the rule would not recognise plugin files when ESLint runs from inside a plugin.
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path'

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

/** Why `spec`, imported from `filename` inside `pluginRoot`, breaks the boundary; null if it is fine. */
export function violationOf(spec, filename, pluginRoot) {
  if (spec.startsWith('@alinha/')) return isAllowedPlatformPackage(spec) ? null : 'platformPackage'
  // file: URLs name a path on disk directly, like an absolute path.
  if (isAbsolute(spec) || /^file:/i.test(spec)) return 'outside'
  if (spec === '.' || spec === '..' || spec.startsWith('./') || spec.startsWith('../')) {
    const target = relative(pluginRoot, resolve(dirname(filename), spec))
    return target === '..' || target.startsWith(`..${sep}`) || isAbsolute(target) ? 'outside' : null
  }
  // Any other bare name is taken to be a third-party package (react, react-router, ...). This relies on there
  // being no path aliases (Vite resolve.alias, tsconfig paths) that map a bare name into the repo: add one and
  // it bypasses this rule, so check it here first.
  return null
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
