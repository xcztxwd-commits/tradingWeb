import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const featurePageSource = readFileSync(join(currentDir, 'FeatureCrudPage.tsx'), 'utf8')
const apiSource = readFileSync(join(currentDir, '../services/adminApi.ts'), 'utf8')
const loginSource = readFileSync(join(currentDir, 'LoginPage.tsx'), 'utf8')
const sharedTypesSource = readFileSync(join(currentDir, '../../../../packages/shared-types/src/generated/openapi.ts'), 'utf8')

describe('feature action permissions', () => {
  it('stores backend authorities during admin login', () => {
    assert.match(sharedTypesSource, /authorities: string\[\]/)
    assert.match(loginSource, /setAdminAuthTokens\(auth\.accessToken,\s*auth\.refreshToken,\s*auth\.authorities/)
  })

  it('filters toolbar and row actions by action-level permissions', () => {
    assert.match(featurePageSource, /getAdminAuthorities/)
    assert.match(featurePageSource, /ACTION_PERMISSION_MAP/)
    assert.match(featurePageSource, /canRunAction\(/)
    assert.match(featurePageSource, /visibleToolbarActions/)
    assert.match(featurePageSource, /visibleRowActions\(/)
    assert.doesNotMatch(featurePageSource, /data\.toolbarActions\.map\(\(action\)/)
    assert.doesNotMatch(featurePageSource, /data\.rowActions\.map\(\(action\)/)
  })

  it('requires explicit confirmation text for high-risk feature actions', () => {
    assert.match(featurePageSource, /ACTION_CONFIRMATION_MAP/)
    assert.match(featurePageSource, /confirmationText/)
    assert.match(featurePageSource, /requiredConfirmationText/)
    assert.match(featurePageSource, /executeAction\(dialog\.action,\s*dialog\.row,\s*actionPayload\(/)
  })

  it('forwards confirmation text to real business APIs', () => {
    assert.match(apiSource, /confirmationText: payload\.confirmationText/)
    assert.match(apiSource, /confirmationText: request\.payload\?\.confirmationText/)
    assert.match(featurePageSource, /CONFIRM_APPROVE/)
  })
})
