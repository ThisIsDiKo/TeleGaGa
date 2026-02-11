# GitHub MCP Setup Guide

## Overview

This guide explains how to set up GitHub integration for TeleGaGa bot using the official GitHub MCP server. This enables the `/showPR` command to analyze Pull Requests using AI.

## Prerequisites

- **Node.js** (v16 or higher) - required for GitHub MCP server
- **npm/npx** - comes with Node.js
- **GitHub account** with repository access
- **Java 17** - for running TeleGaGa bot

Check if Node.js is installed:
```bash
node --version  # Should show v16.x.x or higher
npx --version   # Should show 9.x.x or higher
```

## Step 1: Create GitHub Personal Access Token

### 1.1 Navigate to GitHub Settings

1. Go to **GitHub.com** and log in
2. Click your **profile picture** (top right)
3. Select **Settings**
4. Scroll down to **Developer settings** (left sidebar, bottom)
5. Click **Personal access tokens**
6. Select **Tokens (classic)**

### 1.2 Generate New Token

1. Click **Generate new token** → **Generate new token (classic)**
2. Fill in the form:
   - **Note**: `TeleGaGa Bot - PR Analysis` (or any descriptive name)
   - **Expiration**: Choose duration (recommended: 90 days or No expiration for testing)

### 1.3 Select Token Scopes

**For public repositories:**
- ✅ `public_repo` - Access public repositories

**For private repositories:**
- ✅ `repo` (Full control of private repositories)
  - This automatically includes:
    - `repo:status`
    - `repo_deployment`
    - `public_repo`
    - `repo:invite`
    - `security_events`

**Additional recommended scopes:**
- ✅ `read:user` - Read user profile data
- ✅ `user:email` - Access user email addresses

### 1.4 Generate and Copy Token

1. Click **Generate token** (bottom of page)
2. **IMPORTANT**: Copy the token immediately!
   - It looks like: `ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx`
   - You won't be able to see it again after leaving the page
3. Save it to a secure location temporarily

## Step 2: Configure TeleGaGa Bot

### 2.1 Edit config.properties

Open the `config.properties` file in the TeleGaGa project root:

```properties
# Telegram Bot Configuration
telegram.token=YOUR_TELEGRAM_TOKEN

# GigaChat Configuration
gigachat.model=GigaChat
gigachat.baseUrl=https://gigachat.devices.sberbank.ru
gigachat.authKey=YOUR_GIGACHAT_KEY

# GitHub Configuration (add these lines)
github.token=ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
github.owner=YourGitHubUsername
github.repo=YourRepositoryName
```

### 2.2 Fill in GitHub Configuration

**github.token**
- Paste the Personal Access Token you created in Step 1
- Example: `github.token=ghp_abc123def456ghi789jkl012mno345pqr678`

**github.owner**
- Your GitHub username or organization name
- Find it in your repository URL: `https://github.com/OWNER/repo`
- Example: `github.owner=ThisIsDiKo`

**github.repo**
- Your repository name
- Find it in your repository URL: `https://github.com/owner/REPO`
- Example: `github.repo=TeleGaGa`

### 2.3 Complete Example

```properties
telegram.token=123456789:ABCdefGHIjklMNOpqrsTUVwxyz
gigachat.model=GigaChat
gigachat.baseUrl=https://gigachat.devices.sberbank.ru
gigachat.authKey=BASE64_ENCODED_KEY

github.token=ghp_1A2b3C4d5E6f7G8h9I0jK1lM2nO3pQ4rS5t
github.owner=ThisIsDiKo
github.repo=TeleGaGa
```

## Step 3: Run the Bot

### 3.1 Build and Start

```bash
# Set Java 17 path
export JAVA_HOME=/path/to/openjdk-17.0.1

# Build the project
./gradlew build

# Run the bot
./gradlew run
```

### 3.2 Verify GitHub MCP Initialization

Look for this in the console output:

```
7.5. Инициализация GitHub MCP Service...
   Using npx at: /usr/local/bin/npx
   🚀 Starting github...
   🔍 Process started, isAlive=true, pid=12345
   📡 github initialized: ...
   ✅ GitHub MCP Service инициализирован
```

If you see errors:
- **"npx not found"**: Install Node.js
- **"Not Found: Resource not found"**: Check github.token, github.owner, github.repo
- **"Forbidden"**: Check token scopes (needs `repo` or `public_repo`)

## Step 4: Test GitHub Integration

### 4.1 Send Test Commands

In Telegram, send to your bot:

```
/start
```

You should see:
```
🔧 GitHub Commands:
/showPR [number] - Analyze Pull Request with RAG + GigaChat
/listGitHubTools - Show available GitHub MCP tools
```

### 4.2 List Available Tools

```
/listGitHubTools
```

Expected output:
```
Available GitHub MCP Tools (26):

- create_or_update_file: Create or update a single file...
- get_pull_request: Get details of a specific pull request
- get_pull_request_files: Get the list of files changed in a pull request
- list_pull_requests: List and filter repository pull requests
...
```

### 4.3 Analyze a Pull Request

```
/showPR          # Analyze latest open PR
/showPR 1        # Analyze PR #1
```

Expected flow:
1. "Fetching Pull Request #1..."
2. "Found Pull Request #1: Title"
3. "Analyzing Pull Request with RAG and local documentation..."
4. "Getting diff from Pull Request and analyzing it..."
5. "Analyzing chunk 1 of N..."
6. Final report with issues and suggestions

## Troubleshooting

### Error: "GitHub MCP service is not available"

**Cause**: GitHub MCP server failed to start

**Solutions**:
1. Check Node.js is installed: `node --version`
2. Check npx is in PATH: `which npx`
3. Try manual installation: `npm install -g @modelcontextprotocol/server-github`
4. Check bot console for detailed error messages

### Error: "GitHub owner and repo not configured"

**Cause**: Missing or blank `github.owner` or `github.repo` in config.properties

**Solution**:
1. Open `config.properties`
2. Add/update these lines:
   ```properties
   github.owner=YourUsername
   github.repo=YourRepo
   ```
3. Restart the bot

### Error: "Not Found: Resource not found"

**Cause**: Invalid owner, repo, or PR number

**Solutions**:
1. Verify repository URL: `https://github.com/owner/repo`
2. Check `github.owner` matches exactly (case-sensitive)
3. Check `github.repo` matches exactly (case-sensitive)
4. Verify PR exists: open `https://github.com/owner/repo/pulls`

### Error: "Forbidden" or "401 Unauthorized"

**Cause**: Invalid token or insufficient scopes

**Solutions**:
1. Regenerate token with correct scopes:
   - Public repos: `public_repo`
   - Private repos: `repo`
2. Check token starts with `ghp_`
3. Verify token is not expired
4. Try token manually: `curl -H "Authorization: Bearer YOUR_TOKEN" https://api.github.com/user`

### Error: "rate limit exceeded"

**Cause**: Too many GitHub API requests (5000/hour limit)

**Solution**:
- Wait 1 hour for rate limit reset
- Authenticated requests have higher limits (5000/hour) vs unauthenticated (60/hour)

### Analysis Takes Too Long

**Cause**: Large PR with many files/changes

**Expected times**:
- Small PR (1-5 files): 15-30 seconds
- Medium PR (5-20 files): 40-90 seconds
- Large PR (20-50 files): 2-5 minutes

**If stuck**:
1. Check bot console for errors
2. Verify GigaChat API is responding
3. Try smaller PR first to test
4. Check network connectivity

## Security Best Practices

### Token Security

1. **Never commit tokens to git**
   - `config.properties` is in `.gitignore`
   - Never push to public repositories

2. **Use token rotation**
   - Set expiration (e.g., 90 days)
   - Regenerate periodically
   - Delete old tokens after regeneration

3. **Minimal scopes**
   - Use `public_repo` if only analyzing public repos
   - Only use `repo` if you need private repo access

4. **Revoke if compromised**
   - Go to GitHub Settings → Developer settings → Personal access tokens
   - Click **Delete** next to compromised token
   - Generate new token immediately

### Environment Isolation

The GitHub token is passed to the MCP server via environment variables:

```kotlin
envVars = mapOf(
    "GITHUB_PERSONAL_ACCESS_TOKEN" to config.githubToken
)
```

The token is:
- Never logged to console
- Never sent in Telegram messages
- Only accessible to the MCP server process

## GitHub API Rate Limits

### Limits

- **Authenticated requests**: 5,000 per hour
- **Unauthenticated requests**: 60 per hour
- **Search API**: 30 per minute (authenticated)

### Monitoring

Check your rate limit:
```bash
curl -H "Authorization: Bearer YOUR_TOKEN" https://api.github.com/rate_limit
```

Response:
```json
{
  "rate": {
    "limit": 5000,
    "remaining": 4999,
    "reset": 1612345678
  }
}
```

### Best Practices

1. Always use authentication (token) for higher limits
2. Cache PR analysis results if re-analyzing same PR
3. Avoid analyzing same PR multiple times in succession
4. Use `/showPR` judiciously on large repos

## Advanced Configuration

### Custom MCP Server Port

If port conflicts occur, you can modify the GitHub MCP server startup in `Main.kt`:

```kotlin
// Current: uses default ports
args = listOf("-y", "@modelcontextprotocol/server-github")

// Future: if port configuration is added to MCP server
args = listOf("-y", "@modelcontextprotocol/server-github", "--port", "3005")
```

### Multiple Repositories

To analyze PRs from multiple repositories:

1. Add multiple owner/repo configurations
2. Modify `/showPR` to accept optional owner/repo parameters
3. Example: `/showPR owner/repo 123`

## Additional Resources

- [GitHub Personal Access Tokens Documentation](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/creating-a-personal-access-token)
- [GitHub API Rate Limits](https://docs.github.com/en/rest/overview/resources-in-the-rest-api#rate-limiting)
- [Model Context Protocol](https://modelcontextprotocol.io/)
- [GitHub MCP Server](https://github.com/modelcontextprotocol/servers)
- [Token Scopes Explanation](https://docs.github.com/en/developers/apps/building-oauth-apps/scopes-for-oauth-apps)

## Support

If you encounter issues:

1. Check bot console output for detailed errors
2. Review this troubleshooting section
3. Verify all prerequisites are met
4. Test GitHub API access manually with curl
5. Check GitHub status: https://www.githubstatus.com/

For TeleGaGa-specific issues, check `CLAUDE.md` for project documentation.
