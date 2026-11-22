// Asset imports resolved by Vite to a URL. Declared here (not only through vite/client) so plugin typechecks, which
// compile @alinha/ui sources with their own tsconfig, know them too.
declare module '*.svg' {
  const url: string
  export default url
}
