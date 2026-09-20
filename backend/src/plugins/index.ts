import type { FastifyInstance } from 'fastify';
import fp from 'fastify-plugin';
import cookie from '@fastify/cookie';
import cors from '@fastify/cors';
import rateLimit from '@fastify/rate-limit';
import multipart from '@fastify/multipart';
import { toNodeHandler } from 'better-auth/node';
import { getConfig } from '../config/index.js';
import { getAuth } from '../auth/index.js';
import { isSetupComplete } from '../routes/setup.js';

async function plugins(app: FastifyInstance) {
  app.addContentTypeParser('application/json', { parseAs: 'string' }, (req, body, done) => {
    if (!body || (typeof body === 'string' && body.trim() === '')) {
      done(null, {});
      return;
    }
    try {
      done(null, JSON.parse(body as string));
    } catch (err) {
      done(err as Error, undefined);
    }
  });
  const config = getConfig();
  await app.register(cookie);
  await app.register(cors, {
    origin: true,
    methods: ['GET', 'POST', 'DELETE', 'PUT', 'PATCH', 'OPTIONS'] as any,
    credentials: true,
    exposedHeaders: ['set-auth-token'],
  });
  await app.register(rateLimit, {
    max: config.rateLimit.maxRequests,
    timeWindow: config.rateLimit.windowMs,
  });
  await app.register(multipart, {
    limits: {
      fileSize: 50 * 1024 * 1024,
    },
  });

  app.all('/api/auth/better/*', async (req, reply) => {
    const url = req.url.split('?')[0];
    const isSignUpEndpoint = url.includes('/sign-up/') || url.includes('/signup/');
    if (isSignUpEndpoint && isSetupComplete()) {
      return reply.code(403).send({ success: false, error: 'Self-registration is disabled. Ask an admin to create your account.' });
    }

    const auth = getAuth();
    const handler = toNodeHandler(auth.handler);
    await handler(req.raw, reply.raw);
    reply.hijack();
  });
}

export default fp(plugins, { name: 'app-plugins' });
