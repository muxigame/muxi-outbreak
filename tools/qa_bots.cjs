'use strict';
// Scripted protocol players for integration testing, not a graphical client or a gameplay agent.
const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '..');
const mc = require(path.resolve(root, '../better-mc-remake/artifacts/uid-nickname-qa/protocol-test/node_modules/minecraft-protocol'));
const folder = path.join(root, 'build/qa-server');
const events = path.join(folder, 'bot-events.jsonl');
const actions = path.join(folder, 'bot-actions.jsonl');
fs.writeFileSync(events, ''); fs.writeFileSync(actions, '');
const clients = new Map();
function emit(kind, name, value) {
  fs.appendFileSync(events, JSON.stringify({time: Date.now(), kind, name, value}) + '\n');
}
function connect(name) {
  const c = mc.createClient({host: '127.0.0.1', port: 25683, username: name, auth: 'offline',
    version: '1.21.1', profilesFolder: path.join(folder, 'bot-profiles'), hideErrors: false});
  clients.set(name, c);
  c.on('state', (next, previous) => emit('state', name, {next, previous}));
  c.on('connect', () => emit('connect', name, {}));
  c.on('packet', (packet, meta) => {
    if (c.state !== 'play') emit('configuration_packet', name, {packet: meta.name,
      channel: packet.channel, data: Buffer.isBuffer(packet.data) ? packet.data.toString('hex').slice(0,400) : undefined});
  });
  // NeoForge uses a configuration ping as the vanilla-capability negotiation barrier.
  c.on('ping', p => c.write('pong', {id: p.id}));
  c.on('login', p => { c.qaEntityId = p.entityId; emit('login', name, {uuid: c.uuid, entityId: p.entityId}); });
  c.on('position', p => {
    c.qaPosition = p;
    c.write('teleport_confirm', {teleportId: p.teleportId});
    c.write('position_look', {x: p.x, y: p.y, z: p.z, yaw: p.yaw, pitch: p.pitch, onGround: true});
    emit('position', name, p);
  });
  c.on('systemChat', p => emit('system', name, p));
  c.on('kick_disconnect', p => emit('kick', name, p));
  c.on('disconnect', p => emit('disconnect', name, p));
  c.on('error', e => emit('error', name, e.message));
  c.on('end', reason => emit('end', name, reason));
  c.on('entity_velocity', p => { if (p.entityId === c.qaEntityId) emit('velocity', name, p); });
}
for (const name of ['OutbreakQA', 'OutbreakQB']) connect(name);
let consumed = 0;
setInterval(() => {
  const lines = fs.readFileSync(actions, 'utf8').split('\n').filter(Boolean);
  while (consumed < lines.length) {
    const a = JSON.parse(lines[consumed++]);
    const c = clients.get(a.name);
    try {
      if (a.action === 'reconnect') { connect(a.name); continue; }
      if (!c) throw new Error('unknown bot');
      if (a.action === 'chat') c.chat(a.text);
      else if (a.action === 'sneak') c.write('entity_action', {entityId: c.qaEntityId, actionId: a.value ? 0 : 1, jumpBoost: 0});
      else if (a.action === 'disconnect') c.end();
      else if (a.action === 'drop') c.write('block_dig', {status: 4, location: {x: 0, y: 0, z: 0}, face: 0, sequence: 0});
      else if (a.action === 'move') {
        c.write('position', {x: a.x, y: a.y, z: a.z, onGround: true});
      }
      emit('action', a.name, a);
    } catch (e) { emit('action_error', a.name, e.message); }
  }
}, 100);
process.on('SIGTERM', () => { for (const c of clients.values()) c.end(); process.exit(0); });
