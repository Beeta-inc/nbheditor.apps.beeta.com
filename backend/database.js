const fs = require('fs');
const path = require('path');
const { v4: uuidv4 } = require('uuid');

const DB_PATH = path.join(__dirname, 'database.json');

// Helper to read database
function readDb() {
  try {
    if (!fs.existsSync(DB_PATH)) {
      const initialDb = { 
        apiKeys: {
          "nbh_key_test_dev_123": {
            key: "nbh_key_test_dev_123",
            developerName: "Seeded Test Developer",
            createdAt: Date.now(),
            status: "active"
          }
        }, 
        documents: {}, 
        rooms: {}, 
        sessionTokens: {} 
      };
      fs.writeFileSync(DB_PATH, JSON.stringify(initialDb, null, 2), 'utf-8');
      return initialDb;
    }
    const data = fs.readFileSync(DB_PATH, 'utf-8');
    return JSON.parse(data);
  } catch (error) {
    console.error('Failed to read local database:', error);
    return { apiKeys: {}, documents: {}, rooms: {}, sessionTokens: {} };
  }
}

// Helper to write database atomically
function writeDb(db) {
  try {
    const tempPath = DB_PATH + '.tmp';
    fs.writeFileSync(tempPath, JSON.stringify(db, null, 2), 'utf-8');
    fs.renameSync(tempPath, DB_PATH);
    return true;
  } catch (error) {
    console.error('Failed to write local database:', error);
    return false;
  }
}

const Database = {
  // ─── API KEY MANAGEMENT ──────────────────────────────────────────────
  createApiKey(developerName) {
    const db = readDb();
    const newKey = `nbh_key_${uuidv4().replace(/-/g, '')}`;
    db.apiKeys[newKey] = {
      key: newKey,
      developerName: developerName,
      createdAt: Date.now(),
      status: 'active'
    };
    writeDb(db);
    return db.apiKeys[newKey];
  },

  validateApiKey(key) {
    if (!key) return null;
    const db = readDb();
    const apiKey = db.apiKeys[key];
    if (apiKey && apiKey.status === 'active') {
      return apiKey;
    }
    return null;
  },

  getApiKeys() {
    const db = readDb();
    return Object.values(db.apiKeys);
  },

  // ─── DOCUMENT CRUD OPERATIONS ────────────────────────────────────────
  createDocument(title, content, apiKey) {
    const db = readDb();
    const docId = `doc_${uuidv4().replace(/-/g, '')}`;
    const newDoc = {
      id: docId,
      title: title || 'Untitled Document',
      content: content || '',
      ownerApiKey: apiKey,
      createdAt: Date.now(),
      updatedAt: Date.now()
    };
    db.documents[docId] = newDoc;
    writeDb(db);
    return newDoc;
  },

  getDocument(docId, apiKey) {
    const db = readDb();
    const doc = db.documents[docId];
    if (doc && doc.ownerApiKey === apiKey) {
      return doc;
    }
    return null;
  },

  updateDocument(docId, title, content, apiKey) {
    const db = readDb();
    const doc = db.documents[docId];
    if (!doc || doc.ownerApiKey !== apiKey) {
      return null;
    }
    if (title !== undefined) doc.title = title;
    if (content !== undefined) doc.content = content;
    doc.updatedAt = Date.now();
    db.documents[docId] = doc;
    writeDb(db);
    return doc;
  },

  deleteDocument(docId, apiKey) {
    const db = readDb();
    const doc = db.documents[docId];
    if (!doc || doc.ownerApiKey !== apiKey) {
      return false;
    }
    delete db.documents[docId];
    writeDb(db);
    return true;
  },

  listDocuments(apiKey) {
    const db = readDb();
    return Object.values(db.documents).filter(doc => doc.ownerApiKey === apiKey);
  },

  // ─── COLLABORATIVE ROOMS ─────────────────────────────────────────────
  createRoom(documentId, apiKey, customSessionId = null) {
    const db = readDb();
    
    // Check if document exists and belongs to the API key
    const doc = db.documents[documentId];
    if (!doc || doc.ownerApiKey !== apiKey) {
      return null;
    }

    const roomId = `room_${uuidv4().replace(/-/g, '')}`;
    // Generate simple 7-char Firebase session ID (e.g. NEABC12) if not custom provided
    let sessionId = customSessionId;
    if (!sessionId) {
      const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
      const code = Array.from({ length: 5 }, () => chars[Math.floor(Math.random() * chars.length)]).join('');
      sessionId = `NE${code}`;
    }

    const newRoom = {
      id: roomId,
      documentId: documentId,
      ownerApiKey: apiKey,
      sessionId: sessionId,
      firebasePath: `collaborative_sessions/${sessionId}`,
      createdAt: Date.now(),
      status: 'active'
    };

    db.rooms[roomId] = newRoom;
    writeDb(db);
    return newRoom;
  },

  getRoom(roomId, apiKey) {
    const db = readDb();
    const room = db.rooms[roomId];
    if (room && room.ownerApiKey === apiKey) {
      return room;
    }
    return null;
  },

  findRoomBySessionId(sessionId) {
    const db = readDb();
    return Object.values(db.rooms).find(room => room.sessionId === sessionId) || null;
  },

  // ─── SESSION TOKEN (SDK CLIENT AUTH) ─────────────────────────────────
  createSessionToken(apiKey, userId, userName, roomId, durationMs = 3600000) {
    const db = readDb();
    
    // Validate key and room
    if (!db.apiKeys[apiKey] || !db.rooms[roomId]) return null;

    const token = `nbh_tok_${uuidv4().replace(/-/g, '')}`;
    const newToken = {
      token: token,
      apiKey: apiKey,
      userId: userId,
      userName: userName,
      roomId: roomId,
      expiresAt: Date.now() + durationMs
    };

    db.sessionTokens[token] = newToken;
    writeDb(db);
    return newToken;
  },

  validateSessionToken(token) {
    if (!token) return null;
    const db = readDb();
    const tokenObj = db.sessionTokens[token];
    if (tokenObj && tokenObj.expiresAt > Date.now()) {
      return tokenObj;
    }
    return null;
  }
};

module.exports = Database;
