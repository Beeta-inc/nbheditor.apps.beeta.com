# Changelog

All notable changes to NbhEditor will be documented in this file.

## [4.5.0] - 2026-04-28

### Added
- 📝 **Rich Text Editor**: Full Markdown and HTML rendering support with live formatting
  - Supports headings (# H1, ## H2, ### H3, #### H4)
  - Bold text (**bold** or __bold__)
  - Italic text (*italic* or _italic_)
  - Inline code (`code`)
  - Strikethrough (~~text~~)
  - Bullet lists (- item or * item)
  - HTML tag rendering
- ✍️ **Smart Font Control**: Apply fonts and sizes to selected text or future typing
  - Font options: Monospace, Sans Serif, Serif, Casual, Cursive
  - Size options: 12sp, 14sp, 16sp, 18sp, 20sp, 24sp
  - Selective formatting: Apply to selection or set for new text
- 🎨 **Text Type Button**: Quick access toolbar button for font style and size options
- ⚙️ **Rich Text Toggle**: Enable/disable formatting from Settings menu
  - Located in Settings → Editor Settings
  - Persists across app restarts
- 🔧 **Auto-Formatting**: Smart formatting that applies when you stop typing
  - 1.5 second delay after typing stops
  - Only formats when not actively typing
  - Preserves cursor position

### Fixed
- 📏 **Line Number Alignment**: Perfect alignment with editor text lines
  - Line numbers now match exact line height of editor
  - Proper spacing and padding applied
  - Numbers align perfectly with each text line
- 🐛 **Rich Text Crash**: Fixed IndexOutOfBoundsException when applying formatting
  - Added comprehensive error handling
  - Safe span application with bounds checking
  - Cursor position preservation

### Changed
- 🎯 **Settings Organization**: Moved rich text toggle from toolbar to Settings
- 🔄 **Font Application**: Changed from global to selective font/size changes

## [4.4.0] - 2026-04-15

### Added
- 🔗 **Collaborative Session Auto-Save**: Sessions automatically save when you end or leave
- 📂 **Organized Home Screen**: Separate sections for regular files and collaborative sessions
- ☁️ **Drive Sync for Sessions**: Collaborative sessions sync across all your devices
- 🎯 **Smart Session Management**: Tap to open, long-press to delete saved sessions
- 🔄 **Cross-Device Sync**: Work on any device, your sessions follow you

### Fixed
- 🐛 **Deep Link Support**: Fixed collaborative session deep links
- 🔒 **Typing Indicator Security**: Fixed email exposure in typing indicators
- 💬 **Chat Synchronization**: Fixed mobile-web chat sync issues
- 📱 **Media Support**: Added full media attachment support in collaborative chat

## [4.3.0] - 2026-03-20

### Added
- 🤝 **Real-Time Collaboration**: Work together with others in real-time
- 💬 **Collaborative Chat**: Built-in chat for collaborative sessions
- 👥 **User Presence**: See who's online and typing
- 🔗 **Session Invitations**: Easy sharing with session codes

## [4.2.0] - 2026-02-10

### Added
- ☁️ **Google Drive Integration**: Sync files across devices
- 🔐 **Google Sign-In**: Secure authentication
- 📤 **Auto-Upload**: Automatic cloud backup

## [4.1.0] - 2026-01-15

### Added
- 🧠 **AI Assistance**: Smart suggestions and improvements
- 🎤 **Voice Input**: Hands-free text entry
- 🖼️ **Image Support**: Insert and manage images in documents

## [4.0.0] - 2025-12-01

### Added
- ✨ **Modern Glass UI**: Beautiful, clean interface
- 🌙 **Dark Mode**: Eye-friendly dark theme
- 📱 **Android Support**: Native Android app
- 💾 **Auto-Save**: Never lose your work

---

## Version Format

Versions follow [Semantic Versioning](https://semver.org/):
- **MAJOR** version for incompatible API changes
- **MINOR** version for new functionality in a backwards compatible manner
- **PATCH** version for backwards compatible bug fixes

## Links

- [GitHub Repository](https://github.com/beeta-technologies/nbheditor)
- [Official Website](https://nbheditor.pages.dev)
- [Documentation](https://nbheditor.pages.dev/docs)
