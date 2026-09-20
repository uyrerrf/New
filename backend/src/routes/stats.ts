import type { FastifyInstance } from 'fastify';
import { getDb } from '../db/index.js';
import { clients, logs } from '../db/schema.js';
import { eq, count, gte, and } from 'drizzle-orm';
import { requirePermission, getRequestUser } from '../middleware/auth.js';

export async function statsRoutes(app: FastifyInstance) {
  app.get('/api/stats', {
    preHandler: [app.auth, requirePermission('stats:view')],
  }, async (request) => {
    const user = getRequestUser(request);
    const d = getDb();
    const ownerFilter = user.role === 'admin' ? undefined : eq(clients.ownerId, user.userId);
    const isAdmin = user.role === 'admin';
    const onlineClients = (d.select({ count: count() }).from(clients)
      .where(ownerFilter ? and(eq(clients.online, true), ownerFilter) : eq(clients.online, true)).get())?.count ?? 0;
    const offlineClients = (d.select({ count: count() }).from(clients)
      .where(ownerFilter ? and(eq(clients.online, false), ownerFilter) : eq(clients.online, false)).get())?.count ?? 0;
    const totalLogs = isAdmin ? ((d.select({ count: count() }).from(logs).get())?.count ?? 0) : undefined;
    const todayStart = new Date().toISOString().split('T')[0] + 'T00:00:00.000Z';
    const todayLogs = isAdmin ? ((d.select({ count: count() }).from(logs).where(gte(logs.createdAt, todayStart)).get())?.count ?? 0) : undefined;
    const memoryUsage = process.memoryUsage();
    return {
      success: true,
      data: {
        clients: {
          online: onlineClients,
          offline: offlineClients,
          total: onlineClients + offlineClients,
        },
        logs: {
          total: totalLogs,
          today: todayLogs,
        },
        system: {
          uptime: Math.floor(process.uptime()),
          memoryUsage: Math.round(memoryUsage.heapUsed / 1024 / 1024),
          memoryTotal: Math.round(memoryUsage.heapTotal / 1024 / 1024),
          rss: Math.round(memoryUsage.rss / 1024 / 1024),
          nodeVersion: isAdmin ? process.version : undefined,
          platform: isAdmin ? process.platform : undefined,
        },
      },
    };
  });
}
