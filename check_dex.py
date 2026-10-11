"""Check if dex contains our methods using hex search."""
import zipfile, re, os

apk = r'C:\work\yyba_cloud\yyba_cloud_android\app\build\outputs\apk\debug\app-debug.apk'
print(f'APK size: {os.path.getsize(apk)}')
print(f'APK mtime: {os.path.getmtime(apk)}')

with zipfile.ZipFile(apk) as z:
    dex_names = [n for n in z.namelist() if n.endswith('.dex')]
    print(f'Dex files: {dex_names}')
    for dex_name in dex_names:
        with z.open(dex_name) as f:
            data = f.read()
        print(f'\n=== {dex_name} (size: {len(data)}) ===')
        # Hex search
        for method in ['parentPathOf', 'refreshBackCallback', 'showingCloud', 'backCallback', 'onCreateView', 'loadFiles']:
            hex_bytes = method.encode('utf-8').hex()
            hex_str = data.hex()
            count = hex_str.count(hex_bytes)
            pos = data.find(method.encode('utf-8'))
            print(f'  {method}: count={count}, first_pos={pos}')
