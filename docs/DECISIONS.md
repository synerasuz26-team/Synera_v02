# Architecture decisions

## 2026-11-12: Keep database access behind the API

- The React frontend must not connect directly to PostgreSQL or contain database credentials.
- The Java 25/Spring Boot API owns authentication and persists accounts in Railway PostgreSQL; the frontend does not connect to the database.
- Account passwords use Spring Security PBKDF2; sessions use JDBC-backed storage and HttpOnly cookies.
- Schema changes are applied as new Flyway migrations. Database credentials and allowed frontend origins are environment variables, not committed configuration.
- API and database services use Railway private networking in the same region. The API container and connection contract live in this repository.
