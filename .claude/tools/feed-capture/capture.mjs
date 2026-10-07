// Records the /ws feed to a JSONL file, one {t, m} line per message (t = receive time, epoch ms).
//
// Usage: node capture.mjs <tokenFile> <outFile> <seconds> [wsUrl]
//   tokenFile  file holding an access token from POST /api/auth/login
//   wsUrl      defaults to ws://localhost:8080/ws
//
// Node 22+ (built-in WebSocket). Summarize the output with feed_stats.py.
import fs from 'node:fs';

const [tokenFile, outFile, secs, wsUrl = 'ws://localhost:8080/ws'] = process.argv.slice(2);
const token = fs.readFileSync(tokenFile, 'utf8').trim();
const out = fs.createWriteStream(outFile);
const ws = new WebSocket(`${wsUrl}?token=${encodeURIComponent(token)}`);

let n = 0;
ws.onopen = () => console.log('open');
ws.onmessage = (e) => { n++; out.write(JSON.stringify({ t: Date.now(), m: JSON.parse(e.data) }) + '\n'); };
ws.onclose = (e) => console.log('close', e.code, e.reason);
ws.onerror = (e) => console.log('error', e.message);

setTimeout(() => {
    console.log('messages', n);
    ws.close();
    out.end();
    setTimeout(() => process.exit(0), 200);
}, Number(secs) * 1000);
