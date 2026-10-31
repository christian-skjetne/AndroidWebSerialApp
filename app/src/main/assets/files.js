(() => {
    'use strict';
    if (window !== window.top || !window.AndroidFiles) return;
    const transport = window.AndroidFiles;
    const pending = new Map();
    const CHUNK = 16384;
    let sequence = 0;

    function rpc(method, args = {}) {
        return new Promise((resolve, reject) => {
            const id = ++sequence;
            pending.set(id, { resolve, reject });
            try { transport.postMessage(JSON.stringify({ id, method, ...args })); }
            catch (error) { pending.delete(id); reject(error); }
        });
    }
    transport.onmessage = event => {
        const message = JSON.parse(event.data);
        const task = pending.get(message.id);
        if (!task) return;
        pending.delete(message.id);
        if (message.error) task.reject(new DOMException(message.error.message, message.error.name));
        else task.resolve(message.result);
    };

    function activation() {
        if (!navigator.userActivation?.isActive) {
            throw new DOMException('A user gesture is required.', 'SecurityError');
        }
    }
    function position(value) {
        if (!Number.isSafeInteger(value) || value < 0) throw new TypeError('Invalid file position.');
        return value;
    }
    function name(value) {
        if (typeof value !== 'string' || !value || ['.', '..'].includes(value) || /[/\\\0]/.test(value)) {
            throw new TypeError('Invalid child name.');
        }
        return value;
    }
    function mode(options) {
        const value = options.mode ?? 'read';
        if (!['read', 'readwrite'].includes(value)) throw new TypeError('Invalid permission mode.');
        return value;
    }

    class FileSystemHandle {
        constructor(info) { this._info = info; this.kind = info.kind; this.name = info.name; }
        async isSameEntry(other) {
            return other instanceof FileSystemHandle && this._info.key === other._info.key;
        }
        async queryPermission(options = {}) {
            return mode(options) === 'read' || this._info.writable ? 'granted' : 'prompt';
        }
        async requestPermission(options = {}) {
            if (await this.queryPermission(options) === 'granted') return 'granted';
            activation();
            const granted = await rpc('permission', { handle: this._info.id });
            this._info.writable = granted;
            return granted ? 'granted' : 'denied';
        }
        async _requireWrite() {
            if (!this._info.writable && await this.requestPermission({ mode: 'readwrite' }) !== 'granted') {
                throw new DOMException('Write permission denied.', 'NotAllowedError');
            }
        }
    }

    class FileSystemWritableFileStream extends WritableStream {
        constructor(token) {
            const send = (method, args = {}) => rpc(method, { writer: token, ...args });
            const writeData = async data => {
                if (typeof data !== 'string' && !(data instanceof Blob) &&
                        !(data instanceof ArrayBuffer) && !ArrayBuffer.isView(data)) {
                    throw new TypeError('Writes require a string, Blob, or BufferSource.');
                }
                const blob = data instanceof Blob ? data : new Blob([data]);
                for (let offset = 0; offset < blob.size; offset += CHUNK) {
                    const bytes = new Uint8Array(await blob.slice(offset, offset + CHUNK).arrayBuffer());
                    await send('write', { data: btoa(String.fromCharCode(...bytes)) });
                }
            };
            const write = async data => {
                if (data && typeof data === 'object' && 'type' in data && !(data instanceof Blob)) {
                    switch (data.type) {
                        case 'seek': return send('seek', { position: position(data.position) });
                        case 'truncate': return send('truncate', { position: position(data.size) });
                        case 'write':
                            if (data.position !== undefined) await send('seek', { position: position(data.position) });
                            if (data.data === undefined) throw new TypeError('Write command requires data.');
                            return writeData(data.data);
                        default: throw new TypeError('Invalid write command.');
                    }
                }
                await writeData(data);
            };
            super({
                write: async data => {
                    try { await write(data); }
                    catch (error) { await send('abort'); throw error; }
                },
                close: () => send('commit'),
                abort: () => send('abort')
            });
        }
        async write(data) {
            const writer = this.getWriter();
            try { await writer.write(data); } finally { writer.releaseLock(); }
        }
        seek(offset) { return this.write({ type: 'seek', position: offset }); }
        truncate(size) { return this.write({ type: 'truncate', size }); }
    }

    class FileSystemFileHandle extends FileSystemHandle {
        async getFile() {
            const info = await rpc('stat', { handle: this._info.id });
            const reader = await rpc('openRead', { handle: this._info.id });
            const parts = [];
            try {
                for (;;) {
                    const data = await rpc('read', { reader });
                    if (data === null) break;
                    parts.push(Uint8Array.from(atob(data), character => character.charCodeAt(0)));
                }
            } finally { await rpc('closeRead', { reader }); }
            return new File(parts, info.name, { type: info.type, lastModified: info.lastModified });
        }
        async createWritable(options = {}) {
            if (options.mode !== undefined && options.mode !== 'exclusive') {
                throw new DOMException('Only exclusive writers are supported.', 'NotSupportedError');
            }
            await this._requireWrite();
            return new FileSystemWritableFileStream(await rpc('openWrite', {
                handle: this._info.id, keepExistingData: !!options.keepExistingData
            }));
        }
    }

    function handle(info) {
        return info.kind === 'directory' ? new FileSystemDirectoryHandle(info) : new FileSystemFileHandle(info);
    }

    class FileSystemDirectoryHandle extends FileSystemHandle {
        async *entries() {
            for (const info of await rpc('entries', { handle: this._info.id })) yield [info.name, handle(info)];
        }
        async *keys() { for await (const [key] of this.entries()) yield key; }
        async *values() { for await (const [, value] of this.entries()) yield value; }
        [Symbol.asyncIterator]() { return this.entries(); }
        async _child(childName, kind, options) {
            name(childName);
            if (options.create) await this._requireWrite();
            const child = handle(await rpc('child', {
                handle: this._info.id, name: childName, kind, create: !!options.create
            }));
            if (child.kind !== kind) throw new DOMException('Entry has a different kind.', 'TypeMismatchError');
            return child;
        }
        getFileHandle(childName, options = {}) { return this._child(childName, 'file', options); }
        getDirectoryHandle(childName, options = {}) { return this._child(childName, 'directory', options); }
        async removeEntry(childName, options = {}) {
            if (options.recursive) throw new DOMException('Recursive deletion is not supported.', 'NotSupportedError');
            name(childName);
            await this._requireWrite();
            await rpc('remove', { handle: this._info.id, name: childName });
        }
        async resolve(possibleDescendant) {
            if (await this.isSameEntry(possibleDescendant)) return [];
            // Resolve by walking only the directory the user selected, never arbitrary URI paths.
            for await (const [childName, child] of this.entries()) {
                if (await child.isSameEntry(possibleDescendant)) return [childName];
                if (child.kind === 'directory') {
                    const rest = await child.resolve(possibleDescendant);
                    if (rest !== null) return [childName, ...rest];
                }
            }
            return null;
        }
    }

    function pickerOptions(options) {
        const mimeTypes = [];
        for (const type of options.types ?? []) {
            for (const [mime, extensions] of Object.entries(type.accept ?? {})) {
                if (!/^[\w!#$&^.+-]+\/[\w!#$&^.+*-]+$/.test(mime)) throw new TypeError('Invalid MIME type.');
                for (const extension of typeof extensions === 'string' ? [extensions] : extensions) {
                    if (!/^\.[\w.+-]+$/.test(extension)) throw new TypeError('Invalid file extension.');
                }
                mimeTypes.push(mime);
            }
        }
        if (options.excludeAcceptAllOption && !mimeTypes.length) throw new TypeError('At least one file type is required.');
        return { mimeTypes, mime: mimeTypes.length === 1 ? mimeTypes[0] : '*/*',
            suggestedName: options.suggestedName ?? 'download' };
    }
    async function showOpenFilePicker(options = {}) {
        activation();
        return (await rpc('pickOpen', { ...pickerOptions(options), multiple: !!options.multiple })).map(handle);
    }
    async function showSaveFilePicker(options = {}) {
        activation();
        return handle(await rpc('pickSave', pickerOptions(options)));
    }
    async function showDirectoryPicker(options = {}) {
        activation();
        return handle(await rpc('pickDirectory', { mode: mode(options) }));
    }
    for (const [key, value] of Object.entries({ showOpenFilePicker, showSaveFilePicker, showDirectoryPicker,
        FileSystemHandle, FileSystemFileHandle, FileSystemDirectoryHandle, FileSystemWritableFileStream })) {
        // Android WebView can expose picker functions that always reject instead of opening a picker.
        if (key.startsWith('show') || !(key in window)) {
            Object.defineProperty(window, key, { value, configurable: true, writable: true });
        }
    }

    function report(error) {
        if (error.name !== 'AbortError') {
            console.error('File operation failed:', error);
            transport.postMessage(JSON.stringify({ method: 'notify', message: 'File operation failed: ' + error.message }));
        }
    }
    function download(url, suggestedName) {
        if (/^(blob:|data:)/i.test(url)) saveDownload(url, suggestedName).catch(report);
        else transport.postMessage(JSON.stringify({ method: 'download', url, name: suggestedName }));
    }
    async function saveDownload(url, suggestedName) {
        // Start fetching before showing the picker, so a page may revoke its blob URL after clicking.
        const fetched = fetch(url).then(response => {
            if (!response.ok) throw new Error('Download returned HTTP ' + response.status);
            return response.blob();
        });
        // Attach rejection handling immediately, including while the native picker is still open.
        const data = fetched.then(blob => ({ blob }), error => ({ error }));
        const file = handle(await rpc('pickSave', { suggestedName: suggestedName || 'download', mime: '*/*' }));
        const result = await data;
        if (result.error) throw result.error;
        const writer = await file.createWritable();
        await writer.write(result.blob);
        await writer.close();
    }
    async function directoryInput(input) {
        const directory = await showDirectoryPicker();
        const transfer = new DataTransfer();
        async function collect(folder, prefix) {
            for await (const [childName, child] of folder) {
                if (child.kind === 'directory') await collect(child, prefix + childName + '/');
                else {
                    const file = await child.getFile();
                    Object.defineProperty(file, 'webkitRelativePath', { value: prefix + childName });
                    transfer.items.add(file);
                }
            }
        }
        await collect(directory, directory.name + '/');
        input.files = transfer.files;
        input.dispatchEvent(new Event('input', { bubbles: true }));
        input.dispatchEvent(new Event('change', { bubbles: true }));
    }
    if (typeof document !== 'undefined') {
        document.addEventListener('click', event => {
            const target = event.target?.closest?.('a, input[type="file"]');
            if (!target || event.defaultPrevented) return;
            if (target.tagName === 'INPUT' && target.webkitdirectory) {
                event.preventDefault();
                directoryInput(target).catch(report);
            } else if (target.tagName === 'A' && (target.hasAttribute('download') || /^(blob:|data:)/i.test(target.href))) {
                event.preventDefault();
                download(target.href, target.download);
            }
        });
        const original = HTMLInputElement.prototype.showPicker;
        if (original) HTMLInputElement.prototype.showPicker = function () {
            if (this.type === 'file' && this.webkitdirectory) directoryInput(this).catch(report);
            else return original.call(this);
        };
    }
})();
