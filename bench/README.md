# Run the heavy benchmarks on another computer

P11 is prepared for a separate machine at the user's request. Full benchmark validation is pending.
Requirements: Java 21, Docker Engine + Compose, bash, curl, jq, Python 3, and network access for the pinned images and Maven dependencies. Run from the repository root on Linux, macOS, or WSL.

```bash
java -version
docker compose version
./mvnw -q verify && scripts/check-tests.sh
scripts/bench/run-all.sh
```

If Java is selected through `JAVA_HOME`, also put `$JAVA_HOME/bin` on `PATH`: the runner calls both Maven and `java` directly. On Colima, export these before running:

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

The runner **resets the roster-sync Compose volumes** before the API tests and between rates. Use a dedicated test checkout and keep unrelated traffic off ports 8080/8081. It runs:

1. Six 200k-row reconciliation scenarios, each with one warm-up and three measured iterations, capped at `-Xmx256m`. Fixture setup is excluded. Every run checks exact mutation counts; typical and heavy assert digest + assess + apply < 60 seconds.
2. Fixed-seed CSV generation, API load at 100/250/500/1000 requests per second for 60 seconds each, and the 250/s contention run.
3. Machine capture, raw JSON and metrics under `bench/results/`, and generation of `docs/benchmark.md` from those files.

Return `bench/results/`, `docs/benchmark.md`, and the benchmark Failsafe report. Keep `bench/tmp/contention.jsonl` until reporting succeeds; it is a disposable intermediate and is not committed. The compact request samples and lock observations are retained under results.

For lightweight validation without sending API traffic:

```bash
docker run --rm -v "$PWD/k6:/scripts:ro" grafana/k6:1.8.2 run /scripts/ingest-test.js
```

## Local attempts

The first local attempt exposed PostgreSQL's prohibition on COPY directly into an RLS table. The fixture now COPYs into a temporary table and inserts under the app role's tenant policy. The second attempt timed out in the apply UPDATE after 300 seconds during an initial-load iteration. The repository now explicitly analyzes the temporary `recon_plan` before its apply joins; its performance effect still requires the remote run. Standard tests check correctness without running the heavy benchmark.

Incomplete local measurements and the timeout report are retained in `bench/local-attempts/`; they are **not final benchmark results**. No local run completed all six scenarios.
