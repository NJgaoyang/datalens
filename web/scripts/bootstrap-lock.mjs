import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';

// One-time initial resolution only. The resulting package.json and lockfile are committed.
// Routine development/CI must use npm ci, never run this automatically once locked.
if (existsSync('package-lock.json')) throw new Error('Lockfile exists; use an explicit reviewed dependency update instead.');
const pkg = JSON.parse(readFileSync('package.json', 'utf8'));
const policy = {
  dependencies: { vue: '3.5', 'vue-router': '4', 'element-plus': '2', echarts: '6' },
  devDependencies: { '@playwright/test': '1', '@types/node': '22', '@vitejs/plugin-vue': '6', typescript: '5.9', vite: '7', vitest: '4', 'vue-tsc': '3' },
};
for (const [group, dependencies] of Object.entries(policy)) {
  for (const [name, series] of Object.entries(dependencies)) {
    const output = JSON.parse(execFileSync('npm', ['view', `${name}@${series}`, 'version', '--json'], { encoding: 'utf8' }));
    const version = Array.isArray(output) ? output.at(-1) : output;
    if (typeof version !== 'string' || !/^\d+\.\d+\.\d+$/.test(version)) throw new Error(`Non-release version: ${name}`);
    pkg[group][name] = version;
    console.log(`${name}: ${version}`);
  }
}
writeFileSync('package.json', JSON.stringify(pkg, null, 2) + '\n');
execFileSync('npm', ['install', '--package-lock-only', '--ignore-scripts', '--no-audit', '--no-fund'], { stdio: 'inherit' });
