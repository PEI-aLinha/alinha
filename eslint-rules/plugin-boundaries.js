// Architecture rule for plugin frontends (RNF17): a plugin imports only @alinha/plugin-sdk,
// @alinha/ui and @alinha/api-client from the platform, and never reaches outside its own
// frontend folder (another plugin, the shell). Relative imports are resolved against the file,
// so nesting depth does not matter. Covers static, type-only, re-export, dynamic and require imports
// (require.resolve, createRequire), and Vite's import.meta.glob and new URL(..., import.meta.url).
// Any other bare name must be a package the plugin declares in its package.json, or a Node builtin, so that a path
// alias (Vite resolve.alias, tsconfig paths: `@/`, `~/`, `#x`) cannot reach into the repo dressed as a package name.
// Option `root`: the repo root (eslint.config.js passes its own folder). Without it, ESLint's cwd is used,
// and the rule would not recognise plugin files when ESLint runs from inside a plugin.
import { readFileSync, statSync } from 'node:fs'
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
// A dependency installed from a path is code from the repo, not a package: `link:../x`, `file:../x`, `../x`, `/x`.
const PATH_VERSION = /^(link:|file:|portal:|\.|\/|~)/
// package.json contents per plugin, keyed by its mtime so an IDE's long-running ESLint sees edits.
const declaredPackagesCache = new Map()

/** Package names in `<pluginRoot>/package.json`. Empty when it is missing or unreadable, so the rule fails closed. */
export function declaredPackagesOf(pluginRoot) {
  const file = join(pluginRoot, 'package.json')
  let mtime
  try {
    mtime = statSync(file).mtimeMs
  } catch {
    return new Set() // No package.json: nothing is declared, every bare name is reported. Not cached.
  }
  const cached = declaredPackagesCache.get(pluginRoot)
  if (cached?.mtime === mtime) return cached.names
  let names = new Set()
  try {
    const manifest = JSON.parse(readFileSync(file, 'utf8'))
    const entries = DEPENDENCY_FIELDS.flatMap((field) => Object.entries(manifest[field] ?? {}))
    names = new Set(entries.filter(([, version]) => !PATH_VERSION.test(String(version))).map(([name]) => name))
  } catch {
    // Invalid JSON: nothing is declared until it is fixed.
  }
  declaredPackagesCache.set(pluginRoot, { mtime, names })
  return names
}

/** `react` for 'react/jsx-runtime', `@scope/name` for '@scope/name/sub'; null if `spec` is not a valid package name. */
export function packageNameOf(spec) {
  const [first, second] = spec.split('/')
  if (!first.startsWith('@')) return first
  return first.length > 1 && second ? `${first}/${second}` : null
}

/** TypeScript resolves `name` (and `@scope/name`) to `@types/name` (`@types/scope__name`) as well. */
const typesPackageOf = (name) => `@types/${name.startsWith('@') ? name.slice(1).replace('/', '__') : name}`

const isDeclared = (name, pluginRoot) => {
  const declared = declaredPackagesOf(pluginRoot)
  return name !== null && (declared.has(name) || declared.has(typesPackageOf(name)))
}

const isNodeBuiltin = (spec) => spec.startsWith('node:') || builtinModules.includes(packageNameOf(spec))

/** A package specifier without Vite's query (`?inline`, `?url`) or a hash. */
const withoutQuery = (spec) => spec.replace(/[?#].*$/, '')

const isRelative = (spec) => spec === '.' || spec === '..' || spec.startsWith('./') || spec.startsWith('../')

/** A relative glob `pattern` resolved against the glob's relative `base`, still written as a relative path. */
function underBase(base, pattern) {
  const joined = posix.join(base, pattern)
  return isRelative(joined) || isAbsolute(joined) ? joined : `./${joined}`
}

/** Key name of a non-computed object property (`base`, `'base'`), or undefined. */
const propertyName = (prop) =>
  prop.computed ? undefined : prop.key.type === 'Identifier' ? prop.key.name : staticValue(prop.key)

/** Static property name of `a.b` or `a['b']`, or undefined when it is computed at runtime. */
const memberName = (node) => (node.computed ? staticValue(node.property) : node.property.name)

const NODE_MODULE_SOURCES = ['module', 'node:module']

/** Why `spec`, imported from `filename` inside `pluginRoot`, breaks the boundary; null if it is fine. */
export function violationOf(spec, filename, pluginRoot) {
  // file: URLs name a path on disk directly, like an absolute path. So does Vite's /@fs/ prefix.
  if (isAbsolute(spec) || /^file:/i.test(spec)) return 'outside'
  if (isRelative(spec)) {
    const target = relative(pluginRoot, resolve(dirname(filename), spec))
    return target === '..' || target.startsWith(`..${sep}`) || isAbsolute(target) ? 'outside' : null
  }
  const pkg = withoutQuery(spec)
  // 'react/../../x': a ".." in a package subpath climbs out of the package folder.
  if (pkg.split('/').includes('..')) return 'outside'
  if (pkg.startsWith('@alinha/')) return isAllowedPlatformPackage(pkg) ? null : 'platformPackage'
  if (isNodeBuiltin(pkg)) return null
  return isDeclared(packageNameOf(pkg), pluginRoot) ? null : 'undeclared'
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
    const { sourceCode } = context

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
    const checkGlob = (call) => {
      const [patterns = call, options] = call.arguments
      const opts = { mustBeStatic: true, glob: true, base: globBase(options) }
      if (patterns.type === 'ArrayExpression') patterns.elements.forEach((el) => check(el, opts))
      else check(patterns, opts)
    }

    // require and the functions made by createRequire are followed through scope analysis at the end of the file,
    // so a call before the declaration (hoisting) or after a later assignment is still checked. A require that is
    // passed around as a value (aliased, destructured) cannot be followed and is reported as dynamic.

    /** require('x'): the first argument must be a static path that stays inside. */
    const checkRequireCall = (call) => check(call.arguments[0] ?? call, { mustBeStatic: true })

    /** A reference to a require function: called, or require.resolve called. Anything else is reported. */
    const checkRequireUse = (node) => {
      const { parent } = node
      if (parent.type === 'CallExpression' && parent.callee === node) return checkRequireCall(parent)
      if (parent.type === 'UnaryExpression' && parent.operator === 'typeof') return
      if (parent.type !== 'MemberExpression' || parent.object !== node) return reportDynamic(node)
      const name = memberName(parent)
      if (name === undefined) return reportDynamic(parent)
      if (name !== 'resolve') return // require.main, require.cache: no path is resolved
      const call = parent.parent
      if (call.type === 'CallExpression' && call.callee === parent) checkRequireCall(call)
      else reportDynamic(parent)
    }

    const checkedVariables = new Set()
    /** Every read of `variable` is a use of a require function. */
    const checkRequireVariable = (variable) => {
      if (!variable || checkedVariables.has(variable)) return
      checkedVariables.add(variable)
      variable.references.filter((ref) => ref.isRead()).forEach((ref) => checkRequireUse(ref.identifier))
    }

    /** createRequire(base): base must be import.meta.url or stay inside; the result is followed like require. */
    const checkCreateRequireCall = (call) => {
      const [base] = call.arguments
      if (!isImportMetaProperty(base, ['url'])) check(base ?? call, { mustBeStatic: true })
      const { parent } = call
      if (parent.type === 'VariableDeclarator' && parent.init === call && parent.id.type === 'Identifier') {
        sourceCode.getDeclaredVariables(parent).forEach(checkRequireVariable)
      } else if (parent.type === 'AssignmentExpression' && parent.right === call && parent.left.type === 'Identifier') {
        const ref = sourceCode.getScope(parent).references.find((r) => r.identifier === parent.left)
        if (ref?.resolved) checkRequireVariable(ref.resolved)
        else reportDynamic(call)
      } else {
        checkRequireUse(call)
      }
    }

    /** A reference to createRequire itself: it must be called. */
    const checkCreateRequireUse = (node) => {
      const { parent } = node
      if (parent.type === 'CallExpression' && parent.callee === node) checkCreateRequireCall(parent)
      else reportDynamic(node)
    }

    const createRequireImports = []
    const createRequireMemberCalls = []

    return {
      ImportDeclaration: (node) => check(node.source),
      ExportNamedDeclaration: (node) => node.source && check(node.source),
      ExportAllDeclaration: (node) => check(node.source),
      ImportExpression: (node) => check(node.source, { mustBeStatic: true }),
      TSImportEqualsDeclaration: (node) =>
        node.moduleReference.type === 'TSExternalModuleReference' && check(node.moduleReference.expression),
      TSImportType: (node) => check(node.source ?? node.argument),
      // import { createRequire as cr } from 'node:module'
      ImportSpecifier: (node) => {
        const imported = node.imported.type === 'Identifier' ? node.imported.name : node.imported.value
        if (imported === 'createRequire' && NODE_MODULE_SOURCES.includes(node.parent.source.value)) createRequireImports.push(node)
      },
      CallExpression: (node) => {
        // module.createRequire(...), module['createRequire'](...)
        if (node.callee.type === 'MemberExpression' && memberName(node.callee) === 'createRequire') createRequireMemberCalls.push(node)
        if (isImportMetaProperty(node.callee, ['glob', 'globEager'])) checkGlob(node)
      },
      'Program:exit': (program) => {
        const globalScope = sourceCode.getScope(program)
        const globalRefs = (name) => [
          ...globalScope.through.filter((ref) => ref.identifier.name === name),
          ...(globalScope.set.get(name)?.references ?? []), // when `require` is a configured global
        ]
        globalRefs('require').forEach((ref) => checkRequireUse(ref.identifier))
        globalRefs('createRequire').forEach((ref) => checkCreateRequireUse(ref.identifier))
        createRequireImports.forEach((node) =>
          sourceCode.getDeclaredVariables(node).forEach((v) => v.references.forEach((ref) => checkCreateRequireUse(ref.identifier))),
        )
        createRequireMemberCalls.forEach(checkCreateRequireCall)
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
