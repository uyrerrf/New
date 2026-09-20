import crypto from 'crypto';
import type { FastifyInstance } from 'fastify';
import { getAuth } from '../auth/index.js';
import { getRequestUser, verifySessionToken } from '../middleware/auth.js';
import { getDb, dbHelpers } from '../db/index.js';
import { session as sessionTable, user as userTable, account as accountTable } from '../db/schema.js';
import { eq, sql, gt, and, ne } from 'drizzle-orm';
import { getConfig } from '../config/index.js';
import { validateUsername, validatePasswordStrength, validateEmail } from '../utils/helpers.js';
import { hashPasswordScrypt, verifyPasswordScrypt } from '../db/seed.js';
import type { UserRole } from '../types/index.js';
import { resolvePermissions } from '../types/index.js';
import { log } from '../utils/logger.js';

export async function authRoutes(app: FastifyInstance) {
  app.post('/api/auth/login', {
    config: {
      rateLimit: { max: 20, timeWindow: '1 minute' },
    },
  }, async (request, reply) => {
    const { username, password } = (request.body || {}) as { username?: string; password?: string };

    const config = getConfig();
    const ip = request.ip;
    if (!username || !password) {
      return reply.code(400).send({ success: false, error: 'Username/email and password are required' });
    }
    const dbUser = dbHelpers.getUserByUsernameOrEmail(username);
    const normalizedUsername = (dbUser?.username || username).toLowerCase();
    const lockoutIdentifier = `${ip}|${normalizedUsername}`;
    if (dbHelpers.checkLoginAttempts(ip, config.security.loginAttempts, config.security.loginLockout, lockoutIdentifier)) {
      dbHelpers.addLog('AUTH', 'SECURITY', `Login locked out for identifier: ${normalizedUsername}`, JSON.stringify({ ip }));
      return reply.code(429).send({ success: false, error: 'Too many login attempts. Try again later.' });
    }
    if (!dbUser) {
      try {
        if (DUMMY_SCRYPT_HASH) {
          await verifyPasswordScrypt(password, DUMMY_SCRYPT_HASH);
        }
} catch {
}
      dbHelpers.recordLoginAttempt(ip, lockoutIdentifier);
      dbHelpers.addLog('AUTH', 'LOGIN', `Failed login attempt for: ${username}`, JSON.stringify({ ip }));
      return reply.code(401).send({ success: false, error: 'Invalid credentials' });
    }
    if (dbUser.banned) {
      const isBanExpired = dbUser.banExpires && new Date(dbUser.banExpires) < new Date();
      if (!isBanExpired) {
        return reply.code(403).send({ success: false, error: 'Account banned' });
      }
      dbHelpers.updateUser(dbUser.id, { banned: false, banReason: null, banExpires: null } as any);
    }
    let signInRes: any = null;
    try {
      signInRes = await getAuth().api.signInEmail({
        body: { email: dbUser.email, password },
        headers: request.headers,
      } as any);
    } catch (err) {
      log.error(`Sign in failed: ${dbUser.username}: ${err instanceof Error ? err.message : String(err)}`);
    }
    if (!signInRes || !signInRes.token) {
      dbHelpers.recordLoginAttempt(ip, lockoutIdentifier);
      dbHelpers.addLog('AUTH', 'LOGIN', `Failed login attempt for: ${dbUser.username}`, JSON.stringify({ ip }));
      return reply.code(401).send({ success: false, error: 'Invalid credentials' });
    }
    const sessionToken = signInRes.token as string;
    reply.setCookie('fason.session_token', sessionToken, {
      httpOnly: true,
      sameSite: 'strict',
      secure: process.env.NODE_ENV === 'production',
      path: '/',
      maxAge: Math.floor(config.security.sessionTimeout / 1000),
    });
    dbHelpers.updateUser(dbUser.id, { lastLogin: new Date() as any });
    const permissions = resolvePermissions(dbUser.role as UserRole, dbUser.permissions);
    dbHelpers.addLog('AUTH', 'LOGIN', `User ${dbUser.username} logged in`, JSON.stringify({ ip, role: dbUser.role }));
    return {
      success: true,
      data: {
        id: dbUser.id,
        token: sessionToken,
        username: dbUser.username,
        email: dbUser.email,
        role: dbUser.role,
        permissions,
      },
    };
  });

  app.post('/api/auth/logout', async (request, reply) => {
    let username: string | undefined;
    try {
      const authHeader = request.headers.authorization;
      let token: string | undefined;
      if (authHeader?.startsWith('Bearer ')) {
        token = authHeader.substring(7);
      } else if (request.cookies?.['fason.session_token']) {
        token = request.cookies['fason.session_token'];
      }
      if (token) {
        const sessionUser = await verifySessionToken(token);
        if (sessionUser) {
          username = sessionUser.username;
          dbHelpers.deleteSessionById(sessionUser.sessionId);
        }
      }
} catch {
}
    reply.clearCookie('fason.session_token', { path: '/' });
    reply.clearCookie('fason.session_token.sig', { path: '/' });
    if (username) {
      dbHelpers.addLog('AUTH', 'LOGOUT', `User ${username} logged out`);
    }
    return { success: true };
  });

  app.get('/api/auth/me', {
    preHandler: [app.auth],
  }, async (request, reply) => {
    const user = getRequestUser(request);
    const dbUser = dbHelpers.getUserById(user.userId);
    if (!dbUser) {
      return reply.code(404).send({ success: false, error: 'User not found' });
    }
    const permissions = resolvePermissions(dbUser.role as UserRole, dbUser.permissions);
    return {
      success: true,
      data: {
        id: dbUser.id,
        username: dbUser.username,
        email: dbUser.email,
        role: dbUser.role,
        permissions,
      },
    };
  });

  app.get('/api/auth/sessions', {
    preHandler: [app.auth],
  }, async (request) => {
    const user = getRequestUser(request);
    const d = getDb();
    const nowDate = new Date();
    const rows = user.role === 'admin'
      ? d.select({
          id: sessionTable.id,
          token: sessionTable.token,
          userId: sessionTable.userId,
          username: userTable.username,
          ip: sessionTable.ipAddress,
          userAgent: sessionTable.userAgent,
          createdAt: sessionTable.createdAt,
          expiresAt: sessionTable.expiresAt,
        }).from(sessionTable)
          .leftJoin(userTable, eq(userTable.id, sessionTable.userId))
          .where(gt(sessionTable.expiresAt, nowDate))
          .orderBy(sessionTable.createdAt)
          .all()
      : d.select({
          id: sessionTable.id,
          token: sessionTable.token,
          userId: sessionTable.userId,
          username: userTable.username,
          ip: sessionTable.ipAddress,
          userAgent: sessionTable.userAgent,
          createdAt: sessionTable.createdAt,
          expiresAt: sessionTable.expiresAt,
        }).from(sessionTable)
          .leftJoin(userTable, eq(userTable.id, sessionTable.userId))
          .where(and(eq(sessionTable.userId, user.userId), gt(sessionTable.expiresAt, nowDate)))
          .orderBy(sessionTable.createdAt)
          .all();
    const data = rows.map((r) => ({
      id: r.id,
      userId: r.userId,
      username: r.username,
      ip: r.ip,
      userAgent: r.userAgent,
      createdAt: r.createdAt,
      expiresAt: r.expiresAt,
      isCurrent: user.sessionToken === r.token,
    }));
    return { success: true, data };
  });

  app.delete('/api/auth/sessions/:id', {
    preHandler: [app.auth],
  }, async (request, reply) => {
    const { id } = request.params as { id: string };

    const user = getRequestUser(request);
    const d = getDb();
    const row = d.select({ id: sessionTable.id, userId: sessionTable.userId, token: sessionTable.token })
      .from(sessionTable).where(eq(sessionTable.id, id)).get();
    if (!row) {
      return reply.code(404).send({ success: false, error: 'Session not found' });
    }
    if (user.role !== 'admin' && row.userId !== user.userId) {
      return reply.code(403).send({ success: false, error: 'Cannot revoke another user\'s session' });
    }
    if (row.token === user.sessionToken) {
      return reply.code(400).send({ success: false, error: 'Cannot revoke your current session - use logout instead' });
    }
    d.delete(sessionTable).where(eq(sessionTable.id, id)).run();
    dbHelpers.addLog('AUTH', 'SESSION', `Session ${id} revoked by ${user.username}`);
    return { success: true };
  });

  app.post('/api/auth/change-password', {
    preHandler: [app.auth],
  }, async (request, reply) => {
    const { currentPassword, newPassword } = (request.body || {}) as { currentPassword?: string; newPassword?: string };

    const user = getRequestUser(request);
    if (!currentPassword || !newPassword) {
      return reply.code(400).send({ success: false, error: 'Current password and new password are required' });
    }
    const passwordValidation = validatePasswordStrength(newPassword);
    if (!passwordValidation.valid) {
      return reply.code(400).send({ success: false, error: passwordValidation.message });
    }
    const dbUser = dbHelpers.getUserById(user.userId);
    if (!dbUser) {
      return reply.code(404).send({ success: false, error: 'User not found' });
    }
    const d = getDb();
    const accountRow = d.select({ password: accountTable.password })
      .from(accountTable)
      .where(and(eq(accountTable.userId, user.userId), eq(accountTable.providerId, 'credential')))
      .get();
    if (!accountRow?.password) {
      return reply.code(500).send({ success: false, error: 'Account record missing' });
    }
    const isValid = await verifyPasswordScrypt(currentPassword, accountRow.password);
    if (!isValid) {
      return reply.code(401).send({ success: false, error: 'Current password is incorrect' });
    }
    const newHash = await hashPasswordScrypt(newPassword);
    dbHelpers.updateUserPassword(user.userId, newHash);
    dbHelpers.deleteOtherSessions(user.userId, user.sessionToken);
    dbHelpers.addLog('AUTH', 'PASSWORD', `User ${user.username} changed their password`);
    return { success: true, message: 'Password changed successfully' };
  });

  app.post('/api/auth/update-profile', {
    preHandler: [app.auth],
  }, async (request, reply) => {
    const { username, email } = (request.body || {}) as { username?: string; email?: string };

    const user = getRequestUser(request);
    const updates: { username?: string; email?: string } = {};
    if (username !== undefined) {
      const validation = validateUsername(username);
      if (!validation.valid) {
        return reply.code(400).send({ success: false, error: validation.message });
      }
      const d = getDb();
      const existing = d.select({ id: userTable.id }).from(userTable)
        .where(and(eq(sql`LOWER(${userTable.username})`, username.toLowerCase()), ne(userTable.id, user.userId))).get();
      if (existing) {
        return reply.code(409).send({ success: false, error: 'Username already taken' });
      }
      updates.username = username.toLowerCase();
    }
    if (email !== undefined) {
      const validation = validateEmail(email);
      if (!validation.valid) {
        return reply.code(400).send({ success: false, error: validation.message });
      }
      const d = getDb();
      const existing = d.select({ id: userTable.id }).from(userTable)
        .where(and(eq(sql`LOWER(${userTable.email})`, email.toLowerCase()), ne(userTable.id, user.userId))).get();
      if (existing) {
        return reply.code(409).send({ success: false, error: 'Email already taken' });
      }
      updates.email = email.toLowerCase();
    }
    if (Object.keys(updates).length === 0) {
      return reply.code(400).send({ success: false, error: 'No fields to update' });
    }
    dbHelpers.updateUser(user.userId, updates);
    dbHelpers.addLog('AUTH', 'PROFILE', `User ${user.username} updated their profile`, JSON.stringify(updates));
    return { success: true, message: 'Profile updated successfully' };
  });
}

let DUMMY_SCRYPT_HASH = '';

async function ensureDummyHash() {
  if (!DUMMY_SCRYPT_HASH) {
    const { hashPassword } = await import('better-auth/crypto');
    DUMMY_SCRYPT_HASH = await hashPassword(crypto.randomBytes(16).toString('hex'));
  }
}
ensureDummyHash().catch(() => {
});
