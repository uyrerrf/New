import type { FastifyRequest, FastifyReply } from 'fastify';
import { getAuth } from '../auth/index.js';
import { dbHelpers } from '../db/index.js';
import { resolvePermissions } from '../types/index.js';
import type { SessionUser, UserRole, Permission } from '../types/index.js';

export async function authMiddleware(request: FastifyRequest, reply: FastifyReply): Promise<void> {
  try {
    let headers = request.headers;
    const authHeader = request.headers.authorization;
    if (!authHeader) {
      const cookieToken = request.cookies?.['fason.session_token'];
      if (cookieToken) {
        headers = { ...request.headers, authorization: `Bearer ${cookieToken}` };
      }
    }
    const session = await getAuth().api.getSession({ headers });
    if (!session || !session.user) {
      reply.code(401).send({ success: false, error: 'Authentication required' });
      return;
    }
    const dbUser = dbHelpers.getUserById(session.user.id);
    if (!dbUser) {
      reply.code(401).send({ success: false, error: 'User not found' });
      return;
    }
    if (dbUser.banned) {
      const banExpires = dbUser.banExpires;
      const isBanExpired = banExpires && new Date(banExpires) < new Date();
      if (!isBanExpired) {
        reply.code(403).send({ success: false, error: 'Account banned' });
        return;
      }
      dbHelpers.updateUser(dbUser.id, { banned: false, banReason: null, banExpires: null } as any);
    }
    const permissions = resolvePermissions(dbUser.role as UserRole, dbUser.permissions);
    const sessionUser: SessionUser = {
      userId: dbUser.id,
      username: dbUser.username,
      email: dbUser.email,
      role: dbUser.role as UserRole,
      permissions,
      sessionId: session.session.id,
      sessionToken: session.session.token,
    };
    request.user = sessionUser as any;
  } catch (err) {
    request.log?.error?.({ err }, 'auth middleware error');
    reply.code(401).send({ success: false, error: 'Invalid session' });
  }
}

export function requirePermission(permission: Permission) {
  return async (request: FastifyRequest, reply: FastifyReply): Promise<void> => {
    const user = request.user as SessionUser | undefined;
    if (!user) {
      reply.code(401).send({ success: false, error: 'Authentication required' });
      return;
    }
    if (!user.permissions || !user.permissions.includes(permission)) {
      reply.code(403).send({ success: false, error: 'Insufficient permissions' });
      return;
    }
  };
}

export function hasPermission(user: SessionUser | undefined, permission: Permission): boolean {
  if (!user?.permissions) return false;
  return user.permissions.includes(permission);
}

export function getRequestUser(request: FastifyRequest): SessionUser {
  return request.user as SessionUser;
}

export async function verifySessionToken(token: string): Promise<SessionUser | null> {
  try {
    const headers = new Headers();
    headers.set('authorization', `Bearer ${token}`);
    const session = await getAuth().api.getSession({ headers });
    if (!session || !session.user) return null;
    const dbUser = dbHelpers.getUserById(session.user.id);
    if (!dbUser) return null;
    if (dbUser.banned) {
      const isBanExpired = dbUser.banExpires && new Date(dbUser.banExpires) < new Date();
      if (!isBanExpired) return null;
      dbHelpers.updateUser(dbUser.id, { banned: false, banReason: null, banExpires: null } as any);
    }
    const permissions = resolvePermissions(dbUser.role as UserRole, dbUser.permissions);
    return {
      userId: dbUser.id,
      username: dbUser.username,
      email: dbUser.email,
      role: dbUser.role as UserRole,
      permissions,
      sessionId: session.session.id,
      sessionToken: session.session.token,
    };
  } catch {
    return null;
  }
}
