const { chromium } = require('/opt/node22/lib/node_modules/playwright');
(async () => {
  const fps = 30, dur = 25, dir = __dirname; // node render.js  →  frames/, lalu ffmpeg -framerate 30 -i frames/f%04d.jpg -c:v libx264 -pix_fmt yuv420p out.mp4
  const only = process.argv[2]; // optional: list of seconds for preview stills
  const b = await chromium.launch({ args: ['--no-proxy-server'] });
  const p = await b.newPage({ viewport: { width: 1080, height: 1920 } });
  await p.goto('file://' + dir + '/promo.html');
  await p.evaluate(() => document.fonts.ready);
  const times = only ? only.split(',').map(Number) : Array.from({ length: fps * dur }, (_, i) => i / fps);
  require('fs').mkdirSync(dir + '/frames', { recursive: true });
  for (let i = 0; i < times.length; i++) {
    await p.evaluate(t => window.seek(t), times[i]);
    const name = only ? `still_${times[i]}.jpg` : `frames/f${String(i).padStart(4, '0')}.jpg`;
    await p.screenshot({ path: dir + '/' + name, type: 'jpeg', quality: 92 });
  }
  await b.close();
})();
