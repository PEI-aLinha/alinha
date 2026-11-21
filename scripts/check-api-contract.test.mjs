import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { URL } from 'node:url'
import { parse } from 'yaml'

const contract = parse(readFileSync(new URL('../contracts/openapi/alinha.yaml', import.meta.url), 'utf8'))
const generated = readFileSync(
  new URL('../frontend/packages/api-client/src/schema.generated.ts', import.meta.url),
  'utf8',
)

const resolveRef = (ref) =>
  ref
    .split('/')
    .slice(1)
    .reduce((node, key) => node?.[key], contract)

const parametersOf = (path, operation) =>
  [...(path.parameters ?? []), ...(operation.parameters ?? [])].map((parameter) =>
    parameter.$ref ? resolveRef(parameter.$ref) : parameter,
  )

test('the contract keeps the v1 conventions', () => {
  assert.equal(contract.openapi, '3.1.0')
  assert.ok(contract.paths['/api/me'])
  assert.ok(contract.paths['/api/plugins'])
  assert.ok(contract.paths['/api/{plugin}/ping'])

  const problem = contract.components.schemas.Problem
  assert.deepEqual(problem.required, ['type', 'title', 'status'])
  assert.equal(problem.properties.status.type, 'integer')

  for (const name of [
    'BadRequest',
    'Unauthorized',
    'Forbidden',
    'NotFound',
    'Conflict',
    'UnprocessableEntity',
  ]) {
    const response = contract.components.responses[name]
    assert.equal(response.content['application/problem+json'].schema.$ref, '#/components/schemas/Problem', name)
  }

  const tenant = contract.components.parameters.Tenant
  assert.equal(tenant.in, 'header')
  assert.equal(tenant.name, 'X-Alinha-Tenant')

  assert.deepEqual(contract.components.schemas.PageMeta.required, ['page', 'pageSize', 'total'])
  for (const name of ['Page', 'PageSize']) {
    assert.equal(contract.components.parameters[name].in, 'query', name)
  }
})

test('no tenant or organization is part of a path or query', () => {
  const tenantLike = /^(tenant|tenantId|org|orgId|organization|organizationId)$/i
  const tenantInPath = /\{(tenant|tenantId|org|orgId|organization|organizationId)\}/i

  for (const [path, pathItem] of Object.entries(contract.paths)) {
    assert.doesNotMatch(path, tenantInPath, `tenant in path ${path}`)
    for (const operation of Object.values(pathItem)) {
      if (!operation || typeof operation !== 'object' || !operation.responses) continue
      for (const parameter of parametersOf(pathItem, operation)) {
        if (parameter.in === 'path' || parameter.in === 'query') {
          assert.doesNotMatch(parameter.name, tenantLike, `${parameter.name} in ${path}`)
        }
      }
    }
  }
})

test('the temporary ping documents its tenant and session errors', () => {
  const responses = contract.paths['/api/{plugin}/ping'].get.responses
  for (const status of ['200', '400', '401', '403']) {
    assert.ok(responses[status], status)
  }
})

test('the generated api-client exposes the v1 operations', () => {
  for (const expected of ['getMe', 'listPlugins', 'pingPlugin', 'PluginManifest']) {
    assert.ok(generated.includes(expected), expected)
  }
})

test('every plugin operation documents 401 and 403', () => {
  const core = new Set(['/api/me', '/api/plugins'])
  for (const [path, item] of Object.entries(contract.paths)) {
    if (core.has(path)) continue
    for (const method of ['get', 'post', 'put', 'patch', 'delete']) {
      const operation = item[method]
      if (!operation) continue
      assert.ok(operation.responses['401'], `${method.toUpperCase()} ${path} has no 401`)
      assert.ok(operation.responses['403'], `${method.toUpperCase()} ${path} has no 403`)
    }
  }
})
