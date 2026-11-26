import { test } from 'node:test';
import assert from 'node:assert/strict';
import { findCommand, pathExts, spawnSpec, shellSpec, killTree, safePath, fileUri, pathFromFileUri, uploadName, isSamePath, extraBinDirs, expandHome } from '../src/platform.mjs';

const WIN_ENV = { PATH: 'C:\\Windows\\System32;"C:\\Program Files\\nodejs";C:\\Users\\me\\AppData\\Roaming\\npm', LOCALAPPDATA: 'C:\\Users\\me\\AppData\\Local', APPDATA: 'C:\\Users\\me\\AppData\\Roaming', ComSpec: 'C:\\Windows\\System32\\cmd.exe' };
const fakeFs = files => p => files.includes(p);

test('win32 findCommand: PATHEXT only, extensionless npm shim is skipped, .exe wins by PATHEXT order', () => {
  const env = WIN_ENV;
  const isFile = fakeFs(['C:\\Users\\me\\AppData\\Roaming\\npm\\agy', 'C:\\Users\\me\\AppData\\Roaming\\npm\\agy.cmd']);
  assert.equal(findCommand('agy', { platform: 'win32', env, home: 'C:\\Users\\me', isFile }), 'C:\\Users\\me\\AppData\\Roaming\\npm\\agy.cmd');
  const both = fakeFs(['C:\\Program Files\\nodejs\\agy.cmd', 'C:\\Program Files\\nodejs\\agy.exe']);
  assert.equal(findCommand('agy', { platform: 'win32', env, home: 'C:\\Users\\me', isFile: both }), 'C:\\Program Files\\nodejs\\agy.exe');
  assert.equal(findCommand('agy', { platform: 'win32', env, home: 'C:\\Users\\me', isFile: fakeFs(['C:\\Users\\me\\AppData\\Roaming\\npm\\agy']) }), null);
});

test('win32 findCommand: install dirs outside PATH, explicit extension and explicit path', () => {
  const home = 'C:\\Users\\me';
  const env = { ...WIN_ENV, PATH: 'C:\\Windows\\System32' };
  const isFile = fakeFs(['C:\\Users\\me\\AppData\\Local\\agy\\bin\\agy.exe', 'D:\\tools\\agy.cmd']);
  assert.equal(findCommand('agy', { platform: 'win32', env, home, isFile }), 'C:\\Users\\me\\AppData\\Local\\agy\\bin\\agy.exe');
  assert.equal(findCommand('agy.exe', { platform: 'win32', env, home, isFile }), 'C:\\Users\\me\\AppData\\Local\\agy\\bin\\agy.exe');
  assert.equal(findCommand('D:\\tools\\agy', { platform: 'win32', env, home, isFile }), 'D:\\tools\\agy.cmd');
  assert.equal(findCommand('nope', { platform: 'win32', env, home, isFile }), null);
  assert.deepEqual(pathExts({ PATHEXT: '.exe;.Cmd' }), ['.EXE', '.CMD']);
  assert.ok(extraBinDirs({ platform: 'win32', env: {}, home }).includes('C:\\Users\\me\\AppData\\Roaming\\npm'));
});

test('posix findCommand: PATH then install dirs; a name with a slash is checked as given', () => {
  const isFile = fakeFs(['/usr/bin/agy', '/home/u/.local/bin/other', '/abs/tool']);
  assert.equal(findCommand('agy', { platform: 'linux', env: { PATH: '/bin:/usr/bin' }, home: '/home/u', isFile }), '/usr/bin/agy');
  assert.equal(findCommand('other', { platform: 'linux', env: { PATH: '/bin' }, home: '/home/u', isFile }), '/home/u/.local/bin/other');
  assert.equal(findCommand('/abs/tool', { platform: 'linux', env: {}, isFile }), '/abs/tool');
  assert.equal(findCommand('/abs/none', { platform: 'linux', env: {}, isFile }), null);
  assert.equal(findCommand('missing', { platform: 'linux', env: { PATH: '/bin' }, home: '/home/u', isFile, extraDirs: [] }), null);
});

test('spawnSpec: .cmd goes through cmd.exe /d /s /c with a verbatim quoted line; exe and posix are untouched', () => {
  const s = spawnSpec('C:\\Users\\me\\AppData\\Roaming\\npm\\agy.cmd', ['acp', '--app-data-dir', 'antigravity'], { platform: 'win32', env: WIN_ENV });
  assert.equal(s.command, 'C:\\Windows\\System32\\cmd.exe');
  assert.deepEqual(s.args, ['/d', '/s', '/c', '""C:\\Users\\me\\AppData\\Roaming\\npm\\agy.cmd" "acp" "--app-data-dir" "antigravity""']);
  assert.equal(s.options.windowsVerbatimArguments, true);
  assert.equal(s.options.windowsHide, true);
  const sp = spawnSpec('C:\\Program Files\\x\\agy.BAT', ['a b'], { platform: 'win32', env: {} });
  assert.equal(sp.command, 'cmd.exe');
  assert.equal(sp.args[3], '""C:\\Program Files\\x\\agy.BAT" "a b""');
  const e = spawnSpec('C:\\x\\agy.exe', ['acp'], { platform: 'win32', env: WIN_ENV });
  assert.deepEqual([e.command, e.args, e.options], ['C:\\x\\agy.exe', ['acp'], { windowsHide: true }]);
  assert.deepEqual(spawnSpec('/usr/bin/agy', ['acp'], { platform: 'linux' }), { command: '/usr/bin/agy', args: ['acp'], options: {} });
  for (const bad of ['a"b', '%PATH%', 'a\nb']) assert.throws(() => spawnSpec('C:\\x\\agy.cmd', [bad], { platform: 'win32', env: {} }), /cmd\.exe/);
});

test('killTree: taskkill /T /F on Windows, SIGTERM elsewhere', () => {
  const calls = [];
  const spawnFn = (...a) => { calls.push(a); return { on() {} }; };
  killTree({ pid: 42, kill: () => assert.fail('no direct kill') }, { platform: 'win32', spawnFn });
  assert.deepEqual(calls[0].slice(0, 2), ['taskkill', ['/pid', '42', '/T', '/F']]);
  let sig = null;
  killTree({ pid: 7, kill: s => { sig = s; } }, { platform: 'linux', spawnFn });
  assert.equal(sig, 'SIGTERM'); assert.equal(calls.length, 1);
});

test('shellSpec: login shell on Unix; pwsh, else powershell.exe on Windows, UTF-8 and exit code kept', () => {
  assert.deepEqual(shellSpec('ls', { platform: 'linux', env: { SHELL: '/bin/zsh' } }), { command: '/bin/zsh', args: ['-lc', 'ls'], options: { detached: true } });
  assert.equal(shellSpec('ls', { platform: 'linux', env: {} }).command, '/bin/bash');
  const find = name => (name === 'pwsh' ? 'C:\\Program Files\\PowerShell\\7\\pwsh.exe' : null);
  const w = shellSpec('git status # c', { platform: 'win32', env: {}, find });
  assert.equal(w.command, 'C:\\Program Files\\PowerShell\\7\\pwsh.exe');
  assert.deepEqual(w.args.slice(0, 4), ['-NoLogo', '-NoProfile', '-NonInteractive', '-Command']);
  assert.match(w.args[4], /^\[Console\]::OutputEncoding=\[Text\.Encoding\]::UTF8/);
  assert.match(w.args[4], /\ngit status # c\nif \(\$null -ne \$LASTEXITCODE/);   // a trailing comment cannot swallow the exit-code line
  assert.equal(w.options.windowsHide, true); assert.equal(w.options.detached, undefined);
  assert.equal(shellSpec('dir', { platform: 'win32', env: {}, find: n => (n === 'powershell' ? 'C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe' : null) }).command, 'C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe');
  assert.equal(shellSpec('dir', { platform: 'win32', env: {}, find: () => null }).command, 'powershell.exe');
});

test('win32 safePath: home sandbox with drive letters, mixed slashes, /C:/ URL paths, file:// URIs, ~', () => {
  const home = 'C:\\Users\\me';
  const ok = (p, cwd) => safePath(p, { home, cwd, platform: 'win32' });
  assert.equal(ok('C:\\Users\\me\\proj\\a.txt'), 'C:\\Users\\me\\proj\\a.txt');
  assert.equal(ok('c:/users/ME/proj/a.txt'), 'c:\\users\\ME\\proj\\a.txt');          // drive letter and folder case do not matter
  assert.equal(ok('/C:/Users/me/proj/a.txt'), 'C:\\Users\\me\\proj\\a.txt');         // path of a file:///C:/… URL
  assert.equal(ok('file:///C:/Users/me/My%20Proj/a.txt'), 'C:\\Users\\me\\My Proj\\a.txt');
  assert.equal(ok('out/a.png', 'C:\\Users\\me\\proj'), 'C:\\Users\\me\\proj\\out\\a.png');
  assert.equal(ok('out\\a.png', '/C:/Users/me/proj'), 'C:\\Users\\me\\proj\\out\\a.png');
  assert.equal(ok('~\\proj'), 'C:\\Users\\me\\proj');
  assert.equal(ok('~/proj'), 'C:\\Users\\me\\proj');
  assert.equal(ok(''), home);
  assert.equal(ok('rel/x'), 'C:\\Users\\me\\rel\\x');                                 // no cwd: relative to home
  assert.equal(ok('..\\..\\Windows'), null);
  assert.equal(ok('C:\\Users\\other\\x'), null);
  assert.equal(ok('D:\\Users\\me\\x'), null);                                         // other drive
  assert.equal(ok('C:\\Users\\me2\\x'), null);                                        // sibling sharing a prefix
  assert.equal(ok('\\\\server\\share\\x'), null);
  assert.equal(ok('..foo\\x'), 'C:\\Users\\me\\..foo\\x');                            // a folder named "..foo" is inside home
  assert.ok(isSamePath('C:\\Users\\me', 'c:\\users\\ME\\', 'win32'));
});

test('posix safePath keeps its behaviour', () => {
  const ok = (p, cwd) => safePath(p, { home: '/home/u', cwd, platform: 'linux' });
  assert.equal(ok('/home/u/a'), '/home/u/a');
  assert.equal(ok('~/a'), '/home/u/a');
  assert.equal(ok('a/b', '/home/u/proj'), '/home/u/proj/a/b');
  assert.equal(ok('file:///home/u/a%20b'), '/home/u/a b');
  assert.equal(ok('/home/u/../v'), null);
  assert.equal(ok('/etc/passwd'), null);
  assert.equal(ok('/home/u2/x'), null);
  assert.equal(ok(''), '/home/u');
  assert.equal(expandHome('~x', '/h', 'linux'), '~x');
});

test('fileUri / pathFromFileUri round trip', () => {
  assert.equal(fileUri('C:\\Users\\me\\My Proj\\a#b.txt', 'win32'), 'file:///C:/Users/me/My%20Proj/a%23b.txt');
  assert.equal(pathFromFileUri('file:///C:/Users/me/My%20Proj/a%23b.txt', 'win32'), 'C:\\Users\\me\\My Proj\\a#b.txt');
  assert.equal(fileUri('/home/u/.leash/uploads/s/a.png', 'linux'), 'file:///home/u/.leash/uploads/s/a.png');
  assert.equal(pathFromFileUri('file:///home/u/a.png', 'linux'), '/home/u/a.png');
  assert.equal(pathFromFileUri('/plain/path', 'linux'), '/plain/path');
});

test('uploadName: Windows reserved device names and trailing dots are avoided; Unix names unchanged', () => {
  assert.equal(uploadName('C:\\fakepath\\My Photo (1).png', 'win32'), 'My_Photo_1_.png');
  assert.equal(uploadName('con.txt', 'win32'), '_con.txt');
  assert.equal(uploadName('NUL', 'win32'), '_NUL');
  assert.equal(uploadName('notes. ', 'win32'), 'notes');
  assert.equal(uploadName('con.txt', 'linux'), 'con.txt');
  assert.equal(uploadName('../../etc/passwd', 'linux'), 'passwd');
  assert.equal(uploadName('', 'linux'), 'file');
});
