import { eq } from 'drizzle-orm';
import type { ServerConfig } from '../types/index.js';
import { getDb } from '../db/index.js';
import { settings } from '../db/schema.js';
import { log } from '../utils/logger.js';

export const defaultConfig: ServerConfig = {
  port: 32766,
  debug: false,
  socket: {
    pingInterval: 25000,
    pingTimeout: 60000,
    maxHttpBufferSize: 50000000,
    transports: ['polling', 'websocket'],
    cors: {
      origin: true,
      methods: ['GET', 'POST', 'DELETE', 'PUT', 'PATCH', 'OPTIONS'],
    },
  },
  rateLimit: {
    windowMs: 60000,
    maxRequests: 10000,
  },
  build: {
    timeout: 600000,
    showServerUrl: true,
  },
  security: {
    sessionTimeout: 86400000,
    loginAttempts: 5,
    loginLockout: 900000,
  },
  logger: {
    maxDbLogs: 10000,
    files: {
      maxSize: '20m',
      errorRetention: '30d',
    },
    console: {
      enabled: true,
    },
  },
};

let runtimeConfig: ServerConfig = structuredClone(defaultConfig);

export function getConfig(): ServerConfig {
  return runtimeConfig;
}

export function parseConfigValue(value: unknown): unknown {
  if (typeof value === 'boolean') return value;
  if (typeof value === 'number') return value;
  const str = String(value);
  if (str === 'true') return true;
  if (str === 'false') return false;
  const num = Number(str);
  if (!isNaN(num) && str.trim() !== '') return num;
  return str;
}

export function updateConfig(key: string, value: unknown): void {
  const keys = key.split('.');
  for (const k of keys) {
    if (k === '__proto__' || k === 'prototype' || k === 'constructor') {
      throw new Error(`Forbidden config key segment: "${k}"`);
    }
  }
  let obj: Record<string, unknown> = runtimeConfig as unknown as Record<string, unknown>;
  for (let i = 0; i < keys.length - 1; i++) {
    if (obj[keys[i]] === undefined) {
      throw new Error(`Unknown config key segment: "${keys[i]}" in "${key}"`);
    }
    obj = obj[keys[i]] as Record<string, unknown>;
  }
  obj[keys[keys.length - 1]] = value;
}

export function loadPersistedSettings(): void {
  try {
    const d = getDb();
    const allSettings = d.select().from(settings).all();
    const rootKeys = new Set(Object.keys(runtimeConfig));
    for (const setting of allSettings) {
      const topSegment = setting.key.split('.')[0];
      if (!rootKeys.has(topSegment)) continue;
      try {
        updateConfig(setting.key, parseConfigValue(setting.value));
      } catch (err: unknown) {
        log.warn(`Skipping config key "${setting.key}": ${err instanceof Error ? err.message : String(err)}`);
      }
    }
} catch {
}
}
