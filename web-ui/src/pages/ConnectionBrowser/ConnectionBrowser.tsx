import App from '../../App'

/**
 * Connection Browser component — renders the full React Camera Driver UI.
 * Previously wrapped connection-browser.html in an iframe; now renders
 * the React app directly for consistency with all other modules.
 */
export default function ConnectionBrowserPage() {
  return <App />
}

