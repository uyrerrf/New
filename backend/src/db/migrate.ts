import { initDb, closeDb } from './index.js';
import { log } from '../utils/logger.js';

async function runMigrations() {
  log.info('Running migrations...');
  initDb();
  log.info('Migrations done');
  closeDb();
  process.exit(0);
}
runMigrations().catch((err) => {
  console.error('Migration failed:', err);
  process.exit(1);
});
