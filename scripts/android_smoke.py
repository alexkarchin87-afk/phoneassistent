"""Basic launch and navigation smoke test; it does not simulate real audio or an API key."""
import re
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = os.environ.get('VOICEAGENT_PACKAGE', 'com.voiceagent.oneplus13')
ACTIVITY = PACKAGE + '/com.voiceagent.oneplus13.MainActivity'

def adb(*args):
    return subprocess.check_output(['adb', *args], text=True, stderr=subprocess.STDOUT)

def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/window.xml')
    xml = adb('shell', 'cat', '/sdcard/window.xml')
    return ET.fromstring(xml[xml.index('<?xml'):])

def find(label):
    return next((e for e in tree().iter('node') if label in (e.get('text', '') + ' ' + e.get('content-desc', ''))), None)

def tap(node):
    n = list(map(int, re.findall(r'\d+', node.attrib['bounds'])))
    adb('shell', 'input', 'tap', str((n[0]+n[2])//2), str((n[1]+n[3])//2))
    time.sleep(2)

def wait_for(label, timeout=30):
    end = time.time()+timeout
    while time.time()<end:
        node = find(label)
        if node is not None: return node
        time.sleep(1)
    raise AssertionError('Missing UI element: '+label+'\n'+ET.tostring(tree(), encoding='unicode')+'\nCRASH LOG:\n'+adb('logcat', '-b', 'crash', '-d', '-v', 'threadtime'))

adb('install', '-r', sys.argv[1])
adb('shell', 'pm', 'grant', PACKAGE, 'android.permission.RECORD_AUDIO')
adb('shell', 'pm', 'grant', PACKAGE, 'android.permission.POST_NOTIFICATIONS')
adb('logcat', '-b', 'crash', '-c')
adb('shell', 'am', 'start', '-W', '-n', ACTIVITY)

# Start the real foreground service, Vosk model and microphone on the emulator.
tap(wait_for('Запустить агента', 60))
wait_for('Остановить агента', 60)
end = time.time() + 120
seen_marker = False
while time.time() < end:
    if not adb('shell', 'pidof', PACKAGE).strip():
        raise AssertionError('App died during startup: '+adb('logcat', '-b', 'crash', '-d', '-v', 'brief'))
    try:
        marker = adb('shell', 'run-as', PACKAGE, 'cat', 'shared_prefs/voice_agent_startup.xml')
        seen_marker = True
    except subprocess.CalledProcessError:
        marker = ''
    if seen_marker and re.search(r'<boolean name="listener_started" value="true"', marker):
        break
    time.sleep(3)
else:
    raise AssertionError('Voice startup did not finish: '+marker+'\n'+adb('logcat', '-b', 'crash', '-d', '-v', 'brief'))
time.sleep(5)
crashes = adb('logcat', '-b', 'crash', '-d', '-v', 'brief')
if PACKAGE in crashes:
    raise AssertionError('App crashed after enabling agent: '+crashes)

# Simulate a restart without clearing data, then confirm data and UI remain.
adb('shell', 'run-as', PACKAGE, 'touch', 'files/smoke_data_kept')
adb('shell', 'am', 'force-stop', PACKAGE)
adb('shell', 'am', 'start', '-W', '-n', ACTIVITY)
wait_for('Запустить агента', 60)
adb('shell', 'run-as', PACKAGE, 'ls', 'files/smoke_data_kept')
tap(wait_for('Настройки', 60))
wait_for('Голосовой агент')
for _ in range(12):
    node = find('Ключи, память и диагностика')
    if node is not None: break
    adb('shell', 'input', 'swipe', '500', '1600', '500', '450', '400')
    time.sleep(1)
else:
    raise AssertionError('Settings entry not found: '+ET.tostring(tree(), encoding='unicode'))
tap(node)
wait_for('Ключи и диагностика')
for _ in range(8):
    if find('DeepSeek API key') is not None: break
    adb('shell', 'input', 'swipe', '500', '1600', '500', '450', '400')
    time.sleep(1)
else:
    raise AssertionError('API key field is not accessible: '+ET.tostring(tree(), encoding='unicode'))
print('Voice service started and app reopened without clearing data; API settings are accessible.')
