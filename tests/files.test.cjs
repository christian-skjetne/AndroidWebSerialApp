const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const { File } = require('node:buffer');
const source = fs.readFileSync('app/src/main/assets/files.js', 'utf8');
const fileInfo = { id: 'file', key: 'provider:file', name: 'test.ubx', kind: 'file',
    type: 'application/octet-stream', writable: true, lastModified: 123 };
const folderInfo = { id: 'folder', key: 'provider:folder', name: 'WebSerial', kind: 'directory', writable: true };

function setup(handler = () => null, active = true, extras = {}) {
    const calls = [];
    const bridge = { postMessage(raw) {
        const message = JSON.parse(raw);
        calls.push(message);
        Promise.resolve().then(() => handler(message)).then(result => {
            bridge.onmessage({ data: JSON.stringify({ id: message.id, result }) });
        }, error => bridge.onmessage({ data: JSON.stringify({ id: message.id,
            error: { name: error.name, message: error.message } }) }));
    } };
    const context = { navigator: { userActivation: { isActive: active } }, AndroidFiles: bridge,
        DOMException, ReadableStream, WritableStream, Blob, File, Uint8Array, ArrayBuffer,
        atob, btoa, console, Event, ...extras };
    context.window = context;
    context.top = context;
    vm.runInNewContext(source, context);
    return { api: context, bridge, calls };
}

test('pickers validate activation, modes, types, and preserve native picker errors', async () => {
    const { api, calls } = setup(message => {
        if (message.method === 'pickOpen') return [fileInfo];
        if (message.method === 'pickDirectory') return folderInfo;
        return fileInfo;
    });
    const [file] = await api.showOpenFilePicker({ multiple: true,
        types: [{ accept: { 'application/octet-stream': ['.ubx'] } }] });
    assert.equal(file.name, 'test.ubx');
    assert.equal(file.kind, 'file');
    assert.equal(calls[0].multiple, true);
    assert.deepEqual(calls[0].mimeTypes, ['application/octet-stream']);
    assert.equal((await api.showSaveFilePicker({ suggestedName: 'log.ubx' })).kind, 'file');
    assert.equal(calls[1].suggestedName, 'log.ubx');
    assert.equal((await api.showDirectoryPicker({ mode: 'readwrite' })).kind, 'directory');
    await assert.rejects(api.showDirectoryPicker({ mode: 'invalid' }), { name: 'TypeError' });
    await assert.rejects(api.showOpenFilePicker({ types: [{ accept: { invalid: ['.ubx'] } }] }), { name: 'TypeError' });
    await assert.rejects(api.showOpenFilePicker({ excludeAcceptAllOption: true }), { name: 'TypeError' });
    await assert.rejects(setup(undefined, false).api.showSaveFilePicker(), { name: 'SecurityError' });
    await assert.rejects(setup(() => { throw new DOMException('Cancelled', 'AbortError'); })
        .api.showOpenFilePicker(), { name: 'AbortError' });
});

test('getFile returns exact binary data and metadata, and closes readers on failure', async () => {
    let read = 0;
    const { api, calls } = setup(message => {
        if (message.method === 'pickOpen') return [fileInfo];
        if (message.method === 'stat') return fileInfo;
        if (message.method === 'openRead') return 'reader';
        if (message.method === 'read') return read++ === 0 ? 'AID/' : null;
        return null;
    });
    const [handle] = await api.showOpenFilePicker();
    const file = await handle.getFile();
    assert.equal(file.name, 'test.ubx');
    assert.equal(file.lastModified, 123);
    assert.deepEqual([...new Uint8Array(await file.arrayBuffer())], [0, 128, 255]);
    assert.equal(calls.at(-1).method, 'closeRead');
    const failed = setup(message => {
        if (message.method === 'pickOpen') return [fileInfo];
        if (message.method === 'stat') return fileInfo;
        if (message.method === 'read') throw new DOMException('Read failed', 'NotReadableError');
        return 'reader';
    });
    await assert.rejects((await failed.api.showOpenFilePicker())[0].getFile(), { name: 'NotReadableError' });
    assert.equal(failed.calls.at(-1).method, 'closeRead');
});

test('writes bounded binary chunks, view offsets, strings, blobs, seeks, and truncates before commit', async () => {
    const { api, calls } = setup(message => message.method === 'pickSave' ? fileInfo : 'writer');
    const handle = await api.showSaveFilePicker();
    const writable = await handle.createWritable({ keepExistingData: true });
    await writable.write(new Uint8Array(40000));
    assert.deepEqual(calls.filter(call => call.method === 'write').map(call => atob(call.data).length), [16384, 16384, 7232]);
    await writable.write(new Uint8Array([9, 0, 255, 9]).subarray(1, 3));
    assert.equal(calls.at(-1).data, 'AP8=');
    await writable.write('hello');
    assert.equal(atob(calls.at(-1).data), 'hello');
    await writable.write(new Blob(['blob']));
    assert.equal(atob(calls.at(-1).data), 'blob');
    await writable.write({ type: 'write', position: 4, data: 'x' });
    assert.equal(calls.at(-2).method, 'seek');
    assert.equal(calls.at(-2).position, 4);
    await writable.seek(2);
    await writable.truncate(1);
    assert.equal(calls.at(-1).method, 'truncate');
    assert.equal(calls.at(-1).position, 1);
    assert.equal(calls.find(call => call.method === 'openWrite').keepExistingData, true);
    await writable.close();
    assert.equal(calls.at(-1).method, 'commit');
});

test('writers support pipeTo and abort; invalid writes clean up instead of committing', async () => {
    const { api, calls } = setup(message => message.method === 'pickSave' ? fileInfo : 'writer');
    const handle = await api.showSaveFilePicker();
    await new Blob(['log']).stream().pipeTo(await handle.createWritable());
    assert.equal(calls.at(-1).method, 'commit');
    await (await handle.createWritable()).abort();
    assert.equal(calls.at(-1).method, 'abort');
    const invalid = await handle.createWritable();
    await assert.rejects(invalid.seek(-1), { name: 'TypeError' });
    assert.equal(calls.at(-1).method, 'abort');
    const failure = setup(message => {
        if (message.method === 'pickSave') return fileInfo;
        if (message.method === 'write') throw new DOMException('Disk full', 'UnknownError');
        return 'writer';
    });
    const stream = await (await failure.api.showSaveFilePicker()).createWritable();
    await assert.rejects(stream.write('data'), { name: 'UnknownError' });
    assert.equal(failure.calls.at(-1).method, 'abort');
});

test('read-only file handles request explicit write permission and surface denial', async () => {
    const { api, calls } = setup(message => {
        if (message.method === 'pickOpen') return [{ ...fileInfo, writable: false }];
        if (message.method === 'permission') return false;
        return null;
    });
    const [handle] = await api.showOpenFilePicker();
    assert.equal(await handle.queryPermission(), 'granted');
    assert.equal(await handle.queryPermission({ mode: 'readwrite' }), 'prompt');
    await assert.rejects(handle.createWritable(), { name: 'NotAllowedError' });
    assert.equal(calls.at(-1).method, 'permission');
    const allowed = setup(message => {
        if (message.method === 'pickOpen') return [{ ...fileInfo, writable: false }];
        if (message.method === 'permission') return true;
        return 'writer';
    });
    const [writable] = await allowed.api.showOpenFilePicker();
    assert.equal(await writable.requestPermission({ mode: 'readwrite' }), 'granted');
    assert.equal(await writable.queryPermission({ mode: 'readwrite' }), 'granted');
    await (await writable.createWritable()).abort();
});

test('directories list, create, resolve, compare, and remove only valid children', async () => {
    const { api, calls } = setup(message => {
        if (message.method === 'pickDirectory') return folderInfo;
        if (message.method === 'entries') return [fileInfo];
        if (message.method === 'child') return fileInfo;
        return null;
    });
    const directory = await api.showDirectoryPicker({ mode: 'readwrite' });
    const entries = [];
    for await (const entry of directory) entries.push(entry);
    assert.equal(entries[0][0], 'test.ubx');
    const file = await directory.getFileHandle('test.ubx', { create: true });
    assert.equal(calls.at(-1).create, true);
    assert.equal(await file.isSameEntry(entries[0][1]), true);
    assert.deepEqual(Array.from(await directory.resolve(file)), ['test.ubx']);
    assert.deepEqual(Array.from(await directory.resolve(directory)), []);
    assert.equal(await directory.resolve({}), null);
    await assert.rejects(directory.getFileHandle('../escape'), { name: 'TypeError' });
    await assert.rejects(directory.getDirectoryHandle('test.ubx'), { name: 'TypeMismatchError' });
    await assert.rejects(directory.removeEntry('x', { recursive: true }), { name: 'NotSupportedError' });
    await directory.removeEntry('test.ubx');
    assert.equal(calls.at(-1).method, 'remove');
});

test('creating and removing children of read-only directories requires write permission', async () => {
    const { api, calls } = setup(message => {
        if (message.method === 'pickDirectory') return { ...folderInfo, writable: false };
        if (message.method === 'permission') return false;
        throw new Error('Must not modify a read-only folder.');
    });
    const directory = await api.showDirectoryPicker();
    await assert.rejects(directory.getFileHandle('log.ubx', { create: true }), { name: 'NotAllowedError' });
    await assert.rejects(directory.removeEntry('log.ubx'), { name: 'NotAllowedError' });
    assert.equal(calls.filter(call => call.method === 'permission').length, 2);
});

test('blob/data downloads and HTTPS download attributes route to native saving', async () => {
    let click;
    let committed;
    const done = new Promise(resolve => { committed = resolve; });
    const { calls } = setup(message => {
        if (message.method === 'pickSave') return fileInfo;
        if (message.method === 'commit') committed();
        return 'writer';
    }, true, {
        document: { addEventListener(type, listener) { if (type === 'click') click = listener; } },
        HTMLInputElement: class {},
        fetch: async () => ({ ok: true, blob: async () => new Blob(['binary log']) })
    });
    let prevented = false;
    function target(href, download) {
        const anchor = { tagName: 'A', href, download, hasAttribute: () => true };
        anchor.closest = () => anchor;
        return anchor;
    }
    click({ target: target('blob:https://example.com/log', 'log.ubx'),
        preventDefault() { prevented = true; } });
    await done;
    assert.equal(prevented, true);
    assert.equal(calls.find(call => call.method === 'pickSave').suggestedName, 'log.ubx');
    assert.equal(atob(calls.find(call => call.method === 'write').data), 'binary log');
    click({ target: target('https://example.com/log.ubx', 'log.ubx'), preventDefault() {} });
    assert.equal(calls.at(-1).method, 'download');
    assert.equal(calls.at(-1).url, 'https://example.com/log.ubx');
});

test('facade replaces unusable WebView picker stubs but never runs in embedded frames', () => {
    const nativePicker = () => {};
    assert.notEqual(setup(undefined, true, { showOpenFilePicker: nativePicker }).api.showOpenFilePicker, nativePicker);
    const context = { AndroidFiles: {}, top: {} };
    context.window = context;
    vm.runInNewContext(source, context);
    assert.equal(context.showSaveFilePicker, undefined);
});

test('directory inputs populate files with relative paths and dispatch input/change', async () => {
    let changed;
    const completed = new Promise(resolve => { changed = resolve; });
    const events = [];
    class Input {
        constructor() { this.type = 'file'; this.webkitdirectory = true; }
        showPicker() { throw new Error('Must use the native folder bridge.'); }
        dispatchEvent(event) {
            events.push(event.type);
            if (event.type === 'change') changed();
        }
    }
    class Transfer {
        constructor() { this.files = []; this.items = { add: file => this.files.push(file) }; }
    }
    let read = false;
    setup(message => {
        if (message.method === 'pickDirectory') return folderInfo;
        if (message.method === 'entries') return [fileInfo];
        if (message.method === 'stat') return fileInfo;
        if (message.method === 'openRead') return 'reader';
        if (message.method === 'read') {
            if (read) return null;
            read = true;
            return 'AID/';
        }
        return null;
    }, true, { document: { addEventListener() {} }, HTMLInputElement: Input, DataTransfer: Transfer });
    const input = new Input();
    input.showPicker();
    await completed;
    assert.deepEqual(events, ['input', 'change']);
    assert.equal(input.files[0].webkitRelativePath, 'WebSerial/test.ubx');
    assert.deepEqual([...new Uint8Array(await input.files[0].arrayBuffer())], [0, 128, 255]);
});
