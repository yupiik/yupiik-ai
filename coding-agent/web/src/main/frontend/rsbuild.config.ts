import { defineConfig } from '@rsbuild/core';
import { pluginReact } from '@rsbuild/plugin-react';

export default defineConfig({
  plugins: [pluginReact()],
  html: {
    title: 'YCA',
    tags: [
      // Rsbuild strips extra <link rel="icon">/apple-touch-icon from the index.html template (it
      // manages favicon extraction itself), so the higher-density PNG and iOS touch icons are
      // injected here reliably. The base favicon.ico (in index.html) is what browsers request by
      // default; these add crisp variants for high-DPI displays and iOS home-screen bookmarks.
      {
        tag: 'link',
        attrs: { rel: 'icon', type: 'image/png', sizes: '32x32', href: '/favicon-32x32.png' },
        injectTo: 'head',
      },
      {
        tag: 'link',
        attrs: { rel: 'icon', type: 'image/png', sizes: '16x16', href: '/favicon-16x16.png' },
        injectTo: 'head',
      },
      {
        tag: 'link',
        attrs: { rel: 'apple-touch-icon', sizes: '180x180', href: '/apple-touch-icon.png' },
        injectTo: 'head',
      },
    ],
  },
  output: {
    cleanDistPath: true,
    distPath: {
      root: '../../../target/classes/META-INF/resources',
    },
  },
  server: {
    proxy: {
      '/api': 'http://127.0.0.1:6060',
    },
  },
});