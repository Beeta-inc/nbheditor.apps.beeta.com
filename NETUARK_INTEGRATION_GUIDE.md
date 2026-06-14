# NeTuArk Integration Guide: Connecting NbhEditor Collaborative Workspace

This guide outlines how to integrate the **NbhEditor** collaborative text workspace directly into the **NeTuArk** application.

---

## 1. Architectural Overview

The integration establishes a bidirectional connection between NeTuArk and NbhEditor:

1. **Document Spawning**: NeTuArk clients use the NbhEditor Client SDK to programmatically register and spawn collaborative text files.
2. **Collaborative Embeds**: NbhEditor workspace rooms are embedded inside NeTuArk chat threads (DMs and groups) via secure `<iframe>` interfaces, allowing multiple users to edit the same file simultaneously inside their chat screens.

```
┌────────────────────────────────────────────────────────┐
│                   NeTuArk App Client                   │
│                                                        │
│   ┌────────────────────┐      ┌────────────────────┐   │
│   │   NeTuArk Chat     │      │ NbhEditor SDK      │   │
│   │   • Send Message   │      │ • Create Document  │   │
│   │   • DM & Groups    │      │ • Create Room      │   │
│   └─────────┬──────────┘      └─────────┬──────────┘   │
│             │                           │              │
└─────────────┼───────────────────────────┼──────────────┘
              │                           │
              │ NeTuArk SDK Key           │ SDK REST API
              ▼                           ▼
┌────────────────────────┐      ┌────────────────────────┐
│ NeTuArk Firestore DB   │      │ NbhEditor API Server   │
│ Collection: /chats     │      │ (localhost:3000)       │
└────────────────────────┘      └────────────────────────┘
```

---

## 2. Setting Up NbhEditor Client SDK in NeTuArk

To enable document operations from the NeTuArk client, first download and include the NbhEditor Client SDK in your HTML head or import it into your build pipeline:

```html
<!-- Include NbhEditor client SDK -->
<script src="http://localhost:3000/sdk/nbheditor-sdk.js"></script>
```

Initialize the client with your registered developer credential:

```javascript
const nbhClient = new NbhEditorClient({
  apiKey: 'YOUR_NBHEDITOR_DEVELOPER_KEY', // Registered on NbhEditor Developer Portal
  apiUrl: 'http://localhost:3000'
});
```

---

## 3. Creating Collaborative Documents Inside Chats

### Step 1: Create a Document & Spawning Room
When a user clicks **"Create Collaborative Document"** in a chat window, execute the following script to create a document draft and generate a collaborative session room on NbhEditor's backend:

```javascript
async function startNbhSession(documentTitle, recipientEmailOrGroupId) {
  try {
    // 1. Create the document metadata
    const doc = await nbhClient.createDocument({
      title: documentTitle,
      content: 'Start collaborating on your document here...\n'
    });

    // 2. Spawn the collaborative room
    const room = await nbhClient.createRoom(doc.id);
    
    // 3. Construct the invite redirect link
    const inviteLink = `http://localhost:3000/collaborative-redirect.html/${room.sessionId}`;

    // 4. Send the invite link to the chat thread
    await sendInviteToChat(recipientEmailOrGroupId, inviteLink, documentTitle);

    return { documentId: doc.id, roomId: room.id, inviteLink };
  } catch (err) {
    console.error('Failed to start collaborative session:', err);
  }
}
```

### Step 2: Sending the Invite Link to Chat
Send the generated `inviteLink` as a chat message inside NeTuArk's message pipeline. In Firestore:

* **Collection**: `/chats/{chatId}/messages` (or `/groups/{groupId}/messages`)
* **Message Body**:
  ```json
  {
    "sender": "sender@example.com",
    "senderName": "Sender Name",
    "text": "👋 Join my collaborative NbhEditor document!\n\nInvite: http://localhost:3000/collaborative-redirect.html/SESSION_ID",
    "timestamp": "[Firestore Server Timestamp]",
    "read": false
  }
  ```

---

## 4. Embedding the Editor Workspace (iframe Integration)

To deliver a premium, seamless user experience, you can embed the NbhEditor workspace directly inside NeTuArk's chat dashboard.

### Step 1: Create a Toggleable Split-Screen Layout
In your chat template (`ChatUi.html`), add an iframe container next to the message list:

```html
<div class="chat-container">
  <!-- Message panel -->
  <div class="chat-messages-panel">
    <!-- Messages list and input box -->
  </div>
  
  <!-- Collaborative split-screen panel -->
  <div id="nbhEmbedPanel" class="nbh-embed-panel" style="display: none;">
    <div class="embed-header">
      <span id="embedDocTitle">Document Workspace</span>
      <button class="close-btn" onclick="closeNbhWorkspace()">Close</button>
    </div>
    <iframe id="nbhWorkspaceIframe" src="" frameborder="0" width="100%" height="100%"></iframe>
  </div>
</div>
```

Add styling to align panels side-by-side:

```css
.chat-container {
  display: flex;
  height: 100vh;
  background: #090a0f;
}
.chat-messages-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
}
.nbh-embed-panel {
  width: 50%;
  border-left: 1px solid rgba(255, 255, 255, 0.08);
  display: flex;
  flex-direction: column;
  background: rgba(20, 22, 35, 0.95);
  backdrop-filter: blur(10px);
}
.embed-header {
  padding: 15px;
  background: rgba(255, 255, 255, 0.02);
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  display: flex;
  justify-content: space-between;
  align-items: center;
}
```

### Step 2: Intercepting Session Invites
When rendering messages in the chat feed, intercept messages containing the redirect URL and render them as an interactive card button:

```javascript
function renderMessage(msg) {
  const isInvite = msg.text.includes('/collaborative-redirect.html/');
  
  if (isInvite) {
    const sessionId = msg.text.split('/collaborative-redirect.html/')[1].trim();
    return `
      <div class="message-card invite-card">
        <h4>📝 NbhEditor Collaborative Session</h4>
        <p>You have been invited to edit a live document.</p>
        <button class="btn btn-primary" onclick="openNbhWorkspace('${sessionId}')">
          Join Real-Time Edit Session
        </button>
      </div>
    `;
  }
  
  // Render normal message text...
}
```

### Step 3: Loading the Collaborative Frame
When the user clicks the **"Join Real-Time Edit Session"** card, load the collaborative editor URL inside the iframe and slide the panel open:

```javascript
function openNbhWorkspace(sessionId) {
  const embedPanel = document.getElementById('nbhEmbedPanel');
  const iframe = document.getElementById('nbhWorkspaceIframe');
  
  // Point the iframe to NbhEditor's web redirect file
  iframe.src = `http://localhost:3000/collaborative-redirect.html/${sessionId}`;
  
  // Slide the embed panel open
  embedPanel.style.display = 'flex';
}

function closeNbhWorkspace() {
  const embedPanel = document.getElementById('nbhEmbedPanel');
  const iframe = document.getElementById('nbhWorkspaceIframe');
  
  iframe.src = '';
  embedPanel.style.display = 'none';
}
```

---

## 5. Security & Domain Lock Settings

To ensure secure execution:

1. **Domain Lock Bypass**: During local development, ensure that your client files are running on `localhost` or set `window.__NETUARK_SDK_TEST_MODE = true;` before loading any SDK scripts.
2. **Production Key Registry**: When deploying to production domains (e.g., `themeet.pages.dev`), ensure you register your keys in NeTuArk's `sdk_keys` Firestore collection with `allowedDomains` array containing your domain.
