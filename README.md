# Android Web Serial

A native Android app that opens an HTTPS website in a WebView and supplies
`navigator.serial` through Android USB Host APIs. Websites using the supported
Web Serial methods do not need to import a library or change their transport.

## Run

Requirements: Android 8.0+ (API 26), a current Android System WebView / Chrome,
USB host/OTG support, a data-capable OTG cable, and a supported USB serial device.
Some adapters need a powered USB hub.

1. Open this directory in Android Studio, or install JDK 17+ and Android SDK 35
   with Build Tools 35.0.0. Set `ANDROID_HOME` to your SDK directory, or configure
   `sdk.dir` in an untracked `local.properties` file.
2. Build on Windows: `.\gradlew.bat :app:assembleDebug`. On macOS/Linux:
   `sh gradlew :app:assembleDebug`.
3. Install `app/build/outputs/apk/debug/app-debug.apk` on the phone, or run
   `adb install -r app/build/outputs/apk/debug/app-debug.apk` with USB debugging
   enabled. No root is needed.
4. Launch **Web Serial**. The built-in terminal is the initial page. Connect
   the adapter, tap **Connect**, choose a serial port, and allow Android's USB
   permission request.
5. Enter your HTTPS website in the native address bar and tap the mint arrow.
   Use the website's normal connect action. The terminal icon returns to the
   built-in terminal; the circular arrow reloads the page.

The address is remembered. USB grants are scoped to the current WebView/site
session, not persisted. Changing sites or restarting the app requires selection
again. Reloading the same WebView retains grants for `getPorts()`, but closes
active ports. Rotation recreates the activity and closes the connection.

## App Controls

The native app UI, dialogs, and built-in terminal share a dark slate theme with
mint accents, rounded controls, and consistent outline icons. External websites
retain their own styling. Toolbar captions identify the most common actions;
the terminal groups connection settings, received data, and transmission into
separate panels with a connection status indicator.
The launcher icon combines a terminal prompt and USB connector in mint on dark
slate. It adapts to launcher shapes and supports Android 13+ themed icons.

The mint arrow beside the address opens the entered URL. The second row has:

- **Reload:** reload the current page, closing active serial ports.
- **Terminal:** open the built-in USB serial terminal.
- **Save:** save the current page as a bookmark. A filled mint bookmark indicates it is
  already saved; saving the same URL again does not create a duplicate.
- **Saved:** open saved pages. Use the trash button beside an entry
  to remove it. Bookmarks remain saved across app restarts.
- **Hide:** hide the address bar, toolbar, and status line without reloading
  the page or closing the serial connection. A small down-arrow button remains
  at the top right to restore the controls. Android Back also restores them
  before navigating back. The collapsed state is remembered across restarts.

Long-press any toolbar icon to see its label. Bookmarks save the loaded page's
title and URL, not unsubmitted text in the address bar. Opening a bookmark uses
the same HTTPS origin checks and USB selection rules as opening an address.

## Files and Downloads

Webpages can open files, save logs/images, and select folders using Android's
system document picker. Downloads is the suggested starting location; other
locations supported by the system picker also work. On recent Android versions,
the Downloads root itself cannot be granted as a folder. Create and select a
subfolder such as **Downloads/WebSerial** instead.

Supported in the selected site's top-level page:

- Ordinary file inputs, including multiple files and `webkitdirectory` folder
  inputs (click or `showPicker()`).
- `showOpenFilePicker()`, `showSaveFilePicker()`, and `showDirectoryPicker()`.
- File handles with `getFile()`, permission queries/requests, and
  `createWritable()`. Writable streams support strings, blobs, binary buffers,
  `pipeTo()`, seek, truncate, close, and abort.
- Directory iteration, child file/folder lookup and creation, `resolve()`,
  `isSameEntry()`, and deletion of individual files.
- HTTPS downloads and download links, plus generated blob/data-URL links such
  as `.ubx` logs and graph images. Each download opens a save picker.

Choose a folder with `{ mode: 'readwrite' }` when a site needs to create logs
inside it. Files opened for reading require an additional native confirmation
before the site can change them. The document provider must also permit writing.
Cancelling a picker rejects its promise with `AbortError`; other failures reject
the operation or show a download error notification.

File access is limited to your selections, including descendants of a selected
folder. Handles are session-only: navigation, reload, rotation, or closing the
app invalidates them. Handles cannot be persisted in IndexedDB or passed to
workers. Recursive/folder deletion, synchronous access handles, concurrent
writers to the same file, and full browser File System Access compatibility
are not supported. Picker `startIn`/`id` hints are not implemented; MIME filters
are advisory and extension-only filtering depends on the Android provider.

Writes are staged in the app cache and copied to the selected document on
`close()`; `abort()` discards the staged bytes. Saving a new file can still leave
an empty document if cancelled after selection. Document providers do not
guarantee atomic replacement, so a failed final copy can leave a partial file.
`getFile()` builds a snapshot in page memory, and directory inputs snapshot the
selected files; avoid selecting very large files/folder trees on low-memory
devices. Long-running logging must close the writer to save its data and keep
the app/page open. Blob/data downloads are handled through links, not arbitrary
blob navigations or popup windows.

## Implementation

`MainActivity` creates a WebView with an exact HTTPS origin allowlist. AndroidX
`addDocumentStartJavaScript` installs the facade before website scripts execute.
`addWebMessageListener` passes JSON requests and responses to `SerialBridge`,
which handles a native port chooser, Android USB permissions, and background
USB reads/writes using **usb-serial-for-android 3.11.0**.
An independent origin-scoped `FileBridge` and document-start `files.js` facade
connect file requests to Android's Storage Access Framework. No broad storage
permission is requested. File data crosses the bridge in 16 KiB chunks, and
document I/O runs on a separate executor from USB traffic.

Binary chunks are base64 encoded, limited to 16 KiB per bridge message, and
exposed as `Uint8Array` through Web Streams. Writes are acknowledged after the
driver completes them. Reads run on a separate executor with a 200 ms timeout;
cancelled streams stop polling. This is intended for interactive device tools,
not guaranteed lossless sustained high-throughput capture.

### API Coverage

- `navigator.serial.requestPort({ filters })` with USB vendor/product filters,
  a JavaScript user activation check, and an always-visible native chooser.
- `navigator.serial.getPorts()`, stable port identity, and `port.getInfo()`.
- `port.open({ baudRate, dataBits, stopBits, parity, flowControl, bufferSize })`.
  Supports 7/8 data bits, 1/2 stop bits, none/even/odd parity, and none/hardware
  RTS/CTS flow control when supported by the driver. `bufferSize` is validated;
  bridge chunk size remains fixed, rather than mapping it to a USB driver buffer.
- `port.readable` / `port.writable`, `close()`, `forget()`, `setSignals()`
  (DTR, RTS, break), and `getSignals()` (CTS, DSR, DCD, RI).
- USB detach errors the streams and emits `disconnect` on the port and serial
  manager. Reconnect requires `requestPort()` again; automatic `connect` event
  delivery is not implemented.

This is a compatibility subset, not a browser-complete Web Serial implementation.
BYOB readers, workers, Bluetooth ports, browser permission persistence, embedded
frame access, and background service connections are not implemented. Unsupported
driver operations reject with `NotSupportedError`. Sites requiring these features
or running Web Serial inside a worker need adaptation. Some sites may reject the
Android user agent independently of API availability.

### Security

Only the explicitly selected HTTPS origin receives the native transport, and
only its top-level frame can use it. Cross-origin top-level navigation is blocked;
open another origin explicitly from the address bar. This can affect cross-origin
sign-in flows. File/content access, cleartext traffic, and mixed content are
disabled. TLS errors retain WebView's default rejection. Navigating closes open
ports and invalidates outstanding replies. USB access is not granted merely
because Android already authorized the device: the website still gets a native
port chooser. `forget()` removes the site's grant, not Android's OS permission.

Only open sites you trust: once you select a port, scripts on that origin can
read from and write to that device. The local terminal runs at the reserved
`https://appassets.androidplatform.net` asset origin. Do not host untrusted pages
under that origin. This app does not expose a remote server or change Chrome's
global Web Serial implementation.

## Existing Projects

- [mik3y/usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android):
  the actual USB driver dependency. Supports CDC/ACM, FTDI, CP210x, CH34x, PL2303,
  and other supported drivers. Custom VID/PID devices may need a custom prober.
- [kai-morich/SimpleUsbTerminal](https://github.com/kai-morich/SimpleUsbTerminal):
  a useful reference for an Android USB terminal and background-service designs.
- [google/web-serial-polyfill](https://github.com/google/web-serial-polyfill):
  an existing Web Serial-over-WebUSB implementation, archived in January 2025.
  It requires WebUSB and is not a drop-in transport for Android WebView; this app
  uses Android USB Host instead. No source from that project is vendored here.
- [AndroidX WebViewCompat](https://developer.android.com/reference/androidx/webkit/WebViewCompat):
  the document-start injection and origin-scoped message APIs used by this app.

## Verification

```powershell
node --test tests/serial.test.cjs
node --test tests/files.test.cjs
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

The Node tests simulate the Android message transport. They do not prove real USB
behavior. Hardware acceptance: test your target website with a real OTG adapter,
permission denial, chooser cancellation, filtered selection, send/receive,
disconnect while reading/writing, close/reopen, reload, site switching, and any
device-specific DTR/RTS resets or hardware flow control. A loopback adapter with
TX/RX joined is useful for verifying binary transfers.

File acceptance: test a normal file input, multiple selection, a folder input,
all three modern pickers, picker cancellation, read-only providers, and a
Downloads subfolder with read/write access. Save and reopen a binary `.ubx` log,
export a blob/data-URL graph, test an HTTPS download, and verify seek/truncate,
abort, permission denial, reload, and site switching. Node tests simulate the
file message transport; they do not prove provider-specific behavior.

## TODO:

- rotating screen reloads page, and disconnects the device.