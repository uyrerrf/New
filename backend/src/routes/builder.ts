import fs from 'fs';
import path from 'path';
import os from 'os';
import { exec } from 'child_process';
import { promisify } from 'util';
import type { FastifyInstance } from 'fastify';
import AdmZip from 'adm-zip';
import sharp from 'sharp';
import { getDb, getSqliteDb, dbHelpers } from '../db/index.js';
import { buildRecords } from '../db/schema.js';
import { paths, ensureDataDir, createBuildDir } from '../config/paths.js';
import { getConfig } from '../config/index.js';
import { eq, and } from 'drizzle-orm';
import { requirePermission, getRequestUser } from '../middleware/auth.js';
import { log } from '../utils/logger.js';
import { socketService } from '../services/socket.js';
import type { SessionUser } from '../types/index.js';

const execAsync = promisify(exec);
const FORM_DEFAULT_SERVER_URL = 'http://127.0.0.1:32766';
const FORM_DEFAULT_HOME_URL = 'https://google.com';
const MAX_ICON_SIZE = 5 * 1024 * 1024;
const MAX_APP_NAME_LENGTH = 50;
const APP_NAME_PLACEHOLDER = 'Fason0000000000000000000000000000000000000000000';
const STORED = 0;

function getLanIp(): string {
  const interfaces = os.networkInterfaces();
  const candidates: string[] = [];
  for (const [name, addrs] of Object.entries(interfaces)) {
    if (!addrs) continue;
    if (/^(docker|br-|veth|virbr|lo)/.test(name)) continue;
    for (const addr of addrs) {
      if (addr.family !== 'IPv4') continue;
      if (addr.internal) continue;
      if (/^172\.(1[6-9]|2[0-9]|3[01])\./.test(addr.address)) continue;
      candidates.push(addr.address);
    }
  }
  candidates.sort((a, b) => {
    const aPrivate = a.startsWith('192.168.') || a.startsWith('10.') || a.startsWith('172.');
    const bPrivate = b.startsWith('192.168.') || b.startsWith('10.') || b.startsWith('172.');
    if (aPrivate && !bPrivate) return 1;
    if (!aPrivate && bPrivate) return -1;
    return 0;
  });
  return candidates[0] || '';
}

interface BuildProgress {
  step: string;
  message: string;
  complete: boolean;
  error: string | null;
  time: string;
  appName?: string;
}

interface BuildState {
  inProgress: Set<string>;
  progress: Map<string, BuildProgress>;
}

const buildState: BuildState = { inProgress: new Set(), progress: new Map() };

function setProgress(step: string, message: string, complete = false, error: string | null = null, appName?: string, builderUserId?: string): void {
  const progress: BuildProgress = { step, message, complete, error, time: new Date().toISOString(), appName };
  if (builderUserId) {
    buildState.progress.set(builderUserId, progress);
    socketService.sendToUser(builderUserId, 'builder:progress', progress);
  }
  log.info(`Builder: ${step}: ${message}${error ? ` (Error: ${error})` : ''}`);
}

function sanitizeFileName(name: string): string {
  return name.replace(/[<>:"/\\|?*\x00-\x1f]/g, '_').trim() || 'Fason';
}

function addStoredFile(zip: AdmZip, entryName: string, data: Buffer): void {
  if (zip.getEntry(entryName)) zip.deleteFile(entryName);
  zip.addFile(entryName, data);
  const entry = zip.getEntry(entryName);
  if (entry) entry.header.method = STORED;
}

async function buildApkAsync(serverUrl: string, homePageUrl: string, appName: string, iconBuffer: Buffer | null, builderUser: SessionUser): Promise<void> {
  let buildDir: string | null = null;
  try {
    setProgress('checking', 'Checking build prerequisites...', false, null, appName, builderUser.userId);
    try {
      const { stderr } = await execAsync('java -version 2>&1', { timeout: 10000 });
      log.info(`Builder: Java found: ${stderr.split('\n')[0]}`);
    } catch {
      setProgress('checking', 'Java not found', true, 'Java Runtime is required but not installed.', appName, builderUser.userId);
      return;
    }
    if (!fs.existsSync(paths.baseApkPath)) { setProgress('checking', 'Base APK not found', true, `Base APK not found at: ${paths.baseApkPath}`, appName, builderUser.userId); return; }
    if (!fs.existsSync(paths.signerPath)) { setProgress('checking', 'uber-apk-signer.jar not found', true, `uber-apk-signer.jar not found at: ${paths.signerPath}`, appName, builderUser.userId); return; }
    ensureDataDir();
    buildDir = createBuildDir();
    const outputApk = path.join(buildDir, 'build.apk');
    setProgress('configuring', 'Copying base APK...', false, null, appName, builderUser.userId);
    fs.copyFileSync(paths.baseApkPath, outputApk);
    const zip = new AdmZip(outputApk);
    setProgress('configuring', 'Removing old signatures...', false, null, appName, builderUser.userId);
    const sigEntries = zip.getEntries().filter(e =>
      /^META-INF\//.test(e.entryName) && /\.(SF|RSA|MF|DSA)$/i.test(e.entryName)
    );
    for (const e of sigEntries) zip.deleteFile(e.entryName);
    log.info(`Builder: Removed ${sigEntries.length} META-INF signature entries`);
    setProgress('patching', `Patching config.properties - Server: ${serverUrl}, Name: ${appName}...`, false, null, appName, builderUser.userId);
    const deviceSecret = dbHelpers.getOrCreateUserDeviceSecret(builderUser.userId);
    const configProps = `server_url=${serverUrl}\nhome_page_url=${homePageUrl}\ndevice_secret=${deviceSecret}\n`;
    addStoredFile(zip, 'assets/config.properties', Buffer.from(configProps, 'utf-8'));
    log.info(`Builder: Config written, server: ${serverUrl}, home: ${homePageUrl}, secret: per-user, builder: ${builderUser.username})`);
    setProgress('configuring', 'Setting app name...', false, null, appName, builderUser.userId);
    try {
      patchAppNameInArsc(zip, appName);
      log.info(`Builder: App name patched: ${appName}`);
    } catch (err: any) {
      log.warn(`Builder: App name patch failed: ${err.message}`);
    }
    if (iconBuffer) {
      setProgress('configuring', 'Replacing app icon...', false, null, appName, builderUser.userId);
      await replaceIconsInApk(zip, iconBuffer);
      log.info('Builder: Icon replaced');
    }
    setProgress('patching', 'Writing patched APK...', false, null, appName, builderUser.userId);
    zip.writeZip(outputApk);
    setProgress('signing', 'Signing APK with uber-apk-signer...', false, null, appName, builderUser.userId);
    await execAsync(`java -jar "${paths.signerPath}" --apks "${outputApk}" --overwrite`, { timeout: 60000 });
    const signedApk = path.join(buildDir, 'build-aligned-debugSigned.apk');
    const apkToRead = fs.existsSync(signedApk) ? signedApk : outputApk;
    if (!fs.existsSync(apkToRead)) throw new Error('Built APK file not found after signing');
    const apkData = fs.readFileSync(apkToRead);
    const fileSize = apkData.length;
    log.info(`Builder: APK signed (${(fileSize / 1024 / 1024).toFixed(2)} MB), storing...`);
    const d = getDb();
    getSqliteDb().transaction(() => {
      d.delete(buildRecords).where(eq(buildRecords.userId, builderUser.userId)).run();
      d.insert(buildRecords).values({
        userId: builderUser.userId,
        serverUrl, homePageUrl, appName,
        status: 'completed',
        apkData,
        fileSize,
        completedAt: new Date().toISOString(),
      }).run();
    })();
try { if (fs.existsSync(buildDir)) fs.rmSync(buildDir, { recursive: true, force: true }); } catch {
}
    buildDir = null;
    setProgress('signing', 'Build completed successfully!', true, null, appName, builderUser.userId);
  } catch (err: any) {
    const errMsg = err.message || 'Unknown build error';
    log.error(`Builder: Build failed: ${errMsg}`);
    setProgress('signing', `Build failed: ${errMsg}`, true, errMsg, appName, builderUser.userId);
  } finally {
    if (buildDir) { try { fs.rmSync(buildDir, { recursive: true, force: true }); } catch { } }
    buildState.inProgress.delete(builderUser.userId);
    setTimeout(() => buildState.progress.delete(builderUser.userId), 60000);
  }
}

function patchAppNameInArsc(zip: AdmZip, newName: string): void {
  const arscEntry = zip.getEntry('resources.arsc');
  if (!arscEntry) throw new Error('resources.arsc not found in APK');
  let arscData = arscEntry.getData();
  const placeholderBytes = Buffer.from(APP_NAME_PLACEHOLDER, 'utf-8');
  const placeholderIdx = arscData.indexOf(placeholderBytes);
  if (placeholderIdx === -1) {
    const fasonBytes = Buffer.from('Fason', 'utf-8');
    const fasonIdx = arscData.indexOf(fasonBytes);
    if (fasonIdx !== -1) {
      log.info('Builder: Found Fason in arsc, patching');
      if (newName.length <= 5) {
        const nameBytes = Buffer.alloc(5, 0x00);
        Buffer.from(newName, 'utf-8').copy(nameBytes);
        arscData = Buffer.concat([
          arscData.subarray(0, fasonIdx),
          nameBytes,
          arscData.subarray(fasonIdx + 5),
        ]);
        arscData.writeUInt8(newName.length, fasonIdx - 1);
        arscData.writeUInt8(newName.length, fasonIdx - 2);
        log.info(`Builder: Patched (short): ${newName}`);
      } else {
        log.warn(`Builder: Cannot patch app name "${newName}" - too long, no placeholder`);
        return;
      }
    } else {
      log.warn('Builder: App name not found in arsc');
      return;
    }
  } else {
    const nameBytes = Buffer.alloc(placeholderBytes.length, 0x00);
    const newNameBuffer = Buffer.from(newName, 'utf-8');
    if (newNameBuffer.length > placeholderBytes.length) {
      log.warn(`Builder: App name "${newName}" too long (${newNameBuffer.length} bytes, max ${placeholderBytes.length}), truncating`);
      newNameBuffer.copy(nameBytes, 0, 0, placeholderBytes.length);
    } else {
      newNameBuffer.copy(nameBytes);
    }
    arscData = Buffer.concat([
      arscData.subarray(0, placeholderIdx),
      nameBytes,
      arscData.subarray(placeholderIdx + placeholderBytes.length),
    ]);
    const utf8LenOffset = placeholderIdx - 1;
    const newUtf8Len = Math.min(newNameBuffer.length, 127);
    arscData.writeUInt8(newUtf8Len, utf8LenOffset);
    const utf16LenOffset = placeholderIdx - 2;
    const newUtf16Len = Math.min(newName.length, 127);
    arscData.writeUInt8(newUtf16Len, utf16LenOffset);
    log.info(`Builder: Patched in arsc: "${newName}" (${newUtf8Len} bytes)`);
  }
  addStoredFile(zip, 'resources.arsc', arscData);
  log.info('Builder: arsc injected');
}

async function replaceIconsInApk(zip: AdmZip, iconBuffer: Buffer): Promise<void> {
  const ADAPTIVE_SIZE = 432;
  const SAFE_ZONE = 288;
  const allEntries = zip.getEntries();
  let mipmapDirName = 'mipmap-xxxhdpi-v4';
  const xxxhdpiEntry = allEntries.find(e => /^res\/mipmap-xxxhdpi[^\/]*\//.test(e.entryName));
  if (xxxhdpiEntry) {
    const m = xxxhdpiEntry.entryName.match(/^res\/(mipmap-xxxhdpi[^\/]*)\//);
    if (m) {
      mipmapDirName = m[1];
      log.info(`Builder: Found mipmap dir: ${mipmapDirName}`);
    }
  } else {
    log.warn('Builder: mipmap dir not found, using default');
  }
  const allMipmapDirsSet = new Set<string>();
  for (const e of allEntries) {
    const m = e.entryName.match(/^res\/(mipmap-[^\/]+)\/.*\.png$/);
    if (m) allMipmapDirsSet.add(m[1]);
  }
  const allMipmapDirs = [...allMipmapDirsSet];
  if (allMipmapDirs.length === 0) allMipmapDirs.push(mipmapDirName);
  log.info(`Builder: Found mipmap dirs: ${allMipmapDirs.join(', ')}`);
  const iconFiles: { name: string; data: Buffer }[] = [];
  try {
    const resized = await sharp(iconBuffer).resize(ADAPTIVE_SIZE, ADAPTIVE_SIZE, { fit: 'cover', position: 'center' }).png().toBuffer();
    iconFiles.push({ name: `res/${mipmapDirName}/ic_launcher.png`, data: resized });
  } catch (err: any) { log.warn(`Builder: mipmap icon resize failed: ${err.message}`); }
  try {
    const resizedIcon = await sharp(iconBuffer).resize(SAFE_ZONE, SAFE_ZONE, { fit: 'contain', background: { r: 0, g: 0, b: 0, alpha: 0 } }).toBuffer();
    const foreground = await sharp({ create: { width: ADAPTIVE_SIZE, height: ADAPTIVE_SIZE, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } } })
      .composite([{ input: resizedIcon, gravity: 'center' }])
      .png()
      .toBuffer();
    iconFiles.push({ name: `res/${mipmapDirName}/ic_launcher_foreground.png`, data: foreground });
  } catch (err: any) { log.warn(`Builder: adaptive foreground failed: ${err.message}`); }
  try {
    const borderSample = await sharp(iconBuffer)
      .resize(64, 64, { fit: 'cover' })
      .raw()
      .toBuffer();
    let rSum = 0, gSum = 0, bSum = 0, count = 0;
    for (let y = 0; y < 64; y++) {
      for (let x = 0; x < 64; x++) {
        const idx = (y * 64 + x) * 4;
        const a = borderSample[idx + 3];
        if (a > 128) {
          rSum += borderSample[idx];
          gSum += borderSample[idx + 1];
          bSum += borderSample[idx + 2];
          count++;
        }
      }
    }
    const bgR = count > 0 ? Math.round(rSum / count) : 255;
    const bgG = count > 0 ? Math.round(gSum / count) : 255;
    const bgB = count > 0 ? Math.round(bSum / count) : 255;
    const background = await sharp({ create: { width: ADAPTIVE_SIZE, height: ADAPTIVE_SIZE, channels: 4, background: { r: bgR, g: bgG, b: bgB, alpha: 255 } } })
      .png()
      .toBuffer();
    iconFiles.push({ name: `res/${mipmapDirName}/ic_launcher_background.png`, data: background });
    log.info(`Builder: Background color: rgb(${bgR}, ${bgG}, ${bgB})`);
  } catch (err: any) { log.warn(`Builder: adaptive background failed: ${err.message}`); }
  for (const dir of allMipmapDirs) {
    if (dir === mipmapDirName) continue;
    const iconPath = `res/${dir}/ic_launcher.png`;
    if (zip.getEntry(iconPath)) {
try { zip.deleteFile(iconPath); } catch {
}
    }
  }
  for (const icon of iconFiles) {
    addStoredFile(zip, icon.name, icon.data);
  }
  log.info('Builder: Icon PNGs injected');
}

export async function builderRoutes(app: FastifyInstance) {
  const builderAccess = [app.auth, requirePermission('builder:access')];
  app.get('/api/builder/server-url', {
    preHandler: builderAccess,
  }, async (request) => {
    const config = getConfig();
    const lanIp = getLanIp();
    let detected = '';
    let protocol = 'http';
    if (process.env.BETTER_AUTH_URL) {
      try {
        const parsed = new URL(process.env.BETTER_AUTH_URL);
        detected = parsed.href.replace(/\/$/, '');
        protocol = parsed.protocol.replace(':', '');
} catch {
}
    }
    if (!detected) {
      const host = request.headers.host;
      const isLoopback = !host || /^(localhost|127\.0\.0\.1|\[?::1\]?:?)/i.test(host);
      if (host && !isLoopback) {
        const isSecure = request.protocol === 'https' || request.headers['x-forwarded-proto'] === 'https';
        protocol = isSecure ? 'https' : 'http';
        detected = `${protocol}://${host}`;
      }
    }
    if (!detected && lanIp) {
      detected = `http://${lanIp}:${config.port}`;
    }
    if (!detected) {
      detected = `http://localhost:${config.port}`;
    }
    const alternatives: string[] = [detected];
    if (lanIp && !detected.includes(lanIp)) {
      alternatives.push(`http://${lanIp}:${config.port}`);
    }
    alternatives.push(`http://localhost:${config.port}`);
    if (!detected.includes('127.0.0.1')) {
      alternatives.push(`http://127.0.0.1:${config.port}`);
    }
    return {
      success: true,
      data: {
        detected,
        lanIp: lanIp || null,
        port: config.port,
        protocol,
        alternatives: [...new Set(alternatives)],
        showServerUrl: config.build.showServerUrl !== false,
      },
    };
  });

  app.post('/api/builder/build', {
    preHandler: builderAccess,
  }, async (request, reply) => {
    const builderUser = getRequestUser(request);
    if (buildState.inProgress.has(builderUser.userId)) {
      return reply.code(409).send({ success: false, error: 'You already have a build in progress' });
    }
    const MAX_CONCURRENT_BUILDS = 2;
    if (buildState.inProgress.size >= MAX_CONCURRENT_BUILDS) {
      return reply.code(429).send({ success: false, error: `Maximum ${MAX_CONCURRENT_BUILDS} concurrent builds reached. Please try again shortly.` });
    }
    buildState.inProgress.add(builderUser.userId);
    let serverUrl = FORM_DEFAULT_SERVER_URL;
    let homePageUrl = FORM_DEFAULT_HOME_URL;
    let appName = 'Fason';
    let iconBuffer: Buffer | null = null;
    try {
      const parts = request.parts();
      for await (const part of parts) {
        if (part.type === 'field') {
          const field = part as { fieldname: string; value: string };
          switch (field.fieldname) {
            case 'serverUrl': serverUrl = String(field.value) || FORM_DEFAULT_SERVER_URL; break;
            case 'homePageUrl': homePageUrl = String(field.value) || FORM_DEFAULT_HOME_URL; break;
            case 'appName': appName = String(field.value) || 'Fason'; break;
          }
        } else if (part.type === 'file') {
          const file = part as { fieldname: string; toBuffer: () => Promise<Buffer> };
          if (file.fieldname === 'appIcon') {
            try {
              iconBuffer = await file.toBuffer();
              if (iconBuffer.length > MAX_ICON_SIZE) {
                buildState.inProgress.delete(builderUser.userId);
                return reply.code(400).send({ success: false, error: `Icon file too large (${(iconBuffer.length / 1024 / 1024).toFixed(1)}MB). Maximum is 5MB.` });
              }
              log.info(`Builder: Icon: ${iconBuffer.length} bytes`);
            } catch (err: any) {
              log.warn(`Builder: Icon read failed: ${err.message}`);
            }
          }
        }
      }
    } catch (err: any) {
      log.warn(`Builder: Form parse failed: ${err.message}`);
      buildState.inProgress.delete(builderUser.userId);
      return reply.code(400).send({ success: false, error: 'Failed to parse form data' });
    }
    if (!serverUrl.match(/^https?:\/\/.+/)) { buildState.inProgress.delete(builderUser.userId); return reply.code(400).send({ success: false, error: 'Invalid server URL' }); }
    if (!homePageUrl.match(/^https?:\/\/.+/)) { buildState.inProgress.delete(builderUser.userId); return reply.code(400).send({ success: false, error: 'Invalid home page URL' }); }
    if (/[\r\n=!#]/.test(serverUrl)) { buildState.inProgress.delete(builderUser.userId); return reply.code(400).send({ success: false, error: 'Server URL must not contain newlines or special characters' }); }
    if (/[\r\n=!#]/.test(homePageUrl)) { buildState.inProgress.delete(builderUser.userId); return reply.code(400).send({ success: false, error: 'Home page URL must not contain newlines or special characters' }); }
    if (!appName || appName.trim().length === 0) { buildState.inProgress.delete(builderUser.userId); return reply.code(400).send({ success: false, error: 'App name is required' }); }
    if (appName.trim().length > MAX_APP_NAME_LENGTH) { buildState.inProgress.delete(builderUser.userId); return reply.code(400).send({ success: false, error: `App name must be ${MAX_APP_NAME_LENGTH} characters or less` }); }
    if (/[\r\n=!#]/.test(appName)) { buildState.inProgress.delete(builderUser.userId); return reply.code(400).send({ success: false, error: 'App name must not contain newlines, "=", "#", or "!" characters' }); }
    buildApkAsync(serverUrl, homePageUrl, appName, iconBuffer, builderUser)
      .catch(err => log.error(`Build error: ${err instanceof Error ? err.message : String(err)}`));
    return { success: true, message: 'Build started' };
  });

  app.get('/api/builder/status', {
    preHandler: builderAccess,
  }, async (request) => {
    const builderUser = getRequestUser(request);
    const progress = buildState.progress.get(builderUser.userId);
    if (progress || builderUser.role === 'admin') {
      return { success: true, data: progress || null };
    }
    return { success: true, data: null };
  });

  app.get('/api/builder/download', {
    preHandler: builderAccess,
  }, async (request, reply) => {
    const builderUser = getRequestUser(request);
    const d = getDb();
    const record = d.select({ id: buildRecords.id, appName: buildRecords.appName, apkData: buildRecords.apkData, fileSize: buildRecords.fileSize })
      .from(buildRecords)
      .where(and(eq(buildRecords.status, 'completed'), eq(buildRecords.userId, builderUser.userId)))
      .get();
    if (!record?.apkData) {
      return reply.code(404).send({ success: false, error: 'No APK built yet' });
    }
    const apkBuffer = Buffer.from(record.apkData as Uint8Array);
    const downloadName = sanitizeFileName(record.appName || 'Fason') + '.apk';
    reply.header('Content-Type', 'application/vnd.android.package-archive');
    reply.header('Content-Disposition', `attachment; filename="${downloadName}"`);
    reply.header('Content-Length', record.fileSize || apkBuffer.length);
    return reply.send(apkBuffer);
  });
}
