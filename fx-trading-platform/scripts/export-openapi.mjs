import { mkdir, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'

const sourceUrl =
  process.env.OPENAPI_SOURCE_URL ||
  `http://localhost:${process.env.OPENAPI_BACKEND_PORT || '8080'}/v3/api-docs`
const outputPath = resolve(process.env.OPENAPI_OUTPUT || 'artifacts/openapi/backend-openapi.json')

const controller = new AbortController()
const timeout = setTimeout(() => controller.abort(), Number(process.env.OPENAPI_EXPORT_TIMEOUT_MS || 15000))

try {
  const response = await fetch(sourceUrl, {
    headers: { Accept: 'application/json' },
    signal: controller.signal
  })
  if (!response.ok) {
    throw new Error(`OpenAPI export failed: ${response.status} ${response.statusText}`)
  }
  const document = await response.json()
  if (!document || typeof document.openapi !== 'string' || !document.paths || typeof document.paths !== 'object') {
    throw new Error('OpenAPI export failed: response is not a valid OpenAPI document')
  }
  await mkdir(dirname(outputPath), { recursive: true })
  await writeFile(outputPath, `${JSON.stringify(document, null, 2)}\n`)
  console.log(`OpenAPI exported from ${sourceUrl} to ${outputPath}`)
} finally {
  clearTimeout(timeout)
}
