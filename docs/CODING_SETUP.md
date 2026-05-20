# Code Editing Setup (No Build Required)

You can now **edit code freely** in VS Code without any local builds.

## How It Works

1. **Edit code** in VS Code (no Node.js or Java needed)
2. **Commit changes** to git
3. **On your build machine**, pull the changes and build

## Building on Another Computer

### Backend Build
```bash
cd backend
mvn clean install
# Run with Oracle profile
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=onsemi-oracle"
# Or standard run
java -jar target/resender-1.0.0-SNAPSHOT.jar --spring.profiles.active=onsemi-oracle
```

### Frontend Build
```bash
cd frontend
npm install
npm run build
# Or dev server
npm start
```

## VS Code Extensions (Recommended)

Install these for better code editing (no build required):
- **Prettier** - Code formatting
- **ESLint** - JavaScript/TypeScript linting
- **Java Extension Pack** - Java syntax highlighting
- **Angular Language Service** - Angular template support
- **GitLens** - Git history integration

## Git Workflow

1. **Edit code locally** (no builds)
2. **Stage & commit changes**
   ```bash
   git add .
   git commit -m "Your changes"
   git push
   ```
3. **On build machine**: Pull and run builds

## Excluded Directories

These are hidden in VS Code (won't clutter your editor):
- `node_modules/` (frontend dependencies)
- `target/` (backend build output)
- `.angular/` (Angular cache)
- `dist/` (built frontend)

---

**This setup is permanent - you can now work without any build tools installed.**
