# DTP Resender V2 - Frontend

This is the recreated frontend for the DTP Resender, featuring a premium UI/UX based on the `eta-portal` design system.

## How to Test Locally

1. **Copy the Folder**: Copy the entire `new_frontend` directory to your local machine.
2. **Install Dependencies**:
   ```bash
   npm install
   ```
3. **Run Development Server**:
   ```bash
   npm start
   ```
4. **Build for Production**:
   ```bash
   npm run build
   ```

## Key Technologies
- **Angular 20**: Latest version for better performance and smaller bundles.
- **Glassmorphism UI**: Using CSS backdrop-filters and gradients.
- **Standalone Components**: Modular and easy to maintain.
- **Angular Material 20**: For core accessibility and component robustness.

## Directory Overview
- `src/app/api`: Backend service and mock interfaces.
- `src/app/dashboard`: Operational hub with real-time metrics.
- `src/app/stepper`: Core resender wizard with advanced filtering.
- `src/app/auth`: Modern login and security integration.
- `src/styles.scss`: Global design system and premium theme tokens.
