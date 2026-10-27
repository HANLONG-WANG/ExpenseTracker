CREATE TABLE `mcp_deliveries` (
	`id` text PRIMARY KEY NOT NULL,
	`subscriptionId` text NOT NULL,
	`taskId` text NOT NULL,
	`owner` text NOT NULL,
	`inputRevision` integer NOT NULL,
	`eventId` text NOT NULL,
	`payload` text NOT NULL,
	`state` text NOT NULL,
	`attempts` integer NOT NULL,
	`nextAttempt` integer NOT NULL,
	`lastError` text
);
--> statement-breakpoint
CREATE INDEX `mcp_deliveries_pending` ON `mcp_deliveries` (`state`,`nextAttempt`);--> statement-breakpoint
CREATE TABLE `mcp_subscriptions` (
	`id` text PRIMARY KEY NOT NULL,
	`owner` text NOT NULL,
	`url` text NOT NULL,
	`taskType` text NOT NULL,
	`secret` text NOT NULL,
	`previousSecret` text,
	`previousUntil` integer NOT NULL,
	`expiresAt` integer NOT NULL,
	`verifiedAt` integer NOT NULL,
	`active` integer NOT NULL
);
--> statement-breakpoint
CREATE INDEX `mcp_subscriptions_owner` ON `mcp_subscriptions` (`owner`);