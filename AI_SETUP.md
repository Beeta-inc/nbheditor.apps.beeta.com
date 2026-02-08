# AI Integration Setup

## DeepSeek 3.2 Chimera Integration

NbhEditor now includes AI assistance powered by DeepSeek 3.2 Chimera (deepseek-reasoner model).

### Features
- 🧠 **AI Assistant** - Get intelligent code suggestions and answers
- 💡 **Context-Aware** - Include selected text as context for better responses
- ⚡ **Fast Reasoning** - Powered by DeepSeek's advanced reasoning model

### Setup Instructions

1. **Get API Key**
   - Visit: https://platform.deepseek.com
   - Sign up for a free account
   - Generate your API key

2. **Configure in NbhEditor**
   - Open NbhEditor
   - Go to: `AI → AI Settings`
   - Paste your API key
   - Click Save

3. **Use AI Assistant**
   - Go to: `AI → AI Assistant`
   - Type your question or request
   - Optionally select text in editor and check "Include selected text as context"
   - Click Send

### Dependencies Required

Add to your project:

```xml
<!-- For Maven -->
<dependency>
    <groupId>org.json</groupId>
    <artifactId>json</artifactId>
    <version>20231013</version>
</dependency>
```

Or download: https://github.com/stleary/JSON-java

### Compilation

```bash
javac -cp ".:json-20231013.jar" appdata/*.java
```

### Example Usage

1. **Code Explanation**: Select code → AI Assistant → "Explain this code"
2. **Bug Fix**: Select buggy code → "Find and fix the bug"
3. **Code Generation**: "Write a function to sort an array"
4. **Refactoring**: Select code → "Refactor this for better performance"

### API Costs

DeepSeek offers competitive pricing. Check current rates at:
https://platform.deepseek.com/pricing

### Privacy

- API key stored locally in Java preferences
- No data sent without explicit user action
- All communication over HTTPS
