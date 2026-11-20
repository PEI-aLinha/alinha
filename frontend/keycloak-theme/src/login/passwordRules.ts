import type { PasswordPolicies } from 'keycloakify/login/KcContext'

export interface PasswordRule {
  label: string
  test: (password: string) => boolean
}

type Msg = (key: 'alinhaRuleLength' | 'alinhaRuleLettersDigits', ...args: string[]) => string

/**
 * Rules shown under the password, from the realm's password policy (`length(n)`, `digits(n)`; the realm pairs digits
 * with a letter pattern, so one rule says "letras e números"). Display only: Keycloak checks the policy on submit.
 */
export function passwordRules(policies: PasswordPolicies | undefined, msgStr: Msg): PasswordRule[] {
  const rules: PasswordRule[] = []
  if (policies?.length) {
    const min = policies.length
    rules.push({ label: msgStr('alinhaRuleLength', String(min)), test: (p) => p.length >= min })
  }
  if (policies?.digits) {
    rules.push({ label: msgStr('alinhaRuleLettersDigits'), test: (p) => /\p{L}/u.test(p) && /\d/.test(p) })
  }
  return rules
}
