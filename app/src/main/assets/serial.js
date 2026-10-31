(() => {
    'use strict';
    if (window !== window.top || !window.AndroidSerial) return;
    const transport = window.AndroidSerial;
    const pending = new Map();
    const ports = new Map();
    let sequence = 0;

    function rpc(method, args = {}) {
        return new Promise((resolve, reject) => {
            const id = ++sequence;
            pending.set(id, { resolve, reject });
            try { transport.postMessage(JSON.stringify({ id, method, ...args })); }
            catch (error) { pending.delete(id); reject(error); }
        });
    }

    function serialEvent(target, type, port) {
        const event = new Event(type);
        Object.defineProperty(event, 'port', { value: port });
        target.dispatchEvent(event);
        if (typeof target['on' + type] === 'function') target['on' + type](event);
    }

    class SerialPort extends EventTarget {
        constructor(info) {
            super();
            this._info = info;
            this._state = 'closed';
            this.readable = null;
            this.writable = null;
            this.connected = true;
            this.onconnect = null;
            this.ondisconnect = null;
        }
        getInfo() {
            return { usbVendorId: this._info.usbVendorId, usbProductId: this._info.usbProductId };
        }
        async open(options = {}) {
            if (this._state !== 'closed') throw new DOMException('Port is already open.', 'InvalidStateError');
            const config = { baudRate: options.baudRate, dataBits: options.dataBits ?? 8,
                stopBits: options.stopBits ?? 1, parity: options.parity ?? 'none',
                flowControl: options.flowControl ?? 'none', bufferSize: options.bufferSize ?? 255 };
            if (!Number.isInteger(config.baudRate) || config.baudRate <= 0 ||
                ![7, 8].includes(config.dataBits) || ![1, 2].includes(config.stopBits) ||
                !['none', 'even', 'odd'].includes(config.parity) ||
                !['none', 'hardware'].includes(config.flowControl) ||
                !Number.isInteger(config.bufferSize) || config.bufferSize <= 0 || config.bufferSize > 16777216) {
                throw new TypeError('Invalid serial options.');
            }
            this._state = 'opening';
            try { await rpc('open', { portId: this._info.id, options: config }); }
            catch (error) { this._state = 'closed'; throw error; }
            this._state = 'open';
            this.connected = true;
            let reading = true;
            this._stopReading = () => { reading = false; };
            this.readable = new ReadableStream({
                start: controller => { this._readController = controller; },
                pull: async controller => {
                    try {
                        while (reading && this._state === 'open') {
                            const encoded = await rpc('read', { portId: this._info.id });
                            if (!reading || this._state !== 'open') return;
                            if (encoded) {
                                controller.enqueue(Uint8Array.from(atob(encoded), character => character.charCodeAt(0)));
                                return;
                            }
                        }
                    } catch (error) {
                        if (reading && this._state === 'open') { this.readable = null; controller.error(error); }
                    }
                },
                cancel: () => { reading = false; this._readController = null; this.readable = null; }
            }, { highWaterMark: 0 });
            this.writable = new WritableStream({
                start: controller => { this._writeController = controller; },
                write: async chunk => {
                    if (!(chunk instanceof ArrayBuffer) && !ArrayBuffer.isView(chunk)) {
                        throw new TypeError('Serial writes require a BufferSource.');
                    }
                    const bytes = chunk instanceof ArrayBuffer ? new Uint8Array(chunk) :
                        new Uint8Array(chunk.buffer, chunk.byteOffset, chunk.byteLength);
                    for (let offset = 0; offset < bytes.length; offset += 16384) {
                        const data = btoa(String.fromCharCode(...bytes.subarray(offset, offset + 16384)));
                        await rpc('write', { portId: this._info.id, data });
                    }
                },
                close: () => { this.writable = null; },
                abort: () => { this.writable = null; }
            });
        }
        async close() {
            if (this._state !== 'open') throw new DOMException('Port is not open.', 'InvalidStateError');
            if (this.readable?.locked || this.writable?.locked) {
                throw new DOMException('Release stream locks before closing.', 'InvalidStateError');
            }
            this._state = 'closing';
            this._stopReading();
            try { await rpc('close', { portId: this._info.id }); }
            finally {
                try { this._readController?.close(); } catch (_) { }
                this.readable = null;
                this.writable = null;
                this._state = 'closed';
            }
        }
        async setSignals(signals = {}) {
            this._requireOpen();
            await rpc('setSignals', { portId: this._info.id, signals });
        }
        async getSignals() {
            this._requireOpen();
            return rpc('getSignals', { portId: this._info.id });
        }
        async forget() {
            if (this._state !== 'closed') throw new DOMException('Close the port first.', 'InvalidStateError');
            await rpc('forget', { portId: this._info.id });
            ports.delete(this._info.id);
        }
        _requireOpen() {
            if (this._state !== 'open') throw new DOMException('Port is not open.', 'InvalidStateError');
        }
        _disconnect() {
            this.connected = false;
            this._state = 'closed';
            this._stopReading?.();
            const error = new DOMException('USB device disconnected.', 'NetworkError');
            try { this._readController?.error(error); } catch (_) { }
            try { this._writeController?.error(error); } catch (_) { }
            this.readable = null;
            this.writable = null;
            serialEvent(this, 'disconnect', this);
        }
    }

    function portFor(info) {
        if (!ports.has(info.id)) ports.set(info.id, new SerialPort(info));
        return ports.get(info.id);
    }

    class Serial extends EventTarget {
        constructor() { super(); this.onconnect = null; this.ondisconnect = null; }
        async getPorts() { return (await rpc('getPorts')).map(portFor); }
        async requestPort(options = {}) {
            if (!navigator.userActivation?.isActive) {
                throw new DOMException('A user gesture is required.', 'SecurityError');
            }
            const filters = options.filters ?? [];
            if (!Array.isArray(filters)) throw new TypeError('filters must be an array.');
            for (const filter of filters) {
                for (const key of Object.keys(filter)) {
                    if (!['usbVendorId', 'usbProductId'].includes(key)) throw new TypeError('Only USB filters are supported.');
                    if (!Number.isInteger(filter[key]) || filter[key] < 0 || filter[key] > 65535) throw new TypeError('Invalid USB ID.');
                }
                if (filter.usbProductId !== undefined && filter.usbVendorId === undefined) {
                    throw new TypeError('usbProductId requires usbVendorId.');
                }
            }
            return portFor(await rpc('requestPort', { filters }));
        }
    }

    const serial = new Serial();
    transport.onmessage = event => {
        const message = JSON.parse(event.data);
        if (message.event === 'disconnect') {
            const port = ports.get(message.portId);
            if (port) { port._disconnect(); ports.delete(message.portId); serialEvent(serial, 'disconnect', port); }
            return;
        }
        if (message.event === 'connect') {
            const port = portFor(message.port);
            port.connected = true;
            serialEvent(port, 'connect', port);
            serialEvent(serial, 'connect', port);
            return;
        }
        const request = pending.get(message.id);
        if (!request) return;
        pending.delete(message.id);
        if (message.error) request.reject(new DOMException(message.error.message, message.error.name));
        else request.resolve(message.result);
    };
    Object.defineProperty(navigator, 'serial', { configurable: true, value: serial });
    Object.defineProperty(window, 'Serial', { configurable: true, value: Serial });
    Object.defineProperty(window, 'SerialPort', { configurable: true, value: SerialPort });
})();