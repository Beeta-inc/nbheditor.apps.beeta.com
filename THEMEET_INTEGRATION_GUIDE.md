# TheMeet & NbhEditor Interconnection Integration Guide

This guide explains how to integrate **NbhEditor**'s real-time collaborative document editor directly into the **TheMeet** video conferencing web application.

By following this guide, participants in a TheMeet video call can collaboratively write notes, project roadmaps, or write documentation in real-time, synchronized through the NbhEditor engine.

---

## 🏗️ How it Works

```
 ┌──────────────────────────────────────────────────────────┐
 │                       TheMeet Client                     │
 │  ┌────────────────────────────────────────────────────┐  │
 │  │                  NbhEditor Client SDK              │  │
 │  └─────────────┬───────────────────────────▲──────────┘  │
 └────────────────┼───────────────────────────┼─────────────┘
                  │                           │
                  │ Room Join                 │ Real-Time Sync
                  │ Metadata                  │ WebSockets
                  ▼                           ▼
        ┌──────────────────┐        ┌───────────────────┐
        │ NbhEditor Server │        │ Firebase Realtime │
        │   Node/Express   │        │     Database      │
        └──────────────────┘        └───────────────────┘
```

1.  **Session Room Mapping:** When a meeting is started on TheMeet (e.g. `meet_12345`), TheMeet's backend maps it to a unique NbhEditor room.
2.  **Client Token Exchange:** TheMeet's backend calls NbhEditor's server-to-server API to request a temporary session token for each participant joining the room.
3.  **Real-Time Synced Text Area:** TheMeet's frontend loads the NbhEditor Client SDK, connects to the room session, and headlessly synchronizes a local HTML text area or editor instance with all other participants.

---

## 🛠️ Step-by-Step Integration

### Step 1: Obtain a Developer API Key
Register your app `themeet` on the NbhEditor backend:
```bash
curl -X POST -H "Content-Type: application/json" \
  -d '{"developerName": "themeet"}' \
  http://<nbheditor-api-host>/api/v1/dev/keys
```
*   **Response:**
    ```json
    {
      "message": "API Key generated successfully.",
      "apiKey": "nbh_key_your_generated_developer_key"
    }
    ```
*   **Testing Key:** For local testing, you can use the pre-seeded credentials:
    *   **App Name:** `themeet`
    *   **API Key:** `nbh_key_test_dev_123`

---

### Step 2: Generate Client Session Tokens (TheMeet Backend)
Whenever a participant joins an active meeting, **TheMeet's backend** must call NbhEditor's token endpoint to fetch a temporary session token. This token allows the frontend to sync without exposing your master API Key.

Make a server-to-server HTTP request:
*   **Endpoint:** `POST http://<nbheditor-api-host>/api/v1/auth/session`
*   **Headers:**
    *   `x-api-key: nbh_key_your_generated_developer_key`
    *   `Content-Type: application/json`
*   **Request Body:**
    ```json
    {
      "userId": "themeet_user_soham_pal", // Unique ID of participant
      "userName": "Soham Pal",            // Display name of participant
      "roomId": "room_themeet_meeting_12345" // Scoped to TheMeet meeting ID
    }
    ```
*   **Response:**
    ```json
    {
      "sessionToken": "nbh_tok_a0c102df...",
      "expiresAt": 1729003600000,
      "roomId": "room_themeet_meeting_12345"
    }
    ```
Pass the returned `sessionToken` and the `roomId` to your frontend layout.

---

### Step 3: Embed the Headless Client SDK (TheMeet Frontend)
Load the NbhEditor client script in your HTML page:
```html
<script src="http://<nbheditor-api-host>/sdk/nbheditor-sdk.js"></script>
```

Add a textarea inside your meeting screen layout (e.g. inside a "Collaborative Notes" sidebar panel):
```html
<div class="collab-notes-panel">
  <h3>📝 Collaborative Meeting Notes</h3>
  <div class="active-users" id="notesActiveUsers"></div>
  <textarea id="collabNotesEditor" placeholder="Start typing notes together..."></textarea>
</div>
```

---

### Step 4: Write the Frontend Synchronizer (TheMeet Frontend)
Initialize the SDK client using the temporary token generated in **Step 2**, join the collaborative room, and bind the input events:

```javascript
// 1. Initialize Client
const nbhClient = new NbhEditorClient({
  sessionToken: 'nbh_tok_temporary_session_token_passed_from_backend',
  apiUrl: 'http://localhost:3000' // Your NbhEditor Web API host
});

let isRemoteUpdating = false;
let syncSession = null;

// 2. Connect to the Room
async function startCollabNotes(roomId, localUser) {
  try {
    syncSession = await nbhClient.joinCollaborativeRoom(roomId, {
      id: localUser.uid,     // Unique ID of active participant
      name: localUser.name   // Display name
    }, {
      // Callback: Fired when any participant edits the document content
      onContentChange: (content) => {
        isRemoteUpdating = true;
        const textarea = document.getElementById('collabNotesEditor');
        
        // Save cursor position before updating
        const selectionStart = textarea.selectionStart;
        const selectionEnd = textarea.selectionEnd;
        
        textarea.value = content;
        
        // Restore cursor position
        textarea.setSelectionRange(selectionStart, selectionEnd);
        isRemoteUpdating = false;
      },
      
      // Callback: Fired when participants list, cursors, or typing indicators update
      onUsersChange: (usersMap) => {
        updateActiveUsersDisplay(usersMap);
      }
    });

    // Load initial room content
    document.getElementById('collabNotesEditor').value = syncSession.initialContent;

  } catch (error) {
    console.error("Failed to connect to NbhEditor room:", error);
  }
}

// 3. Bind Local Editor Changes
const notesEditor = document.getElementById('collabNotesEditor');

notesEditor.addEventListener('input', (event) => {
  if (isRemoteUpdating || !syncSession) return;
  
  // Pushes changes to all other connected participants in real time
  nbhClient.updateLiveContent(event.target.value);
  
  // Broadcast typing status and cursor location
  nbhClient.updateLiveCursor(event.target.selectionStart, true);
});

// Clear typing status when participant stops typing
let typingTimer;
notesEditor.addEventListener('keyup', (event) => {
  clearTimeout(typingTimer);
  typingTimer = setTimeout(() => {
    if (syncSession) {
      nbhClient.updateLiveCursor(event.target.selectionStart, false);
    }
  }, 1200);
});

// 4. Render Active Participant List & Cursors
function updateActiveUsersDisplay(usersMap) {
  const container = document.getElementById('notesActiveUsers');
  container.innerHTML = '';
  
  Object.values(usersMap).forEach(user => {
    const userBadge = document.createElement('span');
    userBadge.className = 'user-badge';
    userBadge.style.marginRight = '8px';
    userBadge.style.padding = '4px 8px';
    userBadge.style.border = '1px solid ' + (user.typing ? '#00e676' : '#8892b0');
    userBadge.textContent = `${user.userName} ${user.typing ? '✍️' : ''}`;
    container.appendChild(userBadge);
  });
}

// 5. Leave room when closing meeting
function stopCollabNotes() {
  if (syncSession) {
    syncSession.disconnect();
    syncSession = null;
  }
}
```

---

## 📹 Video Meeting Launch Flow (NbhEditor to TheMeet)
When a user launches or joins a TheMeet call directly inside the **NbhEditor Android Application**, the application opens an intent to:
`https://themeet.pages.dev/meeting/{meetingId}`

### How to handle this on TheMeet Web App:
*   Ensure that URLs containing `/meeting/{meetingId}` route directly to the active video chat room.
*   If the user has authenticated via the interconnect bridge, auto-join them using their displayName and randomized uid.
