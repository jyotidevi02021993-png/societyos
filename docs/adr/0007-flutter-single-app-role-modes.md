# ADR-0007 — One Flutter app with role modes

- **Status:** Accepted (2026-09-28)

## Context
Residents, guards, technicians, housekeeping, managers and vendors all need a mobile app.
Separate apps would multiply store listings, releases and code.

## Decision
One Flutter app. After login the user picks a mode allowed by their roles (Resident,
Gate, Staff, Manager, Vendor). Each mode is a separate feature module with its own route
tree. Gate mode can run as a kiosk on a society-owned device.

## Consequences
Shared auth, offline sync, design system and API client. The app is larger; mitigated by
deferred loading of mode modules.
