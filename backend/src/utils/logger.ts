import pino from 'pino';

const isDev = process.env.NODE_ENV !== 'production';
export const logger = pino({
  ...(isDev ? {
    transport: {
      target: 'pino-pretty',
      options: {
        colorize: true,
        translateTime: 'SYS:yyyy-mm-dd HH:MM:ss',
        ignore: 'pid,hostname',
      },
    },
  } : {}),
  level: process.env.LOG_LEVEL || 'info',
});
export const log = {
  info: (msg: string, ...args: unknown[]) => logger.info({ module: args[0] || 'APP' }, msg),
  error: (msg: string, ...args: unknown[]) => logger.error({ module: args[0] || 'APP' }, msg),
  warn: (msg: string, ...args: unknown[]) => logger.warn({ module: args[0] || 'APP' }, msg),
  debug: (msg: string, ...args: unknown[]) => logger.debug({ module: args[0] || 'APP' }, msg),
};
