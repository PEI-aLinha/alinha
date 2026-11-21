import { createGetKcContextMock } from 'keycloakify/login/KcContext'
import { kcEnvDefaults, themeNames } from '../kc.gen'
import type { KcContextExtension, KcContextExtensionPerPage } from './KcContext'

const kcContextExtension: KcContextExtension = { themeName: themeNames[0], properties: { ...kcEnvDefaults } }
const kcContextExtensionPerPage: KcContextExtensionPerPage = {}

/** Mocked Keycloak context for `pnpm dev` (no Keycloak needed): http://localhost:5174/?page=register.ftl */
export const { getKcContextMock } = createGetKcContextMock({
  kcContextExtension,
  kcContextExtensionPerPage,
  overrides: {
    // The same as the realm: Portuguese by default, English as the second language, email as the username.
    locale: {
      currentLanguageTag: 'pt',
      supported: [
        { languageTag: 'pt', label: 'Português', url: '?kc_locale=pt' },
        { languageTag: 'en', label: 'English', url: '?kc_locale=en' },
      ],
    },
    realm: { displayName: 'aLinha', registrationEmailAsUsername: true },
  },
  overridesPerPage: {
    'register.ftl': { passwordPolicies: { length: 12, digits: 1 } },
    'login.ftl': { realm: { loginWithEmailAllowed: true } },
    'login-reset-password.ftl': { realm: { loginWithEmailAllowed: true } },
  },
})
