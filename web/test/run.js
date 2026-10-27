/* Starts the real local relay before running the integration suite. */
const { spawn } = require('node:child_process');
const net = require('node:net');
const path = require('node:path');

function waitForPort(port, timeoutMs = 10000) {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + timeoutMs;
    const attempt = () => {
      const socket = net.connect(port, '127.0.0.1');
      socket.once('connect', () => { socket.destroy(); resolve(); });
      socket.once('error', () => {
        socket.destroy();
        if (Date.now() >= deadline) reject(new Error(`Server did not open port ${port}`));
        else setTimeout(attempt, 100);
      });
    };
    attempt();
  });
}

(async () => {
  const server = spawn(process.execPath, ['server.js'], {
    cwd: path.resolve(__dirname, '..'),
    env: { ...process.env, GHOSTLINK_TEST: '1' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  server.stdout.on('data', data => { output += data; });
  server.stderr.on('data', data => { output += data; });
  try {
    await Promise.race([waitForPort(8088), new Promise((_, reject) => server.once('exit', code => reject(new Error(`Server exited (${code}): ${output}`))))]);
    const test = spawn(process.execPath, ['--test', 'test/ghostlink.test.js'], {
      cwd: path.resolve(__dirname, '..'), stdio: 'inherit',
    });
    const code = await new Promise(resolve => test.once('exit', resolve));
    process.exitCode = code || 0;
  } catch (error) {
    console.error(error.message, output);
    process.exitCode = 1;
  } finally {
    server.kill();
  }
})();
