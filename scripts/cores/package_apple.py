#!/usr/bin/env python3
"""Install signed cartridge cores and provenance into a normal Xcode product."""
import importlib.util,json,os,pathlib,shutil,subprocess
spec=importlib.util.spec_from_file_location('core_build',pathlib.Path(__file__).with_name('build.py'));build=importlib.util.module_from_spec(spec);spec.loader.exec_module(build)
def main():
    root=pathlib.Path(os.environ['TARGET_BUILD_DIR'])/os.environ['UNLOCALIZED_RESOURCES_FOLDER_PATH']
    sdk=os.environ['PLATFORM_NAME'];arch=os.environ.get('CURRENT_ARCH','arm64')
    if arch=='undefined_arch':arch=os.environ.get('NATIVE_ARCH_ACTUAL','arm64')
    if sdk=='iphoneos':arch='arm64'
    manifest=root/'CoreLicenses.json';licenses=json.loads(manifest.read_text()) if manifest.exists() else []
    cache=os.environ.get('ROMMIO_CORE_SOURCE_CACHE');cache=pathlib.Path(cache) if cache else None
    for runtime in json.loads(build.PINS.read_text()):
        binary=build.build(runtime,sdk,arch,cache=cache);dest=root/'Cores'/f'{runtime}.dylib';dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(binary,dest)
        subprocess.run(['codesign','--force','--sign',os.environ.get('EXPANDED_CODE_SIGN_IDENTITY') or '-','--timestamp=none',str(dest)],check=True)
        if binary.with_name('system-manifest.json').exists():
            assets=root/'Cores/system'/runtime;assets.mkdir(parents=True,exist_ok=True)
            shutil.copytree(binary.with_name('system'),assets/'system',dirs_exist_ok=True)
            shutil.copy2(binary.with_name('system-manifest.json'),assets/'system-manifest.json')
        pin=json.loads(binary.with_name('provenance.json').read_text());pin['sha256']=build.sha(dest.read_bytes());(root/f'{runtime}Provenance.json').write_text(json.dumps(pin,indent=2)+'\n')
        license_dest=root/'Cores/licenses'/f'{runtime}.LICENSE.txt';license_dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(binary.with_name('LICENSE.txt'),license_dest)
        licenses=[x for x in licenses if x['id']!=runtime]+[{'id':runtime,'binary_path':f'Cores/{runtime}.dylib','license_path':f'Cores/licenses/{runtime}.LICENSE.txt'}]
    manifest.write_text(json.dumps(licenses,indent=2)+'\n')
if __name__=='__main__': main()
