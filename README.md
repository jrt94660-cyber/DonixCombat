# DonixCombat

A DonutSMP-style combat-tag plugin for Paper.

## Features

- Tags both players when one damages the other, including player-shot projectiles.
- Tracks player-caused End Crystal and Respawn Anchor explosions and tags nearby players.
- Shows a configurable countdown on the action bar (20 seconds by default).
- Prevents combat-tagged players from entering configured WorldGuard regions.
- Disables flight when combat starts and prevents flight from being toggled back on during combat.
- Blocks commands during combat except the configured allowlist (defaults: `/sell` and `/shop`).
- Supports `/donixcombat reload`.

## Requirements

- Paper 1.21.11 or newer compatible Paper build.
- Java 21.
- WorldGuard is optional, but required for safe-region protection.

## Configuration

Edit `plugins/DonixCombat/config.yml`. Safe-region entries must match WorldGuard region IDs.

## Build

Run `mvn clean package`. The output JAR is `target/DonixCombat-1.0.0.jar`.
GitHub Actions builds the JAR on pushes and pull requests and uploads it as a workflow artifact.
