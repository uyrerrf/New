import type { FastifyInstance } from 'fastify';
import { hashPassword } from 'better-auth/crypto';
import { requirePermission, getRequestUser } from '../middleware/auth.js';
import { getDb, dbHelpers, getSqliteDb } from '../db/index.js';
import { user as userTable, account as accountTable, session as sessionTable } from '../db/schema.js';
import { sql, eq, and, ne } from 'drizzle-orm';
import { validateUsername, validatePasswordStrength, validateEmail } from '../utils/helpers.js';
import { ALL_PERMISSIONS, DEFAULT_USER_PERMISSIONS, PERMISSION_GROUPS, resolvePermissions } from '../types/index.js';
import type { UserRole, Permission } from '../types/index.js';
import { socketService } from '../services/socket.js';
import crypto from 'crypto';

export async function userRoutes(app: FastifyInstance) {
  const manageUsers = [app.auth, requirePermission('users:manage')];
  app.get('/api/users/permissions-schema', {
    preHandler: manageUsers,
  }, async () => {
    return {
      success: true,
      data: {
        permissions: ALL_PERMISSIONS,
        groups: PERMISSION_GROUPS,
        defaults: DEFAULT_USER_PERMISSIONS,
      },
    };
  });

  app.get('/api/users', {
    preHandler: manageUsers,
  }, async () => {
    const allUsers = dbHelpers.getAllUsers();
    return {
      success: true,
      data: allUsers.map(u => ({
        id: u.id,
        username: u.username,
        email: u.email,
        role: u.role,
        permissions: resolvePermissions(u.role as UserRole, u.permissions),
        isDefault: u.isDefault,
        createdAt: u.createdAt,
        lastLogin: u.lastLogin,
        banned: u.banned,
        deviceSecret: u.deviceSecret ? '***' : null,
      })),
    };
  });

  app.post('/api/users', {
    preHandler: manageUsers,
  }, async (request, reply) => {
    const { username, email, password, role, permissions: reqPermissions } = (request.body || {}) as {
      username?: string;
      email?: string;
      password?: string;
      role?: UserRole;
      permissions?: Permission[];
    };
    if (!username || !email || !password) {
      return reply.code(400).send({ success: false, error: 'Username, email, and password are required' });
    }
    const usernameValidation = validateUsername(username);
    if (!usernameValidation.valid) {
      return reply.code(400).send({ success: false, error: usernameValidation.message });
    }
    const emailValidation = validateEmail(email);
    if (!emailValidation.valid) {
      return reply.code(400).send({ success: false, error: emailValidation.message });
    }
    const passwordValidation = validatePasswordStrength(password);
    if (!passwordValidation.valid) {
      return reply.code(400).send({ success: false, error: passwordValidation.message });
    }
    const d = getDb();
    const existingUsername = d.select({ id: userTable.id }).from(userTable)
      .where(eq(sql`LOWER(${userTable.username})`, username.toLowerCase())).get();
    if (existingUsername) {
      return reply.code(409).send({ success: false, error: 'Username already exists' });
    }
    const existingEmail = d.select({ id: userTable.id }).from(userTable)
      .where(eq(sql`LOWER(${userTable.email})`, email.toLowerCase())).get();
    if (existingEmail) {
      return reply.code(409).send({ success: false, error: 'Email already exists' });
    }
    const requestingUser = getRequestUser(request);
    const userRole: UserRole = (role === 'admin' && requestingUser.role === 'admin') ? 'admin' : 'user';
    let userPermissions: Permission[] | undefined;
    if (userRole === 'user' && reqPermissions) {
      if (!Array.isArray(reqPermissions)) {
        return reply.code(400).send({ success: false, error: 'Permissions must be an array' });
      }
      if (requestingUser.role !== 'admin') {
        const requesterPerms = new Set(requestingUser.permissions);
        const disallowed = reqPermissions.filter((p: string) => !requesterPerms.has(p as Permission));
        if (disallowed.length > 0) {
          return reply.code(403).send({ success: false, error: `You cannot grant permissions you don't have: ${disallowed.join(', ')}` });
        }
      }
      userPermissions = Array.from(new Set(reqPermissions.filter((p: string) => ALL_PERMISSIONS.includes(p as Permission))));
    }
    const userId = crypto.randomUUID();
    const passwordHash = await hashPassword(password);
    const perms = userRole === 'admin'
      ? JSON.stringify(ALL_PERMISSIONS)
      : JSON.stringify(userPermissions || DEFAULT_USER_PERMISSIONS);
    const deviceSecret = crypto.randomBytes(24).toString('base64url');
    const now = new Date();
    try {
      getSqliteDb().transaction(() => {
        d.insert(userTable).values({
          id: userId,
          email: email.toLowerCase(),
          emailVerified: false,
          name: username.toLowerCase(),
          username: username.toLowerCase(),
          role: userRole,
          permissions: perms,
          isDefault: 0,
          deviceSecret,
          createdAt: now,
          updatedAt: now,
        }).run();
        d.insert(accountTable).values({
          id: crypto.randomUUID(),
          providerId: 'credential',
          accountId: userId,
          userId,
          password: passwordHash,
        }).run();
      })();
    } catch (err: any) {
      return reply.code(500).send({ success: false, error: `Failed to create user: ${err.message}` });
    }
    dbHelpers.invalidateDeviceSecretsCache();
    dbHelpers.addLog('ADMIN', 'USER', `User ${username} created by admin`, JSON.stringify({ role: userRole }));
    return {
      success: true,
      data: {
        id: userId,
        username: username.toLowerCase(),
        email: email.toLowerCase(),
        role: userRole,
        deviceSecret,
      },
    };
  });

  app.put('/api/users/:id', {
    preHandler: manageUsers,
  }, async (request, reply) => {
    const { id } = request.params as { id: string };

    const { username, email, role, permissions: reqPermissions } = (request.body || {}) as {
      username?: string;
      email?: string;
      role?: UserRole;
      permissions?: Permission[];
    };

    const requestingUser = getRequestUser(request);
    const existingUser = dbHelpers.getUserById(id);
    if (!existingUser) {
      return reply.code(404).send({ success: false, error: 'User not found' });
    }
    if (existingUser.isDefault === 1) {
      return reply.code(403).send({ success: false, error: 'Cannot edit the default admin account' });
    }
    if (existingUser.role === 'admin' && requestingUser.role !== 'admin') {
      return reply.code(403).send({ success: false, error: 'Only admins can modify admin accounts' });
    }
    if (id === requestingUser.userId && reqPermissions !== undefined) {
      return reply.code(403).send({ success: false, error: 'Cannot modify your own permissions' });
    }
    const updates: { username?: string; email?: string; role?: UserRole; permissions?: string } = {};
    if (username !== undefined) {
      const validation = validateUsername(username);
      if (!validation.valid) {
        return reply.code(400).send({ success: false, error: validation.message });
      }
      const d = getDb();
      const existing = d.select({ id: userTable.id }).from(userTable)
        .where(and(eq(sql`LOWER(${userTable.username})`, username.toLowerCase()), ne(userTable.id, id))).get();
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
        .where(and(eq(sql`LOWER(${userTable.email})`, email.toLowerCase()), ne(userTable.id, id))).get();
      if (existing) {
        return reply.code(409).send({ success: false, error: 'Email already taken' });
      }
      updates.email = email.toLowerCase();
    }
    if (role !== undefined) {
      if (!['admin', 'user'].includes(role)) {
        return reply.code(400).send({ success: false, error: 'Invalid role. Must be admin or user' });
      }
      if (role === 'admin' && requestingUser.role !== 'admin') {
        return reply.code(403).send({ success: false, error: 'Only admins can promote users to admin role' });
      }
      if (existingUser.role === 'admin' && role === 'user') {
        if (requestingUser.role !== 'admin') {
          return reply.code(403).send({ success: false, error: 'Only admins can demote admin users' });
        }
        const adminCount = dbHelpers.getAdminCount();
        if (adminCount <= 1) {
          return reply.code(400).send({ success: false, error: 'Cannot demote the last admin' });
        }
      }
      updates.role = role;
      if (role === 'admin') {
        updates.permissions = JSON.stringify(ALL_PERMISSIONS);
      } else if (role === 'user' && !reqPermissions) {
        updates.permissions = JSON.stringify(DEFAULT_USER_PERMISSIONS);
      }
    }
    if (reqPermissions !== undefined && (updates.role === 'user' || (existingUser.role === 'user' && updates.role === undefined))) {
      if (!Array.isArray(reqPermissions)) {
        return reply.code(400).send({ success: false, error: 'Permissions must be an array' });
      }
      if (requestingUser.role !== 'admin') {
        const requesterPerms = new Set(requestingUser.permissions);
        const disallowed = reqPermissions.filter((p: string) => !requesterPerms.has(p as Permission));
        if (disallowed.length > 0) {
          return reply.code(403).send({ success: false, error: `You cannot grant permissions you don't have: ${disallowed.join(', ')}` });
        }
      }
      const validPerms = Array.from(new Set(reqPermissions.filter((p: string) => ALL_PERMISSIONS.includes(p as Permission))));
      updates.permissions = JSON.stringify(validPerms);
    }
    if (Object.keys(updates).length === 0) {
      return reply.code(400).send({ success: false, error: 'No fields to update' });
    }
    dbHelpers.updateUser(id, updates);
    dbHelpers.addLog('ADMIN', 'USER', `User ${existingUser.username} updated by admin`, JSON.stringify(updates));
    return { success: true, message: 'User updated successfully' };
  });

  app.put('/api/users/:id/permissions', {
    preHandler: manageUsers,
  }, async (request, reply) => {
    const { id } = request.params as { id: string };

    const { permissions: reqPermissions } = (request.body || {}) as { permissions?: Permission[] };
    if (!Array.isArray(reqPermissions)) {
      return reply.code(400).send({ success: false, error: 'Permissions must be an array' });
    }
    const existingUser = dbHelpers.getUserById(id);
    if (!existingUser) {
      return reply.code(404).send({ success: false, error: 'User not found' });
    }
    if (existingUser.role === 'admin') {
      return reply.code(400).send({ success: false, error: 'Admin permissions cannot be customized. Admins always have all permissions.' });
    }
    const requestingUser = getRequestUser(request);
    if (requestingUser.role !== 'admin') {
      const requesterPerms = new Set(requestingUser.permissions);
      const disallowed = reqPermissions.filter((p: string) => !requesterPerms.has(p as Permission));
      if (disallowed.length > 0) {
        return reply.code(403).send({ success: false, error: `You cannot grant permissions you don't have: ${disallowed.join(', ')}` });
      }
    }
    const validPerms = reqPermissions.filter((p: string) => ALL_PERMISSIONS.includes(p as Permission)) as Permission[];
    dbHelpers.updateUser(id, { permissions: JSON.stringify(validPerms) });
    dbHelpers.addLog('ADMIN', 'PERMISSIONS', `Permissions updated for user ${existingUser.username}`, JSON.stringify({ permissions: validPerms }));
    return { success: true, message: 'Permissions updated successfully', data: { permissions: validPerms } };
  });

  app.put('/api/users/:id/password', {
    preHandler: manageUsers,
  }, async (request, reply) => {
    const { id } = request.params as { id: string };

    const { password } = (request.body || {}) as { password?: string };
    if (!password) {
      return reply.code(400).send({ success: false, error: 'Password is required' });
    }
    const passwordValidation = validatePasswordStrength(password);
    if (!passwordValidation.valid) {
      return reply.code(400).send({ success: false, error: passwordValidation.message });
    }
    const existingUser = dbHelpers.getUserById(id);
    if (!existingUser) {
      return reply.code(404).send({ success: false, error: 'User not found' });
    }
    if (existingUser.isDefault === 1) {
      return reply.code(403).send({ success: false, error: 'Cannot reset password of the default admin account' });
    }
    const requestingUser = getRequestUser(request);
    if (existingUser.role === 'admin' && requestingUser.role !== 'admin') {
      return reply.code(403).send({ success: false, error: 'Only admins can modify admin accounts' });
    }
    const hash = await hashPassword(password);
    dbHelpers.updateUserPassword(id, hash);
    getDb().delete(sessionTable).where(eq(sessionTable.userId, id)).run();
    dbHelpers.addLog('ADMIN', 'USER', `Password reset for user ${existingUser.username} by admin`);
    return { success: true, message: 'Password reset successfully' };
  });

  app.post('/api/users/:id/regenerate-secret', {
    preHandler: manageUsers,
  }, async (request, reply) => {
    const { id } = request.params as { id: string };

    const existingUser = dbHelpers.getUserById(id);
    if (!existingUser) {
      return reply.code(404).send({ success: false, error: 'User not found' });
    }
    if (existingUser.isDefault === 1) {
      return reply.code(403).send({ success: false, error: 'Cannot regenerate secret for the default admin account' });
    }
    const requestingUser = getRequestUser(request);
    if (existingUser.role === 'admin' && requestingUser.role !== 'admin') {
      return reply.code(403).send({ success: false, error: 'Only admins can modify admin accounts' });
    }
    const newSecret = crypto.randomBytes(24).toString('base64url');
    getDb().update(userTable).set({ deviceSecret: newSecret, updatedAt: new Date() }).where(eq(userTable.id, id)).run();
    dbHelpers.invalidateDeviceSecretsCache();
    getDb().delete(sessionTable).where(eq(sessionTable.userId, id)).run();
    dbHelpers.addLog('ADMIN', 'DEVICE', `Device secret regenerated for user ${existingUser.username}`);
    return { success: true, data: { deviceSecret: newSecret } };
  });

  app.get('/api/users/:id/secret', {
    preHandler: manageUsers,
  }, async (request, reply) => {
    const { id } = request.params as { id: string };

    const existingUser = dbHelpers.getUserById(id);
    if (!existingUser) {
      return reply.code(404).send({ success: false, error: 'User not found' });
    }
    const requestingUser = getRequestUser(request);
    if (existingUser.role === 'admin' && requestingUser.role !== 'admin') {
      return reply.code(403).send({ success: false, error: 'Only admins can view admin secrets' });
    }
    return { success: true, data: { deviceSecret: existingUser.deviceSecret || null } };
  });

  app.post('/api/users/:id/secret', {
    preHandler: manageUsers,
  }, async (request, reply) => {
    const { id } = request.params as { id: string };

    const { value } = (request.body || {}) as { value?: unknown };

    const existingUser = dbHelpers.getUserById(id);
    if (!existingUser) {
      return reply.code(404).send({ success: false, error: 'User not found' });
    }
    if (existingUser.isDefault === 1) {
      return reply.code(403).send({ success: false, error: 'Cannot set secret for the default admin account' });
    }
    const requestingUser = getRequestUser(request);
    if (existingUser.role === 'admin' && requestingUser.role !== 'admin') {
      return reply.code(403).send({ success: false, error: 'Only admins can modify admin accounts' });
    }
    if (value == null || typeof value !== 'string') {
      return reply.code(400).send({ success: false, error: 'Secret value is required' });
    }
    const secretValue = value.trim();
    if (secretValue.length < 8) {
      return reply.code(400).send({ success: false, error: 'Secret must be at least 8 characters' });
    }
    if (secretValue.length > 256) {
      return reply.code(400).send({ success: false, error: 'Secret must be at most 256 characters' });
    }
    if (/[\r\n=]/.test(secretValue)) {
      return reply.code(400).send({ success: false, error: 'Secret must not contain newlines or "=" characters' });
    }
    getDb().update(userTable).set({ deviceSecret: secretValue, updatedAt: new Date() }).where(eq(userTable.id, id)).run();
    dbHelpers.invalidateDeviceSecretsCache();
    getDb().delete(sessionTable).where(eq(sessionTable.userId, id)).run();
    dbHelpers.addLog('ADMIN', 'SECURITY', `Admin manually set device secret for user ${existingUser.username}`);
    return { success: true, data: { deviceSecret: secretValue } };
  });

  app.delete('/api/users/:id', {
    preHandler: manageUsers,
  }, async (request, reply) => {
    const { id } = request.params as { id: string };

    const requestingUser = getRequestUser(request);
    if (id === requestingUser.userId) {
      return reply.code(400).send({ success: false, error: 'Cannot delete your own account' });
    }
    const existingUser = dbHelpers.getUserById(id);
    if (!existingUser) {
      return reply.code(404).send({ success: false, error: 'User not found' });
    }
    if (existingUser.role === 'admin') {
      const requestingUser2 = getRequestUser(request);
      if (requestingUser2.role !== 'admin') {
        return reply.code(403).send({ success: false, error: 'Only admins can delete admin accounts' });
      }
      const adminCount = dbHelpers.getAdminCount();
      if (adminCount <= 1) {
        return reply.code(400).send({ success: false, error: 'Cannot delete the last admin' });
      }
    }
    if (existingUser.isDefault === 1) {
      return reply.code(403).send({ success: false, error: 'Cannot delete the default admin account' });
    }
    const affectedDeviceIds = dbHelpers.deleteUser(id);
    for (const devId of affectedDeviceIds) {
      socketService.invalidateDeviceOwner(devId);
    }
    dbHelpers.addLog('ADMIN', 'USER', `User ${existingUser.username} deleted by admin`);
    return { success: true, message: 'User deleted successfully' };
  });
}
