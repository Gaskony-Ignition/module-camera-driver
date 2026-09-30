import { describe, it, expect } from 'vitest';
import { API } from './paths';

const DATA_BASE = '/data/camera-driver';

describe('API paths', () => {
    it('static string paths are non-empty strings', () => {
        const staticPaths = [
            API.devices,
            API.health,
            API.diagnostics,
            API.authStatus,
            API.logsGateway,
            API.connectionBrowser,
        ];
        staticPaths.forEach((value) => {
            expect(typeof value).toBe('string');
            expect(value).not.toBe('');
        });
    });

    it('static string paths start with the camera-driver base', () => {
        const staticPaths = [
            API.devices,
            API.health,
            API.diagnostics,
            API.authStatus,
            API.logsGateway,
            API.connectionBrowser,
        ];
        staticPaths.forEach((value) => {
            expect(value).toMatch(/^\/data\/camera-driver/);
        });
    });

    it('snapshot() returns a URL with the device name encoded', () => {
        const url = API.snapshot('Front Camera');
        expect(url).toContain(`${DATA_BASE}/snapshot`);
        expect(url).toContain('device=Front%20Camera');
    });

    it('snapshot() includes profile when provided', () => {
        const url = API.snapshot('cam1', 'Profile_1');
        expect(url).toContain('profile=Profile_1');
    });

    it('snapshot() omits profile param when not provided', () => {
        const url = API.snapshot('cam1');
        expect(url).not.toContain('profile=');
    });

    it('stream() returns a URL with the device name encoded', () => {
        const url = API.stream('Back Camera');
        expect(url).toContain(`${DATA_BASE}/stream`);
        expect(url).toContain('device=Back%20Camera');
    });

    it('deviceStatus() returns a URL containing the device name', () => {
        const url = API.deviceStatus('cam1');
        expect(url).toContain(`${DATA_BASE}/device/`);
        expect(url).toContain('cam1');
        expect(url).toContain('/status');
    });

    it('health endpoint matches expected path', () => {
        expect(API.health).toBe(`${DATA_BASE}/health`);
    });

    it('diagnostics endpoint matches expected path', () => {
        expect(API.diagnostics).toBe(`${DATA_BASE}/diagnostics`);
    });

    it('authStatus endpoint matches expected path', () => {
        expect(API.authStatus).toBe(`${DATA_BASE}/auth-status`);
    });

    it('logsGateway endpoint matches expected path', () => {
        expect(API.logsGateway).toBe(`${DATA_BASE}/logs/gateway`);
    });

    it('devices endpoint matches expected path', () => {
        expect(API.devices).toBe(`${DATA_BASE}/devices`);
    });

    it('connectionBrowser endpoint matches expected path', () => {
        expect(API.connectionBrowser).toBe(`${DATA_BASE}/connection-browser`);
    });
});
