const express = require('express');
const cors = require('cors');
const { v4: uuidv4 } = require('uuid');
const Database = require('./database');

const app = express();
const PORT = process.env.PORT || 3000;

// Enable CORS so the SDK can communicate with the server from any origin
app.use(cors());
app.use(express.json());
app.use(express.static(require('path').join(__dirname, '..')));

// Request logger
app.use((req, res, next) => {
  console.log(`[${new Date().toISOString()}] ${req.method} ${req.url}`);
  next();
});

// ─── AUTHENTICATION MIDDLEWARES ──────────────────────────────────────

// Authenticate via Developer API Key (Header: x-api-key)
function requireApiKey(req, res, next) {
  const apiKey = req.headers['x-api-key'];
  if (!apiKey) {
    return res.status(401).json({
      error: 'Unauthorized',
      message: 'Missing x-api-key header.'
    });
  }
  const developer = Database.validateApiKey(apiKey);
  if (!developer) {
    return res.status(403).json({
      error: 'Forbidden',
      message: 'Invalid or deactivated API key.'
    });
  }
  req.developer = developer;
  req.apiKey = apiKey;
  next();
}

// Authenticate via either Developer API Key or Temporary Session Token
function requireAuth(req, res, next) {
  const apiKey = req.headers['x-api-key'];
  const authHeader = req.headers['authorization'];
  
  // Try API Key first
  if (apiKey) {
    const developer = Database.validateApiKey(apiKey);
    if (developer) {
      req.authType = 'apikey';
      req.developer = developer;
      req.apiKey = apiKey;
      return next();
    }
  }

  // Try Session Token
  if (authHeader && authHeader.startsWith('Bearer ')) {
    const token = authHeader.substring(7);
    const tokenObj = Database.validateSessionToken(token);
    if (tokenObj) {
      req.authType = 'session';
      req.sessionTokenObj = tokenObj;
      req.apiKey = tokenObj.apiKey; // Associated developer key
      return next();
    }
  }

  return res.status(401).json({
    error: 'Unauthorized',
    message: 'Provide a valid x-api-key header or Bearer token.'
  });
}

// ─── DEVELOPER TOOL ROUTE (GENERATE KEYS) ────────────────────────────

// Create an API key for testing
app.post('/api/v1/dev/keys', (req, res) => {
  const { developerName } = req.body;
  if (!developerName) {
    return res.status(400).json({ error: 'Missing developerName in request body.' });
  }
  const keyObj = Database.createApiKey(developerName);
  res.status(201).json({
    message: 'API Key generated successfully.',
    developerName: keyObj.developerName,
    apiKey: keyObj.key,
    createdAt: keyObj.createdAt
  });
});

// List developer keys (for debugging/diagnostics)
app.get('/api/v1/dev/keys', (req, res) => {
  const keys = Database.getApiKeys();
  res.json(keys);
});

// ─── TEMPORARY SESSION TOKEN GENERATION (SERVER-TO-SERVER) ───────────

// Developer server generates a temporary token for client SDK instances
app.post('/api/v1/auth/session', requireApiKey, (req, res) => {
  const { userId, userName, roomId, durationSeconds } = req.body;
  if (!userId || !userName || !roomId) {
    return res.status(400).json({
      error: 'Bad Request',
      message: 'userId, userName, and roomId are required fields.'
    });
  }

  const durationMs = (durationSeconds || 3600) * 1000; // default 1 hour
  const tokenObj = Database.createSessionToken(req.apiKey, userId, userName, roomId, durationMs);
  
  if (!tokenObj) {
    return res.status(500).json({
      error: 'Internal Error',
      message: 'Failed to create session token. Ensure the roomId is valid.'
    });
  }

  res.status(201).json({
    sessionToken: tokenObj.token,
    expiresAt: tokenObj.expiresAt,
    roomId: tokenObj.roomId,
    userId: tokenObj.userId,
    userName: tokenObj.userName
  });
});

// ─── DOCUMENT CRUD ENDPOINTS ─────────────────────────────────────────

// Create Document
app.post('/api/v1/documents', requireAuth, (req, res) => {
  const { title, content } = req.body;
  const newDoc = Database.createDocument(title, content, req.apiKey);
  res.status(201).json(newDoc);
});

// List Documents
app.get('/api/v1/documents', requireAuth, (req, res) => {
  const docs = Database.listDocuments(req.apiKey);
  res.json(docs);
});

// Get Document Details
app.get('/api/v1/documents/:id', requireAuth, (req, res) => {
  const doc = Database.getDocument(req.params.id, req.apiKey);
  if (!doc) {
    return res.status(404).json({ error: 'Document not found or access denied.' });
  }
  res.json(doc);
});

// Update Document Content
app.put('/api/v1/documents/:id', requireAuth, (req, res) => {
  const { title, content } = req.body;
  const updatedDoc = Database.updateDocument(req.params.id, title, content, req.apiKey);
  if (!updatedDoc) {
    return res.status(404).json({ error: 'Document not found or access denied.' });
  }
  res.json(updatedDoc);
});

// Delete Document
app.delete('/api/v1/documents/:id', requireAuth, (req, res) => {
  const success = Database.deleteDocument(req.params.id, req.apiKey);
  if (!success) {
    return res.status(404).json({ error: 'Document not found or access denied.' });
  }
  res.json({ message: 'Document deleted successfully.', id: req.params.id });
});

// ─── COLLABORATIVE ROOMS ENDPOINTS ───────────────────────────────────

// Create Collaborative Room
app.post('/api/v1/rooms', requireAuth, (req, res) => {
  const { documentId, customSessionId } = req.body;
  if (!documentId) {
    return res.status(400).json({ error: 'documentId is required.' });
  }

  const room = Database.createRoom(documentId, req.apiKey, customSessionId);
  if (!room) {
    return res.status(404).json({ error: 'Document not found or access denied.' });
  }
  res.status(201).json(room);
});

// Get Room State
app.get('/api/v1/rooms/:id', requireAuth, (req, res) => {
  const room = Database.getRoom(req.params.id, req.apiKey);
  if (!room) {
    return res.status(404).json({ error: 'Room not found or access denied.' });
  }
  res.json(room);
});

// Join Room (yields Firebase connectivity metadata)
app.post('/api/v1/rooms/:id/join', requireAuth, (req, res) => {
  const room = Database.getRoom(req.params.id, req.apiKey);
  if (!room) {
    return res.status(404).json({ error: 'Room not found.' });
  }

  // Fetch document content to initialize or synchronize client
  const doc = Database.getDocument(room.documentId, req.apiKey);

  res.json({
    message: 'Successfully joined room.',
    roomId: room.id,
    sessionId: room.sessionId,
    firebasePath: room.firebasePath,
    firebaseDatabaseUrl: 'https://nbheditior-default-rtdb.firebaseio.com',
    document: {
      id: doc.id,
      title: doc.title,
      content: doc.content
    }
  });
});

// ─── EXPORTING CHANNELS (SOCIAL MEDIA MOCKS) ─────────────────────────

// Export draft to a Feed Post
app.post('/api/v1/export/feed', requireAuth, (req, res) => {
  const { documentId, postText, customMetadata } = req.body;
  if (!documentId) {
    return res.status(400).json({ error: 'documentId is required.' });
  }

  const doc = Database.getDocument(documentId, req.apiKey);
  if (!doc) {
    return res.status(404).json({ error: 'Document not found.' });
  }

  console.log(`[EXPORT FEED] Document "${doc.title}" exported!`);
  console.log(`[EXPORT FEED] Post text context: ${postText || 'N/A'}`);
  console.log(`[EXPORT FEED] Payload exported content:\n${doc.content}\n`);

  res.json({
    status: 'success',
    message: 'Draft successfully exported to social media Feed.',
    exportId: `feed_post_${uuidv4().replace(/-/g, '').substring(0, 10)}`,
    timestamp: Date.now(),
    payload: {
      documentId: doc.id,
      title: doc.title,
      content: doc.content,
      postText: postText,
      meta: customMetadata || {}
    }
  });
});

// Export draft to a Chat DM/Group Chat
app.post('/api/v1/export/chat', requireAuth, (req, res) => {
  const { documentId, chatId, chatType, messageText } = req.body;
  if (!documentId || !chatId || !chatType) {
    return res.status(400).json({
      error: 'Bad Request',
      message: 'documentId, chatId (user ID or group ID), and chatType ("dm" or "group") are required.'
    });
  }

  const doc = Database.getDocument(documentId, req.apiKey);
  if (!doc) {
    return res.status(404).json({ error: 'Document not found.' });
  }

  console.log(`[EXPORT CHAT] Document "${doc.title}" sent to ${chatType.toUpperCase()} ${chatId}`);
  console.log(`[EXPORT CHAT] Accompanying text: ${messageText || 'N/A'}`);

  res.json({
    status: 'success',
    message: `Document successfully sent to ${chatType} chat.`,
    exportId: `chat_msg_${uuidv4().replace(/-/g, '').substring(0, 10)}`,
    timestamp: Date.now(),
    payload: {
      documentId: doc.id,
      title: doc.title,
      chatId: chatId,
      chatType: chatType,
      messageText: messageText
    }
  });
});
// ─── NETUARK BRIDGE SDK PROXY ROUTES ────────────────────────────────

const NETUARK_API_KEY = 'AIzaSyAD0NdeeIAZhOU2qrGYzZWXLUkIsw2j5vA';
const NETUARK_PROJECT_ID = 'ntaf-754e1';

async function verifyNeTuArkUser(email) {
  const cleanEmail = email.trim().toLowerCase();
  const url = `https://firestore.googleapis.com/v1/projects/${NETUARK_PROJECT_ID}/databases/(default)/documents/users/${cleanEmail}?key=${NETUARK_API_KEY}`;
  const response = await fetch(url);
  if (!response.ok) {
    if (response.status === 404) {
      throw new Error(`No NeTuArk account found for "${email}".`);
    }
    const errText = await response.text();
    throw new Error(`Firestore query error: ${response.status} - ${errText}`);
  }
  const data = await response.json();
  return data;
}

async function checkChatExists(chatId) {
  const url = `https://firestore.googleapis.com/v1/projects/${NETUARK_PROJECT_ID}/databases/(default)/documents/chats/${chatId}?key=${NETUARK_API_KEY}`;
  const response = await fetch(url);
  return response.status === 200;
}

async function writeToFirestore(collectionPath, docId, fields) {
  const formattedFields = {};
  for (const [key, val] of Object.entries(fields)) {
    if (typeof val === 'string') {
      formattedFields[key] = { stringValue: val };
    } else if (typeof val === 'boolean') {
      formattedFields[key] = { booleanValue: val };
    } else if (typeof val === 'number') {
      formattedFields[key] = { doubleValue: val };
    } else if (val && typeof val === 'object' && val.hasOwnProperty('timestampValue')) {
      formattedFields[key] = val;
    } else if (Array.isArray(val)) {
      formattedFields[key] = {
        arrayValue: {
          values: val.map(item => ({ stringValue: String(item) }))
        }
      };
    }
  }

  let url = `https://firestore.googleapis.com/v1/projects/${NETUARK_PROJECT_ID}/databases/(default)/documents/${collectionPath}`;
  let method = 'POST';
  if (docId) {
    url += `/${docId}?key=${NETUARK_API_KEY}`;
    method = 'PATCH';
  } else {
    url += `?key=${NETUARK_API_KEY}`;
  }

  const response = await fetch(url, {
    method: method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fields: formattedFields })
  });

  if (!response.ok) {
    const errText = await response.text();
    throw new Error(`Firestore REST error: ${response.status} - ${errText}`);
  }
  return response.json();
}

async function updateFirestore(collectionPath, docId, fields) {
  const formattedFields = {};
  const queryParams = [];
  for (const [key, val] of Object.entries(fields)) {
    queryParams.push(`updateMask.fieldPaths=${key}`);
    if (typeof val === 'string') {
      formattedFields[key] = { stringValue: val };
    } else if (typeof val === 'boolean') {
      formattedFields[key] = { booleanValue: val };
    } else if (typeof val === 'number') {
      formattedFields[key] = { doubleValue: val };
    } else if (val && typeof val === 'object' && val.hasOwnProperty('timestampValue')) {
      formattedFields[key] = val;
    } else if (Array.isArray(val)) {
      formattedFields[key] = {
        arrayValue: {
          values: val.map(item => ({ stringValue: String(item) }))
        }
      };
    }
  }

  const url = `https://firestore.googleapis.com/v1/projects/${NETUARK_PROJECT_ID}/databases/(default)/documents/${collectionPath}/${docId}?${queryParams.join('&')}&key=${NETUARK_API_KEY}`;
  
  const response = await fetch(url, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fields: formattedFields })
  });

  if (!response.ok) {
    const errText = await response.text();
    throw new Error(`Firestore update error: ${response.status} - ${errText}`);
  }
  return response.json();
}

async function uploadToCloudinary(text, filename) {
  const blob = new Blob([text], { type: 'text/plain' });
  const fd = new FormData();
  fd.append('file', blob, filename);
  fd.append('upload_preset', 'ntabys');

  const uploadUrl = 'https://api.cloudinary.com/v1_1/dzgatckob/raw/upload';
  const response = await fetch(uploadUrl, {
    method: 'POST',
    body: fd
  });

  if (!response.ok) {
    const errText = await response.text();
    throw new Error(`Cloudinary upload failed: ${response.status} - ${errText}`);
  }

  const data = await response.json();
  return data.secure_url;
}

// REST Route to verify a user
app.post('/api/v1/netuark/verify', requireAuth, async (req, res) => {
  const { email } = req.body;
  if (!email) {
    return res.status(400).json({ error: 'email is required' });
  }
  try {
    const userData = await verifyNeTuArkUser(email);
    res.json({
      exists: true,
      email: email.trim().toLowerCase(),
      name: userData.fields?.name?.stringValue || email.split('@')[0]
    });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

// REST Route to send message
app.post('/api/v1/netuark/message', requireAuth, async (req, res) => {
  const { senderEmail, targetId, targetType, documentId, sendAsFile, senderName } = req.body;
  if (!senderEmail || !targetId || !targetType || !documentId) {
    return res.status(400).json({
      error: 'senderEmail, targetId, targetType, and documentId are required.'
    });
  }

  try {
    // 1. Get document content
    const doc = Database.getDocument(documentId, req.apiKey);
    if (!doc) {
      return res.status(404).json({ error: 'Document not found.' });
    }

    // 2. Validate sender exists
    await verifyNeTuArkUser(senderEmail);

    const ts = Date.now().toString(36);
    const rand = Math.random().toString(36).substring(2, 10);
    const messageId = `sdk_${ts}_${rand}`;
    const cleanTarget = targetId.trim().toLowerCase();

    let chatId = cleanTarget;
    if (targetType === 'dm') {
      chatId = [senderEmail.trim().toLowerCase(), cleanTarget].sort().join('_');
    }

    const msgData = {
      sender: senderEmail,
      senderName: senderName || senderEmail.split('@')[0],
      senderPhotoURL: '',
      timestamp: { timestampValue: new Date().toISOString() },
      read: false,
      _sdk: true,
      _sdkSource: 'nbheditor_android',
      _sdkVersion: '1.0.0'
    };

    let lastMsgPreview = 'Attachment';

    // 3. Send as file or text
    if (sendAsFile) {
      const filename = `${doc.title || 'untitled'}.txt`;
      const url = await uploadToCloudinary(doc.content || '', filename);
      msgData.fileUrl = url;
      msgData.fileName = filename;
      msgData.fileSize = (Buffer.byteLength(doc.content || '') / 1024 / 1024).toFixed(4) + ' MB';
      msgData.type = 'file';
      lastMsgPreview = '📁 File';
    } else {
      const textContent = `📄 NbhEditor Document: ${doc.title || 'Untitled'}\n\n${doc.content || ''}`;
      if (textContent.length > 4000) {
        return res.status(400).json({
          error: 'Document content is too long to send as text message. Please send as file instead.'
        });
      }
      msgData.text = textContent;
      lastMsgPreview = textContent.substring(0, 50).replace(/\n/g, ' ');
    }

    const path = targetType === 'group' ? `groups/${chatId}/messages` : `chats/${chatId}/messages`;

    // 4. If DM, ensure chat thread doc exists
    if (targetType === 'dm') {
      const threadExists = await checkChatExists(chatId);
      if (!threadExists) {
        await writeToFirestore('chats', chatId, {
          members: [senderEmail.trim().toLowerCase(), cleanTarget].sort(),
          lastMessage: '',
          lastTimestamp: { timestampValue: new Date().toISOString() }
        });
      }
      // Update chat last message
      await updateFirestore('chats', chatId, {
        lastMessage: lastMsgPreview,
        lastTimestamp: { timestampValue: new Date().toISOString() }
      }).catch(err => console.warn('Best-effort chat update failed:', err.message));
    } else {
      // Group best effort update
      await updateFirestore('groups', chatId, {
        lastMessage: `${msgData.senderName}: ${lastMsgPreview}`,
        lastTimestamp: { timestampValue: new Date().toISOString() }
      }).catch(err => console.warn('Best-effort group update failed:', err.message));
    }

    // 5. Write message
    await writeToFirestore(path, messageId, msgData);

    res.json({
      status: 'success',
      messageId: messageId,
      chatId: chatId,
      timestamp: Date.now()
    });

  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// REST Route to create a feed post
app.post('/api/v1/netuark/feed', requireAuth, async (req, res) => {
  const { senderEmail, documentId, postCaption, sendAsFile } = req.body;
  if (!senderEmail || !documentId) {
    return res.status(400).json({
      error: 'senderEmail and documentId are required.'
    });
  }

  try {
    const doc = Database.getDocument(documentId, req.apiKey);
    if (!doc) {
      return res.status(404).json({ error: 'Document not found.' });
    }

    await verifyNeTuArkUser(senderEmail);

    const postId = `sdk_${Date.now().toString(36)}_${Math.random().toString(36).substring(2, 10)}`;
    const postData = {
      author: senderEmail,
      timestamp: { timestampValue: new Date().toISOString() },
      likes: [],
      commentCount: 0,
      viewers: [],
      type: 'post',
      _sdk: true,
      _sdkSource: 'nbheditor_android',
      _sdkVersion: '1.0.0'
    };

    if (sendAsFile) {
      const filename = `${doc.title || 'untitled'}.txt`;
      const url = await uploadToCloudinary(doc.content || '', filename);
      postData.text = postCaption || '';
      postData.mediaUrl = url;
      postData.mediaType = 'file';
    } else {
      let text = postCaption ? `${postCaption.trim()}\n\n` : '';
      text += `📄 **${doc.title || 'Untitled'}**\n\`\`\`text\n${doc.content || ''}\n\`\`\``;
      if (text.length > 5000) {
        text = text.substring(0, 4950) + '\n... [Content truncated due to length limits]';
      }
      postData.text = text;
      postData.mediaUrl = '';
      postData.mediaType = '';
    }

    await writeToFirestore('posts', postId, postData);

    res.json({
      status: 'success',
      postId: postId,
      timestamp: Date.now()
    });

  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// Start listening
app.listen(PORT, () => {
  console.log(`====================================================`);
  console.log(`🚀 NbhEditor API Server running on port ${PORT}`);
  console.log(`📚 Local DB: ${require('path').join(__dirname, 'database.json')}`);
  console.log(`====================================================`);
});
