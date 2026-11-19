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

# Exercise real binder -> orchestrator -> offline command -> chat, with no API key.
for _ in range(4):
    prompt = find('Который час?')
    if prompt is not None: break
    adb('shell', 'input', 'swipe', '500', '1400', '500', '500', '350')
    time.sleep(1)
else:
    raise AssertionError('Offline quick command not found')
tap(prompt)
wait_for('Сейчас', 45)
# The response mode is selectable next to the composer and persists to preferences.
tap(wait_for('Быстро ▾', 30))
tap(wait_for('Вдумчиво — дольше', 30))
mode_xml = adb('shell', 'run-as', PACKAGE, 'cat', 'shared_prefs/response_mode.xml')
if not re.search(r'<boolean name="thoughtful" value="true"', mode_xml):
    raise AssertionError('Thoughtful mode not persisted: '+mode_xml)
tap(wait_for('Вдумчиво ▾', 30))
tap(wait_for('Быстро — короче', 30))
# An ordinary message without a key must return an actionable error.
edit = next((e for e in tree().iter('node') if e.get('class') == 'android.widget.EditText'), None)
if edit is None: raise AssertionError('Chat input not found')
tap(edit)
# On devices exposing IME insets in dumpsys, ensure the composer is close above
# the keyboard instead of being pushed up by the keyboard height twice.
edit = next((e for e in tree().iter('node') if e.get('class') == 'android.widget.EditText'), None)
if edit is None: raise AssertionError('Chat input disappeared after opening keyboard')
edit_rect = list(map(int, re.findall(r'\d+', edit.attrib['bounds'])))
window_info = adb('shell', 'dumpsys', 'window', 'displays')
for line in window_info.splitlines():
    if re.search(r'(?:mType|type)=ime\b', line, re.I) and re.search(r'(?:mVisible|visible)=true', line, re.I):
        frame = re.search(r'(?:mFrame|frame)=\[(\d+),(\d+)\]\[(\d+),(\d+)\]', line)
        if frame:
            left, top, right, bottom = map(int, frame.groups())
            if bottom > top and top > 0:
                gap = top - edit_rect[3]
                print('Keyboard/composer gap:', gap)
                if gap < -25 or gap > max(200, (bottom-top)//2):
                    raise AssertionError('Composer is too far from or behind keyboard: '+str(gap))
                break
else:
    print('IME bounds unavailable in emulator window dump; keyboard placement needs device check.')
adb('shell', 'input', 'text', 'hello')
tap(wait_for('Отправить', 30))
wait_for('Добавь ключ DeepSeek API', 60)
adb('shell', 'input', 'keyevent', '4')

crashes = adb('logcat', '-b', 'crash', '-d', '-v', 'brief')
if PACKAGE in crashes:
    raise AssertionError('App crashed after enabling agent: '+crashes)

# Simulate a restart without clearing data, then confirm data and UI remain.
adb('shell', 'run-as', PACKAGE, 'touch', 'files/smoke_data_kept')
adb('shell', 'am', 'force-stop', PACKAGE)
adb('shell', 'am', 'start', '-W', '-n', ACTIVITY)
wait_for('Запустить агента', 60)
adb('shell', 'run-as', PACKAGE, 'ls', 'files/smoke_data_kept')
# Grant accessibility on the disposable emulator only after the force-stopped
# app is running again, so Android does not prune a stopped service.
component = PACKAGE + '/com.voiceagent.oneplus13.system.AccessibilityAgent'
adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', component)
adb('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1')
value = adb('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services').strip()
if component not in value: raise AssertionError('Accessibility setting not retained: '+value)
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
wait_for('Спец. возможности')
try:
    wait_for('Включено')
except AssertionError as error:
    raise AssertionError(str(error)+'\nAccessibility setting: '+adb('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')) from error
# Root probe is explicit; the emulator should return a result instead of hanging.
for _ in range(8):
    root_check = find('Проверить root-доступ')
    if root_check is not None: break
    adb('shell', 'input', 'swipe', '500', '1500', '500', '600', '350')
    time.sleep(1)
else: raise AssertionError('Root check not found')
tap(root_check)
adb('shell', 'input', 'swipe', '500', '1450', '500', '900', '300')
end = time.time() + 55
labels = ('Root-доступ подтверждён', 'su не найден', 'Команда не выполнена',
          'не получила права root', 'Система запретила запуск su',
          'Root-запрос не получил ответа', 'Проверка root прервана')
while time.time() < end:
    snapshot = ET.tostring(tree(), encoding='unicode')
    if any(label in snapshot for label in labels): break
    time.sleep(1)
else: raise AssertionError('Root check did not finish or its result is off-screen: '+snapshot)
for _ in range(8):
    if find('Скачать Qwen через Wi-Fi') is not None: break
    adb('shell', 'input', 'swipe', '500', '1600', '500', '450', '400')
    time.sleep(1)
else:
    raise AssertionError('Local model download option is not accessible: '+ET.tostring(tree(), encoding='unicode'))
for _ in range(8):
    if find('DeepSeek API key') is not None: break
    adb('shell', 'input', 'swipe', '500', '1600', '500', '450', '400')
    time.sleep(1)
else:
    raise AssertionError('API key field is not accessible: '+ET.tostring(tree(), encoding='unicode'))
print('Voice service, offline reply, missing-key error, accessibility diagnostics and data-preserving restart passed.')
