// Side-effect stylesheet imports (`import './Foo.css'`) are bundled by webpack's
// style/css/sass loaders. TypeScript 6 checks side-effect imports by default
// (noUncheckedSideEffectImports), so declare the stylesheet module shapes here.
declare module '*.css';
declare module '*.scss';
