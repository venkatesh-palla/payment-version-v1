.PHONY: db-up db-down run test verify

db-up:
	docker compose up -d postgres
	@echo "Waiting for PostgreSQL to be healthy..."
	@until docker compose exec -T postgres pg_isready -U payment_user -d payment_db >/dev/null 2>&1; do sleep 1; done
	@echo "PostgreSQL is ready on port 5432!"

db-down:
	docker compose down

run:
	./mvnw spring-boot:run -Dspring-boot.run.profiles=local

test:
	./mvnw test

verify:
	./mvnw clean verify
