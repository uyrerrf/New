import type { FastifyInstance } from 'fastify';
import crypto from 'crypto';
import { getDb } from '../db/index.js';
import { settings, user as userTable } from '../db/schema.js';
import { eq } from 'drizzle-orm';
import { getConfig, updateConfig, parseConfigValue } from '../config/index.js';
import { requirePermission, getRequestUser } from '../middleware/auth.js';
import { dbHelpers } from '../db/index.js';

const ALLOWED_KEYS = [
  'logger.console.enabled',
  'build.showServerUrl',
];
export async function settingsRoutes(app: FastifyInstance) {
  app.get('/api/config', {
    preHandler: [app.auth, requirePermission('settings:view')],
  }, async (request) => {
    const user = getRequestUser(request);
    const config = getConfig();
    if (user.role !== 'admin') {
      return {
        success: true,
        data: {
          port: config.port,
          build: config.build,
          logger: config.logger,
        },
      };
    }
    return { success: true, data: config };
  });

  app.post('/api/config', {
    preHandler: [app.auth, requirePermission('settings:edit')],
  }, async (request, reply) => {
    const { key, value } = (request.body || {}) as { key?: string; value?: string };
    if (!key || value == null) {
      return reply.code(400).send({ success: false, error: 'Key and value are required' });
    }
    if (!ALLOWED_KEYS.includes(key)) {
      return reply.code(403).send({ success: false, error: 'This setting cannot be changed from the UI' });
    }
    const parsedValue = parseConfigValue(value);
    updateConfig(key, parsedValue);
    const stringValue = String(value);
    getDb().insert(settings).values({ key, value: stringValue })
      .onConflictDoUpdate({
        target: settings.key,
        set: { value: stringValue, updatedAt: new Date().toISOString() },
      }).run();
    return { success: true, key, value: parsedValue };
  });
  const DEVICE_SECRET_MIN_LEN = 8;
  const DEVICE_SECRET_MAX_LEN = 256;
  // FIX: device-secret endpoints are per-user (the user reads/sets their OWN
  // secret), so they should be auth-only — NOT require settings:view/edit.
  // Every logged-in user needs to manage their own secret for building APKs.
  // Requiring settings:view caused non-admin users without that permission
  // to see a misleading "No secret set" message even though they had one.
  const secretAuth = [app.auth];
  app.get('/api/config/device-secret', {
    preHandler: secretAuth,
  }, async (request) => {
    const user = getRequestUser(request);
    const dbUser = dbHelpers.getUserById(user.userId);
    return { success: true, data: { deviceSecret: dbUser?.deviceSecret || null } };
  });

  app.post('/api/config/device-secret', {
    preHandler: secretAuth,
  }, async (request, reply) => {
    const user = getRequestUser(request);
    const { value } = (request.body || {}) as { value?: unknown };
    if (value == null || typeof value !== 'string') {
      return reply.code(400).send({ success: false, error: 'Secret value is required' });
    }
    const secretValue = value.trim();
    if (secretValue.length < DEVICE_SECRET_MIN_LEN) {
      return reply.code(400).send({ success: false, error: `Secret must be at least ${DEVICE_SECRET_MIN_LEN} characters` });
    }
    if (secretValue.length > DEVICE_SECRET_MAX_LEN) {
      return reply.code(400).send({ success: false, error: `Secret must be at most ${DEVICE_SECRET_MAX_LEN} characters` });
    }
    if (/[\r\n=]/.test(secretValue)) {
      return reply.code(400).send({ success: false, error: 'Secret must not contain newlines or "=" characters' });
    }
    getDb().update(userTable).set({ deviceSecret: secretValue, updatedAt: new Date() }).where(eq(userTable.id, user.userId)).run();
    dbHelpers.invalidateDeviceSecretsCache();
    dbHelpers.addLog('AUTH', 'SECURITY', `User ${user.username} set their device secret manually`);
    return { success: true, data: { deviceSecret: secretValue } };
  });

  app.post('/api/config/device-secret/regenerate', {
    preHandler: secretAuth,
  }, async (request) => {
    const user = getRequestUser(request);
    const newSecret = crypto.randomBytes(24).toString('base64url');
    getDb().update(userTable).set({ deviceSecret: newSecret, updatedAt: new Date() }).where(eq(userTable.id, user.userId)).run();
    dbHelpers.invalidateDeviceSecretsCache();
    dbHelpers.addLog('AUTH', 'SECURITY', `User ${user.username} regenerated their device secret`);
    return { success: true, data: { deviceSecret: newSecret } };
  });
}
