import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import tailwindcss from '@tailwindcss/vite'
import fs from 'node:fs'
import { createRequire } from 'node:module'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * Hermey Android renderer build — a faithful port of the Hermes Desktop
 * renderer build (apps/desktop/vite.config.ts). Same Tailwind v4 pipeline,
 * same bundled fonts, same emojibase local assets, same chunking strategy.
 * Nothing here diverges from the desktop build; the ONLY differences are
 * the entry (mounts into the Capacitor WebView instead of Electron) and
 * the fact that there is no Electron main bundle.
 */

const __dirname: string = path.dirname(fileURLToPath(import.meta.url))

const requireFromApp = createRequire(path.join(__dirname, 'package.json'))

const emojibaseDir = (() => {
  for (const candidate of [
    path.resolve(__dirname, 'node_modules/emojibase-data'),
    path.resolve(__dirname, '../node_modules/emojibase-data')
  ]) {
    try {
      fs.accessSync(candidate)
      return candidate
    } catch {
      /* try next */
    }
  }
  return null
})()

const EMOJIBASE_PATH = /^[a-z-]+\/(data|messages|shortcodes\/emojibase)\.json$/

const emojibaseAssets = () => ({
  name: 'hermes:emojibase-assets',
  configureServer(server: {
    middlewares: { use: (route: string, handler: (req: unknown, res: unknown, next: () => void) => void) => void }
  }) {
    server.middlewares.use('/emojibase', (req: any, res: any, next: () => void) => {
      const rel = (req.url ?? '').split('?')[0].replace(/^\/+/, '')
      if (!emojibaseDir || !EMOJIBASE_PATH.test(rel)) {
        return next()
      }
      fs.readFile(path.join(emojibaseDir, rel), (err: unknown, buf: Buffer) => {
        if (err) {
          return next()
        }
        res.setHeader('Content-Type', 'application/json')
        res.setHeader('Cache-Control', 'public, max-age=31536000, immutable')
        res.end(buf)
      })
    })
  },
  generateBundle(this: { emitFile: (asset: { type: 'asset'; fileName: string; source: Uint8Array }) => void }) {
    if (!emojibaseDir) {
      return
    }
    for (const rel of ['en/data.json', 'en/messages.json', 'en/shortcodes/emojibase.json']) {
      this.emitFile({
        type: 'asset',
        fileName: `emojibase/${rel}`,
        source: fs.readFileSync(path.join(emojibaseDir, rel))
      })
    }
  }
})

export default defineConfig(({ command }) => ({
  // Relative base: the Capacitor WebView loads the bundle from the Android
  // app's own https://localhost asset server (capacitor:// on iOS, https on
  // Android with @capacitor/android), so every asset path must be relative.
  base: './',
  plugins: [react(), tailwindcss(), emojibaseAssets()],
  css: {
    postcss: { plugins: [] }
  },
  build: {
    manifest: 'renderer-manifest.json',
    chunkSizeWarningLimit: 25000,
    rolldownOptions: {
      output: {
        advancedChunks: {
          groups: [
            {
              name: 'vendor-react',
              test: /node_modules[\\/](react|react-dom|scheduler|react-router|@tanstack[\\/]react-query)[\\/]/
            },
            {
              name: 'vendor-md',
              test: /node_modules[\\/](property-information|hast-util-[^\\/]+|mdast-util-[^\\/]+|micromark[^\\/]*|unist-util-[^\\/]+|vfile[^\\/]*|unified|stringify-entities|space-separated-tokens|comma-separated-tokens|zwitch|html-void-elements|devlop|style-to-js|style-to-object|clsx)[\\/]/
            },
            {
              name: 'vendor-util',
              test: /node_modules[\\/](lodash-es|es-toolkit|uuid|dayjs|d3-array|d3-color|d3-force|d3-interpolate|d3-time[^\\/]*|dompurify|stylis)[\\/]/
            },
            {
              name: 'mermaid',
              test: /node_modules[\\/](mermaid|cytoscape|dagre|khroma|elkjs|d3|d3-[^\\/]+|@mermaid-js)[\\/]/
            },
            {
              name: 'shiki',
              test: /node_modules[\\/](shiki|@shikijs|react-shiki|@streamdown[\\/]code|oniguruma-to-es|oniguruma-parser|regex(-[^\\/]+)?)[\\/]/
            },
            { name: 'katex', test: /node_modules[\\/]katex[\\/]/ }
          ]
        }
      }
    }
  },
  optimizeDeps: {
    exclude: ['driver.js', 'driver.js/dist/driver.js.iife.js', 'driver.js/dist/driver.js.iife.js?raw', 'driver.js/dist/driver.css?raw']
  },
  resolve: {
    // An ARRAY, not an object: Vite's alias accepts both, but the
    // electron-shim entry is a {find, replacement} pair, which is only
    // legal as an array element — an object literal as an unkeyed
    // property is a syntax error.
    alias: [
      { find: '@', replacement: path.resolve(__dirname, './src') },
      { find: '@hermes/plugin-sdk', replacement: path.resolve(__dirname, './src/sdk/index.ts') },
      { find: '@hermes/shared/billing', replacement: path.resolve(__dirname, '../shared/src/billing-types.ts') },
      { find: '@hermes/shared/color', replacement: path.resolve(__dirname, '../shared/src/color.ts') },
      { find: '@hermes/shared', replacement: path.resolve(__dirname, '../shared/src') },
      // Electron main-process modules the renderer imports TYPES from. On
      // Android these resolve to the shim mirrors under electron-shim/ —
      // same exports, no electron/node imports. The regex alias covers every
      // relative depth (../electron/x, ../../electron/x, …) because the
      // renderer files sit at varying depths, exactly like the desktop.
      {
        find: /^(?:\.{2}\/)+electron\/(.*)$/,
        replacement: path.resolve(__dirname, './electron-shim') + '/$1'
      },
      // driver.js only enters the graph through the tour's DYNAMIC import
      // chain; its exports map exposes neither the prebuilt IIFE nor
      // package.json, so resolve the main entry and point at its sibling.
      // Both keys on purpose: alias matching is exact, and the id reaches it
      // with the `?raw` query still attached in dev but stripped in some
      // build paths. (Mirrors apps/desktop/vite.config.ts.)
      {
        find: 'driver.js/dist/driver.js.iife.js?raw',
        replacement: `${path.join(path.dirname(requireFromApp.resolve('driver.js')), 'driver.js.iife.js')}?raw`
      },
      {
        find: 'driver.js/dist/driver.js.iife.js',
        replacement: path.join(path.dirname(requireFromApp.resolve('driver.js')), 'driver.js.iife.js')
      }
    ],
    dedupe: ['react', 'react-dom', 'react-router', '@tanstack/react-query']
  },
  server: {
    port: 5174,
    host: '127.0.0.1'
  },
  preview: {
    port: 4174,
    host: '127.0.0.1'
  }
}))
