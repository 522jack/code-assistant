#!/bin/bash

# Install code-assistant globally

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_DIR="$( cd "$SCRIPT_DIR/.." && pwd )"

echo "📦 Installing code-assistant..."

# Build the project
echo "🔨 Building project..."
cd "$PROJECT_DIR"
./gradlew shadowJar

# Check if build succeeded
JAR_FILE="$PROJECT_DIR/build/libs/code-assistant-0.1.0.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "❌ Build failed: JAR file not found"
    exit 1
fi

# Create installation directory
INSTALL_DIR="$HOME/.local/bin"
mkdir -p "$INSTALL_DIR"

# Copy JAR
echo "📋 Copying JAR to $INSTALL_DIR..."
cp "$JAR_FILE" "$INSTALL_DIR/code-assistant.jar"

# Create wrapper script
echo "✍️  Creating wrapper script..."
cat > "$INSTALL_DIR/code-assistant" << 'EOF'
#!/bin/bash
java -jar "$HOME/.local/bin/code-assistant.jar" "$@"
EOF

chmod +x "$INSTALL_DIR/code-assistant"

# Check if ~/.local/bin is in PATH
if [[ ":$PATH:" != *":$HOME/.local/bin:"* ]]; then
    echo ""
    echo "⚠️  Warning: $HOME/.local/bin is not in your PATH"
    echo ""
    echo "Add it by adding this line to your ~/.bashrc or ~/.zshrc:"
    echo "  export PATH=\"\$HOME/.local/bin:\$PATH\""
    echo ""
    echo "Then reload your shell:"
    echo "  source ~/.bashrc  # or source ~/.zshrc"
fi

echo ""
echo "✅ Installation complete!"
echo ""
echo "Usage:"
echo "  cd /path/to/your/project"
echo "  code-assistant"
echo ""
echo "Make sure to set CLAUDE_API_KEY environment variable:"
echo "  export CLAUDE_API_KEY='your-api-key-here'"