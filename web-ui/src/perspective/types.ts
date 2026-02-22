/**
 * Shared type definitions for Camera Driver Perspective components.
 * Single source of truth — import from here, never redefine locally.
 */

/** How the component obtains the video feed. */
export type StreamMode = 'auto' | 'mse' | 'snapshot';

/** Current state of the stream connection. */
export type StreamStatus = 'loading' | 'streaming' | 'error' | 'idle';
