"""Basic launch and navigation smoke test; it does not simulate real audio or an API key."""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = 'com.voiceagent.oneplus13'

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
    raise AssertionError('Missing UI element: '+label+'\n'+ET.tostring(tree(), encoding='unicode'))

adb('install', '-r', sys.argv[1])
adb('shell', 'am', 'start', '-W', '-n', PACKAGE+'/.MainActivity')
tap(wait_for('Настройки', 60))
for _ in range(6):
    node = find('Ключи, память и диагностика')
    if node is not None: break
    adb('shell', 'input', 'swipe', '500', '1600', '500', '450', '400')
    time.sleep(1)
else:
    raise AssertionError('Settings entry not found')
tap(node)
wait_for('Ключи и диагностика')
for _ in range(8):
    if find('DeepSeek API key') is not None: break
    adb('shell', 'input', 'swipe', '500', '1600', '500', '450', '400')
    time.sleep(1)
else:
    raise AssertionError('API key field is not accessible')
print('Main screen and API settings opened without registration.')
