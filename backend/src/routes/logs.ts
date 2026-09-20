import type { FastifyInstance } from 'fastify';
import { getDb } from '../db/index.js';
import { logs } from '../db/schema.js';
import { eq, desc, count, and, sql } from 'drizzle-orm';
import { requirePermission } from '../middleware/auth.js';

function escapeLikeWildcards(str: string): string {
  return str.replace(/[%_\\]/g, '\\$&');
}

export async function logsRoutes(app: FastifyInstance) {
  app.get('/api/logs', {
    preHandler: [app.auth, requirePermission('logs:view')],
  }, async (request) => {
    const query = request.query as {
      type?: string;
      category?: string;
      search?: string;
      limit?: string;
      offset?: string;
    };

    const d = getDb();
    const limit = Math.min(parseInt(query.limit || '100', 10) || 100, 1000);
    const offset = Math.max(parseInt(query.offset || '0', 10) || 0, 0);
    const conditions = [];
    if (query.type) conditions.push(eq(logs.type, query.type));
    if (query.category) conditions.push(eq(logs.category, query.category));
    if (query.search) {
      const pattern = `%${escapeLikeWildcards(query.search)}%`;
      conditions.push(sql`${logs.message} LIKE ${pattern} ESCAPE '\\'`);
    }
    const result = d.select({
      id: logs.id,
      type: logs.type,
      category: logs.category,
      message: logs.message,
      details: logs.details,
      created_at: logs.createdAt,
    }).from(logs)
      .where(conditions.length > 0 ? and(...conditions) : undefined)
      .orderBy(desc(logs.createdAt))
      .limit(limit)
      .offset(offset)
      .all();
    return { success: true, data: result };
  });

  app.get('/api/logs/stats', {
    preHandler: [app.auth, requirePermission('logs:view')],
  }, async () => {
    const d = getDb();
    const statsRow = d.select({
      total: count(),
      today: sql<number>`SUM(CASE WHEN date(${logs.createdAt}) = date('now') THEN 1 ELSE 0 END)`,
      lastHour: sql<number>`SUM(CASE WHEN datetime(${logs.createdAt}) > datetime('now', '-1 hour') THEN 1 ELSE 0 END)`,
      errors: sql<number>`SUM(CASE WHEN ${logs.type} = 'ERROR' THEN 1 ELSE 0 END)`,
      warnings: sql<number>`SUM(CASE WHEN ${logs.type} = 'WARNING' THEN 1 ELSE 0 END)`,
      connections: sql<number>`SUM(CASE WHEN ${logs.type} = 'CONNECTION' THEN 1 ELSE 0 END)`,
      disconnections: sql<number>`SUM(CASE WHEN ${logs.type} = 'DISCONNECTION' THEN 1 ELSE 0 END)`,
    }).from(logs).get();
    const byType = d.select({ type: logs.type, count: count() }).from(logs).groupBy(logs.type).orderBy(desc(count())).all();
    const byCategory = d.select({ category: logs.category, count: count() }).from(logs).groupBy(logs.category).orderBy(desc(count())).all();
    return {
      success: true,
      data: {
        total: statsRow?.total ?? 0,
        today: statsRow?.today ?? 0,
        lastHour: statsRow?.lastHour ?? 0,
        errors: statsRow?.errors ?? 0,
        warnings: statsRow?.warnings ?? 0,
        connections: statsRow?.connections ?? 0,
        disconnections: statsRow?.disconnections ?? 0,
        byType,
        byCategory,
      },
    };
  });

  app.post('/api/logs/clear', {
    preHandler: [app.auth, requirePermission('logs:clear')],
  }, async () => {
    const d = getDb();
    d.delete(logs).run();
    return { success: true, message: 'Logs cleared' };
  });
}
