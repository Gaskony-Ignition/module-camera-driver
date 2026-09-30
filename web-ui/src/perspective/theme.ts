/**
 * Camera Driver Perspective component theme.
 * Single source of truth for all colours and fonts used in the React components.
 *
 * Colour palette: Catppuccin Mocha — aligned with all Gaskony Ignition modules
 * (AI Terminal, Git Integration, PLC Emulator, Python3 Integration).
 *
 * Changing a value here propagates to both CameraViewer and CameraGrid
 * without touching either component file.
 */
export const CAMERA_THEME = {
    /** Primary dark background for camera cells (Catppuccin Mantle) */
    bg: '#181825',
    /** Darker background used for the grid container (Catppuccin Crust) */
    bgGrid: '#11111b',
    /** Primary text colour (Catppuccin Text) */
    text: '#cdd6f4',
    /** Muted / secondary text colour (Catppuccin Overlay0) */
    textMuted: '#6c7086',
    /** Error state colour (Catppuccin Red) */
    error: '#f38ba8',
    /** Success / streaming active colour (Catppuccin Green) */
    success: '#a6e3a1',
    /** Pending / connecting colour (Catppuccin Yellow) */
    warning: '#f9e2af',
    /** Cell border / placeholder border colour (Catppuccin Surface0) */
    border: '#313244',
    /** Gradient applied to the bottom overlay strip */
    overlayGradient: 'linear-gradient(transparent, rgba(0,0,0,0.7))',
    /** System font stack — matches connection-browser.html --font-sans */
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
} as const;
