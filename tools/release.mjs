#!/usr/bin/env node
// 一次产出可发布的三件套：Windows 绿色版 ZIP、Windows 安装包 EXE、Android APK。
// 用法：
//   node tools/release.mjs                 全量构建并输出到 dist/
//   node tools/release.mjs --skip-android  只出 Windows
//   node tools/release.mjs --skip-installer 跳过 NSIS（未装 makensis 时）
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const args = process.argv.slice(2);
const skip = (flag) => args.includes(flag);
const version = readFileSync(join(root, 'VERSION'), 'utf8').trim();
const distDir = join(root, 'dist');
const isWindows = process.platform === 'win32';

const binaries = {
  portable: `SJTU-Hive-${version}-windows-amd64-portable.zip`,
  installer: `SJTU-Hive-${version}-windows-amd64-installer.exe`,
  apk: `SJTU-Hive-${version}-android.apk`,
  mapping: `SJTU-Hive-${version}-android-mapping.txt`,
};

function log(message) {
  console.log(`\n=== ${message} ===`);
}

function run(command, commandArgs, options = {}) {
  const result = spawnSync(command, commandArgs, {
    cwd: options.cwd ?? root,
    stdio: 'inherit',
    shell: isWindows,
    env: {
      ...process.env,
      PATH: options.extraPath ? `${options.extraPath}${isWindows ? ';' : ':'}${process.env.PATH}` : process.env.PATH,
      GOPROXY: process.env.GOPROXY ?? 'https://goproxy.cn,direct',
      GOSUMDB: process.env.GOSUMDB ?? 'off',
    },
  });
  if (result.status !== 0) {
    console.error(`\n✗ 失败：${command} ${commandArgs.join(' ')}`);
    process.exit(result.status ?? 1);
  }
}

function hasCommand(command, versionFlag = '--version') {
  const result = spawnSync(command, [versionFlag], { stdio: 'ignore', shell: isWindows });
  return result.status === 0;
}

// NSIS 默认装在 Program Files 但常常不在 PATH 里，单独找一次再补进子进程的 PATH。
const NSIS_CANDIDATES = [
  'C:/Program Files (x86)/NSIS/makensis.exe',
  'C:/Program Files/NSIS/makensis.exe',
];

function findNsisDir() {
  if (hasCommand('makensis', '/VERSION')) return '';
  for (const candidate of NSIS_CANDIDATES) {
    if (existsSync(candidate)) return dirname(candidate);
  }
  return null;
}

function sha256(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex');
}

function sizeMB(file) {
  return `${(statSync(file).size / 1024 / 1024).toFixed(1)} MB`;
}

function zip(source, target) {
  if (isWindows) {
    run('powershell.exe', [
      '-NoProfile',
      '-Command',
      `Compress-Archive -Path '${source}' -DestinationPath '${target}' -Force`,
    ]);
  } else {
    run('zip', ['-j', '-q', target, source]);
  }
}

// 1. 版本号必须一致，避免发出一个各处版本号互相打脸的包。
log('校验版本号');
run('node', [join(root, 'tools', 'sync-version.mjs'), '--check']);

rmSync(distDir, { recursive: true, force: true });
mkdirSync(distDir, { recursive: true });

// 2. 先构建前端：main.go 用 //go:embed 嵌入 frontend/dist，没有产物连测试都编译不过。
//    旧产物整体移到系统临时目录而不是逐个删除——重命名是常数开销，也能在禁止批量删除的环境里跑通。
log('构建前端');
const frontendDir = join(root, 'frontend');
const frontendDist = join(frontendDir, 'dist');
if (existsSync(frontendDist)) {
  try {
    renameSync(frontendDist, join(tmpdir(), `sjtu-hive-dist-${Date.now()}`));
  } catch {
    // 跨盘等无法重命名的情况，交给 vite 的 emptyOutDir 处理
  }
}
if (!existsSync(join(frontendDir, 'node_modules'))) {
  run('npm', ['ci', '--no-audit', '--no-fund'], { cwd: frontendDir });
}
run('npm', ['run', 'build'], { cwd: frontendDir });

// 3. 测试不通过就不打包。
log('运行 Go 测试');
run('go', ['test', './...', '-count=1']);

const artifacts = [];

if (!skip('--skip-windows')) {
  log('构建 Windows 绿色版');
  run('go', ['run', 'github.com/wailsapp/wails/v2/cmd/wails@v2.16.0', 'build', '-s', '-platform', 'windows/amd64']);
  const exe = join(root, 'build', 'bin', 'SJTU-Hive.exe');
  if (!existsSync(exe)) {
    console.error('✗ 没有找到 build/bin/SJTU-Hive.exe');
    process.exit(1);
  }
  const portable = join(distDir, binaries.portable);
  zip(exe, portable);
  artifacts.push(portable);
  console.log(`✓ ${binaries.portable} (${sizeMB(portable)})`);

  if (!skip('--skip-installer')) {
    const nsisDir = findNsisDir();
    if (nsisDir === null) {
      console.warn('\n⚠ 未安装 NSIS(makensis)，跳过安装包。安装后重跑本脚本即可：');
      console.warn('  winget install --id NSIS.NSIS -e');
    } else {
      log('构建 Windows 安装包');
      run(
        'go',
        [
          'run',
          'github.com/wailsapp/wails/v2/cmd/wails@v2.16.0',
          'build',
          '-s',
          '-platform',
          'windows/amd64',
          '-nsis',
          '-installscope',
          'user',
          '-webview2',
          'download',
        ],
        { extraPath: nsisDir || undefined },
      );
      // Wails 用 wails.json 的 productName 命名安装包（"SJTU Hive-amd64-installer.exe"），
      // 这里按后缀匹配，改产品名也不会让脚本失效。
      const binDir = join(root, 'build', 'bin');
      const installerPattern = /-amd64-installer\.exe$/i;
      const builtName = existsSync(binDir)
        ? readdirSync(binDir).find((name) => installerPattern.test(name))
        : undefined;
      if (!builtName) {
        console.error('✗ 没有找到 NSIS 安装包产物（build/bin/*-amd64-installer.exe）');
        process.exit(1);
      }
      const installer = join(distDir, binaries.installer);
      copyFileSync(join(binDir, builtName), installer);
      artifacts.push(installer);
      console.log(`✓ ${binaries.installer} (${sizeMB(installer)})`);
    }
  }
}

if (!skip('--skip-android')) {
  if (!hasCommand('gradle', '--version')) {
    console.warn('\n⚠ 未找到 gradle，跳过 Android。');
  } else {
    log('构建 Android 签名 APK');
    run('gradle', [':app:assembleRelease', '--console=plain'], { cwd: join(root, 'android') });
    const apk = join(root, 'android', 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk');
    if (!existsSync(apk)) {
      console.error('✗ 没有找到 Android Release APK');
      process.exit(1);
    }
    const target = join(distDir, binaries.apk);
    copyFileSync(apk, target);
    artifacts.push(target);
    console.log(`✓ ${binaries.apk} (${sizeMB(target)})`);

    // R8 混淆后的堆栈需要 mapping.txt 才能还原，按版本归档；它体积大且含内部符号，不随 release 公开上传。
    const mapping = join(root, 'android', 'app', 'build', 'outputs', 'mapping', 'release', 'mapping.txt');
    if (existsSync(mapping)) {
      const mappingDir = join(distDir, 'mapping');
      mkdirSync(mappingDir, { recursive: true });
      copyFileSync(mapping, join(mappingDir, binaries.mapping));
      console.log(`✓ mapping/${binaries.mapping}`);
    }
  }
}

// 3. 校验和：应用内更新会用 GitHub 返回的 digest 校验，本地这份便于发版时核对。
log('生成 SHA-256');
const lines = artifacts.map((file) => `${sha256(file)}  ${file.split(/[\\/]/).pop()}`);
writeFileSync(join(distDir, 'SHA256SUMS.txt'), `${lines.join('\n')}\n`);
for (const file of artifacts) {
  console.log(`${sha256(file).slice(0, 8)}…  ${file.split(/[\\/]/).pop()}  ${sizeMB(file)}`);
}

log('完成');
console.log(`产物目录：${distDir}`);
const uploads = [binaries.installer, binaries.portable, binaries.apk]
  .map((name) => `dist/${name}`)
  .join(' ');
console.log('\n发布步骤：');
console.log(`  1. gh release create v${version} ${uploads} dist/SHA256SUMS.txt \\`);
console.log(`       --title "SJTU Hive ${version}" --notes-file CHANGELOG.md`);
console.log('  2. 把 site/index.html 里的文件体积与 SHA-256 前 8 位更新为上面的数值');
console.log('  3. dist/mapping/ 只做本地归档，不要上传（含内部符号且体积很大）');
