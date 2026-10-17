# Private synthetic reviewer journey

This guide covers the disposable local rehearsal. Obtain the five persona sign-ins and the synthetic branch/order references from the operator at runtime. Passwords and bearer tokens do not belong in this document. Public deployment and public order creation remain disabled.

## Operator

Sign in with the seeded operator account and open Operations. Prepare the synthetic dataset, then inspect its generation and order references. Reset requires a reason and the displayed confirmation. If a response is lost, leave the dialog open and retry the same request until its outcome is confirmed. Reset archives the previous generation; it preserves unrelated records.

## Driver

Open Driver workspace, go online, then make the driver available. Use **Send synthetic pickup** to publish the first fixture position and **Refresh heartbeat** when needed. The five movement points come from the seeded order's pickup and destination. Each submission uses the normal signed driver-location endpoint and advances only after confirmation. These are straight-line fixture points, with no road route, speed or ETA. The seeded driver never requests device GPS. Advancing fixture positions does not advance delivery status.

## Customer, merchant and dispatcher

The customer opens the seeded order and Tracking. In the private local profile, the customer may also create an order. If creation has an unknown outcome, the draft and dialog stay locked; Retry preserves the original body and idempotency key.

The merchant opens the supplied branch and confirms, prepares and marks the order ready. The dispatcher opens the supplied order to inspect matching, offers and recovery evidence. With the driver available and located, the driver can accept the offer, confirm pickup, start transit and complete delivery. The customer's authenticated SSE stream updates the lifecycle; reconnect requests a fresh authorized snapshot.

For a recovery exercise, leave the driver offline until dispatch reports no candidates. Make the driver available, publish the synthetic pickup, and retry matching with a reason. The driver may reject the offer; the dispatcher can inspect the rejection and cancel before pickup. These actions remain subject to server authorization and lifecycle guards.

## Delivery zones

The merchant opens Delivery zones for their branch. Create or replace a boundary by adding vertices with the labeled longitude/latitude fields, or use Draw new boundary and click the map. Three distinct vertices are required; closing the ring is automatic. Undo or Remove below three vertices cannot save the earlier polygon. Advanced coordinates preserve supported complex geometries.

An edit carries the version that was opened. On a conflict, close the editor, refresh and reopen the latest version. A lost create response keeps the original request and key for Retry. Check a destination to see the actual PostGIS coverage result and policy version. After a policy change, check again. Tile failure retains authorized overlays and offers Retry map tiles; successful fixture recovery alone does not prove external OpenStreetMap delivery.

## Verification

The repeatable signed journey is `python scripts/Run-Day2VerticalJourney.py`, after packaging the application and installing the frontend lockfile. It creates a unique loopback-only service project and removes only that project's containers, volumes and generated credentials. The separate production-topology rehearsal is `python scripts/Run-Day1BrowserSmoke.py` with the six freshly built image inputs. Owner delivery requires a private `FLEETFLOW_DISCORD_WEBHOOK_FILE`; trusted branch CI requires that delivery and fails if it cannot be proved.

See [the current audit](../phases/production/DAYS-1-3-FULL-AUDIT-2026-10-17.md) for exact results and remaining release gates.
