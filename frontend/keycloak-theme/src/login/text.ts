/** Field errors from Keycloak are plain text with HTML entities (`&#39;`); `Field` renders text, so decode them. */
export function plainText(html: string): string {
  if (!html) return ''
  return new DOMParser().parseFromString(html, 'text/html').body.textContent ?? ''
}
