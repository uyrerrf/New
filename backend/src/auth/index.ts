import crypto from 'crypto';
import { betterAuth } from 'better-auth/minimal';
import { drizzleAdapter } from 'better-auth/adapters/drizzle';
import { admin, bearer } from 'better-auth/plugins';
import { eq } from 'drizzle-orm';
import { getDb } from '../db/index.js';
import * as schema from '../db/schema.js';
import { jwtSecret } from '../db/schema.js';
import { getConfig } from '../config/index.js';
import { log } from '../utils/logger.js';
import { DEFAULT_USER_PERMISSIONS } from '../types/index.js';

function readOrCreateSecret(): string {
  const envSecret = process.env.BETTER_AUTH_SECRET;
  if (envSecret && envSecret.length >= 32) return envSecret;
  try {
    const d = getDb();
    const row = d.select({ secret: jwtSecret.secret }).from(jwtSecret).where(eq(jwtSecret.id, 1)).get();
    if (row?.secret && row.secret.length >= 32) return row.secret;
    const newSecret = crypto.randomBytes(48).toString('base64url');
    d.insert(jwtSecret).values({ id: 1, secret: newSecret })
      .onConflictDoUpdate({ target: jwtSecret.id, set: { secret: newSecret } }).run();
    return newSecret;
  } catch (e) {
    log.error(`Auth secret error: ${e}. Using ephemeral.`);
    return crypto.randomBytes(48).toString('base64url');
  }
}

export function buildAuth(): any {
  const config = getConfig();
  const db = getDb();
  return betterAuth({
    database: drizzleAdapter(db, {
      provider: 'sqlite',
      schema,
      usePlural: false,
      camelCase: false,
    }),
    secret: readOrCreateSecret(),
    baseURL: process.env.BETTER_AUTH_URL || `http://localhost:${config.port}`,
    trustedOrigins: process.env.BETTER_AUTH_TRUSTED_ORIGINS
      ? process.env.BETTER_AUTH_TRUSTED_ORIGINS.split(',').map(s => s.trim()).filter(Boolean)
      : process.env.NODE_ENV === 'production'
        ? [`http://localhost:${config.port}`]
        : [`http://localhost:${config.port}`, 'http://localhost:5173', 'http://127.0.0.1:5173'],
    emailAndPassword: {
      enabled: true,
      autoSignIn: false,
      requireEmailVerification: false,
      minPasswordLength: 8,
      maxPasswordLength: 128,
    },
    session: {
      cookieCache: {
        enabled: true,
        maxAge: 5 * 60,
      },
      expiresIn: config.security.sessionTimeout / 1000,
      updateAge: 60 * 60,
    },
    user: {
      additionalFields: {
        username: {
          type: 'string',
          required: true,
          unique: true,
          defaultValue: '',
        },
        permissions: {
          type: 'string',
          required: false,
          defaultValue: JSON.stringify(DEFAULT_USER_PERMISSIONS),
          input: false,
        },
        isDefault: {
          type: 'number',
          required: false,
          defaultValue: 0,
          input: false,
        },
      },
    },
    plugins: [
      admin({
        defaultRole: 'user',
        adminRole: 'admin',
      }),
      bearer({
        requireSignature: false,
      }),
    ],
    rateLimit: {
      enabled: true,
      window: 60,
      max: 100,
    },
    advanced: {
      cookiePrefix: 'fason',
      defaultCookieAttributes: {
        httpOnly: true,
        sameSite: 'strict',
        secure: process.env.NODE_ENV === 'production',
        path: '/',
      },
      crossSubDomainCookies: {
        enabled: false,
      },
    },
  });
}

let authInstance: any = null;

export function getAuth(): any {
  if (!authInstance) {
    authInstance = buildAuth();
  }
  return authInstance;
}
