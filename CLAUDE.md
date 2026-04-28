# Claude Code IntelliJ Plugin

## Build & Test

- Build: `./gradlew buildPlugin`
- Test: `./gradlew test`
- Format: `mvn fmt:format` (if applicable)

## Workflow Rules

- **After every code change or commit**, run `./gradlew test` and ensure all tests pass before proceeding.
- **When creating a GitHub release**, always build the plugin with `./gradlew buildPlugin` and attach the resulting zip from `build/distributions/` to the release using `gh release upload`.
