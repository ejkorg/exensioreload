---
inclusion: always
---

# Environment Constraints

## Testing and Build Environment

This workspace has environment constraints that affect testing and build execution:

### Local Development Constraints

- **Java**: Not available on local development machine
- **Maven**: Not available on local development machine
- **Node.js/npm**: Not available on local development machine
- **Python**: Not available on local development machine
- **Perl**: Not available on local development machine
- **Git**: Not available on local development machine

### Testing Strategy

All tests must be executed on a **remote development node/computer** with the required tooling installed:

1. **Java 21+** - Required for backend compilation and test execution
2. **Maven 3.8+** - Required for building and running tests
3. **Node.js 18+** - Required for frontend testing if needed
4. **Git** - Required for version control operations

### Running Tests Remotely

When implementing features:

1. **Do NOT attempt to run tests locally** - The tools are not available
2. **Push code changes** to the remote repository
3. **Execute tests on remote node** with proper tooling installed
4. **Review test results** and make necessary adjustments

### Maven Commands for Remote Execution

```bash
# Run specific test class
mvn test -Dtest=PipelineConfigCacheTest

# Run all tests
mvn test

# Run tests with specific profile
mvn test -Ppostgresql

# Run with detailed output
mvn test -X

# Skip tests during build
mvn clean package -DskipTests
```

### Property-Based Testing (jqwik)

When running property-based tests:

```bash
# Run PBT tests (these may run 100+ iterations)
mvn test -Dtest=*PropertyTest

# Run specific PBT with custom iterations
mvn test -Dtest=SomePropertyTest -Djqwik.tries=1000
```

### Backend Build

```bash
# Build backend without tests
mvn clean package -DskipTests

# Build with tests
mvn clean package

# Build specific module
mvn clean package -f backend/pom.xml
```

### Frontend Build

```bash
# Build frontend
npm run build

# Run frontend tests
npm test

# Run frontend tests in CI mode
npm test -- --run
```

### Code Quality Tools

These tools should run on the remote node:

- **Compiler checks**: Java compilation via Maven
- **Unit tests**: JUnit 5
- **Property-based tests**: jqwik
- **Code style**: Built-in Maven plugins

## Development Workflow

1. Edit code locally in the workspace
2. Commit changes locally (if git available)
3. Push to remote repository
4. Run tests on remote node with full tooling
5. Review results and iterate

## CI/CD Integration

When setting up CI/CD pipelines, ensure the runner has:

- Java 21+ JDK
- Maven 3.8+
- Node.js 18+ (for frontend)
- PostgreSQL or Oracle JDBC drivers (for tests)
- Sufficient memory (2GB+ heap for Maven builds)

## Notes

- Local code editing and inspection is fully supported
- All compilation and execution must occur remotely
- Remote node must have access to artifact repositories (Maven Central, npm registry)
- Tests may require external services (Elasticsearch, Oracle database) - ensure remote node can reach them
