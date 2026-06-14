/**
 * NbhEditor Developer Client SDK
 * A platform-agnostic, headless client wrapper for interacting with the NbhEditor Web API
 * and subscribing to real-time collaborative document synchronization.
 */
class NbhEditorClient {
  /**
   * Initialize the NbhEditor Client SDK
   * @param {Object} config
   * @param {string} [config.apiKey] - Developer API Key (optional for client-side if token is used)
   * @param {string} [config.sessionToken] - Temporary Session Token (preferred for client-side)
   * @param {string} [config.apiUrl] - Base URL of the NbhEditor API backend
   */
  constructor(config = {}) {
    this.apiKey = config.apiKey || null;
    this.sessionToken = config.sessionToken || null;
    this.apiUrl = config.apiUrl || 'http://localhost:3000';
    this.firebaseApp = null;
    this.firebaseDb = null;
    this.activeRoomRef = null;
    this.listeners = {};
  }

  /**
   * Helper to set headers for requests
   * @private
   */
  _getHeaders() {
    const headers = {
      'Content-Type': 'application/json'
    };
    if (this.sessionToken) {
      headers['Authorization'] = `Bearer ${this.sessionToken}`;
    } else if (this.apiKey) {
      headers['x-api-key'] = this.apiKey;
    }
    return headers;
  }

  /**
   * Helper to make HTTP requests
   * @private
   */
  async _request(endpoint, options = {}) {
    const url = `${this.apiUrl}${endpoint}`;
    const headers = { ...this._getHeaders(), ...options.headers };
    const config = { ...options, headers };

    try {
      const response = await fetch(url, config);
      const data = await response.json();
      if (!response.ok) {
        throw new Error(data.message || data.error || `Request failed with status ${response.status}`);
      }
      return data;
    } catch (error) {
      console.error(`[NbhEditor SDK] API Error at ${endpoint}:`, error);
      throw error;
    }
  }

  // ─── DOCUMENT CRUD METHODS ─────────────────────────────────────────

  /**
   * Create a new document
   * @param {Object} documentData
   * @param {string} documentData.title - Title of the document
   * @param {string} [documentData.content] - Initial text content
   */
  async createDocument(documentData) {
    return this._request('/api/v1/documents', {
      method: 'POST',
      body: JSON.stringify(documentData)
    });
  }

  /**
   * Retrieve a document's details and content
   * @param {string} documentId
   */
  async getDocument(documentId) {
    return this._request(`/api/v1/documents/${documentId}`, {
      method: 'GET'
    });
  }

  /**
   * Update a document's details and content
   * @param {string} documentId
   * @param {Object} updateData
   * @param {string} [updateData.title] - Updated document title
   * @param {string} [updateData.content] - Updated document text content
   */
  async updateDocument(documentId, updateData) {
    return this._request(`/api/v1/documents/${documentId}`, {
      method: 'PUT',
      body: JSON.stringify(updateData)
    });
  }

  /**
   * Delete a document
   * @param {string} documentId
   */
  async deleteDocument(documentId) {
    return this._request(`/api/v1/documents/${documentId}`, {
      method: 'DELETE'
    });
  }

  /**
   * List all documents owned by the api key
   */
  async listDocuments() {
    return this._request('/api/v1/documents', {
      method: 'GET'
    });
  }

  // ─── SOCIAL MEDIA EXPORT METHODS ────────────────────────────────────

  /**
   * Export draft content to a social media Feed Post
   * @param {string} documentId - ID of document to export
   * @param {Object} exportPayload
   * @param {string} [exportPayload.postText] - Custom caption/text for feed post
   * @param {Object} [exportPayload.customMetadata] - Custom tags, config, etc.
   */
  async exportToFeed(documentId, exportPayload = {}) {
    return this._request('/api/v1/export/feed', {
      method: 'POST',
      body: JSON.stringify({ documentId, ...exportPayload })
    });
  }

  /**
   * Send document to a DM or Group Chat
   * @param {string} documentId - ID of document to send
   * @param {Object} exportPayload
   * @param {string} exportPayload.chatId - Targeted Chat ID (UserID or GroupID)
   * @param {string} exportPayload.chatType - "dm" or "group"
   * @param {string} [exportPayload.messageText] - Accompanying message text
   */
  async exportToChat(documentId, exportPayload = {}) {
    return this._request('/api/v1/export/chat', {
      method: 'POST',
      body: JSON.stringify({ documentId, ...exportPayload })
    });
  }

  // ─── NETUARK INTEGRATION METHODS ────────────────────────────────────

  /**
   * Loads the NeTuArk Bridge SDK script dynamically and initializes it.
   * @param {string} apiKey - NeTuArk Bridge SDK API Key
   * @returns {Promise<{ success: boolean, sdkVersion: string }>}
   */
  async initNeTuArk(apiKey) {
    return new Promise((resolve, reject) => {
      if (typeof window === 'undefined') {
        return reject(new Error('NeTuArk SDK is only supported in browser environments.'));
      }
      
      // Bypasses domain lock for local testing (e.g. localhost)
      if (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1') {
        window.__NETUARK_SDK_TEST_MODE = true;
      }

      if (window.NeTuArkSDK) {
        window.NeTuArkSDK.init({ apiKey }).then(resolve).catch(reject);
        return;
      }

      console.log('[NbhEditor SDK] Loading NeTuArk Bridge SDK dynamically...');
      const script = document.createElement('script');
      script.src = 'https://netuark.pages.dev/sdk/netuark-sdk.js';
      script.onload = () => {
        if (!window.NeTuArkSDK) {
          return reject(new Error('NeTuArkSDK global object not found after script load.'));
        }
        window.NeTuArkSDK.init({ apiKey }).then(resolve).catch(reject);
      };
      script.onerror = (e) => reject(new Error('Failed to load NeTuArk Bridge SDK script.'));
      document.head.appendChild(script);
    });
  }

  /**
   * Request permission and verify the NeTuArk account
   * @param {string} email - Email address of the user to connect
   * @returns {Promise<{ verified: boolean, account: Object }>}
   */
  async connectNeTuArkUser(email) {
    if (!window.NeTuArkSDK) {
      throw new Error('NeTuArk SDK is not initialized. Call initNeTuArk(apiKey) first.');
    }
    
    // Step 1: Request permission
    const perm = await window.NeTuArkSDK.requestPermission(email);
    if (!perm.granted) {
      throw new Error('Permission to connect NeTuArk was denied by the user.');
    }
    
    // Step 2: Verify account
    const account = await window.NeTuArkSDK.verifyAccount(email);
    return {
      verified: true,
      account
    };
  }

  /**
   * Fetch NeTuArk active contacts list
   * @returns {Promise<Array>}
   */
  async getNeTuArkContacts() {
    if (!window.NeTuArkSDK) {
      throw new Error('NeTuArk SDK is not initialized. Call initNeTuArk(apiKey) first.');
    }
    return window.NeTuArkSDK.getChatUsers();
  }

  /**
   * Sends a document's content to a NeTuArk chat.
   * @param {string} email - Targeted recipient's email address
   * @param {string} documentId - NbhEditor document ID
   * @param {boolean} [sendAsFile=false] - If true, uploads and sends the document as a .txt file attachment. If false, sends the content as a text message.
   * @returns {Promise<Object>}
   */
  async sendDocumentToNeTuArkChat(email, documentId, sendAsFile = false) {
    if (!window.NeTuArkSDK) {
      throw new Error('NeTuArk SDK is not initialized. Call initNeTuArk(apiKey) first.');
    }
    
    const doc = await this.getDocument(documentId);
    
    if (sendAsFile) {
      const filename = `${doc.title || 'untitled'}.txt`;
      const blob = new Blob([doc.content || ''], { type: 'text/plain' });
      return window.NeTuArkSDK.sendMedia(email, 'dm', 'file', blob, filename);
    } else {
      const textContent = `📄 NbhEditor Document: ${doc.title || 'Untitled'}\n\n${doc.content || ''}`;
      return window.NeTuArkSDK.sendMessage(email, textContent);
    }
  }

  /**
   * Sends a document's content to a NeTuArk group chat.
   * @param {string} groupId - Targeted group's ID
   * @param {string} documentId - NbhEditor document ID
   * @returns {Promise<Object>}
   */
  async sendDocumentToNeTuArkGroup(groupId, documentId) {
    if (!window.NeTuArkSDK) {
      throw new Error('NeTuArk SDK is not initialized. Call initNeTuArk(apiKey) first.');
    }
    
    const doc = await this.getDocument(documentId);
    const filename = `${doc.title || 'untitled'}.txt`;
    const blob = new Blob([doc.content || ''], { type: 'text/plain' });
    return window.NeTuArkSDK.sendMedia(groupId, 'group', 'file', blob, filename);
  }

  /**
   * Publishes a document's content directly to the NeTuArk social feed.
   * @param {string} documentId - NbhEditor document ID
   * @param {string} [postCaption] - Optional text/caption to accompany the post
   * @param {boolean} [sendAsFile=false] - If true, uploads the document as a text file attachment to the post.
   * @returns {Promise<Object>}
   */
  async postDocumentToNeTuArkFeed(documentId, postCaption = '', sendAsFile = false) {
    if (!window.NeTuArkSDK) {
      throw new Error('NeTuArk SDK is not initialized. Call initNeTuArk(apiKey) first.');
    }
    
    const doc = await this.getDocument(documentId);
    let finalCaption = postCaption ? `${postCaption.trim()}\n\n` : '';
    
    if (sendAsFile) {
      const filename = `${doc.title || 'untitled'}.txt`;
      const blob = new Blob([doc.content || ''], { type: 'text/plain' });
      
      console.log('[NbhEditor SDK] Uploading file to NeTuArk Cloudinary...');
      const fd = new FormData();
      fd.append('file', blob, filename);
      fd.append('upload_preset', 'ntabys');
      const resp = await fetch('https://api.cloudinary.com/v1_1/dzgatckob/raw/upload', {
        method: 'POST',
        body: fd
      });
      if (!resp.ok) {
        throw new Error(`Cloudinary upload failed: ${resp.statusText}`);
      }
      const uploadData = await resp.json();
      return window.NeTuArkSDK.createPost({
        text: postCaption,
        mediaUrl: uploadData.secure_url,
        mediaType: 'file'
      });
    } else {
      let text = `${finalCaption}📄 **${doc.title || 'Untitled'}**\n\`\`\`text\n${doc.content || ''}\n\`\`\``;
      if (text.length > 5000) {
        text = text.substring(0, 4950) + '\n... [Content truncated due to length limits]';
      }
      return window.NeTuArkSDK.createPost({
        text: text
      });
    }
  }

  /**
   * Creates a collaborative editing room, then sends its invite link to a NeTuArk contact.
   * @param {string} documentTitle - Title of the new document
   * @param {string} recipientEmail - Recipient email in NeTuArk
   * @returns {Promise<Object>}
   */
  async createSharedCollaborationRoom(documentTitle, recipientEmail) {
    if (!window.NeTuArkSDK) {
      throw new Error('NeTuArk SDK is not initialized. Call initNeTuArk(apiKey) first.');
    }
    
    // 1. Create document
    const doc = await this.createDocument({
      title: documentTitle,
      content: 'Start collaborating here...'
    });

    // 2. Create room
    const room = await this.createRoom(doc.id);

    // 3. Send session join link
    const redirectUrl = `${window.location.origin}/collaborative-redirect.html/${room.sessionId}`;
    
    const inviteMessage = `👋 Join my collaborative NbhEditor document room!\n\nDocument: "${documentTitle}"\nLink: ${redirectUrl}`;
    
    await window.NeTuArkSDK.sendMessage(recipientEmail, inviteMessage);
    
    return {
      documentId: doc.id,
      roomId: room.id,
      sessionId: room.sessionId,
      redirectUrl
    };
  }

  // ─── COLLABORATIVE ROOMS & REAL-TIME SYNC ───────────────────────────

  /**
   * Create a new collaborative editing room linked to a document
   * @param {string} documentId
   * @param {string} [customSessionId] - Optional custom session ID (e.g., NEABC12)
   */
  async createRoom(documentId, customSessionId = null) {
    return this._request('/api/v1/rooms', {
      method: 'POST',
      body: JSON.stringify({ documentId, customSessionId })
    });
  }

  /**
   * Fetch details for a specific collaborative room
   * @param {string} roomId
   */
  async getRoom(roomId) {
    return this._request(`/api/v1/rooms/${roomId}`, {
      method: 'GET'
    });
  }

  /**
   * Join a room and initialize real-time synchronization listeners headlessly
   * @param {string} roomId - ID of the room to join
   * @param {Object} user - User metadata representing the joining client
   * @param {string} user.id - Unique ID of the client user
   * @param {string} user.name - Display name of the user
   * @param {string} [user.photoUrl] - Optional photo URL
   * @param {Object} callbacks - Live synchronization event callbacks
   * @param {Function} callbacks.onContentChange - Fired when the document text content is modified by anyone: `(content) => {}`
   * @param {Function} callbacks.onUsersChange - Fired when active room participants list updates: `(usersMap) => {}`
   * @param {Function} [callbacks.onDisconnect] - Fired when disconnected from room
   */
  async joinCollaborativeRoom(roomId, user, callbacks = {}) {
    // 1. Join room via API to fetch session metadata
    const joinMetadata = await this._request(`/api/v1/rooms/${roomId}/join`, {
      method: 'POST'
    });

    console.log('[NbhEditor SDK] Join metadata fetched successfully:', joinMetadata);

    // 2. Dynamically load Firebase SDK if not already available
    await this._ensureFirebaseLoaded();

    // 3. Initialize Firebase Realtime Database
    this._initFirebase(joinMetadata.firebaseDatabaseUrl);

    const sessionId = joinMetadata.sessionId;
    const sessionPath = joinMetadata.firebasePath;
    const userRefPath = `${sessionPath}/users/${user.id.replace(/\./g, '_')}`;

    console.log(`[NbhEditor SDK] Connecting to room path: ${sessionPath}`);
    this.activeRoomRef = this.firebaseDb.ref(sessionPath);

    // 4. Register client user presence state
    const userPayload = {
      userId: user.id,
      userName: user.name,
      photoUrl: user.photoUrl || '',
      lastActive: firebase.database.ServerValue.TIMESTAMP,
      cursorPosition: 0,
      typing: false,
      status: 'active'
    };

    const userRef = this.firebaseDb.ref(userRefPath);
    await userRef.set(userPayload);

    // Setup disconnect presence cleanup
    userRef.onDisconnect().remove();
    this.firebaseDb.ref('.info/connected').on('value', (snapshot) => {
      if (snapshot.val() === true) {
        userRef.onDisconnect().remove();
        userRef.child('lastActive').setValue(firebase.database.ServerValue.TIMESTAMP);
      }
    });

    // 5. Attach real-time sync listeners
    
    // Live Document Content Changes
    const contentRef = this.activeRoomRef.child('content');
    contentRef.on('value', (snapshot) => {
      const content = snapshot.val() || '';
      if (callbacks.onContentChange) {
        callbacks.onContentChange(content);
      }
    });

    // Live Room Participants List
    const usersRef = this.activeRoomRef.child('users');
    usersRef.on('value', (snapshot) => {
      const users = snapshot.val() || {};
      if (callbacks.onUsersChange) {
        callbacks.onUsersChange(users);
      }
    });

    // Save references so we can unsubscribe during teardown
    this.listeners = {
      contentRef,
      usersRef,
      userRef
    };

    return {
      roomId,
      sessionId,
      initialContent: joinMetadata.document.content,
      disconnect: () => this.leaveRoom()
    };
  }

  /**
   * Broadcast local document content updates to the active room
   * @param {string} content - Full text of the document
   */
  async updateLiveContent(content) {
    if (!this.activeRoomRef) {
      throw new Error('No active room session. Call joinCollaborativeRoom first.');
    }
    await this.activeRoomRef.child('content').set(content);
    await this.activeRoomRef.child('lastActivity').set(firebase.database.ServerValue.TIMESTAMP);
  }

  /**
   * Broadcast cursor position and typing status to other clients
   * @param {number} position - Character index cursor is at
   * @param {boolean} isTyping - Whether the user is actively typing
   */
  async updateLiveCursor(position, isTyping) {
    if (!this.listeners.userRef) {
      throw new Error('No active room user reference. Call joinCollaborativeRoom first.');
    }
    await this.listeners.userRef.update({
      cursorPosition: position,
      typing: isTyping,
      lastActive: firebase.database.ServerValue.TIMESTAMP
    });
  }

  /**
   * Leave the active collaborative room and tear down listeners
   */
  async leaveRoom() {
    console.log('[NbhEditor SDK] Tearing down room connection...');
    if (this.listeners.userRef) {
      await this.listeners.userRef.remove();
    }
    if (this.listeners.contentRef) this.listeners.contentRef.off('value');
    if (this.listeners.usersRef) this.listeners.usersRef.off('value');
    
    this.activeRoomRef = null;
    this.listeners = {};
  }

  /**
   * Helper to ensure Firebase Compat libraries are loaded dynamically
   * @private
   */
  _ensureFirebaseLoaded() {
    return new Promise((resolve, reject) => {
      if (typeof firebase !== 'undefined' && firebase.database) {
        return resolve();
      }

      console.log('[NbhEditor SDK] Loading Firebase libraries dynamically...');
      const scriptApp = document.createElement('script');
      scriptApp.src = 'https://www.gstatic.com/firebasejs/10.8.0/firebase-app-compat.js';
      scriptApp.onload = () => {
        const scriptDb = document.createElement('script');
        scriptDb.src = 'https://www.gstatic.com/firebasejs/10.8.0/firebase-database-compat.js';
        scriptDb.onload = () => resolve();
        scriptDb.onerror = (e) => reject(new Error('Failed to load Firebase Database library.'));
        document.head.appendChild(scriptDb);
      };
      scriptApp.onerror = (e) => reject(new Error('Failed to load Firebase App library.'));
      document.head.appendChild(scriptApp);
    });
  }

  /**
   * Helper to initialize the Firebase instance
   * @private
   */
  _initFirebase(databaseUrl) {
    if (firebase.apps.length > 0) {
      // Re-use existing app instance
      this.firebaseApp = firebase.app();
      this.firebaseDb = firebase.database();
      return;
    }

    const firebaseConfig = {
      databaseURL: databaseUrl
    };

    console.log('[NbhEditor SDK] Initializing Firebase client App with database:', databaseUrl);
    this.firebaseApp = firebase.initializeApp(firebaseConfig);
    this.firebaseDb = firebase.database();
  }
}

// Export module for Node environment if applicable, otherwise expose globally in window
if (typeof module !== 'undefined' && module.exports) {
  module.exports = NbhEditorClient;
} else {
  window.NbhEditorClient = NbhEditorClient;
}
