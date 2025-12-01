#!/bin/bash

# Run code-assistant from the build

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_DIR="$( cd "$SCRIPT_DIR/.." && pwd )"

# Check if JAR exists
JAR_FILE="$PROJECT_DIR/build/libs/code-assistant-0.1.0.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "❌ JAR file not found. Please build the project first:"
    echo "   ./gradlew shadowJar"
    exit 1
fi

# Check for CLAUDE_API_KEY
if [ -z "$CLAUDE_API_KEY" ]; then
    echo "❌ CLAUDE_API_KEY environment variable is not set"
    echo ""
    echo "Please set it using:"
    echo "  export CLAUDE_API_KEY='your-api-key-here'"
    exit 1
fi

# Run
java -jar "$JAR_FILE" "$@"