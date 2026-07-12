const https = require('https');

exports.handler = async (event) => {
  console.log('Function called, method:', event.httpMethod);
  console.log('Has DEEPGRAM_KEY:', !!process.env.DEEPGRAM_KEY);
  console.log('Has ASSEMBLYAI_KEY:', !!process.env.ASSEMBLYAI_KEY);

  if (event.httpMethod !== 'POST') {
    return { statusCode: 405, body: 'Method Not Allowed' };
  }

  const lang = event.headers['x-language'] || 'en';
  const audioData = event.isBase64Encoded
    ? Buffer.from(event.body, 'base64')
    : Buffer.from(event.body || '', 'utf8');

  console.log('Audio size:', audioData.length, 'lang:', lang);

  // جرّب Deepgram
  try {
    const text = await callDeepgram(audioData, lang);
    console.log('Deepgram result:', text);
    if (text && text.trim()) {
      return {
        statusCode: 200,
        body: JSON.stringify({ text, engine: 'deepgram' })
      };
    }
  } catch (e) {
    console.log('Deepgram error:', e.message);
  }

  // جرّب AssemblyAI
  try {
    const text = await callAssemblyAI(audioData);
    console.log('AssemblyAI result:', text);
    if (text && text.trim()) {
      return {
        statusCode: 200,
        body: JSON.stringify({ text, engine: 'assemblyai' })
      };
    }
  } catch (e) {
    console.log('AssemblyAI error:', e.message);
  }

  return {
    statusCode: 200,
    body: JSON.stringify({ text: '', engine: 'none', error: 'No result' })
  };
};

function callDeepgram(audio, lang) {
  return new Promise((resolve, reject) => {
    if (!process.env.DEEPGRAM_KEY) { reject(new Error('No DEEPGRAM_KEY')); return; }
    const opts = {
      hostname: 'api.deepgram.com',
      path: `/v1/listen?language=${lang}&punctuate=true&model=nova-2`,
      method: 'POST',
      headers: {
        'Authorization': `Token ${process.env.DEEPGRAM_KEY}`,
        'Content-Type': 'audio/raw',
        'Content-Length': audio.length
      }
    };
    const req = https.request(opts, res => {
      let data = '';
      res.on('data', c => data += c);
      res.on('end', () => {
        try {
          const j = JSON.parse(data);
          const t = j.results?.channels?.[0]?.alternatives?.[0]?.transcript || '';
          resolve(t);
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
    if (!process.env.ASSEMBLYAI_KEY) { reject(new Error('No ASSEMBLYAI_KEY')); return; }
    // AssemblyAI يحتاج upload أولاً ثم transcribe
    // للتبسيط نستخدم Deepgram فقط الآن
    reject(new Error('AssemblyAI async not supported in this mode'));
  });
}
