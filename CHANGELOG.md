# Changelog

All notable changes to NbhEditor will be documented in this file.

## [6.0.0] - 2025-01-XX

### Added
- **Advanced Math Editor:** Complete GUI-driven LaTeX math formula builder with 14 categories
  - Basic operators, Fractions, Exponents, Roots, Calculus symbols
  - Trigonometry, Geometry, Algebra, Functions, Matrices
  - Greek letters, Mathematical symbols, Logic operators, Set theory
- **Step-by-Step Wizards:** Intuitive multi-step dialogs for complex expressions
  - Integrals (with optional limits and variables)
  - Summations and products
  - Limits and derivatives
  - Fractions, powers, roots, and matrices
- **Flexible Math Input:** Nested expression builder with text input + GUI buttons
  - Type custom values (x^9000, any variable name)
  - Use category buttons for symbols and structures
  - Combine typing and buttons for maximum flexibility
- **Formula Rendering:** Math formulas inserted as beautifully rendered images using JLatexMath
- **Video Chat Mini Player:** Picture-in-picture mode for video calls
  - Split view showing local POV and remote/active speaker
  - Draggable overlay window with minimize/maximize controls
  - Smart permission management with explanation dialogs
- **Media Preview System:** Automatic thumbnail generation for chat attachments
  - Images: Scaled with aspect ratio preservation
  - Videos: First frame extraction with play icon overlay
  - PDFs: First page rendering
  - Documents: File type icons with metadata
- **Permission Management:** Detailed explanation dialogs before requesting system permissions

### Changed
- Math formulas now render as images instead of raw LaTeX text
- Improved wizard UX with clickable input fields that open nested builders
- Enhanced video chat controls with better error handling

### Fixed
- Mini player now displays correct size (180×120dp) instead of full screen
- Video chat buttons no longer crash when fragment is not attached
- Math editor properly handles complex nested expressions

## [5.0.0] - 2024-XX-XX

### Added
- Multi-tab interface for opening multiple files
- 41 premium fonts including JetBrains Mono, Source Code Pro, Merriweather
- Tab bar auto-hide when only one file is open
- Smart tab indicators showing modified files
- Tab state management (content, cursor position, file URI)

## [4.5.1] - 2024-XX-XX

### Fixed
- Voice input now properly types recognized speech into editor
- Improved position calculation for voice input
- Enhanced error handling for voice recognition

### Added
- Better debugging support with enhanced logging

## [4.5.0] - 2024-XX-XX

### Added
- Rich text editor with Markdown and HTML rendering
- Smart font control for selected text or future typing
- Fixed line numbers with perfect alignment
- Rich text toggle in Settings
- Text type button for quick font style and size access
- Auto-formatting that applies when typing stops

## [4.0.0] - 2024-XX-XX

### Added
- Real-time collaboration with Firebase Realtime Database
- Session management with auto-save and sync
- Video chat integration with LiveKit
- Collaborative chat and roadmap features
- Google Drive sync for cloud storage

## [3.0.0] - 2024-XX-XX

### Added
- AI-powered assistance with smart suggestions
- Memory system for context-aware conversations
- Voice mode for hands-free interaction
- Media capabilities (image generation, video analysis)

## [2.0.0] - 2024-XX-XX

### Added
- Modern glass UI design
- Dynamic theming (Dark/Light modes)
- Enhanced editor with syntax highlighting
- File management improvements

## [1.0.0] - 2023-XX-XX

### Added
- Initial release
- Basic text editing functionality
- File open/save operations
- Simple UI
