import React from "react";
import "./_styles.scss";

/**
 * Connection Browser component
 * Embeds the HTML-based connection browser interface in an iframe
 */
const ConnectionBrowserPage = () => {
  return (
    <div className="connection-browser-container">
      <iframe
        src="/data/onvif-driver/connection-browser"
        className="connection-browser-iframe"
        title="ONVIF Connection Browser"
      />
    </div>
  );
};

export default ConnectionBrowserPage;
