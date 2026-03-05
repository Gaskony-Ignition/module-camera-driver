# Contributing to Camera Driver

## Development Setup
1. Clone the repository
2. Copy `gradle.properties.template` to `gradle.properties` and fill in values
3. Run `./gradlew build` to verify setup

## Code Standards
- Java 17 required
- Follow existing code patterns (see CLAUDE.md)
- SLF4J logging with `{}` placeholders, no string concatenation
- Logger: `private static final Logger logger = LoggerFactory.getLogger(ClassName.class);`

## Pull Request Process
1. Create a feature branch from `main`
2. Ensure `./gradlew build` passes (includes Checkstyle, SpotBugs, tests)
3. Update CHANGELOG.md with your changes
4. Submit PR with conventional commit title (feat/fix/docs/refactor/test/build/ci/chore)

## Commit Messages
Use [Conventional Commits](https://www.conventionalcommits.org/):
- `feat:` new features
- `fix:` bug fixes
- `docs:` documentation changes
- `refactor:` code refactoring
- `test:` test additions/changes
- `build:` build system changes
- `ci:` CI/CD changes

## Reporting Issues
Open an issue on GitHub with a clear description, steps to reproduce, and module/Ignition versions.
