import js from '@eslint/js'
import globals from 'globals'
import jsxA11y from 'eslint-plugin-jsx-a11y'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

// eslint-plugin-react-hooks 7 folded the React Compiler diagnostics (refs,
// set-state-in-effect, immutability, purity, ...) into its recommended preset at
// error level. This module does not use the React Compiler, so those rules are
// soft-migrated at warn level (same approach as the jsx-a11y rules below);
// rules-of-hooks and exhaustive-deps keep their preset severities.
const reactHooksRecommended = reactHooks.configs.flat['recommended-latest']
const reactCompilerRulesAsWarnings = Object.fromEntries(
  Object.keys(reactHooksRecommended.rules)
    .filter((rule) => !['react-hooks/rules-of-hooks', 'react-hooks/exhaustive-deps'].includes(rule))
    .map((rule) => [rule, 'warn']),
)

export default defineConfig([
  globalIgnores(['dist', 'build']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooksRecommended,
      reactRefresh.configs.vite,
    ],
    plugins: {
      'jsx-a11y': jsxA11y,
    },
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
    rules: {
      '@typescript-eslint/no-unused-vars': ['warn', { argsIgnorePattern: '^_' }],
      '@typescript-eslint/no-explicit-any': 'warn',
      '@typescript-eslint/ban-ts-comment': 'warn',
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      // Sprint 3 P10 (Apr 2026): a11y soft-migration. Warn-level only.
      'jsx-a11y/label-has-associated-control': ['warn', { assert: 'either' }],
      ...reactCompilerRulesAsWarnings,
    },
  },
])
