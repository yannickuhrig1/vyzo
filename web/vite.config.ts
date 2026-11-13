import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { defineConfig, type Plugin } from 'vite'

/**
 * Adresse et clé publique Supabase : lues dans gradle.properties, comme pour les applis Android et PC.
 * On peut viser un autre projet avec VYZO_SUPABASE_URL et VYZO_SUPABASE_KEY.
 */
function gradleProperty(name: string): string {
  const text = readFileSync(resolve(import.meta.dirname, '../gradle.properties'), 'utf8')
  const line = text.split('\n').find((l) => l.trim().startsWith(name + '='))
  if (!line) throw new Error(`${name} absent de gradle.properties`)
  return line.slice(line.indexOf('=') + 1).trim()
}

const supabaseUrl = process.env.VYZO_SUPABASE_URL || gradleProperty('vyzo.supabaseUrl')
const supabaseKey = process.env.VYZO_SUPABASE_KEY || gradleProperty('vyzo.supabaseKey')
const supabaseHost = new URL(supabaseUrl).host

/**
 * Politique de sécurité du site publié : aucun script, style ni image venant d'ailleurs, et des connexions vers
 * Supabase seulement. Ajoutée au build uniquement (le serveur de dev de Vite injecte des styles en ligne).
 */
function contentSecurityPolicy(): Plugin {
  const csp = [
    "default-src 'none'",
    "script-src 'self'",
    "style-src 'self'",
    "img-src 'self' data:",
    `connect-src https://${supabaseHost} wss://${supabaseHost}`,
    "manifest-src 'self'",
    "base-uri 'none'",
    "form-action 'none'",
  ].join('; ')
  return {
    name: 'vyzo-csp',
    apply: 'build',
    transformIndexHtml: (html) =>
      html.replace('<head>', `<head>\n    <meta http-equiv="Content-Security-Policy" content="${csp}">`),
  }
}

export default defineConfig({
  base: './',
  define: {
    __SUPABASE_URL__: JSON.stringify(supabaseUrl),
    __SUPABASE_KEY__: JSON.stringify(supabaseKey),
  },
  plugins: [contentSecurityPolicy()],
  build: { target: 'es2022', assetsInlineLimit: 0 },
})
