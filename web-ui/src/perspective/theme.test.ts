import { describe, it, expect } from 'vitest';
import { CAMERA_THEME } from './theme';

describe('CAMERA_THEME', () => {
    it('all values are non-empty strings', () => {
        Object.values(CAMERA_THEME).forEach((value) => {
            expect(typeof value).toBe('string');
            expect(value).not.toBe('');
        });
    });

    it('colour values are valid CSS hex or function notation', () => {
        const colorKeys: Array<keyof typeof CAMERA_THEME> = [
            'bg',
            'bgGrid',
            'text',
            'textMuted',
            'error',
            'success',
            'warning',
            'border',
        ];
        colorKeys.forEach((key) => {
            const value = CAMERA_THEME[key];
            // Must be a hex color (#rrggbb or #rrggbbaa) or CSS color function
            const isHex = /^#[0-9a-fA-F]{3,8}$/.test(value);
            const isCssFunc = /^(rgb|rgba|hsl|hsla|linear-gradient|radial-gradient)\(/.test(value);
            expect(isHex || isCssFunc, `Expected ${key}="${value}" to be a valid CSS colour`).toBe(true);
        });
    });

    it('overlayGradient is a CSS gradient', () => {
        expect(CAMERA_THEME.overlayGradient).toMatch(/^linear-gradient\(/);
    });

    it('fontFamily is a non-empty string', () => {
        expect(typeof CAMERA_THEME.fontFamily).toBe('string');
        expect(CAMERA_THEME.fontFamily.length).toBeGreaterThan(0);
    });

    it('bg is distinct from bgGrid', () => {
        expect(CAMERA_THEME.bg).not.toBe(CAMERA_THEME.bgGrid);
    });

    it('error colour differs from success colour', () => {
        expect(CAMERA_THEME.error).not.toBe(CAMERA_THEME.success);
    });
});
