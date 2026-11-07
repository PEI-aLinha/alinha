/**
 * Tipos gerados a partir do OpenAPI do backend (contracts/openapi/alinha.yaml).
 * Gerar com:  pnpm dlx openapi-typescript ../../../contracts/openapi/alinha.yaml -o src/schema.ts
 * Até lá, este ficheiro só exporta tipos partilhados escritos à mão.
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
