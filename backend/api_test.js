const http = require('http');

const PORT = 3000;
const BASE_URL = `http://localhost:${PORT}`;

// Helper to make HTTP requests
function makeRequest(method, path, headers = {}, body = null) {
  return new Promise((resolve, reject) => {
    const options = {
      hostname: 'localhost',
      port: PORT,
      path: path,
      method: method,
      headers: {
        'Content-Type': 'application/json',
        ...headers
      }
    };

    const req = http.request(options, (res) => {
      let data = '';
      res.on('data', (chunk) => {
        data += chunk;
      });
      res.on('end', () => {
        try {
          const json = JSON.parse(data);
          resolve({ status: res.statusCode, data: json });
        } catch (e) {
          resolve({ status: res.statusCode, text: data });
        }
      });
    });

    req.on('error', (err) => {
      reject(err);
    });

    if (body) {
      req.write(JSON.stringify(body));
    }
    req.end();
  });
}

async function runTests() {
  console.log('====================================================');
  console.log('🧪 Starting NbhEditor API Integration Tests...');
  console.log('====================================================\n');

  try {
    let apiKey = null;
    let docId = null;
    let roomId = null;
    let sessionToken = null;

    // Test 1: Onboard developer and generate API Key
    console.log('Test 1: Generating Developer API Key...');
    const test1 = await makeRequest('POST', '/api/v1/dev/keys', {}, { developerName: 'SocialMediaNetworkApp' });
    if (test1.status === 201 && test1.data.apiKey) {
      apiKey = test1.data.apiKey;
      console.log(`✅ Success: API Key generated: ${apiKey}\n`);
    } else {
      throw new Error(`Failed Test 1: ${JSON.stringify(test1)}`);
    }

    // Test 2: Create a Document via API Key
    console.log('Test 2: Creating a Document...');
    const test2 = await makeRequest('POST', '/api/v1/documents', { 'x-api-key': apiKey }, {
      title: 'Project Roadmap Draft',
      content: '## NbhEditor Features\n- Rich Text\n- Live Collaboration'
    });
    if (test2.status === 201 && test2.data.id) {
      docId = test2.data.id;
      console.log(`✅ Success: Document created with ID: ${docId}\n`);
    } else {
      throw new Error(`Failed Test 2: ${JSON.stringify(test2)}`);
    }

    // Test 3: List all Documents
    console.log('Test 3: Listing Documents...');
    const test3 = await makeRequest('GET', '/api/v1/documents', { 'x-api-key': apiKey });
    if (test3.status === 200 && Array.isArray(test3.data)) {
      console.log(`✅ Success: Retrieved ${test3.data.length} documents.\n`);
    } else {
      throw new Error(`Failed Test 3: ${JSON.stringify(test3)}`);
    }

    // Test 4: Retrieve Document Details
    console.log('Test 4: Retrieving Document Details...');
    const test4 = await makeRequest('GET', `/api/v1/documents/${docId}`, { 'x-api-key': apiKey });
    if (test4.status === 200 && test4.data.id === docId) {
      console.log(`✅ Success: Document title is "${test4.data.title}"\n`);
    } else {
      throw new Error(`Failed Test 4: ${JSON.stringify(test4)}`);
    }

    // Test 5: Update Document Content
    console.log('Test 5: Updating Document Content...');
    const test5 = await makeRequest('PUT', `/api/v1/documents/${docId}`, { 'x-api-key': apiKey }, {
      title: 'Project Roadmap Final Draft',
      content: '## NbhEditor Features\n- Rich Text\n- Live Collaboration\n- Developer Web APIs!'
    });
    if (test5.status === 200 && test5.data.title === 'Project Roadmap Final Draft') {
      console.log(`✅ Success: Updated document successfully.\n`);
    } else {
      throw new Error(`Failed Test 5: ${JSON.stringify(test5)}`);
    }

    // Test 6: Create Collaborative Room
    console.log('Test 6: Creating Collaborative Session Room...');
    const test6 = await makeRequest('POST', '/api/v1/rooms', { 'x-api-key': apiKey }, {
      documentId: docId
    });
    if (test6.status === 201 && test6.data.id) {
      roomId = test6.data.id;
      console.log(`✅ Success: Room created! Room ID: ${roomId}, Session ID: ${test6.data.sessionId}\n`);
    } else {
      throw new Error(`Failed Test 6: ${JSON.stringify(test6)}`);
    }

    // Test 7: Generate Temporary Session Token (Server-to-Server flow)
    console.log('Test 7: Generating Temporary Client Session Token...');
    const test7 = await makeRequest('POST', '/api/v1/auth/session', { 'x-api-key': apiKey }, {
      userId: 'social_user_alice_77',
      userName: 'Alice Smith',
      roomId: roomId
    });
    if (test7.status === 201 && test7.data.sessionToken) {
      sessionToken = test7.data.sessionToken;
      console.log(`✅ Success: Session Token generated: ${sessionToken}\n`);
    } else {
      throw new Error(`Failed Test 7: ${JSON.stringify(test7)}`);
    }

    // Test 8: Verify temporary token auth by reading document
    console.log('Test 8: Reading document using Temporary Session Token...');
    const test8 = await makeRequest('GET', `/api/v1/documents/${docId}`, {
      'Authorization': `Bearer ${sessionToken}`
    });
    if (test8.status === 200) {
      console.log(`✅ Success: Token authorized successfully. Document title: "${test8.data.title}"\n`);
    } else {
      throw new Error(`Failed Test 8: ${JSON.stringify(test8)}`);
    }

    // Test 9: Export Document Draft to Social Media Feed Post
    console.log('Test 9: Exporting Document to Social Media Feed Post...');
    const test9 = await makeRequest('POST', '/api/v1/export/feed', { 'x-api-key': apiKey }, {
      documentId: docId,
      postText: 'Look at the new roadmap we just drafted inside NbhEditor!',
      customMetadata: { category: 'tech', tags: ['collaboration', 'api'] }
    });
    if (test9.status === 200 && test9.data.status === 'success') {
      console.log(`✅ Success: Exported to feed! Export ID: ${test9.data.exportId}\n`);
    } else {
      throw new Error(`Failed Test 9: ${JSON.stringify(test9)}`);
    }

    // Test 10: Export Document Draft to Direct Message (Chat)
    console.log('Test 10: Exporting Document to Chat DM...');
    const test10 = await makeRequest('POST', '/api/v1/export/chat', { 'x-api-key': apiKey }, {
      documentId: docId,
      chatId: 'social_group_chat_456',
      chatType: 'group',
      messageText: 'Hey everyone, check out this draft document.'
    });
    if (test10.status === 200 && test10.data.status === 'success') {
      console.log(`✅ Success: Document sent to chat! Message ID: ${test10.data.exportId}\n`);
    } else {
      throw new Error(`Failed Test 10: ${JSON.stringify(test10)}`);
    }

    // Test 11: Delete Document
    console.log('Test 11: Deleting Document...');
    const test11 = await makeRequest('DELETE', `/api/v1/documents/${docId}`, { 'x-api-key': apiKey });
    if (test11.status === 200) {
      console.log(`✅ Success: Document deleted.\n`);
    } else {
      throw new Error(`Failed Test 11: ${JSON.stringify(test11)}`);
    }

    console.log('====================================================');
    console.log('🎉 All 11 Integration Tests Passed Successfully! 🎉');
    console.log('====================================================');

  } catch (error) {
    console.error('\n❌ Test execution failed with error:', error);
    process.exit(1);
  }
}

// Check if running directly
if (require.main === module) {
  runTests();
}

module.exports = { runTests };
