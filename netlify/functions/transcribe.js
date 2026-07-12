const https = require('https');

exports.handler = async (event) => {
  if (event.httpMethod !== 'POST') {
    return { statusCode: 405, body: 'Method Not Allowed' };
  }

  const audioBuffer = Buffer.from(event.body, 'base64');
  const lang = event.headers['x-language'] || 'en';

  // جرّب Speechmatics أولاً
  try {
    const result = await callSpeechmatics(audioBuffer, lang);
    if (result) return { statusCode: 200, body: JSON.stringify({ text: result, engine: 'speechmatics' }) };
  } catch (e) { console.log('Speechmatics failed:', e.message); }

  // ثاني: AssemblyAI
  try {
    const result = await callAssemblyAI(audioBuffer);
    if (result) return { statusCode: 200, body: JSON.stringify({ text: result, engine: 'assemblyai' }) };
  } catch (e) { console.log('AssemblyAI failed:', e.message); }

  // ثالث: Deepgram
  try {
    const result = await callDeepgram(audioBuffer, lang);
    if (result) return { statusCode: 200, body: JSON.stringify({ text: result, engine: 'deepgram' }) };
  } catch (e) { console.log('Deepgram failed:', e.message); }

  return { statusCode: 500, body: JSON.stringify({ error: 'All engines failed' }) };
};

function callDeepgram(audio, lang) {
  return new Promise((resolve, reject) => {
    const options = {
      hostname: 'api.deepgram.com',
      path: `/v1/listen?language=${lang}&punctuate=true`,
      method: 'POST',
      headers: {
        'Authorization': `Token ${process.env.DEEPGRAM_KEY}`,
        'Content-Type': 'audio/raw',
        'Content-Length': audio.length
      }
    };
    const req = https.request(options, res => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try {
          const j = JSON.parse(data);
          resolve(j.results.channels[0].alternatives[0].transcript);
        } catch (e) { reject(e); }
      });
    });
    req.on('error', reject);
    req.write(audio);
    req.end();
  });
}

function callAssemblyAI(audio) {
  return new Promise((resolve, reject) => {
    const options = {
      hostname: 'api.assemblyai.com',
      path: '/v2/stream',
      method: 'POST',
      headers: {
        'Authorization': process.env.ASSEMBLYAI_KEY,
        'Content-Type': 'application/octet-stream',
        'Transfer-Encoding': 'chunked'
      }
    };
    const req = https.request(options, res => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try {
          const j = JSON.parse(data);
          resolve(j.text || '');
        } catch (e) { reject(e); }
      });
    });
    req.on('error', reject);
    req.write(audio);
    req.end();
  });
}

function callSpeechmatics(audio, lang) {
  return new Promise((resolve, reject) => {
    const options = {
      hostname: 'asr.api.speechmatics.com',
      path: `/v2/jobs`,
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${process.env.SPEECHMATICS_KEY}`,
        'Content-Type': 'application/json'
      }
    };
    // Speechmatics async — للـ real-time نستخدم WebSocket
    // هنا نرجع null ونترك Deepgram يشتغل
    resolve(null);
  });
}
