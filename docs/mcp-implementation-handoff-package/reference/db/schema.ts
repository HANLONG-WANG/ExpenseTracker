import {sqliteTable,text,integer,uniqueIndex,index} from 'drizzle-orm/sqlite-core';
export const tasks=sqliteTable('tasks',{id:text().primaryKey(),owner:text().notNull(),type:text().notNull(),title:text().notNull(),instructions:text().notNull(),status:text().notNull(),context:text().notNull(),inputRevision:integer().notNull(),idempotency:text().notNull(),requestHash:text().notNull(),createdAt:text().notNull(),updatedAt:text().notNull()},t=>[uniqueIndex('task_owner_key').on(t.owner,t.idempotency)]);
export const files=sqliteTable('files',{id:text().primaryKey(),taskId:text().notNull(),owner:text().notNull(),role:text().notNull(),name:text().notNull(),mime:text().notNull(),size:integer().notNull(),sha256:text().notNull(),objectKey:text().notNull(),createdAt:text().notNull()},t=>[uniqueIndex('file_dedupe').on(t.taskId,t.role,t.name,t.sha256)]);
export const results=sqliteTable('results',{id:text().primaryKey(),taskId:text().notNull(),owner:text().notNull(),version:integer().notNull(),inputRevision:integer().notNull(),fileId:text().notNull(),summary:text().notNull(),validation:text().notNull(),state:text().notNull(),createdAt:text().notNull()},t=>[uniqueIndex('result_version').on(t.taskId,t.version)]);
export const events=sqliteTable('events',{id:text().primaryKey(),taskId:text().notNull(),owner:text().notNull(),message:text().notNull(),createdAt:text().notNull()});
export const devices=sqliteTable('devices',{id:text().primaryKey(),owner:text().notNull(),name:text().notNull(),tokenHash:text().notNull(),active:integer().notNull(),createdAt:text().notNull(),lastUsed:text()});

export const mcpSubscriptions=sqliteTable('mcp_subscriptions',{
 id:text().primaryKey(),owner:text().notNull(),url:text().notNull(),taskType:text().notNull(),secret:text().notNull(),
 previousSecret:text(),previousUntil:integer().notNull(),expiresAt:integer().notNull(),verifiedAt:integer().notNull(),active:integer().notNull(),
},t=>[index('mcp_subscriptions_owner').on(t.owner)]);
export const mcpDeliveries=sqliteTable('mcp_deliveries',{
 id:text().primaryKey(),subscriptionId:text().notNull(),taskId:text().notNull(),owner:text().notNull(),inputRevision:integer().notNull(),eventId:text().notNull(),
 payload:text().notNull(),state:text().notNull(),attempts:integer().notNull(),nextAttempt:integer().notNull(),lastError:text(),
},t=>[index('mcp_deliveries_pending').on(t.state,t.nextAttempt)]);
