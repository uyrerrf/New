import { socketService } from './socket.js';
import { dbHelpers, getDb, getSqliteDb } from '../db/index.js';
import { clients } from '../db/schema.js';
import { eq } from 'drizzle-orm';
import { getConfig } from '../config/index.js';
import { log } from '../utils/logger.js';

interface Task {
  name: string;
  interval: number;
  handler: () => void | Promise<void>;
  stopOnError: boolean;
  timer?: NodeJS.Timeout;
}

class TaskManager {
  private tasks: Map<string, Task> = new Map();
  private running = false;

  register(name: string, interval: number, handler: () => void | Promise<void>, stopOnError = true): void {
    this.tasks.set(name, { name, interval, handler, stopOnError });
  }

  startAll(): void {
    if (this.running) return;
    this.running = true;
    for (const [name, task] of this.tasks) {
      const timer = setInterval(async () => {
        try { await task.handler(); } catch (err: unknown) {
          log.error(`Task ${name} failed: ${err instanceof Error ? err.message : String(err)}`);
          if (task.stopOnError) { clearInterval(timer); this.tasks.delete(name); }
        }
      }, task.interval);
      this.tasks.set(name, { ...task, timer });
    }
  }

  stopAll(): void {
    for (const [, task] of this.tasks) { if (task.timer) clearInterval(task.timer); }
    this.tasks.clear();
    this.running = false;
  }
}

export const taskManager = new TaskManager();
taskManager.register('cleanup', 3600000, () => {
  try {
    const deleted = socketService.cleanupStaleClients();
    if (deleted > 0) log.info(`Cleaned ${deleted} stale clients`);
  } catch (err: unknown) { log.error(`cleanup stale: ${err instanceof Error ? err.message : String(err)}`); }
  try {
    const sessions = dbHelpers.cleanExpiredSessions();
    if (sessions > 0) log.info(`Cleaned ${sessions} expired sessions`);
  } catch (err: unknown) { log.error(`cleanup sessions: ${err instanceof Error ? err.message : String(err)}`); }
  try {
    dbHelpers.cleanLoginAttempts(getConfig().security.loginLockout);
  } catch (err: unknown) { log.error(`cleanup attempts: ${err instanceof Error ? err.message : String(err)}`); }
  try {
    const cmds = dbHelpers.cleanOldCommands();
    if (cmds > 0) log.info(`Cleaned ${cmds} old commands`);
  } catch (err: unknown) { log.error(`cleanup commands: ${err instanceof Error ? err.message : String(err)}`); }
}, false);

taskManager.register('heartbeat', 30000, () => {
  try {
    const d = getDb();
    const onlineInDb = d.select({ id: clients.id }).from(clients).where(eq(clients.online, true)).all();
    const nowIso = new Date().toISOString();
    for (const client of onlineInDb) {
      if (!socketService.isClientConnected(client.id)) {
        d.update(clients).set({ online: false, lastSeen: nowIso }).where(eq(clients.id, client.id)).run();
      }
    }
  } catch (err: unknown) {
    log.error(`Heartbeat: ${err instanceof Error ? err.message : String(err)}`);
  }
}, false);

taskManager.register('transferCleanup', 300000, () => {
  socketService.cleanupStaleTransfers();
}, false);

taskManager.register('dbMaintenance', 3600000, () => {
  try {
    const d = getSqliteDb();
    d.pragma('optimize');
    log.info('DB maintenance done');
  } catch (err: unknown) { log.error(`DB maintenance: ${err instanceof Error ? err.message : String(err)}`); }
});
