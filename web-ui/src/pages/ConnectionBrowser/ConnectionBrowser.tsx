import React from "react";
import "./_styles.scss";

/**
 * Connection Browser component
 * Embeds the HTML-based connection browser interface in an iframe.
 *
 * The key prop on the iframe is critical — without it, when multiple modules
 * register structurally identical iframe-based pages, React's reconciler
 * reuses the iframe DOM element instead of recreating it, causing the page
 * to appear "stuck" when navigating between modules.
 */
const ConnectionBrowserPage = () => {
  return (
    <div className="connection-browser-container">
      <iframe
        key="camera-connection-browser"
        src="/data/camera-driver/connection-browser"
        className="connection-browser-iframe"
        title="Camera Connection Browser"
      />
    </div>
  );
};

export default ConnectionBrowserPage;
