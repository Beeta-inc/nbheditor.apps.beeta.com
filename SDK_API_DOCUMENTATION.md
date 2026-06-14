# NbhEditor Developer SDK & REST Web API Documentation

Welcome to the official developer documentation for integrating **NbhEditor**'s editing engine, CRUD APIs, and real-time collaborative rooms into your third-party applications (including social media networks, groupware systems, and content management apps).

This documentation details the architecture, authentication, endpoints, Client SDK usage, and export flows.

---

## 🏛️ System Architecture Overview

The NbhEditor integration platform consists of:
1.  **NbhEditor Web API Server:** A Node.js/Express server that handles document metadata persistence, developer authorization, temporary session tokens, and real-time room registrations.
2.  **Firebase Realtime Database:** The underlying synchronization fabric for collaborative rooms, used by both the native clients (Android/Linux) and the Developer SDK.
3.  **Headless Client SDK:** A platform-agnostic JavaScript library that abstracts both the CRUD endpoints and the dynamic Firebase WebSocket listener bindings.
4.  **Native Integrations:** Direct export features within the NbhEditor client apps (e.g. Android's drawer menu export).

```
 ┌─────────────────────────────────────────────────────────────────┐
 │                       Third-Party App                           │
 │  ┌───────────────────────────────────────────────────────────┐  │
 │  │                         Client SDK                        │  │
 │  └─────────────┬───────────┬─────────────────────▲───────────┘  │
 └────────────────┼───────────┼─────────────────────┼──────────────┘
                  │           │                     │
      REST CRUD   │           │ Room Join           │ Real-Time Sync
      & Exports   │           │ Metadata            │ WebSockets
                  ▼           ▼                     ▼
        ┌───────────────────────────┐     ┌───────────────────┐
        │     NbhEditor Backend     │     │ Firebase Realtime │
        │        Node/Express       │     │     Database      │
        └───────────────────────────┘     └───────────────────┘
```

---

## 🔐 Authentication Models

To ensure secure communications, NbhEditor supports two authentication modes. Developers should combine these modes depending on the execution context.

### 1. Developer API Keys (Server-to-Server)
*   **Header Name:** `x-api-key`
*   **Usage:** Used exclusively on the developer's secure backend server to perform CRUD actions or request client session tokens.
*   **Seeded Test Key:** `nbh_key_test_dev_123` is pre-seeded in the database for immediate testing.
*   > [!CAUTION]
    > Never expose your master Developer API Key in client-side code (web browsers or mobile apps).

### 2. Client Session Tokens (Temporary & Scoped)
*   **Header Format:** `Authorization: Bearer <sessionToken>`
*   **Usage:** Used by client-side SDK instances running in a user's browser or mobile application.
*   **Scope:** Scoped to a specific user and room ID, with a configurable expiration window.
*   **Generation Flow:** The developer's backend server uses its Developer API Key to request a temporary session token for a specific user. The token is then passed to the client SDK.

---

## 🌐 REST Web API Specification

All endpoints are prefixed with `/api/v1`. All request and response bodies are formatted in JSON.

---

### 🔑 Developer Key Management

#### 1. Generate Developer API Key
*   **Endpoint:** `POST /api/v1/dev/keys`
*   **Auth Required:** None (Public for developer registration)
*   **Request Body:**
    ```json
    {
      "developerName": "Beeta Social Network"
    }
    ```
*   **Success Response (201 Created):**
    ```json
    {
      "message": "API Key generated successfully.",
      "developerName": "Beeta Social Network",
      "apiKey": "nbh_key_771f28b7e2...",
      "createdAt": 1729000000000
    }
    ```
*   **Error Response (400 Bad Request):**
    ```json
    {
      "error": "Bad Request",
      "message": "Missing developerName in request body."
    }
    ```

#### 2. List Developer Keys
*   **Endpoint:** `GET /api/v1/dev/keys`
*   **Auth Required:** None (Used for debugging/diagnostics)
*   **Success Response (200 OK):**
    ```json
    [
      {
        "key": "nbh_key_test_dev_123",
        "developerName": "Seeded Test Developer",
        "createdAt": 1729000000000,
        "status": "active"
      }
    ]
    ```

---

### 🎟️ Session Tokens

#### 1. Generate Client Session Token
*   **Endpoint:** `POST /api/v1/auth/session`
*   **Auth Required:** Developer API Key (`x-api-key` header)
*   **Request Body:**
    ```json
    {
      "userId": "social_user_alice_77",
      "userName": "Alice Smith",
      "roomId": "room_a52f9c...",
      "durationSeconds": 3600
    }
    ```
*   **Success Response (201 Created):**
    ```json
    {
      "sessionToken": "nbh_tok_a021ff...",
      "expiresAt": 1729003600000,
      "roomId": "room_a52f9c...",
      "userId": "social_user_alice_77",
      "userName": "Alice Smith"
    }
    ```
*   **Error Response (401 Unauthorized):**
    ```json
    {
      "error": "Unauthorized",
      "message": "Invalid or deactivated API key."
    }
    ```

---

### 📄 Document CRUD Operations

*All document endpoints support authentication using either `x-api-key` (server-side) or `Authorization: Bearer <token>` (client-side).*

#### 1. Create Document
*   **Endpoint:** `POST /api/v1/documents`
*   **Request Body:**
    ```json
    {
      "title": "Meeting Agenda",
      "content": "# Agenda\n1. Review goals\n2. Q&A"
    }
    ```
*   **Success Response (201 Created):**
    ```json
    {
      "id": "doc_f21a09...",
      "title": "Meeting Agenda",
      "content": "# Agenda\n1. Review goals\n2. Q&A",
      "ownerApiKey": "nbh_key_test_dev_123",
      "createdAt": 1729000000000,
      "updatedAt": 1729000000000
    }
    ```

#### 2. List Documents
*   **Endpoint:** `GET /api/v1/documents`
*   **Success Response (200 OK):**
    ```json
    [
      {
        "id": "doc_f21a09...",
        "title": "Meeting Agenda",
        "content": "# Agenda...",
        "ownerApiKey": "nbh_key_test_dev_123",
        "createdAt": 1729000000000,
        "updatedAt": 1729000000000
      }
    ]
    ```

#### 3. Retrieve Document Details
*   **Endpoint:** `GET /api/v1/documents/:id`
*   **Success Response (200 OK):**
    ```json
    {
      "id": "doc_f21a09...",
      "title": "Meeting Agenda",
      "content": "# Agenda\n1. Review goals\n2. Q&A",
      "ownerApiKey": "nbh_key_test_dev_123",
      "createdAt": 1729000000000,
      "updatedAt": 1729000000000
    }
    ```
*   **Error Response (404 Not Found):**
    ```json
    {
      "error": "Document not found or access denied."
    }
    ```

#### 4. Update Document
*   **Endpoint:** `PUT /api/v1/documents/:id`
*   **Request Body:**
    ```json
    {
      "title": "Updated Meeting Agenda",
      "content": "# Revised Agenda\n1. Action items"
    }
    ```
*   **Success Response (200 OK):**
    ```json
    {
      "id": "doc_f21a09...",
      "title": "Updated Meeting Agenda",
      "content": "# Revised Agenda\n1. Action items",
      "ownerApiKey": "nbh_key_test_dev_123",
      "createdAt": 1729000000000,
      "updatedAt": 1729005000000
    }
    ```

#### 5. Delete Document
*   **Endpoint:** `DELETE /api/v1/documents/:id`
*   **Success Response (200 OK):**
    ```json
    {
      "message": "Document deleted successfully.",
      "id": "doc_f21a09..."
    }
    ```

---

### 🚪 Collaborative Sessions & Rooms

#### 1. Create Collaboration Room
*   **Endpoint:** `POST /api/v1/rooms`
*   **Request Body:**
    ```json
    {
      "documentId": "doc_f21a09...",
      "customSessionId": "NE9000X"
    }
    ```
*   **Success Response (201 Created):**
    ```json
    {
      "id": "room_a52f9c...",
      "documentId": "doc_f21a09...",
      "ownerApiKey": "nbh_key_test_dev_123",
      "sessionId": "NE9000X",
      "firebasePath": "collaborative_sessions/NE9000X",
      "createdAt": 1729000000000,
      "status": "active"
    }
    ```

#### 2. Get Room Configuration Details
*   **Endpoint:** `GET /api/v1/rooms/:roomId`
*   **Success Response (200 OK):**
    ```json
    {
      "id": "room_a52f9c...",
      "documentId": "doc_f21a09...",
      "ownerApiKey": "nbh_key_test_dev_123",
      "sessionId": "NE9000X",
      "firebasePath": "collaborative_sessions/NE9000X",
      "createdAt": 1729000000000,
      "status": "active"
    }
    ```

#### 3. Join Collaboration Room
*   **Endpoint:** `POST /api/v1/rooms/:roomId/join`
*   **Success Response (200 OK):**
    *Provides the client SDK with connection details for direct Firebase sync.*
    ```json
    {
      "message": "Successfully joined room.",
      "roomId": "room_a52f9c...",
      "sessionId": "NE9000X",
      "firebasePath": "collaborative_sessions/NE9000X",
      "firebaseDatabaseUrl": "https://nbheditior-default-rtdb.firebaseio.com",
      "document": {
        "id": "doc_f21a09...",
        "title": "Meeting Agenda",
        "content": "# Agenda\n1. Review goals\n2. Q&A"
      }
    }
    ```

---

### 📤 Social Media Export Channels

#### 1. Export Draft to Feed
*   **Endpoint:** `POST /api/v1/export/feed`
*   **Request Body:**
    ```json
    {
      "documentId": "doc_f21a09...",
      "postText": "Drafting our release notes inside NbhEditor! #buildinpublic",
      "customMetadata": { "category": "changelog", "visibility": "public" }
    }
    ```
*   **Success Response (200 OK):**
    ```json
    {
      "status": "success",
      "message": "Draft successfully exported to social media Feed.",
      "exportId": "feed_post_88d2f091ab",
      "timestamp": 1729000000000,
      "payload": {
        "documentId": "doc_f21a09...",
        "title": "Meeting Agenda",
        "content": "# Agenda...",
        "postText": "Drafting our release notes inside NbhEditor! #buildinpublic",
        "meta": { "category": "changelog", "visibility": "public" }
      }
    }
    ```

#### 2. Export Draft to Chat (DM / Group)
*   **Endpoint:** `POST /api/v1/export/chat`
*   **Request Body:**
    ```json
    {
      "documentId": "doc_f21a09...",
      "chatId": "group_engineering_11",
      "chatType": "group",
      "messageText": "Here is the agenda we just wrapped up editing."
    }
    ```
*   **Success Response (200 OK):**
    ```json
    {
      "status": "success",
      "message": "Document successfully sent to group chat.",
      "exportId": "chat_msg_bf902cf120",
      "timestamp": 1729000000000,
      "payload": {
        "documentId": "doc_f21a09...",
        "title": "Meeting Agenda",
        "chatId": "group_engineering_11",
        "chatType": "group",
        "messageText": "Here is the agenda we just wrapped up editing."
      }
    }
    ```

---

## 📦 Developer Client SDK Guide

The Client SDK is a headless JavaScript library designed for integration into frontend web apps.

### 1. Initialization

Expose the SDK on your page (or import it if using bundlers). To initialize:

```javascript
// Server-to-server token authentication (recommended for web clients)
const client = new NbhEditorClient({
  sessionToken: 'nbh_tok_temporary_session_token_passed_from_server',
  apiUrl: 'http://localhost:3000'
});

// OR direct API key auth (useful for local development & staging)
const clientDev = new NbhEditorClient({
  apiKey: 'nbh_key_test_dev_123',
  apiUrl: 'http://localhost:3000'
});
```

---

### 2. File CRUD Operations

#### Create a Document
```javascript
async function createNewDoc() {
  try {
    const doc = await client.createDocument({
      title: 'Release Summary v1.0',
      content: '# Version 1.0\nInitial production build release!'
    });
    console.log('Document created:', doc.id);
    return doc.id;
  } catch (error) {
    console.error('Error creating document:', error.message);
  }
}
```

#### Read, Update & Delete Documents
```javascript
// Fetch Document
const doc = await client.getDocument('doc_id_here');

// Update Content
const updated = await client.updateDocument('doc_id_here', {
  title: 'Revised Release Summary v1.0',
  content: '# Version 1.0\nUpdated content...'
});

// Delete Document
await client.deleteDocument('doc_id_here');
```

---

### 3. Collaborative Room Sync (Real-time & Headless)

The SDK exposes a headless interface. Bind event listeners to your UI components (like textareas, WYSIWYG editors, or cursor markers) to sync changes in real-time.

```javascript
let isRemoteUpdating = false;

// 1. Join Room
const roomSession = await client.joinCollaborativeRoom('room_id_here', {
  id: 'user_bob_99',
  name: 'Bob Marley'
}, {
  // Callback when content changes in room
  onContentChange: (content) => {
    isRemoteUpdating = true;
    
    // Bind document content to your custom UI element
    document.getElementById('myTextArea').value = content;
    
    isRemoteUpdating = false;
  },
  
  // Callback when user metadata updates (cursors, typing indicators)
  onUsersChange: (usersMap) => {
    updateActiveUsersDisplay(usersMap);
  }
});

// 2. Broadcast local text updates
document.getElementById('myTextArea').addEventListener('input', (event) => {
  if (isRemoteUpdating) return;
  
  const text = event.target.value;
  // Send content change to all room participants
  client.updateLiveContent(text);
  
  // Update typing indicator and cursor index
  const cursorIndex = event.target.selectionStart;
  client.updateLiveCursor(cursorIndex, true);
});

// 3. Clear typing status when user stops writing
let typingTimer;
document.getElementById('myTextArea').addEventListener('keyup', (event) => {
  clearTimeout(typingTimer);
  typingTimer = setTimeout(() => {
    client.updateLiveCursor(event.target.selectionStart, false);
  }, 1000);
});

// 4. Disconnect when leaving page
window.addEventListener('beforeunload', () => {
  roomSession.disconnect();
});
```

---

### 4. Exporting Drafts

#### Export to Social Feed Post
```javascript
async function publishPost(docId) {
  const response = await client.exportToFeed(docId, {
    postText: 'Shared from NbhEditor project draft! #realtime'
  });
  console.log('Posted! Post ID:', response.exportId);
}
```

#### Send to Chat DM / Group
```javascript
async function sendToGroup(docId) {
  const response = await client.exportToChat(docId, {
    chatId: 'group_marketing_99',
    chatType: 'group',
    messageText: 'Check out the draft we finished yesterday.'
  });
  console.log('Sent! Message ID:', response.exportId);
}
```
