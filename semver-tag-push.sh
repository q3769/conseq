#!/usr/bin/env bash

# Tag and push a new version of the project from the root directory

# Ensure the script is run from the root of the project
[ -f "pom.xml" ] || { echo "Run from the project root with pom.xml"; exit 1; }

# Ensure the working directory is clean
[ -z "$(git status --porcelain)" ] || { echo "Working directory is not clean, please commit or stash your changes"; exit 1; }

# Ensure current POM version is a valid semver
mvn semver:verify-current >/dev/null || { echo "Current POM version is not a valid semver version"; exit 1; }

# Get the current version from the pom.xml file
CURRENT_VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout | tr -d "[:space:]")

# Create and push a new tag with the current version
git tag -a "$CURRENT_VERSION" -m "Release version tag $CURRENT_VERSION"
git push origin "$CURRENT_VERSION"

echo "Tagged and pushed version $CURRENT_VERSION."