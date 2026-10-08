.PHONY: db-up db-down run test verify

db-up:
	@./scripts/db-up.sh

db-down:
	@./scripts/db-down.sh

run:
	@./scripts/run.sh

test:
	@./scripts/test.sh

verify:
	@./scripts/verify.sh

