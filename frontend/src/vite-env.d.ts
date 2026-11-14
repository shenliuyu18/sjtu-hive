/// <reference types="vite/client" />

// 由 vite.config.ts 从 package.json 注入，避免在源码里重复写版本号。
declare const __APP_VERSION__: string;
