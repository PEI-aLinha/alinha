/**
 * Types generated from the backend OpenAPI spec (contracts/openapi/alinha.yaml).
 * Generate with:  pnpm dlx openapi-typescript ../../../contracts/openapi/alinha.yaml -o src/schema.ts
 * Until then, this file only exports hand-written shared types.
 */
export interface PluginManifestDto {
  id: string
  name: string
  kind: 'FEATURE' | 'FRAMEWORK' | 'PROVIDER'
  version: string
  publishes: string[]
  consumes: string[]
  permissions: string[]
}
