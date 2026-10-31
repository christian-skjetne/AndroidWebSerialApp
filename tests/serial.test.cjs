const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync('app/src/main/assets/serial.js', 'utf8');

function setup(handler = () => null, active = true) {
    const calls = [];
    const bridge = { postMessage(raw) {
        const message = JSON.parse(raw);
        calls.push(message);
        Promise.resolve().then(() => handler(message)).then(result => {
            bridge.onmessage({ data: JSON.stringify({ id: message.id, result }) });
        }, error => bridge.onmessage({ data: JSON.stringify({ id: message.id,
            error: { name: error.name, message: error.message } }) }));
    } };
    const context = { navigator: { userActivation: { isActive: active } }, AndroidSerial: bridge,
        EventTarget, Event, DOMException, ReadableStream, WritableStream, Uint8Array, ArrayBuffer, atob, btoa };
    context.window = context;
    context.top = context;
    vm.runInNewContext(source, context);
    return { serial: context.navigator.serial, bridge, calls };
}
const info = { id: '1:0', usbVendorId: 0x1234, usbProductId: 0x5678 };

test('installs before page code, preserves identity, and validates filters/activation', async () => {
    const { serial, calls } = setup(message => message.method === 'getPorts' ? [info] : info);
    const port = await serial.requestPort();
    assert.equal((await serial.getPorts())[0], port);
    assert.equal(port.getInfo().usbVendorId, 0x1234);
    await assert.rejects(serial.requestPort({ filters: [{ usbProductId: 1 }] }), { name: 'TypeError' });
    assert.equal(calls.length, 2);
    await assert.rejects(setup(undefined, false).serial.requestPort(), { name: 'SecurityError' });
});

test('opens, reads binary, writes view offsets, rejects locked close, then closes', async () => {
    const { serial, calls } = setup(message => {
        if (message.method === 'requestPort') return info;
        if (message.method === 'read') return btoa(String.fromCharCode(0, 128, 255));
        return null;
    });
    const port = await serial.requestPort();
    await assert.rejects(port.open({ baudRate: 0 }), { name: 'TypeError' });
    await port.open({ baudRate: 115200 });
    const reader = port.readable.getReader();
    assert.deepEqual(Array.from((await reader.read()).value), [0, 128, 255]);
    await assert.rejects(port.close(), { name: 'InvalidStateError' });
    await reader.cancel();
    reader.releaseLock();
    const writer = port.writable.getWriter();
    await writer.write(new Uint8Array([9, 0, 255, 9]).subarray(1, 3));
    assert.equal(calls.find(call => call.method === 'write').data, 'AP8=');
    await writer.close();
    writer.releaseLock();
    await port.close();
    assert.equal(port.readable, null);
    assert.equal(port.writable, null);
});

test('native permission errors surface as DOMException and detach emits port identity', async () => {
    const denied = setup(() => { throw new DOMException('Denied', 'NotAllowedError'); });
    await assert.rejects(denied.serial.requestPort(), { name: 'NotAllowedError' });
    const { serial, bridge } = setup(() => info);
    const port = await serial.requestPort();
    let disconnected;
    serial.addEventListener('disconnect', event => { disconnected = event.port; });
    bridge.onmessage({ data: JSON.stringify({ event: 'disconnect', portId: info.id }) });
    assert.equal(disconnected, port);
    assert.equal(port.connected, false);
});

test('cancelling an in-flight read stops polling and permits reopening', async () => {
    let completeRead;
    const { serial, calls } = setup(message => {
        if (message.method === 'requestPort') return info;
        if (message.method === 'read') return new Promise(resolve => { completeRead = resolve; });
        return null;
    });
    const port = await serial.requestPort();
    await port.open({ baudRate: 9600 });
    const reader = port.readable.getReader();
    const reading = reader.read();
    await new Promise(resolve => setImmediate(resolve));
    await reader.cancel();
    assert.equal((await reading).done, true);
    reader.releaseLock();
    completeRead('');
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(calls.filter(call => call.method === 'read').length, 1);
    await port.close();
    await port.open({ baudRate: 19200 });
    await port.close();
});

test('writes large buffers in bounded chunks and exposes signal operations', async () => {
    const { serial, calls } = setup(message => {
        if (message.method === 'requestPort') return info;
        if (message.method === 'getSignals') return { clearToSend: true };
        return null;
    });
    const port = await serial.requestPort();
    await assert.rejects(port.getSignals(), { name: 'InvalidStateError' });
    await port.open({ baudRate: 115200, flowControl: 'hardware' });
    const writer = port.writable.getWriter();
    await writer.write(new Uint8Array(40000));
    writer.releaseLock();
    assert.deepEqual(calls.filter(call => call.method === 'write').map(call => atob(call.data).length), [16384, 16384, 7232]);
    await port.setSignals({ dataTerminalReady: true, break: false });
    assert.equal((await port.getSignals()).clearToSend, true);
    await port.close();
    await port.forget();
    assert.equal(calls.at(-1).method, 'forget');
});

test('a late read reply cannot invalidate streams after close and reopen', async () => {
    let completeRead;
    const { serial } = setup(message => {
        if (message.method === 'requestPort') return info;
        if (message.method === 'read') return new Promise(resolve => { completeRead = resolve; });
        return null;
    });
    const port = await serial.requestPort();
    await port.open({ baudRate: 9600 });
    const reader = port.readable.getReader();
    const reading = reader.read();
    const released = assert.rejects(reading, { name: 'TypeError' });
    await new Promise(resolve => setImmediate(resolve));
    reader.releaseLock();
    await released;
    await port.close();
    await port.open({ baudRate: 19200 });
    const newReadable = port.readable;
    completeRead('AA==');
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(port.readable, newReadable);
    await port.close();
});