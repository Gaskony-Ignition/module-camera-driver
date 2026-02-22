/**
 * Camera Driver Perspective component theme.
 * Single source of truth for all colours and fonts used in the React components.
 *
 * Changing a value here propagates to both CameraViewer and CameraGrid
 * without touching either component file.
 */
export const CAMERA_THEME = {
    /** Primary dark background for camera cells */
    bg: '#1c1c22',
    /** Darker background used for the grid container */
    bgGrid: '#111118',
    /** Primary text colour */
    text: '#e0e0e8',
    /** Muted / secondary text colour */
    textMuted: '#8b8fa0',
    /** Error state colour */
    error: '#e05555',
    /** Success / streaming active colour */
    success: '#4caf50',
    /** Pending / connecting colour */
    warning: '#ffa726',
    /** Cell border / placeholder border colour */
    border: '#3c3c44',
    /** Gradient applied to the bottom overlay strip */
    overlayGradient: 'linear-gradient(transparent, rgba(0,0,0,0.7))',
    /** System font stack — matches connection-browser.html --font-sans */
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
} as const;
