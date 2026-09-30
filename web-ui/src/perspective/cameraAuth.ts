import { useEffect, useState } from 'react';
import { ComponentStore, ComponentStoreDelegate } from '@inductiveautomation/perspective-client';

/**
 * Client side of the camera Perspective auth bridge.
 *
 * A Perspective session cannot authenticate to /data/camera-driver/* directly (it is not a
 * Gateway WebUI session), so each camera component's gateway-side CameraComponentDelegate hands
 * the browser a short-lived token. This module:
 *   - holds the current token in a process-global singleton (any valid token authenticates any
 *     camera request, so one holder serves every Camera Viewer / Camera Grid on the page),
 *   - exposes a React hook + a header helper for the streaming code, and
 *   - defines CameraAuthDelegate, which requests the token on mount and refreshes it before expiry.
 */

const EVENT_REQUEST = 'camera-auth-request';
const EVENT_TOKEN = 'camera-auth-token';
const DEFAULT_TTL_MS = 120000;

type Listener = (token: string | null) => void;

class TokenHolder {
    private token: string | null = null;
    private readonly listeners = new Set<Listener>();

    get(): string | null {
        return this.token;
    }

    set(token: string | null): void {
        this.token = token;
        this.listeners.forEach((l) => l(token));
    }

    subscribe(listener: Listener): () => void {
        this.listeners.add(listener);
        return () => {
            this.listeners.delete(listener);
        };
    }
}

export const cameraTokenHolder = new TokenHolder();

/** React hook returning the current camera token, re-rendering when it arrives or refreshes. */
export function useCameraToken(): string | null {
    const [token, setToken] = useState<string | null>(cameraTokenHolder.get());
    useEffect(() => cameraTokenHolder.subscribe(setToken), []);
    return token;
}

/** Header object attaching the camera token to a fetch, or undefined when no token is available yet. */
export function cameraAuthHeaders(token: string | null): Record<string, string> | undefined {
    return token ? { 'X-Camera-Token': token } : undefined;
}

/**
 * Client-side delegate paired with the gateway CameraComponentDelegate. Requests a token on
 * construction and schedules a refresh before expiry; received tokens populate the shared holder.
 */
export class CameraAuthDelegate extends ComponentStoreDelegate {
    private refreshTimer: number | null = null;

    constructor(componentStore: ComponentStore) {
        super(componentStore);
        this.requestToken();
    }

    private requestToken(): void {
        try {
            this.fireEvent(EVENT_REQUEST, {});
        } catch {
            // The gateway delegate may not be ready yet; its proactive onStartup push covers this.
        }
    }

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    handleEvent(eventName: string, eventObject: Record<string, any>): void {
        if (eventName === EVENT_TOKEN && eventObject && typeof eventObject.token === 'string') {
            cameraTokenHolder.set(eventObject.token);
            const ttlMs = typeof eventObject.ttlMs === 'number' ? eventObject.ttlMs : DEFAULT_TTL_MS;
            if (this.refreshTimer !== null) {
                window.clearTimeout(this.refreshTimer);
            }
            // Refresh at 80% of TTL, but never sooner than 5s.
            this.refreshTimer = window.setTimeout(() => this.requestToken(), Math.max(5000, ttlMs * 0.8));
        }
    }
}
