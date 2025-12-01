#!/bin/bash

# Setup environment variables template

echo "Setting up environment variables for code-assistant..."
echo ""
echo "Please provide the following information:"
echo ""

# CLAUDE_API_KEY
read -p "Claude API Key: " CLAUDE_API_KEY
if [ -z "$CLAUDE_API_KEY" ]; then
    echo "❌ Claude API Key is required"
    exit 1
fi

# OLLAMA_URL (optional)
read -p "Ollama URL (default: http://localhost:11434): " OLLAMA_URL
OLLAMA_URL=${OLLAMA_URL:-http://localhost:11434}

# Create env file
ENV_FILE=".env.code-assistant"

cat > "$ENV_FILE" << EOF
# Code Assistant Environment Variables
export CLAUDE_API_KEY='$CLAUDE_API_KEY'
export OLLAMA_URL='$OLLAMA_URL'
EOF

echo ""
echo "✅ Environment file created: $ENV_FILE"
echo ""
echo "To use these variables, run:"
echo "  source $ENV_FILE"
echo ""
echo "Or add these lines to your ~/.bashrc or ~/.zshrc:"
cat "$ENV_FILE"