# Environment Constraints

This workspace runs in a restricted environment. The following tools are NOT available and cannot be installed or invoked:

- **Maven** (`mvn`, `mvnw`) — Java build and test runner
- **Java** (`java`, `javac`) — JDK/JRE runtime
- **Node.js** (`node`, `npm`, `npx`) — JavaScript runtime and package manager
- **Python** (`python`, `python3`, `pip`) — Python runtime and package manager

## Impact on Tasks

- Backend Java tests (JUnit, jqwik) cannot be compiled or executed locally.
- Frontend Angular tests (Karma, Jasmine) cannot be run locally.
- Any task that requires running `mvn test`, `npm test`, `ng test`, or `python` scripts cannot be verified by the agent.

## What the Agent Should Do Instead

- For checkpoint tasks that require running tests, **skip execution** and note that tests must be run manually by the developer in their own environment.
- For code-writing tasks, produce the correct code and verify correctness through static analysis (reading the code, checking types, reviewing logic) rather than execution.
- Do not attempt to install or locate these tools — they are not available in this environment.
