# Shortcuts for reviewers; each target runs the command shown in the README.
.PHONY: up tour crash test faults demo down

up:	## build and start postgres, roster (8080) and booking (8081); waits until healthy
	docker compose up -d --build --wait

tour:	## two-minute tour against the running stack (does not reset data)
	scripts/demo/quick.sh

crash:	## the brief's crash test: roster dies after commit, recovers, booking cancels once
	scripts/faults/kill-roster-after-commit.sh

test:	## all unit, integration and concurrency tests (needs JDK 21 and Docker)
	./mvnw verify && scripts/check-tests.sh

faults:	## every failure scenario, three runs each (resets the stack between runs)
	scripts/faults/run-all.sh

demo:	## full scripted demo, including the 200k/120k safety brake (resets the stack)
	scripts/demo/run-all.sh

down:	## stop everything and delete the volumes
	docker compose down -v
