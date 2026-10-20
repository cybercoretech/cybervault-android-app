"""End-to-end smoke test on an emulator (emulator-5554 only; never a phone).

Creates a vault, adds a Keysmith-generated entry, reveals it, locks, checks a
wrong password is refused, unlocks, then pulls the vault file and opens it
with the desktop CyberVault code. Uses a throwaway test password.

    python3 tools/emulator_smoke.py                 # debug build
    CV_RELEASE=1 python3 tools/emulator_smoke.py    # signed, minified release
"""
import os, pathlib, re, subprocess, sys, tempfile, time
import xml.etree.ElementTree as ET

ADB = os.environ.get('ANDROID_HOME', str(pathlib.Path.home() / 'android')) + '/platform-tools/adb'
PROJECT = pathlib.Path(__file__).resolve().parents[1]
RELEASE = os.environ.get('CV_RELEASE') == '1'
APP = 'dev.cybercore.cybervault' if RELEASE else 'dev.cybercore.cybervault.debug'
TEST_PASSWORD = 'smoke-test-master-pw'   # throwaway, emulator only
SHOTS = PROJECT / 'docs' / 'screenshots'


def adb(*args):
    return subprocess.check_output([ADB, '-s', 'emulator-5554', *args])


def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/cv-ui.xml')
    return list(ET.fromstring(adb('shell', 'cat', '/sdcard/cv-ui.xml')).iter('node'))


def find(label, attr='text', timeout=20):
    deadline = time.time() + timeout
    while time.time() < deadline:
        for node in nodes():
            if node.get(attr, '').strip() == label:
                return node
        time.sleep(1)
    raise AssertionError(f'missing UI ({attr}): {label}')


def tap(label, attr='text', timeout=20):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', find(label, attr, timeout).get('bounds')))
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(.7)


def type_into(field_label, text):
    tap(field_label)
    adb('shell', 'input', 'text', text)
    time.sleep(.4)


def has_text(fragment):
    return any(fragment in (n.get('text') or '') for n in nodes())


def shot(name):
    if RELEASE:  # FLAG_SECURE: release builds can't be captured, by design
        return
    SHOTS.mkdir(parents=True, exist_ok=True)
    (SHOTS / f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p'))


def main():
    apk = PROJECT / ('app/build/outputs/apk/release/app-release.apk' if RELEASE
                     else 'app/build/outputs/apk/debug/app-debug.apk')
    adb('install', '-r', str(apk))
    adb('shell', 'pm', 'clear', APP)
    adb('shell', 'am', 'start', '-W', '-n', f'{APP}/dev.cybercore.cybervault.MainActivity')
    find('Create your vault')
    shot('01-setup')

    type_into('Master password', TEST_PASSWORD)
    type_into('Repeat it', TEST_PASSWORD)
    adb('shell', 'input', 'keyevent', '4')  # hide keyboard
    tap('CREATE VAULT')
    find('Empty vault', timeout=60)
    shot('02-empty')
    print('PASS: vault created')

    tap('Add entry', 'content-desc')
    type_into('Label, e.g. github', 'github')
    adb('shell', 'input', 'keyevent', '4')
    tap('USE THIS')
    tap('SAVE')
    find('github', timeout=60)
    print('PASS: entry saved with a Keysmith passphrase')

    tap('github')
    tap('Reveal', 'content-desc')
    secret = None
    for n in nodes():
        t = n.get('text') or ''
        if t.count('-') == 5 and ' ' not in t:
            secret = t
    assert secret, 'revealed passphrase not shown'
    shot('03-revealed')
    print(f'PASS: revealed a 6-word passphrase ({len(secret)} chars)')

    tap('Keysmith', 'text')
    find('KEYSMITH')
    shot('04-keysmith')
    tap('Settings', 'text')
    find('Change master password')
    shot('05-settings')

    tap('Lock now', 'content-desc')
    find('Locked')
    shot('06-locked')
    type_into('Master password', 'definitely-wrong-pw')
    adb('shell', 'input', 'keyevent', '4')
    tap('UNLOCK')
    deadline = time.time() + 60
    while not has_text('Wrong password'):
        assert time.time() < deadline, 'wrong password was not reported'
        time.sleep(1)
    print('PASS: wrong password refused')

    type_into('Master password', TEST_PASSWORD)
    adb('shell', 'input', 'keyevent', '4')
    tap('UNLOCK')
    find('github', timeout=60)
    print('PASS: unlocked with the right password')

    if RELEASE:  # not debuggable, so run-as can't read the vault; the debug run covers it
        print('PASS: release build (R8-minified) works end to end')
        return
    with tempfile.TemporaryDirectory() as tmp:
        local = pathlib.Path(tmp) / 'phone.cvlt'
        local.write_bytes(adb('exec-out', 'run-as', APP, 'cat', 'files/vault/vault.cvlt'))
        out = subprocess.run(
            ['cargo', 'run', '--quiet', '--example', 'inspect', '--', str(local)],
            cwd=PROJECT / 'rust', env={**os.environ, 'CYBERVAULT_PASSWORD': TEST_PASSWORD},
            capture_output=True, text=True, check=True,
        ).stdout
        assert out.startswith('github\t'), out
        assert f'secret_len={len(secret)}' in out, out
    print('PASS: the desktop CyberVault code opens the phone vault (CVL1 compatible)')


if __name__ == '__main__':
    sys.exit(main())
