import type { FastifyInstance, FastifyRequest, FastifyReply } from 'fastify';
import crypto from 'crypto';
import { getDb, dbHelpers } from '../db/index.js';
import { clientFiles, clients } from '../db/schema.js';
import { eq, and } from 'drizzle-orm';
import { requirePermission, hasPermission, getRequestUser } from '../middleware/auth.js';
import type { SessionUser } from '../types/index.js';
import { log } from '../utils/logger.js';
import { socketService } from '../services/socket.js';
import { CMD } from '../types/index.js';

function canAccessDevice(user: SessionUser, clientId: string): boolean {
  if (user.role === 'admin') return true;
  return dbHelpers.getDeviceOwnerId(clientId) === user.userId;
}

const FILE_TYPE_MAP: Record<string, string> = {
  photos: 'photo',
  recordings: 'recording',
  downloads: 'download',
  uploads: 'upload',
  videos: 'video',
};

const VALID_TYPES = Object.keys(FILE_TYPE_MAP);

export async function fileRoutes(app: FastifyInstance) {
  app.get('/api/files/:type/:id/:fileId', {
    preHandler: [app.auth, requirePermission('files:download')],
  }, async (request: FastifyRequest, reply: FastifyReply) => {
    const { type, id, fileId } = request.params as { type: string; id: string; fileId: string };
    if (!VALID_TYPES.includes(type)) {
      return reply.code(400).send({ success: false, error: `Invalid file type. Must be one of: ${VALID_TYPES.join(', ')}` });
    }
    if (!checkDeviceAccess(request, id)) {
      return reply.code(403).send({ success: false, error: 'Insufficient permissions for this device' });
    }
    const dbFileType = FILE_TYPE_MAP[type];
    return serveFileFromDb(reply, id, parseInt(fileId, 10), dbFileType);
  });

  app.post('/api/files/upload', async (request: FastifyRequest, reply: FastifyReply) => {
    const query = request.query as Record<string, string | undefined>;
    const clientId = query.clientId;
    const cmdId = query.cmdId || '';
    const name = query.name || `upload_${Date.now()}`;
    const declaredSize = parseInt(query.size || '0', 10);
    const token = query.token || request.headers['x-device-token'] as string || '';
    if (!clientId) {
      return reply.code(400).send({ success: false, error: 'Missing clientId' });
    }
    const userSecrets = dbHelpers.getAllDeviceSecrets();
    let authed = false;
    let matchedUserId: string | null = null;
    for (const { userId, deviceSecret } of userSecrets) {
      const a = Buffer.from(String(token || ''));
      const b = Buffer.from(deviceSecret);
      if (a.length === b.length && a.length > 0 && crypto.timingSafeEqual(a, b)) {
        authed = true;
        matchedUserId = userId;
        break;
      }
    }
    if (!authed) {
      return reply.code(401).send({ success: false, error: 'Invalid device token' });
    }
    if (matchedUserId) {
      const deviceOwner = dbHelpers.getDeviceOwnerId(clientId);
      if (deviceOwner && deviceOwner !== matchedUserId) {
        return reply.code(403).send({ success: false, error: 'Device belongs to another user' });
      }
      if (!deviceOwner) {
        dbHelpers.assignDevice(clientId, matchedUserId);
      }
    }
    const d = getDb();
    const client = d.select({ id: clients.id }).from(clients).where(eq(clients.id, clientId)).get();
    if (!client) {
      return reply.code(404).send({ success: false, error: 'Unknown clientId' });
    }
    let fileBuffer: Buffer | null = null;
    try {
      const parts = request.parts();
      for await (const part of parts) {
        if (part.type === 'file' && part.fieldname === 'file') {
          fileBuffer = await part.toBuffer();
          break;
        }
      }
    } catch (err: any) {
      return reply.code(400).send({ success: false, error: 'Failed to parse multipart: ' + (err?.message || String(err)) });
    }
    if (!fileBuffer || fileBuffer.length === 0) {
      return reply.code(400).send({ success: false, error: 'No file data received' });
    }
    const MAX_UPLOAD_SIZE = 50 * 1024 * 1024;
    if (fileBuffer.length > MAX_UPLOAD_SIZE) {
      return reply.code(413).send({ success: false, error: `File too large (${(fileBuffer.length / 1024 / 1024).toFixed(1)}MB). Maximum is 50MB.` });
    }
    const result = d.insert(clientFiles).values({
      clientId,
      fileType: 'upload',
      originalName: name,
      mimeType: guessMime(name),
      data: fileBuffer,
      fileSize: fileBuffer.length,
    }).run();
    dbHelpers.addLog('DATA', 'UPLOAD', `Upload from ${clientId}: ${name} (${fileBuffer.length} bytes, declared ${declaredSize})`);
    log.info(`Upload: ${clientId} uploaded ${name} (${fileBuffer.length} bytes)`);
    if (cmdId) {
      try {
        dbHelpers.markCommandResponded(cmdId, `Uploaded: ${name}`);
} catch {
}
    }
    return { success: true, id: Number(result.lastInsertRowid), size: fileBuffer.length };
  });

  app.post('/api/files/push', {
    preHandler: [app.auth, requirePermission('device:files')],
  }, async (request: FastifyRequest, reply: FastifyReply) => {
    const query = request.query as Record<string, string | undefined>;
    const clientId = query.clientId;
    const dstPath = query.dst;
    const user = getRequestUser(request);
    if (!clientId) return reply.code(400).send({ success: false, error: 'Missing clientId' });
    if (!dstPath) return reply.code(400).send({ success: false, error: 'Missing dst (destination path on device)' });
    if (!canAccessDevice(user, clientId)) {
      return reply.code(403).send({ success: false, error: 'You do not have access to this device' });
    }
    const d = getDb();
    const client = d.select({ id: clients.id }).from(clients).where(eq(clients.id, clientId)).get();
    if (!client) return reply.code(404).send({ success: false, error: 'Unknown clientId' });
    if (!socketService.isClientConnected(clientId)) {
      return reply.code(503).send({ success: false, error: 'Device is offline' });
    }
    let fileBuffer: Buffer | null = null;
    let fileName = 'file';
    try {
      const parts = request.parts();
      for await (const part of parts) {
        if (part.type === 'file' && part.fieldname === 'file') {
          fileBuffer = await part.toBuffer();
          fileName = part.filename || 'file';
          break;
        }
      }
    } catch (err: any) {
      return reply.code(400).send({ success: false, error: 'Failed to parse multipart: ' + (err?.message || String(err)) });
    }
    if (!fileBuffer || fileBuffer.length === 0) {
      return reply.code(400).send({ success: false, error: 'No file data received' });
    }
    const MAX_PUSH_SIZE = 10 * 1024 * 1024;
    if (fileBuffer.length > MAX_PUSH_SIZE) {
      return reply.code(413).send({ success: false, error: `File too large for device push (${(fileBuffer.length / 1024 / 1024).toFixed(1)}MB). Maximum is 10MB.` });
    }
    const base64Data = fileBuffer.toString('base64');
    const result = socketService.send(clientId, CMD.FILES, {
      action: 'push',
      path: dstPath,
      name: fileName,
      buffer: base64Data,
      size: fileBuffer.length,
    });
    dbHelpers.addLog('DATA', 'PUSH', `Pushed ${fileName} (${fileBuffer.length} bytes) to ${clientId}:${dstPath}`);
    log.info(`Push: ${clientId} <- ${fileName} (${fileBuffer.length} bytes) -> ${dstPath}`);
    return { success: true, sent: result.sent, commandId: result.commandId, size: fileBuffer.length };
  });
}

function guessMime(name: string): string {
  const lower = (name || '').toLowerCase();
  if (lower.endsWith('.jpg') || lower.endsWith('.jpeg')) return 'image/jpeg';
  if (lower.endsWith('.png')) return 'image/png';
  if (lower.endsWith('.gif')) return 'image/gif';
  if (lower.endsWith('.webp')) return 'image/webp';
  if (lower.endsWith('.mp4')) return 'video/mp4';
  if (lower.endsWith('.mp3')) return 'audio/mpeg';
  if (lower.endsWith('.pdf')) return 'application/pdf';
  if (lower.endsWith('.txt')) return 'text/plain';
  if (lower.endsWith('.zip')) return 'application/zip';
  return 'application/octet-stream';
}

function checkDeviceAccess(request: FastifyRequest, clientId: string): boolean {
  const user = request.user as SessionUser | undefined;
  if (!user) return false;
  if (!canAccessDevice(user, clientId)) return false;
  if (user.role === 'admin') return true;
  return hasPermission(user, 'files:download') && hasPermission(user, 'device:view');
}

function serveFileFromDb(reply: FastifyReply, clientId: string, fileId: number, fileType: string) {
  const d = getDb();
  const file = d.select({
    id: clientFiles.id,
    originalName: clientFiles.originalName,
    mimeType: clientFiles.mimeType,
    fileSize: clientFiles.fileSize,
    data: clientFiles.data,
  })
    .from(clientFiles)
    .where(and(
      eq(clientFiles.clientId, clientId),
      eq(clientFiles.id, fileId),
      eq(clientFiles.fileType, fileType),
    ))
    .get();
  if (!file || !file.data) {
    return reply.code(404).send({ success: false, error: 'File not found' });
  }
  const data = file.data as Buffer;
  const contentType = file.mimeType || 'application/octet-stream';
  const safeName = (file.originalName || 'file').replace(/[\x00-\x1f\x7f"\\]/g, '_');
  reply.header('Content-Type', contentType);
  reply.header('Content-Disposition', `attachment; filename="${safeName}"`);
  reply.header('Content-Length', file.fileSize || data.length);
  return reply.send(Buffer.from(data));
}
