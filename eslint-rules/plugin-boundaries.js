// Architecture rule for plugin frontends (RNF17): a plugin imports only @alinha/plugin-sdk,
// @alinha/ui and @alinha/api-client from the platform, and never reaches outside its own
// frontend folder (another plugin, the shell). Relative imports are resolved against the file,
// so nesting depth does not matter. Covers static, type-only, re-export, dynamic and require imports
// (require.resolve, createRequire), and Vite's import.meta.glob and new URL(..., import.meta.url).
// Any other bare name must be a package the plugin declares in its package.json, or a Node builtin, so that a path
// alias (Vite resolve.alias, tsconfig paths: `@/`, `~/`, `#x`) cannot reach into the repo dressed as a package name.
// Option `root`: the repo root (eslint.config.js passes its own folder). Without it, ESLint's cwd is used,
// and the rule would not recognise plugin files when ESLint runs from inside a plugin.
import { readFileSync } from 'node:fs'
import { builtinModules } from 'node:module'
import { dirname, isAbsolute, join, posix, relative, resolve, sep } from 'node:path'

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

/** A relative glob `pattern` resolved against the glob's relative `base`, still written as a relative path. */
function underBase(base, pattern) {
  const joined = posix.join(base, pattern)
  return isRelative(joined) || isAbsolute(joined) ? joined : `./${joined}`
}

/** Key name of a non-computed object property (`base`, `'base'`), or undefined. */
const propertyName = (prop) =>
  prop.computed ? undefined : prop.key.type === 'Identifier' ? prop.key.name : staticValue(prop.key)

/** `createRequire(...)` or `<module>.createRequire(...)` */
const isCreateRequire = (node) =>
  node?.type === 'CallExpression' &&
  ((node.callee.type === 'Identifier' && node.callee.name === 'createRequire') ||
    (node.callee.type === 'MemberExpression' && !node.callee.computed && node.callee.property.name === 'createRequire'))

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

    const reportDynamic = (node) => context.report({ node, messageId: 'dynamic' })

    /** Names bound to a require function in this file: `require`, and variables set from createRequire(...). */
    const requireNames = new Set(['require'])
    const isRequire = (node) => (node.type === 'Identifier' && requireNames.has(node.name)) || isCreateRequire(node)

    /** Reports `sourceNode` if it breaks the boundary; true when it is a static value that is fine. */
    const check = (sourceNode, { mustBeStatic = false, glob = false, base } = {}) => {
      const value = staticValue(sourceNode)
      if (value === undefined) {
        if (mustBeStatic) reportDynamic(sourceNode)
        return false
      }
      // A glob may exclude files with a leading "!"; the path after it must stay inside too.
      const pattern = glob && value.startsWith('!') ? value.slice(1) : value
      const spec = base && isRelative(pattern) ? underBase(base, pattern) : pattern
      const messageId = violationOf(spec, filename, pluginRoot)
      if (messageId) context.report({ node: sourceNode, messageId, data: { spec: value } })
      return !messageId
    }

    /**
     * The `base` option of import.meta.glob (relative patterns resolve from it), checked like an import.
     * Options that cannot be read statically (a variable, a spread, a computed key) could hide a base: reported.
     */
    const globBase = (options) => {
      if (!options) return undefined
      if (options.type !== 'ObjectExpression') return reportDynamic(options)
      let base
      for (const prop of options.properties) {
        const name = prop.type === 'Property' ? propertyName(prop) : undefined
        if (name === undefined) reportDynamic(prop)
        // A base that is already reported is not applied to the patterns, so the same escape is reported once.
        else if (name === 'base' && check(prop.value, { mustBeStatic: true })) base = staticValue(prop.value)
      }
      return base
    }

    /** import.meta.glob('...') or import.meta.glob(['...', '!...']), with an optional { base } */
    const checkGlob = ([patterns, options]) => {
      const opts = { mustBeStatic: true, glob: true, base: globBase(options) }
      if (patterns?.type === 'ArrayExpression') patterns.elements.forEach((el) => check(el, opts))
      else check(patterns, opts)
    }

    return {
      ImportDeclaration: (node) => check(node.source),
      ExportNamedDeclaration: (node) => node.source && check(node.source),
      ExportAllDeclaration: (node) => check(node.source),
      ImportExpression: (node) => check(node.source, { mustBeStatic: true }),
      TSImportEqualsDeclaration: (node) =>
        node.moduleReference.type === 'TSExternalModuleReference' && check(node.moduleReference.expression),
      TSImportType: (node) => check(node.source ?? node.argument),
      // const req = createRequire(import.meta.url): from here on, req(...) is a require.
      VariableDeclarator: (node) => {
        if (node.id.type === 'Identifier' && isCreateRequire(node.init)) requireNames.add(node.id.name)
      },
      CallExpression: (node) => {
        const { callee, arguments: args } = node
        // require('x'), req('x'), createRequire(...)('x')
        if (isRequire(callee) && args.length > 0) check(args[0], { mustBeStatic: true })
        // require.resolve('x'), req.resolve('x')
        if (callee.type === 'MemberExpression' && !callee.computed && callee.property.name === 'resolve' && isRequire(callee.object)) {
          check(args[0], { mustBeStatic: true })
        }
        // createRequire(base): require calls resolve relative to base, so base must stay inside too.
        if (isCreateRequire(node) && !isImportMetaProperty(args[0], ['url'])) check(args[0], { mustBeStatic: true })
        if (isImportMetaProperty(callee, ['glob', 'globEager'])) checkGlob(args)
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
