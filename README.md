# QR-Based UPI Payment Service (payment-service)

Production-grade, local-first UPI QR payment service built with Java 21, Spring Boot 3.4.5, Spring JDBC, PostgreSQL, and transactional outbox. Operates with zero message broker dependencies.

Specification: [docs/AI_BUILD_SPEC.md](docs/AI_BUILD_SPEC.md)  
Progress tracking: [docs/PROGRESS.md](docs/PROGRESS.md)

---

## 1. Prerequisites
- **JDK 21**
- **Maven 3.9+** (or included `./mvnw`)
- **PostgreSQL 15+** (via Docker or local install)

---

## 2. Quickstart

### Start Local PostgreSQL
```bash
make db-up
# or
docker compose up -d postgres
```

### Build & Run Full Verification
```bash
make verify
# or
./mvnw clean verify
```

### Run Locally (Profile `local`)
```bash
make run
# or
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```
- App Server: `http://localhost:8080`
- Management / Actuator: `http://localhost:8081/actuator/health`

---

## 3. Profiles
| Profile | Description | Gateway | Auth |
|---|---|---|---|
| `local` (default) | Daily development | `FakeGateway` | Local HS256 JWT |
| `test` | Automated tests | `FakeGateway` / WireMock | Test JWTs |
| `sandbox` | Real provider test mode | Real provider | Local JWT or real issuer |
| `live-smoke` | Live tiny amounts (<= 10.00) | Real provider | Local JWT or real issuer |
| `prod` | Production | Real provider | Real issuer |

Safety guard (`ProfileGuard`) enforces that fake gateway, dev endpoints, and dev JWT secrets fail fast at startup if active under `prod`.

