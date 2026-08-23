// ⚠️ محاولة تجريبية - راجع الشرح تحت قبل ما تحكم على النتيجة
//
// السبب اللي يخلي هذا احتمال فشل عالي:
// Vercel Node.js Functions تشتغل بنموذج "استدعاء واحد → جواب واحد → تنتهي العملية"
// (شبيه AWS Lambda من تحت). WebSocket handshake يحتاج نتحكم بالـ TCP socket الخام
// ونخليه مفتوح لمدة الجلسة كاملة (دقايق) - وهذا يتعارض مع الطبيعة القصيرة والمحدودة
// لاستدعاءات Vercel Functions (مهلة زمنية قصوى حسب خطتك: ~10 ثانية Hobby، أطول شوي Pro).
//
// سيناريوهات النتيجة المتوقعة:
// 1) فشل فوري بالـ handshake نفسه (الأرجح) → test_ws.js يطلع "error" مباشرة
// 2) الاتصال ينفتح لكن ينقطع خلال ثواني قليلة (~10s) → يأكد حد المهلة الزمنية
// 3) يفضل شغال أطول من المتوقع → مفاجأة إيجابية، لازم اختبار حقيقي أطول قبل الاعتماد عليه

const { WebSocketServer, WebSocket } = require('ws');
const crypto = require('crypto');

const DEEPGRAM_PRO_API_KEY  = process.env.DEEPGRAM_PRO_API_KEY;
const DEEPGRAM_PLUS_API_KEY = process.env.DEEPGRAM_PLUS_API_KEY;
const APP_SECRET             = process.env.APP_SECRET;

const wss = new WebSocketServer({ noServer: true });

module.exports = (req, res) => {
  if (!req.headers.upgrade || req.headers.upgrade.toLowerCase() !== 'websocket') {
    res.statusCode = 426;
    res.setHeader('Content-Type', 'text/plain');
    res.end('This endpoint only accepts WebSocket upgrade requests (Upgrade: websocket header missing).');
    return;
  }

  if (!DEEPGRAM_PRO_API_KEY || !DEEPGRAM_PLUS_API_KEY || !APP_SECRET) {
    console.error('❌ متغيرات بيئة ناقصة');
    res.statusCode = 500;
    res.end('Server misconfigured');
    return;
  }

  try {
    // محاولة الوصول للـ socket الخام - قد لا يكون متاح بنفس الطريقة زي Node.js server تقليدي
    wss.handleUpgrade(req, req.socket, Buffer.alloc(0), (clientWs) => {
      handleConnection(clientWs, req);
    });
  } catch (err) {
    console.error('❌ فشل الـ WebSocket upgrade:', err.message);
    res.statusCode = 500;
    res.end('WebSocket upgrade failed: ' + err.message);
  }
};

function handleConnection(clientWs, req) {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const tier   = url.searchParams.get('tier');
  const lang   = url.searchParams.get('lang') || 'en';
  const secret = url.searchParams.get('secret');

  if (secret !== APP_SECRET) {
    clientWs.close(4001, 'unauthorized');
    return;
  }

  const apiKey = tier === 'pro' ? DEEPGRAM_PRO_API_KEY : DEEPGRAM_PLUS_API_KEY;
  if (!apiKey) {
    clientWs.close(4002, 'invalid tier');
    return;
  }

  const dgUrl =
    'wss://api.deepgram.com/v1/listen' +
    '?encoding=linear16&sample_rate=16000&channels=1' +
    '&punctuate=true&interim_results=true&utterance_end_ms=1000' +
    '&model=nova-2&vad_events=true&smart_format=true' +
    `&language=${encodeURIComponent(lang)}`;

  const dgWs = new WebSocket(dgUrl, { headers: { Authorization: `Token ${apiKey}` } });

  let dgReady = false;
  const pendingAudio = [];

  dgWs.on('open', () => {
    dgReady = true;
    while (pendingAudio.length) dgWs.send(pendingAudio.shift());
    console.log(`✅ Deepgram connected (tier=${tier}, lang=${lang})`);
  });

  dgWs.on('message', (data) => {
    if (clientWs.readyState === WebSocket.OPEN) clientWs.send(data);
  });

  dgWs.on('close', () => {
    if (clientWs.readyState === WebSocket.OPEN) clientWs.close();
  });

  dgWs.on('error', (err) => {
    console.error('Deepgram error:', err.message);
    if (clientWs.readyState === WebSocket.OPEN) clientWs.close();
  });

  clientWs.on('message', (data) => {
    if (dgReady) dgWs.send(data);
    else pendingAudio.push(data);
  });

  clientWs.on('close', () => {
    if (dgWs.readyState === WebSocket.OPEN || dgWs.readyState === WebSocket.CONNECTING) {
      dgWs.close();
    }
  });

  clientWs.on('error', () => {
    if (dgWs.readyState === WebSocket.OPEN || dgWs.readyState === WebSocket.CONNECTING) {
      dgWs.close();
    }
  });
}
