#!/usr/bin/env bash

# This script is used to tag and push a new version of the project
# It is intended to be run from the root of the project
# It uses the git tag command to create a new tag and push it to the remote repository

# Ensure the script is run from the root of the project
if [ ! -f "pom.xml" ]; then
  echo "This script must be run from the root of the project where the pom.xml file is located."
  exit 1
fi

# Ensure the working directory is clean
if [ -n "$(git status --porcelain)" ]; then
  echo "Working directory is not clean. Please commit or stash your changes."
  exit 1
fi

# Ensure current POM version is a valid semver
if ! mvn semver:verify-current; then
  echo "Current POM version is not a valid semver version."
  exit 1
fi

# Get the current version from the pom.xml file
CURRENT_VERSION=$(mvn org.apache.maven.plugins:maven-help-plugin:3.2.0:evaluate -Dexpression=project.version -q -DforceStdout | tr -d "[:space:]")

# Create a new tag with the current version
git tag -a "$CURRENT_VERSION" -m "Release version tag $CURRENT_VERSION"

# Push the new tag to the remote repository
git push origin "$CURRENT_VERSION"

echo "Tagged and pushed version $CURRENT_VERSION."