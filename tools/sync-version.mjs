#!/usr/bin/env node
// 版本号单一来源：仓库根目录的 VERSION 文件。
// 用法：
//   node tools/sync-version.mjs              把 VERSION 写入所有需要版本号的位置
//   node tools/sync-version.mjs --check      只校验各处是否与 VERSION 一致（CI 用）
//   node tools/sync-version.mjs --set 1.4.0  先写 VERSION，再同步各位置
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative } from 'node:path';

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const versionPath = join(root, 'VERSION');
const SEMVER = /^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/;

function fail(message) {
  console.error(`✗ ${message}`);
  process.exit(1);
}

function readVersion() {
  if (!existsSync(versionPath)) fail('缺少 VERSION 文件');
  const value = readFileSync(versionPath, 'utf8').trim();
  if (!SEMVER.test(value)) fail(`VERSION 内容不是合法版本号：${value}`);
  return value;
}

// 每个目标：如何把版本写进去，以及如何判断是否已经是目标版本。
const targets = [
  {
    label: 'internal/app/paths.go',
    path: 'internal/app/paths.go',
    apply: (text, v) => text.replace(/(\bVersion\s*=\s*")[^"]*(")/, `$1${v}$2`),
    has: (text, v) => new RegExp(`\\bVersion\\s*=\\s*"${escapeRe(v)}"`).test(text),
  },
  {
    label: 'wails.json',
    path: 'wails.json',
    apply: (text, v) => replaceJsonVersion(text, 'info', 'productVersion', `${v}.0`),
    has: (text, v) => hasJsonVersion(text, 'info', 'productVersion', `${v}.0`),
  },
  {
    label: 'frontend/package.json',
    path: 'frontend/package.json',
    apply: (text, v) => replaceJsonVersion(text, null, 'version', v),
    has: (text, v) => hasJsonVersion(text, null, 'version', v),
  },
  {
    label: 'android/app/build.gradle.kts',
    path: 'android/app/build.gradle.kts',
    apply: (text, v) =>
      text
        .replace(/(versionCode\s*=\s*)\d+/, `$1${versionCodeOf(v)}`)
        .replace(/(versionName\s*=\s*")[^"]*(")/, `$1${v}$2`),
    has: (text, v) =>
      new RegExp(`versionCode\\s*=\\s*${versionCodeOf(v)}\\b`).test(text) &&
      new RegExp(`versionName\\s*=\\s*"${escapeRe(v)}"`).test(text),
  },
  {
    label: 'site/index.html',
    path: 'site/index.html',
    apply: (text, v) => patchSite(text, v),
    has: (text, v) => patchSite(text, v) === text,
  },
];

function escapeRe(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

// Android 要求 versionCode 单调递增，漏改会让老用户无法覆盖安装。
// 直接由版本号推导：1.3.0 → 10300。预发布号不参与（对外只发正式版）。
function versionCodeOf(version) {
  const [major, minor, patch] = version.split(/[-+]/)[0].split('.').map(Number);
  return major * 10000 + minor * 100 + patch;
}

function replaceJsonVersion(text, section, key, value) {
  const data = JSON.parse(text);
  if (section) {
    data[section][key] = value;
  } else {
    data[key] = value;
  }
  // JSON.stringify 固定输出 LF；这里跟随原文件行尾，避免同步时产生无意义的整文件差异。
  const eol = text.includes('\r\n') ? '\r\n' : '\n';
  return `${JSON.stringify(data, null, 2).replace(/\n/g, eol)}${eol}`;
}

function hasJsonVersion(text, section, key, value) {
  try {
    const data = JSON.parse(text);
    return (section ? data[section]?.[key] : data[key]) === value;
  } catch {
    return false;
  }
}

// 下载链接、资源文件名、页脚版本三处由脚本维护；发布说明正文属于历史记录，保持人工编辑。
function patchSite(text, v) {
  return text
    .replace(/releases\/download\/v[0-9][^/"']*\//g, `releases/download/v${v}/`)
    .replace(
      /SJTU-Hive-\d+\.\d+\.\d+(?:[-+][\w.]+)?-(windows-amd64-installer\.exe|windows-amd64-portable\.zip|android\.apk)/g,
      `SJTU-Hive-${v}-$1`,
    )
    .replace(/releases\/tag\/v[0-9][^"']*/g, `releases/tag/v${v}`)
    .replace(/v\d+\.\d+\.\d+(?:[-+][\w.]+)? 正式版/g, `v${v} 正式版`)
    .replace(
      /(releases\/tag\/v[^"]*"[^>]*>)\d+\.\d+\.\d+(?:[-+][\w.]+)?(<\/a>)/g,
      `$1${v}$2`,
    );
}

function main() {
  const args = process.argv.slice(2);
  const checkOnly = args.includes('--check');
  const setIndex = args.indexOf('--set');

  // --check 不会读 --set；--set 写成两个独立分支，避免误改。
  if (setIndex >= 0) {
    if (checkOnly) fail('--set 与 --check 不能同时使用');
    const next = args[setIndex + 1];
    if (!next) fail('--set 后面需要版本号，例如 --set 1.4.0');
    if (!SEMVER.test(next)) fail(`版本号格式不合法：${next}（应为 x.y.z）`);
    writeFileSync(versionPath, `${next}\n`);
    console.log(`VERSION → ${next}`);
    return sync(next);
  }

  const version = readVersion();
  return checkOnly ? check(version) : sync(version);
}

function sync(version) {
  let changed = 0;
  for (const target of targets) {
    const file = join(root, target.path);
    const original = readFileSync(file, 'utf8');
    const next = target.apply(original, version);
    if (next !== original) {
      writeFileSync(file, next);
      console.log(`✓ ${relative(root, file)} → ${version}`);
      changed += 1;
    } else {
      console.log(`· ${relative(root, file)} 已是 ${version}`);
    }
  }
  console.log(changed === 0 ? '所有位置已一致。' : `已更新 ${changed} 个文件。`);
}

function check(version) {
  const stale = [];
  for (const target of targets) {
    const file = join(root, target.path);
    const text = readFileSync(file, 'utf8');
    if (!target.has(text, version)) stale.push(target.path);
  }
  if (stale.length) {
    console.error(`✗ 以下文件的版本号与 VERSION（${version}）不一致：`);
    for (const path of stale) console.error(`  - ${path}`);
    console.error('运行 node tools/sync-version.mjs 修正。');
    process.exit(1);
  }
  console.log(`✓ 版本号一致：${version}`);
}

main();
