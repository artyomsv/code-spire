// Pulls in Vite's ambient client types, which declare the non-code modules a bundler resolves but
// the compiler otherwise knows nothing about — `*.css` here (main.tsx imports ./index.css for its
// side effect), plus `import.meta.env` should this app ever read it.
//
// Lives under src/ so the tsconfig "include" set picks it up. Without it a side-effect import of an
// untyped module was merely *tolerated*: TypeScript 5.6 stayed silent, and TypeScript 7 reports it
// as TS2882. The declaration was always the missing piece — the newer compiler only stopped
// covering for its absence.
/// <reference types="vite/client" />
