# Postman

Import `collection.json` and `environment.json`, then select **Roster Sync local demo**. The 29 requests cover every controller endpoint, including both services' health and metrics. Request name prefixes select fresh HR, admin, operator or employee tokens automatically; change `tenant` to `globex` to test isolation. Demo password: `demo123`.

Start by reading the employee, then editing transport fields. The GET stores its ETag directly in `revision`. Create a trip before a booking; successful responses save `trip_id` and `booking_id`. The employee demo account is bound to `EMP-001`.

For a reconciliation, select the CSV file in the upload request and edit its manifest to match the file and cutoff. Upload saves `run_id`; reading the run saves `assessment_hash`. Approve only after reviewing the assessment; reject is the alternative. Retry requires a failed run with its retained file. Outbox replay requires an actual DEAD row ID in `outbox_id`.

`SERVICE | Consume employee event` requires a trusted `service_token` with the booking audience and SERVICE role. The development user endpoint does not issue that role. Normal demo validation exercises this endpoint through the relay; private signing keys are never exported into Postman.

These are selectable examples with stateful prerequisites. `scripts/demo/run-all.sh` is the automated end-to-end flow. `node postman/check.mjs` checks collection coverage, JavaScript syntax, role-token selection, and the trusted-service boundary without sending requests. No npm packages are needed.
