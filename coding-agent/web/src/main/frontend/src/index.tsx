import React from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';
import { App } from './App';
// Self-hosted brand fonts (no Google Fonts CDN, works air-gapped). The minisite uses Inter
// 300-700 for the UI sans and JetBrains Mono 400/500 for mono; latin subset only keeps the
// bundle lean. Imports must precede index.css so index.css can reference the families.
import '@fontsource/inter/latin-300.css';
import '@fontsource/inter/latin-400.css';
import '@fontsource/inter/latin-500.css';
import '@fontsource/inter/latin-600.css';
import '@fontsource/inter/latin-700.css';
import '@fontsource/jetbrains-mono/latin-400.css';
import '@fontsource/jetbrains-mono/latin-500.css';
import './index.css';

// apply the persisted theme before first paint
(function () {
  const saved = localStorage.getItem('yca-theme');
  const theme = saved ?? (window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark');
  document.documentElement.setAttribute('data-theme', theme);
})();

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </React.StrictMode>,
);