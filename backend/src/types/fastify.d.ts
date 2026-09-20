import type { SessionUser } from './index.js';
import type { FastifyReply, FastifyRequest } from 'fastify';
declare module 'fastify' {
  interface FastifyRequest {
    user?: SessionUser;
  }
  interface FastifyInstance {
    auth: (request: FastifyRequest, reply: FastifyReply) => Promise<void>;
  }
}

export {};
